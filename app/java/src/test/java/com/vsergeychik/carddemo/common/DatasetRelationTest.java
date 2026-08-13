package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Proves the module's one data-access contract, one obligation at a time.
 */
@DisplayName("DatasetRelation - the one deployment data-access contract")
class DatasetRelationTest {
    private static final String DSNAME = "TEST.CARDDEMO.ACCTDATA.VSAM.KSDS";

    private static final int RECORD_LENGTH = 300;

    private static final String COLUMN = "VSAM_RECORD_IMAGE";

    private static final String IMAGE = "\"" + COLUMN + "\"";

    private static final String RELATION = "\"" + DSNAME + "\"";

    private static DatasetRelation described() {
        DatasetRelation relation = DatasetRelation.of(DSNAME, RECORD_LENGTH);
        relation.rememberRecordImageColumn(COLUMN);
        return relation;
    }

    @Nested
    @DisplayName("The z/OS dataset-name grammar decides what may reach a SQL identifier")
    class Grammar {
        @ParameterizedTest(name = "[{0}] is a well-formed z/OS dataset name")
        @ValueSource(strings = {
            "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS",
            "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS",
            "AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH",
            "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS",
            "AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH",
            "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS",
            "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS",
            "AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS",
            "A", "#Q.@R.$S", "A-B.C1", "ABCDEFGH.ABCDEFGH",
            "AWS.M2.CARDDEMO.DALYREJS(+1)", "AWS.M2.CARDDEMO.TRANREPT(+1)", "A.B(-1)", "A.B(+12)",
            "A.B--C",
            "Test.M2.acct.ksds", "aB.cD",
        })
        @DisplayName("accepts every name the platform permits, including the eight the CSD declares")
        void acceptsAWellFormedName(String dsname) {
            assertThat(DatasetRelation.requireDatasetName(dsname)).isEqualTo(dsname);
            assertThat(DatasetRelation.of(dsname, 1).dsname()).isEqualTo(dsname);
        }

        @ParameterizedTest(name = "[{0}] is refused: {1}")
        @CsvSource(delimiter = '|', value = {
            "A.B'C            | a quotation mark",
            "A.B\"C           | a double quote",
            "A.B;DROP         | a statement separator",
            "A.B C            | a space",
            "A.B,C            | a comma",
            "A.B/*C           | a block comment",
            "A.B%C            | a wildcard",
            "''               | an empty name",
            "A..B             | an empty qualifier",
            ".AB              | a leading separator",
            "AB.              | a trailing separator",
            "TOOLONGQUALIFIER | a nine-character qualifier",
            "1LEADING         | a digit in the first position",
            "-LEADING         | a hyphen in the first position",
            "A.B(1)           | a parenthesised suffix that is not a relative generation",
            "A.B(+)           | a relative generation with no number",
            "A.B(+1          | an unterminated suffix",
            "A.B(+1x)         | a non-numeric generation",
            "A.B(*11)         | a suffix whose sign position is neither plus nor minus",
            "A.B(+11          | a suffix long enough to look complete but never closed",
            "A.B(+ 1)         | a generation digit below the digit range",
            "A.B(-1-1)        | a second sign where a digit belongs",
        })
        @DisplayName("refuses everything else, naming the position rather than a category")
        void refusesAMalformedName(String dsname, String why) {
            assertThatIllegalArgumentException()
                    .as("%s", why)
                    .isThrownBy(() -> DatasetRelation.requireDatasetName(dsname))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("refuses a name longer than the 44 characters z/OS allows")
        void refusesAnOverLongName() {
            String tooLong = "ABCDEFGH.ABCDEFGH.ABCDEFGH.ABCDEFGH.ABCDEFGH.A";

            assertThat(tooLong).hasSizeGreaterThan(44);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DatasetRelation.requireDatasetName(tooLong))
                    .withMessageContaining("44 characters");
        }

