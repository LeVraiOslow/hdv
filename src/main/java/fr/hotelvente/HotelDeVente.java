package fr.hotelvente;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public final class HotelDeVente extends JavaPlugin implements Listener {
    private static HotelDeVente instance;

    private Economy economy;
    private ListingManager manager;
    private MenuManager menus;
    private FileConfiguration menuCfg;
    private FileConfiguration msgCfg;
    private BukkitTask expireTask;

    public static HotelDeVente get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;

        if (!setupEconomy()) {
            getLogger().severe("Aucune économie Vault trouvée (installez Vault + un plugin d'économie).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        loadFiles();

        manager = new ListingManager(this);
        manager.load();
        menus = new MenuManager(this);

        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(this, this);

        HdvCommand cmd = new HdvCommand(this);
        getCommand("hdv").setExecutor(cmd);
        getCommand("hdv").setTabCompleter(cmd);

        startExpireTask();
        getLogger().info("HotelDeVente activé. Nexo : "
                + (getServer().getPluginManager().isPluginEnabled("Nexo") ? "détecté" : "absent")
                + " | LuckPerms : "
                + (getServer().getPluginManager().isPluginEnabled("LuckPerms") ? "détecté" : "absent"));
    }

    @Override
    public void onDisable() {
        if (expireTask != null) expireTask.cancel();
        if (manager != null) manager.shutdown();
    }

    // ------------------------------------------------------------- config

    private void loadFiles() {
        for (String name : new String[]{"menu.yml", "messages.yml"}) {
            if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
        }
        menuCfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menu.yml"));
        msgCfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
    }

    public void reloadAll() {
        reloadConfig();
        loadFiles();
        startExpireTask();
    }

    private void startExpireTask() {
        if (expireTask != null) expireTask.cancel();
        long period = Math.max(5, getConfig().getLong("expire-check-seconds", 30)) * 20L;
        expireTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Listing l : manager.tickExpire()) {
                Player p = Bukkit.getPlayer(l.seller);
                if (p != null) send(p, "expired-notice");
            }
        }, 100L, period);
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return true;
    }

    // ------------------------------------------------------------- accès

    public Economy economy() { return economy; }
    public ListingManager manager() { return manager; }
    public MenuManager menus() { return menus; }
    public FileConfiguration menu() { return menuCfg; }
    public FileConfiguration messages() { return msgCfg; }

    public String money(double amount) {
        return economy.format(amount);
    }

    public String timeLeft(long ms) {
        return Text.duration(ms, msgCfg.getString("time-format", "{h}h {m}m"),
                msgCfg.getString("time-less", "<1m"));
    }

    /** Envoie un message de messages.yml : send(p, "cle", "placeholder", "valeur", ...) */
    public void send(CommandSender to, String key, String... placeholders) {
        String raw = msgCfg.getString(key);
        if (raw == null) raw = "&cMessage manquant : " + key;
        if (raw.isBlank()) return;
        to.sendMessage(Text.parse(msgCfg.getString("prefix", "") + Text.replace(raw, Text.ph(placeholders))));
    }

    // ------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("notify-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            int count = manager.unsoldOf(p.getUniqueId()).size();
            if (count > 0) send(p, "join-notice", "count", String.valueOf(count));
        }, 60L);
    }
}
