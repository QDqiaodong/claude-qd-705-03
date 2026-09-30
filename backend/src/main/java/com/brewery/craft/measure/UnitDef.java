package com.brewery.craft.measure;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;

/**
 * 受支持单位的唯一定义处：单位属于哪个维度、换算到维度基准单位的系数、显示精度、最小投放步长。
 *
 * <ul>
 *   <li>质量：kg、g —— 基准 g，1 kg = 1000 g</li>
 *   <li>体积：L、mL —— 基准 mL，1 L = 1000 mL</li>
 *   <li>计数：pkg —— 基准 pkg</li>
 * </ul>
 *
 * 别名表负责历史数据里的大小写/写法不一致（KG / Kg / 克 / 毫升 …），
 * 但别名解析只做「写法归一」，永远不会把一个质量单位猜成体积单位。
 */
@Getter
public final class UnitDef {

    private static final Map<String, UnitDef> REGISTRY = new LinkedHashMap<>();
    /** 历史写法别名（已 trim、大写）→ 规范单位码 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        // 质量
        put(new UnitDef("kg", Dimension.MASS, new BigDecimal("1000"), 3, new BigDecimal("0.001")),
                "KG", "KGS", "KG.", "千克", "公斤");
        put(new UnitDef("g", Dimension.MASS, BigDecimal.ONE, 1, new BigDecimal("1")),
                "G", "G.", "GR", "GRAM", "GRAMS", "克");
        // 体积
        put(new UnitDef("L", Dimension.VOLUME, new BigDecimal("1000"), 3, new BigDecimal("0.001")),
                "L.", "LTR", "LT", "LITRE", "LITER", "LITRES", "LITERS", "升");
        put(new UnitDef("mL", Dimension.VOLUME, BigDecimal.ONE, 1, new BigDecimal("1")),
                "ML", "ML.", "MLS", "MILLILITRE", "MILLILITER", "毫升");
        // 计数
        put(new UnitDef("pkg", Dimension.COUNT, BigDecimal.ONE, 3, new BigDecimal("0.001")),
                "PKG.", "PACK", "PACKAGE", "PACKET", "SACHET", "BAG", "包", "袋", "包/袋");
    }

    /** 规范单位码（也是前端显示用的酿酒师习惯单位） */
    private final String code;
    private final Dimension dimension;
    /** 1 个本单位 = factor 个基准单位 */
    private final BigDecimal factorToBase;
    /** 显示小数位 */
    private final int displayScale;
    /** 本单位下的最小可投放量（步长） */
    private final BigDecimal step;

    private UnitDef(String code, Dimension dimension, BigDecimal factorToBase, int displayScale, BigDecimal step) {
        this.code = code;
        this.dimension = dimension;
        this.factorToBase = factorToBase;
        this.displayScale = displayScale;
        this.step = step;
    }

    private static void put(UnitDef def, String... aliases) {
        REGISTRY.put(def.code.toUpperCase(), def);
        for (String a : aliases) {
            ALIASES.put(a.trim().toUpperCase(), def.code);
        }
    }

    /** 精确按规范码取定义（code 必须完全一致） */
    public static UnitDef byCode(String code) {
        if (code == null) {
            return null;
        }
        return REGISTRY.get(code.trim().toUpperCase());
    }

    /**
     * 解析历史写法：空串返回 null；能通过别名归一的返回定义；完全无法识别返回 null。
     * 不会猜测维度。
     */
    public static UnitDef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase();
        UnitDef direct = REGISTRY.get(key);
        if (direct != null) {
            return direct;
        }
        String canonical = ALIASES.get(key);
        return canonical == null ? null : REGISTRY.get(canonical.toUpperCase());
    }

    /** 该写法是否是可识别单位（含别名） */
    public static boolean isParseable(String raw) {
        return parse(raw) != null;
    }

    public BigDecimal toBase(BigDecimal quantity) {
        return quantity.multiply(factorToBase);
    }

    /** 基准单位数量换算回本单位 */
    public BigDecimal fromBase(BigDecimal baseQuantity) {
        return baseQuantity.divide(factorToBase, 6, java.math.RoundingMode.HALF_UP);
    }

    /** 规范码：识别不了返回 null */
    public static String canonicalCode(String raw) {
        UnitDef d = parse(raw);
        return d == null ? null : d.code;
    }
}
