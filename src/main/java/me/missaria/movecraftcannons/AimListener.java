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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AimListener implements Listener {

    private static final double GRAVITY        = 0.05;   // blocks/tick²
    private static final double DEFAULT_SPEED  = 3.0;    // blocks/tick fallback
    private static final int    MAX_TICKS      = 100;    // max trajectory ticks
    private static final int    SAMPLE_TICKS   = 2;      // record a point every N ticks

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, List<Cannon>>   aimCannons    = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask>     aimTasks      = new ConcurrentHashMap<>();
    // Blocks sent as fake glass last tick — cleared before each new trajectory draw
    private final Map<UUID, List<Location>> sentTrajectory = new ConcurrentHashMap<>();

    public AimListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Toggle on right-click CLOCK (no DC mode required) ────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onClockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var item = event.getItem();
        if (item == null || item.getType() != Material.CLOCK) return;

        Player player = event.getPlayer();

        // Must be piloting some craft (DC mode NOT required)
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        event.setCancelled(true);

        if (aimCannons.containsKey(player.getUniqueId())) {
            stopAiming(player);
        } else {
            startAiming(player, craft);
        }
    }

    // ── Start ─────────────────────────────────────────────────────────────────

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
            PlayerCraft current = CraftManager.getInstance().getCraftByPlayer(player);
            if (!player.isOnline() || current == null) {
                stopAiming(player);
                return;
            }
            List<Cannon> tracked = aimCannons.get(uid);
            if (tracked == null) return;

            Location loc  = player.getLocation();
            float yaw     = loc.getYaw();
            float pitch   = loc.getPitch();

            // Update cannon angles
            for (Cannon cannon : tracked) {
                try {
                    GunAngles angles = GunAngles.getGunAngle(cannon, yaw, pitch);
                    api.setCannonAngle(cannon, angles.getHorizontal(), angles.getVertical());
                } catch (Exception ignored) {}
            }

            // Draw trajectory for the first cannon
            clearTrajectory(player);
            Cannon first = tracked.get(0);
            drawTrajectory(player, first, loc.getDirection().normalize());

        }, 0L, 2L);

        aimTasks.put(uid, task);
        player.sendMessage(Lang.msg("msg.aim_on", player, NamedTextColor.GREEN, cannons.size()));
    }

    // ── Trajectory ────────────────────────────────────────────────────────────

    private void drawTrajectory(Player player, Cannon cannon, Vector direction) {
        // Starting position: muzzle tip of the cannon
        Location start = muzzleLocation(cannon);
        if (start == null) return;
        World world = start.getWorld();
        if (world == null) return;

        double speed = cannonSpeed(cannon);
        double vx = direction.getX() * speed;
        double vy = direction.getY() * speed;
        double vz = direction.getZ() * speed;
        double x = start.getX(), y = start.getY(), z = start.getZ();

        List<Location> points = new ArrayList<>();

        for (int t = 0; t < MAX_TICKS; t++) {
            vy -= GRAVITY;
            x += vx; y += vy; z += vz;

            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);

            Block block = world.getBlockAt(bx, by, bz);

            if (block.getType().isSolid()) {
                // Impact marker — red glass
                Location impactLoc = new Location(world, bx, by, bz);
                player.sendBlockChange(impactLoc, Material.RED_STAINED_GLASS.createBlockData());
                points.add(impactLoc);
                break;
            }

            if (t % SAMPLE_TICKS == 0) {
                Location pt = new Location(world, bx, by, bz);
                player.sendBlockChange(pt, Material.LIME_STAINED_GLASS.createBlockData());
                points.add(pt);
            }
        }

        sentTrajectory.put(player.getUniqueId(), points);
    }

    private void clearTrajectory(Player player) {
        List<Location> prev = sentTrajectory.remove(player.getUniqueId());
        if (prev == null) return;
        for (Location loc : prev) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    public void stopAiming(Player player) {
        UUID uid = player.getUniqueId();
        BukkitTask task = aimTasks.remove(uid);
        if (task != null) task.cancel();
        boolean wasActive = aimCannons.remove(uid) != null;
        clearTrajectory(player);
        if (wasActive && player.isOnline()) {
            player.sendMessage(Lang.msg("msg.aim_off", player, NamedTextColor.YELLOW));
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

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

    private Location muzzleLocation(Cannon cannon) {
        try {
            Location loc = cannon.getCannonDesign().getMuzzle(cannon);
            if (loc != null) return loc;
        } catch (Exception ignored) {}
        // Fallback: center of cannon block + 1 block up
        try {
            var offset = cannon.getCannonPosition().getOffset();
            World world = cannon.getWorldBukkit();
            return new Location(world, offset.getX() + 0.5, offset.getY() + 1.5, offset.getZ() + 0.5);
        } catch (Exception ignored) {}
        return null;
    }

    private double cannonSpeed(Cannon cannon) {
        try {
            double v = cannon.getCannonballVelocity();
            if (v > 0) return v;
        } catch (Exception ignored) {}
        return DEFAULT_SPEED;
    }

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
