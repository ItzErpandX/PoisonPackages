package me.erpand.PoisonPackages;

import me.erpand.PoisonPackages.commands.CommandHandler;
import me.erpand.PoisonPackages.database.DatabaseManager;
import me.erpand.PoisonPackages.listeners.DeathListener;
import me.erpand.PoisonPackages.listeners.InteractionListener;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PoisonPackages extends JavaPlugin {

    private DatabaseManager databaseManager;
    private CommandHandler commandHandler;
    private final Set<UUID> playersWithPackagesEnabled = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        // Load configuration
        saveDefaultConfig();
        reloadConfiguration();

        // Initialize database
        databaseManager = new DatabaseManager(this);
        databaseManager.initialize();

        // Load player preferences
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> 
            databaseManager.loadPlayerPreferences(playersWithPackagesEnabled));

        // Delete expired packages on startup
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int deleted = databaseManager.cleanUpExpiredPackages();
            if (deleted > 0) {
                getLogger().info("Cleaned up " + deleted + " expired packages on startup");
            }
        });

        // Initialize command handler
        commandHandler = new CommandHandler(this, databaseManager, playersWithPackagesEnabled);
        
        // Register tab completer
        getCommand("poisonpackages").setTabCompleter(commandHandler);

        // Register event listeners
        getServer().getPluginManager().registerEvents(new DeathListener(this, databaseManager, playersWithPackagesEnabled), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(this, databaseManager), this);

        getLogger().info("PoisonPackages has been enabled!");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("PoisonPackages has been disabled!");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NonNull [] args) {
        return commandHandler.onCommand(sender, args);
    }

    public void reloadConfiguration() {
        reloadConfig();
    }
}