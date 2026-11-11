package fr.hotelvente;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Construction des menus (configurés dans menu.yml) et gestion des clics. */
public final class MenuManager implements Listener {

    enum Type { MAIN, UNSOLD, CONFIRM }

    static final class Holder implements InventoryHolder {
        final Type type;
        int page = 1;
        int pages = 1;
        UUID listingId;
        final Map<Integer, UUID> slotMap = new HashMap<>();
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inventory;

        Holder(Type type) { this.type = type; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final HotelDeVente plugin;

    public MenuManager(HotelDeVente plugin) {
        this.plugin = plugin;
    }

    private ListingManager manager() { return plugin.manager(); }

    // ================================================================ ouverture

    public void openMain(Player p, int page) { openPaged(p, Type.MAIN, page); }

    public void openUnsold(Player p, int page) { openPaged(p, Type.UNSOLD, page); }

    private void openPaged(Player p, Type type, int page) {
        boolean main = type == Type.MAIN;
        ConfigurationSection c = plugin.menu().getConfigurationSection(main ? "main" : "unsold");
        if (c == null) {
            p.sendMessage(Text.parse("&cMenu manquant dans menu.yml"));
            return;
        }
        int size = normSize(c.getInt("size", 54));
        List<Integer> slots = parseSlots(c.get("listing-slots"), size);
        List<Listing> data = main ? manager().sortedActive() : manager().unsoldOf(p.getUniqueId());

        int per = Math.max(1, slots.size());
        int pages = Math.max(1, (int) Math.ceil(data.size() / (double) per));
        page = Math.max(1, Math.min(page, pages));

        Holder h = new Holder(type);
        h.page = page;
        h.pages = pages;
        Map<String, String> base = Text.ph("page", String.valueOf(page), "pages", String.valueOf(pages));
        Inventory inv = Bukkit.createInventory(h, size, Text.parse(Text.replace(c.getString("title", "HDV"), base)));
        h.inventory = inv;

        placeItems(inv, h, c.getConfigurationSection("items"), base);

        List<String> lore = c.getStringList(main ? "listing-lore" : "unsold-lore");
        List<String> ownLore = main ? c.getStringList("own-listing-lore") : List.of();
        for (int i = 0; i < slots.size(); i++) {
            int idx = (page - 1) * per + i;
            if (idx >= data.size()) break;
            Listing l = data.get(idx);
            List<String> full = new ArrayList<>(lore);
            if (main && l.seller.equals(p.getUniqueId())) full.addAll(ownLore);
            inv.setItem(slots.get(i), display(l, full));
            h.slotMap.put(slots.get(i), l.id);
            h.actions.remove(slots.get(i));
        }
        p.openInventory(inv);
    }

    private void openConfirm(Player p, Listing l, int returnPage) {
        ConfigurationSection c = plugin.menu().getConfigurationSection("confirm");
        if (c == null) {
            buyAndReturn(p, l.id, returnPage);
            return;
        }
        int size = normSize(c.getInt("size", 27));
        Holder h = new Holder(Type.CONFIRM);
        h.listingId = l.id;
        h.page = returnPage;
        Inventory inv = Bukkit.createInventory(h, size, Text.parse(c.getString("title", "Confirmer")));
        h.inventory = inv;
        placeItems(inv, h, c.getConfigurationSection("items"), listingPh(l));
        int slot = c.getInt("item-slot", 13);
        if (slot >= 0 && slot < size) {
            inv.setItem(slot, display(l, c.getStringList("item-lore")));
            h.actions.remove(slot);
        }
        p.openInventory(inv);
    }

    // ================================================================ helpers

    private void placeItems(Inventory inv, Holder h, ConfigurationSection items, Map<String, String> ph) {
        if (items == null) return;
        for (String key : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) continue;
            Object raw = s.get("slots") != null ? s.get("slots") : s.get("slot");
            ItemStack stack = ItemParser.fromSection(s, ph);
            String action = s.getString("action", "none").toLowerCase();
            for (int slot : parseSlots(raw, inv.getSize())) {
                inv.setItem(slot, stack.clone());
                h.actions.put(slot, action);
            }
        }
    }

    private Map<String, String> listingPh(Listing l) {
        return Text.ph(
                "quantity", String.valueOf(l.item.getAmount()),
                "seller", l.sellerName,
                "price", plugin.money(l.price),
                "time", plugin.timeLeft(l.expiresAt - System.currentTimeMillis()));
    }

    /** Item vendu + lignes de description configurées. */
    private ItemStack display(Listing l, List<String> loreLines) {
        ItemStack d = l.item.clone();
        ItemMeta meta = d.getItemMeta();
        if (meta == null) return d;
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.addAll(Text.parseList(loreLines, listingPh(l)));
        meta.lore(lore);
        d.setItemMeta(meta);
        return d;
    }

    private static int normSize(int size) {
        return Math.max(9, Math.min(54, (size / 9) * 9));
    }

