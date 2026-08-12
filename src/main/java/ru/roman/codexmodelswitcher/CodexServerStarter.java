package ru.roman.codexmodelswitcher;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;

@Service(Service.Level.APP)
public final class CodexServerStarter implements Disposable {
    static final String AUTH_TOKEN_ENV = "CODEX_MODEL_SWITCHER_TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PORT_PROBE_TIMEOUT_MILLIS = 300;

    private ShellTerminalWidget serverTerminal;
    private Path authTokenFile;
    private String authToken;

    public static CodexServerStarter getInstance() {
        return ApplicationManager.getApplication().getService(CodexServerStarter.class);
    }

    public synchronized boolean start(Project project) throws IOException {
        URI endpoint = CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
        if (isPortOccupied(endpoint)) {
            // Something is already listening -- most likely this plugin's own
            // server from an earlier session that this window lost track of.
            // Starting a second app-server on the same port would just crash
            // with "Address already in use"; try to adopt the existing one
            // instead of blindly spawning a duplicate.
            restoreTokenFromRunningServer();
            if (authToken != null && authTokenFile != null && Files.isRegularFile(authTokenFile)) {
                return true;
            }
            throw new IOException("Port " + endpoint.getPort() + " on " + endpoint.getHost()
                    + " is already in use by another process. Stop it or change the app-server address in "
                    + "Settings, then try again.");
        }
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
     *
     * <p>Looks up the specific process bound to the configured port (via
     * {@code lsof}) instead of scanning every process for a text match: with
     * several stale attempts lying around, a substring match could recover
     * the wrong process's token, and command-line inspection is otherwise
     * unable to tell which of several matching processes actually holds the
     * port.
     */
    private void restoreTokenFromRunningServer() {
        if (authToken != null && authTokenFile != null && Files.isRegularFile(authTokenFile)) {
            return;
        }
        String endpoint = CodexModelSwitcherSettings.getInstance().serverUrl();
        final URI endpointUri;
        try {
            endpointUri = CodexServerEndpoint.requireValid(endpoint);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        Long pid = pidListeningOnPort(endpointUri);
        if (pid == null) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(process -> {
            String commandLine = process.info().commandLine().orElse("");
            String listenMarker = "--listen " + endpoint;
            if (!commandLine.contains("app-server")
                    || !commandLine.contains("--ws-auth capability-token")
                    || !commandLine.contains(listenMarker)) {
                return;
            }
            Path tokenFile = tokenFileFromCommandLine(commandLine);
            if (tokenFile == null) {
                return;
            }
            try {
                if (!isOwnerOnlyTokenFile(tokenFile)) {
                    return;
                }
                String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (token.matches("[A-Za-z0-9_-]{32,256}")) {
                    authTokenFile = tokenFile;
                    authToken = token;
                }
            } catch (IOException ignored) {
                // The token file vanished or became unreadable between the
                // lsof lookup and this read; leave state untouched so the
                // caller reports a clear "port in use" error instead of
                // silently connecting with a stale/invalid token.
            }
        });
    }

    /**
     * Resolves the PID of the process currently listening on the endpoint's
     * port via {@code lsof}, the only reliable way to answer "who owns this
     * socket" without a JDK API for it. Best-effort: any failure (lsof
     * missing, timeout, unexpected output) is treated as "unknown" rather
     * than propagated, since callers already handle a null/absent result by
     * falling through to a clear user-facing error.
     */
    private static Long pidListeningOnPort(URI endpoint) {
        try {
            Process lsof = new ProcessBuilder(
                    "lsof", "-nP", "-iTCP:" + endpoint.getPort(), "-sTCP:LISTEN", "-t")
                    .redirectErrorStream(true)
                    .start();
            String firstLine;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(lsof.getInputStream(), StandardCharsets.UTF_8))) {
                firstLine = reader.readLine();
            }
            lsof.waitFor(1, TimeUnit.SECONDS);
            lsof.destroyForcibly();
            return firstLine == null || firstLine.isBlank() ? null : Long.parseLong(firstLine.trim());
        } catch (IOException | RuntimeException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean isPortOccupied(URI endpoint) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), PORT_PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException ignored) {
            return false;
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

    /**
     * Token files live under the IDE's own per-installation system directory
     * rather than the OS temp directory. A long-running server can easily
     * outlive several days of IDE/plugin restarts, and the OS temp directory
     * is subject to periodic cleanup of files that haven't been touched in a
     * few days -- silently invalidating a still-running server's credentials
     * out from under restoreTokenFromRunningServer().
     */
    private static Path tokenDirectory() {
        return Path.of(PathManager.getSystemPath(), "codex-model-switcher");
    }

    /**
     * Unlike the OS temp directory, nothing else ever cleans this directory
     * up, so a token file whose owning process died without running
     * {@link #dispose()} (IDE crash, force quit) would sit there forever.
     * Run on every fresh server start: a file is safe to delete only if no
     * currently running app-server process references it in its command
     * line -- that's the same signal restoreTokenFromRunningServer() uses to
     * recognize a live server, just inverted.
     */
    private static void cleanupStaleTokenFiles(Path directory) {
        try (var files = Files.list(directory)) {
            List<Path> candidates = files
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("codex-model-switcher-") && name.endsWith(".token");
                    })
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            List<String> liveCommandLines = ProcessHandle.allProcesses()
                    .map(process -> process.info().commandLine().orElse(""))
                    .filter(commandLine -> commandLine.contains("app-server"))
                    .toList();
            for (Path candidate : candidates) {
                String candidatePath = candidate.toString();
                boolean referenced = liveCommandLines.stream().anyMatch(commandLine -> commandLine.contains(candidatePath));
                if (!referenced) {
                    deleteQuietly(candidate);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort housekeeping; leftover token files are harmless
            // small owner-only files, not a correctness issue.
        }
    }

    private static Path createAuthTokenFile() throws IOException {
        Path directory = tokenDirectory();
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Best-effort on file systems without POSIX permissions.
        }
        cleanupStaleTokenFiles(directory);
        Path tokenFile = Files.createTempFile(directory, "codex-model-switcher-", ".token");
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
