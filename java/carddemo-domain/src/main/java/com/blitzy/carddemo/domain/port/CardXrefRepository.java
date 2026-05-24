/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.CardXrefRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the CARDXREF dataset and its alternate indices
 * (AIX by account id and AIX by customer id).
 *
 * <p>Mirrors the COBOL CARDXREF-FILE access modes used by CBACT03C and
 * CBTRN02C (lookup-card-by-account flow).
 */
public interface CardXrefRepository extends AutoCloseable {

    /**
     * Stream all card-xref entries in physical (sequential) order.
     */
    Stream<CardXrefRecord> streamSequential();

    /**
     * Look up the xref entry for a specific card number (primary key).
     */
    Optional<CardXrefRecord> findByCardNum(String cardNum);

    /**
     * Look up all card-xref entries pointing at the given account id
     * (alternate index path).
     */
    Stream<CardXrefRecord> findByAccountId(long acctId);

    /**
     * Look up all card-xref entries pointing at the given customer id
     * (alternate index path).
     */
    Stream<CardXrefRecord> findByCustomerId(long custId);

    /**
     * Update or insert an xref record.
     */
    void save(CardXrefRecord xref);

    @Override
    void close();
}
