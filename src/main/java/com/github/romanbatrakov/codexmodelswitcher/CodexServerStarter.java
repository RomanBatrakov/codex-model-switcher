package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.terminal.ui.TerminalWidget;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
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

/**
 * Starts, or safely adopts an already-running instance of, the local Codex
 * app server for one project, and owns the lifecycle of its capability token
 * and terminal. Each open project gets its own instance and its own server
 * process/port, so chats created from one project window are never rooted in
 * another project's directory.
 */
@Service(Service.Level.PROJECT)
public final class CodexServerStarter implements Disposable {
    static final String AUTH_TOKEN_ENV = "CODEX_MODEL_SWITCHER_TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PORT_PROBE_TIMEOUT_MILLIS = 300;
    private static final int MAX_PORT_SEARCH_ATTEMPTS = 50;

    private final Project project;
    private final String projectTag;

    private TerminalWidget serverTerminal;
    private Path authTokenFile;
    private String authToken;
    private URI resolvedEndpoint;

    CodexServerStarter(Project project) {
        this.project = project;
        this.projectTag = projectTag(project);
    }

    /** Returns the project-level singleton instance. */
    public static CodexServerStarter getInstance(Project project) {
        return project.getService(CodexServerStarter.class);
    }

    /** The actual address this project's server is listening on, or null if not started this session. */
    synchronized URI resolvedEndpoint() {
        return resolvedEndpoint;
    }

    /**
     * Starts a new local app-server process in a dedicated terminal for this
     * project. The configured address in Settings is only a preferred
     * starting port: if it is already occupied by another project's server
     * (or any other process), this searches forward for the next free port
     * instead of colliding with it. If the configured port is occupied by
     * this same project's own server from an earlier session (recognizable
     * by its project-tagged token file), that server is adopted instead of
     * spawning a duplicate.
     *
     * @return true once the server is started or an existing one was adopted
     * @throws IOException if no free/adoptable port could be found nearby,
     *         or the server process/terminal could not be started
     */
    public synchronized boolean start() throws IOException {
        URI baseEndpoint = CodexServerEndpoint.requireValid(CodexModelSwitcherSettings.getInstance().serverUrl());
        IOException lastConflict = null;
        for (int attempt = 0; attempt < MAX_PORT_SEARCH_ATTEMPTS; attempt++) {
            int port = baseEndpoint.getPort() + attempt;
            if (port > 65535) {
                break;
            }
            URI candidate = withPort(baseEndpoint, port);
            if (!isPortOccupied(candidate)) {
                return spawnServer(candidate);
            }
            if (adoptIfOwnServer(candidate)) {
                resolvedEndpoint = candidate;
                return true;
            }
            lastConflict = new IOException("Port " + port + " on " + candidate.getHost()
                    + " is already in use by another process. Stop it or change the app-server address in "
                    + "Settings, then try again.");
        }
        throw lastConflict != null ? lastConflict
                : new IOException("Could not find a free port near " + baseEndpoint.getPort()
                        + " for the Codex app server.");
    }

