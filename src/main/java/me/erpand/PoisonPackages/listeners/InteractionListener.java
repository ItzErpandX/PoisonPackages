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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InteractionListener implements Listener {
    // Cooldown in milliseconds (e.g., 1000 ms = 1 second)
    private static final long CLAIM_COOLDOWN_MS = 3000;

    // Tracks the last time each player successfully STARTED a claim
    private final Map<UUID, Long> lastClaimTime = new ConcurrentHashMap<>();

    // Tracks which package IDs are currently mid-claim (async DB round trip in flight)
    // Prevents two clicks from both grabbing the same package before the first finishes.
    private final Set<Long> claimsInProgress = ConcurrentHashMap.newKeySet();

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
        // Only handle the main-hand firing of the event. Bukkit can fire this event
        // twice per physical right-click (once for MAIN_HAND, once for OFF_HAND/AIR),
        // which is another common cause of "double claim" bugs.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!event.hasItem() || event.getItem().getType() != Material.CHEST) {
            return;
        }

        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(PackageUtils.getPackageIdKey())) {
            return;
        }

        Long packageId = meta.getPersistentDataContainer().get(PackageUtils.getPackageIdKey(), PersistentDataType.LONG);
        if (packageId == null) {
            return;
        }

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

        // 1) Per-player cooldown check
        Long last = lastClaimTime.get(uuid);
        if (last != null && (now - last) < CLAIM_COOLDOWN_MS) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>Please wait before claiming another package!"));
            return;
        }

        // 2) Per-package lock: if this exact package is already being claimed
        // (e.g. two rapid clicks landed before the first async task finished),
        // reject the duplicate immediately instead of racing the database.
        if (!claimsInProgress.add(packageId)) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>This package is already being claimed!"));
            return;
        }

        // 3) Set the cooldown NOW, synchronously, before doing any async work.
        // This is the critical fix: previously the cooldown was only recorded
        // after the async DB round-trip completed, leaving a window where
        // multiple clicks could all pass the check before any of them "landed".
        lastClaimTime.put(uuid, now);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String serializedItems = databaseManager.getPackageItems(packageId);
            List<ItemStack> items = itemSerializer.deserializeItems(serializedItems);

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (items == null || items.isEmpty()) {
                        player.sendMessage(MINI_MESSAGE.deserialize(
                                plugin.getConfig().getString("messages.package-not-found", "<red>Package not found or empty!")));
                        return;
                    }

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
                } finally {
                    // Always release the per-package lock, even if something above threw,
                    // so a failed claim doesn't permanently block that package.
                    claimsInProgress.remove(packageId);
                }
            });
        });
    }
}