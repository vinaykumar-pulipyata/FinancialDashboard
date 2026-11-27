package com.learning.spring.financialdashboard.repository;

import com.learning.spring.financialdashboard.model.BucketEntry;
import com.learning.spring.financialdashboard.model.EntryKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BucketEntryRepository extends JpaRepository<BucketEntry, Long> {

    /** Rows of [bucketId, balance] for every bucket that has entries. */
    @Query("select e.bucketId, sum(e.amount) from BucketEntry e group by e.bucketId")
    List<Object[]> balances();

    /** Rows of [bucketId, total] for one kind of entry. */
    @Query("select e.bucketId, sum(e.amount) from BucketEntry e where e.kind = :kind group by e.bucketId")
    List<Object[]> totalsByKind(@Param("kind") EntryKind kind);

    List<BucketEntry> findAllByBucketIdOrderByDateAscIdAsc(Long bucketId);

    List<BucketEntry> findAllByTransactionId(Long transactionId);

    void deleteByBucketId(Long bucketId);
}
