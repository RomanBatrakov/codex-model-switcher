package ru.roman.codexmodelswitcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class CodexKeyboardShortcutStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        CodexKeyboardShortcutService.getInstance();
    }
}
