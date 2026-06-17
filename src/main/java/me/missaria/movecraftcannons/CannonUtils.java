package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import at.pavlov.cannons.cannon.DesignStorage;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

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
     * Finds all cannons on the craft using two complementary approaches:
     * 1. CannonManager.getCannonsByLocations() — Cannons' own lookup by location list
     * 2. Iterate all cannons and match floor(offset) against the hitbox
     *    (handles fractional offsets that getCannonsByLocations may miss)
     */
    public static List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();
        UUID worldUID = world.getUID();
        List<Cannon> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        // Method 1: Cannons' own lookup by location list
        try {
            List<Location> locs = new ArrayList<>(hitBox.size());
            for (MovecraftLocation loc : hitBox)
                locs.add(new Location(world, loc.getX(), loc.getY(), loc.getZ()));
            HashSet<Cannon> found = CannonManager.getInstance().getCannonsByLocations(locs);
            if (found != null) {
                for (Cannon c : found) {
                    result.add(c);
                    try { seen.add(c.getUID()); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // Method 2: iterate all cannons, match floor'd offset against hitbox
        // Catches cannons whose offset has fractional coordinates that Method 1 may miss
        try {
            for (Cannon cannon : CannonManager.getInstance().getCannonList().values()) {
                try {
                    UUID uid = cannon.getUID();
                    if (seen.contains(uid)) continue;
                    if (!worldUID.equals(cannon.getCannonPosition().getWorld())) continue;
                    Vector off = cannon.getCannonPosition().getOffset();
                    MovecraftLocation mloc = new MovecraftLocation(
                            (int) Math.floor(off.getX()),
                            (int) Math.floor(off.getY()),
                            (int) Math.floor(off.getZ()));
                    if (hitBox.contains(mloc)) {
                        result.add(cannon);
                        seen.add(uid);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        return result;
    }

    // ── Auto-create cannons by simulating block placement ────────────────────

    /**
     * Scans the craft's hitbox for cannon-design blocks (via DesignStorage.isCannonBlockMaterial),
     * then simulates a BlockPlaceEvent for each unregistered one so that Cannons'
     * cannonPlaceListener detects the design and creates the cannon.
     *
     * Cannons creates cannons via BlockPlaceEvent (cannonPlaceListener), not via right-click.
     * This method handles ships built via WorldEdit/schematics where real BlockPlaceEvents
     * were never fired.
     */
    public static void autoCreateCannons(PlayerCraft craft, Player pilot,
                                         MovecraftCannonsPlugin plugin) {
        if (pilot == null) return;
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();
        Logger log = plugin.getLogger();

        // Positions of already-registered cannons on this craft (skip them)
        Set<String> existing = registeredPositionKeys(craft);

        int simulated = 0;
        for (MovecraftLocation loc : hitBox) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (block.getType().isAir()) continue;

            // Only process blocks that are part of some cannon design
            try {
                if (!DesignStorage.getInstance().isCannonBlockMaterial(block.getType())) continue;
            } catch (Exception ignored) { continue; }

            String key = loc.getX() + "," + loc.getY() + "," + loc.getZ();
            if (existing.contains(key)) continue;

            // Simulate BlockPlaceEvent so Cannons' cannonPlaceListener detects and registers the cannon
            try {
                BlockState replacedState = block.getState();
                Block placedAgainst = block.getRelative(BlockFace.DOWN);
                ItemStack item = new ItemStack(block.getType());
                BlockPlaceEvent fakePlace = new BlockPlaceEvent(
                        block, replacedState, placedAgainst, item, pilot, true, EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(fakePlace);
                simulated++;
            } catch (Exception ignored) {}
        }

        if (simulated > 0)
            log.info("[MovecraftCannons] Simulated " + simulated + " block place(s) for cannon auto-detection.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Set<String> registeredPositionKeys(PlayerCraft craft) {
        Set<String> keys = new HashSet<>();
        for (Cannon c : findCannonsOnCraft(craft)) {
            try {
                Vector off = c.getCannonPosition().getOffset();
                keys.add((int) Math.floor(off.getX()) + ","
                        + (int) Math.floor(off.getY()) + ","
                        + (int) Math.floor(off.getZ()));
            } catch (Exception ignored) {}
        }
        return keys;
    }
}
