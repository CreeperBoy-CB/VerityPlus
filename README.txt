Verity Plus（modid: verityconfig）
========================================================================
Minecraft Java 版 1.20.1 / Forge 47.4.21 的客户端辅助模组，
用于更便捷地配置 Verity JE（varmite.verity）的 AI 参数，并把它的
界面与聊天消息中文化。

本文件写给「想改代码 / 想自己打包」的人。只想玩的玩家看「安装」一节即可。


一、这是什么
------------------------------------------------------------------------
Verity JE 本体把 AI、语音、本地模型等设置放在一个由 Yet Another Config Lib
（YACL）渲染的英文界面里，而且大量提示走聊天栏。Verity Plus 做三件事：

  1. 配置界面中文化
     用同一套 YACL API 重建了一个中文配置界面，覆盖提供商、API Key、
     Base URL、模型、语音朗读、深度思考等全部选项。
  2. 聊天与界面文本中文化
     拦截 Verity 发到聊天栏的固定文案，命中词表就换成中文；同时处理
     断线界面、本地 AI（Ollama）安装页、进度 HUD 等处的英文。
  3. 补齐 Verity 缺失的周边功能
     首次启动引导、旧存档导入、高级设置（dayCount / canCrash 等）、
     记忆文件修复、赞助码等。

注意：AI 生成的回复正文（<Verity> 后面那句）不在翻译范围内，
那是模型输出，不属于模组的固定文案。


二、安装（玩家）
------------------------------------------------------------------------
必需：
  * Minecraft 1.20.1
  * Forge 47.x（开发与测试使用 47.4.21）
  * Verity JE 6.0.0 及以上
  * Yet Another Config Lib 3.6.6 及以上

可选：
  * Modpack Helper（modid: modpackhelper）
    提供帮助文档、常见问题、旧存档导入与 DeepSeek 峰谷提示。
    不装也能正常游戏，只是缺这几项功能与对应指令。

把 jar 放进 mods 目录即可，客户端专用；专用服务器上不会加载。


三、指令与按键
------------------------------------------------------------------------
  /vc                     提示常用指令
  /vc cs                  打开 Verity AI 配置界面
  /vc configscreen        同上
  /vc exitgame            直接退出游戏（部分启动器下按钮不生效时的备用）
  /vc verity mfix         修复 Verity 的聊天记忆（移除空 AI 消息）
  /vc verity mdel         删除记忆文件，让 Verity 重新生成

以下指令需要安装 Modpack Helper 才会注册：
  /vc help                显示指令列表
  /vc help <主题>         打开指定帮助文档（支持中文主题名）
  /vc qanda               打开常见问题
  /vc dshint [on|off]     开关 DeepSeek 峰谷时段提示
  /vc dshint date         按当前时间查询一次峰谷提示
  /vc dshint date <日期> <时间>
                          查询指定时刻，格式 YYYY-MM-DD mm.ss

按键：反斜杠 \ 打开配置界面（可在「按键设置」里改）。


