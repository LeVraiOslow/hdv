package fr.hotelvente;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Une annonce de l'hôtel de vente (active ou invendue). */
public final class Listing {
    public final UUID id;
    public final UUID seller;
    public final String sellerName;
    public final ItemStack item;
    public final double price;
    public final long createdAt;
    public final long expiresAt;

    public Listing(UUID id, UUID seller, String sellerName, ItemStack item,
                   double price, long createdAt, long expiresAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
