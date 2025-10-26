package me.erpand.PoisonPackages.listeners;

import me.erpand.PoisonPackages.database.DatabaseManager;
import me.erpand.PoisonPackages.utils.ItemSerializer;
import me.erpand.PoisonPackages.utils.PackageUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;

public class InteractionListener implements Listener {
    // Cooldown in milliseconds (e.g., 1000 ms = 1 second)
    private static final long CLAIM_COOLDOWN_MS = 3000;
    private final Map<UUID, Long> lastClaimTime = new ConcurrentHashMap<>();
    
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ItemSerializer itemSerializer;

    public InteractionListener(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.itemSerializer = new ItemSerializer(plugin);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.hasItem() || event.getItem().getType() != Material.CHEST) {
            return;
        }

        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(PackageUtils.getPackageIdKey())) {
            return;
        }

        long packageId = meta.getPersistentDataContainer().get(PackageUtils.getPackageIdKey(), PersistentDataType.LONG);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            // View items in GUI
            event.setCancelled(true);
            viewPackageContents(player, packageId);
        } else {
            // Claim items
            event.setCancelled(true);
            claimPackage(player, packageId, event.getItem());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        String guiTitle = plugin.getConfig().getString("gui-title", "<blue>Death Package Contents");
        if (event.getView().title().equals(MINI_MESSAGE.deserialize(guiTitle))) {
            event.setCancelled(true);
        }
    }

    private void viewPackageContents(Player player, long packageId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String serializedItems = databaseManager.getPackageItems(packageId);
            List<ItemStack> items = itemSerializer.deserializeItems(serializedItems);
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (items == null || items.isEmpty()) {
                    player.sendMessage(MINI_MESSAGE.deserialize(
                            plugin.getConfig().getString("messages.package-not-found", "<red>Package not found or empty!")));
                    return;
                }

                String guiTitle = plugin.getConfig().getString("gui-title", "<blue>Death Package Contents");
                Inventory gui = Bukkit.createInventory(null, 54, MINI_MESSAGE.deserialize(guiTitle));

                for (int i = 0; i < items.size() && i < 54; i++) {
                    if (items.get(i) != null) {
                        gui.setItem(i, items.get(i));
                    }
                }

                player.openInventory(gui);
            });
        });
    }

    private void claimPackage(Player player, long packageId, ItemStack packageItem) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastClaimTime.get(uuid);
        if (last != null && (now - last) < CLAIM_COOLDOWN_MS) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>Please wait before claiming another package!"));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String serializedItems = databaseManager.getPackageItems(packageId);
            List<ItemStack> items = itemSerializer.deserializeItems(serializedItems);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (items == null || items.isEmpty()) {
                    player.sendMessage(MINI_MESSAGE.deserialize(
                            plugin.getConfig().getString("messages.package-not-found", "<red>Package not found or empty!")));
                    return;
                }

                // Only set cooldown if claim is successful
                lastClaimTime.put(uuid, now);

                // Remove the package item
                packageItem.setAmount(packageItem.getAmount() - 1);

                // Give items to player or drop them
                for (ItemStack item : items) {
                    if (item == null) continue;

                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                    if (!leftover.isEmpty()) {
                        // No space left, drop the item
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover.get(0));
                    }
                }

                // Delete the package from the database
                databaseManager.deletePackage(packageId);

                player.sendMessage(MINI_MESSAGE.deserialize(
                        plugin.getConfig().getString("messages.package-claimed", "<green>Successfully claimed death package!")));
            });
        });
    }
}
