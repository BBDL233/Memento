package com.bbdl.plugin;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/**
 * 插件主类：生命周期管理、共享数据存储、指令注册。
 *
 * <p>架构定位：
 * <ul>
 *   <li>本类是整个插件的"中枢"，所有其他类（DeathListener、GuiListener、
 *       GuiRenderer、CommandHandler、MessageUtil、DataManager）都持有本类引用，
 *       通过本类访问共享数据和配置。</li>
 *   <li>本类不包含任何业务逻辑（死亡处理、GUI 渲染等），只负责：
 *       初始化 → 注册 → 协调 → 销毁。</li>
 * </ul>
 *
 * <p>共享数据设计：
 * <ul>
 *   <li>所有运行时数据以 Map 形式存储在本类中（内存缓存）。</li>
 *   <li>持久化由 DataManager 负责，在关键时机（死亡、退出、重载、卸载）写入磁盘。</li>
 *   <li>这种"内存 + 按需持久化"的模式兼顾了性能和数据安全。</li>
 * </ul>
 *
 * <p>指令注册方式：
 * <ul>
 *   <li>不使用 plugin.yml 中的 commands 节点（因为指令名需要从 config 动态读取）。</li>
 *   <li>通过反射获取 CommandMap，在运行时动态注册指令。</li>
 *   <li>支持 Paper 服务端的热重注册（重载时指令名变更无需重启）。</li>
 * </ul>
 */
public class Memento extends JavaPlugin {

    // ==================== 共享数据（仓库） ====================
    // 设计意图：所有运行时状态集中存放在主类中，其他类通过 plugin.xxx 访问。
    // 好处：单一数据源，避免多处维护同一份数据导致不一致。

    /** 开发模式开关：true 时每次启动都会强制覆盖 config.yml 和菜单文件（方便调试） */
    boolean dev = false;
    /** 调试模式开关：true 时输出所有 debug 级别日志（由 config 中 settings.debug 控制） */
    boolean debugMode = false;

    /** 玩家死亡位置缓存：UUID → 最后一次死亡的 Location（用于 /ld 查询和复活提醒） */
    final Map<UUID, Location> deathLocations = new HashMap<>();
    /** 复活提醒开关：UUID → 是否在复活时提示死亡位置（默认 true，玩家可通过指令切换） */
    final Map<UUID, Boolean> respawnNotify = new HashMap<>();
    /** 自定义死亡消息：UUID → 玩家设置的自定义死亡广播模板（付费功能） */
    final Map<UUID, CustomDeathMsg> customDeathMsgMap = new HashMap<>();
    /** 死亡消息可见性开关：UUID → 是否接收/显示死亡广播（默认 true，玩家可通过指令切换） */
    final Map<UUID, Boolean> deathMessages = new HashMap<>();
    /** 最近一次死亡背包缓存：UUID → 死亡时的背包快照（用于 /ld inv 快速查看，无需读文件） */
    final Map<UUID, ItemStack[]> deathInv = new HashMap<>();
    /** 从文件加载的死亡背包临时缓存：UUID → 反序列化后的物品数组（GUI 渲染时使用） */
    final Map<UUID, ItemStack[]> deathFileIndex = new HashMap<>();
    /** 菜单文件名列表：按 config 中 menus 节点的顺序排列（如 ["default.yml", "default2.yml"]） */
    final List<String> menuFiles = new ArrayList<>();
    /** 翻页缓存：UUID → 该玩家当前打开的所有页面 Inventory 列表（翻页时通过索引切换） */
    final Map<UUID, List<org.bukkit.inventory.Inventory>> playerPageInv = new HashMap<>();
    /** 菜单点击动作：页码 → (槽位 → 指令列表)（GuiListener 中根据页码+槽位查找要执行的动作） */
    final Map<Integer, Map<Integer, List<String>>> menuClickActions = new HashMap<>();

    /** Vault 经济接口实例（null = 未安装 Vault 或无经济插件） */
    Economy economy = null;
    /** 自定义死亡消息功能是否启用（需要 config 开启 + Vault 可用） */
    boolean customMessageEnabled = false;

    /** 消息格式化工具（全局单例，所有类共用） */
    MessageUtil msg;
    /** 数据持久化工具（全局单例，负责读写 playerdata 目录） */
    DataManager dataManager;
    /** GUI 渲染器（全局单例，负责构建死亡背包查看界面） */
    GuiRenderer guiRenderer;
    /** 指令处理器（全局单例，负责解析和执行所有子指令） */
    CommandHandler commandHandler;


