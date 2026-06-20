package com.carddemo.repository;

import com.carddemo.model.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the TransactionType reference entity
 * (re-platforms the VSAM TRANTYPE KSDS). Provides keyed lookup by the
 * two-character transaction-type code via inherited JpaRepository operations.
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
