package com.vsergeychik.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.CardXrefRepository.ReadResult;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Tests for {@link CardXrefRepository}: the {@code CCXREF} base KSDS and the {@code CXACAIX}
 * alternate-index path over it.
 *
 * <p>Plain JUnit 5 with a mocked {@link JdbcTemplate} and no application context. That is deliberate
 * and it is what makes the whole class reachable: production connectivity cannot be exercised from this
 * build (risk R-E), so every outcome the COBOL enumerates - {@code '00'}, {@code '10'}, {@code '22'},
 * {@code '23'} and the {@code WHEN OTHER} arm - has to be provable without a backend. Driving all of
 * them is gate G47, and doing it from a plain unit test is what lets the {@code card} package meet the
 * per-package branch-coverage gate G49 honestly rather than by exercising a happy path.
 *
 * <p>The record images the mock returns are built through {@link CardXrefRecord} rather than typed out,
 * so a change to the copybook layout would break these tests at their source instead of leaving them
 * asserting a stale shape. The one place a literal image appears is the fixture round-trip, which reads
 * the real 36-byte rows of {@code app/data/ASCII/cardxref.txt}.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule governs
 * this file.
 */
@DisplayName("CardXrefRepository - one cluster, two access paths, three operations")
class CardXrefRepositoryTest {

    // =================================================================================================
    // Fixtures. The dataset names here are TEST values and are deliberately not the production ones:
    // this suite proves that names come from configuration, so putting a production name in it would
    // undermine the very thing being proved.
    // =================================================================================================

    /** The code page the fixtures are in, always named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name for the base cluster. */
    private static final String BASE_DS = "TEST.XREF.BASE";

    /** A stand-in dataset name for the alternate-index path - deliberately a different name. */
    private static final String ALT_DS = "TEST.XREF.ACCTPATH";

    /** The statement the repository composes for the base cluster. */
    private static final String BASE_SQL = "SELECT * FROM \"" + BASE_DS + "\" ORDER BY 1";

    /** The statement the repository composes for the alternate-index path. */
    private static final String ALT_SQL = "SELECT * FROM \"" + ALT_DS + "\" ORDER BY 1";

    /** The first row of {@code app/data/ASCII/cardxref.txt}: card 0500024453765740, customer and account 50. */
    private static final String CARD_1 = "0500024453765740";

    /** The second fixture row's card number. */
    private static final String CARD_2 = "0683586198171516";

