$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ProjectRoot
$ProjectFolder = Split-Path -Leaf $ProjectRoot
$Product = ""
$PluginsDir = ""

function Test-CommandExists {
    param([string]$Name)
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-JavaHome {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $javaPath = Join-Path $Path "bin\java.exe"
    return Test-Path -LiteralPath $javaPath
}

function Find-JavaHome {
    if (Test-JavaHome $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }

    $javaCommand = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $javaHome = Split-Path -Parent $javaBin
        if (Test-JavaHome $javaHome) {
            return $javaHome
        }
    }

    $searchRoots = @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "${env:ProgramFiles(x86)}\Eclipse Adoptium",
        "${env:ProgramFiles(x86)}\Java"
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_) }

    $jdk = Get-ChildItem -LiteralPath $searchRoots -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-JavaHome $_.FullName } |
        Sort-Object Name -Descending |
        Select-Object -First 1

    if ($jdk) {
        return $jdk.FullName
    }

    return ""
}

function Find-WebStormHome {
    $searchRoots = @(
        (Join-Path $env:ProgramFiles "JetBrains"),
        (Join-Path $env:LOCALAPPDATA "Programs"),
        (Join-Path $env:LOCALAPPDATA "JetBrains\Toolbox\apps")
    ) | Where-Object { Test-Path -LiteralPath $_ }

    $installations = foreach ($searchRoot in $searchRoots) {
        Get-ChildItem -LiteralPath $searchRoot -Filter "product-info.json" -File -Recurse -ErrorAction SilentlyContinue |
            ForEach-Object {
                $productInfo = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
                if ($productInfo.productCode -eq "WS") {
                    [PSCustomObject]@{
                        Path = $_.Directory.FullName
                        BuildNumber = [version]$productInfo.buildNumber
                    }
                }
            }
    }

    $webStorm = $installations |
        Sort-Object BuildNumber -Descending |
        Select-Object -First 1

    if ($webStorm) {
        return $webStorm.Path
    }

    return ""
}

