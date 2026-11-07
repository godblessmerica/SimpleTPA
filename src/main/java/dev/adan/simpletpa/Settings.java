package dev.adan.simpletpa;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

record Settings(int delay, int expiry, boolean cancelMovement, boolean cancelDamage, Map<String, String> messages) {
    static Settings parse(YamlConfiguration config, YamlConfiguration defaults) {
        int delay = seconds(config, "teleport-delay-seconds", 0, 3600);
        int expiry = seconds(config, "request-expiry-seconds", 1, 86400);
        boolean movement = bool(config, "cancel-on-movement");
        boolean damage = bool(config, "cancel-on-damage");
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
                    Placeholder.unparsed("cancel", ""));
            messages.put(key, text);
        }
        return new Settings(delay, expiry, movement, damage, Map.copyOf(messages));
    }

    private static int seconds(YamlConfiguration config, String path, int min, int max) {
        Object value = config.get(path);
        if (!(value instanceof Integer number) || number < min || number > max) {
            throw new IllegalArgumentException(path + " must be an integer from " + min + " to " + max);
        }
        return number;
    }

    private static boolean bool(YamlConfiguration config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(path + " must be true or false");
        return flag;
    }
}
