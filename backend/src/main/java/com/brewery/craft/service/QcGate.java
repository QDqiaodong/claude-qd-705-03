package com.brewery.craft.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.Bottling;
import com.brewery.craft.domain.QcRound;
import com.brewery.craft.domain.Shipment;
import com.brewery.craft.repository.BottlingRepository;
import com.brewery.craft.repository.QcRoundRepository;
import com.brewery.craft.repository.ShipmentRepository;

/**
 * 质检放行 → 灌装/出货 的统一约束闸门。
 *
 * 所有“能不能继续往下游走”的判断都收敛在这里，保证页面提示和后端拦截是同一套口径：
 *   1. 批次未报废；
 *   2. 批次处于可成熟/已成熟阶段（熟成及以后才能灌装）；
 *   3. 最新一轮质检【明确放行】，且轮次里带了必要的温度/糖度测量；
 *   4. 出货还要能找到同包装形式的灌装记录且未超出灌装量。
 *
 * 调用方必须先拿 {@code BatchRepository#findByIdForUpdate} 的批次行写锁，
 * 再调用本类的断言方法 —— 放行/撤销与下游推进因此被串行化。
 */
@Service
public class QcGate {

    /** 质检对下游的有效状态码（由最新轮次推导，不在批次上存标志位） */
    public static final String NONE = "NONE";             // 从未送检
    public static final String PENDING = "PENDING";       // 首轮检验中
    public static final String REINSPECT = "REINSPECT";   // 第 2+ 轮检验中
    public static final String PASS = "PASS";
    public static final String REJECT = "REJECT";
    public static final String REVOKED = "REVOKED";

    private final QcRoundRepository qcRoundRepository;
    private final BottlingRepository bottlingRepository;
    private final ShipmentRepository shipmentRepository;

    public QcGate(QcRoundRepository qcRoundRepository,
                  BottlingRepository bottlingRepository,
                  ShipmentRepository shipmentRepository) {
        this.qcRoundRepository = qcRoundRepository;
        this.bottlingRepository = bottlingRepository;
        this.shipmentRepository = shipmentRepository;
    }

    /** 当前有效质检状态（只读视图，列表/详情共用） */
    @Transactional(readOnly = true)
    public QcState stateOf(Batch b) {
        if (b == null) {
            return new QcState(NONE, null, "批次不存在", 0);
        }
        Optional<QcRound> latest = qcRoundRepository.findLatestByBatchId(b.getId());
        long roundCount = qcRoundRepository.countByBatchId(b.getId());
        return evaluate(b, latest.orElse(null), roundCount);
    }

    /** 加锁事务内复用已查出的最新轮次，避免重复查询 */
    public QcState evaluate(Batch b, QcRound latest, long roundCount) {
        if (latest == null) {
            return new QcState(NONE, null, "尚未送检，须先发起质检并取得放行", roundCount);
        }
        switch (latest.getStatus()) {
            case "PENDING":
                if (latest.getRoundSeq() != null && latest.getRoundSeq() > 1) {
                    return new QcState(REINSPECT, latest,
                            "第 " + latest.getRoundSeq() + " 轮复检中，尚未出具结论", roundCount);
                }
                return new QcState(PENDING, latest, "首轮质检尚未出具结论", roundCount);
            case "PASS":
                return new QcState(PASS, latest,
                        "第 " + latest.getRoundSeq() + " 轮已由 " + nv(latest.getDecidedBy()) + " 放行", roundCount);
            case "REJECT":
                return new QcState(REJECT, latest,
                        "第 " + latest.getRoundSeq() + " 轮质检驳回：" + nv(latest.getConclusionNote()), roundCount);
            case "REVOKED":
                return new QcState(REVOKED, latest,
                        "第 " + latest.getRoundSeq() + " 轮放行已被" + nv(latest.getRevokedBy())
                                + "撤销：" + nv(latest.getRevokeReason()), roundCount);
            default:
                return new QcState(NONE, latest, "质检状态未知", roundCount);
        }
    }

