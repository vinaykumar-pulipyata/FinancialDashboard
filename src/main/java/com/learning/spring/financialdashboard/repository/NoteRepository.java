package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /** Newest day first. The Daily Calendar Notes are sealed, so searching, tag filtering and paging are done in NoteService after opening them. */
    List<Note> findAllByOrderByDateDescIdDesc();

    long countByFolderId(Long folderId);

    long countByFolderIdIsNull();

    /** Daily Calendar Notes written before a password (and so a key) existed: sealed the first time the diary is opened. */
    List<Note> findAllByFolderIdIsNullAndSealedFalse();

    /** One row per day that has notes: the date and how many. */
    @Query("select n.date, count(n) from Note n group by n.date order by n.date")
    List<Object[]> countsByDate();

    List<Note> findAllByOrderByDateAscIdAsc();

    /** One row per folder that has notes: its id and how many. */
    @Query("select n.folderId, count(n) from Note n where n.folderId is not null group by n.folderId")
    List<Object[]> countsByFolder();
}
