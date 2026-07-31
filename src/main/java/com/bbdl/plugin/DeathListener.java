package com.bbdl.plugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 核心事件监听器：处理玩家死亡和复活两个关键事件。
 *
 * <p>职责：
 * <ul>
 *   <li>死亡时：取消原版死亡消息 → 发送自定义广播 → 记录死亡位置和背包 → 持久化</li>
 *   <li>复活时：如果玩家开启了复活提醒，发送上次死亡位置</li>
 * </ul>
 *
 * <p>设计意图：
 * <ul>
 *   <li>将事件逻辑独立成类，保持主类 Memento 只做生命周期管理。</li>
 *   <li>所有共享实例（msg / dm）均引用主类已创建的单例，不额外 new。</li>
 *   <li>广播逻辑支持"按观众过滤"：每个在线玩家独立判断是否能看到这条死亡消息。</li>
 * </ul>
 */
public class DeathListener implements Listener {

    /** 主插件实例，用于访问共享数据（Map）、配置和服务器对象 */
    private final Memento plugin;
    /** 消息格式化工具（复用主类实例，不重复创建） */
    private final MessageUtil msg;
    /** 数据持久化工具（复用主类实例，不重复创建） */
    private final DataManager dm;

    /**
     * 构造时引用主类已初始化好的共享实例。
     * 注意：必须在 Memento.onEnable() 中 msg / dataManager 赋值之后再 new 本类。
     */
    public DeathListener(Memento plugin) {
        this.plugin = plugin;
        this.msg = plugin.msg;
        this.dm = plugin.dataManager;
    }

    // ==================== 玩家死亡事件 ====================

