package ru.roman.codexmodelswitcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
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

@Service(Service.Level.APP)
public final class CodexAppServerClient implements Disposable {
    private static final int THREAD_PAGE_SIZE = 100;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final Set<String> liveThreadIds = ConcurrentHashMap.newKeySet();
    private final Object connectionLock = new Object();

    private volatile WebSocket socket;
    private volatile CompletableFuture<Void> connection;
    private volatile String connectedEndpoint;

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

    public boolean isConnected() {
        CompletableFuture<Void> currentConnection = connection;
        return socket != null
                && CodexModelSwitcherSettings.getInstance().serverUrl().equals(connectedEndpoint)
                && currentConnection != null
                && currentConnection.isDone()
                && !currentConnection.isCompletedExceptionally()
                && !currentConnection.isCancelled();
    }

    public CompletableFuture<List<CodexThread>> listThreads() {
        return listThreads(null, new ArrayList<>());
    }

    public CompletableFuture<Void> updateAllThreadSettings(ModelPreset preset) {
        return listThreads().thenCompose(threads -> {
            Set<String> listedThreadIds = new HashSet<>();
            for (CodexThread thread : threads) {
                listedThreadIds.add(thread.id());
            }
            for (String liveThreadId : liveThreadIds) {
                if (listedThreadIds.add(liveThreadId)) {
                    threads.add(new CodexThread(liveThreadId, null, null, 0L));
                }
            }
            return updateThreadSettings(threads, preset, 0);
        });
    }

    private CompletableFuture<List<CodexThread>> listThreads(String cursor, List<CodexThread> threads) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("limit", THREAD_PAGE_SIZE);
        parameters.addProperty("sortDirection", "desc");
        parameters.addProperty("sortKey", "updated_at");
        if (cursor != null) {
            parameters.addProperty("cursor", cursor);
        }
        return request("thread/list", parameters).thenCompose(result -> {
            JsonArray data = result.getAsJsonArray("data");
            for (JsonElement element : data) {
                threads.add(CodexThread.fromJson(element.getAsJsonObject()));
            }
            String nextCursor = optionalString(result, "nextCursor");
            return nextCursor == null
                    ? CompletableFuture.completedFuture(threads)
                    : listThreads(nextCursor, threads);
        });
    }

    private CompletableFuture<Void> updateThreadSettings(List<CodexThread> threads, ModelPreset preset, int index) {
        if (index == threads.size()) {
            return CompletableFuture.completedFuture(null);
        }
        return updateThreadSettings(threads.get(index).id(), preset)
                .handle((ignored, error) -> {
                    if (error == null) {
                        return updateThreadSettings(threads, preset, index + 1);
                    }
                    if (isThreadNotFound(error)) {
                        return updateThreadSettings(threads, preset, index + 1);
                    }
                    return CompletableFuture.<Void>failedFuture(unwrap(error));
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<Void> updateThreadSettings(String threadId, ModelPreset preset) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("threadId", threadId);
        parameters.addProperty("model", preset.model());
        parameters.addProperty("effort", preset.effort());
        return request("thread/settings/update", parameters).thenApply(ignored -> null);
    }

    private static String optionalString(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : null;
    }

    private static boolean isThreadNotFound(Throwable error) {
        Throwable current = unwrap(error);
        String message = current.getMessage();
        return message != null && message.contains("thread not found");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private CompletableFuture<JsonObject> request(String method, JsonObject parameters) {
        return ensureConnected().thenCompose(ignored -> sendRequest(method, parameters));
    }

    private CompletableFuture<Void> ensureConnected() {
        String endpoint = CodexModelSwitcherSettings.getInstance().serverUrl();
        synchronized (connectionLock) {
            if (socket != null && connection != null && connection.isDone() && !connection.isCompletedExceptionally()
                    && endpoint.equals(connectedEndpoint)) {
                return connection;
            }
            if (connection != null && !connection.isDone() && endpoint.equals(connectedEndpoint)) {
                return connection;
            }
            closeSocket();
            connectedEndpoint = endpoint;
            connection = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .buildAsync(URI.create(endpoint), new ServerListener())
                    .thenCompose(newSocket -> {
                        socket = newSocket;
                        JsonObject clientInfo = new JsonObject();
                        clientInfo.addProperty("name", "intellij-codex-model-switcher");
                        clientInfo.addProperty("version", "0.5.0");
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
        return response.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
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
        long id = message.get("id").getAsLong();
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future == null) {
            return;
        }
        if (message.has("error")) {
            future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
            return;
        }
        JsonElement result = message.get("result");
        future.complete(result == null || result.isJsonNull() ? new JsonObject() : result.getAsJsonObject());
    }

    private void processNotification(JsonObject message) {
        String method = optionalString(message, "method");
        JsonObject parameters = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params")
                : null;
        if (parameters == null || method == null) {
            return;
        }
        JsonObject thread = parameters.has("thread") && parameters.get("thread").isJsonObject()
                ? parameters.getAsJsonObject("thread")
                : parameters;
        String threadId = optionalString(thread, "id");
        if (threadId == null) {
            threadId = optionalString(parameters, "threadId");
        }
        if (threadId == null) {
            return;
        }
        if ("thread/started".equals(method)) {
            liveThreadIds.add(threadId);
        } else if ("thread/closed".equals(method)
                || "thread/deleted".equals(method)
                || "thread/archived".equals(method)) {
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

    @Override
    public void dispose() {
        liveThreadIds.clear();
        closeSocket();
    }

    private final class ServerListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) {
                socket = null;
            }
            failPending(new CompletionException("Codex app server closed the connection: " + reason, null));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) {
                socket = null;
            }
            failPending(error);
        }
    }
}
