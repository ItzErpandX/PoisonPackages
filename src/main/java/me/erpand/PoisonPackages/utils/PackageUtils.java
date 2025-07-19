package me.erpand.PoisonPackages.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class PackageUtils {
    
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final NamespacedKey PACKAGE_ID_KEY = new NamespacedKey("deathpackage", "package_id");

    private final JavaPlugin plugin;

    public PackageUtils(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack createDeathPackageItem(Player victim, Player killer, long packageId) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();

        // Set display name
        meta.displayName(MINI_MESSAGE.deserialize(
                plugin.getConfig().getString("package-name", "<white>%victim%'s stash")
                        .replace("%victim%", victim.getName())
        ).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        // Build dynamic lore
        List<Component> lore = new ArrayList<>();
        List<String> loreConfig = plugin.getConfig().getStringList("package-lore");

        if (loreConfig.isEmpty()) {
            // Default lore if none specified
            lore.add(MINI_MESSAGE.deserialize("<gray>Killed by: <white>" + (killer != null ? killer.getName() : "Unknown")).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            lore.add(MINI_MESSAGE.deserialize("<gray>Created on: <white>" + LocalDateTime.now().format(DATE_FORMAT)).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        } else {
            // Process each lore line
            for (String line : loreConfig) {
                line = line.replace("%killer%", killer != null ? killer.getName() : "Unknown")
                        .replace("%victim%", victim.getName())
                        .replace("%date%", LocalDateTime.now().format(DATE_FORMAT))
                        .replace("%id%", String.valueOf(packageId))
                        .replace("%days%", String.valueOf(plugin.getConfig().getInt("package-expiration-days", 1)));

                if (!line.trim().isEmpty()) {
                    lore.add(MINI_MESSAGE.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                }
            }
        }

        meta.lore(lore);
        meta.getPersistentDataContainer().set(PACKAGE_ID_KEY, PersistentDataType.LONG, packageId);
        item.setItemMeta(meta);
        return item;
    }

    public static NamespacedKey getPackageIdKey() {
        return PACKAGE_ID_KEY;
    }
}
