/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.DisGroupRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the DISCGRP dataset (KSDS keyed by composite
 * DIS-GROUP-KEY = ACCT-GROUP-ID + TRAN-TYPE-CD + TRAN-CAT-CD).
 *
 * <p>Mirrors the COBOL DISCGRP access used by CBACT04C (interest calc).
 */
public interface DiscountGroupRepository extends AutoCloseable {

    /**
     * Stream all discount-group records in physical (sequential) order.
     */
    Stream<DisGroupRecord> streamSequential();

    /**
     * Look up by composite key.
     *
     * @param acctGroupId 10-char account group identifier
     * @param tranTypeCd  2-char transaction type code
     * @param tranCatCd   4-digit transaction category code
     */
    Optional<DisGroupRecord> findByKey(String acctGroupId, String tranTypeCd, int tranCatCd);

    /**
     * Update or insert a discount-group record.
     */
    void save(DisGroupRecord group);

    @Override
    void close();
}
