package com.deploymenthost.jetbrains;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.regex.Pattern;

abstract class TransferAction extends AnAction {
    private static final String TITLE = "Push & Pull";
    private static final String REMOTE_ROOT = "my-project:";
    private static final int DEFAULT_TRANSFERS = 4;
    private static final int DEFAULT_CHECKERS = 8;

    private final Direction direction;
    private final Target target;

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

        try {
            TransferRoot transferRoot = resolveTransferRoot(project, file);
            if (transferRoot == null) {
                Messages.showWarningDialog(
                    project,
                    "Could not find rclone.conf for this item. Put rclone.conf in this file's project root or open the correct project.",
                    TITLE
                );
                return;
            }

            makeRclonePasswd(transferRoot.rootPath(), transferRoot.configPath());

            List<String> defaultArgs = getDefaultRcloneArgs(transferRoot.configPath());
            String command = direction == Direction.UPLOAD
                ? buildUploadCommand(transferRoot, file, defaultArgs)
                : buildDownloadCommand(transferRoot, defaultArgs);

            openTerminal(project, transferRoot.rootPath().toString(), command);
        } catch (Exception error) {
            Messages.showErrorDialog(project, error.getMessage() == null ? TITLE + " failed." : error.getMessage(), TITLE);
        }
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

    private String buildUploadCommand(TransferRoot transferRoot, VirtualFile file, List<String> defaultArgs) {
        Path localPath = transferRoot.rootPath().resolve(transferRoot.relativePath()).normalize();
        String remoteTarget = file.isDirectory()
            ? joinRemotePath(REMOTE_ROOT, transferRoot.relativePath())
            : remoteParent(REMOTE_ROOT, transferRoot.relativePath());

        return shellJoin(List.of(
            "rclone",
            "--config",
            transferRoot.configPath().toString(),
            "copy",
            localPath.toString(),
            remoteTarget
        ), defaultArgs);
    }

    private String buildDownloadCommand(TransferRoot transferRoot, List<String> defaultArgs) {
        String remotePath = joinRemotePath(REMOTE_ROOT, transferRoot.relativePath());
        Path localTarget = target == Target.FOLDER
            ? transferRoot.rootPath().resolve(transferRoot.relativePath()).normalize()
            : transferRoot.rootPath().resolve(transferRoot.relativePath()).normalize().getParent();

        List<String> baseArgs = new ArrayList<>(List.of(
            "rclone",
            "--config",
            transferRoot.configPath().toString(),
            "copy",
            remotePath,
            localTarget == null ? transferRoot.rootPath().toString() : localTarget.toString(),
            "--local-no-preallocate"
        ));

        return shellJoin(baseArgs, defaultArgs);
    }

    private List<String> getDefaultRcloneArgs(Path configPath) throws IOException {
        List<String> args = new ArrayList<>(List.of("--progress", "--ignore-size"));
        if (isWebdavProjectRemote(configPath)) {
            args.add("--ignore-times");
        }

        args.add("--transfers");
        args.add(String.valueOf(DEFAULT_TRANSFERS));
        args.add("--checkers");
        args.add(String.valueOf(DEFAULT_CHECKERS));
        return args;
    }

    private void makeRclonePasswd(Path rootPath, Path configPath) throws IOException, InterruptedException {
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
            String obscuredPassword = obscureRclonePassword(visiblePassword, rootPath);
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

    private String obscureRclonePassword(String password, Path cwd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("rclone", "obscure", password)
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

    private void openTerminal(Project project, String workingDirectory, String command) throws IOException {
        TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workingDirectory, TITLE)
            .executeCommand(command);
    }

    private String shellJoin(List<String> baseArgs, List<String> defaultArgs) {
        List<String> allArgs = new ArrayList<>(baseArgs);
        allArgs.addAll(defaultArgs);
        return allArgs.stream().map(this::shellQuote).reduce((left, right) -> left + " " + right).orElse("");
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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

    private record TransferRoot(Path rootPath, Path configPath, String relativePath) {
    }

    private record Section(int start, int end) {
    }
}
