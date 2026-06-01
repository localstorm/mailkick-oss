package co.kuznetsov.mailkick.model.config;

import co.kuznetsov.mailkick.model.MailKickConfig;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.zone.ZoneRulesException;
import java.util.Set;

/**
 * Validates a {@link MailKickConfig} instance, throwing {@link IllegalArgumentException}
 * with a descriptive message on the first constraint violation encountered.
 */
public final class MailKickConfigValidator {

    private MailKickConfigValidator() {
    }

    /**
     * Validates the given {@link MailKickConfig}, checking all required fields and
     * cross-field constraints. Throws {@link IllegalArgumentException} on the first
     * violation found.
     *
     * @param config         the config to validate; must not be null
     * @param knownToolNames the set of tool names registered in the tool registry;
     *                       any name in extraTools or disallowTools not in this set fails validation
     * @throws IllegalArgumentException if any validation constraint is violated
     */
    public static void validate(MailKickConfig config, Set<String> knownToolNames) {
        validate(config);
        for (String promptName : config.getPrompts().keySet()) {
            for (String tool : config.getExtraToolsForPrompt(promptName)) {
                if (!knownToolNames.contains(tool)) {
                    throw new IllegalArgumentException(
                        "Unknown tool '" + tool + "' in extraTools for prompt '" + promptName + "'");
                }
            }
            for (String tool : config.getDisallowedToolsForPrompt(promptName)) {
                if (!knownToolNames.contains(tool)) {
                    throw new IllegalArgumentException(
                        "Unknown tool '" + tool + "' in disallowTools for prompt '" + promptName + "'");
                }
            }
        }
    }

    /**
     * Validates the given {@link MailKickConfig}, checking all required fields and
     * cross-field constraints. Throws {@link IllegalArgumentException} on the first
     * violation found.
     *
     * @param config the config to validate; must not be null
     * @throws IllegalArgumentException if any validation constraint is violated
     */
    public static void validate(MailKickConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        if (isBlank(config.getModel())) {
            throw new IllegalArgumentException("model must not be blank");
        }

        if (isBlank(config.getTimezone())) {
            throw new IllegalArgumentException("timezone must not be blank");
        }

        try {
            ZoneId.of(config.getTimezone());
        } catch (ZoneRulesException e) {
            throw new IllegalArgumentException("Invalid timezone: " + config.getTimezone());
        }

        if (config.getMaxEmailSizeTokens() <= 0) {
            throw new IllegalArgumentException("maxEmailSizeTokens must be positive");
        }

        if (config.getPrompts() == null || config.getPrompts().isEmpty()) {
            throw new IllegalArgumentException("prompts map must not be empty");
        }

        if (isBlank(config.getDefaultPromptName())) {
            throw new IllegalArgumentException("defaultPromptName must not be blank");
        }

        String defaultName = config.getDefaultPromptName();
        if (!config.getPrompts().containsKey(defaultName)) {
            throw new IllegalArgumentException(
                    "defaultPromptName '" + defaultName + "' not found in prompts map");
        }

        if (!isBlank(config.getDigestTime())) {
            String digestTime = config.getDigestTime();
            if (!digestTime.matches("^\\d{2}:\\d{2}$")) {
                throw new IllegalArgumentException(
                        "digestTime must be in HH:mm format, got: " + digestTime);
            }
            try {
                LocalTime.parse(digestTime);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "digestTime must be in HH:mm format, got: " + digestTime);
            }

            if (isBlank(config.getDigestPromptName())) {
                throw new IllegalArgumentException(
                        "digestPromptName must be set when digestTime is configured");
            }

            if (isBlank(config.getDigestSenderAddress())) {
                throw new IllegalArgumentException(
                        "digestSenderAddress must be set when digestTime is configured");
            }
        }

        if (!isBlank(config.getDigestPromptName())) {
            String digestName = config.getDigestPromptName();
            if (!config.getPrompts().containsKey(digestName)) {
                throw new IllegalArgumentException(
                        "digestPromptName '" + digestName + "' not found in prompts map");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
