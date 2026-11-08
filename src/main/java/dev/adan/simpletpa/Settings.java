package dev.adan.simpletpa;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

record Settings(int delay, int expiry, boolean cancelMovement, boolean cancelDamage,
                boolean checkUpdates, Map<String, String> messages) {
    static final List<String> EDITABLE = List.of("teleport-delay-seconds", "request-expiry-seconds",
            "cancel-on-movement", "cancel-on-damage", "check-for-updates");

    static boolean isBoolean(String key) {
        return List.of("cancel-on-movement", "cancel-on-damage", "check-for-updates").contains(key);
    }

    static Object inputValue(String key, String text) {
        if (!EDITABLE.contains(key)) throw new IllegalArgumentException("Unknown setting");
        if (isBoolean(key)) {
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Use true or false");
            }
            return Boolean.parseBoolean(text);
        }
        try {
            int number = Integer.parseInt(text);
            validateSeconds(key, number);
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Use a whole number");
        }
    }

    Object value(String key) {
        return switch (key) {
            case "teleport-delay-seconds" -> delay;
            case "request-expiry-seconds" -> expiry;
            case "cancel-on-movement" -> cancelMovement;
            case "cancel-on-damage" -> cancelDamage;
            case "check-for-updates" -> checkUpdates;
            default -> throw new IllegalArgumentException("Unknown setting");
        };
    }

    static Settings parse(YamlConfiguration config, YamlConfiguration defaults) {
        int delay = seconds(config, "teleport-delay-seconds");
        int expiry = seconds(config, "request-expiry-seconds");
        boolean movement = bool(config, "cancel-on-movement");
        boolean damage = bool(config, "cancel-on-damage");
        boolean updates = config.contains("check-for-updates") ? bool(config, "check-for-updates")
                : defaults.getBoolean("check-for-updates", true);
        Map<String, String> messages = new HashMap<>();
        var section = Objects.requireNonNull(defaults.getConfigurationSection("messages"));
        for (String key : section.getKeys(false)) {
            String path = "messages." + key;
            Object value = config.contains(path) ? config.get(path) : defaults.get(path);
            if (!(value instanceof String text)) throw new IllegalArgumentException(path + " must be text");
            // Strict parsing rejects malformed tags before replacing the active settings.
            MiniMessage.builder().strict(true).build().deserialize(text,
                    Placeholder.unparsed("player", "Player"), Placeholder.unparsed("seconds", "3"),
                    Placeholder.unparsed("accept", ""), Placeholder.unparsed("decline", ""),
                    Placeholder.unparsed("cancel", ""), Placeholder.unparsed("command", "/tpa help"),
                    Placeholder.unparsed("setting", "setting"), Placeholder.unparsed("value", "true"),
                    Placeholder.unparsed("old", "false"), Placeholder.unparsed("current", "1.0.0"),
                    Placeholder.unparsed("latest", "1.1.0"), Placeholder.unparsed("download", ""));
            messages.put(key, text);
        }
        return new Settings(delay, expiry, movement, damage, updates, Map.copyOf(messages));
    }

    private static int seconds(YamlConfiguration config, String path) {
        if (!(config.get(path) instanceof Integer number)) throw new IllegalArgumentException(path + " must be a whole number");
        validateSeconds(path, number);
        return number;
    }

    private static void validateSeconds(String path, int number) {
        int min = path.equals("teleport-delay-seconds") ? 0 : 1;
        int max = path.equals("teleport-delay-seconds") ? 3600 : 86400;
        if (number < min || number > max) throw new IllegalArgumentException(path + " must be from " + min + " to " + max);
    }

    private static boolean bool(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(path + " must be true or false");
        return flag;
    }
}
