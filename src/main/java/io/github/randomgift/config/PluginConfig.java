package io.github.randomgift.config;

import io.github.randomgift.RandomGiftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.List;

/**
 * 插件配置管理（config.yml）
 * 仅负责读取用户配置；礼包持久化数据由 GiftDataStore 负责。
 * 所有数值均来自配置文件，无硬编码。
 */
public class PluginConfig {

    private final RandomGiftPlugin plugin;
    private FileConfiguration config;

    // ===== 用户配置项 =====
    private String targetWorldName;
    private int spawnRange;
    private int spawnCount;
    private int amountMin;
    private int amountMax;
    private Material helmetBlock;
    private boolean glowEnabled;
    private boolean useRealTime;
    private int periodDays;
    private List<String> commands;

    public PluginConfig(RandomGiftPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * 重新加载 config.yml
     */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadConfig();
        plugin.getLogger().info("配置已重新加载");
    }

    /**
     * 从 config.yml 读取所有配置项
     */
    private void loadConfig() {
        targetWorldName = config.getString("target-world", "world");
        spawnRange = config.getInt("spawn-range", 1000);
        spawnCount = config.getInt("spawn-count", 20);
        String amountStr = config.getString("amount-given", "5-25");
        parseAmountRange(amountStr);
        String blockStr = config.getString("helmet-block", "CHEST");
        Material mat = Material.matchMaterial(blockStr);
        helmetBlock = (mat != null) ? mat : Material.CHEST;
        glowEnabled = config.getBoolean("glow-enabled", true);
        useRealTime = config.getBoolean("use-real-time", false);
        periodDays = config.getInt("period-days", 3);
        commands = config.getStringList("commands");
        if (commands == null || commands.isEmpty()) {
            commands = Collections.singletonList("say {player} 获得了 {AMG} 点奖励！");
        }
    }

    /**
     * 解析 "最小值-最大值" 格式的金额区间
     */
    private void parseAmountRange(String amountStr) {
        try {
            String[] parts = amountStr.split("-");
            amountMin = Integer.parseInt(parts[0].trim());
            amountMax = Integer.parseInt(parts[1].trim());
            if (amountMin < 0) amountMin = 0;
            if (amountMax < amountMin) amountMax = amountMin;
        } catch (Exception e) {
            amountMin = 5;
            amountMax = 25;
            plugin.getLogger().warning("amount-given 格式错误，使用默认值 5-25");
        }
    }

    // ========== Getter ==========

    public World getTargetWorld() {
        if (targetWorldName == null || targetWorldName.isEmpty()) {
            return null;
        }
        return Bukkit.getWorld(targetWorldName);
    }

    public String getTargetWorldName() {
        return targetWorldName;
    }

    public int getSpawnRange() {
        return spawnRange;
    }

    public int getSpawnCount() {
        return spawnCount;
    }

    public int getAmountMin() {
        return amountMin;
    }

    public int getAmountMax() {
        return amountMax;
    }

    public Material getHelmetBlock() {
        return helmetBlock;
    }

    public boolean isGlowEnabled() {
        return glowEnabled;
    }

    public boolean isUseRealTime() {
        return useRealTime;
    }

    public int getPeriodDays() {
        return periodDays;
    }

    public List<String> getCommands() {
        return commands;
    }
}
