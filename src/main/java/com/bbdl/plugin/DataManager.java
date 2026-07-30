package com.bbdl.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * 数据持久化管理器：负责所有玩家数据的读盘（加载）和写盘（保存）。
 *
 * <p>存储结构设计：
 * <pre>
 * plugins/Memento/playerdata/
 * ├── {uuid}/
 * │   ├── config.yml              ← 玩家设置（复活提醒开关、广播开关、自定义消息）
 * │   ├── 2026-07-29_12-30-00-123.yml  ← 第 1 次死亡记录
 * │   ├── 2026-07-29_14-45-22-456.yml  ← 第 2 次死亡记录
 * │   └── ...
 * └── {uuid}/
 *     └── ...
 * </pre>
 *
 * <p>设计意图：
 * <ul>
 *   <li>每次死亡生成独立文件（以时间戳命名），天然支持历史回溯，无需额外索引。</li>
 *   <li>config.yml 与死亡记录分离，设置变更不会触发大量死亡文件重写。</li>
 *   <li>所有 IO 操作都有 try-catch 保护，单个玩家数据损坏不影响其他玩家。</li>
 * </ul>
 */
public class DataManager {

    /** 主插件实例，用于访问共享数据（Map）和服务器对象 */
    private final Memento plugin;
    /** 消息格式化工具（复用主类实例，不重复创建） */
    private final MessageUtil msg;

    /**
     * 构造时引用主类已初始化好的共享实例。
     * 注意：必须在 Memento.onEnable() 中 msg 赋值之后再 new 本类。
     */
    public DataManager(Memento plugin) {
        this.plugin = plugin;
        this.msg = plugin.msg;
    }

    // ==================== 数据加载（读盘） ====================

