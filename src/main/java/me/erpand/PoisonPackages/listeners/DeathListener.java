package me.erpand.PoisonPackages.listeners;

import me.erpand.PoisonPackages.PoisonPackages;
import me.erpand.PoisonPackages.database.DatabaseManager;
import me.erpand.PoisonPackages.utils.ItemSerializer;
import me.erpand.PoisonPackages.utils.PackageUtils;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DeathListener implements Listener {
        
    private final PoisonPackages plugin;
    private final DatabaseManager databaseManager;
    private final Set<UUID> playersWithPackagesEnabled;
    private final ItemSerializer itemSerializer;
    private final PackageUtils packageUtils;

    public DeathListener(PoisonPackages plugin, DatabaseManager databaseManager, Set<UUID> playersWithPackagesEnabled) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.playersWithPackagesEnabled = playersWithPackagesEnabled;
        this.itemSerializer = new ItemSerializer(plugin);
        this.packageUtils = new PackageUtils(plugin);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        Location deathLocation = victim.getLocation();

        // Check conditions for package creation
        if (shouldSkipPackageCreation(victim, event, killer)) {
            return;
        }

        // Create the death package
        createDeathPackageAsync(victim, killer, new ArrayList<>(event.getDrops()), deathLocation);
        event.getDrops().clear();
    }

    private boolean shouldSkipPackageCreation(Player victim, PlayerDeathEvent event, Player killer) {
        // Check various conditions that would skip package creation
        return victim.getGameMode() == GameMode.CREATIVE ||
                event.getKeepInventory() ||
                killer == null ||
                (!plugin.getConfig().getBoolean("default-enabled", true) &&
                        !playersWithPackagesEnabled.contains(killer.getUniqueId())) ||
                event.getDrops().isEmpty();
    }

    private void createDeathPackageAsync(Player victim, Player killer, List<ItemStack> drops, Location deathLocation) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String serializedItems = itemSerializer.serializeItems(drops);
            if (serializedItems.isEmpty()) {
                plugin.getLogger().warning("Failed to serialize items for death package");
                return;
            }

            long packageId = databaseManager.createDeathPackage(
                    victim.getUniqueId(), 
                    killer != null ? killer.getUniqueId() : null, 
                    serializedItems
            );
            
            if (packageId != -1) {
                dropPackageItem(victim, killer, packageId, deathLocation);
            }
        });
    }

    private void dropPackageItem(Player victim, Player killer, long packageId, Location deathLocation) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack packageItem = packageUtils.createDeathPackageItem(victim, killer, packageId);
            victim.getWorld().dropItemNaturally(deathLocation, packageItem);
        });
    }
}
