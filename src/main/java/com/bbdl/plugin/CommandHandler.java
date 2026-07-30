package com.bbdl.plugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.*;

/**
 * 指令处理器：集中处理 {@code /death} 全部子指令的解析、分发与执行。
 *
 * <p>本类是"指令层"的唯一入口，承担三件事：把原始参数翻译成内部功能名、按功能名分流到对应处理器、
 * 为每个子指令提供 Tab 补全候选。它不持有任何业务状态，所有数据都通过 {@link #plugin} 访问主类仓库。
 *
 * <p>设计意图：
 * <ul>
 *   <li><b>职责剥离</b> —— 把指令逻辑从主类 {@code Memento} 中拆出，使主类只保留生命周期与数据仓库，
 *       避免"上帝类"。</li>
 *   <li><b>双接口委托</b> —— 同时实现 {@link CommandExecutor} 与 {@link TabCompleter}，
 *       由主类动态注册的 {@link Command} 对象把 {@code execute}/{@code tabComplete} 委托过来。</li>
 *   <li><b>单例复用</b> —— {@link #msg}/{@link #dm}/{@link #gui} 均引用主类已建好的实例，
 *       本类绝不重复 {@code new}，保证全局只有一份消息/持久化/GUI 工具。</li>
 * </ul>
 *
 * <p>线程模型：所有方法都在服务器主线程被调用（Bukkit 指令回调契约），故直接读写主类 {@code Map} 无需加锁。
 */
public class CommandHandler implements CommandExecutor, TabCompleter {

    /** 主插件实例：访问共享数据（各类 {@code Map}）、配置与运行时开关的唯一通道。 */
    private final Memento plugin;
    /** 消息格式化工具：统一走 config 模板 + MiniMessage 渲染，复用主类单例。 */
    private final MessageUtil msg;
    /** 数据持久化工具：把内存改动落盘，复用主类单例。 */
    private final DataManager dm;
    /** GUI 渲染工具：死亡背包菜单的展示，复用主类单例。 */
    private final GuiRenderer gui;

    /**
     * 构造时绑定主类已初始化好的共享实例，避免重复创建工具对象。
     *
     * <p><b>调用时序约束</b>：必须在 {@code Memento.onEnable()} 中完成
     * {@code msg}/{@code dataManager}/{@code guiRenderer} 的赋值之后再 {@code new} 本类，
     * 否则这里会拿到 {@code null} 字段引用。
     *
     * @param plugin 主插件实例，不能为 {@code null}
     */
    public CommandHandler(Memento plugin) {
        this.plugin = plugin;
        this.msg = plugin.msg;
        this.dm = plugin.dataManager;
        this.gui = plugin.guiRenderer;
    }

    // ==================== 指令执行入口 ====================

    /**
     * 指令总入口，由主类匿名 {@link Command} 的 {@code execute()} 委托调用。
     *
     * <p>分发顺序（自上而下短路）：
     * <ol>
     *   <li>控制台 sender → {@link #handleConsole}（仅 help / toggle 调试 / reload 可用）；</li>
     *   <li>玩家且无参数 → {@link #handleNoArgs}（查自己的死亡位置）；</li>
     *   <li>玩家且有参数 → {@code resolveSubCommand} 把触发词映射成内部功能名后 {@code switch} 分流；</li>
     *   <li>触发词未命中 → {@link #handleUnknownOrPlayerName}（先当玩家名查，查不到再提示 help）。</li>
     * </ol>
     *
     * @param sender  指令发送者（玩家或控制台），Bukkit 保证非 {@code null}
     * @param command 被执行的命令对象，本类不直接使用，仅为满足接口契约
     * @param label   玩家实际输入的指令别名，本类不直接使用，仅为满足接口契约
     * @param args    去掉指令名后的参数数组，可能为空数组但不会为 {@code null}
     * @return 恒为 {@code true}，向服务端声明"该指令已被本插件接管处理"，从而抑制 "Unknown command" 回显
     */
    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {

        // 控制台没有 Player 身份，无法走玩家分支，单独处理（且只能执行不依赖玩家身份的少数子指令）
        if (sender instanceof ConsoleCommandSender) {
            handleConsole(sender, args);
            return true;
        }

        // 能走到这里说明 sender 不是控制台；Bukkit 契约保证此时必为 Player，强转安全
        Player player = (Player) sender;

        // 无参数 = 最基础用法：/death 查自己上次死在哪
        if (args.length == 0) {
            return handleNoArgs(player);
        }

        // 把用户输入的触发词（如 "t"/"toggle"）映射为内部功能名（如 "toggle"）；
        // resolveSubCommand 会遍历 config 注册的全部触发词做匹配，未命中返回 null
        String funcName = plugin.resolveSubCommand(args[0]);

        // 触发词没匹配上任何已注册子指令 → 退一步当玩家名查询，仍失败则提示 help
        if (funcName == null) {
            handleUnknownOrPlayerName(player, args);
            return true;
        }

        // 功能名已确定，按名分流到对应处理器；每个分支处理完即返回，互不干扰
        switch (funcName) {
            case "help" -> {
                sendHelp(player);
                return true;
            }
            case "toggle" -> {
                handleToggle(player, args);
                return true;
            }
            case "custom" -> {
                handleCustom(player, args);
                return true;
            }
            case "broadcast" -> {
                handleBroadcast(player, args);
                return true;
            }
            case "inv" -> {
                handleInv(player, args);
                return true;
            }
            case "get" -> {
                handleGet(player, args);
                return true;
            }
            case "reload" -> {
                handleReload(player);
                return true;
            }
            default -> {
                // 防御性兜底：resolveSubCommand 理论上只返回已注册 key，不应落到这里；
                // 保留它是为了在 config 配错（注册了名却没写 case）时给玩家一个友好反馈，而非静默吞掉
                Map<String, String> ph = new HashMap<>();
                ph.put("func", funcName);
                player.sendMessage(msg.formatMsg("messages.normal.not-implemented",
                        "功能 {func} 尚未实现", ph, player));
                return true;
            }
        }
    }

    // ==================== 帮助信息 ====================

