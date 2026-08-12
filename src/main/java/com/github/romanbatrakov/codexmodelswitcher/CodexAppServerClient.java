package com.github.romanbatrakov.codexmodelswitcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * WebSocket client for the local Codex app server's JSON-RPC protocol.
 * Owns the single shared connection, tracks which threads are currently
 * loaded, and exposes the operations the plugin needs: listing chats and
 * updating model/reasoning settings on every active thread.
 */
@Service(Service.Level.APP)
public final class CodexAppServerClient implements Disposable {
    private static final int MAX_THREAD_COUNT = 10_000;
    private static final int MAX_TEXT_MESSAGE_LENGTH = 1_048_576;
    private static final int MAX_SESSION_FILES = 10_000;
    private static final int MAX_SESSION_SCAN_LINES = 96;
    private static final int MAX_SESSION_SCAN_CHARS = 512 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final Set<String> liveThreadIds = ConcurrentHashMap.newKeySet();
    private final Object connectionLock = new Object();
    private final Object modelUpdateLock = new Object();

    private CompletableFuture<Void> modelUpdateTail = CompletableFuture.completedFuture(null);

    private volatile WebSocket socket;
    private volatile CompletableFuture<Void> connection;
    private volatile String connectedEndpoint;

    /** Returns the application-level singleton instance. */
    public static CodexAppServerClient getInstance() {
        return ApplicationManager.getApplication().getService(CodexAppServerClient.class);
    }

    /**
     * Opens the WebSocket and completes only after the app server accepted the
     * protocol initialization. This is the readiness check used before every
     * user action.
     */
    public CompletableFuture<Void> connect() {
        return ensureConnected();
    }

    /**
     * Reads saved chats directly from local session transcript files rather
     * than the app server's {@code thread/list} endpoint, which can become
     * very slow when {@code ~/.codex/sessions} contains large transcripts.
     * Only the stable id and a short preview are needed here, both available
     * in the first records of each local JSONL session file.
     */
    public CompletableFuture<List<CodexThread>> listThreads() {
        return CompletableFuture.supplyAsync(this::listThreadsFromDisk);
    }

    /**
     * Applies {@code preset}'s model and reasoning effort to every thread the
     * app server currently has loaded, plus the last-opened thread. Calls are
     * serialized through {@link #modelUpdateTail} so overlapping clicks don't
     * race on the same socket.
     *
     * @param preset the model/effort to apply; must not be null
     */
    public CompletableFuture<Void> updateAllThreadSettings(ModelPreset preset) {
        if (preset == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No model preset was specified."));
        }
        try {
            ModelPreset.requireModel(preset.model());
            ModelPreset.requireEffort(preset.effort());
        } catch (IllegalArgumentException error) {
            return CompletableFuture.failedFuture(error);
        }
        synchronized (modelUpdateLock) {
            // A double click used to start two request chains on the same
            // socket. Keep model changes ordered and let the next click run
            // even when the previous one failed.
            modelUpdateTail = modelUpdateTail
                    .handle((ignored, _) -> null)
                    .thenCompose(ignored -> updateAllThreadSettingsNow(preset));
            return modelUpdateTail;
        }
    }

