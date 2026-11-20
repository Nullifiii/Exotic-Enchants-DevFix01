package com.exoticenchants.command;

import com.exoticenchants.ExoticEnchantsPlugin;
import com.exoticenchants.core.Rarity;
import com.exoticenchants.core.Registration;
import com.exoticenchants.core.Status;
import com.exoticenchants.item.EnchantPool;
import com.exoticenchants.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /exotic ... (player commands) and /exotic admin ... */
public final class ExoticCommand implements TabExecutor {

    private static final String ADMIN = "exoticenchants.admin";

    private final ExoticEnchantsPlugin plugin;

    public ExoticCommand(ExoticEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            overview(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "top" -> top(sender);
            case "inspect" -> inspect(sender, args, false);
            case "apply" -> withPlayer(sender, p -> result(p, plugin.service().applyBook(p), "Exotic enchantment applied!"));
            case "remove" -> withPlayer(sender, p -> remove(p, args));
            case "shop" -> withPlayer(sender, p -> plugin.shop().open(p));
            case "rolls" -> rolls(sender);
            case "admin" -> admin(sender, args);
            default -> help(sender);
        }
        return true;
    }

    // ---------------------------------------------------------------- player

    private void help(CommandSender s) {
        s.sendMessage(Msg.info("/exotic              your exotics and the next roll"));
        s.sendMessage(Msg.info("/exotic apply        book in OFF-hand, item in MAIN hand"));
        s.sendMessage(Msg.info("/exotic shop         buy exotic books"));
        s.sendMessage(Msg.info("/exotic remove       remove the exotic from your held item (costs money)"));
        s.sendMessage(Msg.info("/exotic top          leaderboard"));
        s.sendMessage(Msg.info("/exotic inspect <player>"));
        s.sendMessage(Msg.info("/exotic rolls        time until the next hourly roll"));
        if (s.hasPermission(ADMIN)) s.sendMessage(Msg.info("/exotic admin <inspect|unregister|transfer|givebook|rollnow|reload|audit|economy|effects>"));
    }

