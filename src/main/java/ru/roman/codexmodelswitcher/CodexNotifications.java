package ru.roman.codexmodelswitcher;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

final class CodexNotifications {
    private CodexNotifications() {
    }

    static void error(Project project, String title, String content) {
        notify(project, title, content, NotificationType.ERROR);
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> NotificationGroupManager.getInstance()
                .getNotificationGroup("Codex Model Switcher")
                .createNotification(title, content, type)
                .notify(project));
    }
}
