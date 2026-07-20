package com.deploymenthost.jetbrains;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

abstract class TransferAction extends AnAction {
    private static final String TITLE = "Push & Pull";
    private static final String NOTIFICATION_GROUP = "Push & Pull";

    private final Direction direction;
    private final Target target;
    private final RcloneBinaryManager binaryManager = new RcloneBinaryManager();
    private final RcloneTransferService transferService = new RcloneTransferService();

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
        List<VirtualFile> selectedFiles = selectedFiles(event);
        boolean correctSelection = !selectedFiles.isEmpty() && selectedFiles.stream().allMatch(this::isExpectedTarget);
        boolean toolbarPlace = event.getPlace().toLowerCase(Locale.ROOT).contains("toolbar");

        event.getPresentation().setEnabled(correctSelection);
        event.getPresentation().setVisible(toolbarPlace || correctSelection);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        List<VirtualFile> selectedFiles = selectedFiles(event);
        if (selectedFiles.isEmpty()) {
            showNotification(
                project,
                target == Target.FOLDER ? "Select a folder first." : "Open or select a file first.",
                NotificationType.WARNING
            );
            return;
        }

        if (!selectedFiles.stream().allMatch(this::isExpectedTarget)) {
            showNotification(
                project,
                target == Target.FOLDER
                    ? "Please select only folders for this action."
                    : "Please select only files for this action.",
                NotificationType.WARNING
            );
            return;
        }

        List<RcloneTransferService.TransferItem> items = new ArrayList<>();

        try {
            for (VirtualFile file : selectedFiles) {
                RcloneTransferService.TransferItem item = resolveTransferItem(project, file);
                if (item == null) {
                    showNotification(
                        project,
                        "Could not find rclone.conf for " + file.getName() + ".",
                        NotificationType.WARNING
                    );
                    return;
                }
                items.add(item);
            }
        } catch (Exception error) {
            showNotification(project, messageOf(error), NotificationType.ERROR);
            return;
        }

        Notification liveNotification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(TITLE, "Checking rclone...", NotificationType.INFORMATION);
        liveNotification.notify(project);

        new Task.Backgroundable(project, TITLE, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);

                try {
                    reportProgress(indicator, liveNotification, "Checking rclone...");
                    Path rclonePath = binaryManager.ensureLatestRclone(
                        message -> reportProgress(indicator, liveNotification, message)
                    );

                    Set<Path> preparedConfigs = new HashSet<>();
                    for (RcloneTransferService.TransferItem item : items) {
                        if (preparedConfigs.add(item.configPath())) {
                            reportProgress(
                                indicator,
                                liveNotification,
                                "Preparing " + item.configPath().getFileName() + "..."
                            );
                            transferService.prepareConfig(rclonePath, item);
                        }
                    }

                    PushPullSettings settings = PushPullSettings.getInstance(project);

                    for (int index = 0; index < items.size(); index++) {
                        RcloneTransferService.TransferItem item = items.get(index);
                        String itemLabel = items.size() > 1
                            ? (index + 1) + "/" + items.size() + " " + item.relativePath()
                            : item.relativePath();
                        String startingMessage = direction == Direction.UPLOAD
                            ? "Starting upload: " + itemLabel
                            : "Starting download: " + itemLabel;

                        reportProgress(indicator, liveNotification, startingMessage);
                        transferService.runTransfer(
                            rclonePath,
                            direction,
                            item,
                            settings.transfers(),
                            settings.checkers(),
                            message -> {
                                String progressMessage = message;

                                if (items.size() > 1) {
                                    String directionPrefix = direction == Direction.UPLOAD
                                        ? "Uploading: "
                                        : "Downloading: ";
                                    String details = message.startsWith(directionPrefix)
                                        ? message.substring(directionPrefix.length())
                                        : message;
                                    progressMessage = directionPrefix + itemLabel + " — " + details;
                                }

                                reportProgress(indicator, liveNotification, progressMessage);
                            }
                        );

                        if (direction == Direction.DOWNLOAD) {
                            selectedFiles.get(index).refresh(false, true);
                        }
                    }

                    String finishedMessage = direction == Direction.UPLOAD
                        ? "Upload finished."
                        : "Download finished.";
                    indicator.setText(finishedMessage);
                    indicator.setText2("");
                    finishNotification(project, liveNotification, finishedMessage, NotificationType.INFORMATION);
                } catch (Exception error) {
                    String failedMessage = (direction == Direction.UPLOAD ? "Upload failed: " : "Download failed: ")
                        + messageOf(error);
                    indicator.setText(failedMessage);
                    indicator.setText2("");
                    finishNotification(project, liveNotification, failedMessage, NotificationType.ERROR);
                }
            }
        }.queue();
    }

    private List<VirtualFile> selectedFiles(AnActionEvent event) {
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null && files.length > 0) {
            return Arrays.asList(files);
        }

        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        return file == null ? List.of() : List.of(file);
    }

    private boolean isExpectedTarget(VirtualFile file) {
        return (target == Target.FOLDER) == file.isDirectory();
    }

    private RcloneTransferService.TransferItem resolveTransferItem(Project project, VirtualFile file) {
        Path itemPath = Path.of(file.getPath()).toAbsolutePath().normalize();
        Path projectRoot = project.getBasePath() == null || project.getBasePath().isBlank()
            ? null
            : Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path rootPath = projectRoot != null && itemPath.startsWith(projectRoot)
            ? projectRoot
            : findRcloneRoot(itemPath, file.isDirectory());

        if (rootPath == null || !Files.isRegularFile(rootPath.resolve("rclone.conf"))) {
            rootPath = findRcloneRoot(itemPath, file.isDirectory());
        }
        if (rootPath == null) {
            return null;
        }

        Path relativePath = rootPath.relativize(itemPath);
        if (relativePath.toString().isBlank() || relativePath.startsWith("..") || relativePath.isAbsolute()) {
            throw new IllegalStateException("Could not make a project-relative file path.");
        }

        return new RcloneTransferService.TransferItem(
            rootPath,
            rootPath.resolve("rclone.conf"),
            relativePath.toString(),
            file.isDirectory()
        );
    }

    private Path findRcloneRoot(Path itemPath, boolean directory) {
        Path currentPath = directory ? itemPath : itemPath.getParent();

        while (currentPath != null) {
            if (Files.isRegularFile(currentPath.resolve("rclone.conf"))) {
                return currentPath;
            }
            currentPath = currentPath.getParent();
        }
        return null;
    }

    private void reportProgress(ProgressIndicator indicator, Notification notification, String message) {
        int separatorIndex = message.indexOf(':');
        if (separatorIndex > 0 && (message.startsWith("Uploading:") || message.startsWith("Downloading:"))) {
            indicator.setText(message.substring(0, separatorIndex) + "...");
            indicator.setText2(message.substring(separatorIndex + 1).trim());
        } else {
            indicator.setText(message);
            indicator.setText2("");
        }

        ApplicationManager.getApplication().invokeLater(
            () -> notification.setContent(StringUtil.escapeXmlEntities(message))
        );
    }

    private void finishNotification(
        Project project,
        Notification liveNotification,
        String message,
        NotificationType notificationType
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            liveNotification.expire();
            showNotification(project, message, notificationType);
        });
    }

    private void showNotification(Project project, String message, NotificationType notificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(TITLE, StringUtil.escapeXmlEntities(message), notificationType)
            .notify(project);
    }

    private String messageOf(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? TITLE + " failed."
            : error.getMessage();
    }
}
