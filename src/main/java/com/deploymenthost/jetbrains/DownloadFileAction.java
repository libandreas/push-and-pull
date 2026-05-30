package com.deploymenthost.jetbrains;

public class DownloadFileAction extends TransferAction {
    public DownloadFileAction() {
        super(Direction.DOWNLOAD, Target.FILE);
    }
}
