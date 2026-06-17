package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of the {@link TransactionRepositoryCustom} insert-only fragment.
 *
 * <p>Spring Data discovers this class by the fragment-implementation naming convention (the custom
 * fragment interface name suffixed with {@code Impl}) and composes it into the
 * {@link TransactionRepository} proxy. Invocations therefore pass through the repository proxy's
 * automatic persistence-exception translation, so the Hibernate {@code ConstraintViolationException}
 * raised by a primary-key collision is surfaced to callers as a Spring
 * {@link org.springframework.dao.DataIntegrityViolationException}.</p>
 */
public class TransactionRepositoryCustomImpl implements TransactionRepositoryCustom {

    /**
     * Transaction-scoped, container-managed entity manager. Because each {@link #insertNew} call runs
     * in its own short transaction, every invocation operates on a fresh persistence context; a
     * rolled-back attempt leaves no managed state behind to contaminate a subsequent retry.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@code persist} (never {@code merge}) to guarantee an {@code INSERT}, then flushes
     * within the same transaction so a duplicate key is reported immediately rather than at a
     * deferred commit. The method manages its own transaction (default {@code REQUIRED} propagation):
     * the transaction-add service deliberately runs its retry loop outside any transaction, so each
     * attempt commits or rolls back independently.</p>
     */
    @Override
    @Transactional
    public Transaction insertNew(Transaction transaction) {
        entityManager.persist(transaction);
        entityManager.flush();
        return transaction;
    }
}
