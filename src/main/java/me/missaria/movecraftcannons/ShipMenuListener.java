package me.missaria.movecraftcannons;

import at.pavlov.cannons.API.CannonsAPI;
import at.pavlov.cannons.Cannons;
import at.pavlov.cannons.Enum.InteractAction;
import at.pavlov.cannons.cannon.Cannon;
import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;

import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.events.CraftRotateEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

public class ShipMenuListener implements Listener {

    private enum SailGear {
        FULL(1), HALF(2), NONE(0);
        final int divisor;
        SailGear(int d) { divisor = d; }
        int apply(int baseBps) { return divisor == 0 ? 0 : Math.max(1, baseBps / divisor); }
    }

    private final MovecraftCannonsPlugin plugin;
    private final WindManager windManager;
    private final AimListener aimListener;
    private final HealthBarListener healthBarListener;
    private TurretListener turretListener;

    // Per-player: slot → action to execute on click
    private final Map<UUID, Consumer<Player>[]> menuActions   = new ConcurrentHashMap<>();
    // Slots that execute action WITHOUT closing the menu (turret rotation buttons)
    private final Map<UUID, java.util.Set<Integer>> noCloseSlots = new ConcurrentHashMap<>();
    // Running repair tasks per player
    private final Map<UUID, BukkitTask>         activeRepairs = new ConcurrentHashMap<>();
    // Sail gear per player (only for sail ships)
    private final Map<UUID, SailGear>           sailGears     = new ConcurrentHashMap<>();
    // Manual cruise direction (used when gear is HALF or NONE)
    private final Map<UUID, CruiseDirection>    reducedDirs   = new ConcurrentHashMap<>();
    // Base speed cached while craft is still cruising (before we stop it for reduced gears)
    private final Map<UUID, Integer>            baseBpsCache  = new ConcurrentHashMap<>();
    // Half-speed lateral (L/R) cruise for FULL-gear ships (separate from sail reducedDirs)
    private final Map<UUID, CruiseDirection>    lateralCruiseDirs = new ConcurrentHashMap<>();

    public void setTurretListener(TurretListener tl) { this.turretListener = tl; }

