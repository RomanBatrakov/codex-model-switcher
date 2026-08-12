package ru.roman.codexmodelswitcher;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

/**
 * Tools-menu action that manually starts the local Codex app server.
 * Every other action already starts it lazily on demand; this is only for
 * warming it up ahead of time.
 */
public final class StartCodexServerAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) {
            CodexWorkflow.startServer(event.getProject());
        }
    }
}