    // ==================== 指令相关 ====================

    /** 主指令名称（从 config 读取，默认 "death"，玩家输入 /death 触发） */
    private String mainCommandName = "death";
    /**
     * 子指令别名映射：标准名 → 触发词列表
     * 例如：{"inv": ["inv", "inventory", "i"], "toggle": ["toggle", "t"]}
     * 意图：让玩家可以输入 /ld i 或 /ld inventory 都能触发 "inv" 子指令
     */
    private final Map<String, List<String>> subCommands = new HashMap<>();
    /** 已注册的 Command 对象引用（用于卸载时反注册） */
    private Command registeredCommand = null;

    // ==================== 工具 ====================

    /** playerdata 目录的 File 对象（存放所有玩家的死亡数据） */
    File dataFolder;
    /** MiniMessage 解析器实例（全局复用，线程安全） */
    final MiniMessage miniMessage = MiniMessage.miniMessage();
    /** Adventure ComponentLogger（支持直接输出 Component 到控制台，保留颜色） */
    final ComponentLogger logger = getComponentLogger();

    // ==================== 生命周期 ====================

    /**
     * 插件启用时的初始化流程。
     *
     * <p>执行顺序（顺序很重要，不能随意调整）：
     * <ol>
     *   <li>创建工具类实例（msg → dataManager → guiRenderer → commandHandler）</li>
     *   <li>注册事件监听器（DeathListener、GuiListener）</li>
     *   <li>保存默认配置文件</li>
     *   <li>创建必要的目录结构</li>
     *   <li>加载指令配置并注册主指令</li>
     *   <li>加载所有玩家数据和菜单配置</li>
     *   <li>初始化 Vault 经济接口</li>
     *   <li>判断付费功能是否可用</li>
     * </ol>
     */
    @Override
    public void onEnable() {
        // ===== 第一步：创建工具类实例 =====
        // 顺序要求：msg 必须最先创建（其他类的构造函数中可能用到 plugin.msg）
        msg = new MessageUtil(this);
        dataManager = new DataManager(this);
        guiRenderer = new GuiRenderer(this);
        commandHandler = new CommandHandler(this);

        // ===== 第二步：注册事件监听器 =====
        // DeathListener：处理玩家死亡和复活事件
        // GuiListener：处理 GUI 点击、拖拽、关闭事件
        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        // ===== 第三步：保存默认配置 =====
        // saveDefaultConfig()：如果 config.yml 不存在，从 jar 中复制一份
        // 不会覆盖已有的 config.yml（保护服主的自定义配置）
        saveDefaultConfig();

        // 开发模式：强制覆盖配置文件（每次启动都用最新的默认配置）
        // 意图：开发阶段频繁修改默认配置，不需要手动删除旧文件
        // 生产环境应将 dev 设为 false
        if (dev) {
            saveResource("config.yml", true);          // true = 覆盖已有文件
            saveResource("menus/default.yml", true);
            saveResource("menus/default2.yml", true);
        }

        // ===== 第四步：创建目录结构 =====
        // playerdata/：存放每个玩家的死亡记录（按 UUID 分子文件夹）
        dataFolder = new File(getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            // 接住 mkdirs 返回值：建目录失败时打 warn，避免静默失败
            if (!dataFolder.mkdirs()) {
                logger.warn(msg.formatMsg("messages.debug.mkdir-failed-playerdata",
                        "无法创建 playerdata 目录", null, null));
            }
        }

        // menus/：存放 GUI 菜单配置文件
        File menuFolder = new File(getDataFolder(), "menus");
        if (!menuFolder.exists()) {
            if (!menuFolder.mkdirs()) {
                logger.warn(msg.formatMsg("messages.debug.mkdir-failed-menus",
                        "无法创建 menus 目录", null, null));
            }
            // 首次创建时复制默认菜单文件（false = 不覆盖，因为目录刚创建所以一定不存在）
            saveResource("menus/default.yml", false);
            saveResource("menus/default2.yml", false);
        }

        // ===== 第五步：加载指令配置并注册 =====
        // 从 config 读取主指令名和子指令别名
        loadCommandConfig();
        // 通过反射获取 CommandMap 并动态注册指令
        registerMainCommand();

        // ===== 第六步：加载所有数据 =====
        // 包括：所有玩家的持久化数据 + 菜单文件列表
        loadAll();

        // ===== 第七步：初始化 Vault 经济接口 =====
        // Vault 是 Bukkit 生态的经济抽象层，通过它可以对接任何经济插件
        // （如 EssentialsX Economy、CMI、PlayerPoints 等）
        // getRegistration()：查找已注册的 Economy 服务提供者
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            // 获取经济接口实例（后续用于扣费操作）
            economy = rsp.getProvider();
        }

