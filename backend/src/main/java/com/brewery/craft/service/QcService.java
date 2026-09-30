package com.brewery.craft.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Batch;
import com.brewery.craft.domain.QcAction;
import com.brewery.craft.domain.QcReading;
import com.brewery.craft.domain.QcRound;
import com.brewery.craft.dto.QcDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.BatchRepository;
import com.brewery.craft.repository.QcActionRepository;
import com.brewery.craft.repository.QcRoundRepository;

/**
 * 模块六：质检轮次流转。
 *
 * 历史不可篡改 vs 当前可操作状态的取舍：
 *   - 每轮送检把批次状态与逐日温度/糖度【快照】进本轮，已提交内容之后绝不原地修改；
 *   - 复检只能 {@link #openRound} 开出下一轮，旧轮结论与操作流水原样保留；
 *   - “能不能继续生产”永远由【最新一轮】决定（{@link QcGate}）。
 */
@Service
public class QcService {

    private final BatchRepository batchRepository;
    private final QcRoundRepository qcRoundRepository;
    private final QcActionRepository qcActionRepository;
    private final QcGate qcGate;

    public QcService(BatchRepository batchRepository,
                     QcRoundRepository qcRoundRepository,
                     QcActionRepository qcActionRepository,
                     QcGate qcGate) {
        this.batchRepository = batchRepository;
        this.qcRoundRepository = qcRoundRepository;
        this.qcActionRepository = qcActionRepository;
        this.qcGate = qcGate;
    }

    /** 批次质检档案：当前有效状态 + 全部历史轮次 + 操作流水 */
    @Transactional(readOnly = true)
    public Views.BatchQcView qcProfile(Long batchId) {
        Batch b = batchRepository.findById(batchId).orElseThrow(() -> new BizException("批次不存在"));
        QcGate.QcState state = qcGate.stateOf(b);
        List<QcRound> rounds = qcRoundRepository.findAllByBatchIdOrderByRoundSeqAsc(batchId);
        List<QcAction> actions = qcActionRepository.findAllByBatchIdOrderByIdAsc(batchId);
        return Views.BatchQcView.of(b, state, rounds, actions);
    }

    /**
     * 发起一轮送检（首检/复检）。
     * 同一批次同时只能有一轮“检验中”；重复点击/网络重试携带相同 clientToken 时幂等返回首轮。
     */
    @Transactional
    public Views.BatchQcView openRound(Long batchId, QcDtos.OpenRoundReq req) {
        Batch b = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new BizException("批次不存在"));
        if ("SCRAPPED".equals(b.getStatus())) {
            throw new BizException("批次已报废，不能再发起质检");
        }
        if ("PLAN".equals(b.getStatus()) || "MASH".equals(b.getStatus()) || "BOIL".equals(b.getStatus())) {
            throw new BizException("批次还在「" + Views.batchStatusName(b.getStatus())
                    + "」阶段，发酵数据尚未产生，暂不能送检");
        }

        // 幂等令牌：前端应随点击生成；缺失时服务端补一个（仅保证本轮请求，重试请由前端带同 token）
        String clientToken = (req.getClientToken() == null || req.getClientToken().isBlank())
                ? java.util.UUID.randomUUID().toString()
                : req.getClientToken();
        if (req.getClientToken() != null && !req.getClientToken().isBlank()) {
            QcRound existing = qcRoundRepository.findByClientToken(clientToken).orElse(null);
            if (existing != null) {
                if (!existing.getBatchId().equals(batchId)) {
                    throw new BizException("送检令牌与批次不匹配，请刷新后重试");
                }
                return qcProfile(batchId);
            }
        }

        List<QcRound> rounds = qcRoundRepository.findAllByBatchIdOrderByRoundSeqAsc(batchId);
        if (!rounds.isEmpty()) {
            QcRound latest = rounds.get(rounds.size() - 1);
            if ("PENDING".equals(latest.getStatus())) {
                throw new BizException("第 " + latest.getRoundSeq()
                        + " 轮质检尚未出具结论，不能重复送检；重复提交会被拦截");
            }
        }

        // 批次必须已经有逐日温度和糖度记录（必要测量记录）
        if (b.getTempReadings().isEmpty() || b.getGravityReadings().isEmpty()) {
            throw new BizException("批次缺少逐日温度/糖度测量记录，请先登记测量数据后再送检");
        }

