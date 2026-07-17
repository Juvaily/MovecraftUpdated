package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.craft.type.RequiredBlockEntry;
import org.bukkit.NamespacedKey;
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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HealthBarListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    private final Map<UUID, TextDisplay>              displays       = new ConcurrentHashMap<>();
    private final Map<UUID, Craft>                    activeCrafts   = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>                  origBlockCount = new ConcurrentHashMap<>();
    private final Map<UUID, List<RequiredBlockEntry>> moveEntries    = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    origEntryCount = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    moveMinCount   = new ConcurrentHashMap<>();
    private final Map<UUID, List<RequiredBlockEntry>> flyEntries     = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    origFlyCount   = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    flyMinCount    = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    scanCache          = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Material, Integer>>   materialCountCache = new ConcurrentHashMap<>();

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

        List<RequiredBlockEntry> entries = loadEntries(craft, CraftType.MOVE_BLOCKS, "moveblocks");
        List<RequiredBlockEntry> fEntries = loadEntries(craft, CraftType.FLY_BLOCKS, "flyblocks");
        moveEntries.put(uid, entries);
        flyEntries.put(uid, fEntries);

        int[] sc = scan(uid, craft, entries, fEntries);
        origBlockCount.put(uid, sc[0]);
        int craftSize = sc[0];
        if (!entries.isEmpty()) {
            int[] maxCounts = new int[entries.size()];
            int[] minCounts = new int[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                RequiredBlockEntry e = entries.get(i);
                maxCounts[i] = e.isNumericMax()
                        ? (int) e.getMax()
                        : (int) Math.round(craftSize * e.getMax() / 100.0);
                minCounts[i] = e.isNumericMin()
                        ? (int) e.getMin()
                        : (int) Math.round(craftSize * e.getMin() / 100.0);
            }
            origEntryCount.put(uid, maxCounts);
            moveMinCount.put(uid, minCounts);

            // Disabled state is managed by Movecraft; we never disable crafts ourselves.
        }
        if (!fEntries.isEmpty()) {
            int[] maxCounts = new int[fEntries.size()];
            int[] minCounts = new int[fEntries.size()];
            for (int i = 0; i < fEntries.size(); i++) {
                RequiredBlockEntry e = fEntries.get(i);
                maxCounts[i] = e.isNumericMax()
                        ? (int) e.getMax()
                        : (int) Math.round(craftSize * e.getMax() / 100.0);
                minCounts[i] = e.isNumericMin()
                        ? (int) e.getMin()
                        : (int) Math.round(craftSize * e.getMin() / 100.0);
            }
            origFlyCount.put(uid, maxCounts);
            flyMinCount.put(uid, minCounts);
        }

        Location pos    = above(craft.getHitBox(), craft.getWorld());
        int      origB  = sc[0];
        int[]    origE  = origEntryCount.getOrDefault(uid, new int[0]);
        int[]    minE   = moveMinCount.getOrDefault(uid, new int[0]);
        int[]    origF  = origFlyCount.getOrDefault(uid, new int[0]);
        int[]    minF   = flyMinCount.getOrDefault(uid, new int[0]);

        TextDisplay disp = pos.getWorld().spawn(pos, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(200, 0, 0, 0));
            e.setShadowed(true);
            e.setPersistent(false);
            e.setViewRange(1.5f);
            e.setTransformation(SCALE);
            Player pilot0 = craft instanceof net.countercraft.movecraft.craft.PlayerCraft pc0 ? pc0.getPilot() : null;
            e.text(buildText(pilot0, craft, sc, origB, entries, origE, minE, fEntries, origF, minF));
        });

        displays.put(uid, disp);
        activeCrafts.put(uid, craft);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRelease(CraftReleaseEvent event) {
        remove(event.getCraft().getUUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSink(CraftSinkEvent event) {
        Craft sinkCraft = event.getCraft();
        UUID uid = sinkCraft.getUUID();
        TextDisplay disp = displays.get(uid);
        if (disp != null) {
            Player pilot = sinkCraft instanceof net.countercraft.movecraft.craft.PlayerCraft pc ? pc.getPilot() : null;
            String msg = pilot != null ? Lang.get("health.sinking", pilot) : Lang.get("health.sinking");
            disp.text(Component.text(msg, NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
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

        Player pilot = craft instanceof net.countercraft.movecraft.craft.PlayerCraft pc ? pc.getPilot() : null;

        List<RequiredBlockEntry> entries  = moveEntries.getOrDefault(uid, List.of());
        List<RequiredBlockEntry> fEntries = flyEntries.getOrDefault(uid, List.of());
        int[] sc    = scan(uid, craft, entries, fEntries);
        scanCache.put(uid, sc);
        int   orig  = origBlockCount.getOrDefault(uid, sc[0]);
        int[] origE = origEntryCount.getOrDefault(uid, new int[0]);
        int[] minE  = moveMinCount.getOrDefault(uid, new int[0]);
        int[] origF = origFlyCount.getOrDefault(uid, new int[0]);
        int[] minF  = flyMinCount.getOrDefault(uid, new int[0]);
        disp.text(buildText(pilot, craft, sc, orig, entries, origE, minE, fEntries, origF, minF));
    }

    private void remove(UUID uid) {
        activeCrafts.remove(uid);
        origBlockCount.remove(uid);
        moveEntries.remove(uid);
        origEntryCount.remove(uid);
        moveMinCount.remove(uid);
        flyEntries.remove(uid);
        origFlyCount.remove(uid);
        flyMinCount.remove(uid);
        scanCache.remove(uid);
        materialCountCache.remove(uid);
        TextDisplay disp = displays.remove(uid);
        if (disp != null && disp.isValid()) disp.remove();
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    /**
     * Single-pass scan. Returns int[] where:
     *   [0]                       = total craft block count (allowed, non-fluid, non-fire)
     *   [1]                       = 1 if fire detected near craft, 0 otherwise
     *   [2 .. 2+move-1]           = block count matching moveEntries.get(i)
     *   [2+move .. 2+move+fly-1]  = block count matching flyEntries.get(i)
     */
    private int[] scan(UUID uid, Craft craft, List<RequiredBlockEntry> entries, List<RequiredBlockEntry> fEntries) {
        int[] result = new int[2 + entries.size() + fEntries.size()];
        EnumSet<Material> allowed = allowedMats(craft);
        World world = craft.getWorld();
        Map<Material, Integer> matCounts = new HashMap<>();

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
                for (int i = 0; i < fEntries.size(); i++) {
                    if (fEntries.get(i).contains(m)) result[2 + entries.size() + i]++;
                }
                matCounts.merge(m, 1, Integer::sum);
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
        materialCountCache.put(uid, matCounts);
        return result;
    }

    // ── Required block entries ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<RequiredBlockEntry> loadEntries(Craft craft, NamespacedKey prop, String tag) {
        try {
            Set<RequiredBlockEntry> set =
                    (Set<RequiredBlockEntry>) craft.getType().getRequiredBlockProperty(prop);
            if (set != null && !set.isEmpty()) {
                if (plugin.isDebug())
                    plugin.getLogger().info("[" + tag + "] " + set.size() + " entries");
                return new ArrayList<>(set);
            }
        } catch (Exception e) {
            if (plugin.isDebug())
                plugin.getLogger().info("[" + tag + "] error: " + e.getMessage());
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EnumSet<Material> allowedMats(Craft craft) {
        try {
            EnumSet<Material> s = craft.getType().getMaterialSetProperty(CraftType.ALLOWED_BLOCKS);
            if (s != null && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        return null;
    }

    private Location above(HitBox box, World world) {
        return new Location(world,
                (box.getMinX() + box.getMaxX()) / 2.0,
                box.getMaxY() + 4.0,
                (box.getMinZ() + box.getMaxZ()) / 2.0);
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private Component buildText(Player pilot, Craft craft, int[] sc, int orig,
                                List<RequiredBlockEntry> entries, int[] origE, int[] minE,
                                List<RequiredBlockEntry> fEntries, int[] origF, int[] minF) {
        int curr = sc[0];

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
                .append(Component.text(craftTitle(craft)).color(NamedTextColor.GOLD))
                .appendNewline()
                .append(Component.text("█".repeat(filled)).color(hColor))
                .append(Component.text("░".repeat(10 - filled)).color(NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format(" %.0f%%", pct)).color(hColor))
                .append(Component.text(" (" + curr + "/" + orig + ")").color(NamedTextColor.GRAY));

        if (sc[1] == 1) {
            String fireMsg = pilot != null ? Lang.get("health.fire", pilot) : Lang.get("health.fire");
            text.appendNewline()
                .append(Component.text(fireMsg).color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        }

        return text.build();
    }

    /**
     * Combined moveblock % relative to max (0–100) for sail-gear thresholds.
     * If the craft has any wool entry in moveblocks, sums ALL moveblock entries.
     * Returns 100 if no wool entry found (not a sail ship).
     */
    public double getSailWoolRawPct(Craft craft) {
        UUID uid = craft.getUUID();
        int[] sc = scanCache.get(uid);
        if (sc == null) return 100.0;
        List<RequiredBlockEntry> entries = moveEntries.getOrDefault(uid, List.of());
        int[] origE = origEntryCount.getOrDefault(uid, new int[0]);

        boolean hasWool = false;
        for (int i = 0; i < entries.size(); i++) {
            if (origE.length <= i || origE[i] <= 0) continue;
            try {
                for (Material m : entries.get(i).getMaterials())
                    if (m.name().endsWith("_WOOL")) { hasWool = true; break; }
            } catch (Exception ignored) {}
            if (hasWool) break;
        }
        if (!hasWool) return 100.0;

        int woolCurr = 0, woolOrig = 0;
        int otherCurr = 0, otherOrig = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (origE.length <= i || origE[i] <= 0) continue;
            int curr = sc.length > 2 + i ? sc[2 + i] : 0;
            boolean isWool = false;
            try {
                for (Material m : entries.get(i).getMaterials())
                    if (m.name().endsWith("_WOOL")) { isWool = true; break; }
            } catch (Exception ignored) {}
            if (isWool) {
                woolCurr += curr;
                woolOrig += origE[i];
            } else if (curr > 0) {
                otherCurr += curr;
                otherOrig += origE[i];
            }
        }
        // No wool at all on ship: fall back to other moveblocks
        if (woolCurr == 0 && otherOrig > 0)
            return Math.max(0.0, Math.min(100.0, (double) otherCurr / otherOrig * 100.0));
        if (woolOrig <= 0) return 0.0;
        // Wool present: other blocks present on ship boost the total
        return Math.max(0.0, Math.min(100.0, (double)(woolCurr + otherCurr) / woolOrig * 100.0));
    }

    /** Health bar lines for the pilot's sidebar HUD (legacy §-color strings). */
    public List<String> getHealthLines(Player pilot, Craft craft) {
        UUID uid = craft.getUUID();
        int[] sc = scanCache.get(uid);
        if (sc == null || sc.length == 0) return List.of();

        List<RequiredBlockEntry> entries  = moveEntries.getOrDefault(uid, List.of());
        List<RequiredBlockEntry> fEntries = flyEntries.getOrDefault(uid, List.of());
        int   orig  = origBlockCount.getOrDefault(uid, sc[0]);
        int[] origE = origEntryCount.getOrDefault(uid, new int[0]);
        int[] minE  = moveMinCount.getOrDefault(uid, new int[0]);
        int[] origF = origFlyCount.getOrDefault(uid, new int[0]);
        int[] minF  = flyMinCount.getOrDefault(uid, new int[0]);
        int curr = sc[0];

        double sinkLost = 0.0;
        try { sinkLost = craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT) / 100.0; }
        catch (Exception ignored) {}
        double ratio = frac(curr, orig);
        double sinkRatio = 1.0 - sinkLost;
        double pct = sinkLost <= 0
                ? ratio * 100.0
                : Math.max(0.0, (ratio - sinkRatio) / (1.0 - sinkRatio)) * 100.0;
        int filled = (int) Math.round(pct / 10.0);
        String hCol = pct > 60 ? "§a" : pct > 30 ? "§e" : "§c";

        List<String> lines = new ArrayList<>();
        lines.add(hCol + "█".repeat(filled) + "§8" + "░".repeat(10 - filled)
                + " " + hCol + String.format("%.0f%%", pct)
                + " §8(" + curr + "/" + orig + ")");

        Map<Material, Integer> matCache = materialCountCache.getOrDefault(uid, Map.of());

        for (int i = 0; i < entries.size(); i++) {
            if (origE.length <= i || origE[i] <= 0) continue;
            int currE = sc.length > 2 + i ? sc[2 + i] : 0;
            if (currE <= 0) continue;
            int maxEntry = origE[i];
            addEntryLines(lines, "⚙", pilot, entries.get(i), currE, maxEntry, matCache);
        }

        int base = 2 + entries.size();
        for (int i = 0; i < fEntries.size(); i++) {
            if (origF.length <= i || origF[i] <= 0) continue;
            int currE = sc.length > base + i ? sc[base + i] : 0;
            if (currE <= 0) continue;
            int maxEntry = origF[i];
            addEntryLines(lines, "🧱", pilot, fEntries.get(i), currE, maxEntry, matCache);
        }

        if (sc[1] == 1) lines.add("§c§l" + Lang.get("health.fire", pilot));

        return lines;
    }

    private static final Map<String, String> FAMILY_ICON;
    static {
        FAMILY_ICON = new java.util.HashMap<>();
        FAMILY_ICON.put("planks",        "🪵");
        FAMILY_ICON.put("logs",          "🌲");
        FAMILY_ICON.put("slabs",         "📏");
        FAMILY_ICON.put("fences",        "🚧");
        FAMILY_ICON.put("fence_gates",   "🚪");
        FAMILY_ICON.put("stairs",        "📐");
        FAMILY_ICON.put("wool",          "🐑");
        FAMILY_ICON.put("beds",          "🛏");
        FAMILY_ICON.put("carpets",       "🎨");
        FAMILY_ICON.put("buttons",       "🔘");
        FAMILY_ICON.put("trapdoors",     "🪜");
        FAMILY_ICON.put("doors",         "🚪");
        FAMILY_ICON.put("banners",       "🏳");
        FAMILY_ICON.put("shulker_boxes", "📦");
        FAMILY_ICON.put("concrete",      "🏗");
        FAMILY_ICON.put("terracotta",    "🏺");
        FAMILY_ICON.put("glass",         "🪟");
        FAMILY_ICON.put("leaves",        "🍃");
        FAMILY_ICON.put("ores",          "⛏");
        FAMILY_ICON.put("redstone",      "⚡");
        FAMILY_ICON.put("iron",          "⚙");
        FAMILY_ICON.put("gold",          "🌕");
        FAMILY_ICON.put("diamond",       "💎");
        FAMILY_ICON.put("emerald",       "💚");
        FAMILY_ICON.put("netherite",     "🖤");
        FAMILY_ICON.put("lapis",         "🔵");
        FAMILY_ICON.put("coal",          "🪨");
        FAMILY_ICON.put("chests",        "📦");
        FAMILY_ICON.put("barrels",       "🪣");
        FAMILY_ICON.put("hoppers",       "🔽");
        FAMILY_ICON.put("dispensers",    "📤");
        FAMILY_ICON.put("tnt",           "💥");
        FAMILY_ICON.put("obsidian",      "⚫");
        FAMILY_ICON.put("ice",           "🧊");
        FAMILY_ICON.put("sand",          "⏳");
        FAMILY_ICON.put("stone",         "🪨");
        FAMILY_ICON.put("hay",           "🌾");
        FAMILY_ICON.put("sponge",        "🧽");
    }

    private static String materialFamily(Material m) {
        String n = m.name();
        if (n.endsWith("_FENCE_GATE"))   return "fence_gates";
        if (n.endsWith("_FENCE"))        return "fences";
        if (n.endsWith("_PLANKS"))       return "planks";
        if (n.endsWith("_LOG"))          return "logs";
        if (n.endsWith("_WOOD"))         return "logs";
        if (n.endsWith("_SLAB"))         return "slabs";
        if (n.endsWith("_STAIRS"))       return "stairs";
        if (n.endsWith("_WOOL"))         return "wool";
        if (n.endsWith("_BED"))          return "beds";
        if (n.endsWith("_CARPET"))       return "carpets";
        if (n.endsWith("_BUTTON"))       return "buttons";
        if (n.endsWith("_TRAPDOOR"))     return "trapdoors";
        if (n.endsWith("_DOOR"))         return "doors";
        if (n.endsWith("_BANNER"))       return "banners";
        if (n.endsWith("_SHULKER_BOX"))  return "shulker_boxes";
        if (n.endsWith("_CONCRETE"))     return "concrete";
        if (n.endsWith("_TERRACOTTA"))   return "terracotta";
        if (n.contains("GLASS"))         return "glass";
        if (n.endsWith("_LEAVES"))       return "leaves";
        if (n.endsWith("_ORE"))          return "ores";
        if (n.equals("REDSTONE_BLOCK"))  return "redstone";
        if (n.equals("IRON_BLOCK"))      return "iron";
        if (n.equals("GOLD_BLOCK"))      return "gold";
        if (n.equals("DIAMOND_BLOCK"))   return "diamond";
        if (n.equals("EMERALD_BLOCK"))   return "emerald";
        if (n.equals("NETHERITE_BLOCK")) return "netherite";
        if (n.equals("LAPIS_BLOCK"))     return "lapis";
        if (n.equals("COAL_BLOCK"))      return "coal";
        if (n.equals("CHEST") || n.equals("TRAPPED_CHEST")) return "chests";
        if (n.equals("BARREL"))                             return "barrels";
        if (n.equals("HOPPER"))                             return "hoppers";
        if (n.equals("DROPPER") || n.equals("DISPENSER"))  return "dispensers";
        if (n.equals("TNT"))             return "tnt";
        if (n.contains("OBSIDIAN"))      return "obsidian";
        if (n.contains("ICE"))           return "ice";
        if (n.equals("SAND") || n.equals("RED_SAND") || n.equals("GRAVEL")) return "sand";
        if (n.contains("STONE") || n.contains("COBBLESTONE")) return "stone";
        if (n.equals("HAY_BLOCK"))       return "hay";
        if (n.equals("SPONGE") || n.equals("WET_SPONGE")) return "sponge";
        return n.toLowerCase();
    }

    private static String familyName(String family, Player pilot) {
        String key = "health.family." + family;
        String val = pilot != null ? Lang.get(key, pilot) : Lang.get(key);
        return val.equals(key) ? family : val;
    }

    private void addEntryLines(List<String> lines, String icon, Player pilot,
                               RequiredBlockEntry entry, int currE, int maxEntry,
                               Map<Material, Integer> matCache) {
        // Group entry materials by block family
        Map<String, Integer> familyCounts = new LinkedHashMap<>();
        try {
            for (Object obj : entry.getMaterials()) {
                Material m = (Material) obj;
                familyCounts.merge(materialFamily(m), matCache.getOrDefault(m, 0), Integer::sum);
            }
        } catch (Exception ignored) {}

        String dominant = familyCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        String dominantName  = dominant != null ? familyName(dominant, pilot) : entryLabel(pilot, entry);
        String othersIcons   = familyCounts.keySet().stream()
                .filter(k -> !k.equals(dominant))
                .map(k -> FAMILY_ICON.getOrDefault(k, ""))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining());
        String label = dominantName + (othersIcons.isEmpty() ? "" : ", " + othersIcons);
        double ePct = Math.min(100.0, (double) currE / maxEntry * 100.0);
        int eFilled = (int) Math.round(ePct / 20.0);
        String mc = ePct >= 70.0 ? "§a" : ePct >= 30.0 ? "§e" : "§c";
        lines.add("§7" + icon + " " + label + " "
                + mc + "§l" + "█".repeat(eFilled)
                + "§8§l" + "░".repeat(5 - eFilled)
                + " " + mc + String.format("%.0f%%", ePct));
    }

    /** Label for a moveblock/flyblock entry: lang file → RU_NAMES → fallback. */
    private String entryLabel(Player pilot, RequiredBlockEntry entry) {
        // 1. Custom name from craft YAML takes priority
        String n = entry.getName();
        if (n != null && !n.isBlank()) {
            String key = "health.entry." + n.trim().toLowerCase();
            String localized = pilot != null ? Lang.get(key, pilot) : Lang.get(key);
            if (!localized.equals(key)) return localized;
            try {
                Material m = Material.matchMaterial(n.trim().toLowerCase());
                if (m != null) {
                    String ru = RU_NAMES.get(m);
                    if (ru != null) return ru;
                }
            } catch (Exception ignored) {}
            return n;
        }

        // 2. Single-material entries: check lang file, then RU_NAMES
        try {
            var mats = new ArrayList<>(entry.getMaterials());
            if (!mats.isEmpty()) {
                Material m = (Material) mats.get(0);
                String matKey = "health.mat." + m.name().toLowerCase();
                String localized = pilot != null ? Lang.get(matKey, pilot) : Lang.get(matKey);
                if (!localized.equals(matKey)) return localized;
                if (mats.size() == 1) {
                    String ru = RU_NAMES.get(m);
                    if (ru != null) return ru;
                }
                return m.name().replace('_', ' ').toLowerCase();
            }
        } catch (Exception ignored) {}

        return pilot != null ? Lang.get("health.entry.block", pilot) : Lang.get("health.entry.block");
    }

    private String craftTitle(Craft craft) {
        // Individual craft name (set on the control sign) takes priority
        try {
            String n = craft.getName();
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        // Fall back to craft type name
        try {
            String n = craft.getType().getStringProperty(CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    private double frac(int curr, int orig) {
        return orig <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) curr / orig));
    }

    // ── Russian material names ────────────────────────────────────────────────

    private static final Map<Material, String> RU_NAMES = new java.util.EnumMap<>(Material.class);
    static {
        // Logs / wood
        RU_NAMES.put(Material.OAK_LOG,      "Дубовое бревно");
        RU_NAMES.put(Material.SPRUCE_LOG,   "Еловое бревно");
        RU_NAMES.put(Material.BIRCH_LOG,    "Берёзовое бревно");
        RU_NAMES.put(Material.JUNGLE_LOG,   "Тропическое бревно");
        RU_NAMES.put(Material.ACACIA_LOG,   "Акациевое бревно");
        RU_NAMES.put(Material.DARK_OAK_LOG, "Тёмно-дубовое бревно");
        RU_NAMES.put(Material.MANGROVE_LOG, "Мангровое бревно");
        RU_NAMES.put(Material.CHERRY_LOG,   "Вишнёвое бревно");
        // Planks
        RU_NAMES.put(Material.OAK_PLANKS,      "Дубовые доски");
        RU_NAMES.put(Material.SPRUCE_PLANKS,   "Еловые доски");
        RU_NAMES.put(Material.BIRCH_PLANKS,    "Берёзовые доски");
        RU_NAMES.put(Material.JUNGLE_PLANKS,   "Тропические доски");
        RU_NAMES.put(Material.ACACIA_PLANKS,   "Акациевые доски");
        RU_NAMES.put(Material.DARK_OAK_PLANKS, "Тёмно-дубовые доски");
        // Wool
        RU_NAMES.put(Material.WHITE_WOOL,  "Белая шерсть");
        RU_NAMES.put(Material.ORANGE_WOOL, "Оранжевая шерсть");
        RU_NAMES.put(Material.YELLOW_WOOL, "Жёлтая шерсть");
        RU_NAMES.put(Material.BLUE_WOOL,   "Синяя шерсть");
        RU_NAMES.put(Material.RED_WOOL,    "Красная шерсть");
        RU_NAMES.put(Material.BLACK_WOOL,  "Чёрная шерсть");
        RU_NAMES.put(Material.GRAY_WOOL,   "Серая шерсть");
        RU_NAMES.put(Material.LIGHT_GRAY_WOOL, "Светло-серая шерсть");
        RU_NAMES.put(Material.CYAN_WOOL,   "Бирюзовая шерсть");
        RU_NAMES.put(Material.PURPLE_WOOL, "Фиолетовая шерсть");
        RU_NAMES.put(Material.GREEN_WOOL,  "Зелёная шерсть");
        RU_NAMES.put(Material.BROWN_WOOL,  "Коричневая шерсть");
        RU_NAMES.put(Material.MAGENTA_WOOL,"Пурпурная шерсть");
        RU_NAMES.put(Material.PINK_WOOL,   "Розовая шерсть");
        RU_NAMES.put(Material.LIME_WOOL,   "Лаймовая шерсть");
        RU_NAMES.put(Material.LIGHT_BLUE_WOOL, "Голубая шерсть");
        // Metal blocks
        RU_NAMES.put(Material.IRON_BLOCK,    "Железный блок");
        RU_NAMES.put(Material.GOLD_BLOCK,    "Золотой блок");
        RU_NAMES.put(Material.DIAMOND_BLOCK, "Алмазный блок");
        RU_NAMES.put(Material.EMERALD_BLOCK, "Блок изумруда");
        RU_NAMES.put(Material.COPPER_BLOCK,  "Медный блок");
        RU_NAMES.put(Material.NETHERITE_BLOCK, "Блок незерита");
        // Stone
        RU_NAMES.put(Material.STONE,       "Камень");
        RU_NAMES.put(Material.COBBLESTONE, "Булыжник");
        RU_NAMES.put(Material.STONE_BRICKS,"Каменный кирпич");
        RU_NAMES.put(Material.OBSIDIAN,    "Обсидиан");
        RU_NAMES.put(Material.NETHERRACK,  "Незерак");
        RU_NAMES.put(Material.GRAVEL,      "Гравий");
        RU_NAMES.put(Material.SAND,        "Песок");
        // Glass
        RU_NAMES.put(Material.GLASS,       "Стекло");
        RU_NAMES.put(Material.GLASS_PANE,  "Стеклянная панель");
        // Misc
        RU_NAMES.put(Material.BOOKSHELF,   "Книжный шкаф");
        RU_NAMES.put(Material.CHEST,       "Сундук");
        RU_NAMES.put(Material.FURNACE,     "Печь");
        RU_NAMES.put(Material.DISPENSER,   "Раздатчик");
        RU_NAMES.put(Material.PISTON,      "Поршень");
        RU_NAMES.put(Material.STICKY_PISTON, "Липкий поршень");
        RU_NAMES.put(Material.TNT,         "ТНТ");
        RU_NAMES.put(Material.GLOWSTONE,   "Светящийся камень");
        RU_NAMES.put(Material.SEA_LANTERN, "Морской фонарь");
        RU_NAMES.put(Material.SHROOMLIGHT, "Грибосвет");
        RU_NAMES.put(Material.CRAFTING_TABLE, "Верстак");
        RU_NAMES.put(Material.BARREL,      "Бочка");
        RU_NAMES.put(Material.BLAST_FURNACE, "Доменная печь");
        RU_NAMES.put(Material.SMOKER,      "Коптильня");
        // Sails / banners
        RU_NAMES.put(Material.WHITE_BANNER,   "Белое знамя");
        RU_NAMES.put(Material.BLACK_BANNER,   "Чёрное знамя");
        RU_NAMES.put(Material.BLUE_BANNER,    "Синее знамя");
        RU_NAMES.put(Material.RED_BANNER,     "Красное знамя");
        RU_NAMES.put(Material.YELLOW_BANNER,  "Жёлтое знамя");
        RU_NAMES.put(Material.GREEN_BANNER,   "Зелёное знамя");
        RU_NAMES.put(Material.BROWN_BANNER,   "Коричневое знамя");
        RU_NAMES.put(Material.PURPLE_BANNER,  "Фиолетовое знамя");
        RU_NAMES.put(Material.CYAN_BANNER,    "Бирюзовое знамя");
        RU_NAMES.put(Material.ORANGE_BANNER,  "Оранжевое знамя");
        RU_NAMES.put(Material.MAGENTA_BANNER, "Пурпурное знамя");
        RU_NAMES.put(Material.LIGHT_BLUE_BANNER, "Голубое знамя");
        RU_NAMES.put(Material.LIME_BANNER,    "Лаймовое знамя");
        RU_NAMES.put(Material.PINK_BANNER,    "Розовое знамя");
        RU_NAMES.put(Material.GRAY_BANNER,    "Серое знамя");
        RU_NAMES.put(Material.LIGHT_GRAY_BANNER, "Светло-серое знамя");
        // Stripped logs
        RU_NAMES.put(Material.STRIPPED_OAK_LOG,      "Очищ. дубовое бревно");
        RU_NAMES.put(Material.STRIPPED_SPRUCE_LOG,   "Очищ. еловое бревно");
        RU_NAMES.put(Material.STRIPPED_BIRCH_LOG,    "Очищ. берёзовое бревно");
        RU_NAMES.put(Material.STRIPPED_JUNGLE_LOG,   "Очищ. тропическое бревно");
        RU_NAMES.put(Material.STRIPPED_ACACIA_LOG,   "Очищ. акациевое бревно");
        RU_NAMES.put(Material.STRIPPED_DARK_OAK_LOG, "Очищ. тёмно-дубовое бревно");
        RU_NAMES.put(Material.STRIPPED_MANGROVE_LOG, "Очищ. мангровое бревно");
        RU_NAMES.put(Material.STRIPPED_CHERRY_LOG,   "Очищ. вишнёвое бревно");
        // Processed wood (rotated logs)
        RU_NAMES.put(Material.OAK_WOOD,      "Дубовая древесина");
        RU_NAMES.put(Material.SPRUCE_WOOD,   "Еловая древесина");
        RU_NAMES.put(Material.BIRCH_WOOD,    "Берёзовая древесина");
        RU_NAMES.put(Material.JUNGLE_WOOD,   "Тропическая древесина");
        RU_NAMES.put(Material.ACACIA_WOOD,   "Акациевая древесина");
        RU_NAMES.put(Material.DARK_OAK_WOOD, "Тёмно-дубовая древесина");
        RU_NAMES.put(Material.STRIPPED_OAK_WOOD,      "Очищ. дубовая древесина");
        RU_NAMES.put(Material.STRIPPED_SPRUCE_WOOD,   "Очищ. еловая древесина");
        RU_NAMES.put(Material.STRIPPED_DARK_OAK_WOOD, "Очищ. тёмно-дубовая древесина");
        // Concrete
        RU_NAMES.put(Material.WHITE_CONCRETE,      "Белый бетон");
        RU_NAMES.put(Material.ORANGE_CONCRETE,     "Оранжевый бетон");
        RU_NAMES.put(Material.MAGENTA_CONCRETE,    "Пурпурный бетон");
        RU_NAMES.put(Material.LIGHT_BLUE_CONCRETE, "Голубой бетон");
        RU_NAMES.put(Material.YELLOW_CONCRETE,     "Жёлтый бетон");
        RU_NAMES.put(Material.LIME_CONCRETE,       "Лаймовый бетон");
        RU_NAMES.put(Material.PINK_CONCRETE,       "Розовый бетон");
        RU_NAMES.put(Material.GRAY_CONCRETE,       "Серый бетон");
        RU_NAMES.put(Material.LIGHT_GRAY_CONCRETE, "Светло-серый бетон");
        RU_NAMES.put(Material.CYAN_CONCRETE,       "Бирюзовый бетон");
        RU_NAMES.put(Material.PURPLE_CONCRETE,     "Фиолетовый бетон");
        RU_NAMES.put(Material.BLUE_CONCRETE,       "Синий бетон");
        RU_NAMES.put(Material.BROWN_CONCRETE,      "Коричневый бетон");
        RU_NAMES.put(Material.GREEN_CONCRETE,      "Зелёный бетон");
        RU_NAMES.put(Material.RED_CONCRETE,        "Красный бетон");
        RU_NAMES.put(Material.BLACK_CONCRETE,      "Чёрный бетон");
        // Terracotta
        RU_NAMES.put(Material.TERRACOTTA,          "Терракота");
        RU_NAMES.put(Material.WHITE_TERRACOTTA,    "Белая терракота");
        RU_NAMES.put(Material.ORANGE_TERRACOTTA,   "Оранжевая терракота");
        RU_NAMES.put(Material.YELLOW_TERRACOTTA,   "Жёлтая терракота");
        RU_NAMES.put(Material.RED_TERRACOTTA,      "Красная терракота");
        RU_NAMES.put(Material.BROWN_TERRACOTTA,    "Коричневая терракота");
        RU_NAMES.put(Material.GRAY_TERRACOTTA,     "Серая терракота");
        RU_NAMES.put(Material.LIGHT_GRAY_TERRACOTTA, "Светло-серая терракота");
        RU_NAMES.put(Material.CYAN_TERRACOTTA,     "Бирюзовая терракота");
        RU_NAMES.put(Material.BLUE_TERRACOTTA,     "Синяя терракота");
        RU_NAMES.put(Material.GREEN_TERRACOTTA,    "Зелёная терракота");
        RU_NAMES.put(Material.BLACK_TERRACOTTA,    "Чёрная терракота");
        // Other ship blocks
        RU_NAMES.put(Material.DIRT,              "Земля");
        RU_NAMES.put(Material.COARSE_DIRT,       "Грубая земля");
        RU_NAMES.put(Material.CLAY,              "Глина");
        RU_NAMES.put(Material.BRICKS,            "Кирпич");
        RU_NAMES.put(Material.NETHER_BRICKS,     "Адский кирпич");
        RU_NAMES.put(Material.DEEPSLATE,         "Глубинный сланец");
        RU_NAMES.put(Material.COBBLED_DEEPSLATE, "Булыжный глубинный сланец");
        RU_NAMES.put(Material.MUD_BRICKS,        "Грязевой кирпич");
        RU_NAMES.put(Material.PACKED_MUD,        "Утрамбованная грязь");
        RU_NAMES.put(Material.HAY_BLOCK,         "Сено");
        RU_NAMES.put(Material.DRIED_KELP_BLOCK,  "Высушенная ламинария");
        RU_NAMES.put(Material.COAL_BLOCK,        "Блок угля");
        RU_NAMES.put(Material.SPONGE,            "Губка");
        RU_NAMES.put(Material.WET_SPONGE,        "Мокрая губка");
        RU_NAMES.put(Material.ICE,               "Лёд");
        RU_NAMES.put(Material.PACKED_ICE,        "Упакованный лёд");
        RU_NAMES.put(Material.BLUE_ICE,          "Синий лёд");
        RU_NAMES.put(Material.HONEYCOMB_BLOCK,   "Блок сот");
        RU_NAMES.put(Material.BAMBOO_BLOCK,      "Бамбуковый блок");
        RU_NAMES.put(Material.STRIPPED_BAMBOO_BLOCK, "Очищ. бамбуковый блок");
        // More planks / slabs
        RU_NAMES.put(Material.MANGROVE_PLANKS,   "Мангровые доски");
        RU_NAMES.put(Material.CHERRY_PLANKS,     "Вишнёвые доски");
        RU_NAMES.put(Material.BAMBOO_PLANKS,     "Бамбуковые доски");
        RU_NAMES.put(Material.CRIMSON_PLANKS,    "Багровые доски");
        RU_NAMES.put(Material.WARPED_PLANKS,     "Искажённые доски");
        // Nether / End blocks
        RU_NAMES.put(Material.RED_NETHER_BRICKS,     "Красный адский кирпич");
        RU_NAMES.put(Material.BASALT,                "Базальт");
        RU_NAMES.put(Material.POLISHED_BASALT,       "Полированный базальт");
        RU_NAMES.put(Material.SMOOTH_BASALT,         "Гладкий базальт");
        RU_NAMES.put(Material.BLACKSTONE,            "Чёрный камень");
        RU_NAMES.put(Material.POLISHED_BLACKSTONE,   "Полированный чёрный камень");
        RU_NAMES.put(Material.POLISHED_BLACKSTONE_BRICKS, "Кирпичи из чёрного камня");
        RU_NAMES.put(Material.SOUL_SAND,             "Адский песок");
        RU_NAMES.put(Material.SOUL_SOIL,             "Адская земля");
        RU_NAMES.put(Material.MAGMA_BLOCK,           "Блок магмы");
        RU_NAMES.put(Material.CRYING_OBSIDIAN,       "Плачущий обсидиан");
        RU_NAMES.put(Material.ANCIENT_DEBRIS,        "Древний мусор");
        RU_NAMES.put(Material.END_STONE,             "Камень края");
        RU_NAMES.put(Material.END_STONE_BRICKS,      "Кирпич из камня края");
        RU_NAMES.put(Material.PURPUR_BLOCK,          "Пурпурный блок");
        RU_NAMES.put(Material.PURPUR_PILLAR,         "Пурпурная колонна");
        // Stone variants
        RU_NAMES.put(Material.GRANITE,               "Гранит");
        RU_NAMES.put(Material.POLISHED_GRANITE,      "Полированный гранит");
        RU_NAMES.put(Material.DIORITE,               "Диорит");
        RU_NAMES.put(Material.POLISHED_DIORITE,      "Полированный диорит");
        RU_NAMES.put(Material.ANDESITE,              "Андезит");
        RU_NAMES.put(Material.POLISHED_ANDESITE,     "Полированный андезит");
        RU_NAMES.put(Material.SMOOTH_STONE,          "Гладкий камень");
        RU_NAMES.put(Material.STONE_BRICK_SLAB,      "Плита из каменного кирпича");
        RU_NAMES.put(Material.MOSSY_STONE_BRICKS,    "Замшелый каменный кирпич");
        RU_NAMES.put(Material.CRACKED_STONE_BRICKS,  "Растрескавшийся каменный кирпич");
        RU_NAMES.put(Material.CHISELED_STONE_BRICKS, "Тёсаный каменный кирпич");
        RU_NAMES.put(Material.COBBLESTONE_SLAB,      "Плита из булыжника");
        RU_NAMES.put(Material.MOSSY_COBBLESTONE,     "Замшелый булыжник");
        RU_NAMES.put(Material.SANDSTONE,             "Песчаник");
        RU_NAMES.put(Material.SMOOTH_SANDSTONE,      "Гладкий песчаник");
        RU_NAMES.put(Material.CHISELED_SANDSTONE,    "Тёсаный песчаник");
        RU_NAMES.put(Material.RED_SANDSTONE,         "Красный песчаник");
        RU_NAMES.put(Material.SMOOTH_RED_SANDSTONE,  "Гладкий красный песчаник");
        RU_NAMES.put(Material.QUARTZ_BLOCK,          "Блок кварца");
        RU_NAMES.put(Material.SMOOTH_QUARTZ,         "Гладкий кварц");
        RU_NAMES.put(Material.QUARTZ_PILLAR,         "Кварцевая колонна");
        RU_NAMES.put(Material.CHISELED_QUARTZ_BLOCK, "Тёсаный кварц");
        // Deepslate variants
        RU_NAMES.put(Material.POLISHED_DEEPSLATE,    "Полированный сланец");
        RU_NAMES.put(Material.DEEPSLATE_BRICKS,      "Кирпич из сланца");
        RU_NAMES.put(Material.DEEPSLATE_TILES,       "Плитка из сланца");
        RU_NAMES.put(Material.CHISELED_DEEPSLATE,    "Тёсаный сланец");
        // Ores / minerals
        RU_NAMES.put(Material.IRON_ORE,         "Железная руда");
        RU_NAMES.put(Material.DEEPSLATE_IRON_ORE, "Рудник железа (сланец)");
        RU_NAMES.put(Material.COAL_ORE,         "Угольная руда");
        RU_NAMES.put(Material.LAPIS_BLOCK,      "Блок лазурита");
        RU_NAMES.put(Material.REDSTONE_BLOCK,   "Блок красного камня");
        // Glass variants
        RU_NAMES.put(Material.TINTED_GLASS,          "Тонированное стекло");
        RU_NAMES.put(Material.WHITE_STAINED_GLASS,   "Белое витражное стекло");
        RU_NAMES.put(Material.ORANGE_STAINED_GLASS,  "Оранжевое витражное стекло");
        RU_NAMES.put(Material.MAGENTA_STAINED_GLASS, "Пурпурное витражное стекло");
        RU_NAMES.put(Material.LIGHT_BLUE_STAINED_GLASS, "Голубое витражное стекло");
        RU_NAMES.put(Material.YELLOW_STAINED_GLASS,  "Жёлтое витражное стекло");
        RU_NAMES.put(Material.LIME_STAINED_GLASS,    "Лаймовое витражное стекло");
        RU_NAMES.put(Material.PINK_STAINED_GLASS,    "Розовое витражное стекло");
        RU_NAMES.put(Material.GRAY_STAINED_GLASS,    "Серое витражное стекло");
        RU_NAMES.put(Material.LIGHT_GRAY_STAINED_GLASS, "Светло-серое витражное стекло");
        RU_NAMES.put(Material.CYAN_STAINED_GLASS,    "Бирюзовое витражное стекло");
        RU_NAMES.put(Material.PURPLE_STAINED_GLASS,  "Фиолетовое витражное стекло");
        RU_NAMES.put(Material.BLUE_STAINED_GLASS,    "Синее витражное стекло");
        RU_NAMES.put(Material.BROWN_STAINED_GLASS,   "Коричневое витражное стекло");
        RU_NAMES.put(Material.GREEN_STAINED_GLASS,   "Зелёное витражное стекло");
        RU_NAMES.put(Material.RED_STAINED_GLASS,     "Красное витражное стекло");
        RU_NAMES.put(Material.BLACK_STAINED_GLASS,   "Чёрное витражное стекло");
        // Concrete powder
        RU_NAMES.put(Material.WHITE_CONCRETE_POWDER,   "Белый бетонный порошок");
        RU_NAMES.put(Material.GRAY_CONCRETE_POWDER,    "Серый бетонный порошок");
        RU_NAMES.put(Material.BLACK_CONCRETE_POWDER,   "Чёрный бетонный порошок");
        // Utility / mechanical
        RU_NAMES.put(Material.OBSERVER,         "Наблюдатель");
        RU_NAMES.put(Material.DROPPER,          "Дроппер");
        RU_NAMES.put(Material.HOPPER,           "Воронка");
        RU_NAMES.put(Material.CAULDRON,         "Котёл");
        RU_NAMES.put(Material.BREWING_STAND,    "Варочная стойка");
        RU_NAMES.put(Material.ENCHANTING_TABLE, "Стол зачарования");
        RU_NAMES.put(Material.ANVIL,            "Наковальня");
        RU_NAMES.put(Material.BEACON,           "Маяк");
        RU_NAMES.put(Material.CONDUIT,          "Кондуит");
        RU_NAMES.put(Material.JUKEBOX,          "Проигрыватель");
        RU_NAMES.put(Material.NOTE_BLOCK,       "Нотный блок");
        RU_NAMES.put(Material.LECTERN,          "Аналой");
        RU_NAMES.put(Material.LOOM,             "Ткацкий станок");
        RU_NAMES.put(Material.CARTOGRAPHY_TABLE,"Картографический стол");
        RU_NAMES.put(Material.SMITHING_TABLE,   "Кузнечный стол");
        RU_NAMES.put(Material.GRINDSTONE,       "Точильный камень");
        RU_NAMES.put(Material.STONECUTTER,      "Камнерез");
        RU_NAMES.put(Material.COMPOSTER,        "Компостер");
        RU_NAMES.put(Material.BELL,             "Колокол");
        RU_NAMES.put(Material.LIGHTNING_ROD,    "Громоотвод");
        // Leaves / vegetation (sometimes used in ships)
        RU_NAMES.put(Material.OAK_LEAVES,      "Дубовые листья");
        RU_NAMES.put(Material.SPRUCE_LEAVES,   "Еловые листья");
        RU_NAMES.put(Material.BIRCH_LEAVES,    "Берёзовые листья");
        RU_NAMES.put(Material.JUNGLE_LEAVES,   "Тропические листья");
        RU_NAMES.put(Material.ACACIA_LEAVES,   "Акациевые листья");
        RU_NAMES.put(Material.DARK_OAK_LEAVES, "Тёмно-дубовые листья");
        RU_NAMES.put(Material.MANGROVE_LEAVES, "Мангровые листья");
        RU_NAMES.put(Material.CHERRY_LEAVES,   "Вишнёвые листья");
        // Fence / stairs (structural elements)
        RU_NAMES.put(Material.OAK_FENCE,       "Дубовый забор");
        RU_NAMES.put(Material.SPRUCE_FENCE,    "Еловый забор");
        RU_NAMES.put(Material.DARK_OAK_FENCE,  "Тёмно-дубовый забор");
        RU_NAMES.put(Material.OAK_STAIRS,      "Дубовые ступени");
        RU_NAMES.put(Material.SPRUCE_STAIRS,   "Еловые ступени");
        RU_NAMES.put(Material.DARK_OAK_STAIRS, "Тёмно-дубовые ступени");
        RU_NAMES.put(Material.COBBLESTONE_STAIRS, "Ступени из булыжника");
        RU_NAMES.put(Material.STONE_BRICK_STAIRS,  "Ступени из каменного кирпича");
        // Slabs
        RU_NAMES.put(Material.OAK_SLAB,        "Дубовая плита");
        RU_NAMES.put(Material.SPRUCE_SLAB,     "Еловая плита");
        RU_NAMES.put(Material.DARK_OAK_SLAB,   "Тёмно-дубовая плита");
        RU_NAMES.put(Material.SMOOTH_STONE_SLAB, "Плита из гладкого камня");
        // Copper variants
        RU_NAMES.put(Material.EXPOSED_COPPER,   "Окисленная медь (слабо)");
        RU_NAMES.put(Material.WEATHERED_COPPER, "Окисленная медь (сильно)");
        RU_NAMES.put(Material.OXIDIZED_COPPER,  "Полностью окисленная медь");
        RU_NAMES.put(Material.WAXED_COPPER_BLOCK,          "Медный блок (покрытый воском)");
        RU_NAMES.put(Material.WAXED_EXPOSED_COPPER,        "Слабо окисленная медь (вощёная)");
        RU_NAMES.put(Material.WAXED_WEATHERED_COPPER,      "Сильно окисленная медь (вощёная)");
        RU_NAMES.put(Material.WAXED_OXIDIZED_COPPER,       "Полностью окисленная медь (вощёная)");
        RU_NAMES.put(Material.CUT_COPPER,       "Резная медь");
        // Dyes
        RU_NAMES.put(Material.WHITE_DYE,        "Белый краситель");
        RU_NAMES.put(Material.RED_DYE,          "Красный краситель");
        RU_NAMES.put(Material.BLUE_DYE,         "Синий краситель");
        RU_NAMES.put(Material.BLACK_DYE,        "Чёрный краситель");
    }
}
