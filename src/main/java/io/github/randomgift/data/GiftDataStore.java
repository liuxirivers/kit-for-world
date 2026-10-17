package io.github.randomgift.data;

import io.github.randomgift.RandomGiftPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 礼包持久化数据存储（gift-data.yml）
 * 记录每个礼包的 UUID、所在世界与区块坐标，使卸载区块中的实体也能被精确定位清理；
 * 同时持久化现实时间模式的上次生成时间。
 */
public class GiftDataStore {

    /** 区块坐标未知标记（旧版本数据迁移用） */
    public static final int UNKNOWN_CHUNK = Integer.MIN_VALUE;

    /**
     * 单个礼包的追踪记录
     */
    public record GiftRecord(UUID uuid, String world, int chunkX, int chunkZ) {
    }

    private final RandomGiftPlugin plugin;
    private final File dataFile;

    /** UUID -> 追踪记录，线程安全集合 */
    private final Map<UUID, GiftRecord> records = new ConcurrentHashMap<>();

    /** 现实时间模式的上次生成时间戳（毫秒），0 表示从未生成 */
    private volatile long lastSpawnTime;

    public GiftDataStore(RandomGiftPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "gift-data.yml");
    }

    /**
     * 加载数据
     * @param defaultWorldName 旧格式数据缺失世界字段时使用的默认世界名
     */
    public void load(String defaultWorldName) {
        records.clear();
        lastSpawnTime = 0;
        if (!dataFile.exists()) {
            return;
        }
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
            lastSpawnTime = yml.getLong("last-spawn-time", 0);

            // 新格式：gifts 节点含 uuid/world/chunk-x/chunk-z
            ConfigurationSection section = yml.getConfigurationSection("gifts");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection entry = section.getConfigurationSection(key);
                    if (entry == null) continue;
                    String uuidStr = entry.getString("uuid");
                    if (uuidStr == null) continue;
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        String world = entry.getString("world", defaultWorldName);
                        int cx = entry.getInt("chunk-x", UNKNOWN_CHUNK);
                        int cz = entry.getInt("chunk-z", UNKNOWN_CHUNK);
                        records.put(uuid, new GiftRecord(uuid, world, cx, cz));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("gift-data.yml 中存在无效 UUID: " + uuidStr);
                    }
                }
            }

            // 兼容旧格式：gift-entities: [uuid, ...]（无区块坐标）
            for (String uuidStr : yml.getStringList("gift-entities")) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    records.putIfAbsent(uuid,
                            new GiftRecord(uuid, defaultWorldName, UNKNOWN_CHUNK, UNKNOWN_CHUNK));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("gift-data.yml 中存在无效 UUID: " + uuidStr);
                }
            }

            plugin.getLogger().info("已加载 " + records.size() + " 条礼包追踪记录");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "加载 gift-data.yml 失败，已忽略", e);
            records.clear();
            lastSpawnTime = 0;
        }
    }

    /**
     * 保存数据到磁盘
     */
    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("last-spawn-time", lastSpawnTime);
        int i = 0;
        for (GiftRecord r : records.values()) {
            String base = "gifts." + i++;
            yml.set(base + ".uuid", r.uuid().toString());
            yml.set(base + ".world", r.world());
            yml.set(base + ".chunk-x", r.chunkX());
            yml.set(base + ".chunk-z", r.chunkZ());
        }
        try {
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "保存 gift-data.yml 失败", e);
        }
    }

    public void add(UUID uuid, String world, int chunkX, int chunkZ) {
        records.put(uuid, new GiftRecord(uuid, world, chunkX, chunkZ));
    }

    public void remove(UUID uuid) {
        records.remove(uuid);
    }

    public boolean contains(UUID uuid) {
        return records.containsKey(uuid);
    }

    public GiftRecord get(UUID uuid) {
        return records.get(uuid);
    }

    public List<GiftRecord> getRecords() {
        return new ArrayList<>(records.values());
    }

    public void clear() {
        records.clear();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public int size() {
        return records.size();
    }

    public long getLastSpawnTime() {
        return lastSpawnTime;
    }

    public void setLastSpawnTime(long lastSpawnTime) {
        this.lastSpawnTime = lastSpawnTime;
    }
}
