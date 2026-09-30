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
import com.brewery.craft.dto.IngredientDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.service.IngredientService;

import jakarta.validation.Valid;

/** 模块四：原料库存。 */
@RestController
@RequestMapping("/api/ingredients")
public class IngredientController {

    private final IngredientService ingredientService;

    public IngredientController(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    @GetMapping
    public ApiResponse<List<Views.IngredientView>> list(
            @RequestParam(value = "type", required = false) String type) {
        return ApiResponse.success(ingredientService.list(type));
    }

    /** 低于安全库存的原料（页面标红） */
    @GetMapping("/low")
    public ApiResponse<List<Views.IngredientView>> low() {
        return ApiResponse.success(ingredientService.low());
    }

    @PostMapping
    public ApiResponse<Views.IngredientView> create(@Valid @RequestBody IngredientDtos.IngredientSaveReq req) {
        return ApiResponse.success("原料已建档", ingredientService.create(req));
    }

    /** 入库 IN / 领用 OUT */
    @PostMapping("/{id}/stock")
    public ApiResponse<Views.IngredientView> changeStock(@PathVariable("id") Long id,
                                                         @RequestBody IngredientDtos.StockReq req) {
        return ApiResponse.success("库存已更新", ingredientService.changeStock(id, req));
    }
}
