package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Category;
import com.learning.spring.financialdashboard.model.CategoryKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByNameAsc();

    List<Category> findAllByKindOrderByNameAsc(CategoryKind kind);

    boolean existsByNameIgnoreCaseAndKindAndParentIdIsNull(String name, CategoryKind kind);

    boolean existsByNameIgnoreCaseAndParentId(String name, Long parentId);

    boolean existsByParentId(Long parentId);

    Optional<Category> findBySystemKey(String systemKey);
}
