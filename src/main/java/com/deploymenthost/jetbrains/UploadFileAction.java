package com.deploymenthost.jetbrains;

public class UploadFileAction extends TransferAction {
    public UploadFileAction() {
        super(Direction.UPLOAD, Target.FILE);
    }
}
