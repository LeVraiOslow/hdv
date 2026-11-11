package fr.hotelvente;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Construit des ItemStack depuis la config : "DIAMOND" ou "nexo:mon_item".
 * Nexo est appelé par réflexion : aucune dépendance de compilation.
 */
public final class ItemParser {
    private ItemParser() {}

    public static ItemStack parse(String id) {
        if (id == null || id.isBlank()) return new ItemStack(Material.STONE);
        if (id.toLowerCase().startsWith("nexo:")) {
            ItemStack nexo = nexoItem(id.substring(5));
            if (nexo != null) return nexo;
            HotelDeVente.get().getLogger().warning("Item Nexo introuvable : " + id);
            return new ItemStack(Material.BARRIER);
        }
        Material m = Material.matchMaterial(id);
        if (m == null || m.isAir() || !m.isItem()) {
            HotelDeVente.get().getLogger().warning("Material invalide : " + id);
            return new ItemStack(Material.BARRIER);
        }
        return new ItemStack(m);
    }

    /** Item de menu depuis une section de menu.yml. */
    public static ItemStack fromSection(ConfigurationSection s, Map<String, String> ph) {
        ItemStack item = parse(s.getString("material", "STONE"));
        item.setAmount(Math.max(1, Math.min(64, s.getInt("amount", 1))));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (s.contains("name")) meta.displayName(Text.parse(Text.replace(s.getString("name"), ph)));
            List<String> lore = s.getStringList("lore");
            if (!lore.isEmpty()) meta.lore(Text.parseList(lore, ph));
            if (s.getBoolean("glow", false)) meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------------ Nexo

    private static ItemStack nexoItem(String id) {
        try {
            Class<?> c = Class.forName("com.nexomc.nexo.api.NexoItems");
            Method m = c.getMethod("itemFromId", String.class);
            Object builder = m.invoke(target(c, m), id);
            if (builder == null) return null;
            Object stack = builder.getClass().getMethod("build").invoke(builder);
            return stack instanceof ItemStack s ? s.clone() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Retourne l'id Nexo d'un item, ou null si ce n'est pas un item Nexo. */
    public static String nexoId(ItemStack item) {
        try {
            Class<?> c = Class.forName("com.nexomc.nexo.api.NexoItems");
            Method m = c.getMethod("idFromItem", ItemStack.class);
            Object r = m.invoke(target(c, m), item);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object target(Class<?> c, Method m) throws Exception {
        return Modifier.isStatic(m.getModifiers()) ? null : c.getField("INSTANCE").get(null);
    }
}
