package com.deploymenthost.jetbrains;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class RcloneTransferService {
    private static final String REMOTE_ROOT = "my-project:";
    private static final long PROGRESS_THROTTLE_MS = 250L;
    private static final Pattern ANSI_SEQUENCE = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern OSC_SEQUENCE = Pattern.compile("\\u001B\\].*?(?:\\u0007|\\u001B\\\\)");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\u0000-\\u0008\\u000B-\\u001F\\u007F]");
    private static final Pattern RCLONE_PROGRESS = Pattern.compile(
        ".*\\d+(?:\\.\\d+)?\\s*(?:B|KiB|MiB|GiB|TiB)\\s*/\\s*\\d+(?:\\.\\d+)?\\s*(?:B|KiB|MiB|GiB|TiB).*"
    );

    void prepareConfig(Path rclonePath, TransferItem item) throws IOException, InterruptedException {
        makeRclonePassword(rclonePath, item.rootPath(), item.configPath());
    }

    void runTransfer(
        Path rclonePath,
        TransferAction.Direction direction,
        TransferItem item,
        int transfers,
        int checkers,
        Consumer<String> reportProgress
    ) throws IOException, InterruptedException {
        List<String> defaultArguments = defaultArguments(item.configPath(), transfers, checkers);
        List<String> arguments = direction == TransferAction.Direction.UPLOAD
            ? uploadArguments(item, defaultArguments)
            : downloadArguments(item, defaultArguments);
        runRcloneCommand(rclonePath, item.rootPath(), arguments, direction, reportProgress);
    }

    private List<String> uploadArguments(TransferItem item, List<String> defaultArguments) {
        Path localPath = item.rootPath().resolve(item.relativePath()).normalize();
        String remoteTarget = item.directory()
            ? joinRemotePath(REMOTE_ROOT, item.relativePath())
            : remoteParent(REMOTE_ROOT, item.relativePath());

        List<String> arguments = new ArrayList<>(List.of(
            "--config",
            item.configPath().toString(),
            "copy",
            localPath.toString(),
            remoteTarget
        ));
        arguments.addAll(defaultArguments);
        return arguments;
    }

    private List<String> downloadArguments(TransferItem item, List<String> defaultArguments) {
        String remotePath = joinRemotePath(REMOTE_ROOT, item.relativePath());
        Path selectedPath = item.rootPath().resolve(item.relativePath()).normalize();
        Path localTarget = item.directory() ? selectedPath : selectedPath.getParent();

        List<String> arguments = new ArrayList<>(List.of(
            "--config",
            item.configPath().toString(),
            "copy",
            remotePath,
            localTarget == null ? item.rootPath().toString() : localTarget.toString(),
            "--local-no-preallocate"
        ));
        arguments.addAll(defaultArguments);
        return arguments;
    }

    private List<String> defaultArguments(Path configPath, int transfers, int checkers) throws IOException {
        List<String> arguments = new ArrayList<>(List.of(
            "--progress",
            "--stats",
            "1s",
            "--stats-one-line",
            "--ignore-size"
        ));

        if (isWebdavProjectRemote(configPath)) {
            arguments.add("--ignore-times");
        }

        arguments.add("--transfers");
        arguments.add(String.valueOf(Math.max(1, transfers)));
        arguments.add("--checkers");
        arguments.add(String.valueOf(Math.max(1, checkers)));
        return arguments;
    }

    private void makeRclonePassword(Path rclonePath, Path rootPath, Path configPath) throws IOException, InterruptedException {
        if (!Files.isRegularFile(configPath)) {
            return;
        }

        String text = Files.readString(configPath, StandardCharsets.UTF_8);
        if (!Pattern.compile("^\\s*pass-visible\\s*=", Pattern.MULTILINE).matcher(text).find()) {
            return;
        }

        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n", -1)));
        List<Section> sections = configSections(lines);
        boolean changed = false;

        for (int sectionIndex = sections.size() - 1; sectionIndex >= 0; sectionIndex--) {
            Section section = sections.get(sectionIndex);
            int visibleIndex = findLineIndex(
                lines,
                section.start(),
                section.end(),
                Pattern.compile("^\\s*pass-visible\\s*=")
            );

            if (visibleIndex == -1) {
                continue;
            }

            String visiblePassword = lines.get(visibleIndex).replaceFirst("^\\s*pass-visible\\s*=\\s*", "");
            String obscuredPassword = obscureRclonePassword(rclonePath, visiblePassword, rootPath);
            String indentation = leadingWhitespace(lines.get(visibleIndex));

            for (int lineIndex = section.end() - 1; lineIndex >= section.start(); lineIndex--) {
                if (Pattern.compile("^\\s*pass\\s*=").matcher(lines.get(lineIndex)).find()) {
                    lines.remove(lineIndex);
                }
            }

            int adjustedVisibleIndex = findLineIndex(
                lines,
                section.start(),
                lines.size(),
                Pattern.compile("^\\s*pass-visible\\s*=")
            );
            lines.add(adjustedVisibleIndex + 1, indentation + "pass = " + obscuredPassword);
            changed = true;
        }

        if (changed) {
            Files.writeString(configPath, String.join(newline, lines), StandardCharsets.UTF_8);
        }
    }

    private String obscureRclonePassword(Path rclonePath, String password, Path workingDirectory)
        throws IOException, InterruptedException {
        Process process = new ProcessBuilder(rclonePath.toString(), "obscure", password)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();

        String output;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            output = reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right).trim();
        }

        if (process.waitFor() != 0) {
            throw new IllegalStateException("Could not run rclone obscure.");
        }
        return output;
    }

    private boolean isWebdavProjectRemote(Path configPath) throws IOException {
        Map<String, Map<String, String>> sections = parseRcloneConfig(
            Files.readString(configPath, StandardCharsets.UTF_8)
        );
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

        while (!currentName.isBlank() && visited.add(currentName)) {
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

    private void runRcloneCommand(
        Path rclonePath,
        Path workingDirectory,
        List<String> arguments,
        TransferAction.Direction direction,
        Consumer<String> reportProgress
    ) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(rclonePath.toString());
        command.addAll(arguments);

        Process process = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        AtomicLong lastProgressAt = new AtomicLong();
        Thread stdoutThread = startReader(process.getInputStream(), stdout, direction, reportProgress, lastProgressAt);
        Thread stderrThread = startReader(process.getErrorStream(), stderr, direction, reportProgress, lastProgressAt);
        int exitCode = process.waitFor();
        stdoutThread.join();
        stderrThread.join();

        if (exitCode == 0) {
            return;
        }

        String details = lastMeaningfulLine(stderr);
        if (details.isBlank()) {
            details = lastMeaningfulLine(stdout);
        }
        if (details.isBlank()) {
            details = "rclone exited with code " + exitCode + ".";
        }
        throw new IllegalStateException(details);
    }

    private Thread startReader(
        InputStream stream,
        StringBuilder output,
        TransferAction.Direction direction,
        Consumer<String> reportProgress,
        AtomicLong lastProgressAt
    ) {
        Thread thread = new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                StringBuilder pending = new StringBuilder();
                boolean skipNextLineFeed = false;
                int read;

                while ((read = reader.read(buffer)) != -1) {
                    String chunkText = new String(buffer, 0, read);

                    if (!chunkText.contains("\r") && !chunkText.contains("\n") && isRcloneProgressText(chunkText)) {
                        pending.setLength(0);
                        pending.append(chunkText);
                        reportOutput(pending.toString(), direction, reportProgress, lastProgressAt);
                        continue;
                    }

                    String firstLine = chunkText.split("\\r?\\n", 2)[0];
                    if (isRcloneProgressText(pending.toString()) && isRcloneProgressText(firstLine)) {
                        pending.setLength(0);
                    }

                    for (int index = 0; index < read; index++) {
                        char currentCharacter = buffer[index];

                        if (skipNextLineFeed) {
                            skipNextLineFeed = false;
                            if (currentCharacter == '\n') {
                                continue;
                            }
                        }

                        if (currentCharacter == '\r') {
                            reportOutput(pending.toString(), direction, reportProgress, lastProgressAt);
                            pending.setLength(0);
                            skipNextLineFeed = true;
                            continue;
                        }

                        if (currentCharacter == '\n') {
                            String line = cleanProgressText(pending.toString());
                            pending.setLength(0);
                            if (!line.isBlank()) {
                                synchronized (output) {
                                    output.append(line).append('\n');
                                }
                                reportOutput(line, direction, reportProgress, lastProgressAt);
                            }
                            continue;
                        }

                        pending.append(currentCharacter);
                    }

                    reportOutput(pending.toString(), direction, reportProgress, lastProgressAt);
                }

                String tail = cleanProgressText(pending.toString());
                if (!tail.isBlank()) {
                    synchronized (output) {
                        output.append(tail).append('\n');
                    }
                    reportOutput(tail, direction, reportProgress, lastProgressAt);
                }
            } catch (IOException ignored) {
            }
        }, "push-pull-rclone-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void reportOutput(
        String value,
        TransferAction.Direction direction,
        Consumer<String> reportProgress,
        AtomicLong lastProgressAt
    ) {
        String cleanLine = compactLine(keepLatestEta(cleanProgressText(value)));
        if (cleanLine.isBlank()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long previousTime = lastProgressAt.get();
        if (currentTime - previousTime < PROGRESS_THROTTLE_MS || !lastProgressAt.compareAndSet(previousTime, currentTime)) {
            return;
        }

        String prefix = direction == TransferAction.Direction.UPLOAD ? "Uploading: " : "Downloading: ";
        reportProgress.accept(prefix + cleanLine);
    }

    private String cleanProgressText(String value) {
        return CONTROL_CHARACTER.matcher(
            OSC_SEQUENCE.matcher(
                ANSI_SEQUENCE.matcher(value).replaceAll(" ")
            ).replaceAll(" ")
        ).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private String keepLatestEta(String value) {
        int firstEta = value.indexOf("ETA");
        int lastEta = value.lastIndexOf("ETA");
        if (firstEta < 0 || firstEta == lastEta) {
            return value;
        }
        return (value.substring(0, firstEta).trim() + " " + value.substring(lastEta).trim()).trim();
    }

    private boolean isRcloneProgressText(String value) {
        return value.contains("ETA") && RCLONE_PROGRESS.matcher(value).matches();
    }

    private String compactLine(String value) {
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 140 ? normalized : normalized.substring(0, 140);
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

    private String remoteParent(String remoteRoot, String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex == -1 ? remoteRoot : remoteRoot + "/" + normalized.substring(0, slashIndex);
    }

    private String joinRemotePath(String remoteRoot, String relativePath) {
        String normalized = relativePath.replace('\\', '/').replaceFirst("^/+", "");
        return normalized.isBlank() ? remoteRoot : remoteRoot + "/" + normalized;
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

    record TransferItem(Path rootPath, Path configPath, String relativePath, boolean directory) {
    }

    private record Section(int start, int end) {
    }
}
