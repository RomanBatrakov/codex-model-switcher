package ru.roman.codexmodelswitcher;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class CodexWorkflow {
    private static final List<Path> CODEX_LOCATIONS = List.of(
            Path.of("/opt/homebrew/bin/codex"),
            Path.of("/usr/local/bin/codex")
    );
    private CodexWorkflow() {
    }

    static void startServer(Project project) {
        ensureServerReady(project).whenComplete((ignored, error) -> {
            if (error != null) {
                CodexNotifications.error(project, "Не удалось подключиться к серверу Codex", serverErrorMessage(error));
            }
        });
    }

    static void switchModel(Project project, ModelPreset preset) {
        ensureServerReady(project)
                .thenCompose(ignored -> CodexAppServerClient.getInstance().updateAllThreadSettings(preset))
                .thenRun(() -> CodexModelSwitcherSettings.getInstance().setLastPresetId(preset.id()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        CodexNotifications.error(project, "Модель не изменена", userMessage(error));
                    }
                });
    }

    static void startNewChat(Project project) {
        String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
        ModelPreset preset = ModelPreset.forId(CodexModelSwitcherSettings.getInstance().lastPresetId());
        ensureServerReady(project).whenComplete((ignored, error) -> {
            if (error != null) {
                CodexNotifications.error(project, "Не удалось создать новый чат", userMessage(error));
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> openNewChatInTerminal(project, directory, preset));
        });
    }

    static void chooseAndOpenChat(Project project) {
        ensureServerReady(project)
                .thenCompose(ignored -> CodexAppServerClient.getInstance().listThreads())
                .whenComplete((threads, error) -> {
                    if (error != null) {
                        CodexNotifications.error(project, "Нет связи с Codex", serverErrorMessage(error));
                        return;
                    }
                    ApplicationManager.getApplication().invokeLater(() -> showChatPicker(project, threads));
                });
    }

    private static void showChatPicker(Project project, List<CodexThread> threads) {
        CodexModelSwitcherSettings settings = CodexModelSwitcherSettings.getInstance();
        ChooseCodexThreadDialog dialog = new ChooseCodexThreadDialog(
                project,
                threads,
                settings.lastThreadId(),
                () -> CodexAppServerClient.getInstance().listThreads());
        if (!dialog.showAndGet()) {
            return;
        }
        CodexThread thread = dialog.selectedThread();
        if (thread != null) {
            settings.setLastThreadId(thread.id());
            openChatInTerminal(project, thread);
        }
    }

    static ModelPreset.PresetId lastPresetId() {
        return CodexModelSwitcherSettings.getInstance().lastPresetId();
    }

    /**
     * Starting the terminal command only means that IntelliJ accepted it. The
     * app server needs a moment before it accepts a WebSocket, so the real
     * protocol connection is retried before a model update begins.
     */
    private static CompletableFuture<Void> ensureServerReady(Project project) {
        return CodexAppServerClient.getInstance().connect()
                .handle((ignored, error) -> error == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : startServerTerminal(project).thenCompose(ignoredAgain -> waitForServer(16)))
                .thenCompose(Function.identity());
    }

    private static CompletableFuture<Void> startServerTerminal(Project project) {
        CompletableFuture<Void> terminalCreated = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                CodexServerStarter.getInstance().start(project);
                terminalCreated.complete(null);
            } catch (Throwable error) {
                terminalCreated.completeExceptionally(error);
            }
        });
        return terminalCreated;
    }

    private static CompletableFuture<Void> waitForServer(int attemptsLeft) {
        return CodexAppServerClient.getInstance().connect()
                .handle((ignored, error) -> {
                    if (error == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (attemptsLeft <= 1) {
                        return CompletableFuture.<Void>failedFuture(error);
                    }
                    return CompletableFuture.runAsync(
                                    () -> { },
                                    CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS)
                            )
                            .thenCompose(nextAttempt -> waitForServer(attemptsLeft - 1));
                })
                .thenCompose(Function.identity());
    }

    private static void openChatInTerminal(Project project, CodexThread thread) {
        if (!thread.id().matches("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")) {
            CodexNotifications.error(project, "Некорректный идентификатор чата", "Плагин не стал открывать чат с неизвестным идентификатором.");
            return;
        }
        try {
            String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
            String command = shellQuote(codexExecutable())
                    + " --remote " + shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl())
                    + " resume " + thread.id();
            ShellTerminalWidget terminal = TerminalView.getInstance(project)
                    .createLocalShellWidget(directory, "Codex — " + thread.shortId(), true, true);
            terminal.executeCommand(command);
        } catch (IOException error) {
            CodexNotifications.error(project, "Не удалось открыть чат в терминале", userMessage(error));
        }
    }

    private static void openNewChatInTerminal(Project project, String directory, ModelPreset preset) {
        try {
            String command = shellQuote(codexExecutable())
                    + " --remote " + shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl())
                    + " --model " + shellQuote(preset.model())
                    + " -c " + shellQuote("model_reasoning_effort=\"" + preset.effort() + "\"");
            ShellTerminalWidget terminal = TerminalView.getInstance(project)
                    .createLocalShellWidget(directory, "Codex — новый чат", true, true);
            terminal.executeCommand(command);
        } catch (IOException error) {
            CodexNotifications.error(project, "Не удалось открыть новый чат", userMessage(error));
        }
    }

    private static String codexExecutable() {
        return CODEX_LOCATIONS.stream()
                .filter(Files::isExecutable)
                .map(Path::toString)
                .findFirst()
                .orElse("codex");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String userMessage(Throwable error) {
        Throwable current = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String serverErrorMessage(Throwable error) {
        return "Сервер Codex не стал доступен за несколько секунд. Открой вкладку «Codex Server» "
                + "внизу IDEA: там видна точная ошибка запуска. Техническая причина: " + userMessage(error);
    }
}
