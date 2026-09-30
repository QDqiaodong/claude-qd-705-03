package com.brewery.craft.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.brewery.craft.common.ApiResponse;
import com.brewery.craft.dto.BatchDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.service.BatchService;

import jakarta.validation.Valid;

/** 模块二：啤酒批次（状态流转 + 每日测温/测糖曲线）。 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping
    public ApiResponse<List<Views.BatchView>> list(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.success(batchService.list(status));
    }

    @GetMapping("/summary")
    public ApiResponse<Views.BatchSummary> summary() {
        return ApiResponse.success(batchService.summary());
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.BatchView> get(@PathVariable("id") Long id) {
        return ApiResponse.success(batchService.get(id));
    }

    @PostMapping
    public ApiResponse<Views.BatchView> create(@Valid @RequestBody BatchDtos.BatchSaveReq req) {
        return ApiResponse.success("批次已建档", batchService.create(req));
    }

    /** 状态推进一格：计划→糖化→煮沸→发酵→熟成→已灌装 */
    @PostMapping("/{id}/advance")
    public ApiResponse<Views.BatchView> advance(@PathVariable("id") Long id,
                                                @RequestBody(required = false) Map<String, String> body) {
        String target = body == null ? null : body.get("status");
        return ApiResponse.success("批次已推进", batchService.advance(id, target));
    }

    @PostMapping("/{id}/scrap")
    public ApiResponse<Views.BatchView> scrap(@PathVariable("id") Long id) {
        return ApiResponse.success("批次已报废", batchService.scrap(id));
    }

    /**
     * 【@ElementCollection Map 写】登记/覆盖第 N 天的温度与糖度
     */
    @PostMapping("/{id}/readings")
    public ApiResponse<Views.BatchView> putReading(@PathVariable("id") Long id,
                                                   @RequestBody BatchDtos.ReadingReq req) {
        return ApiResponse.success("测量数据已登记", batchService.putReading(id, req));
    }

    /** 【@ElementCollection Map 删】删除某一天的记录 */
    @DeleteMapping("/{id}/readings/{dayKey}")
    public ApiResponse<Views.BatchView> removeReading(@PathVariable("id") Long id,
                                                      @PathVariable("dayKey") String dayKey) {
        return ApiResponse.success("测量数据已删除", batchService.removeReading(id, dayKey));
    }

    /** 【@ElementCollection Map 按 key 查】查某一天的温度/糖度 */
    @GetMapping("/{id}/readings/{dayKey}")
    public ApiResponse<Map<String, BigDecimal>> reading(@PathVariable("id") Long id,
                                                        @PathVariable("dayKey") String dayKey) {
        return ApiResponse.success(batchService.reading(id, dayKey));
    }
}
