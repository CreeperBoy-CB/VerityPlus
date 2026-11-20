package com.cb2495.verityplus.client;

import com.cb2495.verityplus.VerityPlus;
import com.cb2495.verityplus.util.Log;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 本模组的按键绑定。
 * <p>原本这里是「打开 Verity 配置」——反斜杠键弹出本模组的 AI 配置界面。
 * 那个界面已经移除（功能与汉化后的 Verity 自己的 YACL 配置界面完全重合），
 * 所以现在这个按键改成<b>直接把玩家送到 Verity 的原生配置界面</b>，行为与
 * 「§Mods → Verity → Config」一致。
 * <p>之所以还留一个按键：那两步点起来确实麻烦，而按键本身不消耗任何界面代码。
 * <p>打开入口同样走 {@link VerityConfigScreenHook} 挂上的中文界面工厂，
 * 因此按 \ 看到的仍是中文版，而不是英文原版。
 */
@Mod.EventBusSubscriber(modid = VerityPlus.MODID, value = Dist.CLIENT)
public class KeyBindings {

    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "打开 Verity 配置",                 // 显示名称
            InputConstants.Type.KEYSYM,         // 按键类型，不能是鼠标
            GLFW.GLFW_KEY_BACKSLASH,            // 默认反斜杠键
            "Verity AI 配置工具"                // 类别
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG);
        // 确认注册；走 debug 级别，正常游戏不用看到
        Log.debug("按键绑定已注册: {}", OPEN_CONFIG.getName());
    }

    /**
     * 按一下把玩家送到 Verity 的配置界面。
     * <p>界面构造放在 {@link VerityConfigScreenFactory#create} 里：
     * 它读 Verity 的 {@code ForgeConfigSpec} 现场生成中文界面，
     * 不依赖本模组任何一个旧界面类。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        while (OPEN_CONFIG.consumeClick()) {
            // 已经有界面开着时不叠加：配置界面是暂停界面，按 \ 反复开只会套娃
            if (mc.screen != null) continue;
            mc.setScreen(ClientCommands.openVerityConfig());
        }
    }
}
