package com.bbdl.plugin;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

/**
 * GUI 渲染器：负责构建死亡背包查看界面的所有页面。
 *
 * <p>核心职责：
 * <ul>
 *   <li>根据 menus/ 目录下的 YAML 配置文件，动态构建多页 GUI 菜单</li>
 *   <li>将死亡背包中的物品注册为占位符，供菜单模板引用</li>
 *   <li>支持两种物品渲染模式：静态装饰物品 和 动态引用死亡背包物品</li>
 *   <li>自动填充：未被菜单配置占用的空槽位，自动填入剩余的死亡背包物品</li>
 * </ul>
 *
 * <p>菜单配置文件结构示例（menus/page1.yml）：
 * <pre>
 * title: "&c{player} 的死亡背包"
 * size: 54
 * items:
 *   "0":
 *     material: RED_STAINED_GLASS_PANE
 *     name: "&c装饰"
 *   "4":
 *     material: item_0        ← 引用死亡背包第 0 号槽位的物品
 *     name: "&e{item_0_name}" ← 使用占位符显示物品名
 *     lore:
 *       - "&7数量: {item_0_amount}"
 *     click:
 *       - "next"              ← 点击翻到下一页
 * </pre>
 *
 * <p>设计意图：
 * <ul>
 *   <li>菜单布局完全由配置文件驱动，服主无需修改代码即可自定义界面。</li>
 *   <li>多页支持：menus/ 目录下按文件名排序的每个 .yml 文件对应一页。</li>
 *   <li>占位符系统：死亡背包物品被注册为 item_N_xxx 格式的占位符，
 *       菜单模板中可自由引用其材质、名称、数量、Lore 等信息。</li>
 * </ul>
 */
public class GuiRenderer {

    /** 主插件实例，用于访问配置、菜单文件列表、内存缓存等 */
    private final Memento plugin;
    /** 消息格式化工具（复用主类实例，不重复创建） */
    private final MessageUtil msg;

    /**
     * 构造时引用主类已初始化好的共享实例。
     */
    public GuiRenderer(Memento plugin) {
        this.plugin = plugin;
        this.msg = plugin.msg;
    }

    // ==================== 核心方法：展示死亡背包 GUI ====================

