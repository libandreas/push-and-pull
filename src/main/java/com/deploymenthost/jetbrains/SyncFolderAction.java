package com.deploymenthost.jetbrains;

public class SyncFolderAction extends TransferAction {
    public SyncFolderAction() {
        super(Direction.SYNC, Target.FOLDER);
    }
}
