package com.deploymenthost.jetbrains;

import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RcloneBinaryManager {
    private static final String DOWNLOAD_HOST = "https://downloads.rclone.org";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final long UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final Object PREPARATION_LOCK = new Object();
    private static CompletableFuture<Path> activePreparation;

    private static final Map<String, Map<String, String>> PLATFORM_ARCHIVE_NAMES = Map.of(
        "windows", Map.of(
            "amd64", "windows-amd64",
            "386", "windows-386",
            "arm64", "windows-arm64"
        ),
        "linux", Map.ofEntries(
            Map.entry("amd64", "linux-amd64"),
            Map.entry("386", "linux-386"),
            Map.entry("arm64", "linux-arm64"),
            Map.entry("arm", "linux-arm"),
            Map.entry("arm-v6", "linux-arm-v6"),
            Map.entry("arm-v7", "linux-arm-v7"),
            Map.entry("loong64", "linux-loong64"),
            Map.entry("ppc64", "linux-ppc64"),
            Map.entry("ppc64le", "linux-ppc64le"),
            Map.entry("riscv64", "linux-riscv64"),
            Map.entry("s390x", "linux-s390x"),
            Map.entry("mips", "linux-mips"),
            Map.entry("mipsle", "linux-mipsle"),
            Map.entry("mips64", "linux-mips64"),
            Map.entry("mips64le", "linux-mips64le")
        ),
        "osx", Map.of(
            "amd64", "osx-amd64",
            "arm64", "osx-arm64"
        ),
        "freebsd", Map.ofEntries(
            Map.entry("amd64", "freebsd-amd64"),
            Map.entry("386", "freebsd-386"),
            Map.entry("arm", "freebsd-arm"),
            Map.entry("arm-v6", "freebsd-arm-v6"),
            Map.entry("arm-v7", "freebsd-arm-v7")
        ),
        "openbsd", Map.of(
            "amd64", "openbsd-amd64",
            "386", "openbsd-386"
        ),
        "netbsd", Map.ofEntries(
            Map.entry("amd64", "netbsd-amd64"),
            Map.entry("386", "netbsd-386"),
            Map.entry("arm", "netbsd-arm"),
            Map.entry("arm-v6", "netbsd-arm-v6"),
            Map.entry("arm-v7", "netbsd-arm-v7")
        ),
        "solaris", Map.of("amd64", "solaris-amd64"),
        "aix", Map.of("ppc64", "aix-ppc64")
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(HTTP_TIMEOUT)
        .build();

    Path ensureLatestRclone(Consumer<String> reportProgress) throws IOException, InterruptedException {
        CompletableFuture<Path> preparation;
        boolean preparesBinary = false;

        synchronized (PREPARATION_LOCK) {
            if (activePreparation == null) {
                activePreparation = new CompletableFuture<>();
                preparesBinary = true;
            }
            preparation = activePreparation;
        }

        if (preparesBinary) {
            try {
                Path executablePath = prepareLatestRclone(reportProgress);
                preparation.complete(executablePath);
            } catch (Exception error) {
                preparation.completeExceptionally(error);
            } finally {
                synchronized (PREPARATION_LOCK) {
                    if (activePreparation == preparation) {
                        activePreparation = null;
                    }
                }
            }
        } else {
            reportProgress.accept("Waiting for rclone...");
        }

        try {
            return preparation.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause == null ? "Could not prepare rclone." : cause.getMessage(), cause);
        }
    }

    private Path prepareLatestRclone(Consumer<String> reportProgress) throws IOException, InterruptedException {
        RclonePlatform platform = supportedPlatform();
        Path storageDirectory = Path.of(PathManager.getSystemPath(), "push-and-pull", "rclone");
        Path versionFile = storageDirectory.resolve("version.txt");
        Path checkedAtFile = storageDirectory.resolve("checked-at.txt");
        Path executablePath = storageDirectory.resolve(platform.binaryName());

        Files.createDirectories(storageDirectory);
        String installedVersion = readTextIfExists(versionFile);
        boolean hasCachedBinary = Files.isRegularFile(executablePath);

        if (hasCachedBinary && !shouldCheckForUpdates(checkedAtFile)) {
            reportProgress.accept("Using cached rclone...");
            return executablePath;
        }

        try {
            reportProgress.accept("Checking latest rclone version...");
            String latestVersion = downloadText(URI.create(DOWNLOAD_HOST + "/version.txt"));
            writeUpdateCheckTime(checkedAtFile);

            if (latestVersion.equals(installedVersion) && hasCachedBinary) {
                reportProgress.accept("Using cached rclone...");
                return executablePath;
            }

            String archiveFileName = "rclone-current-" + platform.archiveName() + ".zip";
            Path archivePath = storageDirectory.resolve(archiveFileName);
            reportProgress.accept("Downloading " + latestVersion + " for " + platform.label() + "...");
            downloadFile(URI.create(DOWNLOAD_HOST + "/" + archiveFileName), archivePath, latestVersion, platform.label(), reportProgress);

            reportProgress.accept("Extracting rclone...");
            extractRcloneBinary(archivePath, executablePath, platform.binaryName());
            Files.writeString(versionFile, latestVersion, StandardCharsets.UTF_8);
            return executablePath;
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (hasCachedBinary) {
                reportProgress.accept("Using cached rclone...");
                return executablePath;
            }
            throw error;
        }
    }

    private RclonePlatform supportedPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = normalizePlatform(osName);
        String architecture = normalizeArchitecture(osArch);
        String archiveName = PLATFORM_ARCHIVE_NAMES.getOrDefault(platform, Map.of()).get(architecture);

        if (archiveName == null) {
            throw new IllegalStateException(
                "No official rclone download mapping is configured for platform " + platform + " " + architecture + "."
            );
        }

        String binaryName = "windows".equals(platform) ? "rclone.exe" : "rclone";
        return new RclonePlatform(archiveName, binaryName, platformLabel(platform, architecture));
    }

    private String normalizePlatform(String osName) {
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "osx";
        }
        if (osName.contains("linux")) {
            return "linux";
        }
        if (osName.contains("freebsd")) {
            return "freebsd";
        }
        if (osName.contains("openbsd")) {
            return "openbsd";
        }
        if (osName.contains("netbsd")) {
            return "netbsd";
        }
        if (osName.contains("sunos") || osName.contains("solaris")) {
            return "solaris";
        }
        if (osName.contains("aix")) {
            return "aix";
        }
        return osName;
    }

    private String normalizeArchitecture(String osArch) {
        return switch (osArch) {
            case "x86_64", "amd64" -> "amd64";
            case "x86", "i386", "i486", "i586", "i686" -> "386";
            case "aarch64", "arm64" -> "arm64";
            case "armv6l" -> "arm-v6";
            case "armv7l" -> "arm-v7";
            case "arm" -> "arm";
            case "ppc64le" -> "ppc64le";
            case "ppc64" -> "ppc64";
            case "riscv64" -> "riscv64";
            case "s390x" -> "s390x";
            case "mipsel", "mipsle" -> "mipsle";
            case "mips64el", "mips64le" -> "mips64le";
            case "mips64" -> "mips64";
            case "mips" -> "mips";
            case "loongarch64", "loong64" -> "loong64";
            default -> osArch;
        };
    }

    private String platformLabel(String platform, String architecture) {
        String platformName = switch (platform) {
            case "windows" -> "Windows";
            case "osx" -> "macOS";
            case "linux" -> "Linux";
            case "freebsd" -> "FreeBSD";
            case "openbsd" -> "OpenBSD";
            case "netbsd" -> "NetBSD";
            case "solaris" -> "Solaris";
            case "aix" -> "AIX";
            default -> platform;
        };
        return platformName + " " + architecture;
    }

    private String downloadText(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("User-Agent", "push-and-pull-jetbrains-plugin")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Download failed with status " + response.statusCode() + ".");
        }
        return response.body().trim();
    }

    private void downloadFile(
        URI uri,
        Path targetPath,
        String version,
        String platformLabel,
        Consumer<String> reportProgress
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("User-Agent", "push-and-pull-jetbrains-plugin")
            .GET()
            .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Download failed with status " + response.statusCode() + ".");
        }

        long totalBytes = response.headers().firstValueAsLong("content-length").orElse(0L);
        long downloadedBytes = 0L;

        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloadedBytes += read;
                String progress = totalBytes > 0
                    ? Math.min(100, Math.round((downloadedBytes * 100.0) / totalBytes)) + "%"
                    : formatBytes(downloadedBytes);
                reportProgress.accept("Downloading " + version + " for " + platformLabel + "... " + progress);
            }
        }
    }

    private void extractRcloneBinary(Path archivePath, Path executablePath, String binaryName) throws IOException {
        Path newExecutablePath = executablePath.resolveSibling(executablePath.getFileName() + ".new");
        Files.deleteIfExists(newExecutablePath);

        try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(archivePath))) {
            ZipEntry entry;

            while ((entry = zipInput.getNextEntry()) != null) {
                if (entry.isDirectory() || !binaryName.equals(Path.of(entry.getName()).getFileName().toString())) {
                    continue;
                }

                Files.copy(zipInput, newExecutablePath, StandardCopyOption.REPLACE_EXISTING);
                setExecutablePermissions(newExecutablePath, binaryName);
                Files.move(newExecutablePath, executablePath, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(archivePath);
                return;
            }
        } finally {
            Files.deleteIfExists(newExecutablePath);
        }

        throw new IllegalStateException("Could not find the rclone binary inside the downloaded archive.");
    }

    private void setExecutablePermissions(Path executablePath, String binaryName) throws IOException {
        if (binaryName.endsWith(".exe")) {
            return;
        }

        try {
            Files.setPosixFilePermissions(executablePath, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private String readTextIfExists(Path filePath) throws IOException {
        return Files.isRegularFile(filePath)
            ? Files.readString(filePath, StandardCharsets.UTF_8).trim()
            : "";
    }

    private boolean shouldCheckForUpdates(Path checkedAtFile) throws IOException {
        String rawValue = readTextIfExists(checkedAtFile);
        if (rawValue.isBlank()) {
            return true;
        }

        try {
            return System.currentTimeMillis() - Long.parseLong(rawValue) >= UPDATE_INTERVAL_MS;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private void writeUpdateCheckTime(Path checkedAtFile) throws IOException {
        Files.writeString(checkedAtFile, String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8);
    }

    private String formatBytes(long value) {
        if (value <= 0) {
            return "0 B";
        }

        String[] units = {"B", "KB", "MB", "GB"};
        double size = value;
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return (size >= 10 ? Math.round(size) : Math.round(size * 10.0) / 10.0) + " " + units[unitIndex];
    }

    private record RclonePlatform(String archiveName, String binaryName, String label) {
    }
}
