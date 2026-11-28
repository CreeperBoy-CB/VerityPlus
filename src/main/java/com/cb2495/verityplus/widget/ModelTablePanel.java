package com.cb2495.verityplus.widget;

import com.cb2495.verityplus.config.AiModelInfo;
import com.cb2495.verityplus.config.ModelCatalog;
import com.cb2495.verityplus.config.ModelRow;
import com.cb2495.verityplus.guide.GuideTexts;
import com.cb2495.verityplus.util.Log;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 第 3 步的主体：搜索框 + 一边的搜索按钮 + 一张可排序的模型表。
 *
 * <h3>为什么是自绘面板而不是一堆控件</h3>
 * 表格的内容全是静态文字，只有表头可点、行可点、一格悬停要出 tooltip；
 * 做成几十个 {@code Button} 既重又难对齐（列宽、滚动、悬停高亮都得自己算一遍）。
 * 所以这里把「表头 / 行 / 滚动条 / tooltip」都放进一个组件里自绘，
 * 只让屏幕那边放搜索框、搜索按钮、主按钮和两个自绘链接。
 *
 * <h3>表的固定结构</h3>
 * <pre>
 *   ┌ 表头 ────────────────────────────────────────────────┐
 *   │ 模型名称▲ │ 常见名称 │ 工具调用 │ 深度思考 │ 价格 ▲   │
 *   ├──────────────────────────────────────────────────────┤
 *   │ （请选择）                                            │   ← 第 0 行，不随排序移动
 *   │ deepseek-flash  │ DeepSeek V4.1 Flash │ ✅ │ ✅ │ 2/8/0.04 │
 *   │ …                                                     │
 *   │ 自定义          │ [输入你的模型……]                      │   ← 最后一行
 *   └──────────────────────────────────────────────────────┘
 *   注1：✅代表支持，❌代表不支持，未知代表不知道
 *   注2：价格信息为手动收集……格式：输入/输出/命中
 * </pre>
 * 两行注解在表格<b>外侧左下角</b>，所以表格本身要先把这两行让出来。
 *
 * <h3>列宽比例</h3>
 * 取附件 1.xlsx 的列宽（{@code A..E} = 20.2 / 19.5 / 9.1 / 9.0 / 14.8），
 * 换算成百分比后按可用宽度分配；最后两列各自吃掉余量的一半，
 * 合计永远是 100%，不会在右边留一条缝。
 */
public class ModelTablePanel {

    // ---------- 列 ----------
    /** 五列，顺序与附件表一致。 */
    private static final ModelRow.SortColumn[] COLUMNS = {
            ModelRow.SortColumn.MODEL, ModelRow.SortColumn.COMMON,
            ModelRow.SortColumn.TOOL, ModelRow.SortColumn.THINKING,
            ModelRow.SortColumn.PRICE
    };
    /** 附件里的列宽（字符数），只用来定比例。 */
    private static final double[] WEIGHT = {20.208, 19.517, 9.125, 9.0, 14.783};

    // ---------- 尺寸 ----------
    /** 表头高度。 */
    private static final int HEADER_HEIGHT = 16;
    /** 每行高度。 */
    private static final int ROW_HEIGHT = 16;
    /** 单元格文字与列边框的内边距。 */
    private static final int CELL_PAD = 4;
    /** 排序箭头在单元格里预留的固定宽度：表头永远给箭头留着位置，切换时文字不跟着跳。 */
    private static final int ARROW_SLOT = 10;
    /** 滚动条宽度。 */
    private static final int SCROLL_WIDTH = 4;
    /** 搜索框高度与搜索按钮尺寸。 */
    private static final int SEARCH_HEIGHT = 20;
    private static final int SEARCH_BUTTON_SIZE = 20;
    private static final int SEARCH_GAP = 4;

    // ---------- 颜色 ----------
    private static final int COLOR_SURFACE = 0xC0000000;
    private static final int COLOR_HEADER_BG = 0xFF2A2A2A;
    private static final int COLOR_BORDER = 0xFFAAAAAA;
    private static final int COLOR_ROW_ALT = 0xFF1A1A1A;
    private static final int COLOR_ROW_HOVER = 0xFF2E2E2E;
    private static final int COLOR_ROW_SELECTED = 0xFF335533;
    private static final int COLOR_TEXT = 0xFFDDDDDD;
    private static final int COLOR_TEXT_DIM = 0xFF9A9A9A;
    private static final int COLOR_TEXT_FOCUS = 0xFFFFFFFF;
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    private static final int COLOR_OK = 0xFF55FF55;
    private static final int COLOR_BAD = 0xFFFF5555;
    private static final int COLOR_NOTE = 0xFF888888;
    /** 注解衬底：半透明黑，压在行内容上时保证文字可读。 */
    private static final int COLOR_NOTE_BG = 0xD0000000;
    private static final int COLOR_ARROW = 0xFFFFD700;
    private static final int COLOR_SCROLL_TRACK = 0xFF3A3A3A;
    private static final int COLOR_SCROLL_THUMB = 0xFFCCCCCC;
    /** 自定义输入框的边框：比行选中色亮一档，方便一眼看出那是能打字的地方。 */
    private static final int COLOR_INPUT_BORDER = 0xFF55AA55;

    // ---------- 文案 ----------
    /** 第 0 行：「还没选」的占位，与第 1 步的下拉框同一措辞。 */
    public static final String PLACEHOLDER_LABEL = GuideTexts.PLACEHOLDER_LABEL;
    /** 最后一行。 */
    public static final String CUSTOM_LABEL = GuideTexts.CUSTOM_LABEL;
    /** 自定义输入框的提示。 */
    public static final String CUSTOM_HINT = GuideTexts.CUSTOM_HINT;
    /** 搜索框的提示。 */
    public static final String SEARCH_HINT = GuideTexts.SEARCH_HINT;
    /** 搜索按钮上的字。用字体里现成的符号，不依赖彩色 emoji。 */
    public static final String SEARCH_SYMBOL = GuideTexts.SEARCH_SYMBOL;

