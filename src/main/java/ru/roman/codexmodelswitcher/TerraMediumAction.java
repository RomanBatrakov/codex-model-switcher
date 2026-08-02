package ru.roman.codexmodelswitcher;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

public final class TerraMediumAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) {
            CodexWorkflow.switchModel(event.getProject(), ModelPreset.forId(ModelPreset.PresetId.TERRA_MEDIUM));
        }
    }
}
