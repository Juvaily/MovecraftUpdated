package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import at.pavlov.cannons.cannon.data.CannonPosition;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.events.CraftSinkEvent;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.countercraft.movecraft.MovecraftLocation;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CraftMoveListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ROOT CAUSE: Cannons 3.4.3 TranslationListener calls cannon.move(Vector),
     * which does NOT exist → NoSuchMethodError → cannon offset never updated →
     * Cannons can't find the cannon at the new block location → creates a fresh
     * cannon with zeroed reload/ammo/temperature state.
     *
     * WHY getCannonsByLocations() was wrong: it calls Block.getBlock() + isCannonBlock()
     * (material check). If cannon design materials overlap with ship materials, or if the
     * cannon block isn't the trigger block at the expected position, it returns empty.
     *
     * FIX: iterate all cannons via getCannonList() and match by stored offset position
     * (UUID world + Vector) against the old hitbox. This is a pure data check — no
     * physical block access — and works regardless of cannon material or design layout.
     *
     * CraftTranslateEvent fires BEFORE blocks physically move (TranslationTask fires
     * the event, then submits CraftTranslateCommand which does the actual block move),
     * so the old hitbox positions are still valid for lookup.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCraftTranslate(CraftTranslateEvent event) {
        HitBox oldBox = event.getOldHitBox();
        HitBox newBox = event.getNewHitBox();

        int dx = newBox.getMinX() - oldBox.getMinX();
        int dy = newBox.getMinY() - oldBox.getMinY();
        int dz = newBox.getMinZ() - oldBox.getMinZ();

        if (dx == 0 && dy == 0 && dz == 0) return;

        World world = event.getCraft().getWorld();
        UUID worldUID = world.getUID();
        Vector translation = new Vector(dx, dy, dz);

        Collection<Cannon> allCannons = getCannons();
        if (allCannons == null || allCannons.isEmpty()) return;

        int updated = 0;
        for (Cannon cannon : allCannons) {
            try {
                CannonPosition pos = cannon.getCannonPosition();

                // World check — skip cannons in other worlds
                if (!worldUID.equals(pos.getWorld())) continue;

                // Check if cannon's stored offset is inside the old hitbox.
                // This is a pure coordinate check — no physical block access needed.
                Vector offset = pos.getOffset();
                MovecraftLocation mloc = new MovecraftLocation(
                        (int) Math.round(offset.getX()),
                        (int) Math.round(offset.getY()),
                        (int) Math.round(offset.getZ())
                );
                if (!oldBox.contains(mloc)) continue;

                // Update stored position. CannonManager is keyed by UUID,
                // so setOffset() is sufficient — no remove/re-add needed.
                pos.setOffset(offset.clone().add(translation));
                cannon.setUpdated(true);
                updated++;

                if (plugin.isDebug()) {
                    plugin.getLogger().info("[debug] Cannon '" + cannon.getCannonDesign().getDesignID()
                            + "' moved by (" + dx + "," + dy + "," + dz + ")"
                            + " to (" + Math.round(offset.getX() + dx)
                            + "," + Math.round(offset.getY() + dy)
                            + "," + Math.round(offset.getZ() + dz) + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error updating cannon position: " + e.getMessage());
            }
        }

        if (plugin.isDebug() && updated > 0) {
            plugin.getLogger().info("[debug] Updated " + updated + " cannon(s) on craft translate.");
        }
    }

    // ── Water fill ────────────────────────────────────────────────────────────

    // CraftTranslateEvent fires BEFORE blocks move. We collect vacated positions
    // here, then restore water in the next tick (after Movecraft moves the blocks).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftTranslateWater(CraftTranslateEvent event) {
        HitBox oldBox = event.getOldHitBox();
        HitBox newBox = event.getNewHitBox();
        World world = event.getCraft().getWorld();

        List<MovecraftLocation> vacated = new ArrayList<>();
        for (MovecraftLocation loc : oldBox) {
            if (!newBox.contains(loc)) vacated.add(loc);
        }
        if (vacated.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> refillWaterAt(vacated, world));
    }

    // On release, fill any air holes left in the craft's last footprint.
    // Scheduled 1 tick to run after Movecraft actually deregisters the craft.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftRelease(CraftReleaseEvent event) {
        Craft craft = event.getCraft();
        List<MovecraftLocation> positions = snapshotHitbox(craft);
        World world = craft.getWorld();
        Bukkit.getScheduler().runTask(plugin, () -> refillWaterAt(positions, world));
    }

    // Sinking is a series of downward translates, but schedule a final cleanup
    // after the sink settles (40 ticks ≈ 2 s) to catch any remaining gaps.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftSink(CraftSinkEvent event) {
        Craft craft = event.getCraft();
        List<MovecraftLocation> positions = snapshotHitbox(craft);
        World world = craft.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> refillWaterAt(positions, world), 40L);
    }

    private List<MovecraftLocation> snapshotHitbox(Craft craft) {
        List<MovecraftLocation> list = new ArrayList<>();
        for (MovecraftLocation loc : craft.getHitBox()) list.add(loc);
        return list;
    }

    private void refillWaterAt(List<MovecraftLocation> positions, World world) {
        for (MovecraftLocation loc : positions) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (!block.getType().isAir()) continue;
            if (adjacentToWater(block)) block.setType(Material.WATER);
        }
    }

    private boolean adjacentToWater(Block b) {
        return isWater(b.getRelative( 1, 0,  0))
            || isWater(b.getRelative(-1, 0,  0))
            || isWater(b.getRelative( 0, 0,  1))
            || isWater(b.getRelative( 0, 0, -1))
            || isWater(b.getRelative( 0, 1,  0))
            || isWater(b.getRelative( 0,-1,  0));
    }

    private boolean isWater(Block b) {
        return b.getType() == Material.WATER;
    }

    /** Get all cannons from CannonManager's internal map (indexed by UUID). */
    @SuppressWarnings("unchecked")
    private Collection<Cannon> getCannons() {
        try {
            ConcurrentHashMap<UUID, Cannon> map = CannonManager.getInstance().getCannonList();
            return map.values();
        } catch (Exception e) {
            plugin.getLogger().warning("Could not access CannonManager.getCannonList(): " + e.getMessage());
            return null;
        }
    }
}
