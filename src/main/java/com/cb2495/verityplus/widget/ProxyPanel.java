package com.cb2495.verityplus.widget;

import com.cb2495.verityplus.config.ProxyConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 第 2 步的代理配置面板：一行可展开的标题 + 展开后的输入区。
 *
 * <h3>结构</h3>
 * <pre>
 *   ▶ 代理设置（未启用）              ← 收起时只占这一行，点它展开
 *   ┌──────────────────────────────────────────────┐
 *   │ 协议 [HTTP/HTTPS] 地址 [127.0.0.1  ] 端口 [7890] │
 *   │ [启用] [应用]                                  │
 *   │ 提示：只影响本模组的检测请求，不影响 Verity 本体   │
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>「应用」与「启用」为什么分开</h3>
 * 「启用」只是一张单子上的勾，「应用」才是真的把它交给网络层。
 * 合在一起的话，玩家每敲一个字符都会改全局状态，代理填一半就被拿去用了。
 * 点「应用」后立即用这份设置重跑一次 Key 校验，玩家马上能看到通不通。
 *
 * <h3>面板高度是动态的</h3>
 * 收起时占 {@link #COLLAPSED_HEIGHT}，展开后占 {@link #expandedHeight()}。
 * 屏幕那边每帧读 {@link #height()} 来决定后续元素（结论行、注册链接）该画在哪。
 */
public final class ProxyPanel {

    // ---------- 尺寸 ----------
    /** 收起时的高度：一行标题。 */
    public static final int COLLAPSED_HEIGHT = 14;
    /** 展开区内部的行高。 */
    private static final int ROW_HEIGHT = 18;
    /** 展开区上下留白。 */
    private static final int PADDING = 4;
    /** 标题行里箭头占的宽度。 */
    private static final int ARROW_WIDTH = 10;
    /** 输入框之间的间隙。 */
    private static final int GAP = 4;

    // ---------- 颜色 ----------
    private static final int COLOR_TITLE = 0xFFAAAAAA;
    private static final int COLOR_TITLE_ACTIVE = 0xFF55FF55;
    private static final int COLOR_TITLE_INVALID = 0xFFFF5555;
    private static final int COLOR_BG = 0xC0000000;
    private static final int COLOR_BORDER = 0xFF555555;
    private static final int COLOR_HINT = 0xFF888888;
    private static final int COLOR_VALUE = 0xFFDDDDDD;
    private static final int COLOR_ARROW = 0xFFCCCCCC;

    /** 底部那行提醒。 */
    private static final String SCOPE_HINT = "只影响本模组的检测请求，不影响 Verity 本体";

    private final Minecraft minecraft;
    private final Font font;

    /** 展开/收起状态。 */
    private boolean expanded = false;

    /** 面板左边缘与宽度，由屏幕每帧 {@link #layout} 设定。 */
    private int left;
    private int width;
    private int top;

    /** 界面上正在编辑的草稿；点「应用」才写进 {@link ProxyConfig}。 */
    private ProxyConfig.Type draftType = ProxyConfig.Type.HTTP;
    private String draftHost = "127.0.0.1";
    private String draftPort = "7890";

    /** 「启用」复选框的草稿值。 */
    private boolean draftEnabled = false;

    // 控件
    private EditBox hostBox;
    private EditBox portBox;
    private Button typeButton;
    private Button enabledButton;
    private Button applyButton;

    /** 点「应用」后的回调，参数是刚生效的设置；屏幕据此立刻重跑 Key 校验。 */
    private final Consumer<ProxyConfig.Settings> onApplied;

    /** 「应用」之后的一条反馈，显示在面板底部；{@code null} 表示没有。 */
    private String feedback = null;
    private int feedbackColor = COLOR_HINT;

    public ProxyPanel(Minecraft minecraft, Consumer<ProxyConfig.Settings> onApplied) {
        this.minecraft = minecraft;
        this.font = minecraft.font;
        this.onApplied = onApplied;
        // 用磁盘上已有的设置初始化草稿
        ProxyConfig.Settings saved = ProxyConfig.get();
        this.draftEnabled = saved.enabled();
        this.draftType = saved.type();
        this.draftHost = saved.host();
        this.draftPort = saved.port() > 0 ? String.valueOf(saved.port()) : "";
    }

    // ---------- 布局 ----------

    /** 告诉面板它该摆在哪里、多宽。 */
    public void layout(int left, int top, int width) {
        this.left = left;
        this.top = top;
        this.width = width;
        relocateWidgets();
    }

    /**
     * 按当前的 left/top/width 重新摆放控件。
     * <p>展开/收起会改变面板高度，滚动或窗口缩放时位置也会变；
     * 不重排的话输入框会留在上一次的位置上，看着像「飘在外面」。
     */
    private void relocateWidgets() {
        if (hostBox == null) return;
        int row1 = top + COLLAPSED_HEIGHT + PADDING;
        int row2 = row1 + ROW_HEIGHT + GAP;

        int typeW = 62;
        int portW = 46;
        int hostW = Math.max(60, width - typeW - portW - GAP * 2 - PADDING * 2);

        int x = left + PADDING;
        typeButton.setX(x);
        typeButton.setY(row1);
        typeButton.setWidth(typeW);

        x += typeW + GAP;
        hostBox.setX(x);
        hostBox.setY(row1);
        hostBox.setWidth(hostW);

        x += hostW + GAP;
        portBox.setX(x);
        portBox.setY(row1);
        portBox.setWidth(portW);

        x = left + PADDING;
        int half = Math.max(50, (width - PADDING * 2 - GAP) / 2);
        enabledButton.setX(x);
        enabledButton.setY(row2);
        enabledButton.setWidth(half);

        x += half + GAP;
        applyButton.setX(x);
        applyButton.setY(row2);
        applyButton.setWidth(Math.max(50, width - PADDING * 2 - GAP - half));
    }

    /** 面板当前占的高度：收起时一行，展开时包含输入区。 */
    public int height() {
        return expanded ? expandedHeight() : COLLAPSED_HEIGHT;
    }

    /** 展开后的高度：两行控件 + 一行反馈。 */
    private int expandedHeight() {
        int inner = PADDING + ROW_HEIGHT + GAP + ROW_HEIGHT + PADDING + this.font.lineHeight + 2;
        return COLLAPSED_HEIGHT + inner;
    }

    /** 面板底边。 */
    public int bottom() {
        return top + height();
    }

    // ---------- 控件 ----------

    /**
     * 建好展开区里的控件并挂到屏幕上。
     * <p>收起时它们被 {@code setVisible(false)} 藏起来，不占空间也不吃点击。
     */
    public void initWidgets(java.util.function.Function<net.minecraft.client.gui.components.AbstractWidget,
            net.minecraft.client.gui.components.AbstractWidget> adder) {
        int row1 = top + COLLAPSED_HEIGHT + PADDING;
        int row2 = row1 + ROW_HEIGHT + GAP;

        // 第一行：协议 / 地址 / 端口
        // 宽度按比例分：协议 62px、端口 46px，剩下全给地址
        int typeW = 62;
        int portW = 46;
        int hostW = Math.max(60, width - typeW - portW - GAP * 2 - PADDING * 2);

        int x = left + PADDING;
        Button type = Button.builder(Component.literal(draftType.label()), b -> onTypePressed())
                .pos(x, row1).size(typeW, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("点击切换 HTTP/HTTPS 与 SOCKS5")))
                .build();
        this.typeButton = type;
        adder.apply(type);

        x += typeW + GAP;
        EditBox host = new EditBox(this.font, x, row1, hostW, ROW_HEIGHT, Component.literal("代理地址"));
        host.setMaxLength(255);
        host.setValue(draftHost);
        host.setHint(Component.literal("例如 127.0.0.1")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        host.setResponder(text -> draftHost = text);
        this.hostBox = host;
        adder.apply(host);

        x += hostW + GAP;
        EditBox port = new EditBox(this.font, x, row1, portW, ROW_HEIGHT, Component.literal("端口"));
        // 只接受数字：在这里就把非法字符挡掉，省得点「应用」才报错
        port.setMaxLength(5);
        port.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        port.setValue(draftPort);
        port.setHint(Component.literal("端口")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        port.setResponder(text -> draftPort = text);
        this.portBox = port;
        adder.apply(port);

        // 第二行：启用 / 应用
        x = left + PADDING;
        int half = Math.max(50, (width - PADDING * 2 - GAP) / 2);
        Button toggle = Button.builder(enabledLabel(), b -> onEnabledPressed())
                .pos(x, row2).size(half, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("勾选后点「应用」才会真正启用")))
                .build();
        this.enabledButton = toggle;
        adder.apply(toggle);

        x += half + GAP;
        Button apply = Button.builder(Component.literal("应用"), b -> onApplyPressed())
                .pos(x, row2).size(Math.max(50, width - PADDING * 2 - GAP - half), ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("应用这份设置，并立刻重新检查 API Key")))
                .build();
        this.applyButton = apply;
        adder.apply(apply);

        syncVisibility();
    }

    /** 展开态决定控件可不可见、能不能点。 */
    private void syncVisibility() {
        for (var widget : new net.minecraft.client.gui.components.AbstractWidget[]{
                hostBox, portBox, typeButton, enabledButton, applyButton}) {
            if (widget == null) continue;
            widget.visible = expanded;
            widget.active = expanded;
        }
        if (typeButton != null) typeButton.setMessage(Component.literal(draftType.label()));
        if (enabledButton != null) enabledButton.setMessage(enabledLabel());
    }

    private Component enabledLabel() {
        return Component.literal(draftEnabled ? "启用：是" : "启用：否");
    }

    /** 每帧推进输入框光标闪烁。 */
    public void tick() {
        if (!expanded) return;
        if (hostBox != null) hostBox.tick();
        if (portBox != null) portBox.tick();
    }

    // ---------- 交互 ----------

    /** 点标题行：展开 / 收起。 */
    private void toggleExpanded() {
        expanded = !expanded;
        if (!expanded) {
            // 收起时把焦点从输入框上摘掉，否则光标会在看不见的地方继续闪
            if (hostBox != null && hostBox.isFocused()) hostBox.setFocused(false);
            if (portBox != null && portBox.isFocused()) portBox.setFocused(false);
        }
        syncVisibility();
    }

    private void onTypePressed() {
        draftType = draftType.next();
        if (typeButton != null) typeButton.setMessage(Component.literal(draftType.label()));
    }

    private void onEnabledPressed() {
        draftEnabled = !draftEnabled;
        if (enabledButton != null) enabledButton.setMessage(enabledLabel());
    }

    /**
     * 点「应用」：把草稿变成当前设置，并回调让屏幕立刻重跑 Key 校验。
     * <p>先做参数检查；不合格就<b>不动</b>全局设置，只在面板底部报原因。
     */
    private void onApplyPressed() {
        int port = parsePort();
        ProxyConfig.Settings candidate = new ProxyConfig.Settings(
                draftEnabled, draftType, draftHost == null ? "" : draftHost.trim(), port);

        String problem = candidate.problem();
        if (problem != null) {
            setFeedback(problem, COLOR_TITLE_INVALID);
            return;
        }
        // 未启用时不校验地址端口，直接用「关闭」
        if (!candidate.enabled()) {
            ProxyConfig.set(ProxyConfig.Settings.disabled());
            ProxyConfig.save();
            setFeedback("已关闭代理", COLOR_TITLE_ACTIVE);
            onApplied.accept(ProxyConfig.Settings.disabled());
            return;
        }

        // 先探一次代理端口：端口没开与目标站不通，玩家该做的事完全不同
        String portProblem = ProxyConfig.probePort(candidate);
        if (portProblem != null) {
            setFeedback(portProblem, COLOR_TITLE_INVALID);
            return;
        }

        ProxyConfig.set(candidate);
        ProxyConfig.save();
        setFeedback("已应用：" + candidate.describe(), COLOR_TITLE_ACTIVE);
        onApplied.accept(candidate);
    }

    private int parsePort() {
        String text = draftPort == null ? "" : draftPort.trim();
        if (text.isEmpty()) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setFeedback(String text, int color) {
        this.feedback = text;
        this.feedbackColor = color;
    }

    /**
     * 处理一次点击。
     *
     * @return {@code true} 表示这一下被面板吃掉了
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        // 标题行整行可点
        if (isTitleHit(mouseX, mouseY)) {
            toggleExpanded();
            return true;
        }
        // 展开区：只吃空白处的点击，控件自己会先拿到
        return expanded && mouseX >= left && mouseX < left + width
                && mouseY >= top && mouseY < bottom();
    }

    /** 鼠标是否落在标题行上。 */
    public boolean isTitleHit(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < left + width
                && mouseY >= top && mouseY < top + COLLAPSED_HEIGHT;
    }

    // ---------- 绘制 ----------

    /** 画面板。收起时只有一行标题。 */
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int h = height();
        // 底板与边框
        graphics.fill(left, top, left + width, top + h, COLOR_BG);
        graphics.renderOutline(left, top, width, h, COLOR_BORDER);

        // 标题行：箭头 + 文案
        String arrow = expanded ? "▼" : "▶";
        int textY = top + (COLLAPSED_HEIGHT - this.font.lineHeight) / 2;
        graphics.drawString(this.font, arrow, left + 3, textY, COLOR_ARROW, false);
        graphics.drawString(this.font, titleText(), left + 3 + ARROW_WIDTH, textY,
                titleColor(), false);

        if (!expanded) return;

        // 展开区：第一行控件由 Minecraft 画（它们是真控件），这里只补标签与提示
        int hintY = top + COLLAPSED_HEIGHT + PADDING + ROW_HEIGHT + GAP + ROW_HEIGHT + PADDING;
        if (feedback != null) {
            graphics.drawString(this.font, trimToWidth(feedback, width - PADDING * 2),
                    left + PADDING, hintY, feedbackColor, false);
        } else {
            graphics.drawString(this.font, trimToWidth(SCOPE_HINT, width - PADDING * 2),
                    left + PADDING, hintY, COLOR_HINT, false);
        }
    }

    /** 标题文案：带上当前生效的设置，收起时也能一眼看到状态。 */
    private String titleText() {
        ProxyConfig.Settings active = ProxyConfig.get();
        if (active.usable()) {
            return "代理设置（已启用：" + active.describe() + "）";
        }
        if (active.enabled()) {
            return "代理设置（设置不完整）";
        }
        return "代理设置（未启用）";
    }

    private int titleColor() {
        ProxyConfig.Settings active = ProxyConfig.get();
        if (active.usable()) return COLOR_TITLE_ACTIVE;
        if (active.enabled()) return COLOR_TITLE_INVALID;
        return COLOR_TITLE;
    }

    /** 截断到指定宽度，超出加省略号。 */
    private String trimToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - this.font.width(ellipsis);
        if (budget <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int w = this.font.width(String.valueOf(c));
            if (used + w > budget) break;
            sb.append(c);
            used += w;
        }
        return sb + ellipsis;
    }

    /** 展开后用来画值的颜色（供将来扩展）。 */
    static int valueColor() {
        return COLOR_VALUE;
    }
}
