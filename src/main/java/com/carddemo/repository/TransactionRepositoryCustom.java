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

/**
 * Custom persistence fragment for {@link TransactionRepository} that exposes an
 * insert-only write whose semantics are required for the auto-generated
 * {@code TRAN-ID} concurrency guard.
 *
 * <p><strong>Why this exists.</strong> {@link Transaction} carries a
 * manually-assigned {@code @Id} ({@code tranId}) and intentionally has no
 * {@code @Version} column (the legacy {@code TRANSACT} VSAM file is append-only;
 * COBOL writes records with {@code WRITE-TRANSACT-FILE} and never rewrites
 * them). Consequently Spring Data's {@code SimpleJpaRepository.save}/
 * {@code saveAndFlush} classifies a populated transaction as
 * <em>not new</em> ({@code isNew()} keys off the non-null id) and routes the
 * write through {@code EntityManager.merge}. Under concurrency {@code merge}
 * issues a {@code SELECT} first: when a competing request has already committed
 * the same generated id, {@code merge} silently performs an {@code UPDATE}
 * (a lost write) instead of failing, so no
 * {@code DataIntegrityViolationException} is raised and the
 * find-highest-then-add-one retry in {@code TransactionAddService} /
 * {@code BillingService} never fires &mdash; two requests then return the same
 * id with one overwriting the other.</p>
 *
 * <p><strong>What this fixes.</strong> {@link #insert(Transaction)} calls
 * {@code EntityManager.persist} followed by an immediate {@code flush}, forcing
 * an {@code INSERT} (never an {@code UPDATE}). A colliding primary key therefore
 * always raises a constraint violation at flush time, which the Spring Data
 * repository proxy translates into
 * {@link org.springframework.dao.DataIntegrityViolationException}. The existing
 * bounded retry loops catch that exception, re-read the highest id and try
 * again, reproducing the serialized "find last id then add one" behaviour that
 * CICS/VSAM provided through record locking. This guarantees every concurrent
 * add and bill-payment receives its own distinct, sequential {@code TRAN-ID}
 * (Issue&nbsp;#6, FINAL-3).</p>
 */
public interface TransactionRepositoryCustom {

    /**
     * Inserts a brand-new transaction with INSERT-only semantics.
     *
     * <p>Equivalent in contract to
     * {@link org.springframework.data.jpa.repository.JpaRepository#saveAndFlush}
     * (the managed entity is returned and the write is flushed immediately), but
     * always issues an {@code INSERT} via {@code EntityManager.persist}. A
     * primary-key collision therefore surfaces deterministically as a
     * {@link org.springframework.dao.DataIntegrityViolationException} (after
     * repository-proxy exception translation) rather than being absorbed by a
     * {@code merge}-driven {@code UPDATE}.</p>
     *
     * @param transaction the transient transaction to insert; must carry an
     *                    application-assigned {@code tranId}
     * @return the now-managed, flushed transaction instance
     * @throws org.springframework.dao.DataIntegrityViolationException if a
     *         transaction with the same {@code tranId} already exists
     */
    Transaction insert(Transaction transaction);
}
