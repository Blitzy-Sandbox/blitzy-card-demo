package com.carddemo.repository;

import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the TransactionCategoryBalance entity
 * (re-platforms the VSAM TCATBALF KSDS). Provides composite-keyed access by
 * TransactionCategoryBalanceId (account id + transaction-type code + category
 * code) for posting updates and a sequential scan for interest calculation,
 * via inherited JpaRepository operations.
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
}