    private CompletableFuture<Void> updateAllThreadSettingsNow(ModelPreset preset) {
        // Refresh the plugin's own connection before a model update. The TUI
        // may keep the app server busy for a while, and a stale plugin socket
        // can otherwise wait until its short request deadline expires.
        return reconnect().thenCompose(ignored -> listLoadedThreadIds()).thenCompose(loadedThreadIds -> {
            // Only loaded threads can accept thread/settings/update. Do not call
            // the historical thread/list endpoint here: it scans all transcripts
            // and can time out even while the active-chat endpoint is healthy.
            Set<String> targetThreadIds = new HashSet<>();
            targetThreadIds.addAll(loadedThreadIds);
            targetThreadIds.addAll(liveThreadIds);
            String lastThreadId = CodexModelSwitcherSettings.getInstance().lastThreadId();
            if (CodexThread.isValidId(lastThreadId)) {
                targetThreadIds.add(lastThreadId);
            }
            if (targetThreadIds.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The Codex server has no active chats. Open a chat via Codex or the \"New chat\" button first."));
            }
            return updateThreadSettings(new ArrayList<>(targetThreadIds), preset, 0);
        });
    }

    /**
     * Returns threads currently loaded by the app server. The regular
     * {@code thread/list} endpoint contains historical records as well, most
     * of which cannot accept settings updates until they are loaded. This
     * endpoint is what makes switching work for a Codex CLI chat that was
     * opened outside the plugin or before the plugin connected.
     */
    private CompletableFuture<List<String>> listLoadedThreadIds() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("limit", MAX_THREAD_COUNT);
        return request("thread/loaded/list", parameters).thenApply(result -> {
            if (!result.has("data") || !result.get("data").isJsonArray()) {
                throw new IllegalStateException("Codex app server returned an invalid loaded-thread list.");
            }
            List<String> loadedThreadIds = new ArrayList<>();
            for (JsonElement element : result.getAsJsonArray("data")) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                String threadId = element.getAsString();
                if (CodexThread.isValidId(threadId)) {
                    loadedThreadIds.add(threadId);
                }
            }
            return loadedThreadIds;
        });
    }

    private CompletableFuture<Void> updateThreadSettings(List<String> threadIds, ModelPreset preset, int index) {
        if (index == threadIds.size()) {
            return CompletableFuture.completedFuture(null);
        }
        return updateThreadSettings(threadIds.get(index), preset).handle((ignored, error) -> {
            if (error == null) {
                return updateThreadSettings(threadIds, preset, index + 1);
            }
            if (isThreadNotFound(error)) {
                return updateThreadSettings(threadIds, preset, index + 1);
            }
            return CompletableFuture.<Void>failedFuture(unwrap(error));
        }).thenCompose(Function.identity());
    }

    private List<CodexThread> listThreadsFromDisk() {
        Path sessionsDirectory = codexSessionsDirectory();
        if (!Files.isDirectory(sessionsDirectory)) {
            return List.of();
        }
        Map<String, CodexThread> threadsById = new HashMap<>();
        try (var paths = Files.walk(sessionsDirectory, 4)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                 .limit(MAX_SESSION_FILES)
                 .forEach(path -> readThreadFile(path).ifPresent(thread -> threadsById.put(thread.id(), thread)));
        } catch (IOException | java.io.UncheckedIOException error) {
            throw new CompletionException("Could not read the local Codex chat list.", error);
        }
        return threadsById.values()
                          .stream()
                          .sorted(Comparator.comparingLong(CodexThread::updatedAt)
                                            .reversed()
                                            .thenComparing(CodexThread::id, Comparator.reverseOrder()))
                          .toList();
    }

    private static java.util.Optional<CodexThread> readThreadFile(Path path) {
        JsonObject metadata = null;
        try {
            String preview = null;
            String fallbackPreview = null;
            int scannedCharacters = 0;
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                for (int lineNumber = 0; lineNumber < MAX_SESSION_SCAN_LINES; lineNumber++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    scannedCharacters += line.length();
                    if (scannedCharacters > MAX_SESSION_SCAN_CHARS) {
                        break;
                    }
                    JsonObject record;
                    try {
                        record = JsonParser.parseString(line).getAsJsonObject();
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    if (lineNumber == 0) {
                        if (!"session_meta".equals(optionalString(record, "type"))) {
                            return java.util.Optional.empty();
                        }
                        metadata = record;
                        JsonObject payload = objectValue(metadata, "payload");
                        String id = firstString(payload, "session_id", "id");
                        if (!CodexThread.isValidId(id)) {
                            return java.util.Optional.empty();
                        }
                    }
                    String eventPreview = eventUserPreview(record);
                    if (eventPreview != null) {
                        preview = eventPreview;
                        break;
                    }
                    if (fallbackPreview == null) {
                        fallbackPreview = previewFromRecord(record);
                    }
                }
            }
            if (metadata == null) {
                return java.util.Optional.empty();
            }
            JsonObject payload = objectValue(metadata, "payload");
            String id = firstString(payload, "session_id", "id");
            if (!CodexThread.isValidId(id)) {
                return java.util.Optional.empty();
            }
            if (preview == null) {
                preview = fallbackPreview;
            }
            long updatedAt = Files.getLastModifiedTime(path).toMillis() / 1000L;
            return java.util.Optional.of(new CodexThread(id, null, preview, updatedAt));
        } catch (IOException | RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static String previewFromRecord(JsonObject record) {
        JsonObject payload = objectValue(record, "payload");
        String eventType = optionalString(payload, "type");
        if ("user_message".equals(eventType)) {
            return firstString(payload, "message", "text");
        }
        if ("message".equals(eventType) && "user".equals(optionalString(payload, "role"))) {
            if (payload.has("content") && payload.get("content").isJsonArray()) {
                for (JsonElement element : payload.getAsJsonArray("content")) {
                    if (element.isJsonObject()) {
                        String text = firstString(element.getAsJsonObject(), "text", "input_text");
                        if (text != null) {
                            return text;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String eventUserPreview(JsonObject record) {
        JsonObject payload = objectValue(record, "payload");
        return "event_msg".equals(optionalString(record, "type")) &&
            "user_message".equals(optionalString(payload, "type")) ? firstString(payload, "message", "text") : null;
    }

    private static Path codexSessionsDirectory() {
        String codexHome = System.getenv("CODEX_HOME");
        Path home = codexHome == null || codexHome.isBlank() ? Path.of(System.getProperty("user.home"), ".codex") :
            Path.of(codexHome);
        return home.resolve("sessions").normalize();
    }

    private static JsonObject objectValue(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name) : new JsonObject();
    }

    private static String firstString(JsonObject value, String... names) {
        for (String name : names) {
            String result = optionalString(value, name);
            if (result != null && !result.isBlank()) {
                return result;
            }
        }
        return null;
    }

    private CompletableFuture<Void> updateThreadSettings(String threadId, ModelPreset preset) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("threadId", threadId);
        parameters.addProperty("model", preset.model());
        parameters.addProperty("effort", preset.effort());
        return request("thread/settings/update", parameters).thenApply(ignored -> null);
    }

    private static String optionalString(JsonObject value, String name) {
        if (!value.has(name) || value.get(name).isJsonNull() || !value.get(name).isJsonPrimitive()) {
            return null;
        }
        try {
            return value.get(name).getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isThreadNotFound(Throwable error) {
        Throwable current = unwrap(error);
        String message = current.getMessage();
        return message != null && message.contains("thread not found");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException) &&
            current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private CompletableFuture<JsonObject> request(String method, JsonObject parameters) {
        return ensureConnected().thenCompose(ignored -> sendRequest(method, parameters));
    }

    private CompletableFuture<Void> ensureConnected() {
        String endpoint = CodexModelSwitcherSettings.getInstance().serverUrl();
        final URI endpointUri;
        try {
            endpointUri = CodexServerEndpoint.requireValid(endpoint);
        } catch (IllegalArgumentException error) {
            return CompletableFuture.failedFuture(error);
        }
        synchronized (connectionLock) {
            if (socket != null && connection != null && connection.isDone() && !connection.isCompletedExceptionally() &&
                endpoint.equals(connectedEndpoint)) {
                return connection;
            }
            if (connection != null && !connection.isDone() && endpoint.equals(connectedEndpoint)) {
                return connection;
            }
            closeSocket();
            connectedEndpoint = endpoint;
            WebSocket.Builder webSocketBuilder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(1));
            String authToken = CodexServerStarter.getInstance().authToken();
            if (authToken != null && !authToken.isBlank()) {
                webSocketBuilder.header("Authorization", "Bearer " + authToken);
            }
            connection = webSocketBuilder.buildAsync(endpointUri, new ServerListener()).thenCompose(newSocket -> {
                socket = newSocket;
                JsonObject clientInfo = new JsonObject();
                clientInfo.addProperty("name", "intellij-codex-model-switcher");
                clientInfo.addProperty("version", CodexUtils.pluginVersion());
                JsonObject parameters = new JsonObject();
                parameters.add("clientInfo", clientInfo);
                JsonObject capabilities = new JsonObject();
                capabilities.addProperty("experimentalApi", true);
                parameters.add("capabilities", capabilities);
                return sendRequest("initialize", parameters).thenApply(ignored -> null);
            });
            return connection;
        }
    }

    private CompletableFuture<Void> reconnect() {
        synchronized (connectionLock) {
            closeSocket();
            connection = null;
            connectedEndpoint = null;
        }
        return ensureConnected();
    }

    private CompletableFuture<JsonObject> sendRequest(String method, JsonObject parameters) {
        WebSocket activeSocket = socket;
        if (activeSocket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Codex app server is not connected"));
        }
        long id = requestIds.incrementAndGet();
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", parameters);
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        pendingRequests.put(id, response);
        activeSocket.sendText(request.toString(), true).whenComplete((ignored, failure) -> {
            if (failure != null) {
                pendingRequests.remove(id);
                response.completeExceptionally(failure);
            }
        });
        CompletableFuture<JsonObject> timedResponse =
            response.orTimeout(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        timedResponse.whenComplete((ignored, _) -> pendingRequests.remove(id, response));
        return timedResponse;
    }

    private void processMessage(String text) {
        JsonObject message;
        try {
            message = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }
        if (message.has("method") && !message.get("method").isJsonNull()) {
            processNotification(message);
        }
        if (!message.has("id") || message.get("id").isJsonNull()) {
            return;
        }
        final long id;
        try {
            id = message.get("id").getAsLong();
        } catch (RuntimeException ignored) {
            return;
        }
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future == null) {
            return;
        }
        if (message.has("error")) {
            future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
            return;
        }
        JsonElement result = message.get("result");
        if (result == null || result.isJsonNull()) {
            future.complete(new JsonObject());
        } else if (result.isJsonObject()) {
            future.complete(result.getAsJsonObject());
        } else {
            future.completeExceptionally(new IllegalStateException("Codex app server returned an invalid response."));
        }
    }

    private void processNotification(JsonObject message) {
        String method = optionalString(message, "method");
        JsonObject parameters =
            message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : null;
        if (parameters == null || method == null) {
            return;
        }
        JsonObject thread =
            parameters.has("thread") && parameters.get("thread").isJsonObject() ? parameters.getAsJsonObject("thread") :
                parameters;
        String threadId = optionalString(thread, "id");
        if (threadId == null) {
            threadId = optionalString(parameters, "threadId");
        }
        if (threadId == null) {
            return;
        }
        if ("thread/started".equals(method)) {
            if (CodexThread.isValidId(threadId) && liveThreadIds.size() < MAX_THREAD_COUNT) {
                liveThreadIds.add(threadId);
            }
        } else if ("thread/closed".equals(method) || "thread/deleted".equals(method) ||
            "thread/archived".equals(method)) {
            liveThreadIds.remove(threadId);
        }
    }

    private void closeSocket() {
        WebSocket oldSocket = socket;
        socket = null;
        if (oldSocket != null) {
            oldSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
        }
        failPending(new IllegalStateException("Codex app server connection was closed"));
    }

    private void failPending(Throwable failure) {
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pendingRequests.entrySet()) {
            if (pendingRequests.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().completeExceptionally(failure);
            }
        }
    }

    /** Closes the socket and fails any in-flight requests when the plugin/IDE shuts down. */
    @Override
    public void dispose() {
        liveThreadIds.clear();
        closeSocket();
    }

    /** Buffers and dispatches incoming JSON-RPC frames, and clears request state on disconnect. */
    private final class ServerListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        /** Reassembles a (possibly multi-frame) text message and hands it to {@link #processMessage}. */
        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (data.length() > MAX_TEXT_MESSAGE_LENGTH - textBuffer.length()) {
                textBuffer.setLength(0);
                failPending(new IllegalStateException("Codex app server message is too large."));
                webSocket.sendClose(1009, "Message too large");
                return CompletableFuture.completedFuture(null);
            }
            textBuffer.append(data);
            if (last) {
                processMessage(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        /** Clears the dead socket reference and fails any requests still awaiting a response. */
        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) {
                socket = null;
            }
            failPending(new CompletionException("Codex app server closed the connection: " + reason, null));
            return CompletableFuture.completedFuture(null);
        }

        /** Same cleanup as {@link #onClose}, triggered by a transport-level failure instead. */
        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) {
                socket = null;
            }
            failPending(error);
        }
    }
}
