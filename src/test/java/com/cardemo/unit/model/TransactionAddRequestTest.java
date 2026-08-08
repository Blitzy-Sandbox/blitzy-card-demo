/*
 ******************************************************************
 * Program     : TransactionAddRequestTest.java
 * Application : CardDemo
 * Type        : Java 25 / JUnit 5 unit test (pure JVM tier)
 * Function    : Proves the transaction-add field contract, the two
 *               deliberately different numeric parsers, the edited
 *               mask that truncates, the text pass-through fields
 *               and the redacted diagnostic rendering.
 * Source      : app/cpy-bms/COTRN02.CPY (21 input fields, group
 *               COTRN2AI) + app/cbl/COTRN02C.cbl @ 7756d89
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.dto.TransactionAddRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit contract for {@link TransactionAddRequest}, the inbound payload of CICS transaction {@code CT02}.
 *
 * <h2>What it does</h2>
 *
 * <p>Proves that the record reproduces the transaction-add screen contract exactly, and - just as
 * importantly - that it reproduces none of the behaviour the legacy program leaves to a later tier. Each
 * assertion cites the frozen artefact it is drawn from; nothing here is asserted from prose. The
 * copybook widths are read out of {@code app/cpy-bms/COTRN02.CPY} at run time by the
 * {@code BmsSymbolicMap} oracle rather than restated as literals, so a width can only pass by agreeing
 * with the frozen map.
 *
 * <p>Locators, all verified at commit {@code 7756d89}:
 *
 * <ul>
 * <li>{@code app/cpy-bms/COTRN02.CPY} - the 21 input fields of group {@code COTRN2AI}, at lines 24, 30,
 *     36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120, 126, 132, 138 and 144, preceded by
 *     the twelve-byte terminal I/O area at line 18.</li>
 * <li>{@code app/cpy-bms/COSGN00.CPY:54} - {@code CURTIMEI PIC X(9)}, the sole width outlier that makes a
 *     shared header abstraction factually wrong.</li>
 * <li>{@code app/cbl/COTRN02C.cbl:L56-L60} - the working-storage numeric and edited fields.</li>
 * <li>{@code app/cbl/COTRN02C.cbl:L204-L206} and {@code :L218-L220} - plain {@code FUNCTION NUMVAL}, each
 *     gated by an {@code IS NOT NUMERIC} test at {@code :197} and {@code :211}.</li>
 * <li>{@code app/cbl/COTRN02C.cbl:L383-L386} - {@code FUNCTION NUMVAL-C} then the masked echo.</li>
 * <li>{@code app/cbl/COTRN02C.cbl:L444-L451} - the descending-browse identifier idiom.</li>
 * <li>{@code app/cbl/COTRN02C.cbl:L454-L457} and {@code :L464-L465} - the pass-through moves.</li>
 * <li>{@code app/cbl/COBIL00C.cbl:L55-L56} - an eight-digit amount mask beside a ten-digit balance mask.</li>
 * <li>{@code app/cbl/COBIL00C.cbl:L249-L267} - the online timestamp, whose eleventh position is a space.</li>
 * <li>{@code app/cbl/COBIL00C.cbl:L472-L496} - the end-of-file path that makes the first identifier 1.</li>
 * <li>{@code app/cbl/CBACT04C.cbl:L613-L625} - the batch timestamp, three dashes and four literal zeros.</li>
 * <li>{@code app/cbl/CBACT04C.cbl:L473-L515} - the interest identifier, a date parameter and a suffix.</li>
 * <li>{@code app/cbl/CBTRN02C.cbl:L149-L175} - the redefinition proving the timestamp is 26 characters.</li>
 * <li>{@code app/cpy/CVTRA05Y.cpy:L8-L10} - {@code TRAN-SOURCE X(10)} and {@code TRAN-AMT S9(09)V99}.</li>
 * <li>{@code app/cpy/COCOM01Y.cpy:L43-L44} - the COMMAREA fields that must have no counterpart here.</li>
 * <li>{@code app/cpy/CSSETATY.cpy} - the OK / NOT-OK / BLANK three-state error model.</li>
 * <li>{@code app/data/ASCII/dailytran.txt} - 300 records of 350 characters, the parity fixture.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build and run the whole unit tier with {@code ./mvnw -B -ntp clean test}, or this class alone with
 * {@code ./mvnw -B -ntp test -Dtest=TransactionAddRequestTest}. The class sits under
 * {@code src/test/java/com/cardemo/unit}, which is the tree <strong>Surefire</strong> collects: the plugin
 * includes {@code **}{@code /*Test.java} and excludes only the {@code integration} and {@code e2e} trees, so
 * moving or renaming this class would hand it to neither Surefire nor Failsafe and it would silently stop
 * running while the build still reported success.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Pure JVM tier: no container, no Spring context, no database and no network. Time comes from an
 * injected fixed clock, {@code FixedClockProvider.canonicalClock()}, pinned to
 * {@code 2022-06-10T19:27:53Z} in UTC - the moment the fixture itself carries - so no assertion reads an
 * ambient clock, an ambient zone or an ambient locale. Every parse and format passes {@link Locale#ROOT}.
 * No test double is required: the record has no collaborator, so no Mockito mock, stub or strictness
 * setting appears and none is needed. Fixture data is read through {@code FixtureLoader} by classpath
 * resource name only; the frozen tree is never written and never copied.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 * <li>The compiler runs with {@code -Xlint:all -Werror} and {@code failOnWarning}, so a raw type or a
 *     deprecation in this file fails the build outright rather than warning.</li>
 * <li>The fixture is {@code dailytran.txt}. {@code dalytran.txt} does not exist: the mainframe DD name is
 *     {@code DALYTRAN} but the ASCII fixture spells "daily" in full, and the misspelling produces a
 *     resource-not-found failure rather than a wrong assertion.</li>
 * <li>Using one numeric parser for both identifiers and amounts is the defect this class exists to catch.
 *     The strict parser applied to an amount rejects a currency-decorated value the source accepts; the
 *     tolerant parser applied to an identifier accepts a value {@code :197} rejects.</li>
 * <li>Formatting a timestamp to nanosecond - or even millisecond - precision diverges from the source. The
 *     fractional field is hundredths followed by four literal zeros.</li>
 * <li>"Fixing" the eight-versus-nine digit mask truncation breaks parity. The truncation is asserted here
 *     deliberately; the parity gates compare rendered output byte for byte.</li>
 * </ul>
 *
 * <h2>Legacy behaviour this class asserts on purpose, and what it does not assert</h2>
 *
 * <ul>
 * <li><strong>The mask is one integer digit narrower than the value, and stays that way.</strong>
 *     {@code WS-TRAN-AMT-N} admits nine integer digits and {@code WS-TRAN-AMT-E} renders eight
 *     ({@code app/cbl/COTRN02C.cbl:58-59}), so an amount of a hundred million or more loses its leading digit
 *     on the echo at {@code :386}. The behaviour is legacy and the parity gates measure it, so section 4
 *     asserts the truncation deliberately.</li>
 * <li><strong>Three screen widths are narrower than the record widths behind them, and stay that way.</strong>
 *     {@code TDESCI} is {@code X(60)} against {@code TRAN-DESC X(100)}, {@code MNAMEI} is {@code X(30)}
 *     against {@code TRAN-MERCHANT-NAME X(50)}, and {@code MCITYI} is {@code X(25)} against
 *     {@code TRAN-MERCHANT-CITY X(50)}. Widening the payload would accept input the screen cannot supply, so
 *     section 13 asserts the right-hand truncation instead.</li>
 * <li><strong>Column types are sourced, not invented.</strong> {@code V1__create_schema.sql} declares all
 *     three money tiers explicitly - {@code tran_amt NUMERIC(11,2)} at line 1069,
 *     {@code acct_curr_bal NUMERIC(12,2)} at line 521 and {@code dis_int_rate NUMERIC(6,2)} at line 891 - and
 *     section 5 cites those lines.</li>
 * <li><strong>Not available - service-level objectives.</strong> The legacy corpus publishes no latency or
 *     throughput target for the transaction-add path, so this class asserts none and invents none. Nothing
 *     here depends on one, because every assertion is a functional contract rather than a timing budget.</li>
 * </ul>
 */
@DisplayName("TransactionAddRequest: 21 screen fields, two parsers, a mask that truncates, a redacted dump")
final class TransactionAddRequestTest {

    /** The transaction-add symbolic map, parsed from the frozen tree at {@code app/cpy-bms/COTRN02.CPY}. */
    private static final BmsSymbolicMap COTRN02 = BmsSymbolicMap.of("COTRN02");

    /** The sign-on map, read only to prove the {@code CURTIMEI} width outlier at {@code COSGN00.CPY:54}. */
    private static final BmsSymbolicMap COSGN00 = BmsSymbolicMap.of("COSGN00");

    /** The number of input fields group {@code COTRN2AI} declares. */
    private static final int DECLARED_FIELD_COUNT = 21;

