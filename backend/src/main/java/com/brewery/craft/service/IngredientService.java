package com.brewery.craft.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.brewery.craft.common.BizException;
import com.brewery.craft.domain.Ingredient;
import com.brewery.craft.dto.IngredientDtos;
import com.brewery.craft.dto.Views;
import com.brewery.craft.repository.IngredientRepository;

/** 模块四：原料库存。 */
@Service
public class IngredientService {

    private final IngredientRepository ingredientRepository;

    public IngredientService(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
    }

    public List<Views.IngredientView> list(String type) {
        List<Ingredient> list = type == null || type.isBlank()
                ? ingredientRepository.findAllByOrderByCodeAsc()
                : ingredientRepository.findAllByTypeOrderByCodeAsc(type);
        List<Views.IngredientView> views = new ArrayList<>();
        for (Ingredient e : list) {
            views.add(Views.IngredientView.of(e));
        }
        return views;
    }

    public List<Views.IngredientView> low() {
        List<Views.IngredientView> views = new ArrayList<>();
        for (Ingredient e : ingredientRepository.findAllByOrderByCodeAsc()) {
            Views.IngredientView v = Views.IngredientView.of(e);
            if (v.isLow()) {
                views.add(v);
            }
        }
        return views;
    }

    @Transactional
    public Views.IngredientView create(IngredientDtos.IngredientSaveReq req) {
        if (ingredientRepository.existsByCode(req.getCode())) {
            throw new BizException("原料编号 " + req.getCode() + " 已存在");
        }
        if (Views.ingredientTypeName(req.getType()).equals(req.getType())) {
            throw new BizException("原料类型取值非法：" + req.getType());
        }
        if (req.getStock() != null && req.getStock().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("库存不能为负");
        }
        Ingredient e = new Ingredient();
        e.setCode(req.getCode());
        e.setName(req.getName());
        e.setType(req.getType());
        e.setUnit(req.getUnit() == null ? "kg" : req.getUnit());
        e.setStock(req.getStock() == null ? BigDecimal.ZERO : req.getStock());
        e.setSafetyStock(req.getSafetyStock() == null ? BigDecimal.ZERO : req.getSafetyStock());
        e.setUnitPrice(req.getUnitPrice());
        e.setSupplier(req.getSupplier());
        e.setShelfLifeTo(req.getShelfLifeTo());
        e.setUpdatedAt(LocalDateTime.now());
        return Views.IngredientView.of(ingredientRepository.save(e));
    }

    /** 入库（IN）或领用扣减（OUT） */
    @Transactional
    public Views.IngredientView changeStock(Long id, IngredientDtos.StockReq req) {
        Ingredient e = ingredientRepository.findById(id).orElseThrow(() -> new BizException("原料不存在"));
        BigDecimal qty = req.getQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("变动数量必须大于 0");
        }
        String mode = req.getMode() == null || req.getMode().isBlank() ? "IN" : req.getMode().toUpperCase();
        BigDecimal stock = e.getStock() == null ? BigDecimal.ZERO : e.getStock();
        if ("OUT".equals(mode)) {
            if (stock.compareTo(qty) < 0) {
                throw new BizException("库存不足，当前仅剩 " + stock.setScale(2, RoundingMode.HALF_UP) + e.getUnit());
            }
            e.setStock(stock.subtract(qty));
        } else if ("IN".equals(mode)) {
            e.setStock(stock.add(qty));
        } else {
            throw new BizException("变动模式只能是 IN 或 OUT");
        }
        e.setUpdatedAt(LocalDateTime.now());
        return Views.IngredientView.of(ingredientRepository.save(e));
    }
}
