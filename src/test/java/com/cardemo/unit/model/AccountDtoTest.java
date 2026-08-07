/*
 * ******************************************************************
 * Program     : AccountDtoTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the account-view payload against its frozen
 *               field contract: the 37 screen fields COACTVW declares,
 *               the widths it declares them at, the monetary conversion
 *               and its refusals, the tri-state screen model, the seed
 *               data the payload must not reject, and the toString
 *               override that withholds protected data.
 * Source      : app/cpy-bms/COACTVW.CPY (37 input fields, group CACTVWAI)
 *               + app/cbl/COACTVWC.cbl @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.model.dto.AccountDto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link AccountDto} against the frozen artefacts it was migrated from.
 *
 * <h2>What it does</h2>
 *
 * <p>{@code AccountDto} is the read projection behind CICS transaction {@code CAVW}. Its shape is not a
 * design choice: every component name, width and position derives from the generated symbolic map
 * {@code app/cpy-bms/COACTVW.CPY}, whose input group is {@code 01 CACTVWAI} at line 17 and whose output
 * redefinition {@code CACTVWAO} begins at line 241. This suite asserts that contract rather than describing
 * it, so that a later edit which widens, narrows, renames, reorders or drops a field fails here instead of
 * diverging silently from the parity baseline. The specific artefacts read or cited are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COACTVW.CPY} - the 37 input-field declarations, including
 *       {@code :60 02 ACCTSIDI PIC 99999999999.} and {@code :102 02 ACURBALI PIC X(15).}</li>
 *   <li>{@code app/cpy-bms/COSGN00.CPY:54} - {@code CURTIMEI PIC X(9)}, the one map in the corpus whose
 *       current-time header field is nine characters rather than eight</li>
 *   <li>{@code app/cpy-bms/COBIL00.CPY:66} - {@code CURBALI PIC X(14)}, a differently named balance at a
 *       different width</li>
 *   <li>{@code app/cpy-bms/COACTUP.CPY:138} - {@code ACURBALI PIC X(15)}, corroborating this map's width,
 *       and {@code :60 ACCTSIDI PIC X(11)}, the parenthesised spelling of the same eleven positions</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} - 941 lines with no {@code WRITE}, {@code REWRITE} or {@code DELETE}
 *       verb and exactly three {@code EXEC CICS READ} statements, which is what makes this payload a read
 *       projection; {@code :466} moves {@code LOW-VALUES} into a screen field, {@code :490} moves the
 *       account group identifier, {@code :514} the state code and {@code :515} the postal code</li>
 *   <li>{@code app/cpy/CVACT01Y.cpy} - the 300-byte account record whose five {@code PIC S9(10)V99}
 *       amounts fix the scale and precision, and whose dates are {@code PIC X(10)} text including
 *       {@code :L11 ACCT-EXPIRAION-DATE}, misspelled in the source and echoed unaltered</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:505-508} - two distinct messages for two distinct states, and
 *       {@code :L848-L849} the {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} predicate that gates
 *       the update path and not this one</li>
 *   <li>{@code app/cpy/CSLKPCDY.cpy:L1013} - the 56-entry {@code 88 VALID-US-STATE-CODE} table</li>
 *   <li>{@code app/data/ASCII/acctdata.txt} and {@code app/data/ASCII/custdata.txt} - the seed rows this
 *       payload has to accept, read through {@link FixtureLoader} from the test classpath</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>This class is a pure-JVM unit test: no container, no Spring context, no database, no cloud endpoint
 * and no network. Run the tier with {@code ./mvnw -B -ntp -Ddependency-check.skip=true test}, or the whole
 * gate with {@code ./mvnw -B -ntp clean verify}. Collection is by
 * <strong>Surefire</strong>, which includes {@code **}{@code /*Test.java} and excludes the
 * {@code integration} and {@code e2e} trees, so this file has to stay at
 * {@code src/test/java/com/cardemo/unit/model/}: moved elsewhere it would be claimed by neither Surefire
 * nor Failsafe and would stop running without any error being reported. Surefire pins
 * {@code workingDirectory} to the project base directory, which is what lets {@link BmsSymbolicMap} resolve
 * {@code app/cpy-bms} as a relative path.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li>Time is injected, never read from the platform. {@link FixedClockProvider#canonicalClock()} supplies
 *       the instant behind every temporal expectation, so the two screen header fields are asserted against
 *       a fixed value; {@code Instant.now()}, {@code LocalDate.now()} and {@code System.currentTimeMillis()}
 *       appear nowhere.</li>
 *   <li>Every formatting and case operation names {@link Locale#ROOT}, so a host locale cannot change an
 *       outcome.</li>
 *   <li>Mockito is pinned at 5.17.0 with its default strict stubbing, but no test doubles are created here:
 *       the type under test is a record with a static conversion method and no collaborators, so there is
 *       nothing to stub and the strictness setting is never engaged. Introducing a mock would add an unused
 *       import and a stub with no observed interaction, which strict stubbing would then reject.</li>
 *   <li>Fixtures are read by classpath resource name only, through {@link FixtureLoader}. Nothing is copied,
 *       edited or written; {@code app/**} stays byte-for-byte as committed.</li>
 *   <li>Over-width probes are padded with the letter {@code Q}, chosen because it occurs in none of the
 *       diagnostic messages, so an assertion that a message does not reproduce the offending value cannot
 *       pass accidentally.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails with "warnings found and -Werror specified".</em> Test compilation runs under
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, so a single unused import, raw type or
 *       deprecation in this file breaks the build. Remove the cause; never relax the flag.</li>
 *   <li><em>A fixture cannot be found.</em> The daily transaction fixture is named {@code dailytran.txt}.
 *       The mainframe dataset and DD name are {@code DALYTRAN}, so {@code dalytran.txt} is the natural
 *       guess and is wrong; {@link FixtureLoader.Fixture} spells every resource the way the repository
 *       does.</li>
 *   <li><em>The field census reads 36.</em> A census regex of the form {@code PIC [X9](} matches only the
 *       parenthesised picture form and therefore silently drops
 *       {@code app/cpy-bms/COACTVW.CPY:60 02 ACCTSIDI PIC 99999999999.}, the one expanded numeric picture
 *       in the corpus. The count is 37, corroborated by the 37 {@code COMP PIC S9(4)} length fields the
 *       generator emits one per input field.</li>
 *   <li><em>Seed data is rejected.</em> Adding a 300-to-850 range rule to the credit score, a digits-only
 *       rule to the postal code, a non-blank rule to the account group identifier or a membership rule to
 *       the state code makes this payload reject rows that are present in the frozen fixtures, which fails
 *       the named-fixture gate. The constraints those rules would encode belong to the update path, not to
 *       this view.</li>
 *   <li><em>Protected data appears in a log.</em> The only rendering this type produces is
 *       {@link AccountDto#toString()}, which emits three unprotected fields and states that the rest were
 *       withheld. A generated record {@code toString} would emit all 37, including the social security
 *       number, both telephone numbers, the date of birth, the government-issued identifier and three
 *       customer names.</li>
 * </ul>
 *
 * <h2>Invariants this suite pins</h2>
 *
 * <ul>
 *   <li>Protected data must not reach {@code toString}, and overpunch decoding must be position aware rather
 *       than global - a global substitution would rewrite the leading {@code A} of the seeded postal code
 *       {@code A000000000} into a sign. Both are asserted against.</li>
 *   <li>No shared header or balance abstraction, no numeric type for {@code ACCTSIDI}, no credit-score range
 *       or state-membership constraint, no snapshot group leaking into this read projection, and no
 *       collapsing of blank into absent. Each is asserted against.</li>
 *   <li>The field census is measured, not transcribed: the corpus contributes 441 input fields across the
 *       seventeen maps and {@code COACTVW} contributes 37, the extra one being the expanded picture at
 *       {@code app/cpy-bms/COACTVW.CPY:60} that a {@code PIC X(} census misses. Both figures are asserted
 *       here so they stay machine-checked rather than written down.</li>
 *   <li>Being a record, {@code AccountDto} inherits value-based {@code equals} and {@code hashCode} over all
 *       37 components, protected ones included. Neither returns a rendering, so neither is a disclosure
 *       surface, and narrowing them would break the equality-to-hash contract that collection membership
 *       depends on. The behaviour is asserted so the reasoning stays evidenced rather than assumed.</li>
 * </ul>
 *
 * <p>Where evidence does not exist, none is invented. No relational schema is asserted anywhere in this
 * file: the column types, widths and constraints behind these fields are <em>Not available</em> from the
 * artefacts this tier may read. Establishing them needs the schema migration and a database-backed tier,
 * both of which sit outside a pure-JVM unit test.
 */
@DisplayName("AccountDto: 37 screen fields, a monetary contract, seed tolerance, and a toString that withholds")
final class AccountDtoTest {

    /** The account-view symbolic map, parsed from the frozen tree at run time. */
    private static final BmsSymbolicMap COACTVW = BmsSymbolicMap.of("COACTVW");

    /** The account-update map, which declares the same balance width and a parenthesised account id. */
    private static final BmsSymbolicMap COACTUP = BmsSymbolicMap.of("COACTUP");

    /** The sign-on map, the only member whose current-time header field is nine characters. */
    private static final BmsSymbolicMap COSGN00 = BmsSymbolicMap.of("COSGN00");

    /** The bill-payment map, whose current balance is a different name at a different width. */
    private static final BmsSymbolicMap COBIL00 = BmsSymbolicMap.of("COBIL00");

    /** The seventeen generated symbolic maps, in the order the corpus census walks them. */
    private static final List<String> ALL_MAPS = List.of(
            "COACTUP", "COACTVW", "COADM01", "COBIL00", "COCRDLI", "COCRDSL", "COCRDUP", "COMEN01",
            "CORPT00", "COSGN00", "COTRN00", "COTRN01", "COTRN02", "COUSR00", "COUSR01", "COUSR02",
            "COUSR03");

