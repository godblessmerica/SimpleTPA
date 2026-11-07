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

public final class SimpleTpaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final RequestBook requests = new RequestBook();
    private final Map<UUID, PendingTeleport> teleports = new HashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>();
    private Settings settings;
    private YamlConfiguration defaults;

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

        PendingTeleport(Player mover, UUID other, Location backDestination, Settings settings) {
            this.mover = mover.getUniqueId();
            this.other = other;
            this.origin = mover.getLocation();
            this.backDestination = backDestination;
            this.settings = settings;
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
        for (String name : List.of("tpa", "tpahere", "back")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name));
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::expireRequests, 1L, 20L);
    }

    private void loadSettings() throws Exception {
        var config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        settings = Settings.parse(config, defaults);
    }

    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        teleports.clear();
        requests.clear();
        backLocations.clear();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("tpa") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("simpletpa.reload")) { tell(sender, "no-permission"); return true; }
            try {
                loadSettings();
                tell(sender, "reloaded");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Config reload rejected; previous settings remain active", e);
                tell(sender, "reload-failed");
            }
            return true;
        }
        if (!(sender instanceof Player player)) { tell(sender, "players-only"); return true; }
        if (!player.hasPermission("simpletpa.use")) { tell(player, "no-permission"); return true; }
        expireRequests();
        if (command.getName().equals("back")) {
            if (args.length != 0) { tell(player, "usage-back"); return true; }
            if (busy(player.getUniqueId())) { tell(player, "busy"); return true; }
            Location back = backLocations.get(player.getUniqueId());
            if (back == null) tell(player, "no-back");
            else startTeleport(player, null, back.clone());
            return true;
        }
        if (command.getName().equals("tpahere")) {
            if (args.length == 1) sendRequest(player, args[0], true);
            else tell(player, "usage-here");
            return true;
        }
        if (args.length == 0) { tell(player, "usage"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept", "decline" -> {
                if (args.length > 3) tell(player, "usage");
                else respond(player, args, args[0].equalsIgnoreCase("accept"));
            }
            case "cancel" -> {
                if (args.length > 2) { tell(player, "usage"); break; }
                RequestBook.Request request = requests.outgoing(player.getUniqueId());
                if (args.length == 2 && (request == null || !request.id().toString().equals(args[1]))) {
                    tell(player, "stale-button");
                } else if (request == null) tell(player, "no-outgoing");
                else {
                    requests.remove(request);
                    notifyPair(request, "cancelled-sender", "cancelled-receiver");
                }
            }
            case "toggle" -> {
                if (args.length != 1) tell(player, "usage");
                else toggle(player);
            }
            default -> {
                if (args.length == 1) sendRequest(player, args[0], false);
                else tell(player, "usage");
            }
        }
        return true;
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
        String base = "/simpletpa:tpa ";
        Component accept = button("button-accept", base + "accept " + sender.getName() + " " + request.id());
        Component decline = button("button-decline", base + "decline " + sender.getName() + " " + request.id());
        Component cancel = button("button-cancel", base + "cancel " + request.id());
        tell(target, here ? "received-here" : "received-tpa", playerTag(sender.getName()),
                Placeholder.component("accept", accept), Placeholder.component("decline", decline));
        tell(sender, here ? "sent-here" : "sent-tpa", playerTag(target.getName()),
                secondsTag(settings.expiry()), Placeholder.component("cancel", cancel));
    }

    private void respond(Player receiver, String[] args, boolean accept) {
        List<RequestBook.Request> incoming = requests.incoming(receiver.getUniqueId());
        RequestBook.Request request;
        if (args.length >= 2) {
            request = incoming.stream().filter(r -> name(r.sender()).equalsIgnoreCase(args[1])).findFirst().orElse(null);
        } else {
            if (incoming.size() > 1) { tell(receiver, "multiple-incoming"); return; }
            request = incoming.isEmpty() ? null : incoming.getFirst();
        }
        if (args.length == 3 && (request == null || !request.id().toString().equals(args[2]))) {
            tell(receiver, "stale-button"); return;
        }
        if (request == null) {
            tell(receiver, args.length >= 2 ? "no-request-from" : "no-incoming",
                    playerTag(args.length >= 2 ? args[1] : ""));
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
        pending.task = Bukkit.getScheduler().runTaskLater(this, () -> finishTeleport(pending), settings.delay() * 20L);
    }

    private void finishTeleport(PendingTeleport pending) {
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
        for (RequestBook.Request request : requests.disconnect(id)) {
            UUID other = request.sender().equals(id) ? request.receiver() : request.sender();
            tell(Bukkit.getPlayer(other), "disconnected", playerTag(player.getName()));
        }
        for (PendingTeleport pending : List.copyOf(teleports.values())) {
            if (!pending.mover.equals(id) && !id.equals(pending.other)) continue;
            teleports.remove(pending.mover);
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
        recipient.sendMessage(mini.deserialize(settings.messages().get(key), tags));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("back")) return List.of();
        List<String> choices = new ArrayList<>();
        boolean use = sender.hasPermission("simpletpa.use");
        if (args.length == 1) {
            if (command.getName().equals("tpa")) {
                if (use) choices.addAll(List.of("accept", "decline", "cancel", "toggle"));
                if (sender.hasPermission("simpletpa.reload")) choices.add("reload");
            }
            if (use && sender instanceof Player player) {
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (!target.equals(player) && player.canSee(target)) choices.add(target.getName());
                }
            }
        } else if (use && args.length == 2 && command.getName().equals("tpa") && sender instanceof Player player
                && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("decline"))) {
            expireRequests();
            for (RequestBook.Request request : requests.incoming(player.getUniqueId())) choices.add(name(request.sender()));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
}