        LocalDateTime now = LocalDateTime.now();
        QcRound r = new QcRound();
        r.setBatchId(b.getId());
        r.setBatchNo(b.getBatchNo());
        r.setRoundSeq(rounds.size() + 1);
        r.setStatus("PENDING");
        r.setBatchStatusSnapshot(b.getStatus());
        r.setSubmittedBy(req.getOperator());
        r.setSubmittedAt(now);
        r.setClientToken(clientToken);
        // 注意：version 是 @Version，交给 Hibernate 初始化（手动 set 0 会被当成游离对象走 UPDATE）
        r.setReadings(snapshotReadings(b, req));
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        qcRoundRepository.saveAndFlush(r);

        // 放行后再次发起复检：先冻结未发出货单（通过新结论再恢复）
        QcRound latest = rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
        if (latest != null && "PASS".equals(latest.getStatus())) {
            qcGate.freezePending(b,
                    "批次 " + b.getBatchNo() + " 第 " + r.getRoundSeq() + " 轮复检已送检，等待新结论",
                    r.getRoundSeq());
        }

        addAction(r, "SUBMIT", "PENDING", req.getOperator(), req.getNote(), now);
        b.setUpdatedAt(now);
        batchRepository.save(b);
        return qcProfile(batchId);
    }

    /** 下结论：PASS 放行 / REJECT 驳回。驳回必须有原因；放行需要酒精度与感官结论。 */
    @Transactional
    public Views.BatchQcView decide(Long batchId, Long roundId, QcDtos.DecideReq req) {
        String decision = req.getDecision() == null ? "" : req.getDecision();
        if (!"PASS".equals(decision) && !"REJECT".equals(decision)) {
            throw new BizException("结论取值非法，只能是 PASS（放行）或 REJECT（驳回）");
        }
        QcRound r = qcRoundRepository.findById(roundId)
                .orElseThrow(() -> new BizException("质检轮次不存在"));
        if (!r.getBatchId().equals(batchId)) {
            throw new BizException("质检轮次不属于该批次");
        }
        Batch b = batchRepository.findByIdForUpdate(r.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        if (!r.getStatus().equals("PENDING")) {
            throw new BizException("该轮质检已出具结论（" + statusText(r.getStatus())
                    + "），结论不可原地修改；如需变更请发起新一轮复检");
        }
        if (req.getExpectedVersion() != null && r.getVersion() != null
                && !r.getVersion().equals(req.getExpectedVersion())) {
            throw new BizException("页面数据已过期（轮次版本 " + r.getVersion()
                    + "，提交基于 " + req.getExpectedVersion() + "），请刷新后重新下结论");
        }
        if (req.getSensory() == null || req.getSensory().isBlank()) {
            throw new BizException("感官结论不能为空");
        }
        if ("REJECT".equals(decision) && (req.getReason() == null || req.getReason().isBlank())) {
            throw new BizException("驳回必须填写原因");
        }
        if ("PASS".equals(decision)) {
            boolean hasTemp = r.getReadings().stream().anyMatch(x -> x.getTemp() != null);
            boolean hasGravity = r.getReadings().stream().anyMatch(x -> x.getGravity() != null);
            if (!hasTemp || !hasGravity) {
                throw new BizException("放行轮次必须包含逐日温度和糖度测量记录");
            }
            if (req.getAbv() == null) {
                throw new BizException("放行必须记录检验酒精度（ABV）");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        r.setStatus(decision);
        r.setAbv(req.getAbv());
        r.setSensory(req.getSensory());
        r.setConclusionNote(req.getReason());
        r.setDecidedBy(req.getOperator());
        r.setDecidedAt(now);
        r.setUpdatedAt(now);
        try {
            qcRoundRepository.saveAndFlush(r);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("该轮质检刚被其他人更新，请刷新后重试");
        }

        if ("PASS".equals(decision)) {
            // 重新放行 → 恢复此前冻结的未发出货单
            qcGate.recoverFrozen(b);
        } else {
            qcGate.freezePending(b,
                    "批次 " + b.getBatchNo() + " 第 " + r.getRoundSeq() + " 轮质检驳回：" + req.getReason(),
                    r.getRoundSeq());
        }
        addAction(r, decision, decision, req.getOperator(), req.getReason(), now);
        b.setUpdatedAt(now);
        batchRepository.save(b);
        return qcProfile(b.getId());
    }

    /** 撤销放行：必须填原因；撤销前的 PASS 结论与流水全部保留，只能靠新轮次重新放行。 */
    @Transactional
    public Views.BatchQcView revoke(Long batchId, Long roundId, QcDtos.RevokeReq req) {
        QcRound r = qcRoundRepository.findById(roundId)
                .orElseThrow(() -> new BizException("质检轮次不存在"));
        if (!r.getBatchId().equals(batchId)) {
            throw new BizException("质检轮次不属于该批次");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("撤销放行必须填写原因");
        }
        Batch b = batchRepository.findByIdForUpdate(r.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        if (!"PASS".equals(r.getStatus())) {
            throw new BizException("只有“已放行”的轮次可以撤销，当前结论为" + statusText(r.getStatus()));
        }
        if (req.getExpectedVersion() != null && r.getVersion() != null
                && !r.getVersion().equals(req.getExpectedVersion())) {
            throw new BizException("页面数据已过期，请刷新后再撤销");
        }

        LocalDateTime now = LocalDateTime.now();
        r.setStatus("REVOKED");
        r.setRevokedBy(req.getOperator());
        r.setRevokedAt(now);
        r.setRevokeReason(req.getReason());
        r.setUpdatedAt(now);
        try {
            qcRoundRepository.saveAndFlush(r);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("该轮质检刚被其他人更新，请刷新后重试");
        }
        qcGate.freezePending(b,
                "批次 " + b.getBatchNo() + " 第 " + r.getRoundSeq() + " 轮放行已撤销：" + req.getReason(),
                r.getRoundSeq());
        addAction(r, "REVOKE", "REVOKED", req.getOperator(), req.getReason(), now);
        b.setUpdatedAt(now);
        batchRepository.save(b);
        return qcProfile(b.getId());
    }

    // ---------------- 内部 ----------------

    /**
     * 送检快照：默认取批次当前曲线；送检人手工补充的摘要合并进来。
     * 快照只存在本轮，后续批次曲线增删不影响本轮历史。
     */
    private List<QcReading> snapshotReadings(Batch b, QcDtos.OpenRoundReq req) {
        Map<String, QcReading> merged = new LinkedHashMap<>();
        for (Map.Entry<String, java.math.BigDecimal> e : b.getTempReadings().entrySet()) {
            QcReading r = new QcReading();
            r.setDayKey(e.getKey());
            r.setTemp(e.getValue());
            merged.put(e.getKey(), r);
        }
        for (Map.Entry<String, java.math.BigDecimal> e : b.getGravityReadings().entrySet()) {
            merged.computeIfAbsent(e.getKey(), k -> {
                QcReading r = new QcReading();
                r.setDayKey(k);
                return r;
            }).setGravity(e.getValue());
        }
        if (req.getReadings() != null) {
            for (QcDtos.Reading x : req.getReadings()) {
                if (x.getDayKey() == null || x.getDayKey().isBlank()) {
                    continue;
                }
                QcReading r = merged.computeIfAbsent(x.getDayKey(), k -> {
                    QcReading nr = new QcReading();
                    nr.setDayKey(k);
                    return nr;
                });
                if (x.getTemp() != null) {
                    r.setTemp(x.getTemp());
                }
                if (x.getGravity() != null) {
                    r.setGravity(x.getGravity());
                }
            }
        }
        List<QcReading> list = new ArrayList<>(merged.values());
        list.sort((a, c) -> Integer.compare(Views.dayKeyOrder(a.getDayKey()), Views.dayKeyOrder(c.getDayKey())));
        return list;
    }

    private void addAction(QcRound r, String action, String conclusion, String operator, String reason,
                           LocalDateTime at) {
        QcAction a = new QcAction();
        a.setRoundId(r.getId());
        a.setBatchId(r.getBatchId());
        a.setRoundSeq(r.getRoundSeq());
        a.setAction(action);
        a.setConclusion(conclusion);
        a.setOperator(operator);
        a.setReason(reason);
        a.setCreatedAt(at);
        qcActionRepository.save(a);
    }

    private String statusText(String s) {
        return switch (s) {
            case "PENDING" -> "检验中";
            case "PASS" -> "已放行";
            case "REJECT" -> "已驳回";
            case "REVOKED" -> "已撤销";
            default -> s;
        };
    }
}
