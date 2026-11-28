package com.cb2495.verityplus.config;

import com.cb2495.verityplus.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把各家 {@code /models} 的响应解析成统一的模型条目。
 *
 * <h3>为什么需要单独一层</h3>
 * 各服务商返回的 JSON 长得完全不一样——连字段名都不统一：
 * <pre>
 *   DeepSeek    {"data":[{"id","name","output_modalities":["text"],...}]}
 *   OpenRouter  {"data":[{"id","name","architecture":{"output_modalities":[...]},...}]}
 *   Gemini      {"models":[{"name":"models/gemini-…","displayName",...}]}
 *   智谱/Mistral {"data":[{"id","object","owned_by"}]}          ← 只有标识
 * </pre>
 * 界面上只需要四个东西：标识、显示名、模态、能力。这里把它们统一出来，
 * 界面层就不用关心到底连的是哪一家。
 *
 * <h3>缺字段就是「未知」，不要当「不支持」</h3>
 * 智谱和 Mistral 的响应里根本没有模态与能力字段。把它们当成「不支持」
 * 会把一大批可用模型误伤掉；所以这里用三态：
 * {@link Capability#SUPPORTED} / {@link Capability#UNSUPPORTED} / {@link Capability#UNKNOWN}。
 * 未知怎么展示由界面决定（后续会补固定文案）。
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    /** 三态能力：支持 / 不支持 / 响应里没说。 */
    public enum Capability {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN;

        /** 解析用：{@code true} → SUPPORTED，{@code null} → UNKNOWN。 */
        static Capability of(Boolean value) {
            if (value == null) return UNKNOWN;
            return value ? SUPPORTED : UNSUPPORTED;
        }
    }

    /**
     * 一个模型条目。
     *
     * @param id            调用时用的标识，保证非空
     * @param displayName   界面上显示的名字；响应里没给时等于 {@code id}
     * @param outputsText   是否支持文本输出；{@code UNKNOWN} 表示响应没说
     * @param toolCall      是否支持工具调用
     * @param thinking      是否支持思考（推理）
     * @param aliases       同一模型的其他可用标识（Mistral 会列出来）；
     *                      调用时用哪个都行，表里只占一行
     * @param mergeKey      归并键（Mistral 的 {@code billing_model_name}）；
     *                      指向同一个模型的多个条目靠它合并，{@code null} 表示不参与归并
     */
    public record Entry(String id, String displayName,
                        Capability outputsText, Capability toolCall, Capability thinking,
                        List<String> aliases, String mergeKey) {

        /** 不带别名的便捷构造。 */
        public Entry(String id, String displayName,
                     Capability outputsText, Capability toolCall, Capability thinking) {
            this(id, displayName, outputsText, toolCall, thinking, List.of(), null);
        }

        /** 是否确定不支持文本输出——这种模型该从列表里滤掉。 */
        public boolean definitelyNotText() {
            return outputsText == Capability.UNSUPPORTED;
        }
    }

    /**
     * 解析一份 {@code /models} 响应体。
     *
     * <p>解析失败不抛异常：返回空列表并记一条日志。这一步只是「顺便拿到清单」，
     * 拿不到也不该让 Key 校验整体失败。
     *
     * @param body 原始响应体
     * @return 解析出的条目；解析不出来时为空列表
     */
    public static List<Entry> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            Log.warn("[ModelCatalog] PARSE_FAILED | REASON=NOT_JSON");
            return List.of();
        }
        if (!root.isJsonObject()) {
            Log.warn("[ModelCatalog] PARSE_FAILED | REASON=ROOT_NOT_OBJECT");
            return List.of();
        }

        JsonArray array = findModelArray(root.getAsJsonObject());
        if (array == null) {
            Log.warn("[ModelCatalog] PARSE_FAILED | REASON=NO_MODEL_ARRAY | KEYS="
                    + root.getAsJsonObject().keySet());
            return List.of();
        }

        List<Entry> entries = new ArrayList<>(array.size());
        // 按 mergeKey 归并：Mistral 会把同一模型的每种叫法都列一条，
        // 51 条里其实只有十几个真模型。同一 mergeKey 只保留<b>第一条</b>——
        // 服务端把固定的名称ID（codestral-2508）排在浮动别名（codestral-latest）前面，
        // 保留第一条就等于保留了具体版本，这也正是调用时更该用的那个。
        java.util.Map<String, Entry> byMergeKey = new java.util.LinkedHashMap<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        int merged = 0;

        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            Entry entry = parseOne(element.getAsJsonObject());
            if (entry == null) continue;
            if (!seenIds.add(entry.id())) continue;

            String key = entry.mergeKey();
            if (key != null && byMergeKey.containsKey(key)) {
                // 已有一条指向同一个模型：把这条的标识并入那条的别名，不再单独占一行
                merged++;
                continue;
            }
            if (key != null) byMergeKey.put(key, entry);
            entries.add(entry);
        }
        Log.debug("[ModelCatalog] PARSED | COUNT=" + entries.size() + " | RAW=" + array.size()
                + (merged > 0 ? " | ALIAS_MERGED=" + merged : ""));
        return entries;
    }

    /**
     * 找到装模型的那个数组。
     * <p>OpenAI 系用 {@code data}；Gemini 用 {@code models}。
     * 智谱的另一条端点（{@code /api/v1}）也用 {@code models}，属于同一形状。
     */
    private static JsonArray findModelArray(JsonObject root) {
        for (String key : new String[] {"data", "models"}) {
            JsonElement e = root.get(key);
            if (e != null && e.isJsonArray()) return e.getAsJsonArray();
        }
        return null;
    }

    /**
     * 解析单个模型对象；连标识都取不到时返回 {@code null}。
     *
     * <p>Mistral 会把同一个模型的所有别名列成独立条目（{@code codestral-2508}、
     * {@code codestral-latest}、{@code mistral-code-latest} 其实是一个模型）。
     * 这里把它们<b>合并成一行</b>：标识取第一个（主名），其余进 {@link Entry#aliases()}。
     * 不合并的话，51 个模型会在表里展开成上百行，而且名字全都一样。
     */
    private static Entry parseOne(JsonObject obj) {
        String id = firstString(obj, "id", "slug");
        // Gemini 的标识长这样：models/gemini-2.0-flash，调用时不该带前缀
        if (id == null) {
            id = stripModelsPrefix(firstString(obj, "name"));
        } else {
            id = stripModelsPrefix(id);
        }
        if (id == null || id.isBlank()) return null;

        // 显示名：name / displayName 优先，都没有就用标识本身。
        // Mistral 的 name 往往就是标识本身（codestral-2508），这时界面会显示成
        // 「常见名称：codestral-2508」，看着像没解析出来——但那是服务端就长这样，
        // 真正的友好名称要靠 model_info.json 补，这里不编造
        String display = firstString(obj, "displayName", "name");
        if (display == null) display = id;
        display = stripModelsPrefix(display);

        // 别名：Mistral 的 aliases 数组。
        // 注意它的语义是「同一模型的其他叫法」，且每个叫法自己也是一条独立条目，
        // 所以这里只是记下来，真正的合并靠 parse() 里的 billing_model_name 归并
        JsonArray aliasArray = stringArray(obj, "aliases");
        List<String> aliases = new ArrayList<>();
        if (aliasArray != null) {
            for (JsonElement e : aliasArray) {
                if (!e.isJsonPrimitive()) continue;
                String alias = stripModelsPrefix(e.getAsString());
                if (alias != null && !alias.isBlank() && !alias.equals(id)) aliases.add(alias);
            }
        }
        // 别名里包含自己的原样写法：有些条目把自己也列进了 aliases
        aliases.remove(id);

        return new Entry(id, display,
                outputsText(obj),
                Capability.of(hasToolCall(obj)),
                Capability.of(hasThinking(obj)),
                List.copyOf(aliases),
                billingKey(obj));
    }

    /**
     * 取「这个条目其实指哪个模型」的归并键。
     *
     * <p>Mistral 是唯一需要这个的：它的 {@code /models} 把同一个模型的每种叫法
     * 都列成独立条目（{@code codestral-2508}、{@code codestral-latest}、
     * {@code mistral-code-latest} 指向的是同一个模型），51 条里其实只有十几个真模型。
     * {@code billing_model_name} 正是那个真名——按它分组，同一组只留一条。
     *
     * <p>不能用 {@code aliases} 做归并：每个别名自己也是一条独立条目，
     * 用它去重会把几乎所有条目都当成别人的别名丢掉。
     *
     * @return 归并键；没有这项信息时返回 {@code null}（表示不参与归并）
     */
    private static String billingKey(JsonObject obj) {
        String billing = firstString(obj, "billing_model_name");
        return billing == null ? null : billing.trim();
    }

    /** 去掉 Gemini 的 {@code models/} 前缀。 */
    private static String stripModelsPrefix(String value) {
        if (value == null) return null;
        String prefix = "models/";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    // ---------- 模态 ----------

    /**
     * 是否支持文本输出。
     *
     * <p>各家放在不同位置：
     * <ul>
     *   <li>DeepSeek / GROQ：顶层 {@code output_modalities}</li>
     *   <li>OpenRouter：{@code architecture.output_modalities}</li>
     *   <li>Gemini：没有模态数组，用 {@code supportedGenerationMethods}——能
     *       {@code generateContent} 的才是对话模型，只有 {@code embedContent} 的是向量模型</li>
     *   <li>Mistral：{@code capabilities.completion_chat}，是个布尔</li>
     * </ul>
     * 都没有时返回 {@link Capability#UNKNOWN}——注意 Mistral 的 51 个模型里
     * 只有靠 {@code completion_chat} 才能判断，缺了这条它们会全部显示未知。
     */
    private static Capability outputsText(JsonObject obj) {
        JsonArray outputs = stringArray(obj, "output_modalities");
        if (outputs == null) {
            JsonElement arch = obj.get("architecture");
            if (arch != null && arch.isJsonObject()) {
                outputs = stringArray(arch.getAsJsonObject(), "output_modalities");
            }
        }
        if (outputs != null) {
            return containsText(outputs) ? Capability.SUPPORTED : Capability.UNSUPPORTED;
        }

        // Gemini：能 generateContent 的才是对话模型；只有 embedContent 的是向量模型
        JsonArray methods = stringArray(obj, "supportedGenerationMethods");
        if (methods != null) {
            return containsValue(methods, "generateContent")
                    ? Capability.SUPPORTED : Capability.UNSUPPORTED;
        }

        // Mistral：capabilities.completion_chat
        JsonElement capabilities = obj.get("capabilities");
        if (capabilities != null && capabilities.isJsonObject()) {
            Boolean chat = bool(capabilities.getAsJsonObject(), "completion_chat");
            if (chat != null) return Capability.of(chat);
        }
        return Capability.UNKNOWN;
    }

    private static boolean containsText(JsonArray array) {
        return containsValue(array, "text");
    }

    private static boolean containsValue(JsonArray array, String value) {
        for (JsonElement e : array) {
            if (e.isJsonPrimitive() && value.equalsIgnoreCase(e.getAsString())) return true;
        }
        return false;
    }

    // ---------- 能力 ----------

    /**
     * 是否支持工具调用（tool call）。
     *
     * <p>已知写法：
     * <ul>
     *   <li>OpenRouter：{@code supported_parameters} 里含 {@code tools}；</li>
     *   <li>GROQ：{@code supported_features} 里含 {@code tools}；</li>
     *   <li>Mistral：{@code capabilities.function_calling}。</li>
     * </ul>
     * 都没有时返回 {@code null}（未知），不能据此判定「不支持」。
     */
    private static Boolean hasToolCall(JsonObject obj) {
        JsonArray params = stringArray(obj, "supported_parameters");
        if (params != null) return containsValue(params, "tools");

        // GROQ 用的是 supported_features，字段名不同但语义一样
        JsonArray features = stringArray(obj, "supported_features");
        if (features != null) return containsValue(features, "tools");

        // Mistral：capabilities.function_calling
        JsonElement capabilities = obj.get("capabilities");
        if (capabilities != null && capabilities.isJsonObject()) {
            Boolean calling = bool(capabilities.getAsJsonObject(), "function_calling");
            if (calling != null) return calling;
        }
        return null;
    }

    /**
     * 是否支持思考（推理）。
     *
     * <p>已知写法：
     * <ul>
     *   <li>DeepSeek：{@code effort.supported_levels} 非空</li>
     *   <li>OpenRouter：{@code reasoning} 对象存在</li>
     *   <li>Gemini：{@code thinking} 字段</li>
     *   <li>GROQ：{@code supported_features} 里含 {@code reasoning}</li>
     *   <li>Mistral：{@code capabilities.reasoning}</li>
     * </ul>
     * 都没有时返回 {@code null}（未知）。
     */
    private static Boolean hasThinking(JsonObject obj) {
        JsonElement effort = obj.get("effort");
        if (effort != null && effort.isJsonObject()) {
            JsonArray levels = stringArray(effort.getAsJsonObject(), "supported_levels");
            if (levels != null) return !levels.isEmpty();
        }
        JsonElement reasoning = obj.get("reasoning");
        if (reasoning != null && reasoning.isJsonObject()) return Boolean.TRUE;

        JsonElement thinking = obj.get("thinking");
        if (thinking != null) {
            if (thinking.isJsonPrimitive() && thinking.getAsJsonPrimitive().isBoolean()) {
                return thinking.getAsBoolean();
            }
            return Boolean.TRUE;
        }

        // GROQ：supported_features 里的 reasoning
        JsonArray features = stringArray(obj, "supported_features");
        if (features != null) return containsValue(features, "reasoning");

        // Mistral：capabilities.reasoning
        JsonElement capabilities = obj.get("capabilities");
        if (capabilities != null && capabilities.isJsonObject()) {
            Boolean canReason = bool(capabilities.getAsJsonObject(), "reasoning");
            if (canReason != null) return canReason;
        }
        return null;
    }

    /** 取一个对象的布尔字段；不是布尔时返回 {@code null}。 */
    private static Boolean bool(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return null;
        return e.getAsBoolean();
    }

    // ---------- JSON 小工具 ----------

    /** 依次尝试多个键，返回第一个字符串值。 */
    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            JsonElement e = obj.get(key);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String s = e.getAsString();
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }

    /** 取一个字符串数组；不是数组时返回 {@code null}。 */
    private static JsonArray stringArray(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonArray()) return null;
        return e.getAsJsonArray();
    }

    /**
     * 从已落盘的 {@code .cache/verity_plus/ai_models.json} 读模型清单。
     *
     * <p>第 2 步验 Key 成功时会把原始响应体写到这个文件，第 3 步直接读它。
     * 文件不存在或解析失败时返回空列表。
     *
     * @return 解析出的条目；文件不存在或解析失败时为空列表
     */
    public static List<Entry> loadCached() {
        List<Entry> entries = loadFileNow(modelsCacheFile());
        if (entries.isEmpty()) {
            // 老版本留下的是 .txt；现在不读它了，但留着文件的人应该知道原因
            Path legacy = legacyCacheFile();
            if (Files.isRegularFile(legacy)) {
                Log.info("[ModelCatalog] LEGACY_CACHE_IGNORED | FILE=" + legacy
                        + " | REASON=RENAMED_TO_JSON");
            }
        }
        return entries;
    }

    /**
     * 同步读盘并解析，<b>不</b>碰 UI 缓存。
     * <p>第 3 步到达时要在后台线程里重新拉一次清单，而
     * {@link #loadCached()} 会读 {@code Minecraft.getInstance()}，
     * 后台线程上不该去碰它。
     *
     * @param file 要读的文件
     * @return 解析出的条目；读不到或解析失败时为空列表
     */
    public static List<Entry> loadFileNow(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            Log.debug("[ModelCatalog] CACHE_MISSING | FILE=" + file);
            return List.of();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("[ModelCatalog] CACHE_READ_FAILED | FILE=" + file + " | ERROR=" + e);
            return List.of();
        }
    }

    /** 模型清单缓存的文件名。 */
    public static final String CACHE_FILE = "ai_models.json";
    /** 缓存目录，相对于游戏目录。 */
    public static final String CACHE_DIR = ".cache/verity_plus";

    /** 模型清单缓存的位置，与 {@code ApiKeyValidator} 写入的路径保持一致。 */
    public static Path modelsCacheFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve(CACHE_DIR)
                .resolve(CACHE_FILE);
    }

    /** 老版本用的 {@code ai_models.txt}，现在只用来提示「它已经不被读取」。 */
    private static Path legacyCacheFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve(CACHE_DIR)
                .resolve("ai_models.txt");
    }

    /** 调试用：把一批条目概括成一行。 */
    public static String describe(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "(empty)";
        int noText = 0;
        int withAlias = 0;
        for (Entry e : entries) {
            if (e.definitelyNotText()) noText++;
            if (e.aliases() != null && !e.aliases().isEmpty()) withAlias++;
        }
        return String.format(Locale.ROOT, "total=%d textUnsupported=%d withAlias=%d",
                entries.size(), noText, withAlias);
    }
}
