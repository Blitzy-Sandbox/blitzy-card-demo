package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import com.vsergeychik.carddemo.common.DatasetIntegrityException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proves the unit-of-work boundary a locking read depends on.
 *
 * <p>The boundary exists because CICS gives it for free and JDBC does not. In {@code COACTUPC}'s
 * {@code 9600-WRITE-PROCESSING} the task locks the account, locks the customer, compares each against
 * the copy the screen was painted from, and only then rewrites - and the comparison is only meaningful
 * because nothing can change either record in between. A connection in auto-commit mode releases a
 * {@code FOR UPDATE} lock the instant the statement returns, so the same sequence run that way would
 * still pass its comparison and protect nothing. These tests hold that boundary to three obligations:
 * a body runs inside one transaction on one connection, a failing body rolls the whole thing back, and
 * a locking read taken with no boundary open is refused rather than issued anyway.
 *
 * <p>An in-memory database backs the transaction manager. That is not a shortcut around residual risk
 * R-E: the subject here is the transaction boundary itself, which is the framework's behaviour rather
 * than the deployment driver's, and a real manager over a real connection is the only way to observe
 * a commit and a rollback actually happening.
 */
@DisplayName("DatasetUnitOfWork - the CICS task boundary, made explicit")
class DatasetUnitOfWorkTest {

