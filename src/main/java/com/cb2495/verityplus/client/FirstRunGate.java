package com.cb2495.verityplus.client;

import com.cb2495.verityplus.VerityPlus;
import com.cb2495.verityplus.config.GameState;
import com.cb2495.verityplus.screen.GuideScreen;
import com.cb2495.verityplus.screen.ResumePrompt;
import com.cb2495.verityplus.util.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 首次启动的引导入口：决定什么时候弹出 {@link GuideScreen}。
 *
 * <h3>为什么需要「等开场动画」</h3>
 * Verity 第一次打开主界面时，会在 {@code ScreenEvent.Opening} 里把
 * {@link TitleScreen} 整个换成它自己的 {@code varmite.verity.client.IntroVideoScreen}
 * （248 帧 / 24 FPS，约 10.3 秒）。要是这时候抢先 {@code setScreen}，
 * 动画会被我们顶掉，玩家还没看过就没了。所以必须先等它播完。
 *
 * <h3>怎么知道它播完了：只看一个信号</h3>
 * <p><b>不要用 {@code ScreenEvent.Closing}。</b> 那个事件在 Forge 1.20.1 里
 * <b>没有任何地方触发</b>（反查 forge universal jar 的常量池，
 * 除了它自己的定义类之外没有任何类引用它），监听它等于永远收不到回调。
 *
 * <p>可靠且唯一的信号是 {@link ScreenEvent.Init.Post}：
 * <ol>
 *   <li>{@code Init.Post(IntroVideoScreen)} —— 动画开始，记下 <b>当前是第几轮</b>；</li>
 *   <li>{@code Init.Post(TitleScreen)} —— 主界面就绪。</li>
 * </ol>
 * 动画结束的唯一路径是 {@code skip()} 里的 {@code setScreen(previousScreen)}，
 * 而 {@code previousScreen} 就是那个 {@code TitleScreen} 实例，
 * <b>重新 setScreen 同一实例会重新 init</b>，所以「动画结束」必然表现为
 * 「动画 init 之后又来了一次 TitleScreen init」。实测日志确认了这一顺序：
 * <pre>
 * 13:44:57.803  检测到 Verity 开场动画
 * 13:45:08.253  主界面控件文字: [...]   ← 10.45 秒后（动画 10.33 秒）
 * </pre>
 *
 * <h3>保险：兜底超时</h3>
 * 万一哪天 Verity 改了实现（类名变了、帧率变了、卡住不结束），
 * 光等事件会让引导页永远不出现。所以动画开始后同时记录一个截止时间，
 * 到点无论如何都弹。动画固定时长 248/24 ≈ 10.33 秒，取 12 秒留足余量。
 */
@Mod.EventBusSubscriber(modid = VerityPlus.MODID, value = Dist.CLIENT)
public final class FirstRunGate {

    private FirstRunGate() {}

    /** Verity 开场动画界面的类名。只做字符串比较，不加载该类。 */
    private static final String INTRO_SCREEN_CLASS = "varmite.verity.client.IntroVideoScreen";

    /** 等动画的上限：动画 248 帧 @ 24 FPS ≈ 10.33 秒，留到 12 秒。 */
    private static final long ANIMATION_TIMEOUT_MS = 12_000L;

    // ---------- 状态 ----------
    /** 已经处理过这次启动的引导判定，不再重复。 */
    private static boolean gateSettled = false;
    /** 开场动画正在播（或我们已经认定它在播）。 */
    private static boolean introActive = false;
    /** 等动画的截止时间。 */
    private static long waitDeadline = 0L;
    /** 本次启动已经弹过引导（或决定不弹），防止从存档退回主界面时又弹一次。 */
    private static boolean guideShownThisSession = false;
    /**
     * 已经决定要弹引导，但要等这一帧的界面切换彻底结束再动手。
     * <p><b>为什么不能立即 setScreen：</b>{@code Minecraft.setScreen} 的顺序是
     * 「旧界面收尾 → 新界面构造并 init → 才把 {@code this.screen} 换成新界面」。
     * 而 {@code ScreenEvent.Init.Post} 是在新界面 init 的内部触发的，
     * 此刻 {@code mc.screen} 还指向上一个界面。如果在这时切换，
     * 外层那次 {@code setScreen} 收尾时会把 {@code this.screen} 重新赋成
     * 它自己的新界面，把我们刚放上去的引导页直接盖掉。
     */
    private static boolean pendingGuide = false;
    /** 连续多少个 tick 观察到玩家停在主界面；用于事件漏接时的兜底判定。 */
    private static int pendingTitleTicks = 0;

    // ---------- 事件 ----------

