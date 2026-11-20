package com.cb2495.verityplus.screen;

import com.cb2495.verityplus.VerityPlus;

/**
 * 本模组跨模块共享的一些数据。
 * <p>类名保留自「模组管理界面」时期，现在<b>不画任何界面</b>：那个界面早已下线，
 * 首次启动流程（欢迎页、按平台自动启停模组）也随配置界面一起移除了，
 * 之后会重写。这里只剩两块仍然有用的东西：
 * <ul>
 *   <li>整合包版本号 —— 测试与诊断时用；</li>
 *   <li>Verity 与配置工具自身的版本号 —— 由 {@link VerityPlus#loadVersions()} 扫描填入。</li>
 * </ul>
 */
public final class ModsListScreen {

    private ModsListScreen() {}

    /** 整合包版本号；默认值直接取自 {@link VerityPlus#MODPACK_VERSION}，避免两处版本号不一致。 */
    public static String modpackVersion = VerityPlus.MODPACK_VERSION;
    /** Verity 本体的版本；由 {@link VerityPlus#loadVersions()} 扫描 mods 目录后填入。 */
    public static String verityVersion = "未知";
    /** 本模组的版本；同上。 */
    public static String configModVersion = "未知";
}
