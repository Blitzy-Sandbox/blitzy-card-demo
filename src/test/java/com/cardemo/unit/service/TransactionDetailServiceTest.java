/*
 * ****************************************************************************
 * Program     : TransactionDetailServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies TransactionDetailService against COTRN01C paragraph
 *               by paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the five width-changing truncations of
 *               :184-189, the edited amount mask of :49 including its
 *               deliberate loss of a ninth integer digit, the exclusive
 *               UPDATE lock the source takes at :275 on a read-only path, the
 *               numeric guard the source does NOT have, and the rule that a
 *               card number never reaches a log or an exception message.
 * Source      : app/cbl/COTRN01C.cbl (330 lines, 9 paragraphs)
 *               app/cpy-bms/COTRN01.CPY   (21 input fields, TDESCI X(60):96)
 *               app/cpy/CVTRA05Y.cpy      (TRAN-RECORD, TRAN-AMT S9(09)V99)
 *               app/cpy/COTTL01Y.cpy      (the two title literals)
 *               app/cpy/CSDAT01Y.cpy      (WS-CURDATE / WS-CURTIME, 8 chars)
 *               app/csd/CARDDEMO.CSD      (CT01 -> COTRN01C) @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionDetailService.AttentionIdentifier;
import com.cardemo.service.transaction.TransactionDetailService.TransactionDetailScreen;
import com.cardemo.unit.model.FixedClockProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit tests for {@link TransactionDetailService}.
 *
 * <p>The two collaborators that reach outside the JVM - the repository and the entity manager - are Mockito
 * doubles, because the entity manager is the only place the preserved {@code :275} lock is observable and a
 * test has to be able to assert that the lock was actually requested. {@link FileStatusMapper} is a real
 * instance rather than a double: it has a no-argument constructor and no collaborators of its own, and using
 * the real translation table means the exception types asserted here are the ones production will raise.
 * The clock is fixed, so the two header fields the source derives from one
 * {@code MOVE FUNCTION CURRENT-DATE} are reproducible.
 */
@DisplayName("TransactionDetailService - COTRN01C / transaction CT01")
final class TransactionDetailServiceTest {

    /** A well-formed sixteen-character transaction identifier, the width of {@code TRAN-ID PIC X(16)}. */
    private static final String ID = "0000000000000042";

    /** A card number used only to prove it never escapes into a log or an exception message. */
    private static final String CARD = "4111111111111111";

    /** A full twenty-six character {@code TRAN-ORIG-TS}, per {@code app/cpy/CSDAT01Y.cpy:42-55}. */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** A full twenty-six character {@code TRAN-PROC-TS}. */
    private static final String PROC_TS = "2022-06-11 08:00:00.000000";

