package me.missaria.movecraftcannons;

import at.pavlov.cannons.API.CannonsAPI;
import at.pavlov.cannons.Cannons;
import at.pavlov.cannons.Enum.InteractAction;
import at.pavlov.cannons.cannon.Cannon;
import at.pavlov.cannons.cannon.CannonManager;
import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import org.bukkit.Location;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.configuration.file.YamlConfiguration;

public class ShipMenuListener implements Listener {

    private final MovecraftCannonsPlugin plugin;

    // Per-player: slot → action to execute on click
    private final Map<UUID, Consumer<Player>[]> menuActions = new ConcurrentHashMap<>();

    public ShipMenuListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Open menu: right-click BOOK while piloting ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBookClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var item = event.getItem();
        if (item == null || item.getType() != Material.BOOK) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        event.setCancelled(true);
        openMenu(player, craft);
    }

    // ── /shipmenu command entry point ──────────────────────────────────────────

    public boolean onCommand(Player player) {
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) {
            player.sendMessage(Component.text("Вы не управляете транспортом.")
                    .color(NamedTextColor.RED));
            return true;
        }
        openMenu(player, craft);
        return true;
    }

    // ── Build and open the inventory ───────────────────────────────────────────

    /*
     * Layout (27 slots = 3×9):
     *
     *  [RotL] [  N  ] [RotR]  [ ] [Release] [Reload] [FireAll] [Repair] [  ]
     *  [ W  ] [Stop ] [  E ]  [ ] [TypeA  ] [TypeB ] [TypeC  ] [TypeD ] [TypeE]
     *  [  ↑ ] [  S  ] [  ↓ ]  [ ] [TypeF  ] [TypeG ] [TypeH  ] [TypeI ] [TypeJ]
     *
     * Slots 0-2:   Rotate-L / Cruise-N / Rotate-R
     * Slot  4:     Release
     * Slot  5:     Reload all cannons from chests
     * Slot  6:     Fire all cannons
     * Slot  7:     Repair (via Repair: sign on craft)
     * Slot  9:     Cruise-W
     * Slot  10:    Cruise Stop
     * Slot  11:    Cruise-E
     * Slots 13-17: Cannon type buttons (row 1) — up to 5 types
     * Slot  18:    Cruise Up
     * Slot  19:    Cruise S
     * Slot  20:    Cruise Down
     * Slots 22-26: Cannon type buttons (row 2) — types 6-10
     */
    @SuppressWarnings("unchecked")
    private void openMenu(Player player, PlayerCraft craft) {
        ShipMenuHolder holder = new ShipMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("⚓ " + craftTitle(craft)).color(NamedTextColor.DARK_AQUA));
        holder.setInventory(inv);

        Consumer<Player>[] actions = new Consumer[27];
        CruiseDirection curDir = craft.getCruising() ? craft.getCruiseDirection() : CruiseDirection.NONE;

        // Relative directions based on pilot's current yaw
        CruiseDirection[] rel = relDirs(player.getLocation().getYaw());
        CruiseDirection fwd  = rel[0], bwd = rel[1], lft = rel[2], rgt = rel[3];

        // Row 0: RotL / Forward / RotR  |  _ / Release / _
        setSlot(inv, actions, 0,
                item(Material.SPECTRAL_ARROW, "§e↺ Поворот влево",  "Повернуть против часовой стрелки"),
                p -> rotateCraft(p, craft, MovecraftRotation.ANTICLOCKWISE));

        setSlot(inv, actions, 1, relCruiseItem(craft, fwd, curDir, "Вперёд"),
                p -> setCruise(p, craft, fwd));

        setSlot(inv, actions, 2,
                item(Material.SPECTRAL_ARROW, "§e↻ Поворот вправо", "Повернуть по часовой стрелке"),
                p -> rotateCraft(p, craft, MovecraftRotation.CLOCKWISE));

        Block releaseSign = findSign(craft, "Release");
        setSlot(inv, actions, 4,
                item(Material.RED_BED, "§4Покинуть судно", "Остановить крейсер и покинуть транспорт"),
                p -> doRelease(p, craft, releaseSign));

        setSlot(inv, actions, 5,
                item(Material.GUNPOWDER, "§eЗарядить пушки", "Зарядить все пушки корабля из сундуков"),
                p -> loadAllCannons(p, craft));

        setSlot(inv, actions, 6,
                item(Material.FIRE_CHARGE, "§cОгонь!", "Выстрелить из всех готовых пушек"),
                p -> fireAllCannons(p, craft));

        boolean hasBlueprint = new File(plugin.getDataFolder(),
                "blueprints/" + player.getUniqueId() + ".yml").exists();
        setSlot(inv, actions, 7,
                hasBlueprint
                        ? item(Material.ANVIL,      "§aРемонт",          "Восстановить повреждённые блоки из сундуков")
                        : disabledItem("Нет чертежа — сначала сохраните"),
                hasBlueprint ? p -> repairFromBlueprint(p, craft) : null);

        setSlot(inv, actions, 8,
                item(Material.WRITABLE_BOOK, "§eСохранить чертёж", "Запомнить текущее состояние корабля для ремонта"),
                p -> saveBlueprint(p, craft));

        // Row 1: Left / Stop / Right
        setSlot(inv, actions, 9,  relCruiseItem(craft, lft, curDir, "Влево"),
                p -> setCruise(p, craft, lft));

        setSlot(inv, actions, 10,
                item(Material.BARRIER, "§cОстановить крейсер", "Отключить крейсерский режим"),
                p -> stopCruise(craft));

        setSlot(inv, actions, 11, relCruiseItem(craft, rgt, curDir, "Вправо"),
                p -> setCruise(p, craft, rgt));

        // Row 2: Up / Backward / Down
        boolean canVertical = allowsVertical(craft);
        if (canVertical) {
            setSlot(inv, actions, 18, relCruiseItem(craft, CruiseDirection.UP,   curDir, "Вверх"),
                    p -> setCruise(p, craft, CruiseDirection.UP));
            setSlot(inv, actions, 20, relCruiseItem(craft, CruiseDirection.DOWN, curDir, "Вниз"),
                    p -> setCruise(p, craft, CruiseDirection.DOWN));
        } else {
            setSlot(inv, actions, 18, disabledItem("Вверх недоступно"), null);
            setSlot(inv, actions, 20, disabledItem("Вниз недоступно"),  null);
        }

        setSlot(inv, actions, 19, relCruiseItem(craft, bwd, curDir, "Назад"),
                p -> setCruise(p, craft, bwd));

        // Cannon type buttons: group by designID, fill slots 12-17 then 21-26
        List<Cannon> allCannons = findCannonsOnCraft(craft);
        Map<String, List<Cannon>> byType = new LinkedHashMap<>();
        for (Cannon cannon : allCannons) {
            try {
                String id = cannon.getCannonDesign().getDesignID();
                byType.computeIfAbsent(id, k -> new ArrayList<>()).add(cannon);
            } catch (Exception ignored) {}
        }
        int[] typeSlots = {13, 14, 15, 16, 17, 22, 23, 24, 25, 26};
        int si = 0;
        for (Map.Entry<String, List<Cannon>> entry : byType.entrySet()) {
            if (si >= typeSlots.length) break;
            String designId = entry.getKey();
            List<Cannon> group = new ArrayList<>(entry.getValue());
            long ready = group.stream().filter(Cannon::isReadyToFire).count();
            setSlot(inv, actions, typeSlots[si],
                    cannonTypeItem(designId, group.size(), (int) ready),
                    p -> fireCannonGroup(p, craft, group));
            si++;
        }

        menuActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
    }

    // ── Handle clicks ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27) return;

        Consumer<Player>[] actions = menuActions.get(player.getUniqueId());
        if (actions == null || slot >= actions.length || actions[slot] == null) return;

        Consumer<Player> action = actions[slot];
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> action.accept(player));
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipMenuHolder)) return;
        menuActions.remove(event.getPlayer().getUniqueId());
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void setCruise(Player player, PlayerCraft craft, CruiseDirection dir) {
        if (craft.getCruising() && craft.getCruiseDirection() == dir) {
            craft.setCruising(false);
        } else {
            craft.setCruiseDirection(dir);
            craft.setCruising(true);
        }
    }

    private void stopCruise(PlayerCraft craft) {
        craft.setCruising(false);
    }

    private void rotateCraft(Player player, PlayerCraft craft, MovecraftRotation rotation) {
        HitBox hb = craft.getHitBox();
        craft.rotate(rotation, new MovecraftLocation(
                (hb.getMinX() + hb.getMaxX()) / 2,
                (hb.getMinY() + hb.getMaxY()) / 2,
                (hb.getMinZ() + hb.getMaxZ()) / 2
        ));
    }

    private void doRelease(Player player, PlayerCraft craft, Block releaseSign) {
        // Always stop cruise first
        craft.setCruising(false);

        if (releaseSign != null) {
            simulateClick(player, releaseSign);
        } else {
            net.countercraft.movecraft.events.CraftReleaseEvent e =
                    new net.countercraft.movecraft.events.CraftReleaseEvent(
                            craft,
                            net.countercraft.movecraft.events.CraftReleaseEvent.Reason.PLAYER);
            Bukkit.getPluginManager().callEvent(e);
            if (!e.isCancelled()) CraftManager.getInstance().release(craft, e.getReason(), false);
        }
    }

    private void simulateClick(Player player, Block signBlock) {
        BlockFace face = getSignFace(signBlock);
        PlayerInteractEvent fake = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(),
                signBlock, face);
        Bukkit.getPluginManager().callEvent(fake);
    }

    private boolean allowsVertical(PlayerCraft craft) {
        try {
            Boolean v = (Boolean) craft.getType().getBoolProperty(
                    net.countercraft.movecraft.craft.type.CraftType.ALLOW_VERTICAL_MOVEMENT);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Cannon name translations ──────────────────────────────────────────────

    private static final Map<String, String> CANNON_NAMES = new java.util.HashMap<>();
    static {
        CANNON_NAMES.put("cannon",    "Стандарт");
        CANNON_NAMES.put("standard",  "Стандарт");
        CANNON_NAMES.put("carronade", "Карронада");
        CANNON_NAMES.put("mortar",    "Мортира");
    }

    private String cannonDisplayName(String designId) {
        String ru = CANNON_NAMES.get(designId.toLowerCase());
        return ru != null ? ru : designId;
    }

    // ── Cannon actions ────────────────────────────────────────────────────────

    private List<Cannon> findCannonsOnCraft(PlayerCraft craft) {
        HitBox hitBox = craft.getHitBox();
        double cx = (hitBox.getMinX() + hitBox.getMaxX()) / 2.0;
        double cy = (hitBox.getMinY() + hitBox.getMaxY()) / 2.0;
        double cz = (hitBox.getMinZ() + hitBox.getMaxZ()) / 2.0;
        // getCannonsInBox divides dx/dy/dz by 2 internally — pass full width + 2 margin
        double dx = (hitBox.getMaxX() - hitBox.getMinX()) + 2;
        double dy = (hitBox.getMaxY() - hitBox.getMinY()) + 2;
        double dz = (hitBox.getMaxZ() - hitBox.getMinZ()) + 2;
        Location center = new Location(craft.getWorld(), cx, cy, cz);
        try {
            HashSet<Cannon> found = CannonManager.getCannonsInBox(center, dx, dy, dz);
            return found != null ? new ArrayList<>(found) : List.of();
        } catch (Exception e) {
            plugin.getLogger().warning("Error finding cannons on craft: " + e.getMessage());
            return List.of();
        }
    }

    private CannonsAPI getCannonsAPI() {
        Cannons c = (Cannons) Bukkit.getPluginManager().getPlugin("Cannons");
        return c != null ? c.getCannonsAPI() : null;
    }

    /** Collect all InventoryHolder blocks (chests, barrels…) in the craft's hitbox. */
    private List<org.bukkit.inventory.Inventory> findShipChests(PlayerCraft craft) {
        List<org.bukkit.inventory.Inventory> result = new ArrayList<>();
        World world = craft.getWorld();
        for (MovecraftLocation loc : craft.getHitBox()) {
            Block b = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (b.getState() instanceof InventoryHolder h) result.add(h.getInventory());
        }
        return result;
    }

    /**
     * Take ammo from ship chests. Returns how many cannons can actually fire
     * (may be less than requested if chests don't have enough ammo).
     */
    private int consumeAmmoFromChests(Player player, PlayerCraft craft, int shots) {
        String matName = plugin.getConfig().getString("ammo.material", "GUNPOWDER");
        if (matName == null || matName.isEmpty()) return shots;
        Material mat = Material.matchMaterial(matName);
        if (mat == null) return shots;
        int amountPer = plugin.getConfig().getInt("ammo.amount", 1);

        List<org.bukkit.inventory.Inventory> chests = findShipChests(craft);
        int have = chests.stream()
                .flatMap(inv -> inv.all(mat).values().stream())
                .mapToInt(ItemStack::getAmount).sum();

        int canFire = Math.min(shots, have / amountPer);
        if (canFire <= 0) {
            player.sendMessage(Component.text(
                    "В сундуках нет боеприпасов (" + matName + " × " + amountPer + "/выстрел).")
                    .color(NamedTextColor.RED));
            return 0;
        }
        if (canFire < shots) {
            player.sendMessage(Component.text(
                    "Боеприпасов хватит на " + canFire + " из " + shots + " пушек.")
                    .color(NamedTextColor.YELLOW));
        }
        // Remove from chests in order
        int toRemove = canFire * amountPer;
        for (org.bukkit.inventory.Inventory inv : chests) {
            if (toRemove <= 0) break;
            var leftover = inv.removeItem(new ItemStack(mat, toRemove));
            toRemove = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        }
        return canFire;
    }

    private void doFire(CannonsAPI api, Player player, List<Cannon> cannons) {
        for (Cannon cannon : cannons) {
            api.playerFiring(cannon, player, InteractAction.fireRightClickTigger);
        }
    }

    private void loadAllCannons(Player player, PlayerCraft craft) {
        List<Cannon> cannons = findCannonsOnCraft(craft);
        if (cannons.isEmpty()) {
            player.sendMessage(Component.text("На этом судне нет пушек.").color(NamedTextColor.YELLOW));
            return;
        }
        long beforeReady = cannons.stream().filter(Cannon::isReadyToFire).count();
        for (Cannon cannon : cannons) cannon.reloadFromChests(player.getUniqueId(), true);
        long afterReady = cannons.stream().filter(Cannon::isReadyToFire).count();
        long newlyLoaded = Math.max(0, afterReady - beforeReady);

        if (newlyLoaded > 0) {
            player.sendMessage(Component.text(
                    "Заряжено " + newlyLoaded + " пушек. Готово: " + afterReady + "/" + cannons.size())
                    .color(NamedTextColor.GREEN));
        } else if (afterReady > 0) {
            player.sendMessage(Component.text(
                    "Пушки уже заряжены. Готово: " + afterReady + "/" + cannons.size())
                    .color(NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text(
                    "Не удалось зарядить (нет боеприпасов в сундуках?).")
                    .color(NamedTextColor.RED));
        }
    }

    private void fireCannonGroup(Player player, PlayerCraft craft, List<Cannon> group) {
        CannonsAPI api = getCannonsAPI();
        if (api == null) return;
        String label = group.isEmpty() ? "?" : cannonDisplayName(group.get(0).getCannonDesign().getDesignID());
        List<Cannon> ready = group.stream().filter(Cannon::isReadyToFire).toList();
        if (ready.isEmpty()) {
            player.sendMessage(Component.text("Пушки «" + label + "» не готовы.").color(NamedTextColor.YELLOW));
            return;
        }
        int canFire = consumeAmmoFromChests(player, craft, ready.size());
        if (canFire <= 0) return;
        doFire(api, player, ready.subList(0, canFire));
        player.sendMessage(Component.text("Выстрелено из " + canFire + " пушек «" + label + "»!").color(NamedTextColor.GREEN));
    }

    private void fireAllCannons(Player player, PlayerCraft craft) {
        CannonsAPI api = getCannonsAPI();
        if (api == null) {
            player.sendMessage(Component.text("Плагин Cannons недоступен.").color(NamedTextColor.RED));
            return;
        }
        List<Cannon> all = findCannonsOnCraft(craft);
        if (all.isEmpty()) {
            player.sendMessage(Component.text("На этом судне нет пушек.").color(NamedTextColor.YELLOW));
            return;
        }
        List<Cannon> ready = all.stream().filter(Cannon::isReadyToFire).toList();
        int notReady = all.size() - ready.size();
        if (ready.isEmpty()) {
            player.sendMessage(Component.text("Нет готовых к стрельбе пушек.").color(NamedTextColor.YELLOW));
            return;
        }
        int canFire = consumeAmmoFromChests(player, craft, ready.size());
        if (canFire <= 0) return;
        doFire(api, player, ready.subList(0, canFire));
        player.sendMessage(Component.text("Выстрелено из " + canFire + " пушек!").color(NamedTextColor.GREEN));
        if (notReady > 0)
            player.sendMessage(Component.text(notReady + " пушек не готовы.").color(NamedTextColor.YELLOW));
    }

    // ── Blueprint repair ──────────────────────────────────────────────────────

    private File blueprintFile(Player player) {
        return new File(plugin.getDataFolder(), "blueprints/" + player.getUniqueId() + ".yml");
    }

    private void saveBlueprint(Player player, PlayerCraft craft) {
        HitBox hitBox = craft.getHitBox();
        World world = craft.getWorld();
        int ox = hitBox.getMinX(), oy = hitBox.getMinY(), oz = hitBox.getMinZ();

        YamlConfiguration yaml = new YamlConfiguration();
        int count = 0;
        for (MovecraftLocation loc : hitBox) {
            Block b = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (b.getType().isAir()) continue;
            String key = (loc.getX() - ox) + "," + (loc.getY() - oy) + "," + (loc.getZ() - oz);
            yaml.set(key, b.getType().name());
            count++;
        }
        try {
            File f = blueprintFile(player);
            f.getParentFile().mkdirs();
            yaml.save(f);
            player.sendMessage(Component.text("Чертёж сохранён: " + count + " блоков.")
                    .color(NamedTextColor.GREEN));
        } catch (IOException e) {
            player.sendMessage(Component.text("Ошибка сохранения: " + e.getMessage())
                    .color(NamedTextColor.RED));
        }
    }

    private void repairFromBlueprint(Player player, PlayerCraft craft) {
        File f = blueprintFile(player);
        if (!f.exists()) {
            player.sendMessage(Component.text("Чертёж не найден. Сохраните его кнопкой «Сохранить чертёж».")
                    .color(NamedTextColor.RED));
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        HitBox hitBox = craft.getHitBox();
        World world = craft.getWorld();
        int ox = hitBox.getMinX(), oy = hitBox.getMinY(), oz = hitBox.getMinZ();

        List<org.bukkit.inventory.Inventory> chests = findShipChests(craft);
        int repaired = 0, missing = 0;

        for (String key : yaml.getKeys(false)) {
            String[] parts = key.split(",");
            int rx = Integer.parseInt(parts[0]);
            int ry = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            Material expected = Material.matchMaterial(yaml.getString(key, ""));
            if (expected == null || expected.isAir()) continue;

            Block block = world.getBlockAt(ox + rx, oy + ry, oz + rz);
            if (block.getType() == expected) continue;

            if (takeFromChests(chests, expected, 1)) {
                block.setType(expected);
                repaired++;
            } else {
                missing++;
            }
        }

        if (repaired > 0)
            player.sendMessage(Component.text("Отремонтировано: " + repaired + " блоков.").color(NamedTextColor.GREEN));
        if (missing > 0)
            player.sendMessage(Component.text("Не хватает материалов для " + missing + " блоков.").color(NamedTextColor.YELLOW));
        if (repaired == 0 && missing == 0)
            player.sendMessage(Component.text("Корабль не повреждён.").color(NamedTextColor.AQUA));
    }

    /** Remove {@code amount} of {@code mat} from the given chests in order. Returns true if all were removed. */
    private boolean takeFromChests(List<org.bukkit.inventory.Inventory> chests, Material mat, int amount) {
        int left = amount;
        for (org.bukkit.inventory.Inventory inv : chests) {
            if (left <= 0) break;
            var result = inv.removeItem(new ItemStack(mat, left));
            left = result.values().stream().mapToInt(ItemStack::getAmount).sum();
        }
        return left == 0;
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack cannonTypeItem(String designId, int total, int ready) {
        String label = cannonDisplayName(designId);
        ItemStack is;
        ItemMeta m;
        if (ready > 0) {
            is = new ItemStack(Material.TNT);
            m = is.getItemMeta();
            m.displayName(Component.text(label).color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            is = new ItemStack(Material.BARRIER);
            m = is.getItemMeta();
            m.displayName(Component.text("🧨 " + label).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        m.lore(List.of(
                Component.text("Пушек: " + total + "  Готово: " + ready)
                        .color(ready > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Нажмите — выстрелить")
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack disabledItem(String label) {
        ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(label).color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Недоступно для этого транспорта")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    /** Returns [fwd, bwd, lft, rgt] CruiseDirections relative to player yaw. */
    private CruiseDirection[] relDirs(float rawYaw) {
        double y = ((rawYaw % 360) + 360) % 360;
        int snapped = ((int) Math.round(y / 90.0) * 90) % 360;
        // yaw 0→S, 90→W, 180→N, 270→E
        return switch (snapped) {
            case 90  -> new CruiseDirection[]{ CruiseDirection.WEST,  CruiseDirection.EAST,  CruiseDirection.SOUTH, CruiseDirection.NORTH };
            case 180 -> new CruiseDirection[]{ CruiseDirection.NORTH, CruiseDirection.SOUTH, CruiseDirection.WEST,  CruiseDirection.EAST  };
            case 270 -> new CruiseDirection[]{ CruiseDirection.EAST,  CruiseDirection.WEST,  CruiseDirection.NORTH, CruiseDirection.SOUTH };
            default  -> new CruiseDirection[]{ CruiseDirection.SOUTH, CruiseDirection.NORTH, CruiseDirection.EAST,  CruiseDirection.WEST  };
        };
    }

    private ItemStack relCruiseItem(PlayerCraft craft, CruiseDirection dir, CruiseDirection active, String relLabel) {
        boolean on = craft.getCruising() && active == dir;
        Material mat = switch (dir) {
            case UP   -> Material.FEATHER;
            case DOWN -> Material.POINTED_DRIPSTONE;
            default   -> Material.ARROW;
        };
        String prefix = on ? "§a▶ " : "§7";
        String lore   = on ? "§aКрейсер АКТИВЕН" : "§7Нажмите для крейсера";
        return item(mat, prefix + relLabel, lore);
    }

    private ItemStack item(Material mat, String name, String... lorelines) {
        ItemStack is = new ItemStack(mat);
        ItemMeta  m  = is.getItemMeta();
        m.displayName(Component.text(stripFormat(name))
                .color(name.contains("§a") ? NamedTextColor.GREEN
                     : name.contains("§c") || name.contains("§4") ? NamedTextColor.RED
                     : name.contains("§b") ? NamedTextColor.AQUA
                     : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        if (lorelines.length > 0) {
            m.lore(Arrays.stream(lorelines)
                    .map(l -> (Component) Component.text(l).color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        is.setItemMeta(m);
        return is;
    }

    private String stripFormat(String s) {
        return s.replaceAll("§.", "");
    }

    private void setSlot(Inventory inv, Consumer<Player>[] actions, int slot,
                         ItemStack item, Consumer<Player> action) {
        inv.setItem(slot, item);
        actions[slot] = action;
    }

    // ── Sign scanning ─────────────────────────────────────────────────────────

    private Block findSign(PlayerCraft craft, String line0match) {
        var world = craft.getWorld();
        for (var loc : craft.getHitBox()) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (!(block.getState() instanceof Sign sign)) continue;
            String l0 = stripColor(getLine(sign, 0)).trim();
            if (l0.equalsIgnoreCase(line0match)) return block;
        }
        return null;
    }

    private String getLine(Sign sign, int index) {
        try { return sign.getSide(Side.FRONT).getLine(index); }
        catch (Exception e) { return sign.getLine(index); }
    }

    @SuppressWarnings("deprecation")
    private String stripColor(String s) {
        return org.bukkit.ChatColor.stripColor(s);
    }

    private BlockFace getSignFace(Block block) {
        try {
            var data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.type.WallSign ws) return ws.getFacing();
        } catch (Exception ignored) {}
        return BlockFace.SOUTH;
    }

    private String craftTitle(PlayerCraft craft) {
        try { String n = craft.getName(); if (n != null && !n.isBlank()) return n; }
        catch (Exception ignored) {}
        try {
            String n = craft.getType().getStringProperty(
                    net.countercraft.movecraft.craft.type.CraftType.NAME);
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
        return "Транспорт";
    }

    // ── InventoryHolder ───────────────────────────────────────────────────────

    static class ShipMenuHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
