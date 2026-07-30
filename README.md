Memento

paper 26.1+
java 25

死亡保护与遗物管理 —— 自定义死亡消息、死亡广播、死亡背包 GUI、遗物提取

简介:
  这是我写的第一个插件，从最初所有逻辑堆在一个类里，到逐步拆分重构、跟着 AI 辅助学习优化，最终形成了现在的结构

✨ 功能

🪦 自定义死亡消息：支持 MiniMessage 标签、十六进制颜色、旧版 & 代码，可分别设置 player 类型（被击杀）和 cause 类型（环境死亡）

📢 死亡广播：全局开关 + 个人开关，支持按玩家控制可见性

📍 死亡位置查询：查询自己或他人的上次死亡坐标

🔔 复活提醒：复活时自动提示上次死亡位置

🎒 死亡背包 GUI：多页菜单（default.yml / default2.yml），支持翻页浏览历史死亡背包

📦 遗物提取：通过 /death get 将死亡背包导出为潜影盒，附带死亡时间、位置、物品数等 Lore

💰 自定义消息收费（可选）：集成 Vault + 经济插件，设置自定义消息时扣费

🔌 PlaceholderAPI 支持：所有玩家消息均支持 PAPI 占位符

📦 安装

前往 Releases 下载最新的 .jar
将 .jar 放入服务器的 plugins/ 文件夹
启动 / 重启服务器
编辑 plugins/Memento/config.yml 按需调整
游戏内执行 /death reload 热重载配置（Paper 支持主指令热重载，Spigot 需重启）

可选依赖：
插件   用途   必须？
Vault + 经济插件   自定义消息收费功能   否（不开收费则无需）

PlaceholderAPI   消息中使用 PAPI 占位符   否

🎮 指令

主指令名可在 config.yml 中自定义（默认为 death），以下以 /death 为例：
指令   说明
/death   查看自己的上次死亡位置

/death <玩家名>   查看指定玩家的上次死亡位置

/death help   显示帮助

/death toggle [玩家] [true false]   切换复活提醒（可操作他人）

/death broadcast    切换全局死亡广播

/death broadcast <玩家名>   查询某玩家的广播状态

/death custom  <消息>   设置自定义死亡消息

/death inv <玩家> [历史] [页码]   查看死亡背包 GUI

/death get   提取死亡背包为潜影盒

/death reload   重载配置

子指令别名可在 config.yml 的 commands.sub 下自定义，支持多个别名，不区分大小写。

🔑 权限
权限   说明  默认持有人
Memento.view   查看他人死亡记录（对方关闭广播时需要）  op

Memento.viewall   无视广播开关查看所有人死亡记录  op

Memento.toggle.others  允许开关别人的复活提醒  op

Memento.reload 允许插件重载  op

Memento.free-custom 免扣费  op

Memento.get  获取玩家背包  op

Memento.keepInv  死亡不掉落背包  op

Memento.keepXp  死亡不掉落经验  op

⚙️ 配置概览

配置文件位于 plugins/Memento/config.yml，主要分区：
区块   说明
menus   GUI 菜单文件列表（menus/ 目录下）

commands   主指令名 + 子指令别名

settings   消息长度限制、严格模式、预览示例值、历史记录上限、收费设置

messages   所有消息模板（支持 MiniMessage / &代码 / 十六进制 / PAPI）

菜单布局文件位于 plugins/Memento/menus/，默认包含 default.yml 和 default2.yml。

环境要求：JDK 17+、Maven 3.8+

📁 项目结构

    Memento/
    ├── src/main/java/com/bbdl/plugin/
    │   ├── Memento.java
    │   ├── CommandHandler.java
    │   ├── DeathListener.java
    │   ├── CustomDeathMsg.java
    │   ├── GuiRenderer.java
    │   ├── GuiListener.java
    │   ├── DeathGuiHolder.java
    │   ├── DataManager.java
    │   └── MessageUtil.java
    ├── src/main/resources/
    │   ├── plugin.yml
    │   ├── config.yml
    │   └── menus/
    │       ├── default.yml
    │       └── default2.yml
    └── pom.xml
