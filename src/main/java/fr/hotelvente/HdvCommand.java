package fr.hotelvente;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HdvCommand implements CommandExecutor, TabCompleter {
    private final HotelDeVente plugin;

    public HdvCommand(HotelDeVente plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("hdv.admin")) {
                plugin.send(sender, "no-permission");
                return true;
            }
            plugin.reloadAll();
            plugin.send(sender, "reload");
            return true;
        }

        if (!(sender instanceof Player p)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (!p.hasPermission("hdv.use")) {
            plugin.send(p, "no-permission");
            return true;
        }

        switch (sub) {
            case "open", "menu" -> plugin.menus().openMain(p, 1);
            case "unsold", "invendus" -> plugin.menus().openUnsold(p, 1);
            case "sell", "vendre" -> sell(p, args);
            default -> {
                for (String line : plugin.messages().getStringList("help")) {
                    p.sendMessage(Text.parse(line));
                }
            }
        }
        return true;
    }

    private void sell(Player p, String[] args) {
        if (!p.hasPermission("hdv.sell")) {
            plugin.send(p, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(p, "usage-sell");
            return;
        }

        double price;
        try {
            price = Double.parseDouble(args[1].replace(',', '.'));
        } catch (NumberFormatException ex) {
            plugin.send(p, "invalid-price");
            return;
        }
        if (Double.isNaN(price) || Double.isInfinite(price) || price <= 0) {
            plugin.send(p, "invalid-price");
            return;
        }
        price = round2(price);

        double min = plugin.getConfig().getDouble("min-price", 1.0);
        double max = plugin.getConfig().getDouble("max-price", 1_000_000_000.0);
        if (price < min) {
            plugin.send(p, "price-too-low", "min", plugin.money(min));
            return;
        }
        if (price > max) {
            plugin.send(p, "price-too-high", "max", plugin.money(max));
            return;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.send(p, "no-item");
            return;
        }
        if (isBlacklisted(hand)) {
            plugin.send(p, "blacklisted");
            return;
        }

        UUID uuid = p.getUniqueId();
        int maxSlots = plugin.manager().maxSlots(p);
        if (plugin.manager().activeOf(uuid).size() >= maxSlots) {
            plugin.send(p, "slots-full", "max", String.valueOf(maxSlots));
            return;
        }

        Economy eco = plugin.economy();
        double tax = round2(price * plugin.getConfig().getDouble("tax-percent", 5.0) / 100.0);
        if (tax > 0) {
            if (!eco.has(p, tax)) {
                plugin.send(p, "tax-no-funds", "tax", plugin.money(tax),
                        "balance", plugin.money(eco.getBalance(p)), "price", plugin.money(price));
                return;
            }
            EconomyResponse r = eco.withdrawPlayer(p, tax);
            if (!r.transactionSuccess()) {
                plugin.send(p, "economy-error");
                return;
            }
        }

        ItemStack copy = hand.clone();
        p.getInventory().setItemInMainHand(null);

        long now = System.currentTimeMillis();
        long duration = (long) (plugin.getConfig().getDouble("duration-hours", 48) * 3_600_000L);
        Listing l = new Listing(UUID.randomUUID(), uuid, p.getName(), copy, price, now, now + duration);
        plugin.manager().addActive(l);

        plugin.send(p, "sell-success",
                "quantity", String.valueOf(copy.getAmount()),
                "price", plugin.money(price),
                "tax", plugin.money(tax),
                "time", plugin.timeLeft(duration));
    }

    private boolean isBlacklisted(ItemStack item) {
        List<String> list = plugin.getConfig().getStringList("blacklist");
        String nexo = null;
        boolean nexoChecked = false;
        for (String entry : list) {
            if (entry.toLowerCase().startsWith("nexo:")) {
                if (!nexoChecked) {
                    nexo = ItemParser.nexoId(item);
                    nexoChecked = true;
                }
                if (nexo != null && nexo.equalsIgnoreCase(entry.substring(5))) return true;
            } else if (item.getType().name().equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("sell", "unsold", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        }
        return out;
    }
}
