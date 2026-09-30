package com.brewery.craft.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.brewery.craft.common.ApiResponse;
import com.brewery.craft.dto.QcDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.service.QcService;

import jakarta.validation.Valid;

/**
 * 模块六：批次质检放行。
 * 送检 → 检验中 → 放行/驳回；放行可撤销（必填原因）；复检永远开新轮，历史不可改。
 */
@RestController
@RequestMapping("/api/batches/{batchId}/qc")
public class QcController {

    private final QcService qcService;

    public QcController(QcService qcService) {
        this.qcService = qcService;
    }

    /** 质检档案：当前有效状态 + 历史轮次 + 操作流水 */
    @GetMapping
    public ApiResponse<Views.BatchQcView> profile(@PathVariable("batchId") Long batchId) {
        return ApiResponse.success(qcService.qcProfile(batchId));
    }

    /** 发起一轮送检（首检/复检）。带 clientToken 的重复提交按幂等处理。 */
    @PostMapping("/rounds")
    public ApiResponse<Views.BatchQcView> openRound(@PathVariable("batchId") Long batchId,
                                                    @Valid @RequestBody QcDtos.OpenRoundReq req) {
        return ApiResponse.success("已发起质检送检", qcService.openRound(batchId, req));
    }

    /** 下结论：PASS 放行 / REJECT 驳回 */
    @PostMapping("/rounds/{roundId}/decision")
    public ApiResponse<Views.BatchQcView> decide(@PathVariable("batchId") Long batchId,
                                                 @PathVariable("roundId") Long roundId,
                                                 @Valid @RequestBody QcDtos.DecideReq req) {
        String msg = "PASS".equals(req.getDecision()) ? "已放行，下游灌装/出货解锁" : "已驳回，下游灌装/出货已阻止并冻结未发出货";
        return ApiResponse.success(msg, qcService.decide(batchId, roundId, req));
    }

    /** 撤销放行（必须填原因，撤销前结论保留，未发出货冻结） */
    @PostMapping("/rounds/{roundId}/revoke")
    public ApiResponse<Views.BatchQcView> revoke(@PathVariable("batchId") Long batchId,
                                                 @PathVariable("roundId") Long roundId,
                                                 @Valid @RequestBody QcDtos.RevokeReq req) {
        return ApiResponse.success("放行已撤销，未发出货单已冻结", qcService.revoke(batchId, roundId, req));
    }
}
