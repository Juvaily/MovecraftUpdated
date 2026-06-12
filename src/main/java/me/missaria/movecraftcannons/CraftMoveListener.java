package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.data.CannonPosition;
import at.pavlov.cannons.hooks.movecraft.MovecraftUtils;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Set;

public class CraftMoveListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    public CraftMoveListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * When a craft translates, Movecraft fires CraftTranslateEvent BEFORE physically
     * moving blocks. Both oldHitBox and newHitBox are available, so we can compute
     * the translation vector and update cannon offsets immediately — they will match
     * the new block positions after Movecraft finishes the move.
     *
     * CannonManager indexes cannons by UUID (ConcurrentHashMap<UUID, Cannon>), not by
     * block location. Location lookups iterate and compare offsets geometrically.
     * So updating CannonPosition.setOffset() is sufficient — no remove/re-add needed.
     *
     * CraftRotateEvent is already handled by Cannons' built-in RotationListener
     * (calls cannon.rotateRight/Left), so we don't duplicate it here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftTranslate(CraftTranslateEvent event) {
        Set<Cannon> cannons = MovecraftUtils.getCannons(event.getCraft());
        if (cannons.isEmpty()) return;

        HitBox oldBox = event.getOldHitBox();
        HitBox newBox = event.getNewHitBox();

        int dx = newBox.getMinX() - oldBox.getMinX();
        int dy = newBox.getMinY() - oldBox.getMinY();
        int dz = newBox.getMinZ() - oldBox.getMinZ();

        if (dx == 0 && dy == 0 && dz == 0) return;

        Vector translation = new Vector(dx, dy, dz);

        for (Cannon cannon : cannons) {
            CannonPosition pos = cannon.getCannonPosition();
            pos.setOffset(pos.getOffset().clone().add(translation));
            cannon.setUpdated(true);
        }
    }
}
