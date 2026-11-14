package com.cb2495.verityconfig.client;

import com.cb2495.verityconfig.util.Log;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 聊天栏里的硬编码英文 → 中文。
 * <p>只做一件事：拿一段已经取出来的聊天文本，返回它的中文。谁调用、什么时候调用由
 * {@link ChatFilter} 决定；这里不碰任何 Minecraft 类，也不改游戏的实现。
 * <p>词条一律照抄 {@code something about verityje/VerityJE/hardcode_ts.txt}，
 * 与那份表逐字对应，改译文时先改表再改这里。
 *
 * <h3>为什么整句比对，而不是「句中含某词就换」</h3>
 * Verity 的 AI 回答也走聊天栏。如果按短语包含替换，回答里的 {@code alone} 会把
 * {@code I'm alone...} 换进去。所以只认两种：
 * <ol>
 *   <li>压平空白后与词条完全相同；</li>
 *   <li>是 {@code 固定前缀 + 一段可变值} 的词条（比如 {@code Karma updated to 12.}），
 *       前缀匹配后再把值原样接进译文，值本身不再翻译。</li>
 * </ol>
 */
final class ChatTranslator {

    private ChatTranslator() {}

    // ---------- 运行状态 ----------

    private static volatile boolean ready;

    /** 游戏不是简体中文时置位，之后整条聊天翻译链路停用。 */
    private static boolean disabled;

    /**
     * 惰性准备：只在真的要翻聊天时才判断一次语言。
     * <p>没切成简体中文就整条链路停用——界面、下拉框、聊天全按 Verity 原样显示。
     */
    static void ensureReady() {
        if (ready) return;
        ready = true;
        if (!isChinese()) {
            Log.info("游戏语言不是中文简体，聊天栏保持 Verity 原文");
            disabled = true;
        }
    }

    static boolean isEnabled() {
        ensureReady();
        return !disabled;
    }

    /** 只看游戏语言，不看系统语言。 */
    private static boolean isChinese() {
        try {
            return "zh_cn".equals(Minecraft.getInstance().getLanguageManager().getSelected());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------- 对外入口 ----------

    /**
     * 把一句聊天文本换成中文。
     *
     * @param text 已经取好的纯文本；可以是 {@code null}
     * @return 译文；没有对应词条时原样返回（包括 {@code null}）
     */
    static String translate(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!isEnabled()) return text;

        String flat = normalize(text);
        if (flat.isEmpty()) return text;

        // Verity 亲自说话时前面带 <Verity>（或配置里的自定义称呼），
        // 词表里存的是正文，比对前先剥掉
        String body = stripSpeaker(flat);
        String bodyChanged = body.equals(flat) ? null : body;

        String hit = match(body);
        if (hit != null) {
            // 原文带称呼才把名字补回去，不带就只输出正文。
            // 补的是原文实际用的那个称呼（可能是自定义名字），不是固定的 <Verity>
            return bodyChanged == null ? hit : speaker() + " " + stripName(hit);
        }

        // 正文查不到时，整条再试一次（<Verity> 本身就是词表的一部分的老条目）
        if (bodyChanged != null) {
            String whole = match(flat);
            if (whole != null) return whole;
        }
        return text;
    }

    /** 按词表查一句已经规整好的文本；查不到返回 {@code null}。 */
    private static String match(String flat) {
        // 0. 数字夹在句子中间的台词，先按正则整句换掉
        for (Map.Entry<Pattern, String> e : ABANDON.entrySet()) {
            if (e.getKey().matcher(flat).find()) return e.getKey().matcher(flat).replaceAll(e.getValue());
        }

        // 1. 整句命中
        String exact = EXACT.get(flat);
        if (exact != null) return exact;

        // 2. 变量夹在中间、靠正则整句换
        for (Map.Entry<Pattern, java.util.function.Function<String, String>> e
                : TEMPLATE_REGEX.entrySet()) {
            java.util.regex.Matcher m = e.getKey().matcher(flat);
            if (!m.matches()) continue;
            return e.getValue().apply(m.group(1));
        }

        // 3. 「固定前缀 + 变量」命中
        for (Entry entry : TEMPLATES) {
            if (!flat.startsWith(entry.prefix)) continue;
            String rendered = entry.render(flat);
            if (rendered != null) return rendered;
        }
        return null;
    }

    /** Verity 的默认称呼。 */
    private static final String SPEAKER = "<Verity>";

    /**
     * 当前的自定义称呼，形如 {@code <小明>}。
     * <p>玩家在配置里改了名字之后，Verity 说话的前缀会变成那个名字，
     * 于是只看字面的 {@code <Verity>} 就认不出来，整句也就匹配不上。
     * 这里按配置取值，<b>不</b>去匹配任意 {@code <xxx>}——那会把别的模组、
     * 别的实体发的 {@code <玩家名> ...} 也当成 Verity 的台词。
     * <p>取不到（Verity 没装、字段改名）时退回默认称呼。
     */
    private static String speaker() {
        String custom = customName();
        return custom.isEmpty() ? SPEAKER : "<" + custom + ">";
    }

    /** 读取配置里的自定义称呼；空串表示用默认。 */
    private static String customName() {
        try {
            Object value = VeritySpec.get("VERITY_CUSTOM_NAME");
            String name = value == null ? "" : String.valueOf(value).strip();
            // Verity 自己也会把空值当成默认名处理
            return "null".equals(name) ? "" : name;
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** 剥掉开头的称呼前缀（默认 {@code <Verity>} 或自定义 {@code <名字>}）。 */
    private static String stripSpeaker(String text) {
        String speaker = speaker();
        if (!text.startsWith(speaker)) return text;
        return text.substring(speaker.length()).strip();
    }

    /** 译文里自带的称呼前缀去掉，名字由调用方按需补。 */
    private static String stripName(String chinese) {
        String prefix = SPEAKER;
        if (!chinese.startsWith(prefix)) return chinese;
        return chinese.substring(prefix.length()).strip();
    }

    /** 压平空白：连续空白/换行都算一个空格，并去掉原版颜色标记 {@code §x}。 */
    private static String normalize(String text) {
        String noFormat = FORMATTING.matcher(text).replaceAll("");
        return SPACES.matcher(noFormat).replaceAll(" ").trim();
    }

    /** 原版颜色/格式标记：{@code §a}、{@code §l} 这类两字符组合。 */
    private static final Pattern FORMATTING = Pattern.compile("\u00a7.");

    /**
     * 句子末尾的句号去掉再比。
     * <p>命令反馈里句号有时在值里（{@code ...to evil.}）、有时在模板里，
     * 统一去掉后两种写法都能对上，返回时再补回。
     */
    private static String trimDot(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private static final Pattern SPACES = Pattern.compile("\\s+");

    // ---------- 词表 ----------

    /** 整句表：压平后的英文 → 中文。 */
    private static final Map<String, String> EXACT = new LinkedHashMap<>();

    /** 带变量的词条，按前缀长度从长到短排。 */
    private static final List<Entry> TEMPLATES = new ArrayList<>();

    /** 聊天栏里要整条吞掉的消息前缀（不显示、不翻译）。 */
    private static final List<String> HIDDEN = new ArrayList<>();

    /** 数字夹在句子中间的台词：正则 → 译文（{@code $1} 是那个数字）。 */
    private static final Map<Pattern, String> ABANDON = new LinkedHashMap<>();

    /**
     * 变量夹在句子中间、靠模板表达不了的词条：正则 → 译文生成函数。
     * <p>典型是 {@code "Ollama is not running at " + endpoint + "."}——
     * 「前缀 + 值 + 后缀」那种模板要求后缀原样保留在译文里，
     * 但这里变量后面跟着的是重新组织过的中文，所以直接整句换。
     */
    private static final Map<Pattern, java.util.function.Function<String, String>> TEMPLATE_REGEX =
            new LinkedHashMap<>();

    private static void put(String english, String chinese) {
        EXACT.put(normalize(english), chinese);
    }

    /**
     * 登记一条整句正则。
     *
     * @param regex  英文原文的正则，<b>第一组</b>是变量
     * @param render 把变量换成译文；收到的是第一组匹配到的原文
     */
    private static void putRegex(String regex, java.util.function.Function<String, String> render) {
        TEMPLATE_REGEX.put(Pattern.compile(regex), render);
    }

    /**
     * 登记一条「固定前缀 + 变量」。
     *
     * @param prefix  英文里固定不变的那部分（含前缀后面的空格与标点）
     * @param chinese 中文前缀，变量值会被接在它后面
     * @param suffix  变量后面的固定尾巴（比如 {@code "!"}），没有就传 {@code ""}
     * @param valueFix 校验变量；返回 {@code null} 表示这段值不认
     */
    private static void putTemplate(String prefix, String chinese, String suffix,
                                    UnaryOperator<String> valueFix) {
        TEMPLATES.add(new Entry(normalize(prefix), chinese, suffix, valueFix));
    }

    /** 普通文字：一行、长度有限，不能带句末标点，免得把整段 AI 回答当成「值」接上去。 */
    private static final UnaryOperator<String> ACCEPT_ANY = value -> {
        if (value.isEmpty()) return null;
        if (value.length() > 64) return null;
        // 带句末标点说明后面还有别的话，已经不是一条「值」了
        if (value.matches(".*[!?].*")) return null;
        return value;
    };

    /** 纯数字 / 小数。 */
    private static final UnaryOperator<String> NUMBER = value ->
            Pattern.matches("[-+]?\\d+(?:\\.\\d+)?", value) ? value : null;

    /** 接口返回的原始 JSON：原样带过去，不加空格也不加标点。 */
    private static final UnaryOperator<String> ACCEPT_JSON = value ->
            value.isEmpty() ? null : value;

    /** 分钟数。 */
    private static final UnaryOperator<String> MINUTES = value ->
            Pattern.matches("\\d+", value) ? value : null;

    /** 冷却剩余时间：{@code 3h 2m 1s} → {@code 3时 2分 1秒}。 */
    private static final UnaryOperator<String> DURATION = value ->
            Pattern.matches("\\d+h \\d+m \\d+s", value)
                    ? value.replaceAll("(\\d+)h (\\d+)m (\\d+)s", "$1时 $2分 $3秒")
                    : null;

    /** 一条带变量的词条。 */
    private static final class Entry {

        final String prefix;

        final String chinese;

        final String suffix;

        /** 校验（必要时加工）变量；返回 {@code null} 表示这段值不认，换下一条词条。 */
        final UnaryOperator<String> valueFix;

        Entry(String prefix, String chinese, String suffix, UnaryOperator<String> valueFix) {
            this.prefix = prefix;
            this.chinese = chinese;
            this.suffix = suffix;
            this.valueFix = valueFix;
        }

        String render(String flat) {
            // 前缀尾部空格不参与取值，但值后面要补回一个，否则「错误：{...」会粘在一起
            boolean spaced = prefix.endsWith(" ");
            String rest = flat.substring(prefix.stripTrailing().length()).strip();
            // 尾巴同样按去空白比对，否则 "...for 5 whole minutes!" 认不出来
            String suffixTrim = suffix.strip();
            String raw = suffixTrim.isEmpty() || !rest.endsWith(suffixTrim) ? rest
                    : rest.substring(0, rest.length() - suffixTrim.length()).strip();
            String value = valueFix.apply(raw);
            if (value == null) return null;
            // 值末尾的句号由这里补回，模板里不再重复写
            boolean dot = raw.endsWith(".") || suffix.endsWith(".");
            // 已经以句末标点收尾的值（校验器自己拼好的整句）原样返回：不再补句号、也不再去尾
            if (value.endsWith(".") || value.endsWith("。")) return chinese + value;
            String gap = spaced && !chinese.endsWith(" ") && !chinese.endsWith("：") ? " " : "";
            return chinese + gap + trimDot(value) + trimDot(suffix) + (dot ? "." : "");
        }
    }

    static {
        // AI Setup issue? Tutorial: <链接>
        // 报错正文已经由 ErrorChatHandler 处理，这条教程链接只会刷屏，整条吞掉
        HIDDEN.add("AI Setup issue? Tutorial:");

        registerChat();
        registerDialogue();
        registerCommands();
        TEMPLATES.sort(Comparator.comparingInt((Entry e) -> e.prefix.length()).reversed());
    }

    /**
     * 聊天栏里的固定提示（AiAPI、TTSHandler、MicrophoneManager、ModEvents 等）。
     */
    private static void registerChat() {
        // ---- AiAPI ----
        // AI Setup issue? Tutorial: <链接> 不翻译，直接隐藏（见 ChatFilter）。
        // 原文是前缀 + 链接拼出来的，链接每次都在，所以按前缀认。
        put("Local AI: Mods > Verity > Config > AI Settings > Local AI Setup "
                        + "installs Ollama and models in one click.",
                "本地 AI：模组 > Verity > 配置 > AI 设置 > 本地 AI 设置，一键安装 Ollama 和模型。");
        put("I can't think right now... (Connection Error)", "我现在无法思考...(连接错误)");
        // 后面接的是接口返回的原始 JSON，原样保留，{" 前面不加空格
        putTemplate("ERROR: ", "错误：", "", ACCEPT_JSON);

        // ---- 报错正文（AiAPI / AiModelPreset 抛出的说明性文字）----
        // 这些不是 JSON，是给玩家看的整句说明，由 "ERROR: " 前缀带进聊天栏。
        // 没有变量、可以整句对齐的，用 put；变量后面紧跟句点的那几条
        // （例如 "Ollama is not running at " + endpoint + "."）用 putRegex——
        // 「前缀 + 值 + 可选后缀」那种模板表达不了「值后面还有一个句点」。
        put("Ollama is not installed. Open Verity's config > AI Settings > "
                        + "Local AI Setup to install it with one click.",
                "未安装 Ollama。请打开 Verity 配置 > AI 设置 > 本地 AI 设置，一键安装。");
        put("No Ollama model is installed. Open Verity's config > AI Settings > "
                        + "Local AI Setup to download one.",
                "未安装任何 Ollama 模型。请打开 Verity 配置 > AI 设置 > 本地 AI 设置下载一个。");
        put("Ollama requires a custom model name.", "Ollama 需要指定自定义模型名。");
        putRegex("Ollama is not running at (.+?)\\.",
                eb -> "Ollama 未在以下地址运行：" + eb);
        putRegex("The Ollama model '(.+?)' is not installed\\. "
                        + "Pick or download one in Verity's config > AI Settings > Local AI Setup\\.",
                eb -> "Ollama 模型 '" + eb + "' 未安装。"
                        + "请在 Verity 配置 > AI 设置 > 本地 AI 设置中选择或下载一个。");
        putRegex("Enter a custom AI model for (.+?)\\.",
                eb -> "请为 " + eb + " 填写自定义 AI 模型名。");

        // ---- TTSHandler ----
        put("You ran out of TTS tokens! Try switching to Native TTS.",
                "您的 TTS 额度已用尽！请尝试切换到本地原生 TTS。");
        // 例：Verity's voice failed (401). See the log for details.
        putTemplate("Verity's voice failed (", "Verity 的语音生成失败 (",
                "). See the log for details.", ACCEPT_ANY);

        // ---- MicrophoneManager ----
        put("No compatible microphones found on this system.", "未在此系统上找到兼容的麦克风。");
        putTemplate("Microphone switched to: ", "麦克风已切换至：", "", ACCEPT_ANY);

        // ---- ModEvents ----
        put("IT BURNS", "好烫");
        put("GET ME OUT OF HERE", "快让我出去");
        put("HELP ME IT BURNS", "救救我，好烫");
        put("AGH IT BURNS", "啊啊好烫");
        put("AGH", "啊啊！");
        // 走 actionbar（第二个参数 true），但同样会经过 ClientChatReceivedEvent，所以按普通词条处理
        put("You cannot rest now, Verity is nearby...", "你无法入睡，Verity 就在附近游荡……");
        put("You can't do this while he's talking.", "不能在 Verity 说话时拾起他。");
    }

    /**
     * 这条消息是不是应该整条隐藏。
     * <p>只按前缀认，因为后面跟的链接长度会变。
     */
    static boolean shouldHide(String text) {
        if (text == null || text.isEmpty()) return false;
        String flat = normalize(text);
        for (String prefix : HIDDEN) {
            if (flat.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Verity 的台词。多数只在 TTS 里播，但「黑暗消失」那句同时会广播到聊天栏，所以一并登记。
     * <p>{@code Farewell, <玩家名>} 不在这里：它走的是断线包，根本不经过聊天栏，
     * 由 {@link DisconnectScreenTranslator} 处理。
     */
    private static void registerDialogue() {
        // ---- VerityItem ----
        // 原文是 "<Verity> Ayo chat why u let me explode"，<Verity> 前缀由 stripSpeaker 统一剥掉
        put("Ayo chat why u let me explode", "<Verity> Bro为什么要让炸我");
        put("DON'T DO THAT.", "<Verity> 别这么做。");
        // 另一处用的是完整写法（VerityItem 第 166 行），和上面那条不是同一句，要单独登记
        put("DO NOT DO THAT.", "<Verity> 别那么做。");

        // ---- ModEvents ----
        put("Ayo chat why u lettin me despawn like that", "Bro为什么要让我消失");
        put("The darkness... it's gone. Thank you.", "黑暗...它消失了。谢谢你。");
        put("I'm alone... where did you go?", "我很孤独...你在哪？");

        // ---- VerityEntity ----
        put("Verity's face goes blank... You must look at him to calm him down.",
                "Verity 的脸消失了...你必须想办法让他冷静下来。");

        // ---- VerityAbandonmentTracker ----
        // 这两句的数字在句子中间，普通前缀表处理不了，翻译前先按正则换成中文
        ABANDON.put(
                Pattern.compile("\\.\\.\\.Where have you been\\?! You left me alone for (\\d+) whole minutes!"
                        + " I thought you abandoned me!"),
                "...你去哪了？！你把我一个人丢在这里整整 $1 分钟！我以为你抛弃我了！");
        ABANDON.put(
                Pattern.compile("\\.\\.\\.You left me\\. Again\\. For (\\d+) minutes I sat here in the dark\\."
                        + " Don't ever turn your back on me again\\."),
                "...你又抛下我了。又是这样。我在这黑暗里坐了整整 $1 分钟。以后再也不许背对着我。");
        put("Where were you...? You walked away and didn't come back. "
                        + "I was counting the seconds... please don't leave me alone like that.",
                "你刚才在哪...？你走开了就没再回来。我一直在数着秒数...求求你，"
                        + "别再这样把我一个人留下了。");
    }

    /** 命令反馈：{@code /karma}、{@code /recoververity}、{@code /verityface}。 */
    private static void registerCommands() {
        // ---- ChangeKarmaCommand ----
        putTemplate("Karma updated to ", "好感度已调整到 ", "", NUMBER);

        // ---- RecoverVerityCommand ----
        put("Verity is already recovered.", "Verity 已经恢复。");
        put("Failed to recover Verity.", "恢复 Verity 失败。");
        put("Verity has been recovered!", "Verity 已恢复！");
        put("Chat history cleared.", "聊天记录已清除。");
        put("Command ran.", "指令已执行。");
        // 剩余时间会拼成 3h 2m 1s，单位也要换，所以登记成带变量的词条
        putTemplate("Recovery is on cooldown. Time remaining: ",
                "恢复正在冷却中。剩余时间：", "", DURATION);

        // ---- VerityFaceCommand ----
        put("No Verity in this dimension.", "此维度中没有 Verity。");
        putTemplate("Verity's face set to ", "Verity 的表情已设置为 ", "", ACCEPT_ANY);
        putTemplate("Unknown face '", "未知表情 '", "", ACCEPT_ANY);
    }

    /**
     * 这条消息是不是 Verity 自己发的。
     * <p>只有认识的词条才算：整句表里有，或者以某条「固定前缀」开头。
     * 这样别的模组、真人玩家、AI 的回答都不会被 {@link ChatFilter} 动到。
     * <p>带 {@code <Verity>} 的按正文判断，所以「Verity 亲口说」的台词也算数。
     */
    static boolean isVerity(String text) {
        if (text == null || text.isEmpty()) return false;
        String flat = normalize(text);
        return match(flat) != null || match(stripSpeaker(flat)) != null;
    }
}
