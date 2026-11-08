package dev.adan.simpletpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class SimpleTpaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final RequestBook requests = new RequestBook();
    private final Map<UUID, PendingTeleport> teleports = new HashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>();
    private Settings settings;
    private YamlConfiguration defaults;
    private BukkitTask updateTask;
    private int updateGeneration;
    private boolean updateCheckComplete;
    private UpdateChecker.Release latestRelease;
    private final Set<UUID> waitingUpdateNotice = new HashSet<>();

    private static final class PendingTeleport {
        final UUID mover;
        final UUID other;
        final Location origin;
        final Location backDestination;
        final Settings settings;
        BukkitTask task;
        boolean transferring;
        Location destination;
        Location departure;
        int remaining;

        PendingTeleport(Player mover, UUID other, Location backDestination, Settings settings) {
            this.mover = mover.getUniqueId();
            this.other = other;
            this.origin = mover.getLocation();
            this.backDestination = backDestination;
            this.settings = settings;
            this.remaining = settings.delay();
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)) {
            defaults = new YamlConfiguration();
            defaults.load(reader);
            loadSettings();
            File preferencesFile = new File(getDataFolder(), "preferences.yml");
            if (preferencesFile.exists()) {
                var preferences = new YamlConfiguration();
                preferences.load(preferencesFile);
                for (String id : preferences.getStringList("disabled-players")) disabled.add(UUID.fromString(id));
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Cannot load SimpleTPA configuration", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String name : List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel", "tptoggle", "back")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name));
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::expireRequests, 1L, 20L);
        configureUpdateChecks();
    }

    private void loadSettings() throws Exception {
        applySettings(ConfigStore.load(getDataFolder().toPath().resolve("config.yml"), defaults));
    }

    private void applySettings(Settings next) {
        boolean changed = settings != null && settings.checkUpdates() != next.checkUpdates();
        settings = next;
        if (changed) configureUpdateChecks();
    }

    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        teleports.clear();
        requests.clear();
        backLocations.clear();
        waitingUpdateNotice.clear();
        updateGeneration++;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String route = command.getName();
        String[] values = args;
        if (route.equals("tpa")) {
            if (args.length == 0) route = "help";
            else if (List.of("help", "config", "reload", "version").contains(args[0].toLowerCase(Locale.ROOT))) {
                route = args[0].toLowerCase(Locale.ROOT);
                values = Arrays.copyOfRange(args, 1, args.length);
            }
        }
        Commands.Entry entry = Commands.entry(route);
        if (!entry.allowed(sender)) { tell(sender, "no-permission"); return true; }
        switch (route) {
            case "help" -> {
                if (values.length != 0) usage(sender, entry);
                else {
                    tell(sender, "help-header");
                    for (Commands.Entry item : Commands.ENTRIES) {
                        if (item.allowed(sender)) tell(sender, item.helpKey(), Placeholder.unparsed("command", item.usage()));
                    }
                }
                return true;
            }
            case "version" -> {
                if (values.length != 0) usage(sender, entry);
                else tell(sender, "version", Placeholder.unparsed("current", getPluginMeta().getVersion()));
                return true;
            }
            case "reload" -> {
                if (values.length != 0) { usage(sender, entry); return true; }
                try {
                    loadSettings();
                    tell(sender, "reloaded");
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Config reload rejected; previous settings remain active", e);
                    tell(sender, "reload-failed");
                }
                return true;
            }
            case "config" -> { configure(sender, values); return true; }
            default -> { }
        }
        if (!(sender instanceof Player player)) { tell(sender, "players-only"); return true; }
        expireRequests();
        switch (route) {
            case "tpa", "tpahere" -> {
                if (values.length == 1) sendRequest(player, values[0], route.equals("tpahere"));
                else usage(player, entry);
            }
            case "tpaccept", "tpdeny" -> {
                if (values.length > 2) usage(player, entry);
                else respond(player, values, route.equals("tpaccept"));
            }
            case "tpcancel" -> {
                if (values.length > 1) { usage(player, entry); break; }
                RequestBook.Request request = requests.outgoing(player.getUniqueId());
                if (values.length == 1 && (request == null || !request.id().toString().equals(values[0]))) {
                    tell(player, "stale-button");
                } else if (request == null) tell(player, "no-outgoing");
                else {
                    requests.remove(request);
                    notifyPair(request, "cancelled-sender", "cancelled-receiver");
                }
            }
            case "tptoggle" -> {
                if (values.length != 0) usage(player, entry);
                else toggle(player);
            }
            case "back" -> {
                if (values.length != 0) { usage(player, entry); break; }
                if (busy(player.getUniqueId())) { tell(player, "busy"); break; }
                Location back = backLocations.get(player.getUniqueId());
                if (back == null) tell(player, "no-back");
                else startTeleport(player, null, back.clone());
            }
            default -> throw new IllegalStateException("Unknown command: " + route);
        }
        return true;
    }

    private void usage(CommandSender sender, Commands.Entry entry) {
        tell(sender, "usage", Placeholder.unparsed("command", entry.usage()));
    }

    private void configure(CommandSender sender, String[] args) {
        if (args.length > 2) { usage(sender, Commands.entry("config")); return; }
        if (args.length == 0) {
            tell(sender, "config-header");
            for (String key : Settings.EDITABLE) showSetting(sender, key);
            return;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        if (!Settings.EDITABLE.contains(key)) { tell(sender, "config-unknown"); return; }
        if (args.length == 1) { showSetting(sender, key); return; }
        try {
            Settings.inputValue(key, args[1]);
        } catch (IllegalArgumentException e) {
            tell(sender, "config-invalid");
            return;
        }
        try {
            ConfigStore.Change change = ConfigStore.change(getDataFolder().toPath().resolve("config.yml"), defaults, key, args[1]);
            applySettings(change.settings());
            tell(sender, "config-changed", Placeholder.unparsed("setting", key),
                    Placeholder.unparsed("old", change.before().toString()), Placeholder.unparsed("value", change.after().toString()));
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Config edit rejected; settings were not changed", e);
            tell(sender, "config-save-failed");
        }
    }

    private void showSetting(CommandSender sender, String key) {
        tell(sender, "config-value", Placeholder.unparsed("setting", key), Placeholder.unparsed("value", settings.value(key).toString()));
    }

    private void sendRequest(Player sender, String name, boolean here) {
        RequestBook.Request existing = requests.outgoing(sender.getUniqueId());
        if (existing != null) { tell(sender, "pending-request", playerTag(name(existing.receiver()))); return; }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !sender.canSee(target)) { tell(sender, "offline", playerTag(name)); return; }
        if (target.equals(sender)) { tell(sender, "self-request"); return; }
        if (disabled.contains(target.getUniqueId())) { tell(sender, "not-accepting", playerTag(target.getName())); return; }
        if (busy(sender.getUniqueId()) || busy(target.getUniqueId())) { tell(sender, "busy"); return; }
        RequestBook.Request request = requests.add(sender.getUniqueId(), target.getUniqueId(), here,
                System.nanoTime() + settings.expiry() * 1_000_000_000L);
        Component accept = button("button-accept", "/simpletpa:tpaccept " + sender.getName() + " " + request.id());
        Component decline = button("button-decline", "/simpletpa:tpdeny " + sender.getName() + " " + request.id());
        Component cancel = button("button-cancel", "/simpletpa:tpcancel " + request.id());
        tell(target, here ? "received-here" : "received-tpa", playerTag(sender.getName()),
                Placeholder.component("accept", accept), Placeholder.component("decline", decline));
        tell(sender, here ? "sent-here" : "sent-tpa", playerTag(target.getName()),
                secondsTag(settings.expiry()), Placeholder.component("cancel", cancel));
    }

    private void respond(Player receiver, String[] args, boolean accept) {
        List<RequestBook.Request> incoming = requests.incoming(receiver.getUniqueId());
        RequestBook.Request request;
        if (args.length >= 1) {
            request = incoming.stream().filter(r -> name(r.sender()).equalsIgnoreCase(args[0])).findFirst().orElse(null);
        } else {
            if (incoming.size() > 1) { tell(receiver, "multiple-incoming"); return; }
            request = incoming.isEmpty() ? null : incoming.getFirst();
        }
        if (args.length == 2 && (request == null || !request.id().toString().equals(args[1]))) {
            tell(receiver, "stale-button"); return;
        }
        if (request == null) {
            tell(receiver, args.length >= 1 ? "no-request-from" : "no-incoming",
                    playerTag(args.length >= 1 ? args[0] : ""));
            return;
        }
        Player sender = Bukkit.getPlayer(request.sender());
        if (sender == null) { requests.remove(request); tell(receiver, "offline", playerTag(name(request.sender()))); return; }
        if (accept && (busy(sender.getUniqueId()) || busy(receiver.getUniqueId()))) { tell(receiver, "busy"); return; }
        requests.remove(request);
        if (!accept) { notifyPair(request, "declined-sender", "declined-receiver"); return; }
        notifyPair(request, "accepted-sender", "accepted-receiver");
        Player mover = request.here() ? receiver : sender;
        Player destination = request.here() ? sender : receiver;
        startTeleport(mover, destination.getUniqueId(), null);
    }

    private void startTeleport(Player mover, UUID other, Location backDestination) {
        PendingTeleport pending = new PendingTeleport(mover, other, backDestination, settings);
        teleports.put(mover.getUniqueId(), pending);
        if (settings.delay() == 0) { finishTeleport(pending); return; }
        tell(mover, backDestination == null ? "countdown-mover" : "countdown-back", secondsTag(settings.delay()));
        if (other != null) tell(Bukkit.getPlayer(other), "countdown-other", playerTag(mover.getName()), secondsTag(settings.delay()));
        showCountdown(pending);
        pending.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (teleports.get(pending.mover) != pending || pending.transferring) {
                pending.task.cancel();
                return;
            }
            if (--pending.remaining == 0) {
                pending.task.cancel();
                finishTeleport(pending);
            } else showCountdown(pending);
        }, 20L, 20L);
    }

    private void showCountdown(PendingTeleport pending) {
        Player mover = Bukkit.getPlayer(pending.mover);
        if (mover != null) mover.sendActionBar(message(pending.backDestination == null ? "countdown-mover" : "countdown-back",
                secondsTag(pending.remaining)));
        Player other = pending.other == null ? null : Bukkit.getPlayer(pending.other);
        if (other != null) other.sendActionBar(message("countdown-other", playerTag(name(pending.mover)), secondsTag(pending.remaining)));
    }

    private void clearCountdown(PendingTeleport pending) {
        Player mover = Bukkit.getPlayer(pending.mover);
        if (mover != null) mover.sendActionBar(Component.empty());
        Player other = pending.other == null ? null : Bukkit.getPlayer(pending.other);
        if (other != null) other.sendActionBar(Component.empty());
    }

    private void finishTeleport(PendingTeleport pending) {
        clearCountdown(pending);
        Player mover = Bukkit.getPlayer(pending.mover);
        Player other = pending.other == null ? null : Bukkit.getPlayer(pending.other);
        if (mover == null || (pending.other != null && other == null)) { cancelTeleport(pending, "failed"); return; }
        Location destination = pending.backDestination == null ? other.getLocation() : pending.backDestination;
        if (destination.getWorld() == null || mover.isDead()) { cancelTeleport(pending, "failed"); return; }
        pending.departure = mover.getLocation();
        pending.destination = destination.clone();
        pending.transferring = true;
        try {
            // Paper loads destination chunks asynchronously rather than blocking the server tick.
            mover.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!teleports.remove(pending.mover, pending)) return;
                    if (error != null || !Boolean.TRUE.equals(success)) {
                        tell(mover, "failed");
                        tell(other, "failed");
                        if (error != null) getLogger().log(Level.WARNING, "Teleport failed", error);
                    } else if (pending.backDestination != null) {
                        backLocations.remove(pending.mover);
                        tell(mover, "success-back");
                    } else {
                        backLocations.put(pending.mover, pending.departure);
                        tell(mover, "success-mover", playerTag(other.getName()));
                        tell(other, "success-other", playerTag(mover.getName()));
                    }
                });
            });
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not start teleport", e);
            cancelTeleport(pending, "failed");
        }
    }

    private boolean busy(UUID player) {
        return teleports.values().stream().anyMatch(p -> p.mover.equals(player) || player.equals(p.other));
    }

    private void cancelTeleport(PendingTeleport pending, String reason) {
        if (!teleports.remove(pending.mover, pending)) return;
        clearCountdown(pending);
        if (pending.task != null) pending.task.cancel();
        Player mover = Bukkit.getPlayer(pending.mover);
        Player other = pending.other == null ? null : Bukkit.getPlayer(pending.other);
        if (reason.equals("damage") || reason.equals("movement")) {
            tell(mover, reason + "-mover");
            tell(other, reason + "-other", playerTag(name(pending.mover)));
        } else {
            tell(mover, reason);
            tell(other, reason);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        PendingTeleport pending = teleports.get(event.getEntity().getUniqueId());
        if (pending != null && !pending.transferring && pending.settings.cancelDamage() && event.getFinalDamage() > 0) {
            cancelTeleport(pending, "damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PendingTeleport pending = teleports.get(event.getPlayer().getUniqueId());
        if (pending == null || pending.transferring || !pending.settings.cancelMovement() || event.getTo() == null) return;
        Location from = pending.origin;
        Location to = event.getTo();
        if (!Objects.equals(from.getWorld(), to.getWorld()) || from.distanceSquared(to) > 0.0001) {
            cancelTeleport(pending, "movement");
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { clearPlayer(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PendingTeleport pending = teleports.get(event.getPlayer().getUniqueId());
        if (pending != null && pending.transferring) {
            if (Objects.equals(event.getTo(), pending.destination)) pending.departure = event.getFrom().clone();
        } else {
            onMove(event);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        for (PendingTeleport pending : List.copyOf(teleports.values())) {
            if (pending.mover.equals(event.getEntity().getUniqueId())) cancelTeleport(pending, "failed");
        }
    }

    private void clearPlayer(Player player) {
        UUID id = player.getUniqueId();
        waitingUpdateNotice.remove(id);
        for (RequestBook.Request request : requests.disconnect(id)) {
            UUID other = request.sender().equals(id) ? request.receiver() : request.sender();
            tell(Bukkit.getPlayer(other), "disconnected", playerTag(player.getName()));
        }
        for (PendingTeleport pending : List.copyOf(teleports.values())) {
            if (!pending.mover.equals(id) && !id.equals(pending.other)) continue;
            teleports.remove(pending.mover);
            clearCountdown(pending);
            if (pending.task != null) pending.task.cancel();
            UUID other = pending.mover.equals(id) ? pending.other : pending.mover;
            if (other != null) tell(Bukkit.getPlayer(other), "disconnected", playerTag(player.getName()));
        }
        backLocations.remove(id);
    }

    private void expireRequests() {
        for (RequestBook.Request request : requests.expired(System.nanoTime())) {
            notifyPair(request, "expired-sender", "expired-receiver");
        }
    }

    private void toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean wasDisabled = disabled.contains(id);
        if (wasDisabled) disabled.remove(id); else disabled.add(id);
        var preferences = new YamlConfiguration();
        preferences.set("disabled-players", disabled.stream().map(UUID::toString).sorted().toList());
        try {
            preferences.save(new File(getDataFolder(), "preferences.yml"));
            tell(player, wasDisabled ? "toggle-on" : "toggle-off");
        } catch (IOException e) {
            if (wasDisabled) disabled.add(id); else disabled.remove(id);
            tell(player, "toggle-save-failed");
            getLogger().log(Level.WARNING, "Could not save request preferences", e);
        }
    }

    private void notifyPair(RequestBook.Request request, String senderMessage, String receiverMessage) {
        tell(Bukkit.getPlayer(request.sender()), senderMessage, playerTag(name(request.receiver())));
        tell(Bukkit.getPlayer(request.receiver()), receiverMessage, playerTag(name(request.sender())));
    }

    private String name(UUID id) {
        return Objects.requireNonNullElse(Bukkit.getOfflinePlayer(id).getName(), id.toString());
    }

    private TagResolver playerTag(String name) { return Placeholder.unparsed("player", name); }
    private TagResolver secondsTag(int seconds) { return Placeholder.unparsed("seconds", Integer.toString(seconds)); }

    private Component button(String key, String command) {
        return mini.deserialize(settings.messages().get(key)).clickEvent(ClickEvent.runCommand(command));
    }

    private void tell(CommandSender recipient, String key, TagResolver... tags) {
        if (recipient == null) return;
        recipient.sendMessage(message(key, tags));
    }

    private Component message(String key, TagResolver... tags) {
        return mini.deserialize(settings.messages().get(key), tags);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String route = command.getName();
        List<String> choices = new ArrayList<>();
        if (route.equals("tpa") && args.length >= 2 && args[0].equalsIgnoreCase("config")) {
            if (!Commands.entry("config").allowed(sender)) return List.of();
            if (args.length == 2) choices.addAll(Settings.EDITABLE);
            else if (args.length == 3 && Settings.isBoolean(args[1].toLowerCase(Locale.ROOT))) choices.addAll(List.of("true", "false"));
        } else if (args.length == 1) {
            if (route.equals("tpa")) {
                for (String sub : List.of("help", "config", "reload", "version")) {
                    if (Commands.entry(sub).allowed(sender)) choices.add(sub);
                }
            }
            if (!Commands.entry(route).allowed(sender)) return filter(choices, args);
            if ((route.equals("tpa") || route.equals("tpahere")) && sender instanceof Player player) {
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (!target.equals(player) && player.canSee(target)) choices.add(target.getName());
                }
            } else if ((route.equals("tpaccept") || route.equals("tpdeny")) && sender instanceof Player player) {
                expireRequests();
                for (RequestBook.Request request : requests.incoming(player.getUniqueId())) choices.add(name(request.sender()));
            }
        }
        return filter(choices, args);
    }

    private List<String> filter(List<String> choices, String[] args) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }

    private void configureUpdateChecks() {
        if (updateTask != null) updateTask.cancel();
        int generation = ++updateGeneration;
        latestRelease = null;
        updateCheckComplete = false;
        waitingUpdateNotice.clear();
        if (!settings.checkUpdates()) return;
        String current = getPluginMeta().getVersion();
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            Optional<UpdateChecker.Release> result;
            try {
                result = UpdateChecker.check(current);
            } catch (Exception e) {
                getLogger().fine("Update check unavailable: " + e.getMessage());
                result = Optional.empty();
            }
            Optional<UpdateChecker.Release> checked = result;
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                if (generation != updateGeneration || !settings.checkUpdates()) return;
                latestRelease = checked.orElse(null);
                updateCheckComplete = true;
                for (UUID id : Set.copyOf(waitingUpdateNotice)) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) notifyUpdate(player);
                }
                waitingUpdateNotice.clear();
            });
        }, 0L, 6L * 60 * 60 * 20);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().isOp() || !settings.checkUpdates()) return;
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOp() || !settings.checkUpdates()) return;
            if (updateCheckComplete) notifyUpdate(player);
            else waitingUpdateNotice.add(id);
        }, 40L);
    }

    private void notifyUpdate(Player player) {
        if (!player.isOp() || latestRelease == null || !settings.checkUpdates()) return;
        tell(player, "update-available");
        tell(player, "update-versions", Placeholder.unparsed("current", getPluginMeta().getVersion()),
                Placeholder.unparsed("latest", latestRelease.version()));
        player.sendMessage(message("update-link").clickEvent(ClickEvent.openUrl(latestRelease.url())));
    }
}
