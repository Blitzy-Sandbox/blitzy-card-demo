/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.SecUserData;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the USRSEC dataset (KSDS keyed by SEC-USR-ID).
 *
 * <p>Mirrors the COBOL access pattern used by COSGN00C (random-by-key
 * lookup of credentials), COUSR00C (sequential listing for the admin
 * user list), COUSR01C / COUSR02C / COUSR03C (add / update / delete).
 *
 * <p>Per AAP &sect;0.1.3 and &sect;0.7.2 the user record stores passwords
 * as plaintext to preserve byte-for-byte parity with the COBOL baseline.
 */
public interface UserSecurityRepository extends AutoCloseable {

    /**
     * Stream all user records in physical (sequential) order.
     */
    Stream<SecUserData> streamSequential();

    /**
     * Look up by user id (primary key).
     */
    Optional<SecUserData> findById(String userId);

    /**
     * Insert a new user record. Mirrors COBOL {@code WRITE USRSEC} from
     * COUSR01C. Throws {@link IllegalStateException} if the key already
     * exists (COBOL duplicate-key file status 22).
     */
    void insert(SecUserData user);

    /**
     * Update an existing user record. Mirrors COBOL {@code REWRITE USRSEC}
     * from COUSR02C. Throws {@link java.util.NoSuchElementException} if the
     * record does not exist (COBOL not-found file status 23).
     */
    void update(SecUserData user);

    /**
     * Delete a user record by id. Mirrors COBOL {@code DELETE USRSEC}
     * from COUSR03C. Throws {@link java.util.NoSuchElementException} if
     * the record does not exist.
     */
    void delete(String userId);

    @Override
    void close();
}