    /** A single-connection source, so "the same connection throughout" is observable. */
    private static DataSource singleConnection() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:unit-of-work-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        source.setSuppressClose(true);
        return source;
    }

    /** A unit of work over a real transaction manager. */
    private static DatasetUnitOfWork unitOfWork(DataSource dataSource) {
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("a transaction manager is required: without one there is no boundary to open")
        void aManagerIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DatasetUnitOfWork(null))
                    .withMessageContaining("PlatformTransactionManager is required");
        }

        @Test
        @DisplayName("it is built from the module's single manager over the repositories' DataSource")
        void itIsBuiltFromTheSharedManager() {
            DataSource dataSource = singleConnection();
            PlatformTransactionManager manager = new JdbcTransactionManager(dataSource);

            // The same manager and the same source the repositories read through, or the boundary would
            // not enclose their statements.
            assertThat(new DatasetUnitOfWork(manager)).isNotNull();
        }
    }

    @Nested
    @DisplayName("The boundary")
    class Boundary {

        @Test
        @DisplayName("a body sees an active transaction, and does not once it returns")
        void aBodyRunsInsideATransaction() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            assertThat(DatasetUnitOfWork.active()).isFalse();
            assertThat(unitOfWork.execute("a locking read", DatasetUnitOfWork::active)).isTrue();
            assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("every statement in one unit of work sees one connection")
        void oneUnitOfWorkIsOneConnection() {
            DataSource dataSource = singleConnection();
            DatasetUnitOfWork unitOfWork = unitOfWork(dataSource);
            List<Connection> seen = new ArrayList<>();

            unitOfWork.execute("two reads and a rewrite", () -> {
                seen.add(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource));
                seen.add(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource));
                return null;
            });

            // Two reads on two connections would let another task change the record between them, which
            // is exactly the interleaving 9700-CHECK-CHANGE-IN-REC exists to detect and cannot.
            assertThat(seen).hasSize(2);
            assertThat(seen.get(0)).isSameAs(seen.get(1));
        }

        @Test
        @DisplayName("a caller's transaction is joined, not nested: one commit, as CICS has one syncpoint")
        void anOuterTransactionIsJoined() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            Object outerName = unitOfWork.execute("the outer unit", () -> {
                Object outer = TransactionSynchronizationManager.getCurrentTransactionName();
                unitOfWork.execute("an inner read", () ->
                        assertThat(TransactionSynchronizationManager.getCurrentTransactionName())
                                .isEqualTo(outer));
                return outer;
            });

            assertThat(DatasetUnitOfWork.active()).isFalse();
            assertThat(outerName).isNull();
        }

        @Test
        @DisplayName("the void form runs its body and closes the boundary too")
        void theVoidFormRunsItsBody() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());
            List<Boolean> observed = new ArrayList<>();

            unitOfWork.execute("a rewrite", () -> observed.add(DatasetUnitOfWork.active()));

            assertThat(observed).containsExactly(true);
            assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("a failing body rolls back and the failure reaches the caller unchanged")
        void aFailingBodyRollsBack() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            // The rollback is what keeps a half-applied update - the account rewritten, the customer not
            // - out of the datasets. The CICS original cannot produce that state and therefore has no
            // code to recover from it.
            assertThatIllegalStateException()
                    .isThrownBy(() -> unitOfWork.execute("a rewrite that fails", () -> {
                        throw new IllegalStateException("the rewrite refused");
                    }))
                    .withMessage("the rewrite refused");
            assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("a description and a body are both required")
        void bothArgumentsAreRequired() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.execute(null, () -> null))
                    .withMessageContaining("description is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.execute("a read", (java.util.function.Supplier<?>) null))
                    .withMessageContaining("body is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.execute("a read", (Runnable) null))
                    .withMessageContaining("body is required");
        }
    }

    @Nested
    @DisplayName("The precondition a locking read enforces")
    class Precondition {

        @Test
        @DisplayName("inside a unit of work, a locking read is permitted")
        void insideAUnitOfWorkItIsPermitted() {
            unitOfWork(singleConnection()).execute("a locking read", () -> {
                DatasetUnitOfWork.requireActive("A read-for-update of account '00000000001'",
                        "TEST.ACCTDATA.KSDS");
                return null;
            });
        }

        @Test
        @DisplayName("outside one, it is refused - and the message says how to open one")
        void outsideOneItIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> DatasetUnitOfWork.requireActive("A read-for-update",
                            "TEST.ACCTDATA.KSDS"))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("TEST.ACCTDATA.KSDS")
                    .withMessageContaining(DatasetUnitOfWork.class.getSimpleName() + ".execute");
        }

        @Test
        @DisplayName("both arguments are required, so a refusal can always name what it refused")
        void bothArgumentsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DatasetUnitOfWork.requireActive(null, "TEST.DS"))
                    .withMessageContaining("operation name is required");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DatasetUnitOfWork.requireActive("A read-for-update", null))
                    .withMessageContaining("dataset name is required");
        }
    }

    @Nested
    @DisplayName("The refusal a rewrite that changed too much raises")
    class Refusal {

        @Test
        @DisplayName("inside a unit of work the throw is the rollback: nothing the body did commits")
        void insideAUnitOfWorkTheThrowIsTheRollback() {
            DataSource dataSource = singleConnection();
            DatasetUnitOfWork unitOfWork = unitOfWork(dataSource);
            JdbcTemplate template = new JdbcTemplate(dataSource);
            template.execute("CREATE TABLE REFUSAL (IMAGE VARCHAR(8))");

            // The body writes a row and then discovers that the write must not stand. A file status
            // returned from there would let the row commit on the way out - which is exactly the
            // outcome BD-03 describes - so the refusal is a throw and the row must be gone afterwards.
            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> unitOfWork.execute("a rewrite that fanned out", () -> {
                        template.update("INSERT INTO REFUSAL VALUES ('DAMAGE')");
                        throw DatasetUnitOfWork.commitRefusal("The rewrite of account 00000*****",
                                "2 rows were replaced where the key selected exactly one");
                    }))
                    .withMessageContaining("must not be allowed to stand")
                    .withMessageContaining("2 rows were replaced")
                    .withMessageContaining("rolled back rather than reported as a file status");

            assertThat(template.queryForObject("SELECT COUNT(*) FROM REFUSAL", Integer.class))
                    .as("the refused write must not have committed")
                    .isZero();
            assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("outside one it says the change has already committed, because it has")
        void outsideOneItSaysTheChangeHasAlreadyCommitted() {
            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> {
                        throw DatasetUnitOfWork.commitRefusal("The rewrite of account 00000*****",
                                "3 rows were replaced where the key selected exactly one");
                    })
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("already been committed by the connection's own autocommit")
                    .withMessageContaining(DatasetUnitOfWork.class.getSimpleName() + ".execute");
        }

        @Test
        @DisplayName("the operation is carried as a component, so a caller need not match prose")
        void theOperationIsCarriedAsAComponent() {
            DatasetIntegrityException refusal =
                    DatasetUnitOfWork.commitRefusal("The rewrite of account 00000*****", "a reason");

            assertThat(refusal.operation()).isEqualTo("The rewrite of account 00000*****");
            assertThat(refusal).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("both arguments are required, so a refusal can always be attributed")
        void bothArgumentsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DatasetUnitOfWork.commitRefusal(null, "a reason"))
                    .withMessageContaining("operation name is required");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DatasetUnitOfWork.commitRefusal("a rewrite", null))
                    .withMessageContaining("reason is required");
        }
    }
}