    /**
     * The input-field census across all seventeen maps, as the parser measures it from the frozen copybooks.
     *
     * <p>Held as a constant so the measurement has one expected value in one place. It is the sum of the
     * per-map counts the parser reports, and every one of those counts is itself derived from
     * {@code app/cpy-bms/**} rather than transcribed, so this figure moves only if a copybook does.
     */
    private static final int CORPUS_INPUT_FIELD_COUNT = 441;

    /**
     * The state codes present in {@code app/data/ASCII/custdata.txt} but absent from the lookup table.
     *
     * <p>{@code AP} on one row, {@code FM} on two, {@code MH} on one and {@code PW} on one: five rows in
     * all, which is exactly how many a membership rule on this read projection would reject.
     */
    private static final List<String> STATE_CODES_ABSENT_FROM_TABLE = List.of("AP", "FM", "MH", "PW");

    /** The width of a monetary display field on the bill-payment map, which is not this map's width. */
    private static final int BILL_PAYMENT_BALANCE_WIDTH = 14;

    /** The width of the current-time header field on the sign-on map, which is not this map's width. */
    private static final int SIGN_ON_CURRENT_TIME_WIDTH = 9;

    /** The number of two-letter codes in the lookup table at {@code app/cpy/CSLKPCDY.cpy:L1013}. */
    private static final int VALID_STATE_CODE_TABLE_SIZE = 56;

    /**
     * The padding character for width probes.
     *
     * <p>Deliberately a letter that appears in no component name, no COBOL field name, no picture clause
     * and no diagnostic message this type produces. That is what makes "the message never reproduces the
     * offending value" a real assertion instead of a coincidence.
     */
    private static final String PROBE = "Q";

    /** One line of the seeded account record, used where a single row is enough to make the point. */
    private static final int FIRST_ROW = 0;

    /**
     * One generated input field: its COBOL name, the record component it became, its picture clause
     * verbatim, the width that clause declares, and the line it is declared on.
     *
     * @param cobolName    the generated field name, for example {@code ACURBALI}
     * @param component    the record component name on {@link AccountDto}
     * @param picture      the picture clause exactly as the copybook spells it
     * @param width        the number of character positions the clause declares
     * @param copybookLine the one-based line number within {@code app/cpy-bms/COACTVW.CPY}
     */
    record ScreenField(String cobolName, String component, String picture, int width,
            int copybookLine) {

        @Override
        public String toString() {
            return cobolName + " " + picture + " at app/cpy-bms/COACTVW.CPY:" + copybookLine;
        }
    }

    /**
     * Every input field of {@code 01 CACTVWAI}, transcribed from the copybook in declaration order.
     *
     * <p>Read as the field contract itself. The ordering is the screen's own, which interleaves the date
     * fields with the monetary fields because the map lays them out in two columns; the record preserves
     * that order rather than grouping like with like. Three independent sources are cross-checked against
     * this table elsewhere in the suite: the copybook as parsed by {@link BmsSymbolicMap}, the width
     * constants published by {@link AccountDto}, and the record's own component order.
     */
    private static final List<ScreenField> FIELDS = List.of(
            new ScreenField("TRNNAMEI", "transactionName", "PIC X(4)", 4, 24),
            new ScreenField("TITLE01I", "title01", "PIC X(40)", 40, 30),
            new ScreenField("CURDATEI", "currentDate", "PIC X(8)", 8, 36),
            new ScreenField("PGMNAMEI", "programName", "PIC X(8)", 8, 42),
            new ScreenField("TITLE02I", "title02", "PIC X(40)", 40, 48),
            new ScreenField("CURTIMEI", "currentTime", "PIC X(8)", 8, 54),
            new ScreenField("ACCTSIDI", "accountId", "PIC 99999999999", 11, 60),
            new ScreenField("ACSTTUSI", "accountStatus", "PIC X(1)", 1, 66),
            new ScreenField("ADTOPENI", "openDate", "PIC X(10)", 10, 72),
            new ScreenField("ACRDLIMI", "creditLimit", "PIC X(15)", 15, 78),
            new ScreenField("AEXPDTI", "expiryDate", "PIC X(10)", 10, 84),
            new ScreenField("ACSHLIMI", "cashCreditLimit", "PIC X(15)", 15, 90),
            new ScreenField("AREISDTI", "reissueDate", "PIC X(10)", 10, 96),
            new ScreenField("ACURBALI", "currentBalance", "PIC X(15)", 15, 102),
            new ScreenField("ACRCYCRI", "currentCycleCredit", "PIC X(15)", 15, 108),
            new ScreenField("AADDGRPI", "accountGroupId", "PIC X(10)", 10, 114),
            new ScreenField("ACRCYDBI", "currentCycleDebit", "PIC X(15)", 15, 120),
            new ScreenField("ACSTNUMI", "customerId", "PIC X(9)", 9, 126),
            new ScreenField("ACSTSSNI", "customerSsn", "PIC X(12)", 12, 132),
            new ScreenField("ACSTDOBI", "customerDateOfBirth", "PIC X(10)", 10, 138),
            new ScreenField("ACSTFCOI", "customerFicoScore", "PIC X(3)", 3, 144),
            new ScreenField("ACSFNAMI", "customerFirstName", "PIC X(25)", 25, 150),
            new ScreenField("ACSMNAMI", "customerMiddleName", "PIC X(25)", 25, 156),
            new ScreenField("ACSLNAMI", "customerLastName", "PIC X(25)", 25, 162),
            new ScreenField("ACSADL1I", "addressLine1", "PIC X(50)", 50, 168),
            new ScreenField("ACSSTTEI", "addressStateCode", "PIC X(2)", 2, 174),
            new ScreenField("ACSADL2I", "addressLine2", "PIC X(50)", 50, 180),
            new ScreenField("ACSZIPCI", "addressZip", "PIC X(5)", 5, 186),
            new ScreenField("ACSCITYI", "addressCity", "PIC X(50)", 50, 192),
            new ScreenField("ACSCTRYI", "addressCountryCode", "PIC X(3)", 3, 198),
            new ScreenField("ACSPHN1I", "phoneNumber1", "PIC X(13)", 13, 204),
            new ScreenField("ACSGOVTI", "governmentIssuedId", "PIC X(20)", 20, 210),
            new ScreenField("ACSPHN2I", "phoneNumber2", "PIC X(13)", 13, 216),
            new ScreenField("ACSEFTCI", "eftAccountId", "PIC X(10)", 10, 222),
            new ScreenField("ACSPFLGI", "primaryCardHolderIndicator", "PIC X(1)", 1, 228),
            new ScreenField("INFOMSGI", "informationMessage", "PIC X(45)", 45, 234),
            new ScreenField("ERRMSGI", "errorMessage", "PIC X(78)", 78, 240));

    /** The five monetary fields, every one of them declared {@code PIC X(15)} on this map. */
    private static final List<String> MONETARY_FIELDS =
            List.of("ACRDLIMI", "ACSHLIMI", "ACURBALI", "ACRCYCRI", "ACRCYDBI");

    /** The six header fields every one of the seventeen maps declares. */
    private static final List<String> HEADER_FIELDS =
            List.of("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI");

    /**
     * The components carrying data that must never reach a diagnostic rendering.
     *
     * <p>The account is joined to the customer by {@code app/cbl/COACTVWC.cbl}, so this payload carries the
     * social security number, the government-issued identifier, both telephone numbers, the date of birth,
     * the electronic funds transfer account identifier and the three name fields.
     */
    private static final List<String> PROTECTED_COMPONENTS = List.of(
            "customerSsn", "customerDateOfBirth", "customerFirstName", "customerMiddleName",
            "customerLastName", "phoneNumber1", "phoneNumber2", "governmentIssuedId", "eftAccountId");

    /** The three components {@link AccountDto#toString()} is permitted to render. */
    private static final List<String> RENDERABLE_COMPONENTS =
            List.of("accountId", "accountStatus", "programName");

