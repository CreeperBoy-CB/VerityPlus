package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.config.GameState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * 「上次的配置未完成，是否继续？」确认框。
 * <p>玩家在引导中途关掉游戏后，下次启动会先看到这个；确认则跳回原来那一步，
 * 取消则结束引导（状态记为完成，不再反复打扰）。
 */
public final class ResumePrompt {

    private ResumePrompt() {}

    /** 弹出确认框。 */
    public static void ask() {
        int step = GameState.step();
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        // 跳回上次那一步继续
                        mc.setScreen(new GuideScreen(step));
                    } else {
                        // 不想继续就当作结束，避免每次启动都问一遍
                        GameState.finishGuide();
                        mc.setScreen(null);
                    }
                },
                Component.literal("配置未完成"),
                Component.literal("上次的配置未完成（进行到第 " + step + " 步），是否继续配置？")));
    }
}
