# Verity Plus

> modid: `verityplus`

Minecraft Java 版 **1.20.1 / Forge 47.4.21** 的客户端辅助模组，用于更便捷地配置

Verity JE（`varmite.verity`）的 AI 参数，并把它的界面与聊天消息中文化。

本文件写给「想改代码 / 想自己打包」的人。只想玩的玩家看 [安装](#二安装玩家) 一节即可。

---

## 一、这是什么

Verity JE 本体把 AI、语音、本地模型等设置放在一个由 Yet Another Config Lib（YACL）

渲染的英文界面里，而且大量提示走聊天栏。Verity Plus 做三件事：

### 1. 配置界面中文化

用同一套 YACL API 重建了一个中文配置界面，覆盖提供商、API Key、Base URL、模型、语音朗读、深度思考等全部选项。文字虽是重写的，选项与取值范围仍全部来自 Verity 自己的 `ForgeConfigSpec`，所以不会和本体走岔。

### 2. 聊天与界面文本中文化

拦截 Verity 发到聊天栏的固定文案，命中词表就换成中文；同时处理断线界面、
本地 AI（Ollama）安装页、进度 HUD 等处的英文。

### 3. 用中文重建 Verity 的配置界面

读 Verity 自己的 `ForgeConfigSpec`，用 YACL 重建一套中文界面，
挂在「§Mods → Verity → Config」上。**本模组不再自带配置界面**——
Verity 原生的配置界面已经覆盖了同样的选项，再维护一套只会两处走岔。

此外还有记忆文件修复、赞助码等周边功能。

> **注意**：AI 生成的回复正文（`<Verity>` 后面那句）不在翻译范围内，
> 那是模型输出，不属于模组的固定文案。

---

## 二、安装（玩家）

**必需**

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x（开发与测试使用 47.4.21） |
| Verity JE | 6.0.0 及以上 |
| Yet Another Config Lib | 3.6.6 及以上 |

**可选**

- **[Modpack Helper](https://github.com/CreeperBoy-CB/ModpackHelper)**（modid: `modpackhelper`）—— 提供帮助文档、常见问题、
  旧存档导入与 DeepSeek 峰谷提示。不装也能正常游戏，只是缺这几项功能与对应指令。

把 jar 放进 `mods` 目录即可，客户端专用；专用服务器上不会加载。

---

## 三、指令与按键

| 指令 | 作用 |
|---|---|
| `/vc` | 提示常用指令 |
| `/vc cs` | 打开 Verity 的配置界面（中文） |
| `/vc configscreen` | 同上 |
| `/vc exitgame` | 直接退出游戏（部分启动器下按钮不生效时的备用） |
| `/vc verity mfix` | 修复 Verity 的聊天记忆（移除空 AI 消息） |
| `/vc verity mdel` | 删除记忆文件，让 Verity 重新生成 |

**以下指令需要安装 Modpack Helper 才会注册：**

| 指令 | 作用 |
|---|---|
| `/vc help` | 显示指令列表 |
| `/vc help <主题>` | 打开指定帮助文档（支持中文主题名） |
| `/vc qanda` | 打开常见问题 |
| `/vc dshint [on\|off]` | 开关 DeepSeek 峰谷时段提示 |
| `/vc dshint date` | 按当前时间查询一次峰谷提示 |
| `/vc dshint date <日期> <时间>` | 查询指定时刻，格式 `YYYY-MM-DD mm.ss` |

**按键**：反斜杠 `\` 打开 Verity 的配置界面（中文），可在「按键设置」里改。
与 `/vc cs` 是同一个界面。

---

## 四、配置文件

游戏目录下：

| 路径 | 说明 |
|---|---|
| `config/verity-common.toml` | Verity 本体的配置（本模组也读写它） |
| `config/verity-common-m.toml` | 手机端模板 |
| `config/verityplus-common.toml` | 本模组自己的开关（赞助码、峰谷提示等） |
| `config/simple_translate/*.json` | 简单翻译模组的联动配置 |

首次启动时若缺少 `verity-common.toml`，会用 `assets/verityplus/config/` 下的模板补齐；
已存在的文件不会被覆盖。

> **从旧版本升级**：modid 从 `verityconfig` 改为 `verityplus` 时配置文件也跟着换了名字，
> 首次启动会自动把 `config/verityconfig-common.toml` 改名为 `config/verityplus-common.toml`，
> 不需要手动处理。

---

## 五、目录结构与代码导航

```
src/main/java/com/cb2495/verityplus/
├── VerityPlus.java                    模组入口，版本扫描，标题界面事件
├── client/                            客户端行为（20 个类）
│   ├── FirstRunGate.java              决定何时弹引导（等开场动画播完）
│   ├── ChatFilter.java                聊天消息入口，只做翻译链路
│   ├── ChatTranslator.java            中英词表与匹配逻辑（纯字典，不改游戏类）
│   ├── ErrorChatHandler.java          解析 "ERROR: {json}" 并给出中文说明
│   ├── ClientCommands.java            /vc 指令注册
│   ├── VerityConfigScreenFactory.java 用 YACL 重建中文配置界面
│   ├── VerityConfigScreenHook.java    接管打开原版配置界面的入口
│   ├── OllamaSetupScreenZh.java       本地 AI（Ollama）安装页中文版
│   ├── OllamaHudTranslator.java       下载进度 HUD 中文
│   ├── DisconnectScreenTranslator.java 被踢出游戏时的 Farewell 文本
│   ├── ModpackHelperBridge.java       反射调用 Modpack Helper（可选依赖）
│   └── KeyBindings.java               \ 键打开 Verity 配置界面
├── config/                            配置读写（7 个类）
│   ├── VerityConfigManager.java       读写 verity-common.toml
│   ├── ModConfig.java                 本模组自己的开关
│   ├── GameState.java                 引导状态（.cache/verity_plus/game_state）
│   └── Providers.java                 AI 提供商与模型清单（暂无界面使用，见下）
├── screen/                            独立界面（6 个类）
│   ├── GuideScreen.java               引导教程（欢迎页 + 5 步 + 结束页）
│   ├── ResumePrompt.java              「上次配置未完成」确认框
│   ├── SponsorScreen.java             赞助页
│   ├── RestartPrompt.java             重启确认
│   ├── OpenUrlScreen.java             缺少 Modpack Helper 时的提示页
│   └── ModsListScreen.java            只剩版本号字段，不画界面
├── util/                              日志、后台线程、图片头解析等工具
└── widget/                            通用的下拉框控件

src/main/resources/
├── assets/verity/lang/zh_cn.json      覆盖 Verity 的内置语言文件
├── assets/verityplus/config/          配置模板
├── data/verity/advancements/          进度（成就）的中文覆盖
└── META-INF/mods.toml                 模组元数据
```

### 关于 `Providers`

`config/Providers.java` 是一张「提供商 → 端点 / API 格式 / Key 申请页 / 模型清单」
的表。**目前没有任何界面在用它**——旧的自带 AI 配置界面已经移除。
保留它是为了重写首次启动配置流程时直接复用：
用户选一个提供商，就自动填上 Base URL 与可选模型。

`widget/DropdownWidget` 同理：它是通用的下拉框控件，暂时没有调用方。

---

## 六、开发环境

| 项目 | 版本 / 说明 |
|---|---|
| **IDE** | IntelliJ IDEA |
| **Gradle** | 8.8 · 用自带的 `gradlew`，不要用系统 Gradle |
| **Forge** | 47.4.21 |
| **Mappings** | official / 1.20.1 |
| **Java** | 17 |

### 从源码构建

```bash
# 克隆后进入仓库根目录
cd VerityConfig

# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

产物在 `build/libs/`，文件名形如：

```
[Verity拓展增强]verityplus'2.9.9'-2.9.9.jar
```

### 单独运行客户端

```bash
gradlew.bat runClient     # Windows
./gradlew runClient       # Linux / macOS
```

> **打包注意**：`build/reobfJar` 会写 `build/libs` 下的 jar。
> 如果那个文件正被游戏或编辑器占用，会报「另一个程序正在使用此文件」，
> 先关掉占用它的程序再打包。


## 七、与 ModPack Helper 的关系（多模块工程）

ModPack Helper 是另一个仓库，通过 `settings.gradle` 以子工程形式接入：

```groovy

include 'modpackhelper'

project(':modpackhelper').projectDir = file('../ModPackHelper')

```

所以两个仓库必须放在同一级目录：

```

任意路径/

├── VerityConfig/      本仓库

└── ModPackHelper/     子工程

```

依赖方向是单向的：

```

ModPackHelper ──编译期依赖──▶ VerityPlus

VerityPlus    ──反射调用────▶ ModPackHelper

```

VerityPlus 从不 `import` ModPack Helper 的类，所有调用都走

`ModpackHelperBridge`（反射），因此：

| 安装情况 | 结果 |
|---|---|
| 只装 VerityPlus | 正常，缺 Modpack Helper 的那几项功能 |
| 只装 Modpack Helper | 游戏拒绝加载（它声明了强制依赖） |
| 两个都装 | 全部功能 |

> **不要**试图把 Modpack Helper 改成 VerityPlus 的编译期依赖，那会形成循环，

> 并且 reobf 后的 SRG 名 jar 混进 `runClient` 的 userdev 环境会直接

> `NoSuchMethodError` 崩溃。

单独构建子工程：

```bash

gradlew.bat :modpackhelper:build

gradlew.bat :modpackhelper:runClient

```

---

## 八、发布时提醒玩家

- 建议两个模组一起分发

- 只装 VerityPlus 时 `/vc help`、`/vc qanda`、`/vc dshint` 不存在，
  这不是 bug

- 本模组**没有自己的配置界面**，`/vc cs`、`/vc configscreen` 和 `\` 键打开的
  都是 Verity 自己的配置界面（中文）。要改 AI 设置请到那里，不要找「VerityPlus 的设置」

- 首次启动会弹引导（欢迎页 → 第 1~5 步 → 结束页）。若 Verity 的开场动画开着，
  会等它播完再弹，不会把动画顶掉

- 引导进度记在 `.cache/verity_plus/game_state`。中途关游戏，下次启动会问
  「是否继续」；想重置引导，删掉这个文件即可

- 引导里目前只有 5 个占位步骤，还没接入真实的配置项

---

## 九、免责声明与授权说明

> **本节是本项目最重要的一节，请在使用或分发前完整阅读。**

### 1. 本项目与 Verity JE 作者无隶属关系

Verity Plus 是**第三方社区项目**，由 CreeperBoy-CB 独立开发，

与 Verity JE 的作者 **Varmite、BlockMaster29、JJack、ErrorCezar**

（以及 Verity 形象原作者 ThatMob）没有任何隶属、合作或背书关系。

本项目未获得他们的官方授权或认可。

### 2. Verity JE 采用 All Rights Reserved

Verity JE 的 `mods.toml` 中明确声明：

```toml

license="All Rights Reserved"

```

**All Rights Reserved 意味着版权方保留全部权利**：除法律明确允许的

合理使用等情形外，未经作者书面许可，不得复制、修改、再分发其代码或资源。

本项目的 MIT 许可证**只覆盖 CreeperBoy-CB 自己编写的代码**，

不能、也不会改变 Verity JE 的授权状态。

### 3. 本项目具体做了什么

为了说明边界，下面逐项列出本项目对 Verity JE 的交互方式：

| 行为 | 说明 | 是否分发 Verity 的代码/资源 |
|---|---|---|
| 聊天/界面文本翻译 | 运行时匹配 Verity 发出的字符串并替换为中文，译文在**本项目自己的字典里** | ❌ 否 |
| 配置界面 | 用 YACL 重新搭建，只读写 `verity-common.toml` 这个**配置数据** | ❌ 否 |
| 界面与 HUD 钩子 | 通过反射按类名访问 `varmite.verity.*`，不 import、不打包 | ❌ 否 |
| `assets/verity/lang/zh_cn.json` | 本项目**自行翻译**的语言文件（原版只有 `en_us.json`） | ⚠️ 翻译文本 |
| `data/verity/advancements/*.json` | 本项目**自行翻译**的进度名称与描述 | ⚠️ 翻译文本 |
| Modpack Helper 的帮助文档 | 文字与截图均为本项目自制 | ❌ 否 |

标 ⚠️ 的两项是**翻译作品**：内容是中文译文，不是 Verity 原始资源的副本。

它们在运行时覆盖原版同名资源，前提是玩家自己已经安装了 Verity JE。

### 4. 翻译文本的来源

聊天栏里那些被替换的英文原句，是开发时通过反编译 Verity JE 的 class 文件
读取字符串常量得到的 —— 因为模组不提供源码，这是唯一可行的途径。

需要说明的是：

- 这些英文原句只作为**匹配用的键**，以中英对照的形式写在本项目的
  `ChatTranslator.java` 字典里
- 译文由本项目自行撰写
- 本项目不分发 Verity JE 的 class 文件、反编译源码或原始资源文件

如果你打算在自己的项目里做类似的事，请注意：多数司法管辖区允许
**为互操作性进行的逆向工程**，但不允许分发逆向得到的代码。
只把字符串常量当作对照表的键、自己撰写译文，是这类项目最稳妥的做法。

### 5. 本项目不包含

- ❌ Verity JE 的任何 `.class` 文件或反编译源码

- ❌ Verity JE 的贴图、模型、音效、动画

- ❌ Verity JE 的原始 `.json` 资源

- ❌ 任何用于绕过 Verity JE 授权检查的代码

本项目的 jar **无法在未安装 Verity JE 的环境下提供任何 Verity 功能**，

它完全依赖玩家自己合法取得的 Verity JE。

### 6. 责任限制

- 本项目按「现状」提供，不附带任何明示或暗示的担保

- 作者不对使用本项目造成的任何直接或间接损失负责
  （包括但不限于存档损坏、AI API 费用、游戏崩溃）

- 本项目会调用第三方 AI 接口，**产生的费用由使用者自行承担**

- 若 Verity JE 作者提出异议，本项目会配合调整或停止分发

### 7. 如果你是 Verity JE 的作者

如果你认为本项目侵犯了你的权利，请通过仓库的 Issue 联系。

作者承诺会在核实后**及时移除相关内容或停止分发**，无需法律程序。

### 8. 建议的合规用法

- ✅ 自己游玩使用

- ✅ 在整合包中附带（注意同时提示玩家 Verity JE 需自行取得授权）

- ✅ 学习、参考本项目的实现思路

- ✅ Fork 并修改本项目自己的代码（MIT 允许）

- ⚠️ 商业整合包分发：请先确认 Verity JE 作者对整合包分发的态度

- ❌ 将本项目改造成绕过 Verity JE 授权或收费的模式

---

## 十、许可证

本项目采用 **MIT 许可证**，全文见仓库根目录的 [LICENSE.txt](LICENSE.txt)。

```

Copyright (c) 2026 CreeperBoy-CB

```

你可以自由使用、修改、再发布本项目的代码，只需在副本中保留上述版权声明与

许可声明。

> 注意本项目的**依赖**各有各的许可证，不在 MIT 覆盖范围内：

| 依赖 | 许可证 |
|---|---|
| Forge | LGPL 2.1 |
| Yet Another Config Lib | LGPL 3.0-or-later |
| Modpack Helper | MIT（本项目） |
| Verity JE | **All Rights Reserved** |

Verity JE 采用 All Rights Reserved，本项目**没有**获得其作者的授权。
本项目与它的交互方式（运行时翻译、反射钩子、读写配置）以及相关风险说明见上一节。