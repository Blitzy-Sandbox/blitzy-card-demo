/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.DalyTranRecord;

import java.util.stream.Stream;

/**
 * Domain port for accessing the DALYTRAN dataset (sequential file of daily
 * transactions awaiting posting). Also exposes the DALYREJS reject-output
 * sink that CBTRN02C writes to when a transaction fails validation.
 *
 * <p>Mirrors the COBOL DALYTRAN access pattern: sequential read by CBTRN01C
 * and CBTRN02C; sequential append-only write for the reject file.
 */
public interface DailyTransactionRepository extends AutoCloseable {

    /**
     * Stream all daily transactions in physical (sequential) order.
     */
    Stream<DalyTranRecord> streamSequential();

    /**
     * Append a daily transaction record. Mirrors COBOL {@code WRITE DALYTRAN}.
     */
    void append(DalyTranRecord dailyTransaction);

    /**
     * Append a rejected daily transaction to the DALYREJS sink. Mirrors
     * COBOL {@code WRITE DALYREJS-FILE} from CBTRN02C paragraph 2500.
     */
    void appendReject(DalyTranRecord rejectedTransaction, String reasonCode);

    @Override
    void close();
}
