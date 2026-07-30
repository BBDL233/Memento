#Memento

## 简介

一款基于 Paper API 开发的 Minecraft 服务端插件。

这是我写的第一个插件，从最初所有逻辑堆在一个类里，到逐步拆分重构、跟着 AI 辅助学习优化，最终形成了现在的结构

## ✨ 核心功能

- 【逐条填写插件功能，按需增删】
- ✅ 保存玩家的死亡位置和背包，并在复活后告诉玩家
- ✅ 查看和获取玩家的历史死亡背包和死亡位置
- ✅ 支持 MiniMessage 现代文本格式（渐变、悬浮、点击事件）
- ✅ 支持 PlaceholderAPI 占位符拓展
- ✅ Vault 花费经济自定义死亡广播消息 （可选付费）
- ✅ 配置文件热重载，无需重启服务器
- ✅ 全部语言消息，主、子指令可自定义设置

## ⚙️ 运行环境与兼容性

| 项目 | 要求 |
| --- | --- |
| Java | Java 25+ |
| 服务端 | Paper（推荐指定版本：paper-26.1.2-74） |
| 不兼容 | 不知道 |
| API | Paper API |

## 📦 依赖插件

无

### 可选依赖

1. PlaceholderAPI：启用占位符变量支持
2. Vault：经济相关功能启用（无经济功能可删除本条）

## 🚀 安装教程

1. 前往仓库 Releases 页面，下载最新版本 `Memento.jar`
2. 将 jar 文件放入服务器 `plugins/` 目录
3. 启动服务器，插件自动生成配置文件夹
4. 根据需求修改配置文件
5. 重载插件 / 重启服务器使配置生效

## 📜 指令列表

主指令：`/death`
可在配置文件自定义指令别名

| 指令 | 功能说明 | 执行者 |
| --- | --- | --- |
| `/death help` | 查看插件帮助菜单 | 玩家/控制台 |
| `/death reload` | 重载全部插件配置 | 管理员/控制台 |

## 🔐 权限节点

```
  Memento.toggle.others:
    description: 允许开关别人的复活提醒
    default: op
  Memento.view:
    description: 允许查看所有人的死亡消息（无视禁用）
    default: op
  Memento.reload:
    description: 允许插件重载
    default: op
  Memento.viewall:
    description: 能看到所有人的死亡广播
    default: op
  Memento.free-custom:
    description: 免扣费
    default: op
  Memento.get:
    description: 管理员获取玩家背包
    default: op
  Memento.keepInv:
    description: 死亡不掉落背包
    default: op
  Memento.keepXp:
    description: 死亡不掉落经验
    default: op
```

> 
> 默认权限说明：OP 默认拥有全部管理员权限；普通玩家仅拥有基础权限。

## 🗂️ 配置结构说明

```
plugins/插件文件夹名/
├─ config.yml      # 主配置文件
└─ menus/          # GUI菜单文件
└─ playerdata/<player uuid>/  玩家数据位置
                └─ config.yl  玩家设置
                └─ xxxx-x-x-x 死亡时的文件
```

规则：

1. 请勿修改配置内所有 Key（键名），仅修改展示文本内容
2. 文本支持 MiniMessage 格式，禁止老式 & 颜色代码与 MiniMessage 混用
3. 所有 `{变量}`、`%占位符%` 不允许汉化修改


## 📄 开源协议

本项目使用 MIT License。
你可以自由使用、修改、分发源码，保留原始开源声明。

