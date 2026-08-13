package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;


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
 */
@DisplayName("DatasetUnitOfWork - the CICS task boundary, made explicit")
class DatasetUnitOfWorkTest {
    private static DataSource singleConnection() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:unit-of-work-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        source.setSuppressClose(true);
        return source;
    }

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
    @DisplayName("The per-verb boundary a RECOVERY(NONE) batch write depends on")
    class VerbBoundary {
        private JdbcTemplate seeded(DataSource dataSource) {
            JdbcTemplate template = new JdbcTemplate(dataSource);
            template.execute("CREATE TABLE VERBS (ID INT)");
            return template;
        }

        @Test
        @DisplayName("a body runs inside a transaction, and its value is returned")
        void aBodyRunsInsideATransaction() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            assertThat(DatasetUnitOfWork.active()).isFalse();
            assertThat(unitOfWork.persistVerb("REWRITE FD-ACCTFILE-REC",
                    () -> DatasetUnitOfWork.active() ? "inside" : "outside")).isEqualTo("inside");
            assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("a verb commits even though the boundary around it rolls back - RECOVERY(NONE)")
        void aVerbCommitsIndependentlyOfAnEnclosingBoundary() {
            DataSource dataSource = singleConnection();
            JdbcTemplate template = seeded(dataSource);
            DatasetUnitOfWork unitOfWork = unitOfWork(dataSource);

            assertThatIllegalStateException().isThrownBy(() -> unitOfWork.execute("a chunk", () -> {
                unitOfWork.persistVerb("REWRITE FD-ACCTFILE-REC",
                        () -> template.update("INSERT INTO VERBS VALUES (1)"));
                throw new IllegalStateException("the abend that follows the rewrite");
            }));

            assertThat(template.queryForObject("SELECT COUNT(*) FROM VERBS", Integer.class))
                    .as("the REWRITE app/cbl/CBACT04C.cbl:356 already performed must survive the abend")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a verb that fails rolls back only itself, leaving earlier verbs committed")
        void aFailingVerbRollsBackOnlyItself() {
            DataSource dataSource = singleConnection();
            JdbcTemplate template = seeded(dataSource);
            DatasetUnitOfWork unitOfWork = unitOfWork(dataSource);

            unitOfWork.persistVerb("the first REWRITE",
                    () -> template.update("INSERT INTO VERBS VALUES (1)"));
            assertThatIllegalStateException()
                    .isThrownBy(() -> unitOfWork.persistVerb("the second REWRITE", () -> {
                        template.update("INSERT INTO VERBS VALUES (2)");
                        throw new IllegalStateException("the second write is refused");
                    }));

            assertThat(template.queryForList("SELECT ID FROM VERBS ORDER BY ID", Integer.class))
                    .as("each verb is its own unit: the first stands, the second does not")
                    .containsExactly(1);
        }

        @Test
        @DisplayName("a verb name and a body are both required, so a failed write can name itself")
        void bothArgumentsAreRequired() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.persistVerb(null, () -> null))
                    .withMessageContaining("verb name is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.persistVerb("REWRITE FD-ACCTFILE-REC", null))
                    .withMessageContaining("body is required");
        }

        @Test
        @DisplayName("a disposition survives the failure that triggered it - it is the initiator's work")
        void aDispositionIsIndependentOfTheFailureThatTriggeredIt() {
            DataSource dataSource = singleConnection();
            JdbcTemplate template = seeded(dataSource);
            DatasetUnitOfWork unitOfWork = unitOfWork(dataSource);
            template.update("INSERT INTO VERBS VALUES (1)");

            assertThatIllegalStateException().isThrownBy(() -> unitOfWork.execute("a chunk", () -> {
                unitOfWork.persistDisposition("DISP=(NEW,CATLG,DELETE)",
                        () -> template.update("DELETE FROM VERBS"));
                throw new IllegalStateException("the abend that triggered the disposition");
            }));

            assertThat(template.queryForObject("SELECT COUNT(*) FROM VERBS", Integer.class))
                    .as("the discard must not be rolled back by the abend that called for it")
                    .isZero();
        }

        @Test
        @DisplayName("a disposition name and a body are both required")
        void aDispositionNeedsBothArguments() {
            DatasetUnitOfWork unitOfWork = unitOfWork(singleConnection());

            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.persistDisposition(null, () -> null))
                    .withMessageContaining("disposition name is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> unitOfWork.persistDisposition("DISP=(NEW,CATLG,DELETE)", null))
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
    @DisplayName("The precondition a write enforces")
    class WritePrecondition {
        @Test
        @DisplayName("inside a unit of work, a write is permitted")
        void insideAUnitOfWorkItIsPermitted() {
            unitOfWork(singleConnection()).execute("a write", () -> {
                DatasetUnitOfWork.requireActiveToPersist("A write to the transaction master",
                        "TEST.TRANSACT.KSDS");
                return null;
            });
        }

        @Test
        @DisplayName("outside one it is refused, and the message says why the row would be lost")
        void outsideOneItIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> DatasetUnitOfWork.requireActiveToPersist(
                            "A write to the transaction master", "TEST.TRANSACT.KSDS"))
                    .withMessageContaining("changes stored records")
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("auto-commit: false")
                    .withMessageContaining("written, then discarded")
                    .withMessageContaining("TEST.TRANSACT.KSDS")
                    .withMessageContaining(DatasetUnitOfWork.class.getSimpleName() + ".execute")
                    .withMessageContaining(DatasetUnitOfWork.class.getSimpleName() + ".persistVerb");
        }

        @Test
        @DisplayName("it decides from the thread alone, so it needs no data source to reach a verdict")
        void itDecidesFromTheThreadAlone() {
            assertThat(DatasetUnitOfWork.active()).isFalse();
            assertThatIllegalStateException().isThrownBy(() -> DatasetUnitOfWork.requireActiveToPersist(
                    "A write to the transaction master", "TEST.TRANSACT.KSDS"));

            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatCode(() -> DatasetUnitOfWork.requireActiveToPersist(
                        "A write to the transaction master", "TEST.TRANSACT.KSDS"))
                        .doesNotThrowAnyException();
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }

        @Test
        @DisplayName("both arguments are required, so a refusal can always name what it refused")
        void bothArgumentsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DatasetUnitOfWork.requireActiveToPersist(null, "TEST.DS"))
                    .withMessageContaining("operation name is required");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DatasetUnitOfWork.requireActiveToPersist("A write", null))
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
