package dev.adan.simpletpa;

import org.bukkit.command.CommandSender;
import java.util.List;

final class Commands {
    record Entry(String command, String permission, String usage, String helpKey) {
        boolean allowed(CommandSender sender) { return Commands.allowed(sender, permission); }
    }

    static final List<Entry> ENTRIES = List.of(
            new Entry("tpa", "tpa.tpa", "/tpa <player>", "help-tpa"),
            new Entry("tpahere", "tpa.tphere", "/tpahere <player>", "help-here"),
            new Entry("tpaccept", null, "/tpaccept [player]", "help-accept"),
            new Entry("tpdeny", null, "/tpdeny [player]", "help-deny"),
            new Entry("tpcancel", null, "/tpcancel", "help-cancel"),
            new Entry("tptoggle", "tpa.toggle", "/tptoggle", "help-toggle"),
            new Entry("back", "tpa.back", "/back", "help-back"),
            new Entry("help", "tpa.help", "/tpa help", "help-help"),
            new Entry("config", "tpa.config", "/tpa config [setting] [value]", "help-config"),
            new Entry("reload", "tpa.reload", "/tpa reload", "help-reload"),
            new Entry("version", "tpa.version", "/tpa version", "help-version"));

    static Entry entry(String command) {
        return ENTRIES.stream().filter(e -> e.command().equals(command)).findFirst().orElseThrow();
    }

    static boolean allowed(CommandSender sender, String permission) {
        return permission == null
                ? sender.hasPermission("tpa.tpa") || sender.hasPermission("tpa.tphere")
                : sender.hasPermission(permission);
    }
}
