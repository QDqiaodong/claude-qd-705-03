package com.brewery.craft.migration;

import java.sql.ResultSet;
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

import com.brewery.craft.service.MaintenanceService;

/**
 * 模块七历史兼容迁移（启动时幂等执行，MySQL 与 H2 测试库同一路径）：
 * <ol>
 *   <li>batch 补列 planned_tank_no / planned_enter_at；</li>
 *   <li>建维护窗口、维护流水、迁罐方案、迁罐分步、迁罐流水、目标罐预留、批次历史罐号七张表；</li>
 *   <li>把现有在罐/已出罐批次按当前 fermenter_no 回填一段历史罐号（只回填没有任何履历的批次）；</li>
 *   <li>种子/历史方案的占位条件指纹按当前真实数据重算一次（幂等，运行期方案指纹正确则跳过）。</li>
 * </ol>
 * 全新库 schema.sql 已含结构和种子，本迁移对其幂等无副作用；旧库升级后结构与新库收敛一致。
 */
@Component
@Order(30)
public class MaintenanceMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceMigrationRunner.class);

    private final JdbcTemplate jdbc;
    private final MaintenanceService maintenanceService;

    public MaintenanceMigrationRunner(JdbcTemplate jdbc, MaintenanceService maintenanceService) {
        this.jdbc = jdbc;
        this.maintenanceService = maintenanceService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureSchema();
        int backfilled = backfillTankHistory();
        int rehashed = maintenanceService.rehashPendingPlanFingerprints();
        if (backfilled > 0 || rehashed > 0) {
            log.info("[维护迁移] 回填历史罐号履历 {} 个批次；重算迁罐方案指纹 {} 条", backfilled, rehashed);
        }
    }

    private void ensureSchema() {
        addColumnIfMissing("batch", "planned_tank_no",
                "ALTER TABLE batch ADD COLUMN planned_tank_no VARCHAR(16) COMMENT '计划罐号'");
        addColumnIfMissing("batch", "planned_enter_at",
                "ALTER TABLE batch ADD COLUMN planned_enter_at DATETIME COMMENT '计划入罐时间'");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fermenter_maintenance (
                    id                  BIGINT       NOT NULL AUTO_INCREMENT,
                    tank_no             VARCHAR(16)  NOT NULL,
                    maint_type          VARCHAR(16)  NOT NULL,
                    status              VARCHAR(16)  NOT NULL,
                    start_at            TIMESTAMP    NOT NULL,
                    expected_recover_at TIMESTAMP    NOT NULL,
                    actual_recover_at   TIMESTAMP,
                    reason              VARCHAR(512) NOT NULL,
                    impact_scope        VARCHAR(512),
                    created_by          VARCHAR(32)  NOT NULL,
                    created_at          TIMESTAMP    NOT NULL,
                    updated_at          TIMESTAMP    NOT NULL,
                    version             INT          NOT NULL DEFAULT 0,
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fermenter_maintenance_event (
                    id               BIGINT       NOT NULL AUTO_INCREMENT,
                    maintenance_id   BIGINT       NOT NULL,
                    tank_no          VARCHAR(16)  NOT NULL,
                    event_type       VARCHAR(24)  NOT NULL,
                    from_start_at    TIMESTAMP,
                    to_start_at      TIMESTAMP,
                    from_recover_at  TIMESTAMP,
                    to_recover_at    TIMESTAMP,
                    reason           VARCHAR(512),
                    operator         VARCHAR(32)  NOT NULL,
                    created_at       TIMESTAMP    NOT NULL,
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tank_transfer_plan (
                    id               BIGINT        NOT NULL AUTO_INCREMENT,
                    maintenance_id   BIGINT        NOT NULL,
                    batch_id         BIGINT        NOT NULL,
                    batch_no         VARCHAR(24)   NOT NULL,
                    source_tank_no   VARCHAR(16)   NOT NULL,
                    target_tank_no   VARCHAR(16)   NOT NULL,
                    batch_stage      VARCHAR(16)   NOT NULL,
                    batch_volume_l   DECIMAL(10,2),
                    need_temp_min    DECIMAL(5,2),
                    need_temp_max    DECIMAL(5,2),
                    planned_move_at  TIMESTAMP     NOT NULL,
                    reserve_from     TIMESTAMP     NOT NULL,
                    reserve_to       TIMESTAMP     NOT NULL,
                    status           VARCHAR(16)   NOT NULL,
                    completed_step   INT           NOT NULL DEFAULT 0,
                    failed_step      INT,
                    fail_reason      VARCHAR(512),
                    attempt_count    INT           NOT NULL DEFAULT 0,
                    expired_reason   VARCHAR(512),
                    conditions_hash  VARCHAR(64)   NOT NULL,
                    proposed_by      VARCHAR(32)   NOT NULL,
                    proposed_at      TIMESTAMP     NOT NULL,
                    confirmed_by     VARCHAR(32),
                    confirmed_at     TIMESTAMP,
                    executed_at      TIMESTAMP,
                    created_at       TIMESTAMP     NOT NULL,
                    updated_at       TIMESTAMP     NOT NULL,
                    version          INT           NOT NULL DEFAULT 0,
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tank_transfer_step (
                    id          BIGINT       NOT NULL AUTO_INCREMENT,
                    plan_id     BIGINT       NOT NULL,
                    attempt_seq INT          NOT NULL,
                    step_seq    INT          NOT NULL,
                    step_code   VARCHAR(24)  NOT NULL,
                    status      VARCHAR(16)  NOT NULL,
                    started_at  TIMESTAMP,
                    finished_at TIMESTAMP,
                    note        VARCHAR(512),
                    operator    VARCHAR(32),
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tank_transfer_event (
                    id           BIGINT       NOT NULL AUTO_INCREMENT,
                    plan_id      BIGINT       NOT NULL,
                    batch_id     BIGINT       NOT NULL,
                    batch_no     VARCHAR(24)  NOT NULL,
                    event_type   VARCHAR(24)  NOT NULL,
                    step_seq     INT,
                    attempt_seq  INT,
                    from_tank_no VARCHAR(16),
                    to_tank_no   VARCHAR(16),
                    detail       VARCHAR(512),
                    operator     VARCHAR(32)  NOT NULL,
                    created_at   TIMESTAMP    NOT NULL,
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tank_transfer_reservation (
                    id             BIGINT       NOT NULL AUTO_INCREMENT,
                    plan_id        BIGINT       NOT NULL,
                    batch_id       BIGINT       NOT NULL,
                    batch_no       VARCHAR(24)  NOT NULL,
                    tank_no        VARCHAR(16)  NOT NULL,
                    reserve_from   TIMESTAMP    NOT NULL,
                    reserve_to     TIMESTAMP    NOT NULL,
                    status         VARCHAR(16)  NOT NULL,
                    created_at     TIMESTAMP    NOT NULL,
                    released_at    TIMESTAMP,
                    release_reason VARCHAR(24),
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS batch_tank_history (
                    id               BIGINT       NOT NULL AUTO_INCREMENT,
                    batch_id         BIGINT       NOT NULL,
                    batch_no         VARCHAR(24)  NOT NULL,
                    stage            VARCHAR(16),
                    tank_no          VARCHAR(16)  NOT NULL,
                    entered_at       TIMESTAMP    NOT NULL,
                    left_at          TIMESTAMP,
                    enter_reason     VARCHAR(24),
                    transfer_plan_id BIGINT,
                    leave_reason     VARCHAR(24),
                    PRIMARY KEY (id)
                )
                """);
    }

    /** 历史批次罐号回填：还没有任何履历段、且当前/曾经有罐号的批次各补一段 ASSIGN */
    private int backfillTankHistory() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id AS id, b.batch_no AS batch_no, b.status AS status,
                       b.fermenter_no AS tank_no, b.ferment_start AS ferment_start,
                       b.mash_start AS mash_start, b.brew_date AS brew_date
                FROM batch b
                LEFT JOIN (SELECT DISTINCT batch_id FROM batch_tank_history) h ON h.batch_id = b.id
                WHERE b.fermenter_no IS NOT NULL AND h.batch_id IS NULL
                ORDER BY b.id
                """);
        int done = 0;
        for (Map<String, Object> row : rows) {
            Object entered = row.get("ferment_start");
            if (entered == null) {
                entered = row.get("mash_start");
            }
            if (entered == null) {
                entered = row.get("brew_date");
            }
            if (entered == null) {
                entered = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
            }
            String status = (String) row.get("status");
            boolean ended = "PACKAGED".equals(status) || "SCRAPPED".equals(status);
            jdbc.update("""
                            INSERT INTO batch_tank_history
                            (batch_id, batch_no, stage, tank_no, entered_at, left_at, enter_reason, leave_reason)
                            VALUES (?, ?, ?, ?, ?, ?, 'ASSIGN', ?)
                            """,
                    row.get("id"), row.get("batch_no"), status, row.get("tank_no"),
                    entered, ended ? entered : null, ended ? "RELEASE" : null);
            done++;
        }
        return done;
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (columnExists(table, column)) {
            return;
        }
        try {
            jdbc.execute(ddl);
        } catch (Exception e) {
            if (!columnExists(table, column)) {
                throw e;
            }
        }
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection con) -> {
            try (ResultSet rs = con.getMetaData()
                    .getColumns(con.getCatalog(), con.getSchema(), table, column)) {
                return rs.next();
            }
        }));
    }
}
