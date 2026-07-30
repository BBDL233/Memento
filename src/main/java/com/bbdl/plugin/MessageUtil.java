package com.bbdl.plugin;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * 消息格式化工具类：统一管理插件中所有消息的渲染流程。
 *
 * <p>核心职责：
 * <ul>
 *   <li>从 config.yml 中读取消息模板（支持热重载后自动生效）</li>
 *   <li>替换自定义占位符（如 {player}、{killer}、{item_0_name}）</li>
 *   <li>替换 PlaceholderAPI 占位符（如 %player_name%、%server_online%）</li>
 *   <li>通过 MiniMessage 解析颜色/格式标签（如 <red>、<bold>、<gradient:...>）</li>
 *   <li>可选地添加消息前缀（如 "[死亡播报] "）</li>
 *   <li>提供调试日志输出（仅在 debug 模式开启时生效）</li>
 * </ul>
 *
 * <p>消息渲染管线（Pipeline）：
 * <pre>
 * config 模板 → 自定义占位符替换 → 前缀拼接 → PAPI 替换 → MiniMessage 解析 → Component
 * </pre>
 *
 * <p>设计意图：
 * <ul>
 *   <li>所有消息输出都经过本类，确保格式一致性（颜色、前缀、占位符行为统一）。</li>
 *   <li>服主只需修改 config.yml 中的模板即可自定义所有消息，无需改代码。</li>
 *   <li>支持 MiniMessage 语法，比传统的 § 颜色码更强大（渐变、悬停、点击等）。</li>
 *   <li>PlaceholderAPI 集成让消息中可以引用其他插件的数据（如等级、金币等）。</li>
 * </ul>
 */
public class MessageUtil {

    /** 主插件实例，用于访问配置（消息模板）和调试开关 */
    private final Memento plugin;

    /**
     * 构造时引用主类实例。
     * 本类是全局单例（由主类创建一次），所有其他类通过 plugin.msg 引用。
     */
    public MessageUtil(Memento plugin) {
        this.plugin = plugin;
    }

    // ==================== 调试日志输出 ====================

    /**
     * 输出调试日志（无占位符版本）。
     *
     * <p>仅当 plugin.debugMode = true 时才会实际输出。
     * 意图：开发/排查阶段使用，生产环境关闭后零开销（直接 return）。
     *
     * <p>输出格式：[Debug] + 消息内容
     * 例如：[Debug] 插件已启用！
     *
     * @param key        config.yml 中的消息路径（如 "messages.debug.enabled"）
     * @param defaultMsg 配置中找不到该路径时的回退消息
     */
    public void debug(String key, String defaultMsg) {
        // 调试模式未开启 → 直接返回，不做任何处理（零开销）
        if (!plugin.debugMode) {
            return;
        }
        // 拼接：[Debug] 前缀 + 实际消息内容
        // 前缀从 config 读取（可自定义），usePrefix=false 避免重复添加消息前缀
        Component msg = formatMsg("messages.debug.prefix", "[Debug] ", null, null)
                .append(formatMsg(key, defaultMsg, null, null, false));
        // 输出到服务器控制台（logger.info 会带上插件名前缀）
        plugin.logger.info(msg);
    }

    /**
     * 输出调试日志（带占位符版本）。
     *
     * <p>与无参版本的区别：支持在消息模板中使用 {key} 占位符。
     * 例如：defaultMsg = "加载菜单文件: {file}"，ph = {"file": "page1.yml"}
     * → 输出：[Debug] 加载菜单文件: page1.yml
     *
     * @param key        config.yml 中的消息路径
     * @param defaultMsg 回退消息模板
     * @param ph         占位符映射（key = 占位符名，value = 替换值）
     */
    public void debug(String key, String defaultMsg, Map<String, ?> ph) {
        // 调试模式未开启 → 直接返回
        if (!plugin.debugMode) return;

        // 拼接：[Debug] 前缀 + 替换占位符后的消息内容
        Component msg = formatMsg("messages.debug.prefix", "[Debug] ", null, null, false)
                .append(formatMsg(key, defaultMsg, ph, null, false));

        plugin.logger.info(msg);
    }

