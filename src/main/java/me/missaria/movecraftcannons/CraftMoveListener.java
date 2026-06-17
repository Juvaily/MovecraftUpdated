package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.events.CraftDetectEvent;
import net.countercraft.movecraft.events.CraftSinkEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.countercraft.movecraft.MovecraftLocation;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CraftMoveListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, List<MovecraftLocation>> spawnWaterPos = new ConcurrentHashMap<>();

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // Cannon position updates on translate are handled by Cannons' own TranslationListener
    // (hooks.movecraft.enabled=true). Adding our own update here would double every movement.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftDetect(CraftDetectEvent event) {
        Craft craft = event.getCraft();

        List<MovecraftLocation> list = waterPositions(craft);
        if (list.isEmpty()) return;

        UUID uid = craft.getUUID();
        World world = craft.getWorld();
        spawnWaterPos.put(uid, list);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            refillWaterAt(spawnWaterPos.getOrDefault(uid, list), world);
            spawnWaterPos.remove(uid);
        }, 20L * 60 * 5);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftSink(CraftSinkEvent event) {
        Craft craft = event.getCraft();
        List<MovecraftLocation> list = waterPositions(craft);
        spawnWaterPos.remove(craft.getUUID());
        if (list.isEmpty()) return;

        World world = craft.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> refillWaterAt(list, world), 20L * 60 * 5);
    }

    private List<MovecraftLocation> waterPositions(Craft craft) {
        int waterLevel = plugin.getConfig().getInt("waterLevel", 62);
        List<MovecraftLocation> list = new ArrayList<>();
        for (MovecraftLocation loc : craft.getHitBox()) {
            if (loc.getY() <= waterLevel) list.add(loc);
        }
        return list;
    }

    private void refillWaterAt(List<MovecraftLocation> positions, World world) {
        for (MovecraftLocation loc : positions) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (!block.getType().isAir()) continue;
            block.setType(Material.WATER);
        }
    }

}
