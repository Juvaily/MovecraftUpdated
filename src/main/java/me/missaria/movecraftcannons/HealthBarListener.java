package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.craft.type.RequiredBlockEntry;
import net.countercraft.movecraft.events.CraftCollisionExplosionEvent;
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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HealthBarListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, TextDisplay> displays       = new ConcurrentHashMap<>();
    private final Map<UUID, Craft>       activeCrafts   = new ConcurrentHashMap<>();
    // Stored at detection time using the same counting method as the live scan
    private final Map<UUID, Integer>     origBlockCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>     origMoveCount  = new ConcurrentHashMap<>();

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
        UUID  uid   = craft.getUUID();

        EnumSet<Material> moveMats = moveBlockMaterials(craft);
        int[] scan = scanCraft(craft, moveMats);

        origBlockCount.put(uid, scan[0]);
        origMoveCount.put(uid, scan[2]);

        Location pos = above(craft.getHitBox(), craft.getWorld());
        int origB = scan[0], origM = scan[2];
        TextDisplay disp = pos.getWorld().spawn(pos, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setDefaultBackground(false);
            e.setShadowed(true);
            e.setPersistent(false);
            e.setViewRange(1.5f);
            e.setTransformation(SCALE);
            e.text(buildText(craft, scan[0], origB, scan[1] == 1, scan[2], origM));
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

    // ── Combat refresh ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCollisionExplosion(CraftCollisionExplosionEvent event) {
        UUID uid = event.getCraft().getUUID();
        if (!displays.containsKey(uid)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshDisplay(uid), 1L);
    }

    // ── Follow movement ───────────────────────────────────────────────────────

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

        EnumSet<Material> moveMats = moveBlockMaterials(craft);
        int[] scan  = scanCraft(craft, moveMats);
        int origB   = origBlockCount.getOrDefault(uid, scan[0]);
        int origM   = origMoveCount.getOrDefault(uid, scan[2]);

        disp.text(buildText(craft, scan[0], origB, scan[1] == 1, scan[2], origM));
    }

    private void remove(UUID uid) {
        activeCrafts.remove(uid);
        origBlockCount.remove(uid);
        origMoveCount.remove(uid);
        TextDisplay disp = displays.remove(uid);
        if (disp != null && disp.isValid()) disp.remove();
    }

    // ── Scan (single pass) ────────────────────────────────────────────────────

    /** Returns [blockCount, onFire (0/1), moveBlockCount]. */
    private int[] scanCraft(Craft craft, EnumSet<Material> moveMats) {
        World world = craft.getWorld();
        int blocks = 0, fire = 0, move = 0;
        for (var loc : craft.getHitBox()) {
            Material m = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ()).getType();
            if (!m.isAir()) blocks++;
            if (m == Material.FIRE || m == Material.SOUL_FIRE) fire = 1;
            if (moveMats != null && moveMats.contains(m)) move++;
            if (fire == 0) {
                Material above = world.getBlockAt(loc.getX(), loc.getY() + 1, loc.getZ()).getType();
                if (above == Material.FIRE || above == Material.SOUL_FIRE) fire = 1;
            }
        }
        return new int[]{blocks, fire, move};
    }

    /** Collects all materials from the craft type's moveBlocks required-block entries. */
    @SuppressWarnings("unchecked")
    private EnumSet<Material> moveBlockMaterials(Craft craft) {
        try {
            Set<RequiredBlockEntry> entries =
                    (Set<RequiredBlockEntry>) craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS);
            if (entries == null || entries.isEmpty()) return null;
            EnumSet<Material> result = EnumSet.noneOf(Material.class);
            for (RequiredBlockEntry entry : entries) {
                Set<?> mats = entry.getMaterials();
                for (Object o : mats) {
                    if (o instanceof Material mat) result.add(mat);
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private Location above(HitBox box, World world) {
        double x = (box.getMinX() + box.getMaxX()) / 2.0;
        double y = box.getMaxY() + 4.0;
        double z = (box.getMinZ() + box.getMaxZ()) / 2.0;
        return new Location(world, x, y, z);
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private Component buildText(Craft craft, int curr, int orig,
                                boolean onFire, int moveCurr, int moveOrig) {
        double sinkLost = 0.0;
        try { sinkLost = craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT) / 100.0; }
        catch (Exception ignored) {}
        double ratio     = frac(curr, orig);
        double sinkRatio = 1.0 - sinkLost;
        double pct       = sinkLost <= 0
                ? ratio * 100.0
                : Math.max(0.0, (ratio - sinkRatio) / (1.0 - sinkRatio)) * 100.0;
        int filled = (int) Math.round(pct / 10.0);

        NamedTextColor hColor = pct > 60 ? NamedTextColor.GREEN
                              : pct > 30 ? NamedTextColor.YELLOW
                              : NamedTextColor.RED;

        var text = Component.text()
                .append(Component.text("⚓ " + typeName(craft))
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true))
                .appendNewline()
                .append(Component.text("█".repeat(filled)).color(hColor))
                .append(Component.text("░".repeat(10 - filled)).color(NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format(" %.0f%%", pct)).color(hColor))
                .append(Component.text(" (" + curr + "/" + orig + ")").color(NamedTextColor.GRAY));

        // Move blocks line
        if (moveOrig > 0) {
            NamedTextColor mColor = moveCurr >= moveOrig * 0.6 ? NamedTextColor.GREEN
                                  : moveCurr >= moveOrig * 0.3 ? NamedTextColor.YELLOW
                                  : NamedTextColor.RED;
            text.appendNewline()
                .append(Component.text("⚙ Мувблоки: ").color(NamedTextColor.GRAY))
                .append(Component.text(moveCurr + "/" + moveOrig).color(mColor));
        }

        // Fire line
        if (onFire) {
            text.appendNewline()
                .append(Component.text("🔥 Горит!")
                        .color(NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true));
        }

        return text.build();
    }

    private String typeName(Craft craft) {
        try {
            String n = craft.getType().getStringProperty(CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    private double frac(int curr, int orig) {
        return orig <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) curr / orig));
    }
}
