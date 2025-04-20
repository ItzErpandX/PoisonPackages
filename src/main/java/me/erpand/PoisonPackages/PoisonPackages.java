package me.erpand.PoisonPackages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PoisonPackages extends JavaPlugin implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final NamespacedKey PACKAGE_ID_KEY = new NamespacedKey("deathpackage", "package_id");

    private Connection connection;
    private FileConfiguration config;
    private final Set<UUID> playersWithPackagesEnabled = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        // Load configuration
        saveDefaultConfig();
        reloadConfiguration();

        // Initialize database
        initializeDatabase();

        // Load player preferences
        Bukkit.getScheduler().runTaskAsynchronously(this, this::loadPlayerPreferences);

        // Delete expired packages on startup
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int deleted = cleanUpExpiredPackages();
            if (deleted > 0) {
                getLogger().info("Cleaned up " + deleted + " expired packages on startup");
            }
        });
        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("DeathPackagePlugin has been enabled!");
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to close database connection", e);
        }
        getLogger().info("DeathPackagePlugin has been disabled!");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            return sendHelp(sender);
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                return handleReloadCommand(sender);
            case "cleanup":
                return handleCleanupCommand(sender);
            case "toggle":
                return handleToggleCommand(sender);
            default:
                return sendHelp(sender);
        }
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("poisonpackages.admin")) {
            sendNoPermission(sender);
            return true;
        }
        reloadConfiguration();
        sender.sendMessage(MINI_MESSAGE.deserialize(config.getString("messages.config-reloaded", "<green>Configuration reloaded!")));
        return true;
    }

    private boolean handleCleanupCommand(CommandSender sender) {
        if (!sender.hasPermission("poisonpackages.admin")) {
            sendNoPermission(sender);
            return true;
        }

        // Run immediately with feedback
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int deleted = cleanUpExpiredPackages(); // Returns count of deleted packages

            Bukkit.getScheduler().runTask(this, () -> {
                String msg = config.getString("messages.cleanup-complete",
                                "<green>Deleted %count% expired packages!")
                        .replace("%count%", String.valueOf(deleted));
                sender.sendMessage(MINI_MESSAGE.deserialize(msg));

                // Optional: Log to console too
                getLogger().info("Manual cleanup removed " + deleted + " packages");
            });
        });
        return true;
    }

    private boolean handleToggleCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MINI_MESSAGE.deserialize(config.getString("messages.player-only", "<red>This command can only be used by players!")));
            return true;
        }
        if (!sender.hasPermission("poisonpackages.toggle")) {
            sendNoPermission(sender);
            return true;
        }
        togglePlayerSetting((Player) sender);
        return true;
    }

    private void togglePlayerSetting(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean currentlyEnabled = playersWithPackagesEnabled.contains(player.getUniqueId());

            if (currentlyEnabled) {
                // Player is disabling packages
                playersWithPackagesEnabled.remove(player.getUniqueId());
                removePlayerPreference(player.getUniqueId());
            } else {
                // Player is enabling packages
                playersWithPackagesEnabled.add(player.getUniqueId());
                savePlayerPreference(player.getUniqueId());
            }

            String message = currentlyEnabled ?
                    config.getString("messages.package-disabled", "<red>You will no longer receive death packages") :
                    config.getString("messages.package-enabled", "<green>You will now receive death packages");

            Bukkit.getScheduler().runTask(this, () ->
                    player.sendMessage(MINI_MESSAGE.deserialize(message)));
        });
    }

    private void savePlayerPreference(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO player_preferences (uuid) VALUES (?)")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to save player preference", e);
        }
    }

    private void removePlayerPreference(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM player_preferences WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to remove player preference", e);
        }
    }

    private boolean sendHelp(CommandSender sender) {
        List<String> helpMessages = config.getStringList("messages.help");
        if (helpMessages.isEmpty()) {
            helpMessages = Arrays.asList(
                    "<gold>PoisonPackages Commands:",
                    "<yellow>/poisonpackages reload <gray>- Reload the configuration",
                    "<yellow>/poisonpackages cleanup <gray>- Cleanup all packages",
                    "<yellow>/poisonpackages toggle <gray>- Toggle receiving packages"
            );
        }
        helpMessages.forEach(msg -> sender.sendMessage(MINI_MESSAGE.deserialize(msg)));
        return true;
    }

    private void sendNoPermission(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(config.getString("messages.no-permission", "<red>You don't have permission to do that!")));
    }

    private void reloadConfiguration() {
        reloadConfig();
        config = getConfig();
    }

    private void loadPlayerPreferences() {
        try (Statement stmt = connection.createStatement()) {
            // Create table if it doesn't exist
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS player_preferences (uuid TEXT PRIMARY KEY)");

            // Load all players who have enabled packages
            ResultSet rs = stmt.executeQuery("SELECT uuid FROM player_preferences");
            while (rs.next()) {
                playersWithPackagesEnabled.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to load player preferences", e);
        }
    }

    private void initializeDatabase() {
        try {
            File dataFolder = getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" +
                    new File(dataFolder, "packages.db").getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                // Create packages table
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS death_packages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "victim_uuid TEXT NOT NULL," +
                        "killer_uuid TEXT," +
                        "created_at INTEGER NOT NULL," +
                        "items TEXT NOT NULL)");
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize database", e);
        }
    }


    private int cleanUpExpiredPackages() {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM death_packages WHERE created_at < ?")) {

            long cutoff = System.currentTimeMillis() -
                    (config.getInt("package-expiration-days", 1) * 86400000L);
            stmt.setLong(1, cutoff);

            // Return the number of deleted rows
            return stmt.executeUpdate();

        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Cleanup failed", e);
            return 0;
        }
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
                (!config.getBoolean("default-enabled", true) &&
                        !playersWithPackagesEnabled.contains(killer.getUniqueId())) ||
                event.getDrops().isEmpty();
    }

    private void createDeathPackageAsync(Player victim, Player killer, List<ItemStack> drops, Location deathLocation) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String serializedItems = serializeItems(drops);
            if (serializedItems.isEmpty()) {
                getLogger().warning("Failed to serialize items for death package");
                return;
            }


            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO death_packages (victim_uuid, killer_uuid, created_at, items) " +
                            "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {

                stmt.setString(1, victim.getUniqueId().toString());
                stmt.setString(2, killer != null ? killer.getUniqueId().toString() : null);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.setString(4, serializedItems);
                stmt.executeUpdate();

                ResultSet rs = stmt.getGeneratedKeys();
                if (rs.next()) {
                    long packageId = rs.getLong(1);
                    dropPackageItem(victim, killer, packageId, deathLocation);
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to create death package", e);
            }
        });
    }

    private void dropPackageItem(Player victim, Player killer, long packageId, Location deathLocation) {
        Bukkit.getScheduler().runTask(this, () -> {
            ItemStack packageItem = createDeathPackageItem(victim, killer, packageId);
            victim.getWorld().dropItemNaturally(deathLocation, packageItem);
        });
    }

    private ItemStack createDeathPackageItem(Player victim, Player killer, long packageId) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();

        // Set display name
        meta.displayName(MINI_MESSAGE.deserialize(
                config.getString("package-name", "<white>%victim%'s stash")
                        .replace("%victim%", victim.getName())
        ));

        // Build dynamic lore
        List<Component> lore = new ArrayList<>();
        List<String> loreConfig = config.getStringList("package-lore");

        if (loreConfig.isEmpty()) {
            // Default lore if none specified
            lore.add(MINI_MESSAGE.deserialize("<gray>Killed by: <white>" + (killer != null ? killer.getName() : "Unknown")));
            lore.add(MINI_MESSAGE.deserialize("<gray>Created on: <white>" + LocalDateTime.now().format(DATE_FORMAT)));
        } else {
            // Process each lore line
            for (String line : loreConfig) {
                line = line.replace("%killer%", killer != null ? killer.getName() : "Unknown")
                        .replace("%victim%", victim.getName())
                        .replace("%date%", LocalDateTime.now().format(DATE_FORMAT))
                        .replace("%id%", String.valueOf(packageId))
                        .replace("%days%", String.valueOf(config.getInt("package-expiration-days", 1)));

                if (!line.trim().isEmpty()) {
                    lore.add(MINI_MESSAGE.deserialize(line));
                }
            }
        }

        meta.lore(lore);
        meta.getPersistentDataContainer().set(PACKAGE_ID_KEY, PersistentDataType.LONG, packageId);
        item.setItemMeta(meta);
        return item;
    }

    private String serializeItems(List<ItemStack> items) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeInt(items.size());
            for (ItemStack item : items) {
                if (item == null || item.getType() == Material.AIR) {
                    dos.writeBoolean(false);
                } else {
                    dos.writeBoolean(true);
                    byte[] serialized = item.serializeAsBytes();
                    dos.writeInt(serialized.length);
                    dos.write(serialized);
                }
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to serialize items", e);
            return "";
        }
    }

    private List<ItemStack> deserializeItems(String serialized) {
        byte[] data = Base64.getDecoder().decode(serialized);
        List<ItemStack> items = new ArrayList<>();

        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
             java.io.DataInputStream dis = new java.io.DataInputStream(bais)) {

            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                if (dis.readBoolean()) {
                    int length = dis.readInt();
                    byte[] itemBytes = new byte[length];
                    dis.readFully(itemBytes);
                    items.add(ItemStack.deserializeBytes(itemBytes));
                } else {
                    items.add(null);
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to deserialize items", e);
            return Collections.emptyList();
        }

        return items;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.hasItem() || event.getItem().getType() != Material.CHEST) {
            return;
        }

        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(PACKAGE_ID_KEY)) {
            return;
        }

        long packageId = meta.getPersistentDataContainer().get(PACKAGE_ID_KEY, PersistentDataType.LONG);
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

    private void viewPackageContents(Player player, long packageId) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<ItemStack> items = getPackageItems(packageId);
            Bukkit.getScheduler().runTask(this, () -> {
                if (items == null || items.isEmpty()) {
                    player.sendMessage(MINI_MESSAGE.deserialize(
                            config.getString("messages.package-not-found", "<red>Package not found or empty!")));
                    return;
                }

                String guiTitle = config.getString("gui-title", "<blue>Death Package Contents");
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
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<ItemStack> items = getPackageItems(packageId);
            Bukkit.getScheduler().runTask(this, () -> {
                if (items == null || items.isEmpty()) {
                    player.sendMessage(MINI_MESSAGE.deserialize(
                            config.getString("messages.package-not-found", "<red>Package not found or empty!")));
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

                // Delete the package from database
                deletePackage(packageId);

                player.sendMessage(MINI_MESSAGE.deserialize(
                        config.getString("messages.package-claimed", "<green>Successfully claimed death package!")));
            });
        });
    }

    private List<ItemStack> getPackageItems(long packageId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT items FROM death_packages WHERE id = ?")) {
            stmt.setLong(1, packageId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return deserializeItems(rs.getString("items"));
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to get package items", e);
        }

        return null;
    }

    private void deletePackage(long packageId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM death_packages WHERE id = ?")) {
            stmt.setLong(1, packageId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to delete package", e);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        String guiTitle = config.getString("gui-title", "<blue>Death Package Contents");
        if (event.getView().title().equals(MINI_MESSAGE.deserialize(guiTitle))) {
            event.setCancelled(true);
        }
    }
}