package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.config.GameState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * 「上次的配置未完成，是否继续？」确认框。
 * <p>玩家在引导中途关掉游戏后，下次启动会先看到这个。
 *
 * <h3>两个按钮的去向</h3>
 * <ul>
 *   <li>继续 —— 跳回上次停下的那<b>页</b>接着做（可能是欢迎页、某一步或结束页）；</li>
 *   <li>从头开始 —— <b>回到欢迎页</b>重新开始。</li>
 * </ul>
 * 取消不直接回主界面，是因为「取消」最常见的含义是「别接上次的，重来」，
 * 而不是「我不想配置了」。真的不想配置的玩家，在欢迎页点「跳过」即可，
 * 那条路本来就在。
 * <p>状态不在这里改：进到 {@link GuideScreen} 的 {@code init()} 时会
 * 自动写成对应页。
 */
public final class ResumePrompt {

    private ResumePrompt() {}

    /** 弹出确认框。 */
    public static void ask() {
        String where = describeCurrentPage();
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(
                resume -> {
                    if (resume) {
                        // 跳回上次那一页继续
                        mc.setScreen(GuideScreen.fromCurrentPage(null));
                    } else {
                        // 回到欢迎页重来。欢迎页有「跳过」，想彻底放弃的玩家走那条
                        mc.setScreen(GuideScreen.start(null));
                    }
                },
                Component.literal("配置未完成"),
                Component.literal("上次的配置未完成（停在" + where + "）。\n"
                        + "是否接着上次继续？选「从头开始」将回到欢迎页重新配置。"),
                // 默认的「是 / 否」读不出差异，换成明确动词
                Component.literal("继续"),
                Component.literal("从头开始")));
    }

    /** 用玩家看得懂的说法描述停在哪一页。 */
    private static String describeCurrentPage() {
        if (GameState.atStartPage()) return "欢迎页";
        if (GameState.atFinishPage()) return "结束页";
        return "第 " + GameState.step() + " 步";
    }
}
