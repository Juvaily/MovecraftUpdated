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
import java.io.IOException;
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
    private final Map<UUID, CraftType>          targetType    = new ConcurrentHashMap<>();
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

        CraftType type = null;
        if (args.length >= 1) {
            // "/craftadmin list" — show all registered type names
            if (args[0].equalsIgnoreCase("list")) {
                sendTypeList(player);
                return true;
            }
            type = findTypeByName(String.join(" ", args));
            if (type == null) {
                player.sendMessage(Lang.msg("msg.admin.type_not_found", player, NamedTextColor.RED,
                        String.join(" ", args)));
                sendTypeList(player);
                return true;
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
        }

        if (type == null) {
            player.sendMessage(Lang.msg("msg.admin.no_craft_nearby", player, NamedTextColor.RED));
            sendTypeList(player);
            return true;
        }

        UUID uid = player.getUniqueId();
        targetType.put(uid, type);
        currentTab.putIfAbsent(uid, Tab.ALLOWED);
        blockOffset.put(uid, 0);
        openMenu(player);
        return true;
    }

    // ── Type name lookup ───────────────────────────────────────────────────────

    /**
     * Find a CraftType by name.
     *
     * Strategy (in order):
     *  1. CraftManager.getCraftTypeFromString — Movecraft's own lookup (uses name: field)
     *  2. Scan types/ folder filenames, call getCraftTypeFromString per filename
     *  3. Iterate getCraftTypes() with normalized name comparison
     */
    private CraftType findTypeByName(String search) {
        // 1. Direct Movecraft lookup
        try {
            CraftType ct = CraftManager.getInstance().getCraftTypeFromString(search);
            if (ct != null) return ct;
        } catch (Exception ignored) {}

        // 2. Filename-based: scan disk and try exact then partial filename match
        List<String> fileNames = listTypeFileNames();
        String normSearch = normName(search);
        String partialMatch = null;
        for (String fn : fileNames) {
            if (normName(fn).equals(normSearch)) {
                try {
                    CraftType ct = CraftManager.getInstance().getCraftTypeFromString(fn);
                    if (ct != null) return ct;
                } catch (Exception ignored) {}
            }
            if (partialMatch == null && normName(fn).contains(normSearch)) partialMatch = fn;
        }
        if (partialMatch != null) {
            try {
                CraftType ct = CraftManager.getInstance().getCraftTypeFromString(partialMatch);
                if (ct != null) return ct;
            } catch (Exception ignored) {}
        }

        // 3. Iterate registered types with normalized name comparison
        CraftType fallback = null;
        for (CraftType ct : CraftManager.getInstance().getCraftTypes()) {
            String name = safeTypeName(ct);
            if (name == null) continue;
            if (normName(name).equals(normSearch)) return ct;
            if (fallback == null && normName(name).contains(normSearch)) fallback = ct;
        }
        return fallback;
    }

    private String safeTypeName(CraftType ct) {
        try { return ct.getStringProperty(CraftType.NAME); }
        catch (Exception e) { return null; }
    }

    /**
     * Send the player a list of all available craft type names.
     *
     * Sources:
     *  - All .craft filenames in Movecraft's types/ folder (most reliable)
     *  - getCraftTypes() with safeTypeName as fallback if folder scan fails
     */
    private void sendTypeList(Player player) {
        List<String> names = new ArrayList<>();

        // Primary: filenames from disk
        List<String> fileNames = listTypeFileNames();
        for (String fn : fileNames) {
            // Show as-is; admin uses this exact string to find the type
            if (!names.contains(fn)) names.add(fn);
        }

        // Fallback: registered types with readable names (may overlap or differ from filenames)
        for (CraftType ct : CraftManager.getInstance().getCraftTypes()) {
            String n = safeTypeName(ct);
            if (n != null && !names.contains(n)) names.add(n);
        }

        if (names.isEmpty()) {
            player.sendMessage(Component.text("Нет зарегистрированных типов. Проверь папку Movecraft/types/")
                    .color(NamedTextColor.GRAY));
            return;
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        player.sendMessage(Component.text("Типы (/craftadmin <имя>): ")
                .color(NamedTextColor.GOLD)
                .append(Component.text(String.join(", ", names)).color(NamedTextColor.YELLOW)));
    }

    /** Returns all .craft filenames without extension from Movecraft's types folder. */
    private List<String> listTypeFileNames() {
        Plugin mc = Bukkit.getPluginManager().getPlugin("Movecraft");
        if (mc == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String dir : new String[]{"types", "craft", "craftTypes"}) {
            File d = new File(mc.getDataFolder(), dir);
            if (!d.exists()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile()) result.add(f.getName().replaceFirst("\\.[^.]+$", ""));
            }
        }
        return result;
    }

    // ── Build and open inventory ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void openMenu(Player player) {
        UUID uid = player.getUniqueId();
        CraftType type = targetType.get(uid);
        if (type == null) return;

        Tab tab = currentTab.getOrDefault(uid, Tab.ALLOWED);
        AdminMenuHolder holder = new AdminMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("⚙ " + getTypeName(type)).color(NamedTextColor.DARK_PURPLE));
        holder.setInventory(inv);

        Consumer<Player>[] actions = new Consumer[54];
        Consumer<Player>[] shifts  = new Consumer[54];
        buildTabRow(inv, actions, shifts, player, tab);

        if (tab == Tab.SETTINGS) buildSettingsContent(inv, actions, shifts, player, type);
        else                     buildBlockContent(inv, actions, shifts, player, type, tab);

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
                                   Player player, CraftType type, Tab tab) {
        UUID uid = player.getUniqueId();
        int offset = blockOffset.getOrDefault(uid, 0);

        List<Material> list = new ArrayList<>(getBlockSet(type, tab));
        list.sort(Comparator.comparing(Enum::name));

        ItemStack bg = bgItem();
        for (int i = GRID_START; i < GRID_START + GRID_SIZE; i++) inv.setItem(i, bg);

        for (int i = 0; i < GRID_SIZE && (offset + i) < list.size(); i++) {
            Material mat = list.get(offset + i);
            int slot = GRID_START + i;
            setSlot(inv, a, s, slot, blockItem(mat), p -> {
                removeBlock(type, tab, mat);
                int off = blockOffset.getOrDefault(p.getUniqueId(), 0);
                int remaining = getBlockSet(type, tab).size();
                blockOffset.put(p.getUniqueId(), Math.min(off, Math.max(0, (remaining / GRID_SIZE) * GRID_SIZE)));
                openMenu(p);
            }, null);
        }

        // Row 5 (slots 45-53)
        for (int i = 45; i <= 53; i++) inv.setItem(i, bg);

        if (offset > 0)
            setSlot(inv, a, s, 45, navItem("◀ Назад"),
                    p -> { blockOffset.put(p.getUniqueId(), offset - GRID_SIZE); openMenu(p); }, null);

        // Page info (slot 49)
        int pages = Math.max(1, (int) Math.ceil((double) list.size() / GRID_SIZE));
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.displayName(Component.text("Блоков: " + list.size())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        im.lore(List.of(
                Component.text("Стр. " + (offset / GRID_SIZE + 1) + "/" + pages)
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Перетащи блок в сетку или ЛКМ из инвентаря")
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        info.setItemMeta(im);
        inv.setItem(49, info);

        if (offset + GRID_SIZE < list.size())
            setSlot(inv, a, s, 53, navItem("Далее ▶"),
                    p -> { blockOffset.put(p.getUniqueId(), offset + GRID_SIZE); openMenu(p); }, null);
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
        if (slot >= 54 && tab != Tab.SETTINGS && type != null) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir() && clicked.getType().isBlock()) {
                Material mat = clicked.getType();
                if (addBlock(type, tab, mat)) {
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
        if (slot >= GRID_START && slot < GRID_START + GRID_SIZE
                && tab != Tab.SETTINGS && type != null) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && cursor.getType().isBlock()
                    && event.getAction() != InventoryAction.PICKUP_ALL
                    && event.getAction() != InventoryAction.PICKUP_SOME) {
                Material mat = cursor.getType();
                if (addBlock(type, tab, mat)) {
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
        CraftType type = targetType.get(uid);
        if (type == null) return;

        Material mat = event.getOldCursor().getType();
        if (mat.isAir() || !mat.isBlock()) return;

        boolean hitsGrid = event.getRawSlots().stream()
                .anyMatch(s -> s >= GRID_START && s < GRID_START + GRID_SIZE);
        if (hitsGrid) {
            if (addBlock(type, tab, mat)) {
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

    // ── CraftType read / write ─────────────────────────────────────────────────
    //
    //  Internal maps are Map<NamespacedKey, V> — keys match the public/private
    //  NamespacedKey constants directly.

    private Set<Material> getBlockSet(CraftType type, Tab tab) {
        NamespacedKey key = blockKey(tab);
        if (key == null) return EnumSet.noneOf(Material.class);
        try {
            EnumSet<Material> s = type.getMaterialSetProperty(key);
            return s != null ? new LinkedHashSet<>(s) : new LinkedHashSet<>();
        } catch (Exception e) { return new LinkedHashSet<>(); }
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
    private boolean addBlock(CraftType type, Tab tab, Material mat) {
        if (getBlockSet(type, tab).contains(mat)) return false;
        modifyMaterialSet(type, blockKey(tab), true, mat);
        return true;
    }

    private void removeBlock(CraftType type, Tab tab, Material mat) {
        modifyMaterialSet(type, blockKey(tab), false, mat);
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

    private void saveChanges(Player player) {
        CraftType type = targetType.get(player.getUniqueId());
        if (type == null) return;

        File file = findCraftTypeFile(type);
        if (file == null || !file.exists()) {
            plugin.getLogger().warning("[CraftAdmin] craft type file not found for: " + getTypeName(type));
            player.sendMessage(Lang.msg("msg.admin.no_file", player, NamedTextColor.YELLOW));
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        saveBlockSet(yaml, type, CraftType.ALLOWED_BLOCKS,     "allowedBlocks",     "allowed_blocks");
        saveBlockSet(yaml, type, CraftType.FORBIDDEN_BLOCKS,   "forbiddenBlocks",   "forbidden_blocks");
        saveBlockSet(yaml, type, CraftType.PASSTHROUGH_BLOCKS, "passthroughBlocks", "passthrough_blocks");

        for (IntProp p : IntProp.values()) {
            int v = safeInt(type, p.key);
            if (v >= 0) yaml.set(resolveKey(yaml, p.yamlKey), v);
        }
        for (DoubleProp p : DoubleProp.values()) {
            double v = safeDouble(type, p.key);
            if (v >= 0) yaml.set(resolveKey(yaml, p.yamlKey), v);
        }

        try {
            yaml.save(file);
            player.sendMessage(Lang.msg("msg.admin.saved", player, NamedTextColor.GREEN, file.getName()));
        } catch (IOException e) {
            player.sendMessage(Lang.msg("msg.admin.save_error", player, NamedTextColor.RED, e.getMessage()));
        }
    }

    // ── Reload from disk → restore in-memory CraftType state ─────────────────

    private void reloadFromFile(Player player) {
        CraftType type = targetType.get(player.getUniqueId());
        if (type == null) return;

        File file = findCraftTypeFile(type);
        if (file == null || !file.exists()) {
            player.sendMessage(Lang.msg("msg.admin.no_file", player, NamedTextColor.YELLOW));
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        // Block sets
        reloadBlockSet(yaml, type, CraftType.ALLOWED_BLOCKS,     "allowedBlocks",     "allowed_blocks");
        reloadBlockSet(yaml, type, CraftType.FORBIDDEN_BLOCKS,   "forbiddenBlocks",   "forbidden_blocks");
        reloadBlockSet(yaml, type, CraftType.PASSTHROUGH_BLOCKS, "passthroughBlocks", "passthrough_blocks");

        // Integer properties
        for (IntProp p : IntProp.values()) {
            String key = resolveKeyPair(yaml, p.yamlKey, toCamel(p.yamlKey));
            if (yaml.contains(key)) modifyInt(type, p.key, yaml.getInt(key));
        }
        // Double/float properties
        for (DoubleProp p : DoubleProp.values()) {
            String key = resolveKeyPair(yaml, p.yamlKey, toCamel(p.yamlKey));
            if (yaml.contains(key)) modifyDoubleOrFloat(type, p.key, yaml.getDouble(key));
        }

        player.sendMessage(Lang.msg("msg.admin.reloaded", player, NamedTextColor.GREEN, file.getName()));
    }

    @SuppressWarnings("unchecked")
    private void reloadBlockSet(YamlConfiguration yaml, CraftType type,
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
            Map<NamespacedKey, EnumSet<Material>> map =
                    (Map<NamespacedKey, EnumSet<Material>>) f.get(type);
            map.put(key, set);
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftAdmin] reloadBlockSet: " + e);
        }
    }

    private void saveBlockSet(YamlConfiguration yaml, CraftType type,
                              NamespacedKey key, String keyCC, String keySC) {
        try {
            EnumSet<Material> set = type.getMaterialSetProperty(key);
            if (set == null) return;
            List<String> names = set.stream().map(m -> m.name().toLowerCase()).sorted().toList();
            yaml.set(resolveKeyPair(yaml, keySC, keyCC), names);
        } catch (Exception ignored) {}
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
        String typeName = getTypeName(type);
        String normTarget = normName(typeName);

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
                if (base.equals(normTarget)) {
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
                + "' (normalized: '" + normTarget + "') in " + dataFolder.getAbsolutePath());
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
