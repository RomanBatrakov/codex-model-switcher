package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Tools-menu action that switches every active Codex chat to the Sol /
 * XHigh preset.
 */
public final class SolXHighAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) {
            CodexWorkflow.switchModel(event.getProject(), ModelPreset.forId(ModelPreset.PresetId.SOL_XHIGH));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        ModelPreset preset = ModelPreset.forId(ModelPreset.PresetId.SOL_XHIGH);
        event.getPresentation().setText("Codex: All chats — " + preset.model() + " / " + preset.effort());
        event.getPresentation().setDescription("Set all active Codex chats to " + preset.model() + " with " + preset.effort() + " reasoning");
    }
}