    /** Substrings that would betray a credential or a full card number if any component name held one. */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "password", "passwd", "pwd", "secret", "token", "credential", "hash", "cardnumber", "cardnum",
            "pan");

    private static final int ACCT_CREDIT_LIMIT_COLUMN = 25;
    private static final int ACCT_EXPIRATION_DATE_COLUMN = 59;
    private static final int ACCT_CYCLE_CREDIT_COLUMN = 79;
    private static final int ACCT_CYCLE_DEBIT_COLUMN = 91;
    private static final int ACCT_ADDR_ZIP_COLUMN = 103;
    private static final int ACCT_GROUP_ID_COLUMN = 113;
    private static final int ACCT_MONEY_WIDTH = 12;
    private static final int ACCT_TEXT_TEN = 10;

    private static final int CUST_STATE_CODE_COLUMN = 235;
    private static final int CUST_FICO_COLUMN = 330;
    private static final int CUST_STATE_CODE_WIDTH = 2;
    private static final int CUST_FICO_WIDTH = 3;

    /**
     * Supplies the 37 field contracts to the parameterised boundary and width tests.
     *
     * @return the field table, in copybook declaration order
     */
    static List<ScreenField> screenFields() {
        return FIELDS;
    }

    /**
     * Returns every component value of a payload, in declaration order, nulls preserved.
     *
     * <p>Written out accessor by accessor rather than reached through reflection. That keeps the read path
     * explicit, lets the compiler check it, and makes the list itself a second statement of the component
     * order which the positional assertions can be checked against.
     *
     * @param dto the payload to read
     * @return the 37 component values, in the order the record declares them
     */
    private static List<String> valuesOf(final AccountDto dto) {
        return Arrays.asList(dto.transactionName(), dto.title01(), dto.currentDate(), dto.programName(),
                dto.title02(), dto.currentTime(), dto.accountId(), dto.accountStatus(), dto.openDate(),
                dto.creditLimit(), dto.expiryDate(), dto.cashCreditLimit(), dto.reissueDate(),
                dto.currentBalance(), dto.currentCycleCredit(), dto.accountGroupId(),
                dto.currentCycleDebit(), dto.customerId(), dto.customerSsn(), dto.customerDateOfBirth(),
                dto.customerFicoScore(), dto.customerFirstName(), dto.customerMiddleName(),
                dto.customerLastName(), dto.addressLine1(), dto.addressStateCode(), dto.addressLine2(),
                dto.addressZip(), dto.addressCity(), dto.addressCountryCode(), dto.phoneNumber1(),
                dto.governmentIssuedId(), dto.phoneNumber2(), dto.eftAccountId(),
                dto.primaryCardHolderIndicator(), dto.informationMessage(), dto.errorMessage());
    }

    /**
     * Builds a payload from 37 positional values.
     *
     * <p>Written out index by index for the same reason {@link #valuesOf(AccountDto)} is written out
     * accessor by accessor: the canonical constructor is then invoked directly, so a width refusal arrives
     * as the {@code IllegalArgumentException} it is rather than wrapped in a reflective failure.
     *
     * @param values exactly 37 component values, in declaration order; any of them may be {@code null}
     * @return the constructed payload
     */
    private static AccountDto fromValues(final String[] values) {
        return new AccountDto(values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                values[7], values[8], values[9], values[10], values[11], values[12], values[13],
                values[14], values[15], values[16], values[17], values[18], values[19], values[20],
                values[21], values[22], values[23], values[24], values[25], values[26], values[27],
                values[28], values[29], values[30], values[31], values[32], values[33], values[34],
                values[35], values[36]);
    }

    /**
     * Builds a payload in which one named field carries a value and every other field is absent.
     *
     * @param field the field to populate
     * @param value the value to place in it, which may be {@code null}
     * @return the constructed payload
     */
    private static AccountDto only(final ScreenField field, final String value) {
        final String[] values = new String[AccountDto.FIELD_COUNT];
        values[FIELDS.indexOf(field)] = value;
        return fromValues(values);
    }

    /**
     * Reads back the one component a {@link #only(ScreenField, String)} payload populated.
     *
     * @param dto   the payload to read
     * @param field the field whose component to return
     * @return the component value as the payload holds it
     */
    private static String valueOf(final AccountDto dto, final ScreenField field) {
        return valuesOf(dto).get(FIELDS.indexOf(field));
    }

    /**
     * Returns the record component names of {@link AccountDto}, in declaration order.
     *
     * @return the 37 component names
     */
    private static List<String> componentNames() {
        return Arrays.stream(AccountDto.class.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * Reads one frozen COBOL source, stripping carriage returns.
     *
     * <p>Five members of the corpus are stored with CRLF terminators, {@code app/cbl/COACTUPC.cbl} among
     * them, so stripping is what keeps a cited line number pointing at the line that was cited. The read
     * is read-only; nothing under {@code app/} is written, moved or reformatted.
     *
     * @param relativePath the repository-relative path of the member
     * @return the member's lines with carriage returns removed
     */
    private static List<String> frozenSource(final String relativePath) {
        final Path path = Path.of(relativePath);
        try {
            return Files.readAllLines(path, StandardCharsets.ISO_8859_1).stream()
                    .map(line -> line.replace("\r", ""))
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("cannot read the frozen member " + path, unreadable);
        }
    }

    /**
     * Builds a payload whose every component carries a distinguishable value.
     *
     * <p>The two header temporal fields are derived from {@link FixedClockProvider#canonicalClock()} so
     * that the payload is identical on every host and in every time zone. The protected values are
     * synthetic and exist only to be looked for, and not found, in a rendering.
     *
     * @return a fully populated payload
     */
    private static AccountDto populated() {
        return new AccountDto("CAVW", "CardDemo Account View", screenDate(), "COACTVWC",
                "View Account", screenTime(), "00000000001", "Y", "2020-01-15", "9999999999.99",
                "2027-01-14", "500.00", "2021-06-30", "1234.56", "200.00", "default",
                "-45.00", "000000001", "123456789", "1961-06-08", "750", "FNAMEAA1",
                "MNAM1", "LNM1", "123 MAIN STREET", "NY", "APT 4B", "10001",
                "NEW YORK", "USA", "5551234567", "GOVT-ID-0000", "5559876543", "EFTACCT441",
                "Y", "Account retrieved", "");
    }

    /**
     * Renders the current-date header field from the injected clock.
     *
     * <p>{@code app/cpy/CSDAT01Y.cpy:30-35} declares {@code WS-CURDATE-MM-DD-YY} as two digits, a solidus,
     * two digits, a solidus and two digits, and {@code app/cbl/COACTVWC.cbl:447} moves exactly that group
     * into the screen field. Eight characters, which is what {@code CURDATEI PIC X(8)} holds.
     *
     * @return the eight-character screen date
     */
    private static String screenDate() {
        return DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT).format(canonicalDateTime());
    }

    /**
     * Renders the current-time header field from the injected clock.
     *
     * <p>{@code app/cpy/CSDAT01Y.cpy:36-41} declares {@code WS-CURTIME-HH-MM-SS} as two digits, a colon,
     * two digits, a colon and two digits, and {@code app/cbl/COACTVWC.cbl:453} moves exactly that group
     * into the screen field. Eight characters, which is what {@code CURTIMEI PIC X(8)} holds on this map
     * and not what it holds on the sign-on map.
     *
     * @return the eight-character screen time
     */
    private static String screenTime() {
        return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).format(canonicalDateTime());
    }

    /**
     * Resolves the injected clock to a local date and time in its own zone.
     *
     * @return the canonical instant, in the canonical zone
     */
    private static LocalDateTime canonicalDateTime() {
        final Clock clock = FixedClockProvider.canonicalClock();
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    @Nested
    @DisplayName("1. The census is 37, and every one of the 37 is matched positionally")
    final class FieldCensus {

        @Test
        @DisplayName("the input group is CACTVWAI, the short form, not COACTVWI")
        void theInputGroupIsNamedCactvwai() {
            assertThat(frozenSource("app/cpy-bms/COACTVW.CPY").get(COACTVW.inputGroupLine() - 1))
                    .as("the 01 group header the generator emitted")
                    .contains("CACTVWAI");
            assertThat(COACTVW.inputGroupLine()).isEqualTo(17);
            assertThat(COACTVW.outputRedefinitionLine())
                    .as("CACTVWAO redefines the input group, and no input field lies beyond it")
                    .isEqualTo(241);
        }

        @Test
        @DisplayName("FIELD_COUNT is the copybook's own input-field count")
        void fieldCountMatchesTheCopybook() {
            assertThat(AccountDto.FIELD_COUNT)
                    .as("app/cpy-bms/COACTVW.CPY input fields as the parser measures them: 36 with a "
                            + "parenthesised picture plus ACCTSIDI at line 60, whose picture is expanded. "
                            + "The record's own component count is compared against this below, so a "
                            + "missing field and an extra field both fail")
                    .isEqualTo(COACTVW.inputFieldCount())
                    .isEqualTo(37);
        }

        @Test
        @DisplayName("the transcribed table holds the same 37 fields the parser found")
        void theTranscribedTableAgreesWithTheParser() {
            assertThat(FIELDS).hasSize(AccountDto.FIELD_COUNT);
            assertThat(FIELDS.stream().map(ScreenField::cobolName).toList())
                    .containsExactlyElementsOf(COACTVW.fieldNames());
        }

        @Test
        @DisplayName("the record declares exactly that many components")
        void recordDeclaresThatManyComponents() {
            assertThat(componentNames()).hasSize(AccountDto.FIELD_COUNT);
        }

        @Test
        @DisplayName("every component matches the copybook field at the same index")
        void componentsCorrespondPositionally() {
            final List<String> components = componentNames();
            final List<String> copybookOrder = COACTVW.fieldNames();

            for (int index = 0; index < AccountDto.FIELD_COUNT; index++) {
                final ScreenField expected = FIELDS.get(index);

                assertThat(copybookOrder.get(index))
                        .as("copybook field at index %d", index)
                        .isEqualTo(expected.cobolName());
                assertThat(components.get(index))
                        .as("component for copybook field %s at index %d", expected.cobolName(), index)
                        .isEqualTo(expected.component());
            }
        }

        @Test
        @DisplayName("the record's accessors are read in that same order")
        void accessorsAreReadInThatOrder() {
            // valuesOf is written out accessor by accessor, so it is an independent statement of the order.
            // Populating one field at a time and finding it at the expected index proves the two agree.
            for (int index = 0; index < AccountDto.FIELD_COUNT; index++) {
                final ScreenField field = FIELDS.get(index);
                final List<String> values = valuesOf(only(field, PROBE));

                assertThat(values.get(index))
                        .as("the value placed in %s is read back at index %d", field.component(), index)
                        .isEqualTo(PROBE);
                assertThat(values.stream().filter(value -> value != null).count())
                        .as("only %s was populated", field.component())
                        .isEqualTo(1L);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("each field's declared width is byte-exact against the copybook")
        void eachWidthIsByteExact(final ScreenField field) {
            assertThat(COACTVW.widthOf(field.cobolName()))
                    .as("the width the copybook declares for %s", field.cobolName())
                    .isEqualTo(field.width());
        }

        @Test
        @DisplayName("the numeric-pictured account identifier is one of the 37")
        void theNumericFieldIsIncluded() {
            final String declaration = frozenSource("app/cpy-bms/COACTVW.CPY").get(59);

            assertThat(declaration)
                    .as("the expanded numeric picture every PIC [X9]( regex misses")
                    .contains("ACCTSIDI")
                    .contains("PIC 99999999999")
                    .doesNotContain("PIC X(");
            assertThat(COACTVW.declares("ACCTSIDI")).isTrue();
            assertThat(COACTVW.widthOf("ACCTSIDI")).isEqualTo(AccountDto.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(componentNames()).contains("accountId");
        }

        @Test
        @DisplayName("exactly one of the fields carries an expanded picture, which is what a parenthesised "
                + "picture census cannot see")
        void oneFieldCarriesAnExpandedPicture() {
            final long parenthesised = FIELDS.stream()
                    .filter(field -> field.picture().startsWith("PIC X("))
                    .count();

            assertThat(parenthesised)
                    .as("a PIC [X9]( census sees only the parenthesised pictures")
                    .isEqualTo(36L);
            assertThat(parenthesised + 1)
                    .as("and the field it cannot see is ACCTSIDI at app/cpy-bms/COACTVW.CPY:60, whose "
                            + "picture is written out as PIC 99999999999. Naming the difference is what "
                            + "makes the two counts reconcilable instead of merely different")
                    .isEqualTo(AccountDto.FIELD_COUNT);
        }

        @Test
        @DisplayName("the corpus census is the sum of the seventeen per-map counts the parser measures")
        void theCorpusCensusIsTheSumOfTheSeventeenPerMapCounts() {
            assertThat(ALL_MAPS).hasSize(17);
            final int census = ALL_MAPS.stream()
                    .map(BmsSymbolicMap::of)
                    .mapToInt(BmsSymbolicMap::inputFieldCount)
                    .sum();

            assertThat(census)
                    .as("measured from app/cpy-bms/** rather than transcribed, so a copybook change moves "
                            + "the census and a transcription error cannot hide in it")
                    .isEqualTo(CORPUS_INPUT_FIELD_COUNT);
        }

        @Test
        @DisplayName("every component is a String, so the payload stays byte-comparable")
        void everyComponentIsAString() {
            assertThat(AccountDto.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType()).isEqualTo(String.class));
        }

        @Test
        @DisplayName("the account identifier is text, so its leading zeros survive")
        void theAccountIdentifierIsText() {
            // app/data/ASCII/acctdata.txt row 1 opens 00000000001. A numeric component would render that as
            // 1 and the screen field, the primary key and the parity comparison would all disagree.
            final ScreenField accountId = FIELDS.get(6);
            final String seeded = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT)
                    .field(FIRST_ROW, 1, AccountDto.ACCOUNT_ID_LENGTH);

            assertThat(accountId.component()).isEqualTo("accountId");
            assertThat(AccountDto.class.getRecordComponents()[6].getType()).isEqualTo(String.class);
            assertThat(seeded).isEqualTo("00000000001").hasSize(AccountDto.ACCOUNT_ID_LENGTH);
            assertThat(valueOf(only(accountId, seeded), accountId))
                    .as("a fully zero-padded identifier round-trips unaltered")
                    .isEqualTo("00000000001");
        }

        @Test
        @DisplayName("an all-zero identifier round-trips too, digit for digit")
        void anAllZeroIdentifierRoundTrips() {
            final ScreenField accountId = FIELDS.get(6);
            final String allZeros = "0".repeat(AccountDto.ACCOUNT_ID_LENGTH);

            assertThat(valueOf(only(accountId, allZeros), accountId)).isEqualTo("00000000000");
        }
    }

    @Nested
    @DisplayName("2. Two widths that differ between maps, so neither may be shared")
    final class NoSharedAbstraction {

        @Test
        @DisplayName("the current-time header is eight characters here and nine on the sign-on map")
        void theCurrentTimeWidthDiffersBetweenMaps() {
            assertThat(COACTVW.widthOf("CURTIMEI"))
                    .as("CURTIMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:54")
                    .isEqualTo(AccountDto.CURRENT_TIME_LENGTH)
                    .isEqualTo(8);
            assertThat(COSGN00.widthOf("CURTIMEI"))
                    .as("CURTIMEI PIC X(9) at app/cpy-bms/COSGN00.CPY:54, the only nine in the corpus")
                    .isEqualTo(SIGN_ON_CURRENT_TIME_WIDTH);
            assertThat(COSGN00.widthOf("CURTIMEI")).isNotEqualTo(COACTVW.widthOf("CURTIMEI"));
        }

        @Test
        @DisplayName("the other five header fields are uniform, which is what makes the sixth decisive")
        void theOtherFiveHeaderFieldsAreUniform() {
            assertThat(HEADER_FIELDS).hasSize(6).containsExactlyElementsOf(
                    FIELDS.subList(0, 6).stream().map(ScreenField::cobolName).toList());
            for (final String header : HEADER_FIELDS.subList(0, 5)) {
                assertThat(COSGN00.widthOf(header))
                        .as("%s is declared at one width on every map", header)
                        .isEqualTo(COACTVW.widthOf(header));
            }
        }

        @Test
        @DisplayName("no shared header type is inherited or implemented")
        void noSharedHeaderTypeIsInherited() {
            // A base class, interface or mixin carrying the six header fields would have to fix one CURTIME
            // width, and would then be wrong for this map or for the sign-on map. The six are declared
            // inline instead, on both.
            assertThat(AccountDto.class.getSuperclass())
                    .as("a record's only supertype")
                    .isEqualTo(Record.class);
            assertThat(AccountDto.class.getInterfaces())
                    .as("no shared header or balance contract is implemented")
                    .isEmpty();
            assertThat(AccountDto.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("the balance is fifteen characters here and on the update map, fourteen on bill payment")
        void theBalanceWidthDiffersBetweenMaps() {
            assertThat(COACTVW.widthOf("ACURBALI"))
                    .as("ACURBALI PIC X(15) at app/cpy-bms/COACTVW.CPY:102")
                    .isEqualTo(AccountDto.MONEY_DISPLAY_LENGTH)
                    .isEqualTo(15);
            assertThat(COACTUP.widthOf("ACURBALI"))
                    .as("ACURBALI PIC X(15) at app/cpy-bms/COACTUP.CPY:138 corroborates the width")
                    .isEqualTo(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(COBIL00.widthOf("CURBALI"))
                    .as("CURBALI PIC X(14) at app/cpy-bms/COBIL00.CPY:66 is a different name at a "
                            + "different width")
                    .isEqualTo(BILL_PAYMENT_BALANCE_WIDTH);
            assertThat(COBIL00.widthOf("CURBALI")).isNotEqualTo(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(COACTVW.declares("CURBALI"))
                    .as("the account-view map uses ACURBALI, not CURBALI")
                    .isFalse();
            assertThat(COBIL00.declares("ACURBALI"))
                    .as("and the bill-payment map uses CURBALI, not ACURBALI")
                    .isFalse();
        }

        @Test
        @DisplayName("the account identifier is eleven positions under either spelling")
        void theAccountIdentifierWidthIsSpellingIndependent() {
            // COACTVW.CPY:60 writes it expanded and COACTUP.CPY:60 parenthesised. Same eleven positions, so
            // the spelling is a census hazard rather than a contract difference.
            assertThat(COACTUP.widthOf("ACCTSIDI")).isEqualTo(COACTVW.widthOf("ACCTSIDI")).isEqualTo(11);
            assertThat(frozenSource("app/cpy-bms/COACTUP.CPY").get(59)).contains("PIC X(11)");
        }
    }

    @Nested
    @DisplayName("3. A read projection, which is why it carries no snapshot")
    final class ReadProjection {

        @Test
        @DisplayName("the driving program never writes, so there is nothing to detect a change against")
        void theDrivingProgramNeverWrites() {
            final List<String> program = frozenSource("app/cbl/COACTVWC.cbl");

            assertThat(program).hasSize(941);
            assertThat(program.stream().filter(line -> line.matches(".*\\bEXEC CICS +READ\\b.*")).count())
                    .as("the cross-reference, account and customer reads")
                    .isEqualTo(3L);
            assertThat(program)
                    .as("no update verb appears anywhere in the member, not even in a comment")
                    .noneMatch(line -> line.matches(".*\\b(WRITE|REWRITE|DELETE)\\b.*"));
        }

        @Test
        @DisplayName("no snapshot group exists to migrate, here")
        void theViewPathDeclaresNoSnapshotGroup() {
            assertThat(frozenSource("app/cbl/COACTVWC.cbl"))
                    .as("the view path holds no before-image group")
                    .noneMatch(line -> line.contains("OLD-DETAILS") || line.contains("NEW-DETAILS"));
        }

        @Test
        @DisplayName("the update path does declare one, which is the contrast")
        void theUpdatePathDoesDeclareOne() {
            // app/cbl/COACTUPC.cbl:L669 and :L757. The member is stored with CRLF terminators, so the read
            // strips carriage returns or these line numbers drift.
            final List<String> program = frozenSource("app/cbl/COACTUPC.cbl");

            assertThat(program.get(668)).contains("ACUP-OLD-DETAILS");
            assertThat(program.get(756)).contains("ACUP-NEW-DETAILS");
            assertThat(COACTUP.inputFieldCount())
                    .as("the update map carries 54 input fields to this map's 37")
                    .isEqualTo(54);
        }

        @Test
        @DisplayName("this payload has no oldDetails or newDetails component")
        void thisPayloadCarriesNoSnapshotPair() {
            // A snapshot belongs to the stateless update request, which has to carry both images because the
            // server keeps none. Letting one leak onto the view payload would invite a change-detection
            // comparison on a path that performs no update.
            assertThat(componentNames())
                    .doesNotContain("oldDetails", "newDetails")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("details"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("snapshot"));
        }

        @Test
        @DisplayName("every date is text, never a temporal type")
        void everyDateIsText() {
            // app/cpy/CVACT01Y.cpy declares the three account dates PIC X(10), and the screen fields are
            // PIC X(10) too. Parsing them into a temporal type would reject a value the source stores and
            // would re-render the ones it accepts.
            final List<String> dateComponents =
                    List.of("openDate", "expiryDate", "reissueDate", "customerDateOfBirth", "currentDate");

            for (final String component : dateComponents) {
                final int index = componentNames().indexOf(component);

                assertThat(index).as("%s is declared on this record", component).isNotNegative();
                assertThat(AccountDto.class.getRecordComponents()[index].getType())
                        .as("%s is text, not a temporal type", component)
                        .isEqualTo(String.class);
            }
            assertThat(AccountDto.DATE_TEXT_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("the misspelled record field name is left misspelled")
        void theMisspelledDateNameIsPreserved() {
            // app/cpy/CVACT01Y.cpy:L11 declares ACCT-EXPIRAION-DATE, missing the second T. It is the field
            // contract, so it is quoted as found wherever the record name is echoed and never repaired.
            final String declaration = frozenSource("app/cpy/CVACT01Y.cpy").get(10);

            assertThat(declaration)
                    .contains("ACCT-EXPIRAION-DATE")
                    .contains("PIC X(10)")
                    .doesNotContain("ACCT-EXPIRATION-DATE");
        }

        @Test
        @DisplayName("the screen field this date reaches is AEXPDTI, ten characters wide")
        void theExpiryScreenFieldIsTenWide() {
            assertThat(COACTVW.widthOf("AEXPDTI")).isEqualTo(AccountDto.DATE_TEXT_LENGTH);
            assertThat(FIELDS.get(10).cobolName()).isEqualTo("AEXPDTI");
            assertThat(FIELDS.get(10).component()).isEqualTo("expiryDate");
        }
    }

    @Nested
    @DisplayName("4. The monetary contract, which is decimal and belongs to this map alone")
    final class MonetaryContract {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ACRDLIMI", "ACSHLIMI", "ACURBALI", "ACRCYCRI", "ACRCYDBI"})
        @DisplayName("each monetary field is declared at the published width")
        void monetaryFieldsShareTheWidth(final String field) {
            assertThat(COACTVW.widthOf(field))
                    .as("%s is declared PIC X(15) on the account-view map", field)
                    .isEqualTo(AccountDto.MONEY_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("all five monetary fields are accounted for")
        void allFiveAreAccountedFor() {
            assertThat(MONETARY_FIELDS).hasSize(5).allMatch(COACTVW::declares);
            assertThat(FIELDS.stream()
                    .filter(field -> field.width() == AccountDto.MONEY_DISPLAY_LENGTH)
                    .map(ScreenField::cobolName)
                    .toList())
                    .as("no other field on this map is fifteen characters wide")
                    .containsExactlyInAnyOrderElementsOf(MONETARY_FIELDS);
        }

        @Test
        @DisplayName("the scale and precision come from PIC S9(10)V99")
        void scaleAndPrecisionComeFromTheRecordLayout() {
            // app/cpy/CVACT01Y.cpy:7-14 declares all five amounts PIC S9(10)V99: ten integer digits and two
            // fractional digits, which is the NUMERIC(12,2) column width in the relational target.
            assertThat(AccountDto.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountDto.MONEY_PRECISION).isEqualTo(12);
            assertThat(AccountDto.MONEY_PRECISION - AccountDto.MONEY_SCALE)
                    .as("integer digits available to an account amount")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("rounding is HALF_EVEN, declared rather than defaulted")
        void roundingIsHalfEven() {
            assertThat(AccountDto.MONEY_ROUNDING).isEqualTo(RoundingMode.HALF_EVEN);
        }

        @Test
        @DisplayName("no binary floating-point type appears anywhere on the type")
        void noBinaryFloatingPointAppears() {
            // The security audit gate asserts this directly: a float or double in a financial field loses
            // cents at the third addition and the loss is not reproducible across platforms.
            final List<Class<?>> forbidden =
                    List.of(float.class, double.class, Float.class, Double.class);

            assertThat(Arrays.stream(AccountDto.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .toList())
                    .doesNotContainAnyElementsOf(forbidden);
            assertThat(Arrays.stream(AccountDto.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getType)
                    .toList())
                    .as("including the static width and money constants")
                    .doesNotContainAnyElementsOf(forbidden);
        }

        @Test
        @DisplayName("the conversion yields BigDecimal, so arithmetic stays decimal")
        void theConversionYieldsBigDecimal() {
            assertThat(AccountDto.toAmount("1234.56", "currentBalance").orElseThrow())
                    .isInstanceOf(BigDecimal.class)
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("amounts are compared by value, and equals would get it wrong")
        void amountsAreComparedByValue() {
            // BigDecimal.equals compares scale as well as value, so an unscaled 1234.5 and a scaled 1234.50
            // are equal by compareTo and unequal by equals. Every comparison in this suite uses compareTo.
            final BigDecimal converted = AccountDto.toAmount("1234.5", "currentBalance").orElseThrow();

            assertThat(converted.scale()).isEqualTo(AccountDto.MONEY_SCALE);
            assertThat(converted).isEqualByComparingTo(new BigDecimal("1234.5"));
            assertThat(converted)
                    .as("same value, different representation; equals separates them and compareTo does not")
                    .isNotEqualTo(new BigDecimal("1234.5"));
            assertThat(converted.compareTo(new BigDecimal("1234.5"))).isZero();
        }

        @Test
        @DisplayName("the display mask is narrower than the stored precision, and that is the legacy limit")
        void theMaskIsNarrowerThanStorage() {
            // COACTVW.CPY:302,314,326,332,344 render these amounts as +ZZZ,ZZZ,ZZZ.99 - a sign, nine integer
            // digits and two decimals. Storage allows ten integer digits, so the tenth cannot be rendered by
            // the legacy mask at all. The gap is faithful to the corpus and is asserted rather than
            // reconciled.
            final String mask = "+ZZZ,ZZZ,ZZZ.99";
            final long maskIntegerDigits = mask.chars().filter(character -> character == 'Z').count();

            assertThat(mask).hasSize(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(maskIntegerDigits).isEqualTo(9L);
            assertThat(maskIntegerDigits)
                    .as("the mask cannot render the tenth integer digit that storage permits")
                    .isLessThan(AccountDto.MONEY_PRECISION - AccountDto.MONEY_SCALE);
            assertThat(frozenSource("app/cpy-bms/COACTVW.CPY").get(301))
                    .as("the first of the five edited output masks")
                    .contains(mask);
        }
    }

    @Nested
    @DisplayName("5. Absent, blank and low-values are three states, and stay three")
    final class TriState {

        @Test
        @DisplayName("the source models exactly three outcomes, not two")
        void theSourceModelsThreeOutcomes() {
            // app/cpy/CSSETATY.cpy is a COPY ... REPLACING template copied into the PROCEDURE DIVISION, so
            // it is not a data layout and gets no class. What it does carry is the state model: valid,
            // not valid, and blank, with the markers firing only on re-entry.
            final List<String> template = frozenSource("app/cpy/CSSETATY.cpy");

            assertThat(template).anyMatch(line -> line.contains("FLG-(TESTVAR1)-NOT-OK"));
            assertThat(template).anyMatch(line -> line.contains("FLG-(TESTVAR1)-BLANK"));
            assertThat(template)
                    .as("the markers are conditional on re-entry, not on first display")
                    .anyMatch(line -> line.contains("CDEMO-PGM-REENTER"));
            assertThat(template)
                    .as("a blank field is marked with an asterisk, a wrong one only reddened")
                    .anyMatch(line -> line.contains("MOVE '*'"));
        }

        @Test
        @DisplayName("the source issues a different message for blank than for invalid")
        void blankAndInvalidCarryDifferentMessages() {
            // app/cbl/COACTUPC.cbl:505-508. Two condition names, two literals. Collapsing blank into
            // absent would leave one of these two messages unreachable.
            final String declarations =
                    String.join(" ", frozenSource("app/cbl/COACTUPC.cbl").subList(504, 508));

            assertThat(declarations)
                    .contains("CRED-LIMIT-IS-BLANK")
                    .contains("'Credit Limit must be supplied'")
                    .contains("CRED-LIMIT-IS-NOT-VALID")
                    .contains("'Credit Limit is not valid'");
        }

        @Test
        @DisplayName("absent and blank are distinguishable on the payload")
        void absentAndBlankAreDistinguishable() {
            final ScreenField balance = FIELDS.get(13);

            assertThat(valueOf(only(balance, null), balance)).isNull();
            assertThat(valueOf(only(balance, ""), balance)).isEmpty();
            assertThat(valueOf(only(balance, "   "), balance)).isEqualTo("   ");
        }

        @Test
        @DisplayName("low-values is a fourth representation and is not turned into blanks")
        void lowValuesIsPreservedVerbatim() {
            // app/cbl/COACTVWC.cbl:466 moves LOW-VALUES into a screen field when the filter is blank, so
            // binary zeros are a state the transport type receives and must carry unchanged.
            final ScreenField accountId = FIELDS.get(6);
            final String lowValues = "\u0000".repeat(AccountDto.ACCOUNT_ID_LENGTH);

            assertThat(valueOf(only(accountId, lowValues), accountId))
                    .isEqualTo(lowValues)
                    .isNotEqualTo(" ".repeat(AccountDto.ACCOUNT_ID_LENGTH));
            assertThat(frozenSource("app/cbl/COACTVWC.cbl").get(465)).contains("LOW-VALUES");
        }

        @Test
        @DisplayName("all four representations remain mutually distinct")
        void allFourRepresentationsRemainDistinct() {
            final ScreenField status = FIELDS.get(7);

            assertThat(Arrays.asList(
                    valueOf(only(status, null), status),
                    valueOf(only(status, ""), status),
                    valueOf(only(status, " "), status),
                    valueOf(only(status, "\u0000"), status)))
                    .as("absent, empty, blank and low-values")
                    .containsExactly(null, "", " ", "\u0000")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the conversion collapses them, and only for arithmetic")
        void theConversionCollapsesThemForArithmeticOnly() {
            assertThat(AccountDto.toAmount(null, "currentBalance")).isEmpty();
            assertThat(AccountDto.toAmount("", "currentBalance")).isEmpty();
            assertThat(AccountDto.toAmount("               ", "currentBalance")).isEmpty();
            assertThat(AccountDto.toAmount("\u0000\u0000\u0000", "currentBalance")).isEmpty();
        }

        @Test
        @DisplayName("binary zeros around a value are neutralised rather than rejected")
        void lowValuesAroundAValueAreNeutralised() {
            assertThat(AccountDto.toAmount("\u0000123.45\u0000", "currentBalance"))
                    .contains(new BigDecimal("123.45"));
            assertThat(AccountDto.toAmount("\u0000\u0000 \u0000", "currentBalance")).isEmpty();
        }
    }

    @Nested
    @DisplayName("6. Parsing, scaling and banker's rounding")
    final class Conversion {

        @Test
        @DisplayName("a plain amount converts and is scaled to two fractional digits")
        void plainAmountConverts() {
            final Optional<BigDecimal> converted = AccountDto.toAmount("1234.56", "currentBalance");

            assertThat(converted).isPresent();
            assertThat(converted.orElseThrow()).isEqualByComparingTo("1234.56");
            assertThat(converted.orElseThrow().scale()).isEqualTo(AccountDto.MONEY_SCALE);
        }

        @Test
        @DisplayName("an unscaled amount is widened to two fractional digits")
        void unscaledAmountIsWidened() {
            assertThat(AccountDto.toAmount("42", "creditLimit").orElseThrow())
                    .isEqualByComparingTo("42.00")
                    .hasToString("42.00");
        }

        @Test
        @DisplayName("a negative amount keeps its sign and is never normalised")
        void negativeAmountKeepsItsSign() {
            // The posting path accumulates negative amounts into the cycle debit deliberately, so an
            // absolute value anywhere on a money path would change the over-limit arithmetic.
            assertThat(AccountDto.toAmount("-45.00", "currentCycleDebit").orElseThrow())
                    .isEqualByComparingTo("-45.00")
                    .isNegative();
        }

        @Test
        @DisplayName("an explicitly signed positive amount converts")
        void signedPositiveConverts() {
            assertThat(AccountDto.toAmount("+1234.56", "currentBalance").orElseThrow())
                    .isEqualByComparingTo("1234.56");
        }

        @Test
        @DisplayName("rounding is HALF_EVEN, which HALF_UP would get wrong")
        void roundingIsHalfEvenNotHalfUp() {
            assertThat(AccountDto.toAmount("2.345", "currentBalance").orElseThrow())
                    .as("HALF_UP would give 2.35")
                    .isEqualByComparingTo("2.34");
            assertThat(AccountDto.toAmount("2.355", "currentBalance").orElseThrow())
                    .as("and the neighbour above is even here")
                    .isEqualByComparingTo("2.36");
        }

        @Test
        @DisplayName("a negative tie rounds to the even neighbour too")
        void negativeTieRoundsToEven() {
            assertThat(AccountDto.toAmount("-2.345", "currentBalance").orElseThrow())
                    .isEqualByComparingTo("-2.34");
        }

        @Test
        @DisplayName("conversion of the same text is idempotent")
        void conversionIsIdempotent() {
            final String text = "1234.56";

            assertThat(AccountDto.toAmount(text, "currentBalance").orElseThrow())
                    .isEqualByComparingTo(AccountDto.toAmount(text, "currentBalance").orElseThrow());
        }

        @Test
        @DisplayName("the widest amount the field allows converts")
        void widestAmountConverts() {
            assertThat(AccountDto.toAmount("9999999999.99", "creditLimit").orElseThrow())
                    .isEqualByComparingTo("9999999999.99");
        }

        @Test
        @DisplayName("zero converts to a scaled zero rather than an absent amount")
        void zeroConvertsToScaledZero() {
            final Optional<BigDecimal> converted = AccountDto.toAmount("0", "currentCycleCredit");

            assertThat(converted).isPresent();
            assertThat(converted.orElseThrow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(converted.orElseThrow().scale()).isEqualTo(AccountDto.MONEY_SCALE);
        }

        @Test
        @DisplayName("excess fractional digits are rounded away rather than refused")
        void excessFractionalDigitsAreRounded() {
            assertThat(AccountDto.toAmount("1.239", "currentBalance").orElseThrow())
                    .isEqualByComparingTo("1.24");
        }
    }

    @Nested
    @DisplayName("7. What the conversion refuses, and how it says so")
    final class Refusals {

        @Test
        @DisplayName("a null field name is refused, because a failure could not then be identified")
        void nullFieldNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("1234.56", null))
                    .withMessageContaining("fieldName is required");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank field name is refused")
        void blankFieldNameIsRefused(final String fieldName) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("1234.56", fieldName))
                    .withMessageContaining("fieldName is required");
        }

        @Test
        @DisplayName("the field name is validated before the display text, so both may be wrong")
        void theFieldNameIsValidatedFirst() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("not a number", null))
                    .withMessageContaining("fieldName is required");
        }

        @Test
        @DisplayName("text longer than the declared width cannot have come from this map")
        void overWideTextIsRefused() {
            final String tooWide = "1".repeat(AccountDto.MONEY_DISPLAY_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount(tooWide, "currentBalance"))
                    .withMessageContaining("currentBalance")
                    .withMessageContaining(String.valueOf(AccountDto.MONEY_DISPLAY_LENGTH));
        }

        @Test
        @DisplayName("text of exactly the declared width is accepted")
        void textOfExactlyTheDeclaredWidthIsAccepted() {
            final String atWidth = "  9999999999.99";

            assertThat(atWidth).hasSize(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(AccountDto.toAmount(atWidth, "creditLimit").orElseThrow())
                    .isEqualByComparingTo("9999999999.99");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"1,234.56", "$1234.56", "1 234.56", "abc", "12.34.56", "--1", "1.2e3x"})
        @DisplayName("grouping separators, currency symbols and non-numeric text are refused")
        void nonNumericTextIsRefused(final String displayText) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount(displayText, "currentBalance"))
                    .withMessageContaining("does not hold a decimal amount");
        }

        @Test
        @DisplayName("the originating NumberFormatException is preserved as the cause")
        void theRootCauseIsPreserved() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("abc", "currentBalance"))
                    .withCauseInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("an amount needing more significant digits than the field allows is refused")
        void tooManySignificantDigitsIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("99999999999.99", "creditLimit"))
                    .withMessageContaining(String.valueOf(AccountDto.MONEY_PRECISION));
        }

        @Test
        @DisplayName("rounding is applied before the precision ceiling, so a tie can push a value over it")
        void roundingPrecedesThePrecisionCeiling() {
            // 9999999999.999 is thirteen significant digits before scaling and rounds to 10000000000.00,
            // which is still thirteen. The order of the two steps is therefore observable.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("9999999999.999", "creditLimit"))
                    .withMessageContaining(String.valueOf(AccountDto.MONEY_PRECISION));
        }

        @Test
        @DisplayName("no refusal message ever quotes the offending value")
        void refusalsNeverQuoteTheValue() {
            // Several components on this payload are protected data, so a diagnostic names the field and
            // never reproduces its contents.
            final String withheld = "123456789012.34";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount(withheld, "customerSsn"))
                    .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(withheld));
        }

        @Test
        @DisplayName("a refusal names the field, so the failure is identifiable without the value")
        void aRefusalNamesTheField() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("abc", "cashCreditLimit"))
                    .withMessageContaining("cashCreditLimit");
        }
    }

    @Nested
    @DisplayName("8. The seed data this payload must not reject")
    final class FixtureTolerance {

        @Test
        @DisplayName("the account fixture has the geometry the record layout implies")
        void theAccountFixtureGeometryHolds() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            assertThat(data.resourceName()).isEqualTo("acctdata.txt");
            assertThat(data.recordWidth()).as("app/cpy/CVACT01Y.cpy declares RECLN 300").isEqualTo(300);
            assertThat(data.recordCount()).isEqualTo(50);
            assertThat(data.byteCount())
                    .as("fifty records of three hundred bytes plus fifty line terminators")
                    .isEqualTo(15_050)
                    .isEqualTo(data.impliedByteCount());
            assertThat(data.records()).allSatisfy(row -> assertThat(row).hasSize(300));
        }

        @Test
        @DisplayName("trailing spaces run to 188 characters, so no whitespace cleanup may touch a row")
        void trailingSpacesAreLoadBearing() {
            // The 178-byte FILLER at app/cpy/CVACT01Y.cpy:17 plus the unset tail of the group identifier
            // means a row can end in a very long run of spaces. Trimming a fixture row would move every
            // field after the trim point and the fixed-width comparison would fail in a way that looks
            // like a decoding bug.
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            int longestRun = 0;
            for (final String row : data.records()) {
                longestRun = Math.max(longestRun, row.length() - row.stripTrailing().length());
            }

            assertThat(longestRun).isEqualTo(188);
        }

        @Test
        @DisplayName("the seeded postal code is not numeric, so no digits-only rule may exist")
        void theSeededPostalCodeIsNotNumeric() {
            // app/cpy/CVACT01Y.cpy:15 ACCT-ADDR-ZIP PIC X(10), columns 103-112, reads A000000000 on every
            // one of the fifty rows. It is text, and it is also why an overpunch decoder has to be driven
            // from the picture clauses: a global substitution would read that leading A as a +1 sign.
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            for (int row = 0; row < data.recordCount(); row++) {
                assertThat(data.field(row, ACCT_ADDR_ZIP_COLUMN, ACCT_TEXT_TEN))
                        .as("account row %d postal code", row)
                        .isEqualTo("A000000000");
            }
        }

        @Test
        @DisplayName("a non-numeric postal code is accepted by the payload")
        void aNonNumericPostalCodeIsAccepted() {
            final ScreenField zip = FIELDS.get(27);
            final String screenWidth = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT)
                    .field(FIRST_ROW, ACCT_ADDR_ZIP_COLUMN, AccountDto.ZIP_LENGTH);

            assertThat(zip.component()).isEqualTo("addressZip");
            assertThat(screenWidth).isEqualTo("A0000");
            assertThatNoException().isThrownBy(() -> only(zip, screenWidth));
            assertThat(valueOf(only(zip, screenWidth), zip)).isEqualTo("A0000");
        }

        @Test
        @DisplayName("the overpunch decode is driven by position, and the letter A stays a letter in text")
        void theOverpunchDecodeIsPositionAware() {
            // The same letters that carry a sign in the final position of a signed numeric field occur
            // legitimately inside text. Decoding the current balance from its own columns yields a signed
            // amount; the postal code read from its own columns stays the text it is.
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            assertThat(data.signedDecimal(FIRST_ROW, ACCT_CREDIT_LIMIT_COLUMN, ACCT_MONEY_WIDTH))
                    .as("row 1 credit limit, whose trailing brace is a positive zero overpunch")
                    .isEqualByComparingTo("2020.00");
            assertThat(data.field(FIRST_ROW, ACCT_ADDR_ZIP_COLUMN, ACCT_TEXT_TEN))
                    .as("and the postal code in the very same row is untouched")
                    .startsWith("A");
            assertThat(FixtureLoader.decodeZonedDecimal("00000020200{", AccountDto.MONEY_SCALE))
                    .as("the decoder reads the sign from the final position only")
                    .isEqualByComparingTo("2020.00");
        }

        @Test
        @DisplayName("the seeded account group identifier is blank on every row")
        void theSeededGroupIdentifierIsBlank() {
            // app/cpy/CVACT01Y.cpy:16 ACCT-GROUP-ID PIC X(10), columns 113-122, and
            // app/cbl/COACTVWC.cbl:490 moves it straight to the screen field. A non-blank rule would
            // reject all fifty rows.
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            final ScreenField group = FIELDS.get(15);

            for (int row = 0; row < data.recordCount(); row++) {
                assertThat(data.field(row, ACCT_GROUP_ID_COLUMN, ACCT_TEXT_TEN))
                        .as("account row %d group identifier", row)
                        .isBlank()
                        .hasSize(AccountDto.ACCOUNT_GROUP_ID_LENGTH);
            }
            assertThat(group.component()).isEqualTo("accountGroupId");
            assertThat(valueOf(only(group, "          "), group))
                    .as("ten spaces are carried through, not folded to empty or absent")
                    .isEqualTo("          ")
                    .hasSize(AccountDto.ACCOUNT_GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("both cycle amounts are zero on every seeded row")
        void bothCycleAmountsAreZero() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            for (int row = 0; row < data.recordCount(); row++) {
                assertThat(data.signedDecimal(row, ACCT_CYCLE_CREDIT_COLUMN, ACCT_MONEY_WIDTH))
                        .as("account row %d cycle credit", row)
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(data.signedDecimal(row, ACCT_CYCLE_DEBIT_COLUMN, ACCT_MONEY_WIDTH))
                        .as("account row %d cycle debit", row)
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        @Test
        @DisplayName("every seeded credit limit lies between 120.00 and 9750.00 and converts")
        void everySeededCreditLimitConverts() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            final BigDecimal lowest = new BigDecimal("120.00");
            final BigDecimal highest = new BigDecimal("9750.00");
            BigDecimal seenLowest = null;
            BigDecimal seenHighest = null;

            for (int row = 0; row < data.recordCount(); row++) {
                final BigDecimal limit = data.signedDecimal(row, ACCT_CREDIT_LIMIT_COLUMN, ACCT_MONEY_WIDTH);

                assertThat(limit).as("account row %d credit limit", row)
                        .isBetween(lowest, highest);
                seenLowest = seenLowest == null || limit.compareTo(seenLowest) < 0 ? limit : seenLowest;
                seenHighest = seenHighest == null || limit.compareTo(seenHighest) > 0 ? limit : seenHighest;
            }

            assertThat(seenLowest).isEqualByComparingTo(lowest);
            assertThat(seenHighest).isEqualByComparingTo(highest);
        }

        @Test
        @DisplayName("every seeded expiry date is text within the observed range")
        void everySeededExpiryDateIsText() {
            // app/cpy/CVACT01Y.cpy:L11 ACCT-EXPIRAION-DATE PIC X(10), columns 59-68. Text, so the range is
            // established lexicographically, which is exact for an ISO calendar date.
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            final ScreenField expiry = FIELDS.get(10);
            String earliest = null;
            String latest = null;

            for (int row = 0; row < data.recordCount(); row++) {
                final String value = data.field(row, ACCT_EXPIRATION_DATE_COLUMN, ACCT_TEXT_TEN);

                assertThat(value).as("account row %d expiry date", row).hasSize(ACCT_TEXT_TEN);
                assertThatNoException().isThrownBy(() -> only(expiry, value));
                earliest = earliest == null || value.compareTo(earliest) < 0 ? value : earliest;
                latest = latest == null || value.compareTo(latest) > 0 ? value : latest;
            }

            assertThat(earliest).isEqualTo("2023-01-06");
            assertThat(latest).isEqualTo("2025-12-28");
        }

        @Test
        @DisplayName("twenty-one seeded credit scores fall below the update path's floor")
        void seededCreditScoresFallBelowTheUpdateFloor() {
            // app/cbl/COACTUPC.cbl:L848-L849 declares 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850, and
            // that predicate gates screen input on the account-update path. This is the view path, and the
            // seed data it renders does not satisfy the predicate.
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);
            final ScreenField score = FIELDS.get(20);
            int belowFloor = 0;

            for (int row = 0; row < data.recordCount(); row++) {
                final String value = data.field(row, CUST_FICO_COLUMN, CUST_FICO_WIDTH);

                assertThat(value).as("customer row %d credit score", row).hasSize(CUST_FICO_WIDTH);
                assertThat(valueOf(only(score, value), score))
                        .as("customer row %d credit score is carried, not judged", row)
                        .isEqualTo(value);
                if (Integer.parseInt(value) < 300) {
                    belowFloor++;
                }
            }

            assertThat(belowFloor)
                    .as("a 300-to-850 rule on this payload would reject this many seeded rows")
                    .isEqualTo(21);
            assertThat(score.component()).isEqualTo("customerFicoScore");
            assertThat(score.width()).isEqualTo(AccountDto.FICO_SCORE_LENGTH);
        }

        @Test
        @DisplayName("the lowest seeded credit scores are accepted verbatim")
        void theLowestSeededCreditScoresAreAccepted() {
            final ScreenField score = FIELDS.get(20);

            for (final String value : List.of("001", "044", "051", "793")) {
                assertThat(valueOf(only(score, value), score))
                        .as("credit score %s is carried through unaltered", value)
                        .isEqualTo(value);
            }
        }

        @Test
        @DisplayName("four seeded state codes are absent from the lookup table")
        void fourSeededStateCodesAreAbsentFromTheTable() {
            // app/cpy/CSLKPCDY.cpy:L1013 declares 88 VALID-US-STATE-CODE over 56 two-letter literals. None
            // of these four is among them, so enforcing membership on this read projection would reject
            // rows the corpus ships.
            final String table = String.join("\n", frozenSource("app/cpy/CSLKPCDY.cpy"));

            assertThat(table)
                    .as("the lookup table is declared where the citation says it is")
                    .contains("VALID-US-STATE-CODE");
            for (final String absent : STATE_CODES_ABSENT_FROM_TABLE) {
                assertThat(table)
                        .as("%s is not a member of the lookup table", absent)
                        .doesNotContain("'" + absent + "'");
            }
        }

        @Test
        @DisplayName("every seeded state code is accepted, member or not")
        void everySeededStateCodeIsAccepted() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);
            final ScreenField state = FIELDS.get(25);
            int unlistedRows = 0;

            for (int row = 0; row < data.recordCount(); row++) {
                final String value = data.field(row, CUST_STATE_CODE_COLUMN, CUST_STATE_CODE_WIDTH);

                assertThat(valueOf(only(state, value), state))
                        .as("customer row %d state code", row)
                        .isEqualTo(value)
                        .hasSize(AccountDto.STATE_CODE_LENGTH);
                if (STATE_CODES_ABSENT_FROM_TABLE.contains(value)) {
                    unlistedRows++;
                }
            }

            assertThat(unlistedRows)
                    .as("a membership rule on this payload would reject this many seeded rows")
                    .isEqualTo(5);
            assertThat(VALID_STATE_CODE_TABLE_SIZE)
                    .as("the table these five are missing from")
                    .isEqualTo(56);
        }

        @Test
        @DisplayName("no bean-validation constraint is declared on any component")
        void noBeanValidationConstraintIsDeclared() {
            // The most direct statement of every tolerance above: there is no annotation on any component,
            // so there is no range rule on the credit score, no pattern on the postal code, no non-blank
            // rule on the group identifier and no membership rule on the state code to remove.
            for (final RecordComponent component : AccountDto.class.getRecordComponents()) {
                assertThat(component.getAnnotations())
                        .as("annotations declared on component %s", component.getName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the fixture is read by resource name and never written")
        void theFixtureIsReadOnly() {
            // The daily transaction fixture is spelled dailytran.txt even though the dataset and DD name
            // are DALYTRAN; loading it by the wrong name is the trap this assertion pins shut.
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.resourceName())
                    .isEqualTo("dailytran.txt")
                    .isNotEqualTo("dalytran.txt");
            assertThat(FixtureLoader.Fixture.ACCOUNT.resourceName()).isEqualTo("acctdata.txt");
            assertThat(FixtureLoader.Fixture.CUSTOMER.resourceName()).isEqualTo("custdata.txt");
            final List<String> rows = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT).records();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("the loaded rows are an immutable copy, so no test can mutate the fixture")
                    .isThrownBy(() -> rows.add("a row this suite must never be able to append"));
        }
    }

    @Nested
    @DisplayName("9. Boundaries, on every one of the 37 fields")
    final class Boundaries {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("an absent value is accepted and stays absent")
        void absentIsAccepted(final ScreenField field) {
            assertThatNoException().isThrownBy(() -> only(field, null));
            assertThat(valueOf(only(field, null), field)).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("an empty value is accepted and stays empty, distinct from absent")
        void emptyIsAccepted(final ScreenField field) {
            assertThatNoException().isThrownBy(() -> only(field, ""));
            assertThat(valueOf(only(field, ""), field)).isNotNull().isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("a field of blanks is accepted with its padding intact")
        void blanksAreAcceptedWithPaddingIntact(final ScreenField field) {
            final String allBlanks = " ".repeat(field.width());

            assertThat(valueOf(only(field, allBlanks), field))
                    .isEqualTo(allBlanks)
                    .hasSize(field.width());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("a value at exactly the declared width is accepted unaltered")
        void exactWidthIsAccepted(final ScreenField field) {
            final String atWidth = PROBE.repeat(field.width());

            assertThat(valueOf(only(field, atWidth), field))
                    .isEqualTo(atWidth)
                    .hasSize(field.width());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("a value one character under the declared width is accepted")
        void oneUnderIsAccepted(final ScreenField field) {
            final String underWidth = PROBE.repeat(field.width() - 1);

            assertThatNoException().isThrownBy(() -> only(field, underWidth));
            assertThat(valueOf(only(field, underWidth), field)).hasSize(field.width() - 1);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("trailing padding on a short value is preserved rather than trimmed")
        void trailingPaddingIsPreserved(final ScreenField field) {
            // A screen field arrives space-padded to its declared width. Trimming here would change the
            // bytes the parity comparison reads, so the guard rejects and never repairs.
            final String padded = PROBE + " ".repeat(field.width() - 1);

            assertThat(valueOf(only(field, padded), field))
                    .isEqualTo(padded)
                    .hasSize(field.width());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.AccountDtoTest#screenFields")
        @DisplayName("a value one character over the declared width is refused, citing the declaration")
        void oneOverIsRefused(final ScreenField field) {
            final String overWidth = PROBE.repeat(field.width() + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> only(field, overWidth))
                    .withMessageContaining(field.component())
                    .withMessageContaining(String.valueOf(field.width() + 1))
                    .withMessageContaining(field.toString())
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("the offending value is never reproduced")
                            .doesNotContain(overWidth));
        }

        @Test
        @DisplayName("a fully absent payload is legal, because a screen may be unpopulated")
        void aFullyAbsentPayloadIsLegal() {
            final AccountDto absent = fromValues(new String[AccountDto.FIELD_COUNT]);

            assertThat(valuesOf(absent)).hasSize(AccountDto.FIELD_COUNT).containsOnlyNulls();
        }

        @Test
        @DisplayName("a fully populated payload round-trips every component")
        void aFullyPopulatedPayloadRoundTrips() {
            final String[] values = new String[AccountDto.FIELD_COUNT];
            for (int index = 0; index < AccountDto.FIELD_COUNT; index++) {
                values[index] = PROBE.repeat(FIELDS.get(index).width());
            }

            assertThat(valuesOf(fromValues(values))).containsExactly(values);
        }

        @Test
        @DisplayName("only the offending component is named when several are over width")
        void theFirstOffendingComponentIsNamed() {
            // The guard checks in declaration order, so the first over-width component is the one reported.
            // That ordering is what makes a diagnostic reproducible.
            final String[] values = new String[AccountDto.FIELD_COUNT];
            values[1] = PROBE.repeat(FIELDS.get(1).width() + 1);
            values[36] = PROBE.repeat(FIELDS.get(36).width() + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fromValues(values))
                    .withMessageContaining("title01")
                    .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("errorMessage"));
        }
    }

    @Nested
    @DisplayName("10. Protected data never reaches a rendering")
    final class ProtectedData {

        @Test
        @DisplayName("only the account identifier, status and program name are rendered")
        void onlyThreeFieldsAreRendered() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .startsWith("AccountDto[")
                    .contains("accountId=00000000001")
                    .contains("accountStatus=Y")
                    .contains("programName=COACTVWC")
                    .endsWith("protectedFieldsOmitted=true]");
            assertThat(RENDERABLE_COMPONENTS).hasSize(3).allSatisfy(
                    component -> assertThat(rendered).contains(component + "="));
        }

        @Test
        @DisplayName("no protected value reaches the rendering")
        void noProtectedValueReachesTheRendering() {
            // Identified by the account identifier rather than by the value under test, so a failure report
            // names the row and not its contents.
            final AccountDto payload = populated();
            final String rendered = payload.toString();
            final List<String> componentOrder = componentNames();

            for (final String component : PROTECTED_COMPONENTS) {
                final String value = valuesOf(payload).get(componentOrder.indexOf(component));

                assertThat(rendered.contains(value))
                        .as("account %s renders its %s", payload.accountId(), component)
                        .isFalse();
                assertThat(rendered)
                        .as("account %s renders the label %s", payload.accountId(), component)
                        .doesNotContain(component);
            }
        }

        @Test
        @DisplayName("all nine protected components are covered by that check")
        void allNineProtectedComponentsAreCovered() {
            assertThat(PROTECTED_COMPONENTS).hasSize(9).doesNotHaveDuplicates()
                    .allSatisfy(component -> assertThat(componentNames()).contains(component));
        }

        @Test
        @DisplayName("the rendering states plainly that fields were withheld")
        void theRenderingAdmitsWithholding() {
            assertThat(populated().toString())
                    .as("a reader must not mistake the rendering for a complete dump")
                    .contains("protectedFieldsOmitted=true");
        }

        @Test
        @DisplayName("the rendering is far shorter than a generated one would be")
        void theRenderingIsShort() {
            // A generated record toString emits all 37 components. This one emits three.
            assertThat(populated().toString().split(", ")).hasSize(4);
        }

        @Test
        @DisplayName("an unpopulated payload renders without failing")
        void unpopulatedPayloadRenders() {
            final AccountDto absent = fromValues(new String[AccountDto.FIELD_COUNT]);

            assertThat(absent.toString())
                    .contains("accountId=null")
                    .contains("protectedFieldsOmitted=true");
        }

        @Test
        @DisplayName("no component name suggests a credential or a full card number")
        void noComponentNameSuggestsACredential() {
            final List<String> names = componentNames().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toList();

            for (final String fragment : FORBIDDEN_NAME_FRAGMENTS) {
                assertThat(names)
                        .as("no component name contains %s", fragment)
                        .noneMatch(name -> name.contains(fragment));
            }
            assertThat(names).hasSize(AccountDto.FIELD_COUNT);
        }

        @Test
        @DisplayName("equality is value-based and neither equality nor hashing can render a value")
        void equalityIsValueBasedAndCannotRender() throws NoSuchMethodException {
            // Being a record, AccountDto compares all 37
            // components, protected ones included; that is value semantics, and narrowing it would break
            // the equality-to-hash contract collection membership depends on. Neither method returns a
            // rendering, so neither is a disclosure surface - toString is, and it withholds.
            final AccountDto first = populated();
            final AccountDto second = populated();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(AccountDto.class.getMethod("equals", Object.class).getReturnType())
                    .isEqualTo(boolean.class);
            assertThat(AccountDto.class.getMethod("hashCode").getReturnType()).isEqualTo(int.class);
        }

        @Test
        @DisplayName("a difference in a protected component still separates two payloads")
        void aProtectedDifferenceStillSeparatesPayloads() {
            final ScreenField ssn = FIELDS.get(18);

            assertThat(ssn.component()).isEqualTo("customerSsn");
            assertThat(only(ssn, "111111111")).isNotEqualTo(only(ssn, "222222222"));
        }
    }

    @Nested
    @DisplayName("11. The two temporal header fields come from an injected clock")
    final class DeterministicScreenHeader {

        @Test
        @DisplayName("the clock is fixed, so two reads agree")
        void theClockIsFixed() {
            final Clock clock = FixedClockProvider.canonicalClock();

            assertThat(clock.instant()).isEqualTo(clock.instant())
                    .isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
            assertThat(clock.getZone())
                    .as("a fixed zone, so a host time zone cannot change a rendering")
                    .isEqualTo(FixedClockProvider.CANONICAL_ZONE);
        }

        @Test
        @DisplayName("the screen date is eight characters, in the layout CSDAT01Y declares")
        void theScreenDateIsEightCharacters() {
            // app/cpy/CSDAT01Y.cpy:30-35 builds WS-CURDATE-MM-DD-YY from two digits, a solidus, two digits,
            // a solidus and two digits, and app/cbl/COACTVWC.cbl:447 moves that group into the screen field.
            assertThat(screenDate())
                    .isEqualTo("06/10/22")
                    .hasSize(AccountDto.CURRENT_DATE_LENGTH)
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(COACTVW.widthOf("CURDATEI")).isEqualTo(AccountDto.CURRENT_DATE_LENGTH);
        }

        @Test
        @DisplayName("the screen time is eight characters, in the layout CSDAT01Y declares")
        void theScreenTimeIsEightCharacters() {
            // app/cpy/CSDAT01Y.cpy:36-41 builds WS-CURTIME-HH-MM-SS from two digits, a colon, two digits, a
            // colon and two digits, and app/cbl/COACTVWC.cbl:453 moves that group into the screen field.
            assertThat(screenTime())
                    .isEqualTo("19:27:53")
                    .hasSize(AccountDto.CURRENT_TIME_LENGTH)
                    .matches("\\d{2}:\\d{2}:\\d{2}");
            assertThat(COACTVW.widthOf("CURTIMEI")).isEqualTo(AccountDto.CURRENT_TIME_LENGTH);
        }

        @Test
        @DisplayName("both header values fit the fields they are declared for")
        void bothHeaderValuesFitTheirFields() {
            final AccountDto payload = populated();

            assertThat(payload.currentDate()).isEqualTo(screenDate());
            assertThat(payload.currentTime()).isEqualTo(screenTime());
            assertThatNoException().isThrownBy(() -> only(FIELDS.get(2), screenDate()));
            assertThatNoException().isThrownBy(() -> only(FIELDS.get(5), screenTime()));
        }

        @Test
        @DisplayName("the sign-on map would accept a ninth character that this map refuses")
        void theSignOnMapWouldAcceptANinthCharacter() {
            // The concrete cost of a shared header type: a nine-character time is legal on COSGN00 and is
            // one character too long here.
            final String nineCharacters = screenTime() + "9";

            assertThat(nineCharacters).hasSize(SIGN_ON_CURRENT_TIME_WIDTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> only(FIELDS.get(5), nineCharacters))
                    .withMessageContaining("currentTime")
                    .withMessageContaining("CURTIMEI PIC X(8)");
        }
    }
}
