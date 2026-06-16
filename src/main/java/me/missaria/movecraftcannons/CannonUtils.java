package me.missaria.movecraftcannons;

import at.pavlov.cannons.Cannons;
import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonDesign;
import at.pavlov.cannons.cannon.CannonManager;
import at.pavlov.cannons.cannon.DesignStorage;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Shared cannon-finding and auto-creation utilities.
 */
public final class CannonUtils {

    private CannonUtils() {}

    // ── Find cannons on a craft ───────────────────────────────────────────────

    /**
     * Returns all Cannons whose stored world position falls inside the craft's hitbox.
     * Uses the full CannonManager list and filters by exact block coordinate match,
     * which is more accurate than getCannonsInBox (avoids including nearby ships).
     */
    public static List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        HitBox hitBox = craft.getHitBox();
        UUID worldUID = craft.getWorld().getUID();
        List<Cannon> result = new ArrayList<>();
        try {
            ConcurrentHashMap<UUID, Cannon> all = CannonManager.getInstance().getCannonList();
            for (Cannon cannon : all.values()) {
                try {
                    if (!worldUID.equals(cannon.getCannonPosition().getWorld())) continue;
                    Vector off = cannon.getCannonPosition().getOffset();
                    MovecraftLocation mloc = new MovecraftLocation(
                            (int) Math.round(off.getX()),
                            (int) Math.round(off.getY()),
                            (int) Math.round(off.getZ()));
                    if (hitBox.contains(mloc)) result.add(cannon);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Logger.getLogger("MovecraftCannons").warning("CannonUtils: findCannonsOnCraft: " + e.getMessage());
        }
        return result;
    }

    // ── Auto-create cannons from block design ─────────────────────────────────

    /**
     * Scans the craft's hitbox for blocks that match any cannon design's rotation-center
     * block type. For each unregistered match, creates a Cannon entity in all 4 facing
     * directions (Cannons will mark the invalid ones as broken on first use).
     * Call this on CraftDetectEvent so cannons auto-exist without manual right-clicking.
     */
    public static void autoCreateCannons(PlayerCraft craft, Logger log) {
        World world = craft.getWorld();
        HitBox hitBox = craft.getHitBox();

        Cannons cannonsPlugin = (Cannons) org.bukkit.Bukkit.getPluginManager().getPlugin("Cannons");
        if (cannonsPlugin == null) return;

        DesignStorage storage;
        try { storage = DesignStorage.getInstance(); }
        catch (Exception e) { return; }

        List<?> designs;
        try { designs = storage.getCannonDesignList(); }
        catch (Exception e) { return; }

        // Collect world positions of already-registered cannons
        UUID worldUID = world.getUID();
        List<Location> registeredLocs = new ArrayList<>();
        try {
            for (Cannon c : CannonManager.getInstance().getCannonList().values()) {
                try {
                    if (!worldUID.equals(c.getCannonPosition().getWorld())) continue;
                    Vector o = c.getCannonPosition().getOffset();
                    registeredLocs.add(new Location(world, o.getX(), o.getY(), o.getZ()));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        for (Object designObj : designs) {
            if (!(designObj instanceof CannonDesign design)) continue;

            // Get the rotation-center block material
            org.bukkit.Material centerMat = rotationCenterMaterial(design);
            if (centerMat == null) continue;

            String designId;
            try { designId = design.getDesignID(); }
            catch (Exception e) { continue; }

            // Scan hitbox for blocks matching the rotation center
            for (MovecraftLocation loc : hitBox) {
                org.bukkit.block.Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
                if (block.getType() != centerMat) continue;

                Location bLoc = block.getLocation();
                // Skip if a cannon is already registered here
                if (alreadyRegistered(registeredLocs, bLoc)) continue;

                // Try to create cannon for each facing direction
                for (BlockFace face : new BlockFace[]{
                        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                    try {
                        Cannon newCannon = CannonManager.getInstance().newCannon(designId);
                        if (newCannon == null) break; // design not found
                        newCannon.getCannonPosition().setOffset(
                                new Vector(loc.getX(), loc.getY(), loc.getZ()));
                        newCannon.getCannonPosition().setWorld(worldUID);
                        newCannon.getCannonPosition().setCannonDirection(face);
                        CannonManager.getInstance().createCannon(newCannon, false);
                        registeredLocs.add(bLoc); // mark as registered
                        break; // created — stop trying faces
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static org.bukkit.Material rotationCenterMaterial(CannonDesign design) {
        try {
            Object obj = design.getSchematicBlockTypeRotationCenter();
            if (obj instanceof org.bukkit.Material m) return m;
            if (obj instanceof org.bukkit.block.data.BlockData bd) return bd.getMaterial();
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean alreadyRegistered(List<Location> registered, Location loc) {
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (Location r : registered) {
            if (r.getBlockX() == bx && r.getBlockY() == by && r.getBlockZ() == bz) return true;
        }
        return false;
    }
}
