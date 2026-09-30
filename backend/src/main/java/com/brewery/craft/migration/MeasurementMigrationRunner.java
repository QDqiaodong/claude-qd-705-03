package com.brewery.craft.migration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.measure.ResolvedQuantity;

/**
 * 历史兼容迁移（启动时幂等执行，MySQL 与 H2 测试库都走这一条路径，保证迁移与建库收敛到同一结构）：
 * <ol>
 *   <li>为旧库补列：recipe.base_batch_l / recipe.version，
 *       recipe_item.dimension / normalized_unit / normalized_quantity / unit_status / unit_note；</li>
 *   <li>建批次用量快照表 batch_recipe_item；</li>
 *   <li>对所有历史 recipe_item 按原料档案/类别推导单位并回填规范化列：
 *       旧写法（KG、千克…）归一、空单位推导，无法可靠推导的保留原值并置 NEEDS_REVIEW，绝不静默乱算；</li>
 *   <li>已有批次（快照表为空）从其当前配方回填一份快照，snapshot_source=MIGRATED 明示「历史回填」；
 *       配方新规则此后只影响新批次，已生产批次永不重算。</li>
 * </ol>
 * 全新库（schema.sql 已含新结构且数据已落）执行同样的推导逻辑一次，结果与历史库一致。
 */
