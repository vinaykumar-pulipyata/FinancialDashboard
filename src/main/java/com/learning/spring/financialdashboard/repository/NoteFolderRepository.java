package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.NoteFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteFolderRepository extends JpaRepository<NoteFolder, Long> {

    List<NoteFolder> findAllByOrderByNameAsc();
}
