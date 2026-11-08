package dev.adan.simpletpa;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path folder;
    YamlConfiguration defaults;
    Path file;

    @BeforeEach void setup() throws Exception {
        defaults = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        file = folder.resolve("config.yml");
        defaults.save(file.toFile());
    }

    @Test void editSavesAndReloadsWhilePreservingMessages() throws Exception {
        var yaml = ConfigStore.read(file);
        yaml.set("messages.success-back", "<green>Custom return.</green>");
        yaml.save(file.toFile());
        var change = ConfigStore.change(file, defaults, "teleport-delay-seconds", "5");
        assertEquals(3, change.before());
        assertEquals(5, change.after());
        assertEquals(5, ConfigStore.load(file, defaults).delay());
        assertEquals("<green>Custom return.</green>", ConfigStore.read(file).getString("messages.success-back"));
        assertEquals(1, Files.list(folder).count());
    }

    @Test void invalidOrUnknownEditsNeverWriteTheFile() throws Exception {
        byte[] original = Files.readAllBytes(file);
        for (String value : new String[]{"-1", "3601", "1.5", "bad", "9999999999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> ConfigStore.change(file, defaults, "teleport-delay-seconds", value));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigStore.change(file, defaults, "messages.success-back", "changed"));
        assertThrows(IllegalArgumentException.class, () -> ConfigStore.change(file, defaults, "cancel-on-damage", "maybe"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void switchesPersistAndCanDisableUpdateChecks() throws Exception {
        var change = ConfigStore.change(file, defaults, "check-for-updates", "false");
        assertEquals(true, change.before());
        assertEquals(false, change.after());
        assertFalse(ConfigStore.load(file, defaults).checkUpdates());
    }

    @Test void upgradeAddsNewKeysAndUpdatesOnlyOldBundledCommandGuidance() throws Exception {
        var yaml = ConfigStore.read(file);
        yaml.set("check-for-updates", null);
        yaml.set("messages.help-header", null);
        yaml.set("messages.usage", "<yellow>Use /tpa \\<player>, /tpahere \\<player>, /tpa accept [player], /tpa decline [player], /tpa cancel, /tpa toggle, or /back.</yellow>");
        yaml.set("messages.multiple-incoming", "<yellow>You have multiple requests. Use /tpa accept \\<player> or /tpa decline \\<player>.</yellow>");
        yaml.set("messages.success-back", "<green>Custom!</green>");
        yaml.save(file.toFile());
        Settings loaded = ConfigStore.load(file, defaults);
        assertTrue(loaded.checkUpdates());
        assertEquals(defaults.getString("messages.usage"), loaded.messages().get("usage"));
        assertEquals(defaults.getString("messages.multiple-incoming"), loaded.messages().get("multiple-incoming"));
        assertEquals("<green>Custom!</green>", loaded.messages().get("success-back"));
        assertNotNull(ConfigStore.read(file).getString("messages.help-header"));
    }

    @Test void malformedConfigAndInvalidReloadLeaveFileUntouched() throws Exception {
        Files.writeString(file, "messages: [broken\n");
        byte[] malformed = Files.readAllBytes(file);
        assertThrows(Exception.class, () -> ConfigStore.load(file, defaults));
        assertArrayEquals(malformed, Files.readAllBytes(file));
        defaults.set("teleport-delay-seconds", -1);
        defaults.save(file.toFile());
        byte[] invalid = Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class, () -> ConfigStore.load(file, defaults));
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @Test void booleansHaveSuggestionsAndNumbersDoNot() {
        assertTrue(Settings.isBoolean("cancel-on-movement"));
        assertTrue(Settings.isBoolean("cancel-on-damage"));
        assertTrue(Settings.isBoolean("check-for-updates"));
        assertFalse(Settings.isBoolean("teleport-delay-seconds"));
        assertFalse(Settings.isBoolean("request-expiry-seconds"));
    }
}
