package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
