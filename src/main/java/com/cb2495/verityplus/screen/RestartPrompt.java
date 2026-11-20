package com.cb2495.verityplus.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * 「是否需要重启游戏」确认框。
 * <p>这段代码原先在 {@code VerityPlusScreen}、模组列表和教程提醒界面里各写了一份，
 * 内容完全一致；后两个界面已随模组管理功能一起移除。
 * <p>默认文案用于「配置已保存」，启动时的配置自检修复、首次启动收尾则用
 * {@link #show(String, String)} 传入自己的说明。
 */
public final class RestartPrompt {

    private RestartPrompt() {}

    /**
     * 弹出重启确认框。
     * <p>确认则直接结束游戏进程；取消时：在存档里就回到游戏，否则回主界面。
     */
    public static void show() {
        show("重启游戏以应用配置", "配置已保存，是否重启游戏？");
    }

    /** 用自定义文案弹出重启确认框；交互行为与 {@link #show()} 相同。 */
    public static void show(String title, String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                mc.stop();
            } else if (mc.player != null) {
                mc.setScreen(null);
            } else {
                mc.setScreen(new TitleScreen());
            }
        },
                Component.literal(title),
                Component.literal(message)));
    }
}
