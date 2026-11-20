package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.VerityPlus;
import com.cb2495.verityplus.util.Log;
import com.cb2495.verityplus.util.PlatformUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模组启停的平台策略。
 * <p>界面上已经不再提供增删模组的入口（那部分功能移除了，说明页在 Modpack Helper 里），
 * 但首次启动仍需要按平台决定哪些模组该开着：目前只剩「输入法冲突修复」——
 * 它只有 Windows 端才有意义，在手机上启用既无用处，也会让整合包作者难以判断问题来源。
 * <p>本类因此不画任何界面，只保留两步：
 * <ol>
 *   <li>{@link #firstTimeSetup} / {@link #pendingAutoEnable} 标记要做的事；</li>
 *   <li>{@link #applyFirstTimeSetup()} 在流程收尾时执行一次，并清掉标记。</li>
 * </ol>
 */
public final class ModsListScreen {

    private ModsListScreen() {}

    /** 整合包版本号；默认值直接取自 {@link VerityPlus#MODPACK_VERSION}，避免两处版本号不一致。 */
    public static String modpackVersion = VerityPlus.MODPACK_VERSION;
    /** Verity 本体的版本；由 {@link VerityPlus#loadVersions()} 扫描 mods 目录后填入。 */
    public static String verityVersion = "未知";
    /** 本模组的版本；同上。 */
    public static String configModVersion = "未知";

    /**
     * 下一次进入配置流程时是否算作「首次启动」。
     * <p>由欢迎页、旧存档导入页置位，收尾时由 {@link #applyFirstTimeSetup()} 清掉。
     * <p>与 {@link #pendingAutoEnable} 分工：本字段描述<b>流程</b>，
     * 后者描述<b>要做的事</b>。
     */
    public static boolean firstTimeSetup = false;

    /**
     * 是否需要执行一次「按平台自动启用模组」。
     * <p>两种情况要跑：首次启动流程，以及玩家从欢迎页直接进配置（老整合包升级上来的玩家
     * 走的就是这条路，之后不会再走首次启动流程）。
     * <p>只处理「按平台应当启用却还停用着」的模组，所以对已经配置好的玩家来说这一步什么都不做。
     */
    public static boolean pendingAutoEnable = false;

    /** 只有 Windows 端才有意义的模组。 */
    private static final String IME_FIX_BRACKET = "输入法冲突修复";

    /** 启用状态的后缀。 */
    private static final String JAR_SUFFIX = ".jar";
    /** 禁用后的后缀。 */
    private static final String DISABLED_SUFFIX = ".jar.disabled";

    /** 括号里的模组名就是各处理流程共用的标识。 */
    private static final Pattern BRACKET_PATTERN = Pattern.compile("\\[(.*?)\\]");

    /**
     * 首次启动流程的收尾：按平台调整一次模组启停，并清掉标记。
     * <p>由 Modpack Helper 的说明页在玩家点「跳过」（或按 Esc）时调用，
     * 因此即使玩家直接跳过说明页，这一步也一定会执行一次。
     *
     * @return 是否真的改动过模组文件（调用方据此决定要不要提示重启）
     */
    public static boolean applyFirstTimeSetup() {
        boolean changed = false;
        if (firstTimeSetup || pendingAutoEnable) {
            changed = syncPlatformMods();
        }
        firstTimeSetup = false;
        pendingAutoEnable = false;
        return changed;
    }

    /**
     * 按平台把「输入法冲突修复」调整到正确的启停状态。
     * <p>Windows 端：文件被改名为 {@code .jar.disabled} 的，恢复启用。
     * <p>非 Windows 端：文件仍处于启用状态的，改名为 {@code .jar.disabled}。
     * <p>只看文件名、不加载模组，所以改动下次启动才生效。
     *
     * @return 是否真的改过文件名
     */
    private static boolean syncPlatformMods() {
        File dir = FMLPaths.MODSDIR.get().toFile();
        if (!dir.exists() || !dir.isDirectory()) return false;

        File[] files = dir.listFiles();
        if (files == null) return false;

        boolean windows = PlatformUtils.isWindows();
        List<String> changedNames = new ArrayList<>();

        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();

            boolean disabled = name.endsWith(DISABLED_SUFFIX);
            String baseName = disabled ? name.substring(0, name.length() - ".disabled".length()) : name;
            if (!baseName.endsWith(JAR_SUFFIX)) continue;

            // 只处理「这个模组本身」的文件：括号里的名字必须正好是目标名
            Matcher bracketMatcher = BRACKET_PATTERN.matcher(baseName);
            if (!bracketMatcher.find() || !IME_FIX_BRACKET.equals(bracketMatcher.group(1))) continue;

            if (windows && disabled) {
                if (rename(file, baseName)) changedNames.add(baseName);
            } else if (!windows && !disabled) {
                String newName = name + ".disabled";
                if (rename(file, newName)) changedNames.add(newName);
            }
        }

        boolean changed = !changedNames.isEmpty();
        if (changed) {
            Log.info("按平台自动调整模组 " + changedNames.size() + " 个: ["
                    + String.join(", ", changedNames) + "]");
        }
        return changed;
    }

    /**
     * 把模组文件改成新名字，返回是否成功。
     * <p>用 {@link Files#move} 而不是 {@link File#renameTo}：后者失败时只返回
     * {@code false}，不给任何原因，排查时无从下手。
     * <p>关于失败的概率：<b>在当前进程里禁用或启用正在运行的模组不会失败</b>，
     * 这一点已在实际游戏内验证过——Windows 上 JVM 读完 jar 条目后就释放了句柄。
     * 真正会失败的是文件被<b>其它进程</b>独占：另一个启动器实例正在运行、
     * 杀毒软件正在扫描该文件等。这类情况少见，但一旦发生必须让玩家看到原因，
     * 否则会以为开关坏了。
     */
    private static boolean rename(File source, String newName) {
        try {
            Files.move(source.toPath(), source.toPath().resolveSibling(newName),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            String action = newName.endsWith(".disabled") ? "禁用" : "启用";
            String reason = "无法" + action + " " + source.getName() + "：" + e.getMessage();
            Log.error(reason);
            // 首次启动流程里没有聊天栏，reportToPlayer 会自动跳过
            reportToPlayer(reason);
            return false;
        }
    }

    /** 把失败原因告诉玩家；没有聊天栏时只留日志。 */
    private static void reportToPlayer(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        player.displayClientMessage(
                Component.literal("[VerityPlus] ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(message).withStyle(ChatFormatting.RED)),
                false);
    }
}
