/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.TranCatRecord;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for accessing the TRANCATG lookup dataset (KSDS keyed by
 * composite TRAN-CAT-KEY = TRAN-TYPE-CD + TRAN-CAT-CD).
 *
 * <p>Mirrors the COBOL access pattern: the dataset is small (18 records)
 * and is read in its entirety on first lookup. CBTRN02C uses this to
 * validate category codes during posting.
 */
public interface TransactionCategoryRepository extends AutoCloseable {

    /**
     * Return all category records.
     */
    List<TranCatRecord> findAll();

    /**
     * Look up by composite key.
     */
    Optional<TranCatRecord> findByKey(String tranTypeCd, int tranCatCd);

    @Override
    void close();
}
