package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import at.pavlov.cannons.cannon.data.CannonPosition;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.countercraft.movecraft.MovecraftLocation;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CraftMoveListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ROOT CAUSE: Cannons 3.4.3 ships a TranslationListener that calls cannon.move(Vector),
     * but Cannon.move() does NOT exist in this build → NoSuchMethodError every time a craft
     * translates → cannon is never re-registered at the new block position → Cannons creates
     * a fresh cannon when the player next interacts → reload/ammo/temperature all reset.
     *
     * FIX: intercept at LOWEST priority (runs before Cannons HIGH listener catches the error),
     * correctly update CannonPosition.offset, and mark each cannon as dirty.
     * CannonManager stores cannons by UUID (not by Location), so setOffset() is sufficient —
     * no remove/re-add required.
     *
     * We use LOWEST so our handler runs before Cannons throws the error. The error still gets
     * thrown by Cannons' HIGH listener, but by then the offset is already correct, so the next
     * location-based lookup (getCannon / getCannonsByLocations) returns the cannon at the right
     * new position and Cannons never creates a duplicate.
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

        // Build location list from the old hitbox to find cannons that sit on this craft.
        // We use the old hitbox because blocks haven't moved yet at LOWEST priority.
        List<Location> locations = new ArrayList<>(oldBox.size());
        for (MovecraftLocation mloc : oldBox) {
            locations.add(new Location(world, mloc.getX(), mloc.getY(), mloc.getZ()));
        }

        Set<Cannon> cannons;
        try {
            cannons = CannonManager.getInstance().getCannonsByLocations(locations);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to look up cannons on craft: " + e.getMessage());
            return;
        }

        if (cannons == null || cannons.isEmpty()) return;

        Vector translation = new Vector(dx, dy, dz);

        for (Cannon cannon : cannons) {
            try {
                CannonPosition pos = cannon.getCannonPosition();
                // setOffset() correctly updates the stored world-coordinate vector.
                // CannonManager indexes by UUID, so no remove/re-add needed.
                pos.setOffset(pos.getOffset().clone().add(translation));
                cannon.setUpdated(true);

                if (plugin.isDebug()) {
                    plugin.getLogger().info("[debug] Moved cannon '" + cannon.getCannonDesign().getDesignID()
                        + "' by (" + dx + ", " + dy + ", " + dz + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to update cannon position: " + e.getMessage());
            }
        }
    }
}
