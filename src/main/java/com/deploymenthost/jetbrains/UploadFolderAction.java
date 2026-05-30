package com.deploymenthost.jetbrains;

public class UploadFolderAction extends TransferAction {
    public UploadFolderAction() {
        super(Direction.UPLOAD, Target.FOLDER);
    }
}
