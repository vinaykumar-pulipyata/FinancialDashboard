package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.AppCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppCredentialRepository extends JpaRepository<AppCredential, Long> {
}
