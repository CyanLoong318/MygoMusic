# 🎵 MygoMusic — MC Java 版全服点歌系统

> 在 Minecraft（**Spigot/Paper 1.21.4** 服务端 + **Fabric 1.21.4** 客户端）里直接搜索、点歌、播放音乐与显示歌词的插件 + 客户端 Mod。
> 玩家在客户端内即可搜索三大音源（酷狗 / 网易云 / Bilibili）、点歌、看歌词与播放队列，全服玩家同步收听。

> 🤖 **AI 编写声明：本项目代码由 AI（Claude）编写**，人类仅负责需求与测试。代码可能存在设计局限或未发现的 Bug，请谨慎用于生产环境，欢迎提交 Issue 反馈问题。

## 🙏 鸣谢

本项目的创意与部分设计参考了 **zmusic**（MC Java 版全服音乐 / 点歌）项目，感谢其原作者 **zhenxin**。

在此一并感谢 **Fluorine_Arrow**、**ZMIBPZF**。

## 📄 免责声明

- 本项目仅供学习交流，请遵守各音乐平台的服务条款与相关法律法规，勿用于商业用途。
- 本项目代码由 AI 编写，可能存在 Bug 或安全缺陷，使用风险自负。

## 📜 开源协议 License

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

本项目采用 **GNU General Public License v3.0（GPL-3.0）** 开源协议，全文见 [LICENSE](LICENSE)。

按 GPL-3.0 的规定：你可以自由使用、复制、修改与再分发本项目；基于本项目的修改与衍生作品**必须同样以 GPL-3.0 开源**并附上源码。本项目以「现状」提供，不附带任何担保。

> ⚠️ 本项目依赖的音乐平台（酷狗 / 网易云 / Bilibili）内容与接口均归各平台所有，使用本项目点歌播放请在遵守平台服务条款与当地法律的前提下进行。

## ✨ 功能特性

- 🎵 **三大音源**：酷狗音乐、网易云音乐、Bilibili（支持 BV 号直达、多分P 选择）
- 🎤 **歌词同步显示**：游戏内 Actionbar / HUD 逐句显示，可开关、移动、缩放；酷狗外文歌词自动显示翻译；B站视频可把**人工 CC 字幕**作为歌词
- 👥 **全服同步播放**：点歌后服务器统一调度，全服玩家客户端同时播放同一首歌
- 📋 **播放队列**：点歌排队、自动连播、上一首 / 下一首 / 暂停 / 继续（**全服同步**），队列在游戏内可视化
- ⏸️ **断点续播**：全服同步暂停后按「继续」，同一首从**暂停处精确续播**（进度不因暂停时长跳变、不会静默快进或提前切歌）；暂停期间也仍可正常切上一首 / 下一首
- 🖥️ **游戏内 GUI**：按 `M` 一键打开主界面，内置搜索点歌、播放队列、播放设置
- 🔐 **账号登录**：支持登录平台账号获取 VIP/版权曲目，Cookie 保存到本地文件，重启自动恢复
- 💾 **队列持久化**：关服自动保存队列，开服自动恢复
- 🗄️ **数据库**：SQLite / MySQL 可选，记录播放历史
- 📊 **PlaceholderAPI**：支持 `%mygomusic_*%` 变量扩展
- ☁️ **服务端统一缓存管理**：客户端音频缓存开关与上限由服务端 `config.yml` 统一下发
- 🌐 **B站音频分发**：服务端将 B站视频用 ffmpeg 转码为 MP3 后，通过内置 HTTP 服务分发给玩家客户端

## 📦 下载

