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
import java.util.Set;
import java.security.SecureRandom;

@Service(Service.Level.APP)
public final class CodexServerStarter implements Disposable {
    static final String AUTH_TOKEN_ENV = "CODEX_MODEL_SWITCHER_TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShellTerminalWidget serverTerminal;
    private Path authTokenFile;
    private String authToken;

    public static CodexServerStarter getInstance() {
        return ApplicationManager.getApplication().getService(CodexServerStarter.class);
    }

    public synchronized boolean start(Project project) throws IOException {
        CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
        Path codexExecutable = CodexUtils.codexExecutable();
        Path newAuthTokenFile = createAuthTokenFile();
        String newAuthToken;
        try {
            newAuthToken = Files.readString(newAuthTokenFile, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException error) {
            deleteQuietly(newAuthTokenFile);
            throw error;
        }
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
        try {
            String directory = project.getBasePath() == null ? System.getProperty("user.dir") : project.getBasePath();
            serverTerminal = TerminalView.getInstance(project).createLocalShellWidget(directory, "Codex Server");
            serverTerminal.executeCommand(CodexUtils.shellQuote(codexExecutable.toString())
                    + " app-server --listen "
                    + CodexUtils.shellQuote(CodexModelSwitcherSettings.getInstance().serverUrl())
                    + " --ws-auth capability-token --ws-token-file "
                    + CodexUtils.shellQuote(authTokenFile.toString()));
            return true;
        } catch (IOException | RuntimeException error) {
            if (serverTerminal != null) {
                try {
                    serverTerminal.close();
                } catch (RuntimeException ignored) {
                    // The terminal may already have been closed by the IDE.
                }
                serverTerminal = null;
            }
            deleteAuthTokenFile();
            throw error;
        }
    }

    synchronized String authToken() {
        restoreTokenFromRunningServer();
        return authToken;
    }

    synchronized Path authTokenFile() {
        restoreTokenFromRunningServer();
        return authTokenFile;
    }

    /**
     * The app-server can outlive an IntelliJ plugin reload. In that case the
     * in-memory token is gone, while the local Codex process still advertises
     * its owner-only token file in its command line. Recovering that token
     * keeps an existing localhost server usable after an IDE/plugin restart.
     */
    private void restoreTokenFromRunningServer() {
        if (authToken != null && authTokenFile != null && Files.isRegularFile(authTokenFile)) {
            return;
        }
        String endpoint = CodexModelSwitcherSettings.getInstance().serverUrl();
        String listenMarker = "--listen " + endpoint;
        try {
            for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
                String commandLine = process.info().commandLine().orElse("");
                if (!commandLine.contains("app-server")
                        || !commandLine.contains("--ws-auth capability-token")
                        || !commandLine.contains(listenMarker)) {
                    continue;
                }
                Path tokenFile = tokenFileFromCommandLine(commandLine);
                if (tokenFile == null || !isOwnerOnlyTokenFile(tokenFile)) {
                    continue;
                }
                String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (token.matches("[A-Za-z0-9_-]{32,256}")) {
                    authTokenFile = tokenFile;
                    authToken = token;
                    return;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // If process inspection is unavailable, the normal server-start
            // path still reports the connection error to the user.
        }
    }

    private static Path tokenFileFromCommandLine(String commandLine) {
        String[] arguments = commandLine.trim().split("\\s+");
        for (int index = 0; index + 1 < arguments.length; index++) {
            if ("--ws-token-file".equals(arguments[index])) {
                try {
                    String rawPath = unquote(arguments[index + 1]);
                    Path path = Path.of(rawPath);
                    return path.isAbsolute() ? path : null;
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isOwnerOnlyTokenFile(Path tokenFile) throws IOException {
        if (!Files.isRegularFile(tokenFile) || !Files.isReadable(tokenFile) || Files.size(tokenFile) > 256) {
            return false;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokenFile);
            return permissions.stream().noneMatch(permission -> permission.name().startsWith("GROUP_")
                    || permission.name().startsWith("OTHERS_"));
        } catch (UnsupportedOperationException ignored) {
            return true;
        }
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A newly-created owner-only token file is safe to leave for the
            // operating system's temporary-file cleanup if deletion races.
        }
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
