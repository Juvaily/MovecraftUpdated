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

    private final Map<UUID, TextDisplay>              displays       = new ConcurrentHashMap<>();
    private final Map<UUID, Craft>                    activeCrafts   = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>                  origBlockCount = new ConcurrentHashMap<>();
    private final Map<UUID, List<RequiredBlockEntry>> moveEntries    = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    origEntryCount = new ConcurrentHashMap<>();
    private final Map<UUID, List<RequiredBlockEntry>> flyEntries     = new ConcurrentHashMap<>();
    private final Map<UUID, int[]>                    origFlyCount   = new ConcurrentHashMap<>();

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

        int[] sc = scan(craft, entries, fEntries);
        origBlockCount.put(uid, sc[0]);
        if (!entries.isEmpty()) {
            int[] orig = new int[entries.size()];
            System.arraycopy(sc, 2, orig, 0, entries.size());
            origEntryCount.put(uid, orig);
        }
        if (!fEntries.isEmpty()) {
            int[] orig = new int[fEntries.size()];
            System.arraycopy(sc, 2 + entries.size(), orig, 0, fEntries.size());
            origFlyCount.put(uid, orig);
        }

        Location pos   = above(craft.getHitBox(), craft.getWorld());
        int      origB = sc[0];
        int[]    origE = origEntryCount.getOrDefault(uid, new int[0]);
        int[]    origF = origFlyCount.getOrDefault(uid, new int[0]);

        TextDisplay disp = pos.getWorld().spawn(pos, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(200, 0, 0, 0));
            e.setShadowed(true);
            e.setPersistent(false);
            e.setViewRange(1.5f);
            e.setTransformation(SCALE);
            e.text(buildText(craft, sc, origB, entries, origE, fEntries, origF));
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

        List<RequiredBlockEntry> entries  = moveEntries.getOrDefault(uid, List.of());
        List<RequiredBlockEntry> fEntries = flyEntries.getOrDefault(uid, List.of());
        int[] sc    = scan(craft, entries, fEntries);
        int   orig  = origBlockCount.getOrDefault(uid, sc[0]);
        int[] origE = origEntryCount.getOrDefault(uid, new int[0]);
        int[] origF = origFlyCount.getOrDefault(uid, new int[0]);
        disp.text(buildText(craft, sc, orig, entries, origE, fEntries, origF));
    }

    private void remove(UUID uid) {
        activeCrafts.remove(uid);
        origBlockCount.remove(uid);
        moveEntries.remove(uid);
        origEntryCount.remove(uid);
        flyEntries.remove(uid);
        origFlyCount.remove(uid);
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
    private int[] scan(Craft craft, List<RequiredBlockEntry> entries, List<RequiredBlockEntry> fEntries) {
        int[] result = new int[2 + entries.size() + fEntries.size()];
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
                for (int i = 0; i < fEntries.size(); i++) {
                    if (fEntries.get(i).contains(m)) result[2 + entries.size() + i]++;
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

    private Component buildText(Craft craft, int[] sc, int orig,
                                List<RequiredBlockEntry> entries, int[] origE,
                                List<RequiredBlockEntry> fEntries, int[] origF) {
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
                .append(Component.text(craftTitle(craft))
                        .color(NamedTextColor.GOLD))
                .appendNewline()
                .append(Component.text("█".repeat(filled)).color(hColor))
                .append(Component.text("░".repeat(10 - filled)).color(NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format(" %.0f%%", pct)).color(hColor))
                .append(Component.text(" (" + curr + "/" + orig + ")").color(NamedTextColor.GRAY));

        // Move-block lines (⚙)
        for (int i = 0; i < entries.size(); i++) {
            RequiredBlockEntry entry = entries.get(i);
            int currE    = sc[2 + i];
            int origEntry = (origE.length > i) ? origE[i] : currE;
            if (origEntry <= 0) origEntry = currE;

            boolean met = entry.check(currE, curr);
            NamedTextColor mc = met ? NamedTextColor.GREEN : NamedTextColor.RED;
            double ePct = origEntry > 0 ? Math.max(0.0, (double) currE / origEntry * 100.0) : 100.0;

            text.appendNewline()
                .append(Component.text("⚙ " + entryLabel(entry) + ": ").color(NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", ePct)).color(mc))
                .append(Component.text(" (" + currE + "/" + origEntry + ")").color(NamedTextColor.GRAY));
        }

        // Fly-block lines (🪂) — skip entries with no blocks at detect time
        int base = 2 + entries.size();
        for (int i = 0; i < fEntries.size(); i++) {
            int origEntry = (origF.length > i) ? origF[i] : 0;
            if (origEntry <= 0) continue; // craft doesn't use this fly block

            RequiredBlockEntry entry = fEntries.get(i);
            int currE = sc[base + i];

            boolean met = entry.check(currE, curr);
            NamedTextColor mc = met ? NamedTextColor.GREEN : NamedTextColor.RED;
            double ePct = Math.max(0.0, (double) currE / origEntry * 100.0);

            text.appendNewline()
                .append(Component.text("🧱 " + entryLabel(entry) + ": ").color(NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", ePct)).color(mc))
                .append(Component.text(" (" + currE + "/" + origEntry + ")").color(NamedTextColor.GRAY));
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

    /** Label for a moveblock/flyblock entry: RU_NAMES (materials) → custom name → fallback. */
    private String entryLabel(RequiredBlockEntry entry) {
        // Always try material lookup first — gives Russian name if available
        try {
            var mats = new ArrayList<>(entry.getMaterials());
            if (!mats.isEmpty()) {
                Material first = (Material) mats.get(0);
                String ru = RU_NAMES.get(first);
                if (ru != null)
                    return mats.size() > 1 ? ru + " +" + (mats.size() - 1) : ru;
                // Not in map — fall through to entry name or raw material name
                String custom = entry.getName();
                if (custom != null && !custom.isBlank()) return custom;
                String raw = first.name().replace('_', ' ').toLowerCase();
                return mats.size() > 1 ? raw + " +" + (mats.size() - 1) : raw;
            }
        } catch (Exception ignored) {}

        String n = entry.getName();
        return (n != null && !n.isBlank()) ? n : "Блок";
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
    }
}