在 **[GitHub Releases](https://github.com/CyanLoong318/MygoMusic/releases)** 页面下载（适用 **Minecraft 1.21.4**）：

| 文件 | 用途 |
|------|------|
| `MygoMusic-1.0.1-mc1.21.4.jar` | 服务端插件，放入服务器 `plugins/` |
| `MygoMusic-Client-1.0.1-mc1.21.4.jar` | 客户端 Mod，放入 `mods/` |

## 🚀 快速开始

### 服务端（Spigot/Paper/Leaves）

1. 下载 `MygoMusic-1.0.1-mc1.21.4.jar` 放入服务器 `plugins/` 文件夹
2. 重启服务器，生成 `plugins/MygoMusic/config.yml`
3. 编辑配置（音源开关、端口、ffmpeg 路径等），再次重启
4. 服务端需安装 **ffmpeg**（用于 B站转码，见下文）

### 客户端（Fabric）

1. 安装 [Fabric Loader](https://fabricmc.net/) 与 [Fabric API](https://modrinth.com/mod/fabric-api)
2. 下载 `MygoMusic-Client-1.0.1-mc1.21.4.jar` 放入客户端 `mods/` 文件夹
3. 加入安装了插件的服务器，进入游戏

### 验证

- 服务器控制台出现 `MygoMusic 启动成功!`
- 游戏中按 `M` 打开主界面，搜索并点一首酷狗/网易云歌 → 全服同时播放；点 B站视频 → 服务端转码 MP3 后分发

## 📖 使用说明

### 游戏内快捷键（客户端）

| 按键 | 功能 |
|------|------|
| `M` | 打开主界面 |

> 主界面内用按钮操作：搜索点歌、播放队列、播放设置、暂停/继续、上一首、下一首、歌词开关、音量滑块。更多控制可用聊天命令 `/mm`。
>
> - 搜索结果为**按钮行**，点击即点歌；B站多分P结果点「N分P·点击选」后逐P列出，长列表可翻页。
> - 播放队列界面顶部显示正在播放，可切换「播放队列 / 最近播放」；每首歌带全局序号 `#N`（与 `/mm remove` 一致）。点**等待队列**中的歌 = 移出队列（仅本人所点，管理员可移任意）；点**最近播放**中的歌 = 重新点歌加入队尾。内容多时可翻页。

### 聊天命令

### 聊天命令

主命令 `/mm`（别名 `/music`，二者等价）。

**玩家命令：**

| 命令 | 说明 |
|------|------|
| `/mm play <歌名>` | 搜索并点歌 |
| `/mm play <歌名> -s <音源>` | 指定音源点歌 |
| `/mm play bilibili <BV号>` | B站 BV 号直达（支持 `p2` 指定分P） |
| `/mm playid <平台> <ID>` | 通过歌曲 ID 点歌 |
| `/mm select <序号>` | 选择搜索结果 |
| `/mm searchparts <片段>` | 按片段搜索 |
| `/mm search <歌名>` | 搜索歌曲 |
| `/mm stop` | 停止播放 |
| `/mm pause` | 暂停播放（**全服同步**：所有玩家一起暂停） |
| `/mm continue` | 继续播放（全服暂停中恢复同一首；停止后则从队列继续） |
| `/mm next` | 下一首 |
| `/mm prev` | 上一首 |
| `/mm remove <序号>` | 从等待队列移除歌曲（仅限自己点的，管理员可移任意；序号同 GUI 播放队列的 `#N`） |
| `/mm queue` | 查看队列 |
| `/mm now` | 查看当前播放 |
| `/mm volume <0-100>` | 调整音量 |
| `/mm lyrics` | 开关歌词 |
| `/mm login <平台> <cookie>` | 登录平台账号 |
| `/mm logout <平台>` | 登出平台账号 |

**管理员命令（`mygomusic.admin`）：**

| 命令 | 说明 |
|------|------|
| `/mm admin reload` | 重载配置（并重新下发客户端缓存设置） |
| `/mm admin clearqueue` | 清空队列 |
| `/mm admin skip` | 强制跳过 |
| `/mm admin prev` | 强制上一首 |
| `/mm admin stop` | 强制停止 |
| `/mm admin debug` | 调试模式 |
| `/mm admin accounts` | 查看账号状态 |
| `/mm admin sources` | 测试各音源 |
| `/mm admin cache clear` | 清空服务端缓存 |
| `/mm admin db` | 查看数据库状态 |
| `/mm admin ffmpeg` | 检查 ffmpeg |
| `/mm admin restore` | 恢复队列 |

## ⚙️ 配置

### 服务端 `plugins/MygoMusic/config.yml`

```yaml
# 数据库
database:
  type: sqlite            # sqlite / mysql
  mysql:
    host: localhost
    port: 3306
    database: mygomusic
    username: root
    password: ""
    pool-size: 10

# 音源
sources:
  netease:
    enabled: true
  kugou:
    enabled: true
  bilibili:
    enabled: true

# 队列
queue:
  max-size: 50
  cooldown-seconds: 10
  auto-play: true
  history-size: 100
  save-on-shutdown: true

# 播放
playback:
  default-volume: 80

# 歌词
lyrics:
  enabled: true
  show-translation: auto     # auto/true/false

# 客户端音频缓存（服务端统一下发，客户端不再提供该设置）
client-cache:
  enabled: true              # 是否允许客户端缓存音频
  max-size-mb: 512           # 缓存上限 (MB)

# HTTP 文件服务器（用于向客户端分发 B站转码后的 MP3）
http-server:
  port: 8080
  host: ""                   # 留空自动检测本机公网 IPv6；无公网 IPv4 时外地玩家靠 IPv6 下载

# FFmpeg（B站视频转码）
ffmpeg:
  path: "ffmpeg"
  cache-dir: "plugins/MygoMusic/cache/bilibili"
  cache-max-size: 1024
```

> 💡 **关于 `http-server.host`（无公网 IPv4 玩家的必读项）**
> 服务器没有公网 IPv4、只有公网 IPv6 时，B站歌曲转码后的 MP3 由服务端内置 HTTP 服务提供。默认 `host` 留空会**自动检测本机公网 IPv6** 并拼进下发给客户端的下载地址；也可手动指定公网 IPv6、DDNS 域名或局域网 IPv4。记得在系统防火墙放行对应端口的入站连接。

### 客户端 `config/allmusic-client.json`

```json
{
  "volume": 80,
  "mute": false,
  "lyricsEnabled": true,
  "lyricsPosition": "actionbar",
  "showTranslation": true,
  "lyricsScale": 1.0,
  "hudEnabled": true,
  "hudX": 10,
  "hudY": 10
}
```

## 🔌 权限节点

| 权限 | 说明 | 默认 |
|------|------|------|
| `mygomusic.play` | 允许点歌 | true |
| `mygomusic.control` | 允许控制播放 | true |
| `mygomusic.queue` | 允许查看队列 | true |
| `mygomusic.search` | 允许搜索歌曲 | true |
| `mygomusic.volume` | 允许调整音量 | true |
| `mygomusic.lyrics` | 允许开关歌词 | true |
| `mygomusic.login` | 允许登录平台 | true |
| `mygomusic.nocooldown` | 免点歌冷却 | false |
| `mygomusic.queuelimit` | 队列优先（可多首） | false |
| `mygomusic.admin` | 管理员命令 | op |

## 📈 PlaceholderAPI 变量

| 变量 | 说明 |
|------|------|
| `%mygomusic_now_title%` | 当前歌曲名 |
| `%mygomusic_now_artist%` | 当前歌手 |
| `%mygomusic_now_source%` | 当前歌曲来源 |
| `%mygomusic_is_playing%` | 是否正在播放 |
| `%mygomusic_queue_size%` | 队列中的歌曲数量 |
| `%mygomusic_queue_next%` | 下一首歌名 |
| `%mygomusic_requester%` | 当前歌曲的点歌人 |
| `%mygomusic_duration%` | 当前歌曲总时长 |
| `%mygomusic_position%` | 当前播放进度 |

## 📌 音源说明与已知限制

- **酷狗音乐**：VIP/版权曲目需要"酷狗官网已登录 VIP 账号"的 Cookie（`/mm login kugou <cookie>`）；无有效 Cookie 无法绕过版权。
- **网易云音乐**：普通曲目可直接播放；VIP 曲目需登录。
- **Bilibili**：视频音频通过服务端 **ffmpeg 转码为 MP3** 后分发给玩家（缓存于 `plugins/MygoMusic/cache/bilibili/`）。
  - 歌词取**人工 CC 字幕**（仅当字幕为中文优先的 CC 字幕时）；纯 AI 字幕的视频无歌词属预期。B站字幕接口需要登录 Cookie（含 `SESSDATA`）。
  - 支持 BV 号直达与多分P 选择，音频/字幕/时长按分P 独立缓存。
- 登录 Cookie 保存在服务端 `plugins/MygoMusic/cookies.yml`，**重启自动恢复**。

## 🛠️ 开发

### 构建（需 JDK 21 + Gradle 8.11+）

```bash
# 构建全部两个模块
gradle build

# 仅服务端插件 → plugin/build/libs/MygoMusic-1.0.1.jar
gradle :plugin:build

# 仅客户端 Mod → client/build/libs/MygoMusic-Client-1.0.1.jar
gradle :client:build
```

### 项目结构

```
mygomusic/
├── plugin/                       # 服务端插件 (Spigot/Paper)
│   └── src/main/java/com/allmusic/
│       ├── AllMusicPlugin.java   # 插件主类
│       ├── command/              # 命令
│       ├── source/               # 音源 (酷狗/网易云/B站)
│       ├── queue/                # 队列与调度
│       ├── network/              # 插件通道 + HTTP 文件服务器
│       ├── database/             # SQLite/MySQL
│       ├── model/                # 数据模型
│       ├── placeholder/          # PlaceholderAPI 扩展
│       ├── config/               # 配置
│       └── util/                 # 工具 (ffmpeg/http/crypto)
├── client/                       # 客户端 Mod (Fabric)
│   └── src/main/java/com/allmusic/client/
│       ├── AllMusicClient.java   # Mod 主类
│       ├── audio/                # 音频下载/缓存/播放
│       ├── lyrics/               # 歌词渲染
│       ├── network/              # 插件通道
│       ├── gui/                  # 搜索/队列/设置 GUI
│       ├── hud/                  # HUD
│       └── config/               # 配置
├── settings.gradle.kts
├── build.gradle.kts
└── gradle.properties             # 版本集中定义 (MC 1.21.4 等)
```

### 依赖 / 环境

| 环境 | 要求 |
|------|------|
| 服务端 | Spigot / Paper / Leaves **1.21.4**、Java 21、ffmpeg（B站转码）、PlaceholderAPI（可选） |
| 客户端 | Fabric Loader 0.16+、Fabric API、Minecraft **1.21.4**、Java 21 |

## 📜 更新日志

### v1.0.1
- 🐛 修复「暂停 → 继续」无法续播的问题：改为**全服同步暂停**（`/mm pause` / 主界面「暂停/继续」按钮），继续后从**断点精确续播**，进度不因暂停时长跳变，暂停期间不会静默快进或提前切歌
- 🐛 修复暂停状态下无法切上一首 / 下一首的问题（客户端播放线程代际失效保护）
- 🖥️ 搜索界面结果改为**按钮行**展示（点击即点歌，支持 B站多分P 选择与长列表翻页）
- 🖥️ 播放队列 / 最近播放改为**按钮行**：带全局序号 `#N`（与 `/mm remove` 一致）与翻页，点击即可移除 / 重新点歌
- ✨ 新增命令 `/mm remove <序号>`：从等待队列移除自己点的歌（管理员可移任意）
- 📋 服务端 QUEUE_SYNC 同步暂停状态；客户端新增请求状态包，GUI 状态实时刷新
- 🐛 修复 B站视频**首次播放拿不到人工 CC 字幕歌词**（第二次才拿到）的问题：字幕列表接口改用 **WBI 签名** `/x/player/wbi/v2`，避免风控降级首次请求只回 AI 轨、漏掉人工 CC 轨；若返回的轨道列表非空却无人工轨，自动重试一次

### v1.0.0
- 初始版本
- 支持酷狗、网易云、B站三大音源（B站 BV 号 / 多分P / CC 字幕歌词）
- 歌词同步显示、播放队列、全服同步播放
- SQLite/MySQL 数据库、队列持久化
- PlaceholderAPI 扩展
- 服务端统一管理客户端音频缓存
- HTTP 服务分发 B站转码音频，支持公网 IPv6 自动检测
