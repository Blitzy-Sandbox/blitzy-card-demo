/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.port;

import com.blitzy.carddemo.domain.record.CustomerRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for accessing the CUSTDATA dataset (KSDS keyed by customer id).
 *
 * <p>Mirrors the COBOL CUSTFILE-FILE access modes used by CBCUS01C
 * (sequential) and COACTVWC (random-by-key).
 */
public interface CustomerRepository extends AutoCloseable {

    /**
     * Stream all customers in physical (sequential) order.
     */
    Stream<CustomerRecord> streamSequential();

    /**
     * Look up a customer by primary key (CUST-ID).
     */
    Optional<CustomerRecord> findById(long custId);

    /**
     * Update or insert a customer record.
     */
    void save(CustomerRecord customer);

    @Override
    void close();
}
