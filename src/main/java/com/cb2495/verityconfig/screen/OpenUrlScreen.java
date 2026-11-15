package com.cb2495.verityconfig.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 没有安装 Modpack Helper 时的降级提示页。
 * <p>帮助文档、常见问题、旧存档导入、模组管理说明都已搬到 Modpack Helper，
 * VerityPlus 只在必要时用反射去开它们。装了什么都好办；没装的时候，
 * 相关按钮不能变成「点了没反应」——本页负责给出文字说明和一句「该装哪个」。
 */
public class OpenUrlScreen extends Screen {

    /** 正文与按钮的纵向布局：正文居中，按钮在下方。 */
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;

    private final String notice;

    private OpenUrlScreen(Component title, String notice) {
        super(title);
        this.notice = notice;
    }

    /** 打开一个只有标题 + 一句说明 + 「确定」的小提示页。 */
    public static void showNotice(String title, String notice) {
        Minecraft.getInstance().setScreen(new OpenUrlScreen(Component.literal(title), notice));
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(Component.literal("确定"), btn -> this.onClose())
                .pos(this.width / 2 - BUTTON_WIDTH / 2, this.height / 2 + 30)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFD700);

        // 说明文字按窗口宽度折行，窄屏上不会顶出屏幕
        int y = this.height / 2 - 8;
        for (var line : this.font.split(Component.literal(this.notice), this.width - 40)) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFDDDDDD);
            y += this.font.lineHeight + 2;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
