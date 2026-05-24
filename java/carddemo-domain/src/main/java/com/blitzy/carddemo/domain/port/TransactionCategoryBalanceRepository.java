/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.TranCatBalRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the TCATBALF dataset (KSDS keyed by composite
 * TRAN-CAT-KEY = TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD).
 *
 * <p>Mirrors the COBOL TCATBALF access used by CBTRN02C paragraph 2700
 * (UPDATE-TCATBAL) and the PRTCATBL job. Both random-by-key and sequential
 * read are needed.
 */
public interface TransactionCategoryBalanceRepository extends AutoCloseable {

    /**
     * Stream all category-balance records in physical (sequential) order.
     */
    Stream<TranCatBalRecord> streamSequential();

    /**
     * Look up by composite key.
     *
     * @param acctId  11-digit account id
     * @param typeCd  2-char transaction type code
     * @param catCd   4-digit category code
     */
    Optional<TranCatBalRecord> findByKey(long acctId, String typeCd, int catCd);

    /**
     * Update or insert a category-balance record. Mirrors COBOL
     * {@code REWRITE TCATBALF} (for existing keys) and {@code WRITE TCATBALF}
     * (for new keys) from CBTRN02C paragraphs 2700-A and 2700-B.
     */
    void save(TranCatBalRecord balance);

    @Override
    void close();
}