四、配置文件
------------------------------------------------------------------------
游戏目录下：
  config/verity-common.toml         Verity 本体的配置（本模组也读写它）
  config/verity-common-m.toml       手机端模板
  config/verityconfig-common.toml   本模组自己的开关（赞助码、峰谷提示等）
  config/simple_translate/*.json    简单翻译模组的联动配置

首次启动时若缺少 verity-common.toml，会用 assets/verityconfig/config/
下的模板补齐；已存在的文件不会被覆盖。


五、目录结构与代码导航
------------------------------------------------------------------------
src/main/java/com/cb2495/verityconfig/
  VerityPlus.java                  模组入口，版本扫描，标题界面事件
  client/                          客户端行为（19 个类）
    ChatFilter.java                  聊天消息入口，只做翻译链路
    ChatTranslator.java              中英词表与匹配逻辑（纯字典，不改游戏类）
    ErrorChatHandler.java            解析 "ERROR: {json}" 并给出中文说明
    ClientCommands.java              /vc 指令注册
    VerityConfigScreenFactory.java   用 YACL 重建中文配置界面
    VerityConfigScreenHook.java      接管打开原版配置界面的入口
    OllamaSetupScreenZh.java         本地 AI（Ollama）安装页中文版
    OllamaHudTranslator.java         下载进度 HUD 中文
    DisconnectScreenTranslator.java  被踢出游戏时的 Farewell 文本
    ModpackHelperBridge.java         反射调用 Modpack Helper（可选依赖）
    KeyBindings.java                 \ 键打开配置
  config/                          配置读写（7 个类）
    VerityConfigManager.java         读写 verity-common.toml
    ModConfig.java                   本模组自己的开关
    Providers.java                   AI 提供商与模型清单
    GeneralSettings.java             高级设置的字段
  screen/                          本模组自己的界面（7 个类）
    VerityPlusScreen.java            主配置界面
    AdvancedSettingsScreen.java      高级设置
    WelcomeScreen.java               首次启动欢迎页
    SponsorScreen.java / RestartPrompt.java / OpenUrlScreen.java
    ModsListScreen.java              只保留按平台自动启停，不再画界面
  util/                            日志、后台线程、图片头解析等工具
  widget/                          下拉框、滑块、勾选框、滚动区域

src/main/resources/
  assets/verity/lang/zh_cn.json     覆盖 Verity 的内置语言文件
  assets/verityconfig/config/       配置模板
  data/verity/advancements/         进度（成就）的中文覆盖
  META-INF/mods.toml                模组元数据


六、开发环境
------------------------------------------------------------------------
IDE        IntelliJ IDEA
Gradle     8.8（用自带的 gradlew，不要用系统 Gradle）
Forge      47.4.21
Mappings   official / 1.20.1
Java       17

从源码构建：

  git clone <本仓库>
  cd VerityConfig
  ./gradlew build                     Linux / macOS
  gradlew.bat build                   Windows

产物在 build/libs/，文件名形如
  [Verity拓展增强]verityplus'2.9.9'-2.9.9.jar

单独运行客户端：

  gradlew.bat runClient
  ./gradlew runClient

注意：打正式包时 build/reobfJar 会写 build/libs 下的 jar。
如果那个文件正被游戏或编辑器占用，会报
「另一个程序正在使用此文件」，先关掉占用它的程序再打包。


七、与 ModPack Helper 的关系（多模块工程）
------------------------------------------------------------------------
ModPack Helper 是另一个仓库，通过 settings.gradle 以子工程形式接入：

  settings.gradle
    include 'modpackhelper'
    project(':modpackhelper').projectDir = file('../ModPackHelper')

所以两个仓库必须放在同一级目录：

  任意路径/
    VerityConfig/      本仓库
    ModPackHelper/     子工程

依赖方向是单向的：

  ModPackHelper ──编译期依赖──▶ VerityPlus
  VerityPlus    ──反射调用────▶ ModPackHelper

VerityPlus 从不 import ModPack Helper 的类，所有调用都走
ModPackHelperBridge（反射），因此：

  * 只装 VerityPlus            → 正常，缺 Modpack Helper 的那几项功能
  * 只装 Modpack Helper        → 游戏拒绝加载（它声明了强制依赖）
  * 两个都装                   → 全部功能

不要试图把 Modpack Helper 改成 VerityPlus 的编译期依赖，那会形成循环，
并且 reobf 后的 SRG 名 jar 混进 runClient 的 userdev 环境会直接
NoSuchMethodError 崩溃。

单独构建子工程：

  gradlew.bat :modpackhelper:build
  gradlew.bat :modpackhelper:runClient


八、发布时提醒玩家
------------------------------------------------------------------------
  * 建议两个模组一起分发
  * 只装 VerityPlus 时 /vc help、/vc qanda、/vc dshint 不存在，
    这不是 bug
  * Android 端不支持输入法冲突修复，首次启动会按平台自动禁用


九、参考素材
------------------------------------------------------------------------
「something about verityje/」目录是 Verity 的反编译与早期汉化素材
（约 250 MB），只作对照参考，已被 .gitignore 排除，不会提交。
其中的 hardcode_ts.txt 表格是历史参考，翻译以代码为准。
