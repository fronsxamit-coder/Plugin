package com.smpx;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMP-X - all-in-one SMP admin toolkit.
 * Everything is used through the /smp command.
 */
@SuppressWarnings("deprecation")
public class SMPX extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final String PREFIX = "&8[&bSMP-X&8] &r";
    private static final long PERMANENT = Long.MAX_VALUE;

    private static final List<String> SUBS = List.of(
            "ban", "unban", "jail", "unjail", "setjail", "freeze", "unfreeze", "god",
            "feed", "heal", "fly", "invsee", "enderchest", "lightning", "nick",
            "ping", "kill", "playtime", "keep-inventory", "keep-xp");

    // Subcommands that take a player name as the first argument.
    private static final Set<String> NEEDS_PLAYER = Set.of(
            "ban", "unban", "jail", "unjail", "freeze", "unfreeze", "god", "feed", "heal", "fly",
            "invsee", "enderchest", "lightning", "nick", "ping", "kill", "playtime",
            "keep-inventory", "keep-xp");

    private File dataFile;

    private final Map<UUID, Long> bans = new ConcurrentHashMap<>();
    private final Map<UUID, Long> jailed = new HashMap<>();
    private final Map<UUID, Location> jailReturn = new HashMap<>();
    private final Map<UUID, String> nicks = new HashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> god = new HashSet<>();
    private final Set<UUID> keepInv = new HashSet<>();
    private final Set<UUID> keepXp = new HashSet<>();
    private Location jailLocation;

    // ------------------------------------------------------------------
    // Startup / shutdown
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        PluginCommand command = getCommand("smp");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(this, 20L, 20L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            applyNick(p);
        }
        getLogger().info("SMP-X enabled. Type /smp in game to see all commands.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------
    // Main command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            sendHelp(s);
            return true;
        }

        String sub = normalize(a[0]);
        if (!SUBS.contains(sub)) {
            send(s, "&cUnknown command. Type &e/smp &cto see all commands.");
            return true;
        }

        // Commands that everybody can use.
        if (sub.equals("ping")) {
            cmdPing(s, a);
            return true;
        }
        if (sub.equals("playtime")) {
            cmdPlaytime(s, a);
            return true;
        }

        // Owner-only command.
        if (sub.equals("god")) {
            if (!s.hasPermission("smpx.owner")) {
                send(s, "&cOnly the server owner can use this command.");
                return true;
            }
            cmdGod(s, a);
            return true;
        }

        // Admin commands.
        if (!s.hasPermission("smpx.admin")) {
            send(s, "&cYou do not have permission to use this command.");
            return true;
        }

        switch (sub) {
            case "ban" -> cmdBan(s, a);
            case "unban" -> cmdUnban(s, a);
            case "jail" -> cmdJail(s, a);
            case "unjail" -> cmdUnjail(s, a);
            case "setjail" -> cmdSetJail(s);
            case "freeze" -> cmdFreeze(s, a);
            case "unfreeze" -> cmdUnfreeze(s, a);
            case "feed" -> cmdFeed(s, a);
            case "heal" -> cmdHeal(s, a);
            case "fly" -> cmdFly(s, a);
            case "invsee" -> cmdInvsee(s, a);
            case "enderchest" -> cmdEnderChest(s, a);
            case "lightning" -> cmdLightning(s, a);
            case "nick" -> cmdNick(s, a);
            case "kill" -> cmdKill(s, a);
            case "keep-inventory" -> cmdKeep(s, a, keepInv, "keep inventory");
            case "keep-xp" -> cmdKeep(s, a, keepXp, "keep XP");
            default -> sendHelp(s);
        }
        return true;
    }

    private String normalize(String raw) {
        String sub = raw.toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "ender-chest", "echest", "ec" -> "enderchest";
            case "keepinventory", "keepinv", "keep-inv" -> "keep-inventory";
            case "keepxp" -> "keep-xp";
            default -> sub;
        };
    }

    private void sendHelp(CommandSender s) {
        send(s, "&b&lSMP-X &7- all commands");
        help(s, "/smp ban <player> [time]", "Ban a player. Time: 30s, 10m, 2h, 1d or perm");
        help(s, "/smp unban <player>", "Unban a player");
        help(s, "/smp jail <player> [time]", "Send a player to jail");
        help(s, "/smp unjail <player>", "Release a player from jail");
        help(s, "/smp setjail", "Set the jail at your location");
        help(s, "/smp freeze <player>", "Freeze a player in place");
        help(s, "/smp unfreeze <player>", "Unfreeze a player");
        help(s, "/smp god [player]", "Toggle god mode - no one can hit you (owner only)");
        help(s, "/smp feed [player]", "Fill the hunger bar");
        help(s, "/smp heal [player]", "Heal a player to full health");
        help(s, "/smp fly [player]", "Toggle flight");
        help(s, "/smp invsee <player>", "Open a player's inventory");
        help(s, "/smp enderchest <player>", "Open a player's ender chest");
        help(s, "/smp lightning <player>", "Strike a player with one lightning bolt");
        help(s, "/smp nick <player> <newname|reset>", "Change a player's display name");
        help(s, "/smp ping [player]", "Show ping (green = low, yellow = medium, red = high)");
        help(s, "/smp kill <player>", "Kill a player");
        help(s, "/smp playtime [player]", "Show playtime (everyone if no name is given)");
        help(s, "/smp keep-inventory <player>", "Toggle keep inventory for one player");
        help(s, "/smp keep-xp <player>", "Toggle keep XP for one player");
    }

    // ------------------------------------------------------------------
    // Ban / unban
    // ------------------------------------------------------------------

    private void cmdBan(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp ban <player> [time]", "Time examples: 30s, 10m, 2h, 1d or perm");
            return;
        }
        OfflinePlayer target = findPlayer(a[1]);
        if (target == null) {
            send(s, "&cPlayer &e" + a[1] + " &cwas not found. They must have joined the server before.");
            return;
        }
        long seconds = 0;
        if (a.length >= 3) {
            seconds = parseTime(a[2]);
            if (seconds < 0) {
                send(s, "&cInvalid time. Use for example 30s, 10m, 2h, 1d or perm.");
                return;
            }
        }
        long until = seconds == 0 ? PERMANENT : System.currentTimeMillis() + seconds * 1000L;
        bans.put(target.getUniqueId(), until);
        saveData();

        Player online = target.getPlayer();
        if (online != null) {
            online.kickPlayer(banScreen(until));
        }
        send(s, "&e" + nameOf(target) + " &7has been banned "
                + (seconds == 0 ? "&cpermanently&7." : "for &e" + formatTime(seconds) + "&7."));
    }

    private void cmdUnban(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp unban <player>");
            return;
        }
        UUID id = null;
        OfflinePlayer target = findPlayer(a[1]);
        if (target != null && bans.containsKey(target.getUniqueId())) {
            id = target.getUniqueId();
        } else {
            for (UUID banned : bans.keySet()) {
                String n = Bukkit.getOfflinePlayer(banned).getName();
                if (n != null && n.equalsIgnoreCase(a[1])) {
                    id = banned;
                    break;
                }
            }
        }
        if (id == null) {
            send(s, "&e" + a[1] + " &cis not banned.");
            return;
        }
        bans.remove(id);
        saveData();
        send(s, "&e" + a[1] + " &7has been unbanned.");
    }

    private String banScreen(long until) {
        String left = until == PERMANENT
                ? "&fPermanent"
                : "&f" + formatTime(Math.max(1, (until - System.currentTimeMillis()) / 1000L));
        return c("&cYou are banned from this server.\n&7Time left: " + left);
    }

    // ------------------------------------------------------------------
    // Jail / unjail
    // ------------------------------------------------------------------

    private void cmdSetJail(CommandSender s) {
        if (!(s instanceof Player p)) {
            send(s, "&cOnly players can set the jail.");
            return;
        }
        jailLocation = p.getLocation();
        saveData();
        send(s, "&aThe jail has been set at your location.");
    }

    private void cmdJail(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp jail <player> [time]", "Time examples: 30s, 10m, 2h, 1d or perm");
            return;
        }
        if (jailLocation == null) {
            send(s, "&cThe jail is not set yet. Stand where you want it and type &e/smp setjail&c.");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        long seconds = 0;
        if (a.length >= 3) {
            seconds = parseTime(a[2]);
            if (seconds < 0) {
                send(s, "&cInvalid time. Use for example 30s, 10m, 2h, 1d or perm.");
                return;
            }
        }
        UUID id = target.getUniqueId();
        if (!jailed.containsKey(id)) {
            jailReturn.put(id, target.getLocation());
        }
        jailed.put(id, seconds == 0 ? PERMANENT : System.currentTimeMillis() + seconds * 1000L);
        saveData();
        target.teleport(jailLocation);

        String length = seconds == 0 ? "until an admin releases you" : "for " + formatTime(seconds);
        send(target, "&cYou have been jailed " + length + ".");
        send(s, "&e" + target.getName() + " &7has been jailed"
                + (seconds == 0 ? "." : " for &e" + formatTime(seconds) + "&7."));
    }

    private void cmdUnjail(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp unjail <player>");
            return;
        }
        OfflinePlayer target = findPlayer(a[1]);
        if (target == null || !jailed.containsKey(target.getUniqueId())) {
            send(s, "&e" + a[1] + " &cis not in jail.");
            return;
        }
        release(target.getUniqueId());
        send(s, "&e" + nameOf(target) + " &7has been released from jail.");
    }

    private void release(UUID id) {
        jailed.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            Location back = jailReturn.remove(id);
            p.teleport(back != null ? back : p.getWorld().getSpawnLocation());
            send(p, "&aYou have been released from jail.");
        }
        saveData();
    }

    // ------------------------------------------------------------------
    // Freeze / unfreeze
    // ------------------------------------------------------------------

    private void cmdFreeze(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp freeze <player>");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        frozen.add(target.getUniqueId());
        saveData();
        send(target, "&bYou have been frozen. You cannot move.");
        send(s, "&e" + target.getName() + " &7is now frozen.");
    }

    private void cmdUnfreeze(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp unfreeze <player>");
            return;
        }
        OfflinePlayer target = findPlayer(a[1]);
        if (target == null || !frozen.contains(target.getUniqueId())) {
            send(s, "&e" + a[1] + " &cis not frozen.");
            return;
        }
        frozen.remove(target.getUniqueId());
        saveData();
        Player online = target.getPlayer();
        if (online != null) {
            send(online, "&aYou have been unfrozen. You can move again.");
        }
        send(s, "&e" + nameOf(target) + " &7is no longer frozen.");
    }

    // ------------------------------------------------------------------
    // God / feed / heal / fly
    // ------------------------------------------------------------------

    private void cmdGod(CommandSender s, String[] a) {
        Player target = targetOrSelf(s, a);
        if (target == null) {
            return;
        }
        boolean on = toggle(god, target.getUniqueId());
        send(target, on ? "&aGod mode enabled. No one can hit you." : "&cGod mode disabled.");
        if (!target.equals(s)) {
            send(s, "&7God mode for &e" + target.getName() + "&7: " + (on ? "&aON" : "&cOFF"));
        }
    }

    private void cmdFeed(CommandSender s, String[] a) {
        Player target = targetOrSelf(s, a);
        if (target == null) {
            return;
        }
        target.setFoodLevel(20);
        target.setSaturation(20f);
        send(target, "&aYour hunger has been filled.");
        if (!target.equals(s)) {
            send(s, "&7You fed &e" + target.getName() + "&7.");
        }
    }

    private void cmdHeal(CommandSender s, String[] a) {
        Player target = targetOrSelf(s, a);
        if (target == null) {
            return;
        }
        target.setHealth(target.getMaxHealth());
        target.setFireTicks(0);
        send(target, "&aYou have been healed.");
        if (!target.equals(s)) {
            send(s, "&7You healed &e" + target.getName() + "&7.");
        }
    }

    private void cmdFly(CommandSender s, String[] a) {
        Player target = targetOrSelf(s, a);
        if (target == null) {
            return;
        }
        boolean enable = !target.getAllowFlight();
        target.setAllowFlight(enable);
        if (!enable) {
            target.setFlying(false);
        }
        send(target, enable ? "&aFlight enabled." : "&cFlight disabled.");
        if (!target.equals(s)) {
            send(s, "&7Flight for &e" + target.getName() + "&7: " + (enable ? "&aON" : "&cOFF"));
        }
    }

    // ------------------------------------------------------------------
    // Invsee / ender chest
    // ------------------------------------------------------------------

    private void cmdInvsee(CommandSender s, String[] a) {
        if (!(s instanceof Player viewer)) {
            send(s, "&cOnly players can open inventories.");
            return;
        }
        if (a.length < 2) {
            usage(s, "/smp invsee <player>");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        viewer.openInventory(target.getInventory());
        send(s, "&7Opened the inventory of &e" + target.getName() + "&7.");
    }

    private void cmdEnderChest(CommandSender s, String[] a) {
        if (!(s instanceof Player viewer)) {
            send(s, "&cOnly players can open ender chests.");
            return;
        }
        if (a.length < 2) {
            usage(s, "/smp enderchest <player>");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        viewer.openInventory(target.getEnderChest());
        send(s, "&7Opened the ender chest of &e" + target.getName() + "&7.");
    }

    // ------------------------------------------------------------------
    // Lightning / kill
    // ------------------------------------------------------------------

    private void cmdLightning(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp lightning <player>");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        // Exactly one lightning bolt.
        target.getWorld().strikeLightning(target.getLocation());
        send(s, "&7Lightning struck &e" + target.getName() + "&7.");
    }

    private void cmdKill(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/smp kill <player>");
            return;
        }
        Player target = online(s, a[1]);
        if (target == null) {
            return;
        }
        target.setHealth(0.0);
        send(s, "&e" + target.getName() + " &7has been killed.");
    }

    // ------------------------------------------------------------------
    // Nick
    // ------------------------------------------------------------------

    private void cmdNick(CommandSender s, String[] a) {
        if (a.length < 3) {
            usage(s, "/smp nick <realplayername> <newname|reset>", "You can use colour codes like &a or &6");
            return;
        }
        OfflinePlayer target = findPlayer(a[1]);
        if (target == null) {
            send(s, "&cPlayer &e" + a[1] + " &cwas not found. They must have joined the server before.");
            return;
        }
        String newName = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
        UUID id = target.getUniqueId();

        if (newName.equalsIgnoreCase("reset") || newName.equalsIgnoreCase("off")) {
            nicks.remove(id);
            send(s, "&7The nickname of &e" + nameOf(target) + " &7has been reset.");
        } else {
            nicks.put(id, newName);
            send(s, "&7The nickname of &e" + nameOf(target) + " &7is now " + c(newName) + "&7.");
        }
        saveData();

        Player online = target.getPlayer();
        if (online != null) {
            applyNick(online);
        }
    }

    private void applyNick(Player p) {
        String nick = nicks.get(p.getUniqueId());
        String shown = nick == null ? p.getName() : c(nick + "&r");
        p.setDisplayName(shown);
        p.setPlayerListName(shown);
    }

    // ------------------------------------------------------------------
    // Ping (everyone can use it)
    // ------------------------------------------------------------------

    private void cmdPing(CommandSender s, String[] a) {
        if (a.length >= 2) {
            Player target = online(s, a[1]);
            if (target == null) {
                return;
            }
            send(s, "&e" + target.getName() + "&7's ping: " + pingText(target.getPing()));
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            send(s, "&cNo players are online.");
            return;
        }
        send(s, "&b&lPlayer pings &7(&agreen = low&7, &eyellow = medium&7, &cred = high&7)");
        for (Player p : Bukkit.getOnlinePlayers()) {
            send(s, "&7- &f" + p.getName() + "&7: " + pingText(p.getPing()));
        }
    }

    private String pingText(int ping) {
        String color = ping < 100 ? "&a" : (ping < 200 ? "&e" : "&c");
        return color + ping + "ms";
    }

    // ------------------------------------------------------------------
    // Playtime (everyone can use it)
    // ------------------------------------------------------------------

    private void cmdPlaytime(CommandSender s, String[] a) {
        if (a.length >= 2) {
            OfflinePlayer target = findPlayer(a[1]);
            if (target == null) {
                send(s, "&cPlayer &e" + a[1] + " &cwas not found. They must have joined the server before.");
                return;
            }
            send(s, "&e" + nameOf(target) + " &7has played: &a" + formatTime(playSeconds(target)));
            return;
        }

        List<OfflinePlayer> players = new ArrayList<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && (p.hasPlayedBefore() || p.isOnline())) {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            send(s, "&cNo playtime has been recorded yet.");
            return;
        }
        players.sort(Comparator.comparingLong(this::playSeconds).reversed());

        send(s, "&b&lPlaytime of all players");
        int limit = 30;
        int shown = 0;
        for (OfflinePlayer p : players) {
            if (shown >= limit) {
                break;
            }
            shown++;
            send(s, "&e" + shown + ". &f" + p.getName() + " &7- &a" + formatTime(playSeconds(p)));
        }
        if (players.size() > limit) {
            send(s, "&7... and " + (players.size() - limit) + " more players.");
        }
    }

    private long playSeconds(OfflinePlayer p) {
        return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
    }

    // ------------------------------------------------------------------
    // Keep inventory / keep XP (per player)
    // ------------------------------------------------------------------

    private void cmdKeep(CommandSender s, String[] a, Set<UUID> set, String what) {
        if (a.length < 2) {
            usage(s, "/smp " + a[0].toLowerCase(Locale.ROOT) + " <player>");
            return;
        }
        OfflinePlayer target = findPlayer(a[1]);
        if (target == null) {
            send(s, "&cPlayer &e" + a[1] + " &cwas not found. They must have joined the server before.");
            return;
        }
        boolean on = toggle(set, target.getUniqueId());
        send(s, "&e" + nameOf(target) + " &7will " + (on ? "&anow" : "&cno longer")
                + " &7" + what + " when they die.");
        Player online = target.getPlayer();
        if (online != null) {
            send(online, on ? "&aYou will now " + what + " when you die."
                    : "&cYou will no longer " + what + " when you die.");
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Long until = bans.get(e.getUniqueId());
        if (until == null) {
            return;
        }
        if (until != PERMANENT && System.currentTimeMillis() >= until) {
            return; // Ban has expired. The timer removes it shortly.
        }
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(until));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        applyNick(p);

        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) {
                return;
            }
            Long until = jailed.get(id);
            if (until != null) {
                if (until != PERMANENT && System.currentTimeMillis() >= until) {
                    release(id);
                } else if (jailLocation != null) {
                    p.teleport(jailLocation);
                }
            } else if (jailReturn.containsKey(id)) {
                // Was released while offline - send them back to where they were.
                Location back = jailReturn.remove(id);
                saveData();
                if (back != null) {
                    p.teleport(back);
                }
                send(p, "&aYou have been released from jail.");
            }
        });
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        UUID id = e.getPlayer().getUniqueId();

        if (jailed.containsKey(id) && jailLocation != null) {
            if (to.getWorld() != jailLocation.getWorld() || to.distanceSquared(jailLocation) > 100) {
                e.setTo(jailLocation);
            }
            return;
        }

        if (frozen.contains(id)) {
            Location from = e.getFrom();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location stay = from.clone();
                stay.setYaw(to.getYaw());
                stay.setPitch(to.getPitch());
                e.setTo(stay);
            }
        }
    }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (jailed.containsKey(p.getUniqueId()) && !p.hasPermission("smpx.admin")) {
            e.setCancelled(true);
            send(p, "&cYou cannot use commands while you are in jail.");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && god.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (keepInv.contains(id)) {
            e.setKeepInventory(true);
            e.getDrops().clear();
        }
        if (keepXp.contains(id)) {
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
    }

    // ------------------------------------------------------------------
    // Timer: expire bans and jail time
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();

        boolean changed = false;
        Iterator<Map.Entry<UUID, Long>> it = bans.entrySet().iterator();
        while (it.hasNext()) {
            long until = it.next().getValue();
            if (until != PERMANENT && now >= until) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            saveData();
        }

        for (UUID id : new ArrayList<>(jailed.keySet())) {
            long until = jailed.get(id);
            if (until != PERMANENT && now >= until) {
                release(id);
            }
        }
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();

        if (a.length == 1) {
            for (String sub : SUBS) {
                if (canUse(s, sub)) {
                    out.add(sub);
                }
            }
        } else if (a.length == 2) {
            String sub = normalize(a[0]);
            if (!canUse(s, sub)) {
                return out;
            }
            if (sub.equals("unban")) {
                for (UUID id : bans.keySet()) {
                    String n = Bukkit.getOfflinePlayer(id).getName();
                    if (n != null) {
                        out.add(n);
                    }
                }
            } else if (NEEDS_PLAYER.contains(sub)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    out.add(p.getName());
                }
            }
        } else if (a.length == 3) {
            String sub = normalize(a[0]);
            if (!canUse(s, sub)) {
                return out;
            }
            if (sub.equals("ban") || sub.equals("jail")) {
                out.addAll(List.of("30s", "10m", "1h", "1d", "perm"));
            } else if (sub.equals("nick")) {
                out.add("reset");
            }
        }

        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private boolean canUse(CommandSender s, String sub) {
        if (sub.equals("ping") || sub.equals("playtime")) {
            return true;
        }
        if (sub.equals("god")) {
            return s.hasPermission("smpx.owner");
        }
        return s.hasPermission("smpx.admin");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void send(CommandSender s, String text) {
        s.sendMessage(c(PREFIX + text));
    }

    private void help(CommandSender s, String command, String description) {
        s.sendMessage(c("&e" + command + " &8- &7" + description));
    }

    private void usage(CommandSender s, String... lines) {
        send(s, "&cUsage: &e" + lines[0]);
        for (int i = 1; i < lines.length; i++) {
            s.sendMessage(c("&7" + lines[i]));
        }
    }

    private Player online(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            send(s, "&cPlayer &e" + name + " &cis not online.");
        }
        return p;
    }

    private Player targetOrSelf(CommandSender s, String[] a) {
        if (a.length >= 2) {
            return online(s, a[1]);
        }
        if (s instanceof Player p) {
            return p;
        }
        send(s, "&cThe console must give a player name.");
        return null;
    }

    private OfflinePlayer findPlayer(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return p;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private String nameOf(OfflinePlayer p) {
        return p.getName() != null ? p.getName() : p.getUniqueId().toString();
    }

    private boolean toggle(Set<UUID> set, UUID id) {
        boolean added = set.add(id);
        if (!added) {
            set.remove(id);
        }
        saveData();
        return added;
    }

    /** Returns seconds, 0 for permanent, or -1 if the text is not valid. */
    private long parseTime(String input) {
        String t = input.toLowerCase(Locale.ROOT).trim();
        if (t.equals("perm") || t.equals("permanent") || t.equals("forever")) {
            return 0;
        }
        if (t.isEmpty()) {
            return -1;
        }
        long multiplier = 1;
        char last = t.charAt(t.length() - 1);
        if (Character.isLetter(last)) {
            switch (last) {
                case 's' -> multiplier = 1;
                case 'm' -> multiplier = 60;
                case 'h' -> multiplier = 3600;
                case 'd' -> multiplier = 86400;
                case 'w' -> multiplier = 604800;
                default -> {
                    return -1;
                }
            }
            t = t.substring(0, t.length() - 1);
        }
        try {
            long n = Long.parseLong(t);
            return n <= 0 ? -1 : n * multiplier;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String formatTime(long totalSeconds) {
        long d = totalSeconds / 86400;
        long h = (totalSeconds % 86400) / 3600;
        long m = (totalSeconds % 3600) / 60;
        long sec = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sec > 0 || sb.length() == 0) sb.append(sec).append("s");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Saving and loading (plugins/SMP-X/data.yml)
    // ------------------------------------------------------------------

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("frozen", uuidList(frozen));
        y.set("god", uuidList(god));
        y.set("keep-inventory", uuidList(keepInv));
        y.set("keep-xp", uuidList(keepXp));
        for (Map.Entry<UUID, Long> en : bans.entrySet()) {
            y.set("bans." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, Long> en : jailed.entrySet()) {
            y.set("jailed." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, Location> en : jailReturn.entrySet()) {
            y.set("jail-return." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, String> en : nicks.entrySet()) {
            y.set("nicks." + en.getKey(), en.getValue());
        }
        y.set("jail-location", jailLocation);
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        readUuids(y.getStringList("frozen"), frozen);
        readUuids(y.getStringList("god"), god);
        readUuids(y.getStringList("keep-inventory"), keepInv);
        readUuids(y.getStringList("keep-xp"), keepXp);

        ConfigurationSection sec = y.getConfigurationSection("bans");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID id = parseUuid(key);
                if (id != null) bans.put(id, sec.getLong(key));
            }
        }
        sec = y.getConfigurationSection("jailed");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID id = parseUuid(key);
                if (id != null) jailed.put(id, sec.getLong(key));
            }
        }
        sec = y.getConfigurationSection("jail-return");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID id = parseUuid(key);
                Location loc = sec.getLocation(key);
                if (id != null && loc != null) jailReturn.put(id, loc);
            }
        }
        sec = y.getConfigurationSection("nicks");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID id = parseUuid(key);
                String nick = sec.getString(key);
                if (id != null && nick != null) nicks.put(id, nick);
            }
        }
        jailLocation = y.getLocation("jail-location");
    }

    private List<String> uuidList(Set<UUID> set) {
        List<String> list = new ArrayList<>();
        for (UUID id : set) {
            list.add(id.toString());
        }
        return list;
    }

    private void readUuids(List<String> source, Set<UUID> target) {
        for (String text : source) {
            UUID id = parseUuid(text);
            if (id != null) {
                target.add(id);
            }
        }
    }

    private UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
