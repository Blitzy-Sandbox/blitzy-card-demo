/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.TranTypeRecord;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for accessing the TRANTYPE lookup dataset (KSDS keyed by
 * 2-character TRAN-TYPE code).
 *
 * <p>Mirrors the COBOL access pattern: the dataset is small (7 records) and
 * is read in its entirety at startup or on first lookup. Random-by-key is
 * needed by CBTRN03C report generation.
 */
public interface TransactionTypeRepository extends AutoCloseable {

    /**
     * Return all transaction type records (the dataset is small and is
     * usually cached in memory).
     */
    List<TranTypeRecord> findAll();

    /**
     * Look up by transaction type code.
     */
    Optional<TranTypeRecord> findByCode(String tranTypeCode);

    @Override
    void close();
}