        @Test
        @DisplayName("a name is required, and is never defaulted")
        void refusesAnAbsentName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DatasetRelation.requireDatasetName(null))
                    .withMessageContaining("carddemo.datasets");
        }

        @Test
        @DisplayName("a record width below one byte is refused: the width comes from the copybook")
        void refusesAnImpossibleRecordWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DatasetRelation.of(DSNAME, 0))
                    .withMessageContaining("at least 1 byte");
        }

        @Test
        @DisplayName("the delimited rendering doubles an embedded quote, for whatever is handed to it")
        void delimitDoublesAnEmbeddedQuote() {
            assertThat(DatasetRelation.delimit("PLAIN")).isEqualTo("\"PLAIN\"");
            assertThat(DatasetRelation.delimit("ODD\"NAME")).isEqualTo("\"ODD\"\"NAME\"");
            assertThatNullPointerException().isThrownBy(() -> DatasetRelation.delimit(null));
        }

        @Test
        @DisplayName("the relation exposes the configured name and its delimited rendering unchanged")
        void exposesTheConfiguredName() {
            DatasetRelation relation = DatasetRelation.of(DSNAME, RECORD_LENGTH);

            assertThat(relation.dsname()).isEqualTo(DSNAME);
            assertThat(relation.identifier()).isEqualTo(RELATION);
            assertThat(relation.recordLength()).isEqualTo(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The record-image column is discovered, never assumed")
    class RecordImageColumn {
        @Test
        @DisplayName("its position is the contract, and its name is read from result-set metadata")
        void theNameComesFromMetadataAtTheDeclaredPosition() throws SQLException {
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(3);
            when(metaData.getColumnName(DatasetRelation.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(COLUMN);

            assertThat(DatasetRelation.RECORD_IMAGE_COLUMN_INDEX).isEqualTo(1);
            assertThat(DatasetRelation.recordImageColumnOf(metaData)).isEqualTo(COLUMN);
        }

        @Test
        @DisplayName("a relation with no column at that position yields no name")
        void aRelationWithNoSuchColumnYieldsNothing() throws SQLException {
            ResultSetMetaData empty = mock(ResultSetMetaData.class);
            when(empty.getColumnCount()).thenReturn(0);

            assertThat(DatasetRelation.recordImageColumnOf(empty)).isNull();
            assertThat(DatasetRelation.recordImageColumnOf(null)).isNull();
        }

        @Test
        @DisplayName("the discovered name is remembered, and forgotten by a close")
        void theNameIsRememberedAndForgotten() {
            DatasetRelation relation = DatasetRelation.of(DSNAME, RECORD_LENGTH);

            assertThat(relation.recordImageColumn()).isEmpty();
            assertThat(relation.rememberRecordImageColumn(COLUMN)).isEqualTo(COLUMN);
            assertThat(relation.recordImageColumn()).contains(COLUMN);

            relation.forgetRecordImageColumn();
            assertThat(relation.recordImageColumn()).isEmpty();
        }

        @ParameterizedTest(name = "a described name of [{0}] is refused")
        @ValueSource(strings = { "", "   ", "COL\tUMN", "COL\nUMN" })
        @DisplayName("an unusable described name is refused rather than composed into a statement")
        void anUnusableNameIsRefused(String described) {
            DatasetRelation relation = DatasetRelation.of(DSNAME, RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> relation.rememberRecordImageColumn(described));
        }

        @Test
        @DisplayName("an absent described name is refused, and says why it cannot be worked around")
        void anAbsentNameIsRefused() {
            DatasetRelation relation = DatasetRelation.of(DSNAME, RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> relation.rememberRecordImageColumn(null))
                    .withMessageContaining("with no name");
        }
    }

    @Nested
    @DisplayName("Statement composition is identical in form for every dataset")
    class Statements {
        @Test
        @DisplayName("the describe names the relation and returns no row")
        void theDescribeTransfersNothing() {
            assertThat(described().describeStatement())
                    .isEqualTo("SELECT * FROM " + RELATION + " WHERE 1 = 0");
        }

        @Test
        @DisplayName("three orderings, and each names what it orders by: none, the ordinal, the key")
        void orderingIsStatedForWhatEachReadDependsOn() {
            DatasetRelation relation = described();

            assertThat(relation.selectAll()).isEqualTo("SELECT * FROM " + RELATION);
            assertThat(relation.selectAllInPhysicalSequence(PhysicalSequence.of("_ROWID_")))
                    .isEqualTo("SELECT * FROM " + RELATION + " ORDER BY _ROWID_ ASC");
            assertThat(relation.selectAllAscending(COLUMN))
                    .isEqualTo("SELECT * FROM " + RELATION + " ORDER BY " + IMAGE + " ASC");
        }

        @Test
        @DisplayName("a physical-sequential read without an ordinal is refused, naming the key that "
                + "supplies one")
        void aPhysicalSequentialReadWithoutAnOrdinalIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> described().selectAllInPhysicalSequence(null))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY);
        }

        @Test
        @DisplayName("the physical ordinal never orders by the record image, which is a different order")
        void thePhysicalOrderingIsNotAnImageOrdering() {
            String statement = described()
                    .selectAllInPhysicalSequence(PhysicalSequence.of("RECORD_ORDINAL"));

            assertThat(statement)
                    .doesNotContain(IMAGE)
                    .endsWith(" ORDER BY RECORD_ORDINAL ASC");
            assertThat(statement.split(" ORDER BY ", -1)).hasSize(2);
        }

        @Test
        @DisplayName("a browse advances strictly past a position, in either direction")
        void theBrowseStepsExcludeTheirPosition() {
            DatasetRelation relation = described();

            assertThat(relation.selectAfterAscending(COLUMN))
                    .isEqualTo("SELECT * FROM " + RELATION + " WHERE (" + IMAGE + " > ? OR " + IMAGE
                            + " IS NULL) ORDER BY " + IMAGE + " ASC");
            assertThat(relation.selectBeforeDescending(COLUMN))
                    .isEqualTo("SELECT * FROM " + RELATION + " WHERE (" + IMAGE + " < ? OR " + IMAGE
                            + " IS NULL) ORDER BY " + IMAGE + " DESC");
        }

        @Test
        @DisplayName("a STARTBR positions at or after a key, ascending")
        void theAnchorIncludesItsKey() {
            assertThat(described().selectFromKeyAscending(COLUMN))
                    .isEqualTo("SELECT * FROM " + RELATION + " WHERE (" + IMAGE + " >= ? OR " + IMAGE
                            + " IS NULL) ORDER BY " + IMAGE + " ASC");
        }

        @Test
        @DisplayName("every positioning read keeps an UNREADABLE row in its candidate set")
        void thePositioningReadsSeeUnreadableRows() {
            DatasetRelation relation = described();

            assertThat(List.of(relation.selectAfterAscending(COLUMN),
                            relation.selectBeforeDescending(COLUMN),
                            relation.selectFromKeyAscending(COLUMN)))
                    .allSatisfy(statement -> assertThat(statement)
                            .contains("OR " + IMAGE + " IS NULL")
                            .contains(" WHERE (")
                            .contains(") ORDER BY "));
        }

        @Test
        @DisplayName("a keyed read and a rewrite are deliberately NOT widened to unreadable rows")
        void theKeyedOperationsAreNotWidened() {
            DatasetRelation relation = described();

            assertThat(List.of(relation.selectByKey(COLUMN), relation.selectByKeyForUpdate(COLUMN),
                            relation.rewriteByKey(COLUMN)))
                    .allSatisfy(statement -> assertThat(statement).doesNotContain("IS NULL"));
        }

        @Test
        @DisplayName("the unreadable-row probe names only the rows whose image is absent")
        void theUnreadableRowProbeSelectsOnlyThoseRows() {
            assertThat(described().selectUnreadableRows(COLUMN))
                    .isEqualTo("SELECT * FROM " + RELATION + " WHERE " + IMAGE + " IS NULL");
        }

        @Test
        @DisplayName("the keyed read carries its predicate, and the locking read adds only the lock")
        void theKeyedReadCarriesItsPredicate() {
            DatasetRelation relation = described();
            String keyed = "SELECT * FROM " + RELATION + " WHERE " + IMAGE + " LIKE ? ESCAPE '\\'"
                    + " ORDER BY " + IMAGE + " ASC";

            assertThat(relation.selectByKey(COLUMN)).isEqualTo(keyed);
            assertThat(relation.selectByKeyForUpdate(COLUMN)).isEqualTo(keyed + " FOR UPDATE");
        }

        @Test
        @DisplayName("the rewrite replaces the image of the records the key selects")
        void theRewriteIsKeyed() {
            assertThat(described().rewriteByKey(COLUMN))
                    .isEqualTo("UPDATE " + RELATION + " SET " + IMAGE + " = ? WHERE " + IMAGE
                            + " LIKE ? ESCAPE '\\'");
        }

        @Test
        @DisplayName("the insert names only the record-image column")
        void theInsertNamesOnlyTheImage() {
            assertThat(described().insertRecordImage(COLUMN))
                    .isEqualTo("INSERT INTO " + RELATION + " (" + IMAGE + ") VALUES (?)");
        }

        @Test
        @DisplayName("the output-only insert names no column at all, and still delimits the dataset")
        void theOutputOnlyInsertNamesNoColumn() {
            assertThat(described().insertRecordImage())
                    .isEqualTo("INSERT INTO " + RELATION + " VALUES (?)");
        }

        @Test
        @DisplayName("the output-only insert needs no discovery, so it works before any describe")
        void theOutputOnlyInsertNeedsNoDiscovery() {
            DatasetRelation undescribed = DatasetRelation.of(DSNAME, 100);

            assertThat(undescribed.recordImageColumn()).isEmpty();
            assertThat(undescribed.insertRecordImage())
                    .isEqualTo("INSERT INTO " + RELATION + " VALUES (?)");
        }

        @Test
        @DisplayName("neither insert form declares a schema: no DDL verb, no column type (G44)")
        void neitherInsertFormDeclaresASchema() {
            DatasetRelation relation = described();

            assertThat(relation.insertRecordImage())
                    .doesNotContain("CREATE", "ALTER", "DROP", "VARCHAR", "CHAR(", "PRIMARY KEY");
            assertThat(relation.insertRecordImage(COLUMN))
                    .doesNotContain("CREATE", "ALTER", "DROP", "VARCHAR", "CHAR(", "PRIMARY KEY");
        }

        @Test
        @DisplayName("no statement carries a pagination or row-limit clause: the row limit is the API's")
        void noStatementCarriesARowLimit() {
            DatasetRelation relation = described();

            assertThat(relation.selectAllAscending(COLUMN))
                    .doesNotContain("LIMIT", "OFFSET", "FETCH", "TOP", "ROWNUM");
            assertThat(relation.selectByKey(COLUMN))
                    .doesNotContain("LIMIT", "OFFSET", "FETCH", "TOP", "ROWNUM");
        }
    }

    @Nested
    @DisplayName("A key is an offset and a length, expressed as an escaped LIKE")
    class Keys {
        @Test
        @DisplayName("a key at offset zero is the key followed by the any-sequence wildcard")
        void aLeadingKeyNeedsNoWildcardPrefix() {
            assertThat(new KeySpan(0, 11).pattern("00000000001")).isEqualTo("00000000001%");
        }

        @Test
        @DisplayName("a key at an offset is preceded by exactly that many single-character wildcards")
        void anInteriorKeyIsPrecededByItsOffset() {
            assertThat(new KeySpan(25, 11).pattern("00000000050"))
                    .isEqualTo("_".repeat(25) + "00000000050%");
            assertThat(new KeySpan(16, 11).pattern("00000000050"))
                    .isEqualTo("_".repeat(16) + "00000000050%");
        }

        @ParameterizedTest(name = "the metacharacter [{0}] in a key is escaped")
        @CsvSource(delimiter = '|', value = {
            "%%%%%%%%%%% | \\%\\%\\%\\%\\%\\%\\%\\%\\%\\%\\%",
            "___________ | \\_\\_\\_\\_\\_\\_\\_\\_\\_\\_\\_",
        })
        @DisplayName("every LIKE metacharacter inside a key is escaped, so it matches only itself")
        void metacharactersAreEscaped(String key, String expectedBody) {
            assertThat(new KeySpan(0, 11).pattern(key)).isEqualTo(expectedBody + "%");
        }

        @Test
        @DisplayName("the escape character itself is escaped")
        void theEscapeCharacterIsEscaped() {
            assertThat(new KeySpan(0, 3).pattern("a\\b")).isEqualTo("a\\\\b%");
        }

        @Test
        @DisplayName("a key of the wrong width is refused rather than becoming a wider prefix match")
        void aKeyMustBeExactlyItsDeclaredWidth() {
            KeySpan span = new KeySpan(0, 11);

            assertThatIllegalArgumentException().isThrownBy(() -> span.pattern("0000000001"))
                    .withMessageContaining("exactly its declared width");
            assertThatIllegalArgumentException().isThrownBy(() -> span.pattern("000000000012"));
            assertThatNullPointerException().isThrownBy(() -> span.pattern(null));
        }

        @Test
        @DisplayName("a span's offset and length are validated at declaration")
        void aSpanIsValidatedAtDeclaration() {
            assertThatIllegalArgumentException().isThrownBy(() -> new KeySpan(-1, 11))
                    .withMessageContaining("absolute and 0-based");
            assertThatIllegalArgumentException().isThrownBy(() -> new KeySpan(0, 0))
                    .withMessageContaining("at least 1 byte");
            assertThat(new KeySpan(25, 11).offset()).isEqualTo(25);
            assertThat(new KeySpan(25, 11).length()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("A refusal is read from the backend, never invented")
    class Diagnostics {
        @Test
        @DisplayName("the SQLSTATE, the vendor code and the exception type all survive")
        void theDriversOwnWordsSurvive() {
            SQLException reported = new SQLException("no route to host", "08001", 17_002);

            BackendDiagnostic diagnostic = BackendDiagnostic.of(reported);

            assertThat(diagnostic.sqlState()).isEqualTo("08001");
            assertThat(diagnostic.vendorCode()).isEqualTo(17_002);
            assertThat(diagnostic.exceptionType()).isEqualTo(SQLException.class.getName());
            assertThat(diagnostic.toString()).doesNotContain("no route to host");
        }

        @Test
        @DisplayName("the SQLSTATE is found through a wrapper chain, and the wrapper's type is kept too")
        void theChainIsWalked() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(new DataAccessResourceFailureException(
                    "wrapped", new IllegalStateException(new SQLException("deep", "40001", 60))));

            assertThat(diagnostic.sqlState()).isEqualTo("40001");
            assertThat(diagnostic.vendorCode()).isEqualTo(60);
            assertThat(diagnostic.exceptionType())
                    .isEqualTo(DataAccessResourceFailureException.class.getName());
        }

        @Test
        @DisplayName("a failure with no SQLException in it reports no SQLSTATE, and does not invent one")
        void noSqlExceptionMeansNoSqlState() {
            BackendDiagnostic diagnostic =
                    BackendDiagnostic.of(new DataAccessResourceFailureException("no cause at all"));

            assertThat(diagnostic.sqlState()).isNull();
            assertThat(diagnostic.vendorCode()).isZero();
            assertThat(diagnostic.sqlStateClass()).isEmpty();
            assertThat(diagnostic.isClass(BackendDiagnostic.CONNECTION_EXCEPTION_CLASS)).isFalse();
        }

        @Test
        @DisplayName("describe() does not call it a backend refusal when no SQLException was found: "
                + "nothing was asked of the backend, so the failure arose above the driver")
        void describeDoesNotBlameTheBackendWhenNoSqlExceptionWasFound() {
            BackendDiagnostic diagnostic =
                    BackendDiagnostic.of(new DataAccessResourceFailureException("no cause at all"));

            assertThat(diagnostic.describe())
                    .doesNotContain("backend refusal")
                    .doesNotContain("SQLSTATE not reported")
                    .doesNotContain("vendor code")
                    .contains("no backend diagnostic")
                    .contains("no SQLException in the cause chain")
                    .contains("arose above the driver")
                    .contains(DataAccessResourceFailureException.class.getName());
        }

        @Test
        @DisplayName("describe() does report a backend refusal, with the SQLSTATE and vendor code, "
                + "when the driver actually reported one")
        void describeReportsTheBackendWhenTheDriverDid() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(new DataAccessResourceFailureException(
                    "wrapped", new SQLException("deep", "23505", 23505)));

            assertThat(diagnostic.describe())
                    .contains("backend refusal")
                    .contains("SQLSTATE 23505")
                    .contains("vendor code 23505")
                    .contains(DataAccessResourceFailureException.class.getName())
                    .doesNotContain("no backend diagnostic");
        }

        @ParameterizedTest(name = "SQLSTATE {0} classifies as {1}")
        @CsvSource({
            "08001, connection",
            "08006, connection",
            "22001, data",
            "23505, integrity",
            "40001, rollback",
            "42000, syntaxOrAccess",
        })
        @DisplayName("classification is by SQLSTATE class, which the SQL standard fixes")
        void classificationIsByStandardClass(String sqlState, String expected) {
            BackendDiagnostic diagnostic =
                    BackendDiagnostic.of(new SQLException("reported", sqlState, 1));

            assertThat(diagnostic.sqlStateClass()).contains(sqlState.substring(0, 2));
            assertThat(diagnostic.connectionFailure()).isEqualTo("connection".equals(expected));
            assertThat(diagnostic.dataException()).isEqualTo("data".equals(expected));
            assertThat(diagnostic.integrityViolation()).isEqualTo("integrity".equals(expected));
            assertThat(diagnostic.transactionRollback()).isEqualTo("rollback".equals(expected));
            assertThat(diagnostic.syntaxOrAccessViolation())
                    .isEqualTo("syntaxOrAccess".equals(expected));
        }

        @Test
        @DisplayName("a SQLSTATE too short to carry a class yields no class rather than a guess")
        void aTruncatedSqlStateYieldsNoClass() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(new SQLException("odd", "0", 1));

            assertThat(diagnostic.sqlStateClass()).isEmpty();
            assertThat(diagnostic.connectionFailure()).isFalse();
        }

        @Test
        @DisplayName("the rendered line quotes the driver's own values and no record content")
        void theRenderedLineCarriesNoRecordContent() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(
                    new SQLException("value '4444333322221111' rejected", "22001", 1_400));

            assertThat(diagnostic.describe())
                    .isEqualTo("backend refusal: SQLSTATE 22001, vendor code 1400, raised as "
                            + SQLException.class.getName())
                    .doesNotContain("4444333322221111");
        }

        @Test
        @DisplayName("the driver's message is not carried at all, so nothing can render it")
        void theDriversMessageIsNotCarriedAtAll() {
            String pan = "4444333322221111";
            BackendDiagnostic diagnostic = BackendDiagnostic.of(
                    new SQLException("value '" + pan + "' rejected", "22001", 1_400));

            assertThat(BackendDiagnostic.class.getRecordComponents())
                    .as("a component holding the driver's message text is what re-introduces the leak")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("sqlState", "vendorCode", "exceptionType");
            assertThat(diagnostic.toString()).doesNotContain(pan);
            assertThat(diagnostic.describe()).doesNotContain(pan);
        }

        @Test
        @DisplayName("a control character in a driver's message cannot reach a rendering either")
        void aControlCharacterCannotReachARendering() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(new SQLException(
                    "rejected\r\n2026-01-01 INFO  all datasets verified", "22001", 1));

            assertThat(diagnostic.describe()).doesNotContain("\n").doesNotContain("\r");
            assertThat(diagnostic.toString()).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("a diagnostic requires something to diagnose")
        void aDiagnosticRequiresAFailure() {
            assertThatNullPointerException().isThrownBy(() -> BackendDiagnostic.of(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new BackendDiagnostic("08001", 1, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> BackendDiagnostic.of(new SQLException("m")).isClass(null));
        }
    }
}
