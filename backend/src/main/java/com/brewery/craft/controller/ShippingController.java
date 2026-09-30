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
import com.brewery.craft.dto.ShipmentDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.service.ShipmentService;

import jakarta.validation.Valid;

/** 模块五：灌装与出货。 */
@RestController
@RequestMapping("/api/shipping")
public class ShippingController {

    private final ShipmentService shipmentService;

    public ShippingController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @GetMapping("/bottlings")
    public ApiResponse<List<Views.BottlingView>> bottlings() {
        return ApiResponse.success(shipmentService.bottlings());
    }

    @PostMapping("/bottlings")
    public ApiResponse<Views.BottlingView> createBottling(@Valid @RequestBody ShipmentDtos.BottlingSaveReq req) {
        return ApiResponse.success("灌装单已登记", shipmentService.createBottling(req));
    }

    @GetMapping("/shipments")
    public ApiResponse<List<Views.ShipmentView>> shipments(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.success(shipmentService.shipments(status));
    }

    @PostMapping("/shipments")
    public ApiResponse<Views.ShipmentView> createShipment(@Valid @RequestBody ShipmentDtos.ShipmentSaveReq req) {
        return ApiResponse.success("出货单已登记", shipmentService.createShipment(req));
    }

    /** 待发 → 已发 → 已结款 */
    @PostMapping("/shipments/{id}/next")
    public ApiResponse<Views.ShipmentView> nextStatus(@PathVariable("id") Long id) {
        return ApiResponse.success("出货状态已推进", shipmentService.nextStatus(id));
    }

    /** 金额与出货量汇总 */
    @GetMapping("/summary")
    public ApiResponse<Views.ShipmentSummary> summary() {
        return ApiResponse.success(shipmentService.summary());
    }
}
