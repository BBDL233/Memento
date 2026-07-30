package com.bbdl.plugin;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * 死亡背包 GUI 的自定义 InventoryHolder（纯标记用）。
 *
 * <p>设计意图：
 * <ul>
 *   <li>Bukkit 的 Inventory 可以通过 getHolder() 获取其持有者对象。</li>
 *   <li>在 InventoryClickEvent 中，通过判断 holder 是否是 DeathGuiHolder 实例，
 *       即可确认玩家点击的是"死亡背包查看界面"，而非普通箱子、背包等。</li>
 *   <li>同时携带 page 信息，用于翻页按钮判断当前是第几页菜单。</li>
 * </ul>
 *
 * <p>使用流程：
 * <ol>
 *   <li>GuiRenderer 创建菜单时：new DeathGuiHolder(page) → 作为 holder 传入 createInventory()</li>
 *   <li>GuiListener 点击事件中：inventory.getHolder() instanceof DeathGuiHolder → 确认是本插件的 GUI，
 *       再经 getPage() 取得当前页码用于翻页</li>
 * </ol>
 *
 * <p>关于 getInventory()：本插件只用 holder 做 instanceof 识别与取页码，
 * 从不读取 getInventory() 的返回值，故按 Spigot 社区惯例直接返回 null
 * （Bukkit 对纯标记用 holder 不会解引用该返回值，安全）。
 *
 * @param page 当前菜单页码（对应 menus/ 目录下的第几个文件，从 1 开始）
 */
public record DeathGuiHolder(int page) implements InventoryHolder {

    /**
     * @param page 当前菜单页码（1 = 第一个菜单文件，2 = 第二个...）
     */
    public DeathGuiHolder {
    }

    /**
     * 获取当前页码。
     * 用途：GuiListener 中翻页按钮点击时，通过 getPage() 知道当前在第几页，
     * 从而计算下一页/上一页的页码。
     */
    @Override
    public int page() {
        return page;
    }

    /**
     * InventoryHolder 接口要求实现的桩方法。
     * 本插件不持有 Inventory 引用，按社区惯例返回 null（见类注释说明）。
     */
    @Override
    @SuppressWarnings("NullableProblems")
    // 父接口 @NotNull，此处已知返回 null 且无消费者，显式压制；若 IDE 不认此 key，用 Alt+Enter→Suppress for method 让 IDE 自填
    public @Nullable Inventory getInventory() {
        return null;
    }
}