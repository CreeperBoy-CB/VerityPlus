package com.cb2495.verityplus.config;

import com.cb2495.verityplus.util.ConfigIO;
import com.cb2495.verityplus.util.Log;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 玩家的引导流程状态，存于 {@code .cache/verity_plus/game_state}。
 *
 * <h3>为什么单独一个文件</h3>
 * 这个状态<b>不属于配置</b>：它不是玩家能改的选项，玩家也不该在
 * {@code config/} 里看到它。{@code .cache/} 是「玩家不会去翻」的地方，
 * 同时它又必须跨启动保存，所以不能放内存里。
 *
 * <h3>三种状态</h3>
 * <ul>
 *   <li>{@link State#FIRST_LAUNCH} —— 文件还不存在，说明是第一次启动；</li>
 *   <li>{@link State#GUIDE_PENDING} —— 引导做到一半退出了，带着 {@link #step()} 记住走到第几步；</li>
 *   <li>{@link State#NORMAL} —— 引导已完成，之后不再弹任何引导界面。</li>
 * </ul>
 *
 * <h3>文件格式</h3>
 * <pre>
 * state=guide_step_3
 * launches=7
 * </pre>
 * 用最简单的 {@code key=value} 而不是 TOML：里面没有段落、没有嵌套，
 * 人工查看时一眼能读完，出错时手改也能救回来。
 *
 * <h3>启动次数的口径</h3>
 * 每启动一次加一，<b>写盘发生在启动之后</b>而不是之前：这样即使上一次
 * 写到一半就崩了，计数也只是保守少一个，不会跳号。
 */
public final class GameState {

    private GameState() {}

    /** 状态文件相对游戏目录的位置。 */
    private static final String DIR = ".cache/verity_plus";
    private static final String FILE_NAME = "game_state";

    private static final String KEY_STATE = "state";
    private static final String KEY_LAUNCHES = "launches";

    /** 状态前缀：写成 {@code guide_step_3} 这种形式，人眼一看就知道走到哪了。 */
    private static final String GUIDE_PREFIX = "guide_step_";

    /** 引导总步数；{@link #step()} 的合法范围是 1..STEPS。 */
    public static final int STEPS = 5;

    /** 玩家的引导状态。 */
    public enum State {
        /** 第一次启动，要完整走一遍引导。 */
        FIRST_LAUNCH,
        /** 引导做到一半，续做时要先问玩家「是否继续」。 */
        GUIDE_PENDING,
        /** 已完成引导，不再弹任何引导界面。 */
        NORMAL
    }

    private static Path stateFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR).resolve(FILE_NAME);
    }

    // ---------- 内存缓存 ----------
    // 启动时读一次，之后都走内存。界面的 init() 会被窗口缩放反复调用，
    // 每帧读盘既没必要也可能读到写了一半的文件。

    private static volatile State state = null;
    private static volatile int step = 1;
    private static volatile int launches = 0;

    /**
     * 启动时调用一次：文件不存在就创建（状态 = 首次启动），
     * 存在就载入，然后把启动次数加一并写回。
     * <p>必须在任何界面可能读状态之前调用，通常是 {@code FMLClientSetupEvent}
     * 的 {@code enqueueWork} 里、配置初始化旁边。
     */
    public static void loadOrCreate() {
        Path file = stateFile();
        boolean created = false;
        try {
            if (Files.exists(file)) {
                readFromDisk(file);
            } else {
                state = State.FIRST_LAUNCH;
                step = 1;
                launches = 0;
                created = true;
            }
        } catch (Exception e) {
            // 文件损坏或读不动：当作首次启动处理。
            // 宁可多弹一次引导，也不要因为一个状态文件把玩家挡在流程之外。
            Log.warn("读取引导状态失败，按首次启动处理: " + e);
            state = State.FIRST_LAUNCH;
            step = 1;
            launches = 0;
            created = true;
        }

        // 本次启动也算一次
        launches = launches + 1;
        Log.info("引导状态: " + state + (state == State.GUIDE_PENDING ? "(第 " + step + " 步)" : "")
                + "，启动次数: " + launches + (created ? "（首次创建）" : ""));

        writeAsync();
    }

    /** 当前状态；尚未 {@link #loadOrCreate()} 时按首次启动处理。 */
    public static State state() {
        State current = state;
        return current == null ? State.FIRST_LAUNCH : current;
    }

    /** 当前步骤，取值 1..{@link #STEPS}；状态不是 {@link State#GUIDE_PENDING} 时没有意义。 */
    public static int step() {
        return clampStep(step);
    }

    /** 本次会话是第几次启动（从 1 开始）。 */
    public static int launches() {
        return launches;
    }

    /** 是否应该显示引导界面：首次启动，或者做到一半。 */
    public static boolean needsGuide() {
        return state() != State.NORMAL;
    }

    /**
     * 是否应该先问玩家「上次的配置未完成，是否继续」。
     * <p>只有做到一半的情况才问；首次启动是直接开讲。
     */
    public static boolean needsResumePrompt() {
        return state() == State.GUIDE_PENDING;
    }

    // ---------- 状态迁移 ----------

    /**
     * 进入引导的某一步。状态变成 {@link State#GUIDE_PENDING}。
     * <p>每走一步都立刻落盘：玩家可能在任意一步直接关游戏，
     * 下次进来应该回到他那一步，而不是从头再来。
     */
    public static void enterStep(int newStep) {
        step = clampStep(newStep);
        state = State.GUIDE_PENDING;
        writeAsync();
    }

    /** 引导完成：状态变为 {@link State#NORMAL}，之后不再弹。 */
    public static void finishGuide() {
        state = State.NORMAL;
        writeAsync();
    }

    /**
     * 把状态文件的 {@code state=} 行读出来。
     * <p>格式不认识时按首次启动处理，见 {@link #loadOrCreate()} 的说明。
     */
    private static void readFromDisk(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        State parsed = null;
        int parsedStep = 1;
        int parsedLaunches = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();

            if (KEY_STATE.equals(key)) {
                if (value.startsWith(GUIDE_PREFIX)) {
                    parsed = State.GUIDE_PENDING;
                    parsedStep = parseStep(value.substring(GUIDE_PREFIX.length()));
                } else if ("normal".equals(value)) {
                    parsed = State.NORMAL;
                } else if ("first_launch".equals(value)) {
                    parsed = State.FIRST_LAUNCH;
                }
                // 其它值一律不认识，parsed 保持 null，下面按首次启动处理
            } else if (KEY_LAUNCHES.equals(key)) {
                try {
                    parsedLaunches = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    // 计数坏了不该影响流程，归零重算
                    parsedLaunches = 0;
                }
            }
        }

        if (parsed == null) {
            Log.warn("引导状态文件内容无法识别，按首次启动处理: " + file);
            state = State.FIRST_LAUNCH;
            step = 1;
            launches = 0;
            return;
        }

        state = parsed;
        step = clampStep(parsedStep);
        launches = Math.max(0, parsedLaunches);
    }

    /** {@code guide_step_3} 里的 {@code 3}；解析失败按第 1 步。 */
    private static int parseStep(String raw) {
        try {
            return clampStep(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 把步数夹到合法范围；文件被手改成 0 或 99 也不会让界面找不到内容。 */
    private static int clampStep(int value) {
        if (value < 1) return 1;
        if (value > STEPS) return STEPS;
        return value;
    }

    private static String stateValue() {
        State current = state();
        if (current == State.NORMAL) return "normal";
        if (current == State.FIRST_LAUNCH) return "first_launch";
        return GUIDE_PREFIX + step;
    }

    // ---------- 磁盘写入 ----------

    /** 后台线程写盘，渲染线程不碰磁盘。 */
    private static void writeAsync() {
        // 先把要写的值抓成快照：写盘时状态可能又被改了，
        // 直接读静态字段会写出一个混合了新旧值的文件
        String stateText = stateValue();
        int launchesText = launches;
        ConfigIO.run(() -> writeToDisk(stateText, launchesText));
    }

    private static void writeToDisk(String stateText, int launchesText) {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
        } catch (Exception e) {
            Log.error("创建状态目录失败: " + file.getParent() + " -> " + e);
            return;
        }
        List<String> lines = List.of(
                "# Verity Plus 引导状态。删掉本文件即可重置为「首次启动」。",
                "# state 取值: first_launch / guide_step_1.." + STEPS + " / normal",
                KEY_STATE + "=" + stateText,
                KEY_LAUNCHES + "=" + launchesText);
        ConfigFileUtils.writeLines(file, lines);
    }

    /** 调试用：把状态重置回首次启动。 */
    public static void reset() {
        state = State.FIRST_LAUNCH;
        step = 1;
        writeAsync();
    }
}
