package ru.roman.codexmodelswitcher;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

public final class StartCodexServerAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) {
            CodexWorkflow.startServer(event.getProject());
        }
    }
}
