/*
 * ******************************************************************
 * Program     : FileStatusMapperTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the central translation of a COBOL FILE
 *               STATUS into a typed exception, the four-character
 *               status rendering the legacy DISPLAY produced, and the
 *               five sites where a status other than '00' is an
 *               accepted control path rather than a failure. Includes
 *               the open-versus-read asymmetry of the statement file
 *               service, which the corpus draws and prose does not.
 * Source      : app/cbl/CBTRN02C.cbl:714-731 (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBTRN02C.cbl:467-500 (2700-UPDATE-TCATBAL)
 *               app/cbl/CBTRN02C.cbl:707-710 (9999-ABEND-PROGRAM)
 *               app/cbl/CBACT04C.cbl:415-460 (rate lookup + default)
 *               app/cbl/CBSTM03A.CBL:353     (read: 00 / 10 only)
 *               app/cbl/CBSTM03A.CBL:736     (open: 00 OR 04)
 *               app/cpy/CSMSG02Y.cpy         (abend work areas) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileStatusMapper;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for the one place that decides what a FILE STATUS means.
 *
 * <p>The legacy corpus repeats a single I/O guard idiom several hundred times: move 8 into a result
 * field, perform the verb, move 0 if the status is {@code '00'} and 12 otherwise, then either continue or
 * display the status and abend. Recognising that as <em>one</em> idiom rather than hundreds of individual
 * checks is what justifies a single mapper, and it is also what makes the mapper dangerous: every
 * mistake in it is a mistake everywhere at once. That is the reason to test it exhaustively rather than
 * representatively.
 *
 * <p>Three things here are easy to get wrong and are therefore asserted directly against the corpus
 * rather than against the implementation.
 *
 * <p>First, the four-character rendering is a <em>contract</em>, not a formatting preference. The
 * end-to-end parity comparison reads log output, so a status rendered differently is a diff.
 * {@code 9910-DISPLAY-IO-STATUS} branches on "not numeric OR first byte is {@code '9'}", and in that
 * branch it does something unobvious: it moves the second status byte into a binary field and prints the
 * <em>numeric value of the byte</em> as three digits, so {@code '90'} renders as {@code 9048} because
 * {@code '0'} is 48. A naive reading produces {@code 9000} and passes a naive test.
 *
 * <p>Second, {@code '23'} is not uniformly an error. At two named sites a record-not-found status is an
 * accepted control path - the category-balance upsert of {@code 2700-UPDATE-TCATBAL} and the rate lookup
 * of {@code CBACT04C} - while at a third, the default-group retry, the same status abends the job. A
 * blanket rule that maps not-found to an exception breaks the first two; a blanket rule that tolerates it
 * breaks the third.
 *
 * <p>Third, and this one was settled by reading the corpus after the code raised the question: the
 * statement file service treats {@code '04'} differently at an open than at a read. {@code :736} accepts
 * {@code '00' OR '04'} when opening; {@code :353} accepts only {@code '00'} and {@code '10'} when
 * reading, and abends on anything else including {@code '04'}. The Agent Action Plan summarises this as
 * "either '00' or '04' as success at every open and read site", which is looser than the source. The
 * source governs - the plan says so itself, that the corpus and not the prose is the authority and that a
 * disagreement is resolved in the corpus's favour and recorded - so the asymmetry is preserved and pinned
 * here by two tests that cite the two lines.
 *
 * <p>These assertions were mutation-checked rather than assumed to bite. Two mutants were introduced
 * together: the read site was widened to tolerate {@code '04'}, and the {@code 9x} branch was changed to
 * render the second status character instead of its byte value - which is precisely the plausible-looking
 * mistake described above. Six of the thirty-nine tests failed, and they were the right six: the four
 * {@code 9x} cases, the non-numeric case that shares that branch, and the read-site asymmetry case. The
 * remaining thirty-three survived, and they survive for a reason worth stating rather than concealing:
 * they exercise the numeric rendering branch, the status-to-exception mapping and the not-found control
 * paths, none of which either mutant touches. A mutant that only one test kills is a mutant one test is
 * responsible for; naming that honestly is more useful than reporting a headline count.
 */
@DisplayName("FileStatusMapper: one idiom, translated once, including the cases that are not errors")
class FileStatusMapperTest {

