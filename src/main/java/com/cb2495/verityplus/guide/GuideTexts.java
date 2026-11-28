package com.cb2495.verityplus.guide;

/**
 * 引导流程里的固定文案。
 *
 * <h3>为什么单独放一处</h3>
 * 同一句话在界面与面板之间有两份拷贝时，改一边忘另一边会以「只在某一步是旧文案」
 * 这种很难发现的方式出错——第 3 步的表格注解就踩过这个坑
 * （屏幕那边写了一份、面板里又写了一份）。这里集中一处，
 * 需要跨包使用的就是 {@code public}。
 */
public final class GuideTexts {

    private GuideTexts() {}

    /** 第 1 步正文：选提供商。 */
    public static final String PROVIDER_QUESTION = "首先，请选择 Verity AI 大脑的提供商";

    /** 第 2 步正文：填 API Key。 */
    public static final String API_KEY_QUESTION = "请在此处填入你的API Key";

    /** 第 3 步正文：选模型。 */
    public static final String MODEL_QUESTION = "请为 Verity 的AI大脑选择模型";

    // ---------- 第 3 步表格的固定文案 ----------

    /** 表格第 0 行：还没选。 */
    public static final String PLACEHOLDER_LABEL = "（请选择）";
    /** 表格最后一行。 */
    public static final String CUSTOM_LABEL = "自定义";
    /** 自定义输入框的提示。 */
    public static final String CUSTOM_HINT = "输入你的模型……";
    /** 搜索框的提示。 */
    public static final String SEARCH_HINT = "搜索模型……";
    /** 搜索按钮上的字。 */
    public static final String SEARCH_SYMBOL = "\uD83D\uDD0D";

    /** 表格左下角外侧的第一行注解。 */
    public static final String NOTE_CAPABILITY =
            "注1：✅代表支持，❌代表不支持，未知代表不知道";

    /** 表格左下角外侧的第二行注解。 */
    public static final String NOTE_PRICE =
            "注2：价格信息为手动收集，部分信息经过汇率换算，仅供参考，"
                    + "准确信息请前往API提供商文档查询，格式：输入/输出/命中";
}
