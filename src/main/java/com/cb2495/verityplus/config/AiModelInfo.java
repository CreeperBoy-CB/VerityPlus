package com.cb2495.verityplus.config;

import com.cb2495.verityplus.util.Log;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 人工收集的模型信息（「AI 信息」）。
 *
 * <h3>它补的是 {@code /models} 里没有的东西</h3>
 * 服务商的 {@code /models} 只保证给出<b>标识</b>，其余多半是空的：
 * 智谱与 Mistral 连模态和工具调用都不返回，价格则谁都不返回。
 * 这些信息只能人工整理，随模组一起打进去（{@code assets/verityplus/model_info.json}）。
 *
 * <h3>为什么不能把这些信息写死进代码</h3>
 * 价格与常见名称会变，更新它们不该动逻辑。放在资源文件里，改完重新打包即可，
 * 也不必重新读一遍代码确认哪里写了什么。
 *
 * <h3>与 {@link ModelCatalog} 的分工</h3>
 * <ul>
 *   <li>{@link ModelCatalog} —— 回答「这个账号能调哪些模型」，来源是实时接口；</li>
 *   <li>本类 —— 回答「这个模型是什么、大概什么价」，来源是人工整理的静态表。</li>
 * </ul>
 * 合并规则在 {@link ModelRow} 里：两边都有时以本表为准，本表没有才看接口。
 *
 * <h3>文件格式</h3>
 * <pre>
 * {
 *   "schema": 2,
 *   "order": {"DEEPSEEK": ["deepseek-flash", "deepseek-v4-pro"]},
 *   "models": {
 *     "deepseek-flash": {
 *       "common": "DeepSeek V4.1 Flash",
 *       "tool": true,
 *       "thinking": true,
 *       "price": {"input": 2, "output": 8, "cache": 0.04, "peak": {...}, "note": "..."}
 *     }
 *   },
 *   "aliases": {"deepseek-v4-flash": "deepseek-flash"},
 *   "rules": [
 *     {"provider": "GROQ", "prefix": "llama-", "tool": true}
 *   ]
 * }
 * </pre>
 *
 * <h3>标识必须按「最具体」来写</h3>
 * 同一个模型在 DeepSeek / OpenRouter / 智谱 三家都叫 {@code glm-5.3-flash}，
 * 但价格并不一样（智谱的免费、中转站要加价）。所以 {@code models} 的键
 * 允许写成 {@code <提供商>/<标识>}，查表时先看带前缀的，再退回不带前缀的。
 */
public final class AiModelInfo {

    private AiModelInfo() {}

    /** 打进 jar 的资源路径。 */
    private static final String RESOURCE = "/assets/verityplus/model_info.json";

    /**
     * 价格，单位是人民币／百万 token。
     *
     * @param input  输入（缓存未命中）
     * @param output 输出
     * @param cache  输入（缓存命中）；{@code null} 表示这家没有缓存价
     */
    public record Price(Double input, Double output, Double cache) {

        /** 有没有可以用来排序的输入价。 */
        public boolean hasInput() {
            return input != null;
        }

        /** 是不是完全没信息。 */
        public boolean isEmpty() {
            return input == null && output == null && cache == null;
        }

        /** 按 {@code 输入/输出/缓存} 拼成一行；缺的写「?」。 */
        public String compact() {
            return value(input) + "/" + value(output) + "/" + value(cache);
        }

        private static String value(Double v) {
            return v == null ? "?" : format(v);
        }
    }

    /** 高峰时段价格；没有分时计费时为 {@code null}。 */
    public record Peak(Price price, String note) {}

    /**
     * 一个模型的人工信息。任何一项都可能是 {@code null}／{@code null} 布尔，
     * 表示「这一项我们不知道」——不能用 {@code false} 代替。
     *
     * @param common   常见名称；{@code null} 表示没有收录
     * @param tool     是否支持工具调用；{@code null} 表示未知
     * @param thinking 是否支持深度思考；{@code null} 表示未知
     * @param price    标准价（非分时计费时就是唯一价）
     * @param peak     高峰时段价（分时计费才有）
     */
    public record Model(String common, Boolean tool, Boolean thinking, Price price, Peak peak) {}

