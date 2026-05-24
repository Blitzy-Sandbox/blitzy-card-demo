/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.CardRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the CARDDATA dataset (KSDS keyed by card number).
 *
 * <p>Mirrors the COBOL CARDFILE-FILE access modes from CBACT02C (sequential)
 * and COCRDSLC / COCRDUPC (random-by-key).
 */
public interface CardRepository extends AutoCloseable {

    /**
     * Stream all cards in physical (sequential) order.
     */
    Stream<CardRecord> streamSequential();

    /**
     * Look up a card by primary key (CARD-NUM). Mirrors COBOL
     * {@code READ CARDFILE KEY IS FD-CARD-NUM}.
     *
     * @param cardNum the 16-digit card number
     * @return the card record if present, or empty if not found
     */
    Optional<CardRecord> findByCardNum(String cardNum);

    /**
     * Update or insert a card record.
     */
    void save(CardRecord card);

    @Override
    void close();
}