    public ShipMenuListener(MovecraftCannonsPlugin plugin, WindManager windManager,
                            AimListener aimListener, HealthBarListener healthBarListener) {
        this.plugin = plugin;
        this.windManager = windManager;
        this.aimListener = aimListener;
        this.healthBarListener = healthBarListener;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickManualCruise, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::enforceAnchorMode, 1L, 1L);
    }

    // ── Open menu: right-click BOOK while piloting ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
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
            player.sendMessage(Lang.msg("msg.no_craft", player, NamedTextColor.RED));
            return true;
        }
        if (plugin.isDebug())
            plugin.getLogger().info("[menu] player=" + player.getName()
                    + " locale=" + player.getLocale()
                    + " lang=" + Lang.langOf(player));
        openMenu(player, craft);
        return true;
    }

    // ── Build and open the inventory ───────────────────────────────────────────

    /*
     * Layout (27 slots = 3×9):
     *
     *  [RotL] [ Fwd ] [RotR]  [  ] [Release] [Reload] [FireAll] [Repair] [Blueprint]
     *  [  W ] [Stop ] [  E ]  [  ] [Type1  ] [Type2 ] [Type3  ] [Type4 ] [Type5    ]
     *  [  ↑ ] [Корма] [  ↓ ]  [  ] [↑ Нос  ] [↓ Корм] [→ Пр.б.] [← Лев.б][      ]
     *
     * Slots 0-2:   Rotate-L / Cruise-fwd / Rotate-R
     * Slot  4:     Release
     * Slot  5:     Reload all cannons
     * Slot  6:     Fire all cannons
     * Slot  7:     Repair / Cancel repair
     * Slot  8:     Save blueprint
     * Slots 9-11:  Cruise left / Stop / Cruise right
     * Slots 13-17: Cannon types (row 1)
     * Slots 18-20: Cruise up / Cruise back / Cruise down
     * Slot  22:    Fire port (left broadside)
     * Slot  23:    Fire starboard (right broadside)
     * Slot  24:    Fire bow (forward cannons)
     * Slot  25:    Fire stern (rear cannons)
     */
    @SuppressWarnings("unchecked")
    private void openMenu(Player player, PlayerCraft craft) {
        ShipMenuHolder holder = new ShipMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("⚓ " + craftTitle(craft)).color(NamedTextColor.DARK_AQUA));
        holder.setInventory(inv);

        Consumer<Player>[] actions = new Consumer[54];
        boolean isSail = windManager.isSailShip(craft);
        UUID uid = player.getUniqueId();
        SailGear gear = isSail ? sailGears.getOrDefault(uid, SailGear.FULL) : SailGear.FULL;
        double woolPct = isSail ? healthBarListener.getSailWoolRawPct(craft) : 100.0;
        // anchorMode: sails intentionally down (enough wool to raise them but chose NONE)
        boolean anchorMode = isSail && gear == SailGear.NONE && woolPct >= 30.0;
        CruiseDirection curDir;
        if (gear == SailGear.FULL) {
            CruiseDirection lat = lateralCruiseDirs.get(uid);
            curDir = craft.getCruising() ? craft.getCruiseDirection()
                    : (lat != null ? lat : CruiseDirection.NONE);
        } else {
            curDir = reducedDirs.getOrDefault(uid, CruiseDirection.NONE);
        }

        // Directions relative to player yaw
        CruiseDirection[] rel = relDirs(player.getLocation().getYaw());
        CruiseDirection fwd = rel[0], bwd = rel[1], lft = rel[2], rgt = rel[3];

        // Row 0: RotL / Forward / RotR  |  _ / Release / Reload / FireAll / Repair / Blueprint
        setSlot(inv, actions, 0,
                item(Material.SPECTRAL_ARROW,
                        Lang.get("menu.rotate_left.name", player),
                        Lang.get("menu.rotate_left.lore", player)),
                p -> rotateCraft(p, craft, MovecraftRotation.ANTICLOCKWISE));

        setSlot(inv, actions, 1,
                anchorMode ? disabledItem(player, cardinalName(fwd, player))
                        : relCruiseItem(player, craft, fwd, curDir, cardinalName(fwd, player)),
                anchorMode ? null : p -> applyCruise(p, craft, fwd, gear));

        setSlot(inv, actions, 2,
                item(Material.SPECTRAL_ARROW,
                        Lang.get("menu.rotate_right.name", player),
                        Lang.get("menu.rotate_right.lore", player)),
                p -> rotateCraft(p, craft, MovecraftRotation.CLOCKWISE));

        Block releaseSign = findSign(craft, "Release");
        setSlot(inv, actions, 4,
                item(Material.RED_BED,
                        Lang.get("menu.leave.name", player),
                        Lang.get("menu.leave.lore", player)),
                p -> doRelease(p, craft, releaseSign));

        setSlot(inv, actions, 5,
                item(Material.GUNPOWDER,
                        Lang.get("menu.reload.name", player),
                        Lang.get("menu.reload.lore", player)),
                p -> loadAllCannons(p, craft));

        setSlot(inv, actions, 6,
                item(Material.FIRE_CHARGE,
                        Lang.get("menu.fire_all.name", player),
                        Lang.get("menu.fire_all.lore", player)),
                p -> fireAllCannons(p, craft));

        // Repair / Cancel-repair button
        boolean repairing    = activeRepairs.containsKey(player.getUniqueId());
        boolean hasBlueprint = blueprintFile(player).exists();
        if (repairing) {
            setSlot(inv, actions, 7,
                    item(Material.BARRIER,
                            Lang.get("menu.cancel_repair.name", player),
                            Lang.get("menu.cancel_repair.lore", player)),
                    p -> cancelRepair(p));
        } else if (hasBlueprint) {
            setSlot(inv, actions, 7,
                    item(Material.ANVIL,
                            Lang.get("menu.repair.name", player),
                            Lang.get("menu.repair.lore", player)),
                    p -> repairFromBlueprint(p, craft));
        } else {
            setSlot(inv, actions, 7, disabledItem(player, Lang.get("menu.no_blueprint_btn", player)), null);
        }

        setSlot(inv, actions, 8,
                item(Material.WRITABLE_BOOK,
                        Lang.get("menu.blueprint.name", player),
                        Lang.get("menu.blueprint.lore", player)),
                p -> saveBlueprint(p, craft));

        // Row 1: Left / Stop / Right
        setSlot(inv, actions, 9,
                anchorMode ? disabledItem(player, cardinalName(lft, player))
                        : relCruiseItem(player, craft, lft, curDir, cardinalName(lft, player)),
                anchorMode ? null : p -> applyLateralCruise(p, craft, lft, gear));
        setSlot(inv, actions, 10,
                item(Material.BARRIER,
                        Lang.get("menu.stop.name", player),
                        Lang.get("menu.stop.lore", player)),
                p -> { stopCruise(craft); reducedDirs.remove(p.getUniqueId()); lateralCruiseDirs.remove(p.getUniqueId()); });
        setSlot(inv, actions, 11,
                anchorMode ? disabledItem(player, cardinalName(rgt, player))
                        : relCruiseItem(player, craft, rgt, curDir, cardinalName(rgt, player)),
                anchorMode ? null : p -> applyLateralCruise(p, craft, rgt, gear));

        // Row 2: Up / Backward / Down
        boolean canVertical = allowsVertical(craft);
        if (canVertical) {
            setSlot(inv, actions, 18,
                    relCruiseItem(player, craft, CruiseDirection.UP, curDir, Lang.get("menu.nav.up", player)),
                    p -> setCruise(p, craft, CruiseDirection.UP));
            setSlot(inv, actions, 20,
                    relCruiseItem(player, craft, CruiseDirection.DOWN, curDir, Lang.get("menu.nav.down", player)),
                    p -> setCruise(p, craft, CruiseDirection.DOWN));
        } else {
            setSlot(inv, actions, 18, disabledItem(player, Lang.get("menu.nav.up_disabled", player)), null);
            setSlot(inv, actions, 20, disabledItem(player, Lang.get("menu.nav.down_disabled", player)), null);
        }
        setSlot(inv, actions, 19,
                anchorMode ? disabledItem(player, cardinalName(bwd, player))
                        : relCruiseItem(player, craft, bwd, curDir, cardinalName(bwd, player)),
                anchorMode ? null : p -> applyCruise(p, craft, bwd, gear));

        // Cannon data: types + broadside groupings (player-yaw relative)
        List<Cannon> allCannons = findCannonsOnCraft(craft);

        BlockFace portFace = cruiseDirToFace(lft);
        BlockFace stbdFace = cruiseDirToFace(rgt);
        BlockFace fwdFace  = cruiseDirToFace(fwd);
        BlockFace bwdFace  = cruiseDirToFace(bwd);
        List<Cannon> portCannons = allCannons.stream()
                .filter(c -> portFace != null && portFace == safeGetDir(c)).toList();
        List<Cannon> stbdCannons = allCannons.stream()
                .filter(c -> stbdFace != null && stbdFace == safeGetDir(c)).toList();
        List<Cannon> fwdCannons = allCannons.stream()
                .filter(c -> fwdFace != null && fwdFace == safeGetDir(c)).toList();
        List<Cannon> bwdCannons = allCannons.stream()
                .filter(c -> bwdFace != null && bwdFace == safeGetDir(c)).toList();

        // Row 2, cols 4-7: Forward / Backward / Right / Left
        setSlot(inv, actions, 22,
                broadsideItem(player, Lang.get("menu.bow_guns.name", player), fwdCannons),
                fwdCannons.isEmpty() ? null : p -> fireCannonGroup(p, craft, fwdCannons));
        setSlot(inv, actions, 23,
                broadsideItem(player, Lang.get("menu.stern_guns.name", player), bwdCannons),
                bwdCannons.isEmpty() ? null : p -> fireCannonGroup(p, craft, bwdCannons));
        setSlot(inv, actions, 24,
                broadsideItem(player, Lang.get("menu.starboard.name", player), stbdCannons),
                stbdCannons.isEmpty() ? null : p -> fireCannonGroup(p, craft, stbdCannons));
        setSlot(inv, actions, 25,
                broadsideItem(player, Lang.get("menu.port.name", player), portCannons),
                portCannons.isEmpty() ? null : p -> fireCannonGroup(p, craft, portCannons));

        // Cannon type buttons: group by designID, slots 13-17 (row 1)
        Map<String, List<Cannon>> byType = new LinkedHashMap<>();
        for (Cannon cannon : allCannons) {
            try {
                String id = cannon.getCannonDesign().getDesignID();
                byType.computeIfAbsent(id, k -> new ArrayList<>()).add(cannon);
            } catch (Exception ignored) {}
        }
        int[] typeSlots = {13, 14, 15, 16, 17};
        int si = 0;
        for (Map.Entry<String, List<Cannon>> entry : byType.entrySet()) {
            if (si >= typeSlots.length) break;
            List<Cannon> group = new ArrayList<>(entry.getValue());
            long ready = group.stream().filter(Cannon::isReadyToFire).count();
            setSlot(inv, actions, typeSlots[si],
                    cannonTypeItem(player, entry.getKey(), group.size(), (int) ready),
                    p -> fireCannonGroup(p, craft, group));
            si++;
        }

        if (windManager.isWindAffected(craft)) buildWindCompass(inv, player);
        if (isSail) buildSailButtons(inv, actions, player, craft, gear);
        buildTurretSection(inv, actions, player, craft);

        menuActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
    }

    // ── Turret section ────────────────────────────────────────────────────────
    //
    //  Slots 30-32 only:
    //   [←] [🎯 selector: none→all→1→2→...→none] [→]
    //
    //  ← → rotate the currently selected turret(s) (noClose).
    //  Center cycles through selection states and reopens the menu.

    @SuppressWarnings("unchecked")
    private void buildTurretSection(Inventory inv, Consumer<Player>[] actions,
                                    Player player, PlayerCraft craft) {
        if (turretListener == null) return;

        List<Block> turrets = turretListener.findTurretSigns(craft);
        UUID uid = player.getUniqueId();
        turretListener.setCache(uid, turrets);
        Integer sel = turretListener.getSelectedIdx(uid);

        java.util.Set<Integer> ncs = noCloseSlots.computeIfAbsent(uid, k -> new java.util.HashSet<>());
        ncs.clear();

        if (turrets.isEmpty()) {
            setSlot(inv, actions, 31, disabledItem(player, Lang.get("menu.turret.none", player)), null);
            return;
        }

        boolean hasSelection = sel != null;

        // ← rotate (slot 30)
        setSlot(inv, actions, 30, turretRotBtn(player, false, hasSelection),
                hasSelection ? p -> rotateTurretsSel(p, turrets, MovecraftRotation.ANTICLOCKWISE) : null);
        if (hasSelection) ncs.add(30);

        // Center: cycling selector (slot 31) — click to advance state
        setSlot(inv, actions, 31, turretSelectorItem(player, turrets, sel),
                p -> { cycleSelectionForward(p.getUniqueId(), turrets.size()); openMenu(p, craft); });

        // → rotate (slot 32)
        setSlot(inv, actions, 32, turretRotBtn(player, true, hasSelection),
                hasSelection ? p -> rotateTurretsSel(p, turrets, MovecraftRotation.CLOCKWISE) : null);
        if (hasSelection) ncs.add(32);
    }

    private void cycleSelectionForward(UUID uid, int n) {
        Integer current = turretListener.getSelectedIdx(uid);
        if (current == null) {
            turretListener.selectAll(uid);
        } else if (current == TurretListener.ALL_TURRETS) {
            if (n > 0) turretListener.selectOne(uid, 0);
            else       turretListener.deselect(uid);
        } else {
            int next = current + 1;
            if (next >= n) turretListener.deselect(uid);
            else           turretListener.selectOne(uid, next);
        }
    }

    private void rotateTurretsSel(Player player, List<Block> turrets, MovecraftRotation rot) {
        if (turretListener == null) return;
        Integer sel = turretListener.getSelectedIdx(player.getUniqueId());
        if (sel == null) return;
        if (sel == TurretListener.ALL_TURRETS) {
            // Stagger rotations: SubcraftRotateSign marks the parent craft busy during detection,
            // so rotating multiple turrets in the same tick causes "parent craft busy" on all
            // but the first. 20 ticks (1s) gives each turret time to finish detection+rotation.
            for (int i = 0; i < turrets.size(); i++) {
                final Block sign = turrets.get(i);
                if (i == 0) {
                    turretListener.rotateTurretFromMenu(sign, player, rot);
                } else {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> turretListener.rotateTurretFromMenu(sign, player, rot), i * 20L);
                }
            }
        } else if (sel >= 0 && sel < turrets.size()) {
            turretListener.rotateTurretFromMenu(turrets.get(sel), player, rot);
        }
    }

    private ItemStack turretRotBtn(Player player, boolean clockwise, boolean enabled) {
        String key = clockwise ? "menu.turret.rot_right" : "menu.turret.rot_left";
        if (!enabled) {
            ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = is.getItemMeta();
            m.displayName(Component.text(Lang.get(key, player))
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text(Lang.get("menu.turret.disabled_rot", player))
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            is.setItemMeta(m);
            return is;
        }
        ItemStack is = new ItemStack(clockwise ? Material.SPECTRAL_ARROW : Material.ARROW);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(Lang.get(key, player))
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text(Lang.get("menu.turret.rot_lore", player))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack turretSelectorItem(Player player, List<Block> turrets, Integer sel) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(Lang.get("menu.turret.count", player, turrets.size()))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(Lang.get("menu.turret.cycle_lore", player))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        if (sel == null) {
            ItemStack is = new ItemStack(Material.COMPASS);
            ItemMeta m = is.getItemMeta();
            m.displayName(Component.text("🎯 " + Lang.get("menu.turret.no_selection", player))
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            m.lore(lore);
            is.setItemMeta(m);
            return is;
        }
        if (sel == TurretListener.ALL_TURRETS) {
            lore.add(1, Component.text(Lang.get("menu.turret.all_lore", player))
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            ItemStack is = new ItemStack(Material.BLAZE_POWDER);
            ItemMeta m = is.getItemMeta();
            m.displayName(Component.text("🎯 " + Lang.get("menu.turret.all", player))
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            m.lore(lore);
            is.setItemMeta(m);
            return is;
        }
        String label = turretListener.getTurretLabel(turrets.get(sel));
        lore.add(1, Component.text(Lang.get("menu.turret.selected_lore", player))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        ItemStack is = new ItemStack(Material.BLAZE_ROD);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text("🎯 " + (sel + 1) + ". " + label)
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        is.setItemMeta(m);
        return is;
    }

    // ── Wind compass (rows 3-5, slots 27-53) ─────────────────────────────────
    //
    //  Layout (row 3 = slots 27-35, row 4 = 36-44, row 5 = 45-53):
    //
    //   [bg][bg][bg][bg][ N ][bg][bg][bg][bg]   27-35
    //   [per][bg][bg][ W ][STR][ E ][bg][bg][bg] 36-44
    //   [bg][bg][bg][bg][ S ][bg][bg][bg][bg]   45-53

    //  Compass layout (left-aligned, cols 0-2):
    //
    //   [ bg][ N ][ bg][ bg][ bg][ bg][ bg][ bg][ bg]   27-35
    //   [  W][STR][  E][ bg][ bg][ bg][ bg][ bg][ bg]   36-44
    //   [ bg][ S ][ bg][ bg][ bg][ bg][ bg][ bg][ bg]   45-53
    //
    //  N=28, W=36, STR=37, E=38, S=46

    private void buildWindCompass(Inventory inv, Player player) {
        int s = windManager.getStrength();
        WindManager.Direction windDir = s >= 1 ? windManager.getDirection() : null;

        // Background — only columns 0-2 of rows 3-5 (cols 3+ freed for other buttons)
        ItemStack bg = windBgPane();
        java.util.Set<Integer> compass = java.util.Set.of(28, 36, 37, 38, 46);
        for (int row = 3; row <= 5; row++) {
            for (int col = 0; col <= 2; col++) {
                int slot = row * 9 + col;
                if (!compass.contains(slot)) inv.setItem(slot, bg);
            }
        }

        // Strength center (slot 37)
        inv.setItem(37, windStrengthItem(s, player));

        // Directional indicators
        placeWindDir(inv, 28, WindManager.Direction.NORTH, windDir, player);
        placeWindDir(inv, 36, WindManager.Direction.WEST,  windDir, player);
        placeWindDir(inv, 38, WindManager.Direction.EAST,  windDir, player);
        placeWindDir(inv, 46, WindManager.Direction.SOUTH, windDir, player);
    }

    private ItemStack windBgPane() {
        ItemStack is = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.empty());
        is.setItemMeta(m);
        return is;
    }

    private ItemStack windStrengthItem(int strength, Player player) {
        Material mat;
        NamedTextColor color;
        if      (strength == 0) { mat = Material.LIGHT_BLUE_STAINED_GLASS_PANE; color = NamedTextColor.AQUA; }
        else if (strength == 1) { mat = Material.WHITE_STAINED_GLASS_PANE;       color = NamedTextColor.GRAY; }
        else if (strength == 2) { mat = Material.YELLOW_STAINED_GLASS_PANE;      color = NamedTextColor.YELLOW; }
        else                    { mat = Material.RED_STAINED_GLASS_PANE;          color = NamedTextColor.RED; }

        String name = Lang.get("wind.strength." + strength, player);
        List<Component> lore = new ArrayList<>();
        switch (strength) {
            case 0 -> lore.add(loreComp(Lang.get("menu.wind.calm_eff", player), NamedTextColor.RED));
            case 1 -> {
                lore.add(loreComp(Lang.get("menu.wind.cross", player, 1), NamedTextColor.GREEN));
                lore.add(loreComp(Lang.get("menu.wind.head",  player, 1), NamedTextColor.RED));
            }
            case 2 -> {
                lore.add(loreComp(Lang.get("menu.wind.tail",  player, 4), NamedTextColor.GREEN));
                lore.add(loreComp(Lang.get("menu.wind.cross", player, 2), NamedTextColor.AQUA));
                lore.add(loreComp(Lang.get("menu.wind.head",  player, 2), NamedTextColor.RED));
            }
            case 3 -> {
                lore.add(loreComp(Lang.get("menu.wind.tail",  player, 6), NamedTextColor.GREEN));
                lore.add(loreComp(Lang.get("menu.wind.head",  player, 6), NamedTextColor.RED));
                lore.add(loreComp(Lang.get("menu.wind.cross", player, 3), NamedTextColor.AQUA));
            }
        }

        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        is.setItemMeta(m);
        return is;
    }

    private void placeWindDir(Inventory inv, int slot, WindManager.Direction dir,
                              WindManager.Direction windDir, Player player) {
        String arrow = switch (dir) {
            case NORTH -> "↑";
            case SOUTH -> "↓";
            case EAST  -> "→";
            case WEST  -> "←";
        };
        String label = arrow + " " + Lang.get("menu.wind." + dir.name().toLowerCase(), player);
        boolean active = dir == windDir;
        ItemStack is;
        ItemMeta m;
        if (active) {
            is = new ItemStack(Material.ARROW);
            m = is.getItemMeta();
            m.displayName(Component.text(label).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            m = is.getItemMeta();
            m.displayName(Component.text(label).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        is.setItemMeta(m);
        inv.setItem(slot, is);
    }

    // ── Sail gear buttons (slots 39/40/41 — row 4 0-idx, cols 3/4/5) ──────────

    @SuppressWarnings("unchecked")
    private void buildSailButtons(Inventory inv, Consumer<Player>[] actions, Player player,
                                  PlayerCraft craft, SailGear current) {
        double woolPct = healthBarListener.getSailWoolRawPct(craft);
        // 0-30%: only NONE; 30-70%: NONE+HALF; 70-100%: all
        boolean canHalf = woolPct >= 30.0;
        boolean canFull = woolPct >= 70.0;

        int[] slots = {40, 41, 42};
        SailGear[] gears = {SailGear.FULL, SailGear.HALF, SailGear.NONE};
        boolean[] allowed = {canFull, canHalf, true};
        for (int i = 0; i < 3; i++) {
            SailGear g = gears[i];
            if (allowed[i]) {
                setSlot(inv, actions, slots[i], sailGearItem(g, current, player),
                        p -> setSailGear(p, craft, g));
            } else {
                setSlot(inv, actions, slots[i], lockedSailItem(g, player), null);
            }
        }
    }

    private ItemStack lockedSailItem(SailGear gear, Player player) {
        String nameKey = switch (gear) {
            case FULL -> "menu.sail.full";
            case HALF -> "menu.sail.half";
            case NONE -> "menu.sail.none";
        };
        ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text("🔒 " + Lang.get(nameKey, player))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text(Lang.get("menu.sail.locked_lore", player))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack sailGearItem(SailGear gear, SailGear current, Player player) {
        boolean active = gear == current;
        String nameKey = switch (gear) {
            case FULL -> "menu.sail.full";
            case HALF -> "menu.sail.half";
            case NONE -> "menu.sail.none";
        };
        String loreKey = switch (gear) {
            case FULL -> "menu.sail.full_lore";
            case HALF -> "menu.sail.half_lore";
            case NONE -> "menu.sail.none_lore";
        };
        Material mat = switch (gear) {
            case FULL -> Material.WHITE_WOOL;
            case HALF -> Material.LIGHT_GRAY_WOOL;
            case NONE -> Material.GRAY_WOOL;
        };
        NamedTextColor color = active ? NamedTextColor.GREEN : NamedTextColor.GRAY;
        String prefix = active ? "▶ " : "";
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(prefix + Lang.get(nameKey, player))
                .color(color).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text(Lang.get(loreKey, player))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        is.setItemMeta(m);
        return is;
    }

    private void setSailGear(Player player, PlayerCraft craft, SailGear gear) {
        double woolPct = healthBarListener.getSailWoolRawPct(craft);
        boolean allowed = switch (gear) {
            case FULL -> woolPct >= 70.0;
            case HALF -> woolPct >= 30.0;
            case NONE -> true;
        };
        if (!allowed) {
            player.sendMessage(Lang.msg("msg.sail_locked", player, NamedTextColor.RED));
            return;
        }
        UUID uid = player.getUniqueId();
        SailGear prev = sailGears.getOrDefault(uid, SailGear.FULL);
        sailGears.put(uid, gear);
        if (gear == SailGear.NONE) {
            // Sails fully down: stop all movement
            craft.setCruising(false);
            reducedDirs.remove(uid);
            lateralCruiseDirs.remove(uid);
            baseBpsCache.remove(uid);
        } else if (gear == SailGear.FULL) {
            baseBpsCache.remove(uid);
            CruiseDirection dir = reducedDirs.remove(uid);
            if (dir != null) { craft.setCruiseDirection(dir); craft.setCruising(true); }
        } else if (prev == SailGear.FULL) {
            // Cache speed BEFORE stopping cruise so getSpeed() still returns correct value
            baseBpsCache.put(uid, getBaseBps(craft));
            // Carry over current direction (native cruise or lateral cruise)
            CruiseDirection cur = craft.getCruising() ? craft.getCruiseDirection()
                    : lateralCruiseDirs.remove(uid);
            craft.setCruising(false);
            if (cur != null && cur != CruiseDirection.NONE
                    && cur != CruiseDirection.UP && cur != CruiseDirection.DOWN)
                reducedDirs.put(uid, cur);
        }
    }

    private void applyCruise(Player player, PlayerCraft craft, CruiseDirection dir, SailGear gear) {
        UUID uid = player.getUniqueId();
        if (gear == SailGear.NONE) {
            player.sendMessage(Lang.msg("msg.sail_cruise_blocked", player, NamedTextColor.YELLOW));
            return;
        }
        lateralCruiseDirs.remove(uid);
        if (gear == SailGear.FULL) {
            setCruise(player, craft, dir);
        } else {
            // HALF: half speed + wind; NONE: no movement — both via reducedDirs
            craft.setCruising(false);
            reducedDirs.put(uid, dir);
        }
    }

    private void applyLateralCruise(Player player, PlayerCraft craft, CruiseDirection dir, SailGear gear) {
        UUID uid = player.getUniqueId();
        if (gear == SailGear.NONE) {
            player.sendMessage(Lang.msg("msg.sail_cruise_blocked", player, NamedTextColor.YELLOW));
            return;
        }
        if (gear == SailGear.FULL) {
            // FULL gear: half-speed lateral via dedicated tick
            craft.setCruising(false);
            reducedDirs.remove(uid);
            if (!baseBpsCache.containsKey(uid)) baseBpsCache.put(uid, getBaseBps(craft));
            lateralCruiseDirs.put(uid, dir);
        } else {
            // HALF: half speed + wind; NONE: no movement — via reducedDirs
            craft.setCruising(false);
            lateralCruiseDirs.remove(uid);
            reducedDirs.put(uid, dir);
        }
    }

    // ── Manual cruise tick (HALF / NONE gears) ────────────────────────────────

    private void tickManualCruise() {
        // Sail manual cruise (HALF gear: half speed with wind)
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, CruiseDirection> entry : reducedDirs.entrySet()) {
            UUID uid = entry.getKey();
            Player player = Bukkit.getPlayer(uid);
            if (player == null) { toRemove.add(uid); continue; }
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null) { toRemove.add(uid); continue; }
            SailGear gear = sailGears.getOrDefault(uid, SailGear.HALF);
            int baseBps = baseBpsCache.getOrDefault(uid, getBaseBps(craft));
            int move    = gear.apply(baseBps);
            // NONE gear = sails down: no movement, no wind
            int wind    = gear == SailGear.NONE ? 0 : windManager.getEffect(entry.getValue());
            int total   = Math.max(0, move + wind);
            if (total == 0) continue;
            int[] cv = sailDirVec(entry.getValue());
            try { craft.translate(cv[0] * total, 0, cv[1] * total); }
            catch (Exception ignored) {}
        }
        toRemove.forEach(uid -> { reducedDirs.remove(uid); sailGears.remove(uid); });

        // Enforce NONE gear: stop native Movecraft cruise if something else re-enabled it
        for (Map.Entry<UUID, SailGear> entry : sailGears.entrySet()) {
            if (entry.getValue() != SailGear.NONE) continue;
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(p);
            if (craft != null && craft.getCruising()) craft.setCruising(false);
        }

        // Lateral cruise (L/R at half base speed for FULL-gear ships, no wind effect)
        List<UUID> toRemoveLat = new ArrayList<>();
        for (Map.Entry<UUID, CruiseDirection> entry : lateralCruiseDirs.entrySet()) {
            UUID uid = entry.getKey();
            Player player = Bukkit.getPlayer(uid);
            if (player == null) { toRemoveLat.add(uid); continue; }
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
            if (craft == null) { toRemoveLat.add(uid); continue; }
            int baseBps = baseBpsCache.getOrDefault(uid, getBaseBps(craft));
            int move    = Math.max(1, baseBps / 2);
            int[] cv    = sailDirVec(entry.getValue());
            try { craft.translate(cv[0] * move, 0, cv[1] * move); }
            catch (Exception ignored) {}
        }
        toRemoveLat.forEach(uid -> {
            lateralCruiseDirs.remove(uid);
            if (!reducedDirs.containsKey(uid)) baseBpsCache.remove(uid);
        });
    }

    private int getBaseBps(PlayerCraft craft) {
        try {
            // getTickCooldown() = ticks between moves of (skipBlocks+1) blocks
            // getSpeed() returns blocks per second directly when cruiseSpeed is set
            double spd = craft.getSpeed();
            if (spd > 0 && spd < 50) return (int) Math.round(spd);
        } catch (Exception ignored) {}
        try {
            int cool = craft.getTickCooldown();
            if (cool > 0) return Math.max(1, 20 / cool);
        } catch (Exception ignored) {}
        return 3;
    }

    private static int[] sailDirVec(CruiseDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST  -> new int[]{1, 0};
            case WEST  -> new int[]{-1, 0};
            default    -> new int[]{0, 0};
        };
    }

    // ── Sail state cleanup ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftRelease(CraftReleaseEvent event) {
        if (!(event.getCraft() instanceof net.countercraft.movecraft.craft.PilotedCraft pc)) return;
        org.bukkit.entity.Player pilot = pc.getPilot();
        if (pilot == null) return;
        UUID uid = pilot.getUniqueId();
        sailGears.remove(uid);
        reducedDirs.remove(uid);
        lateralCruiseDirs.remove(uid);
        baseBpsCache.remove(uid);
    }

    // Intercept cruise sign clicks before Movecraft's NORMAL-priority handler sees them.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onCruiseSign(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign sign)) return;
        String line0 = stripColor(getLine(sign, 0)).trim();
        if (!line0.equalsIgnoreCase("Cruise: ON") && !line0.equalsIgnoreCase("Cruise: OFF")) return;
        Player player = event.getPlayer();
        PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;
        if (sailGears.getOrDefault(player.getUniqueId(), SailGear.FULL) != SailGear.NONE) return;
        player.sendMessage(Lang.msg("msg.sail_cruise_blocked", player, NamedTextColor.YELLOW));
        event.setCancelled(true);
    }

    // Every tick: force-stop native cruise for anchor-mode ships (belt-and-suspenders).
    private void enforceAnchorMode() {
        for (Map.Entry<UUID, SailGear> entry : sailGears.entrySet()) {
            if (entry.getValue() != SailGear.NONE) continue;
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            PlayerCraft craft = CraftManager.getInstance().getCraftByPlayer(p);
            if (craft == null || !craft.getCruising()) continue;
            if (healthBarListener.getSailWoolRawPct(craft) < 30.0) continue; // oars mode
            craft.setCruising(false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uid = event.getPlayer().getUniqueId();
        sailGears.remove(uid);
        reducedDirs.remove(uid);
        lateralCruiseDirs.remove(uid);
        baseBpsCache.remove(uid);
    }

    private Component loreComp(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    private String cardinalName(CruiseDirection dir, Player player) {
        return switch (dir) {
            case NORTH -> Lang.get("menu.nav.north", player);
            case SOUTH -> Lang.get("menu.nav.south", player);
            case EAST  -> Lang.get("menu.nav.east",  player);
            case WEST  -> Lang.get("menu.nav.west",  player);
            default    -> "?";
        };
    }

    // ── Handle clicks ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        Consumer<Player>[] actions = menuActions.get(player.getUniqueId());
        if (actions == null || slot >= actions.length || actions[slot] == null) return;

        Consumer<Player> action = actions[slot];
        java.util.Set<Integer> ncs = noCloseSlots.getOrDefault(player.getUniqueId(), java.util.Set.of());
        if (ncs.contains(slot)) {
            // Rotation button: execute without closing menu
            player.sendMessage("§7[menu] rot-click slot=" + slot + " noClose=true");
            Bukkit.getScheduler().runTask(plugin, () -> action.accept(player));
        } else {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> action.accept(player));
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipMenuHolder)) return;
        UUID uid = event.getPlayer().getUniqueId();
        menuActions.remove(uid);
        noCloseSlots.remove(uid);
    }

    // ── Blueprint rotation tracking ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftRotate(CraftRotateEvent event) {
        if (!(event.getCraft() instanceof PlayerCraft pc)) return;
        Player pilot = pc.getPilot();
        if (pilot == null) return;

        File f = blueprintFile(pilot);
        if (!f.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        if (yaml.getKeys(false).isEmpty()) return;

        HitBox oldBox = event.getOldHitBox();
        HitBox newBox = event.getNewHitBox();
        MovecraftLocation pivot = event.getOriginPoint();
        MovecraftRotation rotation = event.getRotation();

        int oldMinX = oldBox.getMinX(), oldMinY = oldBox.getMinY(), oldMinZ = oldBox.getMinZ();
        int newMinX = newBox.getMinX(), newMinY = newBox.getMinY(), newMinZ = newBox.getMinZ();
        int px = pivot.getX(), pz = pivot.getZ();

        YamlConfiguration rotated = new YamlConfiguration();
        for (String key : yaml.getKeys(false)) {
            String[] parts = key.split(",");
            int rx = Integer.parseInt(parts[0]);
            int ry = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);

            int absX = oldMinX + rx;
            int absY = oldMinY + ry;
            int absZ = oldMinZ + rz;

            int newAbsX, newAbsZ;
            if (rotation == MovecraftRotation.CLOCKWISE) {
                newAbsX = px + (pz - absZ);
                newAbsZ = pz + (absX - px);
            } else {
                newAbsX = px - (pz - absZ);
                newAbsZ = pz - (absX - px);
            }

            String newKey = (newAbsX - newMinX) + "," + (absY - newMinY) + "," + (newAbsZ - newMinZ);
            rotated.set(newKey, yaml.getString(key));
        }
        try {
            rotated.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to update blueprint after rotation: " + e.getMessage());
        }
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
        int[] fwd  = WasdListener.arcFwdVec(player.getLocation().getYaw());
        int dist   = WasdListener.arcDist(craft);

        try { craft.translate(fwd[0] * dist, 0, fwd[1] * dist); } catch (Exception ignored) {}

        int[] newFwd = rotation == MovecraftRotation.CLOCKWISE
                ? new int[]{-fwd[1], fwd[0]} : new int[]{fwd[1], -fwd[0]};

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                HitBox hb = craft.getHitBox();
                craft.rotate(rotation, new MovecraftLocation(
                        (hb.getMinX() + hb.getMaxX()) / 2,
                        (hb.getMinY() + hb.getMaxY()) / 2,
                        (hb.getMinZ() + hb.getMaxZ()) / 2));
            } catch (Exception ignored) {}

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try { craft.translate(newFwd[0] * dist, 0, newFwd[1] * dist); } catch (Exception ignored) {}
            }, 6L);
        }, 6L);
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
                signBlock, face,
                org.bukkit.inventory.EquipmentSlot.HAND);
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

    private String cannonDisplayName(Player player, String designId) {
        String lower = designId.toLowerCase();
        // Try several key formats: exact, underscored, first-word-only
        for (String candidate : new String[]{lower, lower.replace(' ', '_'), lower.split("\\s+")[0]}) {
            String val = Lang.get("cannon." + candidate, player);
            if (!val.startsWith("cannon.")) return val;
        }
        if (plugin.isDebug())
            plugin.getLogger().info("[cannon] no translation for designId='" + designId + "'");
        return designId;
    }

    // ── Cannon actions ────────────────────────────────────────────────────────

    private List<Cannon> findCannonsOnCraft(net.countercraft.movecraft.craft.Craft craft) {
        return CannonUtils.findCannonsOnCraft(craft);
    }

    private CannonsAPI getCannonsAPI() {
        Cannons c = (Cannons) Bukkit.getPluginManager().getPlugin("Cannons");
        return c != null ? c.getCannonsAPI() : null;
    }

    /** Collect all InventoryHolder blocks within the craft's bounding box.
     *  Uses the bounding box (not just the hitbox) so chests outside ALLOWED_BLOCKS are included. */
    private List<org.bukkit.inventory.Inventory> findShipChests(PlayerCraft craft) {
        List<org.bukkit.inventory.Inventory> result = new ArrayList<>();
        HitBox box = craft.getHitBox();
        World world = craft.getWorld();
        java.util.Set<org.bukkit.inventory.Inventory> seen = new java.util.HashSet<>();
        for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
            for (int y = box.getMinY(); y <= box.getMaxY(); y++) {
                for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getState() instanceof InventoryHolder h) {
                        org.bukkit.inventory.Inventory inv = h.getInventory();
                        if (seen.add(inv)) result.add(inv); // dedup double-chests
                    }
                }
            }
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
            player.sendMessage(Lang.msg("msg.no_ammo", player, NamedTextColor.RED, matName, amountPer));
            return 0;
        }
        if (canFire < shots) {
            player.sendMessage(Lang.msg("msg.low_ammo", player, NamedTextColor.YELLOW, canFire, shots));
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
        boolean aiming = aimListener.isAiming(player.getUniqueId());
        for (Cannon cannon : cannons) {
            // When aiming: apply saved aim angles first, then fire without re-aim
            if (aiming) aimListener.applyAimAngle(player, cannon);
            InteractAction action = aiming ? InteractAction.fireOther : InteractAction.fireRightClickTigger;
            api.playerFiring(cannon, player, action);
        }
    }

    private void loadAllCannons(Player player, PlayerCraft craft) {
        List<Cannon> cannons = findCannonsOnCraft(craft);
        if (cannons.isEmpty()) {
            player.sendMessage(Lang.msg("msg.no_cannons", player, NamedTextColor.YELLOW));
            return;
        }
        for (Cannon cannon : cannons) {
            try {
                cannon.reloadFromChests(player.getUniqueId(), true);
                if (!cannon.isReadyToFire()) cannon.reloadFromChests(player.getUniqueId(), true);
            } catch (Exception e) {
                plugin.getLogger().warning("Error reloading cannon: " + e.getMessage());
            }
        }
        long afterReady = cannons.stream().filter(Cannon::isReadyToFire).count();
        player.sendMessage(Lang.msg("msg.reloaded", player,
                afterReady > 0 ? NamedTextColor.GREEN : NamedTextColor.RED,
                afterReady, cannons.size()));
    }

    private void fireCannonGroup(Player player, PlayerCraft craft, List<Cannon> group) {
        CannonsAPI api = getCannonsAPI();
        if (api == null) return;
        String label = group.isEmpty() ? "?" : cannonDisplayName(player, group.get(0).getCannonDesign().getDesignID());
        List<Cannon> ready = group.stream().filter(Cannon::isReadyToFire).toList();
        if (ready.isEmpty()) {
            player.sendMessage(Lang.msg("msg.group_not_ready", player, NamedTextColor.YELLOW, label));
            return;
        }
        int canFire = consumeAmmoFromChests(player, craft, ready.size());
        if (canFire <= 0) return;
        doFire(api, player, ready.subList(0, canFire));
        player.sendMessage(Lang.msg("msg.fired_group", player, NamedTextColor.GREEN, canFire, label));
    }

    private void fireAllCannons(Player player, PlayerCraft craft) {
        CannonsAPI api = getCannonsAPI();
        if (api == null) {
            player.sendMessage(Lang.msg("msg.cannons_unavailable", player, NamedTextColor.RED));
            return;
        }
        List<Cannon> all = new ArrayList<>(findCannonsOnCraft(craft));
        if (turretListener != null)
            all.addAll(turretListener.getAttachedTurretCannons(craft));
        if (all.isEmpty()) {
            player.sendMessage(Lang.msg("msg.no_cannons", player, NamedTextColor.YELLOW));
            return;
        }
        List<Cannon> ready = all.stream().filter(Cannon::isReadyToFire).toList();
        if (ready.isEmpty()) {
            player.sendMessage(Lang.msg("msg.no_ready", player, NamedTextColor.YELLOW));
            return;
        }
        int canFire = consumeAmmoFromChests(player, craft, ready.size());
        if (canFire <= 0) return;
        doFire(api, player, ready.subList(0, canFire));
        player.sendMessage(Lang.msg("msg.fired_all", player, NamedTextColor.GREEN, canFire));
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
            player.sendMessage(Lang.msg("msg.blueprint_saved", player, NamedTextColor.GREEN, count));
        } catch (IOException e) {
            player.sendMessage(Lang.msg("msg.blueprint_error", player, NamedTextColor.RED, e.getMessage()));
        }
    }

    private void repairFromBlueprint(Player player, PlayerCraft craft) {
        UUID uid = player.getUniqueId();
        cancelRepair(player); // cancel any previous repair silently

        File f = blueprintFile(player);
        if (!f.exists()) {
            player.sendMessage(Lang.msg("msg.no_blueprint", player, NamedTextColor.RED));
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        HitBox hitBox = craft.getHitBox();
        World world = craft.getWorld();
        int ox = hitBox.getMinX(), oy = hitBox.getMinY(), oz = hitBox.getMinZ();
        List<org.bukkit.inventory.Inventory> chests = findShipChests(craft);

        // Build ordered list of damaged positions
        record Job(Block block, Material mat) {}
        List<Job> jobs = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            String[] p = key.split(",");
            Material expected = Material.matchMaterial(yaml.getString(key, ""));
            if (expected == null || expected.isAir()) continue;
            Block block = world.getBlockAt(ox + Integer.parseInt(p[0]),
                                           oy + Integer.parseInt(p[1]),
                                           oz + Integer.parseInt(p[2]));
            if (block.getType() != expected) jobs.add(new Job(block, expected));
        }
        if (jobs.isEmpty()) {
            player.sendMessage(Lang.msg("msg.ship_intact", player, NamedTextColor.AQUA));
            return;
        }

        int blocksPerTick = plugin.getConfig().getInt("repair.blocks_per_tick", 1);
        int tickDelay     = plugin.getConfig().getInt("repair.tick_delay", 5);
        int total         = jobs.size();
        player.sendMessage(Lang.msg("msg.repair_start", player, NamedTextColor.GREEN, total));

        // Find pilot sign now (before craft potentially changes)
        Block pilotSign = findCraftSign(craft);
        Location pilotSignLoc = pilotSign != null ? pilotSign.getLocation().clone() : null;

        org.bukkit.scheduler.BukkitRunnable runnable = new org.bukkit.scheduler.BukkitRunnable() {
            int idx      = 0;
            int repaired = 0;
            int missing  = 0;

            @Override
            public void run() {
                int end = Math.min(idx + blocksPerTick, total);
                for (int i = idx; i < end; i++) {
                    Job job = jobs.get(i);
                    if (job.block().getType() == job.mat()) continue; // already fixed
                    if (takeFromChests(chests, job.mat(), 1)) {
                        job.block().setType(job.mat());
                        repaired++;
                    } else {
                        missing++;
                    }
                }
                idx = end;
                player.sendActionBar(Lang.msg("msg.repair_progress", player, NamedTextColor.GREEN, idx, total));

                if (idx < total) return;

                // ── Repair done ──
                cancel();
                activeRepairs.remove(uid);
                player.sendActionBar(Component.empty());

                if (repaired > 0)
                    player.sendMessage(Lang.msg("msg.repair_done", player, NamedTextColor.GREEN, repaired));
                if (missing > 0)
                    player.sendMessage(Lang.msg("msg.repair_missing", player, NamedTextColor.YELLOW, missing));

                // Release and re-detect so new blocks join the craft
                net.countercraft.movecraft.events.CraftReleaseEvent re =
                        new net.countercraft.movecraft.events.CraftReleaseEvent(
                                craft, net.countercraft.movecraft.events.CraftReleaseEvent.Reason.PLAYER);
                Bukkit.getPluginManager().callEvent(re);
                if (!re.isCancelled()) CraftManager.getInstance().release(craft, re.getReason(), false);

                if (pilotSignLoc != null) {
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        simulateClick(player, pilotSignLoc.getBlock()), 1L);
                } else {
                    player.sendMessage(Lang.msg("msg.refit", player, NamedTextColor.YELLOW));
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, tickDelay);
        activeRepairs.put(uid, task);
    }

    private void cancelRepair(Player player) {
        BukkitTask t = activeRepairs.remove(player.getUniqueId());
        if (t != null) {
            t.cancel();
            player.sendActionBar(Component.empty());
            player.sendMessage(Lang.msg("msg.repair_cancelled", player, NamedTextColor.YELLOW));
        }
    }

    /** Find the Movecraft pilot sign on the craft (line 0 matches craft type name). */
    private Block findCraftSign(PlayerCraft craft) {
        String typeName = "";
        try { typeName = craft.getType().getStringProperty(
                net.countercraft.movecraft.craft.type.CraftType.NAME); }
        catch (Exception ignored) {}
        if (typeName == null || typeName.isBlank()) return null;
        String name = typeName;
        var world = craft.getWorld();
        for (var loc : craft.getHitBox()) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (!(block.getState() instanceof Sign sign)) continue;
            String l0 = stripColor(getLine(sign, 0)).trim();
            if (l0.equalsIgnoreCase(name) || l0.equalsIgnoreCase("[" + name + "]")) return block;
        }
        return null;
    }

    /** Remove {@code amount} of {@code mat} from the given chests in order. Returns true if all removed. */
    private boolean takeFromChests(List<org.bukkit.inventory.Inventory> chests, Material mat, int amount) {
        int left = amount;
        for (org.bukkit.inventory.Inventory inv : chests) {
            if (left <= 0) break;
            var result = inv.removeItem(new ItemStack(mat, left));
            left = result.values().stream().mapToInt(ItemStack::getAmount).sum();
        }
        return left == 0;
    }

    // ── Broadside helpers ─────────────────────────────────────────────────────

    private BlockFace cruiseDirToFace(CruiseDirection dir) {
        return switch (dir) {
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case EAST  -> BlockFace.EAST;
            case WEST  -> BlockFace.WEST;
            default    -> null;
        };
    }

    private BlockFace safeGetDir(Cannon cannon) {
        try { return cannon.getCannonPosition().getCannonDirection(); }
        catch (Exception e) { return null; }
    }

    private ItemStack broadsideItem(Player player, String coloredLabel, List<Cannon> cannons) {
        if (cannons.isEmpty()) return disabledItem(player, coloredLabel.replaceAll("§.", ""));
        long ready = cannons.stream().filter(Cannon::isReadyToFire).count();
        return item(Material.TNT, coloredLabel,
                Lang.get("menu.broadside.stats", player, cannons.size(), ready, cannons.size()),
                Lang.get("menu.broadside.fire", player));
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack cannonTypeItem(Player player, String designId, int total, int ready) {
        String label = cannonDisplayName(player, designId);
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
                Component.text(Lang.get("menu.cannon.stats", player, total, ready))
                        .color(ready > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.get("menu.cannon.fire_lore", player))
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        is.setItemMeta(m);
        return is;
    }

    private ItemStack disabledItem(Player player, String label) {
        ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = is.getItemMeta();
        m.displayName(Component.text(label).color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text(Lang.get("menu.disabled.lore", player))
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

    private ItemStack relCruiseItem(Player player, PlayerCraft craft, CruiseDirection dir, CruiseDirection active, String relLabel) {
        boolean on = active == dir && active != CruiseDirection.NONE;
        Material mat = switch (dir) {
            case UP   -> Material.FEATHER;
            case DOWN -> Material.POINTED_DRIPSTONE;
            default   -> Material.ARROW;
        };
        String prefix = on ? "§a▶ " : "§7";
        String lore   = Lang.get(on ? "menu.cruise.active" : "menu.cruise.inactive", player);
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
