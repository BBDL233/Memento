package com.bbdl.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUI 交互监听器：处理死亡背包查看界面的点击、拖拽和关闭事件。
 *
 * <p>职责：
 * <ul>
 *   <li>拦截点击：防止玩家从 GUI 中拿走物品（只读展示）</li>
 *   <li>拦截拖拽：防止玩家通过拖拽方式移动 GUI 中的物品</li>
 *   <li>处理点击动作：翻页（next/prev）、关闭（close）、执行自定义指令</li>
 *   <li>处理关闭事件：清理翻页缓存，释放内存</li>
 * </ul>
 *
 * <p>设计意图：
 * <ul>
 *   <li>通过 DeathGuiHolder 实例判断来精确识别本插件的 GUI，不影响其他插件或原版容器。</li>
 *   <li>关闭事件使用 1 tick 延迟检测，解决"翻页时旧 GUI 关闭触发清理"的竞态问题。</li>
 *   <li>所有点击动作由 config 中的 menus/ 文件驱动，无需修改代码即可自定义按钮行为。</li>
 * </ul>
 */
public class GuiListener implements Listener {

    /** 主插件实例，用于访问菜单配置（menuClickActions）和翻页缓存（playerPageInv） */
    private final Memento plugin;

    /**
     * 构造时引用主类实例。
     * 注意：本类不需要 msg / dm，因为 GUI 交互不涉及消息发送或数据持久化。
     */
    public GuiListener(Memento plugin) {
        this.plugin = plugin;
    }

    // ==================== 点击事件 ====================

    /**
     * 拦截并处理 GUI 中的点击行为。
     *
     * <p>处理流程：
     * <ol>
     *   <li>判断是否是本插件的 GUI（通过 DeathGuiHolder 实例判断）</li>
     *   <li>取消事件（防止玩家拿走展示的物品）</li>
     *   <li>查找该格子配置的点击动作</li>
     *   <li>根据动作类型分流：翻页 / 关闭 / 执行指令</li>
     * </ol>
     *
     * <p>注意：event.setCancelled(true) 会阻止所有默认的点击行为
     * （包括拿取、放置、交换等），确保 GUI 是纯只读的。
     */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // 只处理本插件的 GUI，其他容器（箱子、背包、其他插件 GUI）直接放行
        if (!(event.getInventory().getHolder() instanceof DeathGuiHolder)) return;

        // 取消点击事件：防止玩家从 GUI 中拿走物品
        // 这是"只读展示"的核心保障
        event.setCancelled(true);

        // 获取当前页码（从 DeathGuiHolder 中读取）
        int page = ((DeathGuiHolder) event.getInventory().getHolder()).page();
        // 获取点击的原始槽位编号（getRawSlot 是相对于顶部容器的绝对槽位）
        int slot = event.getRawSlot();

        // 从菜单配置中查找该页的点击动作映射
        // menuClickActions 结构：Map<页码, Map<槽位, List<指令>>>
        Map<Integer, List<String>> pageMap = plugin.menuClickActions.get(page);
        // 该页没有配置任何点击动作 → 不做任何处理（纯展示格子）
        if (pageMap == null) return;

        // 查找该槽位配置的指令列表
        List<String> commands = pageMap.get(slot);
        // 该格子没有配置点击动作 → 不做任何处理
        if (commands == null) return;

        // 取第一条指令判断类型（特殊关键字：next / prev / close）
        String command = commands.getFirst();

        // ===== 翻页动作：next（下一页）或 prev（上一页） =====
        if (command.equalsIgnoreCase("next") || command.equalsIgnoreCase("prev")) {
            // 从翻页缓存中获取该玩家的所有页面 Inventory 列表
            // playerPageInv 结构：Map<玩家UUID, List<Inventory>>（按页码顺序排列）
            List<Inventory> invs = plugin.playerPageInv.get(event.getWhoClicked().getUniqueId());
            // 缓存不存在（理论上不会发生，因为打开 GUI 时一定会写入缓存）→ 防御性返回
            if (invs == null) return;

            // 计算目标页码：next → 当前页+1，prev → 当前页-1
            int target = command.equalsIgnoreCase("next") ? page + 1 : page - 1;
            // 边界检查：防止越界（第一页不能再 prev，最后一页不能再 next）
            if (target < 0 || target >= invs.size()) return;

            // 打开目标页的 Inventory（会触发当前 GUI 的 InventoryCloseEvent，
            // 但 onClose 中的 1 tick 延迟检测会识别出这是翻页而非真正关闭）
            event.getWhoClicked().openInventory(invs.get(target));
            return;
        }

