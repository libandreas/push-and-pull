package com.deploymenthost.jetbrains;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name = "PushPullSettings", storages = @Storage("push-pull.xml"))
public final class PushPullSettings implements PersistentStateComponent<PushPullSettings.SettingsState> {
    private SettingsState state = new SettingsState();

    static PushPullSettings getInstance(Project project) {
        return project.getService(PushPullSettings.class);
    }

    int transfers() {
        return Math.max(1, state.transfers);
    }

    int checkers() {
        return Math.max(1, state.checkers);
    }

    void setTransfers(int transfers) {
        state.transfers = Math.max(1, transfers);
    }

    void setCheckers(int checkers) {
        state.checkers = Math.max(1, checkers);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }

    public static final class SettingsState {
        public int transfers = 4;
        public int checkers = 8;
    }
}