    /** 表格左下角外侧的两行注解。 */
    private static final String[] NOTES = {GuideTexts.NOTE_CAPABILITY, GuideTexts.NOTE_PRICE};

    /** 能力三态在格子里的字。 */
    private static final String MARK_YES = "✅";
    private static final String MARK_NO = "❌";
    private static final String MARK_UNKNOWN = "未知";
    /** 没有价格时的字。 */
    private static final String PRICE_UNKNOWN = "未知";

    private final Minecraft minecraft;
    private final Font font;

    // ---------- 状态 ----------
    private List<ModelRow.Row> allRows = List.of();
    /** 经过搜索与排序之后真正画出来的行（不含第 0 行与自定义行）。 */
    private List<ModelRow.Row> viewRows = List.of();
    private ModelRow.SortSpec sortSpec = ModelRow.SortSpec.defaults();
    private String query = "";
    /** 当前选中的模型标识；{@code null} 表示还没选。 */
    private String selectedModel = null;
    /** 是否选中了「自定义」。 */
    private boolean customSelected = false;
    /** 自定义输入框里已经确认的模型名（失焦 / 打字停下之后）。 */
    private String customValue = "";

    /** 表格区域的滚动偏移，以及当前最大的滚动偏移。 */
    private int scroll = 0;
    private int maxScroll = 0;

    /** 搜索框与搜索按钮。它们是真的控件（要打字、要点击），由面板建与持有。 */
    private EditBox searchBox;
    private SearchButton searchButton;
    private CustomModelEditBox customBox;

    // ---------- 布局（每帧调用 layout 更新） ----------
    private int left;
    private int right;
    private int tableTop;
    private int tableBottom;

    /** 正在左右滚动的那一格（鼠标停在上面时）；没有则为 {@code null}。 */
    private ScrollingCell scrollingCell = null;

    /** 是否正在拖动右侧滚动条。 */
    private boolean draggingScroll = false;

    /** 悬停提示的颜色。 */
    private static final int TOOLTIP_GRAY = 0xFFAAAAAA;

    public ModelTablePanel(Minecraft minecraft) {
        this.minecraft = minecraft;
        this.font = minecraft.font;
    }

    /** 一格正在横向滚动的文字。 */
    private record ScrollingCell(int rowIndex, int column, int overflow, long hoveredAt) {}

    // ================= 数据 =================

    /**
     * 换一批模型。
     *
     * @param provider   Verity 侧提供商标识，用来查人工信息与推荐顺序
     * @param aggregator 接的是不是中转站（OpenRouter），决定价格能不能借用同名模型的参考价
     * @param entries    {@code /models} 解析结果
     */
    public void setModels(String provider, boolean aggregator, List<ModelCatalog.Entry> entries) {
        this.allRows = ModelRow.buildRows(provider, entries, aggregator);
        Log.info("[ModelTable] ROWS_BUILT | PROVIDER=" + provider + " | " + ModelRow.describe(allRows));
        // 换了提供商：原来的选中项可能已经不存在了
        if (selectedModel != null && findRow(selectedModel) == null) {
            selectedModel = null;
        }
        scroll = 0;
        refreshView();
    }

    /** 按当前搜索结果与排序方式重算要画的行。 */
    private void refreshView() {
        this.viewRows = ModelRow.sort(ModelRow.filter(allRows, query), sortSpec);
        this.scrollingCell = null;
    }

    /** 表里所有模型（不受搜索影响）。 */
    public List<ModelRow.Row> allRows() {
        return allRows;
    }

    private ModelRow.Row findRow(String id) {
        for (ModelRow.Row row : allRows) {
            if (row.id().equals(id)) return row;
        }
        return null;
    }

    public boolean isEmpty() {
        return allRows.isEmpty();
    }

    // ================= 选择 =================

    /** 选中的是表里的哪一行；选了「自定义」或没选时返回 {@code null}。 */
    public String selectedModel() {
        return selectedModel;
    }

    /** 最终要写进 {@code aiModel} 的值；没有任何有效选择时返回 {@code null}。 */
    public String effectiveModel() {
        if (customSelected) {
            String value = customValue.trim();
            return value.isEmpty() ? null : value;
        }
        return selectedModel;
    }

    /** 是不是选了「自定义」并且输入框里有内容。 */
    public boolean isCustomReady() {
        return customSelected && !customValue.trim().isEmpty();
    }

    /** 选中表里的某个模型（点行）。 */
    private void selectModel(String id) {
        this.selectedModel = id;
        this.customSelected = false;
        if (customBox != null) customBox.setFocused(false);
    }

    /** 选中「自定义」并把焦点交给输入框。 */
    private void selectCustom() {
        this.customSelected = true;
        if (customBox != null) {
            customBox.setFocused(true);
            setFocusedCustom(true);
        }
    }

    /** 自定义输入框的内容变了。 */
    private void onCustomEdited(String text) {
        this.customValue = text;
        // 一开始打字就当作「已经转投自定义」：否则玩家在输入框里敲字、
        // 表里那一行的绿框还亮着，看不出到底以哪个为准
        if (!customSelected) {
            this.customSelected = true;
        }
    }

    private void setFocusedCustom(boolean focused) {
        if (customBox != null) customBox.setFocused(focused);
    }

    // ================= 控件 =================

