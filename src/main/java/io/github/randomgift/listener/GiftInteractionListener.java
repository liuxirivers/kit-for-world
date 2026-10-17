package io.github.randomgift.listener;

import io.github.randomgift.RandomGiftPlugin;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 玩家与礼包实体交互事件监听器
 *
 * 非 marker 盔甲架存在两条右键事件路径，必须分别处理：
 * 1. 空手右键 → {@link PlayerInteractEntityEvent}
 * 2. 手持物品右键 → {@link PlayerArmorStandManipulateEvent}（装备交换：
 *    会把手持物戴到盔甲架头上、把箱子头颅挤掉，必须显式取消）
 * 该事件是 PlayerInteractEntityEvent 的子类，父类处理器用 instanceof
 * 排除子类，避免同一次点击触发两次发奖。
 */
public class GiftInteractionListener implements Listener {

    private final RandomGiftPlugin plugin;

    /** 正在处理领取的实体 UUID，防止极端情况下同 tick 重复发奖 */
    private final java.util.Set<UUID> processing = ConcurrentHashMap.newKeySet();

    public GiftInteractionListener(RandomGiftPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 空手右键礼包
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // 装备交换事件由下方专用处理器处理，避免重复
        if (event instanceof PlayerArmorStandManipulateEvent) {
            return;
        }
        if (!(event.getRightClicked() instanceof ArmorStand stand)) {
            return;
        }
        if (!plugin.getGiftManager().isGiftEntity(stand)) {
            return;
        }
        // 取消盔甲架默认行为（切换姿态等）
        event.setCancelled(true);
        claimGift(event.getPlayer(), stand);
    }

    /**
     * 手持物品右键礼包：取消装备交换（防止头盔被取下/替换），但正常发奖
     */
    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!plugin.getGiftManager().isGiftEntity(event.getRightClicked())) {
            return;
        }
        // 关键：阻止玩家拿走/替换头盔方块
        event.setCancelled(true);
        claimGift(event.getPlayer(), event.getRightClicked());
    }

    /**
     * 伤害保护：礼包无敌（火焰、爆炸、攻击等一律取消）
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (plugin.getGiftManager().isGiftEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * 统一的礼包领取逻辑：计算随机金额 → 执行指令 → 移除实体
     */
    private void claimGift(Player player, ArmorStand stand) {
        UUID uuid = stand.getUniqueId();
        // 原子占位，防止同一点击的多个事件重复发奖
        if (!processing.add(uuid)) {
            return;
        }
        try {
            int min = plugin.getPluginConfig().getAmountMin();
            int max = plugin.getPluginConfig().getAmountMax();
            int amount = ThreadLocalRandom.current().nextInt(min, max + 1);

            List<String> commands = plugin.getPluginConfig().getCommands();
            for (String cmd : commands) {
                String resolved = cmd
                        .replace("{player}", player.getName())
                        .replace("{AMG}", String.valueOf(amount));
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), resolved);
            }

            plugin.getGiftManager().removeGift(uuid);

            plugin.getLogger().info("玩家 " + player.getName()
                    + " 领取了礼包，随机值: " + amount
                    + "，位置: " + stand.getLocation().getBlockX()
                    + "," + stand.getLocation().getBlockY()
                    + "," + stand.getLocation().getBlockZ());
        } finally {
            processing.remove(uuid);
        }
    }
}
