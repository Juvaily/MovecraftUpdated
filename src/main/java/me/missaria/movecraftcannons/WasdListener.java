package me.missaria.movecraftcannons;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WasdListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    private final WindManager windManager;
    private final HealthBarListener healthBarListener;
    private TurretListener turretListener;

    private final Map<UUID, int[]>    latestDir       = new ConcurrentHashMap<>();
    private final Map<UUID, Long>     latestTime      = new ConcurrentHashMap<>();
    private final Map<UUID, Long>     lastRotate      = new ConcurrentHashMap<>();

    // Flight state saved on DC entry
    private final Map<UUID, Boolean>  savedAllowFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean>  savedFlying      = new ConcurrentHashMap<>();
    private final Map<UUID, Float>    savedWalkSpeed   = new ConcurrentHashMap<>();
    private final Map<UUID, Float>    savedFlySpeed    = new ConcurrentHashMap<>();

    private final java.util.Set<UUID> hudPlayers    = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> hudHidden     = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> turningCrafts = ConcurrentHashMap.newKeySet();

    private static final long   ROTATE_DEBOUNCE = 600L;
    private static final float  PILOT_SPEED     = 0.005f;
    private static final double MOVE_THRESHOLD  = 0.001;

    public void setTurretListener(TurretListener tl) { this.turretListener = tl; }

    public WasdListener(MovecraftCannonsPlugin plugin, WindManager windManager, HealthBarListener healthBarListener) {
        this.plugin = plugin;
        this.windManager = windManager;
        this.healthBarListener = healthBarListener;
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, Math.max(1L, cooldown / 50L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncFlightState, 0L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateHuds, 0L, 10L);
    }

    // ── Flight + DC state management ──────────────────────────────────────────

    private void syncFlightState() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uid = player.getUniqueId();
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            boolean locked  = craft != null && craft.getPilotLocked();
            boolean tracked = savedAllowFlight.containsKey(uid);

            if (locked && !tracked) {
                // ── Enter DC mode ──
                // Save flight state
                float curWalk = player.getWalkSpeed();
                float curFly  = player.getFlySpeed();
                savedAllowFlight.put(uid, player.getAllowFlight());
                savedFlying.put(uid, player.isFlying());
                savedWalkSpeed.put(uid, curWalk > PILOT_SPEED * 2 ? curWalk : 0.2f);
                savedFlySpeed.put(uid,  curFly  > PILOT_SPEED * 2 ? curFly  : 0.1f);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setWalkSpeed(PILOT_SPEED);
                player.setFlySpeed(PILOT_SPEED);

                // Invulnerable + invisible to others
                player.setInvulnerable(true);
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0,
                        true,   // ambient (fewer particles for self)
                        false,  // no particles for others
                        false)); // no icon

            } else if (!locked && tracked) {
                restoreFlight(player);
            }
        }
    }

    private void restoreFlight(Player player) {
        UUID uid = player.getUniqueId();

        // Restore flight
        Boolean origAllow  = savedAllowFlight.remove(uid);
        Boolean origFlying = savedFlying.remove(uid);
        Float   origWalk   = savedWalkSpeed.remove(uid);
        Float   origFly    = savedFlySpeed.remove(uid);

        if (origAllow != null) {
            player.setAllowFlight(origAllow);
            if (origFlying != null && !origFlying && !origAllow) player.setFlying(false);
        } else {
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
        }
        if (origWalk != null) player.setWalkSpeed(origWalk);
        if (origFly  != null) player.setFlySpeed(origFly);

        latestDir.remove(uid);
        latestTime.remove(uid);

        // Restore DC extras
        player.setInvulnerable(false);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot == null) return;
        restoreFlight(pilot);
        if (hudPlayers.remove(pilot.getUniqueId())) {
            try { pilot.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); }
            catch (Exception ignored) {}
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        restoreFlight(player);
        hudPlayers.remove(player.getUniqueId());
    }


    // ── WASD direction capture ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < MOVE_THRESHOLD && Math.abs(dz) < MOVE_THRESHOLD) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;

        int[] dir = resolveDir(player.getLocation().getYaw(), dx, dz);
        if (dir[0] == 0 && dir[1] == 0) return;

        UUID uid = player.getUniqueId();
        latestDir.put(uid, dir);
        latestTime.put(uid, System.currentTimeMillis());

        Location pushBack = from.clone();
        pushBack.setYaw(to.getYaw());
        pushBack.setPitch(to.getPitch());
        event.setTo(pushBack);
    }

    // ── Periodic movement tick ─────────────────────────────────────────────────

    private void tick() {
        long now      = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("wasd.cooldown_ms", 200L);
        long ttl      = 150L;

        latestDir.forEach((uid, dir) -> {
            Long t = latestTime.get(uid);
            if (t == null || now - t > ttl) {
                latestDir.remove(uid);
                latestTime.remove(uid);
                return;
            }
            if (dir[0] == 0 && dir[1] == 0) return;

            Player player = Bukkit.getPlayer(uid);
            if (player == null) { latestDir.remove(uid); latestTime.remove(uid); return; }

            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null || !craft.getPilotLocked()) {
                latestDir.remove(uid); latestTime.remove(uid); return;
            }

            if (craft.getDisabled()) craft.setDisabled(false);
            craft.translate(dir[0], 0, dir[1]);

            if (plugin.isDebug())
                plugin.getLogger().info("[wasd] " + player.getName()
                    + " (" + dir[0] + ",0," + dir[1] + ") age=" + (now - t) + "ms");
        });
    }

    private int[] resolveDir(float rawYaw, double dx, double dz) {
        double y       = ((rawYaw % 360) + 360) % 360;
        int    snapped = ((int) Math.round(y / 90.0) * 90) % 360;

        int fx = -(int) Math.round(Math.sin(Math.toRadians(snapped)));
        int fz =  (int) Math.round(Math.cos(Math.toRadians(snapped)));
        int rx = -(int) Math.round(Math.sin(Math.toRadians(snapped + 90)));
        int rz =  (int) Math.round(Math.cos(Math.toRadians(snapped + 90)));

        double fwd    = dx * fx + dz * fz;
        double strafe = dx * rx + dz * rz;

        int rdx = 0, rdz = 0;
        if      (fwd    >  MOVE_THRESHOLD) { rdx += fx; rdz += fz; }
        else if (fwd    < -MOVE_THRESHOLD) { rdx -= fx; rdz -= fz; }
        if      (strafe >  MOVE_THRESHOLD) { rdx += rx; rdz += rz; }
        else if (strafe < -MOVE_THRESHOLD) { rdx -= rx; rdz -= rz; }

        return new int[]{Math.max(-1, Math.min(1, rdx)), Math.max(-1, Math.min(1, rdz))};
    }

    // ── Q → rotate left, F → rotate right ────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.ANTICLOCKWISE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null || !craft.getPilotLocked()) return;
        event.setCancelled(true);
        if (!rotateDebounce(player.getUniqueId())) return;
        rotateCraft(craft, MovecraftRotation.CLOCKWISE);
    }

    private boolean rotateDebounce(UUID uid) {
        long now  = System.currentTimeMillis();
        Long last = lastRotate.get(uid);
        if (last != null && now - last < ROTATE_DEBOUNCE) return false;
        lastRotate.put(uid, now);
        return true;
    }

    private void rotateCraft(PlayerCraft craft, MovecraftRotation rotation) {
        UUID craftId = craft.getUUID();
        if (!turningCrafts.add(craftId)) return;

        Player pilot = craft.getPilot();
        int[] fwd = arcFwdVec(pilot != null ? pilot.getLocation().getYaw() : 0f);
        int dist   = arcDist(craft);

        if (craft.getDisabled()) craft.setDisabled(false);
        try { craft.translate(fwd[0] * dist, 0, fwd[1] * dist); } catch (Exception ignored) {}

        int[] newFwd = rotation == MovecraftRotation.CLOCKWISE
                ? new int[]{-fwd[1], fwd[0]} : new int[]{fwd[1], -fwd[0]};

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (craft.getDisabled()) craft.setDisabled(false);
            try {
                HitBox hb = craft.getHitBox();
                craft.rotate(rotation, new MovecraftLocation(
                        (hb.getMinX() + hb.getMaxX()) / 2,
                        (hb.getMinY() + hb.getMaxY()) / 2,
                        (hb.getMinZ() + hb.getMaxZ()) / 2));
            } catch (Exception ignored) {}

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (craft.getDisabled()) craft.setDisabled(false);
                try { craft.translate(newFwd[0] * dist, 0, newFwd[1] * dist); } catch (Exception ignored) {}
                turningCrafts.remove(craftId);
            }, 6L);
        }, 6L);
    }

    static int[] arcFwdVec(float rawYaw) {
        double y = ((rawYaw % 360) + 360) % 360;
        int snapped = ((int) Math.round(y / 90.0) * 90) % 360;
        return switch (snapped) {
            case 90  -> new int[]{-1,  0};
            case 180 -> new int[]{ 0, -1};
            case 270 -> new int[]{ 1,  0};
            default  -> new int[]{ 0,  1};
        };
    }

    static int arcDist(PlayerCraft craft) {
        HitBox hb = craft.getHitBox();
        int span = Math.max(hb.getMaxX() - hb.getMinX(), hb.getMaxZ() - hb.getMinZ());
        return Math.max(1, Math.min(4, span / 10));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String craftName(PlayerCraft craft) {
        try {
            String n = craft.getName();
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        try {
            String n = craft.getType().getStringProperty(
                    net.countercraft.movecraft.craft.type.CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    /** Toggle HUD visibility for a player. Returns true if now hidden. */
    public boolean toggleHud(Player player) {
        UUID uid = player.getUniqueId();
        if (hudHidden.remove(uid)) return false;
        hudHidden.add(uid);
        return true;
    }

    private void updateHuds() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uid = player.getUniqueId();
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft != null && !hudHidden.contains(uid)) {
                List<String> healthLines = healthBarListener.getHealthLines(player, craft);
                player.setScoreboard(buildPilotScoreboard(player, craftName(craft), healthLines));
                hudPlayers.add(uid);
            } else if (hudPlayers.remove(uid)) {
                try { player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); }
                catch (Exception ignored) {}
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Scoreboard buildPilotScoreboard(Player player, String shipName, List<String> healthLines) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("hud", "dummy",
                Component.text(shipName).color(NamedTextColor.GOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = new ArrayList<>(healthLines);
        if (turretListener != null) {
            String turretLine = turretListener.getHudLine(player);
            if (turretLine != null) lines.add(turretLine);
        }
        lines.add(Lang.get("dc.sep2", player));
        lines.add(Lang.get("dc.wind", player, windManager.getStrengthDisplay(player)));
        lines.add(Lang.get("hud.hide_hint", player));

        for (int i = 0; i < lines.size(); i++) {
            obj.getScore(lines.get(i)).setScore(lines.size() - i);
        }
        return sb;
    }
}