@Component
@Order(20)
public class MeasurementMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MeasurementMigrationRunner.class);

    private final JdbcTemplate jdbc;

    public MeasurementMigrationRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureSchema();
        int normalizedRows = normalizeRecipeItems();
        int backfilledBatches = backfillBatchSnapshots();
        if (normalizedRows > 0 || backfilledBatches > 0) {
            log.info("[计量迁移] 规范化配方明细 {} 条；回填历史批次快照 {} 个", normalizedRows, backfilledBatches);
        }
    }

    // ---------------- DDL 兼容（列/表已存在则跳过） ----------------

    private void ensureSchema() {
        addColumnIfMissing("recipe", "base_batch_l",
                "ALTER TABLE recipe ADD COLUMN base_batch_l DECIMAL(10,2) COMMENT '基准批量（L），缩放分母'");
        addColumnIfMissing("recipe", "version",
                "ALTER TABLE recipe ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本'");
        addColumnIfMissing("recipe_item", "dimension",
                "ALTER TABLE recipe_item ADD COLUMN dimension VARCHAR(16) COMMENT 'MASS/VOLUME/COUNT'");
        addColumnIfMissing("recipe_item", "normalized_unit",
                "ALTER TABLE recipe_item ADD COLUMN normalized_unit VARCHAR(8) COMMENT '规范化单位码'");
        addColumnIfMissing("recipe_item", "normalized_quantity",
                "ALTER TABLE recipe_item ADD COLUMN normalized_quantity DECIMAL(14,3) COMMENT '基准单位数量 g/mL/pkg'");
        addColumnIfMissing("recipe_item", "unit_status",
                "ALTER TABLE recipe_item ADD COLUMN unit_status VARCHAR(16) COMMENT 'OK/NORMALIZED/INFERRED/NEEDS_REVIEW'");
        addColumnIfMissing("recipe_item", "unit_note",
                "ALTER TABLE recipe_item ADD COLUMN unit_note VARCHAR(256) COMMENT '单位推导/归一说明'");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS batch_recipe_item (
                    id                  BIGINT       NOT NULL AUTO_INCREMENT,
                    batch_id            BIGINT       NOT NULL,
                    stage               VARCHAR(24)  NOT NULL,
                    ingredient_code     VARCHAR(24)  NOT NULL,
                    ingredient_name     VARCHAR(64),
                    ingredient_type     VARCHAR(16),
                    quantity            DECIMAL(10,3) NOT NULL,
                    unit                VARCHAR(8),
                    dimension           VARCHAR(16),
                    normalized_unit     VARCHAR(8),
                    normalized_quantity DECIMAL(14,3),
                    unit_status         VARCHAR(16),
                    unit_note           VARCHAR(256),
                    snapshot_source     VARCHAR(16)  NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_batch_recipe_item_batch (batch_id)
                )
                """);
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (columnExists(table, column)) {
            return;
        }
        try {
            jdbc.execute(ddl);
        } catch (Exception e) {
            // 并发首启或方言差异下列可能已被建立；再查一次确认
            if (!columnExists(table, column)) {
                throw e;
            }
        }
    }

    private boolean columnExists(String table, String column) {
        // JDBC 元数据探测，MySQL / H2 通用，避免依赖 INFORMATION_SCHEMA 方言差异
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection con) -> {
            try (ResultSet rs = con.getMetaData()
                    .getColumns(con.getCatalog(), con.getSchema(), table, column)) {
                return rs.next();
            }
        }));
    }

    // ---------------- 历史单位推导 + 规范化回填 ----------------

    private int normalizeRecipeItems() {
        // 原料档案：code → (type, unit)
        Map<String, String[]> ingredientIndex = new HashMap<>();
        jdbc.query("SELECT code, type, unit FROM ingredient", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                ingredientIndex.put(rs.getString("code"),
                        new String[]{rs.getString("type"), rs.getString("unit")}));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, ingredient_code, ingredient_type, quantity, unit, unit_status FROM recipe_item");
        int touched = 0;
        for (Map<String, Object> row : rows) {
            String status = (String) row.get("unit_status");
            // 幂等：已推导过的行不重算，避免覆盖后续人工修正
            if ("OK".equals(status) || "NORMALIZED".equals(status)
                    || "INFERRED".equals(status) || "DIM_MISMATCH".equals(status)) {
                continue;
            }
            Long id = ((Number) row.get("id")).longValue();
            String code = (String) row.get("ingredient_code");
            String type = (String) row.get("ingredient_type");
            BigDecimal qty = asDecimal(row.get("quantity"));
            String rawUnit = (String) row.get("unit");
            String[] master = ingredientIndex.get(code);
            String masterUnit = master == null ? null : master[1];
            String typeForResolve = type != null ? type : (master == null ? null : master[0]);

            ResolvedQuantity rq = ResolvedQuantity.resolve(typeForResolve, qty, rawUnit, masterUnit);
            if (rq.dimension() != null) {
                jdbc.update("UPDATE recipe_item SET dimension=?, normalized_unit=?, normalized_quantity=?, "
                                + "unit_status=?, unit_note=? WHERE id=?",
                        rq.dimension().name(), rq.displayUnit(),
                        rq.baseQuantity().setScale(3, RoundingMode.HALF_UP),
                        rq.status(), rq.resolutionNote(), id);
            } else {
                jdbc.update("UPDATE recipe_item SET dimension=NULL, normalized_unit=NULL, normalized_quantity=NULL, "
                                + "unit_status=?, unit_note=? WHERE id=?",
                        ResolvedQuantity.NEEDS_REVIEW, rq.resolutionNote(), id);
            }
            touched++;
        }

        // 历史配方缺基准批量 → 1000L 兜底
        jdbc.update("UPDATE recipe SET base_batch_l = 1000.00 WHERE base_batch_l IS NULL OR base_batch_l <= 0");
        return touched;
    }

    // ---------------- 历史批次快照回填（每个批次只回填一次） ----------------

    private int backfillBatchSnapshots() {
        // 逐批次判断：只回填「还没有任何快照行」的批次，幂等且不漏（首启时配方为空、之后补了明细的批次也能补上）
        List<Long> batchIds = jdbc.queryForList(
                "SELECT b.id FROM batch b "
                        + "LEFT JOIN (SELECT DISTINCT batch_id FROM batch_recipe_item) s ON s.batch_id = b.id "
                        + "WHERE b.recipe_id IS NOT NULL AND s.batch_id IS NULL ORDER BY b.id", Long.class);
        int done = 0;
        for (Long batchId : batchIds) {
            List<Map<String, Object>> items = jdbc.queryForList("""
                    SELECT s.stage AS stage, i.ingredient_code AS c0, i.ingredient_name AS c1,
                           i.ingredient_type AS c2, i.quantity AS c3, i.unit AS c4,
                           i.dimension AS c5, i.normalized_unit AS c6, i.normalized_quantity AS c7,
                           i.unit_status AS c8, i.unit_note AS c9
                    FROM batch b
                    JOIN recipe_item_slot s ON s.recipe_id = b.recipe_id
                    JOIN recipe_item i ON i.id = s.item_id
                    WHERE b.id = ?
                    ORDER BY s.recipe_id, s.item_id
                    """, batchId);
            if (items.isEmpty()) {
                continue;
            }
            for (Map<String, Object> it : items) {
                jdbc.update("""
                                INSERT INTO batch_recipe_item
                                (batch_id, stage, ingredient_code, ingredient_name, ingredient_type,
                                 quantity, unit, dimension, normalized_unit, normalized_quantity,
                                 unit_status, unit_note, snapshot_source)
                                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'MIGRATED')
                                """,
                        batchId, it.get("stage"), it.get("c0"), it.get("c1"), it.get("c2"),
                        asDecimal(it.get("c3")), it.get("c4"), it.get("c5"), it.get("c6"),
                        asDecimal(it.get("c7")), it.get("c8"), it.get("c9"));
            }
            done++;
        }
        return done;
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(v.toString());
    }
}
