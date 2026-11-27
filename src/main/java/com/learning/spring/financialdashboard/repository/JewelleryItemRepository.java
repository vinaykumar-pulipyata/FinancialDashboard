package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.JewelleryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JewelleryItemRepository extends JpaRepository<JewelleryItem, Long> {

    List<JewelleryItem> findAllByAccountIdOrderByIdAsc(Long accountId);

    void deleteByAccountId(Long accountId);
}