    /**
     * 建好搜索框与搜索按钮，并挂到屏幕上。
     *
     * @param adder 屏幕的 {@code addRenderableWidget}
     */
    public void initWidgets(java.util.function.Function<AbstractWidget, AbstractWidget> adder,
                            int boxLeft, int boxRight, int boxY) {
        int buttonX = boxRight - SEARCH_BUTTON_SIZE;
        int boxWidth = Math.max(60, buttonX - SEARCH_GAP - boxLeft);

        EditBox box = new EditBox(this.font, boxLeft, boxY, boxWidth, SEARCH_HEIGHT,
                Component.literal(SEARCH_HINT));
        box.setMaxLength(128);
        box.setValue(query);
        box.setHint(Component.literal(SEARCH_HINT)
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        box.setResponder(text -> {
            if (Objects.equals(this.query, text)) return;
            this.query = text;
            this.scroll = 0;
            refreshView();
        });
        this.searchBox = box;
        adder.apply(box);

        SearchButton button = new SearchButton(buttonX, boxY, SEARCH_BUTTON_SIZE, SEARCH_HEIGHT,
                Component.literal(SEARCH_SYMBOL), btn -> applySearch());
        button.setTooltip(Tooltip.create(Component.literal("搜索")));
        this.searchButton = button;
        adder.apply(button);

        // 自定义输入框：位置随滚动变化，由 layout() 每帧校正
        CustomModelEditBox custom = new CustomModelEditBox(this.font, 0, 0, 40, ROW_HEIGHT,
                Component.literal(CUSTOM_LABEL));
        custom.setMaxLength(128);
        custom.setValue(customValue);
        custom.setHint(Component.literal(CUSTOM_HINT)
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        custom.setResponder(this::onCustomEdited);
        this.customBox = custom;
        adder.apply(custom);
    }

    /**
     * 点搜索按钮。
     * <p>搜索本身是<b>边打边筛</b>的（responder 里就刷新了），所以这个按钮的实际作用是
     * 「执行一次筛选并把焦点交回输入框」：玩家敲完字点它，不该没有任何反应。
     */
    private void applySearch() {
        this.query = searchBox == null ? "" : searchBox.getValue();
        this.scroll = 0;
        refreshView();
        if (searchBox != null) {
            setFocusedSearch(true);
        }
        Log.debug("[ModelTable] SEARCH | QUERY=" + this.query + " | HITS=" + viewRows.size());
    }

    private void setFocusedSearch(boolean focused) {
        if (searchBox != null) searchBox.setFocused(focused);
    }

    /** 焦点是否在搜索框上（屏幕那边据此决定 Esc 是清空搜索还是回上一步）。 */
    public boolean isSearchFocused() {
        return searchBox != null && searchBox.isFocused();
    }

    /** 焦点是否在自定义输入框上。 */
    public boolean isCustomFocused() {
        return customBox != null && customBox.isFocused();
    }

    public void tickWidgets() {
        if (searchBox != null) searchBox.tick();
        if (customBox != null) customBox.tick();
    }

    /** 清空搜索框。 */
    public void clearSearch() {
        if (searchBox != null && !searchBox.getValue().isEmpty()) {
            searchBox.setValue("");
        }
        this.query = "";
        this.scroll = 0;
        refreshView();
    }

    // ================= 布局 =================

    /** 搜索框那一行的高度（含与表格之间的间隙）。 */
    public static int searchRowHeight() {
        return SEARCH_HEIGHT + 8;
    }

    /** 表格下方那两行注解共占的高度。 */
    public static int notesHeight(Font font) {
        return NOTES.length * (font.lineHeight + 1) + 2;
    }

    /**
     * 设定表格可用区域。
     * <p>由屏幕在 {@code init()} 里算好：上边界在搜索框下面，下边界要让开注解与按钮。
     */
    public void layout(int left, int right, int top, int bottom) {
        this.left = left;
        this.right = right;
        this.tableTop = top;
        this.tableBottom = bottom;
    }

    /** 内容行的可视高度。 */
    private int bodyHeight() {
        return Math.max(ROW_HEIGHT, tableBottom - tableTop - HEADER_HEIGHT);
    }

    /** 表头下沿。 */
    private int bodyTop() {
        return tableTop + HEADER_HEIGHT;
    }

    /** 表格内容的右边界（不含滚动条）。 */
    private int contentRight() {
        return right - SCROLL_WIDTH - 1;
    }

    /** 表格底边（含边框）。 */
    private int tableBottomEdge() {
        return bodyTop() + bodyHeight();
    }

    /** 需要画笔的固定行数：第 0 行的「（请选择）」与最后一行的「自定义」。 */
    private static int fixedRowCount() {
        return 2;
    }

    /** 全部行数（含两个固定行）。 */
    private int totalRowCount() {
        return fixedRowCount() + viewRows.size();
    }

    /** 内容总高度。 */
    private int contentHeight() {
        return totalRowCount() * ROW_HEIGHT;
    }

    /**
     * 画在左下角的那块注解从表格底边往上占掉多少像素。
     *
     * <p>与 {@link #drawNotes} 的实际排法严格一致：
     * <pre>
     *   底边框       1px
     *   顶部留白     2px
     *   每行文字     行高 + 1px
     * </pre>
     * 这里少算 1px，滚到底时最后一行就会被衬底压住一条边——
     * 数值上是 1px，视觉上就是「最后一行永远显示不全」。
     */
    private int notesOverlayHeight() {
        int perLine = this.font.lineHeight + 1;
        return 1 + 2 + NOTES.length * perLine;
    }

    /**
     * 可滚动的内容区高度。
     *
     * <p>画在左下角的注解压在内容之上，所以这里要把它的实际高度让出来——
     * 否则滚到底时最后一行会被注解盖住，玩家永远看不到它。
     *
     * <p><b>必须向下取整到整行</b>：让出的高度（比如 23px）不是 {@link #ROW_HEIGHT} 的倍数，
     * 直接减会让「内容高 − 有效高」不是整行数，滚动偏移就停在半行上，
     * 最后一行仍然会被衬底压住一条边。取整之后滚到底正好是整行对齐，
     * 末行完整露在注解上方。
     *
     * <p>表格矮到画不下注解时（{@link #notesEnabled()} 为假）就不必让。
     */
    private int effectiveBodyHeight() {
        int available = bodyHeight();
        if (notesEnabled()) {
            int free = available - notesOverlayHeight();
            available = Math.max(ROW_HEIGHT, free / ROW_HEIGHT * ROW_HEIGHT);
        }
        return available;
    }

    private int maxScrollNow() {
        return Math.max(0, contentHeight() - effectiveBodyHeight());
    }

    /** 第 {@code index} 行（0 = 占位行）在内容坐标系里的上沿。 */
    private static int rowTopInContent(int index) {
        return index * ROW_HEIGHT;
    }

    /** 某一列在屏幕上的左右边界。 */
    private int columnLeft(int column) {
        int usable = contentRight() - (left + 1);
        double total = 0;
        for (double w : WEIGHT) total += w;
        int x = left + 1;
        for (int i = 0; i < column; i++) {
            x += Math.round((float) (usable * WEIGHT[i] / total));
        }
        return x;
    }

    private int columnWidth(int column) {
        int usable = contentRight() - (left + 1);
        double total = 0;
        for (double w : WEIGHT) total += w;
        return Math.round((float) (usable * WEIGHT[column] / total));
    }

    private int columnRight(int column) {
        if (column == COLUMNS.length - 1) return contentRight();
        return columnLeft(column) + columnWidth(column);
    }

    // ================= 绘制 =================

    /**
     * 画整个面板。
     *
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        this.maxScroll = maxScrollNow();
        this.scroll = Math.max(0, Math.min(this.maxScroll, this.scroll));

        drawTable(graphics, mouseX, mouseY);
        // 注解画在表格<b>内部</b>，压在内容之上：这样表格能一直铺到按钮上方，
        // 不必为了注解在外面留出两行高度（小窗口下一行就是四分之一个可视区）
        drawNotes(graphics);
        drawCustomBoxOverlay(graphics, mouseX, mouseY);

        // 先把表格自身的东西全部提交，再画 tooltip。
        //
        // 这一步是为「覆盖层变透明」准备的：tooltip 会被模组（例如 ModernUI 的现代
        // tooltip）接管、用自己的一套半透明样式重建批次。如果边框、滚动条这类填充
        // 还排在 tooltip 之后的批次里，就会跟着那一次重建一起变淡。
        // 在这里主动 flush 一次，表格的线条与文字就已经落到屏幕上了，
        // 后面无论谁接管 tooltip 都影响不到它们。
        graphics.flush();

        drawTooltip(graphics, mouseX, mouseY);
        drawCustomTooltip(graphics, mouseX, mouseY);
    }

    /** 表格外框、表头、内容行、滚动条。 */
    private void drawTable(GuiGraphics graphics, int mouseX, int mouseY) {
        int bottom = tableBottomEdge();

        graphics.fill(left, tableTop, right, bottom, COLOR_SURFACE);
        graphics.renderOutline(left, tableTop, right - left, bottom - tableTop, COLOR_BORDER);

        drawHeader(graphics, mouseX, mouseY);

        // 内容区：只画落在可视范围内的行
        int bodyTop = bodyTop();
        graphics.enableScissor(left + 1, bodyTop, contentRight(), bottom - 1);
        int firstIndex = scroll / ROW_HEIGHT;
        int offset = scroll % ROW_HEIGHT;
        for (int i = firstIndex; i < totalRowCount(); i++) {
            int y = bodyTop + rowTopInContent(i) - scroll;
            if (y >= bottom - 1) break;
            if (y + ROW_HEIGHT <= bodyTop) continue;
            drawRow(graphics, i, y, mouseX, mouseY);
        }
        graphics.disableScissor();

        drawScrollBar(graphics, bottom);
    }

    /** 表头：五列标题 + 排序列右侧的箭头 + 竖分隔线。 */
    private void drawHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(left + 1, tableTop + 1, right - 1, bodyTop(), COLOR_HEADER_BG);

        int textY = tableTop + 1 + (HEADER_HEIGHT - this.font.lineHeight) / 2;
        for (int c = 0; c < COLUMNS.length; c++) {
            ModelRow.SortColumn column = COLUMNS[c];
            boolean hovered = mouseX >= columnLeft(c) && mouseX < columnRight(c)
                    && mouseY >= tableTop + 1 && mouseY < bodyTop();

            String label = column.label();
            int labelX = columnLeft(c) + CELL_PAD;
            graphics.drawString(this.font, label, labelX, textY,
                    hovered ? COLOR_TEXT_FOCUS : COLOR_TEXT, false);

            // 箭头：始终占着同一个固定位置（紧跟在标题文字之后），
            // 亮不亮取决于当前排序列，所以切换时其它列的文字不会挪位
            String arrow = arrowFor(column);
            if (arrow != null) {
                graphics.drawString(this.font, arrow, labelX + this.font.width(label) + 2, textY,
                        COLOR_ARROW, false);
            }
        }

        // 竖线：画在列与列之间，表头与内容都靠它分隔
        for (int c = 1; c < COLUMNS.length; c++) {
            int x = columnLeft(c);
            graphics.fill(x, tableTop + 1, x + 1, tableBottomEdge() - 1, 0xFF3F3F3F);
        }
        // 表头与内容之间的横线
        graphics.fill(left + 1, bodyTop() - 1, right - 1, bodyTop(), COLOR_BORDER);
    }

    /** 某一列现在该显示哪个箭头；不是排序列时返回 {@code null}。 */
    private String arrowFor(ModelRow.SortColumn column) {
        if (sortSpec.isDefault() || sortSpec.column() != column) return null;
        return sortSpec.direction() == ModelRow.Direction.ASC ? "▲" : "▼";
    }

    /** 画一行（第 0 行、模型行、自定义行）。 */
    private void drawRow(GuiGraphics graphics, int index, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= left + 1 && mouseX < contentRight()
                && mouseY >= y && mouseY < y + ROW_HEIGHT;
        int rowBottom = y + ROW_HEIGHT;

        int bg;
        if (index == 0) {
            // 「（请选择）」：它同时也是「什么都没选」时的选中态，与第 1 步一致
            bg = selectedModel == null && !customSelected && !hovered ? COLOR_ROW_SELECTED : COLOR_ROW_ALT;
            if (hovered) bg = COLOR_ROW_HOVER;
        } else if (index == totalRowCount() - 1) {
            bg = customSelected ? COLOR_ROW_SELECTED : hovered ? COLOR_ROW_HOVER : COLOR_ROW_ALT;
        } else {
            ModelRow.Row row = viewRows.get(index - 1);
            boolean selected = !customSelected && row.id().equals(selectedModel);
            bg = selected ? COLOR_ROW_SELECTED : hovered ? COLOR_ROW_HOVER
                    : (index % 2 == 0 ? COLOR_ROW_ALT : COLOR_SURFACE);
        }
        graphics.fill(left + 1, y, contentRight(), rowBottom, bg);

        int textY = y + (ROW_HEIGHT - this.font.lineHeight) / 2;

        if (index == 0) {
            drawCell(graphics, 0, PLACEHOLDER_LABEL, textY,
                    selectedModel == null && !customSelected ? COLOR_ACCENT : COLOR_TEXT_DIM);
            return;
        }
        if (index == totalRowCount() - 1) {
            // 自定义行：右列是输入框，由 drawCustomBoxOverlay 盖上去，这里只画左列
            drawCell(graphics, 0, CUSTOM_LABEL, textY,
                    customSelected ? COLOR_ACCENT : COLOR_TEXT);
            return;
        }

        ModelRow.Row row = viewRows.get(index - 1);
        boolean selected = !customSelected && row.id().equals(selectedModel);

        // 1. 模型名称
        drawScrollingCell(graphics, index, 0, row.id(), textY,
                selected ? COLOR_ACCENT : hovered ? COLOR_TEXT_FOCUS : COLOR_TEXT,
                mouseX, mouseY, y);

        // 2. 常见名称
        drawScrollingCell(graphics, index, 1, row.commonCell(), textY,
                row.common() == null ? COLOR_TEXT_DIM : hovered ? COLOR_TEXT_FOCUS : COLOR_TEXT,
                mouseX, mouseY, y);

        // 3. 工具调用 / 4. 深度思考
        drawCapability(graphics, index, 2, row.tool(), textY, mouseX, mouseY, y);
        drawCapability(graphics, index, 3, row.thinking(), textY, mouseX, mouseY, y);

        // 5. 价格
        drawScrollingCell(graphics, index, 4,
                row.hasPrice() ? row.price().compact() : PRICE_UNKNOWN, textY,
                row.hasPrice() ? hovered ? COLOR_TEXT_FOCUS : COLOR_TEXT : COLOR_TEXT_DIM,
                mouseX, mouseY, y);
    }

    /** 画一列能力：✅ / ❌ / 未知，居中。 */
    private void drawCapability(GuiGraphics graphics, int rowIndex, int column,
                                ModelCatalog.Capability capability, int textY,
                                int mouseX, int mouseY, int rowTop) {
        String text = switch (capability) {
            case SUPPORTED -> MARK_YES;
            case UNSUPPORTED -> MARK_NO;
            case UNKNOWN -> MARK_UNKNOWN;
        };
        int color = switch (capability) {
            case SUPPORTED -> COLOR_OK;
            case UNSUPPORTED -> COLOR_BAD;
            case UNKNOWN -> COLOR_TEXT_DIM;
        };
        int x = columnLeft(column) + (columnWidth(column) - this.font.width(text)) / 2;
        boolean hovered = mouseX >= columnLeft(column) && mouseX < columnRight(column)
                && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;
        graphics.drawString(this.font, text, x, textY,
                hovered && capability != ModelCatalog.Capability.UNKNOWN ? color : color, false);
        // 这个格子不需要横向滚动：内容是固定的几个字
        if (hovered) this.scrollingCell = null;
    }

    /** 画一个左对齐、超宽时可横向滚动的单元格。 */
    private void drawScrollingCell(GuiGraphics graphics, int rowIndex, int column, String text,
                                   int textY, int color, int mouseX, int mouseY, int rowTop) {
        int x0 = columnLeft(column) + CELL_PAD;
        int avail = columnWidth(column) - CELL_PAD * 2;
        int textWidth = this.font.width(text);
        int overflow = textWidth - avail;

        boolean hovered = mouseX >= columnLeft(column) && mouseX < columnRight(column)
                && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;

        int shift = 0;
        if (overflow > 0 && hovered) {
            // 鼠标停在这一格上：文字左右往复，把超出的部分露出来
            shift = Math.round(overflow * pingPong(System.currentTimeMillis())) ;
            if (scrollingCell == null || scrollingCell.rowIndex() != rowIndex
                    || scrollingCell.column() != column) {
                scrollingCell = new ScrollingCell(rowIndex, column, overflow, System.currentTimeMillis());
            }
        }
        drawCell(graphics, column, text, textY, color, shift);
    }

    /** 画一列的文字（带裁剪，绝不越出本列）。 */
    private void drawCell(GuiGraphics graphics, int column, String text, int textY, int color) {
        drawCell(graphics, column, text, textY, color, 0);
    }

    private void drawCell(GuiGraphics graphics, int column, String text, int textY, int color, int shift) {
        int x0 = columnLeft(column) + CELL_PAD;
        int x1 = columnRight(column) - CELL_PAD;
        graphics.enableScissor(x0, tableTop + HEADER_HEIGHT, x1, tableBottomEdge() - 1);
        graphics.drawString(this.font, text, x0 - shift, textY, color, false);
        graphics.disableScissor();
    }

    /**
     * 往复曲线 0..1：一个来回 {@link #SCROLL_PING_PONG_MS} 毫秒，两端各停顿一下。
     * <p>用余弦而不是三角波，两端速度自然为 0，看起来像「推过去又拉回来」。
     */
    private static final long SCROLL_PING_PONG_MS = 4000L;

    private static float pingPong(long now) {
        double phase = (now % SCROLL_PING_PONG_MS) / (double) SCROLL_PING_PONG_MS;
        return (float) ((1 - Math.cos(phase * Math.PI * 2)) / 2);
    }

    /**
     * 表格<b>内部</b>左下角的两行灰色注解。
     *
     * <p>压在内容之上而不是单独占一行：表格要铺满「搜索框下方 ~ 继续按钮上方」这一整段，
     * 外面没有地方再放两行字。注解放在左下角、叠在半透明底上，滚动的行从它下面经过，
     * 读起来仍然是「表格的脚注」。
     *
     * <p>为了不让它把最后几行遮死，内容区在算滚动上限时额外让出了这段高度
     * （见 {@link #notesBlockHeight()}），滚到底时最后一行会完整露在注解上方。
     */
    private void drawNotes(GuiGraphics graphics) {
        if (!notesEnabled()) return;
        int blockHeight = notesOverlayHeight();
        int top = tableBottomEdge() - 1 - blockHeight;

        // 半透明衬底：文字压在行内容上时仍然读得清
        graphics.fill(left + 1, top, contentRight(), tableBottomEdge() - 1, COLOR_NOTE_BG);

        int y = top + 2;
        for (String note : NOTES) {
            graphics.drawString(this.font, trimToWidth(note, contentRight() - left - 8),
                    left + 4, y, COLOR_NOTE, false);
            y += this.font.lineHeight + 1;
        }
    }

    /**
     * 表格矮到放不下注解时不画。
     * <p>注解可以没有，能看见的模型行不能没有——极端窗口下优先保住内容。
     */
    private boolean notesEnabled() {
        return bodyHeight() >= notesOverlayHeight() + 3 * ROW_HEIGHT;
    }

    /**
     * 把文字截到指定像素宽度内，超出部分换成省略号。
     * <p>注解是固定长句，小窗口下按整行画会直接捅出表格右边，只能截断。
     */
    private String trimToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - this.font.width(ellipsis);
        if (budget <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int w = this.font.width(String.valueOf(ch));
            if (used + w > budget) break;
            sb.append(ch);
            used += w;
        }
        return sb + ellipsis;
    }

