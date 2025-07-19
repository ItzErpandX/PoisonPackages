package me.erpand.PoisonPackages.commands;

import me.erpand.PoisonPackages.PoisonPackages;
import me.erpand.PoisonPackages.database.DatabaseManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;

public class CommandHandler implements TabCompleter {
    
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    
    private final PoisonPackages plugin;
    private final DatabaseManager databaseManager;
    private final Set<UUID> playersWithPackagesEnabled;

    public CommandHandler(PoisonPackages plugin, DatabaseManager databaseManager, Set<UUID> playersWithPackagesEnabled) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.playersWithPackagesEnabled = playersWithPackagesEnabled;
    }

    public boolean onCommand(@NotNull CommandSender sender, String[] args) {
        if (args.length == 0) {
            return sendHelp(sender);
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReloadCommand(sender);
            case "cleanup" -> handleCleanupCommand(sender);
            case "forcecleanup" -> handleForceCleanupCommand(sender);
            case "toggle" -> handleToggleCommand(sender);
            default -> sendHelp(sender);
        };
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("poisonpackages.admin")) {
            sendNoPermission(sender);
            return false;
        }
        plugin.reloadConfig();
        sender.sendMessage(MINI_MESSAGE.deserialize(plugin.getConfig().getString("messages.config-reloaded", "<green>Configuration reloaded!")));
        return true;
    }

    private boolean handleCleanupCommand(CommandSender sender) {
        if (!sender.hasPermission("poisonpackages.admin")) {
            sendNoPermission(sender);
            return false;
        }

        // Run immediately with feedback
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int deleted = databaseManager.cleanUpExpiredPackages(); // Returns count of deleted packages

            Bukkit.getScheduler().runTask(plugin, () -> {
                String msg = plugin.getConfig().getString("messages.cleanup-complete",
                                "<green>Deleted %count% expired packages!")
                        .replace("%count%", String.valueOf(deleted));
                sender.sendMessage(MINI_MESSAGE.deserialize(msg));

                // Optional: Log to console too
                plugin.getLogger().info("Manual cleanup removed " + deleted + " packages");
            });
        });
        return true;
    }

    private boolean handleForceCleanupCommand(CommandSender sender) {
        if (!sender.hasPermission("poisonpackages.admin")) {
            sendNoPermission(sender);
            return false;
        }

        // Run immediately with feedback
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int deleted = databaseManager.forceCleanAllPackages(); // Clean ALL packages regardless of age

            Bukkit.getScheduler().runTask(plugin, () -> {
                String msg = plugin.getConfig().getString("messages.forcecleanup-complete",
                                "<green>Force deleted ALL %count% packages!")
                        .replace("%count%", String.valueOf(deleted));
                sender.sendMessage(MINI_MESSAGE.deserialize(msg));

                // Optional: Log to console too
                plugin.getLogger().info("Force cleanup removed ALL " + deleted + " packages");
            });
        });
        return true;
    }

    private boolean handleToggleCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(MINI_MESSAGE.deserialize(plugin.getConfig().getString("messages.player-only", "<red>This command can only be used by players!")));
            return false;
        }
        if (!sender.hasPermission("poisonpackages.toggle")) {
            sendNoPermission(sender);
            return true;
        }
        togglePlayerSetting((Player) sender);
        return true;
    }

    private void togglePlayerSetting(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean currentlyEnabled = playersWithPackagesEnabled.contains(player.getUniqueId());

            if (currentlyEnabled) {
                // Player is disabling packages
                playersWithPackagesEnabled.remove(player.getUniqueId());
                databaseManager.removePlayerPreference(player.getUniqueId());
            } else {
                // Player is enabling packages
                playersWithPackagesEnabled.add(player.getUniqueId());
                databaseManager.savePlayerPreference(player.getUniqueId());
            }

            String message = currentlyEnabled ?
                    plugin.getConfig().getString("messages.package-disabled", "<red>You will no longer receive death packages") :
                    plugin.getConfig().getString("messages.package-enabled", "<green>You will now receive death packages");

            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(MINI_MESSAGE.deserialize(message)));
        });
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();
            
            // Available commands based on permissions
            if (sender.hasPermission("poisonpackages.admin")) {
                if ("reload".startsWith(input)) completions.add("reload");
                if ("cleanup".startsWith(input)) completions.add("cleanup");
                if ("forcecleanup".startsWith(input)) completions.add("forcecleanup");
            }
            if (sender.hasPermission("poisonpackages.toggle")) {
                if ("toggle".startsWith(input)) completions.add("toggle");
            }
            
            return completions;
        }
        return new ArrayList<>();
    }

    private boolean sendHelp(CommandSender sender) {
        List<String> helpMessages = plugin.getConfig().getStringList("messages.help");
        if (helpMessages.isEmpty()) {
            helpMessages = Arrays.asList(
                    "<gold>PoisonPackages Commands:",
                    "<yellow>/poisonpackages reload <gray>- Reload the configuration",
                    "<yellow>/poisonpackages cleanup <gray>- Cleanup expired packages",
                    "<yellow>/poisonpackages forcecleanup <gray>- Force cleanup ALL packages",
                    "<yellow>/poisonpackages toggle <gray>- Toggle receiving packages"
            );
        }
        helpMessages.forEach(msg -> sender.sendMessage(MINI_MESSAGE.deserialize(msg)));
        return true;
    }

    private void sendNoPermission(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(plugin.getConfig().getString("messages.no-permission", "<red>You don't have permission to do that!")));
    }
}