    // ==================== 消息格式化（核心方法） ====================

    /**
     * 格式化消息（简化版，默认添加前缀）。
     *
     * <p>这是最常用的重载版本，内部调用完整版并设置 usePrefix=true。
     * 适用于：发送给玩家的所有常规消息（死亡广播、指令反馈等）。
     *
     * @param key        config.yml 中的消息路径（如 "messages.normal.death-by-player"）
     *                   传 null 表示不从 config 读取，直接使用 defaultMsg 作为模板
     * @param defaultMsg 回退消息模板（config 中找不到 key 时使用）
     * @param ph         占位符映射（可为 null 表示无占位符）
     * @param player     关联的玩家（用于 PlaceholderAPI 替换，可为 null）
     * @return 格式化后的 Component（可直接用于 sendMessage）
     */
    public Component formatMsg(String key, String defaultMsg, Map<String, ?> ph, Player player) {
        // 委托给完整版，usePrefix = true（添加消息前缀）
        return formatMsg(key, defaultMsg, ph, player, true);
    }

    /**
     * 格式化消息（完整版，核心渲染管线）。
     *
     * <p>渲染管线步骤：
     * <ol>
     *   <li>获取模板：从 config 读取（key != null）或直接使用 defaultMsg（key == null）</li>
     *   <li>自定义占位符替换：将 {xxx} 替换为 ph 中对应的值</li>
     *   <li>前缀拼接：如果 usePrefix=true，在消息前面加上配置的前缀</li>
     *   <li>PlaceholderAPI 替换：将 %xxx% 替换为 PAPI 提供的值</li>
     *   <li>MiniMessage 解析：将颜色/格式标签解析为 Component 对象</li>
     * </ol>
     *
     * <p>占位符值的特殊处理：
     * <ul>
     *   <li>如果值是 Component（如物品显示名）→ 先序列化为 MiniMessage 字符串再替换，
     *       这样物品名中的颜色/格式不会丢失</li>
     *   <li>如果值是普通对象 → 调用 toString() 转为字符串</li>
     *   <li>如果值是 null → 替换为空字符串（避免显示 "null"）</li>
     * </ul>
     *
     * @param key        config.yml 中的消息路径（null = 直接用 defaultMsg）
     * @param defaultMsg 回退消息模板
     * @param ph         占位符映射（null = 无占位符需要替换）
     * @param player     关联的玩家（null = 不做 PAPI 替换）
     * @param usePrefix  是否添加消息前缀
     *                   true  → 适用于发给玩家的消息（如 "[死亡播报] Steve 死了"）
     *                   false → 适用于标题、调试日志等不需要前缀的场景
     * @return 最终渲染好的 Component
     */
    public Component formatMsg(String key, String defaultMsg, Map<String, ?> ph, Player player, boolean usePrefix) {
        // ===== 步骤 1：获取消息模板 =====
        // key != null → 尝试从 config.yml 读取（支持服主自定义）
        // key == null → 直接使用 defaultMsg（适用于动态模板，如 GUI 标题、物品名）
        // getString 的第二个参数是回退值：config 中没有该路径时使用 defaultMsg
        String msg = (key != null) ? plugin.getConfig().getString(key, defaultMsg) : defaultMsg;

        // ===== 步骤 2：自定义占位符替换 =====
        // 将模板中的 {xxx} 替换为 ph 映射中对应的值
        // 例如："{player} 被 {killer} 杀死" + {player: "Steve", killer: "Notch"}
        // → "Steve 被 Notch 杀死"
        if (ph != null) {
            for (Map.Entry<String, ?> entry : ph.entrySet()) {
                Object val = entry.getValue();
                String str;
                // 特殊处理：如果占位符值是 Component（如物品的自定义显示名）
                // → 序列化为 MiniMessage 字符串（保留颜色/格式标签）
                // 例如：Component("§c火焰剑") → "<red>火焰剑"
                // 这样替换进模板后，后续 MiniMessage 解析时颜色不会丢失
                if (val instanceof Component) {
                    str = MiniMessage.miniMessage().serialize((Component) val);
                } else {
                    // 普通值 → toString()；null → 空字符串（避免显示 "null"）
                    str = val != null ? val.toString() : "";
                }
                // 执行字符串替换：{key} → str
                msg = msg.replace("{" + entry.getKey() + "}", str);
            }
        }

        // ===== 步骤 3：拼接消息前缀 =====
        // 前缀从 config 读取（如 "[死亡播报] "），服主可自定义或设为空字符串
        // usePrefix=false 的场景：GUI 标题、调试日志、物品名称等不需要前缀
        if (usePrefix) {
            msg = plugin.getConfig().getString("messages.normal.prefix", "[死亡播报] ") + msg;
        }

        // ===== 步骤 4：PlaceholderAPI 替换 =====
        // 条件：有关联玩家 且 服务器安装了 PlaceholderAPI 插件
        // PAPI 占位符格式：%plugin_placeholder%（如 %player_name%、%vault_eco_balance%）
        // 意图：让消息模板可以引用其他插件的数据，极大扩展灵活性
        if (player != null && plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            msg = PlaceholderAPI.setPlaceholders(player, msg);
        }

        // 解析原版颜色字符
        msg = convertAmpersandToMiniMessage(msg);

        // ===== 步骤 5：MiniMessage 解析 =====
        // 将包含 MiniMessage 标签的字符串解析为 Component 对象
        // 支持的标签示例：
        //   <red>、<bold>、<italic>       → 基本颜色/格式
        //   <gradient:red:blue>           → 渐变色
        //   <hover:show_text:"提示">      → 悬停提示
        //   <click:run_command:"/ld">     → 点击执行指令
        // 返回的 Component 可直接用于 player.sendMessage()、Inventory 标题等
        return plugin.miniMessage.deserialize(msg);
    }