    /**
     * 界面初始化完成时调用，等价于「这个界面已经显示出来了」。
     * <p>用它而不是 {@code Opening}：{@code Opening} 只是「要切过去」，
     * 允许其它模组替换目标界面（Verity 就是这样换掉主界面的），
     * 必须等切换完成才看得到最终是哪个界面。
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (isIntroScreen(event.getScreen())) {
            onIntroStarted();
        } else if (event.getScreen() instanceof TitleScreen) {
            onTitleScreen();
        }
    }

    /**
     * 兜底超时、延迟切换，以及<b>事件之外的主界面兜底检测</b>。
     * <p>放在 tick 末尾：此刻本帧所有 {@code setScreen} 都已经收尾，
     * {@code mc.screen} 是稳定下来的最终界面，这时候换上去不会被覆盖。
     *
     * <p><b>为什么要在这里再看一眼 {@code mc.screen}：</b>
     * 只靠 {@code ScreenEvent.Init.Post} 会有漏的情况——主界面可能在
     * 我们的监听器「真正生效」之前就已经初始化完毕（启动早期、
     * Verity 关掉开场动画时尤甚），之后再没有任何 init 事件可等，
     * 引导就永远不弹了。这里每个 tick 直接看一次当前界面，
     * 只要它稳稳停在主界面上、且我们还没处理过，就补上这一次。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (pendingGuide) {
            pendingGuide = false;
            openGuideNow();
            return;
        }

        if (introActive) {
            if (System.currentTimeMillis() >= waitDeadline) {
                Log.warn("等待 Verity 开场动画超时（" + ANIMATION_TIMEOUT_MS + " ms），直接进入引导");
                introActive = false;
                fireGuide();
            }
            return;
        }

        // 兜底：事件没接上时，靠这里发现「已经停在主界面」
        Minecraft mc = Minecraft.getInstance();
        if (!gateSettled && !guideShownThisSession && mc.screen instanceof TitleScreen) {
            // 连着两个 tick 都在主界面才算稳：冷启动时第一帧的界面可能还没定下来
            if (pendingTitleTicks++ >= 1) {
                Log.info("轮询发现玩家停在主界面，进入引导判定");
                pendingTitleTicks = 0;
                fireGuide();
            }
        } else {
            pendingTitleTicks = 0;
        }
    }

    // ---------- 内部 ----------

    /** 开场动画开始播放。 */
    private static void onIntroStarted() {
        if (gateSettled || guideShownThisSession) return;

        if (!GameState.needsGuide()) {
            // 状态是 normal：连等待都不必进，省得白挂 12 秒
            gateSettled = true;
            return;
        }

        introActive = true;
        waitDeadline = System.currentTimeMillis() + ANIMATION_TIMEOUT_MS;
        Log.info("检测到 Verity 开场动画，等它播完再进入引导");
    }

    /**
     * 主界面就绪。
     * <p>两种情况会走到这里：
     * <ul>
     *   <li>从来没播过动画（配置关了 / Verity 没装 / 本次进程已播过一次）
     *       —— {@code introActive} 是 false，直接弹；</li>
     *   <li>动画刚结束 —— {@code introActive} 是 true，这正是「等到了」。</li>
     * </ul>
     *
     * <p><b>这里刻意没有任何「玩家在退出」的守卫。</b>
     * 早先版本用「距上一次界面初始化不到 3 秒」来猜玩家要退出，
     * 结果冷启动时 {@code TitleScreen.init()} 本身就会被连续调用两次
     * （实测间隔 16 毫秒），第二次被误判成退出，引导从此再也不弹。
     * <p>真正该防的「退出游戏」场景：玩家想退出时看到引导页也无所谓——
     * 他已经做完决定了，界面不会改变什么；而误判的代价是引导永远不出现。
     * 两害相权，宁可不守。
     */
    private static void onTitleScreen() {
        if (gateSettled || guideShownThisSession) return;

        if (introActive) {
            Log.info("Verity 开场动画已结束（主界面重新初始化），进入引导");
            introActive = false;
        }

        fireGuide();
    }

    /** 真正决定要弹引导：只置标志，实际切换留给下一个 tick 的末尾。 */
    private static void fireGuide() {
        if (gateSettled || guideShownThisSession) return;

        if (!GameState.needsGuide()) {
            gateSettled = true;
            return;
        }

        guideShownThisSession = true;
        gateSettled = true;
        pendingGuide = true;
        Log.info("准备进入引导（下次 tick 末尾切换界面）");
    }

    /**
     * 在 tick 末尾真正切换界面。
     * <p>此时 {@code mc.screen} 一定是稳定下来的最终界面，
     * 不会再被外层 {@code setScreen} 覆盖。
     */
    private static void openGuideNow() {
        Minecraft mc = Minecraft.getInstance();

        // 只有当玩家确实停在主界面时才动手：如果他已经在别的地方
        // （进入了存档、打开了设置），就不要硬塞引导。
        if (!(mc.screen instanceof TitleScreen)) {
            Log.warn("准备弹引导时玩家已不在主界面（" + mc.screen + "），跳过本次");
            return;
        }

        if (GameState.needsResumePrompt()) {
            // 做到一半退出了：先问要不要继续
            ResumePrompt.ask();
        } else {
            // 首次启动：直接开讲
            GuideScreen.openFromStart();
        }
    }

    /**
     * 只按类名判断，不 {@code Class.forName}：
     * 没装 Verity 时 {@code forName} 会抛异常，而字符串比较只是不匹配。
     */
    private static boolean isIntroScreen(Object screen) {
        return screen != null && INTRO_SCREEN_CLASS.equals(screen.getClass().getName());
    }
}