function Sync-GradleVersion {
    param(
        [string]$Version
    )

    $gradlePath = Join-Path $ProjectRoot "build.gradle.kts"
    $raw = Get-Content -LiteralPath $gradlePath -Raw
    $pattern = '(?m)^\s*version\s*=\s*"[^"]*"'

    if ($raw -notmatch $pattern) {
        throw "Could not find a Gradle version assignment in $gradlePath."
    }

    $updated = [regex]::Replace($raw, $pattern, "version = `"$Version`"", 1)
    if ($updated -ne $raw) {
        Set-Content -LiteralPath $gradlePath -Value $updated -NoNewline
        Write-Host "Version: $($gradlePath.Substring($ProjectRoot.Length + 1)) -> $Version"
    }
}

function Get-GradleVersion {
    $gradlePath = Join-Path $ProjectRoot "build.gradle.kts"
    $raw = Get-Content -LiteralPath $gradlePath -Raw
    $match = [regex]::Match($raw, '(?m)^\s*version\s*=\s*"([^"]*)"')

    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) {
        throw "Could not find a Gradle version assignment in $gradlePath."
    }

    return $match.Groups[1].Value
}

function Get-NextCalendarVersion {
    param(
        [string]$CurrentVersion
    )

    $now = Get-Date
    $calendarMajor = $now.Year % 100
    $calendarMinor = $now.Month
    $match = [regex]::Match([string]$CurrentVersion, '^(\d+)\.(\d+)\.(\d+)$')

    if ($match.Success) {
        $major = [int]$match.Groups[1].Value
        $minor = [int]$match.Groups[2].Value
        $patch = [int]$match.Groups[3].Value

        if ($major -eq $calendarMajor -and $minor -eq $calendarMinor) {
            return "$calendarMajor.$calendarMinor.$($patch + 1)"
        }
    }

    return "$calendarMajor.$calendarMinor.0"
}

function Update-BuildVersion {
    $currentVersion = Get-GradleVersion
    $nextVersion = Get-NextCalendarVersion -CurrentVersion $currentVersion
    Sync-GradleVersion -Version $nextVersion
    return $nextVersion
}

function Get-LatestDistributionZip {
    $localBuildRoot = Join-Path $env:USERPROFILE "ceres-assistant-build - Push & Pull\jetbrains"
    $distributionsDir = Join-Path $localBuildRoot "distributions"
    if (-not (Test-Path -LiteralPath $distributionsDir)) {
        throw "No JetBrains distribution folder was produced: $distributionsDir"
    }

    $zip = Get-ChildItem -LiteralPath $distributionsDir -Filter "*.zip" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $zip) {
        throw "No JetBrains distribution zip was produced in $distributionsDir."
    }

    return $zip.FullName
}

function Copy-LatestDistribution {
    param(
        [string]$OutputPath
    )

    $sourceZip = Get-LatestDistributionZip
    $tempPath = "$OutputPath.tmp-$PID-$(Get-Random)"

    Copy-Item -LiteralPath $sourceZip -Destination $tempPath -Force
    if (Test-Path -LiteralPath $OutputPath) {
        Remove-Item -LiteralPath $OutputPath -Force
    }
    Move-Item -LiteralPath $tempPath -Destination $OutputPath -Force
}

function Get-PluginFolderName {
    $name = $ProjectFolder.ToLowerInvariant()
    $name = $name -replace '^ceres-assistant\.com$', 'ceres'
    $name = $name -replace '[^a-z0-9]+', '-'
    $name = $name.Trim('-')

    if ([string]::IsNullOrWhiteSpace($name)) {
        return "jetbrains-plugin"
    }

    return $name
}

function Get-DefaultPluginsDirs {
    if ([string]::IsNullOrWhiteSpace($env:APPDATA)) {
        throw "APPDATA is not set. Pass -PluginsDir explicitly."
    }

    $jetBrainsDir = Join-Path $env:APPDATA "JetBrains"
    if (-not (Test-Path -LiteralPath $jetBrainsDir)) {
        throw "JetBrains config folder was not found. Pass -PluginsDir explicitly."
    }

    if (-not [string]::IsNullOrWhiteSpace($Product)) {
        return @(Join-Path $jetBrainsDir "$Product\plugins")
    }

    $productDirs = @(Get-ChildItem -LiteralPath $jetBrainsDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(WebStorm|PhpStorm|IntelliJIdea|PyCharm|Rider|CLion|DataGrip|RubyMine|GoLand)' } |
        Sort-Object Name)

    if (-not $productDirs.Count) {
        throw "No JetBrains product folder was found. Pass -PluginsDir explicitly."
    }

    return @($productDirs | ForEach-Object { Join-Path $_.FullName "plugins" })
}

function Assert-ValidPluginsDir {
    param(
        [string]$TargetPluginsDir
    )

    if ([string]::IsNullOrWhiteSpace($TargetPluginsDir)) {
        throw "JetBrains plugins directory is empty."
    }

    $fullPath = [System.IO.Path]::GetFullPath($TargetPluginsDir)
    $jetBrainsRoot = [System.IO.Path]::GetFullPath((Join-Path $env:APPDATA "JetBrains"))

    if (-not [System.IO.Path]::IsPathRooted($fullPath)) {
        throw "JetBrains plugins directory must be an absolute path: $TargetPluginsDir"
    }

    if ($fullPath -notlike "$jetBrainsRoot\*") {
        throw "Refusing suspicious JetBrains plugins directory outside APPDATA\JetBrains: $fullPath"
    }

    if ((Split-Path -Leaf $fullPath) -ne "plugins") {
        throw "JetBrains plugins directory must end with '\plugins': $fullPath"
    }

    return $fullPath
}

function Install-PluginZipToDir {
    param(
        [string]$ZipPath,
        [string]$TargetPluginsDir
    )

    $TargetPluginsDir = Assert-ValidPluginsDir -TargetPluginsDir $TargetPluginsDir
    New-Item -ItemType Directory -Force -Path $TargetPluginsDir | Out-Null

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "jetbrains-plugin-install-$PID-$(Get-Random)"
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    try {
        Expand-Archive -LiteralPath $ZipPath -DestinationPath $tempDir -Force
        $entries = @(Get-ChildItem -LiteralPath $tempDir)

        if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) {
            $sourceDir = $entries[0].FullName
            $installName = $entries[0].Name
        } else {
            $sourceDir = $tempDir
            $installName = Get-PluginFolderName
        }

        $targetDir = Join-Path $TargetPluginsDir $installName
        if (Test-Path -LiteralPath $targetDir) {
            Remove-Item -LiteralPath $targetDir -Recurse -Force
        }

        Move-Item -LiteralPath $sourceDir -Destination $targetDir -Force
        Write-Host "Install: $targetDir"
        Write-Host "Restart your JetBrains IDE to load the updated plugin."
    } finally {
        if (Test-Path -LiteralPath $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force
        }
    }
}

function Install-PluginZip {
    param(
        [string]$ZipPath
    )

    $targetPluginsDirs = @(
        if ([string]::IsNullOrWhiteSpace($PluginsDir)) {
            Get-DefaultPluginsDirs
        } else {
            $PluginsDir
        }
    )

    if ($targetPluginsDirs.Count -eq 1) {
        Install-PluginZipToDir -ZipPath $ZipPath -TargetPluginsDir $targetPluginsDirs[0]
        return
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "jetbrains-plugin-install-$PID-$(Get-Random)"
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    try {
        Expand-Archive -LiteralPath $ZipPath -DestinationPath $tempDir -Force
        $entries = @(Get-ChildItem -LiteralPath $tempDir)

        if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) {
            $sourceDir = $entries[0].FullName
            $installName = $entries[0].Name
        } else {
            $sourceDir = $tempDir
            $installName = Get-PluginFolderName
        }

        foreach ($targetPluginsDir in $targetPluginsDirs) {
            $targetPluginsDir = Assert-ValidPluginsDir -TargetPluginsDir $targetPluginsDir
            New-Item -ItemType Directory -Force -Path $targetPluginsDir | Out-Null

            $targetDir = Join-Path $targetPluginsDir $installName
            if (Test-Path -LiteralPath $targetDir) {
                Remove-Item -LiteralPath $targetDir -Recurse -Force
            }

            Copy-Item -LiteralPath $sourceDir -Destination $targetDir -Recurse -Force
            Write-Host "Install: $targetDir"
        }

        Write-Host "Restart your JetBrains IDE to load the updated plugin."
    } finally {
        if (Test-Path -LiteralPath $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force
        }
    }
}

function Build-Project {
    $gradlePath = Join-Path $ProjectRoot "build.gradle.kts"
    $wrapperJarPath = Join-Path $ProjectRoot "gradle\wrapper\gradle-wrapper.jar"
    $wrapperJarArg = "gradle\wrapper\gradle-wrapper.jar"
    $javaPath = Join-Path $env:JAVA_HOME "bin\java.exe"
    $outputPath = Join-Path $RepoRoot "$ProjectFolder-jetbrains-latest.zip"

    Write-Host ""
    Write-Host "== Push & Pull =="

    if (-not (Test-Path -LiteralPath $gradlePath)) {
        throw "Missing JetBrains Gradle config: $gradlePath"
    }

    if (-not (Test-Path -LiteralPath $wrapperJarPath)) {
        throw "Missing Gradle wrapper: $wrapperJarPath"
    }

    if (-not (Test-Path -LiteralPath $javaPath)) {
        throw "Missing Java executable: $javaPath"
    }

    Update-BuildVersion | Out-Null

    if (Test-Path -LiteralPath $outputPath) {
        Remove-Item -LiteralPath $outputPath -Force
    }

    # Keep builds fully clean. Do not keep previous distribution zips here:
    # the installer picks the latest zip from this folder, so stale files can install an old plugin.
    $distributionsDir = Join-Path $env:USERPROFILE "ceres-assistant-build - Push & Pull\jetbrains\distributions"
    if (Test-Path -LiteralPath $distributionsDir) {
        Remove-Item -LiteralPath $distributionsDir -Recurse -Force
    }

    Push-Location $ProjectRoot
    try {
        # Build in the local output directory configured in build.gradle.kts.
        & $javaPath "-Xmx64m" "-Xms64m" "-Dorg.gradle.appname=gradlew" -jar $wrapperJarArg clean buildPlugin --no-build-cache --rerun-tasks --console=plain --info --stacktrace
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle buildPlugin failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    Copy-LatestDistribution -OutputPath $outputPath
    Write-Host "Pack: $outputPath"

    Install-PluginZip -ZipPath $outputPath

    # Temporary: stop the daemon so editor task runners exit cleanly after the build.
    # Later, for faster dev builds, make this optional and keep the daemon warm.
    Push-Location $ProjectRoot
    try {
        & $javaPath "-Xmx64m" "-Xms64m" "-Dorg.gradle.appname=gradlew" -jar $wrapperJarArg --stop --console=plain
    } finally {
        Pop-Location
    }
}

$javaHome = Find-JavaHome
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    Write-Error "Java was not found. Install a JDK and reopen PowerShell/WebStorm. Check with: java -version"
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Write-Host "Java: $javaHome"

$webStormHome = Find-WebStormHome
if ([string]::IsNullOrWhiteSpace($webStormHome)) {
    Write-Error "WebStorm was not found. Install WebStorm and run the build again."
    exit 1
}

$env:WEBSTORM_HOME = $webStormHome
Write-Host "WebStorm: $webStormHome"

Build-Project

Write-Host ""
Write-Host "JetBrains build/install finished."
exit 0
