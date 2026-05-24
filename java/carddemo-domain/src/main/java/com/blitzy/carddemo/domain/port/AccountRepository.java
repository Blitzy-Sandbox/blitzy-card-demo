/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.AccountRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the ACCTDATA dataset (KSDS keyed by account id).
 * Adapters in {@code carddemo-adapter-file} and {@code carddemo-adapter-db}
 * implement this interface; the composition root in {@code carddemo-app}
 * wires the appropriate implementation per AAP &sect;0.3.6.
 *
 * <p>This interface mirrors the COBOL ACCTFILE-FILE access modes:
 * sequential (CBACT01C) and random-by-key (COACTVWC, COACTUPC, CBTRN02C).
 */
public interface AccountRepository extends AutoCloseable {

    /**
     * Stream all accounts in physical (sequential) order. Mirrors COBOL
     * {@code OPEN INPUT ACCTFILE-FILE} followed by {@code READ NEXT}.
     *
     * <p>The returned stream MUST be closed by the caller (it backs an
     * underlying file channel or DB cursor).
     */
    Stream<AccountRecord> streamSequential();

    /**
     * Look up an account by primary key (ACCT-ID). Mirrors COBOL
     * {@code READ ACCTFILE KEY IS FD-ACCT-ID}.
     *
     * @param acctId the 11-digit account number
     * @return the account record if present, or empty if not found
     */
    Optional<AccountRecord> findById(long acctId);

    /**
     * Update or insert an account record (REWRITE for existing key, WRITE
     * for new key). Mirrors COBOL {@code REWRITE} / {@code WRITE} in I-O
     * mode (used by COACTUPC and CBTRN02C).
     *
     * @param account the account to persist
     */
    void save(AccountRecord account);

    /**
     * Closes any underlying resources. Implementations MAY be no-op for
     * in-memory adapters.
     */
    @Override
    void close();
}
