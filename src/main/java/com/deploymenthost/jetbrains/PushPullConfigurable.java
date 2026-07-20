package com.deploymenthost.jetbrains;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class PushPullConfigurable implements Configurable {
    private final PushPullSettings settings;
    private JPanel panel;
    private JSpinner transfersInput;
    private JSpinner checkersInput;

    public PushPullConfigurable(Project project) {
        settings = PushPullSettings.getInstance(project);
    }

    @Override
    public @Nls String getDisplayName() {
        return "Push & Pull";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new GridBagLayout());
        transfersInput = new JSpinner(new SpinnerNumberModel(settings.transfers(), 1, 128, 1));
        checkersInput = new JSpinner(new SpinnerNumberModel(settings.checkers(), 1, 128, 1));

        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(0, 0, 8, 12);

        GridBagConstraints inputConstraints = new GridBagConstraints();
        inputConstraints.gridx = 1;
        inputConstraints.weightx = 1;
        inputConstraints.anchor = GridBagConstraints.WEST;
        inputConstraints.insets = new Insets(0, 0, 8, 0);

        labelConstraints.gridy = 0;
        inputConstraints.gridy = 0;
        panel.add(new JLabel("Transfers"), labelConstraints);
        panel.add(transfersInput, inputConstraints);

        labelConstraints.gridy = 1;
        inputConstraints.gridy = 1;
        panel.add(new JLabel("Checkers"), labelConstraints);
        panel.add(checkersInput, inputConstraints);

        GridBagConstraints fillerConstraints = new GridBagConstraints();
        fillerConstraints.gridx = 0;
        fillerConstraints.gridy = 2;
        fillerConstraints.gridwidth = 2;
        fillerConstraints.weightx = 1;
        fillerConstraints.weighty = 1;
        panel.add(new JPanel(), fillerConstraints);
        return panel;
    }

    @Override
    public boolean isModified() {
        return transfersValue() != settings.transfers() || checkersValue() != settings.checkers();
    }

    @Override
    public void apply() {
        settings.setTransfers(transfersValue());
        settings.setCheckers(checkersValue());
    }

    @Override
    public void reset() {
        transfersInput.setValue(settings.transfers());
        checkersInput.setValue(settings.checkers());
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        transfersInput = null;
        checkersInput = null;
    }

    private int transfersValue() {
        return ((Number) transfersInput.getValue()).intValue();
    }

    private int checkersValue() {
        return ((Number) checkersInput.getValue()).intValue();
    }
}
