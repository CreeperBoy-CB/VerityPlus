package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.client.ModpackHelperBridge;
import com.cb2495.verityplus.client.OllamaSetupScreenZh;
import com.cb2495.verityplus.config.GameState;
import com.cb2495.verityplus.config.Providers;
import com.cb2495.verityplus.config.VerityConfigManager;
import com.cb2495.verityplus.util.Log;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 引导教程界面。
 *
 * <h3>七页</h3>
 * <pre>
 * 欢迎页 → 第 1 步 → … → 第 5 步 → 结束页
 * </pre>
 * 欢迎页已有真实文案（见 {@link #renderStartText}）；中间 5 步与结束页仍是占位文字
 * （「这是引导教程第 x 步」「配置已结束」），真实内容（选提供商、填 Key 等）之后再往里填。
 *
 * <h3>按钮</h3>
 * <ul>
 *   <li>左上角「←」—— 每一页都显示，回到上一页。欢迎页上等同「跳过」；</li>
 *   <li>主按钮 —— 欢迎页是「开始」，中间步骤是「继续」，结束页是「完成」；</li>
 *   <li>「跳过」—— 只在欢迎页显示，放在主按钮下方；</li>
 *   <li>「导入旧存档」—— 只在欢迎页、且装了 Modpack Helper 时显示，
 *       放在「跳过」下方，打开那边的旧存档导入界面。</li>
 * </ul>
 *
 * <h3>与状态文件的关系</h3>
 * 每进入一页就写一次 {@link GameState}，所以玩家在任意一页关掉游戏，
 * 下次启动都会从他停下那页问「是否继续」。结束页点「完成」
 * （或欢迎页点「跳过」）则 {@link GameState#finishGuide()} 置为 normal。
 */
public class GuideScreen extends Screen {

    // ---------- 布局 ----------
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 20;
    /** 相邻按钮之间的间隙。 */
    private static final int BUTTON_GAP = 5;
    /** 按钮行距离屏幕底部的边距。 */
    private static final int BUTTON_BOTTOM_MARGIN = 40;

    // ---------- 欢迎页正文 ----------
    /** 正文块里每行之间的间隙（在 {@code font.lineHeight} 之上额外加的空隙）。 */
    private static final int TEXT_LINE_GAP = 4;
    /** 正文按窗口宽度折行时，左右两边保留的空白。 */
    private static final int TEXT_SIDE_MARGIN = 40;

    /** 欢迎语的金色。 */
    private static final int COLOR_HEADING = 0xFFFFD700;
    /** 正文主句的白色。 */
    private static final int COLOR_BODY = 0xFFFFFFFF;
    /** 次要说明（导入旧存档、跳过按钮）的灰色。 */
    private static final int COLOR_HINT = 0xFFAAAAAA;

    /** 左上角小返回按钮的尺寸，比普通按钮矮一些，不抢视觉重心。 */
    private static final int BACK_BUTTON_WIDTH = 20;
    private static final int BACK_BUTTON_HEIGHT = 20;
    /** 小返回按钮与屏幕左边缘、上边缘的距离。 */
    private static final int BACK_BUTTON_MARGIN = 6;

    /** 标题与「←」按钮之间的水平间隙。 */
    private static final int TITLE_GAP = 6;

    // ---------- 进度条 ----------
    /** 进度条高度。 */
    private static final int BAR_HEIGHT = 4;
    /** 进度条右端与屏幕右边缘的留白。 */
    private static final int BAR_RIGHT_MARGIN = 16;
    /** 标题文字与进度条之间的水平间隙。 */
    private static final int BAR_TITLE_GAP = 8;
    /** 每步分成几份；15 份 ÷ 5 步 = 每步 3 份。 */
    private static final int SUB_STEPS = 3;
    /** 步骤细条：宽 1px、高 6px，比进度条上下各多出 1px 更醒目。 */
    private static final int TICK_WIDTH = 1;
    private static final int TICK_HEIGHT = 6;
    /** 细条标签与「2/5」共用的字号缩放。 */
    private static final float TICK_LABEL_SCALE = 0.75f;
    /** 标签行与标题行之间的垂直间隙。 */
    private static final int TICK_LABEL_GAP = 2;

    // 颜色
    /** 进度条底槽（深灰）。 */
    private static final int COLOR_TRACK = 0xFF333333;
    /** 已完成的填充（绿色）。 */
    private static final int COLOR_FILL = 0xFF4CAF50;
    /** 还没到的步骤（淡灰）。 */
    private static final int COLOR_TODO = 0xFF777777;
    /** 当前步骤（绿色，与填充同色）。 */
    private static final int COLOR_CURRENT = 0xFF4CAF50;
    /** 已完成的步骤（金色）。 */
    private static final int COLOR_DONE = 0xFFFFC107;

    /** 「2/5」的颜色（灰色）。 */
    private static final int PROGRESS_COLOR = 0xFF888888;

    /** 页面位置：欢迎页、中间的第 1..STEPS 步、结束页。 */
    private enum Position {
        /** 欢迎页。 */
        START,
        /** 正式步骤。 */
        STEP,
        /** 结束页。 */
        FINISH
    }

    private final Position position;
    /** {@link Position#STEP} 时有效，取值 1..{@link GameState#STEPS}。 */
    private final int step;

    /**
     * 第 1 步（选提供商）当前选中的下拉框文字，{@code null} 表示还是「（请选择）」。
     * <p>做成静态：下拉框的选择要能跨页保存——玩家往下翻再翻回来、
     * 或者去了 Ollama 配置页再回来，都不该回到「（请选择）」。
     * 静态值只在文件末尾的 setter 里写，和进度条那组动画状态是同一套做法。
     */
    private static String pickedProvider = null;

    /**
     * 第 1 步「自定义」填的 Endpoint。同样是静态，理由同上，
     * 而且比选中项更需要保住：那是一段手打出来的地址。
     */
    private static String customEndpoint = "";

    /**
     * 结束引导后要做的事（回到来处）；为 {@code null} 表示直接关掉回游戏。
     * <p>用回调而不是直接引用 Verity 的配置界面：让依赖保持单向
     * {@code client → screen}，{@code GuideScreen} 不必知道配置界面怎么构造。
     */
    private final Runnable onFinished;

    // ---------- 进度条运行时状态 ----------
    /**
     * 本页当前步骤已完成的份数（0..{@link #SUB_STEPS}）。
     * <p>进入页面时从 0 开始，由调试按钮「+1」推进；推到满则自动进入下一页。
     */
    private int subStepProgress = 0;
    /** 本页已经点了几次「+1」，上限 {@link #DEBUG_CLICK_LIMIT}。 */
    private int debugClicks = 0;

    /**
     * 「+1」按钮每页最多点几次。
     * <p>一步分 {@link #SUB_STEPS} 份，点满这些份这一步就完成了，
     * 再点一次就是下一步——所以上限就是份数本身。
     */
    private static final int DEBUG_CLICK_LIMIT = SUB_STEPS;

    /** 调试「+1」按钮尺寸。 */
    private static final int PLUS_BUTTON_SIZE = 20;
    private static final int PLUS_BUTTON_HEIGHT = 16;

    /** 调试「+1」按钮的引用，用于在次数用尽后置灰。 */
    private Button plusButton;

    /** 进度条是否已经展开（点了「开始」之后为 true）。 */
    private boolean barExpanded = false;

    /**
     * 进度条<b>当前实际显示</b>的份数，由 {@link #tickBarAnimation()} 逐帧算出。
     * <p>故意做成静态：翻页会重建 {@link GuideScreen} 实例，若跟着实例走，
     * 每翻一页都会从目标值重新开始，看起来像「瞬移 + 重放」。
     * 放在类级别就能跨页面连续动画。
     */
    private static float shownSubSteps = 0.0f;
    /** 上一帧的时间戳。 */
    private static long lastAnimFrameMs = 0L;

    // 补间状态：从 animFrom 缓出到 animTo
    private static float animFrom = 0.0f;
    private static float animTo = 0.0f;
    private static long animStartMs = 0L;
    private static boolean animating = false;

    /** 出现动画的起始时间：点「开始」后首次绘制时记下。 */
    private static long appearStartMs = 0L;

    /**
     * 本轮引导里<b>到达过</b>的最高步号（1..STEPS），0 表示还没进过任何一步。
     * <p>与「当前是第几步」区分开：玩家可以点金色细条往回翻，
     * 回去之后后面那些步依然算「到达过」，颜色不该褪掉。
     * <p>同样做成静态，跟随整轮引导存活；{@link #resetBarAnimation()} 会清零。
     */
    private static int maxReachedStep = 0;

    /**
     * 进度条<b>展开</b>动画的时长（毫秒）。
     * <p>这条动画只在第一次显示进度条时播一次，节奏可以慢一些、更有「铺开」的仪式感，
     * 所以用 1000ms 配 {@code LinearOutSlowIn}（先匀速后减速，Material 的标准进场曲线）。
     */
    private static final long BAR_APPEAR_MS = 1000L;

    /**
     * 进度条<b>前进 / 后退</b>动画的时长（毫秒）。
     * <p>用指数缓出：开头极快、末尾极缓，翻页时数字变化立刻可见，尾巴又不会显得生硬。
     */
    private static final long BAR_MOVE_MS = 600L;

    // ---------- 构造 ----------

    private GuideScreen(Position position, int step, Runnable onFinished) {
        super(Component.literal("引导教程"));
        this.position = position;
        this.step = Math.max(1, Math.min(GameState.STEPS, step));
        this.onFinished = onFinished;
    }

    /**
     * 欢迎页。
     * <p>每次从欢迎页开始都视为「新的一轮引导」，把进度动画的静态状态清空，
     * 这样再点「开始」时进度条会重新播放出现动画。
     */
    public static GuideScreen start(Runnable onFinished) {
        resetBarAnimation();
        resetStepAnswers();
        return new GuideScreen(Position.START, 1, onFinished);
    }

    /** 清空进度条的所有动画状态，让下一轮引导从头播放。 */
    private static void resetBarAnimation() {
        shownSubSteps = 0.0f;
        lastAnimFrameMs = 0L;
        animFrom = 0.0f;
        animTo = 0.0f;
        animStartMs = 0L;
        animating = false;
        appearStartMs = 0L;
        maxReachedStep = 0;
    }

    /**
     * 欢迎页，但<b>保留</b>各步骤已经填好的内容。
     * <p>用于第 1 步按「←」退回欢迎页：进度条动画重来一遍没什么损失，
     * 但玩家刚选的提供商不该被抹掉。他再点「开始」时还是原来那一项。
     */
    private static GuideScreen startKeepingAnswers(Runnable onFinished) {
        resetBarAnimation();
        return new GuideScreen(Position.START, 1, onFinished);
    }

    /**
     * 清空各步骤自己记住的填写结果（第 1 步的下拉框与自定义地址）。
     * <p>只在<b>新一轮</b>引导开始时调用：从第 N 步按「←」退回第 N-1 步不算新一轮，
     * 那时玩家的选择必须还在。
     */
    private static void resetStepAnswers() {
        pickedProvider = null;
        customEndpoint = "";
    }
    /** 第 {@code step} 步（1 起算）。 */
    public static GuideScreen step(int step, Runnable onFinished) {
        return new GuideScreen(Position.STEP, step, onFinished);
    }

    /**
     * 第 1 步：选提供商。
     * <p>从别的界面（Ollama 配置页、导入旧存档）回来时用这个，
     * 它<b>不</b>清空已选中的提供商——玩家回来看的就是他刚才选的那一项。
     */
    public static void openStep1(Runnable onFinished) {
        Minecraft.getInstance().setScreen(step(1, onFinished));
    }

    /** 结束页。 */
    public static GuideScreen finish(Runnable onFinished) {
        return new GuideScreen(Position.FINISH, 1, onFinished);
    }

    // ---------- 入口 ----------

    /** 从欢迎页开始，用于首次启动。 */
    public static void openFromStart() {
        Minecraft.getInstance().setScreen(start(null));
    }

    /**
     * 用状态文件里记录的页打开，用于「上次的配置未完成」。
     * <p>状态里记的是 {@code guide_step_N}，这里还原成对应的页面对象。
     */
    public static void openResume() {
        Minecraft.getInstance().setScreen(fromCurrentPage(null));
    }

    /**
     * 从配置界面主动进入引导，结束后执行 {@code onFinished} 回到来处。
     * <p>用于 AI 设置里的「配置引导」按钮。
     * <p>参数 {@code caller} 只是为了与 YACL 的回调签名对齐，实际不使用——
     * 它给的那个界面实例在关闭后无法复用，调用方应传一个重建界面的动作进来。
     * <p>从欢迎页开始：玩家是主动选择「重新配一遍」。
     */
    public static void openFrom(Screen caller, Runnable onFinished) {
        Minecraft.getInstance().setScreen(start(onFinished));
    }

    /**
     * 按当前记录的状态还原页面。
     * <p>顺序必须与 {@link #init()} 里写状态的那段对应，否则会写串。
     */
    public static GuideScreen fromCurrentPage(Runnable onFinished) {
        if (GameState.atStartPage()) return start(onFinished);
        if (GameState.atFinishPage()) return finish(onFinished);
        return step(GameState.step(), onFinished);
    }

    // ---------- 初始化 ----------

    @Override
    protected void init() {
        super.init();

        // 进入本页就落盘：玩家在这里直接关游戏也能正确记住进度。
        // 这一段决定了状态文件里写什么，改动时要与 fromCurrentPage 同步。
        switch (position) {
            case START -> GameState.enterStart();
            case STEP -> GameState.enterStep(step);
            case FINISH -> GameState.enterFinish();
        }

        int buttonY = this.height - BUTTON_BOTTOM_MARGIN;

        // 左上角「←」：每一页都有。欢迎页上它没有上一页，等同「跳过」。
        this.addRenderableWidget(Button.builder(Component.literal("←"), btn -> prev())
                .pos(BACK_BUTTON_MARGIN, BACK_BUTTON_MARGIN)
                .size(BACK_BUTTON_WIDTH, BACK_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal(
                        position == Position.START ? "跳过引导" : "上一步")))
                .build());

        // 主按钮文案随页面变化：
        //   欢迎页「开始」→ 中间各步统一「继续」→ 结束页「完成」
        Component mainLabel = switch (position) {
            case START -> Component.literal("开始");
            case STEP -> Component.literal(step == STEP_PROVIDER ? "确认" : "继续");
            case FINISH -> Component.literal("完成");
        };

        int centerX = (this.width - BUTTON_WIDTH) / 2;
        // 欢迎页的按钮竖排成一列（从上到下：开始 → 跳过 → 导入旧存档），
        // 整列以 buttonY 为底向上排，这样按钮增删都不会让这一组跑偏
        boolean showSkip = position == Position.START;
        boolean showImport = position == Position.START && canImportLegacy();

        // 欢迎页的按钮竖排成一列（自上而下：开始 → 跳过 → 导入旧存档）。
        // 用固定的三行坐标，而不是互相减来减去：
        // 导入旧存档在最底行，跳过在它上面一行，开始在再上面。
        // 这样按钮增删时只需改一个常量，不会再出现两个按钮算到同一个 y。
        int importY = buttonY;
        int skipY = buttonY - (BUTTON_HEIGHT + BUTTON_GAP);
        int startY = buttonY - (BUTTON_HEIGHT + BUTTON_GAP) * 2;

        // 没装 Modpack Helper 时最底下没有「导入旧存档」，整列往上补一行，
        // 让「跳过」落到最底行、「开始」落在它上面
        if (showSkip && !showImport) {
            skipY = buttonY;
            startY = buttonY - (BUTTON_HEIGHT + BUTTON_GAP);
        }

        int mainY = showSkip ? startY : buttonY;

        this.addRenderableWidget(Button.builder(mainLabel, btn -> next())
                .pos(centerX, mainY).size(BUTTON_WIDTH, BUTTON_HEIGHT).build());

        if (showSkip) {
            // 原版按钮本身就是灰底，这里把文字改成白色
            this.addRenderableWidget(Button.builder(
                            Component.literal("跳过").withStyle(ChatFormatting.WHITE), btn -> skip())
                    .pos(centerX, skipY)
                    .size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        // 「导入旧存档」由 Modpack Helper 提供；没装那个模组时这一项连文案都不该出现
        if (showImport) {
            this.addRenderableWidget(Button.builder(
                            Component.literal("导入旧存档").withStyle(ChatFormatting.GRAY), btn -> importLegacy())
                    .pos(centerX, importY).size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        // 第 1 步自己的控件：提供商下拉框、Ollama 入口、自定义 Endpoint 输入框
        if (position == Position.STEP && step == STEP_PROVIDER) {
            initProviderPicker(buttonY);
        }

        // 进度条要等点了「开始」才出现；欢迎页上它一直处于未展开状态。
        // 出现动画只在真正第一次显示时播一次（appearStartMs 由 appearProgress 首帧设置），
        // 之后翻页走的是绿条推进动画，不会再「重新长一遍」。
        barExpanded = position != Position.START;

        // 记录到达过的最远步骤：金色细条据此上色，往回翻也不会褪色
        if (position == Position.STEP) {
            maxReachedStep = Math.max(maxReachedStep, step);
        } else if (position == Position.FINISH) {
            maxReachedStep = GameState.STEPS;
        }

        // 调试用「+1」：只在 1~5 页显示，每页最多点 DEBUG_CLICK_LIMIT 次。
        // 位置放在标签行下方右侧，避开进度条本身。
        if (position == Position.STEP) {
            int plusX = barRight(this.width) - PLUS_BUTTON_SIZE;
            int plusY = labelRowY() + Math.round(this.font.lineHeight * TICK_LABEL_SCALE) + 4;
            Button plus = Button.builder(Component.literal("+1"), btn -> advanceOneSubStep())
                    .pos(plusX, plusY).size(PLUS_BUTTON_SIZE, PLUS_BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.literal("调试：进度 +1")))
                    .build();
            plus.active = debugClicks < DEBUG_CLICK_LIMIT;
            this.plusButton = plus;
            this.addRenderableWidget(plus);
        }
    }

    // ---------- 流程 ----------

    private void prev() {
        Minecraft mc = Minecraft.getInstance();
        switch (position) {
            case START -> {
                // 欢迎页没有上一页：等同「跳过」
                skip();
                return;
            }
            case STEP -> {
                if (step > 1) {
                    mc.setScreen(GuideScreen.step(step - 1, onFinished));
                } else {
                    // 第 1 步的上一页是欢迎页。
                    // 这里不能走 start()：那个工厂会清空各步骤的填写结果，
                    // 玩家只是退回欢迎页看一眼，再点「开始」时选择必须还在。
                    mc.setScreen(GuideScreen.startKeepingAnswers(onFinished));
                }
                return;
            }
            case FINISH -> {
                mc.setScreen(GuideScreen.step(GameState.STEPS, onFinished));
                return;
            }
        }
    }

    private void next() {
        Minecraft mc = Minecraft.getInstance();
        switch (position) {
            case START -> mc.setScreen(GuideScreen.step(1, onFinished));
            case STEP -> {
                // 第 1 步要先选好提供商：没选就往下走，下一步会拿着空配置继续，
                // 玩家到最后才发现前面白填。（可点金色细条跳页，那条路不校验。）
                if (step == STEP_PROVIDER) {
                    if (!providerChosen()) {
                        showProviderHint("请先选择一个提供商", COLOR_WARN);
                        return;
                    }
                    // 自定义网址校验没通过（红）或还在验证（黄）时不许往下走。
                    // 按住 Ctrl 可以强行通过：玩家可能就是想先配完再回来改。
                    if (endpointCheckBlocksNext() && !forceNextHeld()) {
                        Log.debug("[Guide] CONFIRM_BLOCKED | BY=ENDPOINT_CHECK | VERDICT=" + endpointCheck.level());
                        showProviderHint(endpointCheckBlockedHint(), COLOR_WARN);
                        return;
                    }
                    // 按住 Ctrl 绕过了校验：记一条日志，排查时能对上是玩家主动跳过
                    if (endpointCheckBlocksNext()) {
                        Log.info("[Guide] CONFIRM_FORCED | BY=CTRL | VERDICT=" + endpointCheck.level()
                                + " | INPUT=" + customEndpoint);
                    }
                    // Ctrl 强制通过时，地址还没验证过；仍然把填的内容写进配置，
                    // 否则玩家强过一步反而丢了刚输入的地址
                    if (!commitProviderChoice()) return;
                }
                if (step >= GameState.STEPS) {
                    mc.setScreen(GuideScreen.finish(onFinished));
                } else {
                    mc.setScreen(GuideScreen.step(step + 1, onFinished));
                }
            }
            case FINISH -> finish();
        }
    }

    private void skip() {
        finish();
    }

    // ================= 第 1 步：选提供商 =================

    /** 第 1 步的步号。 */
    private static final int STEP_PROVIDER = 1;

    /** 第 1 步的标题。 */
    private static final String PROVIDER_QUESTION = "首先，请选择 Verity AI 大脑的提供商";

    /**
     * 列表左右两侧与屏幕边缘的留白。
     * <p>置 0：表格横跨整个屏幕宽度，只留 1px 给边框不掉出屏幕。
     */
    private static final int PICKER_SIDE_MARGIN = 1;
    /** 左列（提供商名）占的宽度，右列从这条竖线之后开始。 */
    private static final int PICKER_NAME_WIDTH = 90;
    /** 表头高度。 */
    private static final int PICKER_HEADER_HEIGHT = 14;
    /** 一项（一个「块」）的内容行高，不含块与块之间的空隙。 */
    private static final int PICKER_ROW_HEIGHT = 16;
    /** 块与块之间的空隙：每项独立成框，靠空隙拉开距离。 */
    private static final int PICKER_ROW_GAP = 3;
    /** 左列文字距左边缘的内边距。 */
    private static final int PICKER_TEXT_INSET = 4;
    /** 滚动条宽度。 */
    private static final int PICKER_SCROLL_WIDTH = 4;

    /** 一项占的总高度：内容 + 下方空隙。 */
    private static final int PICKER_ITEM_HEIGHT = PICKER_ROW_HEIGHT + PICKER_ROW_GAP;

    /** 提示文字的颜色：正常（中性）、警告（红）、成功（绿）。 */
    private static final int COLOR_NEUTRAL = 0xFFAAAAAA;
    private static final int COLOR_WARN = 0xFFFF5555;
    private static final int COLOR_OK = 0xFF55FF55;

    /** 表格底色、边框与项底色。 */
    private static final int COLOR_LIST_BG = 0xC0000000;
    private static final int COLOR_LIST_BORDER = 0xFFAAAAAA;
    private static final int COLOR_ITEM_BG = 0xFF1E1E1E;
    private static final int COLOR_ITEM_BORDER = 0xFF777777;
    private static final int COLOR_ROW_SELECTED = 0xFF4CAF50;

    // ---------- 自定义网址校验的配色 ----------
    /** 通过：绿。 */
    private static final int COLOR_CHECK_OK = 0xFF55FF55;
    /** 格式错 / 404 / 403：红。 */
    private static final int COLOR_CHECK_ERROR = 0xFFFF5555;
    /** 只是提醒：橙。 */
    private static final int COLOR_CHECK_WARN = 0xFFFFAA00;
    /** 正在验证：黄。 */
    private static final int COLOR_CHECK_PENDING = 0xFFFFFF55;
    /** 「点击此处」超链接：青色。 */
    private static final int COLOR_RETRY_LINK = 0xFF55FFFF;

    /** 校验图标与后面文字的间隙。图标宽度由字体实测，不再写死。 */
    private static final int CHECK_ICON_GAP = 3;

    /** 「自定义」的 Endpoint 输入框，渲染与按键判断都要用。 */
    private EditBox endpointBox;

    /**
     * 第 1 步当前要显示的提示文字与颜色。
     * <p>没有提示时它就是一条空的占位，占据同一行高度——
     * 否则提示一出现就会把下面的按钮往下顶，点「确认」时按钮会在脚下跳走。
     */
    private String providerHint = "";
    private int providerHintColor = COLOR_NEUTRAL;

    /** 列表滚动偏移（像素）。 */
    private int pickerScroll = 0;

    /** 「点击此处」链接在屏幕上的区域，render 时更新，点击时读取。 */
    private int ollamaLinkLeft = 0;
    private int ollamaLinkRight = 0;
    private int ollamaLinkTop = 0;
    private int ollamaLinkBottom = 0;

    /** 当前是「自定义」：右侧说明换成了 Endpoint 输入框。 */
    private boolean customMode() {
        return Providers.CUSTOM_LABEL.equals(pickedProvider);
    }

    // ---------- 自定义 Endpoint 的校验状态 ----------

    /**
     * 校验结论的图标。
     * <p>直接用字体里现成的符号（{@code √ × ●}），不手画像素。
     * 这三个字符都在原版中文字体里，渲染出来清晰且大小自动跟随字号。
     */
    private enum Mark {
        /** 绿钩。 */
        CHECK("√"),
        /** 红叉。 */
        CROSS("×"),
        /** 橙点。 */
        DOT_WARN("●"),
        /** 黄点。 */
        DOT_PENDING("●");

        /** 显示的符号。 */
        private final String symbol;

        Mark(String symbol) {
            this.symbol = symbol;
        }

        String symbol() {
            return symbol;
        }
    }

    /** 校验当前状态；{@code null} 表示还没开始检查（或不需要检查）。 */
    private EndpointValidator.Result endpointCheck = null;

    /** 上一次输入的时间戳，用于「停止输入 2 秒后再探活」的防抖。 */
    private long lastEndpointEditMs = 0L;

    /** 到点要发起的探活时间戳；{@code <= 0} 表示当前没有排队的探活。 */
    private long pendingProbeAt = 0L;
    /** 排队那次探活的代号，用于丢弃过期结果。 */
    private int pendingProbeGeneration = -1;
    /** 排队那次探活要查的地址。 */
    private String pendingProbeEndpoint = "";

    /**
     * 结论里「[点击此处]重新尝试」的可点区域，渲染时更新、点击时读取。
     */
    private int retryLinkLeft = 0;
    private int retryLinkRight = 0;
    private int retryLinkTop = 0;
    private int retryLinkBottom = 0;

    /**
     * 重新安排一次校验。
     * <p>先做格式判断（瞬时），格式对了才进入「等输入停止 → 后台探活」的流程。
     * <p>每次调用都会作废在途请求，避免先发的慢请求盖掉新结果。
     */
    private void scheduleEndpointCheck() {
        lastEndpointEditMs = System.currentTimeMillis();
        // 作废在途请求：序号一变，旧结果回来时会被丢掉
        int generation = EndpointValidator.invalidate();

        String text = endpointBox != null ? endpointBox.getValue().trim() : customEndpoint.trim();
        EndpointValidator.Result formatProblem = EndpointValidator.checkFormat(text);
        if (formatProblem != null) {
            // 格式就不对，不必发请求
            endpointCheck = formatProblem;
            return;
        }
        if (text.isEmpty()) {
            // 空地址：不报错也不探活
            endpointCheck = null;
            return;
        }
        // 格式没问题：先挂上「正在验证」，等 2 秒后再真正发请求
        endpointCheck = new EndpointValidator.Result(
                EndpointValidator.Level.PENDING, "正在验证网址……", false);
        pendingProbeAt = lastEndpointEditMs + EndpointValidator.DEBOUNCE_MS;
        pendingProbeGeneration = generation;
        pendingProbeEndpoint = text;
    }

    /**
     * 防抖到点后发起后台探活。
     * <p>放在 {@code tick()} 里而不是用定时器线程：界面关闭后不该再发请求，
     * 挂在界面的 tick 上天然具备这个生命周期。
     */
    private void tickEndpointCheck() {
        if (pendingProbeAt <= 0L) return;
        if (System.currentTimeMillis() < pendingProbeAt) return;

        int gen = pendingProbeGeneration;
        String endpoint = pendingProbeEndpoint;
        pendingProbeAt = 0L;
        pendingProbeGeneration = -1;
        pendingProbeEndpoint = "";

        // 期间又输入过：这次探活已经作废
        if (gen != EndpointValidator.currentGeneration()) return;

        EndpointValidator.probeAsync(endpoint, gen, result -> endpointCheck = result);
    }

    /** 立刻重试（点「[点击此处]重新尝试」）。 */
    private void retryEndpointCheck() {
        scheduleEndpointCheck();
        // 跳过 2 秒防抖：玩家已经明确要求重试，不该再等
        pendingProbeAt = System.currentTimeMillis();
    }

    /**
     * 当前状态是否禁止「下一步」。
     * <p>只有红（格式错 / 404 / 403）与黄（正在验证）拦人，由
     * {@link EndpointValidator.Result#blocksNext()} 判定。
     */
    private boolean endpointCheckBlocksNext() {
        return customMode() && endpointCheck != null && endpointCheck.blocksNext();
    }

    /** 被拦下时给出的说明，告诉玩家还有 Ctrl 这条后路。 */
    private String endpointCheckBlockedHint() {
        return "网址未通过验证，无法继续（按住 Ctrl 点击「确认」可强制通过）";
    }

    /**
     * 「确认」这一次点击是否按住了 Ctrl。
     * <p>从窗口当前的修饰键状态读，而不是靠事件参数：按钮回调拿不到它。
     */
    private boolean forceNextHeld() {
        long handle = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    /** 点击是否落在「[点击此处]重新尝试」上。 */
    private boolean retryLinkClicked(double mouseX, double mouseY) {
        return retryLinkRight > retryLinkLeft
                && mouseX >= retryLinkLeft && mouseX < retryLinkRight
                && mouseY >= retryLinkTop && mouseY < retryLinkBottom;
    }

    /** 已经选好了提供商（「（请选择）」不算）。 */
    private boolean providerChosen() {
        return pickedProvider != null && !Providers.PICKER_PLACEHOLDER.equals(pickedProvider);
    }

    /**
     * 第 1 步的控件布局。
     * <p>表格是自绘的（见 {@link #renderProviderList}），这里只放真正需要
     * 事件系统的控件：自定义模式的 Endpoint 输入框、「确认」。
     * <p>原来那个 240px 宽的下拉框铺不满屏幕，换成了一整块两列的滚动表格。
     */
    private void initProviderPicker(int buttonY) {
        int centerX = this.width / 2;

        // 自定义模式：输入框<b>盖住「自定义」那一项的介绍文字</b>，
        // 位置和长度都与右列完全一致，看起来像那一行的介绍变成了输入框
        if (customMode()) {
            // 左缘给 descX：保持 bordered 为 true，文字自带 4px 内边距，
            // 所以控件左缘比文字起点再左 4px，正好与其它行的介绍对齐
            int boxLeft = pickerDescX() - 4;
            int boxRight = pickerRowRight() - 1;
            int boxWidth = Math.max(40, boxRight - boxLeft);
            // 自带边框换成绿色的变体：原版的白框压在绿色选中框里会像「框中框」
            EditBox box = new GreenBorderedEditBox(this.font, boxLeft, customBoxY(), boxWidth,
                    PICKER_ROW_HEIGHT, Component.literal("AI Endpoint"));
            box.setMaxLength(512);
            box.setValue(customEndpoint);
            box.setHint(Component.literal("请填入 AI 提供商文档中的AI Endpoint")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            box.setResponder(this::onCustomEndpointEdited);
            this.endpointBox = box;
            this.addRenderableWidget(box);
        } else {
            this.endpointBox = null;
        }

        // 「确认」放在按钮区最下面那一行的位置，和其它页的主按钮齐平
        this.addRenderableWidget(Button.builder(Component.literal("确认"), btn -> next())
                .pos(centerX - BUTTON_WIDTH / 2, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /**
     * 自定义模式下输入框的 Y：与「自定义」那一项的介绍文字同高。
     * <p>输入框把这行介绍覆盖掉，所以它的 Y 就是那一项的内容行起点。
     * <p>滚动时「自定义」可能被滚出可视区，那时把 Y 夹到表格内，
     * 免得输入框跑到表格外面的标题或按钮上——它是个真控件，
     * 藏起来会变成一个看不见却还能聚焦的幽灵。
     */
    private int customBoxY() {
        int rows = pickerItems().size();
        int rowTop = pickerRowsTop() + (rows - 1) * PICKER_ITEM_HEIGHT - pickerScroll;
        int minY = pickerRowsTop();
        int maxY = pickerListBottom() - 1 - PICKER_ROW_HEIGHT;
        return Math.max(minY, Math.min(maxY, rowTop));
    }

    /**
     * 界面语言是不是简体中文。
     * <p>只用来决定那个「非简中时向后排」的顺序，不参与任何配置写入。
     */
    private static boolean isSimplifiedChinese() {
        return "zh_cn".equalsIgnoreCase(Minecraft.getInstance().getLanguageManager().getSelected());
    }

    /** 当前语言下列表里要显示的全部项目。 */
    private static List<Providers.PickerOption> pickerItems() {
        return Providers.pickerOptions(isSimplifiedChinese());
    }

    /** 第 1 步的问题行 Y。 */
    private int providerQuestionY() {
        return titleRowY() + this.font.lineHeight + 24;
    }

    /** 表格左边缘：左右各留 {@link #PICKER_SIDE_MARGIN}，铺满大半个屏幕。 */
    private int pickerLeft() {
        return PICKER_SIDE_MARGIN;
    }

    /** 表格右边缘。 */
    private int pickerRight() {
        return this.width - PICKER_SIDE_MARGIN;
    }

    /** 左列文字的起点（也是整行的左内边距）。 */
    private int pickerNameX() {
        return pickerLeft() + PICKER_TEXT_INSET;
    }

    /** 右列文字的起点：左列宽度之后。 */
    private int pickerDescX() {
        return pickerLeft() + PICKER_TEXT_INSET + PICKER_NAME_WIDTH;
    }

    /** 表格内容区（不含滚动条）的右边界。 */
    private int pickerRowRight() {
        return pickerRight() - PICKER_SCROLL_WIDTH - 1;
    }

    /** 表格顶端。 */
    private int pickerTop() {
        return providerQuestionY() + this.font.lineHeight + 12;
    }

    /**
     * 表格可视高度。
     * <p>铺满「表头下方 ~ 按钮上方」之间的空间：这一页只有这一块内容，
     * 固定高度会在底部留一大片空白。
     * <p>块数取整后把不足一块的余数也算进来，否则表格底边会比可用空间
     * 短一截、露出一条背景色。滚动与命中判定用的都是「整块」，
     * 这里多出来的零点几块只会表现为最后一项下方的一小段空白。
     * <p>自定义模式的输入框是<b>盖在</b>「自定义」那一项上的，不额外占高度。
     */
    private int pickerVisibleHeight() {
        int available = pickerBottomMax() - pickerRowsTop();
        // 至少容得下 3 块；正常窗口下 available 远大于此
        return Math.max(3 * PICKER_ITEM_HEIGHT, available);
    }

    /**
     * 表格可用的底端。
     * <p>表格一直铺到「确认」按钮上方，中间只留出 Ollama 链接那一行：
     * 链接已经挪到按钮正上方，提示行在按钮下方，表格下方不再需要预留。
     * <p>多留 4px 是给链接的悬停提示与表格底边框之间的呼吸，
     * 不留的话表格最后一行的边框会与链接文字贴死。
     */
    private int pickerBottomMax() {
        // 下方只有：Ollama 链接一行 + 它到按钮之间的一点空隙
        int below = this.font.lineHeight + 8 + 4;
        return this.height - BUTTON_BOTTOM_MARGIN - below;
    }

    /** 表头下沿：各块从这里开始排。 */
    private int pickerRowsTop() {
        return pickerTop() + PICKER_HEADER_HEIGHT;
    }

    /** 表格底端（含边框）。 */
    private int pickerListBottom() {
        return pickerRowsTop() + pickerVisibleHeight();
    }

    /** 表格内可滚动的最大偏移。 */
    private int pickerMaxScroll() {
        int content = pickerItems().size() * PICKER_ITEM_HEIGHT;
        return Math.max(0, content - pickerVisibleHeight());
    }

    /** 选中了一项：只记下来，右侧列随之刷新。 */
    private void onProviderPicked(String label) {
        pickedProvider = label;
        showProviderHint("", COLOR_NEUTRAL);
        // 立即重建控件，让「自定义」的输入框当场出现/消失。
        // 不能用 Screen.rebuildWidgets()：反编译 1.20.1 看，它只是排了一次
        // 无障碍朗读（见 m_169380_），并不会真的重建控件；真正重建由 resize() 触发。
        // 所以这里自己 clearWidgets() + init()。
        // clearWidgets() 不会重置 focused，切走时要先手动清掉，
        // 否则 focused 会一直指着一个已经不在控件表里的对象。
        this.setFocused(null);
        this.clearWidgets();
        this.init();
        // 选中「自定义」后把焦点交给新输入框，玩家不必再点一次就能直接打字
        if (customMode() && endpointBox != null) {
            this.setFocused(endpointBox);
            // 进来时先把已有内容校验一遍：玩家可能是改完又切回来的
            scheduleEndpointCheck();
        } else {
            // 切走了：清掉校验状态，别让红字留在界面上
            endpointCheck = null;
            pendingProbeAt = 0L;
            resetRetryLink();
        }
    }

    /** 玩家改了自定义 Endpoint。 */
    private void onCustomEndpointEdited(String text) {
        customEndpoint = text;
        showProviderHint("", COLOR_NEUTRAL);
        // 每敲一个字都重来一遍校验：作废在途请求、重新计时
        scheduleEndpointCheck();
    }

    /** 换一条提示；传空串表示清掉提示。 */
    private void showProviderHint(String text, int color) {
        this.providerHint = text;
        this.providerHintColor = color;
    }

    /**
     * 把这一步的选择写进 Verity 的配置。
     * <p>只写 {@code aiProvider} 与 {@code aiEndpoint}，不碰 API Key、模型与语音设置：
     * 那些是后面几步的事，这里提前写会把玩家原有配置改掉。
     *
     * @return {@code true} 表示确实写入了配置
     */
    private boolean commitProviderChoice() {
        Providers.PickerOption option = Providers.pickerOption(pickedProvider);
        if (option == null) return false;

        VerityConfigManager.ConfigData data = VerityConfigManager.loadVerityConfig();
        if (option.isCustom()) {
            String endpoint = endpointBox != null ? endpointBox.getValue().trim() : customEndpoint.trim();
            if (endpoint.isEmpty()) {
                showProviderHint("请填入 AI Endpoint（OpenAI 格式）", COLOR_WARN);
                return false;
            }
            customEndpoint = endpoint;
            data.provider = Providers.OPENAI;
            data.endpoint = endpoint;
        } else {
            // 「（请选择）」的 provider 为 null，走不到这里（next() 已经拦住了）
            if (option.provider() != null) data.provider = option.provider();
            if (option.endpoint() != null) data.endpoint = option.endpoint();
        }
        // 异步落盘 + 让 Verity 自己重载，不必重启
        VerityConfigManager.saveAll(data, data.provider);
        Log.info("[Guide] PROVIDER_COMMITTED | LABEL=" + pickedProvider
                + " | aiProvider=" + data.provider + " | aiEndpoint=" + data.endpoint);
        return true;
    }

    /** 打开 Verity 的 Ollama 配置页；回来时回到本步，已选中的提供商不会被清掉。 */
    private void openOllama() {
        Screen ollama = OllamaSetupScreenZh.create(this);
        if (ollama == null) {
            showProviderHint("未检测到 Verity，无法打开本地 AI 设置", COLOR_WARN);
            return;
        }
        Minecraft.getInstance().setScreen(ollama);
    }

    // ---------- 第 1 步的绘制 ----------

    /** 画第 1 步：问题、铺满的表格、底部 Ollama 链接、提示行。 */
    private void renderProviderStep(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;

        graphics.drawCenteredString(this.font, Component.literal(PROVIDER_QUESTION),
                centerX, providerQuestionY(), COLOR_BODY);

        renderProviderList(graphics, mouseX, mouseY);
        renderOllamaLink(graphics, mouseX, mouseY);

        // 自定义模式下，把校验结论显示在输入框下方（也就是表格底部再往下一点）
        if (customMode()) {
            renderEndpointCheck(graphics, mouseX, mouseY);
        }

        if (!providerHint.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(providerHint),
                    centerX, providerHintY(), providerHintColor);
        }
    }

    /** 校验结论那一行（图标 + 文字 + 可选的重试链接）。 */
    private void renderEndpointCheck(GuiGraphics graphics, int mouseX, int mouseY) {
        if (endpointCheck == null) {
            resetRetryLink();
            return;
        }

        // 显示在「自定义」那一项的正下方，而不是屏幕底部的提示行：
        // 玩家刚填完地址，视线就在那一行上，报错紧跟其后最容易被看到
        int y = endpointCheckY();
        int textX = drawCheckMark(graphics, pickerLeft(), y);

        int color = checkColor(endpointCheck);
        graphics.drawString(this.font, endpointCheck.message(), textX, y, color, false);

        // 需要重试时在文字后面追加「[点击此处]重新尝试」。
        // 只有方括号里的「点击此处」是青色（可点），后面的「重新尝试」跟正文同色——
        // 整段一起上色的话，玩家分不清点哪儿才有用
        if (endpointCheck.retryable()) {
            int afterText = textX + this.font.width(endpointCheck.message());
            String space = " ";
            String action = "[点击此处]";
            String trailing = "重新尝试";

            graphics.drawString(this.font, space, afterText, y, color, false);
            int actionX = afterText + this.font.width(space);
            graphics.drawString(this.font, action, actionX, y, COLOR_RETRY_LINK, false);
            graphics.drawString(this.font, trailing,
                    actionX + this.font.width(action), y, color, false);

            // 可点区域只覆盖「[点击此处]」本身
            this.retryLinkLeft = actionX;
            this.retryLinkRight = actionX + this.font.width(action);
            this.retryLinkTop = y;
            this.retryLinkBottom = y + this.font.lineHeight;
        } else {
            resetRetryLink();
        }
    }

    /**
     * 校验结论那一行的 Y：紧贴在「自定义」那一项的正下方。
     * <p>「自定义」是列表最后一项，按当前滚动位置反推它的底边即可。
     * <p>表格已经铺到接近按钮，所以结论行可能落在表格外面——
     * 这是有意的：表格内每行都有文字，塞进去反而会挤。
     */
    private int endpointCheckY() {
        int rows = pickerItems().size();
        int rowTop = pickerRowsTop() + (rows - 1) * PICKER_ITEM_HEIGHT - pickerScroll;
        return rowTop + PICKER_ROW_HEIGHT + 4;
    }

    /** 把重试链接的区域清空，避免上一帧的坐标残留导致误点。 */
    private void resetRetryLink() {
        retryLinkLeft = 0;
        retryLinkRight = 0;
        retryLinkTop = 0;
        retryLinkBottom = 0;
    }

    /** 结论对应的颜色。 */
    private static int checkColor(EndpointValidator.Result result) {
        return switch (result.level()) {
            case OK -> COLOR_CHECK_OK;
            case ERROR -> COLOR_CHECK_ERROR;
            case WARN -> COLOR_CHECK_WARN;
            case PENDING -> COLOR_CHECK_PENDING;
        };
    }

    /**
     * 在 {@code x, y} 处画校验图标，返回图标之后的文字应该从哪个 x 开始。
     * <p>直接用字体里的符号（√ × ●），不再手拼像素块：
     * 符号自带正确的字形与基线，换分辨率或改字号都不用重画。
     */
    private int drawCheckMark(GuiGraphics graphics, int x, int y) {
        String symbol = iconFor(endpointCheck).symbol();
        graphics.drawString(this.font, symbol, x, y, checkColor(endpointCheck), false);
        return x + this.font.width(symbol) + CHECK_ICON_GAP;
    }

    /** 按结论挑图标形状。 */
    private static Mark iconFor(EndpointValidator.Result result) {
        return switch (result.level()) {
            case OK -> Mark.CHECK;
            case ERROR -> Mark.CROSS;
            case WARN -> Mark.DOT_WARN;
            case PENDING -> Mark.DOT_PENDING;
        };
    }

    /**
     * 整页唯一的那块滚动表格：顶部一行表头，下面每一项都是一个独立的框。
     * <p>每项单独画边框、彼此留空隙，读起来像一条一条的条目而不是一张密排的表。
     * 选中项边框变绿；「自定义」选中后它下方单独补一个输入框（见 {@link #initProviderPicker}）。
     */
    private void renderProviderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = pickerLeft();
        int top = pickerTop();
        int right = pickerRight();
        int bottom = pickerListBottom();
        int width = right - left;

        // 外层容器
        graphics.fill(left, top, right, bottom, COLOR_LIST_BG);
        graphics.renderOutline(left, top, width, bottom - top, COLOR_LIST_BORDER);

        // 表头：与项同宽，左列「提供商」、右列「介绍」，中间一条竖线
        int rowsTop = pickerRowsTop();
        graphics.fill(left + 1, top + 1, right - 1, rowsTop, 0xFF2A2A2A);
        int headerTextY = top + 1 + (PICKER_HEADER_HEIGHT - this.font.lineHeight) / 2;
        graphics.drawString(this.font, "提供商", pickerNameX(), headerTextY, 0xFFFFFFFF, false);
        graphics.drawString(this.font, "介绍", pickerDescX(), headerTextY, 0xFFFFFFFF, false);
        // 表头与各块之间的分隔线
        graphics.fill(left + 1, rowsTop - 1, right - 1, rowsTop, COLOR_LIST_BORDER);

        List<Providers.PickerOption> items = pickerItems();
        int visible = pickerVisibleHeight();
        int scroll = Math.max(0, Math.min(pickerMaxScroll(), pickerScroll));
        pickerScroll = scroll;

        // 只画落在可视区里的块：滚动偏移可能停在半个块上，首块用 -scroll % ITEM 对齐
        int first = scroll / PICKER_ITEM_HEIGHT;
        int offset = scroll % PICKER_ITEM_HEIGHT;

        int rowRight = pickerRowRight();
        graphics.enableScissor(left + 1, rowsTop, rowRight, bottom - 1);
        for (int i = first; i < items.size(); i++) {
            int rowTop = rowsTop + (i - first) * PICKER_ITEM_HEIGHT - offset;
            if (rowTop >= bottom - 1) break;
            int rowBottom = rowTop + PICKER_ROW_HEIGHT;
            if (rowBottom <= rowsTop) continue;

            Providers.PickerOption item = items.get(i);
            // 还没选的时候「（请选择）」显示成选中态，与实际配置一致
            boolean selected = item.isPlaceholder()
                    ? !providerChosen()
                    : item.label().equals(pickedProvider);

            boolean hovered = mouseX >= left + 1 && mouseX < rowRight
                    && mouseY >= rowTop && mouseY < rowBottom;

            // 每一块：自己的底 + 自己的边框，块之间靠 PICKER_ROW_GAP 隔开
            // 「自定义」校验失败/警告时，这一块的底色与边框跟着变色，
            // 让玩家一眼看出红字说的是哪一项
            boolean tinted = item.isCustom() && customMode() && endpointCheck != null
                    && endpointCheck.level() != EndpointValidator.Level.OK;
            int itemBorderColor = selected
                    ? (tinted ? checkColor(endpointCheck) : COLOR_ROW_SELECTED)
                    : hovered ? 0xFFFFFFFF : COLOR_ITEM_BORDER;
            int itemBg = tinted
                    ? (endpointCheck.level() == EndpointValidator.Level.ERROR
                            ? 0x55FF0000   // 半透明红
                            : 0x55FFAA00)  // 半透明橙/黄共用的暖色
                    : selected ? 0x55336644 : hovered ? 0xFF2E2E2E : COLOR_ITEM_BG;

            graphics.fill(left + 1, rowTop, rowRight, rowBottom, itemBg);
            graphics.renderOutline(left + 1, rowTop, rowRight - left - 1, PICKER_ROW_HEIGHT,
                    itemBorderColor);

            int textY = rowTop + (PICKER_ROW_HEIGHT - this.font.lineHeight) / 2;

            // 左列：提供商名
            int nameColor = selected ? COLOR_ROW_SELECTED : hovered ? 0xFFFFFFFF : 0xFFDDDDDD;
            graphics.drawString(this.font, item.label(), pickerNameX(), textY, nameColor, false);

            // 右列：介绍。
            // 选中「自定义」时这一行的介绍位置被输入框盖住，这里就不必画了——
            // 画了也会被输入框的实心底色挡住，白白多一次绘制
            if (!item.desc().isEmpty() && !(item.isCustom() && customMode())) {
                int descColor = selected ? 0xFFE8E8E8 : hovered ? 0xFFE0E0E0 : 0xFF9A9A9A;
                // 超出右列宽度就截断加省略号，绝不越出这一块
                int maxWidth = rowRight - pickerDescX() - 4;
                graphics.drawString(this.font, trimToWidth(item.desc(), maxWidth),
                        pickerDescX(), textY, descColor, false);
            }
        }
        graphics.disableScissor();

        // 右侧滚动条：内容装得下时不画
        int maxScroll = pickerMaxScroll();
        if (maxScroll > 0) {
            int trackX = right - PICKER_SCROLL_WIDTH - 1;
            int trackTop = rowsTop;
            int trackBottom = bottom - 1;
            int trackHeight = trackBottom - trackTop;
            int contentHeight = items.size() * PICKER_ITEM_HEIGHT;
            int thumbHeight = Math.max(16, trackHeight * visible / contentHeight);
            int thumbY = trackTop + (int) ((long) (trackHeight - thumbHeight) * scroll / maxScroll);
            graphics.fill(trackX, trackTop, trackX + PICKER_SCROLL_WIDTH, trackBottom, 0xFF3A3A3A);
            graphics.fill(trackX, thumbY, trackX + PICKER_SCROLL_WIDTH, thumbY + thumbHeight, 0xFFCCCCCC);
        }
    }

    /**
     * 把文字截到指定像素宽度内，超出部分换成省略号。
     * <p>中文按像素量宽，字符数不等于显示宽度，只能逐字累加。
     */
    private String trimToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - this.font.width(ellipsis);
        if (budget <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int w = this.font.width(String.valueOf(c));
            if (used + w > budget) break;
            sb.append(c);
            used += w;
        }
        return sb + ellipsis;
    }

    /**
     * 「不想为 API 付费？」链接，放在「确认」按钮正上方。
     * <p>内容：普通文字 + 可点的绿色方括号，悬停出提示。
     */
    private void renderOllamaLink(GuiGraphics graphics, int mouseX, int mouseY) {
        // 有校验结论要显示时把这一行藏起来：结论行就落在它附近，
        // 两行叠在一起会互相压字
        if (endpointCheck != null) {
            resetOllamaLink();
            return;
        }

        int centerX = this.width / 2;
        int y = ollamaLinkY();

        String lead = "不想为 API 付费？";
        String action = "[点击此处]";
        String tail = "在本地运行 AI！";
        int leadW = this.font.width(lead);
        int actionW = this.font.width(action);
        int tailW = this.font.width(tail);
        int x = centerX - (leadW + actionW + tailW) / 2;

        graphics.drawString(this.font, lead, x, y, COLOR_NEUTRAL, false);
        graphics.drawString(this.font, action, x + leadW, y, COLOR_OK, false);
        graphics.drawString(this.font, tail, x + leadW + actionW, y, COLOR_NEUTRAL, false);

        // 记下可点区域，点击与悬停都用它
        this.ollamaLinkLeft = x + leadW;
        this.ollamaLinkRight = x + leadW + actionW;
        this.ollamaLinkTop = y;
        this.ollamaLinkBottom = y + this.font.lineHeight;

        if (ollamaLinkHovered(mouseX, mouseY)) {
            graphics.renderTooltip(this.font,
                    Component.literal("配置进入本地 AI（Ollama），点击后进去本地 AI 配置"),
                    mouseX, mouseY);
        }
    }

    /** 清空 Ollama 链接的可点区域（隐藏它时要一并清掉，否则会留下看不见的可点区）。 */
    private void resetOllamaLink() {
        this.ollamaLinkLeft = 0;
        this.ollamaLinkRight = 0;
        this.ollamaLinkTop = 0;
        this.ollamaLinkBottom = 0;
    }

    /** 鼠标是否停在「点击此处」上。 */
    private boolean ollamaLinkHovered(int mouseX, int mouseY) {
        return mouseX >= ollamaLinkLeft && mouseX < ollamaLinkRight
                && mouseY >= ollamaLinkTop && mouseY < ollamaLinkBottom;
    }

    /**
     * Ollama 链接的 Y：紧贴在「确认」按钮上方。
     * <p>它的位置只由按钮决定，不跟表格高度走：表格最高只到它的上沿，
     * 所以窗口怎么变，这行小字都稳稳落在按钮正上方。
     */
    private int ollamaLinkY() {
        return this.height - BUTTON_BOTTOM_MARGIN - this.font.lineHeight - 8;
    }

    /** 提示行的 Y：按钮下方，不挤占表格。 */
    private int providerHintY() {
        return this.height - BUTTON_BOTTOM_MARGIN + BUTTON_HEIGHT + 6;
    }

    /**
     * 点击是否落在「自定义」那一行的 Endpoint 输入框上。
     * <p>那块区域在表格内部，但视觉上已经是输入框；不把它从「点选行」里
     * 摘出来，点击会被列表逻辑吃掉，输入框永远拿不到焦点。
     */
    private boolean isEndpointBoxHit(double mouseX, double mouseY) {
        return endpointBox != null
                && mouseX >= endpointBox.getX() && mouseX < endpointBox.getX() + endpointBox.getWidth()
                && mouseY >= endpointBox.getY() && mouseY < endpointBox.getY() + endpointBox.getHeight();
    }

    /**
     * 鼠标位置落在表格的哪一行上；不在表格内时返回 {@code null}。
     * <p>换算方式与 {@link #renderProviderList} 的绘制保持一致：
     * 先算行号，再用同一个滚动偏移反推这一行的屏幕位置。
     */
    private String pickerRowAt(double mouseX, double mouseY) {
        int left = pickerLeft();
        int right = pickerRight();
        int bottom = pickerListBottom();
        // 表头区域不算在内：它不是可选项
        if (mouseX < left + 1 || mouseX >= right
                || mouseY < pickerRowsTop() || mouseY >= bottom - 1) {
            return null;
        }

        List<Providers.PickerOption> items = pickerItems();
        int scroll = Math.max(0, Math.min(pickerMaxScroll(), pickerScroll));
        int first = scroll / PICKER_ITEM_HEIGHT;
        int offset = scroll % PICKER_ITEM_HEIGHT;

        for (int i = first; i < items.size(); i++) {
            int rowTop = pickerRowsTop() + (i - first) * PICKER_ITEM_HEIGHT - offset;
            if (mouseY >= rowTop && mouseY < rowTop + PICKER_ROW_HEIGHT) {
                return items.get(i).label();
            }
            if (rowTop >= bottom) break;
        }
        return null;
    }

    /**
     * 第 1 步的列表滚轮。
     * <p>先于 {@code super} 处理：表格是自绘的，交给控件链的话会被当成「没人要」的事件。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (position == Position.STEP && step == STEP_PROVIDER) {
            if (mouseX >= pickerLeft() && mouseX < pickerRight()
                    && mouseY >= pickerTop() && mouseY < pickerListBottom()
                    && pickerMaxScroll() > 0) {
                int before = pickerScroll;
                // 一次滚一块，半块滚动会让列表看起来在抖
                pickerScroll = Math.max(0, Math.min(pickerMaxScroll(),
                        pickerScroll - (int) Math.round(delta) * PICKER_ITEM_HEIGHT));
                // 输入框钉在「自定义」那一块下方，块随滚动移动，输入框也得跟着走
                if (endpointBox != null && pickerScroll != before) {
                    endpointBox.setY(customBoxY());
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 打开 Modpack Helper 的旧存档导入界面。
     * <p>「跳过，直接开始配置」之后回到<b>本页</b>而不是直接开始：玩家是在引导途中
     * 顺路导存档，引导的进度不该因此丢掉。
     * <p>调用失败（没装 / 版本对不上）时什么都不做，按钮的悬停提示已经说清楚了。
     */
    private void importLegacy() {
        ModpackHelperBridge.openLegacySaveImport(this, () ->
                Minecraft.getInstance().setScreen(GuideScreen.startKeepingAnswers(onFinished)));
    }

    /**
     * 结束引导：状态置为 normal，然后回到来处。
     * <p>从配置界面点进来的就回配置界面，首次启动进来的就直接回游戏。
     */
    private void finish() {
        GameState.finishGuide();
        if (onFinished != null) {
            onFinished.run();
            return;
        }
        Minecraft.getInstance().setScreen(null);
    }

    // ---------- 渲染 ----------

    // ================= 进度条 =================

    /** 标题左边缘，也是整块标题区的起点。 */
    private static int titleLeft() {
        return BACK_BUTTON_MARGIN + BACK_BUTTON_WIDTH + TITLE_GAP;
    }

    /** 进度条左端：紧跟在标题文字右侧。 */
    private int barLeft() {
        return titleLeft() + this.font.width(this.title) + BAR_TITLE_GAP;
    }

    /** 进度条右端：屏幕右边缘留 {@link #BAR_RIGHT_MARGIN}。 */
    private int barRight(int screenWidth) {
        return screenWidth - BAR_RIGHT_MARGIN;
    }

    /**
     * 标题行的 Y。
     * <p>「标题 + 下方一行标签」作为整块，相对「←」按钮垂直居中。
     * 进度条与标题同一行，标签与「2/5」同一行，都从这两个 Y 派生。
     */
    private int titleRowY() {
        int labelHeight = Math.round(this.font.lineHeight * TICK_LABEL_SCALE);
        int blockHeight = this.font.lineHeight + TICK_LABEL_GAP + labelHeight;
        int buttonCenterY = BACK_BUTTON_MARGIN + BACK_BUTTON_HEIGHT / 2;
        // 四舍五入而不是整数除法：块高为奇数时不会稳定偏上 0.5px
        return buttonCenterY - Math.round(blockHeight / 2.0f);
    }

    /** 标签行的 Y：细条下方的小字与「2/5」共用这条基线。 */
    private int labelRowY() {
        return titleRowY() + this.font.lineHeight + TICK_LABEL_GAP;
    }

    /** 进度条的 Y：与标题同一行、垂直居中。 */
    private int barY() {
        return titleRowY() + (this.font.lineHeight - BAR_HEIGHT) / 2;
    }

    /** 进度条总宽。 */
    private int barFullWidth() {
        return barRight(this.width) - barLeft();
    }

    /** 「完成」标记在进度条上的 x：最右端，与绿色填充的终点对齐。 */
    private int finishTickX() {
        return barLeft() + barFullWidth();
    }

    /**
     * 「完成」标记（进度条最右端）的颜色。
     * <p>走到结束页就是金色；否则与其它未到达的步骤一样是淡灰。
     * <p>它没有「当前」状态——结束页本身就是终点，不需要再用绿色区分。
     */
    private int finishTickColor() {
        return position == Position.FINISH ? COLOR_DONE : COLOR_TODO;
    }

    /** 「完成」标记是否可点：只有已经到达过（结束页）才可点。 */
    private boolean isFinishClickable() {
        return position == Position.FINISH;
    }

    /**
     * 推进进度条动画：从上一位置平滑过渡到目标位置。
     *
     * <p>做法是「补间」而不是「匀速追赶」：记录起点值、终点值和起始时间，
     * 每帧把 0..1 的时间经缓动曲线映射成 0..1 的位移比例。
     *
     * <p>这里只管<b>前进 / 后退</b>（翻页、点「+1」、切换提供商）：
     * 曲线是指数缓出，时长 {@link #BAR_MOVE_MS}。
     * 进度条的<b>展开</b>是另一条独立的动画，见 {@link #appearProgress()}。
     */
    private void tickBarAnimation() {
        int target = targetSubSteps();
        long now = System.currentTimeMillis();

        // 首次绘制：不做动画，直接对齐目标（冷启动不该看到进度条自己爬）。
        // 欢迎页上进度条不显示，这里对没意义。
        if (lastAnimFrameMs == 0L) {
            lastAnimFrameMs = now;
            shownSubSteps = target;
            animFrom = target;
            animTo = target;
            animStartMs = now;
            animating = false;
            return;
        }

        // 目标变了：以当前显示值为起点，开一段新的缓出过渡
        if (target != animTo) {
            animFrom = shownSubSteps;
            animTo = target;
            animStartMs = now;
            animating = true;
        }

        if (!animating) {
            shownSubSteps = target;
            return;
        }

        float t = (now - animStartMs) / (float) BAR_MOVE_MS;
        if (t >= 1.0f) {
            shownSubSteps = animTo;
            animating = false;
        } else {
            // 指数缓出：开头极快、末尾极缓
            float eased = easeOutExpo(t);
            shownSubSteps = animFrom + (animTo - animFrom) * eased;
        }
        lastAnimFrameMs = now;
    }

    /**
     * 指数缓出曲线：{@code t=1} 时为 1，其余时刻为 {@code 1 - 2^(-10t)}。
     * <p>开头推进得极快（前 10% 的时间就走过约一半），后面无限趋缓。
     * 纯指数在 t=0 处会从 0 跳到 0.00098，肉眼看不出来；t=1 处收不到 1，
     * 所以显式特判，避免最后一帧留下千分之一的残余。
     */
    private static float easeOutExpo(float t) {
        if (t >= 1.0f) return 1.0f;
        return 1.0f - (float) Math.pow(2.0, -10.0 * t);
    }

    /**
     * {@code LinearOutSlowIn} 曲线（Material Design 的标准进场缓动）。
     * <p>标准写法是一条以 {@code (0.35, 0)} 与 {@code (0.25, 1)} 为控制点的立方贝塞尔。
     * 这里用二分法反解「给定时间比例 t，位移比例是多少」——
     * 贝塞尔是参数曲线，直接取 t 当参数会得到错误的时序。
     */
    private static float linearOutSlowIn(float t) {
        if (t <= 0.0f) return 0.0f;
        if (t >= 1.0f) return 1.0f;

        // 三次贝塞尔的控制点：P0=(0,0) P1=(0.35,0) P2=(0.25,1) P3=(1,1)
        final double x1 = 0.35, x2 = 0.25;
        final double y1 = 0.0, y2 = 1.0;

        // 先在参数 u 上二分，找到使 x(u) == t 的那个 u
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 20; i++) {
            double u = (lo + hi) * 0.5;
            if (bezier(u, x1, x2) < t) {
                lo = u;
            } else {
                hi = u;
            }
        }
        return (float) bezier((lo + hi) * 0.5, y1, y2);
    }

    /** 三次贝塞尔曲线在一个轴上的取值（端点固定为 0 与 1）。 */
    private static double bezier(double u, double p1, double p2) {
        double inv = 1.0 - u;
        // 3(1-u)²u·p1 + 3(1-u)u²·p2 + u³
        return 3 * inv * inv * u * p1 + 3 * inv * u * u * p2 + u * u * u;
    }

    /**
     * 出现动画的进度 0..1：点「开始」后底槽从左侧拉出。
     * <p>用 {@code LinearOutSlowIn} + 1000ms，与绿条的推进（指数缓出 600ms）各自独立。
     * <p>若起始时间尚未设置（正常不会发生），就地补一个，避免进度条永远不出现。
     */
    private float appearProgress() {
        if (appearStartMs == 0L) {
            appearStartMs = System.currentTimeMillis();
        }
        long elapsed = System.currentTimeMillis() - appearStartMs;
        if (elapsed >= BAR_APPEAR_MS) return 1.0f;
        return linearOutSlowIn(elapsed / (float) BAR_APPEAR_MS);
    }

    /**
     * 绘制进度条整体：底槽、绿色填充、步骤细条与标签。
     *
     * <p>布局：15 份 = 5 步 × 3 份。5 个步骤细条画在每步的<b>起点</b>，
     * 即第 0、3、6、9、12 份的边界；最后一步走完时绿条贴到最右端。
     *
     * <p><b>动画：</b>绿条长度由 {@link #shownSubSteps} 决定，它是一个
     * 跨页面存活的静态值，每帧向 {@link #targetSubSteps()} 靠近，
     * 所以翻页与点「+1」都是平滑推进而不是瞬间跳变。
     */
    private void renderBar(GuiGraphics graphics) {
        if (!barExpanded) return;

        int left = barLeft();
        int fullWidth = barFullWidth();
        int totalSubSteps = GameState.STEPS * SUB_STEPS;
        int y = barY();

        // 出现动画：底槽与内容都按这个比例从左侧拉出。
        // 用「可见宽度」而不是逐个元素淡入：整条像是被「拉」出来，更连贯。
        float appear = appearProgress();
        if (appear <= 0.0f) return;
        int visibleWidth = Math.round(fullWidth * appear);

        // 1. 底槽：深灰，按出现进度铺开
        graphics.fill(left, y, left + visibleWidth, y + BAR_HEIGHT, COLOR_TRACK);

        // 2. 绿色填充：按平滑后的份数算长，再乘出现进度
        int fillWidth = Math.round(shownSubSteps / totalSubSteps * fullWidth * appear);
        if (fillWidth > 0) {
            graphics.fill(left, y, left + fillWidth, y + BAR_HEIGHT, COLOR_FILL);
        }

        // 3. 步骤细条与标签：当前位置还没被「拉」到的先不画，
        //    这样它们会随着进度条的铺开依次出现，而不是提前悬在空处
        for (int i = 0; i < GameState.STEPS; i++) {
            int tickX = left + Math.round((float) (i * SUB_STEPS) / totalSubSteps * fullWidth);
            if (tickX > left + visibleWidth) continue;

            int color = tickColor(i);
            // 细条比槽上下各多出 1px，形成突出的小刻度
            int tickTop = y - (TICK_HEIGHT - BAR_HEIGHT) / 2;
            graphics.fill(tickX, tickTop, tickX + TICK_WIDTH, tickTop + TICK_HEIGHT, color);

            // 标签：与「2/5」同一基线、同一字号，左边缘对齐细条
            drawScaled(graphics, tickLabel(i), tickX, labelRowY(), TICK_LABEL_SCALE, color);
        }

        // 4. 「完成」标记：进度条最右端，表示整轮引导的终点
        if (visibleWidth >= fullWidth) {
            int fx = finishTickX();
            int fColor = finishTickColor();
            int tickTop = y - (TICK_HEIGHT - BAR_HEIGHT) / 2;
            graphics.fill(fx - TICK_WIDTH, tickTop, fx, tickTop + TICK_HEIGHT, fColor);
            // 标签右对齐到进度条末端，避免文字超出屏幕
            String label = "完成";
            int labelWidth = Math.round(this.font.width(label) * TICK_LABEL_SCALE);
            drawScaled(graphics, label, fx - labelWidth, labelRowY(), TICK_LABEL_SCALE, fColor);
        }
    }

    /** 在标签行下方画一条金色细线，表示这一段可点。 */
    private void drawHoverUnderline(GuiGraphics graphics, int x0, int x1) {
        int lineY = labelRowY() + Math.round(this.font.lineHeight * TICK_LABEL_SCALE) + 1;
        graphics.fill(x0, lineY, x1, lineY + 1, COLOR_DONE);
    }

    /** 以左上角为基准缩放绘制一段文字。 */
    private void drawScaled(GuiGraphics graphics, String text,
                            int x, int y, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(this.font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    /**
     * 当前应该显示的份数（0..15），即动画的<b>目标值</b>。
     * <p>实际绘制用的是 {@link #shownSubSteps}，它逐帧向这里靠近。
     */
    private int targetSubSteps() {
        int done = switch (position) {
            case START -> 0;
            case STEP -> (step - 1) * SUB_STEPS
                    + (step == STEP_PROVIDER ? providerStepSubSteps() : subStepProgress);
            case FINISH -> GameState.STEPS * SUB_STEPS;
        };
        int total = GameState.STEPS * SUB_STEPS;
        return Math.max(0, Math.min(total, done));
    }

    /**
     * 第 1 步当前该显示的份数（0..{@link #SUB_STEPS}）。
     * <ul>
     *   <li>还没选（「（请选择）」）→ 0；</li>
     *   <li>选了预置提供商 → 2 份；</li>
     *   <li>选了「自定义」→ 1 份，网址校验通过后升到 2 份。</li>
     * </ul>
     * <p>用 {@code 2} 而不是满格 {@code 3}：这一步真正结束时才该填满，
     * 选了提供商只是「填了一半」，进度条不该提前跑到底。
     */
    private int providerStepSubSteps() {
        if (!providerChosen()) return 0;
        if (!customMode()) return 2;
        // 自定义：填了地址算 1 份，地址验证通过再升到 2 份
        boolean hasText = !(endpointBox != null ? endpointBox.getValue() : customEndpoint).trim().isEmpty();
        if (!hasText) return 1;
        return endpointCheck != null && endpointCheck.level() == EndpointValidator.Level.OK ? 2 : 1;
    }

    /**
     * 第 {@code index} 个步骤（0 起算）细条与标签的颜色。
     * <p>规则只有「到达过 / 没到达过」两种，外加当前页用绿色区分：
     * <ul>
     *   <li>当前页 → 绿色；</li>
     *   <li>到达过（含走回来的）→ 金色，可点击跳转；</li>
     *   <li>没到过 → 淡灰。</li>
     * </ul>
     * <p>用 {@link #maxReachedStep} 而不是「第几步之前」判断：
     * 玩家点金色细条回到第 2 步后，第 3~5 步仍然是到达过的，不该褪回灰色。
     */
    private int tickColor(int index) {
        int stepNo = index + 1;

        // 当前页
        if (position == Position.STEP && stepNo == step) return COLOR_CURRENT;

        // 到达过的
        if (stepNo <= maxReachedStep) return COLOR_DONE;

        return COLOR_TODO;
    }

    /**
     * 第 {@code index} 个步骤能否点击跳转。
     * <p>只有「到达过、且不是当前页」的步骤可点。
     */
    private boolean isStepClickable(int index) {
        int stepNo = index + 1;
        if (stepNo > maxReachedStep) return false;
        return !(position == Position.STEP && stepNo == step);
    }

    /** 跳到第 {@code stepNo} 步（1 起算）。 */
    private void jumpToStep(int stepNo) {
        Minecraft.getInstance().setScreen(GuideScreen.step(stepNo, onFinished));
    }

    /** 跳到结束页。 */
    private void jumpToFinish() {
        Minecraft.getInstance().setScreen(GuideScreen.finish(onFinished));
    }

    /**
     * 把屏幕坐标换算成进度条上的目标：0..STEPS-1 是第几步，
     * {@link #HIT_FINISH} 表示末端的「完成」标记，-1 表示没命中。
     */
    private int hitTestStep(double mouseX, double mouseY) {
        if (!barExpanded) return -1;

        // 纵向至少覆盖细条所在的一小段（含上下各 1px 的突出部分），
        // 横向覆盖细条右侧到下一个细条之前的区域，避免必须精确点在 1px 上
        int y = barY();
        int tickTop = y - (TICK_HEIGHT - BAR_HEIGHT) / 2;
        int labelBottom = labelRowY() + Math.round(this.font.lineHeight * TICK_LABEL_SCALE);
        if (mouseY < tickTop || mouseY > labelBottom) return -1;

        int left = barLeft();
        int fullWidth = barFullWidth();
        int totalSubSteps = GameState.STEPS * SUB_STEPS;

        // 「完成」标记独占最右端一小段：它的标签是右对齐的，
        // 用「标签宽度」作为判定区，避免和最后一个步骤的区段重叠。
        int finishLeft = finishTickX() - Math.round(this.font.width("完成") * TICK_LABEL_SCALE);
        if (mouseX >= finishLeft) return HIT_FINISH;

        // 每个细条负责从自己到「下一个细条之前」这一段
        for (int i = GameState.STEPS - 1; i >= 0; i--) {
            int tickX = left + Math.round((float) (i * SUB_STEPS) / totalSubSteps * fullWidth);
            if (mouseX >= tickX) return i;
        }
        return -1;
    }

    /** {@link #hitTestStep} 表示命中「完成」标记的返回值。 */
    private static final int HIT_FINISH = -2;

    /** 第 {@code index} 个步骤的标签文字（演示版占位：第 x 步）。 */
    private static String tickLabel(int index) {
        return "第" + (index + 1) + "步";
    }

    /**
     * 调试用：进度 +1 份。
     * <p>每页最多点 {@link #DEBUG_CLICK_LIMIT} 次；点满该步的
     * {@link #SUB_STEPS} 份就自动进入下一页。
     * <p>不重建整个界面（那会重播展开动画、重写状态文件），
     * 只就地刷新「+1」按钮的可用性与进度条。
     */
    private void advanceOneSubStep() {
        if (debugClicks >= DEBUG_CLICK_LIMIT) return;
        debugClicks++;
        subStepProgress++;

        if (subStepProgress >= SUB_STEPS) {
            // 这一步已经走满，自动翻到下一页
            next();
            return;
        }

        // 用满次数后把按钮置灰，避免继续点
        if (debugClicks >= DEBUG_CLICK_LIMIT && plusButton != null) {
            plusButton.active = false;
        }
    }

    /** 正文文字（演示版占位；第 1 步另有自己的渲染）。 */
    private String bodyText() {
        return switch (position) {
            case START -> "";
            case STEP -> "这是引导教程第 " + step + " 步";
            case FINISH -> "配置已结束";
        };
    }

    // ---------- 欢迎页正文 ----------

    /**
     * 「导入旧存档」是不是该出现。
     * <p>这条提示与按钮都只属于欢迎页，而且需要 Modpack Helper 提供导入界面。
     */
    private static boolean canImportLegacy() {
        return ModpackHelperBridge.isAvailable();
    }

    /**
     * 欢迎语：报「装的是什么」。
     * <ul>
     *   <li>装了 Modpack Helper —— 玩家装的是整个整合包，报整合包的版本号；</li>
     *   <li>没装 —— 只装了这个模组，报本模组的版本号与作者。</li>
     * </ul>
     * 版本号从 mods.toml 现取，不写死在代码里。
     */
    private static String welcomeLine() {
        ModList mods = ModList.get();
        if (ModpackHelperBridge.isAvailable()) {
            return "欢迎您安装 CB2495 的 Verity 整合包 " + modVersion(mods, ModpackHelperBridge.MODID);
        }
        return "欢迎您安装 Verity Plus " + modVersion(mods, com.cb2495.verityplus.VerityPlus.MODID)
                + "（作者：CB2495）";
    }

    /** 取某个模组的版本号；取不到时给一个不会误导人的占位。 */
    private static String modVersion(ModList mods, String modId) {
        return mods.getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("?");
    }

    /**
     * 欢迎页正文的一行：文字 + 颜色。
     *
     * @param text  这一行的原文（可能被折成多行显示）
     * @param color ARGB 颜色
     */
    private record BodyLine(String text, int color) {}

    /**
     * 欢迎页正文的每一行。
     * <p>第一行是欢迎语（金色），后面是这个页面的说明；
     * 讲「导入旧存档」的那行只在装了 Modpack Helper 时才有，
     * 讲「跳过」的那行什么时候都在。
     */
    private List<BodyLine> startBodyLines() {
        List<BodyLine> lines = new ArrayList<>();
        lines.add(new BodyLine(welcomeLine(), COLOR_HEADING));
        lines.add(new BodyLine("跟随此引导快捷配置 Verity 的 AI 大脑", COLOR_BODY));
        if (canImportLegacy()) {
            lines.add(new BodyLine("若你有旧版整合包的存档，可以点击下方「导入旧存档」按钮导入旧版存档",
                    COLOR_HINT));
        }
        lines.add(new BodyLine("如果你足够熟悉 Verity 的配置流程，并愿意手动配置，"
                + "可点击「跳过」按钮进入游戏手动配置", COLOR_HINT));
        return lines;
    }

    /**
     * 画欢迎页正文（全部同一字号，欢迎语用金色区分），整块在
     * 「进度条下方 ~ 按钮上方」这段空白里垂直居中，而不是死钉在屏幕正中——
     * 这样按钮多了几行也不会把文字压住。
     */
    private void renderStartText(GuiGraphics graphics) {
        int centerX = this.width / 2;
        int wrapWidth = Math.max(40, this.width - TEXT_SIDE_MARGIN * 2);

        // 先把所有行折好，才知道整块有多高；颜色跟着行走进来，折出来的几段共用
        List<Integer> colors = new ArrayList<>();
        List<FormattedCharSequence> rows = new ArrayList<>();
        for (BodyLine line : startBodyLines()) {
            for (FormattedCharSequence piece : this.font.split(Component.literal(line.text()), wrapWidth)) {
                rows.add(piece);
                colors.add(line.color());
            }
        }

        int blockHeight = rows.isEmpty()
                ? 0
                : rows.size() * this.font.lineHeight + (rows.size() - 1) * TEXT_LINE_GAP;

        // 上边界：欢迎页进度条不展开，从标题行下方算起；
        // 下边界：三行按钮列的顶端（与 init 里的排法一致，这里是同一条公式）
        int top = titleRowY() + this.font.lineHeight + 16;
        int bottom = this.height - BUTTON_BOTTOM_MARGIN - (BUTTON_HEIGHT + BUTTON_GAP) * 3;
        int y = Math.max(top, top + (bottom - top - blockHeight) / 2);

        for (int i = 0; i < rows.size(); i++) {
            graphics.drawCenteredString(this.font, rows.get(i), centerX, y, colors.get(i));
            y += this.font.lineHeight + TEXT_LINE_GAP;
        }
    }

    /** 右下角的页序指示；欢迎页与结束页没有「第几步」的概念，按位置显示。 */
    private String progressText() {
        return switch (position) {
            case START -> "开始";
            case STEP -> step + " / " + GameState.STEPS;
            case FINISH -> "完成";
        };
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int centerX = this.width / 2;

        // 标题与「←」按钮同一行，左边缘对齐到按钮右侧
        int titleX = titleLeft();
        int titleY = titleRowY();
        graphics.drawString(this.font, this.title, titleX, titleY, 0xFFFFFF, false);

        // 「2/5」与细条标签同一行、同一字号，左边缘对齐标题
        drawScaled(graphics, progressText(), titleX, labelRowY(),
                TICK_LABEL_SCALE, PROGRESS_COLOR);

        // 进度条：与标题同一行，紧跟在标题文字后面
        tickBarAnimation();
        renderBar(graphics);

        // 鼠标悬在可跳转的金色标记上时，在下划线做提示
        int hovered = hitTestStep(mouseX, mouseY);
        if (hovered == HIT_FINISH && isFinishClickable()) {
            drawHoverUnderline(graphics, finishTickX() - Math.round(this.font.width("完成") * TICK_LABEL_SCALE),
                    finishTickX());
        } else if (hovered >= 0 && isStepClickable(hovered)) {
            int left = barLeft();
            int fullWidth = barFullWidth();
            int totalSubSteps = GameState.STEPS * SUB_STEPS;
            int x0 = left + Math.round((float) (hovered * SUB_STEPS) / totalSubSteps * fullWidth);
            // 段尾：下一个细条位置；最后一段到「完成」标记之前
            int x1 = hovered + 1 >= GameState.STEPS
                    ? finishTickX() - Math.round(this.font.width("完成") * TICK_LABEL_SCALE)
                    : left + Math.round((float) ((hovered + 1) * SUB_STEPS) / totalSubSteps * fullWidth);
            drawHoverUnderline(graphics, x0, x1);
        }

        // 正文：欢迎页与第 1 步有真实内容，其余几页还是占位
        if (position == Position.START) {
            renderStartText(graphics);
        } else if (position == Position.STEP && step == STEP_PROVIDER) {
            // 输入框的边框颜色跟着校验状态走：没有结论时是选中绿
            GreenBorderedEditBox.setBorderColor(
                    endpointCheck != null && customMode()
                            ? checkColor(endpointCheck) : COLOR_ROW_SELECTED);
            renderProviderStep(graphics, mouseX, mouseY);
        } else {
            graphics.drawCenteredString(this.font, Component.literal(bodyText()),
                    centerX, this.height / 2 - 20, COLOR_HEADING);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 驱动输入框的光标闪烁。
     *
     * <p>{@link EditBox} 的光标显隐条件是
     * {@code isFocused() && 闪烁计时 / 6 % 2 == 0}，而那个计时器只在
     * {@code EditBox.tick()} 里自增。它是普通的 {@code AbstractWidget}，
     * 没有自动的 tick 回调，不手动调用的话计时器永远停在 0，
     * 光标就一直亮着、不闪。
     *
     * <p>必须放在 {@code tick()} 而不是 {@code render()} 里：
     * 原版的闪烁节奏是按 <b>tick</b>（固定 20/秒）算的，跟着帧率走的话
     * 高帧率下会闪得只剩一片虚影。
     */
    @Override
    public void tick() {
        super.tick();
        if (endpointBox != null) {
            endpointBox.tick();
        }
        // 停止输入满 2 秒就发起探活
        if (position == Position.STEP && step == STEP_PROVIDER) {
            tickEndpointCheck();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /**
     * 点击进度条上「到达过」的金色标记可跳回去：步骤跳回那一步，
     * 末端的「完成」跳到结束页。
     * <p>先判断自己接不接这个点击，再交给控件处理，
     * 避免和「+1」等按钮抢事件（它们在进度条下方，纵向范围不重叠）。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 第 1 步的列表与「点击此处」是自绘的，没有控件来接管点击，先在这里判
            if (position == Position.STEP && step == STEP_PROVIDER) {
                if (ollamaLinkHovered((int) mouseX, (int) mouseY)) {
                    openOllama();
                    return true;
                }
                // 校验失败后的「[点击此处]重新尝试」
                if (customMode() && retryLinkClicked(mouseX, mouseY)) {
                    retryEndpointCheck();
                    return true;
                }
                String picked = pickerRowAt(mouseX, mouseY);
                if (picked != null) {
                    // 「自定义」被选中后，这一行的右列已经被输入框占据。
                    // 点击落在那块区域要交给输入框（否则它永远拿不到焦点、
                    // 打不了字），只有点在左列名字上才算「重新选这一项」。
                    if (isEndpointBoxHit(mouseX, mouseY)) {
                        return super.mouseClicked(mouseX, mouseY, button);
                    }
                    onProviderPicked(picked);
                    return true;
                }
            }

            int hit = hitTestStep(mouseX, mouseY);
            if (hit == HIT_FINISH) {
                if (isFinishClickable()) {
                    jumpToFinish();
                    return true;
                }
            } else if (hit >= 0 && isStepClickable(hit)) {
                jumpToStep(hit + 1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Esc 的行为与左上角「←」完全一致：
     * <ul>
     *   <li>欢迎页：等同「跳过」，结束引导；</li>
     *   <li>其它页：回到上一页。</li>
     * </ul>
     */
    @Override
    public void onClose() {
        prev();
    }

    /**
     * 第 1 步的输入框里按回车 = 点「确认」。
     * <p>原版输入框不吃回车，不给这条路径的话玩家填完地址还得去够鼠标。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (position == Position.STEP && step == STEP_PROVIDER
                && endpointBox != null && endpointBox.isFocused()
                && (keyCode == 257 || keyCode == 335)) {
            next();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }


    /**
     * 边框是绿色的输入框。
     *
     * <p>它被塞进「自定义」那一项的选中框里，原版的白色边框套在绿框里会像
     * 「框中框」，所以要自己画边框。
     *
     * <p><b>但不能关掉 {@code setBordered(false)}。</b> 反编译 1.20.1 的
     * {@code EditBox.renderWidget} 可以看到 bordered 同时决定两件事：
     * <ul>
     *   <li>文字 Y：{@code bordered ? y + (height - 8) / 2 : y} —— 关掉后文字
     *       从垂直居中变成贴顶，会整体上移 4px；</li>
     *   <li>提示文字的绘制条件：{@code hint != null && value.isEmpty() && !isFocused()}
     *       —— 与 bordered 无关，但关掉后背景也不画了，白底会透出来。</li>
     * </ul>
     * 所以这里<b>保持 bordered 为 true</b>，只在自己画的绿框之前先让父类画一遍，
     * 用纯黑底盖住原版的白框与浅色底，最后再把绿框补在最上层。
     */
    private static final class GreenBorderedEditBox extends EditBox {

        /**
         * 边框颜色。
         * <p>做成静态可变字段：本类是 {@code static} 嵌套类，拿不到外部实例的校验状态；
         * 而同一时刻界面上最多只有一个这样的输入框，用静态字段传色最简单。
         * 外部在每次 {@code render} 之前按校验结果设置它。
         */
        private static int borderColor = COLOR_ROW_SELECTED;

        /** 由外部按校验结果设置边框颜色。 */
        static void setBorderColor(int color) {
            borderColor = color;
        }

        GreenBorderedEditBox(net.minecraft.client.gui.Font font, int x, int y, int width, int height,
                             Component message) {
            super(font, x, y, width, height, message);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 父类会按顺序画：外扩 1px 的边框（聚焦白/否则灰）→ 纯黑底 →
            // 文字与光标 → 提示文字。全部交给它，本类只改两件事。
            super.renderWidget(graphics, mouseX, mouseY, partialTick);

            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();

            // 1. 把父类那一圈边框涂成当前状态色。
            //    只涂边框（外扩 1px 的四条 1px 线），不碰内部，
            //    否则会把刚画好的文字和光标一起擦掉。
            graphics.fill(x - 1, y - 1, x + w + 1, y, borderColor);            // 上
            graphics.fill(x - 1, y + h, x + w + 1, y + h + 1, borderColor);    // 下
            graphics.fill(x - 1, y, x, y + h, borderColor);                    // 左
            graphics.fill(x + w, y, x + w + 1, y + h, borderColor);            // 右

            // 2. 提示文字：父类只在「未聚焦」时画（hint != null && value.isEmpty()
            //    && !isFocused()）。这个输入框一被点中就聚焦，提示当场消失，
            //    玩家反而看不到该填什么。这里在聚焦且为空时补画一份。
            if (getValue().isEmpty() && isFocused()) {
                Component hint = Component.literal("请填入 AI 提供商文档中的AI Endpoint")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
                // 与父类同样的排版：左内边距 4px、垂直居中
                graphics.drawString(Minecraft.getInstance().font, hint,
                        x + 4, y + (h - 8) / 2, 0xFF777777, false);
            }
        }
    }
}
