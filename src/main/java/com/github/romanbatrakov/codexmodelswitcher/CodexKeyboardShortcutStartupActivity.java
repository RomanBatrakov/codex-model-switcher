package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Eagerly initializes {@link CodexKeyboardShortcutService} on project open so
 * its global key event hooks are installed before the user can press a
 * model-switch shortcut.
 */
public final class CodexKeyboardShortcutStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        CodexKeyboardShortcutService.getInstance();
    }
}
