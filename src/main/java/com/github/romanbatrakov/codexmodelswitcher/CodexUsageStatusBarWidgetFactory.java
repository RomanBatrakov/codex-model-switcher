package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the Codex usage widget in every project's status bar. It can be
 * hidden like any other widget via the status bar's right-click menu.
 */
public final class CodexUsageStatusBarWidgetFactory implements StatusBarWidgetFactory {
    @Override
    public @NotNull String getId() {
        return CodexUsageWidget.ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Codex Usage";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new CodexUsageWidget(project);
    }
}