    /**
     * The record component carrying each {@code COTRN02} input field, in copybook declaration order.
     *
     * <p>A flat list of alternating names, folded into pairs by {@code RecordFieldContract.pairsOf}. Written
     * flat so the mapping is stated once rather than twice; an odd length is refused there, which is what
     * turns a dropped entry into an immediate failure instead of a silent re-pairing of every later field.
     */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "TRNNAMEI", "transactionName",
            "TITLE01I", "title01",
            "CURDATEI", "currentDate",
            "PGMNAMEI", "programName",
            "TITLE02I", "title02",
            "CURTIMEI", "currentTime",
            "ACTIDINI", "accountId",
            "CARDNINI", "cardNumber",
            "TTYPCDI", "typeCode",
            "TCATCDI", "categoryCode",
            "TRNSRCI", "source",
            "TDESCI", "description",
            "TRNAMTI", "amount",
            "TORIGDTI", "originatingDate",
            "TPROCDTI", "processingDate",
            "MIDI", "merchantId",
            "MNAMEI", "merchantName",
            "MCITYI", "merchantCity",
            "MZIPI", "merchantZip",
            "CONFIRMI", "confirmation",
            "ERRMSGI", "errorMessage");

    /** The six header fields that recur on all seventeen maps and are still declared inline here. */
    private static final List<String> RECURRING_HEADER_FIELDS =
            List.of("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI");

    /**
     * A synthetic sixteen-digit stand-in for a primary account number.
     *
     * <p>Deliberately not a real card number and deliberately not one of the well-known vendor test values:
     * {@code 9999} is not an issued issuer identification number and the digits fail the Luhn check, so the
     * value cannot be mistaken for a live credential if it ever surfaces in an assertion message. Rule 1
     * clause D names tests explicitly, and the fixture's own card numbers are never hardcoded here - they
     * are read from the classpath resource and identified by row index alone.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "9999888877776666";

    /** The first seeded account identifier, zero-padded to the catalogued key length of eleven. */
    private static final String FIRST_SEEDED_ACCOUNT_ID = "00000000001";

    /** One-based column of {@code TRAN-TYPE-CD} in the 350-byte record, {@code app/cpy/CVTRA05Y.cpy:6}. */
    private static final int TYPE_CODE_COLUMN = 17;

    /** One-based column of {@code TRAN-CAT-CD}, {@code app/cpy/CVTRA05Y.cpy:7}. */
    private static final int CATEGORY_CODE_COLUMN = 19;

    /** One-based column of {@code TRAN-SOURCE}, {@code app/cpy/CVTRA05Y.cpy:8}. */
    private static final int SOURCE_COLUMN = 23;

    /** One-based column of {@code TRAN-DESC}, {@code app/cpy/CVTRA05Y.cpy:9}. */
    private static final int DESCRIPTION_COLUMN = 33;

    /** One-based column of {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:10}. */
    private static final int AMOUNT_COLUMN = 133;

    /** One-based column of {@code TRAN-MERCHANT-ID}, {@code app/cpy/CVTRA05Y.cpy:11}. */
    private static final int MERCHANT_ID_COLUMN = 144;

    /** One-based column of {@code TRAN-MERCHANT-NAME}, {@code app/cpy/CVTRA05Y.cpy:12}. */
    private static final int MERCHANT_NAME_COLUMN = 153;

    /** One-based column of {@code TRAN-MERCHANT-CITY}, {@code app/cpy/CVTRA05Y.cpy:13}. */
    private static final int MERCHANT_CITY_COLUMN = 203;

    /** One-based column of {@code TRAN-MERCHANT-ZIP}, {@code app/cpy/CVTRA05Y.cpy:14}. */
    private static final int MERCHANT_ZIP_COLUMN = 253;

    /** One-based column of {@code TRAN-CARD-NUM}, {@code app/cpy/CVTRA05Y.cpy:15}. */
    private static final int CARD_NUMBER_COLUMN = 263;

    /** One-based column of {@code TRAN-ORIG-TS}, {@code app/cpy/CVTRA05Y.cpy:16}. */
    private static final int ORIGINATING_TIMESTAMP_COLUMN = 279;

    /** One-based column of {@code TRAN-PROC-TS}, {@code app/cpy/CVTRA05Y.cpy:17}. */
    private static final int PROCESSING_TIMESTAMP_COLUMN = 305;

    /** Number of integer digits the edited mask {@code PIC +99999999.99} can render. */
    private static final int MASK_INTEGER_DIGITS = 8;

    /** Number of integer digits the numeric field {@code PIC S9(9)V99} admits. */
    private static final int VALUE_INTEGER_DIGITS = 9;

    /** Width of the generated transaction identifier, {@code WS-TRAN-ID-N PIC 9(16)} at {@code :57}. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** The sentinel {@code READPREV} leaves in the key on end of file, {@code app/cbl/COBIL00C.cbl:488}. */
    private static final String END_OF_FILE_KEY = "0000000000000000";

    /**
     * Folds {@link #FIELD_TO_COMPONENT} into {@code field:component} pairs for the parameterised tests.
     *
     * @return one {@code field:component} string per pair, in copybook declaration order
     */
    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    /**
     * Returns the component names in declaration order, read off the record itself rather than restated.
     *
     * @return the 21 component names, in declaration order
     */
    private static List<String> componentNames() {
        return RecordFieldContract.componentNames(TransactionAddRequest.class);
    }

    /**
     * Builds a payload whose every component carries a distinguishable, in-width value.
     *
     * @return a fully populated payload, never {@code null}
     */
    private static TransactionAddRequest populated() {
        return new TransactionAddRequest("CT02", "CardDemo Add Transaction", "08/01/26", "COTRN02C",
                "Add Transaction", "14:22:31", FIRST_SEEDED_ACCOUNT_ID, SYNTHETIC_CARD_NUMBER, "01", "0001",
                "POS TERM  ", "GROCERY PURCHASE", "+00001234.56", "2022-06-10", "2022-06-11",
                "800000000", "ACME SUPERMARKET", "NEW YORK", "10001-1234", "Y", "");
    }

    /**
     * Builds a payload in which every component is {@code null}.
     *
     * @return an entirely absent payload, never {@code null}
     */
    private static TransactionAddRequest absent() {
        return new TransactionAddRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a payload carrying one value in one component and {@code null} everywhere else.
     *
     * @param component the component name to populate; must be one of the 21
     * @param value     the value to place in it, which may be {@code null}, empty or blank
     * @return the payload, never {@code null}
     * @throws AssertionError if {@code component} is not one of the 21 declared components
     */
    private static TransactionAddRequest withOnly(final String component, final String value) {
        final List<String> names = componentNames();
        final int position = names.indexOf(component);
        if (position < 0) {
            throw new AssertionError(
                    "TransactionAddRequest declares no component named " + component + "; it declares " + names);
        }
        final String[] arguments = new String[names.size()];
        arguments[position] = value;
        return new TransactionAddRequest(arguments[0], arguments[1], arguments[2], arguments[3], arguments[4],
                arguments[5], arguments[6], arguments[7], arguments[8], arguments[9], arguments[10],
                arguments[11], arguments[12], arguments[13], arguments[14], arguments[15], arguments[16],
                arguments[17], arguments[18], arguments[19], arguments[20]);
    }

    /**
     * Reads one component off a payload by name, in the record's own accessor order.
     *
     * <p>Built with {@code Arrays.asList} rather than {@code List.of} on purpose: an absent component is
     * {@code null} and {@code List.of} refuses a {@code null} element, so the immutable factory would force a
     * sentinel and the sentinel would then have to be translated back. Absence has to survive this hop
     * intact, because telling absence from blank is the property several assertions below turn on.
     *
     * @param request   the payload to read
     * @param component the component name; must be one of the 21
     * @return the value held in that component, which may be {@code null}
     * @throws AssertionError if {@code component} is not one of the 21 declared components
     */
    private static String componentValue(final TransactionAddRequest request, final String component) {
        final List<String> names = componentNames();
        final List<String> values = java.util.Arrays.asList(request.transactionName(), request.title01(),
                request.currentDate(), request.programName(), request.title02(), request.currentTime(),
                request.accountId(), request.cardNumber(), request.typeCode(), request.categoryCode(),
                request.source(), request.description(), request.amount(), request.originatingDate(),
                request.processingDate(), request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantZip(), request.confirmation(),
                request.errorMessage());
        final int position = names.indexOf(component);
        if (position < 0) {
            throw new AssertionError(
                    "TransactionAddRequest declares no component named " + component + "; it declares " + names);
        }
        return values.get(position);
    }

    /**
     * Runs a payload through the shared bean validator.
     *
     * @param request the payload to validate; must not be {@code null}
     * @return the constraint violations, empty when every declared width ceiling is respected
     */
    private static Set<ConstraintViolation<TransactionAddRequest>> violationsOf(
            final TransactionAddRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    /**
     * Repeats one character to a given length, used to build one-under and one-over boundary values.
     *
     * @param character the character to repeat
     * @param length    how many times to repeat it; zero yields the empty string
     * @return the repeated string
     */
    private static String repeat(final char character, final int length) {
        return String.valueOf(character).repeat(length);
    }

    /**
     * Models the <strong>strict</strong> identifier conversion the source applies to an account identifier and
     * a card number: the {@code IS NOT NUMERIC} guard at {@code app/cbl/COTRN02C.cbl:197} and {@code :211},
     * followed by plain {@code FUNCTION NUMVAL} at {@code :204-205} and {@code :218-219}.
     *
     * <p>COBOL's {@code IS NUMERIC} class test on an alphanumeric {@code PIC X(n)} item is true only when
     * <em>every</em> character position holds a digit. There is no tolerance for a sign, a decimal point, a
     * thousands separator, a currency symbol or a space - not even a trailing one. That is why the seeded
     * account identifier is {@code 00000000001} and not {@code 1} followed by ten spaces: the padded form is
     * the only one that passes the guard.
     *
     * <p>A pure function with no state of any kind, so it is safe to call from any test in any order.
     *
     * @param screenText the raw screen characters, which may be {@code null}
     * @return the converted value at scale zero, or {@link Optional#empty()} when the guard rejects the text
     */
    private static Optional<BigDecimal> strictNumval(final String screenText) {
        if (screenText == null || screenText.isEmpty()) {
            return Optional.empty();
        }
        for (int position = 0; position < screenText.length(); position++) {
            final char character = screenText.charAt(position);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
        }
        return Optional.of(new BigDecimal(screenText));
    }

    /**
     * Models the <strong>currency-tolerant</strong> amount conversion the source applies to the amount field:
     * {@code FUNCTION NUMVAL-C} at {@code app/cbl/COTRN02C.cbl:383-384} and {@code :456-457}.
     *
     * <p>{@code NUMVAL-C} accepts everything {@code NUMVAL} accepts and, in addition, a currency sign and
     * thousands separators. What it does not accept is an embedded space, a second decimal point, a sign in
     * both positions at once, or a value with no digit in it at all. Surrounding spaces are permitted and
     * ignored, which is what lets a screen field arrive space padded.
     *
     * <p>A pure function with no state. The result is normalised to
     * {@link TransactionAddRequest#AMOUNT_SCALE} using {@link TransactionAddRequest#AMOUNT_ROUNDING_MODE},
     * which is the scale and rounding the persisted {@code TRAN-AMT PIC S9(09)V99} requires.
     *
     * @param screenText the raw screen characters, which may be {@code null}
     * @return the converted value at the amount scale, or {@link Optional#empty()} when the text is not a
     *         conforming currency figure
     */
    private static Optional<BigDecimal> tolerantNumvalC(final String screenText) {
        if (screenText == null) {
            return Optional.empty();
        }
        final String trimmed = screenText.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        boolean negative = false;
        String body = trimmed;
        if (body.charAt(0) == '+' || body.charAt(0) == '-') {
            negative = body.charAt(0) == '-';
            body = body.substring(1);
        } else if (body.charAt(body.length() - 1) == '+' || body.charAt(body.length() - 1) == '-') {
            negative = body.charAt(body.length() - 1) == '-';
            body = body.substring(0, body.length() - 1);
        }
        if (!body.isEmpty() && body.charAt(0) == '$') {
            body = body.substring(1);
        }
        final StringBuilder digits = new StringBuilder(body.length());
        boolean decimalPointSeen = false;
        boolean anyDigit = false;
        for (int position = 0; position < body.length(); position++) {
            final char character = body.charAt(position);
            if (character >= '0' && character <= '9') {
                digits.append(character);
                anyDigit = true;
            } else if (character == '.') {
                if (decimalPointSeen) {
                    return Optional.empty();
                }
                decimalPointSeen = true;
                digits.append('.');
            } else if (character == ',') {
                if (decimalPointSeen) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
        if (!anyDigit) {
            return Optional.empty();
        }
        final BigDecimal magnitude = new BigDecimal(digits.toString())
                .setScale(TransactionAddRequest.AMOUNT_SCALE, TransactionAddRequest.AMOUNT_ROUNDING_MODE);
        return Optional.of(negative ? magnitude.negate() : magnitude);
    }

    /**
     * Models {@code MOVE WS-TRAN-AMT-N TO WS-TRAN-AMT-E} at {@code app/cbl/COTRN02C.cbl:385}, the move onto
     * the edited display mask {@code PIC +99999999.99} declared at {@code :59}.
     *
     * <p>Three properties of the COBOL move are reproduced, and each one is load bearing:
     *
     * <ul>
     * <li>The sign position is <strong>mandatory</strong>. A {@code +} picture symbol always prints a
     *     character - {@code +} for a value that is not negative, {@code -} for one that is - so the rendering
     *     is always twelve characters wide, exactly the width of {@code TRNAMTI PIC X(12)}.</li>
     * <li>The integer part is truncated at the <strong>high order</strong> end. The receiving field has eight
     *     integer digit positions and the sending field has nine, so a value of a hundred million or more
     *     loses its leading digit silently. This is the asymmetry the parity gates measure; it is reproduced,
     *     never repaired.</li>
     * <li>The fraction is exactly two digits, zero filled, aligned on the decimal point.</li>
     * </ul>
     *
     * <p>A pure function. {@link Locale#ROOT} is passed explicitly so the decimal separator cannot follow the
     * host locale, which in many locales would emit a comma and break byte-exact comparison.
     *
     * @param value the parsed amount; must not be {@code null}
     * @return the twelve-character masked rendering, never {@code null}
     */
    private static String renderOnEditedMask(final BigDecimal value) {
        final BigDecimal scaled =
                value.setScale(TransactionAddRequest.AMOUNT_SCALE, TransactionAddRequest.AMOUNT_ROUNDING_MODE);
        final BigDecimal magnitude = scaled.abs();
        final BigDecimal maskCapacity = BigDecimal.TEN.pow(MASK_INTEGER_DIGITS);
        final BigDecimal truncated = magnitude.remainder(maskCapacity);
        final char sign = scaled.signum() < 0 ? '-' : '+';
        final String integerPart = truncated.toBigInteger().toString();
        final String fraction = truncated.subtract(new BigDecimal(truncated.toBigInteger()))
                .setScale(TransactionAddRequest.AMOUNT_SCALE, TransactionAddRequest.AMOUNT_ROUNDING_MODE)
                .unscaledValue()
                .toString();
        return String.format(Locale.ROOT, "%c%s.%s", Character.valueOf(sign),
                "0".repeat(MASK_INTEGER_DIGITS - integerPart.length()) + integerPart,
                "0".repeat(TransactionAddRequest.AMOUNT_SCALE - fraction.length()) + fraction);
    }

    /**
     * Models the descending-browse identifier idiom at {@code app/cbl/COTRN02C.cbl:444-451}.
     *
     * <p>The source moves high values into {@code TRAN-ID}, starts a browse, reads the <em>previous</em>
     * record, ends the browse, moves the retrieved key into {@code WS-TRAN-ID-N PIC 9(16)} and adds one. On
     * end of file the sibling program leaves zeros in the key -
     * {@code app/cbl/COBIL00C.cbl:487-488} - so the first identifier a fresh file yields is 1.
     *
     * <p>The algorithm is inherently racy under concurrency, exactly as the browse was, and it is retained for
     * parity rather than replaced by a database sequence: a sequence would change the generated values and
     * break byte-exact comparison against the legacy baseline. A collision surfaces as a duplicate-key
     * violation from the primary-key constraint. Held, as a deliberately preserved quirk, at
     * {@code DL-PP-04} in {@code DECISION_LOG.md}.
     *
     * <p>A pure function of the retrieved key alone; it holds no counter and no state.
     *
     * @param retrievedKey the key {@code READPREV} left behind, sixteen digits, zeros on end of file
     * @return the next identifier, sixteen characters, zero padded so a leading zero survives
     * @throws AssertionError if {@code retrievedKey} is not sixteen digits, which would mean the browse
     *                        returned something the {@code PIC 9(16)} field could not have held
     */
    private static String nextTransactionId(final String retrievedKey) {
        if (strictNumval(retrievedKey).isEmpty() || retrievedKey.length() != TRANSACTION_ID_WIDTH) {
            throw new AssertionError("a PIC 9(16) key holds exactly sixteen digits, but the browse returned a "
                    + (retrievedKey == null ? "null" : retrievedKey.length() + "-character")
                    + " value that is not all digits");
        }
        final BigDecimal next = new BigDecimal(retrievedKey).add(BigDecimal.ONE);
        final String digits = next.toBigInteger().toString();
        return "0".repeat(Math.max(0, TRANSACTION_ID_WIDTH - digits.length())) + digits;
    }

    @Nested
    @DisplayName("1. The 21 input fields of COTRN2AI, every width taken from the frozen copybook")
    final class FieldContract {

        @Test
        @DisplayName("the copybook declares 21 input fields and the record declares 21 components")
        void componentCountMatchesTheCopybook() {
            assertThat(COTRN02.inputFieldCount())
                    .as("input fields in group COTRN2AI of app/cpy-bms/COTRN02.CPY")
                    .isEqualTo(DECLARED_FIELD_COUNT);
            assertThat(componentNames())
                    .as("record components of TransactionAddRequest")
                    .hasSize(DECLARED_FIELD_COUNT);
        }

        @Test
        @DisplayName("every component sits at the copybook index of the field it carries")
        void componentsCorrespondPositionally() {
            final List<String> components = componentNames();
            final List<String> copybookOrder = COTRN02.fieldNames();

            assertThat(FIELD_TO_COMPONENT)
                    .as("the flat mapping pairs every field with a component")
                    .hasSize(2 * DECLARED_FIELD_COUNT);
            for (int index = 0; index < DECLARED_FIELD_COUNT; index++) {
                assertThat(copybookOrder.get(index))
                        .as("copybook field at index %d", Integer.valueOf(index))
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index));
                assertThat(components.get(index))
                        .as("record component at index %d", Integer.valueOf(index))
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index + 1));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.TransactionAddRequestTest#fieldComponentPairs")
        @DisplayName("each declared ceiling is the copybook width, read rather than restated")
        void declaredWidthComesFromTheCopybook(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);

            assertThat(COTRN02.declares(field))
                    .as("%s is declared in group COTRN2AI", field)
                    .isTrue();
            assertThat(RecordFieldContract.declaredSizeMax(TransactionAddRequest.class, component))
                    .as("%s declares PIC X(%d) on COTRN02", field,
                            Integer.valueOf(COTRN02.widthOf(field)))
                    .isEqualTo(COTRN02.widthOf(field));
        }

        @Test
        @DisplayName("the twelve-byte terminal I/O area is storage and is carried by no component")
        void terminalIoAreaIsNotAField() {
            assertThat(COTRN02.fieldNames())
                    .as("the leading FILLER PIC X(12) at app/cpy-bms/COTRN02.CPY:18 is not a screen field")
                    .doesNotContain("FILLER");
            assertThat(componentNames()).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("filler"));
        }

        @Test
        @DisplayName("the input group is read, and the output redefinition that follows it is not")
        void onlyTheInputGroupIsRead() {
            assertThat(COTRN02.inputGroupLine())
                    .as("01 COTRN2AI. at app/cpy-bms/COTRN02.CPY:17")
                    .isEqualTo(17);
            assertThat(COTRN02.outputRedefinitionLine())
                    .as("01 COTRN2AO REDEFINES COTRN2AI. at app/cpy-bms/COTRN02.CPY:145")
                    .isEqualTo(145)
                    .isGreaterThan(COTRN02.inputGroupLine());
        }

        @Test
        @DisplayName("a fully populated in-width payload raises no violation")
        void populatedPayloadIsValid() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("an entirely absent payload raises no violation, because absence is legitimate")
        void absentPayloadIsValid() {
            assertThat(violationsOf(absent()))
                    .as("the source renders a blank field with a marker rather than refusing the request")
                    .isEmpty();
        }

        @Test
        @DisplayName("the corpus-wide census is 441 input fields, measured across all seventeen maps")
        void corpusWideCensusIsFourHundredAndFortyOne() {
            final List<String> allMembers = List.of("COACTUP", "COACTVW", "COADM01", "COBIL00", "COCRDLI",
                    "COCRDSL", "COCRDUP", "COMEN01", "CORPT00", "COSGN00", "COTRN00", "COTRN01", "COTRN02",
                    "COUSR00", "COUSR01", "COUSR02", "COUSR03");
            int aggregate = 0;
            for (final String member : allMembers) {
                aggregate += BmsSymbolicMap.of(member).inputFieldCount();
            }

            assertThat(allMembers).as("seventeen generated symbolic maps").hasSize(17);
            assertThat(BmsSymbolicMap.of("COACTVW").inputFieldCount())
                    .as("COACTVW declares 37, not 36: PIC 99999999999 at app/cpy-bms/COACTVW.CPY:60 is an"
                            + " input field written in expanded form and is easy to miss")
                    .isEqualTo(37);
            assertThat(aggregate)
                    .as("measured across all seventeen members by parsing the frozen copybooks rather"
                            + " than by transcribing a total")
                    .isEqualTo(441)
                    .isNotEqualTo(460)
                    .isNotEqualTo(440);
            assertThat(COTRN02.inputFieldCount())
                    .as("this map's own contribution is measured directly, so no assertion here depends on"
                            + " the aggregate being right")
                    .isEqualTo(DECLARED_FIELD_COUNT);
        }
    }

    @Nested
    @DisplayName("2. The six recurring header fields, declared inline because one width differs")
    final class RecurringHeaderFields {

        @Test
        @DisplayName("all six header fields open the input group in copybook order")
        void headerFieldsOpenTheGroup() {
            assertThat(COTRN02.fieldNames().subList(0, RECURRING_HEADER_FIELDS.size()))
                    .containsExactlyElementsOf(RECURRING_HEADER_FIELDS);
        }

        @Test
        @DisplayName("five header widths are uniform across the two maps")
        void fiveHeaderWidthsAgree() {
            assertThat(COTRN02.widthOf("TRNNAMEI")).isEqualTo(4).isEqualTo(COSGN00.widthOf("TRNNAMEI"));
            assertThat(COTRN02.widthOf("TITLE01I")).isEqualTo(40).isEqualTo(COSGN00.widthOf("TITLE01I"));
            assertThat(COTRN02.widthOf("CURDATEI")).isEqualTo(8).isEqualTo(COSGN00.widthOf("CURDATEI"));
            assertThat(COTRN02.widthOf("PGMNAMEI")).isEqualTo(8).isEqualTo(COSGN00.widthOf("PGMNAMEI"));
            assertThat(COTRN02.widthOf("TITLE02I")).isEqualTo(40).isEqualTo(COSGN00.widthOf("TITLE02I"));
        }

        @Test
        @DisplayName("the sixth width differs, so a shared header type would misstate one map")
        void currentTimeWidthIsTheOutlier() {
            assertThat(COTRN02.widthOf("CURTIMEI"))
                    .as("CURTIMEI PIC X(8) at app/cpy-bms/COTRN02.CPY:54")
                    .isEqualTo(8);
            assertThat(COSGN00.widthOf("CURTIMEI"))
                    .as("CURTIMEI PIC X(9) at app/cpy-bms/COSGN00.CPY:54, the sole outlier in the corpus")
                    .isEqualTo(9);
            assertThat(COTRN02.widthOf("CURTIMEI"))
                    .as("a hoisted header type would have to pick one of these and misstate the other")
                    .isNotEqualTo(COSGN00.widthOf("CURTIMEI"));
        }

        @Test
        @DisplayName("this record carries its own currentTime ceiling, not the sign-on map's")
        void currentTimeCeilingFollowsThisMap() {
            assertThat(RecordFieldContract.declaredSizeMax(TransactionAddRequest.class, "currentTime"))
                    .isEqualTo(COTRN02.widthOf("CURTIMEI"))
                    .isNotEqualTo(COSGN00.widthOf("CURTIMEI"));
        }

        @Test
        @DisplayName("no supertype is interposed: the record extends nothing and implements nothing")
        void recordDeclaresNoSharedHeaderSupertype() {
            assertThat(TransactionAddRequest.class.getInterfaces())
                    .as("no header interface or mixin is implemented")
                    .isEmpty();
            assertThat(TransactionAddRequest.class.getSuperclass())
                    .as("a record's only supertype is java.lang.Record")
                    .isEqualTo(java.lang.Record.class);
        }
    }

    @Nested
    @DisplayName("3. Two different numeric parsers on one screen, and they are not interchangeable")
    final class TwoDistinctNumericParsers {

        @Test
        @DisplayName("the identifier and the amount are both carried as text, so both parsers stay reachable")
        void bothFieldsAreCarriedAsText() {
            final List<String> numericLooking = List.of("accountId", "cardNumber", "amount", "merchantId");
            for (final String component : numericLooking) {
                assertThat(RecordFieldContract.annotationOn(TransactionAddRequest.class, component,
                        Size.class))
                        .as("%s declares the width ceiling of its screen field", component)
                        .isNotNull();
                assertThat(componentValue(withOnly(component, "0000000001"), component))
                        .as("%s is carried verbatim, so no conversion happens at the boundary", component)
                        .isEqualTo("0000000001");
            }
        }

        @ParameterizedTest(name = "the strict parser refuses \"{0}\"")
        @ValueSource(strings = {"$1,234.56", "1 234", "12.34.56", "-0", "+", "", "+00001234.56", "1234.56",
            "1,234", "00000000001 ", " 00000000001", "0000000000A"})
        @DisplayName("the strict identifier parser refuses anything that is not digits end to end")
        void strictParserRefusesNonDigits(final String hostileText) {
            assertThat(strictNumval(hostileText))
                    .as("IS NOT NUMERIC at app/cbl/COTRN02C.cbl:197 and :211 rejects this text")
                    .isEmpty();
        }

        @Test
        @DisplayName("the strict parser accepts a zero-padded identifier and a sixteen-digit card number")
        void strictParserAcceptsDigitsOnly() {
            assertThat(strictNumval(FIRST_SEEDED_ACCOUNT_ID))
                    .as("the seeded account identifier passes the IS NUMERIC guard")
                    .contains(new BigDecimal(FIRST_SEEDED_ACCOUNT_ID));
            assertThat(strictNumval(SYNTHETIC_CARD_NUMBER))
                    .as("a sixteen-digit card number passes the guard at app/cbl/COTRN02C.cbl:211")
                    .isPresent();
        }

        @ParameterizedTest(name = "the tolerant parser reads \"{0}\" as {1}")
        @CsvSource({
            "'$1,234.56', 1234.56",
            "'1,234.56', 1234.56",
            "'1234.56', 1234.56",
            "'+00001234.56', 1234.56",
            "'-00001234.56', -1234.56",
            "'1234.56-', -1234.56",
            "'  1234.56  ', 1234.56",
            "'$0.05', 0.05",
            "'-0', 0.00",
            "'$1,234,567.89', 1234567.89"})
        @DisplayName("the currency-tolerant amount parser admits a currency sign and thousands separators")
        void tolerantParserAcceptsDecoratedAmounts(final String screenText, final String expected) {
            final Optional<BigDecimal> parsed = tolerantNumvalC(screenText);

            assertThat(parsed)
                    .as("FUNCTION NUMVAL-C at app/cbl/COTRN02C.cbl:383-384 accepts this text")
                    .isPresent();
            assertThat(parsed.orElseThrow())
                    .as("compared by value with compareTo, never by scale with equals")
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @ParameterizedTest(name = "the tolerant parser refuses \"{0}\" too")
        @ValueSource(strings = {"1 234", "12.34.56", "+", "", "   ", "-+1234", "12,34.5,6", "1234.5A",
            "USD 1234.56"})
        @DisplayName("the tolerant parser is tolerant, not permissive: an embedded space is still refused")
        void tolerantParserStillRefusesMalformedText(final String hostileText) {
            assertThat(tolerantNumvalC(hostileText))
                    .as("NUMVAL-C admits a currency sign and separators, not arbitrary text")
                    .isEmpty();
        }

        @Test
        @DisplayName("the strict parser applied to an amount rejects what the source accepts")
        void strictParserOnAnAmountIsTheDefect() {
            final String decoratedAmount = "$1,234.56";

            assertThat(tolerantNumvalC(decoratedAmount))
                    .as("the amount path at app/cbl/COTRN02C.cbl:456-457 accepts it")
                    .isPresent();
            assertThat(strictNumval(decoratedAmount))
                    .as("sharing one parser would reject an amount the legacy screen accepted")
                    .isEmpty();
        }

        @Test
        @DisplayName("the tolerant parser applied to a card number accepts what the source rejects")
        void tolerantParserOnAnIdentifierIsTheDefect() {
            final String decoratedIdentifier = "$1,234.56";

            assertThat(strictNumval(decoratedIdentifier))
                    .as("the guard at app/cbl/COTRN02C.cbl:211 refuses it with 'Card Number must be Numeric...'")
                    .isEmpty();
            assertThat(tolerantNumvalC(decoratedIdentifier))
                    .as("sharing one parser would admit an identifier the legacy screen refused")
                    .isPresent();
        }

        @Test
        @DisplayName("a seventeen-digit card number is caught by the width ceiling, not by the parser")
        void seventeenDigitCardNumberBreachesTheWidth() {
            final String seventeenDigits = repeat('7', 17);

            assertThat(strictNumval(seventeenDigits))
                    .as("every character is a digit, so the class test alone would pass it")
                    .isPresent();
            assertThat(violationsOf(withOnly("cardNumber", seventeenDigits)))
                    .as("CARDNINI PIC X(16) at app/cpy-bms/COTRN02.CPY:66 is what refuses it")
                    .hasSize(1)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("cardNumber"));
        }

        @Test
        @DisplayName("the record declares no constraint that would pre-empt either parser")
        void noConstraintPreEmptsEitherParser() {
            for (final String component : componentNames()) {
                assertThat(RecordFieldContract.declares(TransactionAddRequest.class, component, Digits.class))
                        .as("%s declares no @Digits, which would reject a currency-decorated amount", component)
                        .isFalse();
                assertThat(RecordFieldContract.declares(TransactionAddRequest.class, component, Pattern.class))
                        .as("%s declares no @Pattern", component)
                        .isFalse();
                assertThat(RecordFieldContract.declares(TransactionAddRequest.class, component, NotNull.class))
                        .as("%s declares no @NotNull, because the source tolerates a blank field", component)
                        .isFalse();
                assertThat(RecordFieldContract.declares(TransactionAddRequest.class, component, NotBlank.class))
                        .as("%s declares no @NotBlank", component)
                        .isFalse();
                assertThat(RecordFieldContract.declares(TransactionAddRequest.class, component, NotEmpty.class))
                        .as("%s declares no @NotEmpty", component)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("parsing is locale independent, so a comma-decimal host cannot change the outcome")
        void parsingIsLocaleIndependent() {
            assertThat(tolerantNumvalC("1,234.56").orElseThrow())
                    .as("the comma groups and the period is the decimal point, under Locale.ROOT")
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(String.format(Locale.ROOT, "%s", new BigDecimal("1234.56")))
                    .as("Locale.ROOT renders a period, which is what the mask requires; no double is used")
                    .isEqualTo("1234.56");
        }
    }

    @Nested
    @DisplayName("4. The edited mask renders one integer digit fewer than the value holds")
    final class EditedMaskTruncation {

        @Test
        @DisplayName("the mask is the source literal, eight integer digits and two decimals")
        void maskIsTheSourceLiteral() {
            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK)
                    .as("WS-TRAN-AMT-E PIC +99999999.99 at app/cbl/COTRN02C.cbl:59")
                    .isEqualTo("+99999999.99");
            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK.chars().filter(c -> c == '9').count())
                    .as("eight integer positions plus two decimal positions")
                    .isEqualTo(MASK_INTEGER_DIGITS + TransactionAddRequest.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("the rendered width equals the screen field width, because the sign is mandatory")
        void renderedWidthFillsTheScreenField() {
            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK.length())
                    .as("TRNAMTI PIC X(12) at app/cpy-bms/COTRN02.CPY:96")
                    .isEqualTo(COTRN02.widthOf("TRNAMTI"));
            assertThat(renderOnEditedMask(new BigDecimal("1234.56")))
                    .hasSize(COTRN02.widthOf("TRNAMTI"))
                    .isEqualTo("+00001234.56");
        }

        @Test
        @DisplayName("the sign position is always occupied, and zero is rendered positive")
        void signIsMandatory() {
            assertThat(renderOnEditedMask(new BigDecimal("1234.56"))).startsWith("+");
            assertThat(renderOnEditedMask(new BigDecimal("-1234.56"))).startsWith("-").isEqualTo("-00001234.56");
            assertThat(renderOnEditedMask(tolerantNumvalC("-0").orElseThrow()))
                    .as("a negative-zero screen entry has no sign once converted, so the mask prints +")
                    .isEqualTo("+00000000.00");
        }

        @Test
        @DisplayName("exactly two decimal positions are rendered, zero filled, never rounded away")
        void twoDecimalsAlways() {
            assertThat(renderOnEditedMask(new BigDecimal("7"))).isEqualTo("+00000007.00");
            assertThat(renderOnEditedMask(new BigDecimal("0.05"))).isEqualTo("+00000000.05");
            assertThat(renderOnEditedMask(new BigDecimal("0.5"))).isEqualTo("+00000000.50");
            assertThat(renderOnEditedMask(new BigDecimal("999.996")))
                    .as("HALF_EVEN carries into the integer part rather than truncating the fraction")
                    .isEqualTo("+00001000.00");
        }

        @Test
        @DisplayName("a nine-integer-digit value loses its leading digit, and that is preserved not repaired")
        void nineDigitValueIsTruncatedByTheMask() {
            final BigDecimal nineIntegerDigits = new BigDecimal("123456789.99");

            assertThat(nineIntegerDigits.precision() - nineIntegerDigits.scale())
                    .as("nine integer digits, which WS-TRAN-AMT-N PIC S9(9)V99 at :58 admits")
                    .isEqualTo(VALUE_INTEGER_DIGITS);
            assertThat(renderOnEditedMask(nineIntegerDigits))
                    .as("the MOVE at :385 truncates at the high order end; the leading 1 is gone")
                    .isEqualTo("+23456789.99")
                    .hasSize(COTRN02.widthOf("TRNAMTI"));
            assertThat(tolerantNumvalC(renderOnEditedMask(nineIntegerDigits)).orElseThrow())
                    .as("re-reading the echo at :386 therefore yields a different value: parity, not a defect")
                    .isNotEqualByComparingTo(nineIntegerDigits);
        }

        @Test
        @DisplayName("the ceiling of the value field is exactly ten times the ceiling of the mask, plus change")
        void theTwoCeilingsDifferByOneDigit() {
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX)
                    .as("the largest figure PIC +99999999.99 can render")
                    .isEqualByComparingTo(new BigDecimal("99999999.99"));
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX)
                    .as("the largest figure PIC S9(9)V99 admits")
                    .isEqualByComparingTo(new BigDecimal("999999999.99"));
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX)
                    .isLessThan(TransactionAddRequest.AMOUNT_VALUE_MAX);
            assertThat(renderOnEditedMask(TransactionAddRequest.AMOUNT_MASK_MAX))
                    .as("the mask ceiling renders whole")
                    .isEqualTo("+99999999.99");
            assertThat(renderOnEditedMask(TransactionAddRequest.AMOUNT_VALUE_MAX))
                    .as("the value ceiling does not, which is the whole asymmetry in one line")
                    .isEqualTo("+99999999.99");
        }

        @Test
        @DisplayName("exactly one hundred million renders as zero, the most extreme form of the truncation")
        void oneHundredMillionRendersAsZero() {
            assertThat(renderOnEditedMask(new BigDecimal("100000000.00")))
                    .as("the only significant digit sits in the ninth position the mask cannot reach")
                    .isEqualTo("+00000000.00");
        }

        @Test
        @DisplayName("the billing masks are a different width and are deliberately not unified with this one")
        void billingMasksAreNotUnified() {
            final String billingAmountMask = "+99999999.99";
            final String billingBalanceMask = "+9999999999.99";

            assertThat(billingAmountMask)
                    .as("WS-TRAN-AMT at app/cbl/COBIL00C.cbl:55 matches this screen's amount mask")
                    .isEqualTo(TransactionAddRequest.AMOUNT_DISPLAY_MASK);
            assertThat(billingBalanceMask.chars().filter(c -> c == '9').count()
                    - TransactionAddRequest.AMOUNT_SCALE)
                    .as("WS-CURR-BAL at app/cbl/COBIL00C.cbl:56 renders ten integer digits, not eight")
                    .isEqualTo(10);
            assertThat(billingBalanceMask)
                    .as("two masks on one program; unifying them would change rendered output")
                    .isNotEqualTo(billingAmountMask);
        }
    }

    @Nested
    @DisplayName("5. Amount arithmetic: decimal only, three precision tiers, compared by value")
    final class AmountArithmeticContract {

        @Test
        @DisplayName("the transaction amount is the middle of three money tiers, never collapsed into one")
        void transactionPrecisionIsItsOwnTier() {
            // The three tiers are declared explicitly in the schema migration, which is present in this
            // checkout and is the evidence for the column types named here:
            //   tran_amt      NUMERIC(11,2)  src/main/resources/db/migration/V1__create_schema.sql
            //   acct_curr_bal NUMERIC(12,2)  src/main/resources/db/migration/V1__create_schema.sql
            //   dis_int_rate  NUMERIC(6,2)   src/main/resources/db/migration/V1__create_schema.sql
            // They derive from S9(09)V99, S9(10)V99 and S9(04)V99 respectively. This class asserts the
            // constant the payload publishes rather than reading the migration, so that the unit tier stays
            // free of a resource dependency; the migration columns are cited as the corroborating source.
            assertThat(TransactionAddRequest.AMOUNT_PRECISION)
                    .as("TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:10 is eleven characters")
                    .isEqualTo(11);
            assertThat(TransactionAddRequest.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionAddRequest.AMOUNT_PRECISION)
                    .as("the account tier from S9(10)V99 is NUMERIC(12,2); collapsing the two widens this one")
                    .isNotEqualTo(12);
            assertThat(TransactionAddRequest.AMOUNT_PRECISION)
                    .as("the rate tier from S9(04)V99 is NUMERIC(6,2); collapsing the two narrows this one")
                    .isNotEqualTo(6);
            assertThat(TransactionAddRequest.AMOUNT_PRECISION - TransactionAddRequest.AMOUNT_SCALE)
                    .as("nine integer digits, matching WS-TRAN-AMT-N at app/cbl/COTRN02C.cbl:58")
                    .isEqualTo(VALUE_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("the declared ceiling is consistent with the declared precision and scale")
        void ceilingAgreesWithPrecisionAndScale() {
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.scale())
                    .isEqualTo(TransactionAddRequest.AMOUNT_SCALE);
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.precision())
                    .isEqualTo(TransactionAddRequest.AMOUNT_PRECISION);
        }

        @Test
        @DisplayName("rounding is banker's rounding, never the arithmetic default")
        void roundingIsHalfEven() {
            assertThat(TransactionAddRequest.AMOUNT_ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
            assertThat(new BigDecimal("2.345").setScale(2, TransactionAddRequest.AMOUNT_ROUNDING_MODE))
                    .isEqualByComparingTo(new BigDecimal("2.34"));
            assertThat(new BigDecimal("2.355").setScale(2, TransactionAddRequest.AMOUNT_ROUNDING_MODE))
                    .as("ties go to the even digit in both directions")
                    .isEqualByComparingTo(new BigDecimal("2.36"));
            assertThat(new BigDecimal("2.345").setScale(2, RoundingMode.HALF_UP))
                    .as("HALF_UP would give a different answer, which is why the mode is pinned")
                    .isEqualByComparingTo(new BigDecimal("2.35"));
        }

        @Test
        @DisplayName("equality is by compareTo, because equals compares scale as well as value")
        void equalityIsByCompareTo() {
            final BigDecimal twoDecimals = new BigDecimal("1234.50");
            final BigDecimal oneDecimal = new BigDecimal("1234.5");

            assertThat(twoDecimals.compareTo(oneDecimal))
                    .as("the same amount at two scales compares equal")
                    .isZero();
            assertThat(twoDecimals.equals(oneDecimal))
                    .as("but equals is false, which is why no assertion here uses it on an amount")
                    .isFalse();
            assertThat(twoDecimals).isEqualByComparingTo(oneDecimal);
        }

        @Test
        @DisplayName("no component and no constant is a floating-point type")
        void noFloatingPointAnywhere() {
            for (final var component : TransactionAddRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("component %s carries screen text", component.getName())
                        .isEqualTo(String.class);
            }
            for (final var field : TransactionAddRequest.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s is neither float nor double", field.getName())
                        .isNotIn(float.class, double.class, Float.class, Double.class);
            }
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX).isInstanceOf(BigDecimal.class);
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX).isInstanceOf(BigDecimal.class);
        }

        @Test
        @DisplayName("a negative amount survives conversion and rendering with no absolute value taken")
        void negativeAmountsAreNeverNormalised() {
            final BigDecimal debit = tolerantNumvalC("-998.33").orElseThrow();

            assertThat(debit).isNegative().isEqualByComparingTo(new BigDecimal("-998.33"));
            assertThat(debit.abs())
                    .as("the magnitude exists but is never substituted for the signed value")
                    .isNotEqualByComparingTo(debit);
            assertThat(renderOnEditedMask(debit))
                    .as("the sign reaches the rendering, because the cycle-debit branch depends on it")
                    .isEqualTo("-00000998.33");
        }
    }

    @Nested
    @DisplayName("6. The source, the description and both timestamps pass straight through")
    final class PassThroughFields {

        @Test
        @DisplayName("the source is free text of width ten, carried exactly as supplied")
        void sourceIsCarriedVerbatim() {
            assertThat(COTRN02.widthOf("TRNSRCI"))
                    .as("TRNSRCI PIC X(10) at app/cpy-bms/COTRN02.CPY:84, TRAN-SOURCE PIC X(10) at CVTRA05Y:8")
                    .isEqualTo(10);
            assertThat(componentValue(withOnly("source", "POS TERM  "), "source"))
                    .as("MOVE TRNSRCI OF COTRN2AI TO TRAN-SOURCE at app/cbl/COTRN02C.cbl:454")
                    .isEqualTo("POS TERM  ");
        }

        @ParameterizedTest(name = "source \"{0}\" round-trips byte-exactly")
        @ValueSource(strings = {"System    ", "POS TERM  ", "OPERATOR  "})
        @DisplayName("all three ten-character source values round-trip")
        void everySourceValueRoundTrips(final String tenCharacterSource) {
            assertThat(tenCharacterSource).hasSize(COTRN02.widthOf("TRNSRCI"));
            assertThat(componentValue(withOnly("source", tenCharacterSource), "source"))
                    .isEqualTo(tenCharacterSource);
            assertThat(violationsOf(withOnly("source", tenCharacterSource)))
                    .as("no domain check happens at this boundary, so none of the three is refused")
                    .isEmpty();
        }

        @Test
        @DisplayName("the source component is a String, not the two-constant enum")
        void sourceIsNotEnumTyped() {
            assertThat(RecordFieldContract.componentNames(TransactionAddRequest.class)).contains("source");
            for (final var component : TransactionAddRequest.class.getRecordComponents()) {
                assertThat(component.getType().isEnum())
                        .as("component %s is not bound to an enum", component.getName())
                        .isFalse();
            }
            assertThat(componentValue(withOnly("source", "OPERATOR  "), "source"))
                    .as("OPERATOR appears in no program literal, yet the fixture carries it on 50 of 300 rows")
                    .isEqualTo("OPERATOR  ");
        }

        @Test
        @DisplayName("an out-of-domain source value is carried rather than refused")
        void outOfDomainSourceIsCarried() {
            final String unknownSource = "MOBILEAPP ";

            assertThat(unknownSource).hasSize(COTRN02.widthOf("TRNSRCI"));
            assertThat(violationsOf(withOnly("source", unknownSource)))
                    .as("binding to an enum would turn this into a framework error, changing behaviour")
                    .isEmpty();
        }

        @Test
        @DisplayName("the description is carried unaltered, with no trimming and no case folding")
        void descriptionIsCarriedVerbatim() {
            final String mixedCaseWithPadding = "  Purchase at Abshire-Lowe  ";

            assertThat(componentValue(withOnly("description", mixedCaseWithPadding), "description"))
                    .as("MOVE TDESCI OF COTRN2AI TO TRAN-DESC at app/cbl/COTRN02C.cbl:455")
                    .isEqualTo(mixedCaseWithPadding);
        }

        @Test
        @DisplayName("the type and category codes are raw text too, with no lookup at this boundary")
        void codesAreCarriedVerbatim() {
            assertThat(componentValue(withOnly("typeCode", "03"), "typeCode"))
                    .as("MOVE TTYPCDI OF COTRN2AI TO TRAN-TYPE-CD at app/cbl/COTRN02C.cbl:452")
                    .isEqualTo("03");
            assertThat(componentValue(withOnly("categoryCode", "0001"), "categoryCode"))
                    .as("MOVE TCATCDI OF COTRN2AI TO TRAN-CAT-CD at app/cbl/COTRN02C.cbl:453")
                    .isEqualTo("0001");
            assertThat(violationsOf(withOnly("typeCode", "ZZ")))
                    .as("an unknown type code is a service concern, not a binding concern")
                    .isEmpty();
        }

        @Test
        @DisplayName("the merchant postal code accepts both a five-digit and a ZIP+4 shape")
        void merchantZipAcceptsBothShapes() {
            assertThat(violationsOf(withOnly("merchantZip", "00022     "))).isEmpty();
            assertThat(violationsOf(withOnly("merchantZip", "03491-5716"))).isEmpty();
            assertThat(componentValue(withOnly("merchantZip", "03491-5716"), "merchantZip"))
                    .as("MZIPI PIC X(10) at app/cpy-bms/COTRN02.CPY:132 holds either shape")
                    .isEqualTo("03491-5716");
        }
    }

    @Nested
    @DisplayName("7. Three timestamp shapes, all text, all from a fixed clock")
    final class TimestampShapes {

        @Test
        @DisplayName("both date components are text, never a date or timestamp type")
        void datesAreText() {
            assertThat(RecordFieldContract.declaredSizeMax(TransactionAddRequest.class, "originatingDate"))
                    .as("TORIGDTI PIC X(10) at app/cpy-bms/COTRN02.CPY:102")
                    .isEqualTo(COTRN02.widthOf("TORIGDTI"));
            assertThat(RecordFieldContract.declaredSizeMax(TransactionAddRequest.class, "processingDate"))
                    .as("TPROCDTI PIC X(10) at app/cpy-bms/COTRN02.CPY:108")
                    .isEqualTo(COTRN02.widthOf("TPROCDTI"));
            for (final var component : TransactionAddRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("component %s is not a temporal type", component.getName())
                        .isNotIn(LocalDate.class, java.time.LocalDateTime.class, java.time.Instant.class,
                                java.util.Date.class, java.sql.Timestamp.class);
            }
        }

        @Test
        @DisplayName("shape one, ONLINE: the eleventh position is a space and the last six are zeros")
        void onlineShapeIsReproduced() {
            final String online = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());

            assertThat(online)
                    .as("GET-CURRENT-TIMESTAMP at app/cbl/COBIL00C.cbl:249-267")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(online.charAt(10))
                    .as("position 11 is left a space by INITIALIZE WS-TIMESTAMP at :263, never a dash or a T")
                    .isEqualTo(' ');
            assertThat(online.charAt(19)).as("position 20 is the decimal point").isEqualTo('.');
            assertThat(online.substring(20))
                    .as("MOVE ZEROS TO WS-TIMESTAMP-TM-MS6 at :266 leaves six literal zeros")
                    .isEqualTo("000000");
        }

        @Test
        @DisplayName("shape two, BATCH: three dashes, hundredths, then four literal zeros")
        void batchShapeIsReproduced() {
            final String batch = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());

            assertThat(batch)
                    .as("Z-GET-DB2-FORMAT-TIMESTAMP at app/cbl/CBACT04C.cbl:613-625")
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(batch.chars().filter(c -> c == '-').count())
                    .as("MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3 at :623 is three separators")
                    .isEqualTo(3);
            assertThat(batch.charAt(10))
                    .as("so a dash sits between DD and HH, where the online shape has a space")
                    .isEqualTo('-');
            assertThat(batch.chars().filter(c -> c == '.').count())
                    .as("MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3 at :624")
                    .isEqualTo(3);
            assertThat(batch.substring(22))
                    .as("MOVE '0000' TO DB2-REST at :622, so never nanoseconds and never milliseconds")
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("the two generated shapes differ only in their three separator positions")
        void theTwoGeneratedShapesDifferOnlyThere() {
            final String online = FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP;
            final String batch = FixedClockProvider.CANONICAL_BATCH_TIMESTAMP;
            final List<Integer> differing = new ArrayList<>();

            for (int position = 0; position < FixedClockProvider.TIMESTAMP_LENGTH; position++) {
                if (online.charAt(position) != batch.charAt(position)) {
                    differing.add(Integer.valueOf(position));
                }
            }

            assertThat(differing)
                    .as("the separators at 14 and 17 are colons online and dots in batch, plus position 11")
                    .containsExactly(Integer.valueOf(10), Integer.valueOf(13), Integer.valueOf(16));
        }

        @Test
        @DisplayName("shape three, PASS-THROUGH: ten screen characters land in a 26-character field")
        void passThroughShapeIsReproduced() {
            final String screenDate = "2022-06-10";

            assertThat(screenDate).hasSize(COTRN02.widthOf("TORIGDTI"));
            assertThat(FixedClockProvider.passThroughTimestamp(screenDate))
                    .as("MOVE TORIGDTI OF COTRN2AI TO TRAN-ORIG-TS at app/cbl/COTRN02C.cbl:464")
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                    .isEqualTo(screenDate + " ".repeat(16));
            assertThat(componentValue(withOnly("originatingDate", screenDate), "originatingDate"))
                    .as("the record itself neither pads nor parses; it carries the ten characters")
                    .isEqualTo(screenDate);
        }

        @Test
        @DisplayName("an all-blank 26-character timestamp round-trips, because the fixture holds exactly that")
        void twentySixSpacesRoundTrip() {
            final String blankTimestamp = " ".repeat(FixedClockProvider.TIMESTAMP_LENGTH);

            assertThat(FixedClockProvider.passThroughTimestamp(blankTimestamp))
                    .as("TRAN-PROC-TS is 26 blanks on all 300 rows of app/data/ASCII/dailytran.txt")
                    .isEqualTo(blankTimestamp)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("the clock is injected and pinned, so no assertion here can read an ambient clock")
        void theClockIsFixedAndExplicit() {
            final Clock canonical = FixedClockProvider.canonicalClock();
            final var firstRead = canonical.instant();
            final var secondRead = canonical.instant();

            assertThat(firstRead)
                    .as("pinned to the moment the fixture itself carries")
                    .isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
            assertThat(secondRead)
                    .as("the same instant on every read, which is what makes the renderings assertable")
                    .isEqualTo(firstRead);
            assertThat(canonical.getZone())
                    .as("an explicit zone, never the host default")
                    .isEqualTo(FixedClockProvider.CANONICAL_ZONE)
                    .isEqualTo(ZoneOffset.UTC);
            assertThat(FixedClockProvider.onlineTimestamp(
                    FixedClockProvider.fixedClock(FixedClockProvider.CANONICAL_INSTANT, ZoneOffset.UTC)))
                    .as("stating the zone at the call site yields the same rendering")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        }

        @Test
        @DisplayName("a sub-second component is discarded, not rounded, in both generated shapes")
        void subSecondPrecisionIsDiscarded() {
            final Clock justBeforeTheNextSecond = FixedClockProvider.fixedClock(
                    FixedClockProvider.CANONICAL_INSTANT.plusMillis(999L), ZoneOffset.UTC);

            assertThat(FixedClockProvider.onlineTimestamp(justBeforeTheNextSecond))
                    .as("the online shape keeps six literal zeros regardless of the fraction")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(FixedClockProvider.batchTimestamp(justBeforeTheNextSecond))
                    .as("DB2-MIL is hundredths and truncates: 999 milliseconds becomes 99")
                    .isEqualTo("2022-06-10-19.27.53.990000");
        }
    }

    @Nested
    @DisplayName("8. The date validation format is a COBOL picture handed to a routine, not a Java pattern")
    final class DateValidationFormatLiteral {

        @Test
        @DisplayName("the literal is the source literal, ten characters wide")
        void literalIsTheSourceLiteral() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT)
                    .as("WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD' at app/cbl/COTRN02C.cbl:60")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(10);
        }

        @Test
        @DisplayName("it is the width of the two date fields it validates")
        void literalFitsTheDateFields() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT.length())
                    .isEqualTo(COTRN02.widthOf("TORIGDTI"))
                    .isEqualTo(COTRN02.widthOf("TPROCDTI"));
        }

        @Test
        @DisplayName("used as a Java pattern it produces a plausible wrong answer rather than failing")
        void usedAsAJavaPatternItMisformats() {
            final LocalDate tenthOfJune = LocalDate.of(2022, 6, 10);
            final String misformatted = DateTimeFormatter
                    .ofPattern(TransactionAddRequest.DATE_VALIDATION_FORMAT, Locale.ROOT)
                    .format(tenthOfJune);

            assertThat(misformatted)
                    .as("Y is week-based-year and D is day-of-year, so the tenth of June becomes day 161")
                    .isEqualTo("2022-06-161")
                    .isNotEqualTo("2022-06-10");
            assertThat(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).format(tenthOfJune))
                    .as("the equivalent Java pattern is lower case, which is why the two must not be conflated")
                    .isEqualTo("2022-06-10");
        }

        @Test
        @DisplayName("the literal is passed as data, so it differs from the Java pattern that means the same")
        void literalIsNotTheJavaPattern() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT)
                    .as("MOVE WS-DATE-FORMAT TO CSUTLDTC-DATE-FORMAT at app/cbl/COTRN02C.cbl:390 moves text")
                    .isNotEqualTo("yyyy-MM-dd")
                    .isEqualTo("YYYY-MM-DD".toUpperCase(Locale.ROOT));
        }
    }

    @Nested
    @DisplayName("9. The racy descending-browse identifier, retained")
    final class IdentifierGeneration {

        @Test
        @DisplayName("the map declares no transaction-identifier field, so the record carries none")
        void noClientSuppliedIdentifier() {
            assertThat(COTRN02.declares("TRNIDI"))
                    .as("app/cpy-bms/COTRN02.CPY declares no transaction-identifier input field")
                    .isFalse();
            assertThat(COTRN02.declares("TRNIDINI"))
                    .as("that spelling belongs to app/cpy-bms/COTRN01.CPY and is not borrowed here")
                    .isFalse();
            assertThat(componentNames())
                    .as("generation belongs to the service tier, never to the payload")
                    .doesNotContain("transactionId", "tranId", "id");
            assertThat(componentNames())
                    .noneMatch(name -> "transactionid".equals(name.toLowerCase(Locale.ROOT)));
        }

        @Test
        @DisplayName("an empty file yields 1, because end of file leaves zeros in the key")
        void firstIdentifierOnAnEmptyFileIsOne() {
            assertThat(nextTransactionId(END_OF_FILE_KEY))
                    .as("MOVE ZEROS TO TRAN-ID at app/cbl/COBIL00C.cbl:488, then ADD 1 at COTRN02C.cbl:449")
                    .isEqualTo("0000000000000001")
                    .hasSize(TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName("the identifier is a sixteen-character zero-padded String, so a leading zero survives")
        void identifierKeepsItsLeadingZeros() {
            final String next = nextTransactionId("0000000000683580");

            assertThat(next)
                    .as("WS-TRAN-ID-N PIC 9(16) at app/cbl/COTRN02C.cbl:57")
                    .isEqualTo("0000000000683581")
                    .hasSize(TRANSACTION_ID_WIDTH)
                    .startsWith("0");
            assertThat(strictNumval(next))
                    .as("still all digits, so it satisfies the class test wherever it is re-read")
                    .isPresent();
            assertThat(new BigDecimal(next).toBigInteger().toString())
                    .as("a numeric type would render 683581 and break byte-exact comparison")
                    .isEqualTo("683581")
                    .isNotEqualTo(next);
        }

        @Test
        @DisplayName("the increment is a plain add one, with no gap and no reservation")
        void incrementIsPlainAddOne() {
            assertThat(nextTransactionId("0000000000000001")).isEqualTo("0000000000000002");
            assertThat(nextTransactionId("0000000000000009")).isEqualTo("0000000000000010");
            assertThat(nextTransactionId("0000000000000099")).isEqualTo("0000000000000100");
            assertThat(nextTransactionId("9999999999999998"))
                    .as("the width is used to its last digit before the field would overflow")
                    .isEqualTo("9999999999999999");
        }

        @Test
        @DisplayName("two concurrent callers reading one key collide")
        void theAlgorithmIsRacyByDesign() {
            final String observedHighestKey = "0000000000683580";
            final String firstCaller = nextTransactionId(observedHighestKey);
            final String secondCaller = nextTransactionId(observedHighestKey);

            assertThat(firstCaller)
                    .as("both callers browse before either writes, so both derive the same identifier")
                    .isEqualTo(secondCaller);
            assertThat(firstCaller)
                    .as("the collision is surfaced by the primary-key constraint, never masked by an upsert")
                    .isEqualTo("0000000000683581");
        }

        @Test
        @DisplayName("an interest-run identifier is numerically large and would dominate the browse")
        void interestIdentifiersDominateTheBrowse() {
            final String dateParameter = "2022061000";
            final String suffix = "000001";
            final String interestIdentifier = dateParameter + suffix;

            assertThat(dateParameter)
                    .as("PARM-DATE is eight date digits followed by two zeros, app/cbl/CBACT04C.cbl:473-516")
                    .hasSize(10);
            assertThat(interestIdentifier)
                    .as("STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID")
                    .hasSize(TRANSACTION_ID_WIDTH);
            assertThat(new BigDecimal(interestIdentifier))
                    .as("once an interest run has occurred it sits above any online identifier")
                    .isGreaterThan(new BigDecimal("0000000000683581"));
        }
    }

    @Nested
    @DisplayName("10. The diagnostic rendering publishes two non-sensitive fields and nothing else")
    final class SecurityPosture {

        @Test
        @DisplayName("the rendering is exactly the account identifier and the originating program name")
        void renderingIsTwoFieldsOnly() {
            assertThat(populated().toString())
                    .isEqualTo("TransactionAddRequest[accountId=00000000001, programName=COTRN02C]");
        }

        @Test
        @DisplayName("the card number is omitted entirely, not masked and not truncated")
        void cardNumberNeverReachesTheRendering() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .as("cardNumber is a primary account number and is never emitted")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER);
            assertThat(rendered)
                    .as("nor a last-four remnant, nor a masked placeholder derived from it")
                    .doesNotContain("6666")
                    .doesNotContain("9999")
                    .doesNotContain("*");
        }

        @Test
        @DisplayName("the merchant name, city and postal code are withheld as well")
        void merchantDetailsAreWithheld() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("ACME SUPERMARKET")
                    .doesNotContain("NEW YORK")
                    .doesNotContain("10001-1234");
        }

        @Test
        @DisplayName("no other populated component reaches the rendering either")
        void noOtherComponentIsRendered() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("GROCERY PURCHASE")
                    .doesNotContain("+00001234.56")
                    .doesNotContain("POS TERM")
                    .doesNotContain("2022-06-10")
                    .doesNotContain("800000000");
        }

        @Test
        @DisplayName("the rendering carries absence through without inventing a value for it")
        void absenceIsRenderedAsAbsence() {
            assertThat(absent().toString())
                    .as("rendered exactly as received, with no normalisation to blank or to zero")
                    .isEqualTo("TransactionAddRequest[accountId=null, programName=null]");
        }

        @Test
        @DisplayName("the type declares no credential, no identity document and no contact component")
        void noCredentialOrPersonalDataComponent() {
            final List<String> forbiddenTokens = List.of("password", "passwd", "pwd", "secret", "token",
                    "hash", "credential", "ssn", "socialsecurity", "govt", "phone", "dateofbirth", "dob",
                    "birth", "fico", "creditscore");

            for (final String component : componentNames()) {
                final String lowerCased = component.toLowerCase(Locale.ROOT);
                for (final String forbidden : forbiddenTokens) {
                    assertThat(lowerCased)
                            .as("component %s must not carry a %s", component, forbidden)
                            .doesNotContain(forbidden);
                }
            }
        }

        @Test
        @DisplayName("the record is not Java-serializable, so it is no insecure-deserialization surface")
        void recordIsNotSerializable() {
            assertThat(java.io.Serializable.class.isAssignableFrom(TransactionAddRequest.class))
                    .as("Rule 1 clause D flags insecure deserialization as a risky pattern")
                    .isFalse();
        }

        @Test
        @DisplayName("generated equality covers every component, so no component is silently excluded")
        void generatedEqualityCoversEveryComponent() {
            final TransactionAddRequest baseline = populated();
            final TransactionAddRequest differentCardOnly = new TransactionAddRequest("CT02",
                    "CardDemo Add Transaction", "08/01/26", "COTRN02C", "Add Transaction", "14:22:31",
                    FIRST_SEEDED_ACCOUNT_ID, "9999888877776667", "01", "0001", "POS TERM  ",
                    "GROCERY PURCHASE", "+00001234.56", "2022-06-10", "2022-06-11", "800000000",
                    "ACME SUPERMARKET", "NEW YORK", "10001-1234", "Y", "");

            assertThat(baseline).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(baseline)
                    .as("the card number participates in equality; only the rendering withholds it")
                    .isNotEqualTo(differentCardOnly);
        }
    }

    @Nested
    @DisplayName("11. Absent, blank and marked are three distinct states")
    final class TriStateBoundaries {

        @Test
        @DisplayName("absent, empty, blank and marked are four distinguishable values on one component")
        void fourStatesStayDistinguishable() {
            assertThat(componentValue(withOnly("amount", null), "amount"))
                    .as("FLG-...-OK with nothing supplied at all")
                    .isNull();
            assertThat(componentValue(withOnly("amount", ""), "amount"))
                    .as("the empty string is never coerced to null")
                    .isNotNull()
                    .isEmpty();
            assertThat(componentValue(withOnly("amount", " "), "amount"))
                    .as("FLG-...-BLANK, which app/cpy/CSSETATY.cpy treats as its own state")
                    .isEqualTo(" ");
            assertThat(componentValue(withOnly("amount", "*"), "amount"))
                    .as("the asterisk CSSETATY stamps on a blank field, on re-entry only")
                    .isEqualTo("*");
        }

        @Test
        @DisplayName("blank and invalid are distinct states, exactly as the source's two messages are")
        void blankAndInvalidAreDistinctStates() {
            final String blankAmount = " ".repeat(COTRN02.widthOf("TRNAMTI"));
            final String invalidAmount = "not a number";

            assertThat(violationsOf(withOnly("amount", blankAmount)))
                    .as("a blank field is within its width, so bean validation raises nothing")
                    .isEmpty();
            assertThat(tolerantNumvalC(blankAmount))
                    .as("'Credit Limit must be supplied' at app/cbl/COACTUPC.cbl:505-506 is the BLANK state")
                    .isEmpty();
            assertThat(tolerantNumvalC(invalidAmount))
                    .as("'Credit Limit is not valid' at app/cbl/COACTUPC.cbl:507-508 is the NOT-OK state")
                    .isEmpty();
            assertThat(blankAmount)
                    .as("two distinct inputs reaching one refusal must stay distinguishable upstream")
                    .isNotEqualTo(invalidAmount);
        }

        @Test
        @DisplayName("no component is normalised on ingest: no trim, no case folding, no padding")
        void noComponentIsNormalisedOnIngest() {
            final String untidy = "  MiXeD case  ";

            assertThat(componentValue(withOnly("description", untidy), "description")).isEqualTo(untidy);
            assertThat(componentValue(withOnly("merchantName", untidy), "merchantName")).isEqualTo(untidy);
            assertThat(componentValue(withOnly("errorMessage", untidy), "errorMessage")).isEqualTo(untidy);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.TransactionAddRequestTest#fieldComponentPairs")
        @DisplayName("every component accepts null, empty, blank and a value one under its ceiling")
        void everyComponentAcceptsAbsentBlankAndOneUnder(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int ceiling = COTRN02.widthOf(field);

            assertThat(violationsOf(withOnly(component, null)))
                    .as("%s absent", component).isEmpty();
            assertThat(violationsOf(withOnly(component, "")))
                    .as("%s empty", component).isEmpty();
            assertThat(violationsOf(withOnly(component, repeat(' ', ceiling))))
                    .as("%s blank to its full width", component).isEmpty();
            if (ceiling > 1) {
                assertThat(violationsOf(withOnly(component, repeat('X', ceiling - 1))))
                        .as("%s one character under the ceiling of %d", component, Integer.valueOf(ceiling))
                        .isEmpty();
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.TransactionAddRequestTest#fieldComponentPairs")
        @DisplayName("every component accepts exactly its ceiling and refuses one character more")
        void everyComponentRefusesOneOver(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int ceiling = COTRN02.widthOf(field);

            final String atCeiling = repeat('X', ceiling);
            final String oneOver = repeat('X', ceiling + 1);

            assertThat(violationsOf(withOnly(component, atCeiling)))
                    .as("%s at exactly the copybook width of %d", component, Integer.valueOf(ceiling))
                    .isEmpty();
            assertThat(violationsOf(withOnly(component, oneOver)))
                    .as("%s one character over the ceiling of %d", component, Integer.valueOf(ceiling))
                    .hasSize(1)
                    .allSatisfy(violation -> {
                        assertThat(violation.getPropertyPath().toString()).isEqualTo(component);
                        assertThat(violation.getMessage())
                                .as("the message names the component and quotes the offending value nowhere,"
                                        + " which is what keeps an over-length card number out of a log")
                                .contains(component)
                                .doesNotContain(oneOver);
                    });
        }

        @Test
        @DisplayName("trailing-space padding is preserved at the ceiling, not silently stripped")
        void trailingPaddingIsPreserved() {
            final String paddedToWidth = "POS TERM" + repeat(' ', COTRN02.widthOf("TRNSRCI") - 8);

            assertThat(paddedToWidth).hasSize(COTRN02.widthOf("TRNSRCI"));
            assertThat(componentValue(withOnly("source", paddedToWidth), "source"))
                    .isEqualTo(paddedToWidth)
                    .hasSize(COTRN02.widthOf("TRNSRCI"));
            assertThat(violationsOf(withOnly("source", paddedToWidth))).isEmpty();
        }

        @Test
        @DisplayName("no class-level constraint models a gated cross-field edit")
        void noClassLevelCrossFieldConstraint() {
            assertThat(TransactionAddRequest.class.getAnnotation(AssertTrue.class))
                    .as("app/cbl/COACTUPC.cbl:1667-1672 runs its cross-field edit only when both single-field"
                            + " edits already passed, so an unconditional class constraint would fire in cases"
                            + " the source never reaches and would produce a different message set")
                    .isNull();
            for (final String component : componentNames()) {
                assertThat(RecordFieldContract.declares(TransactionAddRequest.class, component,
                        AssertTrue.class))
                        .as("%s declares no @AssertTrue either", component)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the confirmation gate keeps five inputs apart rather than collapsing to a boolean")
        void confirmationIsNotABoolean() {
            assertThat(COTRN02.widthOf("CONFIRMI"))
                    .as("CONFIRMI PIC X(1) at app/cpy-bms/COTRN02.CPY:138")
                    .isEqualTo(1);
            assertThat(componentValue(withOnly("confirmation", null), "confirmation")).isNull();
            assertThat(componentValue(withOnly("confirmation", ""), "confirmation")).isEmpty();
            assertThat(componentValue(withOnly("confirmation", "Y"), "confirmation")).isEqualTo("Y");
            assertThat(componentValue(withOnly("confirmation", "N"), "confirmation")).isEqualTo("N");
            assertThat(componentValue(withOnly("confirmation", "Q"), "confirmation"))
                    .as("an invalid character is its own state with its own message and cursor behaviour")
                    .isEqualTo("Q");
        }
    }

    @Nested
    @DisplayName("12. No server-side session state survives the COMMAREA")
    final class NoServerSideSessionState {

        @Test
        @DisplayName("no routing component survives CDEMO-FROM-TRANID and its three siblings")
        void noRoutingComponents() {
            assertThat(componentNames())
                    .as("routing is URL-based, so app/cpy/COCOM01Y.cpy's transfer fields have no counterpart")
                    .doesNotContain("fromTranId", "toTranId", "fromProgram", "toProgram");
        }

        @Test
        @DisplayName("no re-entry flag survives CDEMO-PGM-CONTEXT")
        void noReEntryFlag() {
            for (final String component : componentNames()) {
                final String lowerCased = component.toLowerCase(Locale.ROOT);
                assertThat(lowerCased)
                        .as("component %s carries no pseudo-conversational context", component)
                        .doesNotContain("reenter")
                        .doesNotContain("pgmcontext")
                        .doesNotContain("context");
            }
        }

        @Test
        @DisplayName("no screen-state component survives CDEMO-LAST-MAP or CDEMO-LAST-MAPSET")
        void noScreenStateComponents() {
            assertThat(componentNames())
                    .as("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are both PIC X(7) at app/cpy/COCOM01Y.cpy:43-44,"
                            + " not X(8); neither width matters here because neither field is carried at all")
                    .doesNotContain("lastMap", "lastMapset", "mapName", "mapsetName");
        }

        @Test
        @DisplayName("no pagination component appears: page state travels as request parameters")
        void noPaginationComponents() {
            for (final String component : componentNames()) {
                final String lowerCased = component.toLowerCase(Locale.ROOT);
                assertThat(lowerCased)
                        .as("component %s carries no paging state", component)
                        .doesNotContain("page")
                        .doesNotContain("nextpage")
                        .doesNotContain("pageno");
            }
        }

        @Test
        @DisplayName("every component present is a screen field the map itself declares")
        void everyComponentTracesToTheMap() {
            final List<String> componentsInTheMapping = new ArrayList<>();
            for (int index = 1; index < FIELD_TO_COMPONENT.size(); index += 2) {
                componentsInTheMapping.add(FIELD_TO_COMPONENT.get(index));
            }

            assertThat(componentNames())
                    .as("nothing is carried that COTRN2AI does not declare")
                    .containsExactlyElementsOf(componentsInTheMapping);
        }
    }

    @Nested
    @DisplayName("13. Parity against app/data/ASCII/dailytran.txt, 300 records of 350 characters")
    final class FixtureParity {

        /**
         * Loads the daily-transaction fixture.
         *
         * <p>Loaded per test rather than cached in a static field, because Rule 1 clause B forbids global
         * mutable state and a shared snapshot would couple the order these tests run in.
         *
         * @return an immutable snapshot of all 300 records, trailing spaces intact
         */
        private FixtureLoader.FixtureData dailyTransactions() {
            return FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
        }

        @Test
        @DisplayName("the fixture geometry is intact, which is what a whitespace cleanup would destroy")
        void fixtureGeometryIsIntact() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();

            assertThat(fixture.resourceName())
                    .as("the actual name spells daily in full; dalytran.txt does not exist")
                    .isEqualTo("dailytran.txt");
            assertThat(fixture.recordCount()).isEqualTo(300);
            assertThat(fixture.recordWidth()).isEqualTo(350);
            assertThat(fixture.byteCount())
                    .as("300 records of 350 characters plus one line feed each")
                    .isEqualTo(105_300)
                    .isEqualTo(fixture.impliedByteCount());
            for (int row = 0; row < fixture.recordCount(); row++) {
                assertThat(fixture.recordAt(row))
                        .as("record %d retains its full fixed width, trailing spaces included",
                                Integer.valueOf(row))
                        .hasSize(350);
            }
        }

        @Test
        @DisplayName("every row's screen-relevant slices reach the record unaltered")
        void everyRowRoundTripsThroughTheRecord() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final TransactionAddRequest request = fromFixtureRow(fixture, row);

                assertThat(request.typeCode())
                        .as("row %d type code", Integer.valueOf(row))
                        .isEqualTo(fixture.field(row, TYPE_CODE_COLUMN, COTRN02.widthOf("TTYPCDI")));
                assertThat(request.categoryCode())
                        .as("row %d category code", Integer.valueOf(row))
                        .isEqualTo(fixture.field(row, CATEGORY_CODE_COLUMN, COTRN02.widthOf("TCATCDI")));
                assertThat(request.source())
                        .as("row %d source", Integer.valueOf(row))
                        .isEqualTo(fixture.field(row, SOURCE_COLUMN, COTRN02.widthOf("TRNSRCI")));
                assertThat(request.merchantId())
                        .as("row %d merchant identifier", Integer.valueOf(row))
                        .isEqualTo(fixture.field(row, MERCHANT_ID_COLUMN, COTRN02.widthOf("MIDI")));
                assertThat(violationsOf(request))
                        .as("row %d raises no width violation", Integer.valueOf(row))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the screen widths are narrower than the record widths, and truncation is on the right")
        void screenWidthsTruncateTheWiderRecordFields() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();

            assertThat(COTRN02.widthOf("TDESCI"))
                    .as("TDESCI X(60) against TRAN-DESC X(100) at app/cpy/CVTRA05Y.cpy:9")
                    .isEqualTo(60)
                    .isLessThan(100);
            assertThat(COTRN02.widthOf("MNAMEI"))
                    .as("MNAMEI X(30) against TRAN-MERCHANT-NAME X(50) at app/cpy/CVTRA05Y.cpy:12")
                    .isEqualTo(30)
                    .isLessThan(50);
            assertThat(COTRN02.widthOf("MCITYI"))
                    .as("MCITYI X(25) against TRAN-MERCHANT-CITY X(50) at app/cpy/CVTRA05Y.cpy:13")
                    .isEqualTo(25)
                    .isLessThan(50);

            final String recordDescription = fixture.field(0, DESCRIPTION_COLUMN, 100);
            final String screenDescription = fromFixtureRow(fixture, 0).description();

            assertThat(screenDescription)
                    .as("a MOVE into the narrower screen field keeps the leading characters")
                    .isEqualTo(recordDescription.substring(0, COTRN02.widthOf("TDESCI")))
                    .hasSize(COTRN02.widthOf("TDESCI"));
        }

        @Test
        @DisplayName("the fixture partitions perfectly: 250 positive card sales and 50 negative operator rows")
        void fixturePartitionsIntoCreditsAndDebits() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            int positiveCardSales = 0;
            int negativeOperatorAdjustments = 0;

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String typeCode = fixture.field(row, TYPE_CODE_COLUMN, COTRN02.widthOf("TTYPCDI"));
                final String source = fixture.field(row, SOURCE_COLUMN, COTRN02.widthOf("TRNSRCI"));
                final BigDecimal amount =
                        fixture.signedDecimal(row, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);

                assertThat(amount)
                        .as("row %d carries no zero amount", Integer.valueOf(row))
                        .isNotEqualByComparingTo(BigDecimal.ZERO);
                if ("01".equals(typeCode)) {
                    assertThat(source).as("row %d", Integer.valueOf(row)).isEqualTo("POS TERM  ");
                    assertThat(amount).as("row %d", Integer.valueOf(row)).isPositive();
                    positiveCardSales++;
                } else {
                    assertThat(typeCode).as("row %d", Integer.valueOf(row)).isEqualTo("03");
                    assertThat(source).as("row %d", Integer.valueOf(row)).isEqualTo("OPERATOR  ");
                    assertThat(amount).as("row %d", Integer.valueOf(row)).isNegative();
                    negativeOperatorAdjustments++;
                }
            }

            assertThat(positiveCardSales).isEqualTo(250);
            assertThat(negativeOperatorAdjustments)
                    .as("the negative rows are what exercise the cycle-debit branch, so no abs() is permitted")
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("overpunch decoding is position-aware, driven by the PIC width alone")
        void overpunchDecodingIsPositionAware() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            final Set<Character> overpunchCodes = new TreeSet<>();
            BigDecimal smallest = FixtureLoader.decodeZonedDecimal("0000000000{",
                    FixtureLoader.DECIMAL_SCALE);
            BigDecimal largest = smallest;

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String rawAmount =
                        fixture.field(row, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
                overpunchCodes.add(Character.valueOf(rawAmount.charAt(rawAmount.length() - 1)));

                final BigDecimal decoded =
                        fixture.signedDecimal(row, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
                smallest = smallest.min(decoded);
                largest = largest.max(decoded);
            }

            assertThat(overpunchCodes)
                    .as("all twenty codes are exercised: { and A-I positive, } and J-R negative")
                    .hasSize(20)
                    .containsExactly('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N',
                            'O', 'P', 'Q', 'R', '{', '}');
            assertThat(smallest).isEqualByComparingTo(new BigDecimal("-998.33"));
            assertThat(largest).isEqualByComparingTo(new BigDecimal("999.77"));
        }

        @Test
        @DisplayName("a decode at the wrong width reads a neighbouring column as the sign")
        void aWrongWidthDecodeReadsTheWrongSign() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            final BigDecimal correct =
                    fixture.signedDecimal(0, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
            final BigDecimal offByOne =
                    fixture.signedDecimal(0, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH - 1);

            assertThat(correct)
                    .as("TRAN-AMT PIC S9(09)V99 is eleven characters, so the overpunch is the eleventh")
                    .isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(offByOne)
                    .as("reading ten characters treats a digit as the sign and yields a different value")
                    .isNotEqualByComparingTo(correct);
        }

        @Test
        @DisplayName("letters inside the description are not overpunch and are untouched")
        void lettersInsideTextAreNotOverpunch() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            final String description = fromFixtureRow(fixture, 0).description();

            assertThat(description)
                    .as("the description legitimately holds letters in the A-R range")
                    .containsPattern("[A-R]");
            assertThat(description)
                    .as("a global overpunch substitution would corrupt every one of them")
                    .startsWith("Purchase at ");
        }

        @Test
        @DisplayName("a fixture amount survives conversion and the masked echo the source performs")
        void fixtureAmountSurvivesTheMaskedEcho() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final BigDecimal decoded =
                        fixture.signedDecimal(row, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
                final String masked = renderOnEditedMask(decoded);

                assertThat(masked)
                        .as("row %d renders on the twelve-character mask", Integer.valueOf(row))
                        .hasSize(COTRN02.widthOf("TRNAMTI"));
                assertThat(tolerantNumvalC(masked))
                        .as("row %d is re-readable by the tolerant parser at :456-457", Integer.valueOf(row))
                        .isPresent();
                assertThat(tolerantNumvalC(masked).orElseThrow())
                        .as("row %d survives the echo intact, because it is far below the mask ceiling",
                                Integer.valueOf(row))
                        .isEqualByComparingTo(decoded);
            }
        }

        @Test
        @DisplayName("the originating timestamp is one distinct value on all 300 rows, and it is the fixed one")
        void originatingTimestampMatchesTheFixedClock() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            final Set<String> distinctTimestamps = new LinkedHashSet<>();

            for (int row = 0; row < fixture.recordCount(); row++) {
                distinctTimestamps.add(fixture.field(row, ORIGINATING_TIMESTAMP_COLUMN,
                        FixedClockProvider.TIMESTAMP_LENGTH));
            }

            assertThat(distinctTimestamps)
                    .as("TRAN-ORIG-TS at columns 279-304 of every record")
                    .containsExactly(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock()))
                    .as("which is exactly what the injected fixed clock renders, with no call to now()")
                    .isEqualTo(distinctTimestamps.iterator().next());
        }

        @Test
        @DisplayName("the processing timestamp is 26 blanks on all 300 rows and round-trips as such")
        void processingTimestampIsBlankOnEveryRow() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            final String blank = " ".repeat(FixedClockProvider.TIMESTAMP_LENGTH);

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String held = fixture.field(row, PROCESSING_TIMESTAMP_COLUMN,
                        FixedClockProvider.TIMESTAMP_LENGTH);

                assertThat(held).as("row %d processing timestamp", Integer.valueOf(row)).isEqualTo(blank);
                assertThat(FixedClockProvider.passThroughTimestamp(held))
                        .as("row %d survives the pass-through move at :465", Integer.valueOf(row))
                        .isEqualTo(blank);
            }
        }

        @Test
        @DisplayName("every card number resolves against the cross-reference fixture, with no orphan")
        void everyCardNumberResolves() {
            final FixtureLoader.FixtureData transactions = dailyTransactions();
            final FixtureLoader.FixtureData crossReference =
                    FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
            final Set<String> knownCards = new LinkedHashSet<>();
            final Set<String> usedCards = new LinkedHashSet<>();

            for (int row = 0; row < crossReference.recordCount(); row++) {
                knownCards.add(crossReference.field(row, 1, COTRN02.widthOf("CARDNINI")));
            }
            for (int row = 0; row < transactions.recordCount(); row++) {
                final String card = transactions.field(row, CARD_NUMBER_COLUMN,
                        COTRN02.widthOf("CARDNINI"));
                usedCards.add(card);
                assertThat(knownCards.contains(card))
                        .as("row %d references a card the cross reference declares", Integer.valueOf(row))
                        .isTrue();
            }

            assertThat(usedCards).as("fifty distinct card numbers across the three hundred rows").hasSize(50);
            assertThat(knownCards).hasSize(50);
        }

        @Test
        @DisplayName("every transaction identifier in the fixture is distinct and sixteen digits wide")
        void fixtureIdentifiersAreDistinctAndDigitsOnly() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            final Set<String> identifiers = new LinkedHashSet<>();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String identifier = fixture.field(row, 1, TRANSACTION_ID_WIDTH);

                assertThat(strictNumval(identifier))
                        .as("row %d identifier passes the strict class test", Integer.valueOf(row))
                        .isPresent();
                identifiers.add(identifier);
            }

            assertThat(identifiers).hasSize(fixture.recordCount()).hasSize(300);
        }

        @Test
        @DisplayName("the merchant postal codes mix five-digit and ZIP+4 shapes, so no shape may be enforced")
        void merchantZipCodesMixTwoShapes() {
            final FixtureLoader.FixtureData fixture = dailyTransactions();
            int fiveDigitPadded = 0;
            int zipPlusFour = 0;

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String zip = fixture.field(row, MERCHANT_ZIP_COLUMN, COTRN02.widthOf("MZIPI"));

                assertThat(violationsOf(withOnly("merchantZip", zip)))
                        .as("row %d postal code is within MZIPI PIC X(10)", Integer.valueOf(row))
                        .isEmpty();
                if (zip.indexOf('-') >= 0) {
                    zipPlusFour++;
                } else {
                    fiveDigitPadded++;
                }
            }

            assertThat(fiveDigitPadded)
                    .as("a digits-only or fixed-length constraint would reject one shape or the other")
                    .isPositive();
            assertThat(zipPlusFour).isPositive();
            assertThat(fiveDigitPadded + zipPlusFour).isEqualTo(300);
        }

        /**
         * Projects one fixture record onto the transaction-add screen, slicing each field at its
         * <em>screen</em> width rather than its record width.
         *
         * <p>Three fields are wider in the record than on the screen - the description, the merchant name and
         * the merchant city - so the projection truncates on the right exactly as a COBOL {@code MOVE} into a
         * narrower alphanumeric item does. The dates carry the first ten characters of the two 26-character
         * timestamps, which is the width {@code TORIGDTI} and {@code TPROCDTI} declare.
         *
         * <p>Reads only; the fixture snapshot is immutable and nothing is written anywhere.
         *
         * @param fixture the loaded snapshot
         * @param row     the zero-based record index
         * @return the projected payload, never {@code null}
         */
        private TransactionAddRequest fromFixtureRow(final FixtureLoader.FixtureData fixture, final int row) {
            return new TransactionAddRequest("CT02", "CardDemo Add Transaction", "08/01/26", "COTRN02C",
                    "Add Transaction", "14:22:31", null,
                    fixture.field(row, CARD_NUMBER_COLUMN, COTRN02.widthOf("CARDNINI")),
                    fixture.field(row, TYPE_CODE_COLUMN, COTRN02.widthOf("TTYPCDI")),
                    fixture.field(row, CATEGORY_CODE_COLUMN, COTRN02.widthOf("TCATCDI")),
                    fixture.field(row, SOURCE_COLUMN, COTRN02.widthOf("TRNSRCI")),
                    fixture.field(row, DESCRIPTION_COLUMN, COTRN02.widthOf("TDESCI")),
                    renderOnEditedMask(
                            fixture.signedDecimal(row, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH)),
                    fixture.field(row, ORIGINATING_TIMESTAMP_COLUMN, COTRN02.widthOf("TORIGDTI")),
                    fixture.field(row, PROCESSING_TIMESTAMP_COLUMN, COTRN02.widthOf("TPROCDTI")),
                    fixture.field(row, MERCHANT_ID_COLUMN, COTRN02.widthOf("MIDI")),
                    fixture.field(row, MERCHANT_NAME_COLUMN, COTRN02.widthOf("MNAMEI")),
                    fixture.field(row, MERCHANT_CITY_COLUMN, COTRN02.widthOf("MCITYI")),
                    fixture.field(row, MERCHANT_ZIP_COLUMN, COTRN02.widthOf("MZIPI")), "Y", "");
        }
    }

    @Nested
    @DisplayName("14. Diagnostics: a wrong offset fails loudly, with context, a root cause and no content")
    final class DiagnosticContract {

        @Test
        @DisplayName("decoding a text column as a zoned decimal wraps the root cause rather than losing it")
        void aWrongColumnWrapsAndPreservesTheRootCause() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThatThrownBy(() -> fixture.signedDecimal(0, DESCRIPTION_COLUMN,
                    FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .as("the description is text, so it cannot decode; a silent wrong number would be worse")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dailytran.txt")
                    .hasMessageContaining("record 0")
                    .hasMessageContaining("columns " + DESCRIPTION_COLUMN)
                    .cause()
                    .as("the root cause is preserved, never swallowed and never replaced")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digit");
        }

        @Test
        @DisplayName("a slice running past the record width reports offsets and widths, never content")
        void anOutOfBoundsSliceQuotesNoContent() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final String cardNumberOnRowZero =
                    fixture.field(0, CARD_NUMBER_COLUMN, COTRN02.widthOf("CARDNINI"));

            assertThatThrownBy(() -> fixture.field(0, CARD_NUMBER_COLUMN, 200))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runs past")
                    .hasMessageContaining("350-character record 0")
                    .as("the diagnostic names offsets and widths, so no card number can reach a log")
                    .hasMessageNotContaining(cardNumberOnRowZero);
        }

        @Test
        @DisplayName("a record index outside the fixture is refused with a count, not with a record")
        void anOutOfRangeRowQuotesNoRecord() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThatThrownBy(() -> fixture.recordAt(fixture.recordCount()))
                    .isInstanceOf(IndexOutOfBoundsException.class)
                    .hasMessageContaining("300 records")
                    .hasMessageNotContaining("POS TERM");
        }

        @Test
        @DisplayName("the identifier generator refuses a malformed key with context and without the key")
        void theIdentifierGeneratorRefusesAMalformedKey() {
            assertThatThrownBy(() -> nextTransactionId(SYNTHETIC_CARD_NUMBER.substring(0, 3)))
                    .as("a PIC 9(16) field cannot have yielded a three-character value")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("PIC 9(16)")
                    .hasMessageContaining("3-character")
                    .hasMessageNotContaining(SYNTHETIC_CARD_NUMBER.substring(0, 3));
            assertThatThrownBy(() -> nextTransactionId(null))
                    .as("absence is reported as absence rather than read as zero")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("a missing component name is refused by name, listing the contract it violated")
        void anUnknownComponentIsRefusedByName() {
            assertThatThrownBy(() -> withOnly("transactionIdentifier", "1"))
                    .as("the map declares no such field, and the refusal says which 21 it does declare")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("transactionIdentifier")
                    .hasMessageContaining("accountId");
        }
    }

    /**
     * The diagnostic rendering may not be turned into a forged log record.
     *
     * <p>Every component {@code toString()} emits is declared {@code String} and arrives from a JSON request
     * body, so a caller controls its bytes: concatenated straight in, a CR or LF forges as many further log
     * lines as the caller likes, in the exact shape a reader trusts. {@code @Size} and {@code @Pattern} run
     * <em>after</em> Jackson has constructed the record, and a validation failure is precisely the occasion
     * on which something renders the offending instance, so these tests build hostile values directly and
     * never validate them first.
     */
    @Nested
    @DisplayName("the diagnostic rendering cannot forge a log record")
    class HostileDiagnosticRendering {

        @ParameterizedTest(name = "a CR/LF payload in {0} cannot break the record")
        @ValueSource(strings = {"accountId", "programName"})
        @DisplayName("a control character in any rendered component is escaped, not emitted")
        void aControlCharacterInAnyRenderedComponentIsEscaped(final String component) {
            final String hostile = "AAA\r\n2026-08-04 INFO forged FORGED-RECORD";

            final String rendered = withOnly(component, hostile).toString();

            assertThat(rendered)
                    .as("the raw terminators must be gone, or the rendering is one log record per attacker "
                            + "newline rather than one per event")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(rendered.lines().count())
                    .as("and the whole rendering must remain exactly one line")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the escaped payload is still legible, so the evidence survives neutralisation")
        void theEscapedPayloadRemainsLegible() {
            final String rendered = withOnly("accountId", "AAA\r\nFORGED-RECORD").toString();

            assertThat(rendered)
                    .as("a reader investigating a hostile request needs to see what arrived; escaping the "
                            + "terminator must not discard the value around it")
                    .contains("FORGED-RECORD")
                    .contains("\\u000D")
                    .contains("\\u000A");
        }

        @Test
        @DisplayName("an over-long component is bounded, so one field cannot flood the record")
        void anOverLongComponentIsBounded() {
            final String rendered = withOnly("accountId", "q".repeat(400)).toString();

            assertThat(rendered)
                    .as("the length constraints have not run on an instance being rendered because it failed "
                            + "them, so the rendering bounds the value itself")
                    .contains("chars)")
                    .hasSizeLessThan(600);
        }

        @Test
        @DisplayName("a benign instance renders unchanged, so the guard is invisible in normal use")
        void aBenignInstanceRendersUnchanged() {
            assertThat(populated().toString())
                    .as("neutralisation must not alter what an ordinary log record says")
                    .doesNotContain("chars)")
                    .doesNotContain("\\u");
        }
    }

}
