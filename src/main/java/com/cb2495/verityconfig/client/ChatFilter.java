package com.cb2495.verityconfig.client;

import com.cb2495.verityconfig.VerityPlus;
import com.cb2495.verityconfig.config.ModConfig;
import com.cb2495.verityconfig.config.VerityConfigManager;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 聊天栏的统一入口：拦截 Verity 发来的消息，命中词条就换成中文。
 *
 * <h3>为什么在这里做，而不是去改 {@code Component}</h3>
 * 以前是把 {@code Component.getString()} 全局换掉，等于给整个游戏装一层补丁。
 * 那条路依赖 {@code getUnformattedText} 这个方法名，而它在正式环境里叫 {@code m_1543_}，
 * 初始化时就抛 {@code NoSuchMethodException}，整条翻译静默失效——聊天栏一直是英文。
 * <p>现在只在 {@link ClientChatReceivedEvent} 里处理这一条消息：
 * 取成纯文本 → 查词表 → 命中就整体换成一个新 {@link Component}。
 * 不认识的句子一律不碰，所以别的模组、玩家发言、AI 回答都保持原样。
 *
 * <h3>优先级</h3>
 * {@link EventPriority#HIGHEST}：{@link ErrorChatHandler} 要在 NORMAL 里读
 * {@code ERROR: {json}} 那段原始 JSON。那个 JSON 在 {@code ERROR: } 之后，
 * 这里只把前面的固定前缀换成中文，两者互不影响。
 */
@Mod.EventBusSubscriber(modid = VerityPlus.MODID, value = Dist.CLIENT)
public class ChatFilter {

    /** DeepSeek 官方计价文档，「详见此处」点击后打开。 */
    private static final String PRICING_DOC_URL = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/";

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) return;

        String text = message.getString();

        // Need help setting up this mod? Watch this tutorial. → 替换为自定义提示
        // 比较都发生在翻译之前，所以按英文原句判断
        if (containsAll(text, "Need help setting up this mod?", "Watch this tutorial.")) {
            event.setCanceled(true);
            sendCustomHelpMessage();
            return;
        }

        // 首次生成时 Verity 会紧跟自定义提示发一条可点击的 "Setup Tutorial"（链接），
        // 第一句已经被上面拦下了，剩下这条链接没有意义，一并去掉。
        // 只在译文还没生效时才匹配：词表里「设置教程」也是用来替换这条链接的。
        if (!ChatTranslator.isEnabled() && text.contains("Setup Tutorial")) {
            event.setCanceled(true);
            return;
        }

        // AI 出错时那条 "AI Setup issue? Tutorial: <链接>" 直接隐藏：
        // 报错本身已经由 ErrorChatHandler 处理，这条链接只会刷屏
        if (ChatTranslator.shouldHide(text)) {
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

    private static boolean containsAll(String text, String... keywords) {
        for (String k : keywords) {
            if (!text.contains(k)) return false;
        }
        return true;
    }

    private static void sendCustomHelpMessage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Component msg = Component.literal("[VerityPlus] ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("如果你在游玩过程中遇到任何问题，随时输入 ").withStyle(ChatFormatting.WHITE))
                .append(ChatLinks.suggestCmd("/vc qanda", ChatLinks.CLICK_TO_FILL))
                .append(Component.literal(" 获取帮助，如果没有在列表里找到你的问题，也可以在 QQ 交流群中提问。").withStyle(ChatFormatting.WHITE));

        mc.player.displayClientMessage(msg, false);

        // 灰色温馨提示
        Component tip = Component.literal("温馨提示：该模组发送的消息中所有青色(淡蓝色?)字体都是可以点击并自动填入的指令，点击后可按回车直接执行")
                .withStyle(ChatFormatting.GRAY);
        mc.player.displayClientMessage(tip, false);

        // 峰谷提示的首次说明：只在这里说一次，之后不再重复
        sendPricingHintIntro();
    }

    /**
     * 首次下发「可关闭峰谷提示」的说明。
     * <p>峰谷提示本身每次进入存档都会发，但「怎么关」只在首次启动时说明一次；
     * 说明过就写进配置，之后不再出现。开关已关闭或当前不是 DeepSeek 时不说明，
     * 这两种情况下关掉说明会让玩家以后重新打开时再也看不到。
     * <p>不在这里发峰谷提示的正文：正文需要当天节假日数据，
     * 同步取回会卡住启动流程，交给 {@link DeepSeekHintHandler} 在进入存档后异步发出。
     */
    private static void sendPricingHintIntro() {
        if (!ModConfig.isShowDeepSeekHint()) return;
        if (!ModConfig.isPricingHintIntroduced()) return;
        if (!"DeepSeek".equals(VerityConfigManager.getCurrentProvider())) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(
                Component.literal("[提示]").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("DeepSeek 采用峰谷计价方式，计价规则").withStyle(ChatFormatting.WHITE))
                        .append(ChatLinks.openUrl("[详见此处]", PRICING_DOC_URL, "点击打开 DeepSeek 官方计价文档"))
                        .append(Component.literal("。若不想看到此类提示，可输入 ").withStyle(ChatFormatting.WHITE))
                        .append(ChatLinks.suggestCmd("/vc dshint off", ChatLinks.CLICK_TO_FILL))
                        .append(Component.literal(" 关闭。").withStyle(ChatFormatting.WHITE)),
                false);

        ModConfig.setPricingHintIntroduced(true);
    }
}