    /**
     * 发送帮助菜单，玩家与控制台共用本方法。
     *
     * <p>所有文本经消息系统输出，既支持 config 自定义，也支持 MiniMessage 颜色标签。
     * 每行帮助是独立的消息 key，服主可在 config 中逐行改写而不影响其它行。
     *
     * @param sender 接收帮助信息的发送者；为玩家时额外传入其引用以解析 PlaceholderAPI 占位符
     */
    private void sendHelp(CommandSender sender) {
        // 玩家传自身引用（供 PlaceholderAPI 解析），控制台无玩家上下文，传 null
        Player player = (sender instanceof Player) ? (Player) sender : null;
        // 主指令名可能被服主在 config 中改过，这里动态取，保证帮助文本与真实指令名一致
        String cmd = plugin.getMainCommandName();

        // 占位符表：{cmd} → 当前主指令名，使帮助里的示例命令自动跟随指令名变更
        Map<String, String> ph = new HashMap<>();
        ph.put("cmd", cmd);

        // 逐行下发：header 为标题，其余每行对应一个子指令的用法说明
        sender.sendMessage(msg.formatMsg("messages.help.header",
                "<gold>=== {cmd} 帮助 ===", ph, player));
        sender.sendMessage(msg.formatMsg("messages.help.toggle",
                "<gray>/{cmd} toggle [玩家] <true|false> <gray>- 切换复活提醒", ph, player));
        sender.sendMessage(msg.formatMsg("messages.help.broadcast",
                "<gray>/{cmd} broadcast <true|false> <gray>- 全局死亡广播开关", ph, player));
        sender.sendMessage(msg.formatMsg("messages.help.custom",
                "<gray>/{cmd} custom <player|cause> <消息> <gray>- 自定义死亡消息", ph, player));
        sender.sendMessage(msg.formatMsg("messages.help.inv",
                "<gray>/{cmd} inv <玩家> [历史] [页码] <gray>- 查看死亡背包", ph, player));
        sender.sendMessage(msg.formatMsg("messages.help.help",
                "<gray>/{cmd} help <gray>- 显示此帮助", ph, player));
    }

    // ==================== Tab 补全 ====================