    private boolean spawnServer(URI endpoint) throws IOException {
        Path codexExecutable = CodexUtils.codexExecutable();
        Path newAuthTokenFile = createAuthTokenFile(projectTag);
        String newAuthToken;
        try {
            newAuthToken = Files.readString(newAuthTokenFile, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException error) {
            deleteQuietly(newAuthTokenFile);
            throw error;
        }
        if (serverTerminal != null) {
            try {
                serverTerminal.dispose();
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
            serverTerminal = CodexUtils.openTerminalSession(project, directory, "Codex Server");
            serverTerminal.sendCommandToExecute(CodexUtils.shellQuote(codexExecutable.toString())
                    + " app-server --listen "
                    + CodexUtils.shellQuote(endpoint.toString())
                    + " --ws-auth capability-token --ws-token-file "
                    + CodexUtils.shellQuote(authTokenFile.toString()));
            resolvedEndpoint = endpoint;
            return true;
        } catch (RuntimeException error) {
            if (serverTerminal != null) {
                try {
                    serverTerminal.dispose();
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
        refreshTokenIfNeeded();
        return authToken;
    }

    synchronized Path authTokenFile() {
        refreshTokenIfNeeded();
        return authTokenFile;
    }

    /**
     * The app-server can outlive an IntelliJ plugin reload, which drops this
     * service's in-memory token. Since the token file's name is tagged with
     * this project's id, re-scanning the last resolved port and matching the
     * tag recovers the right token without risking adoption of a different
     * project's still-running server.
     */
    private void refreshTokenIfNeeded() {
        if (authToken != null && authTokenFile != null && Files.isRegularFile(authTokenFile)) {
            return;
        }
        if (resolvedEndpoint == null) {
            return;
        }
        adoptIfOwnServer(resolvedEndpoint);
    }

    /**
     * Looks up the process bound to {@code endpoint}'s port (via {@code
     * lsof}) and, if it is a plugin-managed app-server whose token file is
     * tagged for this project, recovers its token. Returns false for a free
     * port, an unrelated process, or another project's server -- callers
     * fall back to treating the port as still occupied by someone else.
     */
    private boolean adoptIfOwnServer(URI endpoint) {
        Long pid = pidListeningOnPort(endpoint);
        if (pid == null) {
            return false;
        }
        return ProcessHandle.of(pid).map(process -> {
            String commandLine = process.info().commandLine().orElse("");
            String listenMarker = "--listen " + endpoint;
            if (!commandLine.contains("app-server")
                    || !commandLine.contains("--ws-auth capability-token")
                    || !commandLine.contains(listenMarker)) {
                return false;
            }
            Path tokenFile = tokenFileFromCommandLine(commandLine);
            if (tokenFile == null || !tokenFile.getFileName().toString().startsWith(tokenFilePrefix(projectTag))) {
                return false;
            }
            try {
                if (!isOwnerOnlyTokenFile(tokenFile)) {
                    return false;
                }
                String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (!token.matches("[A-Za-z0-9_-]{32,256}")) {
                    return false;
                }
                authTokenFile = tokenFile;
                authToken = token;
                return true;
            } catch (IOException ignored) {
                // The token file vanished or became unreadable between the
                // lsof lookup and this read; leave state untouched so the
                // caller treats the port as unavailable instead of silently
                // connecting with a stale/invalid token.
                return false;
            }
        }).orElse(false);
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

    private static URI withPort(URI base, int port) throws IOException {
        try {
            return new URI(base.getScheme(), null, base.getHost(), port, null, null, null);
        } catch (URISyntaxException error) {
            throw new IOException("Could not build an app-server address for port " + port, error);
        }
    }

    /** A short, filesystem-safe id unique to this project's location, used to tag this project's own token files. */
    private static String projectTag(Project project) {
        String sanitized = project.getLocationHash().replaceAll("[^A-Za-z0-9]", "");
        return sanitized.isEmpty() ? "p" : sanitized;
    }

    private static String tokenFilePrefix(String projectTag) {
        return "codex-model-switcher-" + projectTag + "-";
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
     * out from under adoptIfOwnServer().
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
     * line -- that's the same signal adoptIfOwnServer() uses to recognize a
     * live server, just inverted. Not scoped to this project's tag: any
     * project's stale files are equally safe to sweep here.
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

    private static Path createAuthTokenFile(String projectTag) throws IOException {
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
        Path tokenFile = Files.createTempFile(directory, tokenFilePrefix(projectTag), ".token");
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

    /** Closes the server's terminal (if this instance opened one) and deletes its token file. */
    @Override
    public void dispose() {
        TerminalWidget terminal;
        synchronized (this) {
            terminal = serverTerminal;
            serverTerminal = null;
            deleteAuthTokenFile();
        }
        if (terminal != null) {
            ApplicationManager.getApplication().invokeLater(terminal::dispose);
        }
    }
}
