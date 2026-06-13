package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.events.CraftCollisionExplosionEvent;
import org.bukkit.NamespacedKey;
import net.countercraft.movecraft.events.CraftDetectEvent;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.events.CraftRotateEvent;
import net.countercraft.movecraft.events.CraftSinkEvent;
import net.countercraft.movecraft.events.CraftTranslateEvent;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HealthBarListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, TextDisplay> displays     = new ConcurrentHashMap<>();
    private final Map<UUID, Craft>       activeCrafts = new ConcurrentHashMap<>();

    private static final Transformation SCALE = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(2.5f, 2.5f, 2.5f),
            new AxisAngle4f(0, 0, 0, 1)
    );

    public HealthBarListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 10L);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDetect(CraftDetectEvent event) {
        Craft craft = event.getCraft();
        UUID uid    = craft.getUUID();
        int  orig   = craft.getOrigBlockCount();
        int  curr   = countBlocks(craft);
        if (orig <= 0) orig = curr;

        Location pos = above(craft.getHitBox(), craft.getWorld());
        int finalOrig = orig;
        TextDisplay disp = pos.getWorld().spawn(pos, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setDefaultBackground(false);
            e.setShadowed(true);
            e.setPersistent(false);
            e.setViewRange(1.5f);
            e.setTransformation(SCALE);
            e.text(buildText(craft, curr, finalOrig));
        });

        displays.put(uid, disp);
        activeCrafts.put(uid, craft);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelease(CraftReleaseEvent event) {
        remove(event.getCraft().getUUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSink(CraftSinkEvent event) {
        UUID uid = event.getCraft().getUUID();
        TextDisplay disp = displays.get(uid);
        if (disp != null) {
            disp.text(Component.text("☠ Тонет!", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(uid), 100L);
    }

    // ── Combat block loss ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCollisionExplosion(CraftCollisionExplosionEvent event) {
        UUID uid = event.getCraft().getUUID();
        if (!displays.containsKey(uid)) return;
        // Schedule 1 tick later so blocks are physically removed first
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshDisplay(uid), 1L);
    }

    // ── Follow craft movement ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTranslate(CraftTranslateEvent event) {
        TextDisplay disp = displays.get(event.getCraft().getUUID());
        if (disp == null) return;
        disp.teleport(above(event.getNewHitBox(), event.getCraft().getWorld()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRotate(CraftRotateEvent event) {
        TextDisplay disp = displays.get(event.getCraft().getUUID());
        if (disp == null) return;
        disp.teleport(above(event.getNewHitBox(), event.getCraft().getWorld()));
    }

    // ── Periodic update ───────────────────────────────────────────────────────

    private void updateAll() {
        activeCrafts.forEach((uid, craft) -> {
            TextDisplay disp = displays.get(uid);
            if (disp == null || !disp.isValid()) { remove(uid); return; }
            refreshDisplay(uid);
        });
    }

    private void refreshDisplay(UUID uid) {
        Craft craft = activeCrafts.get(uid);
        TextDisplay disp = displays.get(uid);
        if (craft == null || disp == null || !disp.isValid()) return;

        int orig = craft.getOrigBlockCount();
        int curr = countBlocks(craft);
        if (orig <= 0) orig = curr;
        disp.text(buildText(craft, curr, orig));
    }

    private void remove(UUID uid) {
        activeCrafts.remove(uid);
        TextDisplay disp = displays.remove(uid);
        if (disp != null && disp.isValid()) disp.remove();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Count non-air blocks actually present in the world within the craft's hitbox. */
    private int countBlocks(Craft craft) {
        World world = craft.getWorld();
        int count = 0;
        for (var loc : craft.getHitBox()) {
            if (!world.getBlockAt(loc.getX(), loc.getY(), loc.getZ()).getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    private Location above(HitBox box, World world) {
        double x = (box.getMinX() + box.getMaxX()) / 2.0;
        double y = box.getMaxY() + 4.0;
        double z = (box.getMinZ() + box.getMaxZ()) / 2.0;
        return new Location(world, x, y, z);
    }

    private static final NamespacedKey KEY_SINK_PCT =
            new NamespacedKey("movecraft", "overall_sink_percent");

    private Component buildText(Craft craft, int curr, int orig) {
        // Read the sink threshold so 0% = craft is about to sink, not physically empty
        double sinkLost = 0.0;
        try { sinkLost = craft.getType().getDoubleProperty(KEY_SINK_PCT) / 100.0; }
        catch (Exception ignored) {}
        double sinkRatio = 1.0 - sinkLost; // fraction of blocks at which craft sinks

        double ratio = frac(curr, orig);
        double pct;
        if (sinkLost <= 0.0) {
            pct = ratio * 100.0;
        } else {
            pct = Math.max(0.0, (ratio - sinkRatio) / (1.0 - sinkRatio)) * 100.0;
        }
        int    filled = (int) Math.round(pct / 10.0);

        NamedTextColor hColor = pct > 60 ? NamedTextColor.GREEN
                              : pct > 30 ? NamedTextColor.YELLOW
                              : NamedTextColor.RED;

        String filledBar = "█".repeat(filled);
        String emptyBar  = "░".repeat(10 - filled);

        return Component.text()
                .append(Component.text("⚓ " + typeName(craft))
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true))
                .appendNewline()
                .append(Component.text(filledBar).color(hColor))
                .append(Component.text(emptyBar).color(NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format(" %.0f%%", pct)).color(hColor))
                .append(Component.text(" (" + curr + "/" + orig + ")").color(NamedTextColor.GRAY))
                .build();
    }

    private String typeName(Craft craft) {
        try {
            // CraftType exposes no public key getter — find the NamespacedKey field via reflection
            for (var field : craft.getType().getClass().getDeclaredFields()) {
                if (field.getType() == org.bukkit.NamespacedKey.class) {
                    field.setAccessible(true);
                    org.bukkit.NamespacedKey key = (org.bukkit.NamespacedKey) field.get(craft.getType());
                    String n = key.getKey();
                    if (!n.isBlank()) return n.substring(0, 1).toUpperCase() + n.substring(1);
                }
            }
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    private double frac(int curr, int orig) {
        return orig <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) curr / orig));
    }
}