    private FileStatusMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new FileStatusMapper();
    }

    @Nested
    @DisplayName("1. The four-character rendering reproduces 9910-DISPLAY-IO-STATUS")
    class DisplayRendering {

        @ParameterizedTest(name = "status {0} renders as {1}")
        @CsvSource({
            "00, 0000", "10, 0010", "22, 0022", "23, 0023", "35, 0035", "04, 0004",
        })
        @DisplayName("a numeric status whose first byte is not 9 becomes 00 then the two bytes")
        void numericStatusRendersWithLeadingZeros(final String status, final String expected) {
            assertThat(mapper.displayIoStatus(status))
                    .as("CBTRN02C:725-726 moves '0000' then the status into positions 3-4")
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + expected);
        }

        @ParameterizedTest(name = "status {0} renders as {1}")
        @CsvSource({
            "90, 9048", "91, 9049", "97, 9055", "99, 9057",
        })
        @DisplayName("a 9x status prints the byte VALUE of the second character, not the character")
        void nineFamilyRendersTheByteValue(final String status, final String expected) {
            // CBTRN02C:718-721 moves IO-STAT2 into TWO-BYTES-RIGHT and prints the binary field as three
            // digits. '0' is 48, '1' is 49, '7' is 55, '9' is 57. Rendering the character instead would
            // give 9000 and would look plausible in isolation.
            assertThat(mapper.displayIoStatus(status))
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + expected);
            assertThat(expected.substring(1))
                    .as("the three digits are the decimal byte value")
                    .isEqualTo(String.format("%03d", (int) status.charAt(1)));
        }

        @Test
        @DisplayName("a non-numeric status takes the same branch as the 9 family")
        void nonNumericTakesTheNineBranch() {
            // The IF is "NOT NUMERIC OR IO-STAT1 = '9'", so a wholly alphabetic status renders the same
            // way: first byte through, second byte as its decimal value. 'B' is 66.
            assertThat(mapper.displayIoStatus("AB"))
                    .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX + "A066");
        }

        @Test
        @DisplayName("the rendered field is always exactly four characters")
        void renderedFieldIsAlwaysFourCharacters() {
            for (String status : new String[] {"00", "04", "10", "22", "23", "35", "90", "99", "AB"}) {
                assertThat(mapper.displayIoStatus(status))
                        .as("status %s", status)
                        .hasSize(FileStatus.DISPLAY_MESSAGE_PREFIX.length()
                                + FileStatus.RENDERED_STATUS_LENGTH);
            }
        }

        @Test
        @DisplayName("the prefix is the legacy literal, including its NNNN placeholder")
        void prefixIsTheLegacyLiteral() {
            // The legacy DISPLAY names the field in the literal itself, so the emitted line contains the
            // placeholder text as well as the value. Preserving that is what makes the log comparable.
            assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
            assertThat(mapper.displayIoStatus("00")).startsWith("FILE STATUS IS: NNNN");
        }
    }

    @Nested
    @DisplayName("2. The guard's numeric result reproduces the 0 / 12 / 16 idiom")
    class ApplResult {

        @Test
        @DisplayName("the guard admits only 00")
        void guardAdmitsOnlySuccess() {
            assertThat(mapper.applResultForGuard("00")).isEqualTo(FileStatusMapper.APPL_AOK);
            for (String status : new String[] {"04", "10", "22", "23", "35", "90", "AB"}) {
                assertThat(mapper.applResultForGuard(status))
                        .as("the non-sequential guard treats %s as failure", status)
                        .isEqualTo(FileStatusMapper.APPL_FAILURE);
            }
        }

        @Test
        @DisplayName("a sequential read additionally admits 10 as end of file")
        void sequentialReadAdmitsEndOfFile() {
            assertThat(mapper.applResultForSequentialRead("00")).isEqualTo(FileStatusMapper.APPL_AOK);
            assertThat(mapper.applResultForSequentialRead("10")).isEqualTo(FileStatusMapper.APPL_EOF);
            assertThat(mapper.applResultForSequentialRead("23")).isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        @Test
        @DisplayName("the initial value is 8, which is neither success nor failure")
        void initialValueIsEight() {
            // The idiom moves 8 in before performing the verb, so that a path which never reaches the
            // status check cannot be mistaken for either outcome. Preserving the constant preserves that.
            assertThat(FileStatusMapper.APPL_RESULT_INITIAL).isEqualTo(8);
            assertThat(FileStatusMapper.APPL_RESULT_INITIAL)
                    .isNotEqualTo(FileStatusMapper.APPL_AOK)
                    .isNotEqualTo(FileStatusMapper.APPL_FAILURE)
                    .isNotEqualTo(FileStatusMapper.APPL_EOF);
        }
    }

    @Nested
    @DisplayName("3. Each status class becomes its own typed exception")
    class ExceptionTranslation {

        @ParameterizedTest(name = "{0} yields no exception")
        @ValueSource(strings = {"00", "10"})
        @DisplayName("success and end of file are not failures")
        void successAndEndOfFileAreNotFailures(final String status) {
            assertThat(mapper.toException(status, "ACCTDAT", "READ")).isEmpty();
        }

        @Test
        @DisplayName("22 becomes DuplicateRecordException")
        void duplicateKey() {
            assertThat(mapper.toException("22", "TRANSACT", "WRITE"))
                    .get().isInstanceOf(DuplicateRecordException.class);
        }

        @Test
        @DisplayName("23 becomes RecordNotFoundException")
        void recordNotFound() {
            assertThat(mapper.toException("23", "ACCTDAT", "READ"))
                    .get().isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("35 becomes FileUnavailableException")
        void fileUnavailable() {
            assertThat(mapper.toException("35", "CUSTDAT", "OPEN"))
                    .get().isInstanceOf(FileUnavailableException.class);
        }

        @ParameterizedTest(name = "{0} becomes FileAccessException")
        @ValueSource(strings = {"90", "92", "97", "99"})
        @DisplayName("the 9x family becomes FileAccessException carrying the expanded status")
        void ioErrorFamily(final String status) {
            Optional<CardDemoException> thrown = mapper.toException(status, "TRANSACT", "READ");

            assertThat(thrown).get().isInstanceOf(FileAccessException.class);
            assertThat(thrown.orElseThrow().getMessage())
                    .as("the message identifies the file and the operation")
                    .contains("TRANSACT")
                    .contains("READ");
        }

        @Test
        @DisplayName("an unrecognised status becomes a fatal abend rather than a guess")
        void unrecognisedStatusIsFatal() {
            assertThat(mapper.toException("AB", "TRANSACT", "READ"))
                    .get().isInstanceOf(FatalProcessingException.class);
        }

        @Test
        @DisplayName("a cause is preserved, never swallowed")
        void causeIsPreserved() {
            IOException cause = new IOException("device");

            assertThat(mapper.toException("35", "CUSTDAT", "OPEN", cause))
                    .get()
                    .extracting(Throwable::getCause)
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("no message ever carries a record key, an account id or a card number")
        void messagesCarryNoIdentifiers() {
            // The class documents this as an invariant. It is asserted rather than trusted because the
            // signature makes it easy to break: a caller could pass an identifier as the operation.
            String message = mapper.toException("35", "CUSTDAT", "OPEN").orElseThrow().getMessage();

            assertThat(message).doesNotContain("0000000000683580").doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("requireSuccess throws for every status except 00")
        void requireSuccessThrows() {
            mapper.requireSuccess("00", "ACCTDAT", "READ");

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> mapper.requireSuccess("23", "ACCTDAT", "READ"));
            assertThatExceptionOfType(FileUnavailableException.class)
                    .isThrownBy(() -> mapper.requireSuccess("35", "ACCTDAT", "OPEN"));
        }

        @Test
        @DisplayName("requireSuccessOrEndOfFile reports end of file as a boolean, not an exception")
        void requireSuccessOrEndOfFileReportsEof() {
            assertThat(mapper.requireSuccessOrEndOfFile("00", "DALYTRAN", "READ"))
                    .as("a good read is not end of file")
                    .isFalse();
            assertThat(mapper.requireSuccessOrEndOfFile("10", "DALYTRAN", "READ"))
                    .as("CBTRN02C loops until 10, so 10 terminates rather than fails")
                    .isTrue();
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> mapper.requireSuccessOrEndOfFile("35", "DALYTRAN", "READ"));
        }
    }

    @Nested
    @DisplayName("4. The three sites where record-not-found is not an error")
    class NotFoundIsNotAlwaysAnError {

        @Test
        @DisplayName("the category-balance upsert accepts 23 and reports it as create-needed")
        void categoryBalanceUpsertAcceptsNotFound() {
            // CBTRN02C:2700-UPDATE-TCATBAL accepts EITHER '00' OR '23' before dispatching to the create
            // or the rewrite branch. Returning a boolean rather than throwing is what lets the caller
            // choose the branch, exactly as the legacy create flag did.
            assertThat(mapper.requireCategoryBalanceReadSuccess("00"))
                    .as("found: rewrite branch")
                    .isFalse();
            assertThat(mapper.requireCategoryBalanceReadSuccess("23"))
                    .as("not found: create branch, which the legacy code treats as success")
                    .isTrue();
        }

        @Test
        @DisplayName("the category-balance upsert still abends on any other status")
        void categoryBalanceUpsertAbendsOtherwise() {
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> mapper.requireCategoryBalanceReadSuccess("35"))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains(FileStatusMapper.TCATBAL_READ_FAILURE_TEXT));
        }

        @Test
        @DisplayName("the rate lookup accepts 23 so the default group can be retried")
        void rateLookupAcceptsNotFound() {
            assertThat(mapper.requireDisclosureGroupReadSuccess("00")).isFalse();
            assertThat(mapper.requireDisclosureGroupReadSuccess("23"))
                    .as("CBACT04C:415-460 substitutes the DEFAULT group and reads again")
                    .isTrue();
        }

        @Test
        @DisplayName("but the default-group retry abends on 23, because there is nothing left to try")
        void defaultRateRetryAbendsOnNotFound() {
            // This is the asymmetry that a single blanket rule cannot express: the same status is a
            // control path on the first read and a fatal condition on the retry.
            mapper.requireDefaultDisclosureGroupReadSuccess("00");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> mapper.requireDefaultDisclosureGroupReadSuccess("23"))
                    .satisfies(thrown -> assertThat(thrown.getAbendReason())
                            .isEqualTo(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT));
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> mapper.requireDefaultDisclosureGroupReadSuccess("35"));
        }

        @Test
        @DisplayName("an abend carries the four CSMSG02Y work-area fields")
        void abendCarriesTheWorkAreaFields() {
            FatalProcessingException thrown = null;
            try {
                mapper.requireDefaultDisclosureGroupReadSuccess("23");
            } catch (FatalProcessingException caught) {
                thrown = caught;
            }

            assertThat(thrown).isNotNull();
            assertThat(thrown.getAbendCulprit())
                    .as("CSMSG02Y ABEND-CULPRIT X(08) names the failing program")
                    .isEqualTo("CBACT04C");
            assertThat(thrown.getAbendReason()).isNotBlank();
            assertThat(thrown.getAbendMessage()).isNotBlank();
            assertThat(thrown.getAbendCode())
                    .as("the code field is present; the mapper leaves it unset rather than inventing one")
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);
        }
    }

    @Nested
    @DisplayName("5. The statement file service distinguishes an open from a read")
    class FileServiceAsymmetry {

        @Test
        @DisplayName("an open accepts 00 or 04, per CBSTM03A.CBL:736")
        void openAcceptsSuccessSecondary() {
            mapper.requireFileServiceSuccess("00", "TRNXFILE", "OPEN");
            mapper.requireFileServiceSuccess("04", "TRNXFILE", "OPEN");

            assertThat(FileStatus.SUCCESS_SECONDARY.code())
                    .as("04 is a named status, not a magic literal")
                    .contains("04");
        }

        @Test
        @DisplayName("a read accepts 00 or 10 and abends on 04, per CBSTM03A.CBL:353")
        void readRejectsSuccessSecondary() {
            // The EVALUATE at :353 lists WHEN '00' and WHEN '10' and sends everything else to
            // 9999-ABEND-PROGRAM. '04' is therefore fatal at a read even though it is success at an open.
            // Reading the corpus settled this; the plan's prose would have permitted 04 here.
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile("00", "XREFFILE", "READ")).isFalse();
            assertThat(mapper.requireFileServiceSuccessOrEndOfFile("10", "XREFFILE", "READ")).isTrue();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> mapper.requireFileServiceSuccessOrEndOfFile("04", "XREFFILE", "READ"))
                    .as("04 is accepted when opening and fatal when reading");
        }

        @Test
        @DisplayName("the abend names the statement program and quotes the return code")
        void abendNamesTheStatementProgram() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> mapper.requireFileServiceSuccess("12", "TRNXFILE", "OPEN"))
                    .satisfies(thrown -> {
                        assertThat(thrown.getAbendCulprit()).isEqualTo("CBSTM03A");
                        assertThat(thrown.getAbendMessage())
                                .contains(FileStatusMapper.FILE_SERVICE_RETURN_CODE_TEXT)
                                .contains("12")
                                .contains("TRNXFILE")
                                .contains("OPEN");
                    });
        }

        @Test
        @DisplayName("an absent dd name or operation is disclosed, not rendered as null")
        void absentArgumentsAreDisclosed() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> mapper.requireFileServiceSuccess("12", null, null))
                    .satisfies(thrown -> assertThat(thrown.getAbendMessage())
                            .doesNotContain("null")
                            .contains("unidentified"));
        }
    }
}