    /**
     * 自定义输入框：盖在「自定义」那一行的右四列上。
     * <p>它是个真控件（要打字），位置随滚动走，所以每帧重算 Y。
     */
    private void drawCustomBoxOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (customBox == null) return;
        int rowIndex = totalRowCount() - 1;
        int y = bodyTop() + rowTopInContent(rowIndex) - scroll;

        int x0 = columnLeft(1);
        int x1 = contentRight() - 2;
        int width = Math.max(40, x1 - x0);
        // 只在落在可视区里时才摆出来；滚出去时挪到表格外并禁用，免得变成看不见还能聚焦的幽灵
        boolean visible = y >= bodyTop() && y + ROW_HEIGHT <= tableBottomEdge();
        customBox.setX(x0);
        customBox.setY(visible ? y : tableBottomEdge() + 200);
        customBox.setWidth(width);
        customBox.setVisible(visible);
        // editable 只管「能不能打字」，它不挡点击——所以下面 mouseClicked 里
        // 还得先判一次命中，否则玩家点进输入框时会先被当成「选中自定义行」，
        // 焦点被抢走、光标落不到想点的位置上
        if (!visible && customBox.isFocused()) {
            customBox.setFocused(false);
        }
        if (!visible) return;

        graphics.enableScissor(x0, bodyTop(), contentRight(), tableBottomEdge() - 1);
        // 边框与输入框<b>紧挨着</b>画，中间不夹任何 drawString。
        // drawString 会触发 GuiGraphics 的 endBatch，把前面排队的绘制切成另一个批次；
        // 而模组（如 ModernUI）可能接管批次提交、按自己的样式重建，
        // 一旦边框和输入框被切到不同批次，边框就会跟着那一次提交一起变淡甚至消失。
        // 所以顺序是：输入框 → 边框 → 提示文字（文字放最后，它自己开新批次也无所谓）。
        customBox.render(graphics, mouseX, mouseY, 0f);
        // 边框：选中自定义时亮起，否则与行边框同色
        graphics.renderOutline(x0 - 1, y, width + 2, ROW_HEIGHT,
                customSelected ? COLOR_INPUT_BORDER : 0xFF555555);
        // 提示放在最后，理由见 CustomModelEditBox.drawHint
        customBox.drawHint(graphics);
        graphics.disableScissor();