    /** Accepte un nombre, une plage "0-44" ou une liste de ceux-ci. */
    private static List<Integer> parseSlots(Object raw, int size) {
        List<Integer> out = new ArrayList<>();
        if (raw == null) return out;
        List<?> list = raw instanceof List<?> l ? l : List.of(raw);
        for (Object o : list) {
            String str = String.valueOf(o).trim();
            try {
                if (str.contains("-")) {
                    String[] part = str.split("-");
                    int a = Integer.parseInt(part[0].trim());
                    int b = Integer.parseInt(part[1].trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(str));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        out.removeIf(i -> i < 0 || i >= size);
        return out;
    }

    /** Vérifie (sans rien modifier) que l'item rentre dans l'inventaire. */
    private static boolean canFit(Player p, ItemStack item) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        Inventory tmp = Bukkit.createInventory(null, 36);
        for (int i = 0; i < contents.length && i < 36; i++) {
            tmp.setItem(i, contents[i] == null ? null : contents[i].clone());
        }
        return tmp.addItem(item.clone()).isEmpty();
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    // ================================================================ clics

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getSlot();
        ClickType click = e.getClick();
        UUID listingId = h.slotMap.get(slot);

        switch (h.type) {
            case MAIN -> {
                if (listingId != null) {
                    clickListing(p, h, listingId, click);
                    return;
                }
                runAction(p, h, h.actions.get(slot));
            }
            case UNSOLD -> {
                if (listingId != null) {
                    if (click.isLeftClick()) claim(p, h, listingId);
                    return;
                }
                runAction(p, h, h.actions.get(slot));
            }
            case CONFIRM -> runAction(p, h, h.actions.get(slot));
        }
    }

    private void runAction(Player p, Holder h, String action) {
        if (action == null) return;
        switch (action) {
            case "next-page" -> {
                if (h.page < h.pages) later(() -> openPaged(p, h.type, h.page + 1));
            }
            case "previous-page" -> {
                if (h.page > 1) later(() -> openPaged(p, h.type, h.page - 1));
            }
            case "open-unsold" -> later(() -> openUnsold(p, 1));
            case "open-main" -> later(() -> openMain(p, 1));
            case "close" -> later(p::closeInventory);
            case "confirm" -> {
                if (h.type == Type.CONFIRM && h.listingId != null) {
                    later(() -> buyAndReturn(p, h.listingId, h.page));
                }
            }
            case "cancel" -> later(() -> openMain(p, h.page));
            default -> { }
        }
    }

    // ---------------------------------------------------------------- annonces

    private void clickListing(Player p, Holder h, UUID id, ClickType click) {
        Listing l = manager().getActive(id);
        if (l == null) {
            plugin.send(p, "listing-gone");
            later(() -> openMain(p, h.page));
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            if (click.isRightClick()) {
                manager().moveToUnsold(l);
                plugin.send(p, "cancelled");
                later(() -> openMain(p, h.page));
            } else {
                plugin.send(p, "own-listing-left");
            }
            return;
        }
        if (!click.isLeftClick()) return;
        if (plugin.getConfig().getBoolean("confirm-purchase", true)) {
            later(() -> openConfirm(p, l, h.page));
        } else {
            later(() -> buyAndReturn(p, id, h.page));
        }
    }

    private void buyAndReturn(Player p, UUID id, int page) {
        buy(p, id);
        openMain(p, page);
    }

    private void buy(Player p, UUID id) {
        Listing l = manager().getActive(id);
        if (l == null) {
            plugin.send(p, "listing-gone");
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            plugin.send(p, "own-listing-left");
            return;
        }
        Economy eco = plugin.economy();
        if (!eco.has(p, l.price)) {
            plugin.send(p, "no-funds");
            return;
        }
        if (!canFit(p, l.item)) {
            plugin.send(p, "inventory-full");
            return;
        }
        EconomyResponse w = eco.withdrawPlayer(p, l.price);
        if (!w.transactionSuccess()) {
            plugin.send(p, "economy-error");
            return;
        }
        EconomyResponse d = eco.depositPlayer(Bukkit.getOfflinePlayer(l.seller), l.price);
        if (!d.transactionSuccess()) {
            eco.depositPlayer(p, l.price); // remboursement
            plugin.send(p, "economy-error");
            return;
        }
        manager().removeActive(id);
        p.getInventory().addItem(l.item.clone());

        String price = plugin.money(l.price);
        String qty = String.valueOf(l.item.getAmount());
        plugin.send(p, "bought", "quantity", qty, "price", price, "seller", l.sellerName);
        if (plugin.getConfig().getBoolean("notify-seller-on-sale", true)) {
            Player seller = Bukkit.getPlayer(l.seller);
            if (seller != null) plugin.send(seller, "sold-notify", "buyer", p.getName(), "quantity", qty, "price", price);
        }
    }

    private void claim(Player p, Holder h, UUID id) {
        Listing l = manager().getUnsold(id);
        if (l == null || !l.seller.equals(p.getUniqueId())) {
            later(() -> openUnsold(p, h.page));
            return;
        }
        if (!canFit(p, l.item)) {
            plugin.send(p, "inventory-full");
            return;
        }
        manager().removeUnsold(id);
        p.getInventory().addItem(l.item.clone());
        plugin.send(p, "claimed");
        later(() -> openUnsold(p, h.page));
    }
}
