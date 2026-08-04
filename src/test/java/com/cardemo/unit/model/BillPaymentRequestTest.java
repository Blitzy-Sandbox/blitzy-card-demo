/*
 * ******************************************************************
 * Program     : BillPaymentRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Pins the bill-payment payload to its frozen field
 *               contract and to the semantics of the program that
 *               drives it: the ten screen fields COBIL0AI declares,
 *               the fourteen-character balance mask that is not the
 *               account map's fifteen, the four-way confirmation
 *               gate in which a blank is not an error, the payment
 *               that is always the whole balance, and the online
 *               timestamp whose eleventh byte is a space.
 * Source      : app/cpy-bms/COBIL00.CPY (10 input fields, group
 *               COBIL0AI) + app/cbl/COBIL00C.cbl @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.dto.BillPaymentRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link BillPaymentRequest} against the frozen bill-payment screen and the program behind it.
 *
 * <h2>What it does</h2>
 *
 * <p>This payload is the boundary of one legacy screen, and almost everything interesting about it is a
 * width or a literal that must not drift. The tests below therefore read the contract out of the frozen
 * corpus rather than restating it, and each assertion names the locator that proves it:</p>
 *
 * <ul>
 *   <li><strong>The ten input fields</strong> of group {@code COBIL0AI} - {@code TRNNAMEI X(4)} at
 *       {@code app/cpy-bms/COBIL00.CPY:24}, {@code TITLE01I X(40)} at {@code :30},
 *       {@code CURDATEI X(8)} at {@code :36}, {@code PGMNAMEI X(8)} at {@code :42},
 *       {@code TITLE02I X(40)} at {@code :48}, {@code CURTIMEI X(8)} at {@code :54},
 *       {@code ACTIDINI X(11)} at {@code :60}, {@code CURBALI X(14)} at {@code :66},
 *       {@code CONFIRMI X(1)} at {@code :72} and {@code ERRMSGI X(78)} at {@code :78}. This is the
 *       smallest of the seventeen symbolic maps.</li>
 *   <li><strong>No shared header abstraction.</strong> {@code CURTIMEI} is eight characters here and nine
 *       at {@code app/cpy-bms/COSGN00.CPY:54}, so the six-field header is declared inline on every map
 *       rather than factored out.</li>
 *   <li><strong>No shared balance formatter.</strong> {@code CURBALI} is fourteen characters, matching the
 *       {@code WS-CURR-BAL PIC +9999999999.99} mask at {@code app/cbl/COBIL00C.cbl:56} exactly, while the
 *       account maps declare {@code ACURBALI PIC X(15)} at {@code app/cpy-bms/COACTVW.CPY:102} and
 *       {@code app/cpy-bms/COACTUP.CPY:138}.</li>
 *   <li><strong>The truncations, reproduced not repaired.</strong> The program's message work area is
 *       {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COBIL00C.cbl:39} but the field it reaches at
 *       {@code :293} is seventy-eight characters wide; and the amount mask
 *       {@code WS-TRAN-AMT PIC +99999999.99} at {@code :55} is twelve characters over a
 *       {@code TRAN-AMT PIC S9(09)V99} value ({@code app/cpy/CVTRA05Y.cpy:10}, bytes 133-143) that has
 *       nine integer digits - the same asymmetry as {@code app/cbl/COTRN02C.cbl:58-59}.</li>
 *   <li><strong>The four-way confirmation gate</strong> of {@code app/cbl/COBIL00C.cbl:173-190}, in which
 *       {@code 'Y'}/{@code 'y'} and {@code 'N'}/{@code 'n'} are each accepted in both cases, a blank or a
 *       low value reads the account and raises no error at all, and anything else answers
 *       {@code 'Invalid value. Valid values are (Y/N)...'} verbatim.</li>
 *   <li><strong>The full-balance semantics</strong> of {@code :193-195}, {@code :198-201} and
 *       {@code :217-237}: the guard refuses a balance at or below zero but only once an account
 *       identifier has been supplied, the payment is the entire balance, and the balance lands on exactly
 *       zero. Consequently the map declares no amount field, and none is invented here.</li>
 *   <li><strong>The identifier</strong> generated by the descending browse whose end-of-file path at
 *       {@code :472-496} moves zeros, so the first identifier is one, sixteen characters and zero
 *       padded.</li>
 *   <li><strong>The online timestamp</strong> built at {@code :249-267} over the layout at
 *       {@code app/cpy/CSDAT01Y.cpy:42-55}, whose eleventh byte is a declared space - not the batch
 *       rendering of {@code app/cbl/CBACT04C.cbl:613-625} and {@code app/cbl/CBTRN02C.cbl:149-175},
 *       which carries three dashes and hundredths. Corroborated by
 *       {@code app/data/ASCII/dailytran.txt}, whose three hundred records carry one single value in
 *       columns 279-304.</li>
 *   <li><strong>The tri-state model</strong> of {@code app/cpy/CSSETATY.cpy} and
 *       {@code app/cbl/COACTUPC.cbl:505-508}, and the gated cross-field edit at
 *       {@code app/cbl/COACTUPC.cbl:1665-1675} that forbids an unconditional class-level constraint.</li>
 *   <li><strong>Absence of session state</strong> - {@code app/cpy/COCOM01Y.cpy:43-44} declares
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} as {@code PIC X(7)}, and neither they nor
 *       the navigation fields at {@code :21-24} and {@code :29} have any counterpart on this payload.</li>
 *   <li><strong>Fixed-width geometry</strong> read through {@link FixtureLoader} from
 *       {@code app/data/ASCII/acctdata.txt} and {@code app/data/ASCII/dailytran.txt} - by classpath
 *       resource name, never by filesystem path, and never written.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Ddependency-check.skip=true test} runs this class;
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify} runs it inside the full gate. Maven
 * Surefire owns this tier: it collects {@code **}{@code /*Test.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class moved out of
 * {@code src/test/java/com/cardemo/unit/} would be collected by neither Surefire nor Failsafe and would
 * silently never run. Surefire also pins the working directory to the project base directory, which is
 * what lets {@link BmsSymbolicMap} and {@link RecordLayoutCopybook} resolve {@code app/cpy-bms} and
 * {@code app/cpy} relatively.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Pure JVM: no container, no Spring context, no database and no cloud endpoint, so nothing here can
 * reach a live service and no credential is needed. Every instant comes from
 * {@link FixedClockProvider}, never from {@code now()}, and every parse and format pins
 * {@link Locale#ROOT}, so a host time zone or locale cannot change an outcome. Monetary values are
 * {@link BigDecimal} with {@link RoundingMode#HALF_EVEN} and are compared with
 * {@link BigDecimal#compareTo} rather than {@code equals}, which also compares scale. Bean-validation
 * outcomes come from the shared validator in {@link ValidationSupport}; there is no mock and therefore no
 * strictness setting to tune.</p>
 *
 * <h2>What this tier does not verify - not available here</h2>
 *
 * <p>No schema text is read: {@code src/main/resources/db/migration/V1__create_schema.sql} is outside this
 * pure-JVM tier and outside this class's dependencies, so no column type, constraint or index is asserted
 * anywhere below. Where a group-three message names a decimal form such as {@code NUMERIC(11,2)}, it
 * states the mapping the migration prescribes for that PIC clause and nothing more; the assertion itself
 * is made against the copybook geometry read from {@link RecordLayoutCopybook}. Whether the deployed
 * schema declares that form is <strong>Not available</strong> to this class - verifying it would need the
 * migration file in scope, and it is covered by the schema-level tests instead. Nothing about it is
 * invented here.</p>
 *
 * <h2>Common failure modes</h2>
 *
 * <p>Each is classified by the severity a review would assign it.</p>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - treating a blank confirmation as invalid. It is its own {@code WHEN}
 *       branch, raises no error, and is the initial-display path.</li>
 *   <li><strong>Blocker</strong> - inventing a payment-amount field, or modelling the confirmation as a
 *       boolean. The payment is always the entire balance, and the gate has four states.</li>
 *   <li><strong>Blocker</strong> - altering a message literal, or letting a card number reach a
 *       rendering.</li>
 *   <li><strong>High</strong> - sharing a balance formatter or a header abstraction with the account
 *       payloads. Fourteen characters here against fifteen there, eight against nine.</li>
 *   <li><strong>High</strong> - writing the zero-balance guard as {@code <} rather than {@code <=}, so a
 *       balance of exactly zero is paid instead of refused.</li>
 *   <li><strong>High</strong> - repairing the twelve-character amount mask, widening the error line to
 *       eighty, or holding a timestamp in a temporal type.</li>
 *   <li><strong>Medium</strong> - spelling the daily-transaction fixture {@code dalytran.txt}. It is
 *       {@code dailytran.txt}, spelled in full, even though the legacy DD name is {@code DALYTRAN}.</li>
 *   <li><strong>Medium</strong> - an unused import or a raw type, which fails the build outright because
 *       {@code -Xlint:all -Werror} with {@code failOnWarning} reaches test compilation.</li>
 * </ul>
 */
@DisplayName("BillPaymentRequest - app/cpy-bms/COBIL00.CPY group COBIL0AI + app/cbl/COBIL00C.cbl @ 7756d89")
final class BillPaymentRequestTest {

    /** The bill-payment symbolic map, parsed from the frozen tree on each use. */
    private static final BmsSymbolicMap COBIL00 = BmsSymbolicMap.of("COBIL00");

