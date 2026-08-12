package ru.roman.codexmodelswitcher;

/**
 * A user-configured launch profile: a display name plus extra CLI arguments
 * appended to the trusted Codex executable (see {@link CodexUtils#codexExecutable()}).
 * A plain public bean so {@link CodexModelSwitcherSettings} can serialize a
 * list of these via IntelliJ's {@code PersistentStateComponent} XML
 * serializer, which requires public no-arg-constructible fields.
 */
final class LaunchProfile {
    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_ARGUMENTS_LENGTH = 256;

    public String name = "";
    public String arguments = "";

    static String validateName(String value) {
        if (value == null || value.isBlank()) {
            return "must not be empty.";
        }
        if (value.trim().length() > MAX_NAME_LENGTH) {
            return "is too long (maximum " + MAX_NAME_LENGTH + " characters).";
        }
        return null;
    }

    static String validateArguments(String value) {
        if (value == null || value.isBlank()) {
            return "must not be empty.";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ARGUMENTS_LENGTH) {
            return "is too long (maximum " + MAX_ARGUMENTS_LENGTH + " characters).";
        }
        if (!normalized.matches("[A-Za-z0-9._:/~+@=,\\s'\"-]+")) {
            return "contains invalid characters.";
        }
        return null;
    }

    static boolean isValidName(String value) {
        return validateName(value) == null;
    }

    static boolean isValidArguments(String value) {
        return validateArguments(value) == null;
    }

    static String requireName(String value) {
        String normalized = value == null ? "" : value.trim();
        String error = validateName(normalized);
        if (error != null) {
            throw new IllegalArgumentException("Invalid launch profile name: " + error);
        }
        return normalized;
    }

    static String requireArguments(String value) {
        String normalized = value == null ? "" : value.trim();
        String error = validateArguments(normalized);
        if (error != null) {
            throw new IllegalArgumentException("Invalid launch profile arguments: " + error);
        }
        return normalized;
    }
}
