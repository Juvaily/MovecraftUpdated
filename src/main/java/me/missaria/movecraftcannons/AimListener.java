package me.missaria.movecraftcannons;

import at.pavlov.cannons.API.CannonsAPI;
import at.pavlov.cannons.Cannons;
import at.pavlov.cannons.aim.GunAngles;
import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AimListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, List<Cannon>> aimCannons = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask>   aimTasks   = new ConcurrentHashMap<>();

    public AimListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Toggle AIM on right-click CLOCK while piloting ────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onClockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var item = event.getItem();
        if (item == null || item.getType() != Material.CLOCK) return;

        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        event.setCancelled(true);

        if (aimCannons.containsKey(player.getUniqueId())) {
            stopAiming(player);
        } else {
            startAiming(player, craft);
        }
    }

    // ── Start: collect cannons, launch update task ────────────────────────────

    private void startAiming(Player player, PlayerCraft craft) {
        List<Cannon> cannons = findCannonsOnCraft(craft);
        if (cannons.isEmpty()) {
            player.sendMessage(Lang.msg("msg.no_cannons", player, NamedTextColor.YELLOW));
            return;
        }

        CannonsAPI api = getCannonsAPI();
        if (api == null) {
            player.sendMessage(Lang.msg("msg.cannons_unavailable", player, NamedTextColor.RED));
            return;
        }

        UUID uid = player.getUniqueId();
        aimCannons.put(uid, cannons);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Auto-stop if player left DC mode
            PlayerCraft current = CraftManager.getInstance().getCraftByPlayer(player);
            if (!player.isOnline() || current == null || !current.getPilotLocked()) {
                stopAiming(player);
                return;
            }
            List<Cannon> tracked = aimCannons.get(uid);
            if (tracked == null) return;

            Location loc = player.getLocation();
            float yaw   = loc.getYaw();
            float pitch = loc.getPitch();

            for (Cannon cannon : tracked) {
                try {
                    GunAngles angles = GunAngles.getGunAngle(cannon, yaw, pitch);
                    api.setCannonAngle(cannon, angles.getHorizontal(), angles.getVertical());
                } catch (Exception ignored) {}
            }
        }, 0L, 2L);

        aimTasks.put(uid, task);
        player.sendMessage(Lang.msg("msg.aim_on", player, NamedTextColor.GREEN, cannons.size()));
    }

    // ── Stop: cancel task, notify ─────────────────────────────────────────────

    public void stopAiming(Player player) {
        UUID uid = player.getUniqueId();
        BukkitTask task = aimTasks.remove(uid);
        if (task != null) task.cancel();
        boolean wasActive = aimCannons.remove(uid) != null;
        if (wasActive && player.isOnline()) {
            player.sendMessage(Lang.msg("msg.aim_off", player, NamedTextColor.YELLOW));
        }
    }

    // ── Cleanup handlers ──────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopAiming(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot != null) stopAiming(pilot);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        HitBox hitBox = craft.getHitBox();
        double cx = (hitBox.getMinX() + hitBox.getMaxX()) / 2.0;
        double cy = (hitBox.getMinY() + hitBox.getMaxY()) / 2.0;
        double cz = (hitBox.getMinZ() + hitBox.getMaxZ()) / 2.0;
        double dx = (hitBox.getMaxX() - hitBox.getMinX()) + 2;
        double dy = (hitBox.getMaxY() - hitBox.getMinY()) + 2;
        double dz = (hitBox.getMaxZ() - hitBox.getMinZ()) + 2;
        Location center = new Location(craft.getWorld(), cx, cy, cz);
        try {
            HashSet<Cannon> found = CannonManager.getCannonsInBox(center, dx, dy, dz);
            return found != null ? new ArrayList<>(found) : new ArrayList<>();
        } catch (Exception e) {
            plugin.getLogger().warning("AimListener: error finding cannons: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private CannonsAPI getCannonsAPI() {
        Cannons c = (Cannons) Bukkit.getPluginManager().getPlugin("Cannons");
        return c != null ? c.getCannonsAPI() : null;
    }
}