    /**
     * 构建并打开死亡背包查看界面。
     *
     * @param sender  指令发送者（必须是玩家才能打开 GUI）
     * @param uuid    要查看的目标玩家 UUID（可以是自己，也可以是别人）
     * @param history 查看第几次死亡（1 = 最近一次，2 = 倒数第二次...）
     * @param Page    打开第几页菜单（1 = 第一页，对应 menus/ 下排序后的第一个文件）
     *
     * <p>完整执行流程：
     * <ol>
     *   <li>参数校验（玩家身份、history 范围、Page 范围）</li>
     *   <li>权限校验（查看他人背包时的可见性检查）</li>
     *   <li>收集并排序死亡记录文件</li>
     *   <li>清理超出保留上限的旧文件</li>
     *   <li>加载目标死亡记录的背包数据</li>
     *   <li>注册物品占位符</li>
     *   <li>逐页构建 GUI（解析配置 → 渲染物品 → 注册点击动作 → 自动填充）</li>
     *   <li>缓存所有页面并打开指定页</li>
     * </ol>
     */
    public void showDeathInv(CommandSender sender, UUID uuid, int history, int Page) {
        // ===== 第一步：校验发送者必须是玩家 =====
        // GUI 只能由玩家打开（控制台没有背包界面），通过名字反查 Player 对象
        Player player = plugin.getServer().getPlayer(sender.getName());
        if (player == null) {
            // 控制台或离线玩家 → 提示只能由玩家使用
            sender.sendMessage(msg.formatMsg("messages.gui.only-player", "该指令只能由玩家打开", null, null));
            return;
        }

        // ===== 第二步：校验 history 参数 =====
        // history <= 0 无意义，强制修正为 1（最近一次死亡）
        if (history <= 0) history = 1;

        // 检查是否超出配置的最大可查看历史次数
        // max-history = 0 表示不限制；非 0 时 history 不能超过该值
        int max_history = plugin.getConfig().getInt("settings.max-history", 0);
        if (max_history != 0 && history > max_history) {
            Map<String, String> ph = new HashMap<>();
            ph.put("history", history + "");
            ph.put("max-history", max_history + "");
            sender.sendMessage(msg.formatMsg("messages.gui.history-out-of-range",
                    "仅支持查看 {max-history} 次死亡内的背包，你输入了 {history}", ph, player));
            return;
        }

        // ===== 第三步：校验 Page 参数 =====
        // menuFiles 是 menus/ 目录下按文件名排序的文件列表
        int menusCout = plugin.menuFiles.size();
        // 外部传入的 Page 从 1 开始，内部索引从 0 开始，所以先减 1
        Page--;
        // 越界保护：超出范围或小于 0 时回退到第一页
        if (Page >= menusCout || 0 > Page) {
            Page = 0;
        }

        // ===== 第四步：检查目标玩家的死亡数据文件夹是否存在 =====
        File playerDeathInv = new File(new File(plugin.getDataFolder(), "playerdata"), uuid.toString());
        if (!playerDeathInv.exists()) {
            // 文件夹不存在 → 该玩家从未死亡过（或数据被手动删除）
            sender.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
            return;
        }

        // ===== 第五步：权限校验（查看他人背包时的可见性检查） =====
        // 如果查看的不是自己的死亡背包，需要额外检查
        if (!player.getUniqueId().equals(uuid)) {
            // 不可见条件：目标玩家关闭了广播 或 查看者自己关闭了广播
            // （两个开关任一为 false 就不可见，除非有 viewall 权限）
            if (!plugin.deathMessages.getOrDefault(uuid, true)

                    || !plugin.deathMessages.getOrDefault(player.getUniqueId(), true)) {
                // 没有 Memento.viewall 权限 → 拒绝查看
                if (!player.hasPermission("Memento.viewall")) {
                    // 统一返回"不存在"而非"无权限"，避免泄露目标玩家是否有死亡记录
                    sender.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
                    return;
                }
            }
        }

        // ===== 第六步：收集所有死亡记录文件并按时间排序 =====
        // TreeMap 自动按 key（时间戳）升序排列，最早的在前，最新的在后
        Map<Long, File> deathFiles = new TreeMap<>();
        File[] fileList = playerDeathInv.listFiles();
        if (fileList == null) {
            sender.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
            return;
        }

        // 遍历文件夹中的所有文件，筛选出有效的死亡记录
        for (File f : fileList) {
            // 只处理 .yml 文件
            if (!f.getName().endsWith(".yml")) continue;
            // 排除 config.yml（那是设置文件，不是死亡记录）
            if (f.getName().endsWith("config.yml")) continue;
            // 读取文件中的时间戳作为排序 key
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
            long time = yaml.getLong("time");
            deathFiles.put(time, f);
        }

        // ===== 第七步：清理超出保留上限的旧文件 =====
        // save-max-history：磁盘上最多保留多少次死亡记录（0 = 不限制）
        // 意图：防止长期运行后死亡文件无限增长，占用大量磁盘空间
        int save_max_history = plugin.getConfig().getInt("settings.save-max-history", 0);
        if (save_max_history != 0) {
            // TreeMap 的 keySet() 按升序返回，所以前几个就是最旧的
            List<Long> times = new ArrayList<>(deathFiles.keySet());
            // 计算需要删除的数量：总数 - 保留上限
            int dels = times.size() - save_max_history;
            for (int i = 0; i < dels; i++) {
                long t = times.get(i);
                File f = deathFiles.get(t);
                // 删除磁盘上的旧文件
                if (!f.delete()) {
                    // 删除失败（可能是权限问题）→ 输出调试日志
                    Map<String, String> delPh = new HashMap<>();
                    delPh.put("filename", f.getName());
                    msg.debug("messages.gui.delete-old-failed", "无法删除旧死亡背包: {filename}", delPh);
                }
                // 无论删除是否成功，都从内存映射中移除（避免后续逻辑引用已标记删除的文件）
                deathFiles.remove(t);
            }
        }

        // ===== 第八步：校验请求的 history 索引是否有效 =====
        // 例如：只有 3 次死亡记录，但请求 history=5 → 不存在
        if (history > deathFiles.size()) {
            sender.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
            return;
        }

        // ===== 第九步：加载目标死亡记录的背包数据 =====
        // TreeMap 升序排列，最新的在最后，所以第 N 次死亡 = 倒数第 N 个
        // 索引计算：times.size() - history（history=1 → 最后一个 = 最新）
        List<Long> times = new ArrayList<>(deathFiles.keySet());
        long t = times.get(times.size() - history);
        File f = deathFiles.get(t);
        YamlConfiguration data = YamlConfiguration.loadConfiguration(f);

        // 反序列化背包内容（与 DataManager.loadAllData 中的逻辑一致）
        List<?> bag = data.getList("death-inventory");
        if (bag != null) {
            ItemStack[] inv = new ItemStack[bag.size()];
            for (int i = 0; i < bag.size(); i++) {
                Object o = bag.get(i);
                // 每个非空物品在 YAML 中被序列化为 Map<String, Object>
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) o;
                    inv[i] = ItemStack.deserialize(map);
                }
                // null 表示该槽位为空
            }
            // 写入临时缓存（deathFileIndex），供后续 regItemsPh 使用
            // 注意：这里用 uuid 作为 key，如果同时查看不同玩家的背包会覆盖，
            // 但因为 GUI 操作是同步的，不会并发，所以安全
            plugin.deathFileIndex.put(uuid, inv);
        }

        // ===== 第十步：注册物品占位符 =====
        // 将死亡背包中的每个物品转换为占位符映射（item_0_material, item_0_name 等）
        // 供菜单模板中的 {item_N_xxx} 引用
        Map<Integer, Object> itemsPh = regItemsPh(plugin.deathFileIndex.get(uuid));

        // 存储所有页面的 Inventory 对象（用于翻页）
        List<Inventory> invs = new ArrayList<>();

        // ===== 第十一步：逐页构建 GUI =====
        for (int i = 0; i < menusCout; i++) {
            // 获取当前页对应的菜单配置文件
            File menusFile = new File(plugin.getDataFolder(), "menus/" + plugin.menuFiles.get(i));

            // 输出调试日志：正在加载哪个菜单文件
            Map<String, String> loadPh = new HashMap<>();
            loadPh.put("file", plugin.menuFiles.get(i));
            msg.debug("messages.gui.loading-menu", "加载菜单文件: {file}", loadPh);

            // 加载菜单 YAML 配置
            YamlConfiguration menus = YamlConfiguration.loadConfiguration(menusFile);

            // 构建标题占位符（{title} 会被替换为配置中的 title 值）
            Map<String, String> ph = new HashMap<>();
            ph.put("title", menus.getString("title", ""));

            // 全局占位符
            // 死亡时间戳（毫秒），提一个变量，deathtime 和下面拆分都复用它，避免读两次
            long deathTime = data.getLong("time", 0);
            ph.put("deathtime", String.valueOf(deathTime));

            // 把时间戳拆成 年/月/日/时/分/秒/毫秒，注册成占位符供标题模板引用
            // Calendar.getInstance() 用服务器本地时区，符合"死亡时间按服主本地显示"的直觉
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(deathTime);
            ph.put("death_year",   String.valueOf(cal.get(Calendar.YEAR)));                 // 年，原样，如 2026
            ph.put("death_month",  String.format("%02d", cal.get(Calendar.MONTH) + 1));     // 月，补两位（Calendar 月份从 0 算，必须 +1）
            ph.put("death_day",    String.format("%02d", cal.get(Calendar.DAY_OF_MONTH)));  // 日，补两位
            ph.put("death_hour",   String.format("%02d", cal.get(Calendar.HOUR_OF_DAY)));   // 时，24 小时制，补两位
            ph.put("death_minute", String.format("%02d", cal.get(Calendar.MINUTE)));        // 分，补两位
            ph.put("death_second", String.format("%02d", cal.get(Calendar.SECOND)));        // 秒，补两位
            ph.put("death_millis", String.format("%03d", cal.get(Calendar.MILLISECOND)));   // 毫秒，补三位

            // 死亡位置 世界占位符
            ph.put("death_world",data.getString("world",""));
            ph.put("death_x",data.getString("x",""));
            ph.put("death_y",data.getString("y",""));
            ph.put("death_z",data.getString("z",""));

            // 解析 GUI 大小（必须是 9 的倍数，范围 9~54）
            int size = menus.getInt("size", 54);
            // 不合法的值统一回退为 54（6 行箱子）
            if (size % 9 != 0 || size < 9 || size > 54) size = 54;

            // 创建 Inventory 实例
            // holder = new DeathGuiHolder(i)：携带页码信息，用于 GuiListener 中识别和翻页
            // title：通过消息系统格式化（支持 MiniMessage 颜色 + PlaceholderAPI）
            // 最后一个参数 false：表示不需要添加前缀（标题不需要 [插件名] 前缀）
            Inventory gui = plugin.getServer().createInventory(
                    new DeathGuiHolder(i), size,
                    msg.formatMsg(null, menus.getString("title", ""), ph, player, false));

            // 存储当前页的点击动作映射：Map<槽位, List<指令>>
            Map<Integer, List<String>> clickMap = new HashMap<>();

            // 获取 items 配置节（定义了每个槽位放什么物品）
            ConfigurationSection items = menus.getConfigurationSection("items");
            if (items != null) {
                // 遍历 items 下的所有键（每个键是一个槽位编号字符串，如 "0", "4", "13"）
                for (String slotStr : items.getKeys(false)) {
                    // 将键解析为整数槽位号
                    int slot;
                    try {
                        slot = Integer.parseInt(slotStr);
                    } catch (NumberFormatException e) {
                        // 键不是数字（配置错误）→ 跳过
                        continue;
                    }
                    // 槽位越界保护
                    if (slot < 0 || slot >= size) continue;

                    // 获取该槽位配置的物品材质
                    String matStr = items.getString(slotStr + ".material", "STONE");
                    ItemStack item;
                    ItemMeta meta;

                    // ===== 分支 A：动态物品（material 以 "item_" 开头） =====
                    // 例如 material: "item_3" → 引用死亡背包第 3 号槽位的物品
                    // 意图：让菜单模板能展示玩家死亡时的实际物品
                    if (matStr.startsWith("item_")) {
                        // 解析引用的槽位编号：截取 "item_" 后面的数字
                        int refSlot;
                        try {
                            refSlot = Integer.parseInt(matStr.substring(5));
                        } catch (NumberFormatException e) {
                            continue;  // 格式错误 → 跳过
                        }

                        // 从占位符映射中获取该槽位的数据
                        Object val = itemsPh.get(refSlot);
                        // 如果该槽位为空（null）或不是 Map → 跳过（不渲染任何东西）
                        if (!(val instanceof Map)) continue;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> phh = (Map<String, Object>) val;

                        // 把全局占位符（death_year、death_world、death_x 等）合并进来，
                        // 这样物品 name/lore 模板里也能引用 {death_year}、{death_x} 等
                        phh.putAll(ph);

                        // 获取真实的材质名（如 "DIAMOND_SWORD"）
                        String realMat = (String) phh.get("item_" + refSlot + "_material");
                        Material mat = Material.matchMaterial(realMat != null ? realMat : "STONE");
                        if (mat == null) continue;  // 无法识别的材质 → 跳过

                        // 从占位符映射中移除已使用的条目
                        // 意图：后面"自动填充"阶段只会填充未被显式引用的剩余物品
                        itemsPh.remove(refSlot);

                        // 获取物品数量
                        Object amountObj = phh.get("item_" + refSlot + "_amount");
                        int amount = 1;
                        if (amountObj instanceof Number) amount = ((Number) amountObj).intValue();
                        if (amount < 1) amount = 1;  // 数量至少为 1

                        // 创建物品实例
                        item = new ItemStack(mat, amount);
                        meta = item.getItemMeta();

                        // --- 设置物品名称 ---
                        // 优先使用菜单配置中的 name（允许服主覆盖显示名）
                        if (items.contains(slotStr + ".name")) {
                            String name = items.getString(slotStr + ".name");
                            if (name != null && !name.isEmpty()) {
                                // 格式化时传入 phh（包含 item_N_xxx 占位符），支持 {item_0_name} 等引用
                                meta.displayName(msg.formatMsg(null, name, phh, player, false));
                            }
                        }
                        else {
                            // 菜单没配置 name → 使用物品原始名称
                            Object nameObj = phh.get("item_" + refSlot + "_name");
                            if (nameObj instanceof Component) {
                                // 原始名称是 Component（有自定义显示名的物品）→ 直接使用
                                meta.displayName((Component) nameObj);
                            }
                        }

                        // --- 设置物品 Lore ---
                        // 优先使用菜单配置中的 lore（允许服主自定义描述）
                        if (items.contains(slotStr + ".lore")) {
                            List<String> loreRaw = items.getStringList(slotStr + ".lore");
                            if (!loreRaw.isEmpty()) {
                                List<Component> loreComp = new ArrayList<>();
                                for (String line : loreRaw) {
                                    // 手动替换占位符（因为 lore 中可能引用 item_N_xxx）
                                    String replaced = line;
                                    for (Map.Entry<String, ?> entry : phh.entrySet()) {
                                        Object val2 = entry.getValue();
                                        String str;
                                        // 如果占位符值是 Component，序列化为 MiniMessage 字符串再替换
                                        if (val2 instanceof Component) {
                                            str = MiniMessage.miniMessage().serialize((Component) val2);
                                        } else {
                                            str = val2 != null ? val2.toString() : "";
                                        }
                                        replaced = replaced.replace("{" + entry.getKey() + "}", str);
                                    }
                                    // 替换 PlaceholderAPI 占位符（如 %player_name%）
                                    replaced = PlaceholderAPI.setPlaceholders(player, replaced);
                                    // 支持 \n 换行：一行配置可以生成多行 lore
                                    for (String l : replaced.split("\n")) {
                                        loreComp.add(MiniMessage.miniMessage().deserialize(l));
                                    }
                                }
                                meta.lore(loreComp);
                            }
                        } else {
                            // 菜单没配置 lore → 使用物品原始 lore
                            Object loreObj = phh.get("item_" + refSlot + "_lore");
                            if (loreObj instanceof String && !((String) loreObj).isEmpty()) {
                                List<Component> loreComp = new ArrayList<>();
                                // 原始 lore 以 \n 分隔多行
                                for (String l : ((String) loreObj).split("\n")) {
                                    loreComp.add(MiniMessage.miniMessage().deserialize(l));
                                }
                                meta.lore(loreComp);
                            }
                        }

                    }
                    // ===== 分支 B：静态装饰物品（普通材质名） =====
                    // 例如 material: "RED_STAINED_GLASS_PANE" → 纯装饰用途
                    else {
                        Material mat = Material.matchMaterial(matStr);
                        if (mat == null) continue;  // 无法识别的材质 → 跳过

                        item = new ItemStack(mat);
                        meta = item.getItemMeta();

                        // 设置名称（静态物品不需要物品占位符，只支持全局占位符和 PAPI）
                        if (items.contains(slotStr + ".name")) {
                            String name = items.getString(slotStr + ".name");
                            if (name != null && !name.isEmpty()) {
                                meta.displayName(msg.formatMsg(null, name, ph, player, false));
                            }
                        }

                        // 设置 Lore
                        if (items.contains(slotStr + ".lore")) {
                            List<String> loreRaw = items.getStringList(slotStr + ".lore");
                            if (!loreRaw.isEmpty()) {
                                List<Component> loreComp = new ArrayList<>();
                                for (String line : loreRaw) {
                                    loreComp.add(msg.formatMsg(null, line, ph, player, false));
                                }
                                meta.lore(loreComp);
                            }
                        }
                    }

                    // --- 解析点击动作 ---
                    // click 配置示例：click: ["next"] 或 click: ["say hello", "give @s diamond"]
                    List<String> clickCmds = items.getStringList(slotStr + ".click");
                    if (!clickCmds.isEmpty()) {
                        clickMap.put(slot, clickCmds);
                    }

                    // 将构建好的物品放入 GUI 的对应槽位
                    item.setItemMeta(meta);
                    gui.setItem(slot, item);
                }

                // ===== 自动填充：将未被显式引用的死亡背包物品填入剩余空槽位 =====
                // 意图：如果菜单只显式引用了部分物品（如前 5 个），
                // 剩余的物品自动填入空位，确保玩家能看到完整的死亡背包

                // 第一步：收集菜单配置中已占用的所有槽位
                Set<Integer> configRegSlots = new HashSet<>();
                for (String key : items.getKeys(false)) {
                    try {
                        configRegSlots.add(Integer.parseInt(key));
                    } catch (NumberFormatException e) {
                        // 非数字键 → 输出调试日志（配置错误提示）
                        Map<String, String> errPh = new HashMap<>();
                        errPh.put("error", String.valueOf(e));
                        msg.debug("messages.gui.parse-error", "菜单配置解析错误: {error}", errPh);
                    }
                }

                // 第二步：找出所有未被占用的空槽位
                List<Integer> emptySlots = new ArrayList<>();
                for (int s = 0; s < size; s++) {
                    if (!configRegSlots.contains(s)) emptySlots.add(s);
                }

                // 第三步：将 itemsPh 中剩余的物品（未被显式引用的）依次填入空槽位
                // 条件：有空槽位 且 有剩余物品
                List<Integer> toRemove = new ArrayList<>();
                if (!emptySlots.isEmpty() && !itemsPh.isEmpty()) {
                    int index = 0;  // 空槽位索引指针
                    for (Map.Entry<Integer, Object> entry : itemsPh.entrySet()) {
                        // 空槽位用完了就停止
                        if (index >= emptySlots.size()) break;

                        Object val = entry.getValue();
                        // null 表示该槽位原本就是空的（AIR），跳过不填充
                        if (!(val instanceof Map)) continue;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> phh = (Map<String, Object>) val;
                        int refSlot = entry.getKey();

                        // 获取材质
                        String realMat = (String) phh.get("item_" + refSlot + "_material");
                        Material mat = Material.matchMaterial(realMat != null ? realMat : "STONE");
                        if (mat == null) continue;

                        // 获取数量
                        Object amountObj = phh.get("item_" + refSlot + "_amount");
                        int amount = 1;
                        if (amountObj instanceof Number) amount = ((Number) amountObj).intValue();
                        if (amount < 1) amount = 1;

                        // 创建物品
                        ItemStack fillItem = new ItemStack(mat, amount);
                        ItemMeta fillMeta = fillItem.getItemMeta();

                        // 使用物品原始名称
                        Object nameObj = phh.get("item_" + refSlot + "_name");
                        if (nameObj instanceof Component) {
                            fillMeta.displayName((Component) nameObj);
                        }

                        // 使用物品原始 lore
                        Object loreObj = phh.get("item_" + refSlot + "_lore");
                        if (loreObj instanceof String && !((String) loreObj).isEmpty()) {
                            List<Component> loreComp = new ArrayList<>();
                            for (String l : ((String) loreObj).split("\n")) {
                                loreComp.add(MiniMessage.miniMessage().deserialize(l));
                            }
                            fillMeta.lore(loreComp);
                        }
                        toRemove.add(refSlot);
                        // 放入空槽位
                        fillItem.setItemMeta(fillMeta);
                        gui.setItem(emptySlots.get(index), fillItem);
                        index++;
                    }
                }

                for (Integer key : toRemove) {
                    itemsPh.remove(key);
                }
            }

            // 将当前页的点击动作映射存入主类内存
            // key = 页码索引（0-based），value = Map<槽位, List<指令>>
            // GuiListener.onClick 中通过 page 查找对应的 clickMap
            plugin.menuClickActions.put(i, clickMap);

            // 将构建好的 Inventory 加入页面列表
            invs.add(gui);
        }

        // ===== 第十二步：缓存所有页面并打开指定页 =====
        // 以玩家 UUID 为 key 存储所有页面的 Inventory 列表
        // GuiListener 翻页时通过此缓存获取目标页的 Inventory
        plugin.playerPageInv.put(player.getUniqueId(), invs);
        // 打开玩家请求的那一页（Page 已经在前面转为 0-based 索引）
        player.openInventory(invs.get(Page));
    }

    // ==================== 物品占位符注册 ====================

    /**
     * 将死亡背包中的物品数组转换为占位符映射。
     *
     * <p>转换规则（以第 i 个槽位为例）：
     * <ul>
     *   <li>item_i_material → 材质名（如 "DIAMOND_SWORD"）</li>
     *   <li>item_i_amount   → 数量（如 1）</li>
     *   <li>item_i_name     → 显示名（Component 或回退字符串）</li>
     *   <li>item_i_lore     → Lore 文本（多行以 \n 连接的 MiniMessage 字符串）</li>
     * </ul>
     *
     * <p>用途：
     * <ul>
     *   <li>菜单模板中 material: "item_3" → 引用第 3 号槽位的物品</li>
     *   <li>菜单模板中 name: "{item_3_name}" → 显示该物品的名称</li>
     *   <li>菜单模板中 lore: ["数量: {item_3_amount}"] → 显示该物品的数量</li>
     * </ul>
     *
     * @param items 死亡背包物品数组（可能包含 null 表示空槽位）
     * @return Map<槽位索引, 占位符Map 或 null>
     *         null 表示该槽位为空（AIR），菜单中引用时会跳过
     */
    public Map<Integer, Object> regItemsPh(ItemStack[] items) {
        Map<Integer, Object> map = new HashMap<>();
        int i = 0;

        for (ItemStack item : items) {
            Map<String, Object> ph = new HashMap<>();
            String key = "item_" + i;  // 占位符前缀，如 "item_0", "item_1"

            // 空槽位或空气 → 存 null（后续引用时会跳过）
            if (item == null || item.getType() == Material.AIR) {
                map.put(i, null);
                i++;
                continue;
            }

            ItemMeta meta = item.getItemMeta();

            // 注册材质名（如 "DIAMOND_SWORD", "COBBLESTONE"）
            ph.put(key + "_material", item.getType().name());
            // 注册数量（如 1, 64）
            ph.put(key + "_amount", item.getAmount());

            // 注册显示名
            if (meta.displayName() != null) {
                // 有自定义显示名 → 存为 Component（保留颜色、格式等）
                ph.put(key + "_name", meta.displayName());
            } else {
                // 没有自定义名 → 存一个"美化英文材质名"作为兜底
                //
                // 为什么还要存？
                // 默认情况（菜单不写 name 字段）走渲染端 else 分支，客户端自己本地化，
                // 根本用不到这个值，所以不影响正常显示。
                // 这个值只为了一种角落情况服务：
                //   服主在菜单里硬写了 name: "{item_0_name}"，而该物品恰好没改名，
                //   此时若不存 _name，占位符找不到 key → 玩家看到字面量 {item_0_name}。
                //   存了这个兜底，占位符至少能换成 "Oak Planks"，不至于露花括号。
                //
                // 注意：这是服务端能造的最好的英文名，不是客户端本地化名
                //      （本地化名只有客户端自己知道，服务端取不到）。
                ph.put(key + "_name", prettyMaterialName(item.getType()));
            }
//            else {
//                // 没有自定义显示名 → 用材质名生成一个可读的回退名
//                // 例如：DIAMOND_SWORD → "diamondsword"（去下划线、小写）
//                ph.put(key + "_name", item.getType().name().toLowerCase().replace("_", ""));
//            }

            // 注册 Lore：只取一次 lore() 存进 loreList，对变量判 null，避免 IDE 窄化失败
            List<Component> loreList = meta.lore();
            if (loreList != null && !loreList.isEmpty()) {
                // 将多行 Lore（List<Component>）序列化为单个字符串，行间以 \n 连接
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < loreList.size(); j++) {
                    sb.append(MiniMessage.miniMessage().serialize(loreList.get(j)));
                    if (j < loreList.size() - 1) sb.append("\n");
                }
                ph.put(key + "_lore", sb.toString());
            } else {
                // 没有 lore → 存空字符串（避免模板引用时出现 null）
                ph.put(key + "_lore", "");
            }

            // 将该槽位的完整占位符映射存入结果
            map.put(i, ph);
            i++;
        }

        return map;
    }

    /**
     * 把材质枚举名转成可读的英文兜底名。
     * OAK_PLANKS → "Oak Planks"
     * DIAMOND_SWORD → "Diamond Sword"
     *
     * <p>仅用于"服主在菜单硬贴 {item_N_name} 但物品无自定义名"的兜底，
     * 正常本地化显示不经过这里。
     */
    private String prettyMaterialName(Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (i > 0) sb.append(' ');
            // 首字母大写，其余小写
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

}