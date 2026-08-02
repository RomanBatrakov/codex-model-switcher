package ru.roman.codexmodelswitcher;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service(Service.Level.APP)
public final class CodexServerStarter implements Disposable {
    private static final List<Path> CODEX_LOCATIONS = List.of(
            Path.of("/opt/homebrew/bin/codex"),
            Path.of("/usr/local/bin/codex")
    );

    private ShellTerminalWidget serverTerminal;

    public static CodexServerStarter getInstance() {
        return ApplicationManager.getApplication().getService(CodexServerStarter.class);
    }

    public synchronized boolean start(Project project) throws IOException {
        CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
        Path codexExecutable = codexExecutable();
        if (serverTerminal != null) {
            try {
                serverTerminal.close();
            } catch (RuntimeException ignored) {
                // The old terminal may already have been closed by the user.
            }
            serverTerminal = null;
        }
        String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
        serverTerminal = TerminalView.getInstance(project).createLocalShellWidget(directory, "Codex Server");
        serverTerminal.executeCommand(shellQuote(codexExecutable.toString())
                + " app-server --listen "
                + shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl()));
        return true;
    }

    private static Path codexExecutable() throws IOException {
        return CODEX_LOCATIONS.stream()
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Не найден безопасный исполняемый файл Codex в /opt/homebrew/bin/codex или /usr/local/bin/codex."));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    @Override
    public void dispose() {
        ShellTerminalWidget terminal;
        synchronized (this) {
            terminal = serverTerminal;
            serverTerminal = null;
        }
        if (terminal != null) {
            ApplicationManager.getApplication().invokeLater(terminal::close);
        }
    }
}
