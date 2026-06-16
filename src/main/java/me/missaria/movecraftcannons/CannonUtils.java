package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class CannonUtils {

    private CannonUtils() {}

    // ── Find cannons on a craft ───────────────────────────────────────────────

    /**
     * Returns all Cannons on the craft by passing every hitbox block location to
     * CannonManager.getCannonsByLocations() — the same lookup Cannons' own Movecraft
     * hook uses, so it correctly handles fractional offsets, etc.
     */
    public static List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();
        List<Location> locations = new ArrayList<>(hitBox.size());
        for (MovecraftLocation loc : hitBox) {
            locations.add(new Location(world, loc.getX(), loc.getY(), loc.getZ()));
        }
        try {
            HashSet<Cannon> found = CannonManager.getInstance().getCannonsByLocations(locations);
            return found != null ? new ArrayList<>(found) : new ArrayList<>();
        } catch (Exception e) {
            Logger.getLogger("MovecraftCannons").warning("CannonUtils.findCannonsOnCraft: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── Auto-create cannons by clicking every cannon block ────────────────────

    /**
     * Scans the craft's hitbox for any block whose material is part of a cannon design
     * (via CannonManager.isCannonBlockMaterial), then simulates a PlayerInteractEvent
     * right-click on each one. Cannons' own PlayerListener detects complete designs and
     * creates the cannon entity — no manual clicking required.
     *
     * Blocks where a cannon is already registered are skipped.
     */
    public static void autoCreateCannons(PlayerCraft craft, Player pilot, Logger log) {
        if (pilot == null) return;
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();
        CannonManager mgr = CannonManager.getInstance();

        // Collect positions of already-registered cannons (skip those)
        Set<String> existing = existingPositionKeys(craft.getWorld().getUID(), world, hitBox);

        int clicked = 0;
        for (MovecraftLocation loc : hitBox) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (block.getType().isAir()) continue;

            // Only right-click blocks that are part of some cannon design
            try {
                if (!mgr.isCannonBlockMaterial(block.getType())) continue;
            } catch (Exception ignored) { continue; }

            // Skip already-existing cannon positions
            String key = loc.getX() + "," + loc.getY() + "," + loc.getZ();
            if (existing.contains(key)) continue;

            // Simulate right-click: Cannons PlayerListener creates the cannon if
            // the surrounding blocks complete a known design pattern
            try {
                PlayerInteractEvent fake = new PlayerInteractEvent(
                        pilot,
                        Action.RIGHT_CLICK_BLOCK,
                        new ItemStack(Material.AIR),
                        block,
                        BlockFace.UP,
                        EquipmentSlot.HAND);
                fake.setUseItemInHand(Event.Result.DENY);
                Bukkit.getPluginManager().callEvent(fake);
                clicked++;
            } catch (Exception ignored) {}
        }

        if (clicked > 0)
            log.info("[MovecraftCannons] Sent " + clicked + " cannon-block click(s) for auto-detection.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Set<String> existingPositionKeys(UUID worldUID, World world, HitBox hitBox) {
        Set<String> keys = new HashSet<>();
        try {
            List<Location> locations = new ArrayList<>(hitBox.size());
            for (MovecraftLocation loc : hitBox) {
                locations.add(new Location(world, loc.getX(), loc.getY(), loc.getZ()));
            }
            HashSet<Cannon> existing = CannonManager.getInstance().getCannonsByLocations(locations);
            if (existing == null) return keys;
            for (Cannon c : existing) {
                try {
                    var off = c.getCannonPosition().getOffset();
                    keys.add((int) Math.round(off.getX()) + ","
                            + (int) Math.round(off.getY()) + ","
                            + (int) Math.round(off.getZ()));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return keys;
    }
}
