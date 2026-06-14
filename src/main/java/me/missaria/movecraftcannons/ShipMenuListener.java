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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.HashSet;

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
     *  [RotL] [  N  ] [RotR]  [ ] [Release] [Reload] [Fire] [ ] [ ]
     *  [ W  ] [Stop ] [  E ]  [ ] [       ] [      ] [    ] [ ] [ ]
     *  [  ↑ ] [  S  ] [  ↓ ]  [ ] [       ] [      ] [    ] [ ] [ ]
     *
     * Slots 0-2:  Rotate-L / Cruise-N / Rotate-R
     * Slot  4:    Release
     * Slot  5:    Reload all cannons from chests
     * Slot  6:    Fire all cannons
     * Slot  9:    Cruise-W
     * Slot  10:   Cruise Stop
     * Slot  11:   Cruise-E
     * Slot  18:   Cruise Up
     * Slot  19:   Cruise S
     * Slot  20:   Cruise Down
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

    private void loadAllCannons(Player player, PlayerCraft craft) {
        List<Cannon> cannons = findCannonsOnCraft(craft);
        if (cannons.isEmpty()) {
            player.sendMessage(Component.text("На этом судне нет пушек.")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        for (Cannon cannon : cannons) {
            cannon.reloadFromChests(player.getUniqueId(), false);
        }
        player.sendMessage(Component.text("Заряжаем " + cannons.size() + " пушек...")
                .color(NamedTextColor.GREEN));
    }

    private void fireAllCannons(Player player, PlayerCraft craft) {
        List<Cannon> cannons = findCannonsOnCraft(craft);
        if (cannons.isEmpty()) {
            player.sendMessage(Component.text("На этом судне нет пушек.")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        Cannons cannonsPlugin = (Cannons) Bukkit.getPluginManager().getPlugin("Cannons");
        if (cannonsPlugin == null) {
            player.sendMessage(Component.text("Плагин Cannons недоступен.")
                    .color(NamedTextColor.RED));
            return;
        }
        CannonsAPI api = cannonsPlugin.getCannonsAPI();
        int fired = 0;
        int notReady = 0;
        for (Cannon cannon : cannons) {
            if (!cannon.isReadyToFire()) { notReady++; continue; }
            api.playerFiring(cannon, player, InteractAction.fireOther);
            fired++;
        }
        if (fired > 0) {
            player.sendMessage(Component.text("Выстрелено из " + fired + " пушек!")
                    .color(NamedTextColor.GREEN));
        }
        if (notReady > 0) {
            player.sendMessage(Component.text(notReady + " пушек не готовы к стрельбе.")
                    .color(NamedTextColor.YELLOW));
        }
    }

    // ── Item builders ─────────────────────────────────────────────────────────

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
