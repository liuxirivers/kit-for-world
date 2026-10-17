package io.github.randomgift.command;

import io.github.randomgift.RandomGiftPlugin;
import io.github.randomgift.manager.GiftManager;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /gift 管理员指令处理器
 * 权限统一为 randomgift.admin
 * 子命令：spawn、reload、cr [数量]
 */
public class GiftCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "randomgift.admin";
    private static final List<String> SUB_COMMANDS = Arrays.asList("spawn", "reload", "cr");

    private final RandomGiftPlugin plugin;

    public GiftCommand(RandomGiftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§c你没有执行该指令的权限！");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6===== RandomGift 管理指令 =====");
            sender.sendMessage("§e/gift spawn §7- 在脚下生成 1 个礼包（仅玩家）");
            sender.sendMessage("§e/gift reload §7- 重载 config.yml");
            sender.sendMessage("§e/gift cr [数量] §7- 清除旧礼包并在当前世界重新生成（可指定数量）");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> handleSpawn(sender);
            case "reload" -> handleReload(sender);
            case "cr" -> handleCr(sender, args);
            default -> sender.sendMessage("§c未知子指令，可用: spawn, reload, cr");
        }
        return true;
    }

    /**
     * /gift spawn - 在执行者脚下生成 1 个礼包
     */
    private void handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能执行此指令！");
            return;
        }

        boolean success = plugin.getGiftManager().spawnGift(player.getLocation());
        if (success) {
            plugin.getDataStore().save();
            player.sendMessage("§a已在你脚下生成 1 个礼包！");
            plugin.getLogger().info("管理员 " + player.getName() + " 在脚下生成了礼包");
        } else {
            player.sendMessage("§c礼包生成失败！");
        }
    }

    /**
     * /gift reload - 重载 config.yml 并同步发光状态与调度周期
     */
    private void handleReload(CommandSender sender) {
        plugin.getPluginConfig().reload();
        plugin.getGiftManager().applyGlowToExisting();
        plugin.restartScheduler();
        sender.sendMessage("§aconfig.yml 已重新加载，发光效果与调度周期已同步！");
        plugin.getLogger().info("管理员 " + sender.getName() + " 重载了配置文件");
    }

    /**
     * /gift cr [数量] - 清除旧礼包，并在执行者当前世界重新生成
     * 不带数量时使用 config.yml 的 spawn-count
     */
    private void handleCr(CommandSender sender, String[] args) {
        World world = (sender instanceof Player player)
                ? player.getWorld()
                : plugin.getPluginConfig().getTargetWorld();
        if (world == null) {
            sender.sendMessage("§c无法确定目标世界！");
            return;
        }

        // 解析可选数量参数（不随机，严格生成指定个数）
        int count = plugin.getPluginConfig().getSpawnCount();
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c数量必须是整数！用法: /gift cr [数量]");
                return;
            }
            if (count < 1 || count > GiftManager.MAX_MANUAL_COUNT) {
                sender.sendMessage("§c数量必须在 1 ~ " + GiftManager.MAX_MANUAL_COUNT + " 之间！");
                return;
            }
        }

        final int target = count;
        sender.sendMessage("§7正在清除旧礼包并预加载区块，请稍候...");
        plugin.getLogger().info("管理员 " + sender.getName() + " 执行 cr，世界: "
                + world.getName() + "，目标数量: " + target);

        // 异步：先清除（含卸载区块），再精确生成指定数量
        plugin.getGiftManager().clearAllGiftsAsync(removed -> {
            sender.sendMessage("§a已清除 " + removed + " 个旧礼包，正在生成 " + target + " 个新礼包...");
            plugin.getGiftManager().spawnBatchAsync(world, target, spawned -> {
                if (spawned >= target) {
                    sender.sendMessage("§a已在世界 " + world.getName() + " 生成 " + spawned + " 个礼包！");
                } else {
                    sender.sendMessage("§e实际生成 " + spawned + " / " + target
                            + " 个（范围内合法地表不足，详见控制台日志）");
                }
                plugin.getDataStore().setLastSpawnTime(System.currentTimeMillis());
                plugin.getDataStore().save();
                plugin.getLogger().info("管理员 cr 完成，世界: " + world.getName()
                        + "，清除: " + removed + "，生成: " + spawned + "/" + target);
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cr")) {
            // 提示数量参数示例
            return Arrays.asList("10", "20", "50");
        }
        return Collections.emptyList();
    }
}
