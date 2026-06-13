package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.PlayerCraft;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShipMenuListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    // slot index → sign block for each open menu
    private final Map<UUID, List<Block>> playerMenuSigns = new ConcurrentHashMap<>();

    public ShipMenuListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Open menu on right-click with compass while piloting ───────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCompassClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS) return;

        Player      player = event.getPlayer();
        PlayerCraft craft  = CraftManager.getInstance().getCraftByPlayer(player);
        if (craft == null) return;

        event.setCancelled(true);
        openMenu(player, craft);
    }

    // ── Build and open the inventory ───────────────────────────────────────────

    private void openMenu(Player player, PlayerCraft craft) {
        List<Block> signs = findControlSigns(craft);

        int rows = Math.max(1, (int) Math.ceil(signs.size() / 9.0));
        rows = Math.min(rows, 6);
        int size = rows * 9;

        String craftName = craftTitle(craft);
        ShipMenuHolder holder = new ShipMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, size,
                Component.text("⚓ " + craftName).color(NamedTextColor.DARK_AQUA));
        holder.setInventory(inv);

        List<Block> slotSigns = new ArrayList<>();
        for (int i = 0; i < Math.min(signs.size(), size); i++) {
            Block sign = signs.get(i);
            inv.setItem(i, buildItem(sign));
            slotSigns.add(sign);
        }

        playerMenuSigns.put(player.getUniqueId(), slotSigns);
        player.openInventory(inv);
    }

    // ── Handle clicks inside the menu ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipMenuHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        List<Block> signs = playerMenuSigns.get(player.getUniqueId());
        if (signs == null || slot >= signs.size()) return;
        Block signBlock = signs.get(slot);

        // Close the inventory first, then simulate the sign click on the next tick
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> simulateSignClick(player, signBlock));
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipMenuHolder)) return;
        playerMenuSigns.remove(event.getPlayer().getUniqueId());
    }

    // ── Simulate right-click on a sign ────────────────────────────────────────

    private void simulateSignClick(Player player, Block signBlock) {
        // Determine the block face to click (use the sign's facing direction)
        BlockFace face = getSignFace(signBlock);
        PlayerInteractEvent fake = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(),
                signBlock, face);
        Bukkit.getPluginManager().callEvent(fake);
    }

    // ── Scan craft hitbox for control signs ───────────────────────────────────

    private List<Block> findControlSigns(PlayerCraft craft) {
        List<Block> result = new ArrayList<>();
        var world = craft.getWorld();

        for (var loc : craft.getHitBox()) {
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            if (!(block.getState() instanceof Sign sign)) continue;
            String line0 = stripColor(getLine(sign, 0)).trim();
            if (isControlSign(line0)) result.add(block);
        }
        return result;
    }

    private boolean isControlSign(String line0) {
        String lo = line0.toLowerCase();
        return lo.startsWith("move:")
                || lo.startsWith("cruise:")
                || lo.startsWith("ascend:")
                || lo.startsWith("descend:")
                || lo.startsWith("speed:")
                || lo.equals("[helm]")
                || lo.equalsIgnoreCase("release")
                || lo.equalsIgnoreCase("contacts");
    }

    // ── Build inventory item for a sign ───────────────────────────────────────

    private ItemStack buildItem(Block signBlock) {
        Sign sign = (Sign) signBlock.getState();
        String line0 = stripColor(getLine(sign, 0)).trim();
        String line1 = stripColor(getLine(sign, 1)).trim();

        Material mat;
        String   label;
        NamedTextColor color = NamedTextColor.WHITE;

        String lo = line0.toLowerCase();
        if (lo.startsWith("move:")) {
            mat   = directionMaterial(line1);
            label = directionLabel(line1);
            color = NamedTextColor.YELLOW;
        } else if (lo.startsWith("cruise:")) {
            boolean on = lo.contains("on");
            mat   = on ? Material.GREEN_DYE : Material.RED_DYE;
            label = on ? "Крейсер: ВКЛ" : "Крейсер: ВЫКЛ";
            color = on ? NamedTextColor.GREEN : NamedTextColor.RED;
        } else if (lo.startsWith("ascend:")) {
            boolean on = lo.contains("on");
            mat   = Material.FEATHER;
            label = on ? "Подъём: ВКЛ" : "Подъём: ВЫКЛ";
            color = on ? NamedTextColor.GREEN : NamedTextColor.RED;
        } else if (lo.startsWith("descend:")) {
            boolean on = lo.contains("on");
            mat   = Material.POINTED_DRIPSTONE;
            label = on ? "Спуск: ВКЛ" : "Спуск: ВЫКЛ";
            color = on ? NamedTextColor.GREEN : NamedTextColor.RED;
        } else if (lo.startsWith("speed:")) {
            mat   = Material.CLOCK;
            label = "Скорость" + (line1.isBlank() ? "" : ": " + line1);
            color = NamedTextColor.GOLD;
        } else if (lo.equals("[helm]")) {
            mat   = Material.NAME_TAG;
            label = "Штурвал (прямое управление)";
            color = NamedTextColor.AQUA;
        } else if (lo.equalsIgnoreCase("release")) {
            mat   = Material.BARRIER;
            label = "Покинуть судно";
            color = NamedTextColor.RED;
        } else if (lo.equalsIgnoreCase("contacts")) {
            mat   = Material.SPYGLASS;
            label = "Контакты";
            color = NamedTextColor.LIGHT_PURPLE;
        } else {
            mat   = Material.OAK_SIGN;
            label = line0;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.displayName(Component.text(label).color(color)
                .decoration(TextDecoration.ITALIC, false));

        // Show additional sign lines as lore
        List<Component> lore = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String l = stripColor(getLine(sign, i)).trim();
            if (!l.isBlank())
                lore.add(Component.text(l).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    // ── Direction helpers ──────────────────────────────────────────────────────

    private Material directionMaterial(String line1) {
        String dir = line1.toLowerCase().split(" ")[0];
        return switch (dir) {
            case "up"    -> Material.POINTED_DRIPSTONE;
            case "down"  -> Material.GRAVEL;
            default      -> Material.ARROW;
        };
    }

    private String directionLabel(String line1) {
        if (line1.isBlank()) return "Движение";
        String[] parts = line1.trim().split("\\s+");
        String dir = switch (parts[0].toLowerCase()) {
            case "north" -> "Север";
            case "south" -> "Юг";
            case "east"  -> "Восток";
            case "west"  -> "Запад";
            case "up"    -> "Вверх";
            case "down"  -> "Вниз";
            default      -> parts[0];
        };
        return "Движение: " + dir + (parts.length > 1 ? " ×" + parts[1] : "");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String getLine(Sign sign, int index) {
        try {
            return sign.getSide(Side.FRONT).getLine(index);
        } catch (Exception e) {
            return sign.getLine(index);
        }
    }

    @SuppressWarnings("deprecation")
    private String stripColor(String s) {
        return org.bukkit.ChatColor.stripColor(s);
    }

    private BlockFace getSignFace(Block block) {
        try {
            var data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.type.WallSign ws)
                return ws.getFacing();
            if (data instanceof org.bukkit.block.data.type.Sign s)
                return BlockFace.SOUTH; // floor sign — use south as default click face
        } catch (Exception ignored) {}
        return BlockFace.SOUTH;
    }

    private String craftTitle(PlayerCraft craft) {
        try {
            String n = craft.getName();
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {}
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
