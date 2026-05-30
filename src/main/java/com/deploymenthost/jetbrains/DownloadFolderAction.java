package com.deploymenthost.jetbrains;

public class DownloadFolderAction extends TransferAction {
    public DownloadFolderAction() {
        super(Direction.DOWNLOAD, Target.FOLDER);
    }
}
