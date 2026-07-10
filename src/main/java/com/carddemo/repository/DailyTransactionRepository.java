package com.carddemo.repository;

import com.carddemo.entity.DailyTransaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link DailyTransaction} staging entity.
 *
 * <p>This repository is the persistence access point for the
 * <strong>daily (unposted) transaction</strong> staging table
 * ({@code daily_transaction}). It is the idiomatic Spring Data replacement for
 * the legacy COBOL {@code DALYTRAN} sequential file described by copybook
 * {@code app/cpy/CVTRA06Y.cpy} ({@code DALYTRAN-RECORD}, fixed record length
 * <strong>350</strong> bytes), referenced at source commit SHA
 * {@code 27d6c6f}.</p>
 *
 * <p><strong>Role in the posting pipeline.</strong> Daily transactions are the
 * input consumed by the batch posting job — the migration target of legacy
 * program {@code CBTRN02C} (POSTTRAN). In COBOL, {@code DALYTRAN-FILE} is
 * declared {@code ORGANIZATION IS SEQUENTIAL} / {@code ACCESS MODE IS SEQUENTIAL}
 * and is read record-by-record inside a {@code PERFORM UNTIL END-OF-FILE} loop
 * ({@code 1000-DALYTRAN-GET-NEXT}); accepted rows are then posted to the
 * {@code TRANSACT} (posted {@link com.carddemo.entity.Transaction}) file. Because
 * the legacy access pattern is a pure forward scan rather than a keyed lookup,
 * this repository intentionally declares <strong>no custom or derived query
 * methods</strong>: the Spring Batch reader for the posting step pages over the
 * staging rows in sorted order using the inherited
 * {@link JpaRepository#findAll(org.springframework.data.domain.Pageable)}
 * (for example via a {@code RepositoryItemReader}), and individual rows are
 * fetched, inserted, or persisted in bulk through the inherited
 * {@link JpaRepository#findById(Object)}, {@link JpaRepository#save(Object)}, and
 * {@link JpaRepository#saveAll(Iterable)} operations. Restartability and
 * idempotency of the posting step are governed by the batch layer; this
 * repository only reads and writes.</p>
 *
 * <p><strong>Identifier type.</strong> The primary key is the natural key
 * {@code DailyTransaction.dalytranId}, translated from COBOL
 * {@code DALYTRAN-ID PIC X(16)} to a {@link String} (VARCHAR(16)); accordingly
 * the repository is parameterized as {@code JpaRepository<DailyTransaction,
 * String>}.</p>
 *
 * <p><strong>Distinct from {@code TransactionRepository}.</strong> Although the
 * daily-transaction layout ({@code CVTRA06Y}) is byte-identical to the posted
 * transaction layout ({@code CVTRA05Y}), the two are modelled as separate
 * entities backed by separate tables ({@code daily_transaction} staging versus
 * {@code transaction} posted). This repository governs the staging table only
 * and must not be merged with the posted-transaction repository.</p>
 *
 * <p>The underlying {@code daily_transaction} table is created and evolved by the
 * Flyway migrations; with Hibernate {@code ddl-auto: validate} this repository
 * issues no DDL and operates strictly over the migrated schema.</p>
 */
@Repository
public interface DailyTransactionRepository
        extends JpaRepository<DailyTransaction, String> {
    // No custom query methods: the DALYTRAN staging file is consumed by a
    // sequential batch reader using the inherited findAll(Pageable), findById,
    // save, and saveAll operations. Adding derived queries here would exceed the
    // migrated behavioral scope.
}
