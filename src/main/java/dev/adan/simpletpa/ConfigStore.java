package dev.adan.simpletpa;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

final class ConfigStore {
    private static final Map<String, String> OLD_MESSAGES = Map.of(
            "usage", "<yellow>Use /tpa \\<player>, /tpahere \\<player>, /tpa accept [player], /tpa decline [player], /tpa cancel, /tpa toggle, or /back.</yellow>",
            "multiple-incoming", "<yellow>You have multiple requests. Use /tpa accept \\<player> or /tpa decline \\<player>.</yellow>");
    record Change(Settings settings, Object before, Object after) {}

    static YamlConfiguration read(Path path) throws Exception {
        var config = new YamlConfiguration();
        config.options().parseComments(true);
        config.load(path.toFile());
        return config;
    }

    static Settings load(Path path, YamlConfiguration defaults) throws Exception {
        var config = read(path);
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!config.contains(key)) {
                config.set(key, defaults.get(key));
                changed = true;
            }
        }
        for (var entry : OLD_MESSAGES.entrySet()) {
            String key = "messages." + entry.getKey();
            if (entry.getValue().equals(config.getString(key))) {
                config.set(key, defaults.get(key));
                changed = true;
            }
        }
        Settings result = Settings.parse(config, defaults);
        if (changed) save(path, config);
        return result;
    }

    static Change change(Path path, YamlConfiguration defaults, String key, String input) throws Exception {
        Object value = Settings.inputValue(key, input);
        var config = read(path);
        Settings before = Settings.parse(config, defaults);
        config.set(key, value);
        Settings after = Settings.parse(config, defaults);
        save(path, config);
        return new Change(after, before.value(key), after.value(key));
    }

    private static void save(Path path, YamlConfiguration config) throws Exception {
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "config-", ".tmp");
        try {
            Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
