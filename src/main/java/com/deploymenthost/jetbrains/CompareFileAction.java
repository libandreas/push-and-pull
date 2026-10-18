package com.deploymenthost.jetbrains;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class CompareFileAction extends AnAction {
    private static final String TITLE = "Push & Pull";
    private static final String NOTIFICATION_GROUP = "Push & Pull";

    private final RcloneBinaryManager binaryManager = new RcloneBinaryManager();
    private final RcloneTransferService transferService = new RcloneTransferService();
    private final ReadOnlyTransferService readOnlyTransferService = new ReadOnlyTransferService();

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile localFile = project == null ? null : selectedFile(event, project);
        boolean validFile = localFile != null && !localFile.isDirectory();
        boolean toolbarPlace = event.getPlace().toLowerCase(Locale.ROOT).contains("toolbar");

        event.getPresentation().setEnabled(validFile);
        event.getPresentation().setVisible(toolbarPlace || validFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        VirtualFile localFile = selectedFile(event, project);
        if (localFile == null || localFile.isDirectory()) {
            showNotification(project, "Open a local file first.", NotificationType.WARNING);
            return;
        }

        ReadOnlyTransferService.ReadOnlySource readOnlySource;
        try {
            readOnlySource = readOnlyTransferService.resolve(localFile);
        } catch (Exception error) {
            showNotification(project, "Compare failed: " + errorMessage(error), NotificationType.ERROR);
            return;
        }

        if (readOnlySource != null) {
            compareReadOnlyFile(project, localFile, readOnlySource);
            return;
        }

        RcloneTransferService.TransferItem item;
        try {
            item = transferService.resolveTransferItem(localFile);
        } catch (Exception error) {
            showNotification(project, errorMessage(error), NotificationType.ERROR);
            return;
        }

        if (item == null) {
            showNotification(
                project,
                "Could not find rclone.conf for this file. Put rclone.conf in this file's project root or open the correct workspace.",
                NotificationType.WARNING
            );
            return;
        }

        String fileName = localFile.getName();
        Path temporaryFile = Path.of(
            PathManager.getSystemPath(),
            "push-and-pull",
            "compare",
            Integer.toHexString(item.rootPath().toString().hashCode()),
            item.relativePath()
        );

        Notification liveNotification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(TITLE, "Downloading online version...", NotificationType.INFORMATION);
        liveNotification.notify(project);

        new Task.Backgroundable(project, "Comparing " + fileName, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    reportProgress(indicator, liveNotification, "Downloading online version...");

                    Path rclonePath = binaryManager.ensureLatestRclone(
                        message -> reportProgress(indicator, liveNotification, message)
                    );
                    transferService.prepareConfig(rclonePath, item);
                    transferService.downloadForComparison(
                        rclonePath,
                        item,
                        temporaryFile,
                        message -> reportProgress(indicator, liveNotification, message)
                    );

                    ApplicationManager.getApplication().invokeLater(() -> openDiff(
                        project,
                        localFile,
                        temporaryFile,
                        fileName
                    ));
                    finishNotification(project, liveNotification, "Compare finished.", NotificationType.INFORMATION);
                } catch (Exception error) {
                    finishNotification(
                        project,
                        liveNotification,
                        "Compare failed: " + errorMessage(error),
                        NotificationType.ERROR
                    );
                }
            }
        }.queue();
    }

    private void compareReadOnlyFile(
        Project project,
        VirtualFile localFile,
        ReadOnlyTransferService.ReadOnlySource source
    ) {
        String fileName = localFile.getName();
        Path temporaryFile = Path.of(
            PathManager.getSystemPath(),
            "push-and-pull",
            "compare",
            Integer.toHexString(source.rootPath().toString().hashCode()),
            source.relativePath()
        );

        Notification liveNotification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(TITLE, "Downloading online version via HTTP...", NotificationType.INFORMATION);
        liveNotification.notify(project);

        new Task.Backgroundable(project, "Comparing " + fileName, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    reportProgress(indicator, liveNotification, "Downloading online version via HTTP...");

                    byte[] body = readOnlyTransferService.download(
                        source,
                        message -> reportProgress(indicator, liveNotification, message)
                    );
                    Files.createDirectories(temporaryFile.getParent());
                    Files.write(temporaryFile, body);
                    reportProgress(indicator, liveNotification, "Opening comparison...");

                    ApplicationManager.getApplication().invokeLater(() -> openDiff(
                        project,
                        localFile,
                        temporaryFile,
                        fileName
                    ));
                    finishNotification(project, liveNotification, "Compare finished.", NotificationType.INFORMATION);
                } catch (Exception error) {
                    finishNotification(
                        project,
                        liveNotification,
                        "Compare failed: " + errorMessage(error),
                        NotificationType.ERROR
                    );
                }
            }
        }.queue();
    }

    private VirtualFile activeFile(Project project) {
        VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        return selectedFiles.length == 0 ? null : selectedFiles[0];
    }

    private VirtualFile selectedFile(AnActionEvent event, Project project) {
        VirtualFile contextFile = event.getData(CommonDataKeys.VIRTUAL_FILE);
        return contextFile == null ? activeFile(project) : contextFile;
    }

    private void openDiff(Project project, VirtualFile localFile, Path temporaryFile, String fileName) {
        VirtualFile onlineFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temporaryFile);
        if (onlineFile == null) {
            showNotification(project, "Compare failed: Could not open the online version.", NotificationType.ERROR);
            return;
        }

        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
        DiffContent onlineContent = contentFactory.create(project, onlineFile);
        Document localDocument = FileDocumentManager.getInstance().getDocument(localFile);
        DiffContent localContent = localDocument == null
            ? contentFactory.create(project, localFile)
            : contentFactory.create(project, localDocument, localFile);

        SimpleDiffRequest request = new SimpleDiffRequest(
            fileName + ": Online ↔ Local",
            onlineContent,
            localContent,
            "Online",
            "Local"
        );
        DiffManager.getInstance().showDiff(project, request);
    }

    private void reportProgress(ProgressIndicator indicator, Notification notification, String message) {
        indicator.setText(message);
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

    private void showNotification(Project project, String message, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(TITLE, StringUtil.escapeXmlEntities(message), type)
            .notify(project));
    }

    private String errorMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? "Push & Pull failed."
            : error.getMessage();
    }
}
