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

    /** 第 1 步左侧列表与右侧介绍面板的宽度。 */
    private static final int PICKER_LIST_WIDTH = 170;
    private static final int PICKER_PANEL_WIDTH = 200;
    /** 列表与右侧面板之间的间隙。 */
    private static final int PICKER_COLUMN_GAP = 10;
    /** 列表一行的行高。 */
    private static final int PICKER_ROW_HEIGHT = 18;
    /** 列表左右内边距。 */
    private static final int PICKER_TEXT_INSET = 4;
    /** 列表可视高度（不含滚动条与边框）。 */
    private static final int PICKER_LIST_VISIBLE = PICKER_ROW_HEIGHT * 9;
    /** 列表滚动条宽度。 */
    private static final int PICKER_SCROLL_WIDTH = 3;

    /** 提示文字的颜色：正常（中性）、警告（红）、成功（绿）。 */
    private static final int COLOR_NEUTRAL = 0xFFAAAAAA;
    private static final int COLOR_WARN = 0xFFFF5555;
    private static final int COLOR_OK = 0xFF55FF55;

    /** 列表底色与边框。 */
    private static final int COLOR_LIST_BG = 0xC0000000;
    private static final int COLOR_LIST_BORDER = 0xFF555555;
    private static final int COLOR_ROW_SELECTED = 0xFF4CAF50;

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

    /** 已经选好了提供商（「（请选择）」不算）。 */
    private boolean providerChosen() {
        return pickedProvider != null && !Providers.PICKER_PLACEHOLDER.equals(pickedProvider);
    }

    /**
     * 第 1 步的控件布局。
     * <p>列表与右侧面板都是自绘的（见 {@link #renderProviderStep}），
     * 这里只放真正需要事件系统的控件：自定义输入框、「确认」。
     * <p>原来那个下拉框不再使用：整屏只填一个 240px 宽的下拉框太空，
     * 换来的是中间一整块两列的滚动列表 + 介绍面板。
     */
    private void initProviderPicker(int buttonY) {
        int centerX = this.width / 2;

        // 自定义模式的输入框：占满右侧面板。
        // 往里缩 4px，否则它自带的边框会压在面板边框上，看起来像外框套内框。
        if (customMode()) {
            EditBox box = new EditBox(this.font, pickerPanelLeft() + 4, pickerPanelTop() + 4,
                    PICKER_PANEL_WIDTH - 8, PICKER_ROW_HEIGHT, Component.literal("AI Endpoint"));
            box.setMaxLength(512);
            box.setValue(customEndpoint);
            box.setHint(Component.literal("键入 API 提供商文档内的 OpenAI BaseURL……")
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

    /** 列表整体的左边缘：两列（列表 + 面板）合起来在屏幕上水平居中。 */
    private int pickerBlockLeft() {
        int total = PICKER_LIST_WIDTH + PICKER_COLUMN_GAP + PICKER_PANEL_WIDTH;
        return (this.width - total) / 2;
    }

    /** 列表左边缘。 */
    private int pickerListLeft() {
        return pickerBlockLeft();
    }

    /** 右侧面板的左边缘。 */
    private int pickerPanelLeft() {
        return pickerBlockLeft() + PICKER_LIST_WIDTH + PICKER_COLUMN_GAP;
    }

    /**
     * 列表与面板共用的顶端。
     * <p>整块（问题行 → 两列列表 → Ollama 链接 → 提示行）在「问题行 ~ 按钮上方」
     * 这段空白里垂直居中。原来贴着问题行往下排，1280×720 下按钮上方会空出
     * 四百多像素，整页头重脚轻。
     */
    private int pickerPanelTop() {
        // 从问题行下方到提示行底部，整块的净高
        int blockHeight = 12 + (PICKER_LIST_VISIBLE + 2)
                + 10 + this.font.lineHeight
                + 6 + this.font.lineHeight;

        int top = providerQuestionY() + this.font.lineHeight;              // 可排区顶端
        int bottom = this.height - BUTTON_BOTTOM_MARGIN - BUTTON_HEIGHT - 12; // 可排区底端
        int spare = Math.max(0, bottom - top - blockHeight);
        return top + spare / 2;
    }

    /** 列表底端（含边框）。 */
    private int pickerListBottom() {
        return pickerPanelTop() + PICKER_LIST_VISIBLE + 2;
    }

    /** 列表内可滚动的最大偏移。 */
    private int pickerMaxScroll() {
        int content = pickerItems().size() * PICKER_ROW_HEIGHT;
        return Math.max(0, content - PICKER_LIST_VISIBLE);
    }

    /** 选中了一项：只记下来，右侧面板随之刷新。 */
    private void onProviderPicked(String label) {
        pickedProvider = label;
        showProviderHint("", COLOR_NEUTRAL);
        // 自定义与其他项的右侧内容不一样（输入框 ↔ 说明），要重建控件
        this.rebuildWidgets();
    }

    /** 玩家改了自定义 Endpoint。 */
    private void onCustomEndpointEdited(String text) {
        customEndpoint = text;
        showProviderHint("", COLOR_NEUTRAL);
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
        Log.info("引导第 1 步：提供商已切换为 " + pickedProvider
                + "（aiProvider=" + data.provider + ", aiEndpoint=" + data.endpoint + "）");
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

    /** 画第 1 步：问题、两列列表、右侧介绍/输入框、底部 Ollama 链接、提示行。 */
    private void renderProviderStep(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;

        graphics.drawCenteredString(this.font, Component.literal(PROVIDER_QUESTION),
                centerX, providerQuestionY(), COLOR_BODY);

        renderProviderList(graphics, mouseX, mouseY);
        renderProviderPanel(graphics);
        renderOllamaLink(graphics, mouseX, mouseY);

        if (!providerHint.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(providerHint),
                    centerX, providerHintY(), providerHintColor);
        }
    }

    /** 左列：可滚动的提供商列表。 */
    private void renderProviderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = pickerListLeft();
        int top = pickerPanelTop();
        int right = left + PICKER_LIST_WIDTH;
        int bottom = pickerListBottom();

        graphics.fill(left, top, right, bottom, COLOR_LIST_BG);
        graphics.renderOutline(left, top, PICKER_LIST_WIDTH, bottom - top, COLOR_LIST_BORDER);

        List<Providers.PickerOption> items = pickerItems();
        int scroll = Math.max(0, Math.min(pickerMaxScroll(), pickerScroll));
        pickerScroll = scroll;

        // 只画落在可视区里的行：滚动偏移可能停在半行上，首行用 -scroll % ROW 对齐
        int first = scroll / PICKER_ROW_HEIGHT;
        int offset = scroll % PICKER_ROW_HEIGHT;

        int rowRight = right - PICKER_SCROLL_WIDTH - 1;
        graphics.enableScissor(left + 1, top + 1, rowRight, bottom - 1);
        for (int i = first; i < items.size(); i++) {
            int rowTop = top + 1 + (i - first) * PICKER_ROW_HEIGHT - offset;
            if (rowTop >= bottom - 1) break;
            if (rowTop + PICKER_ROW_HEIGHT <= top + 1) continue;

            Providers.PickerOption item = items.get(i);
            // 还没选的时候「（请选择）」显示成选中态，与实际配置一致
            boolean selected = item.isPlaceholder()
                    ? !providerChosen()
                    : item.label().equals(pickedProvider);

            boolean hovered = mouseX >= left + 1 && mouseX < rowRight
                    && mouseY >= rowTop && mouseY < rowTop + PICKER_ROW_HEIGHT;

            if (selected) {
                graphics.fill(left + 1, rowTop, rowRight,
                        Math.min(rowTop + PICKER_ROW_HEIGHT, bottom - 1), 0x5500FF00);
            } else if (hovered) {
                graphics.fill(left + 1, rowTop, rowRight,
                        Math.min(rowTop + PICKER_ROW_HEIGHT, bottom - 1), 0x30FFFFFF);
            }

            int color = selected ? COLOR_ROW_SELECTED : hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
            int textY = rowTop + (PICKER_ROW_HEIGHT - this.font.lineHeight) / 2;
            graphics.drawString(this.font, item.label(),
                    left + PICKER_TEXT_INSET, textY, color, false);
        }
        graphics.disableScissor();

        // 滚动条：内容装得下时不画
        int maxScroll = pickerMaxScroll();
        if (maxScroll > 0) {
            int trackX = right - PICKER_SCROLL_WIDTH - 1;
            int trackTop = top + 1;
            int trackBottom = bottom - 1;
            int trackHeight = trackBottom - trackTop;
            int contentHeight = items.size() * PICKER_ROW_HEIGHT;
            int thumbHeight = Math.max(12, trackHeight * PICKER_LIST_VISIBLE / contentHeight);
            int thumbY = trackTop + (int) ((long) (trackHeight - thumbHeight) * scroll / maxScroll);
            graphics.fill(trackX, trackTop, trackX + PICKER_SCROLL_WIDTH, trackBottom, 0xFF333333);
            graphics.fill(trackX, thumbY, trackX + PICKER_SCROLL_WIDTH, thumbY + thumbHeight, 0xFFAAAAAA);
        }
    }

    /** 右列：选中项的介绍；选「自定义」时换成 Endpoint 输入框。 */
    private void renderProviderPanel(GuiGraphics graphics) {
        int left = pickerPanelLeft();
        int top = pickerPanelTop();
        int right = left + PICKER_PANEL_WIDTH;
        int bottom = pickerListBottom();

        graphics.fill(left, top, right, bottom, COLOR_LIST_BG);
        graphics.renderOutline(left, top, PICKER_PANEL_WIDTH, bottom - top, COLOR_LIST_BORDER);

        if (customMode()) {
            // 输入框本身就是控件（由 super.render 绘制），这里只补一句说明。
            // 位置从输入框下方起算，避免和输入框内的灰色斜体提示叠在一起。
            int y = top + PICKER_ROW_HEIGHT + 12;
            int wrapWidth = PICKER_PANEL_WIDTH - 12;
            String note = "仅限 OpenAI 接口。地址一般以 /v1 结尾，填错会连不上。";
            for (FormattedCharSequence line : this.font.split(Component.literal(note), wrapWidth)) {
                if (y + this.font.lineHeight > bottom - 4) break;
                graphics.drawString(this.font, line, left + 6, y, COLOR_NEUTRAL, false);
                y += this.font.lineHeight + 2;
            }
            return;
        }

        Providers.PickerOption option = pickedProvider == null
                ? pickerItems().get(0) : Providers.pickerOption(pickedProvider);
        if (option == null) return;

        // 介绍按面板宽度折行，从面板顶端开始往下排
        int y = top + 6;
        int wrapWidth = PICKER_PANEL_WIDTH - 12;
        for (FormattedCharSequence line : this.font.split(Component.literal(option.desc()), wrapWidth)) {
            if (y + this.font.lineHeight > bottom - 4) break;
            graphics.drawString(this.font, line, left + 6, y, COLOR_NEUTRAL, false);
            y += this.font.lineHeight + 2;
        }

        // 选中项的额外技术提示（如「模型尚未配置」）放在介绍下面，金色提醒
        if (providerChosen() && option.note() != null) {
            y += 4;
            for (FormattedCharSequence line : this.font.split(Component.literal(option.note()), wrapWidth)) {
                if (y + this.font.lineHeight > bottom - 4) break;
                graphics.drawString(this.font, line, left + 6, y, COLOR_DONE, false);
                y += this.font.lineHeight + 2;
            }
        }
    }

    /** 底部的「不想为 API 付费？」链接：普通文字 + 可点的绿色方括号，悬停出提示。 */
    private void renderOllamaLink(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int y = pickerListBottom() + 10;

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

    /** 鼠标是否停在「点击此处」上。 */
    private boolean ollamaLinkHovered(int mouseX, int mouseY) {
        return mouseX >= ollamaLinkLeft && mouseX < ollamaLinkRight
                && mouseY >= ollamaLinkTop && mouseY < ollamaLinkBottom;
    }

    /** 提示行 Y：Ollama 链接下方。 */
    private int providerHintY() {
        return pickerListBottom() + 10 + this.font.lineHeight + 6;
    }

    /**
     * 鼠标位置落在列表的哪一行上；不在列表内或落在空白处时返回 {@code null}。
     * <p>换算方式与 {@link #renderProviderList} 的绘制保持一致：
     * 先算行号，再用同一个滚动偏移反推这一行的屏幕位置。
     */
    private String pickerRowAt(double mouseX, double mouseY) {
        int left = pickerListLeft();
        int top = pickerPanelTop();
        int right = left + PICKER_LIST_WIDTH - PICKER_SCROLL_WIDTH - 1;
        int bottom = pickerListBottom();
        if (mouseX < left + 1 || mouseX >= right || mouseY < top + 1 || mouseY >= bottom - 1) {
            return null;
        }

        List<Providers.PickerOption> items = pickerItems();
        int scroll = Math.max(0, Math.min(pickerMaxScroll(), pickerScroll));
        int first = scroll / PICKER_ROW_HEIGHT;
        int offset = scroll % PICKER_ROW_HEIGHT;

        for (int i = first; i < items.size(); i++) {
            int rowTop = top + 1 + (i - first) * PICKER_ROW_HEIGHT - offset;
            if (mouseY >= rowTop && mouseY < rowTop + PICKER_ROW_HEIGHT) {
                return items.get(i).label();
            }
            if (rowTop >= bottom) break;
        }
        return null;
    }

    /**
     * 第 1 步的列表滚轮。
     * <p>先于 {@code super} 处理：列表是自绘的，交给控件链的话会被当成「没人要」的事件。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (position == Position.STEP && step == STEP_PROVIDER) {
            int left = pickerListLeft();
            int right = left + PICKER_LIST_WIDTH;
            if (mouseX >= left && mouseX < right
                    && mouseY >= pickerPanelTop() && mouseY < pickerListBottom()
                    && pickerMaxScroll() > 0) {
                // 一次滚一格（整行），半行滚动会让列表看起来在抖
                pickerScroll = Math.max(0, Math.min(pickerMaxScroll(),
                        pickerScroll - (int) Math.round(delta) * PICKER_ROW_HEIGHT));
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
            renderProviderStep(graphics, mouseX, mouseY);
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
            // 第 1 步的列表与「点击此处」是自绘的，没有控件来接管点击，先在这里判
            if (position == Position.STEP && step == STEP_PROVIDER) {
                if (ollamaLinkHovered((int) mouseX, (int) mouseY)) {
                    openOllama();
                    return true;
                }
                String picked = pickerRowAt(mouseX, mouseY);
                if (picked != null) {
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
}
