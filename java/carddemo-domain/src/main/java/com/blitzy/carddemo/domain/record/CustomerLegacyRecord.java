/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * Alternate {@code CUSTOMER-RECORD} layout from {@code app/cpy/CUSTREC.cpy},
 * preserved for fixture compatibility per AAP &sect;0.4.1.
 *
 * <p>This layout is byte-identical to {@code CVCUS01Y.cpy} but with the
 * single field {@code CUST-DOB-YYYYMMDD} (10 chars, no dashes) instead of
 * the dashed {@code CUST-DOB-YYYY-MM-DD}. The structural identity makes it
 * a thin wrapper that delegates parse/encode to
 * {@link CustomerRecord}; the {@code legacy} marker provides a separate
 * type token so callsites can distinguish the two layouts at compile time.
 */
@CobolProgram(
        value = "CUSTREC",
        sourcePath = "app/cpy/CUSTREC.cpy",
        notes = "Alternate CUSTOMER-RECORD layout preserved per AAP §0.4.1; delegates to CustomerRecord"
)
public record CustomerLegacyRecord(CustomerRecord delegate) {

    public CustomerLegacyRecord {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
    }

    /** Factory: parses a 500-byte buffer via {@link CustomerRecord#parse(byte[])}. */
    public static CustomerLegacyRecord parse(byte[] buffer) {
        return new CustomerLegacyRecord(CustomerRecord.parse(buffer));
    }

    /** Encodes via {@link CustomerRecord#encode()}. */
    public byte[] encode() {
        return delegate.encode();
    }
}
