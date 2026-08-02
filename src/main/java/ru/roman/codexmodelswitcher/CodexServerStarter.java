package ru.roman.codexmodelswitcher;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.util.EnumSet;
import java.security.SecureRandom;

@Service(Service.Level.APP)
public final class CodexServerStarter implements Disposable {
    static final String AUTH_TOKEN_ENV = "CODEX_MODEL_SWITCHER_TOKEN";
    private static final List<Path> CODEX_LOCATIONS = List.of(
            Path.of("/opt/homebrew/bin/codex"),
            Path.of("/usr/local/bin/codex")
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShellTerminalWidget serverTerminal;
    private Path authTokenFile;
    private String authToken;

    public static CodexServerStarter getInstance() {
        return ApplicationManager.getApplication().getService(CodexServerStarter.class);
    }

    public synchronized boolean start(Project project) throws IOException {
        CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
        Path codexExecutable = codexExecutable();
        Path newAuthTokenFile = createAuthTokenFile();
        String newAuthToken = Files.readString(newAuthTokenFile, StandardCharsets.UTF_8).trim();
        if (serverTerminal != null) {
            try {
                serverTerminal.close();
            } catch (RuntimeException ignored) {
                // The old terminal may already have been closed by the user.
            }
            serverTerminal = null;
        }
        deleteAuthTokenFile();
        authTokenFile = newAuthTokenFile;
        authToken = newAuthToken;
        String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
        serverTerminal = TerminalView.getInstance(project).createLocalShellWidget(directory, "Codex Server");
        serverTerminal.executeCommand(shellQuote(codexExecutable.toString())
                + " app-server --listen "
                + shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl())
                + " --ws-auth capability-token --ws-token-file "
                + shellQuote(authTokenFile.toString()));
        return true;
    }

    synchronized String authToken() {
        return authToken;
    }

    synchronized Path authTokenFile() {
        return authTokenFile;
    }

    private static Path createAuthTokenFile() throws IOException {
        Path tokenFile = Files.createTempFile("codex-model-switcher-", ".token");
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tokenFile, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The default permissions of createTempFile are already owner-only
            // on supported local file systems.
        }
        return tokenFile;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void deleteAuthTokenFile() {
        if (authTokenFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(authTokenFile);
        } catch (IOException ignored) {
            // A stale owner-only token file is harmless and can be cleaned up
            // on the next start.
        }
        authTokenFile = null;
        authToken = null;
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
            deleteAuthTokenFile();
        }
        if (terminal != null) {
            ApplicationManager.getApplication().invokeLater(terminal::close);
        }
    }
}
