package com.cb2495.verityplus.config;

import com.cb2495.verityplus.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 引导流程里可选的网络代理。
 *
 * <h3>为什么需要它</h3>
 * 有些服务商在国内直连不通（GROQ 的 403、Mistral 的 DNS 污染），
 * 玩家手上有代理时希望能先用代理把 Key 验通。这里只管<b>本模组自己发的请求</b>：
 * <ul>
 *   <li>第 1 步的自定义 Endpoint 探活；</li>
 *   <li>第 2 步的 API Key 校验；</li>
 *   <li>第 3 步的模型清单刷新。</li>
 * </ul>
 * <b>不影响 Verity 本体</b>：它调用 AI 走的是自己的 langchain4j 客户端，
 * 那部分代码不在我们手里。所以玩家可能遇到「这里显示可用、进游戏却连不上」，
 * 面板上必须写清楚这一点。
 *
 * <h3>为什么不用全局 {@code System.setProperty}</h3>
 * 全局代理会连带影响游戏里所有走 {@code URLConnection} 的代码（包括 Forge、认证、
 * 其它模组），一旦填错就把整个游戏搞成断网。这里改成<b>逐连接</b>应用：
 * 调用方拿到 {@link #openConnection(URL)} 得到的连接，只有它走代理。
 *
 * <h3>协议</h3>
 * <ul>
 *   <li>{@code HTTP} —— {@link Proxy.Type#HTTP}，CONNECT 隧道，所有厂家的客户端都支持；</li>
 *   <li>{@code SOCKS5} —— {@link Proxy.Type#SOCKS}，JDK 内置支持。</li>
 * </ul>
 */
public final class ProxyConfig {

    private ProxyConfig() {}

    /** 支持的协议。 */
    public enum Type {
        HTTP("HTTP/HTTPS"),
        SOCKS5("SOCKS5");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 在 HTTP 与 SOCKS5 之间循环（点一下换一个）。 */
        public Type next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * 一份代理设置。
     *
     * @param enabled 是否启用
     * @param type    协议
     * @param host    地址
     * @param port    端口；非法或未填时为 {@code 0}
     */
    public record Settings(boolean enabled, Type type, String host, int port) {

        /** 默认：不启用。 */
        public static Settings disabled() {
            return new Settings(false, Type.HTTP, "127.0.0.1", 7890);
        }

        /** 这份设置能不能真正拿来建连接。 */
        public boolean usable() {
            return enabled && port > 0 && port <= 65535 && host != null && !host.isBlank();
        }

        /** 不能用的原因；能用时返回 {@code null}。 */
        public String problem() {
            if (!enabled) return null;
            if (host == null || host.isBlank()) return "请填入代理地址";
            if (port <= 0 || port > 65535) return "端口不正确（1-65535）";
            return null;
        }

        /** 日志与界面用的简短描述，例如 {@code HTTP/HTTPS @ 127.0.0.1:7890}。 */
        public String describe() {
            return type.label() + " @ " + host + ":" + port;
        }
    }

    // ---------- 当前设置 ----------

    private static volatile Settings current = Settings.disabled();

    public static Settings get() {
        return current;
    }

    /** 换个设置；传入 {@code null} 等于关掉。 */
    public static void set(Settings settings) {
        current = settings == null ? Settings.disabled() : settings;
        Log.info("[Proxy] APPLIED | " + (current.enabled()
                ? current.describe() : "DISABLED"));
    }

    // ---------- 建连接 ----------

    /**
     * 按当前设置打开一个连接。
     * <p>没启用代理时就是普通的 {@link URL#openConnection()}，行为与以前完全一致。
     *
     * @param url 目标地址
     * @return 已配置好代理的连接
     */
    public static URLConnection openConnection(URL url) throws IOException {
        Settings settings = current;
        if (!settings.usable()) {
            return url.openConnection();
        }
        Proxy proxy = new Proxy(
                settings.type() == Type.SOCKS5 ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(settings.host(), settings.port()));
        return url.openConnection(proxy);
    }

    /**
     * 用一个临时的设置打开连接，用于「应用」按下时<b>先试这一份</b>。
     * <p>不读 {@link #current}：失败时不该把全局设置改成一份错的。
     *
     * @param url      目标地址
     * @param settings 要试的设置
     */
    public static URLConnection openConnection(URL url, Settings settings) throws IOException {
        if (settings == null || !settings.usable()) {
            return url.openConnection();
        }
        Proxy proxy = new Proxy(
                settings.type() == Type.SOCKS5 ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(settings.host(), settings.port()));
        return url.openConnection(proxy);
    }

    // ---------- 自检 ----------

    /**
     * 只用 TCP 探一次代理端口通不通。
     * <p>比发一个完整 HTTPS 请求快得多，用来区分「代理端口没开」和「代理开了但目标站不通」——
     * 这两种情况玩家该做的事完全不同。
     *
     * @return 连上返回 {@code null}，否则返回一句给人看的原因
     */
    public static String probePort(Settings settings) {
        if (settings == null || !settings.usable()) return "代理未启用或参数不完整";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), 3000);
            return null;
        } catch (IOException e) {
            return "连不上代理 " + settings.host() + ":" + settings.port()
                    + "（" + shortMessage(e) + "）";
        }
    }

    /** 把异常化简成一句能放进界面的短语。 */
    private static String shortMessage(Exception e) {
        String name = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (message == null || message.isBlank()) return name;
        // 只留第一行，异常消息常常带一长串节点路径
        int newline = message.indexOf('\n');
        if (newline > 0) message = message.substring(0, newline);
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }

    // ---------- 持久化 ----------
    //
    // 存在 .cache/verity_plus/ 下，与 game_state 同一层：
    // 它是调试/测试用的设置，不属于玩家的 AI 配置，不该混进 config/ 里。
    // 每次改动立即落盘，避免玩家配好之后重启又得重填一遍。

    private static final String DIR = ".cache/verity_plus";
    private static final String FILE_NAME = "proxy";

    /** 从磁盘读一次。文件不存在或读不懂时保持默认（关闭）。 */
    public static void load() {
        java.nio.file.Path file = file();
        if (!java.nio.file.Files.isRegularFile(file)) return;
        try {
            Settings loaded = parse(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
            if (loaded != null) current = loaded;
            Log.info("[Proxy] LOADED | " + (current.enabled() ? current.describe() : "DISABLED"));
        } catch (IOException e) {
            Log.warn("[Proxy] LOAD_FAILED | FILE=" + file + " | ERROR=" + e);
        }
    }

    /** 把当前设置写回磁盘（后台线程）。 */
    public static void save() {
        Settings snapshot = current;
        com.cb2495.verityplus.util.ConfigIO.run(() -> write(snapshot));
    }

    private static void write(Settings settings) {
        java.nio.file.Path file = file();
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            String text = "# Verity Plus 引导流程的代理设置。删掉本文件即可恢复为「不使用代理」。\n"
                    + "# 只影响本模组发出的检测请求，不影响 Verity 本体。\n"
                    + "enabled=" + settings.enabled() + "\n"
                    + "type=" + settings.type().name().toLowerCase(Locale.ROOT) + "\n"
                    + "host=" + settings.host() + "\n"
                    + "port=" + settings.port() + "\n";
            ConfigFileUtils.writeLines(file, java.util.List.of(text.split("\\R")));
        } catch (IOException e) {
            Log.warn("[Proxy] SAVE_FAILED | FILE=" + file + " | ERROR=" + e);
        }
    }

    private static java.nio.file.Path file() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                .resolve(DIR).resolve(FILE_NAME);
    }

    /** 解析 {@code key=value} 格式；读不懂时返回 {@code null}。 */
    static Settings parse(String text) {
        Settings result = Settings.disabled();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "enabled" -> result = new Settings(
                        Boolean.parseBoolean(value), result.type(), result.host(), result.port());
                case "type" -> result = new Settings(result.enabled(),
                        "socks5".equalsIgnoreCase(value) ? Type.SOCKS5 : Type.HTTP,
                        result.host(), result.port());
                case "host" -> result = new Settings(result.enabled(), result.type(),
                        value, result.port());
                case "port" -> {
                    int port;
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        port = 0;
                    }
                    result = new Settings(result.enabled(), result.type(), result.host(), port);
                }
                default -> { }
            }
        }
        return result;
    }
}
