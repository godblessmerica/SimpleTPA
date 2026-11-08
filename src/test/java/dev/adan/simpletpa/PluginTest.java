package dev.adan.simpletpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginTest {
    ServerMock server;
    SimpleTpaPlugin plugin;
    PlayerMock alice, bob;
    YamlConfiguration config;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        config = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        config.set("check-for-updates", false);
        java.nio.file.Path data = folder.resolve("SimpleTPA");
        java.nio.file.Files.createDirectories(data);
        config.save(data.resolve("config.yml").toFile());
        var description = new org.bukkit.plugin.PluginDescriptionFile(getClass().getResourceAsStream("/plugin.yml"));
        // Define the proxy directly: MockBukkit 4.118's missing-class lookup throws before ByteBuddy can inject it.
        var loader = new org.mockbukkit.mockbukkit.plugin.MockBukkitConfiguredPluginClassLoader(
                server, description, data.toFile(), data.resolve("test.jar").toFile()) {
            @Override public Class<? extends org.bukkit.plugin.Plugin> loadProxyClass(Class<? extends org.bukkit.plugin.Plugin> type) {
                String name = "dev.adan.simpletpa.SimpleTpaTestProxy";
                byte[] bytes = new net.bytebuddy.ByteBuddy().subclass(type).name(name).make().getBytes();
                return defineClass(name, bytes, 0, bytes.length).asSubclass(org.bukkit.plugin.Plugin.class);
            }
        };
        plugin = (SimpleTpaPlugin) loader.loadProxyClass(SimpleTpaPlugin.class).getConstructor().newInstance();
        server.getPluginManager().registerLoadedPlugin(plugin);
        server.getPluginManager().addPermissions(description.getPermissions());
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled());
        alice = new TeleportPlayer(server, "Alice");
        bob = new TeleportPlayer(server, "Bob");
        server.addPlayer(alice);
        server.addPlayer(bob);
        alice.setOp(false);
        bob.setOp(false);
        alice.setLocation(new Location(server.getWorld("world"), 10, 70, 10));
        bob.setLocation(new Location(server.getWorld("world"), 100, 70, 100));
        chat(alice);
        chat(bob);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String chat(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        for (Component next; (next = player.nextComponentMessage()) != null;) messages.add(plain(next));
        return String.join("\n", messages);
    }

    private void command(PlayerMock player, String command) { assertTrue(server.dispatchCommand(player, command)); }
    private void admin(String command) { assertTrue(server.dispatchCommand(server.getConsoleSender(), command)); }

    private List<String> clicks(Component component) {
        List<String> result = new ArrayList<>();
        if (component.clickEvent() != null && component.clickEvent().payload() instanceof ClickEvent.Payload.Text text) result.add(text.value());
        for (Component child : component.children()) result.addAll(clicks(child));
        return result;
    }

    @Test void permissionsSeparateSendingAndAutomaticallyAllowRequestActions() {
        alice.addAttachment(plugin, "tpa.tpa", false);
        alice.addAttachment(plugin, "tpa.tphere", false);
        for (String command : List.of("tpa Bob", "tpahere Bob", "tpaccept", "tpdeny", "tpcancel")) {
            command(alice, command);
            assertTrue(chat(alice).contains("permission"), command);
        }
        alice.addAttachment(plugin, "tpa.tphere", true);
        command(alice, "tpaccept");
        assertTrue(chat(alice).contains("no pending"));
        command(alice, "tpahere Bob");
        assertTrue(chat(bob).contains("wants you to teleport"));
        command(alice, "tpcancel");
        assertTrue(chat(alice).contains("cancelled"));
    }

    @Test void helpFiltersCommandsAndAdminToolsHaveSeparatePermissions() {
        alice.addAttachment(plugin, "tpa.toggle", false);
        command(alice, "tpa help");
        String help = chat(alice);
        assertTrue(help.contains("/tpaccept"));
        assertTrue(help.contains("/tpa <player>"));
        assertFalse(help.contains("/tptoggle"));
        assertFalse(help.contains("/tpa config"));
        assertFalse(help.contains("/tpa reload"));
        assertFalse(help.contains("/tpa version"));
        command(alice, "tpa version");
        assertTrue(chat(alice).contains("permission"));
        alice.addAttachment(plugin, "tpa.version", true);
        command(alice, "tpa version");
        assertTrue(chat(alice).contains("1.1.0"));
        command(alice, "tpa config");
        assertTrue(chat(alice).contains("permission"));
        command(alice, "tpa reload");
        assertTrue(chat(alice).contains("permission"));
    }

    @Test void renamedButtonsWorkAndOldCancelButtonCannotCancelNewRequest() {
        command(alice, "tpa Bob");
        Component sent = alice.nextComponentMessage();
        Component received = bob.nextComponentMessage();
        assertEquals(2, clicks(received).size());
        assertTrue(clicks(received).stream().anyMatch(c -> c.startsWith("/simpletpa:tpaccept Alice ")));
        assertTrue(clicks(received).stream().anyMatch(c -> c.startsWith("/simpletpa:tpdeny Alice ")));
        String oldCancel = clicks(sent).getFirst();
        assertTrue(oldCancel.startsWith("/simpletpa:tpcancel "));
        command(alice, "tpcancel");
        chat(alice); chat(bob);
        command(alice, "tpa Bob");
        chat(alice); chat(bob);
        command(alice, oldCancel.substring(1));
        assertTrue(chat(alice).contains("no longer active"));
        command(bob, "tpdeny Alice");
        assertTrue(chat(alice).contains("declined"));
    }

    @Test void oldSubcommandsDoNotResolveRequestsOrToggle() {
        command(alice, "tpa Bob");
        chat(alice); chat(bob);
        command(bob, "tpa accept Alice");
        assertTrue(chat(bob).contains("Use /tpa"));
        command(alice, "tpa cancel");
        assertFalse(chat(alice).contains("You cancelled"));
        command(bob, "tpaccept Alice");
        assertTrue(chat(bob).contains("You accepted"));
    }

    @Test void countdownRunsThreeTwoOneThenGrantsOneUseBackOnlyToMover() {
        Location origin = alice.getLocation();
        Location destination = bob.getLocation();
        command(alice, "tpa Bob");
        command(bob, "tpaccept Alice");
        assertTrue(plain(alice.nextActionBar()).contains("3 seconds"));
        server.getScheduler().performTicks(20);
        assertTrue(plain(alice.nextActionBar()).contains("2 seconds"));
        assertEquals(origin, alice.getLocation());
        server.getScheduler().performTicks(20);
        assertTrue(plain(alice.nextActionBar()).contains("1 seconds"));
        server.getScheduler().performTicks(22);
        assertEquals(destination, alice.getLocation());
        command(bob, "back");
        assertTrue(chat(bob).contains("no previous teleport"));
        admin("tpa config teleport-delay-seconds 0");
        command(alice, "back");
        server.getScheduler().performTicks(2);
        assertEquals(origin, alice.getLocation());
        chat(alice);
        command(alice, "back");
        assertTrue(chat(alice).contains("no previous teleport"));
    }

    @Test void damageStopsCountdownAndDoesNotGrantBack() {
        Location origin = alice.getLocation();
        command(alice, "tpa Bob");
        command(bob, "tpaccept");
        chat(alice); chat(bob);
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityDamageEvent(alice,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.CUSTOM, 1.0));
        assertTrue(chat(alice).contains("took damage"));
        server.getScheduler().performTicks(80);
        assertEquals(origin, alice.getLocation());
        command(alice, "back");
        assertTrue(chat(alice).contains("no previous teleport"));
    }

    @Test void movementCancellationCanBeEnabledAndLookingAroundIsAllowed() {
        admin("tpa config cancel-on-movement true");
        command(alice, "tpa Bob");
        command(bob, "tpaccept");
        chat(alice); chat(bob);
        Location rotated = alice.getLocation();
        rotated.setYaw(90);
        alice.simulatePlayerMove(rotated);
        assertFalse(chat(alice).contains("cancelled"));
        alice.simulatePlayerMove(alice.getLocation().add(1, 0, 0));
        assertTrue(chat(alice).contains("because you moved"));
        server.getScheduler().performTicks(80);
        assertNotEquals(bob.getLocation(), alice.getLocation());
    }

    @Test void configEditsArePermissionCheckedAndImmediatelyApplied() throws Exception {
        command(alice, "tpa config teleport-delay-seconds 0");
        assertTrue(chat(alice).contains("permission"));
        alice.addAttachment(plugin, "tpa.config", true);
        command(alice, "tpa config");
        assertTrue(chat(alice).contains("request-expiry-seconds: 60"));
        command(alice, "tpa config teleport-delay-seconds 0");
        assertTrue(chat(alice).contains("3 → 0"));
        assertEquals(0, ConfigStore.read(plugin.getDataFolder().toPath().resolve("config.yml")).getInt("teleport-delay-seconds"));
        command(alice, "tpa config messages.success-back changed");
        assertTrue(chat(alice).contains("Messages cannot"));
        command(alice, "tpa config request-expiry-seconds -1");
        assertTrue(chat(alice).contains("Invalid value"));
        command(alice, "tpa Bob");
        command(bob, "tpaccept");
        server.getScheduler().performTicks(2);
        assertEquals(bob.getLocation(), alice.getLocation());
    }

    @Test void completionsSuggestBooleansButNotNumbersOrRemovedSubcommands() {
        var command = plugin.getCommand("tpa");
        assertNotNull(command);
        assertFalse(command.tabComplete(alice, "tpa", new String[]{""}).contains("accept"));
        assertFalse(command.tabComplete(alice, "tpa", new String[]{""}).contains("config"));
        alice.addAttachment(plugin, "tpa.config", true);
        assertTrue(command.tabComplete(alice, "tpa", new String[]{"config", ""}).contains("check-for-updates"));
        assertEquals(List.of("false", "true"), command.tabComplete(alice, "tpa", new String[]{"config", "cancel-on-damage", ""}));
        assertTrue(command.tabComplete(alice, "tpa", new String[]{"config", "teleport-delay-seconds", ""}).isEmpty());
        assertTrue(command.tabComplete(alice, "tpa", new String[]{"config", "request-expiry-seconds", ""}).isEmpty());
    }

    @Test void cachedUpdateNoticeOnlyGoesToOperatorsWhenChecksEnabled() throws Exception {
        config.set("check-for-updates", true);
        setField("settings", Settings.parse(config, config));
        setField("updateCheckComplete", true);
        setField("latestRelease", new UpdateChecker.Release("1.2.0", UpdateChecker.REPOSITORY + "/releases/tag/v1.2.0"));
        alice.setOp(true);
        plugin.onJoin(new PlayerJoinEvent(alice, Component.empty()));
        plugin.onJoin(new PlayerJoinEvent(bob, Component.empty()));
        server.getScheduler().performTicks(40);
        assertTrue(chat(alice).contains("1.1.0 → 1.2.0"));
        assertEquals("", chat(bob));
        config.set("check-for-updates", false);
        setField("settings", Settings.parse(config, config));
        plugin.onJoin(new PlayerJoinEvent(alice, Component.empty()));
        server.getScheduler().performTicks(40);
        assertEquals("", chat(alice));
    }

    private static class TeleportPlayer extends PlayerMock {
        boolean failTeleport;
        TeleportPlayer(ServerMock server, String name) { super(server, name); }
        @Override public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(Location location,
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause, io.papermc.paper.entity.TeleportFlag... flags) {
            // MockBukkit leaves teleportAsync unimplemented; simulate its completion using the event-aware teleport method.
            return java.util.concurrent.CompletableFuture.completedFuture(!failTeleport && teleport(location, cause, flags));
        }
    }

    private void setField(String name, Object value) throws Exception {
        var field = SimpleTpaPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