    /**
     * 玩家死亡时的核心处理逻辑。
     *
     * <p>执行流程：
     * <ol>
     *   <li>取消原版死亡消息（避免重复显示）</li>
     *   <li>判断死因类型（被玩家杀 / 环境死亡）</li>
     *   <li>构建自定义死亡消息（优先用玩家自己设置的模板，否则用 config 默认模板）</li>
     *   <li>按观众过滤后广播（每个在线玩家独立判断是否可见）</li>
     *   <li>记录死亡位置到内存</li>
     *   <li>克隆并记录死亡时的背包到内存</li>
     *   <li>输出调试日志</li>
     *   <li>持久化到磁盘</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        // getKiller()：如果是被其他玩家击杀则返回击杀者，否则返回 null
        Player killer = player.getKiller();

        // [Debug] 死亡事件入口
        msg.debug("messages.debug.death-event-enter",
                "onPlayerDeath 触发: {player}",
                Map.of("player", player.getName()));

        // 拦截死亡掉落
        if (player.hasPermission("Memento.keepInv")) {
            // 禁止掉落
            event.getDrops().clear();
            event.setKeepInventory(true);
            // [Debug] keepInv 生效
            msg.debug("messages.debug.keep-inv-applied",
                    "{player} 触发 keepInv，掉落已拦截",
                    Map.of("player", player.getName()));
        }

        // 拦截掉落经验
        if (player.hasPermission("Memento.keepXp")) {
            event.setKeepLevel(true);
            // [Debug] keepXp 生效
            msg.debug("messages.debug.keep-xp-applied",
                    "{player} 触发 keepXp，经验已拦截",
                    Map.of("player", player.getName()));
        }

        // 取消原版死亡消息（如 "Steve was slain by Notch"），由本插件接管广播
        // 传 null 表示不显示任何原版消息
        event.deathMessage(null);

        // [Debug] 广播观众计数
        int viewerCount = 0;

        // ===== 分支 1：被其他玩家击杀 =====
        if (killer != null) {
            // 获取击杀者主手中的武器（用于消息中的 {weapon} 占位符）
            ItemStack weaponItem = killer.getInventory().getItemInMainHand();
            // 取物品类型名（如 DIAMOND_SWORD），后续可在消息模板中自行映射为中文
            String weaponName = weaponItem.getType().name();

            // [Debug] 进入玩家击杀分支
            Map<String, String> killDbg = new HashMap<>();
            killDbg.put("player", player.getName());
            killDbg.put("killer", killer.getName());
            killDbg.put("weapon", weaponName);
            msg.debug("messages.debug.branch-player-kill",
                    "分支: 玩家击杀 | 死者={player} 击杀者={killer} 武器={weapon}", killDbg);

            // 构建占位符映射
            Map<String, String> ph = new HashMap<>();
            ph.put("player", player.getName());   // 死者名
            ph.put("killer", killer.getName());   // 击杀者名
            ph.put("weapon", weaponName);         // 武器类型名

            // 构建最终的死亡消息 Component
            Component deathMsg;
            // 优先使用玩家自己设置的自定义消息模板
            CustomDeathMsg customMsg = plugin.customDeathMsgMap.get(player.getUniqueId());
            if (customMsg != null && customMsg.byPlayer != null) {
                // 有自定义模板 → 用自定义模板渲染（key 传 null，直接用 customMsg.byPlayer 作为模板）
                deathMsg = msg.formatMsg(null, customMsg.byPlayer, ph, player);
                // [Debug] 使用了自定义模板
                msg.debug("messages.debug.custom-template-used",
                        "{player} 使用了自定义 byPlayer 模板",
                        Map.of("player", player.getName()));
            } else {
                // 没有自定义模板 → 用 config 中的默认模板
                deathMsg = msg.formatMsg("messages.normal.death-by-player",
                        "{player} 被 {killer} 用 {weapon} 杀死", ph, null);
            }

            // 按观众过滤后广播：遍历所有在线玩家，逐个判断是否可见
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                // 可见条件（满足其一即可）：
                // 1. 有 Memento.viewall 权限（管理员强制看所有）
                // 2. 该观众自己的全局广播开关为 true（默认 true）
                if (viewer.hasPermission("Memento.viewall")

                        || plugin.deathMessages.getOrDefault(viewer.getUniqueId(), true)) {
                    viewer.sendMessage(deathMsg);
                    viewerCount++;
                }
            }
        }
        // ===== 分支 2：非玩家击杀（环境、怪物、坠落等） =====
        else {
            // 获取最后一次伤害原因（如 FALL、DROWNING、ENTITY_ATTACK 等）
            EntityDamageEvent lastDamage = player.getLastDamageCause();
            // 防御性处理：极端情况下 lastDamage 可能为 null
            String causeName = (lastDamage != null) ? lastDamage.getCause().name() : "UNKNOWN";

            // [Debug] 进入环境死亡分支
            Map<String, String> causeDbg = new HashMap<>();
            causeDbg.put("player", player.getName());
            causeDbg.put("cause", causeName);
            msg.debug("messages.debug.branch-env-death",
                    "分支: 环境死亡 | 死者={player} 死因={cause}", causeDbg);

            // 构建占位符映射
            Map<String, String> ph = new HashMap<>();
            ph.put("player", player.getName());  // 死者名
            ph.put("cause", causeName);          // 死因枚举名（如 FALL、LAVA）

            // 构建最终的死亡消息 Component
            Component deathMsg;
            // 优先使用玩家自己设置的自定义消息模板
            CustomDeathMsg customMsg = plugin.customDeathMsgMap.get(player.getUniqueId());
            if (customMsg != null && customMsg.byCause != null) {
                deathMsg = msg.formatMsg(null, customMsg.byCause, ph, player);
                // [Debug] 使用了自定义模板
                msg.debug("messages.debug.custom-template-used",
                        "{player} 使用了自定义 byCause 模板",
                        Map.of("player", player.getName()));
            } else {
                deathMsg = msg.formatMsg("messages.normal.death-by-cause",
                        "{player} 因为 {cause} 死亡", ph, null);
            }

            // 同样按观众过滤后广播
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (viewer.hasPermission("Memento.viewall")

                        || plugin.deathMessages.getOrDefault(viewer.getUniqueId(), true)) {
                    viewer.sendMessage(deathMsg);
                    viewerCount++;
                }
            }
        }

        // [Debug] 广播完成
        msg.debug("messages.debug.broadcast-done",
                "广播完成，共 {count} 人收到",
                Map.of("count", String.valueOf(viewerCount)));

        // ===== 记录死亡数据到内存 =====

        // 记录死亡位置（用于 /ld 查询和复活提醒）
        Location deathLoc = player.getLocation();

        // 克隆死亡时的背包内容
        // 意图：必须 clone()，因为玩家复活后背包会被清空/改变，
        // 如果不克隆，引用的 ItemStack 对象会随之变化，导致保存的数据不正确
        ItemStack[] oldinv = player.getInventory().getContents();
        ItemStack[] inv = new ItemStack[oldinv.length];
        int nonNullSlots = 0;
        for (int i = 0; i < oldinv.length; i++) {
            if (oldinv[i] != null) {
                inv[i] = oldinv[i].clone();  // 深拷贝，切断与原背包的引用关系
                nonNullSlots++;
            }
            // null 槽位保持 null（表示该位置为空）
        }
        // 写入内存缓存（覆盖上一次的死亡背包，只保留最新一次）
        plugin.deathInv.put(player.getUniqueId(), inv);

        // [Debug] 背包克隆完成
        Map<String, String> invDbg = new HashMap<>();
        invDbg.put("player", player.getName());
        invDbg.put("slots", String.valueOf(nonNullSlots));
        invDbg.put("total", String.valueOf(oldinv.length));
        msg.debug("messages.debug.inv-clone-done",
                "{player} 背包克隆完成: {slots}/{total} 非空", invDbg);

        // 输出调试日志（方便服主在控制台确认死亡事件是否正确触发）
        Map<String, String> logPh = new HashMap<>();
        logPh.put("player", player.getName());
        logPh.put("world", deathLoc.getWorld().getName());
        logPh.put("x", String.valueOf(deathLoc.getBlockX()));
        logPh.put("y", String.valueOf(deathLoc.getBlockY()));
        logPh.put("z", String.valueOf(deathLoc.getBlockZ()));
        plugin.logger.info(msg.formatMsg("messages.debug.death-log",
                "{player} 死亡！位置: {world} {x} {y} {z}", logPh, null));

        // 将死亡位置写入内存缓存（覆盖上一次的死亡位置）
        plugin.deathLocations.put(player.getUniqueId(), deathLoc);

        // 持久化到磁盘（mode="death" → 生成新的时间戳文件，保存位置 + 背包）
        dm.saveData(player.getUniqueId(), "death");

        // [Debug] 持久化完成
        msg.debug("messages.debug.save-done",
                "{player} 数据已写入磁盘",
                Map.of("player", player.getName()));
    }

    // ==================== 玩家复活事件 ====================

    /**
     * 玩家复活时的处理逻辑。
     *
     * <p>功能：如果玩家开启了"复活提醒"（默认开启），在复活时自动发送上次死亡位置。
     * 这样玩家无需手动输入 /ld，复活后立刻就知道自己死在哪了。
     *
     * <p>注意：PlayerRespawnEvent 在玩家实际传送回重生点之前触发，
     * 此时发送消息是安全的（玩家已经能看到聊天栏）。
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // [Debug] 复活事件入口
        msg.debug("messages.debug.respawn-event-enter",
                "onPlayerRespawn 触发: {player}",
                Map.of("player", player.getName()));

        // 从内存缓存中获取上次死亡位置
        Location deathLoc = plugin.deathLocations.get(player.getUniqueId());
        // 获取该玩家的复活提醒开关（默认 true = 开启）
        boolean enabled = plugin.respawnNotify.getOrDefault(player.getUniqueId(), true);

        // 两个条件都满足才发送提醒：有死亡记录 且 开关开启
        if (deathLoc != null && enabled) {
            Map<String, String> ph = new HashMap<>();
            ph.put("world", deathLoc.getWorld().getName());
            ph.put("x", String.valueOf(deathLoc.getBlockX()));
            ph.put("y", String.valueOf(deathLoc.getBlockY()));
            ph.put("z", String.valueOf(deathLoc.getBlockZ()));
            // 发送复活提醒消息（走消息系统，支持 config 自定义和 MiniMessage 颜色）
            player.sendMessage(msg.formatMsg("messages.normal.respawn-reminder",
                    "你上次死在了 {world} 的 {x} {y} {z}", ph, player));

            // [Debug] 复活提醒已发送
            Map<String, String> sentDbg = new HashMap<>();
            sentDbg.put("player", player.getName());
            sentDbg.put("world", deathLoc.getWorld().getName());
            sentDbg.put("x", String.valueOf(deathLoc.getBlockX()));
            sentDbg.put("y", String.valueOf(deathLoc.getBlockY()));
            sentDbg.put("z", String.valueOf(deathLoc.getBlockZ()));
            msg.debug("messages.debug.respawn-notify-sent",
                    "复活提醒已发送: {player} → {world} {x} {y} {z}", sentDbg);
        } else {
            // [Debug] 复活提醒跳过
            String reason = (deathLoc == null) ? "无死亡记录" : "提醒开关已关闭";
            msg.debug("messages.debug.respawn-notify-skipped",
                    "复活提醒跳过: {player}，原因: {reason}",
                    Map.of("player", player.getName(), "reason", reason));
        }
    }
}