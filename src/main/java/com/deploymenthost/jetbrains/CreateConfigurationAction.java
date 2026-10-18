package com.deploymenthost.jetbrains;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CreateConfigurationAction extends DumbAwareAction {
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null && project.getBasePath() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.getBasePath() == null) {
            return;
        }

        try {
            VirtualFile projectFolder = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(project.getBasePath());
            if (projectFolder == null || !projectFolder.isDirectory()) {
                throw new IOException("Could not find the project folder.");
            }

            projectFolder.refresh(false, false);
            VirtualFile configFile = WriteCommandAction.writeCommandAction(project)
                .withName("Create Push & Pull Configuration")
                .compute(() -> {
                    VirtualFile existingFile = projectFolder.findChild("rclone.conf");
                    if (existingFile != null) {
                        if (existingFile.isDirectory()) {
                            throw new IOException("rclone.conf is a folder. A configuration file is required.");
                        }
                        return existingFile;
                    }

                    String template = """
                        # Replace the server details and destination below.
                        # Keep this file private and add rclone.conf to your .gitignore.

                        [my-server]
                        type = ftp
                        host = example.com
                        user = my-user
                        port = 21
                        # Explicit FTPS. Set to false only if your server requires plain FTP.
                        explicit_tls = true
                        pass-visible = my-password

                        [my-project]
                        type = alias
                        remote = my-server:/httpdocs
                        """;
                    VirtualFile createdFile = projectFolder.createChildData(this, "rclone.conf");
                    createdFile.setBinaryContent(template.getBytes(StandardCharsets.UTF_8));
                    return createdFile;
                });

            FileEditorManager.getInstance(project).openFile(configFile, true);
        } catch (IOException error) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Push & Pull")
                .createNotification(
                    "Push & Pull",
                    StringUtil.escapeXmlEntities("Configuration failed: " + error.getMessage()),
                    NotificationType.ERROR
                )
                .notify(project);
        }
    }
}
