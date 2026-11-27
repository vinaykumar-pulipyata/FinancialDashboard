package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Bucket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BucketRepository extends JpaRepository<Bucket, Long> {
}
