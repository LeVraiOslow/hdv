package fr.hotelvente;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Stockage des annonces (data.yml). Tout est manipulé sur le thread principal. */
public final class ListingManager {
    private final HotelDeVente plugin;
    private final File file;
    private final Map<UUID, Listing> active = new LinkedHashMap<>();
    private final Map<UUID, Listing> unsold = new LinkedHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public ListingManager(HotelDeVente plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------ persistance

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        read(y, "active", active);
        read(y, "unsold", unsold);
        plugin.getLogger().info(active.size() + " annonce(s) active(s), " + unsold.size() + " invendue(s) chargée(s).");
    }

    private void read(YamlConfiguration y, String root, Map<UUID, Listing> into) {
        ConfigurationSection sec = y.getConfigurationSection(root);
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection s = sec.getConfigurationSection(key);
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item")));
                UUID id = UUID.fromString(key);
                into.put(id, new Listing(id, UUID.fromString(s.getString("seller")),
                        s.getString("seller-name", "?"), item, s.getDouble("price"),
                        s.getLong("created"), s.getLong("expires")));
            } catch (Exception ex) {
                plugin.getLogger().warning("Annonce illisible ignorée (" + key + ") : " + ex.getMessage());
            }
        }
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        write(y, "active", active);
        write(y, "unsold", unsold);
        return y.saveToString();
    }

    private void write(YamlConfiguration y, String root, Map<UUID, Listing> from) {
        for (Listing l : from.values()) {
            String p = root + "." + l.id + ".";
            y.set(p + "seller", l.seller.toString());
            y.set(p + "seller-name", l.sellerName);
            y.set(p + "price", l.price);
            y.set(p + "created", l.createdAt);
            y.set(p + "expires", l.expiresAt);
            y.set(p + "item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
        }
    }

    private void writeFile(String data) {
        try {
            File tmp = new File(file.getParentFile(), "data.yml.tmp");
            Files.writeString(tmp.toPath(), data);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            plugin.getLogger().severe("Impossible de sauvegarder data.yml : " + ex.getMessage());
        }
    }

    /** Sauvegarde asynchrone (l'ordre des écritures est garanti). */
    public void save() {
        String data = serialize();
        io.submit(() -> writeFile(data));
    }

    public void shutdown() {
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        writeFile(serialize());
    }

    // ------------------------------------------------------------------ accès

    public void addActive(Listing l) {
        active.put(l.id, l);
        save();
    }

    public Listing getActive(UUID id) {
        return active.get(id);
    }

    public Listing getUnsold(UUID id) {
        return unsold.get(id);
    }

    public void removeUnsold(UUID id) {
        unsold.remove(id);
        save();
    }

    public void removeActive(UUID id) {
        active.remove(id);
        save();
    }

    /** Retire l'annonce de l'HDV et la place dans les invendus. */
    public void moveToUnsold(Listing l) {
        active.remove(l.id);
        unsold.put(l.id, l);
        save();
    }

    public List<Listing> sortedActive() {
        List<Listing> list = new ArrayList<>(active.values());
        list.sort(Comparator.comparingLong((Listing l) -> l.createdAt).reversed());
        return list;
    }

    public List<Listing> activeOf(UUID seller) {
        List<Listing> list = new ArrayList<>();
        for (Listing l : active.values()) if (l.seller.equals(seller)) list.add(l);
        return list;
    }

    public List<Listing> unsoldOf(UUID seller) {
        List<Listing> list = new ArrayList<>();
        for (Listing l : unsold.values()) if (l.seller.equals(seller)) list.add(l);
        list.sort(Comparator.comparingLong((Listing l) -> l.expiresAt).reversed());
        return list;
    }

    /** Déplace les annonces expirées vers les invendus et les retourne. */
    public List<Listing> tickExpire() {
        long now = System.currentTimeMillis();
        List<Listing> expired = new ArrayList<>();
        for (Listing l : new ArrayList<>(active.values())) {
            if (l.expiresAt <= now) {
                active.remove(l.id);
                unsold.put(l.id, l);
                expired.add(l);
            }
        }
        if (!expired.isEmpty()) save();
        return expired;
    }

    // ------------------------------------------------------------------ rangs

    /**
     * Nombre max d'annonces selon les groupes LuckPerms du joueur.
     * LuckPerms donne le node "group.&lt;nom&gt;" (héritage inclus) : on retient la valeur la plus haute.
     */
    public int maxSlots(Player p) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("slots");
        if (s == null) return 3;
        int best = s.getInt("default", 3);
        for (String group : s.getKeys(false)) {
            if (group.equalsIgnoreCase("default")) continue;
            if (p.hasPermission("group." + group.toLowerCase())) best = Math.max(best, s.getInt(group));
        }
        return best;
    }
}
