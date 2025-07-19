package me.erpand.PoisonPackages.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;

public class ItemSerializer {
    
    private final JavaPlugin plugin;

    public ItemSerializer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public String serializeItems(List<ItemStack> items) {
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
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize items", e);
            return "";
        }
    }

    public List<ItemStack> deserializeItems(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return Collections.emptyList();
        }
        
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
            plugin.getLogger().log(Level.SEVERE, "Failed to deserialize items", e);
            return Collections.emptyList();
        }

        return items;
    }
}