    /**
     * 将旧版 & 颜色/格式代码转换为等价的 MiniMessage 标签。
     *
     * <p>转换规则：
     * <ul>
     *   <li>&0~&9, &a~&f → 对应颜色标签（如 &c → <red>）</li>
     *   <li>&l → <bold>, &o → <italic>, &n → <underlined>, &m → <strikethrough></li>
     *   <li>&k → <obfuscated>, &r → <reset></li>
     *   <li>&#RRGGBB → <#RRGGBB>（十六进制颜色）</li>
     * </ul>
     *
     * <p>设计意图：
     * 在字符串层面完成转换，不经过 LegacyComponentSerializer → Component → MiniMessage.serialize() 的路径，
     * 因为那条路径会把原始 MiniMessage 标签（<red> 等）当作纯文本转义掉，导致格式全部丢失。
     *
     * <p>注意：本方法不处理 § 代码（服务端内部格式），只处理 & 代码（用户输入格式）。
     */
    private String convertAmpersandToMiniMessage(String input) {
        if (input == null || input.isEmpty()) return input;

        StringBuilder sb = new StringBuilder(input.length() + 32);
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            if (c == '&' && i + 1 < input.length()) {
                char next = Character.toLowerCase(input.charAt(i + 1));

                // &#RRGGBB → <#RRGGBB>
                if (next == '#' && i + 7 < input.length()) {
                    String hex = input.substring(i + 2, i + 8);
                    if (hex.matches("[0-9a-fA-F]{6}")) {
                        sb.append("<#").append(hex).append('>');
                        i += 8;
                        continue;
                    }
                }

                // &0~&f 颜色代码
                String colorTag = switch (next) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    // 格式代码
                    case 'l' -> "<bold>";
                    case 'o' -> "<italic>";
                    case 'n' -> "<underlined>";
                    case 'm' -> "<strikethrough>";
                    case 'k' -> "<obfuscated>";
                    case 'r' -> "<reset>";
                    default -> null;
                };

                if (colorTag != null) {
                    sb.append(colorTag);
                    i += 2;
                    continue;
                }
            }

            // 普通字符，原样保留
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}