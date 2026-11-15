package com.cb2495.verityconfig.client;

import com.cb2495.verityconfig.VerityPlus;
import com.cb2495.verityconfig.config.ModConfig;
import com.cb2495.verityconfig.screen.VerityPlusScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.time.LocalDate;
import java.time.LocalDateTime;

@SuppressWarnings("removal")
@Mod.EventBusSubscriber(modid = VerityPlus.MODID, value = Dist.CLIENT)
public class ClientCommands {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 三个子指令依赖 Modpack Helper 的实现：
        //   help  / qanda   → 帮助文档与常见问题界面都在那边
        //   dshint          → 峰谷提示整块实现都在那边
        // 没装这个模组时不注册它们，玩家在补全里就不会看到一个点了没用的指令。
        boolean helper = ModpackHelperBridge.isAvailable();

        var root = Commands.literal("vc")
                .executes(ctx -> {
                    showHelpPrompt();
                    return 1;
                })
                .then(Commands.literal("cs")
                        .executes(ctx -> {
                            openConfigScreen();
                            return 1;
                        })
                )
                .then(Commands.literal("configscreen")
                        .executes(ctx -> {
                            openConfigScreen();
                            return 1;
                        })
                );

        if (helper) {
            root.then(Commands.literal("help")
                    .executes(ctx -> {
                        showCommandList();
                        return 1;
                    })
                    .then(Commands.argument("topic", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String topic = StringArgumentType.getString(ctx, "topic");
                                // 帮助界面在 Modpack Helper 里；它自己做中文名反查
                                openHelpTopic(topic);
                                return 1;
                            })
                    )
            );
            root.then(Commands.literal("qanda")
                    .executes(ctx -> {
                        if (!ModpackHelperBridge.openQandA()) {
                            reportHelperMissing();
                        }
                        return 1;
                    })
            );
            root.then(dshintCommand());
        }

        root.then(Commands.literal("exitgame")
                .executes(ctx -> {
                    Minecraft.getInstance().stop();
                    return 1;
                })
        )
                .then(Commands.literal("verity")
                        .then(Commands.literal("mfix")
                                .executes(ctx -> {
                                    VerityMemoryManager.fix();
                                    return 1;
                                })
                        )
                        .then(Commands.literal("mdel")
                                .executes(ctx -> {
                                    VerityMemoryManager.delete();
                                    return 1;
                                })
                        )
                );

        dispatcher.register(root);
    }

    /**
     * {@code /vc dshint}：峰谷提示的开关与查询。
     * <p>实现已经搬到 Modpack Helper，这里只做「改配置 + 通知它刷新缓存」。
     * 配置本身仍在本模组，因为那是 Verity 的 AI 设置的一部分。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> dshintCommand() {
        return Commands.literal("dshint")
                .executes(ctx -> {
                    boolean next = !ModConfig.isShowDeepSeekHint();
                    ModConfig.setShowDeepSeekHint(next);
                    ModpackHelperBridge.refreshDeepSeekToggle();
                    sendToggleMessage(next);
                    return 1;
                })
                .then(Commands.literal("on")
                        .executes(ctx -> {
                            ModConfig.setShowDeepSeekHint(true);
                            ModpackHelperBridge.refreshDeepSeekToggle();
                            sendToggleMessage(true);
                            return 1;
                        })
                )
                .then(Commands.literal("off")
                        .executes(ctx -> {
                            ModConfig.setShowDeepSeekHint(false);
                            ModpackHelperBridge.refreshDeepSeekToggle();
                            sendToggleMessage(false);
                            return 1;
                        })
                )
                .then(Commands.literal("date")
                        // 不带参数：按当前时间输出一次提示
                        .executes(ctx -> {
                            ModpackHelperBridge.sendDeepSeekHintAt(LocalDateTime.now());
                            return 1;
                        })
                        .then(Commands.argument("date", StringArgumentType.word())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            runDeepSeekDateTest(
                                                    StringArgumentType.getString(ctx, "date"),
                                                    StringArgumentType.getString(ctx, "time"));
                                            return 1;
                                        })
                                )
                        )
                );
    }

    private static void openConfigScreen() {
        Minecraft.getInstance().setScreen(new VerityPlusScreen());
    }

    /**
     * 把给定时刻当作"现在"，输出模组本应显示的峰谷提示。
     * <p>用于查看任意时刻（含跨天、跨假期）的判断结果。
     *
     * @param date 日期，格式 {@code YYYY-MM-DD}
     * @param time 时间，格式 {@code mm.ss}，即小时.分钟
     */
    private static void runDeepSeekDateTest(String date, String time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        LocalDateTime moment;
        try {
            LocalDate day = LocalDate.parse(date);
            String[] parts = time.split("\\.");
            if (parts.length != 2) throw new IllegalArgumentException("时间需要 mm.ss");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour > 23 || minute > 59) throw new IllegalArgumentException("时间超出范围");
            moment = day.atTime(hour, minute);
        } catch (Exception e) {
            mc.player.displayClientMessage(
                    Component.literal("[VerityPlus] 时间格式错误，应为 /vc dshint date YYYY-MM-DD mm.ss，例如 2026-10-01 10.00")
                            .withStyle(ChatFormatting.RED),
                    false);
            return;
        }

        ModpackHelperBridge.sendDeepSeekHintAt(moment);
    }

    /** 反馈 DeepSeek 峰谷提示开关的当前状态。 */
    private static void sendToggleMessage(boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(
                Component.literal("[VerityPlus] ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("DeepSeek 峰谷时段提示已").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(enabled ? "开启" : "关闭")
                                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                        .append(Component.literal("。").withStyle(ChatFormatting.WHITE)),
                false);
    }

    private static void showHelpPrompt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Component msg = Component.literal("[VerityPlus] ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("输入 ").withStyle(ChatFormatting.WHITE))
                .append(ModpackHelperBridge.suggestCmd("/vc help", ModpackHelperBridge.CLICK_TO_FILL))
                .append(Component.literal(" 查看指令列表，或 ").withStyle(ChatFormatting.WHITE))
                .append(ModpackHelperBridge.suggestCmd("/vc cs", ModpackHelperBridge.CLICK_TO_FILL))
                .append(Component.literal(" 打开配置界面。").withStyle(ChatFormatting.WHITE));

        mc.player.displayClientMessage(msg, false);
    }

    private static void showCommandList() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(Component.literal("==== VerityPlus 指令 ====").withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD), false);
        say(mc, "/vc cs", "打开配置界面");
        say(mc, "/vc help", "查看此列表");
        // 下面几条的实现都在 Modpack Helper 里，没装时那个子指令压根没注册，
        // 指令列表也就不该把它列出来
        if (ModpackHelperBridge.isAvailable()) {
            say(mc, "/vc qanda", "打开常见问题解答");
            say(mc, "/vc dshint", "开关 DeepSeek 峰谷时段提示");
            say(mc, "/vc dshint date", "按当前时间查询一次峰谷提示");
        }
        say(mc, "/vc verity mfix", "修复 Verity 的聊天记忆（移除空 AI 消息）");
        say(mc, "/vc verity mdel", "删除记忆文件（让 Verity 重新生成）");
    }

    /** 指令列表里的一行：可点击的指令 + 灰色说明。 */
    private static void say(Minecraft mc, String command, String description) {
        mc.player.displayClientMessage(
                ModpackHelperBridge.suggestCmd(command, ModpackHelperBridge.CLICK_TO_FILL)
                        .copy()
                        .append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY)),
                false);
    }

    /**
     * 打开帮助主题。
     * <p>帮助文档与界面都在 Modpack Helper 里，这里只把主题名原样递过去；
     * 中文名反查也在那边做，找不到主题时由它自己的界面显示「无法加载」。
     */
    private static void openHelpTopic(String topic) {
        if (!ModpackHelperBridge.openHelp(null, topic)) {
            reportHelperMissing();
        }
    }

    /** 没有安装 Modpack Helper 时在聊天栏说明原因，而不是让指令静默失败。 */
    private static void reportHelperMissing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(
                Component.literal("[VerityPlus] 该功能位于 Modpack Helper 模组中，请先安装它。")
                        .withStyle(ChatFormatting.RED),
                false);
    }
    // 「可点击填入指令」的组件构造已搬到 Modpack Helper 的 ChatLinks，这里经 ModpackHelperBridge 调用
}
