package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findAllByOrderByDateAscIdAsc();
}
