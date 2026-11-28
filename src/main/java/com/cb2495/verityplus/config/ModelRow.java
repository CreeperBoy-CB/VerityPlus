package com.cb2495.verityplus.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第 3 步表格里的一行：把「接口给的」和「人工整理的」合成一份。
 *
 * <h3>合并规则</h3>
 * 能力与常见名称都是<b>三级</b>取值，谁先说听谁的：
 * <ol>
 *   <li>人工整理的 {@link AiModelInfo}；</li>
 *   <li>{@code /models} 响应里的字段（见 {@link ModelCatalog}）；</li>
 *   <li>两者的前缀规则；</li>
 * </ol>
 * 三级都没有就是 {@link ModelCatalog.Capability#UNKNOWN}——<b>不是「不支持」</b>。
 *
 * <h3>为什么输出能力不单独占一列</h3>
 * 表格只有五列（模型名称 / 常见名称 / 工具调用 / 深度思考 / 价格）。
 * 「不能输出文本」的模型（向量模型、图像生成模型）留在表里会让人误选，
 * 所以直接在建表时滤掉——见 {@link #buildRows}。
 */
public final class ModelRow {

    private ModelRow() {}

    /**
     * 一行。
     *
     * @param id       调用时用的标识
     * @param common   常见名称；{@code null} 表示没收录，界面显示「暂无」
     * @param tool     工具调用能力
     * @param thinking 深度思考能力
     * @param price    价格；{@code null} 表示未知
     * @param peak     高峰时段价；非分时计费为 {@code null}
     * @param rank     默认排序里的位置（越小越靠前）；不在推荐表里时是 {@code Integer.MAX_VALUE}
     * @param aliases  同一模型的其他可用标识（Mistral 会列出来），搜索时也参与匹配
     */
    public record Row(String id, String common,
                      ModelCatalog.Capability tool, ModelCatalog.Capability thinking,
                      AiModelInfo.Price price, AiModelInfo.Peak peak, int rank,
                      List<String> aliases) {

        /** 兼容不含别名的旧构造。 */
        public Row(String id, String common,
                   ModelCatalog.Capability tool, ModelCatalog.Capability thinking,
                   AiModelInfo.Price price, AiModelInfo.Peak peak, int rank) {
            this(id, common, tool, thinking, price, peak, rank, List.of());
        }

        /** 界面上显示的名字：没有常见名称时退到标识本身。 */
        public String displayName() {
            return common == null || common.isBlank() ? id : common;
        }

        /** 常见名称这一格该显示什么。 */
        public String commonCell() {
            return common == null || common.isBlank() ? "暂无" : common;
        }

        /** 有没有可排序的价格。 */
        public boolean hasPrice() {
            return price != null && price.hasInput();
        }

        /** 这个标识（或它的任一别名）是否包含某段小写文本。 */
        public boolean matchesId(String lowercaseNeedle) {
            if (id.toLowerCase(java.util.Locale.ROOT).contains(lowercaseNeedle)) return true;
            for (String alias : aliases) {
                if (alias.toLowerCase(java.util.Locale.ROOT).contains(lowercaseNeedle)) return true;
            }
            return false;
        }
    }

    /**
     * 把接口条目与人工信息合成表格行。
     *
     * @param provider Verity 侧提供商标识，用来查带前缀的人工信息与推荐顺序
     * @param entries  {@link ModelCatalog#parse} 或 {@link ModelCatalog#loadCached} 的结果
     */
    public static List<Row> buildRows(String provider, List<ModelCatalog.Entry> entries) {
        return buildRows(provider, entries, false);
    }

    /**
     * 把接口条目与人工信息合成表格行。
     *
     * @param provider  Verity 侧提供商标识，用来查带前缀的人工信息与推荐顺序
     * @param entries   {@link ModelCatalog#parse} 或 {@link ModelCatalog#loadCached} 的结果
     * @param aggregator 连接的是不是中转站（OpenRouter）。是的话，本家查不到价格时
     *                   退回同名模型的参考价——见 {@link AiModelInfo#inheritedPricing}
     */
    public static List<Row> buildRows(String provider, List<ModelCatalog.Entry> entries,
                                      boolean aggregator) {
        if (entries == null || entries.isEmpty()) return List.of();

        // 默认排序的位置表：推荐顺序里第几个
        List<String> order = AiModelInfo.orderFor(provider);
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.putIfAbsent(order.get(i), i);
        }

        List<Row> rows = new ArrayList<>(entries.size());
        int dropped = 0;
        // 通过中转站（OpenRouter）调用时，同一份模型的价目表照样成立，
        // 只是账单由中转站结算。所以查不到本家的价时，退回同一份表——
        // 但那只是<b>参考价</b>，标记出来，悬停提示里会写明
        boolean inheritedPrice = false;
        for (ModelCatalog.Entry entry : entries) {
            // 确定不能输出文本的（向量模型、图像生成）不进表
            if (entry.definitelyNotText()) {
                dropped++;
                continue;
            }
            // 标识明显是非对话模型（tts、image、embedding、realtime…）：不进表。
            //
            // 这一步<b>不看</b> outputsText：Gemini 的 TTS 与图像模型同样带
            // generateContent（它们确实能「生成」内容，只是产出的是音频或图片，
            // 不是给对话用的文本），OpenAI 干脆什么都不给。所以名字特征独立生效。
            if (AiModelInfo.looksNonText(entry.id())) {
                dropped++;
                continue;
            }
            AiModelInfo.Model info = AiModelInfo.lookup(provider, entry.id());
            AiModelInfo.Rule rule = AiModelInfo.ruleFor(provider, entry.id());
            AiModelInfo.Price price = info == null ? null : info.price();
            AiModelInfo.Peak peak = info == null ? null : info.peak();
            AiModelInfo.Model inherited = null;
            if (price == null && aggregator) {
                inherited = AiModelInfo.inheritedPricing(entry.id());
                if (inherited != null) {
                    price = inherited.price();
                    peak = inherited.peak();
                    inheritedPrice = true;
                }
            }

            rows.add(new Row(
                    entry.id(),
                    info != null ? info.common() : inherited == null ? null : inherited.common(),
                    merge(entry.toolCall(), info == null ? null : info.tool(), rule == null ? null : rule.tool()),
                    merge(entry.thinking(), info == null ? null : info.thinking(),
                            rule == null ? null : rule.thinking()),
                    price,
                    peak,
                    rank.getOrDefault(entry.id(), Integer.MAX_VALUE),
                    entry.aliases() == null ? List.of() : entry.aliases()));
        }
        if (dropped > 0) {
            com.cb2495.verityplus.util.Log.debug("[ModelRow] NON_TEXT_DROPPED | COUNT=" + dropped
                    + " | PROVIDER=" + provider);
        }
        if (inheritedPrice) {
            com.cb2495.verityplus.util.Log.debug("[ModelRow] PRICE_INHERITED | PROVIDER=" + provider);
        }
        return rows;
    }

    /** 三级取值：人工 → 接口 → 规则。 */
    private static ModelCatalog.Capability merge(ModelCatalog.Capability fromApi,
                                                 Boolean fromInfo,
                                                 Boolean fromRule) {
        if (fromInfo != null) {
            return fromInfo ? ModelCatalog.Capability.SUPPORTED : ModelCatalog.Capability.UNSUPPORTED;
        }
        if (fromApi != null && fromApi != ModelCatalog.Capability.UNKNOWN) {
            return fromApi;
        }
        if (fromRule != null) {
            return fromRule ? ModelCatalog.Capability.SUPPORTED : ModelCatalog.Capability.UNSUPPORTED;
        }
        return ModelCatalog.Capability.UNKNOWN;
    }

    // ================= 排序 =================

    /** 价格的三个排序依据。 */
    public enum PriceField {
        /** 输入价——默认就是它。 */
        INPUT("输入"),
        /** 输出价。 */
        OUTPUT("输出"),
        /** 缓存命中价。 */
        CACHE("命中");

        private final String label;

        PriceField(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 在输入 / 输出 / 命中三者之间循环。 */
        public PriceField next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** 取这个依据对应的数值；没有时返回 {@code null}。 */
        public Double pick(AiModelInfo.Price price) {
            if (price == null) return null;
            return switch (this) {
                case INPUT -> price.input();
                case OUTPUT -> price.output();
                case CACHE -> price.cache();
            };
        }
    }

    /**
     * 表头可点击的四列。
     * <p>模型名称排在最左，与「（请选择）」占位行同一个位置，也一起参与切换。
     */
    public enum SortColumn {
        MODEL("模型名称"),
        /** 常见名称。 */
        COMMON("常见名称"),
        TOOL("工具调用"),
        THINKING("深度思考"),
        PRICE("价格 / RMB");

        private final String label;

        SortColumn(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 排序方向：升序 / 降序。 */
    public enum Direction {
        /** 升序，表头画 ▲。 */
        ASC,
        /** 降序，表头画 ▼。 */
        DESC
    }

    /**
     * 一次排序的完整描述。
     *
     * @param column   按哪一列排
     * @param direction 方向
     * @param priceField 只有 {@code column == PRICE} 时有意义
     */
    public record SortSpec(SortColumn column, Direction direction, PriceField priceField) {

        /** 默认排序：按人工表里的推荐顺序，表里没有的按标识 A-Z。 */
        public static SortSpec defaults() {
            return new SortSpec(null, null, PriceField.INPUT);
        }

        /** 是不是「默认顺序」。 */
        public boolean isDefault() {
            return column == null;
        }

        /**
         * 点一下表头之后的下一档：默认 → 升序 → 降序 → 回到默认。
         *
         * @param clicked 点的是哪一列
         * @return 新的一档；切到别的列时一律从升序开始
         */
        public SortSpec toggle(SortColumn clicked) {
            if (column != clicked) return new SortSpec(clicked, Direction.ASC, priceField);
            return switch (direction) {
                case ASC -> new SortSpec(clicked, Direction.DESC, priceField);
                case DESC -> defaults();
            };
        }

        /**
         * 在价格列上右键：切换排序依据。
         * <p>只在「价格列已选中」时才有意义，否则右键不改变任何东西
         * （玩家还没按价格排，换依据看不出效果）。
         */
        public SortSpec cyclePriceField() {
            if (column != SortColumn.PRICE) return this;
            return new SortSpec(column, direction, priceField.next());
        }
    }

    /**
     * 按当前排序方式排好序。
     *
     * <h3>三条共同规则</h3>
     * <ol>
     *   <li><b>未知 / 没有的永远在末尾</b>，不参与比较；</li>
     *   <li>并列时一律回到「默认顺序」——推荐表里的位置、表里没有的按标识 A-Z。
     *       这就是需求里说的「一组内排序按默认排」；</li>
     *   <li>倒序<b>不</b>把末尾那批也翻过来：它们内部始终是 A-Z。</li>
     * </ol>
     *
     * @param rows 原始行
     * @param spec 排序方式；{@code null} 或默认时只做推荐顺序 + A-Z
     */
    public static List<Row> sort(List<Row> rows, SortSpec spec) {
        List<Row> sorted = new ArrayList<>(rows);
        Comparator<Row> byId = Comparator.comparing(Row::id, String.CASE_INSENSITIVE_ORDER);
        Comparator<Row> byRank = Comparator.comparingInt(Row::rank).thenComparing(byId);

        if (spec == null || spec.isDefault()) {
            sorted.sort(byRank);
            return sorted;
        }

        boolean desc = spec.direction() == Direction.DESC;
        Comparator<Integer> intOrder = desc ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<String> textOrder = desc
                ? String.CASE_INSENSITIVE_ORDER.reversed()
                : String.CASE_INSENSITIVE_ORDER;
        Comparator<Double> doubleOrder = desc ? Comparator.reverseOrder() : Comparator.naturalOrder();

        switch (spec.column()) {
            case MODEL -> sorted.sort(atEndById(Row::id, textOrder, byRank));
            case COMMON -> sorted.sort(atEndById(r -> r.common() == null ? null : r.common(),
                    textOrder, byRank));
            case TOOL -> sorted.sort(atEndByInt(r -> capabilityOrder(r.tool()), intOrder, byRank));
            case THINKING -> sorted.sort(atEndByInt(r -> capabilityOrder(r.thinking()), intOrder, byRank));
            case PRICE -> sorted.sort(atEndByDouble(
                    r -> spec.priceField().pick(r.price()), doubleOrder, byRank));
        }
        return sorted;
    }

    /**
     * 能力 → 排序用的整数。
     * <p>需求是「√ 在前、× 在后」，未知不参与比较。
     * 返回 {@code null} 表示排在末尾。
     */
    private static Integer capabilityOrder(ModelCatalog.Capability capability) {
        return switch (capability) {
            case SUPPORTED -> 0;
            case UNSUPPORTED -> 1;
            case UNKNOWN -> null;
        };
    }

    /** 末尾那一批内部固定按标识 A-Z，与方向无关。 */
    private static final Comparator<Row> ID_ASC =
            Comparator.comparing(Row::id, String.CASE_INSENSITIVE_ORDER);

    /**
     * 有值时按 {@code valueOrder} 比，没值时一律沉到末尾。
     * <p>末尾那批用 {@code byRank} 而不是 A-Z：需求说「一组内排序按默认排」，
     * 而默认排本身就是「推荐表顺序，没有的 A-Z」。
     */
    private static Comparator<Row> atEndById(java.util.function.Function<Row, String> get,
                                             Comparator<String> valueOrder,
                                             Comparator<Row> byRank) {
        return (a, b) -> {
            String ka = get.apply(a);
            String kb = get.apply(b);
            if (ka == null && kb == null) return byRank.compare(a, b);
            if (ka == null) return 1;
            if (kb == null) return -1;
            int c = valueOrder.compare(ka, kb);
            return c != 0 ? c : ID_ASC.compare(a, b);
        };
    }

    private static Comparator<Row> atEndByInt(java.util.function.Function<Row, Integer> get,
                                              Comparator<Integer> valueOrder,
                                              Comparator<Row> byRank) {
        return (a, b) -> {
            Integer ka = get.apply(a);
            Integer kb = get.apply(b);
            if (ka == null && kb == null) return byRank.compare(a, b);
            if (ka == null) return 1;
            if (kb == null) return -1;
            int c = valueOrder.compare(ka, kb);
            return c != 0 ? c : byRank.compare(a, b);
        };
    }

    private static Comparator<Row> atEndByDouble(java.util.function.Function<Row, Double> get,
                                                 Comparator<Double> valueOrder,
                                                 Comparator<Row> byRank) {
        return (a, b) -> {
            Double ka = get.apply(a);
            Double kb = get.apply(b);
            if (ka == null && kb == null) return byRank.compare(a, b);
            if (ka == null) return 1;
            if (kb == null) return -1;
            int c = valueOrder.compare(ka, kb);
            return c != 0 ? c : byRank.compare(a, b);
        };
    }

    /**
     * 搜索过滤：标识、别名与常见名称里任意一个包含关键字就算命中。
     * <p>不区分大小写；关键字为空时不筛。
     * <p>别名要参与：Mistral 的模型常用浮动名（{@code codestral-latest}）调用，
     * 但表里显示的是固定版本名，不按别名搜的话玩家会以为「搜不到这个模型」。
     */
    public static List<Row> filter(List<Row> rows, String query) {
        if (query == null || query.isBlank()) return rows;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        for (Row row : rows) {
            if (row.matchesId(needle)) {
                out.add(row);
                continue;
            }
            String common = row.common();
            if (common != null && common.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(row);
            }
        }
        return out;
    }

    /** 总数、支持数等统计，写日志用。 */
    public static String describe(List<Row> rows) {
        if (rows == null || rows.isEmpty()) return "(empty)";
        int withPrice = 0;
        int withCommon = 0;
        for (Row row : rows) {
            if (row.hasPrice()) withPrice++;
            if (row.common() != null) withCommon++;
        }
        return "rows=" + rows.size() + " withPrice=" + withPrice + " withCommon=" + withCommon;
    }
}