    /**
     * 按前缀推断能力的规则，用于 {@code /models} 里一长串没有能力字段的模型。
     * <p>只在<b>前两级都没答案</b>时才用，不会盖掉明确写过的值。
     *
     * @param provider 限定提供商；{@code null} 表示任何提供商
     * @param prefix   标识前缀，例如 {@code llama-}
     */
    public record Rule(String provider, String prefix, Boolean tool, Boolean thinking) {}

    // ---------- 载入 ----------

    private static Map<String, Model> models = Map.of();
    private static Map<String, String> aliases = Map.of();
    private static Map<String, List<String>> order = Map.of();
    private static List<Rule> rules = List.of();
    /** 按标识前缀判定「不是对话模型」的规则。 */
    private static List<String> nonTextPrefixes = List.of();
    /**
     * 按标识<b>包含</b>片段判定「不是对话模型」的规则。
     * <p>有些命名把用途放在中间：{@code gemini-2.5-flash-preview-tts}、
     * {@code gpt-4o-mini-tts-2025-03-20}。只看开头的 {@link #nonTextPrefixes}
     * 会漏掉它们。
     */
    private static List<String> nonTextContains = List.of();
    private static volatile boolean loaded = false;

    /** 载入静态表；失败时留下空表，界面会把一切显示成「未知」。 */
    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = AiModelInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Log.warn("[AiModelInfo] RESOURCE_MISSING | PATH=" + RESOURCE);
                return;
            }
            parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.error("[AiModelInfo] LOAD_FAILED | ERROR=" + e);
        }
    }

    /** 解析整份 JSON。任何一段坏掉都只丢那一段，不影响其余。 */
    private static void parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        Map<String, Model> parsedModels = new LinkedHashMap<>();
        JsonObject modelsObj = object(root, "models");
        if (modelsObj != null) {
            for (Map.Entry<String, JsonElement> e : modelsObj.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                Model model = parseModel(e.getValue().getAsJsonObject());
                if (model != null) parsedModels.put(e.getKey(), model);
            }
        }

        Map<String, String> parsedAliases = new LinkedHashMap<>();
        JsonObject aliasObj = object(root, "aliases");
        if (aliasObj != null) {
            for (Map.Entry<String, JsonElement> e : aliasObj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) parsedAliases.put(e.getKey(), e.getValue().getAsString());
            }
        }

        Map<String, List<String>> parsedOrder = new LinkedHashMap<>();
        JsonObject orderObj = object(root, "order");
        if (orderObj != null) {
            for (Map.Entry<String, JsonElement> e : orderObj.entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                List<String> list = new ArrayList<>();
                for (JsonElement item : e.getValue().getAsJsonArray()) {
                    if (item.isJsonPrimitive()) list.add(item.getAsString());
                }
                parsedOrder.put(e.getKey(), list);
            }
        }

        List<Rule> parsedRules = new ArrayList<>();
        JsonElement rulesEl = root.get("rules");
        if (rulesEl != null && rulesEl.isJsonArray()) {
            for (JsonElement item : rulesEl.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject o = item.getAsJsonObject();
                String prefix = string(o, "prefix");
                if (prefix == null || prefix.isBlank()) continue;
                parsedRules.add(new Rule(string(o, "provider"), prefix,
                        bool(o, "tool"), bool(o, "thinking")));
            }
        }

        List<String> parsedNonText = new ArrayList<>();
        JsonElement nonTextEl = root.get("nonTextPrefixes");
        if (nonTextEl != null && nonTextEl.isJsonArray()) {
            for (JsonElement item : nonTextEl.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    String prefix = item.getAsString();
                    if (!prefix.isBlank()) parsedNonText.add(prefix.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<String> parsedContains = new ArrayList<>();
        JsonElement containsEl = root.get("nonTextContains");
        if (containsEl != null && containsEl.isJsonArray()) {
            for (JsonElement item : containsEl.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    String frag = item.getAsString();
                    if (!frag.isBlank()) parsedContains.add(frag.toLowerCase(Locale.ROOT));
                }
            }
        }

        models = parsedModels;
        aliases = parsedAliases;
        order = parsedOrder;
        rules = parsedRules;
        nonTextPrefixes = parsedNonText;
        nonTextContains = parsedContains;
        Log.info("[AiModelInfo] LOADED | MODELS=" + parsedModels.size()
                + " | ALIASES=" + parsedAliases.size()
                + " | ORDER_GROUPS=" + parsedOrder.size()
                + " | RULES=" + parsedRules.size()
                + " | NON_TEXT=" + parsedNonText.size()
                + " | NON_TEXT_CONTAINS=" + parsedContains.size());
    }

    /**
     * 按标识判断这个模型是不是<b>明显不能对话</b>的（向量、语音、图像、审核…）。
     *
     * <p>用于 OpenAI、Gemini 这类「{@code /models} 只给标识、不给模态」的服务商：
     * 它们的列表里混着几十个 {@code text-embedding-*}、{@code gpt-image-*}、
     * {@code gpt-realtime-*}，全列出来会让玩家在一堆没用的条目里找可用模型。
     *
     * <p>先看前缀（覆盖绝大多数），再看包含片段（覆盖
     * {@code gemini-2.5-flash-preview-tts} 这种把用途放在中间的命名）。
     *
     * <p>只认<b>服务商命名约定里非常明确</b>的特征，宁漏不误：
     * 判错的代价是把一个能用的对话模型藏起来，比多显示几条更糟。
     */
    public static boolean looksNonText(String id) {
        if (id == null || id.isBlank()) return false;
        ensureLoaded();
        String lower = id.toLowerCase(Locale.ROOT);
        for (String prefix : nonTextPrefixes) {
            if (lower.startsWith(prefix)) return true;
        }
        for (String fragment : nonTextContains) {
            if (lower.contains(fragment)) return true;
        }
        return false;
    }

    private static Model parseModel(JsonObject obj) {
        return new Model(
                string(obj, "common"),
                bool(obj, "tool"),
                bool(obj, "thinking"),
                parsePrice(obj.get("price")),
                parsePeak(obj.get("peak")));
    }

    /** 价格：既接受 {@code {input,output,cache}} 对象，也接受 {@code "1.5/1.0/0.4"} 字符串。 */
    private static Price parsePrice(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            double[] nums = parseNumbers(el.getAsString());
            return new Price(num(nums, 0), num(nums, 1), num(nums, 2));
        }
        if (!el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        Price price = new Price(dbl(o, "input"), dbl(o, "output"), dbl(o, "cache"));
        return price.isEmpty() ? null : price;
    }

    private static Peak parsePeak(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        Price price = parsePrice(o.get("price"));
        if (price == null) return null;
        return new Peak(price, string(o, "note"));
    }

    /** {@code "1.5/1.0/0.4"} → {@code [1.5, 1.0, 0.4]}；解析不了的位留 {@code NaN}。 */
    private static double[] parseNumbers(String text) {
        String[] parts = text.split("/");
        double[] out = {Double.NaN, Double.NaN, Double.NaN};
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // 留 NaN，下面 num() 会转成 null
            }
        }
        return out;
    }

    private static Double num(double[] nums, int index) {
        if (index >= nums.length) return null;
        return Double.isNaN(nums[index]) ? null : nums[index];
    }

    // ---------- 查询 ----------

    /**
     * 查一个模型的人工信息。
     *
     * @param provider Verity 侧的提供商标识（{@code OPENAI} / {@code GEMINI} / …）
     * @param id       模型标识
     * @return 查不到时返回 {@code null}
     */
    public static Model lookup(String provider, String id) {
        if (id == null || id.isBlank()) return null;
        ensureLoaded();
        String key = aliases.getOrDefault(id, id);

        // 1) 按传进来的 provider 查
        Model direct = findByKey(provider, key);
        if (direct != null) return direct;

        // 2) 去掉日期后缀再试一次。
        // OpenAI 会同时列出浮动名（gpt-5.2）和固定版本（gpt-5.2-2025-12-11），
        // 两者的价格与名称完全一样，没理由为每个日期各写一条。
        // 只认「-YYYY-MM-DD」这种严格形态，避免把 gpt-5.4-mini 误当成 gpt-5.4
        String stripped = stripDateSuffix(key);
        if (stripped != null) {
            String aliasOfStripped = aliases.getOrDefault(stripped, stripped);
            Model byStripped = findByKey(provider, aliasOfStripped);
            if (byStripped != null) return byStripped;
            key = aliasOfStripped;
        }

        // 3) 按模型标识自己推断该是哪一家。
        //
        // 这一步是必需的：Verity 侧只有 OPENAI / GEMINI / GROQ 三个枚举，
        // DeepSeek、智谱、ChatGPT、Mistral、OpenRouter 在配置里**统统是 OPENAI**。
        // 所以按传进来的 provider 去找 Mistral 的条目永远找不到——
        // 而 {mistral,gpt,gemini}-* 这些前缀本身就能区分是哪一家。
        Model inferred = findByKey(inferProvider(key), key);
        if (inferred != null) return inferred;

        // 4) 最后退回不带提供商的键（DeepSeek / 智谱 就是这么存的）
        return models.get(key);
    }

    /**
     * 按模型标识推断它属于哪一家。
     * <p>只用于「键的前缀」，不改变任何界面逻辑——目的是让 JSON 能以
     * {@code Mistral/xxx} 这样可读的形式存储，而不必迁就 Verity 的枚举值。
     *
     * @param id 模型标识
     * @return 推断出的前缀；看不出来时返回 {@code null}
     */
    private static String inferProvider(String id) {
        if (id == null) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mistral-") || lower.startsWith("ministral-")
                || lower.startsWith("codestral-") || lower.startsWith("magistral-")
                || lower.startsWith("devstral-") || lower.startsWith("voxtral-")
                || lower.startsWith("pixtral-")) {
            return "Mistral";
        }
        if (lower.startsWith("gemini") || lower.startsWith("gemma")) {
            return "Gemini";
        }
        if (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3")
                || lower.startsWith("o4") || lower.startsWith("chatgpt")
                || lower.startsWith("text-embedding") || lower.startsWith("dall-e")
                || lower.startsWith("whisper") || lower.startsWith("tts-")) {
            return "OpenAI";
        }
        if (lower.startsWith("glm")) {
            return "智谱";
        }
        return null;
    }

    /**
     * 先按 {@code <提供商>/<键>} 查，再按不带提供商的键查。
     *
     * <p><b>提供商标识的大小写必须容错。</b> 代码传进来的是 Verity 侧枚举
     * （{@code OPENAI} / {@code GEMINI} / {@code GROQ}，全大写），而 JSON 里为了可读性
     * 写的是 {@code OpenAI/}、{@code Gemini/}。只试「原样 + 小写」的话
     * {@code OPENAI} 对不上 {@code OpenAI}，整张表会全部落空、界面上一排「暂无 / 未知」。
     * 所以这里把大写、小写、原样三种都试一遍。
     */
    private static Model findByKey(String provider, String key) {
        if (provider != null && !provider.isBlank()) {
            Model exact = models.get(provider + "/" + key);
            if (exact != null) return exact;
            Model byLower = models.get(provider.toLowerCase(Locale.ROOT) + "/" + key);
            if (byLower != null) return byLower;
            Model byUpper = models.get(provider.toUpperCase(Locale.ROOT) + "/" + key);
            if (byUpper != null) return byUpper;
        }
        return models.get(key);
    }

    /**
     * 去掉末尾的 {@code -YYYY-MM-DD}；没有这种后缀时返回 {@code null}。
     * <p>用严格形态而不是「最后一个 dash 之前的部分」：
     * {@code gpt-5.4-mini} 必须保留成 {@code gpt-5.4-mini}，
     * 不能因为它以 dash 分隔就被截成 {@code gpt-5.4}。
     */
    private static String stripDateSuffix(String key) {
        java.util.regex.Matcher m = DATE_SUFFIX.matcher(key);
        return m.find() ? key.substring(0, m.start()) : null;
    }

    /** {@code -2025-12-11} 这样的日期后缀。 */
    private static final java.util.regex.Pattern DATE_SUFFIX =
            java.util.regex.Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    /**
     * 按前缀规则推能力。
     * <p>只在前两级都没答案时才会被调用，见 {@link ModelRow#merge}。
     */
    public static Rule ruleFor(String provider, String id) {
        if (id == null) return null;
        ensureLoaded();
        String lower = id.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.provider() != null && !rule.provider().equalsIgnoreCase(provider)) continue;
            if (lower.startsWith(rule.prefix().toLowerCase(Locale.ROOT))) return rule;
        }
        return null;
    }

    /**
     * 本家查不到时，退回同一份表里<b>任何</b>前缀下的同名模型。
     *
     * <p>用途只有一个：通过中转站（OpenRouter）调用时，模型还是那个模型，
     * 价目表照样成立，只是账单由中转站结算。没有这一步的话，
     * 从中转站连进去的 DeepSeek、智谱模型价格会全部显示成「未知」，
     * 而那恰恰是玩家最想比较的场景。
     *
     * <p><b>普通查询不会退化到这一步</b>——见 {@link #lookup}：
     * 直接连智谱时拿 DeepSeek 的通用价去套 {@code glm-*} 是错的。
     * 只有 {@link ModelRow#buildRows} 在确认连的是中转站后才会调用本方法。
     *
     * @return 找到时返回对应条目；没有任何前缀下收录过它时返回 {@code null}
     */
    public static Model inheritedPricing(String id) {
        if (id == null || id.isBlank()) return null;
        ensureLoaded();
        String key = aliases.getOrDefault(id, id);
        Model direct = models.get(key);
        if (direct != null && direct.price() != null) return direct;
        for (Map.Entry<String, Model> e : models.entrySet()) {
            int slash = e.getKey().indexOf('/');
            if (slash < 0) continue;
            if (!e.getKey().substring(slash + 1).equals(key)) continue;
            Model candidate = e.getValue();
            if (candidate != null && candidate.price() != null) return candidate;
        }
        return null;
    }

    /**
     * 这个地址是不是中转站（同一个模型由它代为转发、按它的价结算）。
     * <p>目前只有 OpenRouter：它的 {@code /models} 里混着各家模型，
     * 而价目表是按<b>原厂</b>整理的。
     */
    public static boolean isAggregator(String endpoint) {
        if (endpoint == null) return false;
        return endpoint.toLowerCase(Locale.ROOT).contains("openrouter.ai");
    }

    /**
     * 该提供商下的推荐顺序（{} 里是标识列表）。
     * <p>第 3 步的「默认排序」用它：表里列出的在前、按表的顺序，
     * 表里没有的按标识 A-Z 排在后面。
     *
     * @return 没有为该提供商配顺序时返回空列表
     */
    public static List<String> orderFor(String provider) {
        ensureLoaded();
        List<String> list = order.get(provider);
        return list == null ? List.of() : list;
    }

    // ---------- 显示 ----------

    /**
     * 价格显示成 {@code 1.5/1.0/0.4}。
     * <p>小数位按数值本身决定：8 显示成 {@code 8} 而不是 {@code 8.0}，
     * 0.025 这种小额又不会被截掉。
     */
    public static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.4f", value);
        // 去掉尾部的 0，但至少留一位小数
        while (text.endsWith("0") && !text.endsWith(".0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    // ---------- JSON 小工具 ----------

    private static JsonObject object(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    /** 布尔；缺省或写成 {@code null} 时返回 {@code null}（未知），不是 {@code false}。 */
    private static Boolean bool(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return null;
        return e.getAsBoolean();
    }

    /** 数字；缺省或写成字符串时返回 {@code null}。 */
    private static Double dbl(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 调试用：整份表里有多少条。 */
    public static int size() {
        ensureLoaded();
        return models.size();
    }
}
