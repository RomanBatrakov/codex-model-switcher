package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

/**
 * Tools-menu action that opens the chat picker without changing the
 * currently selected model preset.
 */
public final class OpenCodexChatAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) {
            CodexWorkflow.chooseAndOpenChat(event.getProject());
        }
    }
}
