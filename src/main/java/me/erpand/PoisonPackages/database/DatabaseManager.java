package me.erpand.PoisonPackages.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.Set;
import java.util.logging.Level;

public class DatabaseManager {
    
    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            File dataFolder = plugin.getDataFolder();
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
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to close database connection", e);
        }
    }

    public void loadPlayerPreferences(Set<UUID> playersWithPackagesEnabled) {
        try (Statement stmt = connection.createStatement()) {
            // Create table if it doesn't exist
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS player_preferences (uuid TEXT PRIMARY KEY)");

            // Load all players who have enabled packages
            ResultSet rs = stmt.executeQuery("SELECT uuid FROM player_preferences");
            while (rs.next()) {
                playersWithPackagesEnabled.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player preferences", e);
        }
    }

    public void savePlayerPreference(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO player_preferences (uuid) VALUES (?)")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player preference", e);
        }
    }

    public void removePlayerPreference(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM player_preferences WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove player preference", e);
        }
    }

    public int cleanUpExpiredPackages() {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM death_packages WHERE created_at < ?")) {

            long cutoff = System.currentTimeMillis() -
                    (plugin.getConfig().getInt("package-expiration-days", 1) * 86400000L);
            stmt.setLong(1, cutoff);

            // Return the number of deleted rows
            return stmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Cleanup failed", e);
            return 0;
        }
    }

    public int forceCleanAllPackages() {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM death_packages")) {
            
            // Return the number of deleted rows
            return stmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Force cleanup failed", e);
            return 0;
        }
    }

    public long createDeathPackage(UUID victimUuid, UUID killerUuid, String serializedItems) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO death_packages (victim_uuid, killer_uuid, created_at, items) " +
                        "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, victimUuid.toString());
            stmt.setString(2, killerUuid != null ? killerUuid.toString() : null);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, serializedItems);
            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create death package", e);
        }
        return -1;
    }

    public String getPackageItems(long packageId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT items FROM death_packages WHERE id = ?")) {
            stmt.setLong(1, packageId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("items");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get package items", e);
        }
        return null;
    }

    public void deletePackage(long packageId) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM death_packages WHERE id = ?")) {
            stmt.setLong(1, packageId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete package", e);
        }
    }
}