    /** Each {@code COBIL0AI} input field paired with its record component, in copybook order. */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "TRNNAMEI", "transactionName",
            "TITLE01I", "title01",
            "CURDATEI", "currentDate",
            "PGMNAMEI", "programName",
            "TITLE02I", "title02",
            "CURTIMEI", "currentTime",
            "ACTIDINI", "accountId",
            "CURBALI", "currentBalance",
            "CONFIRMI", "confirmation",
            "ERRMSGI", "errorMessage");

    /** The six screen-header components that recur, at their own widths, on every one of the maps. */
    private static final List<String> HEADER_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime");

    /** {@code WS-CURR-BAL PIC +9999999999.99} at {@code app/cbl/COBIL00C.cbl:56}. */
    private static final String BALANCE_MASK = "+9999999999.99";

    /** {@code WS-TRAN-AMT PIC +99999999.99} at {@code app/cbl/COBIL00C.cbl:55}. */
    private static final String AMOUNT_MASK = "+99999999.99";

    /** {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COBIL00C.cbl:39}, two bytes wider than the field. */
    private static final int MESSAGE_WORK_AREA_WIDTH = 80;

    /** {@code 'Invalid value. Valid values are (Y/N)...'} at {@code app/cbl/COBIL00C.cbl:187}. */
    private static final String MSG_CONFIRMATION_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** {@code 'You have nothing to pay...'} at {@code app/cbl/COBIL00C.cbl:201}. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** {@code 'Confirm to make a bill payment...'} at {@code app/cbl/COBIL00C.cbl:237}. */
    private static final String MSG_CONFIRMATION_REQUIRED = "Confirm to make a bill payment...";

    /** {@code MOVE '02' TO TRAN-TYPE-CD} at {@code app/cbl/COBIL00C.cbl:220}. */
    private static final String TRANSACTION_TYPE_CODE = "02";

    /**
     * {@code MOVE 2 TO TRAN-CAT-CD} at {@code app/cbl/COBIL00C.cbl:221} - a numeric literal into
     * {@code PIC 9(04)}, so the stored value is four digits.
     */
    private static final int TRANSACTION_CATEGORY_CODE = 2;

    /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} at {@code app/cbl/COBIL00C.cbl:222}. */
    private static final String TRANSACTION_SOURCE = "POS TERM";

    /** {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} at {@code app/cbl/COBIL00C.cbl:223}. */
    private static final String TRANSACTION_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /** {@code MOVE 999999999 TO TRAN-MERCHANT-ID} at {@code app/cbl/COBIL00C.cbl:226}. */
    private static final long MERCHANT_ID = 999_999_999L;

    /** {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} at {@code app/cbl/COBIL00C.cbl:227}. */
    private static final String MERCHANT_NAME = "BILL PAYMENT";

    /** {@code 'N/A'} into both merchant city and merchant ZIP, {@code app/cbl/COBIL00C.cbl:228-229}. */
    private static final String NOT_APPLICABLE = "N/A";

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} on every row of {@code app/data/ASCII/dailytran.txt}. */
    private static final String FIXTURE_MERCHANT_ID = "800000000";

    /** {@code DALYTRAN-SOURCE} on fifty of the three hundred fixture rows, matching no program literal. */
    private static final String FIXTURE_UNSOURCED_VALUE = "OPERATOR";

    /** The one-based column of {@code TRAN-ORIG-TS} in the 350-byte layout of {@code CVTRA05Y}. */
    private static final int ORIGINATING_TIMESTAMP_COLUMN = 279;

    /** The one-based column of {@code ACCT-CURR-BAL} in the 300-byte layout of {@code CVACT01Y}. */
    private static final int ACCOUNT_BALANCE_COLUMN = 13;

    /** The one-based column of {@code ACCT-ADDR-ZIP} in the 300-byte layout of {@code CVACT01Y}. */
    private static final int ACCOUNT_ZIP_COLUMN = 103;

    /** {@code ACCT-ADDR-ZIP} on all fifty account rows - alphabetic, so no digits-only rule may exist. */
    private static final String ACCOUNT_ZIP_VALUE = "A000000000";

    /** The width of every generated transaction identifier, {@code TRAN-ID PIC X(16)}. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** The scale of every monetary and rate field in this corpus: the {@code V99} of the PIC clause. */
    private static final int MONEY_SCALE = 2;

    /** The four states {@code EVALUATE CONFIRMI} distinguishes at {@code app/cbl/COBIL00C.cbl:173-190}. */
    private enum Confirmation {

        /** {@code WHEN 'Y'} or {@code WHEN 'y'} at {@code :174-175}: the account is read and paid. */
        AFFIRMATIVE,

        /** {@code WHEN 'N'} or {@code WHEN 'n'} at {@code :178-179}: the screen clears, error flag set. */
        NEGATIVE,

        /** {@code WHEN SPACES} or {@code WHEN LOW-VALUES} at {@code :182-183}: read, and no error. */
        BLANK,

        /** {@code WHEN OTHER} at {@code :186}: the error flag and the invalid-value message. */
        INVALID
    }

    /**
     * Classifies one confirmation character exactly as {@code EVALUATE CONFIRMI OF COBIL0AI} does at
     * {@code app/cbl/COBIL00C.cbl:173-190}.
     *
     * <p>The case insensitivity is confined to this one field by two explicit {@code WHEN} clauses per
     * outcome; it is not a case function applied to the payload, and no other character joins the set.
     * A {@code null} models a field the terminal never transmitted, which is the same branch as a blank
     * because {@code INITIALIZE} leaves the field low valued.</p>
     *
     * @param confirmation the raw {@code CONFIRMI} character, which may be {@code null}
     * @return the branch the source would have taken
     */
    private static Confirmation confirmationOf(final String confirmation) {
        if (confirmation == null || confirmation.isEmpty() || "\u0000".equals(confirmation)
                || confirmation.isBlank()) {
            return Confirmation.BLANK;
        }
        return switch (confirmation) {
            case "Y", "y" -> Confirmation.AFFIRMATIVE;
            case "N", "n" -> Confirmation.NEGATIVE;
            default -> Confirmation.INVALID;
        };
    }

    /**
     * Returns the message the source moves into {@code WS-MESSAGE} for a confirmation branch.
     *
     * @param confirmation the branch taken
     * @return the message literal, or the empty string when the branch sets none
     */
    private static String messageFor(final Confirmation confirmation) {
        return switch (confirmation) {
            case INVALID -> MSG_CONFIRMATION_INVALID;
            case NEGATIVE, BLANK, AFFIRMATIVE -> "";
        };
    }

    /**
     * Reports whether a screen field carries a value at all, reproducing
     * {@code NOT = SPACES AND LOW-VALUES}.
     *
     * <p>That condition is an abbreviated combined relation and expands to
     * {@code NOT = SPACES AND NOT = LOW-VALUES}: it is true only when the field is neither blank nor low
     * valued, which is to say only when the terminal actually sent something.</p>
     *
     * @param field the raw screen field, which may be {@code null}
     * @return {@code true} when the field holds a value that is neither blank nor low valued
     */
    private static boolean supplied(final String field) {
        return field != null && !field.isEmpty() && !field.isBlank()
                && field.chars().anyMatch(character -> character != 0);
    }

    /**
     * Reproduces the guard at {@code app/cbl/COBIL00C.cbl:198-199}.
     *
     * <p>Two things about it are easy to get wrong. The comparison is {@code <= ZEROS}, so a balance of
     * exactly zero is refused rather than paid. And the second half of the condition means the guard
     * cannot fire at all until an account identifier has been supplied, which is what keeps the message
     * off the first, empty display of the screen.</p>
     *
     * @param balance   the authoritative {@code ACCT-CURR-BAL} read from the account record
     * @param accountId the {@code ACTIDINI} value submitted with the request
     * @return {@code true} when the source would answer with the nothing-to-pay message
     */
    private static boolean nothingToPay(final BigDecimal balance, final String accountId) {
        return balance.signum() <= 0 && supplied(accountId);
    }

    /**
     * Renders a value on a COBOL numeric-edited mask of the {@code +9(n).99} family.
     *
     * <p>The sign is mandatory and therefore always present, the integer part is zero filled to the mask's
     * width rather than space filled, and the fraction is always two digits. Rounding is
     * {@link RoundingMode#HALF_EVEN} and formatting pins {@link Locale#ROOT}, so no host setting can
     * reach the output. A value with more integer digits than the mask holds is truncated on the left,
     * exactly as a COBOL {@code MOVE} into the edited field truncates - which is the behaviour the
     * twelve-character amount mask exhibits over a nine-digit value and which is reproduced, not
     * repaired.</p>
     *
     * @param value         the value to render
     * @param integerDigits the count of {@code 9}s before the decimal point in the mask
     * @return the rendered field, one sign character plus {@code integerDigits} digits plus a point plus
     *         two digits
     */
    private static String renderOnMask(final BigDecimal value, final int integerDigits) {
        final BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        final BigDecimal magnitude = scaled.abs();
        final String digits = magnitude.unscaledValue().toString();
        final String padded = "0".repeat(Math.max(0, integerDigits + MONEY_SCALE - digits.length()))
                + digits;
        final String kept = padded.substring(padded.length() - (integerDigits + MONEY_SCALE));
        return String.format(Locale.ROOT, "%s%s.%s", scaled.signum() < 0 ? "-" : "+",
                kept.substring(0, integerDigits), kept.substring(integerDigits));
    }

    /**
     * Counts the integer digit positions in a numeric-edited picture of the {@code +9(n).99} family.
     *
     * @param mask the picture as the program spells it
     * @return the number of {@code 9}s before the decimal point
     */
    private static int integerDigitsOf(final String mask) {
        return (int) mask.substring(0, mask.indexOf('.')).chars().filter(digit -> digit == '9').count();
    }

    /**
     * Reproduces a COBOL alphanumeric {@code MOVE} into a {@code PIC X(n)} receiving field: left
     * justified, space filled to the receiving width and truncated on the right.
     *
     * @param value the sending literal
     * @param width the receiving field's declared width
     * @return the receiving field's contents after the move
     */
    private static String movedInto(final String value, final int width) {
        return value.length() >= width ? value.substring(0, width)
                : value + " ".repeat(width - value.length());
    }

    /**
     * Reproduces a COBOL numeric {@code MOVE} into a {@code PIC 9(n)} receiving field: right justified
     * and zero filled.
     *
     * @param value the sending literal
     * @param width the receiving field's declared width
     * @return the receiving field's contents after the move
     */
    private static String movedInto(final long value, final int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    /**
     * Reproduces the descending-browse identifier generation of {@code app/cbl/COBIL00C.cbl:212-217}.
     *
     * <p>High values go into the key, the browse reads the previous record, and one is added to whatever
     * identifier came back. When the file is empty the {@code DFHRESP(ENDFILE)} branch at {@code :472-496}
     * moves zeros into the key first, so the first identifier a run ever produces is one.</p>
     *
     * <p>The algorithm is inherently racy: two concurrent payments read the same maximum and compute the
     * same successor. It is retained exactly as written, because parity is the contract - a database
     * sequence would generate different values and a collision is meant to surface as a duplicate-key
     * failure from the primary key, which is what the source's own duplicate handling did. Recorded as a
     * preserved quirk in {@code DECISION_LOG.md}.</p>
     *
     * @param highestOnFile the identifier the browse returned, or {@code null} for the end-of-file case
     * @return the next identifier, sixteen characters and zero padded so leading zeros survive
     */
    private static String nextTransactionId(final String highestOnFile) {
        final long highest = highestOnFile == null ? 0L : Long.parseLong(highestOnFile.trim());
        return movedInto(highest + 1L, TRANSACTION_ID_WIDTH);
    }

    /**
     * The transaction record the confirmed payment builds at {@code app/cbl/COBIL00C.cbl:218-232}, with
     * every field held at the width its {@code CVTRA05Y} PIC clause declares.
     *
     * @param id                     {@code TRAN-ID PIC X(16)}
     * @param typeCode               {@code TRAN-TYPE-CD PIC X(02)}
     * @param categoryCode           {@code TRAN-CAT-CD PIC 9(04)}
     * @param source                 {@code TRAN-SOURCE PIC X(10)} - free text, never a closed enumeration
     * @param description            {@code TRAN-DESC PIC X(100)}
     * @param amount                 {@code TRAN-AMT PIC S9(09)V99}, the whole balance
     * @param cardNumber             {@code TRAN-CARD-NUM PIC X(16)}, taken from the cross reference
     * @param merchantId             {@code TRAN-MERCHANT-ID PIC 9(09)}
     * @param merchantName           {@code TRAN-MERCHANT-NAME PIC X(50)}
     * @param merchantCity           {@code TRAN-MERCHANT-CITY PIC X(50)}
     * @param merchantZip            {@code TRAN-MERCHANT-ZIP PIC X(10)}
     * @param originatingTimestamp   {@code TRAN-ORIG-TS PIC X(26)}
     * @param processingTimestamp    {@code TRAN-PROC-TS PIC X(26)}, the same generated value
     */
    private record PostedTransaction(String id, String typeCode, String categoryCode, String source,
            String description, BigDecimal amount, String cardNumber, String merchantId,
            String merchantName, String merchantCity, String merchantZip, String originatingTimestamp,
            String processingTimestamp) {
    }

    /**
     * The outcome of a confirmed payment: the record written, and the balance left behind.
     *
     * @param transaction      the transaction the payment wrote
     * @param remainingBalance {@code ACCT-CURR-BAL} after {@code app/cbl/COBIL00C.cbl:234}
     */
    private record Posting(PostedTransaction transaction, BigDecimal remainingBalance) {
    }

    /**
     * Reproduces the confirmed-payment branch of {@code app/cbl/COBIL00C.cbl:210-235} end to end.
     *
     * <p>The amount is the entire balance and never a portion of it, which is why the screen declares no
     * amount field; the subtraction that follows therefore lands on exactly zero. Both timestamps receive
     * the same generated value from one {@code MOVE} with two receiving fields.</p>
     *
     * @param balance       the authoritative balance read from the account record
     * @param cardNumber    the card number the cross-reference read supplied
     * @param highestOnFile the identifier the descending browse returned, or {@code null} at end of file
     * @param clock         the fixed clock standing in for {@code EXEC CICS ASKTIME}
     * @return the transaction written and the balance remaining
     */
    private static Posting pay(final BigDecimal balance, final String cardNumber,
            final String highestOnFile, final Clock clock) {
        final RecordLayoutCopybook layout = RecordLayoutCopybook.of("CVTRA05Y");
        final String timestamp = FixedClockProvider.onlineTimestamp(clock);
        final BigDecimal amount = balance.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        final PostedTransaction transaction = new PostedTransaction(
                nextTransactionId(highestOnFile),
                movedInto(TRANSACTION_TYPE_CODE, layout.widthOf("TRAN-TYPE-CD")),
                movedInto(TRANSACTION_CATEGORY_CODE, layout.widthOf("TRAN-CAT-CD")),
                movedInto(TRANSACTION_SOURCE, layout.widthOf("TRAN-SOURCE")),
                movedInto(TRANSACTION_DESCRIPTION, layout.widthOf("TRAN-DESC")),
                amount,
                movedInto(cardNumber, layout.widthOf("TRAN-CARD-NUM")),
                movedInto(MERCHANT_ID, layout.widthOf("TRAN-MERCHANT-ID")),
                movedInto(MERCHANT_NAME, layout.widthOf("TRAN-MERCHANT-NAME")),
                movedInto(NOT_APPLICABLE, layout.widthOf("TRAN-MERCHANT-CITY")),
                movedInto(NOT_APPLICABLE, layout.widthOf("TRAN-MERCHANT-ZIP")),
                timestamp,
                timestamp);
        return new Posting(transaction, amount.subtract(transaction.amount()));
    }

    /**
     * Feeds the per-field width assertion, one case per copybook field.
     *
     * @return the ten pairs, each spelled {@code FIELD:component}, in copybook declaration order
     */
    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    /**
     * Builds a fully populated payload with one confirmation character substituted.
     *
     * @param confirmation the {@code CONFIRMI} value under test, which may be {@code null}
     * @return a payload whose every other component is within its declared width
     */
    private static BillPaymentRequest withConfirmation(final String confirmation) {
        return new BillPaymentRequest("CB00", "CardDemo Bill Payment", "08/01/26", "COBIL00C",
                "Bill Payment", "14:22:31", "00000000001", "+0000001940.00", confirmation, "");
    }

    /**
     * Builds a payload carrying one component and nothing else, for a boundary test.
     *
     * @param component the component to populate
     * @param value     the value to place in it
     * @return a payload with every other component absent
     */
    private static BillPaymentRequest with(final String component, final String value) {
        return new BillPaymentRequest(
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "accountId".equals(component) ? value : null,
                "currentBalance".equals(component) ? value : null,
                "confirmation".equals(component) ? value : null,
                "errorMessage".equals(component) ? value : null);
    }

    /**
     * Validates one payload through the shared validator, as a controller boundary would.
     *
     * @param request the payload to validate, which may carry {@code null} components
     * @return the violations raised, empty when the payload is within every declared width
     */
    private Set<ConstraintViolation<BillPaymentRequest>> violationsOf(final BillPaymentRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    @Nested
    @DisplayName("1. The ten input fields group COBIL0AI declares, and the two-byte message truncation")
    final class FieldContract {

        @Test
        @DisplayName("declares one component per input field, and the map declares exactly ten")
        void componentCountMatchesTheCopybook() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("app/cpy-bms/COBIL00.CPY is the smallest of the seventeen symbolic maps")
                    .hasSize(COBIL00.inputFieldCount())
                    .hasSize(10);
        }

        @Test
        @DisplayName("bounds the input group between its declaration and the output redefinition")
        void theInputGroupIsBounded() {
            assertThat(COBIL00.member()).isEqualTo("COBIL00");
            assertThat(COBIL00.inputGroupLine())
                    .as("01 COBIL0AI. at app/cpy-bms/COBIL00.CPY:17, after the licence banner")
                    .isEqualTo(17);
            assertThat(COBIL00.outputRedefinitionLine())
                    .as("01 COBIL0AO REDEFINES COBIL0AI. at :79 - the ten input fields all precede it, "
                            + "so nothing from the output redefinition can be mistaken for an input")
                    .isEqualTo(79);
        }

        @Test
        @DisplayName("matches every component to the copybook field at the same index")
        void componentsCorrespondPositionally() {
            final List<String> components = RecordFieldContract.componentNames(BillPaymentRequest.class);
            final List<String> copybookOrder = COBIL00.fieldNames();

            assertThat(FIELD_TO_COMPONENT).hasSize(2 * components.size());
            for (int index = 0; index < components.size(); index++) {
                assertThat(copybookOrder.get(index))
                        .as("copybook field at index %d", index)
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index));
                assertThat(components.get(index))
                        .as("component at index %d", index)
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index + 1));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.BillPaymentRequestTest#fieldComponentPairs")
        @DisplayName("takes each declared width from the copybook rather than restating a literal")
        void declaredWidthComesFromTheCopybook(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);

            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, component))
                    .as("%s is PIC X(%d) on COBIL00", field, COBIL00.widthOf(field))
                    .isEqualTo(COBIL00.widthOf(field));
        }

        @Test
        @DisplayName("keeps the error line at seventy-eight, which is two narrower than the work area")
        void theMessageFieldIsNarrowerThanTheWorkArea() {
            final int field = COBIL00.widthOf("ERRMSGI");

            assertThat(field).as("ERRMSGI PIC X(78) at app/cpy-bms/COBIL00.CPY:78").isEqualTo(78);
            assertThat(MESSAGE_WORK_AREA_WIDTH - field)
                    .as("MOVE WS-MESSAGE TO ERRMSGO at app/cbl/COBIL00C.cbl:293 moves an eighty-character "
                            + "work area (:39) into a seventy-eight character field, so the last two bytes "
                            + "are silently lost on the way to the wire; the payload reproduces the field, "
                            + "not the work area")
                    .isEqualTo(2);
            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, "errorMessage"))
                    .as("widening to eighty would accept a message the screen could never have shown")
                    .isEqualTo(field)
                    .isNotEqualTo(MESSAGE_WORK_AREA_WIDTH);
        }

        @Test
        @DisplayName("accepts every message the field can hold, and refuses the seventy-ninth character")
        void theMessageBoundaryIsSeventyEight() {
            assertThat(violationsOf(with("errorMessage", "M".repeat(78)))).isEmpty();
            assertThat(violationsOf(with("errorMessage", "M".repeat(79))))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath()).hasToString("errorMessage"));
        }
    }

    @Nested
    @DisplayName("2. The six-field header is declared inline, because its widths are not uniform")
    final class HeaderIsNotShared {

        @Test
        @DisplayName("carries the same six header components every map carries")
        void theHeaderSextetIsPresent() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02 and CURTIME recur on all seventeen "
                            + "maps, at app/cpy-bms/COBIL00.CPY:24, :30, :36, :42, :48 and :54 here")
                    .startsWith(HEADER_COMPONENTS.toArray(new String[0]));
        }

        @Test
        @DisplayName("declares the header time as eight characters, which the sign-on map does not")
        void theHeaderTimeWidthDivergesFromTheSignOnMap() {
            final BmsSymbolicMap signOn = BmsSymbolicMap.of("COSGN00");

            assertThat(COBIL00.widthOf("CURTIMEI"))
                    .as("CURTIMEI PIC X(8) at app/cpy-bms/COBIL00.CPY:54")
                    .isEqualTo(8);
            assertThat(signOn.widthOf("CURTIMEI"))
                    .as("CURTIMEI PIC X(9) at app/cpy-bms/COSGN00.CPY:54 - the one map that differs, "
                            + "which is why no shared header type, base class or mixin may exist: it "
                            + "would have to pick one of the two widths and would be wrong on some map")
                    .isEqualTo(9)
                    .isNotEqualTo(COBIL00.widthOf("CURTIMEI"));
        }

        @Test
        @DisplayName("agrees with the sign-on map on the five header fields that are uniform")
        void theOtherFiveHeaderFieldsAreUniform() {
            final BmsSymbolicMap signOn = BmsSymbolicMap.of("COSGN00");

            for (final String field : List.of("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I")) {
                assertThat(COBIL00.widthOf(field))
                        .as("%s is uniform across the maps, so only CURTIMEI defeats the abstraction",
                                field)
                        .isEqualTo(signOn.widthOf(field));
            }
        }

        @Test
        @DisplayName("declares the header inline as flat components, with no nested type to share")
        void theHeaderIsNotAnEmbeddedType() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("ten flat components, so there is nothing for a header abstraction to hold")
                    .hasSize(10);
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(BillPaymentRequest.class))
                    .isNotEmpty()
                    .allSatisfy(typeName -> {
                        assertThat(typeName)
                                .as("a header value object would appear here as a com.cardemo type")
                                .doesNotContain("com.cardemo");
                        assertThat(typeName)
                                .as("no binary floating-point type may touch a financial payload")
                                .isNotEqualTo("float")
                                .isNotEqualTo("double")
                                .isNotEqualTo("java.lang.Float")
                                .isNotEqualTo("java.lang.Double");
                    });
        }
    }

    @Nested
    @DisplayName("3. The balance is a rendered mask exactly fourteen wide - not the account maps' fifteen")
    final class BalanceMask {

        @Test
        @DisplayName("counts the balance mask to exactly the width the screen field declares")
        void theMaskLengthIsTheFieldWidth() {
            assertThat(BALANCE_MASK)
                    .as("WS-CURR-BAL PIC +9999999999.99 at app/cbl/COBIL00C.cbl:56")
                    .hasSize(14);
            assertThat(1 + integerDigitsOf(BALANCE_MASK) + 1 + MONEY_SCALE)
                    .as("one mandatory sign, ten integer digits, the point and two decimals")
                    .isEqualTo(BALANCE_MASK.length());
            assertThat(COBIL00.widthOf("CURBALI"))
                    .as("CURBALI PIC X(14) at app/cpy-bms/COBIL00.CPY:66 - byte for byte the mask, which "
                            + "is the proof that the balance crosses the boundary as rendered text and "
                            + "not as a number")
                    .isEqualTo(BALANCE_MASK.length());
        }

        @Test
        @DisplayName("carries the balance as text, so a leading sign and zero fill survive the boundary")
        void theBalanceIsCarriedAsText() {
            final String rendered = renderOnMask(new BigDecimal("194.00"), integerDigitsOf(BALANCE_MASK));

            assertThat(rendered)
                    .as("MOVE ACCT-CURR-BAL TO WS-CURR-BAL then MOVE WS-CURR-BAL TO CURBALI, "
                            + "app/cbl/COBIL00C.cbl:193-194")
                    .isEqualTo("+0000000194.00")
                    .hasSize(COBIL00.widthOf("CURBALI"))
                    .startsWith("+");
            assertThat(violationsOf(with("currentBalance", rendered))).isEmpty();
        }

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource({
            "0.00,          +0000000000.00",
            "194.00,        +0000000194.00",
            "-919.00,       -0000000919.00",
            "9750.00,       +0000009750.00",
            "9999999999.99, +9999999999.99",
            "0.01,          +0000000000.01"
        })
        @DisplayName("renders a mandatory sign, ten zero-filled integer digits and two decimals")
        void theMaskShapeIsInvariant(final String value, final String expected) {
            final String rendered = renderOnMask(new BigDecimal(value), integerDigitsOf(BALANCE_MASK));

            assertThat(rendered).isEqualTo(expected).hasSize(14);
            assertThat(rendered.charAt(0)).isIn('+', '-');
            assertThat(rendered.charAt(11)).as("the decimal point sits at position 12").isEqualTo('.');
        }

        @Test
        @DisplayName("does not share a width, and therefore a formatter, with the account maps")
        void theAccountMapsAreWider() {
            final int view = BmsSymbolicMap.of("COACTVW").widthOf("ACURBALI");
            final int update = BmsSymbolicMap.of("COACTUP").widthOf("ACURBALI");

            assertThat(view).as("ACURBALI PIC X(15) at app/cpy-bms/COACTVW.CPY:102").isEqualTo(15);
            assertThat(update).as("ACURBALI PIC X(15) at app/cpy-bms/COACTUP.CPY:138").isEqualTo(15);
            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, "currentBalance"))
                    .as("fourteen here against fifteen there, and the field is named differently too, so "
                            + "a helper shared with AccountDto or AccountUpdateRequest would have to "
                            + "truncate or pad - neither of which the source does")
                    .isEqualTo(14)
                    .isNotEqualTo(view);
            assertThat(COBIL00.declares("ACURBALI")).isFalse();
            assertThat(BmsSymbolicMap.of("COACTVW").declares("CURBALI")).isFalse();
        }

        @Test
        @DisplayName("accepts a balance filling the field and refuses one character more")
        void theBoundaryIsTheCopybookWidth() {
            final int width = COBIL00.widthOf("CURBALI");

            assertThat(violationsOf(with("currentBalance", "9".repeat(width)))).isEmpty();
            assertThat(violationsOf(with("currentBalance", "9".repeat(width + 1))))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath()).hasToString("currentBalance"));
        }

        @Test
        @DisplayName("keeps the amount mask twelve wide over a nine-digit value, truncation and all")
        void theAmountMaskTruncatesNineIntegerDigits() {
            final RecordLayoutCopybook transaction = RecordLayoutCopybook.of("CVTRA05Y");
            final int storedIntegerDigits = transaction.geometry("TRAN-AMT").digits();

            assertThat(AMOUNT_MASK)
                    .as("WS-TRAN-AMT PIC +99999999.99 at app/cbl/COBIL00C.cbl:55")
                    .hasSize(12);
            assertThat(integerDigitsOf(AMOUNT_MASK)).isEqualTo(8);
            assertThat(storedIntegerDigits)
                    .as("TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:10 holds nine integer digits, so "
                            + "the eight-digit mask cannot render the largest value it can store - the "
                            + "same asymmetry as WS-TRAN-AMT-N beside WS-TRAN-AMT-E at "
                            + "app/cbl/COTRN02C.cbl:58-59")
                    .isEqualTo(9)
                    .isGreaterThan(integerDigitsOf(AMOUNT_MASK));
            assertThat(renderOnMask(new BigDecimal("123456789.99"), integerDigitsOf(AMOUNT_MASK)))
                    .as("the leading digit is dropped exactly as the legacy MOVE drops it; the truncation "
                            + "is reproduced and never repaired")
                    .isEqualTo("+23456789.99");
        }

        @Test
        @DisplayName("keeps the three precision tiers distinct, never collapsing them onto one")
        void thePrecisionTiersStayDistinct() {
            final RecordLayoutCopybook.Geometry accountMoney =
                    RecordLayoutCopybook.of("CVACT01Y").geometry("ACCT-CURR-BAL");
            final RecordLayoutCopybook.Geometry transactionAmount =
                    RecordLayoutCopybook.of("CVTRA05Y").geometry("TRAN-AMT");
            final RecordLayoutCopybook.Geometry interestRate =
                    RecordLayoutCopybook.of("CVTRA02Y").geometry("DIS-INT-RATE");

            assertThat(accountMoney.width())
                    .as("ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:7 occupies twelve digit "
                            + "positions and becomes NUMERIC(12,2) - the balance this screen pays is the "
                            + "account-money tier")
                    .isEqualTo(12);
            assertThat(transactionAmount.width())
                    .as("TRAN-AMT PIC S9(09)V99 becomes NUMERIC(11,2) - the amount tier, one digit "
                            + "narrower than the balance it is copied from")
                    .isEqualTo(11);
            assertThat(interestRate.width())
                    .as("DIS-INT-RATE PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy becomes NUMERIC(6,2)")
                    .isEqualTo(6);
            assertThat(List.of(accountMoney.digits(), transactionAmount.digits(), interestRate.digits()))
                    .as("ten, nine and four integer digits respectively, which is the whole of the "
                            + "difference between the three tiers")
                    .containsExactly(10, 9, 4);
            assertThat(List.of(accountMoney.scale(), transactionAmount.scale(), interestRate.scale()))
                    .as("every tier shares the V99 scale, which is the only thing they do share")
                    .containsOnly(MONEY_SCALE);
            assertThat(List.of(accountMoney.signed(), transactionAmount.signed(), interestRate.signed()))
                    .as("all three are signed, so no absolute-value normalisation is admissible")
                    .containsOnly(true);
        }

        @Test
        @DisplayName("compares monetary values by value and not by scale, and uses no binary float")
        void monetaryComparisonIgnoresScale() {
            final BigDecimal fromTheScreen = new BigDecimal("194.0");
            final BigDecimal fromTheRecord = new BigDecimal("194.00");

            assertThat(fromTheScreen.compareTo(fromTheRecord))
                    .as("compareTo is the only admissible equality for a decimal amount")
                    .isZero();
            assertThat(fromTheScreen.equals(fromTheRecord))
                    .as("BigDecimal.equals also compares scale, so 194.0 and 194.00 are unequal by it - "
                            + "which is precisely why it is never used on a financial field")
                    .isFalse();
            assertThat(new BigDecimal("0.145").setScale(MONEY_SCALE, RoundingMode.HALF_EVEN))
                    .as("HALF_EVEN, the rounding the migration mandates")
                    .isEqualByComparingTo("0.14");
            assertThat(new BigDecimal("0.155").setScale(MONEY_SCALE, RoundingMode.HALF_EVEN))
                    .isEqualByComparingTo("0.16");
        }
    }

    @Nested
    @DisplayName("4. The confirmation gate has four outcomes, and a blank is not one of the failures")
    final class ConfirmationGate {

        @ParameterizedTest(name = "[{0}] is affirmative")
        @ValueSource(strings = {"Y", "y"})
        @DisplayName("accepts both cases of the affirmative, which is why no case function is applied")
        void bothCasesOfTheAffirmativeAreAccepted(final String confirmation) {
            assertThat(confirmationOf(confirmation))
                    .as("WHEN 'Y' and WHEN 'y' are two separate branches of the one EVALUATE at "
                            + "app/cbl/COBIL00C.cbl:174-175, so the insensitivity is confined to this "
                            + "field and is not a FUNCTION UPPER-CASE over the payload")
                    .isEqualTo(Confirmation.AFFIRMATIVE);
            assertThat(messageFor(confirmationOf(confirmation)))
                    .as("the affirmative reads the account and pays; it sets no message")
                    .isEmpty();
            assertThat(withConfirmation(confirmation).confirmation())
                    .as("the payload transports the character verbatim, so the service can still see "
                            + "which of the two the terminal actually sent")
                    .isEqualTo(confirmation);
        }

        @ParameterizedTest(name = "[{0}] is negative")
        @ValueSource(strings = {"N", "n"})
        @DisplayName("accepts both cases of the negative, which clears the screen and flags an error")
        void bothCasesOfTheNegativeAreAccepted(final String confirmation) {
            assertThat(confirmationOf(confirmation))
                    .as("WHEN 'N' and WHEN 'n' at app/cbl/COBIL00C.cbl:178-179 PERFORM "
                            + "CLEAR-CURRENT-SCREEN and MOVE 'Y' TO WS-ERR-FLG")
                    .isEqualTo(Confirmation.NEGATIVE);
            assertThat(messageFor(confirmationOf(confirmation)))
                    .as("the negative raises the error flag but moves no message of its own")
                    .isEmpty();
        }

        @Test
        @DisplayName("treats a blank as its own outcome, reading the account and raising NO error")
        void aBlankIsNotAnError() {
            assertThat(confirmationOf(" "))
                    .as("WHEN SPACES at app/cbl/COBIL00C.cbl:182 shares its branch with WHEN LOW-VALUES "
                            + "at :183, and that branch does nothing but PERFORM READ-ACCTDAT-FILE - this "
                            + "is the initial-display path, so rejecting a blank would make the screen "
                            + "unreachable on first entry")
                    .isEqualTo(Confirmation.BLANK);
            assertThat(messageFor(confirmationOf(" "))).isEmpty();
            assertThat(confirmationOf(Confirmation.BLANK.name()))
                    .as("only the exact characters are in the set; a word is not a blank")
                    .isEqualTo(Confirmation.INVALID);
        }

        @Test
        @DisplayName("treats a low value and an absent field as the same outcome as a blank")
        void lowValuesAndAbsenceJoinTheBlankBranch() {
            // The NUL is supplied here rather than through a parameter source because it cannot be
            // written into the Surefire XML report as part of a display name.
            assertThat(confirmationOf("\u0000")).isEqualTo(Confirmation.BLANK);
            assertThat(confirmationOf(null))
                    .as("a field the terminal never transmitted is low valued, so it takes the same "
                            + "branch; the payload still keeps absent and blank distinguishable")
                    .isEqualTo(Confirmation.BLANK);
            assertThat(confirmationOf("")).isEqualTo(Confirmation.BLANK);
            assertThat(violationsOf(withConfirmation(null))).isEmpty();
            assertThat(violationsOf(withConfirmation(" "))).isEmpty();
            assertThat(violationsOf(withConfirmation("\u0000"))).isEmpty();
        }

        @ParameterizedTest(name = "[{0}] is invalid")
        @ValueSource(strings = {"1", "0", "T", "F", "Q", "z", "*", "-", "j"})
        @DisplayName("sends every other single character to the invalid branch, widening nothing")
        void everyOtherCharacterIsInvalid(final String confirmation) {
            assertThat(confirmationOf(confirmation))
                    .as("WHEN OTHER at app/cbl/COBIL00C.cbl:186 - '1', '0', 'T' and 'F' are NOT "
                            + "affirmatives here, and the accepted set is exactly Y, y, N and n")
                    .isEqualTo(Confirmation.INVALID);
            assertThat(messageFor(confirmationOf(confirmation))).isEqualTo(MSG_CONFIRMATION_INVALID);
        }

        @Test
        @DisplayName("treats an over-length affirmative as invalid, and the boundary refuses it too")
        void anOverLengthAffirmativeIsInvalid() {
            assertThat(confirmationOf("yes"))
                    .as("the EVALUATE compares against one-character literals, so 'yes' can only reach "
                            + "WHEN OTHER")
                    .isEqualTo(Confirmation.INVALID);
            assertThat(COBIL00.widthOf("CONFIRMI")).isEqualTo(1);
            assertThat(violationsOf(withConfirmation("YY")))
                    .as("CONFIRMI PIC X(1) at app/cpy-bms/COBIL00.CPY:72 cannot hold two characters")
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath()).hasToString("confirmation"));
        }

        @Test
        @DisplayName("quotes the invalid-value message byte for byte, brackets and three dots included")
        void theInvalidMessageIsQuotedExactly() {
            assertThat(MSG_CONFIRMATION_INVALID)
                    .as("app/cbl/COBIL00C.cbl:187 - compared byte for byte by the parity gates, so it is "
                            + "never paraphrased and never re-punctuated")
                    .isEqualTo("Invalid value. Valid values are (Y/N)...")
                    .contains("(Y/N)")
                    .endsWith("...")
                    .doesNotEndWith("....")
                    .hasSize(40);
            assertThat(MSG_CONFIRMATION_INVALID.length())
                    .as("it fits the seventy-eight character error line without truncation")
                    .isLessThanOrEqualTo(COBIL00.widthOf("ERRMSGI"));
        }

        @Test
        @DisplayName("keeps all four outcomes reachable, which a boolean confirmation could not")
        void allFourOutcomesAreReachable() {
            assertThat(List.of(confirmationOf("Y"), confirmationOf("N"), confirmationOf(" "),
                            confirmationOf("Q")))
                    .as("four states, so the payload models the field as a nullable character and never "
                            + "as a boolean - a boolean carries two")
                    .containsExactly(Confirmation.AFFIRMATIVE, Confirmation.NEGATIVE, Confirmation.BLANK,
                            Confirmation.INVALID);
            assertThat(Confirmation.values()).hasSize(4);
            assertThat(withConfirmation("Y").confirmation()).isEqualTo("Y");
            assertThat(withConfirmation("N").confirmation()).isEqualTo("N");
            assertThat(withConfirmation(" ").confirmation())
                    .as("a transmitted blank is carried as a blank, not folded onto absence")
                    .isEqualTo(" ");
            assertThat(withConfirmation(null).confirmation())
                    .as("four distinguishable carried values - 'Y', 'N', a blank and absence - which a "
                            + "primitive boolean could not represent: it has two states and no null")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("5. The payment is the whole balance, and the guard refuses a balance of exactly zero")
    final class FullBalanceSemantics {

        @Test
        @DisplayName("declares no amount field, because the source never pays a portion")
        void thereIsNoAmountField() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("MOVE ACCT-CURR-BAL TO TRAN-AMT at app/cbl/COBIL00C.cbl:224 pays the entire "
                            + "balance, so the ten-field map has no amount input at all; inventing one "
                            + "would offer a caller a partial payment the source cannot express")
                    .doesNotContain("amount", "paymentAmount", "transactionAmount", "amountDue",
                            "partialAmount", "payment");
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("the only monetary component is the display echo of the balance")
                    .filteredOn(component -> component.toLowerCase(Locale.ROOT).contains("bal"))
                    .containsExactly("currentBalance");
        }

        @Test
        @DisplayName("drives the balance to exactly zero, never to a remainder")
        void theBalanceLandsOnZero() {
            final Posting posting = pay(new BigDecimal("194.00"), "4111111111111111", "0000000000000042",
                    FixedClockProvider.canonicalClock());

            assertThat(posting.transaction().amount())
                    .as("the amount is the balance itself, app/cbl/COBIL00C.cbl:224")
                    .isEqualByComparingTo("194.00");
            assertThat(posting.remainingBalance())
                    .as("COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT at :234 subtracts the whole "
                            + "balance from itself, so the account lands on exactly zero")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(posting.remainingBalance().signum()).isZero();
        }

        @ParameterizedTest(name = "balance {0} with an identifier supplied -> refused: {1}")
        @CsvSource({
            "-919.00, true",
            "-0.01,   true",
            "0.00,    true",
            "0.01,    false",
            "194.00,  false"
        })
        @DisplayName("refuses a balance at or below zero - the comparison is <=, not <")
        void theGuardIsAtOrBelowZero(final String balance, final boolean refused) {
            assertThat(nothingToPay(new BigDecimal(balance), "00000000001"))
                    .as("IF ACCT-CURR-BAL <= ZEROS at app/cbl/COBIL00C.cbl:198 - a balance of exactly "
                            + "zero is refused, so writing the guard as < would accept a payment the "
                            + "source declines and would then write a zero-amount transaction")
                    .isEqualTo(refused);
        }

        @Test
        @DisplayName("quotes the nothing-to-pay message byte for byte")
        void theNothingToPayMessageIsQuotedExactly() {
            assertThat(MSG_NOTHING_TO_PAY)
                    .as("app/cbl/COBIL00C.cbl:201, with the cursor sent back to ACTIDINL at :203")
                    .isEqualTo("You have nothing to pay...")
                    .endsWith("...");
            assertThat(MSG_NOTHING_TO_PAY.length()).isLessThanOrEqualTo(COBIL00.widthOf("ERRMSGI"));
        }

        @ParameterizedTest(name = "identifier [{0}] supplied: {1}")
        @CsvSource({
            "00000000001, true",
            "1,           true",
            "'           ', false",
            "'', false"
        })
        @DisplayName("expands the abbreviated combined relation, so a blank identifier is not a value")
        void theAbbreviatedCombinedRelationExpands(final String accountId, final boolean isSupplied) {
            assertThat(supplied(accountId))
                    .as("ACTIDINI NOT = SPACES AND LOW-VALUES at app/cbl/COBIL00C.cbl:199 abbreviates "
                            + "ACTIDINI NOT = SPACES AND ACTIDINI NOT = LOW-VALUES; it does not compare "
                            + "the field against a two-value set")
                    .isEqualTo(isSupplied);
        }

        @Test
        @DisplayName("keeps the guard silent until an identifier arrives, so first entry shows no error")
        void theGuardCannotFireBeforeAnIdentifierArrives() {
            final BigDecimal zero = new BigDecimal("0.00");

            assertThat(nothingToPay(zero, null))
                    .as("on the very first display no account has been read, so ACCT-CURR-BAL is zero "
                            + "and the identifier is still low valued; both halves of the condition must "
                            + "hold, so no message is produced")
                    .isFalse();
            assertThat(nothingToPay(zero, "           ")).isFalse();
            assertThat(nothingToPay(zero, "\u0000".repeat(11))).isFalse();
            assertThat(nothingToPay(zero, "00000000001"))
                    .as("once an identifier is supplied the same zero balance does produce the message")
                    .isTrue();
        }

        @Test
        @DisplayName("prompts for confirmation, byte for byte, when the payment is not confirmed")
        void theUnconfirmedPathPrompts() {
            assertThat(confirmationOf(" ")).isEqualTo(Confirmation.BLANK);
            assertThat(MSG_CONFIRMATION_REQUIRED)
                    .as("the ELSE of the CONF-PAY-YES test at app/cbl/COBIL00C.cbl:236-239 moves this "
                            + "literal and repositions the cursor on CONFIRML; it is a prompt and not an "
                            + "error, so it is never conflated with the invalid-value message")
                    .isEqualTo("Confirm to make a bill payment...")
                    .isNotEqualTo(MSG_CONFIRMATION_INVALID)
                    .endsWith("...");
            assertThat(MSG_CONFIRMATION_REQUIRED.length())
                    .isLessThanOrEqualTo(COBIL00.widthOf("ERRMSGI"));
        }

        @Test
        @DisplayName("carries the confirmation in the request, never as state held between requests")
        void theHandshakeIsARequestField() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("the two-phase handshake of app/cbl/COBIL00C.cbl:173 becomes a request field, so "
                            + "the boundary stays stateless")
                    .contains("confirmation");
            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, "confirmation"))
                    .isEqualTo(COBIL00.widthOf("CONFIRMI"));
        }
    }

    @Nested
    @DisplayName("6. The transaction the confirmed payment derives, literal for literal")
    final class DerivedTransaction {

        private Posting confirmedPayment() {
            return pay(new BigDecimal("194.00"), "4111111111111111", "0000000000000042",
                    FixedClockProvider.canonicalClock());
        }

        @Test
        @DisplayName("moves the type code as text and the category code as four numeric digits")
        void theTypeAndCategoryCodesAreMovedAsTheSourceMovesThem() {
            final PostedTransaction transaction = confirmedPayment().transaction();

            assertThat(transaction.typeCode())
                    .as("MOVE '02' TO TRAN-TYPE-CD PIC X(02) at app/cbl/COBIL00C.cbl:220")
                    .isEqualTo("02");
            assertThat(transaction.categoryCode())
                    .as("MOVE 2 TO TRAN-CAT-CD at :221 is a NUMERIC literal into PIC 9(04) at "
                            + "app/cpy/CVTRA05Y.cpy:7, so the stored value is four digits - not the "
                            + "string '0002' written by hand, and not an unpadded 2")
                    .isEqualTo("0002")
                    .hasSize(RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-CAT-CD"));
        }

        @Test
        @DisplayName("pads the source and the description into their declared widths")
        void theSourceAndDescriptionArePadded() {
            final PostedTransaction transaction = confirmedPayment().transaction();

            assertThat(transaction.source())
                    .as("MOVE 'POS TERM' TO TRAN-SOURCE PIC X(10) at app/cbl/COBIL00C.cbl:222 leaves two "
                            + "trailing spaces, which is exactly what every POS TERM row of "
                            + "app/data/ASCII/dailytran.txt carries")
                    .isEqualTo("POS TERM  ")
                    .hasSize(10);
            assertThat(transaction.description())
                    .as("MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC PIC X(100) at :223")
                    .startsWith("BILL PAYMENT - ONLINE")
                    .hasSize(100)
                    .isEqualTo(TRANSACTION_DESCRIPTION + " ".repeat(100 - TRANSACTION_DESCRIPTION.length()));
        }

        @Test
        @DisplayName("keeps the source free text, so no closed enumeration may type it")
        void theSourceIsFreeText() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final RecordLayoutCopybook layout = RecordLayoutCopybook.of("CVTRA05Y");
            final int sourceColumn = 23;
            int unsourced = 0;
            for (int record = 0; record < daily.recordCount(); record++) {
                if (daily.field(record, sourceColumn, layout.widthOf("TRAN-SOURCE"))
                        .startsWith(FIXTURE_UNSOURCED_VALUE)) {
                    unsourced++;
                }
            }

            assertThat(layout.geometry("TRAN-SOURCE").numeric())
                    .as("TRAN-SOURCE PIC X(10) is alphanumeric")
                    .isFalse();
            assertThat(unsourced)
                    .as("the fixture is the evidence: fifty of the three hundred rows of "
                            + "app/data/ASCII/dailytran.txt carry OPERATOR, a value that appears in NO "
                            + "program literal anywhere in the corpus - the only two are 'POS TERM' at "
                            + "app/cbl/COBIL00C.cbl:222 and 'System' at app/cbl/CBACT04C.cbl:484 - so the "
                            + "domain is open and an enum-typed source would reject real data")
                    .isEqualTo(50);
            assertThat(confirmedPayment().transaction().source())
                    .as("the value on the wire contains a space, so it could not even be spelled as an "
                            + "enum constant name; the source stays a String carrying padded text")
                    .contains(" ")
                    .isNotEqualTo(TRANSACTION_SOURCE);
        }

        @Test
        @DisplayName("moves the merchant literals byte for byte, including nine nines")
        void theMerchantLiteralsAreExact() {
            final PostedTransaction transaction = confirmedPayment().transaction();

            assertThat(transaction.merchantId())
                    .as("MOVE 999999999 TO TRAN-MERCHANT-ID PIC 9(09) at app/cbl/COBIL00C.cbl:226 fills "
                            + "the field exactly - nine nines, with no room to spare")
                    .isEqualTo("999999999")
                    .hasSize(9)
                    .isEqualTo(String.valueOf(MERCHANT_ID));
            assertThat(transaction.merchantName())
                    .as("MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME PIC X(50) at :227")
                    .startsWith(MERCHANT_NAME)
                    .hasSize(50);
            assertThat(transaction.merchantCity())
                    .as("MOVE 'N/A' TO TRAN-MERCHANT-CITY PIC X(50) at :228")
                    .startsWith(NOT_APPLICABLE)
                    .hasSize(50);
            assertThat(transaction.merchantZip())
                    .as("MOVE 'N/A' TO TRAN-MERCHANT-ZIP PIC X(10) at :229")
                    .isEqualTo("N/A       ")
                    .hasSize(10);
        }

        @Test
        @DisplayName("differs from the fixture's merchant identifier, which no online payment writes")
        void theMerchantIdentifierIsNotTheFixtureValue() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final int merchantIdColumn = 144;
            final int merchantIdWidth = RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-MERCHANT-ID");

            for (int record = 0; record < daily.recordCount(); record++) {
                assertThat(daily.field(record, merchantIdColumn, merchantIdWidth))
                        .as("staged row %d of app/data/ASCII/dailytran.txt", record)
                        .isEqualTo(FIXTURE_MERCHANT_ID);
            }
            assertThat(confirmedPayment().transaction().merchantId())
                    .as("every one of the three hundred staged rows carries 800000000, so a test that "
                            + "asserted the fixture value here would pass while the online payment wrote "
                            + "the nine nines of app/cbl/COBIL00C.cbl:226 instead")
                    .isNotEqualTo(FIXTURE_MERCHANT_ID);
        }

        @Test
        @DisplayName("sets both timestamps to the same generated value, not merely to a populated one")
        void bothTimestampsAreTheSameValue() {
            final PostedTransaction transaction = confirmedPayment().transaction();

            assertThat(transaction.originatingTimestamp())
                    .as("MOVE WS-TIMESTAMP TO TRAN-ORIG-TS TRAN-PROC-TS at app/cbl/COBIL00C.cbl:231-232 "
                            + "is one MOVE with two receiving fields, so the two are identical by "
                            + "construction and asserting only that both are populated would miss a "
                            + "second call to the clock")
                    .isEqualTo(transaction.processingTimestamp());
            assertThat(transaction.originatingTimestamp())
                    .hasSize(RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-ORIG-TS"))
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("numbers the first transaction one, because the empty-file branch moves zeros")
        void theFirstIdentifierIsOne() {
            assertThat(nextTransactionId(null))
                    .as("WHEN DFHRESP(ENDFILE) MOVE ZEROS TO TRAN-ID inside READPREV-TRANSACT-FILE, "
                            + "app/cbl/COBIL00C.cbl:472-496, then ADD 1 at :217")
                    .isEqualTo("0000000000000001");
            assertThat(nextTransactionId("0000000000000042")).isEqualTo("0000000000000043");
            assertThat(nextTransactionId("0000000000000999")).isEqualTo("0000000000001000");
        }

        @Test
        @DisplayName("keeps the identifier a sixteen-character zero-padded string, so leading zeros live")
        void theIdentifierKeepsItsLeadingZeros() {
            final String identifier = nextTransactionId("0000000000000042");

            assertThat(identifier)
                    .as("TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:5 - a numeric type would drop the "
                            + "leading zeros and the key would no longer collate as the browse expects")
                    .hasSize(TRANSACTION_ID_WIDTH)
                    .hasSize(RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-ID"))
                    .startsWith("0")
                    .containsOnlyDigits();
        }

        @Test
        @DisplayName("generates the identifier by browsing, a race retained deliberately for parity")
        void theIdentifierRaceIsRetained() {
            final String first = nextTransactionId("0000000000000042");
            final String second = nextTransactionId("0000000000000042");

            assertThat(second)
                    .as("two callers reading the same maximum compute the same successor: MOVE "
                            + "HIGH-VALUES, STARTBR, READPREV, ENDBR, ADD 1 at app/cbl/COBIL00C.cbl:"
                            + "212-217 is racy exactly as written. It is kept, because a database "
                            + "sequence would generate different values and break the parity baseline; "
                            + "the collision is meant to surface as a duplicate-key failure from the "
                            + "primary key. Recorded as a preserved quirk in DECISION_LOG.md")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("copies the card number from the cross reference and never from this payload")
        void theCardNumberComesFromTheCrossReference() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("MOVE XREF-CARD-NUM TO TRAN-CARD-NUM at app/cbl/COBIL00C.cbl:225 reads the card "
                            + "number from the cross-reference record, so the screen never submits one "
                            + "and this payload must not carry one")
                    .doesNotContain("cardNumber", "cardNum", "pan");
            assertThat(confirmedPayment().transaction().cardNumber())
                    .hasSize(RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-CARD-NUM"));
        }

        @Test
        @DisplayName("writes an amount inside the transaction tier, one digit narrower than the balance")
        void theAmountFitsTheTransactionTier() {
            final RecordLayoutCopybook.Geometry amount =
                    RecordLayoutCopybook.of("CVTRA05Y").geometry("TRAN-AMT");
            final BigDecimal written = confirmedPayment().transaction().amount();

            assertThat(written.scale()).as("the amount is held at the V99 scale").isEqualTo(MONEY_SCALE);
            assertThat(written.precision())
                    .as("well inside TRAN-AMT PIC S9(09)V99, which holds %d digit positions",
                            amount.width())
                    .isLessThanOrEqualTo(amount.width());
            assertThat(amount.width())
                    .as("a balance filling ACCT-CURR-BAL PIC S9(10)V99 could not be written whole, which "
                            + "is a source-level narrowing this migration preserves rather than widens")
                    .isLessThan(RecordLayoutCopybook.of("CVACT01Y").geometry("ACCT-CURR-BAL").width());
        }
    }

    @Nested
    @DisplayName("7. The online timestamp, whose eleventh byte is a space - and the batch one it is not")
    final class OnlineTimestamp {

        @Test
        @DisplayName("renders twenty-six characters with a space at position eleven")
        void positionElevenIsASpace() {
            final String timestamp = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());

            assertThat(timestamp)
                    .as("GET-CURRENT-TIMESTAMP at app/cbl/COBIL00C.cbl:249-267 moves the date into "
                            + "WS-TIMESTAMP(01:10) and the time into (12:08), writing nothing into byte "
                            + "eleven - and app/cpy/CSDAT01Y.cpy:48 declares that byte as FILLER PIC X(01) "
                            + "VALUE ' ', so it is a space by declaration as well as by omission")
                    .hasSize(26)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(timestamp.charAt(10))
                    .as("position eleven, one-based - a space, never the dash the batch format uses")
                    .isEqualTo(' ')
                    .isNotEqualTo('-');
            assertThat(timestamp.charAt(19))
                    .as("app/cpy/CSDAT01Y.cpy:54 declares position twenty as FILLER PIC X(01) VALUE '.'")
                    .isEqualTo('.');
            assertThat(timestamp.substring(20))
                    .as("MOVE ZEROS TO WS-TIMESTAMP-TM-MS6 at app/cbl/COBIL00C.cbl:266 fills the six "
                            + "microsecond digits with literal zeros, so no sub-second precision is ever "
                            + "emitted and a nanosecond rendering would differ from the baseline")
                    .isEqualTo("000000");
        }

        @Test
        @DisplayName("agrees byte for byte with the value all three hundred fixture rows carry")
        void theFixtureCorroboratesTheFormat() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final int width = RecordLayoutCopybook.of("CVTRA05Y").widthOf("TRAN-ORIG-TS");

            for (int record = 0; record < daily.recordCount(); record++) {
                assertThat(daily.field(record, ORIGINATING_TIMESTAMP_COLUMN, width))
                        .as("columns 279-304 of staged row %d", record)
                        .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            }
            assertThat(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .as("one single distinct value across the whole fixture, which is what makes it a "
                            + "usable independent proof of the rendering rather than a sample of it")
                    .isEqualTo("2022-06-10 19:27:53.000000");
        }

        @Test
        @DisplayName("takes its instant from the injected clock, so the assertion cannot drift")
        void theInstantComesFromTheInjectedClock() {
            final Clock clock = FixedClockProvider.fixedClock(Instant.parse("2022-06-10T19:27:53Z"));

            assertThat(FixedClockProvider.onlineTimestamp(clock))
                    .as("EXEC CICS ASKTIME at app/cbl/COBIL00C.cbl:251 is the only clock read in the "
                            + "paragraph, and it is replaced by an injected fixed clock here; a call to "
                            + "now() would make this assertion pass today and fail tomorrow")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(FixedClockProvider.onlineTimestamp(clock))
                    .as("the same clock renders the same value, so the payload's timestamps are "
                            + "reproducible across runs and hosts")
                    .isEqualTo(FixedClockProvider.onlineTimestamp(clock));
            assertThat(FixedClockProvider.CANONICAL_ZONE.getId())
                    .as("the zone is pinned, so a host time zone cannot shift the rendering")
                    .isEqualTo("Z");
        }

        @Test
        @DisplayName("is not the batch rendering, which carries three dashes and hundredths")
        void theBatchRenderingIsADifferentFormat() {
            final Clock clock = FixedClockProvider.canonicalClock();
            final String online = FixedClockProvider.onlineTimestamp(clock);
            final String batch = FixedClockProvider.batchTimestamp(clock);

            assertThat(batch)
                    .as("Z-GET-DB2-FORMAT-TIMESTAMP at app/cbl/CBACT04C.cbl:613-625 moves '-' into "
                            + "DB2-STREEP-1, DB2-STREEP-2 and DB2-STREEP-3 over the redefinition at "
                            + "app/cbl/CBTRN02C.cbl:149-175, so a DASH separates the day from the hour "
                            + "where the online format has a space")
                    .isEqualTo("2022-06-10-19.27.53.000000")
                    .hasSize(online.length());
            assertThat(batch.charAt(10)).isEqualTo('-');
            assertThat(batch.chars().filter(character -> character == '-').count())
                    .as("three dashes in the batch rendering against two in the online one")
                    .isEqualTo(3);
            assertThat(online.chars().filter(character -> character == '-').count()).isEqualTo(2);
            assertThat(online)
                    .as("the two formats are the same length and the same instant, and are still not "
                            + "interchangeable; this screen writes the online one")
                    .isNotEqualTo(batch);
            assertThat(batch.substring(22))
                    .as("DB2-MIL PIC 9(002) is hundredths and MOVE '0000' TO DB2-REST at "
                            + "app/cbl/CBACT04C.cbl:622 appends four literal zeros - millisecond "
                            + "precision at most, never nanoseconds")
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("stores every timestamp as text of the declared width, never as a temporal type")
        void timestampsAreTextNotTemporal() {
            final RecordLayoutCopybook layout = RecordLayoutCopybook.of("CVTRA05Y");

            assertThat(layout.geometry("TRAN-ORIG-TS").numeric())
                    .as("TRAN-ORIG-TS PIC X(26) at app/cpy/CVTRA05Y.cpy:16 is alphanumeric, and so is "
                            + "TRAN-PROC-TS at :17; a LocalDateTime or Instant column could not hold the "
                            + "all-spaces value that every staged row carries in columns 305-330")
                    .isFalse();
            assertThat(layout.widthOf("TRAN-ORIG-TS")).isEqualTo(26);
            assertThat(layout.widthOf("TRAN-PROC-TS")).isEqualTo(26);
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(BillPaymentRequest.class))
                    .as("nor does any temporal type reach this payload: CURDATEI and CURTIMEI are screen "
                            + "text at app/cpy-bms/COBIL00.CPY:36 and :54")
                    .allSatisfy(typeName -> assertThat(typeName).doesNotContain("java.time"));
        }

        @Test
        @DisplayName("leaves an unposted processing timestamp blank rather than defaulting it")
        void anUnpostedProcessingTimestampStaysBlank() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final int processingColumn = 305;

            assertThat(daily.field(0, processingColumn, 26))
                    .as("the staging fixture has not been posted yet, so TRAN-PROC-TS is twenty-six "
                            + "spaces - which is why the field is text and why a blank is a state rather "
                            + "than a missing value")
                    .isBlank()
                    .hasSize(26);
            assertThat(daily.field(0, ORIGINATING_TIMESTAMP_COLUMN, 26))
                    .as("while the originating timestamp on the same row is populated, so the two are "
                            + "genuinely independent on a staged record")
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("8. Boundaries, the three distinct states, and input treated as hostile")
    final class BoundariesAndTriState {

        @Test
        @DisplayName("keeps absent, empty and blank as three distinct states on every component")
        void absentEmptyAndBlankStayDistinct() {
            final BillPaymentRequest absent = with("accountId", null);
            final BillPaymentRequest empty = with("accountId", "");
            final BillPaymentRequest blank = with("accountId", "           ");

            assertThat(absent.accountId()).isNull();
            assertThat(empty.accountId()).isEmpty();
            assertThat(blank.accountId()).isNotEmpty().isBlank();
            assertThat(List.of(supplied(""), supplied("           "), supplied("00000000001")))
                    .as("app/cpy/CSSETATY.cpy models exactly OK, NOT-OK and BLANK, and "
                            + "app/cbl/COACTUPC.cbl:505-508 gives blank and invalid separate messages - "
                            + "'Credit Limit must be supplied' against 'Credit Limit is not valid' - so "
                            + "collapsing the states would produce the wrong message")
                    .containsExactly(false, false, true);
            assertThat(violationsOf(absent)).isEmpty();
            assertThat(violationsOf(empty)).isEmpty();
            assertThat(violationsOf(blank)).isEmpty();
        }

        @Test
        @DisplayName("declares no class-level constraint, because a gated cross-field edit is not one")
        void noClassLevelConstraintIsDeclared() {
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(BillPaymentRequest.class))
                    .as("app/cbl/COACTUPC.cbl:1665-1668 runs the cross-field edit only once both "
                            + "single-field edits have passed, with the outcome set at :1674; a "
                            + "class-level AssertTrue fires unconditionally and would answer with a "
                            + "different message set")
                    .doesNotContain(AssertTrue.class.getName());
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .allSatisfy(component -> assertThat(RecordFieldContract.declares(
                            BillPaymentRequest.class, component, AssertTrue.class))
                            .as("%s must not carry a cross-field assertion either", component)
                            .isFalse());
        }

        @Test
        @DisplayName("makes no component mandatory and imposes no format on any of them")
        void nothingStricterIsInvented() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .allSatisfy(component -> {
                        assertThat(RecordFieldContract.declares(BillPaymentRequest.class, component,
                                NotNull.class))
                                .as("%s must tolerate absence: a 3270 field the terminal did not "
                                        + "transmit arrives low valued, which the source accepts",
                                        component)
                                .isFalse();
                        assertThat(RecordFieldContract.declares(BillPaymentRequest.class, component,
                                Pattern.class))
                                .as("%s must impose no format: the program does the editing, and a "
                                        + "boundary regex would answer with a different message",
                                        component)
                                .isFalse();
                    });
        }

        @ParameterizedTest(name = "{0} accepts {1} and refuses {2}")
        @CsvSource({
            "transactionName, 4,  5",
            "title01,         40, 41",
            "currentDate,     8,  9",
            "programName,     8,  9",
            "title02,         40, 41",
            "currentTime,     8,  9",
            "accountId,       11, 12",
            "currentBalance,  14, 15",
            "confirmation,    1,  2",
            "errorMessage,    78, 79"
        })
        @DisplayName("accepts a component at its declared width and refuses one character more")
        void everyComponentHasItsOwnBoundary(final String component, final int accepted,
                final int refused) {
            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, component))
                    .isEqualTo(accepted);
            assertThat(violationsOf(with(component, "X".repeat(accepted))))
                    .as("%s holds %d characters", component, accepted)
                    .isEmpty();
            assertThat(violationsOf(with(component, "X".repeat(refused))))
                    .as("%s cannot hold %d", component, refused)
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath()).hasToString(component));
        }

        @Test
        @DisplayName("preserves trailing padding rather than trimming it at the boundary")
        void trailingPaddingIsPreserved() {
            final BillPaymentRequest padded = with("accountId", "1          ");

            assertThat(padded.accountId())
                    .as("a 3270 field arrives space filled to its declared width, and trimming here "
                            + "would destroy the fixed-width geometry the parity comparison depends on")
                    .isEqualTo("1          ")
                    .hasSize(COBIL00.widthOf("ACTIDINI"));
            assertThat(violationsOf(padded)).isEmpty();
        }

        @ParameterizedTest(name = "hostile balance [{0}]")
        @ValueSource(strings = {
            "0000001940.00",
            "+1,000,000.00",
            "+0000001940.0",
            "1940",
            "not a balance"
        })
        @DisplayName("carries a malformed balance echo without rejecting it, because it is untrusted")
        void aMalformedBalanceEchoIsCarriedNotTrusted(final String balance) {
            assertThat(violationsOf(with("currentBalance", balance)))
                    .as("an unsigned value, a grouped value and a short fraction are all things a caller "
                            + "can send. None is rejected here, because the service re-reads the "
                            + "authoritative balance from the account record at app/cbl/COBIL00C.cbl:193 "
                            + "and never parses this echo - a boundary format rule would answer with a "
                            + "message the screen never produced")
                    .isEmpty();
            assertThat(balance.length()).isLessThanOrEqualTo(COBIL00.widthOf("CURBALI"));
        }

        @ParameterizedTest(name = "hostile identifier [{0}]")
        @ValueSource(strings = {"ABCDEFGHIJK", "-0000000001", "00000000 01", "          1"})
        @DisplayName("carries a non-numeric account identifier, which the program edits rather than typed")
        void aNonNumericIdentifierIsCarried(final String accountId) {
            assertThat(accountId.length()).isEqualTo(COBIL00.widthOf("ACTIDINI"));
            assertThat(violationsOf(with("accountId", accountId)))
                    .as("ACTIDINI is PIC X(11) at app/cpy-bms/COBIL00.CPY:60 - alphanumeric, so the "
                            + "screen can send anything eleven characters wide and the program decides")
                    .isEmpty();
        }

        @Test
        @DisplayName("reads the account fixture by resource name, and its geometry is exactly as frozen")
        void theAccountFixtureGeometryHolds() {
            final FixtureLoader.FixtureData accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            assertThat(accounts.resourceName())
                    .as("loaded as a classpath resource, never as a filesystem path into app/, which "
                            + "stays frozen and unwritten")
                    .isEqualTo("acctdata.txt")
                    .doesNotContain("/");
            assertThat(accounts.byteCount()).isEqualTo(15_050);
            assertThat(accounts.recordCount()).isEqualTo(50);
            assertThat(accounts.recordWidth())
                    .as("RECLN 300 at app/cpy/CVACT01Y.cpy:2, so 15050 == 50 x 301 with one terminator "
                            + "each; any whitespace cleanup would break this arithmetic immediately")
                    .isEqualTo(300);
            assertThat(accounts.impliedByteCount()).isEqualTo(accounts.byteCount());
            assertThat(accounts.recordAt(0))
                    .as("the record still ends in its 188-character run of padding: ten spaces of "
                            + "ACCT-GROUP-ID followed by the 178-character FILLER at "
                            + "app/cpy/CVACT01Y.cpy:17")
                    .endsWith(" ".repeat(188))
                    .hasSize(300);
        }

        @Test
        @DisplayName("finds an alphabetic postal code, so no digits-only rule may be invented anywhere")
        void thePostalCodeIsNotNumeric() {
            final FixtureLoader.FixtureData accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            for (int record = 0; record < accounts.recordCount(); record++) {
                assertThat(accounts.field(record, ACCOUNT_ZIP_COLUMN, 10))
                        .as("ACCT-ADDR-ZIP on row %d", record)
                        .isEqualTo(ACCOUNT_ZIP_VALUE);
            }
            assertThat(ACCOUNT_ZIP_VALUE)
                    .as("all fifty rows carry A000000000, an alphabetic leading character, so a "
                            + "digits-only constraint would reject the corpus's own seed data")
                    .startsWith("A")
                    .hasSize(10);
        }

        @Test
        @DisplayName("decodes an overpunched balance position-aware, and refuses to decode text")
        void overpunchDecodingIsPositionAware() {
            final FixtureLoader.FixtureData accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            final int width = FixtureLoader.MONEY_FIELD_WIDTH;

            assertThat(accounts.field(0, ACCOUNT_BALANCE_COLUMN, width))
                    .as("ACCT-CURR-BAL is twelve characters of zoned decimal with the sign overpunched "
                            + "onto the last digit")
                    .isEqualTo("00000001940{");
            assertThat(accounts.signedDecimal(0, ACCOUNT_BALANCE_COLUMN, width))
                    .as("the brace decodes to +0, so the field is +194.00 - decoded from the PIC clause's "
                            + "own offset and width, never by substituting characters across a record")
                    .isEqualByComparingTo("194.00");
            assertThat(FixtureLoader.decodeZonedDecimal("0000009190}", MONEY_SCALE))
                    .as("the closing brace decodes to -0, so a genuinely negative amount survives; "
                            + "app/data/ASCII/dailytran.txt carries both sign families")
                    .isEqualByComparingTo("-919.00");
            assertThatThrownBy(() -> FixtureLoader.decodeZonedDecimal(ACCOUNT_ZIP_VALUE, MONEY_SCALE))
                    .as("a global substitution would have read A000000000 as a number, and the same "
                            + "letters occur inside embossed names, descriptions, merchant names and "
                            + "cities; decoding a text field must fail loudly instead")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digit");
        }
    }

    @Nested
    @DisplayName("9. The rendering discloses nothing, and no session state is carried")
    final class RenderingAndStatelessness {

        @Test
        @DisplayName("carries no card number and no customer-identifying component at all")
        void itCarriesNothingSensitive() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("the payment derives TRAN-CARD-NUM from the cross reference at "
                            + "app/cbl/COBIL00C.cbl:225, so no card number is ever submitted here; nor is "
                            + "a password, a hash, a social security number, a telephone number or a date "
                            + "of birth")
                    .doesNotContain("cardNumber", "cardholderName", "embossedName", "customerFirstName",
                            "customerMiddleName", "customerLastName", "customerSsn", "ssn", "phoneNumber1",
                            "phoneNumber2", "dateOfBirth", "governmentIssuedId", "eftAccountId",
                            "password", "passwordHash");
        }

        @Test
        @DisplayName("names exactly the ten screen components and invents none")
        void itsComponentsAreExactlyTheMapsFields() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime", "accountId", "currentBalance", "confirmation", "errorMessage");
        }

        @Test
        @DisplayName("overrides the generated rendering, which would have emitted every component")
        void toStringIsDeclaredOnTheType() {
            assertThat(ReflectionCensus.declaredMethodNames(BillPaymentRequest.class))
                    .as("a record's inherited rendering emits all ten components verbatim, including the "
                            + "account identifier and the balance")
                    .contains("toString");
        }

        @Test
        @DisplayName("withholds the account identifier, the balance and the presentation header")
        void renderingWithholdsTheIdentifierAndBalance() {
            final String rendered = withConfirmation("Y").toString();

            assertThat(rendered)
                    .as("each modelled member is named and then described by shape, so the labels are "
                            + "present and no submitted byte is")
                    .startsWith("BillPaymentRequest[")
                    .endsWith("]")
                    .contains("accountId=", "currentBalance=", "errorMessage=")
                    .doesNotContain("00000000001", "+0000001940.00", "1940");
            assertThat(rendered)
                    .as("the transaction name and the program name are caller supplied too, so the whole "
                            + "presentation header is withheld rather than echoed; a line break in any of "
                            + "them would otherwise forge a log entry")
                    .doesNotContain("transactionName=", "programName=", "CB00", "COBIL00C")
                    .contains("header=<6 presentation members omitted>");
        }

        @Test
        @DisplayName("keeps the rendering null safe, and absent still distinct from empty")
        void theRenderingIsNullSafe() {
            final BillPaymentRequest unpopulated = new BillPaymentRequest(
                    null, null, null, null, null, null, null, null, null, null);

            assertThat(unpopulated.toString())
                    .as("a payload bound from a partly filled screen carries nulls, and a "
                            + "NullPointerException raised while building a log line would lose the very "
                            + "event being reported")
                    .isEqualTo("BillPaymentRequest[accountId=absent, currentBalance=absent, "
                            + "confirmation=absent, errorMessage=absent, "
                            + "header=<6 presentation members omitted>]");
            assertThat(withConfirmation("Y").toString())
                    .as("a transmitted blank reports as empty rather than absent, so the two screen "
                            + "states stay distinguishable in a log")
                    .contains("errorMessage=empty")
                    .doesNotContain("errorMessage=absent");
        }

        @Test
        @DisplayName("describes an over-width confirmation by its width rather than by its content")
        void anOverWidthConfirmationIsDescribedByItsWidth() {
            assertThat(withConfirmation("YY").toString())
                    .as("a single character renders as a code point, which is injective and fully "
                            + "diagnostic; anything wider is already invalid against "
                            + "app/cpy-bms/COBIL00.CPY:72 and must be reported without being leaked")
                    .contains("confirmation=2 chars")
                    .doesNotContain("YY");
        }

        @Test
        @DisplayName("carries no navigation or session field, because routing is by URL")
        void noSessionStateIsCarried() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("app/cpy/COCOM01Y.cpy:21-24 declares CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM, "
                            + "CDEMO-TO-TRANID and CDEMO-TO-PROGRAM, :29 declares CDEMO-PGM-CONTEXT and "
                            + ":43-44 declares CDEMO-LAST-MAP and CDEMO-LAST-MAPSET as PIC X(7) - not "
                            + "X(8). None has a counterpart here: the boundary holds no server-side "
                            + "session state, and the confirmation handshake is a request field")
                    .doesNotContain("fromTranId", "toTranId", "fromProgram", "toProgram", "pgmContext",
                            "programContext", "lastMap", "lastMapset", "sessionId", "commArea");
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .as("PGMNAMEI at app/cpy-bms/COBIL00.CPY:42 is a screen header field carrying the "
                            + "owning program name for display, which is not navigation state")
                    .contains("programName");
        }

        @Test
        @DisplayName("keeps every value readable through its accessor, so nothing was lost to redaction")
        void everyValueRemainsReadable() {
            final BillPaymentRequest request = withConfirmation("Y");

            assertThat(request.accountId()).isEqualTo("00000000001");
            assertThat(request.currentBalance()).isEqualTo("+0000001940.00");
            assertThat(request.confirmation()).isEqualTo("Y");
            assertThat(request.errorMessage()).isEmpty();
            assertThat(request.transactionName()).isEqualTo("CB00");
            assertThat(request.programName()).isEqualTo("COBIL00C");
        }
    }
}
