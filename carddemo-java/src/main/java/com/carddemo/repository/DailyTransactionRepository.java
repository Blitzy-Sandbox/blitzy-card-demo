package com.carddemo.repository;

import com.carddemo.model.entity.DailyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the DailyTransaction staging entity (the daily
 * transaction input consumed by the posting pipeline). Provides staging persist
 * and sequential read for the daily-transaction-posting batch job via inherited
 * JpaRepository operations.
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {
}
