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

/**
 * Top-level entry points for every user-triggered action: switching a model
 * preset, opening a new or existing chat, and launching a custom profile.
 * Each of these lazily ensures the local app server is reachable first (see
 * {@link #ensureServerReady(Project)}) except {@link #launchProfile}, which
 * is a standalone CLI invocation that never talks to the app server.
 */
final class CodexWorkflow {
    private CodexWorkflow() {
    }

    static void startServer(Project project) {
        ensureServerReady(project).whenComplete((ignored, error) -> {
            if (error != null) {
                CodexNotifications.error(project, "Could not connect to the Codex server", serverErrorMessage(error));
            }
        });
    }

    static void switchModel(Project project, ModelPreset preset) {
        ensureServerReady(project)
                .thenCompose(ignored -> CodexAppServerClient.getInstance().updateAllThreadSettings(preset))
                .thenRun(() -> CodexModelSwitcherSettings.getInstance().setLastPresetId(preset.id()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        CodexNotifications.error(project, "Model was not changed", CodexUtils.userMessage(error));
                    }
                });
    }

    static void startNewChat(Project project) {
        String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
        ModelPreset preset = ModelPreset.forId(CodexModelSwitcherSettings.getInstance().lastPresetId());
        ensureServerReady(project).whenComplete((ignored, error) -> {
            if (error != null) {
                CodexNotifications.error(project, "Could not create a new chat", CodexUtils.userMessage(error));
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
                        CodexNotifications.error(project, "No connection to Codex", serverErrorMessage(error));
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
        if (thread == null || !CodexThread.isValidId(thread.id())) {
            CodexNotifications.error(project, "Invalid chat identifier", "The plugin refused to open a chat with an unknown identifier.");
            return;
        }
        try {
            CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
            String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
            String command = authEnvironmentPrefix() + CodexUtils.shellQuote(CodexUtils.codexExecutable().toString())
                    + " --remote " + CodexUtils.shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl())
                    + remoteAuthOption()
                    + " resume " + CodexUtils.shellQuote(thread.id());
            ShellTerminalWidget terminal = TerminalView.getInstance(project)
                    .createLocalShellWidget(directory, "Codex — " + thread.shortId(), true, true);
            terminal.executeCommand(command);
        } catch (IOException error) {
            CodexNotifications.error(project, "Could not open the chat in a terminal", CodexUtils.userMessage(error));
        }
    }

    private static void openNewChatInTerminal(Project project, String directory, ModelPreset preset) {
        try {
            CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
            ModelPreset.requireModel(preset.model());
            ModelPreset.requireEffort(preset.effort());
            String command = authEnvironmentPrefix() + CodexUtils.shellQuote(CodexUtils.codexExecutable().toString())
                    + " --remote " + CodexUtils.shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl())
                    + remoteAuthOption()
                    + " --model " + CodexUtils.shellQuote(preset.model())
                    + " -c " + CodexUtils.shellQuote("model_reasoning_effort=\"" + preset.effort() + "\"");
            ShellTerminalWidget terminal = TerminalView.getInstance(project)
                    .createLocalShellWidget(directory, "Codex — new chat", true, true);
            terminal.executeCommand(command);
        } catch (IOException error) {
            CodexNotifications.error(project, "Could not open a new chat", CodexUtils.userMessage(error));
        }
    }

    static void launchProfile(Project project, LaunchProfile profile) {
        try {
            LaunchProfile.requireName(profile.name);
            LaunchProfile.requireArguments(profile.arguments);
            String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
            StringBuilder command = new StringBuilder(CodexUtils.shellQuote(CodexUtils.codexExecutable().toString()));
            for (String token : CodexUtils.tokenizeArguments(profile.arguments)) {
                command.append(' ').append(CodexUtils.shellQuote(token));
            }
            ShellTerminalWidget terminal = TerminalView.getInstance(project)
                    .createLocalShellWidget(directory, "Codex — " + profile.name, true, true);
            terminal.executeCommand(command.toString());
        } catch (IOException error) {
            CodexNotifications.error(project, "Could not launch the profile", CodexUtils.userMessage(error));
        }
    }

    private static String authEnvironmentPrefix() {
        Path tokenFile = CodexServerStarter.getInstance().authTokenFile();
        if (tokenFile == null) {
            return "";
        }
        return CodexServerStarter.AUTH_TOKEN_ENV
                + "=\"$(/bin/cat " + CodexUtils.shellQuote(tokenFile.toString()) + ")\" ";
    }

    private static String remoteAuthOption() {
        return CodexServerStarter.getInstance().authTokenFile() == null
                ? ""
                : " --remote-auth-token-env " + CodexServerStarter.AUTH_TOKEN_ENV;
    }

    private static String serverErrorMessage(Throwable error) {
        return "The Codex server did not become available within a few seconds. Open the \"Codex Server\" tab "
                + "at the bottom of the IDE to see the exact startup error. Technical reason: " + CodexUtils.userMessage(error);
    }
}
