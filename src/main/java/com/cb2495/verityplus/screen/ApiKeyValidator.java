package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.config.Providers;
import com.cb2495.verityplus.util.ConfigIO;
import com.cb2495.verityplus.util.Log;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 2 步「填 API Key」的可用性校验。
 *
 * <h3>一次请求两件事</h3>
 * 对 {@code <baseUrl>/models} 发一次 GET，既验证 Key 是否可用，
 * 又顺手拿到模型清单：
 * <ul>
 *   <li>{@code 200} —— Key 可用，响应体就是模型列表，覆盖写入
 *       {@code .cache/verity_plus/ai_models}，留给第 3 步选模型；</li>
 *   <li>{@code 401} —— Key 无效；{@code 402} —— 欠费；</li>
 *   <li>{@code 429} / {@code 5xx} —— 服务器繁忙，可以重试；</li>
 *   <li>其余状态码 —— 说不准；</li>
 *   <li>连不上 —— 本机网络问题。</li>
 * </ul>
 *
 * <h3>Gemini 的鉴权方式不同</h3>
 * Gemini 不认 {@code Authorization: Bearer}，Key 要放在查询串里：
 * {@code /models?key=...}。两者的 {@code /models} 都是公开的列表接口，
 * 所以这个请求对两条路线都成立。
 *
 * <h3>防抖与竞态</h3>
 * 与 {@link EndpointValidator} 同一套做法：停止输入 {@link #DEBOUNCE_MS} 毫秒后
 * 才发请求，每次输入递增一个序号，只有序号仍是最新的结果才会被接受。
 */
final class ApiKeyValidator {

    private ApiKeyValidator() {}

    /** 停止输入多久后开始校验。 */
    static final long DEBOUNCE_MS = 2000L;
    /** 连接与读取超时。给得比地址校验宽一点：这里要把整份 JSON 读完。 */
    static final int TIMEOUT_MS = 8000;
    /** 模型清单最多记多少字节：防止拿一个巨大的响应把内存和磁盘写满。 */
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** 模型清单的落盘位置，相对于游戏目录。 */
    static final String MODELS_DIR = ".cache/verity_plus";
    static final String MODELS_FILE = "ai_models";

    /** 校验结论。 */
    enum Level {
        /** 正在等待输入停止或等待服务器响应。 */
        PENDING,
        /** Key 可用（200）。 */
        OK,
        /** 只是提醒，不拦人（欠费、繁忙、说不准、连不上）。 */
        WARN,
        /** Key 无效（401）。 */
        ERROR
    }

    /**
     * 「[点击此处]」那段的去向。
     * <p>状态码到界面地址的映射不能靠比中文文案来做——文案一改就悄悄断了。
     */
    enum LinkTarget {
        /** 没有链接。 */
        NONE,
        /** 就地重试（繁忙 / 连不上）。 */
        RETRY,
        /** 去该提供商的「新建 API Key」页（Key 无效）。 */
        NEW_KEY,
        /** 去该提供商的「充值」页（余额不足）。 */
        TOP_UP
    }

    /**
     * 一次校验的结果。
     *
     * @param level    结论等级
     * @param message  显示在图标右边的文字
     * @param linkTarget 链接去向
     * @param tailText 后接的「[点击此处]」之后那段文字；{@code null} 表示没有链接
     */
    record Result(Level level, String message, LinkTarget linkTarget, String tailText) {

        /** 绿色的成功结论，没有后续链接。 */
        static Result ok(String message) {
            return new Result(Level.OK, message, LinkTarget.NONE, null);
        }

        /** 带「[点击此处]重新尝试」的结论。 */
        static Result retryable(Level level, String message) {
            return new Result(level, message, LinkTarget.RETRY, "重新尝试");
        }

        /** 带「[点击此处]&lt;去平台&gt;」的结论。 */
        static Result linked(Level level, String message, LinkTarget target, String linkText) {
            return new Result(level, message, target, linkText);
        }

        /**
         * 是否禁止「继续」。
         * <p>只有红（Key 无效）与黄（还在检查）拦人。
         * 橙色的「欠费 / 繁忙 / 说不准 / 连不上」都放行：玩家可能就是在
         * 欠费状态下想先把配置配完，不该被卡死在这一页。
         */
        boolean blocksNext() {
            return level == Level.ERROR || level == Level.PENDING;
        }

        /** 有没有可点的「[点击此处]」。 */
        boolean hasLink() {
            return linkTarget != LinkTarget.NONE;
        }

        /** 链接是不是「就地重试」。 */
        boolean retry() {
            return linkTarget == LinkTarget.RETRY;
        }
    }

    // ---------- 地址拼装 ----------

    /**
     * 校验请求要打的完整地址。
     *
     * <p><b>OpenRouter 例外：</b>它的 {@code /models} 是<b>公开</b>接口，
     * 不带 Key、或者带一个乱写的 Key，都会正常返回 200 与整份模型清单。
     * 拿它验 Key 的话，任何字符串都会显示「可用」。所以对 OpenRouter 改打
     * {@code /auth/key}——这个接口必须带有效 Key，无效时回 401。
     * 代价是它不返回模型清单，那一步的清单就取不到了（清单本来也能从
     * 公开的 {@code /models} 单独拿，但那是另一个请求，这里不做）。
     *
     * @param baseUrl  AI 的 Base URL（第 1 步写进配置的那个）
     * @param apiKey   玩家填的 Key
     * @param gemini   是否走 Gemini 的 {@code ?key=} 鉴权
     * @param provider 提供商标识（{@code OPENAI} / {@code GEMINI} / …），用于识别例外
     */
    static String modelsUrl(String baseUrl, String apiKey, boolean gemini, String provider) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (isOpenRouter(base)) {
            return base + "/auth/key";
        }
        String url = base + "/models";
        if (gemini) {
            url += "?key=" + urlEncode(apiKey == null ? "" : apiKey.trim());
        }
        return url;
    }

    /** 这个 Base URL 是不是 OpenRouter。 */
    private static boolean isOpenRouter(String baseUrl) {
        return baseUrl.toLowerCase(java.util.Locale.ROOT).contains("openrouter.ai");
    }

    /** 只转义 Key 里可能在查询串中出事的字符，不做完整表单编码。 */
    private static String urlEncode(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append(String.format("%02X", b));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 日志里用的脱敏地址：Key 绝不进日志。
     * <p>Gemini 的 Key 在查询串里，OpenAI 的在请求头里——前者必须显式抹掉，
     * 后者只要不打请求头就安全，这里统一给个最保险的处理。
     */
    static String redact(String url) {
        if (url == null) return "(none)";
        int idx = url.indexOf("?key=");
        if (idx < 0) idx = url.indexOf("&key=");
        return idx < 0 ? url : url.substring(0, idx + 1) + "key=***";
    }

    // ---------- 后台校验 ----------

    /** 每次发起校验就递增，用于丢弃过期的结果。 */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    static int currentGeneration() {
        return GENERATION.get();
    }

    /** 作废所有在途请求，让它们的结果被丢弃。 */
    static int invalidate() {
        return GENERATION.incrementAndGet();
    }

    /**
     * 在后台线程校验一个 Key。
     *
     * @param baseUrl    第 1 步定下来的 Base URL
     * @param provider   提供商标识（{@code OPENAI} / {@code GEMINI} / …）
     * @param gemini     是否 Gemini
     * @param generation 发起时的代号，必须等于 {@link #currentGeneration()}
     * @param onSaved    模型清单写盘成功后的回调（在主线程），参数是写入的路径
     */
    static void checkAsync(String baseUrl, String apiKey, String provider, boolean gemini, int generation,
                           java.util.function.Consumer<Result> onResult,
                           java.util.function.Consumer<Path> onSaved) {
        ConfigIO.run(() -> {
            Result result;
            Path savedPath = null;
            try {
                Outcome outcome = check(baseUrl, apiKey, gemini, provider);
                result = outcome.result();
                if (outcome.body() != null) {
                    savedPath = saveModels(outcome.body());
                }
            } catch (RuntimeException e) {
                Log.warn("[ApiKeyCheck] CHECK=CRASHED | ERROR=" + e);
                result = Result.retryable(Level.WARN, "无法判断该 API Key 的可用性");
            }
            Result finalResult = result;
            Path finalPath = savedPath;
            ConfigIO.backToClient(() -> {
                if (generation != GENERATION.get()) return;
                onResult.accept(finalResult);
                if (finalPath != null && onSaved != null) onSaved.accept(finalPath);
            });
        });
    }

    /** 一次校验的产物：结论 + 可能要落盘的响应体。 */
    private record Outcome(Result result, String body) {}

    /** 真正发请求并解释响应。跑在后台线程。 */
    private static Outcome check(String baseUrl, String apiKey, boolean gemini, String provider) {
        String url = modelsUrl(baseUrl, apiKey, gemini, provider);
        String logUrl = redact(url);
        Log.debug("[ApiKeyCheck] TEST=" + logUrl);

        HttpURLConnection conn = null;
        long startedAt = System.nanoTime();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // 不跟随重定向：跳走之后打到哪里就说不清了，Key 也可能被带去别的主机
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "VerityPlus-ApiKeyCheck/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (!gemini) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }

            int code = conn.getResponseCode();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            String contentType = conn.getContentType();

            // 2xx 时响应体就是模型清单；4xx 时也要读一小段——
            // 有些服务商（Gemini）把「Key 无效」藏在 400 的响应体里，
            // 不看内容就只能归到「说不准」
            String body;
            if (code >= 200 && code < 300) {
                body = readBody(conn.getInputStream());
            } else if (code >= 400 && code < 500) {
                body = readBody(conn.getErrorStream());
            } else {
                body = null;
            }

            Result result = interpret(code, body);
            Log.debug("[ApiKeyCheck] TEST=" + logUrl
                    + " | HTTP=" + code + '-' + statusText(code)
                    + " | TYPE=" + (contentType == null ? "(none)" : contentType)
                    + " | MS=" + elapsedMs
                    + " | VERDICT=" + result.level()
                    + " | MODELS=" + describeModels(body));
            if (result.level() != Level.OK) {
                // 结论不是绿的，说明玩家可能遇到了问题，这行必须能在 info 级别看到
                Log.info("[ApiKeyCheck] TEST=" + logUrl
                        + " | HTTP=" + code + '-' + statusText(code)
                        + " | MS=" + elapsedMs
                        + " | VERDICT=" + result.level());
            }
            // 2xx 但响应体不是模型列表：不落盘，否则第 3 步会读到一份看不懂的内容
            boolean usable = result.level() == Level.OK;
            return new Outcome(result, usable ? body : null);
        } catch (java.net.UnknownHostException e) {
            return new Outcome(reportConnectFailure("DNS_RESOLVE_FAILED", logUrl, startedAt, e), null);
        } catch (java.net.SocketTimeoutException e) {
            return new Outcome(reportConnectFailure("TIMEOUT_" + TIMEOUT_MS + "MS", logUrl, startedAt, e), null);
        } catch (java.net.ConnectException e) {
            return new Outcome(reportConnectFailure("CONNECT_REFUSED", logUrl, startedAt, e), null);
        } catch (java.io.IOException e) {
            return new Outcome(reportConnectFailure("IO_" + e.getClass().getSimpleName(), logUrl, startedAt, e), null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 读响应体，最多 {@link #MAX_BODY_BYTES}；读失败时当作没有响应体。 */
    private static String readBody(InputStream in) {
        if (in == null) return null;
        try (InputStream stream = in) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() >= MAX_BODY_BYTES) break;
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.debug("[ApiKeyCheck] BODY=READ_FAILED | ERROR=" + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 按状态码给出结论。文字与需求一一对应。
     *
     * <p>两个按响应体细分的例外：
     * <ul>
     *   <li>Gemini 对无效 Key 回的是 <b>400 INVALID_ARGUMENT</b> 而不是 401，
     *       不能都当「说不准」放过去；</li>
     *   <li>OpenRouter 走 {@code /auth/key}，有效 Key 回 200、无效回 401，
     *       与通用逻辑一致，不用特判。</li>
     * </ul>
     */
    private static Result interpret(int code, String body) {
        if (code >= 200 && code < 300) {
            // 有的服务商会用 200 + {"error":…} 表达失败，不能只看状态码
            if (body != null && body.contains("\"error\"") && !body.contains("\"data\"")) {
                return new Result(Level.WARN, "无法判断该 API Key 的可用性", LinkTarget.NONE, null);
            }
            return Result.ok("该 API Key 可用");
        }
        if (code == 401) {
            return Result.linked(Level.ERROR, "该 API Key 无效", LinkTarget.NEW_KEY, "新建一个 API Key 尝试");
        }
        // Gemini 的无效 Key：400 INVALID_ARGUMENT（API_KEY_INVALID）
        if (code == 400 && body != null && body.contains("API_KEY_INVALID")) {
            return Result.linked(Level.ERROR, "该 API Key 无效", LinkTarget.NEW_KEY, "新建一个 API Key 尝试");
        }
        if (code == 402) {
            // 链接的网址在界面层按提供商填（这里是纯逻辑，不该知道界面上选了谁）
            return Result.linked(Level.WARN, "账户余额不足", LinkTarget.TOP_UP, "充值或等待额度重置");
        }
        if (code == 429 || code >= 500) {
            return Result.retryable(Level.WARN, "服务器繁忙");
        }
        return new Result(Level.WARN, "无法判断该 API Key 的可用性", LinkTarget.NONE, null);
    }

    /** 连接阶段失败：写一行英文日志，并给出可重试的结论。 */
    private static Result reportConnectFailure(String reason, String logUrl, long startedAt, Exception cause) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        Log.info("[ApiKeyCheck] TEST=" + logUrl
                + " | CONNECTIVITY=FAILED | REASON=" + reason
                + " | MS=" + elapsedMs
                + " | VERDICT=" + Level.WARN
                + " | ERROR=" + cause);
        return Result.retryable(Level.WARN, "连接失败，请检查网络连接");
    }

    /** 日志用：响应体里有几个模型，或者是不是合法的 JSON。 */
    private static String describeModels(String body) {
        if (body == null) return "(none)";
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("data")
                    && root.getAsJsonObject().get("data").isJsonArray()) {
                return String.valueOf(root.getAsJsonObject().getAsJsonArray("data").size());
            }
            return "JSON_NO_DATA_ARRAY";
        } catch (RuntimeException e) {
            return "NOT_JSON";
        }
    }

    /** HTTP 状态码的标准短语。 */
    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> code < 300 ? "Success" : code < 400 ? "Redirect" : code < 500 ? "Client Error" : "Server Error";
        };
    }

    // ---------- 模型清单落盘 ----------

    /** {@code .cache/verity_plus/ai_models} 的绝对路径。 */
    static Path modelsFile() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        return gameDir.toPath().resolve(MODELS_DIR).resolve(MODELS_FILE);
    }

    /**
     * 把响应体原样覆盖到模型清单文件。
     * <p>存的是<b>服务端返回的原始 JSON</b>，不做任何提取或改写：
     * 第 3 步要用的模型名、上下文长度等都在里面，改写一遍等于把信息丢掉。
     * <p>先写临时文件再原子替换：中途失败时不会留下一份半截的清单。
     *
     * @return 写入成功时返回文件路径；失败返回 {@code null}
     */
    private static Path saveModels(String body) {
        Path target = modelsFile();
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(MODELS_FILE + ".tmp");
            Files.writeString(temp, body, StandardCharsets.UTF_8);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Log.info("[ApiKeyCheck] MODELS_SAVED | FILE=" + target + " | BYTES=" + body.length()
                    + " | COUNT=" + describeModels(body));
            return target;
        } catch (IOException | RuntimeException e) {
            Log.warn("[ApiKeyCheck] MODELS_SAVE_FAILED | FILE=" + target + " | ERROR=" + e);
            return null;
        }
    }

    /**
     * 第 1 步的 Base URL。
     * <p>自定义取玩家填的地址，预置提供商取注册表里的地址；
     * 两者都没有时返回空串（调用方不该在那种情况下发请求）。
     */
    static String baseUrlFor(String pickedLabel, String customEndpoint) {
        Providers.PickerOption option = Providers.pickerOption(pickedLabel);
        if (option == null) return "";
        if (option.isCustom()) return customEndpoint == null ? "" : customEndpoint.trim();
        return option.endpoint() == null ? "" : option.endpoint();
    }

    /** 该提供商是不是走 Gemini 的 {@code ?key=} 鉴权。 */
    static boolean isGemini(String pickedLabel) {
        Providers.PickerOption option = Providers.pickerOption(pickedLabel);
        return option != null && Providers.GEMINI.equals(option.provider());
    }
}
