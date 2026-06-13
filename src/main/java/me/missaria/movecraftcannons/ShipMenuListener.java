package me.missaria.movecraftcannons;

import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.MovecraftRotation;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.util.hitboxes.HitBox;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
     *  [RotL] [  N  ] [RotR]  [ ] [Release] [ ] [ ] [ ] [ ]
     *  [ W  ] [Stop ] [  E ]  [ ] [        ] [ ] [ ] [ ] [ ]
     *  [  ↑ ] [  S  ] [  ↓ ]  [ ] [        ] [ ] [ ] [ ] [ ]
     *
     * Slots 0-2:  Rotate-L / Cruise-N / Rotate-R
     * Slot  4:    Release
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

        // Row 0
        setSlot(inv, actions, 0,
                item(Material.SPECTRAL_ARROW, "§e↺ Поворот влево",  "Повернуть судно против часовой стрелки"),
                p -> rotateCraft(p, craft, MovecraftRotation.ANTICLOCKWISE));

        setSlot(inv, actions, 1, cruiseItem(craft, CruiseDirection.NORTH, curDir),
                p -> setCruise(p, craft, CruiseDirection.NORTH));

        setSlot(inv, actions, 2,
                item(Material.SPECTRAL_ARROW, "§e↻ Поворот вправо", "Повернуть судно по часовой стрелке"),
                p -> rotateCraft(p, craft, MovecraftRotation.CLOCKWISE));

        Block releaseSign = findSign(craft, "Release");
        setSlot(inv, actions, 4,
                item(Material.RED_BED, "§4Покинуть судно", "Остановить крейсер и покинуть транспорт"),
                p -> doRelease(p, craft, releaseSign));

        // Row 1
        setSlot(inv, actions, 9,  cruiseItem(craft, CruiseDirection.WEST, curDir),
                p -> setCruise(p, craft, CruiseDirection.WEST));

        setSlot(inv, actions, 10,
                item(Material.BARRIER, "§cОстановить крейсер", "Отключить крейсерский режим"),
                p -> stopCruise(craft));

        setSlot(inv, actions, 11, cruiseItem(craft, CruiseDirection.EAST, curDir),
                p -> setCruise(p, craft, CruiseDirection.EAST));

        // Row 2
        setSlot(inv, actions, 18, cruiseItem(craft, CruiseDirection.UP, curDir),
                p -> setCruise(p, craft, CruiseDirection.UP));

        setSlot(inv, actions, 19, cruiseItem(craft, CruiseDirection.SOUTH, curDir),
                p -> setCruise(p, craft, CruiseDirection.SOUTH));

        setSlot(inv, actions, 20, cruiseItem(craft, CruiseDirection.DOWN, curDir),
                p -> setCruise(p, craft, CruiseDirection.DOWN));

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

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack cruiseItem(PlayerCraft craft, CruiseDirection dir, CruiseDirection active) {
        boolean on = craft.getCruising() && active == dir;
        String label;
        Material mat;
        switch (dir) {
            case NORTH -> { label = "Север";   mat = Material.ARROW; }
            case SOUTH -> { label = "Юг";      mat = Material.ARROW; }
            case EAST  -> { label = "Восток";  mat = Material.ARROW; }
            case WEST  -> { label = "Запад";   mat = Material.ARROW; }
            case UP    -> { label = "Вверх";   mat = Material.FEATHER; }
            case DOWN  -> { label = "Вниз";    mat = Material.POINTED_DRIPSTONE; }
            default    -> { label = dir.name(); mat = Material.ARROW; }
        }
        String prefix = on ? "§a▶ " : "§7";
        String lore   = on ? "§aКрейсер АКТИВЕН" : "§7Нажмите для крейсера";
        return item(mat, prefix + "Крейсер: " + label, lore);
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
