package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.util.ConfigIO;
import com.cb2495.verityplus.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 1 步「自定义」Endpoint 的地址校验。
 *
 * <h3>两段式</h3>
 * <ol>
 *   <li><b>格式校验</b>（同步、瞬时）：必须以 {@code https://} 开头，且有主机名；</li>
 *   <li><b>可用性探活</b>（异步、带超时）：对 {@code <endpoint>/models} 发一次 HEAD，
 *       按状态码与 {@code Content-Type} 判断它是不是一个 OpenAI 兼容接口。</li>
 * </ol>
 *
 * <h3>为什么用 HEAD 而不是 curl -I 起子进程</h3>
 * 需求里写的是 {@code curl -I}，那等价于「发一个 HEAD 请求」。用
 * {@link HttpURLConnection} 直接发就够，不必依赖系统上装了 curl、也不用管
 * 进程的输入输出与超时回收。<b>不跟随重定向</b>：3xx 本身就是要判断的信息。
 *
 * <h3>防抖与竞态</h3>
 * 玩家停止输入 {@link #DEBOUNCE_MS} 毫秒后才发请求。每次输入都会递增一个序号，
 * 请求在后台线程完成后，只有序号仍是最新的结果才会被接受——
 * 否则连打几个字符时，先发的慢请求可能后到，把新结果盖掉。
 */
final class EndpointValidator {

    private EndpointValidator() {}

    /** 停止输入多久后开始探活。 */
    static final long DEBOUNCE_MS = 2000L;
    /** 连接与读取超时：需求定了 5 秒，且不重试。 */
    static final int TIMEOUT_MS = 5000;

    /** 校验结论。 */
    enum Level {
        /** 正在等待输入停止或等待服务器响应。 */
        PENDING,
        /** 通过：是 OpenAI 兼容接口。 */
        OK,
        /** 只是提示，不拦截（非 OpenAI 接口、服务器繁忙等）。 */
        WARN,
        /** 阻止继续（格式错、404、403）。 */
        ERROR
    }

    /**
     * 一次校验的结果。
     *
     * @param level    结论等级
     * @param message  显示在圆圈/叉号右边的文字
     * @param retryable 是否在文字后面追加「[点击此处]重新尝试」
     */
    record Result(Level level, String message, boolean retryable) {

        /**
         * 是否应当禁止「确认」。
         * <p>红叉（格式错 / 404 / 403）与黄圈（还在验证）都拦人；
         * 橙圈只是提醒，放行。
         */
        boolean blocksNext() {
            return level == Level.ERROR || level == Level.PENDING;
        }

        /** 是否有结论（PENDING 表示还在验证）。 */
        boolean isBusy() {
            return level == Level.PENDING;
        }
    }

    // ---------- 格式校验 ----------

    /**
     * 只做格式判断，不发网络请求。
     *
     * @return 格式有问题时返回错误结果；格式正确返回 {@code null}
     */
    static Result checkFormat(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            // 空地址不在这里报错：没填时由「确认」按钮那句提示负责，
            // 一选中自定义就满屏红字会很吵
            return null;
        }
        if (!text.startsWith("https://")) {
            Log.debug("[EndpointCheck] FORMAT=FAILED | REASON=NO_HTTPS_PREFIX | INPUT=" + text);
            return new Result(Level.ERROR, "网址不符合格式要求，正确格式：https://……", false);
        }
        String host = text.substring("https://".length());
        // 截掉路径，只留主机部分
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.isEmpty() || host.startsWith(".") || host.endsWith(".")) {
            Log.debug("[EndpointCheck] FORMAT=FAILED | REASON=INVALID_HOST | INPUT=" + text);
            return new Result(Level.ERROR, "网址不符合格式要求，正确格式：https://……", false);
        }
        return null;
    }

    /**
     * 把用户填的地址拼成探活用的 {@code <endpoint>/models}。
     * <p>末尾多余的斜杠先去掉，避免拼出 {@code //models}——有些服务器会因此回 404。
     */
    static String modelsUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/models";
    }

    // ---------- 探活 ----------

    /** 每次发起探活就递增，用于丢弃过期的结果。 */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** 当前这一代（最新一次输入对应的校验）。 */
    static int currentGeneration() {
        return GENERATION.get();
    }

    /** 作废所有在途请求，让它们的结果被丢弃。 */
    static int invalidate() {
        return GENERATION.incrementAndGet();
    }

    /**
     * 在后台线程对 {@code endpoint} 做一次探活。
     * <p>结果通过 {@code onResult} 交回客户端主线程；若期间又有新的输入
     * （{@link #invalidate()} 被调用过），结果会被丢弃。
     *
     * @param generation 发起时的代号，必须等于 {@link #currentGeneration()}
     */
    static void probeAsync(String endpoint, int generation, java.util.function.Consumer<Result> onResult) {
        ConfigIO.run(() -> {
            Result result;
            try {
                result = probe(endpoint);
            } catch (RuntimeException e) {
                Log.warn("[EndpointCheck] PROBE=CRASHED | ERROR=" + e);
                result = new Result(Level.WARN, "无法判断该网址是否是 OpenAI 格式", false);
            }
            Result finalResult = result;
            ConfigIO.backToClient(() -> {
                // 期间又改过输入：这次结论已经过期，直接丢掉
                if (generation != GENERATION.get()) return;
                onResult.accept(finalResult);
            });
        });
    }

    /** 真正发请求并解释响应。跑在后台线程。 */
    private static Result probe(String endpoint) {
        String url = modelsUrl(endpoint);
        Log.debug("[EndpointCheck] TEST=" + url);

        HttpURLConnection conn = null;
        long startedAt = System.nanoTime();
        try {
            URL target = new URL(url);
            conn = (HttpURLConnection) target.openConnection();
            // 用 GET 而不是 HEAD：不少网关（例如 SiliconFlow）对 HEAD 直接回 404，
            // 而同样的地址 GET 能正常返回 JSON。HEAD 只是省流量的手段，
            // 判断「地址能不能当 OpenAI 接口用」用 GET 才准确。
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // 不跟随重定向：3xx 本身就是「这不是个 API」的信号之一
            conn.setInstanceFollowRedirects(false);
            // 有些服务器对没有 UA 的请求直接 403，带一个常见的值更稳
            conn.setRequestProperty("User-Agent", "VerityPlus-EndpointCheck/1.0");
            conn.setRequestProperty("Accept", "application/json");
            // 只要前 1 个字节：模型列表可能很大，我们只需要响应头，
            // 不该为了一次探活真把整份 JSON 拉下来。
            // 即使服务器不支持 Range、回的是 200 全量，下面也只读一小段就断开。
            conn.setRequestProperty("Range", "bytes=0-0");

            int code = conn.getResponseCode();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            String contentType = conn.getContentType();
            String location = conn.getHeaderField("Location");
            String server = conn.getHeaderField("Server");

            // 416（Range 无法满足）说明这个地址是个可访问的资源，只是不接受该区间。
            // 有些网关对 Range 处理得不好，退回成按 GET 成功对待。
            Result result = interpret(code == 416 ? 200 : code, contentType);

            // 一行一条：测试地址、连通性、类型，再加耗时与关键响应头。
            // 通过的请求只写 debug（正常使用时不该每填一次地址就刷一行），
            // 有问题的提到 info，保证玩家反馈「网址明明是好的却报错」时日志里一定有线索。
            StringBuilder line = new StringBuilder("[EndpointCheck] TEST=").append(url)
                    .append(" | CONNECTIVITY=").append(code).append('-').append(statusText(code))
                    .append(" | TYPE=").append(contentType == null ? "(none)" : contentType)
                    .append(" | METHODS=").append(allowMethods(conn))
                    .append(" | MS=").append(elapsedMs)
                    .append(" | VERDICT=").append(result.level());
            if (server != null) line.append(" | SERVER=").append(server);
            // 3xx 只有跟着 Location 去访问才知道真实目标，这条对排查很有用
            if (location != null) line.append(" | LOCATION=").append(location);
            if (!result.message().isEmpty()) {
                // 结论原文是中文界面文案，日志里只留等级，避免控制台乱码
                line.append(" | BLOCKS=").append(result.blocksNext());
            }

            if (result.level() == Level.OK) {
                Log.debug(line.toString());
            } else {
                Log.info(line.toString());
            }
            return result;
        } catch (java.net.UnknownHostException e) {
            return reportConnectFailure("DNS_RESOLVE_FAILED", url, startedAt, e);
        } catch (java.net.SocketTimeoutException e) {
            return reportConnectFailure("TIMEOUT_" + TIMEOUT_MS + "MS", url, startedAt, e);
        } catch (java.net.ConnectException e) {
            return reportConnectFailure("CONNECT_REFUSED", url, startedAt, e);
        } catch (java.io.IOException e) {
            // 连接重置、SSL 握手失败等
            return reportConnectFailure("IO_" + e.getClass().getSimpleName(), url, startedAt, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 连接阶段失败：写一条英文日志（原因 + 地址 + 耗时 + 异常），并给出可重试的结论。
     * <p>不把异常栈打出来——这类失败大多是玩家本机网络问题，
     * 一行原因加一行地址就足够定位，栈会淹没日志。
     */
    private static Result reportConnectFailure(String reason, String url, long startedAt, Exception cause) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        Log.info("[EndpointCheck] TEST=" + url
                + " | CONNECTIVITY=FAILED | REASON=" + reason
                + " | MS=" + elapsedMs
                + " | VERDICT=" + Level.WARN
                + " | ERROR=" + cause);
        return new Result(Level.WARN, "连接失败，请检查网络连接", true);
    }

    /** HTTP 状态码对应的标准短语，例如 200 → OK、404 → Not Found。 */
    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> code < 300 ? "Success" : code < 400 ? "Redirect" : code < 500 ? "Client Error" : "Server Error";
        };
    }

    /**
     * 响应头里的 {@code Allow}（服务端认可的方法）。
     * <p>有些网关对 HEAD 回 405，但 {@code Allow} 里写着 POST——这条能直接说明
     * 「地址是对的，只是这个位置不支持 HEAD」，比只看到一个 405 有用得多。
     */
    private static String allowMethods(HttpURLConnection conn) {
        String allow = conn.getHeaderField("Allow");
        return allow == null || allow.isBlank() ? "(n/a)" : allow.trim();
    }

    /**
     * 按状态码与 Content-Type 给出结论。
     *
     * <p>判断顺序很重要：<b>先看状态码再看 Content-Type</b>。因为 404 的响应体
     * 常常是 HTML 错误页，如果先判断「不是 json」，就会被误报成
     * 「可能不是 OpenAI 接口」，而实际上这个地址压根不存在。
     *
     * @param code         HTTP 状态码
     * @param contentType  响应头里的 Content-Type，可能为 {@code null}
     */
    private static Result interpret(int code, String contentType) {
        // 404：地址本身不存在
        if (code == 404) {
            return new Result(Level.ERROR, "该网址不是一个有效的网址", false);
        }
        // 403：存在但拒绝访问
        if (code == 403) {
            return new Result(Level.ERROR, "该网址无法被访问", false);
        }
        // 5xx / 429：服务器暂时不可用
        if (code >= 500 || code == 429) {
            return new Result(Level.WARN, "服务器繁忙", false);
        }
        // 2xx / 401：接口在，且要求鉴权（401 恰恰说明是标准 OpenAI 端点）
        if ((code >= 200 && code < 300) || code == 401) {
            // 2xx 还要求 Content-Type 是 json；401 可能没有响应体，直接放行
            if (code == 401 || isJson(contentType)) {
                return new Result(Level.OK, "该网址是标准 OpenAI 兼容 API（大概？）", false);
            }
            return new Result(Level.WARN, "该网址可能不是 OpenAI 接口", false);
        }
        // 其余（400/405/…）：说不准
        return new Result(Level.WARN, "无法判断该网址是否是 OpenAI 格式", false);
    }

    /** Content-Type 是不是 JSON（允许带 {@code ; charset=utf-8} 之类的参数）。 */
    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("application/json") || lower.contains("+json");
    }
}
