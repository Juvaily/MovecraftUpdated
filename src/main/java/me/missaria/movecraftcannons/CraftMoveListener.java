package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.API.CannonsAPI;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.events.CraftRotateEvent;
import net.countercraft.movecraft.util.hitbox.HitBox;
import net.countercraft.movecraft.MovecraftLocation;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CraftMoveListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * When a craft translates, Movecraft physically moves all blocks.
     * Cannons indexes cannons by block location, so after movement the cannon
     * is "lost" — its block is at a new position but the plugin still looks at
     * the old location, resetting reload progress.
     *
     * Fix: snapshot cannon objects before the move, then after Movecraft has
     * finished moving blocks (1-tick delay), re-register each cannon at its
     * new location while keeping the in-memory state intact.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftTranslate(CraftTranslateEvent event) {
        Craft craft = event.getCraft();
        World world = craft.getWorld();

        int dx = event.getDx();
        int dy = event.getDy();
        int dz = event.getDz();

        if (dx == 0 && dy == 0 && dz == 0) return;

        // Snapshot: for every block currently in the craft that has a cannon,
        // remember the Cannon object before blocks are moved.
        Map<MovecraftLocation, Cannon> snapshot = snapshotCannons(craft, world);
        if (snapshot.isEmpty()) return;

        // Movecraft moves blocks asynchronously after this event resolves.
        // One-tick delay ensures blocks are already at their new positions
        // before we update the Cannons registry.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Map.Entry<MovecraftLocation, Cannon> entry : snapshot.entrySet()) {
                MovecraftLocation oldMloc = entry.getKey();
                Cannon cannon = entry.getValue();

                Location oldLoc = toLocation(world, oldMloc);
                Location newLoc = oldLoc.clone().add(dx, dy, dz);

                moveCannon(cannon, oldLoc, newLoc);
            }
        });
    }

    /**
     * When a craft rotates, cannon positions rotate around the craft centre
     * and cannon facing direction must also be rotated.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftRotate(CraftRotateEvent event) {
        Craft craft = event.getCraft();
        World world = craft.getWorld();

        Map<MovecraftLocation, Cannon> snapshot = snapshotCannons(craft, world);
        if (snapshot.isEmpty()) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // After rotation the craft's hitbox already reflects new positions,
            // so we can pair old → new by iterating the snapshot against the
            // rotated hitbox. For now: re-locate each cannon by matching its
            // new world block to the Cannons registry.
            for (Map.Entry<MovecraftLocation, Cannon> entry : snapshot.entrySet()) {
                MovecraftLocation oldMloc = entry.getKey();
                Cannon cannon = entry.getValue();
                Location oldLoc = toLocation(world, oldMloc);

                // Compute rotated position using Movecraft rotation helper
                MovecraftLocation newMloc = event.getRotation().rotate(
                        oldMloc, event.getCraft().getHitBox().getMidPoint());
                Location newLoc = toLocation(world, newMloc);

                moveCannon(cannon, oldLoc, newLoc);
                // TODO: also rotate cannon barrel direction to match craft rotation
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<MovecraftLocation, Cannon> snapshotCannons(Craft craft, World world) {
        Map<MovecraftLocation, Cannon> map = new HashMap<>();
        HitBox hitBox = craft.getHitBox();
        for (MovecraftLocation mloc : hitBox) {
            Location loc = toLocation(world, mloc);
            Cannon cannon = CannonsAPI.getCannon(loc, null);
            if (cannon != null) map.put(mloc, cannon);
        }
        return map;
    }

    private void moveCannon(Cannon cannon, Location oldLoc, Location newLoc) {
        // Remove the old registry entry so Cannons stops associating the
        // cannon with the pre-move block location.
        CannonsAPI.removeCannon(oldLoc);

        // Update the cannon object's stored location.
        // All in-memory state (reload progress, ammo, temperature …) stays intact.
        cannon.setCannonBlockLocation(newLoc);

        // Re-register at the new location so the Cannons plugin can find it.
        CannonsAPI.addCannon(cannon);
    }

    private static Location toLocation(World world, MovecraftLocation mloc) {
        return new Location(world, mloc.getX(), mloc.getY(), mloc.getZ());
    }
}
