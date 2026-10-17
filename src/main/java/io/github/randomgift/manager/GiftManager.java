package io.github.randomgift.manager;

import io.github.randomgift.RandomGiftPlugin;
import io.github.randomgift.config.PluginConfig;
import io.github.randomgift.data.GiftDataStore;
import io.github.randomgift.data.GiftDataStore.GiftRecord;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.block.data.type.Snow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 礼包实体管理类
 *
 * 生成策略：每个礼包独立随机选择区块（优先未使用区块，保证分散），
 * 通过 {@link World#getChunkAtAsync} 异步预加载区块，加载完成后在区块内
 * 严格筛选地表位置生成；单个礼包在区块无合法位置时自动换区块重试，
 * 直到达到目标数量或尝试上限，保证生成数量尽量等于要求值。
 *
 * 清理策略：除遍历已加载实体外，依据持久化的区块坐标异步加载卸载区块，
 * 确保礼包数量与记录精确对账，不遗漏卸载区块中的实体。
 */
public class GiftManager {

    /** 单个礼包最多尝试的不同区块数（极端全海洋地形的防死循环上限） */
    private static final int MAX_CHUNK_ATTEMPTS_PER_GIFT = 10;
    /** 单个区块内最多探测的随机地表列数 */
    private static final int MAX_COLUMN_TRIES_PER_CHUNK = 64;
    /** /gift cr 手动指定数量的防呆上限 */
    public static final int MAX_MANUAL_COUNT = 10000;
    /** 从地面向上允许的最大探测深度（花/草/雪层等覆盖物） */
    private static final int GROUND_SCAN_DEPTH = 8;
    /** 薄雪层材质（兼容不同版本的材质命名） */
    private static final Material SNOW_LAYER = Material.matchMaterial("SNOW");

    private final RandomGiftPlugin plugin;
    private final PluginConfig config;
    private final GiftDataStore store;
    private final NamespacedKey giftKey;

    public GiftManager(RandomGiftPlugin plugin, PluginConfig config, GiftDataStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.giftKey = new NamespacedKey(plugin, "is-gift");
    }

    // ==================== 生成 ====================

    /**
     * 在指定位置同步生成一个礼包（调用时实体所在区块必须已加载）
     * @return 是否成功生成
     */
    public boolean spawnGift(Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        // 非 marker 不可见小盔甲架：保留碰撞箱（可右键），装备仍渲染（可见礼包方块）
        ArmorStand stand = (ArmorStand) world.spawnEntity(location, EntityType.ARMOR_STAND);
        stand.setMarker(false);
        stand.setVisible(false);
        stand.setSmall(true);
        stand.setGravity(false);
        stand.setCanPickupItems(false);
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.setPersistent(true);
        stand.setGlowing(config.isGlowEnabled());
        // PDC 双保险标签：即使追踪记录丢失也能识别本插件实体
        stand.getPersistentDataContainer().set(giftKey, PersistentDataType.BYTE, (byte) 1);

        // 头盔方块材质（客户端 12 月自动显示圣诞礼物贴图）
        ItemStack helmet = new ItemStack(config.getHelmetBlock(), 1);
        ItemMeta meta = helmet.getItemMeta();
        if (meta != null) {
            meta.setItemModel(config.getHelmetBlock().getKey());
            helmet.setItemMeta(meta);
        }
        stand.getEquipment().setHelmet(helmet, true);

        // 记录 UUID + 世界 + 区块坐标（写盘由调用方统一执行，避免批量生成频繁 IO）
        int cx = location.getBlockX() >> 4;
        int cz = location.getBlockZ() >> 4;
        store.add(stand.getUniqueId(), world.getName(), cx, cz);
        return true;
    }

    /**
     * 异步生成指定数量的礼包。
     * 每个礼包独立选块、独立重试，最终回调实际成功数量。
     *
     * @param world 目标世界
     * @param target 目标数量（必须 ≥ 1）
     * @param callback 全部尝试结束后的回调（主线程执行），参数为实际生成数量
     */
    public void spawnBatchAsync(World world, int target, Consumer<Integer> callback) {
        if (target <= 0) {
            callback.accept(0);
            return;
        }
        new SpawnBatch(world, target, callback).start();
    }

    /**
     * 单批次异步生成的状态容器
     */
    private final class SpawnBatch {
        private final World world;
        private final int target;
        private final Consumer<Integer> callback;
        private final int minBx, minBz, maxBx, maxBz;
        private final Set<Long> usedChunks = new HashSet<>();
        private final AtomicInteger success = new AtomicInteger(0);
        private final AtomicInteger chainsLeft;

        SpawnBatch(World world, int target, Consumer<Integer> callback) {
            this.world = world;
            this.target = target;
            this.callback = callback;
            this.chainsLeft = new AtomicInteger(target);
            Location spawn = world.getSpawnLocation();
            int range = config.getSpawnRange();
            this.minBx = spawn.getBlockX() - range;
            this.maxBx = spawn.getBlockX() + range;
            this.minBz = spawn.getBlockZ() - range;
            this.maxBz = spawn.getBlockZ() + range;
        }

        void start() {
            // 每个礼包一条独立"尝试链"，彼此并发预加载不同区块
            for (int i = 0; i < target; i++) {
                nextTry(new AtomicInteger(0));
            }
        }

        /**
         * 尝试一次：随机选区块 → 异步预加载 → 区块内寻找地表 → 生成
         */
        void nextTry(AtomicInteger tries) {
            // 已达标或超过单礼包尝试上限，结束本条链
            if (success.get() >= target
                    || tries.incrementAndGet() > MAX_CHUNK_ATTEMPTS_PER_GIFT) {
                chainDone();
                return;
            }
            long chunkKey = pickChunk();
            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;

            // gen=true：未生成地形的区块先异步生成/加载，加载完成后再生成礼包
            world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, ex) -> {
                if (ex != null) {
                    plugin.getLogger().warning("预加载区块 " + cx + "," + cz + " 失败: " + ex.getMessage());
                    nextTry(tries);
                    return;
                }
                Location loc = findSurfaceInChunk(chunk, cx, cz);
                if (loc != null && success.get() < target && spawnGift(loc)) {
                    success.incrementAndGet();
                    chainDone();
                } else {
                    // 该区块没有合法地表位置，换一个区块重试
                    nextTry(tries);
                }
            });
        }

        void chainDone() {
            if (chainsLeft.decrementAndGet() == 0) {
                int actual = success.get();
                store.save();
                callback.accept(actual);
            }
        }

        /**
         * 在范围内随机选择区块，优先选择本批次未使用的区块以保证分散
         */
        private long pickChunk() {
            int minCX = Math.floorDiv(minBx, 16);
            int maxCX = Math.floorDiv(maxBx, 16);
            int minCZ = Math.floorDiv(minBz, 16);
            int maxCZ = Math.floorDiv(maxBz, 16);
            var random = ThreadLocalRandom.current();
            for (int i = 0; i < 6; i++) {
                int cx = minCX + random.nextInt(maxCX - minCX + 1);
                int cz = minCZ + random.nextInt(maxCZ - minCZ + 1);
                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (usedChunks.add(key)) {
                    return key;
                }
            }
            int cx = minCX + random.nextInt(maxCX - minCX + 1);
            int cz = minCZ + random.nextInt(maxCZ - minCZ + 1);
            usedChunks.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }
    }

    /**
     * 在已加载区块内随机筛选合法地表位置
     * 条件：地面是完整实体方块、脚部与头部位置无方块、头顶露天
     */
    private Location findSurfaceInChunk(Chunk chunk, int cx, int cz) {
        World world = chunk.getWorld();
        // 打乱区块内 16x16 列的探测顺序
        List<Integer> order = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) order.add(i);
        Collections.shuffle(order);
        int limit = Math.min(MAX_COLUMN_TRIES_PER_CHUNK, order.size());
        for (int i = 0; i < limit; i++) {
            int v = order.get(i);
            int wx = (cx << 4) + (v & 15);
            int wz = (cz << 4) + (v >> 4);
            // 跨范围边界的区块只取范围内的列
            if (wx < getMinBlockX(world) || wx > getMaxBlockX(world)
                    || wz < getMinBlockZ(world) || wz > getMaxBlockZ(world)) {
                continue;
            }
            Location loc = validateColumn(world, wx, wz);
            if (loc != null) return loc;
        }
        return null;
    }

    // 范围边界缓存（每批次调用量较大时直接算也很廉价，保持无状态）
    private int getMinBlockX(World world) {
        return world.getSpawnLocation().getBlockX() - config.getSpawnRange();
    }

    private int getMaxBlockX(World world) {
        return world.getSpawnLocation().getBlockX() + config.getSpawnRange();
    }

    private int getMinBlockZ(World world) {
        return world.getSpawnLocation().getBlockZ() - config.getSpawnRange();
    }

    private int getMaxBlockZ(World world) {
        return world.getSpawnLocation().getBlockZ() + config.getSpawnRange();
    }

    /**
     * 校验单个 (x,z) 列是否存在合法地表，返回礼包实体生成位置。
     *
     * 判定规则：
     * 1. 从该列最高方块向下扫描，跳过花草等无碰撞覆盖物，遇液体直接放弃整列；
     * 2. 地面必须是完整一格高、有碰撞箱的实体方块（排除台阶、雪层、栅栏、耕地）；
     * 3. 脚部格只允许空气/可通行装饰物，或恰好 1 层薄雪（实体落在雪面上）；
     *    多层雪或任何实体方块包围一律拒绝，防止卡在坑里/方块内；
     * 4. 头部格（y+2）必须为空气或可通行装饰物，保证头顶露天、射线可点击；
     * 5. 实体 Y 精确对齐实际可站立面（方块顶面或薄雪顶面），绝不下陷。
     *
     * @return 脚部精确对齐站立面的位置；不合法返回 null
     */
    private Location validateColumn(World world, int x, int z) {
        int startY = world.getHighestBlockYAt(x, z);
        int bottom = world.getMinHeight();

        for (int y = startY; y >= bottom && y > startY - GROUND_SCAN_DEPTH; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Material type = ground.getType();
            if (type.isAir()) continue;
            if (isLiquid(type)) return null;
            if (!type.isSolid() || !ground.isCollidable()) {
                // 花、草、火把等无碰撞覆盖物，继续向下找真正地面
                continue;
            }
            // 只要完整一格高的方块顶面，排除台阶/雪层/栅栏/耕地碰撞箱等异形方块
            BoundingBox box = ground.getBoundingBox();
            if (Math.abs(box.getMaxY() - (y + 1.0)) > 1.0E-4) continue;

            // 脚部格（y+1）：计算实际站立高度
            Block feet = world.getBlockAt(x, y + 1, z);
            double standOnY = y + 1.0;
            Material feetType = feet.getType();
            if (!feetType.isAir()) {
                if (isLiquid(feetType)) return null;
                if (feetType == SNOW_LAYER && feet.getBlockData() instanceof Snow snow) {
                    // 仅允许 1 层薄雪作为脚垫，实体抬高到雪面；多层雪会埋住实体，拒绝
                    if (snow.getLayers() != 1) return null;
                    standOnY += 2.0 / 16.0;
                } else if (!feet.isPassable()) {
                    // 实体方块包围（坑洞、悬垂、厚雪层），点击会被挡住，拒绝
                    return null;
                }
            }

            // 头部格（y+2）必须可通行且非液体，保证露天可见、可右键
            Block head = world.getBlockAt(x, y + 2, z);
            Material headType = head.getType();
            if (isLiquid(headType) || !head.isPassable()) {
                return null;
            }

            return new Location(world, x + 0.5, standOnY, z + 0.5);
        }
        return null;
    }

    /**
     * 是否为液体类方块
     */
    private boolean isLiquid(Material type) {
        return type == Material.WATER || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN;
    }

    // ==================== 清理 ====================

    /**
     * 异步清除全部礼包（用于运行期：/gift cr、周期生成前、启动残留清理）。
     * 已加载实体立即删除；卸载区块依据持久化坐标异步加载后删除。
     *
     * @param callback 完成回调（主线程），参数为实际删除数量
     */
    public void clearAllGiftsAsync(Consumer<Integer> callback) {
        AtomicInteger removed = new AtomicInteger(0);

        // 阶段 1：同步扫描所有已加载世界的实体（PDC 标签兜底识别孤儿实体）
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isOurGift(entity)) {
                    entity.remove();
                    store.remove(entity.getUniqueId());
                    removed.incrementAndGet();
                }
            }
        }

        // 阶段 2：剩余记录位于未加载区块，按 世界+区块 分组异步加载
        Map<ChunkRef, List<GiftRecord>> pending = new java.util.HashMap<>();
        for (GiftRecord record : store.getRecords()) {
            if (record.chunkX() == GiftDataStore.UNKNOWN_CHUNK
                    || record.chunkZ() == GiftDataStore.UNKNOWN_CHUNK) {
                // 旧版本无坐标记录，无法精确定位，丢弃避免计数永久失真
                plugin.getLogger().warning("丢弃无区块坐标的旧版礼包记录: " + record.uuid());
                store.remove(record.uuid());
                continue;
            }
            World world = Bukkit.getWorld(record.world());
            if (world == null) {
                // 世界当前未加载，保留记录待下次处理
                continue;
            }
            pending.computeIfAbsent(
                    new ChunkRef(record.world(), record.chunkX(), record.chunkZ()),
                    k -> new ArrayList<>()).add(record);
        }
        store.save();

        if (pending.isEmpty()) {
            callback.accept(removed.get());
            return;
        }

        AtomicInteger chunksLeft = new AtomicInteger(pending.size());
        for (Map.Entry<ChunkRef, List<GiftRecord>> entry : pending.entrySet()) {
            ChunkRef ref = entry.getKey();
            World world = Bukkit.getWorld(ref.world());
            if (world == null) {
                if (chunksLeft.decrementAndGet() == 0) callback.accept(removed.get());
                continue;
            }
            // gen=false：仅加载已生成的区块，不触发生成新地形
            world.getChunkAtAsync(ref.chunkX(), ref.chunkZ(), false)
                    .whenComplete((chunk, ex) -> {
                        try {
                            if (ex != null) {
                                plugin.getLogger().log(Level.WARNING,
                                        "清理时加载区块 " + ref.chunkX() + "," + ref.chunkZ()
                                                + " 失败，该批记录保留待下次清理", ex);
                            } else if (chunk != null) {
                                for (Entity entity : chunk.getEntities()) {
                                    if (isOurGift(entity)) {
                                        entity.remove();
                                        removed.incrementAndGet();
                                    }
                                }
                                // 区块已成功加载：无论实体是否还存在（可能被外部清除），
                                // 记录都应对账移除，保证数量一致
                                for (GiftRecord record : entry.getValue()) {
                                    store.remove(record.uuid());
                                }
                                store.save();
                            }
                        } finally {
                            if (chunksLeft.decrementAndGet() == 0) {
                                callback.accept(removed.get());
                            }
                        }
                    });
        }
    }

    /**
     * 同步清除全部礼包（仅用于 onDisable 服务器关闭流程）。
     * 卸载区块通过同步加载读取后删除；任何异常都不得阻止服务器关闭，
     * 失败记录保留并在下次启用时由异步清理兜底。
     *
     * @return 实际删除数量
     */
    public int clearAllGifts() {
        int removed = 0;

        // 已加载实体快扫
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isOurGift(entity)) {
                    entity.remove();
                    store.remove(entity.getUniqueId());
                    removed++;
                }
            }
        }

        // 未加载记录：同步加载对应区块后删除
        for (GiftRecord record : store.getRecords()) {
            if (record.chunkX() == GiftDataStore.UNKNOWN_CHUNK
                    || record.chunkZ() == GiftDataStore.UNKNOWN_CHUNK) {
                store.remove(record.uuid());
                continue;
            }
            World world = Bukkit.getWorld(record.world());
            if (world == null) continue;
            try {
                Chunk chunk = world.getChunkAt(record.chunkX(), record.chunkZ());
                for (Entity entity : chunk.getEntities()) {
                    if (isOurGift(entity)) {
                        entity.remove();
                        removed++;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "关闭时清理区块 " + record.chunkX() + "," + record.chunkZ()
                                + " 的礼包失败，将在下次启动时清理", e);
                continue;
            }
            store.remove(record.uuid());
        }
        store.save();
        return removed;
    }

    /**
     * 判断指定实体是否为本插件生成的礼包（供监听器使用，含旧版特征识别）
     */
    public boolean isGiftEntity(Entity entity) {
        return isOurGift(entity);
    }

    /**
     * 移除单个礼包实体（玩家右键领取时调用，此时实体必然处于已加载区块）
     */
    public void removeGift(java.util.UUID uuid) {
        // 按 UUID 精确匹配删除（旧版无记录的孤儿实体同样可删）
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(uuid)) {
                    entity.remove();
                    break;
                }
            }
        }
        store.remove(uuid);
        store.save();
    }

    /**
     * 判断指定 UUID 是否有追踪记录
     */
    public boolean isGift(java.util.UUID uuid) {
        return store.contains(uuid);
    }

    /**
     * 实体是否归属本插件：
     * 1. 追踪记录命中；
     * 2. PDC 标签命中（新版本实体）；
     * 3. 旧版本特征指纹命中（无 PDC 的历史孤儿实体，仅扫描目标世界，
     *    组合条件玩家几乎不可能手动构造，误伤风险极低）。
     */
    private boolean isOurGift(Entity entity) {
        if (!(entity instanceof ArmorStand stand)) return false;
        if (store.contains(stand.getUniqueId())) return true;
        if (stand.getPersistentDataContainer().has(giftKey, PersistentDataType.BYTE)) {
            return true;
        }
        return isLegacyGift(stand);
    }

    /**
     * 识别旧版本插件生成、但无 PDC 标签的残留礼包：
     * 隐形 + 小型 + 无重力 + 不可碰撞 + 头盔为配置方块，且位于配置的目标世界。
     */
    private boolean isLegacyGift(ArmorStand stand) {
        // 仅在目标世界启用兜底，避免误伤其他世界的装饰盔甲架
        if (!stand.getWorld().getName().equals(config.getTargetWorldName())) {
            return false;
        }
        var equipment = stand.getEquipment();
        if (equipment == null) return false;
        ItemStack helmet = equipment.getHelmet();
        if (helmet == null || helmet.getType() != config.getHelmetBlock()) {
            return false;
        }
        return !stand.isVisible()
                && stand.isSmall()
                && !stand.hasGravity()
                && !stand.isCollidable();
    }

    /**
     * 按当前配置同步已有礼包的发光状态（/gift reload 后使用）
     */
    public void applyGlowToExisting() {
        boolean glow = config.isGlowEnabled();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ArmorStand && isOurGift(entity)) {
                    entity.setGlowing(glow);
                }
            }
        }
    }

    /**
     * 区块引用键
     */
    private record ChunkRef(String world, int chunkX, int chunkZ) {
    }
}
