package ru.roman.codexmodelswitcher;

import java.net.InetAddress;
import java.net.URI;

/**
 * Validates the local WebSocket endpoint used by the Codex app server.
 *
 * <p>The plugin starts an unauthenticated local process, so accepting an
 * arbitrary host here would allow the setting to turn the plugin into a
 * client for a remote endpoint or make the server bind to a public address.
 */
final class CodexServerEndpoint {
    static final String DEFAULT_URL = "ws://127.0.0.1:4500";

    private CodexServerEndpoint() {
    }

    static URI requireValid(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("The app-server address has an invalid format." + example(), error);
        }

        String scheme = uri.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("The app-server address must use ws:// or wss://." + example());
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "The app-server address must not contain a login, query parameters, or a fragment." + example());
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty() && !"/".equals(rawPath)) {
            throw new IllegalArgumentException("The app-server address must not contain a path." + example());
        }
        String host = uri.getHost();
        if (host == null || !isLoopbackHost(host)) {
            throw new IllegalArgumentException(
                    "Only a local app-server address is allowed (localhost or loopback)." + example());
        }
        if (uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Specify an app-server port between 1 and 65535." + example());
        }
        return uri;
    }

    private static String example() {
        return " Example: " + DEFAULT_URL;
    }

    static boolean isValid(String rawValue) {
        try {
            requireValid(rawValue);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String normalizeOrDefault(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        return isValid(value) ? value : DEFAULT_URL;
    }

    private static boolean isLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        // Do not resolve arbitrary names. Only numeric IPv4/IPv6 literals are
        // considered here, which keeps validation local and deterministic.
        if (!host.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.io.IOException error) {
            return false;
        }
    }
}
