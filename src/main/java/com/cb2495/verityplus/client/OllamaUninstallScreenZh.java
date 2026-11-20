package com.cb2495.verityplus.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 中文版的 Ollama 卸载确认界面。
 * <p><b>为什么要自己画：</b>Verity 的 {@code OllamaUninstallScreen} 标题、正文、三个按钮
 * 都是 {@code init()} 里现场拼出来的 {@code Component.literal(...)}，没有任何字段留着原文，
 * 也没有走 {@code Component#getString()} 的地方可包，事后改不了。
 * <p><b>布局严格照搬原版</b>：
 * <ul>
 *   <li>标题居中于 {@code (width/2, 40)}</li>
 *   <li>正文 {@code MultiLineLabel} 居中于 {@code (width/2, 60)}，最大宽度 {@code width-50}</li>
 *   <li>三个按钮宽 250，x 为 {@code width/2-125}，
 *       y 依次为 {@code min(height-76, 110)}、{@code +24}、{@code +48}</li>
 * </ul>
 * <p>点按钮的行为与原版完全一致：把选择交给回调，然后回到上一级界面。
 * 真正的卸载逻辑仍在 Verity 的 {@code OllamaSetupManager} 里，本类不碰。
 */
public final class OllamaUninstallScreenZh extends Screen {

    /** 正文里要填的目录，取自 Verity 自己的 {@code OllamaRuntime.managedDir()}。 */
    private static final String RUNTIME = "varmite.verity.ollama.OllamaRuntime";

    private final Screen parent;

    private final Consumer<Boolean> onConfirm;

    private MultiLineLabel message = MultiLineLabel.EMPTY;

    private OllamaUninstallScreenZh(Screen parent, Consumer<Boolean> onConfirm) {
        super(Component.literal("卸载 Ollama？"));
        this.parent = parent;
        this.onConfirm = onConfirm;
    }

    /**
     * 打开中文版卸载确认界面。
     *
     * @param parent    取消时回到的界面
     * @param onConfirm 收到 {@code true} 表示连模型一起删，{@code false} 表示保留模型
     * @return 新界面；Verity 没装时返回 null
     */
    public static Screen create(Screen parent, Consumer<Boolean> onConfirm) {
        try {
            Class.forName(RUNTIME);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        return new OllamaUninstallScreenZh(parent, onConfirm);
    }

    /**
     * 正文。译文取自 {@code hardcode_ts.txt} 的 {@code OllamaUninstallScreen.java} 一节，
     * {@code {dir}} 换成 Verity 实际使用的目录。
     */
    private static Component body() {
        String dir = managedDir();
        return Component.literal("这将删除 Verity 安装在此电脑 " + dir
                + " 中的 Ollama 副本。您的模型单独存放，并与其他 Ollama 共用。"
                + "之后只要选择本地模型，Verity 就可以重新下载 Ollama。");
    }

    /** Verity 管理的 Ollama 目录；取不到时留空，句子照样通顺。 */
    private static String managedDir() {
        try {
            Class<?> runtime = Class.forName(RUNTIME);
            Object dir = runtime.getMethod("managedDir").invoke(null);
            String text = dir == null ? "" : String.valueOf(dir);
            return "null".equals(text) ? "" : text;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    @Override
    protected void init() {
        message = MultiLineLabel.create(font, body(), width - 50);

        int x = width / 2 - 125;
        int y = Math.min(height - 76, 110);
        addRenderableWidget(Button.builder(Component.literal("卸载 Ollama，保留模型"),
                        b -> choose(false))
                .bounds(x, y, 250, 20).build());
        addRenderableWidget(Button.builder(
                        Component.literal("卸载 Ollama 并删除所有模型").withStyle(ChatFormatting.RED),
                        b -> choose(true))
                .bounds(x, y + 24, 250, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y + 48, 250, 20).build());
    }

    /** 与原版一致：先把选择交出去，再关界面。 */
    private void choose(boolean deleteModels) {
        onConfirm.accept(deleteModels);
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 40, 0xFFFFFF);
        // 原版用居中版：MultiLineLabel 自己按行的实际宽度居中，长句换行后仍然对称
        message.renderCentered(graphics, width / 2, 60);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // 原版回到构造时记下的 parent，这里照做
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
