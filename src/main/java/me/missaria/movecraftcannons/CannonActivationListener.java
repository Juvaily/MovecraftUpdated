package me.missaria.movecraftcannons;

import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CannonActivationListener implements Listener {

    private final Map<UUID, Set<UUID>> activations = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Activated cannon UIDs for the given player (empty set if none). */
    public Set<UUID> getActivated(UUID playerUID) {
        return activations.getOrDefault(playerUID, Set.of());
    }

    // ── Right-click with empty hand to toggle cannon activation ───────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() != null) return; // empty hand only

        Block block = event.getClickedBlock();
        if (block == null || block.getType().isAir()) return;

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        Cannon cannon = findCannonAt(block.getLocation(), craft);
        if (cannon == null) return;

        event.setCancelled(true);

        UUID uid = player.getUniqueId();
        UUID cid = cannon.getUID();
        Set<UUID> set = activations.computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet());
        if (set.remove(cid)) {
            player.sendMessage(Lang.msg("cannon.deactivated", player, NamedTextColor.YELLOW, cannonName(cannon)));
        } else {
            set.add(cid);
            player.sendMessage(Lang.msg("cannon.activated", player, NamedTextColor.GREEN, cannonName(cannon)));
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot != null) activations.remove(pilot.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activations.remove(event.getPlayer().getUniqueId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Cannon findCannonAt(Location loc, PlayerCraft craft) {
        UUID worldUID = loc.getWorld().getUID();
        HitBox hitBox = craft.getHitBox();

        // Method 1: Cannons' own lookup by location
        try {
            HashSet<Cannon> found = CannonManager.getInstance()
                    .getCannonsByLocations(Collections.singletonList(loc));
            if (found != null) {
                for (Cannon c : found) {
                    Vector off = c.getCannonPosition().getOffset();
                    MovecraftLocation mloc = new MovecraftLocation(
                            (int) Math.floor(off.getX()),
                            (int) Math.floor(off.getY()),
                            (int) Math.floor(off.getZ()));
                    if (hitBox.contains(mloc)) return c;
                }
            }
        } catch (Exception ignored) {}

        // Method 2: match floor'd offset against clicked block coords
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        try {
            for (Cannon c : CannonManager.getInstance().getCannonList().values()) {
                if (!worldUID.equals(c.getCannonPosition().getWorld())) continue;
                Vector off = c.getCannonPosition().getOffset();
                if ((int) Math.floor(off.getX()) == bx
                        && (int) Math.floor(off.getY()) == by
                        && (int) Math.floor(off.getZ()) == bz) {
                    MovecraftLocation mloc = new MovecraftLocation(bx, by, bz);
                    if (hitBox.contains(mloc)) return c;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String cannonName(Cannon cannon) {
        try {
            String n = cannon.getCannonName();
            return (n != null && !n.isBlank()) ? n : "Пушка";
        } catch (Exception ignored) { return "Пушка"; }
    }
}
