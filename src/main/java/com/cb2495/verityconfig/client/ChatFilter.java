package com.cb2495.verityconfig.client;

import com.cb2495.verityconfig.VerityPlus;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 聊天栏的统一入口：把 Verity 发来的消息换成中文。
 *
 * <h3>为什么在这里做，而不是去改 {@code Component}</h3>
 * 以前是把 {@code Component.getString()} 全局换掉，等于给整个游戏装一层补丁。
 * 那条路依赖 {@code getUnformattedText} 这个方法名，而它在正式环境里叫 {@code m_1543_}，
 * 初始化时就抛 {@code NoSuchMethodException}，整条翻译静默失效——聊天栏一直是英文。
 * <p>现在只在 {@link ClientChatReceivedEvent} 里处理这一条消息：
 * 取成纯文本 → 查词表 → 命中就整体换成一个新 {@link Component}。
 * 不认识的句子一律不碰，所以别的模组、玩家发言、AI 回答都保持原样。
 *
 * <h3>引导类消息不在这里处理</h3>
 * 「Need help setting up this mod?」「AI Setup issue? Tutorial:」这些与翻译无关，
 * 属于帮助/引导功能，由 Modpack Helper 的 {@code ChatGuideFilter} 处理。
 * 本类只保留一条<b>必须知道翻译状态</b>的判断：译文生效时，
 * 「Setup Tutorial」这条链接交给词表翻译，不该在这里直接丢掉。
 *
 * <h3>优先级</h3>
 * {@link EventPriority#HIGHEST}：{@link ErrorChatHandler} 要在 NORMAL 里读
 * {@code ERROR: {json}} 那段原始 JSON。那个 JSON 在 {@code ERROR: } 之后，
 * 这里只把前面的固定前缀换成中文，两者互不影响。
 */
@Mod.EventBusSubscriber(modid = VerityPlus.MODID, value = Dist.CLIENT)
public class ChatFilter {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) return;

        String text = message.getString();

        // 首次生成时 Verity 会紧跟教程推销发一条可点击的 "Setup Tutorial"（链接）。
        // 推销那句与 AI 报错链接由 Modpack Helper 拦掉；这里只剩翻译链路：
        // 只有译文<b>没</b>生效时才需要把这条链接也丢掉，否则词表里
        // 「设置教程」本来就是用来替换它的。
        if (!ChatTranslator.isEnabled() && text.contains("Setup Tutorial")) {
            event.setCanceled(true);
            return;
        }

        // 只替换 Verity 的消息；其它来源原样放过
        if (!ChatTranslator.isVerity(text)) return;

        String chinese = ChatTranslator.translate(text);
        if (!chinese.equals(text)) {
            event.setMessage(styled(chinese, message));
            return;
        }

        // 整句没命中：消息可能是「样式片段 + 正文」拼起来的（<Verity> 名字带颜色），
        // 这时只换最后一段正文，前面的名字与颜色原样保留
        Component body = replaceBody(message);
        if (body != message) event.setMessage(body);
    }

    /**
     * 把译文包成组件，并沿用原消息的样式。
     * <p>{@code Local AI: ...} 那条是 {@code ChatFormatting.GRAY} 发的，换字时如果丢掉样式
     * 就会变成默认白色，看起来和别的消息不一样。
     * <p>颜色有三个来源，按可靠程度依次尝试：
     * <ol>
     *   <li>消息自己的样式（{@code withStyle(GRAY)} 那种）；</li>
     *   <li>文本开头的颜色码（Verity 有几句把 {@code §4} 直接写在字符串里）；</li>
     *   <li>第一个子组件带颜色的。</li>
     * </ol>
     */
    private static Component styled(String chinese, Component original) {
        var style = original.getStyle();

        if (style.getColor() == null) {
            String raw = original.getString();
            // 只认紧跟开头的那一个颜色码；别处出现的不算，免得把正文里的颜色当整条的颜色
            if (raw.length() >= 2 && raw.charAt(0) == '\u00a7') {
                ChatFormatting format = ChatFormatting.getByCode(raw.charAt(1));
                if (format != null) style = style.withColor(format);
            }
        }

        if (style.getColor() == null) {
            for (Component child : original.getSiblings()) {
                var childColor = child.getStyle().getColor();
                if (childColor != null) {
                    style = style.withColor(childColor);
                    break;
                }
            }
        }

        return Component.literal(chinese).setStyle(style);
    }

    /**
     * 只替换最后一段正文。
     * <p>例：{@code §6<Verity> §fWhere were you...?} 前半段是带颜色的名字，
     * 后半段才是句子；句子本身在词表里。
     */
    private static Component replaceBody(Component message) {
        var children = message.getSiblings();
        if (children.isEmpty()) return message;

        Component last = children.get(children.size() - 1);
        String body = last.getString();
        String chinese = ChatTranslator.translate(body);
        if (chinese.equals(body)) return message;

        MutableComponent head = Component.empty().setStyle(message.getStyle());
        for (int i = 0; i < children.size() - 1; i++) head.append(children.get(i));
        head.append(Component.literal(chinese).setStyle(last.getStyle()));
        return head;
    }
}
