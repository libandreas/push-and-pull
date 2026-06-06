package com.deploymenthost.jetbrains;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

abstract class TransferAction extends AnAction {
    private static final String TITLE = "Push & Pull";
    private static final String REMOTE_ROOT = "my-project:";
    private static final int DEFAULT_TRANSFERS = 4;
    private static final int DEFAULT_CHECKERS = 8;
    private static final String RCLONE_DOWNLOAD_HOST = "https://downloads.rclone.org";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final long RCLONE_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long RCLONE_FINISH_MESSAGE_MS = 1000L;
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
        "solaris", Map.of(
            "amd64", "solaris-amd64"
        ),
        "aix", Map.of(
            "ppc64", "aix-ppc64"
        )
    );

    private final Direction direction;
    private final Target target;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(HTTP_TIMEOUT)
        .build();

    enum Direction {
        UPLOAD,
        DOWNLOAD
    }

    enum Target {
        FILE,
        FOLDER
    }

    TransferAction(Direction direction, Target target) {
        this.direction = direction;
        this.target = target;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean targetSelected = file != null && (target == Target.FOLDER) == file.isDirectory();
        boolean toolbarPlace = event.getPlace().toLowerCase(Locale.ROOT).contains("toolbar");

        event.getPresentation().setEnabled(targetSelected);
        event.getPresentation().setVisible(toolbarPlace || targetSelected);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || file == null) {
            return;
        }

        TransferRoot transferRoot;

        try {
            transferRoot = resolveTransferRoot(project, file);
        } catch (Exception error) {
            Messages.showErrorDialog(project, error.getMessage() == null ? TITLE + " failed." : error.getMessage(), TITLE);
            return;
        }

        if (transferRoot == null) {
            Messages.showWarningDialog(
                project,
                "Could not find rclone.conf for this item. Put rclone.conf in this file's project root or open the correct project.",
                TITLE
            );
            return;
        }

        new Task.Backgroundable(project, TITLE, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);

                try {
                    indicator.setText("Checking rclone...");
                    Path rclonePath = ensureLatestRclone(indicator);

                    indicator.setText("Preparing rclone.conf...");
                    makeRclonePasswd(rclonePath, transferRoot.rootPath(), transferRoot.configPath());

                    List<String> defaultArgs = getDefaultRcloneArgs(transferRoot.configPath());
                    List<String> command = direction == Direction.UPLOAD
                        ? buildUploadCommand(transferRoot, file, defaultArgs)
                        : buildDownloadCommand(transferRoot, defaultArgs);

                    indicator.setText(direction == Direction.UPLOAD ? "Starting upload..." : "Starting download...");
                    runRcloneCommand(rclonePath, transferRoot.rootPath(), command, indicator);
                    indicator.setText(direction == Direction.UPLOAD ? "Upload finished." : "Download finished.");
                    indicator.setText2("");
                    sleepQuietly(RCLONE_FINISH_MESSAGE_MS);
                } catch (Exception error) {
                    indicator.setText((direction == Direction.UPLOAD ? "Upload failed: " : "Download failed: ") + messageOf(error));
                    indicator.setText2("");
                    sleepQuietly(RCLONE_FINISH_MESSAGE_MS);
                }
            }
        }.queue();
    }

    private TransferRoot resolveTransferRoot(Project project, VirtualFile file) {
        Path itemPath = Path.of(file.getPath()).toAbsolutePath().normalize();
        Path projectRoot = project.getBasePath() == null || project.getBasePath().isBlank()
            ? null
            : Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path rootPath = projectRoot != null && itemPath.startsWith(projectRoot)
            ? projectRoot
            : findRcloneRoot(itemPath, file.isDirectory());

        if (rootPath == null) {
            return null;
        }

        Path configPath = rootPath.resolve("rclone.conf");
        if (!Files.isRegularFile(configPath)) {
            Path discoveredRoot = findRcloneRoot(itemPath, file.isDirectory());
            if (discoveredRoot == null) {
                return null;
            }
            rootPath = discoveredRoot;
            configPath = rootPath.resolve("rclone.conf");
        }

        Path relativePath = rootPath.relativize(itemPath);
        if (relativePath.toString().isBlank() || relativePath.startsWith("..") || relativePath.isAbsolute()) {
            throw new IllegalStateException("Could not make a project-relative file path.");
        }

        return new TransferRoot(rootPath, configPath, relativePath.toString());
    }

    private Path findRcloneRoot(Path itemPath, boolean isDirectory) {
        Path currentPath = isDirectory ? itemPath : itemPath.getParent();

        while (currentPath != null) {
            if (Files.isRegularFile(currentPath.resolve("rclone.conf"))) {
                return currentPath;
            }
            currentPath = currentPath.getParent();
        }

        return null;
    }

    private List<String> buildUploadCommand(TransferRoot transferRoot, VirtualFile file, List<String> defaultArgs) {
        Path localPath = transferRoot.rootPath().resolve(transferRoot.relativePath()).normalize();
        String remoteTarget = file.isDirectory()
            ? joinRemotePath(REMOTE_ROOT, transferRoot.relativePath())
            : remoteParent(REMOTE_ROOT, transferRoot.relativePath());

        List<String> args = new ArrayList<>(List.of(
            "--config",
            transferRoot.configPath().toString(),
            "copy",
            localPath.toString(),
            remoteTarget
        ));
        args.addAll(defaultArgs);
        return args;
    }

    private List<String> buildDownloadCommand(TransferRoot transferRoot, List<String> defaultArgs) {
        String remotePath = joinRemotePath(REMOTE_ROOT, transferRoot.relativePath());
        Path localTarget = target == Target.FOLDER
            ? transferRoot.rootPath().resolve(transferRoot.relativePath()).normalize()
            : transferRoot.rootPath().resolve(transferRoot.relativePath()).normalize().getParent();

        List<String> args = new ArrayList<>(List.of(
            "--config",
            transferRoot.configPath().toString(),
            "copy",
            remotePath,
            localTarget == null ? transferRoot.rootPath().toString() : localTarget.toString(),
            "--local-no-preallocate"
        ));
        args.addAll(defaultArgs);
        return args;
    }

    private List<String> getDefaultRcloneArgs(Path configPath) throws IOException {
        List<String> args = new ArrayList<>(List.of(
            "--progress",
            "--stats",
            "1s",
            "--stats-one-line",
            "--ignore-size"
        ));
        if (isWebdavProjectRemote(configPath)) {
            args.add("--ignore-times");
        }

        args.add("--transfers");
        args.add(String.valueOf(DEFAULT_TRANSFERS));
        args.add("--checkers");
        args.add(String.valueOf(DEFAULT_CHECKERS));
        return args;
    }

    private void makeRclonePasswd(Path rclonePath, Path rootPath, Path configPath) throws IOException, InterruptedException {
        if (!Files.isRegularFile(configPath)) {
            return;
        }

        String text = Files.readString(configPath);
        if (!Pattern.compile("^\\s*pass-visible\\s*=", Pattern.MULTILINE).matcher(text).find()) {
            return;
        }

        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n", -1)));
        boolean changed = false;

        for (Section section : configSections(lines)) {
            int visibleIndex = findLineIndex(lines, section.start(), section.end(), Pattern.compile("^\\s*pass-visible\\s*="));
            if (visibleIndex == -1) {
                continue;
            }

            String visiblePassword = lines.get(visibleIndex).replaceFirst("^\\s*pass-visible\\s*=\\s*", "");
            String obscuredPassword = obscureRclonePassword(rclonePath, visiblePassword, rootPath);
            String indent = leadingWhitespace(lines.get(visibleIndex));

            for (int index = section.end() - 1; index >= section.start(); index--) {
                if (Pattern.compile("^\\s*pass\\s*=").matcher(lines.get(index)).find()) {
                    lines.remove(index);
                }
            }

            int adjustedVisibleIndex = findLineIndex(lines, section.start(), lines.size(), Pattern.compile("^\\s*pass-visible\\s*="));
            lines.add(adjustedVisibleIndex + 1, indent + "pass = " + obscuredPassword);
            changed = true;
        }

        if (changed) {
            Files.writeString(configPath, String.join(newline, lines));
        }
    }

    private String obscureRclonePassword(Path rclonePath, String password, Path cwd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(rclonePath.toString(), "obscure", password)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right).trim();
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Could not run rclone obscure.");
        }

        return output;
    }

    private boolean isWebdavProjectRemote(Path configPath) throws IOException {
        Map<String, Map<String, String>> sections = parseRcloneConfig(Files.readString(configPath));
        return "webdav".equals(resolveRcloneRemoteType(sections, "my-project"));
    }

    private Map<String, Map<String, String>> parseRcloneConfig(String text) {
        Map<String, Map<String, String>> sections = new HashMap<>();
        Map<String, String> currentSection = null;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = new HashMap<>();
                sections.put(line.substring(1, line.length() - 1).trim(), currentSection);
                continue;
            }

            int equalsIndex = line.indexOf('=');
            if (currentSection == null || equalsIndex == -1) {
                continue;
            }

            currentSection.put(
                line.substring(0, equalsIndex).trim().toLowerCase(Locale.ROOT),
                line.substring(equalsIndex + 1).trim()
            );
        }

        return sections;
    }

    private String resolveRcloneRemoteType(Map<String, Map<String, String>> sections, String remoteName) {
        Set<String> visited = new HashSet<>();
        String currentName = rcloneRemoteName(remoteName);

        while (!currentName.isBlank() && !visited.contains(currentName)) {
            visited.add(currentName);
            Map<String, String> section = sections.get(currentName);
            if (section == null) {
                return "";
            }

            String type = section.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            if (type.isBlank()) {
                return "";
            }

            if (!"alias".equals(type)) {
                return type;
            }

            currentName = rcloneRemoteName(section.getOrDefault("remote", ""));
        }

        return "";
    }

    private void runRcloneCommand(Path rclonePath, Path cwd, List<String> args, ProgressIndicator indicator) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(rclonePath.toString());
        command.addAll(args);

        Process process = new ProcessBuilder(command)
            .directory(cwd.toFile())
            .start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutThread = startReader(process.getInputStream(), stdout, indicator);
        Thread stderrThread = startReader(process.getErrorStream(), stderr, indicator);
        int exitCode = process.waitFor();
        stdoutThread.join();
        stderrThread.join();

        if (exitCode != 0) {
            String details = lastMeaningfulLine(stderr);
            if (details.isBlank()) {
                details = lastMeaningfulLine(stdout);
            }
            if (details.isBlank()) {
                details = "rclone exited with code " + exitCode + ".";
            }
            throw new IllegalStateException(details);
        }
    }

    private Thread startReader(InputStream stream, StringBuilder sink, ProgressIndicator indicator) {
        Thread thread = new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                StringBuilder pending = new StringBuilder();
                int read;

                while ((read = reader.read(buffer)) != -1) {
                    pending.append(buffer, 0, read);
                    int boundaryIndex;

                    while ((boundaryIndex = findLineBoundary(pending)) != -1) {
                        String line = pending.substring(0, boundaryIndex).trim();
                        removeProcessedLine(pending, boundaryIndex);

                        if (line.isBlank()) {
                            continue;
                        }

                        synchronized (sink) {
                            sink.append(line).append('\n');
                        }

                        indicator.setText(direction == Direction.UPLOAD ? "Uploading..." : "Downloading...");
                        indicator.setText2(compactLine(line));
                    }
                }

                String tail = pending.toString().trim();
                if (!tail.isBlank()) {
                    synchronized (sink) {
                        sink.append(tail).append('\n');
                    }

                    indicator.setText(direction == Direction.UPLOAD ? "Uploading..." : "Downloading...");
                    indicator.setText2(compactLine(tail));
                }
            } catch (IOException ignored) {
            }
        }, "push-pull-rclone-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private Path ensureLatestRclone(ProgressIndicator indicator) throws IOException, InterruptedException {
        RclonePlatform platform = getSupportedPlatform();
        Path storageDir = Path.of(PathManager.getSystemPath(), "push-and-pull", "rclone");
        Path versionFile = storageDir.resolve("version.txt");
        Path checkedAtFile = storageDir.resolve("checked-at.txt");
        Path executablePath = storageDir.resolve(platform.binaryName());

        Files.createDirectories(storageDir);
        String installedVersion = readTextIfExists(versionFile);
        boolean hasCachedBinary = Files.isRegularFile(executablePath);
        boolean shouldCheckForUpdates = shouldCheckForRcloneUpdates(checkedAtFile);

        if (hasCachedBinary && !shouldCheckForUpdates) {
            indicator.setText("Using cached rclone...");
            return executablePath;
        }

        try {
            indicator.setText("Checking latest rclone version...");
            String latestVersion = downloadText(URI.create(RCLONE_DOWNLOAD_HOST + "/version.txt"));
            writeUpdateCheckTime(checkedAtFile);

            if (latestVersion.equals(installedVersion) && hasCachedBinary) {
                return executablePath;
            }

            Path zipPath = storageDir.resolve("rclone-current-" + platform.archiveName() + ".zip");
            indicator.setText("Downloading rclone...");
            downloadFile(URI.create(RCLONE_DOWNLOAD_HOST + "/rclone-current-" + platform.archiveName() + ".zip"), zipPath, indicator);

            indicator.setText("Extracting rclone...");
            extractRcloneBinary(zipPath, executablePath, platform.binaryName());
            Files.writeString(versionFile, latestVersion, StandardCharsets.UTF_8);

            return executablePath;
        } catch (IOException | InterruptedException error) {
            if (hasCachedBinary) {
                indicator.setText("Using cached rclone...");
                return executablePath;
            }

            throw error;
        }
    }

    private RclonePlatform getSupportedPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = normalizePlatform(osName);
        String arch = normalizeArchitecture(osArch);
        String archiveName = PLATFORM_ARCHIVE_NAMES.getOrDefault(platform, Map.of()).get(arch);

        if (archiveName == null) {
            throw new IllegalStateException("No official rclone download mapping is configured for platform " + platform + " " + arch + ".");
        }

        return new RclonePlatform(
            archiveName,
            "windows".equals(platform) ? "rclone.exe" : "rclone"
        );
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

    private String downloadText(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("User-Agent", "push-and-pull-jetbrains-plugin")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Download failed with status " + response.statusCode() + ".");
        }
        return response.body().trim();
    }

    private void downloadFile(URI uri, Path targetPath, ProgressIndicator indicator) throws IOException, InterruptedException {
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
        AtomicLong downloadedBytes = new AtomicLong();

        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                long currentBytes = downloadedBytes.addAndGet(read);
                if (totalBytes > 0) {
                    indicator.setText2(Math.min(100, Math.round((currentBytes * 100.0) / totalBytes)) + "%");
                } else {
                    indicator.setText2(formatBytes(currentBytes));
                }
            }
        }
    }

    private void extractRcloneBinary(Path zipPath, Path executablePath, String binaryName) throws IOException {
        Files.createDirectories(executablePath.getParent());

        try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;

            while ((entry = zipInput.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = Path.of(entry.getName()).getFileName().toString();
                if (!binaryName.equals(entryName)) {
                    continue;
                }

                Files.copy(zipInput, executablePath, StandardCopyOption.REPLACE_EXISTING);
                zipInput.closeEntry();

                if (!binaryName.endsWith(".exe")) {
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

                Files.deleteIfExists(zipPath);
                return;
            }
        }

        throw new IllegalStateException("Could not find the rclone binary inside the downloaded archive.");
    }

    private String readTextIfExists(Path filePath) throws IOException {
        if (!Files.isRegularFile(filePath)) {
            return "";
        }
        return Files.readString(filePath, StandardCharsets.UTF_8).trim();
    }

    private boolean shouldCheckForRcloneUpdates(Path checkedAtFile) throws IOException {
        String rawValue = readTextIfExists(checkedAtFile);
        if (rawValue.isBlank()) {
            return true;
        }

        try {
            long lastCheckedAt = Long.parseLong(rawValue);
            return System.currentTimeMillis() - lastCheckedAt >= RCLONE_UPDATE_INTERVAL_MS;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private void writeUpdateCheckTime(Path checkedAtFile) throws IOException {
        Files.writeString(checkedAtFile, String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8);
    }

    private String compactLine(String value) {
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 140 ? normalized : normalized.substring(0, 140);
    }

    private int findLineBoundary(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            char currentChar = text.charAt(index);
            if (currentChar == '\r' || currentChar == '\n') {
                return index;
            }
        }
        return -1;
    }

    private void removeProcessedLine(StringBuilder text, int boundaryIndex) {
        int removeUntil = boundaryIndex + 1;
        if (removeUntil < text.length()) {
            char current = text.charAt(boundaryIndex);
            char next = text.charAt(removeUntil);
            if ((current == '\r' && next == '\n') || (current == '\n' && next == '\r')) {
                removeUntil++;
            }
        }
        text.delete(0, removeUntil);
    }

    private String lastMeaningfulLine(StringBuilder text) {
        String[] lines = text.toString().split("\\r?\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String remoteParent(String remoteRoot, String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex == -1 ? remoteRoot : remoteRoot + "/" + normalized.substring(0, slashIndex);
    }

    private String joinRemotePath(String remoteRoot, String relativePath) {
        return remoteRoot + "/" + relativePath.replace('\\', '/').replaceFirst("^/+", "");
    }

    private String rcloneRemoteName(String remote) {
        return remote == null ? "" : remote.trim().split(":", 2)[0];
    }

    private List<Section> configSections(List<String> lines) {
        List<Integer> starts = new ArrayList<>();
        Pattern sectionPattern = Pattern.compile("^\\s*\\[[^]]+]\\s*$");

        for (int index = 0; index < lines.size(); index++) {
            if (sectionPattern.matcher(lines.get(index)).matches()) {
                starts.add(index);
            }
        }

        if (starts.isEmpty()) {
            return List.of(new Section(0, lines.size()));
        }

        List<Section> sections = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            sections.add(new Section(starts.get(index), index + 1 < starts.size() ? starts.get(index + 1) : lines.size()));
        }
        return sections;
    }

    private int findLineIndex(List<String> lines, int start, int end, Pattern pattern) {
        for (int index = start; index < Math.min(end, lines.size()); index++) {
            if (pattern.matcher(lines.get(index)).find()) {
                return index;
            }
        }
        return -1;
    }

    private String leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(0, index);
    }

    private String messageOf(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? TITLE + " failed." : error.getMessage();
    }

    private record TransferRoot(Path rootPath, Path configPath, String relativePath) {
    }

    private record Section(int start, int end) {
    }

    private record RclonePlatform(String archiveName, String binaryName) {
    }
}
