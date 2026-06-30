package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class CraftAdminMenu implements Listener {

    static final String PERMISSION = "movecraftcannons.admin.craft";

    private enum Tab { ALLOWED, FORBIDDEN, PASSTHROUGH, SETTINGS }

    // ── Private CraftType constant retrieval ───────────────────────────────────
    //
    //  CraftType stores properties in Map<NamespacedKey, V> maps.
    //  Public constants: ALLOWED_BLOCKS, FORBIDDEN_BLOCKS, PASSTHROUGH_BLOCKS, MIN_SIZE, MAX_SIZE, NAME.
    //  All speed/cooldown/height constants are private → accessed via reflection.

    private static NamespacedKey craftKey(String fieldName) {
        try {
            Field f = CraftType.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return (NamespacedKey) f.get(null);
        } catch (Exception e) {
            return new NamespacedKey("movecraft", fieldName.toLowerCase());
        }
    }

    // ── Property descriptors ───────────────────────────────────────────────────

    private enum IntProp {
        MIN_SIZE   ("Мин. размер",         "min_size",               Material.CHEST,             CraftType.MIN_SIZE),
        MAX_SIZE   ("Макс. размер",        "max_size",               Material.ENDER_CHEST,       CraftType.MAX_SIZE),
        TICK_COOL  ("Тик ручн.",           "tick_cooldown",          Material.CLOCK,             craftKey("TICK_COOLDOWN")),
        CRUISE_COOL("Тик круиза",          "cruise_tick_cooldown",   Material.POWERED_RAIL,      craftKey("CRUISE_TICK_COOLDOWN")),
        CRUISE_SKIP("Прыжок круиза",       "cruise_skip_blocks",     Material.RAIL,              craftKey("CRUISE_SKIP_BLOCKS")),
        VERT_COOL  ("Тик верт.",           "vert_cruise_tick_cooldown", Material.LADDER,         craftKey("VERT_CRUISE_TICK_COOLDOWN")),
        VERT_SKIP  ("Прыжок верт.",        "vert_cruise_skip_blocks",   Material.VINE,           craftKey("VERT_CRUISE_SKIP_BLOCKS")),
        MIN_HEIGHT ("Мин. высота",         "min_height_limit",       Material.POINTED_DRIPSTONE, craftKey("MIN_HEIGHT_LIMIT")),
        MAX_HEIGHT ("Макс. высота",        "max_height_limit",       Material.SCAFFOLDING,       craftKey("MAX_HEIGHT_LIMIT"));

        final String label; final String yamlKey; final Material icon; final NamespacedKey key;
        IntProp(String l, String y, Material i, NamespacedKey k) { label=l; yamlKey=y; icon=i; key=k; }
    }

    // Speeds may be Float or Double depending on Movecraft build; safeDouble() tries both.
    private enum DoubleProp {
        CRUISE_SPEED("Скорость круиза", "cruise_speed",      Material.CYAN_DYE, craftKey("CRUISE_SPEED")),
        VERT_SPEED  ("Верт. скорость",  "vert_cruise_speed", Material.FEATHER,  craftKey("VERT_CRUISE_SPEED")),
        SPEED       ("Скорость DC",     "speed",             Material.SUGAR,    craftKey("SPEED"));

        final String label; final String yamlKey; final Material icon; final NamespacedKey key;
        DoubleProp(String l, String y, Material i, NamespacedKey k) { label=l; yamlKey=y; icon=i; key=k; }
    }

    // Settings layout: 3 params per row ([-][val][+] per param), 4 rows = 12 params
    // Groups of 3 slots: [slot-1, slot, slot+1] per param
    private static final int[] PARAM_VAL_SLOTS = {
        10, 13, 16,   // row 1: slots 9-17 → val at 10,13,16
        19, 22, 25,   // row 2
        28, 31, 34,   // row 3
        37, 40, 43    // row 4
    };
    private static final IntProp[]    INT_PROPS    = IntProp.values();
    private static final DoubleProp[] DOUBLE_PROPS = DoubleProp.values();
    // Total: 9 int + 3 double = 12 params, fits PARAM_VAL_SLOTS exactly

    // Block grid: rows 1-4 (slots 9-44), 36 slots
    private static final int GRID_START = 9;
    private static final int GRID_SIZE  = 36;

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Tab>                currentTab    = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            blockOffset   = new ConcurrentHashMap<>();
    // CraftType mode (may be null in file-only mode)
    private final Map<UUID, CraftType>          targetType    = new ConcurrentHashMap<>();
    // File-based mode: always set; in CraftType mode it's the .craft file for that type
    private final Map<UUID, File>               targetFile    = new ConcurrentHashMap<>();
    // Working block sets — unified source of truth for the menu.
    // In CraftType mode: mirrors CraftType maps (updated together).
    // In file-only mode: loaded from YAML, saved back to YAML.
    private final Map<UUID, Map<Tab, Set<Material>>> workingBlocks = new ConcurrentHashMap<>();
    // Group tags (#planks, #wool, etc.) from the original YAML — preserved across saves.
    private final Map<UUID, Map<Tab, List<String>>>  groupTagsCache = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<Player>[]> menuActions   = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<Player>[]> shiftActions  = new ConcurrentHashMap<>();
    // Anvil input state: player → callback for the typed string
    private final Map<UUID, Consumer<String>>   pendingAnvil  = new ConcurrentHashMap<>();

    public CraftAdminMenu(MovecraftCannonsPlugin plugin) { this.plugin = plugin; }

    // ── Command entry point ────────────────────────────────────────────────────

    public boolean onCommand(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Lang.msg("msg.no_permission", player, NamedTextColor.RED));
            return true;
        }

        CraftType type   = null;
        File      file   = null;
        String    search = args.length >= 1 ? String.join(" ", args) : null;

        if (search != null) {
            if (search.equalsIgnoreCase("list")) {
                sendTypeList(player);
                return true;
            }
            type = findTypeByName(search);
            if (type == null) {
                // Try to find the .craft file even if Movecraft didn't load the type
                file = findFileBySearch(search);
                if (file == null) {
                    player.sendMessage(Lang.msg("msg.admin.type_not_found", player, NamedTextColor.RED, search));
                    sendTypeList(player);
                    return true;
                }
                // File-only mode: type is null, file is the .craft on disk
                player.sendMessage(Lang.msg("msg.admin.file_only_mode", player, NamedTextColor.YELLOW, file.getName()));
            } else {
                file = findCraftTypeFile(type);
            }
        } else {
            // No args: piloted craft first, then any nearby active craft
            PlayerCraft pc = CraftManager.getInstance().getCraftByPlayer(player);
            if (pc != null) {
                type = pc.getType();
            } else {
                for (var c : CraftManager.getInstance().getCraftsInWorld(player.getWorld())) {
                    try {
                        if (c.getHitBox().boundingHitBox().contains(
                                new net.countercraft.movecraft.MovecraftLocation(
                                        player.getLocation().getBlockX(),
                                        player.getLocation().getBlockY(),
                                        player.getLocation().getBlockZ()))) {
                            type = c.getType(); break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (type != null) file = findCraftTypeFile(type);
        }

        if (type == null && file == null) {
            player.sendMessage(Lang.msg("msg.admin.no_craft_nearby", player, NamedTextColor.RED));
            sendTypeList(player);
            return true;
        }

        UUID uid = player.getUniqueId();
        if (type != null) targetType.put(uid, type);
        else              targetType.remove(uid);
        if (file != null) targetFile.put(uid, file);
        else              targetFile.remove(uid);

        // Load working blocks from source (CraftType takes precedence, then file)
        loadWorkingBlocks(uid, type, file);

        currentTab.putIfAbsent(uid, Tab.ALLOWED);
        blockOffset.put(uid, 0);
        openMenu(player);
        return true;
    }

    // ── Type name lookup ───────────────────────────────────────────────────────

    // ── Type name lookup ───────────────────────────────────────────────────────
    //
    //  Each CraftType has:
    //    • stringPropertyMap.get(NAME_KEY) → the "name:" YAML value (may be absent)
    //    • namespacedKey field              → e.g. movecraft:trader (always present,
    //                                         derived from the .craft filename)
    //
    //  getCraftTypeFromString(s) uses the name: value internally and fails when
    //  the YAML has no name: field.  We use namespacedKey.getKey() as a reliable
    //  fallback: for "Trader.craft" the key is always "trader".

    private CraftType findTypeByName(String search) {
        String normSearch = normName(search);

        // Pass 1: iterate all registered types — exact match on name OR namespacedKey
        CraftType partial = null;
        for (CraftType ct : CraftManager.getInstance().getCraftTypes()) {
            // Try YAML name: field
            String name = safeTypeName(ct);
            if (name != null) {
                if (normName(name).equals(normSearch)) return ct;
                if (partial == null && normName(name).contains(normSearch)) partial = ct;
            }
            // Try namespacedKey.getKey() (always present, e.g. "trader" for Trader.craft)
            String nkKey = safeNkKey(ct);
            if (nkKey != null) {
                if (normName(nkKey).equals(normSearch)) return ct;
                if (partial == null && normName(nkKey).contains(normSearch)) partial = ct;
            }
        }
        if (partial != null) return partial;

        // Pass 2: Movecraft's own lookup (uses name: field)
        try {
            CraftType ct = CraftManager.getInstance().getCraftTypeFromString(search);
            if (ct != null) return ct;
        } catch (Exception ignored) {}

        return null;
    }

    /** YAML name: field value, or null if absent / throws. */
    private String safeTypeName(CraftType ct) {
        try { return ct.getStringProperty(CraftType.NAME); }
        catch (Exception e) { return null; }
    }

    /** namespacedKey.getKey() — e.g. "trader" for a type loaded from Trader.craft. */
    private String safeNkKey(CraftType ct) {
        try {
            Field f = rf(CraftType.class, "namespacedKey");
            Object nk = f.get(ct);
            if (nk instanceof NamespacedKey key) return key.getKey();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Human-readable identifier for a CraftType, preferring the YAML name: value,
     * then the namespacedKey key (filename-derived), then null.
     */
    private String displayName(CraftType ct) {
        String n = safeTypeName(ct);
        if (n != null) return n;
        return safeNkKey(ct);
    }

    /**
     * Send the player a list of all types (registered AND unregistered files on disk).
     * Unregistered types (failed to load) are shown with a ⚠ prefix.
     */
    private void sendTypeList(Player player) {
        List<String> entries = new ArrayList<>();

        // Registered types (CraftManager)
        for (CraftType ct : CraftManager.getInstance().getCraftTypes()) {
            String name  = safeTypeName(ct);
            String nkKey = safeNkKey(ct);
            String show;
            if (name != null && nkKey != null && !normName(name).equals(normName(nkKey)))
                show = name + " (" + nkKey + ")";
            else
                show = name != null ? name : nkKey;
            if (show != null) entries.add(show);
        }

        // .craft files on disk that weren't in the registered list (failed to load)
        Plugin mc = Bukkit.getPluginManager().getPlugin("Movecraft");
        if (mc != null) {
            for (String dir : new String[]{"types", "craft", "craftTypes"}) {
                File d = new File(mc.getDataFolder(), dir);
                if (!d.exists()) continue;
                File[] files = d.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String fn = f.getName().replaceFirst("\\.[^.]+$", "");
                    // Add if not already covered by registered types
                    boolean covered = entries.stream()
                            .anyMatch(e -> normName(e).contains(normName(fn)));
                    if (!covered) entries.add("⚠ " + fn + " (не загружен)");
                }
            }
        }

        if (entries.isEmpty()) {
            player.sendMessage(Component.text("Нет типов транспорта.")
                    .color(NamedTextColor.GRAY));
            return;
        }
        entries.sort(String.CASE_INSENSITIVE_ORDER);
        player.sendMessage(Component.text("Типы (/craftadmin <имя>): ")
                .color(NamedTextColor.GOLD)
                .append(Component.text(String.join(", ", entries)).color(NamedTextColor.YELLOW)));
    }

    /** Find a .craft file on disk by normalized name search. */
    private File findFileBySearch(String search) {
        Plugin mc = Bukkit.getPluginManager().getPlugin("Movecraft");
        if (mc == null) return null;
        String norm = normName(search);
        File partialMatch = null;
        for (String dir : new String[]{"types", "craft", "craftTypes"}) {
            File d = new File(mc.getDataFolder(), dir);
            if (!d.exists()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String fn = normName(f.getName().replaceFirst("\\.[^.]+$", ""));
                if (fn.equals(norm)) return f;
                if (partialMatch == null && fn.contains(norm)) partialMatch = f;
            }
        }
        return partialMatch;
    }

    /** Settings tab placeholder in file-only mode. */
    @SuppressWarnings("unchecked")
    private void buildFileOnlySettingsNote(Inventory inv, Player player) {
        ItemStack bg = bgItem();
        for (int i = 9; i < 54; i++) inv.setItem(i, bg);
        ItemStack note = new ItemStack(Material.BOOK);
        ItemMeta m = note.getItemMeta();
        m.displayName(Component.text("Параметры недоступны в файловом режиме")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Тип не загружен Movecraft.")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Исправь блоки → Сохранить → перезапусти сервер.")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        note.setItemMeta(m);
        inv.setItem(31, note);
    }

    // ── Build and open inventory ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void openMenu(Player player) {
        UUID uid = player.getUniqueId();

        Tab tab = currentTab.getOrDefault(uid, Tab.ALLOWED);
        AdminMenuHolder holder = new AdminMenuHolder();
        CraftType type = targetType.get(uid);
        File      file = targetFile.get(uid);
        if (type == null && file == null) return;
        String title = type != null ? displayName(type) : null;
        if (title == null && file != null) title = file.getName().replaceFirst("\\.[^.]+$", "");
        if (title == null) title = "?";
        boolean fileOnly = (type == null);

        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text((fileOnly ? "📄 " : "⚙ ") + title).color(
                        fileOnly ? NamedTextColor.GOLD : NamedTextColor.DARK_PURPLE));
        holder.setInventory(inv);

        Consumer<Player>[] actions = new Consumer[54];
        Consumer<Player>[] shifts  = new Consumer[54];
        buildTabRow(inv, actions, shifts, player, tab);

        if (tab == Tab.SETTINGS && !fileOnly) buildSettingsContent(inv, actions, shifts, player, type);
        else if (tab == Tab.SETTINGS)         buildFileOnlySettingsNote(inv, player);
        else                                  buildBlockContent(inv, actions, shifts, player, uid, tab);

        menuActions.put(uid, actions);
        shiftActions.put(uid, shifts);
        player.openInventory(inv);
    }

    // ── Row 0: tabs + save ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void buildTabRow(Inventory inv, Consumer<Player>[] a, Consumer<Player>[] s, Player player, Tab active) {
        setSlot(inv, a, s, 0, tabItem("✅ Разрешённые", Material.LIME_STAINED_GLASS_PANE, active == Tab.ALLOWED),     p -> switchTab(p, Tab.ALLOWED),     null);
        setSlot(inv, a, s, 1, tabItem("🚫 Запрещённые", Material.RED_STAINED_GLASS_PANE,  active == Tab.FORBIDDEN),   p -> switchTab(p, Tab.FORBIDDEN),   null);
        setSlot(inv, a, s, 2, tabItem("🔵 Сквозные",    Material.CYAN_STAINED_GLASS_PANE,  active == Tab.PASSTHROUGH), p -> switchTab(p, Tab.PASSTHROUGH), null);
        setSlot(inv, a, s, 3, tabItem("⚙ Параметры",   Material.COMPARATOR,               active == Tab.SETTINGS),    p -> switchTab(p, Tab.SETTINGS),    null);
        ItemStack bg = bgItem();
        for (int i = 4; i <= 6; i++) inv.setItem(i, bg);
        setSlot(inv, a, s, 7, reloadItem(), p -> { reloadFromFile(p); openMenu(p); }, null);
        setSlot(inv, a, s, 8, saveItem(),   p -> { saveChanges(p);   openMenu(p); }, null);
    }

    private void switchTab(Player player, Tab tab) {
        currentTab.put(player.getUniqueId(), tab);
        blockOffset.put(player.getUniqueId(), 0);
        openMenu(player);
    }

    // ── Block tab content ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void buildBlockContent(Inventory inv, Consumer<Player>[] a, Consumer<Player>[] s,
                                   Player player, UUID uid, Tab tab) {
        int offset = blockOffset.getOrDefault(uid, 0);

        // Unified list: group tags first (#planks, #wool...), then sorted individual blocks
        List<Object> allItems = new ArrayList<>(getGroupTags(uid, tab));
        List<Material> mats = new ArrayList<>(getWorkingBlocks(uid, tab));
        mats.sort(Comparator.comparing(Enum::name));
        allItems.addAll(mats);

        ItemStack bg = bgItem();
        for (int i = GRID_START; i < GRID_START + GRID_SIZE; i++) inv.setItem(i, bg);

        for (int i = 0; i < GRID_SIZE && (offset + i) < allItems.size(); i++) {
            Object entry = allItems.get(offset + i);
            int slot = GRID_START + i;
            if (entry instanceof String tag) {
                setSlot(inv, a, s, slot, groupTagItem(tag), p -> {
                    removeGroupTag(p.getUniqueId(), tab, tag);
                    int off = blockOffset.getOrDefault(p.getUniqueId(), 0);
                    int rem = getGroupTags(p.getUniqueId(), tab).size() + getWorkingBlocks(p.getUniqueId(), tab).size();
                    blockOffset.put(p.getUniqueId(), Math.min(off, Math.max(0, (rem / GRID_SIZE) * GRID_SIZE)));
                    openMenu(p);
                }, null);
            } else if (entry instanceof Material mat) {
                setSlot(inv, a, s, slot, blockItem(mat), p -> {
                    UUID puid = p.getUniqueId();
                    removeBlock(puid, tab, mat);
                    int off = blockOffset.getOrDefault(puid, 0);
                    int rem = getGroupTags(puid, tab).size() + getWorkingBlocks(puid, tab).size();
                    blockOffset.put(puid, Math.min(off, Math.max(0, (rem / GRID_SIZE) * GRID_SIZE)));
                    openMenu(p);
                }, null);
            }
        }

        // Row 5 (slots 45-53)
        for (int i = 45; i <= 53; i++) inv.setItem(i, bg);

        if (offset > 0)
            setSlot(inv, a, s, 45, navItem("◀ Назад"),
                    p -> { blockOffset.put(p.getUniqueId(), offset - GRID_SIZE); openMenu(p); }, null);

        // Add group tag button (slot 46)
        setSlot(inv, a, s, 46, addGroupTagButton(), p ->
                openAnvilInput(p, "#тег_группы", "#", str -> {
                    String tag = str.trim();
                    if (!tag.isEmpty()) addGroupTag(p.getUniqueId(), tab, tag);
                }), null);

        // Page info (slot 49)
        int pages = Math.max(1, (int) Math.ceil((double) allItems.size() / GRID_SIZE));
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.displayName(Component.text("Записей: " + allItems.size())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        im.lore(List.of(
                Component.text("Стр. " + (offset / GRID_SIZE + 1) + "/" + pages)
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Золото = группа (#tag), блок = материал")
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Перетащи блок в сетку или ЛКМ из инвентаря")
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        info.setItemMeta(im);
        inv.setItem(49, info);

        if (offset + GRID_SIZE < allItems.size())
            setSlot(inv, a, s, 53, navItem("Далее ▶"),
                    p -> { blockOffset.put(p.getUniqueId(), offset + GRID_SIZE); openMenu(p); }, null);
    }

    private ItemStack groupTagItem(String tag) {
        ItemStack is = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(tag).color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Группа блоков Movecraft").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Нажмите — удалить").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
        ));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack addGroupTagButton() {
        ItemStack is = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text("+ Добавить группу (#tag)").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Например: #planks, #wool, #logs").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private void addGroupTag(UUID uid, Tab tab, String tag) {
        if (!tag.startsWith("#")) tag = "#" + tag;
        groupTagsCache.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                .computeIfAbsent(tab, k -> new ArrayList<>())
                .add(tag);
    }

    private void removeGroupTag(UUID uid, Tab tab, String tag) {
        Map<Tab, List<String>> tagsMap = groupTagsCache.get(uid);
        if (tagsMap != null) {
            List<String> list = tagsMap.get(tab);
            if (list != null) list.remove(tag);
        }
    }

    // ── Settings tab content ───────────────────────────────────────────────────
    //
    //  Layout: 3 params per row, each param occupies 3 slots: [decr] [val] [incr]
    //  Regular click: small step (±1 or ±0.1)
    //  Shift-click:   large step (±10 or ±1.0)
    //  Click on [val]: opens anvil for exact input

    @SuppressWarnings("unchecked")
    private void buildSettingsContent(Inventory inv, Consumer<Player>[] a, Consumer<Player>[] s,
                                      Player player, CraftType type) {
        ItemStack bg = bgItem();
        for (int i = 9; i < 54; i++) inv.setItem(i, bg);

        // 9 int props
        for (int i = 0; i < INT_PROPS.length && i < PARAM_VAL_SLOTS.length; i++) {
            IntProp p = INT_PROPS[i];
            int val = safeInt(type, p.key);
            int valSlot = PARAM_VAL_SLOTS[i];
            int decrSlot = valSlot - 1;
            int incrSlot = valSlot + 1;

            setSlot(inv, a, s, decrSlot, stepItem("◀ −1", Material.RED_DYE),
                    pl -> { modifyInt(type, p.key, safeInt(type, p.key) - 1); openMenu(pl); },
                    pl -> { modifyInt(type, p.key, safeInt(type, p.key) - 10); openMenu(pl); });
            setSlot(inv, a, s, valSlot, intItem(p.label, val, p.icon),
                    pl -> openAnvilInput(pl, p.label, String.valueOf(Math.max(0, safeInt(type, p.key))),
                            str -> { try { modifyInt(type, p.key, Integer.parseInt(str)); } catch (NumberFormatException ignored) {} }), null);
            setSlot(inv, a, s, incrSlot, stepItem("+1 ▶", Material.LIME_DYE),
                    pl -> { modifyInt(type, p.key, safeInt(type, p.key) + 1); openMenu(pl); },
                    pl -> { modifyInt(type, p.key, safeInt(type, p.key) + 10); openMenu(pl); });
        }

        // 3 double props (after the 9 int props)
        for (int i = 0; i < DOUBLE_PROPS.length; i++) {
            DoubleProp p = DOUBLE_PROPS[i];
            double val = safeDouble(type, p.key);
            int valSlot = PARAM_VAL_SLOTS[INT_PROPS.length + i];
            int decrSlot = valSlot - 1;
            int incrSlot = valSlot + 1;

            setSlot(inv, a, s, decrSlot, stepItem("◀ −0.1", Material.RED_DYE),
                    pl -> { modifyDoubleOrFloat(type, p.key, round(safeDouble(type, p.key) - 0.1)); openMenu(pl); },
                    pl -> { modifyDoubleOrFloat(type, p.key, round(safeDouble(type, p.key) - 1.0)); openMenu(pl); });
            setSlot(inv, a, s, valSlot, doubleItem(p.label, val, p.icon),
                    pl -> openAnvilInput(pl, p.label,
                            val < 0 ? "0" : String.format("%.1f", val).replace(',', '.'),
                            str -> { try { modifyDoubleOrFloat(type, p.key, Double.parseDouble(str.replace(',', '.'))); } catch (NumberFormatException ignored) {} }), null);
            setSlot(inv, a, s, incrSlot, stepItem("+0.1 ▶", Material.LIME_DYE),
                    pl -> { modifyDoubleOrFloat(type, p.key, round(safeDouble(type, p.key) + 0.1)); openMenu(pl); },
                    pl -> { modifyDoubleOrFloat(type, p.key, round(safeDouble(type, p.key) + 1.0)); openMenu(pl); });
        }
    }

    private double round(double v) { return Math.round(v * 10.0) / 10.0; }

    // ── Anvil input ────────────────────────────────────────────────────────────
    //
    //  Opens a virtual anvil; the player types a value in the rename field and
    //  takes the result item (slot 2) to confirm. Closing without confirming
    //  reopens the admin menu.

    private void openAnvilInput(Player player, String label, String currentVal, Consumer<String> callback) {
        pendingAnvil.put(player.getUniqueId(), callback);
        // Close current menu so openAnvil works cleanly
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            InventoryView view;
            try {
                view = player.openAnvil(player.getLocation(), true);
            } catch (Exception e) {
                pendingAnvil.remove(player.getUniqueId());
                player.sendMessage(Lang.msg("msg.admin.anvil_failed", player, NamedTextColor.RED));
                openMenu(player);
                return;
            }
            if (view == null) {
                pendingAnvil.remove(player.getUniqueId());
                player.sendMessage(Lang.msg("msg.admin.anvil_failed", player, NamedTextColor.RED));
                openMenu(player);
                return;
            }
            // Place item in left slot with current value as name (player types new value)
            ItemStack base = new ItemStack(Material.PAPER);
            ItemMeta m = base.getItemMeta();
            m.displayName(Component.text(currentVal)
                    .decoration(TextDecoration.ITALIC, false).color(NamedTextColor.WHITE));
            m.lore(List.of(Component.text("← " + label)
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            base.setItemMeta(m);
            view.getTopInventory().setItem(0, base);
            player.sendActionBar(Component.text("Введите значение для: " + label)
                    .color(NamedTextColor.YELLOW));
        });
    }

    // Anvil: set cost to 0 so admin doesn't spend XP
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!pendingAnvil.containsKey(player.getUniqueId())) return;
        if (event.getInventory() instanceof AnvilInventory anvil) anvil.setRepairCost(0);
        // Keep result item if one was generated
    }

    // Anvil: player clicks output slot (slot 2) → confirm
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uid = player.getUniqueId();
        Consumer<String> callback = pendingAnvil.get(uid);
        if (callback == null) return;
        if (event.getRawSlot() != 2) { event.setCancelled(true); return; }

        ItemStack result = event.getCurrentItem();
        if (result == null || !result.hasItemMeta()) { event.setCancelled(true); return; }

        Component nameComp = result.getItemMeta().displayName();
        String text = nameComp != null
                ? PlainTextComponentSerializer.plainText().serialize(nameComp)
                : "";

        event.setCancelled(true);
        pendingAnvil.remove(uid);
        player.closeInventory();
        // Run callback then reopen admin menu on next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            callback.accept(text);
            openMenu(player);
        });
    }

    // Anvil: player closed without confirming → reopen admin menu
    @EventHandler
    public void onAnvilClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (pendingAnvil.remove(player.getUniqueId()) != null) {
            Bukkit.getScheduler().runTask(plugin, () -> openMenu(player));
        }
    }

    // ── Inventory events ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uid = player.getUniqueId();
        Tab tab = currentTab.getOrDefault(uid, Tab.ALLOWED);
        CraftType type = targetType.get(uid);
        int slot = event.getRawSlot();

        // ── Click in PLAYER's own inventory (slots 54+): add block ─────────
        if (slot >= 54 && tab != Tab.SETTINGS) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir() && clicked.getType().isBlock()) {
                Material mat = clicked.getType();
                if (addBlock(uid, tab, mat)) {
                    player.sendMessage(Lang.msg("msg.admin.block_added", player, NamedTextColor.GREEN,
                            mat.name().toLowerCase()));
                } else {
                    player.sendMessage(Lang.msg("msg.admin.block_exists", player, NamedTextColor.YELLOW,
                            mat.name().toLowerCase()));
                }
                Bukkit.getScheduler().runTask(plugin, () -> openMenu(player));
            }
            return;
        }

        // ── Drop block onto grid (cursor has block → slot 9-44): add it ───
        if (slot >= GRID_START && slot < GRID_START + GRID_SIZE && tab != Tab.SETTINGS) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && cursor.getType().isBlock()
                    && event.getAction() != InventoryAction.PICKUP_ALL
                    && event.getAction() != InventoryAction.PICKUP_SOME) {
                Material mat = cursor.getType();
                if (addBlock(uid, tab, mat)) {
                    player.sendMessage(Lang.msg("msg.admin.block_added", player, NamedTextColor.GREEN,
                            mat.name().toLowerCase()));
                } else {
                    player.sendMessage(Lang.msg("msg.admin.block_exists", player, NamedTextColor.YELLOW,
                            mat.name().toLowerCase()));
                }
                Bukkit.getScheduler().runTask(plugin, () -> openMenu(player));
                return;
            }
        }

        // ── Normal action slot ─────────────────────────────────────────────
        if (slot < 0 || slot >= 54) return;
        boolean shift = event.isShiftClick();
        Consumer<Player>[] map = shift
                ? shiftActions.getOrDefault(uid, menuActions.get(uid))
                : menuActions.get(uid);
        if (map == null || map[slot] == null) {
            // Fallback to regular if no shift action
            map = menuActions.get(uid);
            if (map == null || map[slot] == null) return;
        }
        Consumer<Player> action = map[slot];
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> action.accept(player));
    }

    // ── Drag block items into the grid to add them ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uid = player.getUniqueId();
        Tab tab = currentTab.getOrDefault(uid, Tab.ALLOWED);
        if (tab == Tab.SETTINGS) return;

        Material mat = event.getOldCursor().getType();
        if (mat.isAir() || !mat.isBlock()) return;

        boolean hitsGrid = event.getRawSlots().stream()
                .anyMatch(s -> s >= GRID_START && s < GRID_START + GRID_SIZE);
        if (hitsGrid) {
            if (addBlock(uid, tab, mat)) {
                player.sendMessage(Lang.msg("msg.admin.block_added", player, NamedTextColor.GREEN,
                        mat.name().toLowerCase()));
            } else {
                player.sendMessage(Lang.msg("msg.admin.block_exists", player, NamedTextColor.YELLOW,
                        mat.name().toLowerCase()));
            }
            Bukkit.getScheduler().runTask(plugin, () -> openMenu(player));
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder)) return;
        UUID uid = event.getPlayer().getUniqueId();
        menuActions.remove(uid);
        shiftActions.remove(uid);
    }

    // ── Working block layer ────────────────────────────────────────────────────
    //
    //  workingBlocks is the single source of truth for the menu display.
    //  In CraftType mode: initialised from CraftType; changes propagate to
    //  CraftType immediately AND to workingBlocks.
    //  In file-only mode: initialised from YAML; changes go to workingBlocks only
    //  until Save is clicked.

    /** Populate workingBlocks (and groupTagsCache) always from the file on disk.
     *  Using the CraftType's expanded materialSet would include all blocks covered
     *  by group tags (#planks → oak_planks, etc.), polluting the saved output.
     *  If no file is available, fall back to CraftType (shouldn't occur in practice). */
    private void loadWorkingBlocks(UUID uid, CraftType type, File file) {
        Map<Tab, Set<Material>> wb   = new LinkedHashMap<>();
        Map<Tab, List<String>>  tags = new LinkedHashMap<>();
        YamlConfiguration yaml = (file != null && file.exists())
                ? YamlConfiguration.loadConfiguration(file) : null;

        if (yaml != null) {
            wb.put(Tab.ALLOWED,     parseYamlBlockSet(yaml, "allowedBlocks",     "allowed_blocks"));
            wb.put(Tab.FORBIDDEN,   parseYamlBlockSet(yaml, "forbiddenBlocks",   "forbidden_blocks"));
            wb.put(Tab.PASSTHROUGH, parseYamlBlockSet(yaml, "passthroughBlocks", "passthrough_blocks"));
            tags.put(Tab.ALLOWED,     parseYamlGroupTags(yaml, "allowedBlocks",     "allowed_blocks"));
            tags.put(Tab.FORBIDDEN,   parseYamlGroupTags(yaml, "forbiddenBlocks",   "forbidden_blocks"));
            tags.put(Tab.PASSTHROUGH, parseYamlGroupTags(yaml, "passthroughBlocks", "passthrough_blocks"));
        } else if (type != null) {
            for (Tab t : new Tab[]{Tab.ALLOWED, Tab.FORBIDDEN, Tab.PASSTHROUGH}) {
                wb.put(t, getBlockSetFromType(type, t));
            }
        }

        workingBlocks.put(uid, wb);
        groupTagsCache.put(uid, tags);
    }

    private Set<Material> getBlockSetFromType(CraftType type, Tab tab) {
        NamespacedKey key = blockKey(tab);
        if (key == null) return new LinkedHashSet<>();
        try {
            EnumSet<Material> s = type.getMaterialSetProperty(key);
            return s != null ? new LinkedHashSet<>(s) : new LinkedHashSet<>();
        } catch (Exception e) { return new LinkedHashSet<>(); }
    }

    private Set<Material> parseYamlBlockSet(YamlConfiguration yaml, String keyCC, String keySC) {
        String k = yaml.contains(keyCC) ? keyCC : yaml.contains(keySC) ? keySC : null;
        Set<Material> result = new LinkedHashSet<>();
        if (k == null) return result;
        List<?> raw = yaml.getList(k);
        if (raw == null) return result;
        for (Object entry : raw) {
            if (!(entry instanceof String s)) continue;
            if (s.startsWith("#")) continue; // group tags handled separately
            Material mat = Material.matchMaterial(s);
            if (mat != null && mat.isBlock()) result.add(mat);
        }
        return result;
    }

    /** Collect Movecraft group tags (#planks, #wool, etc.) from the YAML list — preserved on save. */
    private List<String> parseYamlGroupTags(YamlConfiguration yaml, String keyCC, String keySC) {
        String k = yaml.contains(keyCC) ? keyCC : yaml.contains(keySC) ? keySC : null;
        List<String> tags = new ArrayList<>();
        if (k == null) return tags;
        List<?> raw = yaml.getList(k);
        if (raw == null) return tags;
        for (Object entry : raw) {
            if (entry instanceof String s && s.startsWith("#")) tags.add(s);
        }
        return tags;
    }

    private List<String> getGroupTags(UUID uid, Tab tab) {
        Map<Tab, List<String>> tags = groupTagsCache.get(uid);
        if (tags == null) return List.of();
        return tags.getOrDefault(tab, List.of());
    }

    private Set<Material> getWorkingBlocks(UUID uid, Tab tab) {
        Map<Tab, Set<Material>> wb = workingBlocks.get(uid);
        if (wb == null) return new LinkedHashSet<>();
        return wb.getOrDefault(tab, new LinkedHashSet<>());
    }

    private NamespacedKey blockKey(Tab tab) {
        return switch (tab) {
            case ALLOWED     -> CraftType.ALLOWED_BLOCKS;
            case FORBIDDEN   -> CraftType.FORBIDDEN_BLOCKS;
            case PASSTHROUGH -> CraftType.PASSTHROUGH_BLOCKS;
            default          -> null;
        };
    }

    /** Returns true if block was added, false if already present. */
    private boolean addBlock(UUID uid, Tab tab, Material mat) {
        Set<Material> wb = getWorkingBlocks(uid, tab);
        if (wb.contains(mat)) return false;
        workingBlocks.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                .computeIfAbsent(tab, k -> new LinkedHashSet<>()).add(mat);
        // Also update live CraftType if available
        CraftType type = targetType.get(uid);
        if (type != null) modifyMaterialSet(type, blockKey(tab), true, mat);
        return true;
    }

    private void removeBlock(UUID uid, Tab tab, Material mat) {
        Map<Tab, Set<Material>> wb = workingBlocks.get(uid);
        if (wb != null) wb.getOrDefault(tab, new LinkedHashSet<>()).remove(mat);
        CraftType type = targetType.get(uid);
        if (type != null) modifyMaterialSet(type, blockKey(tab), false, mat);
    }

    @SuppressWarnings("unchecked")
    private void modifyMaterialSet(CraftType type, NamespacedKey key, boolean add, Material mat) {
        if (key == null) return;
        try {
            Field f = rf(CraftType.class, "materialSetPropertyMap");
            Map<NamespacedKey, EnumSet<Material>> map =
                    (Map<NamespacedKey, EnumSet<Material>>) f.get(type);
            EnumSet<Material> cur = map.get(key);
            if (cur == null) {
                if (add) map.put(key, EnumSet.of(mat));
                return;
            }
            try {
                if (add) cur.add(mat); else cur.remove(mat);
            } catch (UnsupportedOperationException e) {
                EnumSet<Material> ns = cur.isEmpty() ? EnumSet.noneOf(Material.class) : EnumSet.copyOf(cur);
                if (add) ns.add(mat); else ns.remove(mat);
                map.put(key, ns);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftAdmin] modifyMaterialSet: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private void modifyInt(CraftType type, NamespacedKey key, int value) {
        try {
            ((Map<NamespacedKey, Integer>) rf(CraftType.class, "intPropertyMap").get(type)).put(key, value);
        } catch (Exception e) { plugin.getLogger().warning("[CraftAdmin] modifyInt: " + e); }
    }

    @SuppressWarnings("unchecked")
    private void modifyDoubleOrFloat(CraftType type, NamespacedKey key, double value) {
        try {
            Map<NamespacedKey, Double> dm =
                    (Map<NamespacedKey, Double>) rf(CraftType.class, "doublePropertyMap").get(type);
            if (dm.containsKey(key)) { dm.put(key, value); return; }
        } catch (Exception ignored) {}
        try {
            Map<NamespacedKey, Float> fm =
                    (Map<NamespacedKey, Float>) rf(CraftType.class, "floatPropertyMap").get(type);
            if (fm.containsKey(key)) { fm.put(key, (float) value); return; }
        } catch (Exception ignored) {}
        plugin.getLogger().warning("[CraftAdmin] modifyDouble: key not found: " + key);
    }

    private int safeInt(CraftType type, NamespacedKey key) {
        try { return type.getIntProperty(key); } catch (Exception e) { return -1; }
    }

    private double safeDouble(CraftType type, NamespacedKey key) {
        try { return type.getDoubleProperty(key); } catch (Exception ignored) {}
        try { return type.getFloatProperty(key); } catch (Exception ignored) {}
        return -1;
    }

    private Field rf(Class<?> cls, String name) throws NoSuchFieldException, IllegalAccessException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ── Save to disk ───────────────────────────────────────────────────────────
    //
    //  We do NOT use YamlConfiguration.save() for the whole file: Bukkit cannot
    //  serialise YAML list-keys like  ["#wool", "#leaves"]:  used by moveblocks/
    //  flyblocks, so it silently drops those sections.  Instead we read the file
    //  as plain text and surgically replace only the three block-list sections
    //  (allowedBlocks / forbiddenBlocks / passthroughBlocks) and numeric scalars.
    //  Everything else (moveblocks, flyblocks, comments…) is left untouched.

    private void saveChanges(Player player) {
        UUID uid = player.getUniqueId();
        CraftType type = targetType.get(uid);
        File file = targetFile.get(uid);
        if (file == null && type != null) file = findCraftTypeFile(type);
        if (file == null || !file.exists()) {
            player.sendMessage(Lang.msg("msg.admin.no_file", player, NamedTextColor.YELLOW));
            return;
        }

        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            player.sendMessage(Lang.msg("msg.admin.save_error", player, NamedTextColor.RED, e.getMessage()));
            return;
        }

        // Replace only the block-list sections (preserves moveblocks/flyblocks/etc.)
        content = replaceYamlList(content, new String[]{"allowedBlocks",     "allowed_blocks"},
                getGroupTags(uid, Tab.ALLOWED),     getWorkingBlocks(uid, Tab.ALLOWED));
        content = replaceYamlList(content, new String[]{"forbiddenBlocks",   "forbidden_blocks"},
                getGroupTags(uid, Tab.FORBIDDEN),   getWorkingBlocks(uid, Tab.FORBIDDEN));
        content = replaceYamlList(content, new String[]{"passthroughBlocks", "passthrough_blocks"},
                getGroupTags(uid, Tab.PASSTHROUGH), getWorkingBlocks(uid, Tab.PASSTHROUGH));

        // Replace numeric scalars (only when CraftType is available)
        if (type != null) {
            for (IntProp p : IntProp.values()) {
                int v = safeInt(type, p.key);
                if (v >= 0) content = replaceYamlScalar(content,
                        new String[]{toCamel(p.yamlKey), p.yamlKey}, String.valueOf(v));
            }
            for (DoubleProp p : DoubleProp.values()) {
                double v = safeDouble(type, p.key);
                if (v < 0) continue;
                // Write as integer if whole number (matches original file style)
                String vs = (v == Math.floor(v) && v < 1e9) ? String.valueOf((long) v)
                        : String.format("%.1f", v);
                content = replaceYamlScalar(content,
                        new String[]{toCamel(p.yamlKey), p.yamlKey}, vs);
            }
        }

        final File fFile = file;
        try (FileWriter fw = new FileWriter(fFile, java.nio.charset.StandardCharsets.UTF_8)) {
            fw.write(content);
            player.sendMessage(Lang.msg("msg.admin.saved", player, NamedTextColor.GREEN, fFile.getName()));
            if (type == null)
                player.sendMessage(Lang.msg("msg.admin.file_saved_reload", player, NamedTextColor.YELLOW));
        } catch (IOException e) {
            player.sendMessage(Lang.msg("msg.admin.save_error", player, NamedTextColor.RED, e.getMessage()));
        }
        targetFile.put(uid, fFile);
    }

    /**
     * Replace a YAML block-list section in raw file text.
     * Only the indented lines that follow the key are replaced; everything before
     * and after the section (including moveblocks/flyblocks) is preserved exactly.
     * Items that start with '#' are double-quoted so YAML parsers don't treat them as comments.
     */
    private String replaceYamlList(String content, String[] keyVariants,
                                   List<String> groupTags, Set<Material> blocks) {
        List<String> allItems = new ArrayList<>(groupTags);
        blocks.stream().map(m -> m.name().toLowerCase()).sorted().forEach(allItems::add);

        for (String key : keyVariants) {
            // Match the key line followed by all indented / blank lines belonging to it
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?m)^" + java.util.regex.Pattern.quote(key) + ":[ \\t]*\\n"
                            + "((?:[ \\t][^\\n]*\\n|[ \\t]*\\n)*)");
            java.util.regex.Matcher m = p.matcher(content);
            if (!m.find()) continue;

            StringBuilder newBlock = new StringBuilder(key).append(":\n");
            for (String item : allItems) {
                // '#tag' must be quoted in YAML (# starts a comment otherwise)
                String val = item.startsWith("#") ? "\"" + item + "\"" : item;
                newBlock.append("    - ").append(val).append("\n");
            }
            return content.substring(0, m.start()) + newBlock + content.substring(m.end());
        }
        return content; // key not present — leave unchanged
    }

    /** Replace a single YAML scalar value in raw file text (first matching key variant wins). */
    private String replaceYamlScalar(String content, String[] keyVariants, String value) {
        for (String key : keyVariants) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?m)^" + java.util.regex.Pattern.quote(key) + ":[ \\t]*[^\\n]*$");
            java.util.regex.Matcher m = p.matcher(content);
            if (!m.find()) continue;
            return content.substring(0, m.start()) + key + ": " + value + content.substring(m.end());
        }
        return content;
    }

    /** Write a Set<Material> (plus original group tags) to a YAML config. */
    private void writeBlockSet(YamlConfiguration yaml, Set<Material> blocks, List<String> groupTags, String keyCC, String keySC) {
        List<String> names = new ArrayList<>(groupTags);
        blocks.stream().map(m -> m.name().toLowerCase()).sorted().forEach(names::add);
        if (names.isEmpty()) return;
        yaml.set(resolveKeyPair(yaml, keySC, keyCC), names);
    }

    // ── Reload from disk ──────────────────────────────────────────────────────

    private void reloadFromFile(Player player) {
        UUID uid = player.getUniqueId();
        CraftType type = targetType.get(uid);
        File file = targetFile.get(uid);
        if (file == null && type != null) file = findCraftTypeFile(type);
        if (file == null || !file.exists()) {
            player.sendMessage(Lang.msg("msg.admin.no_file", player, NamedTextColor.YELLOW));
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        // Reload workingBlocks from file
        loadWorkingBlocks(uid, null, file); // file-first reload

        // If CraftType is available, also propagate to its in-memory maps
        if (type != null) {
            reloadBlockSetIntoType(yaml, type, CraftType.ALLOWED_BLOCKS,     "allowedBlocks", "allowed_blocks");
            reloadBlockSetIntoType(yaml, type, CraftType.FORBIDDEN_BLOCKS,   "forbiddenBlocks", "forbidden_blocks");
            reloadBlockSetIntoType(yaml, type, CraftType.PASSTHROUGH_BLOCKS, "passthroughBlocks", "passthrough_blocks");

            for (IntProp p : IntProp.values()) {
                String key = resolveKeyPair(yaml, p.yamlKey, toCamel(p.yamlKey));
                if (yaml.contains(key)) modifyInt(type, p.key, yaml.getInt(key));
            }
            for (DoubleProp p : DoubleProp.values()) {
                String key = resolveKeyPair(yaml, p.yamlKey, toCamel(p.yamlKey));
                if (yaml.contains(key)) modifyDoubleOrFloat(type, p.key, yaml.getDouble(key));
            }
        }

        if (file != null) targetFile.put(uid, file);
        player.sendMessage(Lang.msg("msg.admin.reloaded", player, NamedTextColor.GREEN, file.getName()));
    }

    @SuppressWarnings("unchecked")
    private void reloadBlockSetIntoType(YamlConfiguration yaml, CraftType type,
                                        NamespacedKey key, String keyCC, String keySC) {
        String yamlKey = yaml.contains(keyCC) ? keyCC : yaml.contains(keySC) ? keySC : null;
        if (yamlKey == null) return;
        List<?> raw = yaml.getList(yamlKey);
        if (raw == null) return;
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        for (Object entry : raw) {
            if (!(entry instanceof String s)) continue;
            Material mat = Material.matchMaterial(s);
            if (mat != null) set.add(mat);
        }
        try {
            Field f = rf(CraftType.class, "materialSetPropertyMap");
            ((Map<NamespacedKey, EnumSet<Material>>) f.get(type)).put(key, set);
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftAdmin] reloadBlockSetIntoType: " + e);
        }
    }

    /** Write a Set<Material> (plus original group tags) to a YAML config. */
    private void writeBlockSet(YamlConfiguration yaml, Set<Material> blocks, List<String> groupTags, String keyCC, String keySC) {
        List<String> names = new ArrayList<>(groupTags);
        blocks.stream().map(m -> m.name().toLowerCase()).sorted().forEach(names::add);
        if (names.isEmpty()) return;
        yaml.set(resolveKeyPair(yaml, keySC, keyCC), names);
    }

    // ── File finding ───────────────────────────────────────────────────────────
    //
    //  Movecraft stores types in plugins/Movecraft/types/*.craft
    //
    //  Strategy:
    //  1. Scan types/ (and other dirs) comparing normalized names
    //     ("Ground Train" == "ground_train" == "groundtrain")
    //  2. As last resort, open each .craft file and check its name: field

    private File findCraftTypeFile(CraftType type) {
        Plugin mc = Bukkit.getPluginManager().getPlugin("Movecraft");
        if (mc == null) return null;
        File dataFolder = mc.getDataFolder();
        // Collect all candidate names for this type
        List<String> candidateNames = new ArrayList<>();
        String typeName = getTypeName(type);
        if (!typeName.equals("Unknown")) candidateNames.add(typeName);
        String nkKey = safeNkKey(type);
        if (nkKey != null) candidateNames.add(nkKey);
        if (candidateNames.isEmpty()) candidateNames.add("Unknown");
        String normTarget = candidateNames.stream()
                .map(this::normName).findFirst().orElse("unknown");

        // Pass 1: match by filename (normalized)
        List<File> candidates = new ArrayList<>();
        for (String dir : new String[]{"types", "craft", "craftTypes", ""}) {
            File d = dir.isEmpty() ? dataFolder : new File(dataFolder, dir);
            if (!d.exists()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String base = normName(f.getName().replaceFirst("\\.[^.]+$", ""));
                // Match against all candidate names (YAML name and namespacedKey)
                boolean matched = candidateNames.stream().map(this::normName).anyMatch(base::equals);
                if (matched) {
                    plugin.getLogger().info("[CraftAdmin] found by name: " + f.getAbsolutePath());
                    return f;
                }
                candidates.add(f); // keep for pass 2
            }
        }

        // Pass 2: open each candidate and check YAML name: field
        for (File f : candidates) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                // Movecraft uses various keys: name, craftType, type
                for (String key : new String[]{"name", "craftType", "type"}) {
                    String val = yaml.getString(key);
                    if (val != null && normName(val).equals(normTarget)) {
                        plugin.getLogger().info("[CraftAdmin] found by yaml '" + key + "': " + f.getAbsolutePath());
                        return f;
                    }
                }
            } catch (Exception ignored) {}
        }

        plugin.getLogger().warning("[CraftAdmin] type file not found for '" + typeName
                + "' / '" + nkKey + "' in " + dataFolder.getAbsolutePath());
        return null;
    }

    /** Normalize for comparison: lowercase, strip spaces and underscores. */
    private String normName(String s) {
        return s.toLowerCase().replaceAll("[_ ]+", "");
    }

    private String getTypeName(CraftType type) {
        try {
            String n = type.getStringProperty(CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // ── YAML key helpers ───────────────────────────────────────────────────────

    private String resolveKey(YamlConfiguration yaml, String snake) {
        String cc = toCamel(snake);
        if (yaml.contains(cc)) return cc;
        if (yaml.contains(snake)) return snake;
        return cc;
    }

    private String resolveKeyPair(YamlConfiguration yaml, String sc, String cc) {
        if (yaml.contains(cc)) return cc;
        if (yaml.contains(sc)) return sc;
        return cc;
    }

    private String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { up = true; }
            else { sb.append(up ? Character.toUpperCase(c) : c); up = false; }
        }
        return sb.toString();
    }

    // ── Item builders ──────────────────────────────────────────────────────────

    private ItemStack tabItem(String name, Material mat, boolean active) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text((active ? "▶ " : "") + name)
                .color(active ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack bgItem() {
        ItemStack is = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.empty());
        is.setItemMeta(m);
        return is;
    }

    private ItemStack reloadItem() {
        ItemStack is = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text("↩ Сбросить из файла")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Откатить изменения к состоянию на диске")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack saveItem() {
        ItemStack is = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text("💾 Сохранить в файл")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Записать изменения на диск")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack blockItem(Material mat) {
        // Some blocks have no placeable item form; use PAPER as neutral icon
        ItemStack is = new ItemStack(mat.isItem() && mat != Material.AIR ? mat : Material.PAPER);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(mat.name().toLowerCase())
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (!mat.isItem()) lore.add(Component.text("(нет предмета — тех. блок)")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Нажмите — удалить")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        is.setItemMeta(m);
        return is;
    }

    private ItemStack navItem(String label) {
        ItemStack is = new ItemStack(Material.ARROW);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(label)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack stepItem(String label, Material mat) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(label)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Shift+клик: ×10")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack intItem(String name, int value, Material mat) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(name + ": " + (value < 0 ? "—" : value))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Нажмите — ввести точное значение")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack doubleItem(String name, double value, Material mat) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        String display = value < 0 ? "—" : String.format("%.2f", value);
        m.displayName(Component.text(name + ": " + display)
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Нажмите — ввести точное значение")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    @SuppressWarnings("unchecked")
    private void setSlot(Inventory inv, Consumer<Player>[] actions, Consumer<Player>[] shifts,
                         int slot, ItemStack item, Consumer<Player> action, Consumer<Player> shift) {
        inv.setItem(slot, item);
        actions[slot] = action;
        shifts[slot] = shift != null ? shift : action;
    }

    // ── InventoryHolder ───────────────────────────────────────────────────────

    static class AdminMenuHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
