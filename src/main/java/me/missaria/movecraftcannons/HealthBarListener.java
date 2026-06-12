package me.missaria.movecraftcannons;

import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.PilotedCraft;
import net.countercraft.movecraft.events.CraftDetectEvent;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.events.CraftSinkEvent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HealthBarListener implements Listener {

    private final MovecraftCannonsPlugin plugin;
    // craft UUID → BossBar shown to the pilot
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    // craft UUID → Craft reference (to read hitBox size in the update task)
    private final Map<UUID, Craft> crafts = new ConcurrentHashMap<>();

    public HealthBarListener(MovecraftCannonsPlugin plugin) {
        this.plugin = plugin;
        // Refresh every 10 ticks (0.5 s) — picks up block loss from explosions/collisions
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDetect(CraftDetectEvent event) {
        Craft craft = event.getCraft();
        if (!(craft instanceof PilotedCraft)) return;

        Player pilot = ((PilotedCraft) craft).getPilot();
        if (pilot == null) return;

        UUID uid = craft.getUUID();
        int orig = craft.getOrigBlockCount();
        int curr = craft.getHitBox().size();
        // origBlockCount may be 0 right at detect; fall back to current size
        if (orig <= 0) orig = curr;

        BossBar bar = Bukkit.createBossBar(
                title(craft, curr, orig),
                color(curr, orig),
                BarStyle.SEGMENTED_10
        );
        bar.setProgress(frac(curr, orig));
        bar.addPlayer(pilot);

        bars.put(uid, bar);
        crafts.put(uid, craft);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelease(CraftReleaseEvent event) {
        remove(event.getCraft().getUUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSink(CraftSinkEvent event) {
        UUID uid = event.getCraft().getUUID();
        BossBar bar = bars.get(uid);
        if (bar != null) {
            bar.setTitle("§c⚠ Тонет!");
            bar.setColor(BarColor.RED);
            bar.setProgress(0.0);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(uid), 100L);
    }

    // ── Update task ───────────────────────────────────────────────────────────

    private void updateAll() {
        crafts.forEach((uid, craft) -> {
            BossBar bar = bars.get(uid);
            if (bar == null) return;

            int orig = craft.getOrigBlockCount();
            int curr = craft.getHitBox().size();
            if (orig <= 0) orig = curr;

            double f = frac(curr, orig);
            bar.setProgress(f);
            bar.setColor(color(curr, orig));
            bar.setTitle(title(craft, curr, orig));
        });
    }

    private void remove(UUID uid) {
        crafts.remove(uid);
        BossBar bar = bars.remove(uid);
        if (bar != null) bar.removeAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double frac(int curr, int orig) {
        return orig <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) curr / orig));
    }

    private BarColor color(int curr, int orig) {
        double f = frac(curr, orig);
        if (f > 0.6) return BarColor.GREEN;
        if (f > 0.3) return BarColor.YELLOW;
        return BarColor.RED;
    }

    private String title(Craft craft, int curr, int orig) {
        // Try to get a meaningful craft name; fall back to generic label
        String name = null;
        try { name = craft.getName(); } catch (Exception ignored) {}
        if (name == null || name.isBlank()) name = "Транспорт";

        double pct = frac(curr, orig) * 100;
        String pctColor = pct > 60 ? "§a" : pct > 30 ? "§e" : "§c";
        return "§f⚓ " + name + " " + pctColor + String.format("%.0f%%", pct)
                + " §7(" + curr + "/" + orig + " блоков)";
    }
}
