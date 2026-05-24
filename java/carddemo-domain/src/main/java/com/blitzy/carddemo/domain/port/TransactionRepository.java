/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.TranRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the TRANSACT dataset (KSDS keyed by tran id).
 *
 * <p>Mirrors the COBOL TRANSACT-FILE access modes used by COTRN00C
 * (sequential listing), COTRN01C (random-by-key), and CBTRN02C
 * (WRITE for newly-posted transactions).
 */
public interface TransactionRepository extends AutoCloseable {

    /**
     * Stream all transactions in physical (sequential) order.
     */
    Stream<TranRecord> streamSequential();

    /**
     * Look up a transaction by primary key (TRAN-ID).
     */
    Optional<TranRecord> findById(String tranId);

    /**
     * Look up transactions associated with a particular card number.
     */
    Stream<TranRecord> findByCardNum(String cardNum);

    /**
     * Append a new transaction record. Mirrors COBOL {@code WRITE TRANSACT}.
     */
    void append(TranRecord transaction);

    @Override
    void close();
}
