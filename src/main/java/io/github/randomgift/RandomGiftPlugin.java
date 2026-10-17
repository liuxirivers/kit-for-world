package io.github.randomgift;

import io.github.randomgift.command.GiftCommand;
import io.github.randomgift.config.PluginConfig;
import io.github.randomgift.data.GiftDataStore;
import io.github.randomgift.listener.GiftInteractionListener;
import io.github.randomgift.manager.GiftManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * RandomGift 插件主类
 * 负责生命周期管理、周期调度、异步礼包生成/清理流程编排。
 */
public final class RandomGiftPlugin extends JavaPlugin {

    /** 现实时间模式定时检查间隔（tick，60 秒） */
    private static final long REALTIME_CHECK_INTERVAL_TICKS = 1200L;
    /** 一个 MC 游戏天的 tick 数 */
    private static final long TICKS_PER_MC_DAY = 24000L;
    /** 一天对应的毫秒数 */
    private static final long MILLIS_PER_DAY = 86400000L;

    private PluginConfig pluginConfig;
    private GiftDataStore dataStore;
    private GiftManager giftManager;
    private BukkitRunnable schedulerTask;
    /** 自动生成流程进行中标记，防止周期任务重入 */
    private volatile boolean autoSpawnInProgress;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginConfig = new PluginConfig(this);
        dataStore = new GiftDataStore(this);
        dataStore.load(pluginConfig.getTargetWorldName());
        giftManager = new GiftManager(this, pluginConfig, dataStore);

        // 注册指令
        GiftCommand giftCommand = new GiftCommand(this);
        var cmd = getCommand("gift");
        if (cmd != null) {
            cmd.setExecutor(giftCommand);
            cmd.setTabCompleter(giftCommand);
        }

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(new GiftInteractionListener(this), this);

        // 启动流程：需要立即生成则"先清后建"；否则仅清理上次异常残留
        if (shouldSpawnImmediately()) {
            getLogger().info(dataStore.getLastSpawnTime() == 0
                    ? "首次启动，立即执行礼包生成"
                    : "检测到上次生成已过期，执行礼包生成");
            performAutoSpawn(() -> { });
        } else if (!dataStore.isEmpty()) {
            getLogger().info("检测到 " + dataStore.size() + " 个上次运行残留的礼包，开始清理");
            giftManager.clearAllGiftsAsync(removed ->
                    getLogger().info("启动残留清理完成，共清除 " + removed + " 个礼包"));
        }

        startScheduler();
        getLogger().info("RandomGift 已启用 (v" + getDescription().getVersion() + ")");
    }

    @Override
    public void onDisable() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
        // 同步强制清理全部礼包（含卸载区块），防止实体残留
        int removed = giftManager.clearAllGifts();
        dataStore.save();
        getLogger().info("RandomGift 已禁用，已清理 " + removed + " 个礼包实体");
    }

    /**
     * 是否应在启动时立即生成（仅现实时间模式：首次启动或已过周期）
     */
    private boolean shouldSpawnImmediately() {
        if (!pluginConfig.isUseRealTime()) {
            return false;
        }
        long last = dataStore.getLastSpawnTime();
        if (last == 0) return true;
        long periodMs = pluginConfig.getPeriodDays() * MILLIS_PER_DAY;
        return System.currentTimeMillis() - last >= periodMs;
    }

    /**
     * 启动周期调度器
     */
    private void startScheduler() {
        if (pluginConfig.isUseRealTime()) {
            // 现实时间模式：定时检查现实时间戳差值（Java 标准时间 API 记录，持久化保存）
            schedulerTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (autoSpawnInProgress) return;
                    long last = dataStore.getLastSpawnTime();
                    long periodMs = pluginConfig.getPeriodDays() * MILLIS_PER_DAY;
                    if (last == 0 || System.currentTimeMillis() - last >= periodMs) {
                        performAutoSpawn(() -> { });
                    }
                }
            };
            schedulerTask.runTaskTimer(this, REALTIME_CHECK_INTERVAL_TICKS,
                    REALTIME_CHECK_INTERVAL_TICKS);
        } else {
            // 游戏 tick 模式：按 MC 游戏天延迟执行，完成后再排下一周期，避免任务重叠
            scheduleTickBasedSpawn();
        }
    }

    /**
     * 调度游戏 tick 模式的下一次礼包生成
     */
    private void scheduleTickBasedSpawn() {
        long periodTicks = pluginConfig.getPeriodDays() * TICKS_PER_MC_DAY;
        schedulerTask = new BukkitRunnable() {
            @Override
            public void run() {
                performAutoSpawn(RandomGiftPlugin.this::scheduleTickBasedSpawn);
            }
        };
        schedulerTask.runTaskLater(this, periodTicks);
    }

    /**
     * 执行一次完整的自动礼包生成：异步清除旧礼包 → 异步生成新礼包 → 记录时间
     *
     * @param afterDone 全部完成后的回调（主线程），用于重排下一周期
     */
    private void performAutoSpawn(Runnable afterDone) {
        World world = pluginConfig.getTargetWorld();
        if (world == null) {
            getLogger().warning("自动生成失败：目标世界 " + pluginConfig.getTargetWorldName() + " 未找到");
            autoSpawnInProgress = false;
            afterDone.run();
            return;
        }
        autoSpawnInProgress = true;
        int target = pluginConfig.getSpawnCount();
        getLogger().info("开始周期礼包生成，目标世界: " + world.getName()
                + "，目标数量: " + target + "（先清除旧礼包并预加载区块）");

        giftManager.clearAllGiftsAsync(removed -> {
            getLogger().info("已清除 " + removed + " 个旧礼包实体，开始异步预加载区块生成新礼包");
            giftManager.spawnBatchAsync(world, target, spawned -> {
                dataStore.setLastSpawnTime(System.currentTimeMillis());
                dataStore.save();
                autoSpawnInProgress = false;
                if (spawned < target) {
                    getLogger().warning("礼包生成完成：实际 " + spawned + " / 目标 " + target
                            + "（在 " + pluginConfig.getSpawnRange() + " 格范围内未找到足够的合法地表位置，"
                            + "可尝试调大范围或减少生成数量）");
                } else {
                    getLogger().info("礼包生成完成，共生成 " + spawned + " 个新礼包实体");
                }
                afterDone.run();
            });
        });
    }

    /**
     * /gift reload 后按新配置重启调度器，使周期/时间模式立即生效
     */
    public void restartScheduler() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        autoSpawnInProgress = false;
        startScheduler();
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public GiftDataStore getDataStore() {
        return dataStore;
    }

    public GiftManager getGiftManager() {
        return giftManager;
    }
}