        // ===== 第八步：判断付费功能是否可用 =====
        // 自定义死亡消息是付费功能：需要 config 中开启 + 服务器有经济插件
        boolean chargeEnabled = getConfig().getBoolean("settings.custom-death-message.enabled", false);

        if (chargeEnabled && economy == null) {
            // 配置中开启了收费功能，但服务器没有安装经济插件
            // → 无法扣费 → 禁用整个自定义消息功能（避免玩家设置了消息但无法生效）
            logger.warn(msg.formatMsg("messages.debug.no-vault",
                    "已开启收费，但未检测到经济插件(Vault)，功能已禁用！", null, null));
            customMessageEnabled = false;
        } else {
            // 两种情况都走这里：
            // 1. chargeEnabled=false → 功能免费开放，不需要经济插件
            // 2. chargeEnabled=true && economy!=null → 收费功能正常可用
            customMessageEnabled = true;
        }

        // 输出启动成功日志
        logger.info(msg.formatMsg("messages.debug.on-enable", "插件已加载!", null, null));
    }

    /**
     * 插件禁用时的清理流程。
     *
     * <p>执行顺序：
     * <ol>
     *   <li>保存所有玩家的设置数据到磁盘（防止数据丢失）</li>
     *   <li>反注册主指令（防止插件卸载后指令残留报错）</li>
     *   <li>输出卸载日志</li>
     * </ol>
     */
    @Override
    public void onDisable() {
        // 持久化所有内存中的玩家设置（死亡位置、开关状态、自定义消息等）
        saveAll();
        // 从 CommandMap 中移除本插件注册的指令
        unregisterMainCommand();
        logger.info(msg.formatMsg("messages.debug.on-disable", "插件已卸载!", null, null));
    }

    // ==================== 重载 ====================

    /**
     * 插件热重载：在不重启服务器的情况下重新加载配置和数据。
     *
     * <p>执行流程：
     * <ol>
     *   <li>保存当前所有数据（防止重载过程中丢失未保存的更改）</li>
     *   <li>清空所有内存缓存（为重新加载做准备）</li>
     *   <li>重新读取 config.yml</li>
     *   <li>重新加载所有数据和菜单</li>
     *   <li>如果主指令名发生变更，尝试热重注册（仅 Paper 支持）</li>
     * </ol>
     *
     * <p>限制：
     * <ul>
     *   <li>Spigot 服务端不支持指令热重注册（CommandMap.unregister 后无法重新 register）</li>
     *   <li>如果指令名变更且不是 Paper，需要重启服务器才能生效</li>
     * </ul>
     */
    public void reloadPlugin() {
        // 先保存再清空，确保数据不丢失
        saveAll();
        clearAll();
        // reloadConfig()：重新从磁盘读取 config.yml（Bukkit 内置方法）
        reloadConfig();
        // 重新加载玩家数据和菜单列表
        loadAll();

        // 记录旧的指令名，用于检测是否发生变更
        String oldName = mainCommandName;
        // 重新读取指令配置（可能修改了主指令名或子指令别名）
        loadCommandConfig();

        // 指令名没变 → 无需重注册，直接返回
        if (mainCommandName.equals(oldName)) {
            return;
        }

        // 指令名变了 → 需要重注册
        if (isPaper()) {
            // Paper 服务端：支持先 unregister 再 register（热重载）
            unregisterMainCommand();
            registerMainCommand();
            Map<String, String> ph = new HashMap<>();
            ph.put("cmd", mainCommandName);
            logger.info(msg.formatMsg("messages.debug.command-hot-reloaded",
                    "主指令已热重载为 /{cmd}", ph, null));
        } else {
            // Spigot/其他服务端：不支持热重注册，提示需要重启
            Map<String, String> ph = new HashMap<>();
            ph.put("cmd", mainCommandName);
            logger.warn(msg.formatMsg("messages.debug.command-restart-needed",
                    "主指令名已改为 /{cmd}，但当前服务端不支持热重注册，需重启服务器生效。", ph, null));
        }
    }

    // ==================== 指令注册（核心） ====================

    /**
     * 通过反射动态注册主指令。
     *
     * <p>为什么不用 plugin.yml？
     * <ul>
     *   <li>plugin.yml 中的指令名是编译时固定的，无法从 config 动态读取。</li>
     *   <li>本插件允许服主在 config 中自定义指令名（如改为 /death、/ld 等）。</li>
     *   <li>因此必须在运行时通过 CommandMap 动态注册。</li>
     * </ul>
     *
     * <p>注册流程：
     * <ol>
     *   <li>反射获取服务端的 CommandMap 实例</li>
     *   <li>创建匿名 Command 子类，重写 execute 和 tabComplete</li>
     *   <li>设置指令描述和用法</li>
     *   <li>调用 commandMap.register() 完成注册</li>
     * </ol>
     */
    private void registerMainCommand() {
        // 通过反射获取 CommandMap（Bukkit 没有公开 API 获取它）
        CommandMap commandMap = getCommandMap();
        if (commandMap == null) {
            // 反射失败（极端情况，如服务端修改了内部结构）
            // → 插件将无法响应任何指令，输出错误日志
            logger.error(msg.formatMsg("messages.debug.commandmap-failed",
                    "无法获取 CommandMap，主指令注册失败！插件将无法响应任何指令。", null, null));
            return;
        }

        // 创建匿名 Command 子类
        // 参数 mainCommandName：玩家输入的指令名（如 "death"）
        registeredCommand = new Command(mainCommandName) {
            /**
             * 指令执行入口：玩家输入 /death xxx 时触发
             * 委托给 CommandHandler 处理（保持主类精简）
             */
            @Override
            public boolean execute(@NonNull CommandSender sender, @NonNull String label, String @NonNull [] args) {
                return commandHandler.onCommand(sender, this, label, args);
            }

            /**
             * Tab 补全入口：玩家输入 /death 后按 Tab 时触发
             * 委托给 CommandHandler 处理
             */
            @Override
            public @NonNull List<String> tabComplete(@NonNull CommandSender sender, @NonNull String alias, String @NonNull [] args) {
                // onTabComplete 可能返回 null（语义=此刻无补全项，属正常情况，非 bug），
                // 但本方法已承诺 @NonNull，故 null 时兜底为空列表（= 不弹任何补全），
                // 既满足非空契约，又不会像 requireNonNull 那样把 null 变成运行时崩溃
                List<String> result = commandHandler.onTabComplete(sender, this, alias, args);
                return result != null ? result : Collections.emptyList();
            }
        };

        // 设置指令描述（显示在 /help 列表中）
        // 注意：setDescription 接收纯文本 String，/help 列表不渲染颜色
        // 所以直接用 getConfig().getString 读原始字符串，不走 MiniMessage 解析
        registeredCommand.setDescription(
                getConfig().getString("messages.normal.command-description",
                        "death - 查看上次死亡位置"));
        // 设置用法提示（输入错误时显示）
        registeredCommand.setUsage("/" + mainCommandName);

        // 注册到 CommandMap
        // 第一个参数 "death" 是 fallback prefix（当指令名冲突时用于区分来源）
        // 第二个参数是 Command 对象
        commandMap.register("death", registeredCommand);

        // 输出注册成功日志
        Map<String, String> ph = new HashMap<>();
        ph.put("cmd", mainCommandName);
        logger.info(msg.formatMsg("messages.debug.command-registered",
                "主指令 /{cmd} 注册成功", ph, null));
    }

    /**
     * 反注册主指令（从 CommandMap 中移除）。
     *
     * <p>调用时机：
     * <ul>
     *   <li>插件 onDisable 时（防止卸载后指令残留）</li>
     *   <li>重载时指令名变更（先移除旧的，再注册新的）</li>
     * </ul>
     */
    private void unregisterMainCommand() {
        // 没有注册过 → 无需操作
        if (registeredCommand == null) {
            return;
        }
        CommandMap commandMap = getCommandMap();
        if (commandMap != null) {
            // 从 CommandMap 中移除该指令（之后玩家输入该指令会提示"未知指令"）
            registeredCommand.unregister(commandMap);
        }
        // 清空引用，允许 GC 回收
        registeredCommand = null;
    }

    /**
     * 通过反射获取服务端的 CommandMap 实例。
     *
     * <p>原理：Bukkit 的 Server 实现类（CraftServer）内部持有一个 commandMap 字段，
     * 但该字段没有公开的 getter 方法，只能通过反射访问。
     *
     * <p>风险：如果服务端修改了字段名或类结构，反射会失败。
     * 但目前 Spigot/Paper/Folia 等主流服务端都保持了该字段的兼容性。
     *
     * @return CommandMap 实例，反射失败时返回 null
     */
    private CommandMap getCommandMap() {
        try {
            // 获取 Server 实现类的 commandMap 字段
            // Bukkit.getServer() 返回的是 CraftServer 实例
            Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            // 绕过 private 访问限制
            field.setAccessible(true);
            // 获取字段值（即 CommandMap 实例）
            return (CommandMap) field.get(Bukkit.getServer());
        } catch (Exception e) {
            // 反射失败：可能是字段名变更、安全限制等
            logger.error(msg.formatMsg("messages.debug.reflect-failed",
                    "反射获取 CommandMap 失败", null, null), e);
            return null;
        }
    }

    /**
     * 检测当前服务端是否为 Paper（或其分支如 Purpur、Folia）。
     *
     * <p>用途：判断是否支持指令热重注册。
     * Paper 的 CommandMap 实现允许 unregister 后重新 register，
     * 而 Spigot 的原版实现不支持（会抛出异常或静默失败）。
     *
     * <p>检测方式：尝试加载 Paper 特有的类。
     * <ul>
     *   <li>com.destroystokyo.paper.PaperConfig → 旧版 Paper（1.16 及以前）</li>
     *   <li>io.papermc.paper.configuration.Configuration → 新版 Paper（1.17+）</li>
     * </ul>
     * 两个都找不到 → 不是 Paper。
     *
     * @return true = Paper 或其分支，false = Spigot/CraftBukkit/其他
     */
    private boolean isPaper() {
        try {
            // 尝试加载旧版 Paper 的配置类
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException e) {
            try {
                // 旧版没找到，尝试新版 Paper 的配置类
                Class.forName("io.papermc.paper.configuration.Configuration");
                return true;
            } catch (ClassNotFoundException e2) {
                // 两个都没找到 → 不是 Paper
                return false;
            }
        }
    }

    // ==================== 指令配置读取 ====================

    /**
     * 从 config.yml 中加载指令配置。
     *
     * <p>读取内容：
     * <ul>
     *   <li>commands.main.name → 主指令名（如 "death"）</li>
     *   <li>commands.sub → 子指令别名映射</li>
     * </ul>
     *
     * <p>config 中的结构示例：
     * <pre>
     * commands:
     *   main:
     *     name: "death"
     *   sub:
     *     inv: ["inv", "inventory", "i", "背包"]
     *     toggle: ["toggle", "t", "开关"]
     *     reload: ["reload", "rl"]
     * </pre>
     */
    private void loadCommandConfig() {
        // 读取主指令名（默认 "death"）
        mainCommandName = getConfig().getString("commands.main.name", "death");
        // 清空旧的子指令映射（重载时需要先清空再重新加载）
        subCommands.clear();

        // 读取子指令配置节
        ConfigurationSection sec = getConfig().getConfigurationSection("commands.sub");
        if (sec != null) {
            // 遍历每个子指令键（如 "inv", "toggle", "reload"）
            for (String key : sec.getKeys(false)) {
                // 获取该子指令的所有触发词列表
                List<String> triggers = sec.getStringList(key);
                // 只注册有触发词的子指令（空列表无意义）
                if (!triggers.isEmpty()) {
                    subCommands.put(key, triggers);
                }
            }
        }
    }

    /**
     * 将玩家输入的子指令文本解析为标准名。
     *
     * <p>例如：玩家输入 "i" → 匹配到 inv 的触发词列表 ["inv", "inventory", "i"]
     * → 返回标准名 "inv"
     *
     * <p>意图：CommandHandler 中只需处理标准名，无需关心玩家用了哪个别名。
     *
     * @param input 玩家输入的子指令文本（如 "i", "inv", "inventory"）
     * @return 标准名（如 "inv"），未匹配到任何子指令时返回 null
     */
    public String resolveSubCommand(String input) {
        // 统一转小写进行比较（指令不区分大小写）
        String lower = input.toLowerCase();
        // 遍历所有子指令的触发词列表
        for (Map.Entry<String, List<String>> entry : subCommands.entrySet()) {
            for (String trigger : entry.getValue()) {
                // 精确匹配（不是 startsWith，避免 "inv" 匹配到 "inventory2" 之类的）
                if (trigger.toLowerCase().equals(lower)) {
                    return entry.getKey();  // 返回标准名
                }
            }
        }
        // 没有任何匹配 → 返回 null（CommandHandler 中会提示"未知子指令"）
        return null;
    }

    /**
     * 获取主指令名（供 CommandHandler 中构建帮助消息使用）。
     */
    public String getMainCommandName() {
        return mainCommandName;
    }

    /**
     * 获取子指令别名映射（供 CommandHandler 中构建 Tab 补全使用）。
     */
    public Map<String, List<String>> getSubCommands() {
        return subCommands;
    }

    // ==================== 数据持久化 ====================

    /**
     * 保存所有玩家的设置数据到磁盘。
     *
     * <p>收集所有在内存中有记录的 UUID（取四个 Map 的并集），
     * 然后逐个调用 DataManager.saveData 写入 playerdata/{uuid}/config.yml。
     *
     * <p>调用时机：
     * <ul>
     *   <li>插件 onDisable（服务器关闭/插件卸载）</li>
     *   <li>reloadPlugin（重载前先保存，防止丢失）</li>
     * </ul>
     *
     * <p>注意：这里只保存"设置"数据（config 模式），不保存死亡背包。
     * 死亡背包在每次死亡时就已经实时保存了（DeathListener 中调用 dm.saveData(uuid, "death")）。
     */
    private void saveAll() {
        // 收集所有有数据的 UUID（四个 Map 的 key 取并集）
        // 使用 HashSet 去重（同一个玩家可能在多个 Map 中都有记录）
        Set<UUID> allUuids = new HashSet<>(deathLocations.keySet());
        allUuids.addAll(respawnNotify.keySet());
        allUuids.addAll(customDeathMsgMap.keySet());
        allUuids.addAll(deathMessages.keySet());

        // 逐个保存
        for (UUID uuid : allUuids) {
            dataManager.saveData(uuid, "config");
        }
    }

    /**
     * 清空所有内存缓存。
     *
     * <p>调用时机：reloadPlugin 中，在重新加载之前清空旧数据。
     * 意图：确保重载后不会残留旧的配置或数据。
     *
     * <p>注意：playerPageInv 和 menuClickActions 也被清空，
     * 这意味着重载后正在查看 GUI 的玩家会失去翻页能力（下次点击翻页时无效）。
     * 这是可接受的：重载是低频操作，且玩家关闭 GUI 后缓存本来就会被清理。
     */
    private void clearAll() {
        deathLocations.clear();
        respawnNotify.clear();
        customDeathMsgMap.clear();
        deathMessages.clear();
        deathInv.clear();
        deathFileIndex.clear();
        menuFiles.clear();
        playerPageInv.clear();
        menuClickActions.clear();
    }

    /**
     * 加载所有数据：玩家持久化数据 + 菜单文件列表。
     *
     * <p>调用时机：
     * <ul>
     *   <li>onEnable（首次启动）</li>
     *   <li>reloadPlugin（重载后重新加载）</li>
     * </ul>
     */
    private void loadAll() {
        // 加载所有玩家的持久化数据（遍历 playerdata/ 目录下的所有 UUID 文件夹）
        // 包括：死亡位置、复活提醒开关、自定义消息、死亡消息开关
        dataManager.loadAllData();

        // 加载菜单文件列表（从 config 的 menus 节点读取）
        // 先清空再添加（重载时需要）
        menuFiles.clear();
        menuFiles.addAll(getConfig().getStringList("menus"));

        // 输出每个成功加载的菜单文件的调试日志
        int count = 0;
        for (String menu : menuFiles) {
            // 跳过 null 或空字符串（配置错误时的防御性处理）
            if (menu != null && !menu.isEmpty()) {
                count++;
                Map<String, String> ph = new HashMap<>();
                ph.put("menus", String.valueOf(count));  // 第几个菜单
                ph.put("menu", menu);                     // 菜单文件名
                logger.info(msg.formatMsg("messages.debug.menu-loaded",
                        "成功加载第{menus}个菜单：{menu}", ph, null));
            }
        }
    }
}