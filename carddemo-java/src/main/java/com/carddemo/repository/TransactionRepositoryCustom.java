package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;

/**
 * Custom persistence fragment for {@link TransactionRepository} that supplies an explicit
 * <em>insert-only</em> save operation.
 *
 * <p>The {@link com.carddemo.model.entity.Transaction} entity carries an application-assigned
 * primary key (the sixteen-digit transaction id) and has no JPA {@code @Version} attribute. For
 * such entities Spring Data's {@code save(...)} delegates to {@code EntityManager.merge(...)},
 * which first issues a {@code SELECT} and then decides between an {@code INSERT} (key absent) and
 * an {@code UPDATE} (key present). Under concurrent transaction-add requests that derive the same
 * browse-to-end id, that merge path is unsafe: the request whose {@code SELECT} observes the
 * already-committed row is silently turned into an {@code UPDATE} that overwrites the winning
 * transaction instead of being rejected &mdash; corrupting data while still returning success.</p>
 *
 * <p>{@link #insertNew(Transaction)} forces true {@code INSERT} semantics via
 * {@code EntityManager.persist(...)} so a colliding key is always rejected by the primary-key
 * constraint and surfaces (through Spring Data's automatic persistence-exception translation) as a
 * {@link org.springframework.dao.DataIntegrityViolationException}. The transaction-add service can
 * then re-derive the id from the new maximum and retry, guaranteeing that simultaneous adds each
 * receive a distinct id and that no transaction is ever silently overwritten.</p>
 */
public interface TransactionRepositoryCustom {

    /**
     * Inserts a new, transient {@link Transaction} using explicit {@code INSERT} semantics and
     * flushes immediately so any primary-key collision is reported synchronously to the caller.
     *
     * <p>The supplied entity must be transient (a freshly constructed instance that has not been
     * loaded into, or detached from, a persistence context); passing a managed or detached entity
     * is a programming error. The insert and its flush run in their own short transaction, so a
     * collision rolls back only this attempt and never marks an enclosing context rollback-only.</p>
     *
     * @param transaction the transient transaction to insert; its id must already be assigned
     * @return the persisted, now-managed transaction instance
     * @throws org.springframework.dao.DataIntegrityViolationException if the transaction id (or any
     *                                                                 other unique/constraint key)
     *                                                                 already exists
     */
    Transaction insertNew(Transaction transaction);
}
