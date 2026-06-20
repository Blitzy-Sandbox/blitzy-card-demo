package com.carddemo.repository;

import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.key.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the TransactionCategory reference entity
 * (re-platforms the VSAM TRANCATG KSDS). Provides composite-keyed lookup by
 * TransactionCategoryId (transaction-type code + category code) via inherited
 * JpaRepository operations.
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
