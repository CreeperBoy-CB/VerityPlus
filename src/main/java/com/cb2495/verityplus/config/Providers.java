package com.cb2495.verityplus.config;

import com.cb2495.verityplus.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供商注册表：端点、对话补全地址、API 格式、Key 获取地址与可选模型。
 * <p>这些信息原本分在两处维护：一个已经移除的配置界面里有一份「提供商 →
 * 模型 / Key 地址」，{@code VerityConfigManager} 里另有一份「提供商 → 端点 /
 * API 格式」。两边各写一遍提供商清单，往一边加提供商而忘了另一边，会以
 * 「下拉框里没有它」或者「Base URL 被清空」这种静默方式出错。
 * <p>这里合并成唯一来源，两个读取接口都改成查这张表。
 *
 * <p><b>当前没有任何界面在用这张表</b>——旧的 AI 配置界面已经移除，
 * 功能与 Verity 自己的配置界面重合。表本身保留下来：重写首次启动配置流程时，
 * 「选提供商 → 自动填端点 / 模型清单」正是要复用的部分。
 * 想临时用一下可以走 {@link VerityConfigManager#getEndpointForProvider} 那几个转发方法。
 */
public final class Providers {

    private Providers() {}

    /** 一个可选模型。 */
    public record Model(String name, String desc) {}

    /**
     * 一个提供商。
     *
     * @param name      显示名，同时也是各处的键
     * @param endpoint  默认 Base URL
     * @param chatUrl   对话补全的固定地址；为空表示由用户填的 Base URL 推导（自定义提供商）
     * @param apiFormat Verity 侧的 API 格式标识
     * @param keyUrl    申请 API Key 的页面；为空表示没有预设链接
     * @param models    可选模型；空列表表示需要用户自己填
     */
    public record Provider(String name, String endpoint, String chatUrl,
                           String apiFormat, String keyUrl, List<Model> models) {}

    /** 「自定义」的固定名字：多处逻辑依赖这个名字做判断。 */
    public static final String CUSTOM = "自定义";

    /**
     * 引导流程第一页里「自定义」那一项的显示名。
     * <p>与 {@link #CUSTOM} 并存而不是直接复用它：注册表里的 {@code 自定义}
     * 是「地址由玩家填」的兜底项，这里是下拉框里给玩家看的标签，两者语义相同、
     * 措辞可以各走各的。写配置时统一落到 {@code OPENAI}。
     */
    public static final String CUSTOM_LABEL = "自定义";

    /**
     * 引导第一页下拉框里「还没选」的那一项。
     * <p>它不是一个真正的提供商，选中它不写任何配置。
     */
    public static final String PICKER_PLACEHOLDER = "（请选择）";

    /**
     * Verity 侧 {@code aiProvider} 的枚举值与本地化名。
     * <p>这几个值是写进 {@code verity-common.toml} 的字面量，必须与 Verity 的
     * {@code AiProvider} 枚举完全一致；写错了 Verity 会因为「非法枚举值」
     * 把整份配置打回默认。
     */
    public static final String GEMINI = "GEMINI";
    public static final String GROQ = "GROQ";
    public static final String OPENAI = "OPENAI";
    public static final String OLLAMA = "OLLAMA";

    private static final Map<String, Provider> REGISTRY = new LinkedHashMap<>();

    private static void put(Provider provider) {
        REGISTRY.put(provider.name(), provider);
    }

    static {
        put(new Provider("DeepSeek",
                "https://api.deepseek.com/v1",
                "https://api.deepseek.com/chat/completions",
                "DEEPSEEK_CHAT",
                "https://platform.deepseek.com/api_keys",
                List.of(
                        new Model("deepseek-flash", "低价、快速"),
                        new Model("deepseek-v4-pro", "深度思考，旗舰"))));

        put(new Provider("智谱 (GLM)",
                "https://open.bigmodel.cn/api/paas/v4",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "OPENAI_CHAT_COMPAT",
                "https://open.bigmodel.cn/console/overview",
                List.of(
                        new Model("glm-4-flash", "免费、文本生成"),
                        new Model("glm-4.7-flash", "免费、深度思考"),
                        new Model("glm-4-flash-250414", "免费、文本生成"),
                        new Model("glm-5.3-flash", "低价、深度思考"),
                        new Model("glm-4.7-flash-x", "付费、深度思考"),
                        new Model("glm-4.7", "付费、深度思考"),
                        new Model("glm-4.5-air", "付费、深度思考"),
                        new Model("glm-4.6", "付费、深度思考"),
                        new Model("glm-5.2", "付费、深度思考"),
                        new Model("glm-5.3", "付费、旗舰"))));

        put(new Provider("阿里云百炼 (通义千问)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "OPENAI_CHAT_COMPAT",
                "https://bailian.console.aliyun.com/?apiKey=1",
                List.of(
                        new Model("qwen3.6-flash", "低成本、轻量极速"),
                        new Model("qwen3-turbo", "低成本、轻量极速"),
                        new Model("qwen3.7-plus", "付费、均衡主力"),
                        new Model("qwen3.8-max", "付费、旗舰"),
                        new Model("qwen3.7-max", "付费、旗舰"))));

        put(new Provider("Kimi",
                "https://api.moonshot.cn/v1",
                "https://api.moonshot.cn/v1/chat/completions",
                "OPENAI_CHAT_COMPAT",
                "https://platform.moonshot.cn/console/api-keys",
                List.of(
                        new Model("kimi-k2.6", "付费、思考/非思考"),
                        new Model("kimi-k2.5", "付费、思考/非思考"),
                        new Model("kimi-k3", "付费、旗舰"),
                        new Model("moonshot-v1-128k", "付费、纯文本"),
                        new Model("moonshot-v1-32k", "付费、纯文本"),
                        new Model("moonshot-v1-8k", "付费、纯文本"))));

        put(new Provider(CUSTOM, "", "", "OPENAI_CHAT_COMPAT", "", List.of()));
    }

    /** 全部提供商显示名，顺序与注册顺序一致。 */
    public static List<String> names() {
        return new ArrayList<>(REGISTRY.keySet());
    }

    /** 全部提供商；下拉框直接用它渲染。 */
    public static List<Provider> all() {
        return new ArrayList<>(REGISTRY.values());
    }

    public static Provider byName(String name) {
        return REGISTRY.get(name);
    }

    /** 该提供商的可选模型；未知提供商或自定义返回空列表。 */
    public static List<Model> modelsFor(String name) {
        Provider provider = REGISTRY.get(name);
        return provider == null ? Collections.emptyList() : provider.models();
    }

    public static String endpointFor(String name) {
        Provider provider = REGISTRY.get(name);
        return provider == null ? "" : provider.endpoint();
    }

    public static String keyUrlFor(String name) {
        Provider provider = REGISTRY.get(name);
        return provider == null ? "" : provider.keyUrl();
    }

    public static String apiFormatFor(String name) {
        Provider provider = REGISTRY.get(name);
        return provider == null ? "OPENAI_CHAT_COMPAT" : provider.apiFormat();
    }

    /**
     * 对话补全的完整地址。
     * <p>预定义提供商直接用注册表里的固定地址（即使 Base URL 被改过也保持原逻辑）；
     * 自定义提供商则从用户填的 Base URL 推导。
     */
    public static String chatUrlFor(String name, String baseUrl) {
        Provider provider = REGISTRY.get(name);
        if (provider != null && !provider.chatUrl().isEmpty()) {
            return provider.chatUrl();
        }
        if (baseUrl.endsWith("/chat/completions")) return baseUrl;
        if (baseUrl.endsWith("/")) return baseUrl + "chat/completions";
        return baseUrl + "/chat/completions";
    }

    /**
     * 由 Base URL 反查提供商；没匹配上任何预定义端点时算「自定义」。
     */
    public static String inferFromEndpoint(String endpoint, String fallback) {
        if (endpoint == null || endpoint.isEmpty()) return fallback;
        for (Provider provider : REGISTRY.values()) {
            if (endpoint.equals(provider.endpoint())) {
                return provider.name();
            }
        }
        return CUSTOM;
    }

    // ========== 引导第一页：提供商列表 ==========

    /**
     * 引导第一页列表里的一项。
     *
     * @param label        左边一列显示的提供商名
     * @param desc         右边一列显示的介绍；为空表示这一项没有介绍（选中后右列换成输入框）
     * @param provider     选中后要写进 {@code aiProvider} 的值；{@code null} 表示只有 {@code endpoint}
     * @param endpoint     选中后要写进 {@code aiEndpoint} 的地址；{@code null} 表示不写
     * @param note         选中后额外补一行技术提示；{@code null} 表示没有
     * @param newKeyUrl    「新建一个 API Key」跳转的页面；空串表示没有
     * @param topUpUrl     「充值」跳转的页面；空串表示没有
     * @param registerUrl  「获取 API Key」跳转的注册页；空串表示没有（自定义永远为空）
     */
    public record PickerOption(String label, String desc,
                               String provider, String endpoint, String note,
                               String newKeyUrl, String topUpUrl, String registerUrl) {

        /** 「自定义」：地址由玩家自己填，右列换成输入框。 */
        public boolean isCustom() {
            return CUSTOM_LABEL.equals(label);
        }

        /** 「（请选择）」：占位项，选中它不写任何配置。 */
        public boolean isPlaceholder() {
            return PICKER_PLACEHOLDER.equals(label);
        }
    }

    /**
     * 真正落地时的 {@code aiProvider}。
     * <p>DeepSeek / 智谱 / ChatGPT / OpenRouter / Mistral 走的都是 OpenAI 兼容接口，
     * Verity 侧只能填 {@code OPENAI}，靠 {@code aiEndpoint} 区分；
     * 只有 Gemini / GROQ 是独立枚举。
     *
     * <p><b>endpoint 什么时候留 null：</b>Verity 只对 {@code OPENAI} 读
     * {@code aiEndpoint}。Gemini 走 {@code GoogleGenAiChatModel}、Mistral 走
     * {@code MistralAiChatModel}、OpenRouter 与 GROQ 走 {@code OpenAiChatModel}
     * 但 baseUrl 是写死的字符串——这四家的 builder 都不看 endpoint。
     * 给它们写地址只会让玩家在 Verity 配置界面里看到一个不起作用的值、
     * 还以为要改它，所以一律留 null，交给 Verity 自己的默认地址。
     *
     * <p>每项的末尾三个地址是第 2 步（填 API Key）要用的跳转目标，
     * 顺序固定为：新建 Key / 充值 / 注册。
     */
    private static List<PickerOption> pickerTemplate() {
        List<PickerOption> list = new ArrayList<>();
        list.add(new PickerOption(PICKER_PLACEHOLDER, "选择一个你中意的 AI 提供商",
                null, null, null, "", "", ""));
        // GROQ：Cloudflare 按 loc=CN 在边缘直接 403，连 /chat/completions 都发不出去，
        // 所以措辞说的是「API 无法访问」，而官网 groq.com 静态页其实能打开
        list.add(new PickerOption("GROQ", "极速输出，有免费额度，国内API无法访问",
                GROQ, null, "已切换为 GROQ，但模型尚未配置",
                "https://console.groq.com/keys",
                "https://console.groq.com/settings/billing",
                "https://console.groq.com/login"));
        list.add(new PickerOption("DeepSeek", "对话自然，擅长深度思考，性价比高，分峰谷计费",
                OPENAI, "https://api.deepseek.com/v1", null,
                "https://platform.deepseek.com/api_keys",
                "https://platform.deepseek.com/top_up",
                "https://platform.deepseek.com/sign_in"));
        list.add(new PickerOption("智谱", "试玩首选，上手轻便，有免费模型与免费额度，部分时间段可能反应迟钝",
                OPENAI, "https://open.bigmodel.cn/api/paas/v4", null,
                "https://open.bigmodel.cn/console/apikeys",
                "https://open.bigmodel.cn/console/finance",
                "https://open.bigmodel.cn/login"));
        // ChatGPT 是 OPENAI 路径，aiEndpoint 对它是真的会被读，所以这里要写
        list.add(new PickerOption("ChatGPT", "能力顶尖，功能强大，价格偏高，国内无法正常访问",
                OPENAI, "https://api.openai.com/v1", null,
                "https://platform.openai.com/api-keys",
                "https://platform.openai.com/settings/organization/billing/overview",
                "https://platform.openai.com/signup"));
        // Gemini：GoogleGenAiChatModel 不用 endpoint，留 null
        list.add(new PickerOption("Gemini", "聪慧（？），有免费额度，国内无法正常访问",
                GEMINI, null, "已切换为 Gemini，但模型尚未配置",
                "https://aistudio.google.com/app/apikey",
                "https://aistudio.google.com/app/billing",
                "https://aistudio.google.com/"));
        // OpenRouter：Verity 内部写死 https://openrouter.ai/api/v1/，留 null
        //
        // 文案从「国内可访问但延迟较高」改成「国内无法注册账号」：
        // 早先只测了接口延迟（能通，2~7 秒），据此归进了「可访问」。
        // 但注册走不通就没意义——实测两台设备、两个不同出口（校园网 AS4134 电信、
        // 私有网络 AS9808 移动）都在提交时秒报
        // "Unable to verify ... For your security, this request could not be completed"，
        // 且始终看不到验证码。追下去是注册页的验证码依赖 www.google.com，
        // 而该域名在境内被阻断（recaptcha.net 镜像能通，Google 主站不能）。
        // 换成 GitHub 登录也一样：OAuth 本身走得通，回来后仍要过同一道校验。
        // 所以对外措辞只说「无法注册账号」——API 确实能连，不该说成完全不可访问。
        list.add(new PickerOption("OpenRouter", "一个模型集合中转站，聚合大量模型（含免费模型），国内无法注册账号",
                OPENAI, null, null,
                "https://openrouter.ai/settings/keys",
                "https://openrouter.ai/settings/credits",
                "https://openrouter.ai/sign-up"));
        // Mistral：MistralAiChatModel 不用 endpoint，留 null
        list.add(new PickerOption("Mistral", "多语言表现佳，有免费额度，国内无法正常访问",
                OPENAI, null, null,
                "https://console.mistral.ai/api-keys/",
                "https://console.mistral.ai/billing/",
                "https://console.mistral.ai/"));
        list.add(new PickerOption(CUSTOM_LABEL,
                "接入你自己使用的 AI API，仅限 OpenAI 接口", OPENAI, null, null, "", "", ""));
        return list;
    }

    /**
     * 按界面语言排好的列表项。
     *
     * <p><b>简体中文</b>：按「国内能不能用」分成三档——
     * <ol>
     *   <li>国内可直接用：DeepSeek、智谱；</li>
     *   <li>国内用不了：GROQ、ChatGPT、Gemini、OpenRouter、Mistral；</li>
     *   <li>自定义。</li>
     * </ol>
     * 组内保持 {@link #pickerTemplate()} 里的相对顺序。
     *
     * <p><b>判断标准是「能不能走完 注册 → 拿 Key → 调用」整条链路</b>，
     * 而不是某一次接口探活通不通。第二档里各家的断点其实并不一样：
     * <ul>
     *   <li>GROQ：Cloudflare 按 {@code loc=CN} 在边缘 403，API 直接调不通；</li>
     *   <li>OpenRouter：页面与 API 都能连，但注册的验证码依赖 {@code www.google.com}，
     *       境内被阻断，于是拿不到账号（见 {@link #pickerTemplate()} 里那一段注释）；</li>
     *   <li>ChatGPT / Gemini / Mistral：控制台与 API 本身也访问不了。</li>
     * </ul>
     * 对玩家来说结果一致：都用不了，所以放在同一档。
     * 早先单独分出来的「很慢但能用」那一档已经取消——慢不等于不能用，
     * 而当时那一档里只剩 OpenRouter 一家，分出来只会误导。
     *
     * <p><b>其它语言</b>：保持 {@link #pickerTemplate()} 的原始顺序
     * （GROQ 在第 2 位），不做任何调整。
     *
     * @param simplifiedChinese 界面语言是否是简体中文
     */
    public static List<PickerOption> pickerOptions(boolean simplifiedChinese) {
        List<PickerOption> all = pickerTemplate();
        // 非简中就是模板原样。复制一份再返回，避免调用方改动时污染后续查询
        if (!simplifiedChinese) return new ArrayList<>(all);

        PickerOption placeholder = all.get(0);
        // 组 1：国内可直接用 —— 实测百毫秒级，注册与调用都走得通
        List<PickerOption> local = List.of(all.get(2), all.get(3));          // DeepSeek, 智谱
        // 组 2：国内用不了 —— 断点各不相同，但结果都是拿不到可用的 Key / 调不通
        List<PickerOption> blocked = List.of(all.get(1), all.get(4), all.get(5), all.get(6), all.get(7));
        //                                                                     GROQ, ChatGPT, Gemini, OpenRouter, Mistral
        // 组 3：自定义
        List<PickerOption> custom = List.of(all.get(8));

        List<PickerOption> options = new ArrayList<>();
        options.add(placeholder);
        options.addAll(local);
        options.addAll(blocked);
        options.addAll(custom);
        return options;
    }

    /** 按显示名找回列表里的一项；找不到时返回 {@code null}。 */
    public static PickerOption pickerOption(String label) {
        if (label == null) return null;
        for (PickerOption option : pickerTemplate()) {
            if (option.label().equals(label)) return option;
        }
        return null;
    }

    // ========== 第 2 步：用刚选的提供商去查 Key 相关地址 ==========

    /**
     * 第 2 步要用的那一项：把第 1 步选中的显示名换算成 {@link PickerOption}。
     * <p>选的是「自定义」或者还没选时返回 {@code null}，调用方据此隐藏
     * 「获取 API Key」这类预置提供商才有的入口。
     */
    public static PickerOption selectedOption(String pickedLabel) {
        PickerOption option = pickerOption(pickedLabel);
        if (option == null || option.isCustom() || option.isPlaceholder()) return null;
        return option;
    }

    /**
     * 从 Verity 配置反推出玩家上次选的是哪一项。
     *
     * <p>引导的选择原本只活在内存里，重启游戏就没了——第 1 步会退回
     * 「（请选择）」，第 2 步因为拿不到 Base URL 而永远报「无法判断」。
     * 但配置本身一直在，所以重启后可以从 {@code aiProvider} + {@code aiEndpoint}
     * 倒推出对应的那一项。
     *
     * <p><b>OPENAI 路径要特别小心。</b> DeepSeek / 智谱 / ChatGPT / OpenRouter /
     * Mistral / 自定义在配置里都是 {@code aiProvider = OPENAI}，只能靠
     * endpoint 区分。其中 OpenRouter 与 Mistral 我们故意不写 endpoint（Verity
     * 不读它），于是它们的配置与「自定义」几乎一样——不能靠「有没有 endpoint」
     * 判断，否则 Mistral 和自定义都会被错认成 OpenRouter。
     *
     * <p>匹配顺序：
     * <ol>
     *   <li>endpoint 与某一项<b>完全相等</b> —— DeepSeek / 智谱 / ChatGPT 走这条；</li>
     *   <li>endpoint 为空：只可能是 OpenRouter 或 Mistral（这两家都不写），
     *       优先按提供商标识判断，OPENAI 路径下再按<b>无法区分</b>处理；</li>
     *   <li>endpoint 非空但对不上任何预置值 → 自定义；</li>
     *   <li>非 OPENAI 路径（GEMINI / GROQ）直接按枚举值认。</li>
     * </ol>
     *
     * @param provider {@code verity-common.toml} 里的 {@code aiProvider}
     * @param endpoint {@code verity-common.toml} 里的 {@code aiEndpoint}
     * @return 能对应上的列表项显示名；完全无法判断时返回 {@code null}
     */
    public static String inferPickedLabel(String provider, String endpoint) {
        if (provider == null || provider.isBlank()) return null;
        String id = provider.trim();
        String url = endpoint == null ? "" : endpoint.trim();

        // 1) endpoint 与某一项完全相等：这是唯一能区分 DeepSeek/智谱/ChatGPT 的依据
        if (!url.isEmpty()) {
            for (PickerOption option : pickerTemplate()) {
                if (option.isPlaceholder()) continue;
                if (!id.equals(option.provider())) continue;
                if (url.equals(option.endpoint())) return option.label();
            }
            // 早期版本给 OpenRouter / Mistral 写过 endpoint（后来改成不写），
            // 配置里可能还留着这些地址。它们不是「自定义」，玩家没填过东西——
            // 认成自定义等于把玩家手打的地址看成一个他没填过的空壳
            if (OPENAI.equals(id) && isLegacyBuiltinEndpoint(url)) {
                Log.debug("[Providers] LEGACY_ENDPOINT_IGNORED | URL=" + url);
                return null;
            }
            // 2) 有 endpoint 但对不上任何预置值 → 玩家自己填的
            if (OPENAI.equals(id)) return CUSTOM_LABEL;
        }

        // 3) endpoint 为空：只可能是不写 endpoint 的那几家
        for (PickerOption option : pickerTemplate()) {
            if (option.isPlaceholder()) continue;
            if (!id.equals(option.provider())) continue;
            if (option.endpoint() != null) continue;
            // OPENAI 路径下有多个候选（OpenRouter / Mistral），无法从配置区分；
            // 取列表中靠前的那个不如返回 null 让玩家重选，免得替他猜错一家
            if (OPENAI.equals(id)) return null;
            return option.label();
        }

        return null;
    }

    /** 该提供商在 Verity 侧是否需要玩家填 endpoint（只有 OPENAI 路径需要）。 */
    public static boolean needsEndpoint(String provider) {
        return OPENAI.equals(provider);
    }

    /**
     * 这个地址是不是「以前被我们写进配置、现在不该写了」的内建地址。
     * <p>只认 Verity 内部写死的那几家（OpenRouter / Mistral），
     * 玩家真填了这个值当自定义也无所谓——反正结论都是「无法从配置判断，重选」。
     */
    private static boolean isLegacyBuiltinEndpoint(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("/")) lower = lower.substring(0, lower.length() - 1);
        return lower.equals("https://openrouter.ai/api/v1")
                || lower.equals("https://api.mistral.ai/v1");
    }
}
