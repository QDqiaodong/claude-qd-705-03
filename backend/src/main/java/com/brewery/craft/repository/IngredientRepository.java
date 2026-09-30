package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.Ingredient;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    List<Ingredient> findAllByOrderByCodeAsc();

    Optional<Ingredient> findByCode(String code);

    boolean existsByCode(String code);

    List<Ingredient> findAllByTypeOrderByCodeAsc(String type);

    long countByType(String type);

    /** 单位推导要用到原料档案单位：一次把相关原料查回，避免逐条查库 */
    List<Ingredient> findAllByCodeIn(Set<String> codes);
}