    /** The codec every assertion uses, over the explicitly named code page. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /**
     * Builds a base-cluster binding: a KSDS, copybook width, no base of its own.
     *
     * @param dsname the dataset name
     * @return the binding
     */
    private static DatasetBinding ksds(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, CardXrefRecord.RECORD_LENGTH,
                "CVACT03Y", null, null, null);
    }

    /**
     * Builds an alternate-index path binding over {@code CCXREF}, keyed on {@code XREF-ACCT-ID}.
     *
     * @param dsname the dataset name
     * @return the binding
     */
    private static DatasetBinding aixPath(String dsname) {
        return new DatasetBinding(dsname, "aix-path", false, "FB", null, CardXrefRecord.RECORD_LENGTH,
                "CVACT03Y", null, CardXrefRepository.BASE_DD_NAME,
                CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
    }

    /**
     * Assembles a two-entry catalogue under the two keys the repository looks up.
     *
     * @param base           the {@code CCXREF} binding, or {@code null} to omit the entry
     * @param alternateIndex the {@code CXACAIX} binding, or {@code null} to omit the entry
     * @return the catalogue
     */
    private static DatasetBindings bindings(DatasetBinding base, DatasetBinding alternateIndex) {
        DatasetBindings catalogue = new DatasetBindings();
        if (base != null) {
            catalogue.put(CardXrefRepository.BASE_DD_NAME, base);
        }
        if (alternateIndex != null) {
            catalogue.put(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, alternateIndex);
        }
        return catalogue;
    }

    /**
     * Assembles the catalogue the majority of these tests use: both entries present and both valid.
     *
     * @return a catalogue whose two entries are both valid
     */
    private static DatasetBindings validBindings() {
        return bindings(ksds(BASE_DS), aixPath(ALT_DS));
    }

    /**
     * Builds a repository over a mocked template and a valid catalogue.
     *
     * @param jdbcTemplate the mocked template
     * @return the repository
     */
    private static CardXrefRepository repository(JdbcTemplate jdbcTemplate) {
        return new CardXrefRepository(jdbcTemplate, validBindings(), ASCII);
    }

    /**
     * Renders a record as the fifty-character image a driver would present.
     *
     * @param cardNumber the card number
     * @param customerId the customer id
     * @param accountId  the account id
     * @return the fifty-character image
     */
    private static String image(String cardNumber, int customerId, long accountId) {
        return new String(new CardXrefRecord(cardNumber, customerId, accountId).encode(ASCII), ASCII);
    }

    /**
     * Stubs a statement to return the given record images.
     *
     * @param jdbcTemplate the mocked template
     * @param sql          the statement to stub
     * @param rows         the rows to return, which may contain a {@code null} element
     */
    private static void stubRows(JdbcTemplate jdbcTemplate, String sql, List<String> rows) {
        when(jdbcTemplate.query(eq(sql), ArgumentMatchers.<RowMapper<String>>any())).thenReturn(rows);
    }

    /**
     * Stubs a statement to fail the way a driver that cannot be reached fails.
     *
     * @param jdbcTemplate the mocked template
     * @param sql          the statement to stub
     */
    private static void stubFailure(JdbcTemplate jdbcTemplate, String sql) {
        when(jdbcTemplate.query(eq(sql), ArgumentMatchers.<RowMapper<String>>any()))
                .thenThrow(new DataAccessResourceFailureException("the dataset cannot be reached"));
    }

    // =================================================================================================
    // Startup. Everything checkable about the configuration is checked in the constructor, so a
    // misconfiguration fails at context refresh rather than at the first read.
    // =================================================================================================

    @Nested
    @DisplayName("Construction is configuration-bound and refuses to guess")
    class Construction {

        @Test
        @DisplayName("A valid catalogue resolves both dataset names from configuration")
        void aValidCatalogueResolvesBothDatasetNames() {
            CardXrefRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.baseDatasetName()).isEqualTo(BASE_DS);
            assertThat(repository.alternateIndexDatasetName()).isEqualTo(ALT_DS);
        }

        @Test
        @DisplayName("Gate G45: the base and the alternate-index path are DIFFERENT dataset names")
        void theTwoAccessPathsResolveDifferentDatasetNames() {
            CardXrefRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.baseDatasetName())
                    .isNotEqualTo(repository.alternateIndexDatasetName());
        }

        @Test
        @DisplayName("The two CICS FILE names are the eight-byte PIC X(08) literals the programs declare")
        void theCicsFileNamesArePaddedToEightBytes() {
            CardXrefRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.baseFileNameForCics())
                    .isEqualTo("CCXREF  ")
                    .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH);
            assertThat(repository.alternateIndexFileNameForCics())
                    .isEqualTo("CXACAIX ")
                    .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH);
        }

        @Test
        @DisplayName("A base binding whose record length is not 50 is rejected, naming the copybook")
        void aBaseBindingOfTheWrongWidthIsRejected() {
            DatasetBindings catalogue = bindings(
                    new DatasetBinding(BASE_DS, "ksds", false, "FB", null, 36, "CVACT03Y", null, null,
                            null),
                    aixPath(ALT_DS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII))
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME)
                    .withMessageContaining("CVACT03Y")
                    .withMessageContaining("36");
        }

        @Test
        @DisplayName("An alternate-index binding whose record length is not 50 is rejected too")
        void anAlternateIndexBindingOfTheWrongWidthIsRejected() {
            DatasetBindings catalogue = bindings(ksds(BASE_DS),
                    new DatasetBinding(ALT_DS, "aix-path", false, "FB", null, 50 + 1, "CVACT03Y", null,
                            CardXrefRepository.BASE_DD_NAME,
                            CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("Gate G45 asserted: an alternate-index path that names a different base is rejected")
        void anAlternateIndexOverTheWrongBaseIsRejected() {
            DatasetBindings catalogue = bindings(ksds(BASE_DS),
                    new DatasetBinding(ALT_DS, "aix-path", false, "FB", null,
                            CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, "CARDDAT",
                            CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII))
                    .withMessageContaining("CARDDAT")
                    .withMessageContaining("gate G45");
        }

        @Test
        @DisplayName("Gate G45 asserted: an alternate-index path keyed on the wrong field is rejected")
        void anAlternateIndexOnTheWrongKeyIsRejected() {
            DatasetBindings catalogue = bindings(ksds(BASE_DS),
                    new DatasetBinding(ALT_DS, "aix-path", false, "FB", null,
                            CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null,
                            CardXrefRepository.BASE_DD_NAME, CardXrefRecord.XREF_CUST_ID_NAME));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII))
                    .withMessageContaining(CardXrefRecord.XREF_CUST_ID_NAME)
                    .withMessageContaining(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
        }

        @Test
        @DisplayName("A base cluster that claims to be an index over something else is rejected")
        void aBaseThatDeclaresItsOwnBaseIsRejected() {
            DatasetBindings catalogue = bindings(
                    new DatasetBinding(BASE_DS, "ksds", false, "FB", null,
                            CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, "CARDDAT", null),
                    aixPath(ALT_DS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII))
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME)
                    .withMessageContaining("IS the base cluster");
        }

        @Test
        @DisplayName("An absent dataset name is rejected, and so is a blank one")
        void anAbsentOrBlankDatasetNameIsRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds(null), aixPath(ALT_DS)), ASCII))
                    .withMessageContaining("gate G46");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds(BASE_DS), aixPath("   ")), ASCII))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("A dataset name holding a control character is rejected, naming the position")
        void aDatasetNameWithAControlCharacterIsRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds("TEST.X\nREF"), aixPath(ALT_DS)), ASCII))
                    .withMessageContaining("control character at position 6");
        }

        @Test
        @DisplayName("An unconfigured DD name is reported by the catalogue, not defaulted")
        void anUnconfiguredDdNameIsReported() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(null, aixPath(ALT_DS)), ASCII))
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds(BASE_DS), null), ASCII))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("Every collaborator is required: none is defaulted and none is optional")
        void everyCollaboratorIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardXrefRepository(null, validBindings(), ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), validBindings(),
                            null));
        }

        @Test
        @DisplayName("The declared geometry is carried from the copybook, never restated")
        void theDeclaredGeometryComesFromTheCopybook() {
            assertThat(CardXrefRepository.RECORD_LENGTH).isEqualTo(50);
            assertThat(CardXrefRepository.CARD_NUMBER_KEY_LENGTH).isEqualTo(16);
            assertThat(CardXrefRepository.ACCOUNT_ID_KEY_LENGTH).isEqualTo(11);
            assertThat(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD).isEqualTo("XREF-ACCT-ID");
            assertThat(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX).isOne();
            assertThat(CardXrefRepository.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(CardXrefRepository.CICS_RESP2_NOT_APPLICABLE).isZero();
            assertThat(CardXrefRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(FileStatus.outcomeOfStatus(CardXrefRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(Outcome.OTHER);
        }
    }

    // =================================================================================================
    // The base keyed read - app/cbl/COTRN02C.cbl:609-637 READ-CCXREF-FILE, plus the four batch keyed
    // reads. All three EVALUATE arms, and every I/O path that can reach them.
    // =================================================================================================

    @Nested
    @DisplayName("readByCardNumber - the CCXREF base key, XREF-CARD-NUM at offset 0")
    class BaseKeyedRead {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL): a matching record comes back with status '00'")
        void aMatchingRecordIsFound() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NORMAL);
            assertThat(result.cicsResp2()).isEqualTo(CardXrefRepository.CICS_RESP2_NOT_APPLICABLE);
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.record()).contains(new CardXrefRecord(CARD_1, 50, 50L));
        }

        @Test
        @DisplayName("Gate G45: the base read addresses the BASE dataset, never the alternate path")
        void theBaseReadAddressesTheBaseDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L)));
            // The alternate-index statement is stubbed to a DIFFERENT record. If the base read reached
            // for it, the assertion below would see account 999 instead of 50.
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 999, 999L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND): an unmatched key is status '23' and is NOT an exception")
        void anUnmatchedKeyIsNotFound() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTFND);
            assertThat(result.record()).isEmpty();
            assertThat(result.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("An unmatched key is NOT end of file: a keyed read has not reached the end of anything")
        void anUnmatchedKeyIsNotEndOfFile() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, Collections.emptyList());

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.status()).isNotEqualTo(FileStatus.END_OF_FILE);
        }

        @Test
        @DisplayName("Two records on one base key report DUPREC and hand back the first")
        void aDuplicateBaseKeyReportsDuprec() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L), image(CARD_1, 77, 77L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.DUPREC);
            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(50);
            assertThat(result.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("WHEN OTHER: an unreachable dataset is a permanent-error status, never a throw")
        void anUnreachableDatasetIsReportedAsAStatus() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubFailure(jdbc, BASE_SQL);

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(result.record()).isEmpty();
            assertThat(result.statusImage()).isEqualTo(
                    FileStatus.toStatusImage(CardXrefRepository.PERMANENT_ERROR_STATUS));
        }

        @Test
        @DisplayName("A template that yields no result at all is WHEN OTHER, not an empty dataset")
        void aNullResultIsNotAnEmptyDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, null);

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.isNotFound()).isFalse();
        }

        @Test
        @DisplayName("A row whose record image is absent is WHEN OTHER, never silently skipped")
        void aNullRowImageIsAnIoDefect() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, Arrays.asList(image(CARD_1, 50, 50L), null));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("The key is a PIC X MOVE: a short key is space-padded on the RIGHT")
        void aShortKeyIsSpacePaddedOnTheRight() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // "0500" occupies the first four bytes and the remaining twelve are the padding a
            // PIC X(16) receiver supplies. A record stored that way must be found by the short key.
            stubRows(jdbc, BASE_SQL, List.of(image("0500", 9, 9L)));

            ReadResult result = repository(jdbc).readByCardNumber("0500");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefCardNum())
                    .isEqualTo("0500            ")
                    .hasSize(CardXrefRepository.CARD_NUMBER_KEY_LENGTH);
        }

        @Test
        @DisplayName("The key is a PIC X MOVE: an over-long key is truncated on the RIGHT, not the left")
        void anOverLongKeyIsTruncatedOnTheRight() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L)));

            // Seventeen characters: the leading sixteen survive, which is the PIC X rule. Truncating on
            // the left instead would have kept "500024453765740X" and found nothing.
            ReadResult result = repository(jdbc).readByCardNumber(CARD_1 + "X");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
        }

        @Test
        @DisplayName("An all-spaces key is a legitimate lookup, not a missing argument")
        void anEmptyKeyLooksUpSpaces() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image("", 1, 1L)));

            ReadResult result = repository(jdbc).readByCardNumber("");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("An absent key is a defect in the caller, not a NOTFND outcome")
        void anAbsentKeyIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(null))
                    .withMessageContaining(CardXrefRecord.XREF_CARD_NUM_NAME);
        }

        @Test
        @DisplayName("Gate G19 / risk R-F: a 36-byte row is rejected, not quietly accepted")
        void aShortRowIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // Exactly what app/data/ASCII/cardxref.txt holds: the three fields and no trailing FILLER.
            stubRows(jdbc, BASE_SQL, List.of(CARD_1 + "000000050" + "00000000050"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(CARD_1))
                    .withMessageContaining("padToDeclaredWidth");
        }

        @Test
        @DisplayName("A row wider than the copybook is rejected as well")
        void anOverWideRowIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L) + " "));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(CARD_1));
        }
    }

    // =================================================================================================
    // The alternate-index keyed read - app/cbl/COACTVWC.cbl:723-770, COACTUPC:3655, COTRN02C:576-604
    // and COBIL00C:408-437. Two COBOL views of one eleven-byte span, and they must not diverge.
    // =================================================================================================

    @Nested
    @DisplayName("readByAccountIdViaAltIndex - the CXACAIX key, XREF-ACCT-ID at offset 25")
    class AlternateIndexKeyedRead {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL): the record comes back and carries the two fields COACTVWC moves")
        void aMatchingRecordCarriesTheFieldsTheCallerMoves() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(27L);

            assertThat(result.isFound()).isTrue();
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NORMAL);
            // app/cbl/COACTVWC.cbl:739-740 - MOVE XREF-CUST-ID TO CDEMO-CUST-ID and
            //                                MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM.
            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(27);
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(CARD_2);
        }

        @Test
        @DisplayName("Gate G45: the alternate read addresses the PATH dataset, never the base")
        void theAlternateReadAddressesThePathDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 111, 50L)));
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 222, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(222);
        }

        @Test
        @DisplayName("The key is a PIC 9 MOVE: it is zero-filled on the LEFT, so account 50 is 00000000050")
        void theKeyIsZeroFilledOnTheLeft() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isFound()).isTrue();
            // The stored span really is left-zero-filled; the match proves the key image was too.
            assertThat(image(CARD_1, 50, 50L)
                    .substring(CardXrefRecord.XREF_ACCT_ID_OFFSET,
                            CardXrefRecord.XREF_ACCT_ID_OFFSET + CardXrefRecord.XREF_ACCT_ID_LENGTH))
                    .isEqualTo("00000000050");
        }

        @Test
        @DisplayName("The two COBOL views of the key are identical on the wire")
        void bothKeyViewsProduceTheSameResult() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            // COTRN02C:582 and COBIL00C:414 pass the PIC 9(11) field; COACTVWC:729 and COACTUPC:3655
            // pass its PIC X(11) REDEFINES. One span, two pictures, one outcome.
            ReadResult numericView = repository.readByAccountIdViaAltIndex(50L);
            ReadResult alphanumericView = repository.readByAccountIdViaAltIndex("00000000050");

            assertThat(alphanumericView).isEqualTo(numericView);
            assertThat(alphanumericView.isFound()).isTrue();
        }

        @Test
        @DisplayName("The alphanumeric view is left-zero-filled too, so a short image still matches")
        void theAlphanumericViewIsLeftZeroFilled() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L)));

            assertThat(repository(jdbc).readByAccountIdViaAltIndex("50").isFound()).isTrue();
        }

        @Test
        @DisplayName("The alphanumeric view keeps the LOW-order digits when the image is too long")
        void theAlphanumericViewTruncatesOnTheLeft() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L)));

            // Twelve digits: the leading 9 is discarded and 00000000050 survives, which is the PIC 9
            // rule and the opposite of the PIC X rule applied to the base key.
            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex("900000000050");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("A non-digit key image is rejected: the span it stands for is a PIC 9(11)")
        void aNonDigitKeyImageIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByAccountIdViaAltIndex("0000000005A"));
        }

        @Test
        @DisplayName("An absent key image is a defect in the caller")
        void anAbsentKeyImageIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository(jdbc).readByAccountIdViaAltIndex((String) null))
                    .withMessageContaining(CardXrefRecord.XREF_ACCT_ID_NAME);
        }

        @Test
        @DisplayName("A negative account id has no unsigned representation and is rejected")
        void aNegativeAccountIdIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByAccountIdViaAltIndex(-1L));
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND): COACTVWC's DID-NOT-FIND-ACCT-IN-CARDXREF branch")
        void anUnmatchedAccountIsNotFound() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(99999999999L);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTFND);
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("A non-unique alternate key reports DUPKEY - not DUPREC - and returns the first")
        void aDuplicateAlternateKeyReportsDupkey() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // One account holding two cards: legitimate, and exactly the DUPKEY condition a CICS READ
            // through a path over a non-unique alternate index raises. Ordered by record image, so the
            // lower card number is first.
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, 50L), image(CARD_2, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.cicsResp())
                    .isEqualTo(FileStatus.DUPKEY)
                    .isNotEqualTo(FileStatus.DUPREC);
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
        }

        @Test
        @DisplayName("WHEN OTHER: an unreachable path, a null result and an absent row image")
        void everyIoFailurePathIsReportedAsOther() {
            JdbcTemplate unreachable = mock(JdbcTemplate.class);
            stubFailure(unreachable, ALT_SQL);
            assertThat(repository(unreachable).readByAccountIdViaAltIndex(50L).isOther()).isTrue();

            JdbcTemplate nullResult = mock(JdbcTemplate.class);
            stubRows(nullResult, ALT_SQL, null);
            assertThat(repository(nullResult).readByAccountIdViaAltIndex(50L).isOther()).isTrue();

            JdbcTemplate nullRow = mock(JdbcTemplate.class);
            stubRows(nullRow, ALT_SQL, Collections.singletonList(null));
            ReadResult result = repository(nullRow).readByAccountIdViaAltIndex(50L);
            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("The widest representable account id round-trips through the key")
        void theWidestAccountIdIsRepresentable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            long widest = CardXrefRecord.XREF_ACCT_ID_MAX_VALUE;
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 50, widest)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(widest);

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(widest);
        }
    }

    // =================================================================================================
    // The sequential browse - app/cbl/CBACT03C.cbl:118-134 open, :92-116 get-next, :136 close, driving
    // the whole program's loop at :74. Also CBSTM03B's 2000-XREFFILE-PROC open/read/close dispatch.
    // =================================================================================================

    @Nested
    @DisplayName("openBrowse - the CBACT03C sequential pass, in XREF-CARD-NUM order")
    class SequentialBrowse {

        @Test
        @DisplayName("A successful OPEN INPUT reports '00' and positions at the first record")
        void aSuccessfulOpenPositionsAtTheStart() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            try (BrowseCursor cursor = repository.openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(cursor.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(cursor.isOpen()).isTrue();
                assertThat(cursor.position()).isZero();
                assertThat(cursor.datasetName()).isEqualTo(BASE_DS);
            }
        }

        @Test
        @DisplayName("The whole CBACT03C loop: every record, in order, then end of file")
        void theWholeLoopReadsEveryRecordThenReportsEndOfFile() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));
            List<String> displayed = new ArrayList<>();

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                for (ReadResult next = cursor.readNext(); !next.isEndOfFile(); next = cursor.readNext()) {
                    assertThat(next.isFound()).isTrue();
                    assertThat(next.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
                    displayed.add(next.record().orElseThrow().xrefCardNum());
                }
                assertThat(cursor.position()).isEqualTo(2);
            }

            assertThat(displayed).containsExactly(CARD_1, CARD_2);
        }

        @Test
        @DisplayName("End of file is status '10', APPL-RESULT 16, and is reported repeatedly")
        void endOfFileIsIdempotent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, Collections.emptyList());

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult first = cursor.readNext();
                ReadResult second = cursor.readNext();

                assertThat(first.isEndOfFile()).isTrue();
                assertThat(first.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(first.cicsResp()).isEqualTo(FileStatus.ENDFILE);
                assertThat(first.applResult()).isEqualTo(FileStatus.APPL_EOF);
                assertThat(first.record()).isEmpty();
                assertThat(second).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("ERROR OPENING XREFFILE: a failed open reports 12 and stays unusable")
        void aFailedOpenIsReportedAndStaysUnusable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubFailure(jdbc, BASE_SQL);

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(cursor.openApplResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                assertThat(cursor.isOpen()).isFalse();

                // A caller that ignored openStatus still cannot mistake an unreachable dataset for an
                // empty one: the open's own status comes back, not a fresh end of file.
                ReadResult next = cursor.readNext();
                assertThat(next.isOther()).isTrue();
                assertThat(next.isEndOfFile()).isFalse();
                assertThat(next.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("A template that yields no result at all is a failed open, not an empty pass")
        void aNullResultIsAFailedOpen() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, null);

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.isOpen()).isFalse();
                assertThat(cursor.readNext().isOther()).isTrue();
            }
        }

        @Test
        @DisplayName("An absent record image mid-pass is WHEN OTHER, and the pass advances past it")
        void anAbsentRowImageIsReportedAndSkippedPast() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL,
                    Arrays.asList(image(CARD_1, 50, 50L), null, image(CARD_2, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.readNext().isFound()).isTrue();

                ReadResult broken = cursor.readNext();
                assertThat(broken.isOther()).isTrue();
                assertThat(broken.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.position()).isEqualTo(2);

                // A caller that chooses to continue reaches the next record rather than re-reading the
                // broken row for ever.
                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_2);
                assertThat(cursor.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("CLOSE reports '00', is idempotent, and leaves the cursor unusable")
        void closeIsIdempotentAndFinal() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L)));

            BrowseCursor cursor = repository(jdbc).openBrowse();
            assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            assertThat(cursor.isOpen()).isFalse();
        }

        @Test
        @DisplayName("Reading a closed pass is a defect in the caller, not a file status")
        void readingAClosedCursorThrows() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L)));

            BrowseCursor cursor = repository(jdbc).openBrowse();
            cursor.close();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(cursor::readNext)
                    .withMessageContaining(BASE_DS);
        }

        @Test
        @DisplayName("try-with-resources and an explicit close in the same block are both safe")
        void tryWithResourcesToleratesAnExplicitClose() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.readNext().isFound()).isTrue();
                assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            }
        }

        @Test
        @DisplayName("Two passes are independent: a @Repository singleton holds no browse position")
        void twoPassesAreIndependent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));
            CardXrefRepository repository = repository(jdbc);

            try (BrowseCursor first = repository.openBrowse();
                    BrowseCursor second = repository.openBrowse()) {
                first.readNext();
                first.readNext();

                assertThat(first.position()).isEqualTo(2);
                assertThat(second.position()).isZero();
                assertThat(second.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
            }
        }

        @Test
        @DisplayName("Gate G19: a malformed row is raised by the readNext that reaches it, not by the open")
        void aMalformedRowIsRaisedWhenItIsReached() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL,
                    List.of(image(CARD_1, 50, 50L), CARD_2 + "000000027" + "00000000027"));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                // The open itself decodes nothing, so it succeeds.
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.readNext().isFound()).isTrue();

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(cursor::readNext)
                        .withMessageContaining("padToDeclaredWidth");
            }
        }

        @Test
        @DisplayName("A pass reads the base cluster, never the alternate-index path")
        void aPassReadsTheBaseCluster() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, List.of(image(CARD_1, 111, 50L)));
            stubRows(jdbc, ALT_SQL, List.of(image(CARD_1, 222, 50L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.readNext().record().orElseThrow().xrefCustId()).isEqualTo(111);
            }
        }
    }

    // =================================================================================================
    // Gates G19, G21 and G16: the record is fifty bytes, the FILLER is fourteen spaces, and the
    // real 36-byte fixture is normalised UP by the harness rather than absorbed by production code.
    // =================================================================================================

    @Nested
    @DisplayName("Byte-level parity - the offset audit and the real fixture")
    class ByteLevelParity {

        /** Where the fixture rows are copied to on the test classpath. */
        private static final String FIXTURE = "/fixtures/cardxref.txt";

        /**
         * Reads the shipped fixture rows.
         *
         * @return the fifty rows, exactly as stored
         * @throws IOException if the classpath resource cannot be read
         */
        private List<String> fixtureRows() throws IOException {
            try (InputStream stream = CardXrefRepositoryTest.class.getResourceAsStream(FIXTURE)) {
                assertThat(stream).as("the fixture must be on the test classpath").isNotNull();
                return new String(stream.readAllBytes(), ASCII).lines().toList();
            }
        }

        @Test
        @DisplayName("Gates G19 and G21: a round-tripped record is 50 bytes and bytes 36-49 are spaces")
        void aRoundTrippedRecordIsFiftyBytesWithASpaceFilledFiller() {
            byte[] encoded = new CardXrefRecord(CARD_1, 50, 50L).encode(ASCII);

            assertThat(encoded).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(new String(encoded, ASCII).substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("Risk R-F confirmed: every shipped fixture row is 36 bytes, not 50")
        void everyFixtureRowIsThirtySixBytes() throws IOException {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(50);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(36));
        }

        @Test
        @DisplayName("Gate G16: padded 36 to 50, all fifty fixture rows decode and re-encode byte-identically")
        void theWholeFixtureRoundTripsAfterNormalisation() throws IOException {
            List<String> padded = new ArrayList<>();
            for (String row : fixtureRows()) {
                // The normalisation application-test.yml declares as
                // carddemo.test.fixtures.cardxref.pad-to: 50 - performed by the harness, never here.
                padded.add(CODEC.padToDeclaredWidth(row, CardXrefRepository.RECORD_LENGTH));
            }
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, padded);
            List<CardXrefRecord> decoded = new ArrayList<>();

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                for (ReadResult next = cursor.readNext(); !next.isEndOfFile(); next = cursor.readNext()) {
                    decoded.add(next.record().orElseThrow());
                }
            }

            assertThat(decoded).hasSize(50);
            for (int index = 0; index < decoded.size(); index++) {
                assertThat(new String(decoded.get(index).encode(ASCII), ASCII))
                        .as("fixture row %d re-encodes byte-identically", index + 1)
                        .isEqualTo(padded.get(index));
            }
            // The first shipped row, field for field: card 0500024453765740, customer 50, account 50.
            assertThat(decoded.get(0)).isEqualTo(new CardXrefRecord(CARD_1, 50, 50L));
        }

        @Test
        @DisplayName("Both keys find the first fixture row, at their two different offsets")
        void bothKeysFindTheFirstFixtureRowFromTheRealData() throws IOException {
            List<String> padded = new ArrayList<>();
            for (String row : fixtureRows()) {
                padded.add(CODEC.padToDeclaredWidth(row, CardXrefRepository.RECORD_LENGTH));
            }
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, padded);
            stubRows(jdbc, ALT_SQL, padded);
            CardXrefRepository repository = repository(jdbc);

            ReadResult byCard = repository.readByCardNumber(CARD_1);
            ReadResult byAccount = repository.readByAccountIdViaAltIndex(50L);

            assertThat(byCard.isFound()).isTrue();
            assertThat(byAccount.isFound()).isTrue();
            // Two access paths, two key offsets - 0 and 25 - and one record.
            assertThat(byAccount.record()).isEqualTo(byCard.record());
            assertThat(byCard.ddName()).isNotEqualTo(byAccount.ddName());
        }

        @Test
        @DisplayName("An unpadded fixture row is rejected: production never absorbs the 36-byte form")
        void anUnpaddedFixtureRowIsRejected() throws IOException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_SQL, fixtureRows());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(CARD_1))
                    .withMessageContaining("padToDeclaredWidth");
        }
    }

    // =================================================================================================
    // The outcome type's own invariants. A result whose status and meaning disagreed, or that carried a
    // record it should not, would make every caller's guard chain unreliable - so it cannot be built.
    // =================================================================================================

    @Nested
    @DisplayName("ReadResult - a status and its meaning are two views of one fact")
    class ReadResultInvariants {

        /** A record to attach to the outcomes that carry one. */
        private static final CardXrefRecord RECORD = new CardXrefRecord(CARD_1, 50, 50L);

        @Test
        @DisplayName("Each factory produces the status, outcome, RESP and record presence it stands for")
        void eachFactoryIsInternallyConsistent() {
            ReadResult found = ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD);
            ReadResult notFound = ReadResult.notFound(CardXrefRepository.BASE_DD_NAME);
            ReadResult endOfFile = ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME);
            ReadResult duplicate = ReadResult.duplicate(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                    RECORD, FileStatus.DUPKEY);
            ReadResult other = ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                    CardXrefRepository.PERMANENT_ERROR_STATUS);

            assertThat(found.status()).isEqualTo(FileStatus.OK);
            assertThat(found.record()).isPresent();
            assertThat(notFound.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(notFound.record()).isEmpty();
            assertThat(endOfFile.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(endOfFile.record()).isEmpty();
            assertThat(duplicate.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(duplicate.record()).isPresent();
            assertThat(other.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(other.record()).isEmpty();
        }

        @Test
        @DisplayName("Gate G47: all four enumerated statuses are reachable and mutually exclusive")
        void allFourEnumeratedStatusesAreReachable() {
            List<ReadResult> results = List.of(
                    ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD),
                    ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME),
                    ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, RECORD, FileStatus.DUPREC),
                    ReadResult.notFound(CardXrefRepository.BASE_DD_NAME));

            assertThat(results).extracting(ReadResult::status)
                    .containsExactly(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                            FileStatus.NOT_FOUND);
            // Exactly one predicate answers true for each outcome.
            for (ReadResult result : results) {
                long trueCount = List.of(result.isFound(), result.isEndOfFile(), result.isDuplicate(),
                        result.isNotFound(), result.isOther()).stream().filter(flag -> flag).count();
                assertThat(trueCount).as("%s answers exactly one predicate", result.status()).isOne();
            }
        }

        @Test
        @DisplayName("APPL-RESULT is 0 for '00', 16 for '10' and 12 for everything else")
        void applResultFollowsTheCbact03cEvaluate() {
            assertThat(ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD).applResult())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME).applResult())
                    .isEqualTo(FileStatus.APPL_EOF);
            assertThat(ReadResult.notFound(CardXrefRepository.BASE_DD_NAME).applResult())
                    .isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, RECORD, FileStatus.DUPREC)
                    .applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                    CardXrefRepository.PERMANENT_ERROR_STATUS).applResult())
                    .isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("A status of the wrong length is rejected: COBOL compares exactly two characters")
        void aStatusOfTheWrongLengthIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, "000",
                            Outcome.OK, java.util.Optional.of(RECORD), FileStatus.NORMAL, 0))
                    .withMessageContaining("exactly");
        }

        @Test
        @DisplayName("A status and an outcome that disagree cannot be built")
        void aStatusAndOutcomeThatDisagreeAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.NOT_FOUND, java.util.Optional.empty(), FileStatus.NOTFND, 0))
                    .withMessageContaining("does not classify");
        }

        @Test
        @DisplayName("A record present on an outcome that carries none - and absent when it should - is rejected")
        void recordPresenceMustMatchTheOutcome() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME,
                            FileStatus.NOT_FOUND, Outcome.NOT_FOUND, java.util.Optional.of(RECORD),
                            FileStatus.NOTFND, 0))
                    .withMessageContaining("carries no record");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.empty(), FileStatus.NORMAL, 0))
                    .withMessageContaining("but none was supplied");
        }

        @Test
        @DisplayName("Every component is required: no null escapes the type")
        void everyComponentIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(null, FileStatus.OK, Outcome.OK,
                            java.util.Optional.of(RECORD), FileStatus.NORMAL, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, null, Outcome.OK,
                            java.util.Optional.of(RECORD), FileStatus.NORMAL, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            null, java.util.Optional.of(RECORD), FileStatus.NORMAL, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, null, FileStatus.NORMAL, 0));
        }

        @Test
        @DisplayName("A found or duplicate outcome must actually carry its record")
        void aCarryingOutcomeMustCarryItsRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ReadResult.found(CardXrefRepository.BASE_DD_NAME, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, null,
                            FileStatus.DUPREC));
        }

        @Test
        @DisplayName("A duplicate must name WHICH key duplicated - DUPREC or DUPKEY, nothing else")
        void aDuplicateMustNameWhichKeyDuplicated() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, RECORD,
                            FileStatus.NORMAL))
                    .withMessageContaining("DUPREC")
                    .withMessageContaining("DUPKEY");
        }

        @Test
        @DisplayName("The WHEN OTHER arm rejects a status that classifies as one of the enumerated four")
        void theOtherArmRejectsAnEnumeratedStatus() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReadResult.other(CardXrefRepository.BASE_DD_NAME, FileStatus.OK))
                    .withMessageContaining("does not classify");
        }

        @Test
        @DisplayName("The status renders as the four-character image 9910-DISPLAY-IO-STATUS produces")
        void theStatusRendersAsItsDisplayImage() {
            assertThat(ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD).statusImage())
                    .isEqualTo(FileStatus.toStatusImage(FileStatus.OK))
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }
    }

    // =================================================================================================
    // Residual risk R-E made testable. The driver is a deployment-time input, so the two decisions that
    // follow from it - no column name anywhere, and a statement composed only from configuration - are
    // asserted rather than merely documented.
    // =================================================================================================

    @Nested
    @DisplayName("Risk R-E - a record image column with no name, and a statement built from configuration")
    class DriverIndependence {

        /**
         * Captures the {@link RowMapper} the repository hands the template and runs it against a mocked
         * row, which is the only way to observe how the record image is actually fetched.
         *
         * @throws SQLException never; declared because the mapper contract declares it
         */
        @Test
        @DisplayName("The record image is read by column POSITION 1, and by no column name at all")
        void theRecordImageIsReadByPositionAndNeverByName() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ArgumentCaptor<RowMapper<String>> mapperCaptor = ArgumentCaptor.captor();
            when(jdbc.query(eq(BASE_SQL), mapperCaptor.capture()))
                    .thenReturn(List.of(image(CARD_1, 50, 50L)));
            repository(jdbc).readByCardNumber(CARD_1);

            ResultSet row = mock(ResultSet.class);
            when(row.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn("a row image");

            assertThat(mapperCaptor.getValue().mapRow(row, 0)).isEqualTo("a row image");
            verify(row).getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX);
            // The whole point: a dataset carrying no relational metadata is presented as a single
            // record-image column, so naming that column in Java would be an unverifiable literal.
            verify(row, never()).getString(anyString());
        }

        @Test
        @DisplayName("An absent column value is surfaced, not swallowed, so the caller can classify it")
        void anAbsentColumnValueIsSurfaced() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ArgumentCaptor<RowMapper<String>> mapperCaptor = ArgumentCaptor.captor();
            when(jdbc.query(eq(BASE_SQL), mapperCaptor.capture()))
                    .thenReturn(List.of(image(CARD_1, 50, 50L)));
            repository(jdbc).readByCardNumber(CARD_1);

            ResultSet row = mock(ResultSet.class);
            when(row.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);

            assertThat(mapperCaptor.getValue().mapRow(row, 0)).isNull();
        }

        @Test
        @DisplayName("Both statements are composed only from the configured names, delimited and ordered")
        void bothStatementsComeOnlyFromConfiguration() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.captor();
            when(jdbc.query(sqlCaptor.capture(), ArgumentMatchers.<RowMapper<String>>any()))
                    .thenReturn(List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            repository.readByCardNumber(CARD_1);
            repository.readByAccountIdViaAltIndex(50L);
            repository.openBrowse();

            assertThat(sqlCaptor.getAllValues()).containsExactly(BASE_SQL, ALT_SQL, BASE_SQL);
            assertThat(sqlCaptor.getAllValues()).allSatisfy(sql -> assertThat(sql)
                    // The dataset name is a delimited identifier, because a mainframe name contains
                    // periods and would otherwise be parsed as a qualified name.
                    .contains("\"")
                    // ORDER BY an ordinal, so the statement names no column - and the base key is the
                    // leading fixed-width span, so image order IS card-number order.
                    .endsWith("ORDER BY " + CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX));
        }

        @Test
        @DisplayName("A quote inside a configured dataset name is escaped, never left as syntax")
        void aQuoteInADatasetNameIsEscaped() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.captor();
            when(jdbc.query(sqlCaptor.capture(), ArgumentMatchers.<RowMapper<String>>any()))
                    .thenReturn(Collections.emptyList());

            new CardXrefRepository(jdbc, bindings(ksds("TEST.\"ODD\".NAME"), aixPath(ALT_DS)), ASCII)
                    .readByCardNumber(CARD_1);

            assertThat(sqlCaptor.getValue()).isEqualTo("SELECT * FROM \"TEST.\"\"ODD\"\".NAME\" ORDER BY 1");
        }
    }
}
