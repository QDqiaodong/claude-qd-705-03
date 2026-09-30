package com.brewery.craft.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.brewery.craft.common.ApiResponse;
import com.brewery.craft.dto.MaintenanceDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.service.MaintenanceService;

import jakarta.validation.Valid;

/**
 * 模块七：发酵罐维护窗口与在制批次迁罐。
 * 没有 DELETE 接口 —— 维护窗口与迁罐方案都只追加/流转，不做物理删除。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    // ---------------- 维护窗口 ----------------

    /** 维护日历：全部窗口（含操作历史），前端按日期范围渲染 */
    @GetMapping("/windows")
    public ApiResponse<List<Views.MaintenanceView>> windows() {
        return ApiResponse.success(maintenanceService.calendar());
    }

    @GetMapping("/windows/{id}")
    public ApiResponse<Views.MaintenanceView> getWindow(@PathVariable("id") Long id) {
        return ApiResponse.success(maintenanceService.get(id));
    }

    @PostMapping("/windows")
    public ApiResponse<Views.MaintenanceView> register(
            @Valid @RequestBody MaintenanceDtos.MaintenanceSaveReq req) {
        return ApiResponse.success("维护窗口已登记", maintenanceService.register(req));
    }

    /** 调整/延期：计划中可改开始+恢复；已开始只能延期恢复时间 */
    @PostMapping("/windows/{id}/adjust")
    public ApiResponse<Views.MaintenanceView> adjust(
            @PathVariable("id") Long id,
            @Valid @RequestBody MaintenanceDtos.MaintenanceAdjustReq req) {
        return ApiResponse.success("维护窗口已更新", maintenanceService.adjust(id, req));
    }

    /** 取消：仅计划中可取消；已开始的维护不能删除 */
    @PostMapping("/windows/{id}/cancel")
    public ApiResponse<Views.MaintenanceView> cancel(
            @PathVariable("id") Long id,
            @Valid @RequestBody MaintenanceDtos.MaintenanceCancelReq req) {
        return ApiResponse.success("维护窗口已取消", maintenanceService.cancel(id, req));
    }

    @PostMapping("/windows/{id}/start")
    public ApiResponse<Views.MaintenanceView> start(
            @PathVariable("id") Long id,
            @Valid @RequestBody MaintenanceDtos.MaintenanceActionReq req) {
        return ApiResponse.success("维护已开始", maintenanceService.markStarted(id, req));
    }

    /** 提前结束：实际恢复时间钉死，后续排程按新窗口更新，已发生的迁罐历史不改写 */
    @PostMapping("/windows/{id}/finish")
    public ApiResponse<Views.MaintenanceView> finish(
            @PathVariable("id") Long id,
            @Valid @RequestBody MaintenanceDtos.MaintenanceActionReq req) {
        return ApiResponse.success("维护已结束", maintenanceService.finishEarly(id, req));
    }

    /** 一条时间线：窗口 + 当前占罐批次 + 未来待入罐批次 + 冲突结论 + 候选目标罐 */
    @GetMapping("/windows/{id}/timeline")
    public ApiResponse<Views.MaintenanceTimelineView> timeline(@PathVariable("id") Long id) {
        return ApiResponse.success(maintenanceService.timeline(id));
    }

    /** 冲突清单：跨全部生效窗口汇总 需要迁罐 / 必须延期 / 无法处理 */
    @GetMapping("/conflicts")
    public ApiResponse<List<Views.TimelineItemView>> conflicts() {
        return ApiResponse.success(maintenanceService.conflictList());
    }

    /** 未来待入罐批次排程（计划罐号/计划入罐时间） */
    @PostMapping("/plan-entry")
    public ApiResponse<Void> planEntry(@Valid @RequestBody MaintenanceDtos.PlannedEnterReq req) {
        maintenanceService.planEntry(req);
        return ApiResponse.success("入罐计划已登记", null);
    }

    // ---------------- 迁罐方案 ----------------

    /** 迁罐预览 + 生成可确认方案：不传 targetTankNo 时系统自动选适配度最高的空闲罐 */
    @PostMapping("/windows/{id}/batches/{batchId}/transfer-proposal")
    public ApiResponse<Views.TransferPlanView> propose(
            @PathVariable("id") Long id,
            @PathVariable("batchId") Long batchId,
            @RequestBody(required = false) MaintenanceDtos.TransferProposeReq req) {
        MaintenanceDtos.TransferProposeReq body = req == null ? new MaintenanceDtos.TransferProposeReq() : req;
        if (body.getOperator() == null || body.getOperator().isBlank()) {
            body.setOperator("设备负责人");
        }
        return ApiResponse.success("迁罐方案已生成，待确认",
                maintenanceService.propose(id, batchId, body));
    }

    /** 确认迁罐：条件快照过期（窗口改期/目标罐变化/被抢占）时返回 409，需重新计算 */
    @PostMapping("/transfers/{planId}/confirm")
    public ApiResponse<Views.TransferPlanView> confirm(
            @PathVariable("planId") Long planId,
            @Valid @RequestBody MaintenanceDtos.TransferConfirmReq req) {
        return ApiResponse.success("迁罐已确认，开始执行", maintenanceService.confirm(planId, req));
    }

    /** 处理进度：完成下一步（1 断管隔离 → 5 接管温控） */
    @PostMapping("/transfers/{planId}/steps/{stepSeq}/done")
    public ApiResponse<Views.TransferPlanView> completeStep(
            @PathVariable("planId") Long planId,
            @PathVariable("stepSeq") Integer stepSeq,
            @RequestParam(value = "operator", defaultValue = "设备负责人") String operator) {
        return ApiResponse.success("第 " + stepSeq + " 步已完成",
                maintenanceService.completeStep(planId, stepSeq, operator));
    }

    /** 迁移中途失败：回退已完成步骤、方案退回待处理，原罐继续占用、目标罐保持空闲 */
    @PostMapping("/transfers/{planId}/fail")
    public ApiResponse<Views.TransferPlanView> fail(
            @PathVariable("planId") Long planId,
            @Valid @RequestBody MaintenanceDtos.TransferFailReq req) {
        return ApiResponse.success("迁移失败已回退到待处理", maintenanceService.fail(planId, req));
    }

    /** 失败后继续处理：按最新条件复核后重开一轮五步 */
    @PostMapping("/transfers/{planId}/resume")
    public ApiResponse<Views.TransferPlanView> resume(
            @PathVariable("planId") Long planId,
            @Valid @RequestBody MaintenanceDtos.TransferResumeReq req) {
        return ApiResponse.success("已按最新条件重新开始迁移", maintenanceService.resume(planId, req));
    }

    /** 取消待处理方案（已执行的迁罐不能取消/删除） */
    @PostMapping("/transfers/{planId}/cancel")
    public ApiResponse<Views.TransferPlanView> cancel(
            @PathVariable("planId") Long planId,
            @Valid @RequestBody MaintenanceDtos.TransferCancelReq req) {
        return ApiResponse.success("迁罐方案已取消", maintenanceService.cancelPlan(planId, req));
    }

    /** 迁罐预览/处理进度：方案详情（分步进度 + 操作流水 + 实时适配复核） */
    @GetMapping("/transfers/{planId}")
    public ApiResponse<Views.TransferPlanView> plan(@PathVariable("planId") Long planId) {
        return ApiResponse.success(maintenanceService.planDetail(planId));
    }

    /** 方案列表（可按维护窗口过滤） */
    @GetMapping("/transfers")
    public ApiResponse<List<Views.TransferPlanView>> plans(
            @RequestParam(value = "maintenanceId", required = false) Long maintenanceId) {
        return ApiResponse.success(maintenanceService.plans(maintenanceId));
    }
}
