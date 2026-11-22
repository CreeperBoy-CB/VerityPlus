package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.client.ModpackHelperBridge;
import com.cb2495.verityplus.config.GameState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.ModList;

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

    /** 所有进度动画的时长（毫秒）。 */
    private static final long BAR_ANIM_MS = 380L;

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

    /** 第 {@code step} 步（1 起算）。 */
    public static GuideScreen step(int step, Runnable onFinished) {
        return new GuideScreen(Position.STEP, step, onFinished);
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
            case STEP -> Component.literal("继续");
            case FINISH -> Component.literal("完成");
        };

        int centerX = (this.width - BUTTON_WIDTH) / 2;
        // 欢迎页的按钮竖排成一列（从上到下：开始 → 跳过 → 导入旧存档），
        // 整列以 buttonY 为底向上排，这样按钮增删都不会让这一组跑偏
        boolean showSkip = position == Position.START;
        int mainY = showSkip
                ? buttonY - (BUTTON_HEIGHT + BUTTON_GAP) * (1 + (canImportLegacy() ? 1 : 0))
                : buttonY;

        this.addRenderableWidget(Button.builder(mainLabel, btn -> next())
                .pos(centerX, mainY).size(BUTTON_WIDTH, BUTTON_HEIGHT).build());

        if (showSkip) {
            // 原版按钮本身就是灰底，这里把文字改成白色
            this.addRenderableWidget(Button.builder(
                            Component.literal("跳过").withStyle(ChatFormatting.WHITE), btn -> skip())
                    .pos(centerX, buttonY - BUTTON_HEIGHT - BUTTON_GAP)
                    .size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        // 「导入旧存档」由 Modpack Helper 提供；没装那个模组时这一项连文案都不该出现
        if (position == Position.START && canImportLegacy()) {
            this.addRenderableWidget(Button.builder(
                            Component.literal("导入旧存档").withStyle(ChatFormatting.GRAY), btn -> importLegacy())
                    .pos(centerX, buttonY).size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
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
                    // 第 1 步的上一页是欢迎页
                    mc.setScreen(GuideScreen.start(onFinished));
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

    /**
     * 打开 Modpack Helper 的旧存档导入界面。
     * <p>「跳过，直接开始配置」之后回到<b>本页</b>而不是直接开始：玩家是在引导途中
     * 顺路导存档，引导的进度不该因此丢掉。
     * <p>调用失败（没装 / 版本对不上）时什么都不做，按钮的悬停提示已经说清楚了。
     */
    private void importLegacy() {
        ModpackHelperBridge.openLegacySaveImport(this, () ->
                Minecraft.getInstance().setScreen(GuideScreen.start(onFinished)));
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
     * 推进进度条动画：按<b>缓出曲线</b>从上一位置过渡到目标位置。
     *
     * <p>做法是「补间」而不是「匀速追赶」：记录起点值、终点值和起始时间，
     * 每帧用 {@code easeOutCubic} 把 0..1 的时间映射成 0..1 的位移比例。
     * 相比线性逼近，快慢变化更明显——开头快、结尾缓，看起来更顺滑。
     *
     * <p>两种过渡共用一个动画状态：
     * <ul>
     *   <li><b>出现</b>：底槽与绿色填充一起从 0 拉出去；</li>
     *   <li><b>翻页 / +1</b>：已在显示的进度条平滑推进到新位置。</li>
     * </ul>
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

        float t = (now - animStartMs) / (float) BAR_ANIM_MS;
        if (t >= 1.0f) {
            shownSubSteps = animTo;
            animating = false;
        } else {
            // 缓出：开头快、结尾缓
            float eased = easeOutCubic(t);
            shownSubSteps = animFrom + (animTo - animFrom) * eased;
        }
        lastAnimFrameMs = now;
    }

    /** 缓出曲线：{@code 1-(1-t)^3}。t=0→0，t=1→1，且前段变化快、后段趋缓。 */
    private static float easeOutCubic(float t) {
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }

    /**
     * 出现动画的进度 0..1：点「开始」后底槽从左侧拉出。
     * <p>同样是缓出曲线，与绿条的推进各自独立。
     * <p>若起始时间尚未设置（正常不会发生），就地补一个，避免进度条永远不出现。
     */
    private float appearProgress() {
        if (appearStartMs == 0L) {
            appearStartMs = System.currentTimeMillis();
        }
        long elapsed = System.currentTimeMillis() - appearStartMs;
        if (elapsed >= BAR_ANIM_MS) return 1.0f;
        return easeOutCubic(elapsed / (float) BAR_ANIM_MS);
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
            case STEP -> (step - 1) * SUB_STEPS + subStepProgress;
            case FINISH -> GameState.STEPS * SUB_STEPS;
        };
        int total = GameState.STEPS * SUB_STEPS;
        return Math.max(0, Math.min(total, done));
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

    /** 正文文字（演示版占位）。 */
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

        // 正文：欢迎页有真实文案，其余两页还是占位
        if (position == Position.START) {
            renderStartText(graphics);
        } else {
            graphics.drawCenteredString(this.font, Component.literal(bodyText()),
                    centerX, this.height / 2 - 20, COLOR_HEADING);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
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
}