    /**
     * 启动时调用：遍历 playerdata 目录下所有玩家文件夹，将数据加载到内存。
     *
     * <p>加载内容：
     * <ol>
     *   <li>config.yml → 复活提醒开关、广播开关、自定义死亡消息</li>
     *   <li>最新的死亡记录文件 → 死亡位置（写入 deathLocations）</li>
     *   <li>最新死亡记录中的背包 → 死亡背包（写入 deathInv）</li>
     * </ol>
     *
     * <p>容错策略：
     * <ul>
     *   <li>文件夹名不是合法 UUID → 跳过并输出警告日志</li>
     *   <li>世界未加载（如世界被删除）→ 跳过死亡位置，不报错</li>
     *   <li>任何单个玩家数据异常 → 不影响其他玩家的加载</li>
     * </ul>
     */
    public void loadAllData() {
        // 获取 playerdata 目录下的所有文件/文件夹
        File[] files = plugin.dataFolder.listFiles();
        // 目录不存在或为空时 listFiles() 返回 null，直接返回
        if (files == null) {
            return;
        }

        for (File file : files) {
            // 只处理文件夹（每个文件夹代表一个玩家），跳过散落的文件
            if (!file.isDirectory()) {
                continue;
            }

            // 文件夹名就是玩家的 UUID（如 "a1b2c3d4-e5f6-..."）
            String fileName = file.getName();
            UUID uuid;
            try {
                // 尝试将文件夹名解析为 UUID
                uuid = UUID.fromString(fileName);
            } catch (IllegalArgumentException e) {
                // 不是合法 UUID（可能是手动创建的测试文件夹等）→ 跳过并记录警告
                Map<String, String> ph = new HashMap<>();
                ph.put("filename", file.getName());
                plugin.logger.warn(msg.formatMsg("messages.debug.skip-file", "跳过无效文件 {filename}", ph, null));
                continue;
            }

            // ===== 第一步：加载 config.yml（玩家设置） =====
            File configFile = new File(file, "config.yml");
            if (configFile.exists()) {
                YamlConfiguration data = YamlConfiguration.loadConfiguration(configFile);

                // 复活提醒开关（默认 true = 开启）
                plugin.respawnNotify.put(uuid, data.getBoolean("respawn-notify", true));
                // 全局死亡广播开关（默认 true = 开启）
                plugin.deathMessages.put(uuid, data.getBoolean("death-messages", true));

                // 自定义死亡消息（可能为 null，表示玩家从未设置过）
                CustomDeathMsg customMsg = new CustomDeathMsg();
                customMsg.byPlayer = data.getString("custom-by-player");
                customMsg.byCause = data.getString("custom-by-cause");
                // 只有至少有一个自定义消息时才放入内存（避免无意义的空对象占用）
                if (customMsg.byPlayer != null || customMsg.byCause != null) {
                    plugin.customDeathMsgMap.put(uuid, customMsg);
                }
            }

            // ===== 第二步：找到最新的死亡记录文件 =====
            File[] allFiles = file.listFiles();
            if (allFiles == null) {
                continue;  // 文件夹为空或读取失败，跳过
            }

            // 过滤出死亡记录文件（排除 config.yml 和非 .yml 文件）
            List<File> filelist = new ArrayList<>();
            for (File f : allFiles) {
                // 排除 config.yml（那是设置文件，不是死亡记录）
                if (f.getName().endsWith("config.yml")) {
                    continue;
                }
                // 只保留 .yml 文件
                if (!f.getName().endsWith(".yml")) {
                    continue;
                }
                filelist.add(f);
            }

            // 没有任何死亡记录文件 → 跳过后续处理
            if (filelist.isEmpty()) {
                continue;
            }

            // 遍历所有死亡记录文件，找到时间戳最大的那个（即最近一次死亡）
            File lastFile = null;
            long lastTime = -1;  // 初始值 -1，确保任何有效时间戳都能覆盖
            for (File f : filelist) {
                YamlConfiguration data = YamlConfiguration.loadConfiguration(f);
                // 每个死亡记录文件内部都存有 "time" 字段（毫秒时间戳）
                long time = data.getLong("time", 0);
                if (time > lastTime) {
                    lastTime = time;
                    lastFile = f;
                }
            }

            // 理论上不会为 null（filelist 非空时一定有最大值），防御性检查
            if (lastFile == null) {
                continue;
            }

            // ===== 第三步：从最新死亡记录中提取位置和背包 =====
            YamlConfiguration data = YamlConfiguration.loadConfiguration(lastFile);

            // 提取死亡位置
            String worldName = data.getString("world");
            if (worldName != null) {
                // 通过世界名获取 World 对象（如果世界被删除/未加载，返回 null）
                World world = plugin.getServer().getWorld(worldName);
                if (world != null) {
                    int x = data.getInt("x");
                    int y = data.getInt("y");
                    int z = data.getInt("z");
                    // 写入内存缓存，供 /ld 指令和复活提醒使用
                    plugin.deathLocations.put(uuid, new Location(world, x, y, z));
                }
                // world == null 时静默跳过：世界被删除后死亡位置自然失效，无需报错
            }

            // 提取死亡时的背包内容
            List<?> bag = data.getList("death-inventory");
            if (bag != null) {
                ItemStack[] inv = new ItemStack[bag.size()];
                for (int i = 0; i < bag.size(); i++) {
                    Object o = bag.get(i);
                    // 每个非空物品在 YAML 中被序列化为 Map<String, Object>
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) o;
                        // 反序列化为 ItemStack 对象
                        inv[i] = ItemStack.deserialize(map);
                    }
                    // o 为 null 时 inv[i] 保持 null（表示该槽位为空）
                }
                // 写入内存缓存，供 /ld inv 指令查看
                plugin.deathInv.put(uuid, inv);
            }
        }
    }

    // ==================== 数据保存（写盘） ====================

    /**
     * 保存指定玩家的数据到磁盘。
     *
     * @param uuid 玩家 UUID
     * @param mode 保存模式：
     *             <ul>
     *               <li>"config" → 只保存玩家设置（开关、自定义消息）</li>
     *               <li>"death"  → 只保存死亡记录（位置 + 背包），生成新文件</li>
     *               <li>"all"    → 两者都保存</li>
     *             </ul>
     *
     * <p>调用时机：
     * <ul>
     *   <li>mode="config"：玩家执行 toggle / broadcast / custom 指令后立即调用</li>
     *   <li>mode="death"：玩家死亡事件中调用（DeathListener.onPlayerDeath）</li>
     *   <li>mode="all"：插件关闭时（onDisable → saveAll）</li>
     * </ul>
     */
    public void saveData(UUID uuid, String mode) {
        // 确保玩家文件夹存在（首次保存时自动创建）
        File playerFolder = new File(plugin.dataFolder, uuid.toString());
        if (!playerFolder.exists()) {
            // mkdirs 返回 false = 没能创建（权限不足 / 磁盘满 / 路径被同名文件占等）。
            // 注意：因外层已用 exists() 排除"目录已存在"，此处的 false 才可靠地代表"真失败"。
            if (!playerFolder.mkdirs()) {
                // 目录建不出来，下面的 save 必然也写不进去，提前放弃并记日志，
                // 让病根（建目录失败）暴露在日志里，而不是推迟到 save 抛 FileNotFoundException 误导排障
                logSaveError(uuid, new java.io.IOException(
                        "无法创建玩家数据目录: " + playerFolder.getAbsolutePath()));
                return;
            }
        }

        // ===== 保存玩家设置（config.yml） =====
        if (mode.equals("config") || mode.equals("all")) {
            File configFile = new File(playerFolder, "config.yml");
            YamlConfiguration config = new YamlConfiguration();

            // 写入复活提醒开关（默认 true）
            config.set("respawn-notify", plugin.respawnNotify.getOrDefault(uuid, true));
            // 写入全局广播开关（默认 true）
            config.set("death-messages", plugin.deathMessages.getOrDefault(uuid, true));

            // 写入自定义死亡消息（如果玩家设置过的话）
            CustomDeathMsg customMsg = plugin.customDeathMsgMap.get(uuid);
            if (customMsg != null) {
                config.set("custom-by-player", customMsg.byPlayer);
                config.set("custom-by-cause", customMsg.byCause);
            }

            // 写入磁盘（可能因磁盘满、权限不足等原因失败）
            try {
                config.save(configFile);
            } catch (Exception e) {
                logSaveError(uuid, e);
            }
        }

        // ===== 保存死亡记录（每次死亡生成独立文件） =====
        if (mode.equals("death") || mode.equals("all")) {
            Location loc = plugin.deathLocations.get(uuid);
            // 没有死亡位置则不保存（理论上不会发生，因为死亡事件中一定会先 put 再 save）
            if (loc != null) {
                // 用当前时间戳作为文件名，保证唯一性且可按时间排序
                // 格式：2026-07-29_14-45-22-456.yml（精确到毫秒，防止同秒内多次死亡冲突）
                long now = System.currentTimeMillis();
                File deathFile = new File(playerFolder,
                        new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS")
                                .format(new java.util.Date(now)) + ".yml");
                YamlConfiguration deathData = new YamlConfiguration();

                // 写入时间戳（加载时用于判断哪个是最新的死亡记录）
                deathData.set("time", now);
                // 写入死亡位置
                deathData.set("world", loc.getWorld().getName());
                deathData.set("x", loc.getBlockX());
                deathData.set("y", loc.getBlockY());
                deathData.set("z", loc.getBlockZ());

                // 写入死亡时的背包内容
                ItemStack[] inv = plugin.deathInv.get(uuid);
                if (inv != null) {
                    // 将 ItemStack[] 序列化为 List<Map>，YAML 才能正确存储
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (ItemStack item : inv) {
                        // null 槽位存 null（表示该位置为空），非空槽位序列化为 Map
                        list.add(item != null ? item.serialize() : null);
                    }
                    deathData.set("death-inventory", list);
                }

                // 写入磁盘
                try {
                    deathData.save(deathFile);
                } catch (Exception e) {
                    logSaveError(uuid, e);
                }
            }
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 统一的保存失败日志输出。
     * 意图：将错误信息格式化后输出到控制台，方便服主排查问题（如磁盘满、权限不足）。
     */
    private void logSaveError(UUID uuid, Exception e) {
        Map<String, String> ph = new HashMap<>();
        ph.put("uuid", uuid.toString());
        ph.put("error", e.getMessage());
        plugin.logger.warn(msg.formatMsg("messages.debug.save-error", "{uuid} 保存失败: {error}", ph, null));
    }
}