    /** 灌装准入校验（调用方须持批次行锁） */
    public Check checkBottling(Batch b, QcState state) {
        Check base = checkReleased(b, state);
        if (!base.allowed) {
            return base;
        }
        if (!hasRequiredReadings(state.latestRound)) {
            return Check.deny("放行轮次缺少必要的逐日温度/糖度测量记录，不能灌装");
        }
        return Check.allow();
    }

    /** 出货准入校验（调用方须持批次行锁） */
    public Check checkShipmentCreate(Batch b, QcState state, String packageForm, Integer quantity) {
        Check base = checkBottling(b, state);
        if (!base.allowed) {
            return base;
        }
        List<Bottling> bottlings = bottlingRepository
                .findAllByBatchNoAndPackageFormOrderByIdAsc(b.getBatchNo(), packageForm);
        if (bottlings.isEmpty()) {
            return Check.deny("批次 " + b.getBatchNo() + " 没有 " + packageFormName(packageForm)
                    + " 包装的灌装记录，出货必须基于已有的灌装单");
        }
        int bottled = bottlings.stream().mapToInt(Bottling::getQuantity).sum();
        int reserved = shipmentRepository.findAllByBatchNoOrderByIdAsc(b.getBatchNo()).stream()
                // FROZEN 仍占用灌装量（不能用同一批货再开新单）；仅未冻结的“幽灵单”才不计
                .filter(s -> packageForm.equals(s.getPackageForm()))
                .mapToInt(Shipment::getQuantity).sum();
        if (quantity != null && quantity > 0 && reserved + quantity > bottled) {
            return Check.deny("超出该批次" + packageFormName(packageForm) + "灌装量：已灌装 " + bottled
                    + "，待发/冻结/已出 " + reserved + "，本次 " + quantity);
        }
        return Check.allow();
    }

    /** 放行是灌装/出货的共同前提：未报废 + 已熟成 + 最新轮明确放行 */
    private Check checkReleased(Batch b, QcState state) {
        if ("SCRAPPED".equals(b.getStatus())) {
            return Check.deny("批次已报废，不能再灌装或出货");
        }
        if (!"CONDITION".equals(b.getStatus()) && !"PACKAGED".equals(b.getStatus())) {
            return Check.deny("批次当前为「" + batchStageName(b.getStatus())
                    + "」阶段，须熟成并通过质检后才能灌装/出货");
        }
        switch (state.code) {
            case PASS:
                return Check.allow();
            case NONE:
                return Check.deny("批次尚未质检放行，须先发起质检并取得明确放行结论");
            case PENDING:
                return Check.deny("首轮质检尚未出具结论，暂不能灌装/出货");
            case REINSPECT:
                return Check.deny("批次复检中（" + state.reason + "），暂不能灌装/出货");
            case REJECT:
                return Check.deny("批次质检已驳回：" + nv(state.latestRound.getConclusionNote()));
            case REVOKED:
                return Check.deny("批次放行已撤销：" + nv(state.latestRound.getRevokeReason())
                        + "；须重新送检并放行后才能继续");
            default:
                return Check.deny("质检结论不明确，暂不能灌装/出货");
        }
    }

    /** 推进到「已灌装」的前提：明确放行 + 必要测量 + 已有灌装单 */
    public void assertCanPackage(Batch b, QcState state) {
        if (!PASS.equals(state.code)) {
            checkReleased(b, state).assertAllowed();
        }
        if (!hasRequiredReadings(state.latestRound)) {
            throw new BizException("放行轮次缺少必要的逐日温度/糖度测量记录，不能灌装");
        }
        if (bottlingRepository.findAllByBatchNoOrderByIdAsc(b.getBatchNo()).isEmpty()) {
            throw new BizException("批次尚无任何灌装记录，不能直接标记为已灌装");
        }
    }

    public void assertBottling(Batch b, QcState state) {
        checkBottling(b, state).assertAllowed();
    }

    public void assertShipmentCreate(Batch b, QcState state, String packageForm, Integer quantity) {
        checkShipmentCreate(b, state, packageForm, quantity).assertAllowed();
    }

