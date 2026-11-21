package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.config.GameState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 引导教程界面（演示版）。
 *
 * <h3>当前只是骨架</h3>
 * 正文固定为「这是引导教程第 x 步」，用来先把<b>流程</b>跑通：
 * 状态怎么记、退出后回到哪一步、跳过怎么收尾。
 * 每一步的真实内容（选提供商、填 Key 等）之后再往里填。
 *
 * <h3>三个按钮</h3>
 * <ul>
 *   <li>返回 —— 仅当不是第一步时显示，回到上一步；</li>
 *   <li>继续 —— 下一步；最后一步时文案变成「完成」并结束引导；</li>
 *   <li>跳过 —— 仅在第一步显示，直接结束引导。</li>
 * </ul>
 *
 * <h3>与状态文件的关系</h3>
 * 每进到一步就写一次 {@link GameState#enterStep}，所以玩家在任意一步关掉游戏，
 * 下次启动都会从他停下那一步问「是否继续」。走到最后一步点「完成」
 * （或第一步点「跳过」）则 {@link GameState#finishGuide()} 置为 normal。
 */
public class GuideScreen extends Screen {

    // ---------- 布局 ----------
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 20;
    /** 相邻按钮之间的间隙。 */
    private static final int BUTTON_GAP = 5;
    /** 按钮行距离屏幕底部的边距。 */
    private static final int BUTTON_BOTTOM_MARGIN = 40;

    /** 左上角小返回按钮的尺寸，比普通按钮矮一些，不抢视觉重心。 */
    private static final int BACK_BUTTON_WIDTH = 20;
    private static final int BACK_BUTTON_HEIGHT = 20;
    /** 小返回按钮与屏幕左边缘、上边缘的距离。 */
    private static final int BACK_BUTTON_MARGIN = 6;

    /** 第几步（1 起算）。 */
    private final int step;

    /**
     * @param step 要显示的步骤，会被夹到 1..{@link GameState#STEPS}
     */
    public GuideScreen(int step) {
        super(Component.literal("引导教程"));
        this.step = Math.max(1, Math.min(GameState.STEPS, step));
    }

    /** 从当前记录的状态打开，用于「继续上次的配置」。 */
    public static void openResume() {
        Minecraft.getInstance().setScreen(new GuideScreen(GameState.step()));
    }

    /** 从第一步开始打开，用于首次启动。 */
    public static void openFromStart() {
        Minecraft.getInstance().setScreen(new GuideScreen(1));
    }

    @Override
    protected void init() {
        super.init();

        // 进入本步就落盘：玩家在这里直接关游戏也能正确记住进度
        GameState.enterStep(step);

        int buttonY = this.height - BUTTON_BOTTOM_MARGIN;

        // 左上角的小返回按钮：只画一个「←」，不是全宽的按钮
        if (step > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("←"), btn -> prev())
                    .pos(BACK_BUTTON_MARGIN, BACK_BUTTON_MARGIN)
                    .size(BACK_BUTTON_WIDTH, BACK_BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.literal("上一步")))
                    .build());
        }

        // 底部按钮：主按钮（继续/完成）始终水平居中；
        // 「跳过」只在第 1 步出现，放在主按钮正下方，不与它抢位置。
        Component nextLabel = step >= GameState.STEPS
                ? Component.literal("完成")
                : Component.literal("继续");

        int centerX = (this.width - BUTTON_WIDTH) / 2;
        // 有跳过时整体上移一行，让「继续 + 跳过」这两行仍然视觉居中
        boolean showSkip = step == 1;
        int mainY = showSkip ? buttonY - (BUTTON_HEIGHT + BUTTON_GAP) : buttonY;

        this.addRenderableWidget(Button.builder(nextLabel, btn -> next())
                .pos(centerX, mainY).size(BUTTON_WIDTH, BUTTON_HEIGHT).build());

        if (showSkip) {
            this.addRenderableWidget(Button.builder(
                            Component.literal("跳过").withStyle(ChatFormatting.GRAY), btn -> skip())
                    .pos(centerX, buttonY).size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    // ---------- 流程 ----------

    private void prev() {
        if (step <= 1) return;
        // 返回不写状态：回到上一步后 init() 会重新 enterStep，
        // 重复写一次没有意义，反而多一次磁盘操作
        Minecraft.getInstance().setScreen(new GuideScreen(step - 1));
    }

    private void next() {
        if (step >= GameState.STEPS) {
            finish();
            return;
        }
        Minecraft.getInstance().setScreen(new GuideScreen(step + 1));
    }

    private void skip() {
        finish();
    }

    /** 结束引导：状态置为 normal，然后退出界面。 */
    private void finish() {
        GameState.finishGuide();
        Minecraft.getInstance().setScreen(null);
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int centerX = this.width / 2;

        // 标题
        graphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFFFFF);

        // 正文（演示版）：大字居中，先把位置占住
        graphics.drawCenteredString(this.font,
                Component.literal("这是引导教程第 " + step + " 步"),
                centerX, this.height / 2 - 20, 0xFFFFD700);

        // 步骤指示（右下角，不抢正文的位置）
        String progress = step + " / " + GameState.STEPS;
        graphics.drawString(this.font, progress,
                this.width - this.font.width(progress) - 10,
                this.height - 12, 0xFF888888, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /**
     * Esc 的行为随步骤变化：
     * <ul>
     *   <li>第 1 步：等同「跳过」，结束引导；</li>
     *   <li>第 2 步及以后：等同左上角的「←」，回到上一步。</li>
     * </ul>
     * <p>没有「取消整个引导」的入口是刻意的：引导是流程的一部分，
     * 玩家真想中断直接关游戏，下次启动会问「是否继续」。
     */
    @Override
    public void onClose() {
        if (step > 1) {
            prev();
        } else {
            skip();
        }
    }
}
