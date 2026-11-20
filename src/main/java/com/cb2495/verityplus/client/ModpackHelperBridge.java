package com.cb2495.verityplus.client;

import com.cb2495.verityplus.util.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 对 Modpack Helper 模组（modid {@code modpackhelper}）的调用桥。
 * <p>帮助文档、常见问题、旧存档导入三个界面已经搬到 Modpack Helper 里，
 * 那边通过 mods.toml 反向依赖本模组（拿日志、后台线程、图片头解析等公共工具），
 * 所以本模组<b>不能</b>反向硬依赖它，否则两个 jar 就互相锁死了。
 * <p>这里全部走反射，且只在调用时解析类：没装 Modpack Helper 时本模组照常工作，
 * 只是少了这几个入口（调用方自行兜底）。
 * <p>类名与方法签名改了就抛 {@link HelperUnavailable}，调用方按「没装」处理即可。
 */
public final class ModpackHelperBridge {

    private ModpackHelperBridge() {}

    /** Modpack Helper 的 modid。 */
    public static final String MODID = "modpackhelper";

    private static final String PKG = "com.cb2495.modpackhelper";

    /** 反射失败时抛这个，调用方据此决定降级路径。 */
    public static final class HelperUnavailable extends RuntimeException {
        HelperUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 打开帮助文档。
     *
     * @param topic        帮助主题（英文，如 {@code low_memory}）
     * @param returnScreen 返回时回到的界面；可以为 {@code null}
     * @return 是否成功打开
     */
    public static boolean openHelp(Screen returnScreen, String topic) {
        try {
            Class<?> cls = Class.forName(PKG + ".screen.HelperScreen");
            Constructor<?> ctor = cls.getConstructor(String.class, Screen.class);
            Minecraft.getInstance().setScreen((Screen) ctor.newInstance(topic, returnScreen));
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Log.warn("无法打开帮助界面（Modpack Helper 未安装或版本过旧）: " + e);
            return false;
        }
    }

    /**
     * 打开「旧版整合包存档导入」界面。
     * <p>扫描实现也在 Modpack Helper 里，这里先反射调用 {@code LegacySaveScanner.scan(Path)}，
     * 把拿到的 {@code List<LegacySave>} 原样交给它的界面构造函数。
     *
     * @param returnScreen 返回时回到的界面
     * @param onFinished    导入页结束后的后续动作
     * @return 是否成功打开（扫描不到存档也算成功——界面会自己显示「没有发现」）
     */
    public static boolean openLegacySaveImport(Screen returnScreen, Runnable onFinished) {
        try {
            Class<?> scanner = Class.forName(PKG + ".save.LegacySaveScanner");
            Method scan = scanner.getMethod("scan", java.nio.file.Path.class);
            Object saves = scan.invoke(null, Minecraft.getInstance().gameDirectory.toPath());
            if (!(saves instanceof List<?> list)) {
                return false;
            }
            Class<?> screenCls = Class.forName(PKG + ".screen.LegacySaveImportScreen");
            Constructor<?> ctor = screenCls.getConstructor(List.class, Screen.class, Runnable.class);
            Minecraft.getInstance().setScreen(
                    (Screen) ctor.newInstance(list, returnScreen, onFinished));
            return true;
        } catch (InvocationTargetException e) {
            // 扫描本身出错（目录异常等）不挡流程，当作「没有旧存档」
            Log.warn("扫描旧整合包存档失败: " + e.getCause());
            return false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Log.warn("无法打开旧存档导入界面（Modpack Helper 未安装或版本过旧）: " + e);
            return false;
        }
    }

    /**
     * 打开常见问题界面。
     *
     * @return 是否成功打开
     */
    public static boolean openQandA() {
        try {
            Class<?> cls = Class.forName(PKG + ".screen.QandAScreen");
            Constructor<?> ctor = cls.getConstructor();
            Minecraft.getInstance().setScreen((Screen) ctor.newInstance());
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Log.warn("无法打开常见问题界面（Modpack Helper 未安装或版本过旧）: " + e);
            return false;
        }
    }

    /**
     * 打开「模组管理功能已移除」的说明页。
     *
     * @param onFinished 点「跳过」（或按 Esc）之后要做的事
     * @return 是否成功打开
     */
    public static boolean openModsRemoved(Runnable onFinished) {
        try {
            Class<?> cls = Class.forName(PKG + ".screen.ModsRemovedScreen");
            Constructor<?> ctor = cls.getConstructor(Runnable.class);
            Minecraft.getInstance().setScreen((Screen) ctor.newInstance(onFinished));
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Log.warn("无法打开模组管理说明页（Modpack Helper 未安装或版本过旧）: " + e);
            return false;
        }
    }

    /** Modpack Helper 是否可用；只是提前告知调用方，不保证每个方法都对得上。 */
    public static boolean isAvailable() {
        return net.minecraftforge.fml.ModList.get().isLoaded(MODID);
    }

    // ---------- 可点击指令文本（实现已搬到 Modpack Helper） ----------

    /** 「点击填入命令」的悬停提示文案。 */
    public static final String CLICK_TO_FILL = "点击填入命令";

    /**
     * 一段青色文字，点击后把指令填进输入框。
     * <p>构造逻辑在 Modpack Helper 的 {@code ChatLinks} 里；取不到时退化为一段普通青色文字，
     * 至少不会让整条错误提示消失。
     */
    public static net.minecraft.network.chat.Component suggestCmd(String command, String hover) {
        try {
            Class<?> cls = Class.forName(PKG + ".client.ChatLinks");
            Object result = cls.getMethod("suggestCmd", String.class, String.class)
                    .invoke(null, command, hover);
            if (result instanceof net.minecraft.network.chat.Component c) {
                return c;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Log.debug("ChatLinks 不可用，退化为纯文本: " + e);
        }
        return net.minecraft.network.chat.Component.literal(command)
                .withStyle(net.minecraft.ChatFormatting.AQUA);
    }

    // ---------- 峰谷提示（实现已搬到 Modpack Helper） ----------
    /**
     * 开关改动后让 Modpack Helper 立刻重新读配置。
     * <p>实现里缓存了开关状态以免每 tick 读盘，所以改完必须通知它。
     */
    public static void refreshDeepSeekToggle() {
        invokeDeepSeek("refreshToggle", new Class<?>[0]);
    }

    /**
     * 按指定时刻输出一次峰谷提示，供 {@code /vc dshint date} 使用。
     *
     * @param moment 作为「当前时刻」的时间
     */
    public static void sendDeepSeekHintAt(java.time.LocalDateTime moment) {
        invokeDeepSeek("sendHintAt", new Class<?>[]{java.time.LocalDateTime.class}, moment);
    }

    /**
     * 调用 Modpack Helper 的 {@code DeepSeekHintHandler}。
     * <p>没装 Modpack Helper 时静默失败：{@code /vc dshint} 那几个子指令本来就只在
     * 它存在时才注册，走到这里说明用户是直接敲的完整指令，给他一句提示即可。
     */
    private static void invokeDeepSeek(String method, Class<?>[] types, Object... args) {
        try {
            Class<?> cls = Class.forName(PKG + ".client.DeepSeekHintHandler");
            cls.getMethod(method, types).invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Log.warn("调用 Modpack Helper 的峰谷提示失败（可能未安装）: " + e);
        }
    }
}
