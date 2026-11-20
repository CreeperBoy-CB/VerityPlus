package com.cb2495.verityplus;

import com.cb2495.verityplus.config.ModConfig;
import com.cb2495.verityplus.config.VerityConfigManager;
import com.cb2495.verityplus.screen.ModsListScreen;
import com.cb2495.verityplus.screen.RestartPrompt;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("removal")
@Mod(VerityPlus.MODID)
public class VerityPlus {
    public static final String MODID = "verityplus";
    public static final String MODPACK_VERSION = "6.99"; // 整合包版本，可修改

    public VerityPlus() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        // 首次启动时生成默认配置，让玩家能在 config 目录里直接看到可改的选项。
        // 用 enqueueWork 切回主线程：FMLClientSetupEvent 在并行线程上触发，
        // 此时直接取 Minecraft 实例可能拿到未初始化完成的 gameDirectory。
        event.enqueueWork(() -> {
            // modid 从 verityconfig 改成 verityplus 时配置文件也跟着换名，
            // 先把旧文件接过来，否则老玩家的开关会被重置成默认值。
            // 必须排在 createDefaultIfMissing 之前，否则新文件先被创建，迁移就放弃了。
            ModConfig.migrateLegacyConfig();
            ModConfig.createDefaultIfMissing();
            // 两份联动配置（verity-common.toml + simple_translate）缺任一份时，
            // 用内置模板补齐；已存在的文件不会被覆盖。
            // 必须排在下面的预读之前：预读会立刻读盘，晚了就读到空配置
            VerityConfigManager.copyTemplatesIfMissing();
            // 后台预读两份配置，后续读写都走内存缓存，渲染线程不再碰磁盘。
            VerityConfigManager.preload();
        });
    }

    /** 配置自检是否已得到结论：主界面会被反复打开（取消重启、从存档退出等）。 */
    private static final AtomicBoolean TRANSLATE_RESYNC_SETTLED = new AtomicBoolean(false);

    /**
     * 主界面打开时的配置自检。
     * <p>这里曾经还负责「没有 API Key 就弹欢迎页」的首次启动流程，
     * 那套界面已经整块去掉（要重写）。现在入口只剩这一项自检。
     */
    @SubscribeEvent
    public void onTitleScreenOpen(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen)) return;

        // 简单翻译的 Key 为空、而 Verity 的有：按 Verity 的配置补写，
        // 补过就要重启才生效。
        // 只有补写过才置位：置位后不再重复处理，未补写则下次进来还能再查
        if (!TRANSLATE_RESYNC_SETTLED.get()
                && VerityConfigManager.resyncTranslateIfKeyMissing()) {
            TRANSLATE_RESYNC_SETTLED.set(true);
            Minecraft.getInstance().tell(() -> RestartPrompt.show(
                    "重启游戏以应用配置",
                    "翻译模组配置出现错误，现已修复，需要重启以应用修复，是否重启？"));
        }
    }

    /**
     * 扫描 mods 文件夹，提取版本信息并写入 {@link ModsListScreen} 的静态字段。
     * <p>目前没有任何界面在显示这些值——旧的欢迎页已移除。
     * 保留它是为了重写首次启动配置流程时能直接取用（版本号、整合包版本）。
     */
    public static void loadVersions() {
        // modpackVersion 的默认值由 ModsListScreen 的字段初始化给出
        // （取自 MODPACK_VERSION），这里不再重复赋值。
        // 下面两个必须扫目录才知道，先退回「未知」
        ModsListScreen.verityVersion = "未知";
        ModsListScreen.configModVersion = "未知";

        Path modsDir = FMLPaths.MODSDIR.get();
        File dir = modsDir.toFile();
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            // 现在发布的文件名是 [VerityPlus]…；旧的 [Verity拓展增强]… 也一并认，
            // 否则用旧版文件名升级上来的玩家会看到「配置工具版本: 未知」
            if (name.startsWith("[VerityPlus]") || name.startsWith("[Verity拓展增强]")) {
                ModsListScreen.configModVersion = extractVersion(name);
            } else if (name.startsWith("[Verity]")) {
                ModsListScreen.verityVersion = extractVersion(name);
            }
        }
    }

    private static String extractVersion(String filename) {
        Pattern quotePattern = Pattern.compile("['\"]([^'\"]*)['\"]");
        Matcher matcher = quotePattern.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Pattern versionPattern = Pattern.compile("(\\d+(?:\\.\\d+)+)");
        Matcher versionMatcher = versionPattern.matcher(filename);
        if (versionMatcher.find()) {
            return versionMatcher.group(1);
        }
        return "未知";
    }
}
