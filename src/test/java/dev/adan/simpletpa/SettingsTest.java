package dev.adan.simpletpa;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    private YamlConfiguration config() throws Exception {
        var yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    @Test void defaultsParseAndContainAllMessagesAndNoCooldown() throws Exception {
        var config = config();
        var settings = Settings.parse(config, config);
        assertEquals(3, settings.delay());
        assertEquals(60, settings.expiry());
        assertFalse(settings.cancelMovement());
        assertTrue(settings.cancelDamage());
        assertFalse(config.contains("request-cooldown-seconds"));
        assertTrue(settings.messages().containsKey("no-back"));
        assertTrue(settings.messages().containsKey("success-back"));
    }

    @Test void zeroDelayAllowedButZeroExpiryRejected() throws Exception {
        var config = config();
        config.set("teleport-delay-seconds", 0);
        assertEquals(0, Settings.parse(config, config()).delay());
        config.set("request-expiry-seconds", 0);
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(config, config()));
    }

    @Test void invalidTypesAndExcessiveDurationsRejected() throws Exception {
        var config = config();
        config.set("cancel-on-damage", "yes");
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(config, config()));
        var negative = config();
        negative.set("teleport-delay-seconds", -1);
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(negative, config()));
        negative.set("teleport-delay-seconds", 3601);
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(negative, config()));
    }

    @Test void missingMessagesFallBackButNonTextAndBrokenFormattingRejected() throws Exception {
        var config = config();
        config.set("messages.no-back", null);
        assertNotNull(Settings.parse(config, config()).messages().get("no-back"));
        config.set("messages.no-back", 3);
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(config, config()));
        config.set("messages.no-back", "<green>Unclosed");
        assertThrows(RuntimeException.class, () -> Settings.parse(config, config()));
    }

    @Test void playerNamesAreInsertedAsPlainText() {
        var component = MiniMessage.miniMessage().deserialize("<player>", Placeholder.unparsed("player", "<red>name"));
        assertEquals(net.kyori.adventure.text.Component.text("<red>name"), component);
    }
}