    /**
     * Tab 补全逻辑：依据已输入参数的个数与当前子指令，返回候选词列表。
     *
     * <p>补全策略按参数位分层：
     * <ul>
     *   <li>第 1 位 —— 所有已注册触发词，按用户已输入前缀过滤；</li>
     *   <li>第 2 位 —— 依子指令类型给出 {@code true/false}、在线玩家名、{@code player/cause} 等；</li>
     *   <li>第 3 位及以后 —— 依子指令给出数字范围（历史序号/页码）或占位符提示。</li>
     * </ul>
     *
     * <p>注意：本方法不做权限过滤，候选词仅用于输入辅助；真正的权限校验在各 {@code handle*} 执行时进行。
     *
     * @param sender  触发补全的发送者，本方法未据此过滤（保留参数以满足接口契约）
     * @param command 被补全的命令对象，未使用，仅为满足接口契约
     * @param alias   玩家实际输入的指令别名，未使用，仅为满足接口契约
     * @param args    当前已输入的参数数组，长度至少为 1（Bukkit 在用户敲下指令名后才会回调）
     * @return 候选词列表；无匹配时返回空列表（而非 {@code null}），Bukkit 据此不弹补全框
     */
    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String[] args) {
        List<String> result = new ArrayList<>();

        if (args.length == 1) {
            // 第 1 位：遍历全部触发词，按用户已输入的小写前缀过滤（输入 "t" 命中 "toggle" 等）
            String partial = args[0].toLowerCase();
            for (List<String> triggers : plugin.getSubCommands().values()) {
                for (String trigger : triggers) {
                    if (trigger.toLowerCase().startsWith(partial)) {
                        result.add(trigger);
                    }
                }
            }
        } else if (args.length == 2) {
            // 第 2 位：先确定子指令类型，再给该类型专属的候选
            String funcName = plugin.resolveSubCommand(args[0]);
            if (funcName != null) {
                switch (funcName) {
                    case "toggle" -> {
                        // toggle 第 2 位既可能是设定值 true/false，也可能是要操作的目标玩家名
                        result.add("true");
                        result.add("false");
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            result.add(p.getName());
                        }
                    }
                    case "broadcast" -> {
                        // broadcast 第 2 位同上：设定值或目标玩家名
                        result.add("true");
                        result.add("false");
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            result.add(p.getName());
                        }
                    }
                    case "custom" -> {
                        // custom 第 2 位是消息类型：player（被玩家杀）或 cause（环境/怪物致死）
                        result.add("player");
                        result.add("cause");
                    }
                    case "inv" -> {
                        // inv 第 2 位是目标玩家名
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            result.add(p.getName());
                        }
                    }
                    case "get"  -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            result.add(p.getName());
                        }
                    }
                }
            }
        } else if (args.length >= 3) {
            // 第 3 位及以后：候选进一步收敛到具体取值
            String funcName = plugin.resolveSubCommand(args[0]);
            if ("toggle".equals(funcName) || "broadcast".equals(funcName)) {
                // 操作别人时的第 3 位是显式布尔设定值
                result.add("true");
                result.add("false");
            } else if ("inv".equals(funcName)) {
                if (args.length == 3) {
                    // 第 3 位 = 历史序号（第几次死亡，从 0 起），上限取 config 的 max-history
                    int maxHistory = plugin.getConfig().getInt("settings.max-history", 0);
                    for (int i = 0; i < maxHistory; i++) {
                        result.add(String.valueOf(i));
                    }
                } else if (args.length == 4) {
                    // 第 4 位 = 菜单页码，范围 1 ~ 菜单文件总数（menuFiles 由主类扫描资源得到）
                    int maxPage = plugin.menuFiles.size();
                    for (int i = 1; i <= maxPage; i++) {
                        result.add(String.valueOf(i));
                    }
                }
            } else if ("custom".equals(funcName)) {
                // 第 3 位起提示该类型可用占位符，方便玩家 Tab 直接补出，免去手敲花括号
                if (args[1].equalsIgnoreCase("player")) {
                    result.add("{player}");
                    result.add("{killer}");
                    result.add("{weapon}");
                } else if (args[1].equalsIgnoreCase("cause")) {
                    result.add("{player}");
                    result.add("{cause}");
                }
            }
        }

        return result;
    }

    // ==================== 控制台指令处理 ====================

    /**
     * 控制台专用处理：仅放行 help / toggle（调试模式）/ reload 三类。
     *
     * <p>其余子指令（inv、custom 等）依赖玩家身份或玩家数据，控制台无意义，故不在此分支处理；
     * 输入不支持的子指令时不抛错，而是回显帮助做友好引导。
     *
     * @param sender 控制台发送者
     * @param args   参数数组
     */
    private void handleConsole(CommandSender sender, String[] args) {
        // 控制台裸敲指令（无参数）→ 直接给帮助，让服主知道有哪些可用项
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        String funcName = plugin.resolveSubCommand(args[0]);

        // help：与玩家侧共用同一份帮助渲染
        if ("help".equals(funcName)) {
            sendHelp(sender);
            return;
        }

        // toggle 在控制台语义被复用为"调试模式开关"（注意：并非玩家的复活提醒开关）。
        // 意图：让服主无需重启即可在运行时动态开关 debug 日志，便于现场排障
        if ("toggle".equals(funcName)) {
            plugin.debugMode = !plugin.debugMode;
            if (plugin.debugMode) {
                // 开启态用 debug() 输出，该方法自带 [Debug] 前缀且受 debugMode 门控
                msg.debug("messages.debug.toggle-on", "调试模式已开启");
            } else {
                // 关闭态不能用 debug()（此刻 debugMode 已为 false，debug() 会被静默），故走普通 info
                plugin.logger.info(msg.formatMsg("messages.debug.toggle-off",
                        "调试模式已关闭", null, null));
            }
            return;
        }

        // reload：热重载配置，并计时回显，便于服主判断重载是否存在性能问题
        if ("reload".equals(funcName)) {
            long start = System.currentTimeMillis();
            plugin.reloadPlugin();
            long cost = System.currentTimeMillis() - start;

            Map<String, String> ph = new HashMap<>();
            ph.put("cost", String.valueOf(cost));
            plugin.logger.info(msg.formatMsg("messages.debug.success-reload",
                    "插件重载完成! 耗时 {cost}ms", ph, null));
            return;
        }

        // 走到这里 = 控制台敲了不支持的子指令：不报错，回显帮助并带上原始输入便于服主自查
        Map<String, String> ph = new HashMap<>();
        ph.put("input", args[0]);
        ph.put("cmd", plugin.getMainCommandName());
        plugin.logger.info(msg.formatMsg("messages.normal.console-unknown",
                "未知指令: {input}，输入 /{cmd} help 查看帮助", ph, null));
    }

    // ==================== 玩家：无参数 ====================

    /**
     * 处理 {@code /death}（无参数）：查询并回显调用者自己的上次死亡位置。
     *
     * <p>这是插件最基础的用法——玩家死亡后敲 {@code /ld} 即可定位尸体。
     * 数据来自主类内存缓存（启动时由 {@code DataManager.loadAllData()} 灌入）。
     *
     * @param player 调用指令的玩家
     * @return 恒为 {@code true}（与 {@link #onCommand} 的"已处理"语义对齐）
     */
    private boolean handleNoArgs(Player player) {
        // 取自己的死亡位置缓存；为 null 表示从未死过，或重启后对应世界尚未加载
        Location loc = plugin.deathLocations.get(player.getUniqueId());
        if (loc == null) {
            player.sendMessage(msg.formatMsg("messages.normal.no-death-record",
                    "你还没有死亡记录!", null, player));
        } else {
            // 有记录：把世界名与整数坐标装进占位符表，渲染成可读的死亡位置消息
            Map<String, String> ph = new HashMap<>();
            ph.put("world", loc.getWorld().getName());
            ph.put("x", String.valueOf(loc.getBlockX()));
            ph.put("y", String.valueOf(loc.getBlockY()));
            ph.put("z", String.valueOf(loc.getBlockZ()));
            ph.put("player", player.getName());
            player.sendMessage(msg.formatMsg("messages.normal.self-death-location",
                    "你上次死在了 {world} 的 {x} {y} {z}", ph, player));
        }
        return true;
    }

    // ==================== 玩家：未匹配的输入 ====================

    /**
     * 处理无法映射到任何子指令的输入。
     *
     * <p>策略：当且仅当参数恰为 1 个时，先把它当玩家名查其死亡位置；查不到或参数多于 1 个，
     * 则判定为未知指令并引导 help。
     *
     * <p>意图：让玩家能直接 {@code /ld Notch} 查别人死在哪，省去记忆子指令的心智负担。
     *
     * @param player 调用指令的玩家（查询发起者）
     * @param args   参数数组，此处 {@code args[0]} 为待解析的疑似玩家名
     */
    private void handleUnknownOrPlayerName(Player player, String[] args) {
        // 仅当恰好 1 个参数时才尝试当玩家名（多参数绝不可能是合法玩家名）
        if (args.length == 1) {
            // 用 IfCached 变体：只查本地缓存，绝不触发 Mojang API 网络请求，避免阻塞主线程
            OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[0]);
            if (target != null) {
                // 缓存命中 → 进一步看能否把其死亡位置展示给查询者
                Location loc = plugin.deathLocations.get(target.getUniqueId());
                // 目标是否开启全局死亡广播（缺省 true = 开启）
                boolean broadcastEnabled = plugin.deathMessages.getOrDefault(target.getUniqueId(), true);

                // 可见性判定：有记录 且（广播开着 或 查询者持管理员查看权限）
                boolean canView = loc != null && (broadcastEnabled || player.hasPermission("Memento.view"));

                if (canView) {
                    // 拼装目标的世界与坐标占位符，回显其死亡位置
                    Map<String, String> ph = new HashMap<>();
                    ph.put("player", target.getName());
                    ph.put("world", loc.getWorld().getName());
                    ph.put("x", String.valueOf(loc.getBlockX()));
                    ph.put("y", String.valueOf(loc.getBlockY()));
                    ph.put("z", String.valueOf(loc.getBlockZ()));
                    player.sendMessage(msg.formatMsg("messages.normal.other-death-location",
                            "{player} 上次死在了 {world} 的 {x} {y} {z}", ph, player));
                } else {
                    // 无记录，或广播关且查询者无权限：统一回"无记录"，刻意不暴露"对方关了广播"这一隐私
                    player.sendMessage(msg.formatMsg("messages.normal.no-death-record-other",
                            "该玩家没有死亡记录!", null, player));
                }
                return;
            }
        }

        // 缓存无此人，或参数不止一个 → 视为未知指令，回显帮助并带上原始输入
        Map<String, String> ph = new HashMap<>();
        ph.put("input", args[0]);
        ph.put("cmd", plugin.getMainCommandName());
        player.sendMessage(msg.formatMsg("messages.normal.unknown-command",
                "<red>未知指令: {input}，输入 /{cmd} help 查看帮助", ph, player));
    }

    // ==================== 子指令：toggle ====================

    /**
     * 处理 {@code /death toggle [玩家] [true|false]}：切换"复活提醒"开关。
     *
     * <p>开启后，玩家复活时会收到上次死亡位置提示。四种入参形态：
     * <ul>
     *   <li>{@code /ld toggle} —— 取反自己的开关；</li>
     *   <li>{@code /ld toggle true|false} —— 显式设定自己的开关；</li>
     *   <li>{@code /ld toggle <玩家名>} —— 取反别人的开关（需 {@code Memento.toggle.others}）；</li>
     *   <li>{@code /ld toggle <玩家名> true|false} —— 显式设定别人的开关。</li>
     * </ul>
     *
     * <p>每次写内存后都立即 {@code saveData} 落盘，防止服务器崩溃丢失玩家设置。
     *
     * @param player 调用指令的玩家（操作发起者）
     * @param args   参数数组
     */
    private void handleToggle(Player player, String[] args) {
        // 形态 1：/ld toggle → 取反自己的开关
        if (args.length == 1) {
            // 读当前值，缺省 true（新玩家默认开启复活提醒）
            boolean enabled = plugin.respawnNotify.getOrDefault(player.getUniqueId(), true);
            // 取反写回内存并立即落盘
            plugin.respawnNotify.put(player.getUniqueId(), !enabled);
            dm.saveData(player.getUniqueId(), "config");
            // 注意：enabled 是"改之前"的旧值，故 !enabled 才代表"改之后的新状态"；
            // 旧值为 false 意味着这次把它打开了，反之亦然
            if (!enabled) {
                player.sendMessage(msg.formatMsg("messages.normal.notify-enabled",
                        "复活提醒已开启", null, player));
            } else {
                player.sendMessage(msg.formatMsg("messages.normal.notify-disabled",
                        "复活提醒已关闭", null, player));
            }
            return;
        }

        // 形态 2：/ld toggle true|false → 不取反，直接把第 2 个参数解析为目标值
        if (args.length == 2 && (args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false"))) {
            boolean value = args[1].equalsIgnoreCase("true");
            plugin.respawnNotify.put(player.getUniqueId(), value);
            dm.saveData(player.getUniqueId(), "config");
            // 用三元按设定值挑消息 key 与默认文案，避免再写一遍 if/else
            String key = value ? "messages.normal.notify-enabled" : "messages.normal.notify-disabled";
            String defMsg = value ? "复活提醒已开启" : "复活提醒已关闭";
            player.sendMessage(msg.formatMsg(key, defMsg, null, player));
            return;
        }

        // 形态 3/4：操作别人。先做权限闸——无 toggle.others 权限者只能改自己
        if (!player.hasPermission("Memento.toggle.others")) {
            // 目标名不是自己 → 越权，拒绝并终止
            if (!args[1].equalsIgnoreCase(player.getName())) {
                player.sendMessage(msg.formatMsg("messages.normal.no-permission",
                        "你没有权限执行此操作", null, player));
                return;
            }
        }

        // 解析目标玩家：只查缓存，不触发 Mojang 网络请求，避免阻塞主线程
        OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            player.sendMessage(msg.formatMsg("messages.normal.player-not-found",
                    "找不到该玩家", null, player));
            return;
        }

        // 占位符 {player} 指向目标名，供下面两条"操作别人"的反馈文案使用
        Map<String, String> ph = new HashMap<>();
        ph.put("player", target.getName());

        // 形态 3：/ld toggle <玩家> → 取反目标的开关
        if (args.length == 2) {
            boolean enabled = plugin.respawnNotify.getOrDefault(target.getUniqueId(), true);
            plugin.respawnNotify.put(target.getUniqueId(), !enabled);
            dm.saveData(target.getUniqueId(), "config");
            if (!enabled) {
                player.sendMessage(msg.formatMsg("messages.normal.notify-other-enabled",
                        "{player} 的复活提醒已开启", ph, player));
            } else {
                player.sendMessage(msg.formatMsg("messages.normal.notify-other-disabled",
                        "{player} 的复活提醒已关闭", ph, player));
            }
            return;
        }

        // 形态 4：/ld toggle <玩家> true|false → 显式设定目标的开关。
        // 能落到这里说明 args.length >= 3 且前面所有 return 都未触发，流程必然收敛，无需额外兜底
        boolean value = args[2].equalsIgnoreCase("true");
        plugin.respawnNotify.put(target.getUniqueId(), value);
        dm.saveData(target.getUniqueId(), "config");
        String key = value ? "messages.normal.notify-other-enabled" : "messages.normal.notify-other-disabled";
        String def = value ? "{player} 的复活提醒已开启" : "{player} 的复活提醒已关闭";
        player.sendMessage(msg.formatMsg(key, def, ph, player));
    }

    // ==================== 子指令：custom ====================

    /**
     * 处理 {@code /death custom <player|cause> <消息>}：让玩家自定义自己的死亡广播模板。
     *
     * <ul>
     *   <li>{@code player} 类型 —— 被其他玩家击杀时显示，可用占位符 {@code {player} {killer} {weapon}}；</li>
     *   <li>{@code cause} 类型 —— 因环境/怪物致死时显示，可用占位符 {@code {player} {cause}}。</li>
     * </ul>
     *
     * <p>单条设置的完整流水线：校验长度 → 校验必需占位符 → 扣费（可选）→ 写内存 → 预览效果 → 落盘。
     * 任一校验不通过即短路返回，不会污染内存。
     *
     * @param player 调用指令的玩家
     * @param args   参数数组，{@code args[1]} 为类型，{@code args[2..]} 拼接为消息文本
     */
    private void handleCustom(Player player, String[] args) {

        // 通用占位符 {cmd}：用法提示里要回显当前主指令名
        Map<String, String> ph = new HashMap<>();
        ph.put("cmd", plugin.getMainCommandName());

        // 只敲了 custom 没给类型 → 回顶层用法
        if (args.length == 1) {
            player.sendMessage(msg.formatMsg("messages.normal.custom-usage",
                    "用法: /{cmd} custom [player|cause] <自定义消息>", ph, player));
            return;
        }

        // ===== player 类型：被玩家击杀时的自定义消息 =====
        if (args[1].equalsIgnoreCase("player")) {
            // 给了类型却没给消息体 → 回该类型的详细用法与可用占位符
            if (args.length == 2) {
                player.sendMessage(msg.formatMsg("messages.normal.custom-usage-player",
                        "用法: /{cmd} custom player <消息> 占位符: {player} {killer} {weapon}", ph, player));
                return;
            }

            // 第 3 个参数起用空格拼成完整文本（指令按空格切词，这里再还原成一句话）
            String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

            // 校验 1：纯文本长度（剔除占位符后计长，防止刷屏，且不让占位符本身占配额）
            if (isLengthInvalid(player, text)) return;

            // 校验 2：必需占位符齐全性（严格模式缺则拦，非严格仅提示）
            List<String> requiredPlaceholders = Arrays.asList("player", "killer", "weapon");
            if (hasInvalidPlaceholders(player, text, requiredPlaceholders)) return;

            // 校验 3：扣费闸（config 未开收费时该方法直接放行）
            if (isCustomMessageBlocked(player)) {
                return;  // 余额不足或扣费失败，方法内已发提示，这里直接终止
            }

            // 三关全过 → 写内存。computeIfAbsent 保证"无则新建、有则复用"，避免覆盖既有另一类型模板
            CustomDeathMsg customMsg = plugin.customDeathMsgMap.computeIfAbsent(
                    player.getUniqueId(), _ -> new CustomDeathMsg());
            customMsg.byPlayer = text;

            // 先告知设置成功，紧接着给预览，让玩家确认实际显示效果
            player.sendMessage(msg.formatMsg("messages.normal.custom-set-success",
                    "成功设置自定义被玩家杀死时的消息(预览): ", null, player));

            // 用 config 的预览假值替换占位符渲染一遍；key 传 null 表示跳过 config 模板、直接以 text 为模板
            Map<String, String> previewPh = new HashMap<>();
            previewPh.put("player", player.getName());
            previewPh.put("killer", plugin.getConfig().getString("settings.preview.killer", "Notch"));
            previewPh.put("weapon", plugin.getConfig().getString("settings.preview.weapon", "钻石剑"));
            player.sendMessage(msg.formatMsg(null, text, previewPh, player));

            // 落盘，保证玩家下次上线该模板仍生效
            dm.saveData(player.getUniqueId(), "config");
            return;
        }

        // ===== cause 类型：因环境/怪物死亡时的自定义消息（流水线与 player 同构，仅占位符集不同） =====
        if (args[1].equalsIgnoreCase("cause")) {
            if (args.length == 2) {
                player.sendMessage(msg.formatMsg("messages.normal.custom-usage-cause",
                        "用法: /{cmd} custom cause <消息> 占位符: {player} {cause}", ph, player));
                return;
            }

            String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (isLengthInvalid(player, text)) return;

            // cause 类型只需 {player} 与 {cause} 两个必需占位符
            List<String> requiredPlaceholders = Arrays.asList("player", "cause");
            if (hasInvalidPlaceholders(player, text, requiredPlaceholders)) return;

            if (isCustomMessageBlocked(player)) {
                return;
            }

            CustomDeathMsg customMsg = plugin.customDeathMsgMap.computeIfAbsent(
                    player.getUniqueId(), _ -> new CustomDeathMsg());
            customMsg.byCause = text;

            player.sendMessage(msg.formatMsg("messages.normal.custom-set-success",
                    "成功设置自定义死亡消息(预览): ", null, player));

            Map<String, String> previewPh = new HashMap<>();
            previewPh.put("player", player.getName());
            previewPh.put("cause", plugin.getConfig().getString("settings.preview.cause", "摔死"));
            player.sendMessage(msg.formatMsg(null, text, previewPh, player));

            dm.saveData(player.getUniqueId(), "config");
            return;
        }

        // 第 2 个参数既非 player 也非 cause → 回顶层用法
        player.sendMessage(msg.formatMsg("messages.normal.custom-usage",
                "用法: /{cmd} custom [player|cause] <消息>", ph, player));
    }

    /**
     * 自定义消息的扣费闸：判断本次设置是否应被拦截。
     *
     * <p>判定优先级（自上而下短路）：
     * <ol>
     *   <li>未开启收费 → 放行（免费）；</li>
     *   <li>开启收费但经济插件不可用 → 拦截并提示功能不可用；</li>
     *   <li>持 {@code Memento.free-custom} 权限 → 豁免放行；</li>
     *   <li>余额不足 → 拦截；</li>
     *   <li>扣费调用本身失败（极端情况）→ 拦截；</li>
     *   <li>扣费成功 → 回显余额并放行。</li>
     * </ol>
     *
     * <p><b>返回值方向</b>：方法名是 "blocked"，故 {@code true} 表示"被拦截"，与调用处
     * {@code if (isCustomMessageBlocked(player)) return;} 的"拦了就退出"语义一致。
     *
     * @param player 待扣费的玩家
     * @return {@code true} = 拦截（方法内已发提示，调用方应直接返回）；
     *         {@code false} = 放行（免费、豁免或扣费成功）
     */
    private boolean isCustomMessageBlocked(Player player) {
        // 收费总开关，缺省 false = 不收费
        boolean chargeEnabled = plugin.getConfig().getBoolean("settings.custom-death-message.enabled", false);

        // 1. 没开收费 → 免费放行
        if (!chargeEnabled) {
            return false;
        }

        // 2. 开了收费却无经济插件（Vault 未装/无经济实现）→ 功能不可用，拦截。
        // customMessageEnabled 由主类 onEnable 根据 Vault 探测结果设定
        if (!plugin.customMessageEnabled) {
            player.sendMessage(msg.formatMsg("messages.normal.custom-msg-disabled",
                    "自定义死亡消息功能当前不可用", null, player));
            return true;
        }

        // 3. 管理员豁免：持 free-custom 权限者不扣费，直接放行
        if (player.hasPermission("Memento.free-custom")) {
            return false;
        }

        // 4. 进入正常扣费流程
        double cost = plugin.getConfig().getDouble("settings.custom-death-message.cost", 100.0);
        Map<String, String> ph = new HashMap<>();
        ph.put("cost", String.valueOf(cost));

        // 余额不足 → 提示所需金额并拦截
        if (!plugin.economy.has(player, cost)) {
            player.sendMessage(msg.formatMsg("messages.normal.not-enough-money",
                    "余额不足，需要 {cost} 元", ph, player));
            return true;
        }

        // 真正扣款。withdrawPlayer 返回 EconomyResponse，须用 transactionSuccess() 判定是否到账级成功，
        // 而不能仅看 has()——has 通过但 withdraw 仍可能因经济插件内部异常/并发而失败
        if (!plugin.economy.withdrawPlayer(player, cost).transactionSuccess()) {
            player.sendMessage(msg.formatMsg("messages.normal.charge-failed",
                    "扣费失败，请稍后重试", null, player));
            return true;
        }

        // 5. 扣费成功 → 回显扣除额与扣后余额，放行
        double balance = plugin.economy.getBalance(player);
        ph.put("balance", String.valueOf(balance));
        player.sendMessage(msg.formatMsg("messages.normal.charge-success",
                "扣费成功！扣除金额：{cost} 余额: {balance}", ph, player));
        return false;
    }

    // ==================== 子指令：broadcast ====================

    /**
     * 处理 {@code /death broadcast [true|false|玩家名] [true|false]}：控制全局死亡广播可见性。
     *
     * <p>关闭后，自己死亡不广播给别人、也看不到别人的广播；开启则恢复正常。
     * 持 {@code Memento.view} 权限者可查看/修改他人广播状态。
     *
     * @param player 调用指令的玩家
     * @param args   参数数组
     */
    private void handleBroadcast(Player player, String[] args) {
        // 形态 1：/ld broadcast → 取反自己的广播开关
        if (args.length == 1) {
            // 读旧值（缺省 true = 开启）；用包装类 Boolean 仅为承接 Map 的 V 类型，getOrDefault 保证非 null，拆箱安全
            Boolean value = plugin.deathMessages.getOrDefault(player.getUniqueId(), true);
            plugin.deathMessages.put(player.getUniqueId(), !value);
            dm.saveData(player.getUniqueId(), "config");  // 立即落盘
            // value 是旧值，!value 才是新状态：旧值为 false 表示这次打开了
            if (!value) {
                player.sendMessage(msg.formatMsg("messages.normal.broadcast-enabled",
                        "已开启全局死亡广播，同时你可以看到其他开启的玩家的全局广播", null, player));
            } else {
                player.sendMessage(msg.formatMsg("messages.normal.broadcast-disabled",
                        "已关闭全局死亡广播，你无法看到任何死亡广播", null, player));
            }
            return;
        }

        // 形态 2：/ld broadcast true|false → 显式设定自己的开关
        if (args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false")) {
            boolean value = args[1].equalsIgnoreCase("true");
            plugin.deathMessages.put(player.getUniqueId(), value);
            dm.saveData(player.getUniqueId(), "config");
            String key = value ? "messages.normal.broadcast-enabled" : "messages.normal.broadcast-disabled";
            String defMsg = value
                    ? "已开启全局死亡广播，同时你可以看到其他开启的玩家的全局广播"
                    : "已关闭全局死亡广播，你无法看到任何死亡广播";
            player.sendMessage(msg.formatMsg(key, defMsg, null, player));
            return;
        }

        // 形态 3/4：操作别人，先过权限闸
        if (!player.hasPermission("Memento.view")) {
            player.sendMessage(msg.formatMsg("messages.normal.no-permission",
                    "你没有权限执行此操作", null, player));
            return;
        }

        // 解析目标：只查缓存，且要求曾进过服（hasPlayedBefore），排除纯输入串误命中
        OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[1]);
        if (target == null || !target.hasPlayedBefore()) {
            player.sendMessage(msg.formatMsg("messages.normal.player-not-found",
                    "找不到该玩家", null, player));
            return;
        }

        Map<String, String> ph = new HashMap<>();

        // 形态 3：/ld broadcast <玩家> → 只读查询目标当前广播状态，不修改
        if (args.length == 2) {
            ph.put("value", String.valueOf(plugin.deathMessages.getOrDefault(target.getUniqueId(), true)));
            ph.put("player", target.getName());
            player.sendMessage(msg.formatMsg("messages.normal.broadcast-status",
                    "玩家 {player} 的全局广播状态为 {value}", ph, player));
            return;
        }

        // 形态 4：/ld broadcast <玩家> true|false → 显式设定目标的开关并落盘
        boolean value = args[2].equalsIgnoreCase("true");
        plugin.deathMessages.put(target.getUniqueId(), value);
        dm.saveData(target.getUniqueId(), "config");
        String key = value ? "messages.normal.broadcast-other-enabled" : "messages.normal.broadcast-other-disabled";
        String defMsg = value ? "已开启 {player} 的全局广播" : "已关闭 {player} 的全局广播";
        ph.put("player", target.getName());
        player.sendMessage(msg.formatMsg(key, defMsg, ph, player));
    }

    // ==================== 子指令：inv ====================

    /**
     * 处理 {@code /death inv [玩家] [历史序号] [页码]}：打开 GUI 查看死亡时保存的背包。
     *
     * <p>参数为"右对齐缺省"：缺玩家=自己，缺历史=最近一次（1），缺页码=第 1 页。
     * 历史序号语义为"第几次死亡"（1=最近，2=倒数第二……），页码对应主类扫描到的菜单文件序号。
     *
     * @param player 调用指令的玩家（GUI 的观看者）
     * @param args   参数数组
     */
    private void handleInv(Player player, String[] args) {
        // /ld inv → 看自己、最近一次、第 1 页
        if (args.length == 1) {
            gui.showDeathInv(player, player.getUniqueId(), 1, 1);
            return;
        }

        // 解析目标玩家：只查缓存，避免网络阻塞
        OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            player.sendMessage(msg.formatMsg("messages.normal.player-not-found",
                    "找不到该玩家", null, player));
            return;
        }

        // /ld inv <玩家> → 看目标、最近一次、第 1 页
        if (args.length == 2) {
            gui.showDeathInv(player, target.getUniqueId(), 1, 1);
            return;
        }

        // 解析历史序号；非数字直接报错并终止，不向下传非法值
        int history;
        try {
            history = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(msg.formatMsg("messages.normal.invalid-number",
                    "请输入有效的数字", null, player));
            return;
        }

        // /ld inv <玩家> <历史> → 看目标第 N 次死亡、第 1 页
        if (args.length == 3) {
            gui.showDeathInv(player, target.getUniqueId(), history, 1);
            return;
        }

        // 解析页码；同样做数字校验
        int page;
        try {
            page = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(msg.formatMsg("messages.normal.invalid-number",
                    "请输入有效的数字", null, player));
            return;
        }

        // /ld inv <玩家> <历史> <页码> → 参数齐全，打开指定页
        gui.showDeathInv(player, target.getUniqueId(), history, page);
    }

    // ==================== 子指令：get ====================
    private void handleGet(Player player, String[] args) {
        // 检查权限
        if (!player.hasPermission("Memento.get")) {
            player.sendMessage(msg.formatMsg("messages.normal.no-permission",
                    "你没有权限执行此操作", null, player));
            return;
        }

        // 无参数
        if (args.length == 1) {
            return;
        }

        // 有参数
        if (args.length >= 2) {
            // 解析目标玩家：只查缓存，避免网络阻塞
            OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                player.sendMessage(msg.formatMsg("messages.normal.player-not-found",
                        "找不到该玩家", null, player));
                return;
            }
            // 无其他参数
            if (args.length == 2) {
                return;
            }
            // 解析历史序号；非数字直接报错并终止，不向下传非法值
            int history;
            try {
                history = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(msg.formatMsg("messages.normal.invalid-number",
                        "请输入有效的数字", null, player));
                return;
            }
            // ===== 校验 history 参数 =====
            // history <= 0 无意义，强制修正为 1（最近一次死亡）
            if (history <= 0) history = 1;

            // 检查是否超出配置的最大可查看历史次数
            // max-history = 0 表示不限制；非 0 时 history 不能超过该值
            int max_history = plugin.getConfig().getInt("settings.max-history", 0);
            if (max_history != 0 && history > max_history) {
                Map<String, String> ph = new HashMap<>();
                ph.put("history", history + "");
                ph.put("max-history", max_history + "");
                player.sendMessage(msg.formatMsg("messages.gui.history-out-of-range",
                        "仅支持查看 {max-history} 次死亡内的背包，你输入了 {history}", ph, player));
                return;
            }

            // ===== 检查目标玩家的死亡数据文件夹是否存在 =====
            File playerDeathInv = new File(new File(plugin.getDataFolder(), "playerdata"), target.getUniqueId().toString());
            if (!playerDeathInv.exists()) {
                // 文件夹不存在 → 该玩家从未死亡过（或数据被手动删除）
                player.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
                return;
            }
            // ===== 收集所有死亡记录文件并按时间排序 =====
            // TreeMap 自动按 key（时间戳）升序排列，最早的在前，最新的在后
            Map<Long, File> deathFiles = new TreeMap<>();
            File[] fileList = playerDeathInv.listFiles();
            if (fileList == null) {
                player.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
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
                player.sendMessage(msg.formatMsg("messages.gui.no-death-inventory", "死亡背包不存在", null, player));
                return;
            }


            Map<String,String> ph = new HashMap<>();

            // ===== 第九步：加载目标死亡记录的背包数据 =====
            // TreeMap 升序排列，最新的在最后，所以第 N 次死亡 = 倒数第 N 个
            // 索引计算：times.size() - history（history=1 → 最后一个 = 最新）
            List<Long> times = new ArrayList<>(deathFiles.keySet());
            long t = times.get(times.size() - history);
            File f = deathFiles.get(t);
            YamlConfiguration data = YamlConfiguration.loadConfiguration(f);

            // 反序列化背包内容（与 DataManager.loadAllData 中的逻辑一致）
            List<?> bag = data.getList("death-inventory");
            ItemStack[] inv = new ItemStack[0];
            if (bag != null) {
                inv = new ItemStack[bag.size()];
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
            }

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

            // 拿到玩家数据 inv
            // ===== 第十步：过滤 null 和空气 =====
            List<ItemStack> items = Arrays.stream(inv)        // ← 直接用 inv，不再是 Map
                    .filter(s -> s != null && s.getType() != Material.AIR)
                    .toList();

            if (items.isEmpty()) {
                player.sendMessage(msg.formatMsg("messages.gui.no-death-inventory",
                        "死亡背包为空", null, player));
                return;
            }

            // ===== 第十一步：按 27 个一切，造潜影盒 =====
            int boxCount = (items.size() + 26) / 27;   // 向上取整：1~27 个→1 盒，28~54→2 盒

            for (int b = 0; b < boxCount; b++) {
                ItemStack box = new ItemStack(Material.SHULKER_BOX);
                BlockStateMeta bsm = (BlockStateMeta) box.getItemMeta();
                ShulkerBox shulker = (ShulkerBox) bsm.getBlockState();

                int start = b * 27;
                int end = Math.min(start + 27, items.size());

                for (int i = start; i < end; i++) {
                    // i - start 是盒子内部的槽位（0~26），items.get(i) 是全局第 i 件物品
                    shulker.getInventory().setItem(i - start, items.get(i));
                }

                // —— 名字（Adventure Component，不再用弃用的 setDisplayName）——
                ph.put("player", player.getName());
                ph.put("target", target.getName());
                ph.put("page", String.valueOf((b+1)));
                ph.put("maxPage", String.valueOf(boxCount));
                ph.put("items", String.valueOf(items.size()));
                bsm.displayName(msg.formatMsg("messages.gui.get-container-name","{target} 的遗物 {page}/{maxPage} - {death_year}年{death_month}月{death_day}日{death_hour}时{death_minute}分{death_second}秒{death_millis}毫秒",ph,null,false));

                // —— lore ——
                List<String> loreTemplates = plugin.getConfig()
                        .getStringList("messages.gui.get-container-lore");
                List<Component> loreComp = new ArrayList<>();
                for (String line : loreTemplates) {
                    loreComp.add(msg.formatMsg(null, line, ph, null, false));
                }
                bsm.lore(loreComp);

                bsm.setBlockState(shulker);   // ⚠️ 写回内部容器
                box.setItemMeta(bsm);         // ⚠️ 写回 meta

                player.getInventory().addItem(box);
                player.sendMessage(msg.formatMsg("messages.gui.get-success","成功拿到{target}在{death_year}年{death_month}月{death_day}日{death_hour}时{death_minute}分{death_second}秒{death_millis}毫秒时死亡的背包",ph,player));
            }
        }
    }

    // ==================== 子指令：reload ====================

    /**
     * 处理 {@code /death reload}：热重载插件配置，需 {@code Memento.reload} 权限（默认仅 op）。
     *
     * <p>重载流程由主类 {@code reloadPlugin()} 完成（保存当前数据 → 清空内存 → 重读 config → 重灌数据），
     * 本方法只负责权限闸与计时回显。
     *
     * @param player 调用指令的玩家
     */
    private void handleReload(Player player) {
        // 权限闸：无 reload 权限直接拒绝
        if (!player.hasPermission("Memento.reload")) {
            player.sendMessage(msg.formatMsg("messages.normal.no-permission",
                    "你没有权限执行此操作", null, player));
            return;
        }

        // 计时回显：用 wall-clock 差值衡量重载耗时，帮服主判断是否存在性能瓶颈
        long start = System.currentTimeMillis();
        plugin.reloadPlugin();
        long cost = System.currentTimeMillis() - start;

        Map<String, String> ph = new HashMap<>();
        ph.put("cost", String.valueOf(cost));
        player.sendMessage(msg.formatMsg("messages.debug.success-reload",
                "插件重载完成! 耗时 {cost}ms", ph, player));
    }

    // ==================== 校验工具 ====================

    /**
     * 校验自定义消息的纯文本长度是否越界（剔除占位符后计长）。
     *
     * <p>意图：防刷屏，同时不让占位符字面量（如 {@code {player}}）占用长度配额。
     * 例：{@code max-length=20} 时，{@code "{player} 被 {killer} 杀了"} 的纯文本为 {@code " 被  杀了"}（6 字符），合法。
     *
     * <p><b>返回值方向</b>：方法名是 "invalid"，故 {@code true} 表示"不合法"，与调用处
     * {@code if (isLengthInvalid(...)) return;} 的"非法就退出"语义一致。
     *
     * @param player 调用者，长度越界时向其发送限制说明
     * @param text   待校验的原始消息文本（含占位符）
     * @return {@code true} = 长度不合法（已发提示，调用方应返回）；{@code false} = 合法
     */
    private boolean isLengthInvalid(Player player, String text) {
        // 长度上下限取自 config，带缺省值兜底
        int minLength = plugin.getConfig().getInt("settings.min-length", 0);
        int maxLength = plugin.getConfig().getInt("settings.max-length", 20);

        // 把所有有效占位符替换为空串，得到"玩家真正贡献的字符"，再据此计长
        String pureText = text
                .replace("{player}", "")
                .replace("{killer}", "")
                .replace("{weapon}", "")
                .replace("{cause}", "");

        // 越界（过短或过长）→ 回显允许区间与当前长度，便于玩家自行修正
        if (pureText.length() < minLength || pureText.length() > maxLength) {
            Map<String, String> ph = new HashMap<>();
            ph.put("min", String.valueOf(minLength));
            ph.put("max", String.valueOf(maxLength));
            ph.put("current", String.valueOf(pureText.length()));
            player.sendMessage(msg.formatMsg("messages.normal.length-invalid",
                    "仅支持 {min}~{max} 个字符（当前 {current} 个，不包括有效{}占位符）", ph, player));
            return true;
        }
        return false;
    }

    /**
     * 校验自定义消息是否含全部必需占位符。
     *
     * <p>行为由 config 三个开关共同决定：
     * <ul>
     *   <li>{@code settings.strict = false} → 完全不校验，恒视为通过；</li>
     *   <li>{@code settings.strict-prompt = true} → 每缺一个就发一条提示，告知缺了哪个；</li>
     *   <li>{@code settings.strict-require-{name} = true} → 该占位符缺失即拦截（false 则仅提示不拦）。</li>
     * </ul>
     *
     * <p><b>返回值方向</b>：方法名是 "hasInvalid..."，故 {@code true} 表示"存在缺失/校验失败"，
     * 与调用处 {@code if (hasInvalidPlaceholders(...)) return;} 的"有问题就退出"语义一致。
     *
     * @param player               调用者，缺失时向其发送提示
     * @param text                 待校验的消息文本
     * @param requiredPlaceholders 必需占位符名列表（不含花括号，如 {@code "player"}、{@code "killer"}）
     * @return {@code true} = 校验失败/有缺失（已发提示，调用方应返回）；{@code false} = 校验通过
     */
    private boolean hasInvalidPlaceholders(Player player, String text, List<String> requiredPlaceholders) {
        // 严格模式总开关：关掉则下面的 require 判定全部失效，等价于不校验
        boolean strict = plugin.getConfig().getBoolean("settings.strict", true);
        // 是否在缺失时逐条发提示
        boolean prompt = plugin.getConfig().getBoolean("settings.strict-prompt", true);
        // 通过性标记：初始为通过，遇到"必需且严格"的缺失才翻为 false
        boolean valid = true;

        for (String name : requiredPlaceholders) {
            String placeholder = "{" + name + "}";
            if (!text.contains(placeholder)) {
                // 开了提示就逐个点名缺了哪个占位符，帮玩家定位
                if (prompt) {
                    String promptKey = "messages.normal.missing-placeholder-" + name;
                    String promptDef = "缺少占位符 " + placeholder + "！";
                    player.sendMessage(msg.formatMsg(promptKey, promptDef, null, player));
                }
                // 该占位符被配为"必须"且严格模式开启 → 整体判为不通过
                boolean require = plugin.getConfig().getBoolean("settings.strict-require-" + name, true);
                if (require && strict) {
                    valid = false;
                }
            }
        }

        // 只要有任何必需占位符缺失，补一条总结性拦截提示，避免玩家只看到零散点名而不知已被拦
        if (!valid) {
            player.sendMessage(msg.formatMsg("messages.normal.validation-failed",
                    "请检查占位符设置后重试！", null, player));
        }
        // 返回"是否不通过"：valid 为通过，故取反
        return !valid;
    }
}