package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PilotedCraft;
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

    // craft UUID → TextDisplay entity floating above the craft
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    // craft UUID → Craft reference for the periodic health update
    private final Map<UUID, Craft>       activeCrafts = new ConcurrentHashMap<>();

    // Scale for the display entity — 2.5× makes it readable from ~40–80 blocks away
    private static final Transformation SCALE = new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(2.5f, 2.5f, 2.5f),
            new AxisAngle4f(0, 0, 0, 1)
    );

    public HealthBarListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        // Refresh text every 10 ticks (0.5 s) to pick up block loss from combat
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 10L);
    }

    // ── Craft lifecycle ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDetect(CraftDetectEvent event) {
        Craft craft = event.getCraft();
        UUID uid   = craft.getUUID();
        int orig   = craft.getOrigBlockCount();
        int curr   = craft.getHitBox().size();
        if (orig <= 0) orig = curr;

        Location pos = above(craft.getHitBox(), craft.getWorld());

        int finalOrig = orig;
        TextDisplay disp = pos.getWorld().spawn(pos, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER); // always faces the viewer
            e.setDefaultBackground(false);
            e.setShadowed(true);
            e.setPersistent(false);  // not saved to disk
            e.setViewRange(1.5f);   // ~96 blocks visibility
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

    // ── Follow craft movement ─────────────────────────────────────────────────

    /**
     * CraftTranslateEvent fires BEFORE blocks physically move,
     * but getNewHitBox() gives us the destination — teleport to it immediately.
     * The sub-tick visual gap is imperceptible.
     */
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

            int orig = craft.getOrigBlockCount();
            int curr = craft.getHitBox().size();
            if (orig <= 0) orig = curr;

            disp.text(buildText(craft, curr, orig));
        });
    }

    private void remove(UUID uid) {
        activeCrafts.remove(uid);
        TextDisplay disp = displays.remove(uid);
        if (disp != null && disp.isValid()) disp.remove();
    }

    // ── Position ──────────────────────────────────────────────────────────────

    /** 4 blocks above the highest point of the craft's hitbox, horizontally centred. */
    private Location above(HitBox box, World world) {
        double x = (box.getMinX() + box.getMaxX()) / 2.0;
        double y = box.getMaxY() + 4.0;
        double z = (box.getMinZ() + box.getMaxZ()) / 2.0;
        return new Location(world, x, y, z);
    }

    // ── Text content ──────────────────────────────────────────────────────────

    private Component buildText(Craft craft, int curr, int orig) {
        double pct    = frac(curr, orig) * 100;
        int filled    = (int) Math.round(frac(curr, orig) * 10);

        NamedTextColor hColor = pct > 60 ? NamedTextColor.GREEN
                              : pct > 30 ? NamedTextColor.YELLOW
                              : NamedTextColor.RED;

        String filledBar = "█".repeat(filled);
        String emptyBar  = "░".repeat(10 - filled);

        return Component.text()
                .append(Component.text("⚓ " + name(craft))
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true))
                .appendNewline()
                .append(Component.text(filledBar).color(hColor))
                .append(Component.text(emptyBar).color(NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format(" %.0f%%", pct)).color(hColor))
                .append(Component.text(" (" + curr + "/" + orig + ")").color(NamedTextColor.GRAY))
                .build();
    }

    private String name(Craft craft) {
        try {
            String n = craft.getName();
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    private double frac(int curr, int orig) {
        return orig <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) curr / orig));
    }
}
