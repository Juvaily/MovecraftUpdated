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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HealthBarListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, TextDisplay>               displays       = new ConcurrentHashMap<>();
    private final Map<UUID, Craft>                     activeCrafts   = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>                   origBlockCount = new ConcurrentHashMap<>();
    private final Map<UUID, List<RequiredBlockEntry>>  moveEntries    = new ConcurrentHashMap<>();

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

        List<RequiredBlockEntry> entries = loadMoveEntries(craft);
        moveEntries.put(uid, entries);

        int[] scan = scan(craft, entries);
        origBlockCount.put(uid, scan[0]);

        Location pos = above(craft.getHitBox(), craft.getWorld());
        int origB = scan[0];
        TextDisplay disp = pos.getWorld().spawn(pos, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setDefaultBackground(false);
            e.setShadowed(true);
            e.setPersistent(false);
            e.setViewRange(1.5f);
            e.setTransformation(SCALE);
            e.text(buildText(craft, scan, origB, entries));
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

        List<RequiredBlockEntry> entries = moveEntries.getOrDefault(uid, List.of());
        int[] sc   = scan(craft, entries);
        int   orig = origBlockCount.getOrDefault(uid, sc[0]);
        disp.text(buildText(craft, sc, orig, entries));
    }

    private void remove(UUID uid) {
        activeCrafts.remove(uid);
        origBlockCount.remove(uid);
        moveEntries.remove(uid);
        TextDisplay disp = displays.remove(uid);
        if (disp != null && disp.isValid()) disp.remove();
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    /**
     * Single-pass scan. Returns int[] where:
     *   [0] = block count (allowed, non-fluid)
     *   [1] = 1 if fire detected, 0 otherwise
     *   [2 + i] = count of blocks matching entries.get(i)
     */
    private int[] scan(Craft craft, List<RequiredBlockEntry> entries) {
        int[] result = new int[2 + entries.size()];
        EnumSet<Material> allowed = allowedMats(craft);
        World world = craft.getWorld();

        for (var loc : craft.getHitBox()) {
            Material m = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ()).getType();

            boolean isFluid = m == Material.WATER || m == Material.LAVA
                    || m == Material.CAVE_AIR || m == Material.VOID_AIR;
            boolean isFire  = m == Material.FIRE || m == Material.SOUL_FIRE;

            if (!isFluid && !isFire) {
                if (allowed != null ? allowed.contains(m) : !m.isAir()) result[0]++;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).contains(m)) result[2 + i]++;
                }
            }

            if (result[1] == 0) {
                if (isFire) {
                    result[1] = 1;
                } else {
                    Material above = world.getBlockAt(loc.getX(), loc.getY() + 1, loc.getZ()).getType();
                    if (above == Material.FIRE || above == Material.SOUL_FIRE) result[1] = 1;
                }
            }
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EnumSet<Material> allowedMats(Craft craft) {
        try {
            EnumSet<Material> s = craft.getType().getMaterialSetProperty(CraftType.ALLOWED_BLOCKS);
            if (s != null && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<RequiredBlockEntry> loadMoveEntries(Craft craft) {
        try {
            Set<RequiredBlockEntry> set =
                    (Set<RequiredBlockEntry>) craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS);
            if (set != null && !set.isEmpty()) {
                if (plugin.isDebug())
                    plugin.getLogger().info("[moveblocks] loaded " + set.size() + " entries for " + typeName(craft));
                return new ArrayList<>(set);
            }
        } catch (Exception e) {
            if (plugin.isDebug())
                plugin.getLogger().info("[moveblocks] failed: " + e.getMessage());
        }
        return List.of();
    }

    private Location above(HitBox box, World world) {
        return new Location(world,
                (box.getMinX() + box.getMaxX()) / 2.0,
                box.getMaxY() + 4.0,
                (box.getMinZ() + box.getMaxZ()) / 2.0);
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private Component buildText(Craft craft, int[] sc, int orig,
                                List<RequiredBlockEntry> entries) {
        int curr = sc[0];

        // Effective health relative to sink threshold
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

        // Per-entry move blocks lines
        for (int i = 0; i < entries.size(); i++) {
            RequiredBlockEntry entry = entries.get(i);
            int entryCurr = sc[2 + i];
            boolean met   = entry.check(entryCurr, curr);

            NamedTextColor mc = met ? NamedTextColor.GREEN : NamedTextColor.RED;
            String label  = entryLabel(entry);
            String req    = entryReq(entry, curr);

            text.appendNewline()
                .append(Component.text("⚙ " + label + ": ")
                        .color(NamedTextColor.GRAY))
                .append(Component.text(entryCurr + " " + req)
                        .color(mc));
        }

        // Fire indicator
        if (sc[1] == 1) {
            text.appendNewline()
                .append(Component.text("🔥 Горит!")
                        .color(NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true));
        }

        return text.build();
    }

    /** Human-readable label for a RequiredBlockEntry. */
    private String entryLabel(RequiredBlockEntry entry) {
        String n = entry.getName();
        if (n != null && !n.isBlank()) return n;
        // Fall back to comma-separated material names (first 2)
        var mats = new ArrayList<>(entry.getMaterials());
        if (mats.isEmpty()) return "?";
        String first = mats.get(0).toString().replace("_", " ").toLowerCase();
        if (mats.size() > 1) first += " +" + (mats.size() - 1);
        return first;
    }

    /** Threshold string: "≥N" (count) or "≥N% (of total)" (ratio). */
    private String entryReq(RequiredBlockEntry entry, int total) {
        double min = entry.getMin();
        double max = entry.getMax();
        boolean numMin = entry.isNumericMin();
        boolean numMax = entry.isNumericMax();

        String minStr = numMin
                ? "≥" + (int) min
                : "≥" + String.format("%.0f%%", min * 100) + " (" + (int)(min * total) + ")";

        if (max > 0 && max < Double.MAX_VALUE / 2) {
            String maxStr = numMax
                    ? "≤" + (int) max
                    : "≤" + String.format("%.0f%%", max * 100) + " (" + (int)(max * total) + ")";
            return "[" + minStr + " " + maxStr + "]";
        }
        return "[" + minStr + "]";
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
