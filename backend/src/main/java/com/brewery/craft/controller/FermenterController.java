package com.brewery.craft.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.brewery.craft.common.ApiResponse;
import com.brewery.craft.dto.FermenterDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.service.FermenterService;

import jakarta.validation.Valid;

/** 模块一：发酵罐（首页罐阵数据源）。 */
@RestController
@RequestMapping("/api/fermenters")
public class FermenterController {

    private final FermenterService fermenterService;

    public FermenterController(FermenterService fermenterService) {
        this.fermenterService = fermenterService;
    }

    @GetMapping
    public ApiResponse<List<Views.FermenterView>> list(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.success(status == null || status.isBlank()
                ? fermenterService.list()
                : fermenterService.listByStatus(status));
    }

    @GetMapping("/overview")
    public ApiResponse<Views.Overview> overview() {
        return ApiResponse.success(fermenterService.overview());
    }

    @PostMapping
    public ApiResponse<Views.FermenterView> create(@Valid @RequestBody FermenterDtos.FermenterSaveReq req) {
        return ApiResponse.success("发酵罐已建档", fermenterService.create(req));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Views.FermenterView> changeStatus(@PathVariable("id") Long id,
                                                         @Valid @RequestBody FermenterDtos.StatusReq req) {
        return ApiResponse.success("罐体状态已更新", fermenterService.changeStatus(id, req));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<Views.FermenterView> assign(@PathVariable("id") Long id,
                                                   @RequestBody FermenterDtos.AssignReq req) {
        return ApiResponse.success("批次已入罐", fermenterService.assign(id, req));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<Views.FermenterView> release(@PathVariable("id") Long id) {
        return ApiResponse.success("批次已出罐，转入清洗", fermenterService.release(id));
    }

    /** 前端画链表用的罐体状态字典 */
    @GetMapping("/status-dict")
    public ApiResponse<List<Map<String, String>>> statusDict() {
        return ApiResponse.success(fermenterService.statusDict());
    }
}
