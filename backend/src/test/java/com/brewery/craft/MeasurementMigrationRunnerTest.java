package com.brewery.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.brewery.craft.domain.BatchRecipeItem;
import com.brewery.craft.migration.MeasurementMigrationRunner;
import com.brewery.craft.repository.BatchRecipeItemRepository;

/**
 * 历史兼容迁移：旧单位大小写/旧写法归一、空单位推导、未知单位待处理；
 * 已有批次快照回填且标记 MIGRATED；迁移幂等（重复执行不改变结果）。
 */
@SpringBootTest
class MeasurementMigrationRunnerTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeasurementMigrationRunner runner;
    @Autowired private BatchRecipeItemRepository snapshotRepo;

    @Test
    void migratesLegacyUnitsAndBackfillsBatchSnapshotIdempotently() throws Exception {
        // 原料档案（kg / L / pkg）
        jdbc.update("INSERT INTO ingredient (code,name,type,unit,stock,safety_stock,updated_at) "
                + "VALUES ('MG-MALT','迁移麦芽','MALT','kg',1,0,NOW())");
        jdbc.update("INSERT INTO ingredient (code,name,type,unit,stock,safety_stock,updated_at) "
                + "VALUES ('MG-LAC','迁移乳酸','WATER','L',1,0,NOW())");
        jdbc.update("INSERT INTO ingredient (code,name,type,unit,stock,safety_stock,updated_at) "
                + "VALUES ('MG-HOP','迁移陈花','HOP','kg',1,0,NOW())");
        // 旧配方（base_batch_l 留空）+ 旧批次
        jdbc.update("INSERT INTO recipe (id,code,name,style,version,updated_at) "
                + "VALUES (9101,'MR-1','迁移配方','IPA',0,NOW())");
        jdbc.update("INSERT INTO batch (id,batch_no,recipe_id,brewer,brew_date,volume_l,status,updated_at) "
                + "VALUES (9201,'MB-MIG-1',9101,'迁移师',CURRENT_DATE,1000,'PACKAGED',NOW())");

        // 模拟旧形态：规范化列全无、单位大小写/旧写法/空/未知
        insertItem(9301, "MG-MALT", "迁移麦芽", "MALT", "180000", "G");      // 大写克
        insertItem(9302, "MG-LAC", "迁移乳酸", "WATER", "1.2", "ltr");      // 旧写法升
        insertItem(9303, "MG-HOP", "迁移陈花", "HOP", "0.3", "罐");         // 未知
        insertItem(9304, "MG-MALT", "迁移麦芽", "MALT", "2", null);         // 空单位
        jdbc.update("INSERT INTO recipe_item_slot (recipe_id,stage,item_id) VALUES "
                + "(9101,'MASH_IN',9301),(9101,'WATER',9302),(9101,'DRYHOP_1',9303),(9101,'SPARGE',9304)");

        runner.run(null);

        // —— 单位推导结果 ——
        Map<String, Object> g = row(9301);
        assertEquals("MASS", g.get("dimension"));
        assertEquals("g", g.get("normalized_unit"));
        assertEquals("NORMALIZED", g.get("unit_status"));
        assertEquals(0, new BigDecimal("180000.000").compareTo((BigDecimal) g.get("normalized_quantity")));

        Map<String, Object> ltr = row(9302);
        assertEquals("VOLUME", ltr.get("dimension"));
        assertEquals("L", ltr.get("normalized_unit"));
        assertEquals(0, new BigDecimal("1200.000").compareTo((BigDecimal) ltr.get("normalized_quantity")));

        Map<String, Object> bad = row(9303);
        assertEquals("NEEDS_REVIEW", bad.get("unit_status"));
        assertNull(bad.get("dimension"));
        assertNull(bad.get("normalized_quantity"));
        assertEquals("罐", bad.get("unit")); // 原值保留

        Map<String, Object> blank = row(9304);
        assertEquals("INFERRED", blank.get("unit_status"));
        assertEquals("kg", blank.get("normalized_unit"));
        assertEquals(0, new BigDecimal("2000.000").compareTo((BigDecimal) blank.get("normalized_quantity")));

        // 基准批量兜底
        assertEquals(0, new BigDecimal("1000.00")
                .compareTo(jdbc.queryForObject("SELECT base_batch_l FROM recipe WHERE id=9101", BigDecimal.class)));

        // —— 历史批次快照回填并标记 MIGRATED ——
        List<BatchRecipeItem> snap = snapshotRepo.findAllByBatchIdOrderByIdAsc(9201L);
        assertEquals(4, snap.size());
        assertTrue(snap.stream().allMatch(s -> "MIGRATED".equals(s.getSnapshotSource())));
        assertFalse(snap.stream().anyMatch(s -> s.getNormalizedQuantity() == null && !"NEEDS_REVIEW".equals(s.getUnitStatus())));

        // —— 幂等：再跑一次不新增快照、不改状态 ——
        runner.run(null);
        List<BatchRecipeItem> again = snapshotRepo.findAllByBatchIdOrderByIdAsc(9201L);
        assertEquals(4, again.size());
        assertEquals("NEEDS_REVIEW", row(9303).get("unit_status"));
    }

    private void insertItem(long id, String code, String name, String type, String qty, String unit) {
        if (unit == null) {
            jdbc.update("INSERT INTO recipe_item (id,ingredient_code,ingredient_name,ingredient_type,quantity,unit) "
                    + "VALUES (?,?,?,?,?,?)", id, code, name, type, new BigDecimal(qty), null);
        } else {
            jdbc.update("INSERT INTO recipe_item (id,ingredient_code,ingredient_name,ingredient_type,quantity,unit) "
                    + "VALUES (?,?,?,?,?,?)", id, code, name, type, new BigDecimal(qty), unit);
        }
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "SELECT dimension,normalized_unit,normalized_quantity,unit_status,unit FROM recipe_item WHERE id=?", id);
    }
}
