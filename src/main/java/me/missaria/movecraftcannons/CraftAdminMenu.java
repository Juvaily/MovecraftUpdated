package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class CraftAdminMenu implements Listener {

    static final String PERMISSION = "movecraftcannons.admin.craft";

    private enum Tab { ALLOWED, FORBIDDEN, PASSTHROUGH, SETTINGS }

    // Retrieve a private static NamespacedKey constant from CraftType via reflection.
    // Falls back to a constructed key using lowercase field name if reflection fails.
    private static NamespacedKey craftKey(String fieldName) {
        try {
            Field f = CraftType.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return (NamespacedKey) f.get(null);
        } catch (Exception e) {
            return new NamespacedKey("movecraft", fieldName.toLowerCase());
        }
    }

    // ── Property descriptors for Settings tab ─────────────────────────────────
    //
    //  Some CraftType constants are private; we retrieve them via reflection.
    //  Public ones are referenced directly (ALLOWED_BLOCKS, MIN_SIZE, MAX_SIZE, NAME).

    private enum IntProp {
        MIN_SIZE   ("Мин. размер",          "min_size",                 Material.CHEST,            CraftType.MIN_SIZE),
        MAX_SIZE   ("Макс. размер",         "max_size",                 Material.ENDER_CHEST,      CraftType.MAX_SIZE),
        TICK_COOL  ("Тик ручн. (Manual)",   "tick_cooldown",            Material.CLOCK,            craftKey("TICK_COOLDOWN")),
        CRUISE_COOL("Тик круиза",           "cruise_tick_cooldown",     Material.POWERED_RAIL,     craftKey("CRUISE_TICK_COOLDOWN")),
        CRUISE_SKIP("Прыжок круиза",        "cruise_skip_blocks",       Material.RAIL,             craftKey("CRUISE_SKIP_BLOCKS")),
        VERT_COOL  ("Тик верт. круиза",     "vert_cruise_tick_cooldown",Material.LADDER,           craftKey("VERT_CRUISE_TICK_COOLDOWN")),
        VERT_SKIP  ("Прыжок верт. круиза",  "vert_cruise_skip_blocks",  Material.VINE,             craftKey("VERT_CRUISE_SKIP_BLOCKS")),
        MIN_HEIGHT ("Мин. высота",          "min_height_limit",         Material.POINTED_DRIPSTONE,craftKey("MIN_HEIGHT_LIMIT")),
        MAX_HEIGHT ("Макс. высота",         "max_height_limit",         Material.SCAFFOLDING,      craftKey("MAX_HEIGHT_LIMIT"));

        final String label; final String yamlKey; final Material icon; final NamespacedKey key;
        IntProp(String l, String y, Material i, NamespacedKey k) { label=l; yamlKey=y; icon=i; key=k; }
    }

    // Speeds may be Double or Float properties depending on Movecraft build;
    // safeDouble() tries both getDoubleProperty and getFloatProperty.
    private enum DoubleProp {
        CRUISE_SPEED("Скорость круиза",  "cruise_speed",      Material.CYAN_DYE, craftKey("CRUISE_SPEED")),
        VERT_SPEED  ("Верт. скорость",   "vert_cruise_speed", Material.FEATHER,  craftKey("VERT_CRUISE_SPEED")),
        SPEED       ("Скорость DC",      "speed",             Material.SUGAR,    craftKey("SPEED"));

        final String label; final String yamlKey; final Material icon; final NamespacedKey key;
        DoubleProp(String l, String y, Material i, NamespacedKey k) { label=l; yamlKey=y; icon=i; key=k; }
    }

    // Settings grid layout (rows 1-5, slots 9-53)
    private static final int[] INT_SLOTS    = {9, 11, 13, 15, 18, 20, 22, 27, 29};
    private static final int[] DOUBLE_SLOTS = {37, 39, 41};

    // Block grid: rows 1-4 (slots 9-44), 36 slots
    private static final int GRID_START = 9;
    private static final int GRID_SIZE  = 36;

    private final MovecraftCannonsPlugin plugin;
    private final Map<UUID, Tab>                currentTab   = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>            blockOffset  = new ConcurrentHashMap<>();
    private final Map<UUID, CraftType>          targetType   = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<Player>[]> menuActions  = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<String>>   pendingInput = new ConcurrentHashMap<>();

    public CraftAdminMenu(MovecraftCannonsPlugin plugin) { this.plugin = plugin; }

    // ── Command entry point ────────────────────────────────────────────────────

    public boolean onCommand(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Lang.msg("msg.no_permission", player, NamedTextColor.RED));
            return true;
        }

        CraftType type = null;
        if (args.length >= 1) {
            String search = String.join(" ", args).toLowerCase();
            for (CraftType ct : CraftManager.getInstance().getCraftTypes()) {
                try {
                    String name = ct.getStringProperty(CraftType.NAME);
                    if (name != null && name.toLowerCase().contains(search)) { type = ct; break; }
                } catch (Exception ignored) {}
            }
            if (type == null) {
                player.sendMessage(Lang.msg("msg.admin.type_not_found", player, NamedTextColor.RED,
                        String.join(" ", args)));
                return true;
            }
        } else {
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
            return true;
        }

        UUID uid = player.getUniqueId();
        targetType.put(uid, type);
        currentTab.putIfAbsent(uid, Tab.ALLOWED);
        blockOffset.put(uid, 0);
        openMenu(player);
        return true;
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
        buildTabRow(inv, actions, player, tab);

        if (tab == Tab.SETTINGS) buildSettingsContent(inv, actions, player, type);
        else                     buildBlockContent(inv, actions, player, type, tab);

        menuActions.put(uid, actions);
        player.openInventory(inv);
    }

    // ── Row 0: tabs ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void buildTabRow(Inventory inv, Consumer<Player>[] actions, Player player, Tab active) {
        setSlot(inv, actions, 0, tabItem("✅ Разрешённые",  Material.LIME_STAINED_GLASS_PANE, active == Tab.ALLOWED),     p -> switchTab(p, Tab.ALLOWED));
        setSlot(inv, actions, 1, tabItem("🚫 Запрещённые",  Material.RED_STAINED_GLASS_PANE,  active == Tab.FORBIDDEN),   p -> switchTab(p, Tab.FORBIDDEN));
        setSlot(inv, actions, 2, tabItem("🔵 Сквозные",     Material.CYAN_STAINED_GLASS_PANE,  active == Tab.PASSTHROUGH), p -> switchTab(p, Tab.PASSTHROUGH));
        setSlot(inv, actions, 3, tabItem("⚙ Параметры",    Material.COMPARATOR,               active == Tab.SETTINGS),    p -> switchTab(p, Tab.SETTINGS));
        ItemStack bg = bgItem();
        for (int i = 4; i <= 7; i++) inv.setItem(i, bg);
        setSlot(inv, actions, 8, saveItem(), p -> { saveChanges(p); openMenu(p); });
    }

    private void switchTab(Player player, Tab tab) {
        currentTab.put(player.getUniqueId(), tab);
        blockOffset.put(player.getUniqueId(), 0);
        openMenu(player);
    }

    // ── Block tab content (rows 1-5) ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void buildBlockContent(Inventory inv, Consumer<Player>[] actions, Player player, CraftType type, Tab tab) {
        UUID uid = player.getUniqueId();
        int offset = blockOffset.getOrDefault(uid, 0);

        List<Material> list = new ArrayList<>(getBlockSet(type, tab));
        list.sort(Comparator.comparing(Enum::name));

        ItemStack bg = bgItem();
        for (int i = GRID_START; i < GRID_START + GRID_SIZE; i++) inv.setItem(i, bg);

        for (int i = 0; i < GRID_SIZE && (offset + i) < list.size(); i++) {
            Material mat = list.get(offset + i);
            int slot = GRID_START + i;
            setSlot(inv, actions, slot, blockItem(mat), p -> {
                removeBlock(type, tab, mat);
                int off = blockOffset.getOrDefault(p.getUniqueId(), 0);
                int remaining = getBlockSet(type, tab).size();
                blockOffset.put(p.getUniqueId(), Math.min(off, Math.max(0, (remaining / GRID_SIZE) * GRID_SIZE)));
                openMenu(p);
            });
        }

        for (int i = 45; i <= 53; i++) inv.setItem(i, bg);

        if (offset > 0)
            setSlot(inv, actions, 45, navItem("◀ Назад"),
                    p -> { blockOffset.put(p.getUniqueId(), offset - GRID_SIZE); openMenu(p); });

        // Page count info (slot 49, no action)
        int pages = Math.max(1, (int) Math.ceil((double) list.size() / GRID_SIZE));
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.displayName(Component.text("Блоков: " + list.size())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        im.lore(List.of(Component.text("Стр. " + (offset / GRID_SIZE + 1) + "/" + pages)
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        info.setItemMeta(im);
        inv.setItem(49, info);

        setSlot(inv, actions, 48, addBlockItem(), p -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir() || !hand.getType().isBlock()) {
                p.sendMessage(Lang.msg("msg.admin.not_a_block", p, NamedTextColor.RED));
                openMenu(p); return;
            }
            addBlock(type, tab, hand.getType());
            p.sendMessage(Lang.msg("msg.admin.block_added", p, NamedTextColor.GREEN, hand.getType().name().toLowerCase()));
            openMenu(p);
        });

        if (offset + GRID_SIZE < list.size())
            setSlot(inv, actions, 53, navItem("Далее ▶"),
                    p -> { blockOffset.put(p.getUniqueId(), offset + GRID_SIZE); openMenu(p); });
    }

    // ── Settings tab content (rows 1-5) ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void buildSettingsContent(Inventory inv, Consumer<Player>[] actions, Player player, CraftType type) {
        ItemStack bg = bgItem();
        for (int i = 9; i < 54; i++) inv.setItem(i, bg);

        IntProp[] ips = IntProp.values();
        for (int i = 0; i < ips.length && i < INT_SLOTS.length; i++) {
            IntProp p = ips[i];
            int val = safeInt(type, p.key);
            setSlot(inv, actions, INT_SLOTS[i], intItem(p.label, val, p.icon),
                    pl -> promptInt(pl, p.label, v -> { modifyInt(type, p.key, v); openMenu(pl); }));
        }

        DoubleProp[] dps = DoubleProp.values();
        for (int i = 0; i < dps.length && i < DOUBLE_SLOTS.length; i++) {
            DoubleProp p = dps[i];
            double val = safeDouble(type, p.key);
            setSlot(inv, actions, DOUBLE_SLOTS[i], doubleItem(p.label, val, p.icon),
                    pl -> promptDouble(pl, p.label, v -> { modifyDoubleOrFloat(type, p.key, v); openMenu(pl); }));
        }
    }

    // ── Chat prompt for number input ───────────────────────────────────────────

    private void promptInt(Player player, String name, Consumer<Integer> callback) {
        player.sendMessage(Lang.msg("msg.admin.prompt_int", player, NamedTextColor.YELLOW, name));
        pendingInput.put(player.getUniqueId(), input -> {
            if (isCancel(input)) { openMenu(player); return; }
            try { callback.accept(Integer.parseInt(input.trim())); }
            catch (NumberFormatException e) {
                player.sendMessage(Lang.msg("msg.admin.bad_number", player, NamedTextColor.RED, input));
                openMenu(player);
            }
        });
    }

    private void promptDouble(Player player, String name, Consumer<Double> callback) {
        player.sendMessage(Lang.msg("msg.admin.prompt_double", player, NamedTextColor.YELLOW, name));
        pendingInput.put(player.getUniqueId(), input -> {
            if (isCancel(input)) { openMenu(player); return; }
            try { callback.accept(Double.parseDouble(input.trim().replace(',', '.'))); }
            catch (NumberFormatException e) {
                player.sendMessage(Lang.msg("msg.admin.bad_number", player, NamedTextColor.RED, input));
                openMenu(player);
            }
        });
    }

    private boolean isCancel(String s) {
        return s.equalsIgnoreCase("отмена") || s.equalsIgnoreCase("cancel");
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Consumer<String> handler = pendingInput.remove(event.getPlayer().getUniqueId());
        if (handler == null) return;
        event.setCancelled(true);
        String msg = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> handler.accept(msg));
    }

    // ── CraftType read / write via reflection ─────────────────────────────────

    private Set<Material> getBlockSet(CraftType type, Tab tab) {
        NamespacedKey key = blockKey(tab);
        if (key == null) return EnumSet.noneOf(Material.class);
        try {
            EnumSet<Material> s = type.getMaterialSetProperty(key);
            return s != null ? s : EnumSet.noneOf(Material.class);
        } catch (Exception e) { return EnumSet.noneOf(Material.class); }
    }

    private NamespacedKey blockKey(Tab tab) {
        return switch (tab) {
            case ALLOWED     -> CraftType.ALLOWED_BLOCKS;
            case FORBIDDEN   -> CraftType.FORBIDDEN_BLOCKS;
            case PASSTHROUGH -> CraftType.PASSTHROUGH_BLOCKS;
            default          -> null;
        };
    }

    private void addBlock(CraftType type, Tab tab, Material mat) {
        modifyMaterialSet(type, blockKey(tab), true, mat);
    }

    private void removeBlock(CraftType type, Tab tab, Material mat) {
        modifyMaterialSet(type, blockKey(tab), false, mat);
    }

    /**
     * Finds the Property entry in the map whose getNamespacedKey() equals target,
     * then adds/removes the given Material.
     */
    @SuppressWarnings("unchecked")
    private void modifyMaterialSet(CraftType type, NamespacedKey target, boolean add, Material mat) {
        if (target == null) return;
        try {
            Field f = declaredField(CraftType.class, "materialSetPropertyMap");
            Map<Object, Set<Material>> map = (Map<Object, Set<Material>>) f.get(type);
            Object pk = findPropKey(map, target);
            if (pk == null) return;
            Set<Material> cur = map.get(pk);
            if (cur == null) {
                if (add) map.put(pk, EnumSet.of(mat));
                return;
            }
            try {
                if (add) cur.add(mat); else cur.remove(mat);
            } catch (UnsupportedOperationException e) {
                Set<Material> ns = cur.isEmpty() ? EnumSet.noneOf(Material.class) : EnumSet.copyOf(cur);
                if (add) ns.add(mat); else ns.remove(mat);
                map.put(pk, ns);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftAdmin] modifyMaterialSet: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private void modifyInt(CraftType type, NamespacedKey target, int value) {
        try {
            Field f = declaredField(CraftType.class, "intPropertyMap");
            Map<Object, Integer> map = (Map<Object, Integer>) f.get(type);
            Object pk = findPropKey(map, target);
            if (pk != null) map.put(pk, value);
            else plugin.getLogger().warning("[CraftAdmin] modifyInt: key not found: " + target);
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftAdmin] modifyInt: " + e);
        }
    }

    /** Tries doublePropertyMap first, falls back to floatPropertyMap. */
    @SuppressWarnings("unchecked")
    private void modifyDoubleOrFloat(CraftType type, NamespacedKey target, double value) {
        try {
            Field fd = declaredField(CraftType.class, "doublePropertyMap");
            Map<Object, Double> dm = (Map<Object, Double>) fd.get(type);
            Object pk = findPropKey(dm, target);
            if (pk != null) { dm.put(pk, value); return; }
        } catch (Exception ignored) {}
        try {
            Field ff = declaredField(CraftType.class, "floatPropertyMap");
            Map<Object, Float> fm = (Map<Object, Float>) ff.get(type);
            Object pk = findPropKey(fm, target);
            if (pk != null) fm.put(pk, (float) value);
            else plugin.getLogger().warning("[CraftAdmin] modifyDouble: key not found: " + target);
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftAdmin] modifyDouble: " + e);
        }
    }

    private int safeInt(CraftType type, NamespacedKey key) {
        try { return type.getIntProperty(key); } catch (Exception e) { return -1; }
    }

    private double safeDouble(CraftType type, NamespacedKey key) {
        try { return type.getDoubleProperty(key); } catch (Exception ignored) {}
        try { return type.getFloatProperty(key); } catch (Exception ignored) {}
        return -1;
    }

    /** Find the map key whose getNamespacedKey() equals target. */
    private Object findPropKey(Map<Object, ?> map, NamespacedKey target) {
        for (Object k : map.keySet()) {
            try {
                NamespacedKey nk = (NamespacedKey) k.getClass()
                        .getMethod("getNamespacedKey").invoke(k);
                if (target.equals(nk)) return k;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Field declaredField(Class<?> cls, String name)
            throws NoSuchFieldException, IllegalAccessException {
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

    private void saveBlockSet(YamlConfiguration yaml, CraftType type,
                              NamespacedKey key, String keyCC, String keySC) {
        try {
            EnumSet<Material> set = type.getMaterialSetProperty(key);
            if (set == null) return;
            List<String> names = set.stream().map(m -> m.name().toLowerCase()).sorted().toList();
            yaml.set(resolveKeyPair(yaml, keySC, keyCC), names);
        } catch (Exception ignored) {}
    }

    /** Returns camelCase key if it exists in yaml, else snake_case key if it exists, else camelCase. */
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

    /** snake_case → camelCase */
    private String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { up = true; }
            else { sb.append(up ? Character.toUpperCase(c) : c); up = false; }
        }
        return sb.toString();
    }

    private File findCraftTypeFile(CraftType type) {
        Plugin mc = Bukkit.getPluginManager().getPlugin("Movecraft");
        if (mc == null) return null;

        // Try the private fileKey field
        String fileKey = null;
        try {
            Field f = declaredField(CraftType.class, "fileKey");
            Object val = f.get(type);
            if (val instanceof String s) fileKey = s;
        } catch (Exception ignored) {}

        if (fileKey != null) {
            for (String dir : new String[]{"types", "craft", "craftTypes", ""}) {
                for (String ext : new String[]{"", ".craft", ".yml"}) {
                    File c = dir.isEmpty()
                            ? new File(mc.getDataFolder(), fileKey + ext)
                            : new File(mc.getDataFolder(), dir + "/" + fileKey + ext);
                    if (c.exists()) return c;
                }
            }
        }

        // Fallback: search by type name
        String typeName = getTypeName(type);
        for (String dir : new String[]{"types", "craft", "craftTypes"}) {
            File d = new File(mc.getDataFolder(), dir);
            if (!d.exists()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().replaceFirst("\\.[^.]+$", "");
                if (n.equalsIgnoreCase(typeName)) return f;
            }
        }
        return null;
    }

    private String getTypeName(CraftType type) {
        try {
            String n = type.getStringProperty(CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // ── Inventory events ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        Consumer<Player>[] actions = menuActions.get(player.getUniqueId());
        if (actions == null || actions[slot] == null) return;
        Consumer<Player> action = actions[slot];
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> action.accept(player));
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder)) return;
        menuActions.remove(event.getPlayer().getUniqueId());
    }

    // ── Item builders ─────────────────────────────────────────────────────────

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
        ItemStack is = mat.isItem() ? new ItemStack(mat) : new ItemStack(Material.BARRIER);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(mat.name().toLowerCase())
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Нажмите — удалить из списка")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack addBlockItem() {
        ItemStack is = new ItemStack(Material.LIME_DYE);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text("+ Добавить блок из руки")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Возьмите блок в основную руку")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("и нажмите этот слот")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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

    private ItemStack intItem(String name, int value, Material mat) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(name + ": " + (value < 0 ? "(не задано)" : value))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Нажмите — изменить")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack doubleItem(String name, double value, Material mat) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        String display = value < 0 ? "(не задано)" : String.format("%.2f", value);
        m.displayName(Component.text(name + ": " + display)
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Нажмите — изменить")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    @SuppressWarnings("unchecked")
    private void setSlot(Inventory inv, Consumer<Player>[] actions, int slot,
                         ItemStack item, Consumer<Player> action) {
        inv.setItem(slot, item);
        actions[slot] = action;
    }

    // ── InventoryHolder ───────────────────────────────────────────────────────

    static class AdminMenuHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