    private TransactionRepository repository;
    private EntityManager entityManager;
    private FileStatusMapper fileStatusMapper;
    private TransactionDetailService service;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        entityManager = mock(EntityManager.class);
        fileStatusMapper = new FileStatusMapper();
        service = new TransactionDetailService(repository, fileStatusMapper, entityManager,
                FixedClockProvider.canonicalClock());
        logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(TransactionDetailService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * Builds a transaction record. Every argument maps to one field of {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param description {@code TRAN-DESC PIC X(100)}
     * @param amount {@code TRAN-AMT PIC S9(09)V99}
     * @param origTs {@code TRAN-ORIG-TS PIC X(26)}
     * @param procTs {@code TRAN-PROC-TS PIC X(26)}
     * @param merchantName {@code TRAN-MERCHANT-NAME PIC X(50)}
     * @param merchantCity {@code TRAN-MERCHANT-CITY PIC X(50)}
     * @param merchantId {@code TRAN-MERCHANT-ID PIC 9(09)}
     * @param categoryCode {@code TRAN-CAT-CD PIC 9(04)}
     * @param cardNumber {@code TRAN-CARD-NUM PIC X(16)}
     * @return the record, never {@code null}
     */
    private static Transaction record(final String description,
            final BigDecimal amount,
            final String origTs,
            final String procTs,
            final String merchantName,
            final String merchantCity,
            final Long merchantId,
            final Integer categoryCode,
            final String cardNumber) {
        return new Transaction(ID, "01", categoryCode, "POS       ", description, amount, merchantId,
                merchantName, merchantCity, "12345-0001", cardNumber, origTs, procTs);
    }

    /** The canonical record: every field comfortably inside its width so nothing truncates. */
    private static Transaction canonical() {
        return record("COFFEE", new BigDecimal("123.45"), ORIG_TS, PROC_TS, "ACME", "SEATTLE",
                123L, 5, CARD);
    }

    /** Stubs the repository to return {@code candidate} for {@link #ID} and drives the deep-link entry. */
    private TransactionDetailScreen view(final Transaction candidate) {
        when(repository.findById(ID)).thenReturn(Optional.of(candidate));
        return service.viewTransaction(ID);
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY populate block, :177-190")
    final class PopulateBlock {

        @Test
        @DisplayName("all 21 detail fields are populated, each at its map width")
        void allDetailFieldsPopulated() {
            final TransactionDetailScreen screen = view(canonical());
            final TransactionDto detail = screen.detail();

            assertThat(detail.detailProjection()).hasSize(TransactionDto.DETAIL_FIELD_COUNT);

            // The six recurring header fields of POPULATE-HEADER-INFO, :253-262.
            assertThat(detail.transactionName()).isEqualTo("CT01");
            assertThat(detail.title01()).isEqualTo("      AWS Mainframe Modernization       ");
            assertThat(detail.currentDate()).isEqualTo("06/10/22");
            assertThat(detail.programName()).isEqualTo("COTRN01C");
            assertThat(detail.title02()).isEqualTo("              CardDemo                  ");
            // CURTIMEI is X(8) on this map, not X(9).
            assertThat(detail.currentTime()).isEqualTo("19:27:53").hasSize(8);

            // The fourteen moves of :177-190. Fields 7 and 8 are distinct on the map and both carry a value.
            assertThat(detail.transactionIdInput()).isEqualTo(ID);
            assertThat(detail.transactionId()).isEqualTo(ID);
            assertThat(detail.cardNumber()).isEqualTo(CARD);
            assertThat(detail.typeCode()).isEqualTo("01");
            assertThat(detail.categoryCode()).isEqualTo("0005");
            assertThat(detail.source()).isEqualTo("POS       ");
            assertThat(detail.description()).isEqualTo("COFFEE");
            assertThat(detail.amount()).isEqualTo("+00000123.45");
            assertThat(detail.originatingDate()).isEqualTo("2022-06-10");
            assertThat(detail.processingDate()).isEqualTo("2022-06-11");
            assertThat(detail.merchantId()).isEqualTo("000000123");
            assertThat(detail.merchantName()).isEqualTo("ACME");
            assertThat(detail.merchantCity()).isEqualTo("SEATTLE");
            assertThat(detail.merchantZip()).isEqualTo("12345-0001");

            assertThat(detail.errorMessage()).isEmpty();
            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.navigationTarget()).isNull();
        }

        @Test
        @DisplayName(":184 TRAN-DESC X(100) -> TDESCI X(60): sixty, never twenty-six")
        void descriptionTruncatesToSixty() {
            final TransactionDto detail = view(record("D".repeat(100), new BigDecimal("1.00"),
                    ORIG_TS, PROC_TS, "ACME", "SEATTLE", 1L, 1, CARD)).detail();

            assertThat(detail.description()).hasSize(TransactionDto.DESCRIPTION_LENGTH)
                    .isEqualTo("D".repeat(60));
            // Guard against the list-row width leaking onto the detail screen.
            assertThat(detail.description()).hasSizeGreaterThan(TransactionDto.ROW_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName(":185/:186 both X(26) timestamps -> X(10), the yyyy-MM-dd portion")
        void timestampsTruncateToTen() {
            final TransactionDto detail = view(canonical()).detail();

            assertThat(detail.originatingDate()).hasSize(TransactionDto.DETAIL_DATE_LENGTH)
                    .isEqualTo(ORIG_TS.substring(0, 10));
            assertThat(detail.processingDate()).hasSize(TransactionDto.DETAIL_DATE_LENGTH)
                    .isEqualTo(PROC_TS.substring(0, 10));
        }

        @Test
        @DisplayName("a blank twenty-six space TRAN-PROC-TS is real data and passes through")
        void blankProcessingTimestampIsHandled() {
            final TransactionDto detail = view(record("X", new BigDecimal("1.00"), ORIG_TS,
                    " ".repeat(26), "ACME", "SEATTLE", 1L, 1, CARD)).detail();

            assertThat(detail.processingDate()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName(":188/:189 merchant name X(50)->X(30) and city X(50)->X(25)")
        void merchantNameAndCityTruncate() {
            final TransactionDto detail = view(record("X", new BigDecimal("1.00"), ORIG_TS, PROC_TS,
                    "N".repeat(50), "C".repeat(50), 1L, 1, CARD)).detail();

            assertThat(detail.merchantName()).hasSize(TransactionDto.MERCHANT_NAME_LENGTH)
                    .isEqualTo("N".repeat(30));
            assertThat(detail.merchantCity()).hasSize(TransactionDto.MERCHANT_CITY_LENGTH)
                    .isEqualTo("C".repeat(25));
        }

        @Test
        @DisplayName(":181/:187 numeric-to-alphanumeric moves keep their leading zeros")
        void numericFieldsAreZeroFilled() {
            final TransactionDto low = view(record("X", new BigDecimal("1.00"), ORIG_TS, PROC_TS,
                    "A", "B", 7L, 3, CARD)).detail();
            assertThat(low.categoryCode()).isEqualTo("0003");
            assertThat(low.merchantId()).isEqualTo("000000007");
        }

        @Test
        @DisplayName("the widest permitted numerics fill their fields exactly, with no overflow")
        void widestNumericsFillTheirFields() {
            final TransactionDto wide = view(record("X", new BigDecimal("1.00"), ORIG_TS, PROC_TS,
                    "A", "B", 999_999_999L, 9999, CARD)).detail();
            assertThat(wide.categoryCode()).isEqualTo("9999").hasSize(4);
            assertThat(wide.merchantId()).isEqualTo("999999999").hasSize(9);
        }

        @Test
        @DisplayName("the amount travels as a BigDecimal beside its edited text")
        void amountValueAccompaniesTheMask() {
            final TransactionDto detail = view(canonical()).detail();
            assertThat(detail.amountValue()).isEqualByComparingTo("123.45");
        }
    }

    @Nested
    @DisplayName("the edited amount mask, WS-TRAN-AMT PIC +99999999.99 at :49")
    final class EditedAmountMask {

        @ParameterizedTest(name = "{0} renders {1}")
        @CsvSource({
            "123.45,      +00000123.45",
            "-123.45,     -00000123.45",
            "0.00,        +00000000.00",
            "-0.01,       -00000000.01",
            "0.10,        +00000000.10",
            "99999999.99, +99999999.99"
        })
        @DisplayName("sign is mandatory and the integer part is zero-filled to eight digits")
        void rendersOnTheMask(final String raw, final String expected) {
            final TransactionDto detail = view(record("X", new BigDecimal(raw), ORIG_TS, PROC_TS,
                    "A", "B", 1L, 1, CARD)).detail();

            assertThat(detail.amount()).isEqualTo(expected)
                    .hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH);
        }

        @ParameterizedTest(name = "{0} loses its leading digit and renders {1}")
        @CsvSource({
            "100000000.00, +00000000.00",
            "123456789.99, +23456789.99",
            "999999999.99, +99999999.99",
            "-123456789.99, -23456789.99"
        })
        @DisplayName("a ninth integer digit is discarded, because the mask holds only eight")
        void ninthIntegerDigitIsDiscarded(final String raw, final String expected) {
            final TransactionDto detail = view(record("X", new BigDecimal(raw), ORIG_TS, PROC_TS,
                    "A", "B", 1L, 1, CARD)).detail();

            assertThat(detail.amount()).isEqualTo(expected)
                    .hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("a negative amount is never normalised to its magnitude")
        void negativeAmountsKeepTheirSign() {
            final TransactionDto detail = view(record("X", new BigDecimal("-4200.00"), ORIG_TS, PROC_TS,
                    "A", "B", 1L, 1, CARD)).detail();

            assertThat(detail.amount()).startsWith("-");
            assertThat(detail.amountValue()).isNegative();
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY validation, :146-156")
    final class Validation {

        @Test
        @DisplayName("a blank identifier raises the legacy message and never reads")
        void blankIdentifierIsRejected() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, "   ", null);

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.detail().errorMessage()).isEqualTo("Tran ID can NOT be empty...");
            assertThat(screen.cursorField()).isEqualTo("TRNIDIN");
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName("a null identifier is treated as blank, LOW-VALUES having no HTTP counterpart")
        void nullIdentifierIsRejected() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, null, null);

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.detail().errorMessage()).isEqualTo("Tran ID can NOT be empty...");
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName("a NON-NUMERIC identifier is NOT rejected: COTRN01C has no numeric guard")
        void nonNumericIdentifierReachesTheLookup() {
            when(repository.findById("ABCDEFGH")).thenReturn(Optional.empty());

            // COTRN00C:214 rejects a non-numeric identifier; COTRN01C does not test for it at all. The
            // absent guard is preserved, so the value reaches the read and returns through not-found rather
            // than through a numeric-format complaint.
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, "ABCDEFGH", null))
                    .withMessage("Transaction ID NOT found...");

            verify(repository).findById("ABCDEFGH");
        }

        @Test
        @DisplayName("no error message anywhere mentions a numeric format")
        void noNumericFormatMessageExists() {
            when(repository.findById("!!!")).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.submitScreen(AttentionIdentifier.ENTER, "!!!", null))
                    .satisfies(thrown -> assertThat(String.valueOf(thrown.getMessage()).toLowerCase(
                            java.util.Locale.ROOT)).doesNotContain("numeric"));
        }
    }

    @Nested
    @DisplayName("READ-TRANSACT-FILE, :267-298 - the preserved :275 UPDATE lock")
    final class ReadTransactFile {

        @Test
        @DisplayName(":275 UPDATE: an exclusive PESSIMISTIC_WRITE lock is taken on this read-only path")
        void pessimisticWriteLockIsAcquired() {
            final Transaction found = canonical();
            view(found);

            // This is the parity assertion for the single most consequential preserved quirk in the
            // program: COTRN01C is a pure VIEW - grep finds no WRITE, REWRITE or DELETE - yet its READ
            // carries UPDATE at :275. The lock is intentional legacy behaviour and must not be cleaned up.
            verify(entityManager).lock(eq(found), eq(LockModeType.PESSIMISTIC_WRITE));
        }

        @Test
        @DisplayName("no lock is attempted when the row is absent")
        void absentRowTakesNoLock() {
            when(repository.findById(ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            verify(entityManager, never()).lock(any(), any(LockModeType.class));
        }

        @Test
        @DisplayName("DFHRESP(NOTFND) at :283-288 becomes RecordNotFoundException")
        void notFoundBecomesTypedException() {
            when(repository.findById(ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(ID))
                    .withMessage("Transaction ID NOT found...");
        }

        @Test
        @DisplayName("WHEN OTHER at :289-296 routes through FileStatusMapper and preserves the cause")
        void ioFailureIsTranslatedAndCausePreserved() {
            final QueryTimeoutException boom = new QueryTimeoutException("read timed out");
            when(repository.findById(ID)).thenThrow(boom);

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID))
                    .isInstanceOf(FileAccessException.class)
                    .withCause(boom);
        }

        @Test
        @DisplayName("a failure raised while taking the lock is translated the same way")
        void lockFailureIsTranslated() {
            final Transaction found = canonical();
            when(repository.findById(ID)).thenReturn(Optional.of(found));
            final PersistenceException boom = new PersistenceException("could not acquire lock");
            doThrow(boom).when(entityManager).lock(any(), any(LockModeType.class));

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID))
                    .isInstanceOf(FileAccessException.class)
                    .withCause(boom);
        }

        @Test
        @DisplayName("the single DISPLAY at :290 becomes one structured ERROR log, with no card number")
        void ioFailureLogsOnceWithoutCardholderData() {
            when(repository.findById(ID)).thenThrow(new QueryTimeoutException("read timed out"));

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service.viewTransaction(ID));

            final List<ILoggingEvent> errors = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getFormattedMessage())
                    .contains("Unable to lookup Transaction...")
                    .doesNotContain(CARD);
        }
    }

    @Nested
    @DisplayName("MAIN-PARA key dispatch, :112-139")
    final class KeyDispatch {

        @Test
        @DisplayName(":116-117 PF3 with no origin falls back to the literal COMEN01C")
        void pf3WithoutOriginGoesToMainMenu() {
            assertThat(service.submitScreen(AttentionIdentifier.PF3, ID, null).navigationTarget())
                    .isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName(":119-120 PF3 with an origin returns to it")
        void pf3WithOriginReturnsThere() {
            assertThat(service.submitScreen(AttentionIdentifier.PF3, ID, "COTRN00C").navigationTarget())
                    .isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName(":126 PF5 always walks back to the list, whatever the origin")
        void pf5AlwaysGoesToTheList() {
            assertThat(service.submitScreen(AttentionIdentifier.PF5, ID, "COMEN01C").navigationTarget())
                    .isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName(":123 PF4 clears the key as well as the outputs, and reads nothing")
        void pf4ClearsEverything() {
            final TransactionDetailScreen screen = service.submitScreen(AttentionIdentifier.PF4, ID, null);

            // INITIALIZE-ALL-FIELDS blanks TRNIDINI at :312, which PROCESS-ENTER-KEY's list at :159-171
            // does not - that is what makes PF4 a reset rather than a re-query.
            assertThat(screen.detail().transactionIdInput()).isEmpty();
            assertThat(screen.detail().transactionId()).isEmpty();
            assertThat(screen.detail().amount()).isEmpty();
            assertThat(screen.detail().amountValue()).isNull();
            assertThat(screen.detail().errorMessage()).isEmpty();
            assertThat(screen.navigationTarget()).isNull();
            assertThat(screen.cursorField()).isEqualTo("TRNIDIN");
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName(":137 WHEN OTHER raises the shared invalid-key message")
        void unknownKeyRaisesInvalidKeyMessage() {
            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.OTHER, ID, null);

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.detail().errorMessage())
                    .isEqualTo("Invalid key pressed. Please see below...");
        }

        @Test
        @DisplayName("a null attention identifier lands on the WHEN OTHER arm rather than failing")
        void nullKeyLandsOnTheOtherArm() {
            final TransactionDetailScreen screen = service.submitScreen(null, ID, null);

            assertThat(screen.errorFlagOn()).isTrue();
            assertThat(screen.detail().errorMessage())
                    .isEqualTo("Invalid key pressed. Please see below...");
        }

        @Test
        @DisplayName(":113 ENTER runs the lookup and populates the screen")
        void enterRunsTheLookup() {
            when(repository.findById(ID)).thenReturn(Optional.of(canonical()));

            final TransactionDetailScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, ID, null);

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.detail().transactionId()).isEqualTo(ID);
        }
    }

    @Nested
    @DisplayName("MAIN-PARA entry paths, :93-109")
    final class EntryPaths {

        @Test
        @DisplayName(":95-96 an absent comm area routes to the literal COSGN00C")
        void absentCommAreaRoutesToSignOn() {
            final TransactionDetailScreen screen = service.openWithoutContext();

            assertThat(screen.navigationTarget()).isEqualTo("COSGN00C");
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName(":103-107 a populated selection deep-links straight into the lookup")
        void deepLinkRunsTheLookup() {
            final TransactionDetailScreen screen = view(canonical());

            assertThat(screen.detail().transactionIdInput()).isEqualTo(ID);
            assertThat(screen.detail().transactionId()).isEqualTo(ID);
            verify(repository).findById(ID);
        }

        @Test
        @DisplayName(":102 a blank selection parks the cursor and reads nothing")
        void blankSelectionDoesNotRead() {
            final TransactionDetailScreen screen = service.viewTransaction(null);

            assertThat(screen.errorFlagOn()).isFalse();
            assertThat(screen.cursorField()).isEqualTo("TRNIDIN");
            assertThat(screen.detail().transactionId()).isEmpty();
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName("an all-blank selection is treated as no selection")
        void whitespaceSelectionDoesNotRead() {
            final TransactionDetailScreen screen = service.viewTransaction("      ");

            assertThat(screen.errorFlagOn()).isFalse();
            verify(repository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("cardholder data containment")
    final class CardholderData {

        @Test
        @DisplayName("the card number reaches the payload but never a log")
        void cardNumberIsDisplayedButNeverLogged() {
            final Transaction found = canonical();
            view(found);

            assertThat(found.toString()).doesNotContain(CARD);
            assertThat(appender.list).isNotEmpty();
            for (final ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(CARD);
            }
            assertThat(appender.list)
                    .anyMatch(event -> event.getFormattedMessage()
                            .contains("<redacted-16-digit-card-number>"));
        }

        @Test
        @DisplayName("an absent card number logs the absence marker rather than an empty gap")
        void absentCardNumberLogsTheAbsenceMarker() {
            view(record("X", new BigDecimal("1.00"), ORIG_TS, PROC_TS, "A", "B", 1L, 1, ""));

            assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("<absent>"));
        }

        @Test
        @DisplayName("no exception message carries a card number")
        void exceptionMessagesCarryNoCardNumber() {
            when(repository.findById(ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewTransaction(ID))
                    .satisfies(thrown -> assertThat(String.valueOf(thrown.getMessage()))
                            .doesNotContain(CARD));
        }
    }

    @Nested
    @DisplayName("construction and design invariants")
    final class DesignInvariants {

        @Test
        @DisplayName("every collaborator is required")
        void constructorRejectsNulls() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new TransactionDetailService(null, fileStatusMapper, entityManager,
                            FixedClockProvider.canonicalClock()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new TransactionDetailService(repository, null, entityManager,
                            FixedClockProvider.canonicalClock()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new TransactionDetailService(repository, fileStatusMapper, null,
                            FixedClockProvider.canonicalClock()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new TransactionDetailService(repository, fileStatusMapper, entityManager, null));
        }

        @Test
        @DisplayName("exactly one constructor, so Spring injects without @Autowired")
        void singleConstructor() {
            assertThat(TransactionDetailService.class.getDeclaredConstructors()).hasSize(1);
        }

        @Test
        @DisplayName("no mutable state: every instance field is final and every static field is final")
        void beanCarriesNoMutableState() {
            for (final Field field : TransactionDetailService.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("all nine COTRN01C paragraphs are present as distinct private methods")
        void allNineParagraphsArePresent() {
            final List<String> paragraphs = List.of("mainPara", "processEnterKey", "returnToPrevScreen",
                    "sendTrnviewScreen", "receiveTrnviewScreen", "populateHeaderInfo", "readTransactFile",
                    "clearCurrentScreen", "initializeAllFields");

            for (final String paragraph : paragraphs) {
                final List<Method> matches = java.util.Arrays
                        .stream(TransactionDetailService.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals(paragraph))
                        .toList();
                assertThat(matches)
                        .as("paragraph %s must map to exactly one private method", paragraph)
                        .hasSize(1);
                assertThat(Modifier.isPrivate(matches.get(0).getModifiers()))
                        .as("paragraph %s must be private", paragraph)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the screen payload rejects a missing detail projection")
        void screenRequiresDetail() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionDetailScreen(null, null, null, false));
        }

        @Test
        @DisplayName("only the four keys COTRN01C tests are modelled, plus its WHEN OTHER arm")
        void attentionIdentifierModelsOnlyTheTestedKeys() {
            assertThat(AttentionIdentifier.values())
                    .containsExactly(AttentionIdentifier.ENTER, AttentionIdentifier.PF3,
                            AttentionIdentifier.PF4, AttentionIdentifier.PF5, AttentionIdentifier.OTHER);
            assertThat(AttentionIdentifier.valueOf("ENTER")).isEqualTo(AttentionIdentifier.ENTER);
        }
    }

    @Nested
    @DisplayName("defensive arms the REST surface cannot reach")
    final class DefensiveArms {

        /**
         * These branches exist because the source has them, or because Rule 1 clause B requires null and
         * empty cases to be handled explicitly. The entity constructor rejects {@code null} for every field
         * involved, so no public call can drive them; they are exercised directly so that the behaviour is
         * pinned rather than merely asserted in prose.
         *
         * @param name the private static helper to invoke
         * @param parameterTypes its parameter types
         * @return the accessible method handle
         * @throws ReflectiveOperationException if the helper is absent, which would itself be the defect
         */
        private Method helper(final String name, final Class<?>... parameterTypes)
                throws ReflectiveOperationException {
            final Method method = TransactionDetailService.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        }

        @Test
        @DisplayName("truncate(null) yields a blank field rather than propagating null")
        void truncateHandlesNull() throws ReflectiveOperationException {
            assertThat(helper("truncate", String.class, int.class).invoke(null, null, 10)).isEqualTo("");
        }

        @Test
        @DisplayName("renderZeroPadded(null) yields a blank field")
        void renderZeroPaddedHandlesNull() throws ReflectiveOperationException {
            assertThat(helper("renderZeroPadded", Number.class, int.class).invoke(null, null, 4))
                    .isEqualTo("");
        }

        @Test
        @DisplayName("renderZeroPadded keeps the low-order digits when the value overflows the field")
        void renderZeroPaddedTruncatesOverflow() throws ReflectiveOperationException {
            final Method method = helper("renderZeroPadded", Number.class, int.class);
            assertThat(method.invoke(null, Integer.valueOf(123_456), 4)).isEqualTo("3456");
            assertThat(method.invoke(null, Integer.valueOf(-42), 4)).isEqualTo("0042");
        }

        @Test
        @DisplayName("renderEditedAmount(null) yields a blank field")
        void renderEditedAmountHandlesNull() throws ReflectiveOperationException {
            assertThat(helper("renderEditedAmount", BigDecimal.class).invoke(null, (BigDecimal) null))
                    .isEqualTo("");
        }

        @Test
        @DisplayName("renderEditedAmount rounds HALF_EVEN rather than letting the column decide")
        void renderEditedAmountRoundsHalfEven() throws ReflectiveOperationException {
            final Method method = helper("renderEditedAmount", BigDecimal.class);
            assertThat(method.invoke(null, new BigDecimal("1.005"))).isEqualTo("+00000001.00");
            assertThat(method.invoke(null, new BigDecimal("1.015"))).isEqualTo("+00000001.02");
        }

        @Test
        @DisplayName(":199-201 RETURN-TO-PREV-SCREEN defaults an unset target to COSGN00C")
        void returnToPrevScreenDefaultsToSignOn() throws ReflectiveOperationException {
            final Class<?> workAreaType = java.util.Arrays
                    .stream(TransactionDetailService.class.getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals("ScreenWorkArea"))
                    .findFirst()
                    .orElseThrow();
            final Constructor<?> workAreaConstructor = workAreaType.getDeclaredConstructor();
            workAreaConstructor.setAccessible(true);
            final Object work = workAreaConstructor.newInstance();

            final Method paragraph = TransactionDetailService.class
                    .getDeclaredMethod("returnToPrevScreen", workAreaType);
            paragraph.setAccessible(true);
            paragraph.invoke(service, work);

            final Field navigationTarget = workAreaType.getDeclaredField("navigationTarget");
            navigationTarget.setAccessible(true);
            assertThat(navigationTarget.get(work)).isEqualTo("COSGN00C");

            // :204 MOVE ZEROS TO CDEMO-PGM-CONTEXT, carried as the single digit the PIC 9(01) field holds.
            final Field programContext = workAreaType.getDeclaredField("programContext");
            programContext.setAccessible(true);
            assertThat(programContext.get(work)).isEqualTo("0");
        }
    }
}
