package com.cb2495.verityplus.client;

import com.cb2495.verityplus.VerityPlus;
import com.cb2495.verityplus.util.Log;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;

/**
 * 把 Verity 踢人时那句 {@code Farewell, <玩家名>} 换成中文。
 *
 * <h3>为什么不能放 {@code ChatTranslator} 里</h3>
 * 玩家被 Verity 恶魔杀死时，服务端调的是 {@code ServerPlayer#disconnect}，
 * 发的是 {@code ClientboundDisconnectPacket}；客户端收到后直接进
 * {@link DisconnectedScreen}，<b>完全不经过聊天事件</b>。
 * 所以 {@code ClientReceiveEvent} 那条链路碰不到它，
 * 词表里虽然写了这句，实际上一次都不会命中。
 *
 * <h3>改哪一块</h3>
 * 断线界面的正文是 {@code init()} 里用
 * {@code new MultiLineTextWidget(reason, font)} 现场造的，外面没有引用。
 * 但那个 widget 的文案就在自己的 {@code message} 字段上，而
 * {@code setMessage(Component)} 是公开方法，直接换掉即可。
 *
 * <h3>为什么换 message 就够，不用重建界面</h3>
 * {@code MultiLineTextWidget} 用 {@code SingleKeyCache} 缓存折行结果，
 * 缓存键是 {@code CacheKey(message, maxWidth, maxRows)} 这个 record——
 * {@code Component} 没有覆写 {@code equals}，比较的就是对象本身。
 * 换成一个新对象，键就不相等，下一次 {@code renderWidget} 自动重新折行。
 * 所以不必等下一次 {@code init()}，也不用碰 {@code reason} 这种 final 字段。
 *
 * <h3>只在认得出的句子上下手</h3>
 * 别的断线原因（服务器关闭、连接超时、被管理员踢）一律不碰。
 * 每次绘制前都过一遍，因为界面重建会换一个新的 widget 出来。
 */
@Mod.EventBusSubscriber(modid = VerityPlus.MODID, value = Dist.CLIENT)
public final class DisconnectScreenTranslator {

    private DisconnectScreenTranslator() {}

    /** 英文前缀，后面接玩家名。 */
    private static final String FAREWELL = "Farewell, ";

    /** 中文前缀；已经是它说明这条翻过了，不再处理。 */
    private static final String FAREWELL_ZH = "永别了，";

    private static boolean warned;

    /**
     * 每次绘制前扫一遍这个界面的控件，把正文换掉。
     * <p>放在渲染前而不是 {@code Init.Post}：{@code DisconnectedScreen} 每次重建
     * 都会造一个新的 {@code MultiLineTextWidget}，只有渲染前这一下能保证
     * 当前这批控件也被覆盖到。
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof DisconnectedScreen screen)) return;
        try {
            translate(screen);
        } catch (RuntimeException e) {
            if (warned) return;
            warned = true;
            Log.warn("翻译断线界面文字失败: " + e);
        }
    }

    /** 把界面上所有「Farewell, xxx」正文换成中文。 */
    private static void translate(DisconnectedScreen screen) {
        for (AbstractWidget widget : widgets(screen)) {
            if (!(widget instanceof MultiLineTextWidget)) continue;
            Component message = widget.getMessage();
            if (message == null) continue;
            String text = message.getString();
            if (!text.startsWith(FAREWELL)) continue;
            String player = text.substring(FAREWELL.length()).strip();
            if (player.isEmpty()) continue;
            // 只换文字，样式靠 setStyle 原样带过去
            widget.setMessage(Component.literal(FAREWELL_ZH + player).setStyle(message.getStyle()));
        }
    }

    /** 界面里现有的全部控件；{@code children()} 是公开方法，取不到就当空。 */
    private static Iterable<AbstractWidget> widgets(DisconnectedScreen screen) {
        var out = new java.util.ArrayList<AbstractWidget>();
        for (var listener : screen.children()) {
            if (listener instanceof AbstractWidget widget) out.add(widget);
        }
        return out;
    }
}