        // 输入框是自绘的，不经过控件的悬停链，所以「未选中时点它会怎样」得自己提示
        if (!customSelected && !customValue.isEmpty()) {
            if (mouseX >= x0 && mouseX < x0 + width && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                graphics.renderTooltip(this.font,
                        Component.literal("当前使用表内模型；点这里可改用自定义模型名"),
                        mouseX, mouseY);
            }
        }
    }

    /** 悬停提示：价格明细、常见名称全称、能力说明。 */
    private void drawTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (mouseY < bodyTop() || mouseY >= tableBottomEdge()) return;
        if (mouseX < left + 1 || mouseX >= contentRight()) return;

        int rowIndex = (int) ((mouseY - bodyTop() + scroll) / ROW_HEIGHT);
        int firstModelRow = 1;
        int lastModelRow = totalRowCount() - 2;
        if (rowIndex < firstModelRow || rowIndex > lastModelRow) return;

        ModelRow.Row row = viewRows.get(rowIndex - firstModelRow);
        int column = columnAt(mouseX);
        if (column < 0) return;

        List<Component> lines = tooltipFor(row, column);
        if (lines.isEmpty()) return;
        graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 自定义行的悬停提示。 */
    private void drawCustomTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int rowIndex = totalRowCount() - 1;
        int y = bodyTop() + rowTopInContent(rowIndex) - scroll;
        if (mouseY < y || mouseY >= y + ROW_HEIGHT) return;
        if (mouseX < left + 1 || mouseX >= contentRight()) return;
        int column = columnAt(mouseX);
        if (column < 1) return;
        if (customSelected) return;

        graphics.renderTooltip(this.font,
                List.of(Component.literal("点击后在这里输入模型标识").withStyle(ChatFormatting.WHITE)),
                java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 鼠标落在第几列；不在任何列里时返回 {@code -1}。 */
    private int columnAt(int mouseX) {
        for (int c = 0; c < COLUMNS.length; c++) {
            if (mouseX >= columnLeft(c) && mouseX < columnRight(c)) return c;
        }
        return -1;
    }

    /**
     * 某一格该出什么提示。
     * <p>价格那格按需求给三行明细；分时计费再补一行说明是哪一档。
     */
    private static List<Component> tooltipFor(ModelRow.Row row, int column) {
        List<Component> lines = new ArrayList<>();
        if (column == 4) {
            AiModelInfo.Price price = row.price();
            if (price == null) {
                lines.add(Component.literal("价格：未知").withStyle(ChatFormatting.GRAY));
                return lines;
            }
            lines.add(Component.literal("百万Token输入：" + value(price.input()) + " 元")
                    .withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("百万Token输出：" + value(price.output()) + " 元")
                    .withStyle(ChatFormatting.WHITE));
            if (price.cache() != null) {
                lines.add(Component.literal("百万Token输出（缓存命中）：" + value(price.cache()) + " 元")
                        .withStyle(ChatFormatting.WHITE));
            }
            if (row.peak() != null && row.peak().price() != null) {
                lines.add(Component.literal("非高峰时段：" + row.peak().price().compact() + " 元")
                        .withStyle(ChatFormatting.GRAY));
                if (row.peak().note() != null) {
                    lines.add(Component.literal(row.peak().note()).withStyle(ChatFormatting.GRAY));
                }
            }
            return lines;
        }

        if (column == 1 && row.common() != null) {
            String text = row.common();
            if (Minecraft.getInstance().font.width(text) > 0) {
                lines.add(Component.literal(text).withStyle(ChatFormatting.WHITE));
            }
            return lines;
        }

        if (column == 2 || column == 3) {
            ModelCatalog.Capability capability = column == 2 ? row.tool() : row.thinking();
            String what = column == 2 ? "工具调用" : "深度思考";
            String value = switch (capability) {
                case SUPPORTED -> "支持";
                case UNSUPPORTED -> "不支持";
                case UNKNOWN -> "未知（接口与信息表都没有给出）";
            };
            lines.add(Component.literal(what + "：" + value).withStyle(TOOLTIP_GRAY_COLOR));
            return lines;
        }
        return lines;
    }

    private static final ChatFormatting TOOLTIP_GRAY_COLOR = ChatFormatting.GRAY;

    private static String value(Double v) {
        return v == null ? "?" : AiModelInfo.format(v);
    }

    /** 滚动条：内容装得下时不画。 */
    private void drawScrollBar(GuiGraphics graphics, int bottom) {
        if (maxScroll <= 0) return;
        int trackTop = bodyTop();
        int trackHeight = bottom - 1 - trackTop;
        int thumbHeight = scrollThumbHeight(trackHeight);
        int thumbY = trackTop + scrollThumbOffset(trackTop, trackHeight, thumbHeight);
        int trackX = scrollTrackX();
        graphics.fill(trackX, trackTop, trackX + SCROLL_WIDTH, bottom - 1, COLOR_SCROLL_TRACK);
        graphics.fill(trackX, thumbY, trackX + SCROLL_WIDTH, thumbY + thumbHeight, COLOR_SCROLL_THUMB);
    }

    /** 滚动条轨道的左边缘。 */
    private int scrollTrackX() {
        return right - SCROLL_WIDTH - 1;
    }

    /** 滑块高度，与 {@link #drawScrollBar} 的算法一致。 */
    private int scrollThumbHeight(int trackHeight) {
        return Math.max(16, trackHeight * effectiveBodyHeight() / Math.max(1, contentHeight()));
    }

    /** 滑块顶端相对轨道顶部的偏移。 */
    private int scrollThumbOffset(int trackTop, int trackHeight, int thumbHeight) {
        if (maxScroll <= 0) return 0;
        return (int) ((long) (trackHeight - thumbHeight) * scroll / maxScroll);
    }

    /** 鼠标是否落在滚动条轨道上（含滑块与空白处）。 */
    private boolean isScrollTrackHit(double mouseX, double mouseY) {
        if (maxScroll <= 0) return false;
        int trackX = scrollTrackX();
        return mouseX >= trackX && mouseX < trackX + SCROLL_WIDTH
                && mouseY >= bodyTop() && mouseY < tableBottomEdge() - 1;
    }

    /**
     * 按鼠标位置换算出滚动偏移（拖动滑块 / 点轨道空白处用）。
     * <p>拖动时不要求鼠标正好在滑块上，只要还在轨道里就跟手走——
     * 这与原版滚动条的手感一致：按下之后滑块黏在指针上。
     */
    private void scrollFromMouseY(double mouseY) {
        int trackTop = bodyTop();
        int trackHeight = tableBottomEdge() - 1 - trackTop;
        int thumbHeight = scrollThumbHeight(trackHeight);
        int travel = trackHeight - thumbHeight;
        if (travel <= 0) return;
        // 让滑块中心对齐指针
        double relative = mouseY - trackTop - thumbHeight / 2.0;
        double ratio = relative / travel;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        scroll = (int) Math.round(ratio * maxScrollNow());
    }

    // ================= 交互 =================

    /**
     * 处理一次左键点击。
     *
     * @return {@code true} 表示这一下点被表格吃掉了
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) return false;
        if (mouseX < left || mouseX >= right) return false;

        // 滚动条优先：它在最右侧一条窄带里，不该被当成「点了某一行」
        if (button == 0 && isScrollTrackHit(mouseX, mouseY)) {
            draggingScroll = true;
            scrollFromMouseY(mouseY);
            return true;
        }
        draggingScroll = false;

        // 表头：左键切排序；右键只对「价格」列有意义（换排序依据）
        if (mouseY >= tableTop && mouseY < bodyTop()) {
            int column = columnAt((int) mouseX);
            if (column < 0) return true;
            ModelRow.SortColumn target = COLUMNS[column];
            if (button == 1) {
                if (target == ModelRow.SortColumn.PRICE) {
                    sortSpec = sortSpec.cyclePriceField();
                    // 右键换依据时如果还没按价格排，顺手把它排上——
                    // 否则玩家点完看不出任何变化，会以为没生效
                    if (sortSpec.isDefault()) {
                        sortSpec = new ModelRow.SortSpec(ModelRow.SortColumn.PRICE,
                                ModelRow.Direction.ASC, ModelRow.SortSpec.defaults().priceField());
                    }
                    refreshView();
                }
                return true;
            }
            sortSpec = sortSpec.toggle(target);
            scroll = 0;
            refreshView();
            Log.debug("[ModelTable] SORT | COLUMN=" + target + " | SPEC=" + sortSpec);
            return true;
        }

        if (mouseY < bodyTop() || mouseY >= tableBottomEdge()) return false;

        int rowIndex = (int) ((mouseY - bodyTop() + scroll) / ROW_HEIGHT);
        if (rowIndex == 0) {
            // 「（请选择）」：清空选择
            selectedModel = null;
            customSelected = false;
            setFocusedCustom(false);
            return true;
        }
        if (rowIndex == totalRowCount() - 1) {
            // 先判「点在输入框上」：那时应该只是移动光标，不该把整行重新选一遍
            if (customSelected && customBox != null && customBox.getX() >= 0
                    && mouseX >= customBox.getX()
                    && mouseX < customBox.getX() + customBox.getWidth()
                    && mouseY >= customBox.getY()
                    && mouseY < customBox.getY() + customBox.getHeight()) {
                return customBox.mouseClicked(mouseX, mouseY, button);
            }
            selectCustom();
            // 点左列或右边的空白：把焦点交给输入框，玩家可以直接开始打字
            return customBox != null && customBox.mouseClicked(
                    customBox.getX() + 2, customBox.getY() + customBox.getHeight() / 2, button);
        }
        if (rowIndex > viewRows.size()) return true;

        ModelRow.Row row = viewRows.get(rowIndex - 1);
        if (button == 0) {
            selectModel(row.id());
            Log.debug("[ModelTable] MODEL_PICKED | ID=" + row.id());
        }
        return true;
    }

    /** 滚轮：一次滚一行。 */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < left || mouseX >= right) return false;
        if (mouseY < tableTop || mouseY >= tableBottomEdge()) return false;
        if (maxScrollNow() <= 0) return false;
        int before = scroll;
        scroll = Math.max(0, Math.min(maxScrollNow(),
                scroll - (int) Math.round(delta) * ROW_HEIGHT));
        return scroll != before;
    }

    /**
     * 拖动滚动条。
     * <p>只有按下时确实落在轨道上才生效（{@link #draggingScroll}），
     * 否则从表头按下再划到右边也会莫名其妙地滚动。
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!draggingScroll || button != 0) return false;
        scrollFromMouseY(mouseY);
        return true;
    }

    /** 松开：结束拖动。 */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return false;
    }

    /** 是否正在拖滚动条（屏幕那边据此避免把拖动也算成点击）。 */
    public boolean isDraggingScroll() {
        return draggingScroll;
    }

    /** 点击是否落在任一面板区域上（用于把焦点从输入框上摘下来）。 */
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < right && mouseY >= tableTop && mouseY < tableBottomEdge();
    }

    // ================= 内部控件 =================

    /**
     * 搜索按钮。
     * <p>只是为了让「🔍」有个能点、能出 tooltip 的落点；
     * 文字用字体里的符号，不依赖彩色 emoji 字体。
     */
    private static final class SearchButton extends net.minecraft.client.gui.components.Button {

        SearchButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }
    }

    /**
     * 自定义模型名的输入框。
     * <p>原版 {@link EditBox} 的白框压在表格里会显得很跳，这里自己描一圈暗绿边框，
     * 与「自定义」行的选中色呼应。
     */
    private static final class CustomModelEditBox extends EditBox {

        CustomModelEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }

        /** 现在是否需要补画提示（聚焦、为空）。 */
        boolean needsHint() {
            return getValue().isEmpty() && isFocused();
        }

        /**
         * 在控件<b>外部</b>补画那行提示。
         * <p>不能写在 {@link #renderWidget} 里：1.20.1 的 {@code GuiGraphics.drawString}
         * 末尾会调 {@code flushIfUnmanaged()}，在控件绘制中途调用会把之前排队的绘制
         * <b>提前提交</b>，导致本页其余文字落到错的图层。
         * 由 {@link #drawCustomBoxOverlay} 在渲染完输入框之后补。
         */
        void drawHint(GuiGraphics graphics) {
            if (!needsHint()) return;
            graphics.drawString(Minecraft.getInstance().font,
                    Component.literal(CUSTOM_HINT).withStyle(ChatFormatting.ITALIC),
                    getX() + 4, getY() + (getHeight() - 8) / 2, 0xFF777777, false);
        }
    }
}
