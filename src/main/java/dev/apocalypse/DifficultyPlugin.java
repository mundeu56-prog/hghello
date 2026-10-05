package dev.apocalypse;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class DifficultyPlugin extends JavaPlugin implements Listener, TabExecutor {

    private record Entry(EntityType type, double minDifficulty, int weight) {}

    private static final List<Entry> SPAWN_TABLE = List.of(
            new Entry(EntityType.ZOMBIE, 1, 40),
            new Entry(EntityType.SKELETON, 1, 25),
            new Entry(EntityType.SPIDER, 1, 15),
            new Entry(EntityType.CREEPER, 8, 10),
            new Entry(EntityType.HUSK, 15, 15),
            new Entry(EntityType.STRAY, 15, 10),
            new Entry(EntityType.CAVE_SPIDER, 20, 6),
            new Entry(EntityType.WITCH, 25, 4),
            new Entry(EntityType.PILLAGER, 30, 6),
            new Entry(EntityType.VINDICATOR, 35, 8),
            new Entry(EntityType.WITHER_SKELETON, 55, 10),
            new Entry(EntityType.RAVAGER, 75, 2)
    );

    private double difficulty;
    private int elapsedSeconds = 0;
    private Scoreboard board;
    private Objective objective;
    private MobBuffer buffer;
    private File stateFile;

    // ───────────────────────── 생명주기 ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");
        buffer = new MobBuffer(this);

        difficulty = clamp(YamlConfiguration.loadConfiguration(stateFile)
                .getDouble("difficulty", minDifficulty()));

        setupScoreboard();
        getServer().getPluginManager().registerEvents(this, this);

        var cmd = Objects.requireNonNull(getCommand("difficulty"));
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);

        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(board);

        Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::chaseTick, 40L, 20L);
        scheduleSpawn();
    }

    @Override
    public void onDisable() {
        saveState();
    }

    // ───────────────────────── 난이도 ─────────────────────────

    private double minDifficulty() { return getConfig().getDouble("difficulty.start", 1.0); }
    private double maxDifficulty() { return getConfig().getDouble("difficulty.max", 99.0); }

    private double clamp(double v) { return Math.max(minDifficulty(), Math.min(maxDifficulty(), v)); }

    /** 0.0(시작) ~ 1.0(최대) 로 정규화된 난이도 */
    public double norm() {
        double span = maxDifficulty() - minDifficulty();
        if (span <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, (difficulty - minDifficulty()) / span));
    }

    private static String fmt(double d) { return String.format(Locale.US, "%.1f", d); }

    private void secondTick() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;   // 아무도 없으면 시간 정지
        if (difficulty >= maxDifficulty()) return;
        if (++elapsedSeconds >= getConfig().getInt("difficulty.interval-seconds", 30)) {
            elapsedSeconds = 0;
            setDifficulty(difficulty + getConfig().getDouble("difficulty.step", 1.0), true);
        }
    }

    private void setDifficulty(double value, boolean announce) {
        difficulty = clamp(value);
        updateBoard();
        saveState();
        if (announce) {
            Component msg = Component.text("난이도 상승! ", NamedTextColor.RED)
                    .append(Component.text(fmt(difficulty), NamedTextColor.YELLOW));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar(msg);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.4f, 0.6f);
            }
        }
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("difficulty", difficulty);
        try {
            getDataFolder().mkdirs();
            y.save(stateFile);
        } catch (IOException ex) {
            getLogger().warning("난이도 저장 실패: " + ex.getMessage());
        }
    }

    // ───────────────────────── 스코어보드 (Difficulty 표시) ─────────────────────────

    private void setupScoreboard() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("apoc_difficulty", Criteria.DUMMY, Component.text("Difficulty"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        objective.getScore(" ").setScore(0);
        updateBoard();
    }

    private void updateBoard() {
        TextColor color = TextColor.lerp((float) norm(), NamedTextColor.GREEN, NamedTextColor.DARK_RED);
        objective.displayName(Component.text("Difficulty ", NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text(fmt(difficulty), color, TextDecoration.BOLD)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().setScoreboard(board);
    }

    // ───────────────────────── 몬스터 강화 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Mob m && MobBuffer.isApocalypseMob(m)) {
            buffer.apply(m, norm());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) src = shooter;
        if (!MobBuffer.isApocalypseMob(src)) return;
        e.setDamage(e.getDamage() * buffer.damageMultiplier(norm()));
    }

    // ───────────────────────── 추가 스폰 ─────────────────────────

    private void scheduleSpawn() {
        int start = getConfig().getInt("spawn.interval-ticks-start", 120);
        int min = getConfig().getInt("spawn.interval-ticks-min", 10);
        long delay = Math.max(min, Math.round(start + (min - start) * norm()));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                spawnCycle();
            } finally {
                if (isEnabled()) scheduleSpawn();
            }
        }, delay);
    }

    private static boolean isTargetable(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private void spawnCycle() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isTargetable(p)) spawnAround(p);
        }
    }

    private void spawnAround(Player p) {
        World w = p.getWorld();
        if (w.getDifficulty() == Difficulty.PEACEFUL) return;

        double n = norm();
        int capStart = getConfig().getInt("spawn.mob-cap-start", 8);
        int capMax = getConfig().getInt("spawn.mob-cap-at-max", 80);
        int cap = (int) Math.round(capStart + n * (capMax - capStart));

        long near = p.getNearbyEntities(48, 32, 48).stream()
                .filter(en -> MobBuffer.isApocalypseMob(en)).count();
        if (near >= cap) return;

        int groupMax = getConfig().getInt("spawn.group-size-at-max", 6);
        int amount = 1 + (int) Math.floor(n * (groupMax - 1));
        amount = (int) Math.min(amount, cap - near);

        for (int i = 0; i < amount; i++) {
            Location loc = findSpawn(p);
            if (loc == null) continue;
            w.spawnEntity(loc, pickType());   // CreatureSpawnEvent 에서 자동 강화됨
        }
    }

    private EntityType pickType() {
        int total = 0;
        for (Entry en : SPAWN_TABLE) if (difficulty >= en.minDifficulty()) total += en.weight();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (Entry en : SPAWN_TABLE) {
            if (difficulty < en.minDifficulty()) continue;
            roll -= en.weight();
            if (roll < 0) return en.type();
        }
        return EntityType.ZOMBIE;
    }

    private Location findSpawn(Player p) {
        World w = p.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double minD = getConfig().getDouble("spawn.min-distance", 18);
        double maxD = getConfig().getDouble("spawn.max-distance", 40);
        Location base = p.getLocation();

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = minD + r.nextDouble() * (maxD - minD);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;

            int py = base.getBlockY();
            for (int y = py + 6; y >= py - 10; y--) {
                Block feet = w.getBlockAt(x, y, z);
                if (feet.isPassable() && !feet.isLiquid()
                        && w.getBlockAt(x, y + 1, z).isPassable()
                        && w.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    // ───────────────────────── 추적 강화 ─────────────────────────

    private void chaseTick() {
        double n = norm();
        double range = 32 + n * 48;
        double speed = 1.0 + n * 0.35;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isTargetable(p)) continue;
            for (Entity e : p.getNearbyEntities(range, range / 2, range)) {
                if (!(e instanceof Mob m) || !MobBuffer.isApocalypseMob(m)) continue;

                LivingEntity cur = m.getTarget();
                if (!(cur instanceof Player) || !cur.isValid()) m.setTarget(p);

                if (n >= 0.15 && m.getTarget() instanceof Player t
                        && t.getWorld().equals(m.getWorld())
                        && m.getLocation().distanceSquared(t.getLocation()) > 36) {
                    m.getPathfinder().moveTo(t.getLocation(), speed);
                }
            }
        }
    }

    // ───────────────────────── 명령어 ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(Component.text("Difficulty: " + fmt(difficulty) + " / " + fmt(maxDifficulty()),
                    NamedTextColor.YELLOW));
            return true;
        }
        if (!s.hasPermission("difficulty.admin")) {
            s.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (a.length < 2) {
                    s.sendMessage(Component.text("사용법: /difficulty set <값>", NamedTextColor.RED));
                    return true;
                }
                try {
                    setDifficulty(Double.parseDouble(a[1]), false);
                    elapsedSeconds = 0;
                    s.sendMessage(Component.text("난이도를 " + fmt(difficulty) + "(으)로 설정했습니다.", NamedTextColor.GREEN));
                } catch (NumberFormatException ex) {
                    s.sendMessage(Component.text("숫자를 입력하세요.", NamedTextColor.RED));
                }
            }
            case "reset" -> {
                setDifficulty(minDifficulty(), false);
                elapsedSeconds = 0;
                s.sendMessage(Component.text("난이도를 초기화했습니다.", NamedTextColor.GREEN));
            }
            default -> s.sendMessage(Component.text("사용법: /difficulty [set <값>|reset]", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1 && s.hasPermission("difficulty.admin")) {
            return List.of("set", "reset").stream()
                    .filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