        // ===== 关闭动作：close =====
        if (command.equalsIgnoreCase("close")) {
            // 直接关闭当前 GUI（会触发 InventoryCloseEvent，正常清理缓存）
            event.getWhoClicked().closeInventory();
            return;
        }

        // ===== 自定义指令动作：执行配置中的所有指令 =====
        // 支持一个格子配置多条指令（按顺序依次执行）
        // 例如：["say hello", "give @s diamond 1"] → 先发消息再给钻石
        for (String cmd : commands) {
            // dispatchCommand：以点击者的身份执行指令（无需加 / 前缀）
            // 注意：这里用的是 event.getWhoClicked()（HumanEntity），
            // 它实现了 CommandSender 接口，可以直接作为指令执行者
            Bukkit.dispatchCommand(event.getWhoClicked(), cmd);
        }
    }

    // ==================== 拖拽事件 ====================

    /**
     * 拦截 GUI 中的拖拽行为。
     *
     * <p>意图：InventoryClickEvent 只能拦截单击，但玩家还可以通过拖拽
     * （按住物品在多个格子间划过）来移动物品。必须额外拦截 DragEvent
     * 才能完全保证 GUI 的只读性。
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        // 只处理本插件的 GUI
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof DeathGuiHolder)) return;
        // 取消拖拽事件
        event.setCancelled(true);
    }

    // ==================== 关闭事件 ====================

    /**
     * 处理 GUI 关闭事件：清理翻页缓存，释放内存。
     *
     * <p>核心难点：翻页时会触发"旧 GUI 关闭 → 新 GUI 打开"的流程，
     * 如果在关闭事件中立即清理缓存，会导致新 GUI 的翻页功能失效
     * （因为 playerPageInv 被清空了，下次点击 next/prev 时找不到缓存）。
     *
     * <p>解决方案：延迟 1 tick（约 50ms）后再检查玩家当前打开的容器。
     * <ul>
     *   <li>如果 1 tick 后玩家打开的仍然是 DeathGuiHolder → 说明是翻页，不清理</li>
     *   <li>如果 1 tick 后玩家打开的不是 DeathGuiHolder → 说明是真正关闭，清理缓存</li>
     * </ul>
     *
     * <p>为什么 1 tick 足够？
     * Bukkit 的事件处理是同步的，openInventory() 在同一个 tick 内完成，
     * 所以 1 tick 后新 GUI 一定已经打开了。
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // 第一步：只关心本插件的 GUI，其他容器的关闭不管
        if (!(event.getInventory().getHolder() instanceof DeathGuiHolder)) return;

        // 第二步：获取关闭 GUI 的玩家信息
        Player player = (Player) event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 第三步：调度一个 1 tick 后执行的任务
        // runTaskLater 参数：(插件实例, 任务内容, 延迟tick数)
        // 1 tick ≈ 50ms，足够让翻页的 openInventory 执行完
        Bukkit.getScheduler().runTaskLater(plugin, () -> {

            // 第四步：1 tick 后，检查玩家【当前】打开的容器
            // getOpenInventory() 返回玩家当前正在查看的容器视图
            // getTopInventory() 返回上半部分（即 GUI 本身，而非玩家自己的背包）
            Inventory currentTop = player.getOpenInventory().getTopInventory();

            // 如果当前顶部容器的 holder 仍然是 DeathGuiHolder
            // → 说明玩家只是翻了页，新 GUI 已经打开了，不能清缓存
            if (currentTop.getHolder() instanceof DeathGuiHolder) {
                return;  // 什么都不做，缓存保留，翻页功能正常
            }

            // 否则 → 玩家真的关闭了 GUI（或者打开了别的容器如箱子、背包）
            // 清理这个玩家的所有菜单缓存，释放内存
            // 如果不清理，大量玩家查看后缓存会持续累积，造成内存泄漏
            plugin.playerPageInv.remove(uuid);

        }, 1L);  // 延迟 1 tick（20 tick = 1 秒，所以 1 tick = 50ms）
    }
}