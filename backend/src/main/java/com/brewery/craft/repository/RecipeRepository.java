package com.brewery.craft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.Recipe;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    List<Recipe> findAllByOrderByCodeAsc();

    Optional<Recipe> findByCode(String code);

    boolean existsByCode(String code);

    List<Recipe> findAllByStyleOrderByCodeAsc(String style);
}