    private void overview(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            help(sender);
            return;
        }
        List<Registration> mine = plugin.registry().ownedBy(p.getUniqueId());
        p.sendMessage(Msg.info("--- Your exotic enchantments (" + mine.size() + ") ---"));
        for (Registration r : mine) p.sendMessage(line(r, false));
        if (mine.isEmpty()) p.sendMessage(Msg.info("None yet. Hold an item and stay active for the hourly roll!"));
        p.sendMessage(Msg.info("Next hourly roll in " + Msg.duration(plugin.scheduler().millisUntilNext())
                + ". Shop rotates in " + Msg.duration(plugin.shop().millisUntilRotation()) + "."));
    }

    private void rolls(CommandSender s) {
        s.sendMessage(Msg.info("Next hourly roll in " + Msg.duration(plugin.scheduler().millisUntilNext()) + "."));
    }

    private void top(CommandSender s) {
        Map<UUID, int[]> counts = new HashMap<>();     // [total, legendary]
        for (Registration r : plugin.registry().all()) {
            if (r.status() != Status.ACTIVE) continue;
            int[] c = counts.computeIfAbsent(r.owner(), k -> new int[2]);
            c[0]++;
            if (r.rarity() == Rarity.LEGENDARY) c[1]++;
        }
        List<Map.Entry<UUID, int[]>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<UUID, int[]>>comparingInt(e -> -e.getValue()[0])
                .thenComparingInt(e -> -e.getValue()[1]));
        s.sendMessage(Msg.info("--- Exotic leaderboard ---"));
        if (sorted.isEmpty()) s.sendMessage(Msg.info("No exotics registered yet."));
        for (int i = 0; i < Math.min(10, sorted.size()); i++) {
            Map.Entry<UUID, int[]> e = sorted.get(i);
            s.sendMessage(Msg.info("#" + (i + 1) + " " + nameOf(e.getKey()) + " - " + e.getValue()[0]
                    + " exotic(s), " + e.getValue()[1] + " legendary"));
        }
    }

    private void inspect(CommandSender s, String[] args, boolean admin) {
        int nameIdx = admin ? 2 : 1;
        if (args.length <= nameIdx) {
            s.sendMessage(Msg.error("Usage: /exotic " + (admin ? "admin " : "") + "inspect <player>"));
            return;
        }
        OfflinePlayer target = resolve(args[nameIdx]);
        if (target == null) {
            s.sendMessage(Msg.error("Unknown player (they must have joined this server)."));
            return;
        }
        List<Registration> owned = plugin.registry().ownedBy(target.getUniqueId());
        s.sendMessage(Msg.info("--- " + args[nameIdx] + "'s exotics (" + owned.size() + ") ---"));
        for (Registration r : owned) s.sendMessage(line(r, admin));
    }

    private void remove(Player p, String[] args) {
        boolean confirm = args.length > 1 && args[1].equalsIgnoreCase("confirm");
        String res = plugin.service().removeExotic(p, confirm);
        if (res == null) p.sendMessage(Msg.ok("Exotic enchantment removed. Normal enchantments were kept."));
        else p.sendMessage(confirm || res.startsWith("Hold") || res.contains("not registered") || res.contains("needs")
                ? Msg.error(res) : Msg.info(res));
    }

    private void result(Player p, String error, String success) {
        p.sendMessage(error == null ? Msg.ok(success) : Msg.error(error));
    }

    // ----------------------------------------------------------------- admin

    private void admin(CommandSender s, String[] args) {
        if (!s.hasPermission(ADMIN)) {
            s.sendMessage(Msg.error("You don't have permission."));
            return;
        }
        if (args.length < 2) {
            help(s);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "inspect" -> inspect(s, args, true);
            case "unregister" -> adminUnregister(s, args);
            case "transfer" -> adminTransfer(s, args);
            case "givebook" -> adminGiveBook(s, args);
            case "rollnow" -> {
                plugin.scheduler().runRound();
                plugin.audit().log("ADMIN " + s.getName() + " forced a roll round");
                s.sendMessage(Msg.ok("Roll round executed."));
            }
            case "reload" -> {
                plugin.reloadAll();
                plugin.audit().log("ADMIN " + s.getName() + " reloaded config");
                s.sendMessage(Msg.ok("Configuration reloaded."));
            }
            case "audit" -> adminAudit(s);
            case "economy" -> adminEconomy(s);
            case "effects" -> adminEffects(s);
            default -> help(s);
        }
    }

    private void adminUnregister(CommandSender s, String[] args) {
        Long id = parseId(args, 2);
        if (id == null) {
            s.sendMessage(Msg.error("Usage: /exotic admin unregister <registrationId>"));
            return;
        }
        if (plugin.registry().release(id)) {
            plugin.audit().log("ADMIN " + s.getName() + " unregistered reg#" + id);
            s.sendMessage(Msg.ok("Registration #" + id + " released. The item loses its exotic automatically."));
        } else {
            s.sendMessage(Msg.error("No live registration with that id."));
        }
    }

    private void adminTransfer(CommandSender s, String[] args) {
        Long id = parseId(args, 2);
        OfflinePlayer target = args.length > 3 ? resolve(args[3]) : null;
        if (id == null || target == null) {
            s.sendMessage(Msg.error("Usage: /exotic admin transfer <registrationId> <player>"));
            return;
        }
        Registration r = plugin.registry().get(id).orElse(null);
        if (r == null || r.status() != Status.ACTIVE || !plugin.registry().changeOwner(r.itemUuid(), target.getUniqueId())) {
            s.sendMessage(Msg.error("That registration is not ACTIVE."));
            return;
        }
        plugin.audit().log("ADMIN " + s.getName() + " transferred reg#" + id + " to " + target.getUniqueId());
        s.sendMessage(Msg.ok("Registered owner changed. (The physical item does not move.)"));
    }

    private void adminGiveBook(CommandSender s, String[] args) {
        if (args.length < 5) {
            s.sendMessage(Msg.error("Usage: /exotic admin givebook <player> <enchant> <level>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        Enchantment ench = EnchantPool.lookup(args[3].toLowerCase(Locale.ROOT));
        int level;
        try {
            level = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            s.sendMessage(Msg.error("Level must be a number."));
            return;
        }
        if (target == null || ench == null || level < 1 || level > ench.getMaxLevel()) {
            s.sendMessage(Msg.error("Unknown player/enchantment, or level out of range (1-"
                    + (ench == null ? "?" : ench.getMaxLevel()) + ")."));
            return;
        }
        ItemStack book = plugin.service().createBook(ench, level);
        target.getInventory().addItem(book).values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        plugin.audit().log("ADMIN " + s.getName() + " gave book " + args[3] + " " + level + " to " + target.getName());
        s.sendMessage(Msg.ok("Gave an Exotic Book to " + target.getName() + "."));
    }

    private void adminEconomy(CommandSender s) {
        plugin.economy().setup(Bukkit.getServer());
        if (plugin.economy().available()) {
            s.sendMessage(Msg.ok("Economy READY via " + plugin.economy().providerName() + ". The shop and /exotic remove work."));
        } else {
            s.sendMessage(Msg.error("Economy NOT ready (" + plugin.economy().status() + "): " + plugin.economy().problem()));
        }
    }

    private void adminEffects(CommandSender s) {
        s.sendMessage(Msg.info("Custom effects implemented by this plugin (vanilla already handles the normal item types):"));
        s.sendMessage(Msg.info("  Frost Walker on any non-boots item: freezes water around you while it is held"));
        s.sendMessage(Msg.info("  Density on bow/crossbow: arrows shot while falling deal bonus damage (capped)"));
        s.sendMessage(Msg.info("  Sharpness, Smite, Bane of Arthropods, Fire Aspect on bow/crossbow: apply to arrows"));
        s.sendMessage(Msg.info("  Protection, Fire/Blast/Projectile Protection, Feather Falling on non-armor items: apply while held"));
        s.sendMessage(Msg.info("  Mending, Unbreaking, Vanishing Curse work on any item natively"));
        s.sendMessage(Msg.info("All other wrong-item combinations are never rolled/sold/applied (no effect). Full per-enchantment "
                + "table: plugins/ExoticEnchants/effects-audit.txt"));
    }

    private void adminAudit(CommandSender s) {
        int disputed = 0;
        int pending = 0;
        int active = 0;
        for (Registration r : plugin.registry().all()) {
            if (r.status() == Status.DISPUTED) {
                disputed++;
                s.sendMessage(line(r, true));
            } else if (r.status() == Status.PENDING) pending++;
            else if (r.status() == Status.ACTIVE) active++;
        }
        s.sendMessage(Msg.info("Active: " + active + ", pending: " + pending + ", disputed: " + disputed));
        for (String a : plugin.registry().anomalies()) s.sendMessage(Msg.error(a));
        List<String> problems = plugin.registry().checkInvariants();
        s.sendMessage(problems.isEmpty() ? Msg.ok("Registry invariants OK (no duplicate keys or items).")
                : Msg.error("Registry problems: " + problems));
    }

    // --------------------------------------------------------------- helpers

    private Component line(Registration r, boolean admin) {
        String text = Msg.titleCase(r.key().enchant()) + " " + Msg.roman(r.key().level()) + " on "
                + Msg.titleCase(r.key().material()) + " [" + Msg.titleCase(r.rarity().name()) + "]";
        if (admin) text += "  (reg#" + r.id() + ", " + r.status() + ", item " + r.itemUuid().toString().substring(0, 8) + ")";
        return Component.text("  " + text, Msg.rarityColor(r.rarity()));
    }

    private static Long parseId(String[] args, int idx) {
        if (args.length <= idx) return null;
        try {
            return Long.parseLong(args[idx].replace("#", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private static String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    private void withPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (sender instanceof Player p) action.accept(p);
        else sender.sendMessage(Msg.error("Only players can use this."));
    }

    // ------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("top", "inspect", "apply", "shop", "remove", "rolls"));
            if (sender.hasPermission(ADMIN)) out.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            out.add("confirm");
        } else if (args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            if (args.length == 2) {
                out.addAll(List.of("inspect", "unregister", "transfer", "givebook", "rollnow", "reload", "audit", "economy", "effects"));
            } else if (args.length == 3 && List.of("inspect", "givebook").contains(args[1].toLowerCase(Locale.ROOT))) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 4 && args[1].equalsIgnoreCase("transfer")) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 4 && args[1].equalsIgnoreCase("givebook")) {
                for (Enchantment e : plugin.pool().allEnchants()) out.add(e.getKey().getKey());
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
