package com.cb2495.verityplus.config;

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
     * <p>每项的末尾三个地址是第 2 步（填 API Key）要用的跳转目标，
     * 顺序固定为：新建 Key / 充值 / 注册。
     */
    private static List<PickerOption> pickerTemplate() {
        List<PickerOption> list = new ArrayList<>();
        list.add(new PickerOption(PICKER_PLACEHOLDER, "选择一个你中意的 AI 提供商",
                null, null, null, "", "", ""));
        list.add(new PickerOption("GROQ", "试玩首选（仅国外），极速输出，有免费额度，国内可能无法稳定访问",
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
        // 地址用 OpenAI 官方的兼容端点：ChatGPT 没有独立的 API 域名，
        // 平台上的 /v1 就是它的 Base URL
        list.add(new PickerOption("ChatGPT", "能力顶尖，功能强大，价格偏高，国内无法正常访问",
                OPENAI, "https://api.openai.com/v1", null,
                "https://platform.openai.com/api-keys",
                "https://platform.openai.com/settings/organization/billing/overview",
                "https://platform.openai.com/signup"));
        list.add(new PickerOption("Gemini", "聪慧（？），有免费额度，国内无法正常访问",
                GEMINI, "https://generativelanguage.googleapis.com/v1beta", "已切换为 Gemini，但模型尚未配置",
                "https://aistudio.google.com/app/apikey",
                "https://aistudio.google.com/app/billing",
                "https://aistudio.google.com/"));
        list.add(new PickerOption("OpenRouter", "一个模型集合中转站，聚合大量模型（含免费模型），国内可能无法稳定访问",
                OPENAI, "https://openrouter.ai/api/v1", null,
                "https://openrouter.ai/settings/keys",
                "https://openrouter.ai/settings/credits",
                "https://openrouter.ai/sign-up"));
        list.add(new PickerOption("Mistral", "多语言表现佳，有免费额度，国内无法正常访问",
                OPENAI, "https://api.mistral.ai/v1", null,
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
     * <p><b>简体中文</b>：按「国内可直接用」的梯度分组排序——
     * <ol>
     *   <li>国内提供商：DeepSeek、智谱；</li>
     *   <li>国内可能无法稳定访问：GROQ、OpenRouter；</li>
     *   <li>国内无法正常访问：ChatGPT、Gemini、Mistral；</li>
     *   <li>自定义。</li>
     * </ol>
     * 组内保持 {@link #pickerTemplate()} 里的相对顺序。
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
        // 组 1：国内可直接用
        List<PickerOption> local = List.of(all.get(2), all.get(3));          // DeepSeek, 智谱
        // 组 2：国内可能无法稳定访问
        List<PickerOption> unstable = List.of(all.get(1), all.get(6));       // GROQ, OpenRouter
        // 组 3：国内无法正常访问
        List<PickerOption> blocked = List.of(all.get(4), all.get(5), all.get(7)); // ChatGPT, Gemini, Mistral
        // 组 4：自定义
        List<PickerOption> custom = List.of(all.get(8));

        List<PickerOption> options = new ArrayList<>();
        options.add(placeholder);
        options.addAll(local);
        options.addAll(unstable);
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
}