    /** 驳回 / 撤销 / 放行后再次复检：冻结该批次所有尚未发出的出货单 */
    public void freezePending(Batch b, String reason, Integer roundSeq) {
        LocalDateTime now = LocalDateTime.now();
        for (Shipment s : shipmentRepository.findAllByBatchNoOrderByIdAsc(b.getBatchNo())) {
            if ("PENDING".equals(s.getStatus()) || "FROZEN".equals(s.getStatus())) {
                s.setStatus("FROZEN");
                s.setFrozenReason(reason);
                s.setFrozenAt(now);
                s.setFrozenRoundSeq(roundSeq);
                s.setUpdatedAt(now);
                shipmentRepository.save(s);
            }
            // SHIPPED / PAID 是历史单据，一律不动，更不能被“悄悄改写”
        }
    }

    /** 重新放行：把冻结中的出货单恢复为待发（已发/已结款本来就没被动过） */
    public void recoverFrozen(Batch b) {
        LocalDateTime now = LocalDateTime.now();
        for (Shipment s : shipmentRepository.findAllByBatchNoAndStatusOrderByIdAsc(b.getBatchNo(), "FROZEN")) {
            s.setStatus("PENDING");
            s.setFrozenReason(null);
            s.setFrozenAt(null);
            s.setFrozenRoundSeq(null);
            s.setUpdatedAt(now);
            shipmentRepository.save(s);
        }
    }

    /**
     * 已存在的待发出货单【现在能否继续发出】：放行仍有效 + 同包装灌装记录仍在。
     * 与 {@link #checkShipmentCreate} 不同，这里不做容量校验（单据自身已占容量）。
     */
    public Check checkExistingShipmentAdvance(Batch b, QcState state, String packageForm) {
        Check base = checkReleased(b, state);
        if (!base.allowed) {
            return base;
        }
        if (!hasRequiredReadings(state.latestRound)) {
            return Check.deny("放行轮次缺少必要的逐日温度/糖度测量记录，不能出货");
        }
        if (bottlingRepository.findAllByBatchNoAndPackageFormOrderByIdAsc(b.getBatchNo(), packageForm).isEmpty()) {
            return Check.deny("批次 " + b.getBatchNo() + " 缺少 " + packageFormName(packageForm)
                    + " 包装的灌装记录，不能出货");
        }
        return Check.allow();
    }

    // ---------------- 内部 ----------------
    private boolean hasRequiredReadings(QcRound r) {
        if (r == null || r.getReadings() == null || r.getReadings().isEmpty()) {
            return false;
        }
        boolean hasTemp = r.getReadings().stream().anyMatch(x -> x.getTemp() != null);
        boolean hasGravity = r.getReadings().stream().anyMatch(x -> x.getGravity() != null);
        return hasTemp && hasGravity;
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    private static String packageFormName(String code) {
        return switch (code == null ? "" : code) {
            case "BOTTLE" -> "瓶装";
            case "CAN" -> "易拉罐";
            case "KEG" -> "桶装";
            default -> code;
        };
    }

    private static String batchStageName(String code) {
        return switch (code == null ? "" : code) {
            case "PLAN" -> "计划";
            case "MASH" -> "糖化";
            case "BOIL" -> "煮沸";
            case "FERMENT" -> "发酵";
            case "CONDITION" -> "熟成";
            case "PACKAGED" -> "已灌装";
            case "SCRAPPED" -> "报废";
            default -> code;
        };
    }

    /** 推导结果：有效状态码 + 最新轮次 + 面向用户的原因说明 */
    public static class QcState {
        public final String code;
        public final QcRound latestRound;
        public final String reason;
        public final long roundCount;

        public QcState(String code, QcRound latestRound, String reason, long roundCount) {
            this.code = code;
            this.latestRound = latestRound;
            this.reason = reason;
            this.roundCount = roundCount;
        }

        public boolean isPass() {
            return PASS.equals(code);
        }
    }

    public static class Check {
        public final boolean allowed;
        public final String reason;

        private Check(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static Check allow() {
            return new Check(true, "");
        }

        public static Check deny(String reason) {
            return new Check(false, reason);
        }

        public void assertAllowed() {
            if (!allowed) {
                throw new BizException(reason);
            }
        }
    }
}
