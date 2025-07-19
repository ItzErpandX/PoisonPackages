package me.erpand.PoisonPackages.utils;

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class SQLiteLoader {
    private final JavaPlugin plugin;
    private final BukkitLibraryManager libraryManager;

    public SQLiteLoader(JavaPlugin plugin) {
        this.plugin = plugin;
        this.libraryManager = new BukkitLibraryManager(plugin);
        this.libraryManager.addMavenCentral();
    }

    public void loadSQLite() {
        plugin.getLogger().info("Loading required SQLite library...");
        try {
            Library sqlite = Library.builder()
                    .groupId("org.xerial")
                    .artifactId("sqlite-jdbc")
                    .version("3.50.2.0")
                    .build();
            libraryManager.loadLibrary(sqlite);
            plugin.getLogger().info("SQLite loaded successfully!");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load required library", e);
            throw new RuntimeException("Could not load required library", e);
        }
    }
}
