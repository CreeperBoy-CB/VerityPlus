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
 * <h3>状态与页面的对应</h3>
 * 引导一共七页，状态直接沿用两端的既有名字：
 * <table border="1">
 *   <caption>页面与状态</caption>
 *   <tr><th>页面</th><th>状态</th><th>下次启动</th></tr>
 *   <tr><td>欢迎页</td><td>{@code first_launch}</td><td>直接重新进欢迎页</td></tr>
 *   <tr><td>第 1~5 步</td><td>{@code guide_step_N}</td><td>问「是否继续」</td></tr>
 *   <tr><td>结束页</td><td>{@code normal}</td><td>不再弹</td></tr>
 * </table>
 *
 * <p>欢迎页用 {@code first_launch}：这时玩家还没动过任何东西，
 * 下次直接重进欢迎页即可，不需要打断他问「是否继续」。
 * <p>结束页用 {@code normal}：走到这里就等于配置完成，之后不再打扰。
 *
 * <h3>文件格式</h3>
 * <pre>
 * state=guide_step_3
 * launches=7
 * </pre>
 * 用 {@code key=value} 而不是 TOML：里面没有段落、没有嵌套，
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

    /** 正式步骤的步数（不含欢迎页与结束页）。 */
    public static final int STEPS = 5;

    /** 状态串：欢迎页 / 首次启动。 */
    private static final String NAME_FIRST_LAUNCH = "first_launch";
    /** 状态串前缀：正式步骤。 */
    private static final String NAME_STEP_PREFIX = "guide_step_";
    /** 状态串：结束页 / 已完成。 */
    private static final String NAME_NORMAL = "normal";

    /** 玩家的引导状态。 */
    public enum State {
        /**
         * 还没开始配置：文件不存在，或停在欢迎页。
         * <p>两者行为一致——都要弹引导，但都不问「是否继续」。
         */
        FIRST_LAUNCH,
        /** 配置到一半（第 1~5 步之一），续做时要先问玩家「是否继续」。 */
        GUIDE_PENDING,
        /** 配置已完成（停在结束页），之后不再弹任何引导界面。 */
        NORMAL
    }

    private static Path stateFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR).resolve(FILE_NAME);
    }

    // ---------- 内存缓存 ----------
    // 启动时读一次，之后都走内存。界面的 init() 会被窗口缩放反复调用，
    // 每帧读盘既没必要也可能读到写了一半的文件。

    private static volatile State state = null;
    /** {@link State#GUIDE_PENDING} 时表示第几步；其它状态无意义。 */
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
                resetInMemory();
                created = true;
            }
        } catch (Exception e) {
            // 文件损坏或读不动：当作首次启动处理。
            // 宁可多弹一次引导，也不要因为一个状态文件把玩家挡在流程之外。
            Log.warn("读取引导状态失败，按首次启动处理: " + e);
            resetInMemory();
            created = true;
        }

        // 本次启动也算一次
        launches = launches + 1;
        Log.info("引导状态: " + describe() + "，启动次数: " + launches
                + (created ? "（首次创建）" : ""));

        writeAsync();
    }

    /** 当前状态；尚未 {@link #loadOrCreate()} 时按首次启动处理。 */
    public static State state() {
        State current = state;
        return current == null ? State.FIRST_LAUNCH : current;
    }

    /** 当前是第几步（1..{@link #STEPS}）；不在中间步骤时返回 1。 */
    public static int step() {
        return clampStep(step);
    }

    /** 本次会话是第几次启动（从 1 开始）。 */
    public static int launches() {
        return launches;
    }

    /** 是否应该显示引导界面：还没完成（首次启动 / 停在欢迎页 / 做到一半）。 */
    public static boolean needsGuide() {
        return state() != State.NORMAL;
    }

    /**
     * 是否应该先问玩家「上次的配置未完成，是否继续」。
     * <p>只有停在第 1~5 步时才问。首次启动与停留欢迎页都直接重进欢迎页——
     * 玩家在欢迎页还没动过任何东西，没必要打断他。
     */
    public static boolean needsResumePrompt() {
        return state() == State.GUIDE_PENDING;
    }

    /** 当前是否停在欢迎页（状态为首次启动）。 */
    public static boolean atStartPage() {
        return state() == State.FIRST_LAUNCH;
    }

    /** 当前是否停在结束页（状态为已完成）。 */
    public static boolean atFinishPage() {
        return state() == State.NORMAL;
    }

    // ---------- 状态迁移 ----------

    /**
     * 进入欢迎页：状态记作 {@code first_launch}。
     * <p>等价于「还没真正开始配置」，所以下次启动直接重进欢迎页。
     */
    public static void enterStart() {
        state = State.FIRST_LAUNCH;
        step = 1;
        writeAsync();
    }

    /** 进入第 {@code step} 步（1 起算），状态记作 {@code guide_step_N}。 */
    public static void enterStep(int newStep) {
        state = State.GUIDE_PENDING;
        step = clampStep(newStep);
        writeAsync();
    }

    /**
     * 进入结束页：状态记作 {@code normal}。
     * <p>走到结束页就等于配置完成，玩家在这里关游戏也不会被再问一次。
     */
    public static void enterFinish() {
        state = State.NORMAL;
        writeAsync();
    }

    /** 引导完成（等同 {@link #enterFinish()}，保留给流程收尾调用）。 */
    public static void finishGuide() {
        enterFinish();
    }

    /** 把内存里的状态重置为首次启动（不写盘）。 */
    private static void resetInMemory() {
        state = State.FIRST_LAUNCH;
        step = 1;
        launches = 0;
    }

    /**
     * 把状态文件的 {@code state=} 行读出来。
     * <p>格式不认识时按首次启动处理，见 {@link #loadOrCreate()} 的说明。
     */
    private static void readFromDisk(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        State parsedState = null;
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
                if (NAME_FIRST_LAUNCH.equals(value)) {
                    parsedState = State.FIRST_LAUNCH;
                    parsedStep = 1;
                } else if (value.startsWith(NAME_STEP_PREFIX)) {
                    parsedState = State.GUIDE_PENDING;
                    parsedStep = clampStep(parseStep(value));
                } else if (NAME_NORMAL.equals(value)) {
                    parsedState = State.NORMAL;
                }
                // 其它值一律不认识，parsedState 保持 null，下面按首次启动处理
            } else if (KEY_LAUNCHES.equals(key)) {
                try {
                    parsedLaunches = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    // 计数坏了不该影响流程，归零重算
                    parsedLaunches = 0;
                }
            }
        }

        if (parsedState == null) {
            Log.warn("引导状态文件内容无法识别，按首次启动处理: " + file);
            resetInMemory();
            return;
        }

        state = parsedState;
        step = parsedStep;
        launches = Math.max(0, parsedLaunches);
    }

    /** {@code guide_step_3} 里的 {@code 3}；解析失败按第 1 步。 */
    private static int parseStep(String raw) {
        try {
            return Integer.parseInt(raw.substring(NAME_STEP_PREFIX.length()));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return 1;
        }
    }

    /** 把步数夹到合法范围；文件被手改成 0 或 99 也不会让界面找不到内容。 */
    private static int clampStep(int value) {
        if (value < 1) return 1;
        if (value > STEPS) return STEPS;
        return value;
    }

    /** 供日志用的状态描述，中间步骤带上步号。 */
    private static String describe() {
        return state() == State.GUIDE_PENDING ? state + "（第 " + step + " 步）" : String.valueOf(state());
    }

    private static String stateValue() {
        State current = state();
        if (current == State.NORMAL) return NAME_NORMAL;
        if (current == State.FIRST_LAUNCH) return NAME_FIRST_LAUNCH;
        return NAME_STEP_PREFIX + step;
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
                "# state 取值:",
                "#   first_launch       欢迎页（或从未开始），下次直接重进欢迎页",
                "#   guide_step_1.." + STEPS + "    第 1~" + STEPS + " 步，下次启动会问「是否继续」",
                "#   normal             结束页 / 已完成，不再弹出",
                KEY_STATE + "=" + stateText,
                KEY_LAUNCHES + "=" + launchesText);
        ConfigFileUtils.writeLines(file, lines);
    }

    /** 调试用：把状态重置回首次启动。 */
    public static void reset() {
        resetInMemory();
        writeAsync();
    }
}
