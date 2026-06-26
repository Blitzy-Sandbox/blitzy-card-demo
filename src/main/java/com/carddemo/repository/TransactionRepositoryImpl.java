/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Default implementation of {@link TransactionRepositoryCustom}.
 *
 * <p>Discovered automatically by Spring Data via the {@code Impl} postfix
 * convention ({@code TransactionRepository} &rarr; {@code TransactionRepositoryImpl})
 * and composed into the {@link TransactionRepository} proxy, so its methods are
 * subject to the same {@code PersistenceExceptionTranslationInterceptor} that
 * translates JPA/Hibernate persistence exceptions into the Spring
 * {@code org.springframework.dao} hierarchy.</p>
 *
 * <p>The injected {@link EntityManager} is the shared, container-managed proxy;
 * each call therefore joins whatever transaction is active on the calling thread
 * &mdash; in the auto-id paths that is the per-attempt {@code REQUIRES_NEW}
 * transaction opened by the service's {@code TransactionTemplate}, giving every
 * retry a fresh persistence context.</p>
 */
public class TransactionRepositoryImpl implements TransactionRepositoryCustom {

    /**
     * Container-managed persistence context shared with the active transaction
     * on the current thread.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * <p>Performs an INSERT-only write: {@code persist} schedules the row and the
     * immediate {@code flush} executes the {@code INSERT} (and therefore the
     * primary-key uniqueness check) inside the current transaction. When the
     * generated {@code tranId} has already been committed by a concurrent writer,
     * the database raises a unique-constraint violation that the repository proxy
     * translates to
     * {@link org.springframework.dao.DataIntegrityViolationException}, allowing the
     * caller's bounded retry loop to re-read the highest id and try again instead
     * of silently overwriting the existing record.</p>
     */
    @Override
    public Transaction insert(Transaction transaction) {
        entityManager.persist(transaction);
        entityManager.flush();
        return transaction;
    }
}
