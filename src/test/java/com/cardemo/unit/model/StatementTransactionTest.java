/*
 * ******************************************************************
 * Program     : StatementTransactionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the 350-byte statement record geometry, the
 *               two-byte processing-timestamp truncation the legacy
 *               sort projection introduces, the 133-byte report line
 *               layouts with their two distinct sign conventions, the
 *               three legacy timestamp shapes, and the fixture parity
 *               of all 300 daily transaction records.
 * Source      : app/cpy/COSTM01.CPY (01 TRNX-RECORD L20, 32-byte
 *               TRNX-KEY, 350 total) + app/cpy/CVTRA07Y.cpy report
 *               lines @ 7756d89
 *               app/cpy/CVTRA05Y.cpy:L4-L18   (base offset map)
 *               app/jcl/CREASTMT.JCL:L30,L32  (KEYS 32 / RECORDSIZE 350)
 *               app/jcl/CREASTMT.JCL:L53,L54  (SORT and OUTREC)
 *               app/jcl/CREASTMT.JCL:L69,L73,L89,L94 (LRECL 80 / 100)
 *               app/cbl/CBSTM03A.CBL:L71-L83,L149,L225-L233,L316-L338,
 *               L347-L351,L416-L456,L818-L853
 *               app/cbl/CBSTM03B.CBL          (file-service contract)
 *               app/cbl/CBTRN03C.cbl:L131     (batch page size)
 *               app/cbl/CBTRN02C.cbl:L149-L175 (batch timestamp)
 *               app/cbl/CBACT04C.cbl:L613-L625 ('System' source)
 *               app/cbl/COBIL00C.cbl:L249-L267 ('POS TERM' source)
 *               app/cbl/COACTUPC.cbl:L505-L508,L1667-L1678 (tri-state)
 *               app/cpy/COCOM01Y.cpy:L43-L44  (session state omitted)
 *               app/cpy/CSSETATY.cpy          (OK / NOT-OK / BLANK)
 *               app/proc/TRANREPT.prc:L1,L76  (REPROC quirk, LRECL 133)
 *               app/data/ASCII/dailytran.txt  (300 x 350 fixture)
 *               app/data/ASCII/cardxref.txt   (50 x 36 fixture)
 *               frozen at commit 7756d89
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

import com.cardemo.model.dto.StatementTransaction;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for {@link StatementTransaction} - the projected statement record and the report
 * lines drawn from it.
 *
 * <h2>What this test does</h2>
 *
 * <p>This is the one data transfer object in the package whose contract is <strong>byte geometry</strong>
 * rather than a BMS symbolic-map field census, so this test asserts measurements and literals rather than
 * screen fields. Its values cross a byte-exact boundary: the statement outputs are written at
 * {@value StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH} and
 * {@value StatementTransaction#STATEMENT_HTML_RECORD_LENGTH} bytes per line and the transaction report at
 * {@value StatementTransaction#REPORT_LINE_LENGTH}, and a width that is wrong by one shifts every subsequent
 * field on the line.
 *
 * <p>Widths are therefore asserted as <em>arithmetic identities</em> rather than as isolated numbers - the
 * fourteen component widths must sum to the declared record length, the key must equal its two parts, the
 * remainder must equal the record less the key, and each totals line's label and dots must pad to the same
 * figure. An identity catches a compensating pair of errors that a list of independent equality checks would
 * not.
 *
 * <p>Every locator this test relies on, all frozen at commit {@code 7756d89}:
 * {@code app/cpy/COSTM01.CPY:L20-L36} (the record, its 32-byte key and its 318-byte remainder);
 * {@code app/cpy/CVTRA05Y.cpy:L4-L18} (the base offset map the projection reads from);
 * {@code app/cpy/CVTRA07Y.cpy:L4-L13} (the report name header), {@code :L15-L31} (the detail line),
 * {@code :L30} (the minus-leading amount mask), {@code :L33-L46} (the column header), {@code :L48} (the
 * 133-byte rule) and {@code :L50-L54} (the page totals line with its plus-leading mask);
 * {@code app/jcl/CREASTMT.JCL:L30} ({@code KEYS(32 0)}), {@code :L32} ({@code RECORDSIZE(350 350)}),
 * {@code :L53} (the two-key sort), {@code :L54} (the {@code OUTREC} projection), {@code :L69}, {@code :L73}
 * and {@code :L89} ({@code LRECL=80}) and {@code :L94} ({@code LRECL=100});
 * {@code app/cbl/CBSTM03A.CBL:L71-L83} (the file-service call area), {@code :L149} (the hundred-character HTML
 * field), {@code :L225-L233} (the 51-by-10 table), {@code :L316-L338} (the redundant index assignment),
 * {@code :L347-L351} (the call idiom whose success set is {@code '00'} or {@code '04'}), {@code :L416-L456}
 * (the linear scan with the card-number early exit) and {@code :L818-L853} (the build loop and its final
 * flush); {@code app/cbl/CBSTM03B.CBL} (the four-file by six-operation matrix behind that call area);
 * {@code app/cbl/CBTRN03C.cbl:L131} (the batch page size that must not appear here);
 * {@code app/cbl/COBIL00C.cbl:L249-L267} (the online timestamp build);
 * {@code app/cbl/CBACT04C.cbl:L613-L625} and {@code app/cbl/CBTRN02C.cbl:L149-L175} (the batch timestamp
 * build); {@code app/cpy/COCOM01Y.cpy:L43-L44} (the session state deliberately absent here);
 * {@code app/cpy/CSSETATY.cpy} with {@code app/cbl/COACTUPC.cbl:L505-L508} and {@code :L1667-L1678} (the
 * three-state model and the gated cross-field edit); {@code app/proc/TRANREPT.prc:L1} and {@code :L76} (the
 * procedure-name quirk and {@code LRECL=133}); and the two fixtures
 * {@code app/data/ASCII/dailytran.txt} and {@code app/data/ASCII/cardxref.txt}.
 *
 * <h2>The truncation is the subtle part</h2>
 *
 * <p>The upstream sort projects with {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at
 * {@code app/jcl/CREASTMT.JCL:L54}, copying fifty bytes from offset 279 back to offset 279. That span covers
 * the whole twenty-six-byte originating timestamp and only the <em>first twenty-four</em> of the twenty-six
 * processing-timestamp bytes, and it drops the trailing twenty-byte filler entirely. So the projected
 * processing timestamp arrives two characters short. That is a property of the frozen job control, not a
 * defect in the Java, and it must be reproduced rather than repaired - a "fixed" implementation would differ
 * from the legacy baseline in a way that looks like a Java bug. {@link ProjectionTruncation} pins it.
 *
 * <h2>Two legacy quirks preserved, three legacy defects logged</h2>
 *
 * <p>Preserved: the account totals label reads {@code "Account Total"} even though the control break that
 * emits it triggers on the card number, and the redundant index assignment at
 * {@code app/cbl/CBSTM03A.CBL:L316-L338} - where the mainline sets the outer subscript immediately before a
 * {@code VARYING} loop re-initialises it - stays in the frozen source. The label is reproduced verbatim
 * because the report text is compared byte-for-byte against the baseline. The redundant assignment is
 * documented here and deliberately <strong>not</strong> reproduced as Java, because a Java restatement of it
 * would be dead code that Rule 1 clause B forbids while adding nothing to parity.
 *
 * <p>Logged and deliberately <strong>not</strong> repaired, because repairing them would move the baseline:
 * the corrupted {@code STMTFILE} DD card at {@code app/jcl/CREASTMT.JCL:L90}, where {@code SPACE=} is
 * followed by fragments of unrelated text; the 80-versus-100 record-length mismatch for the HTML output
 * between the pre-delete step at {@code :L69} and the execution step at {@code :L94}, resolved in favour of
 * the execution step so the HTML width stays 100; and the procedure whose internal name differs from the
 * member the execute statement resolves, {@code app/proc/TRANREPT.prc:L1} declaring {@code //REPROC PROC}
 * while {@code EXEC PROC=TRANREPT} resolves {@code TRANREPT}. {@link LoggedLegacyDefects} states each.
 *
 * <h2>The removed 510-transaction ceiling is a labelled deviation, not parity</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L225-L233} declares the working set as
 * {@value StatementTransaction#LEGACY_MAX_CARDS_PER_RUN} card entries of
 * {@value StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_CARD} transactions each - a hard ceiling of
 * {@value StatementTransaction#LEGACY_MAX_TRANSACTIONS_PER_RUN} transactions per run - and the build loop at
 * {@code :L818-L848} increments both subscripts with no bounds check whatsoever, a latent storage-overrun
 * defect. The Java target streams into an unbounded {@link List} instead, which removes a silent
 * truncation-and-corruption hazard.
 *
 * <p><strong>That removal is a deliberate, labelled deviation and not equivalence.</strong> Pretending the
 * ceiling was preserved would be false; pretending its removal is invisible would be worse. The tradeoff is
 * therefore justified in writing <em>here</em>, in this docstring, which is the one place it cannot drift
 * away from the code it governs; it is additionally owed an entry in the planned {@code DECISION_LOG.md}
 * under the statement-generation capacity heading. The three legacy figures remain published as constants so
 * the historical limit stays discoverable, and they are owed an entry in the planned
 * {@code TRACEABILITY_MATRIX.md} as the capacity of the legacy program. Neither register exists in this
 * branch, so both references are forward references rather than citations.
 * {@link PreservedLegacyQuirks} asserts that the figures are carried and that no cap is enforced.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Built by the root {@code pom.xml} against Java 25 with {@code -Xlint:all}, {@code -Werror} and
 * {@code failOnWarning}, so a raw type or a deprecated call in this file fails the build outright. Run this
 * tier with {@code ./mvnw -B -ntp test}, or this class alone with
 * {@code ./mvnw -B -ntp -Dtest=StatementTransactionTest test}; coverage is enforced at {@code verify}.
 *
 * <p><strong>Surefire, not Failsafe, binds this class.</strong> The Surefire 3.5.4 configuration includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and {@code **}{@code /e2e/**},
 * so a class moved out of {@code src/test/java/com/cardemo/unit} is collected by neither plugin and silently
 * never runs - a green build with both plugins reporting success and no warning anywhere. This class must
 * stay where it is.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This is a pure-JVM tier. No container, no Spring context, no database, no object store and no live
 * endpoint is reachable, so no host, port, JDBC URL or cloud endpoint literal appears anywhere in this file.
 *
 * <ul>
 *   <li><strong>Time is injected, never read.</strong> Every timestamp assertion resolves through
 *       {@code FixedClockProvider}, whose canonical instant is fixed at {@code 2022-06-10T19:27:53Z} in UTC.
 *       {@code Instant.now()}, {@code LocalDate.now()} and {@code System.currentTimeMillis()} appear nowhere.
 *       The canonical instant is not arbitrary: it renders to the single distinct originating timestamp that
 *       all 300 rows of {@code app/data/ASCII/dailytran.txt} carry.</li>
 *   <li><strong>Formatting is locale-free.</strong> {@link #renderEditMask(String, BigDecimal)} manipulates
 *       characters directly and consults no {@link Locale} at all, which is a stronger guarantee than passing
 *       {@link Locale#ROOT}. Where a caller must use a platform formatter,
 *       {@link EditMaskRendering#rootLocaleSeparatorsMatchTheCopybookMask()} shows that
 *       {@link Locale#ROOT} is the value that reproduces the copybook's separators.</li>
 *   <li><strong>Fixtures are read by classpath resource name only</strong>, through {@code FixtureLoader}, and
 *       are never copied, edited or written. The daily fixture's real name is {@code dailytran.txt} - the word
 *       spelled in full - even though the mainframe DD name is {@code DALYTRAN}; {@code dalytran.txt} does not
 *       exist and resolves to nothing.</li>
 *   <li><strong>No shared mutable state.</strong> Every constant here is immutable and every helper is a pure
 *       function, so tests cannot influence one another and Surefire's alphabetical run order is irrelevant to
 *       the outcome. The legacy 51-by-10 table was global mutable state; it has no Java counterpart.</li>
 *   <li>Mockito is on the test classpath but is deliberately unused: a record with no collaborators has
 *       nothing to stub, and a mock of a value type would assert the mock rather than the contract.</li>
 * </ul>
 *
 * <h2>Common failure modes</h2>
 *
 * <ul>
 *   <li>An unused import or a raw type fails the build rather than warning, because {@code -Werror} and
 *       {@code failOnWarning} both apply to test compilation.</li>
 *   <li>"Fixing" the two-byte projection truncation so the processing timestamp carries 26 significant
 *       characters. <strong>Blocker.</strong> The 24 is the contract; see {@link ProjectionTruncation}.</li>
 *   <li>Unifying {@link StatementTransaction#DETAIL_AMOUNT_MASK} and
 *       {@link StatementTransaction#TOTALS_AMOUNT_MASK} into one formatter. <strong>Blocker.</strong> A
 *       positive detail amount renders with a leading space; a positive total renders with a leading
 *       {@code '+'}.</li>
 *   <li>Treating {@code Z} as {@code 9}, so leading zeros render as zeros instead of spaces.
 *       <strong>High.</strong></li>
 *   <li>Harmonising the 80 and 100 statement output widths to a single value. <strong>High.</strong> They are
 *       measured, not chosen.</li>
 *   <li>Letting a card number reach {@code toString}. <strong>Blocker.</strong> The card number is the
 *       <em>first</em> component of the key, so a naive key-oriented rendering leaks it. No assertion message
 *       in this file interpolates a card number either; failures are identified by field name or row
 *       index.</li>
 *   <li>Spelling the fixture {@code dalytran.txt}. The load fails with a resource-not-found message rather
 *       than a wrong value, which at least fails loudly.</li>
 *   <li>Reflowing, trimming or converting a fixture. {@code FixtureLoader.load} rejects a fixture whose byte
 *       count, record count or record width has moved, so a whitespace cleanup is caught rather than silently
 *       destroying the fixed-width geometry.</li>
 * </ul>
 *
 * <h2>Stated as unavailable rather than invented</h2>
 *
 * <ul>
 *   <li><strong>A symbolic-map field census is {@code Not available}.</strong> This type has no BMS map: it is
 *       a batch projection, not a screen. Borrowing another map's census would be fabrication. Were a common
 *       header ever to surface here, note that {@code CURTIMEI} is {@code PIC X(8)} on sixteen maps and
 *       {@code PIC X(9)} only on {@code app/cpy-bms/COSGN00.CPY:54}, so no shared header helper is
 *       sound.</li>
 *   <li><strong>Any DDL claim is {@code Not available}.</strong> This is a data transfer object, not an
 *       entity; it maps to no table and {@code V1__create_schema.sql} declares nothing for it.</li>
 *   <li><strong>Baseline evidence for a zero report amount is {@code Not available}</strong>, which is why
 *       {@link #renderEditMask(String, BigDecimal)} models only the primary zero-suppression rule. See that
 *       method's contract.</li>
 *   <li><strong>No service-level objective exists</strong> anywhere in the corpus for statement generation, so
 *       none is asserted. Establishing one needs a measured baseline from a running system.</li>
 * </ul>
 */
@DisplayName("StatementTransaction: 350-byte geometry, a two-byte truncation and the fixture parity behind it")
class StatementTransactionTest {

    /**
     * A legacy batch timestamp at its declared 26-character width, {@code YYYY-MM-DD-HH.MM.SS.NNNNNN}.
     *
     * <p>Ten date characters, a separator, eight time characters, a separator and six fractional digits is
     * exactly 26. Writing one character more - which is easy to do by hand - trips the record's own width
     * guard and reports a fixture defect as a contract failure, so the value is stated once here and sized
     * through {@link #sizedTo26(String)} at every use.
     */
    private static final String LEGACY_TIMESTAMP = "2022-06-10-19.27.53.123000";

    /** The COBOL column, one-based, of {@code DALYTRAN-ID} at {@code app/cpy/CVTRA06Y.cpy:L5}. */
    private static final int BASE_TRANSACTION_ID_COLUMN = 1;

    /** The COBOL column, one-based, of {@code DALYTRAN-TYPE-CD} at {@code app/cpy/CVTRA06Y.cpy:L6}. */
    private static final int BASE_TYPE_CODE_COLUMN = 17;

    /** The COBOL column, one-based, of {@code DALYTRAN-CAT-CD} at {@code app/cpy/CVTRA06Y.cpy:L7}. */
    private static final int BASE_CATEGORY_CODE_COLUMN = 19;

    /** The COBOL column, one-based, of {@code DALYTRAN-SOURCE} at {@code app/cpy/CVTRA06Y.cpy:L8}. */
    private static final int BASE_SOURCE_COLUMN = 23;

    /** The COBOL column, one-based, of {@code DALYTRAN-DESC} at {@code app/cpy/CVTRA06Y.cpy:L9}. */
    private static final int BASE_DESCRIPTION_COLUMN = 33;

    /** The COBOL column, one-based, of {@code DALYTRAN-AMT} at {@code app/cpy/CVTRA06Y.cpy:L10}. */
    private static final int BASE_AMOUNT_COLUMN = 133;

    /** The COBOL column, one-based, of {@code DALYTRAN-MERCHANT-ID} at {@code app/cpy/CVTRA06Y.cpy:L11}. */
    private static final int BASE_MERCHANT_ID_COLUMN = 144;

    /** The COBOL column, one-based, of {@code DALYTRAN-MERCHANT-NAME} at {@code app/cpy/CVTRA06Y.cpy:L12}. */
    private static final int BASE_MERCHANT_NAME_COLUMN = 153;

    /** The COBOL column, one-based, of {@code DALYTRAN-MERCHANT-CITY} at {@code app/cpy/CVTRA06Y.cpy:L13}. */
    private static final int BASE_MERCHANT_CITY_COLUMN = 203;

    /** The COBOL column, one-based, of {@code DALYTRAN-MERCHANT-ZIP} at {@code app/cpy/CVTRA06Y.cpy:L14}. */
    private static final int BASE_MERCHANT_ZIP_COLUMN = 253;

    /** The COBOL column, one-based, of the trailing {@code FILLER} at {@code app/cpy/CVTRA06Y.cpy:L18}. */
    private static final int BASE_FILLER_COLUMN = 331;

    /**
     * The literal {@code TRAN-SOURCE} value the interest job writes, {@code app/cbl/CBACT04C.cbl:484}, padded
     * to the {@code PIC X(10)} width of {@code app/cpy/COSTM01.CPY:27}.
     */
    private static final String SOURCE_SYSTEM = "System    ";

    /**
     * The literal {@code TRAN-SOURCE} value the bill-payment program writes,
     * {@code app/cbl/COBIL00C.cbl:222}, padded to the {@code PIC X(10)} width.
     */
    private static final String SOURCE_POS_TERMINAL = "POS TERM  ";

    /**
     * The {@code TRAN-SOURCE} value 50 of the 300 fixture rows carry, padded to the {@code PIC X(10)} width.
     *
     * <p>It appears in <strong>no</strong> program literal anywhere in the corpus - it is data, not a coded
     * domain - which is exactly why {@code source} is an unconstrained {@code String} rather than an enum.
     */
    private static final String SOURCE_OPERATOR = "OPERATOR  ";

    /**
     * Sizes a candidate timestamp to exactly {@value StatementTransaction#PROCESSING_TIMESTAMP_LENGTH}
     * characters, truncating a longer value and space-padding a shorter one.
     *
     * <p>Delegates to {@code FixedClockProvider.passThroughTimestamp}, which is the Java counterpart of a COBOL
     * alphanumeric {@code MOVE} into a {@code PIC X(26)} receiving field: a long sender is truncated on the
     * right and a short sender is padded with spaces. Using it rather than an inline {@code substring} keeps
     * this test honest about the one operation the legacy code actually performs.
     *
     * @param candidate the sending field, never {@code null}
     * @return the candidate at exactly 26 characters
     */
    private static String sizedTo26(final String candidate) {
        return FixedClockProvider.passThroughTimestamp(candidate);
    }

    /**
     * Builds a {@link StatementTransaction} from one fixture row, applying the field reordering that
     * {@code app/jcl/CREASTMT.JCL:L54} performs.
     *
     * <p>This is the Java equivalent of the {@code OUTREC} projection: the card number is read from base column
     * {@value StatementTransaction#BASE_CARD_NUMBER_OFFSET} and placed first, and the transaction identifier is
     * read from base column 1 and placed second. Every other component is read at its base column and keeps its
     * relative order, so the result is the {@code COSTM01} layout built from a {@code CVTRA06Y} record.
     *
     * <p>The trailing filler is passed as {@code null} rather than as the fixture's twenty blanks, because the
     * projection stops at output position
     * {@value StatementTransaction#PROJECTION_LAST_WRITTEN_POSITION} and never writes it. That is asserted
     * separately against the fixture, so the two facts stay distinguishable.
     *
     * <p>Side effects: none. Reads the supplied snapshot and allocates one record.
     *
     * @param daily     the loaded daily transaction fixture, never {@code null}
     * @param recordRow the zero-based fixture row to project
     * @return the projected statement record for that row
     */
    private static StatementTransaction projectedFrom(final FixtureLoader.FixtureData daily,
            final int recordRow) {
        return new StatementTransaction(
                daily.field(recordRow, StatementTransaction.BASE_CARD_NUMBER_OFFSET,
                        StatementTransaction.CARD_NUMBER_LENGTH),
                daily.field(recordRow, BASE_TRANSACTION_ID_COLUMN,
                        StatementTransaction.TRANSACTION_ID_LENGTH),
                daily.field(recordRow, BASE_TYPE_CODE_COLUMN, StatementTransaction.TYPE_CODE_LENGTH),
                daily.field(recordRow, BASE_CATEGORY_CODE_COLUMN, StatementTransaction.CATEGORY_CODE_LENGTH),
                daily.field(recordRow, BASE_SOURCE_COLUMN, StatementTransaction.SOURCE_LENGTH),
                daily.field(recordRow, BASE_DESCRIPTION_COLUMN, StatementTransaction.DESCRIPTION_LENGTH),
                daily.signedDecimal(recordRow, BASE_AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH),
                daily.field(recordRow, BASE_MERCHANT_ID_COLUMN, StatementTransaction.MERCHANT_ID_LENGTH),
                daily.field(recordRow, BASE_MERCHANT_NAME_COLUMN, StatementTransaction.MERCHANT_NAME_LENGTH),
                daily.field(recordRow, BASE_MERCHANT_CITY_COLUMN, StatementTransaction.MERCHANT_CITY_LENGTH),
                daily.field(recordRow, BASE_MERCHANT_ZIP_COLUMN, StatementTransaction.MERCHANT_ZIP_LENGTH),
                daily.field(recordRow, StatementTransaction.BASE_TAIL_OFFSET,
                        StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH),
                daily.field(recordRow, StatementTransaction.BASE_TAIL_OFFSET
                        + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH,
                        StatementTransaction.PROCESSING_TIMESTAMP_LENGTH),
                null);
    }

    /**
     * Renders a decimal value through a COBOL numeric-edit picture, as a reference implementation of the two
     * masks {@code app/cpy/CVTRA07Y.cpy} declares.
     *
     * <p>The mask is exactly {@value StatementTransaction#AMOUNT_MASK_LENGTH} characters: a fixed sign
     * character, nine {@code Z} digit positions carrying two inserted commas, an inserted decimal point, and
     * two {@code Z} digit positions. Eleven digit positions in total, which is what {@code PIC S9(09)V99}
     * declares.
     *
     * <p>Three editing rules are modelled, and they are the whole reason a bespoke renderer exists rather than
     * a {@link java.text.DecimalFormat}:
     *
     * <ol>
     *   <li><strong>{@code Z} is zero suppression, not {@code 9}.</strong> A leading zero renders as a space,
     *       never as the character {@code '0'}.</li>
     *   <li><strong>An inserted comma inside the suppressed region is suppressed with it.</strong> A comma is
     *       emitted only once suppression has ended, so a small value carries no stray separator.</li>
     *   <li><strong>The sign position is fixed and mask-specific.</strong> A leading {@code '-'} renders as a
     *       space when the value is not negative; a leading {@code '+'} renders as {@code '+'}. This is the one
     *       difference between the two masks and it must not be unified.</li>
     * </ol>
     *
     * <p><strong>Scope of the zero rule, stated rather than assumed.</strong> Suppression terminates at the
     * first non-zero digit or at the decimal point, whichever comes first - the primary rule, which is what
     * this renderer implements. IBM Enterprise COBOL adds a refinement whereby an item whose digit positions
     * are <em>all</em> {@code Z} is blanked entirely when the value is zero, which would blank the decimal
     * digits too. That refinement is deliberately not modelled here, and the reason is evidence rather than
     * preference: {@code app/data/ASCII/dailytran.txt} contains <strong>no zero amount at all</strong> in any
     * of its 300 rows, so the frozen parity oracle never exercises a zero report amount and baseline evidence
     * for that rendering is <strong>{@code Not available}</strong>. Establishing it needs a legacy report
     * produced from an input that carries a zero amount. Under the primary rule the two masks still differ at
     * zero, a space against a {@code '+'}, so the "not the same formatter" contract remains provable.
     *
     * <p>Side effects: none. Consults no {@link Locale}, no default time zone and no system property, so its
     * output cannot vary with the host.
     *
     * @param mask  the copybook edit mask, either {@link StatementTransaction#DETAIL_AMOUNT_MASK} or
     *              {@link StatementTransaction#TOTALS_AMOUNT_MASK}
     * @param value the value to render; the sign is honoured and never normalised away
     * @return the rendered field, always {@value StatementTransaction#AMOUNT_MASK_LENGTH} characters
     * @throws IllegalArgumentException if the mask is not 15 characters, or if the value needs more than the
     *                                  eleven digit positions the mask provides
     */
    private static String renderEditMask(final String mask, final BigDecimal value) {
        if (mask.length() != StatementTransaction.AMOUNT_MASK_LENGTH) {
            throw new IllegalArgumentException("an edit mask must be "
                    + StatementTransaction.AMOUNT_MASK_LENGTH + " characters but this one is " + mask.length());
        }
        final BigDecimal scaled = value.setScale(StatementTransaction.AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        final String significant = scaled.abs().unscaledValue().toString();
        if (significant.length() > StatementTransaction.AMOUNT_PRECISION) {
            throw new IllegalArgumentException("the value needs " + significant.length()
                    + " digit positions but the mask provides only "
                    + StatementTransaction.AMOUNT_PRECISION);
        }
        final String digits = "0".repeat(StatementTransaction.AMOUNT_PRECISION - significant.length())
                + significant;
        final String integerDigits = digits.substring(0, StatementTransaction.AMOUNT_INTEGER_DIGITS);
        final String decimalDigits = digits.substring(StatementTransaction.AMOUNT_INTEGER_DIGITS);

        final StringBuilder rendered = new StringBuilder(StatementTransaction.AMOUNT_MASK_LENGTH);
        // The sign position is index 0 and is a fixed insertion character, not a digit position.
        if (scaled.signum() < 0) {
            rendered.append('-');
        } else {
            rendered.append(mask.charAt(0) == '+' ? '+' : ' ');
        }

        // Indices 1 to 11 are the integer region: nine Z positions with commas inserted after the third and
        // the sixth. The loop walks the mask rather than the digits, so the comma positions come from the
        // copybook rather than from an assumption about grouping.
        boolean suppressing = true;
        int digitCursor = 0;
        final int integerRegionEnd = mask.indexOf('.') - 1;
        for (int maskIndex = 1; maskIndex <= integerRegionEnd; maskIndex++) {
            if (mask.charAt(maskIndex) == 'Z') {
                final char digit = integerDigits.charAt(digitCursor);
                digitCursor++;
                if (suppressing && digit == '0') {
                    rendered.append(' ');
                } else {
                    suppressing = false;
                    rendered.append(digit);
                }
            } else {
                rendered.append(suppressing ? ' ' : mask.charAt(maskIndex));
            }
        }

        // Suppression terminates at the decimal point, so both decimal positions always render as digits.
        rendered.append('.').append(decimalDigits);
        return rendered.toString();
    }

    /**
     * Returns the declared record component names of a record type, in declaration order.
     *
     * @param recordType the record class to inspect
     * @return the component names, in declaration order
     */
    private static List<String> componentNames(final Class<?> recordType) {
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    /**
     * Returns the fully qualified type names of a record type's declared components, in declaration order.
     *
     * @param recordType the record class to inspect
     * @return the component type names, in declaration order
     */
    private static List<String> componentTypeNames(final Class<?> recordType) {
        final List<String> typeNames = new ArrayList<>();
        for (final RecordComponent component : recordType.getRecordComponents()) {
            typeNames.add(component.getType().getName());
        }
        return List.copyOf(typeNames);
    }

    /**
     * Returns the names of every {@code public static} field declared on a type.
     *
     * <p>Used by the absence proofs, which must distinguish a published <em>measurement</em> from a modelled
     * <em>field</em>. The distinction matters concretely:
     * {@link StatementTransaction#STATEMENT_HTML_RECORD_LENGTH} names HTML but is a byte width, whereas a
     * record component holding markup would be a real violation.
     *
     * @param type the class to inspect
     * @return the public static field names, unordered
     */
    private static Set<String> publicStaticFieldNames(final Class<?> type) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Field field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Returns the every record type this test treats as part of the statement contract - the record itself and
     * its four nested layouts.
     *
     * @return the five record types, outermost first
     */
    private static List<Class<?>> statementRecordTypes() {
        return List.of(StatementTransaction.class,
                StatementTransaction.Key.class,
                StatementTransaction.CardGroup.class,
                StatementTransaction.ReportDetailLine.class,
                StatementTransaction.ReportTotalsLine.class);
    }


    @Nested
    @DisplayName("1. The 350-byte record geometry closes as an arithmetic identity")
    class RecordGeometry {

        @Test
        @DisplayName("the fourteen component widths sum to the declared record length")
        void componentWidthsSumToTheRecord() {
            // 16+16+2+4+10+100+11+9+50+50+10+26+26+20 = 350, from app/cpy/COSTM01.CPY:L22-L36. Summing rather
            // than checking each width in isolation is what catches two compensating errors, for instance a
            // description widened by two and a filler narrowed by two.
            final int sum = StatementTransaction.CARD_NUMBER_LENGTH
                    + StatementTransaction.TRANSACTION_ID_LENGTH
                    + StatementTransaction.TYPE_CODE_LENGTH
                    + StatementTransaction.CATEGORY_CODE_LENGTH
                    + StatementTransaction.SOURCE_LENGTH
                    + StatementTransaction.DESCRIPTION_LENGTH
                    + StatementTransaction.AMOUNT_LENGTH
                    + StatementTransaction.MERCHANT_ID_LENGTH
                    + StatementTransaction.MERCHANT_NAME_LENGTH
                    + StatementTransaction.MERCHANT_CITY_LENGTH
                    + StatementTransaction.MERCHANT_ZIP_LENGTH
                    + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_LENGTH
                    + StatementTransaction.FILLER_LENGTH;

            assertThat(sum).isEqualTo(StatementTransaction.RECORD_LENGTH).isEqualTo(350);
        }

        @Test
        @DisplayName("the composite key equals its two parts and matches the work cluster's declared key")
        void keyEqualsItsParts() {
            // app/jcl/CREASTMT.JCL:L30 defines the work cluster with KEYS(32 0), the independent confirmation
            // that the card number and the transaction identifier together are the key.
            assertThat(StatementTransaction.CARD_NUMBER_LENGTH
                    + StatementTransaction.TRANSACTION_ID_LENGTH)
                    .isEqualTo(StatementTransaction.KEY_LENGTH)
                    .isEqualTo(32);
        }

        @Test
        @DisplayName("the remainder is the record less the key, and CBSTM03A states it as a literal width")
        void remainderIsRecordLessKey() {
            // app/cbl/CBSTM03A.CBL:L230 declares WS-TRAN-REST PIC X(318), so the 318 is not merely derived by
            // subtraction here - the consuming program spells it out.
            assertThat(StatementTransaction.RECORD_LENGTH - StatementTransaction.KEY_LENGTH)
                    .isEqualTo(StatementTransaction.REMAINDER_LENGTH)
                    .isEqualTo(318);
            assertThat(StatementTransaction.KEY_LENGTH + StatementTransaction.REMAINDER_LENGTH)
                    .isEqualTo(StatementTransaction.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the remainder's twelve field widths sum to 318 on their own")
        void remainderFieldWidthsSumToTheRemainder() {
            // The TRNX-REST group of app/cpy/COSTM01.CPY:L24-L36 in isolation, so a key-side error cannot
            // compensate for a remainder-side one.
            final int remainder = StatementTransaction.TYPE_CODE_LENGTH
                    + StatementTransaction.CATEGORY_CODE_LENGTH
                    + StatementTransaction.SOURCE_LENGTH
                    + StatementTransaction.DESCRIPTION_LENGTH
                    + StatementTransaction.AMOUNT_LENGTH
                    + StatementTransaction.MERCHANT_ID_LENGTH
                    + StatementTransaction.MERCHANT_NAME_LENGTH
                    + StatementTransaction.MERCHANT_CITY_LENGTH
                    + StatementTransaction.MERCHANT_ZIP_LENGTH
                    + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_LENGTH
                    + StatementTransaction.FILLER_LENGTH;

            assertThat(remainder).isEqualTo(StatementTransaction.REMAINDER_LENGTH).isEqualTo(318);
        }

        @Test
        @DisplayName("the amount precision decomposes into nine integer digits and a scale of two")
        void amountPrecisionDecomposes() {
            // PIC S9(09)V99 at app/cpy/COSTM01.CPY:29 is eleven characters, so NUMERIC(11,2) - never the
            // NUMERIC(12,2) that the account money fields of app/cpy/CVACT01Y.cpy use.
            assertThat(StatementTransaction.AMOUNT_INTEGER_DIGITS + StatementTransaction.AMOUNT_SCALE)
                    .isEqualTo(StatementTransaction.AMOUNT_PRECISION)
                    .isEqualTo(StatementTransaction.AMOUNT_LENGTH)
                    .isEqualTo(11);
            assertThat(StatementTransaction.AMOUNT_PRECISION).isNotEqualTo(12);
        }

        @Test
        @DisplayName("the record leads with the card number, as the sort projection rearranged it")
        void recordLeadsWithTheCardNumber() {
            // app/cpy/COSTM01.CPY:L22-L23 puts the card number at offsets 1-16 and the identifier at 17-32.
            // The order is what gives the work cluster a card-number-major key, so statements can be produced
            // per card.
            assertThat(componentNames(StatementTransaction.class))
                    .startsWith("cardNumber", "transactionId")
                    .hasSize(14);
            assertThat(componentNames(StatementTransaction.Key.class))
                    .containsExactly("cardNumber", "transactionId");
        }

        @Test
        @DisplayName("the two statement outputs keep their distinct record lengths")
        void statementOutputsKeepDistinctLengths() {
            // app/jcl/CREASTMT.JCL:L89 declares LRECL=80 for the text output and :L94 declares LRECL=100 for
            // the HTML output. Harmonising them would be a High-severity regression.
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH).isEqualTo(80);
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)
                    .isNotEqualTo(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("2. The projection truncates the processing timestamp by two characters")
    class ProjectionTruncation {

        @Test
        @DisplayName("the projected span ends where the sort's fifty-byte tail ends")
        void projectedSpanEndsWithTheTail() {
            // app/jcl/CREASTMT.JCL:L54 clause 279:279,50 copies fifty bytes from offset 279, so the last byte
            // written is 279 + 50 - 1 = 328.
            assertThat(StatementTransaction.BASE_TAIL_OFFSET + StatementTransaction.BASE_TAIL_LENGTH - 1)
                    .isEqualTo(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION)
                    .isEqualTo(328);
        }

        @Test
        @DisplayName("the fifty-byte tail is the whole originating timestamp plus twenty-four of the processing")
        void tailSplitsIntoTwentySixPlusTwentyFour() {
            // The decisive arithmetic: 26 + 24 = 50. The originating timestamp survives whole; the processing
            // timestamp loses its last two characters.
            assertThat(StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .isEqualTo(StatementTransaction.BASE_TAIL_LENGTH)
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("the significant and pad lengths reconstitute the full declared timestamp width")
        void significantPlusPadIsTheDeclaredWidth() {
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH)
                    .isEqualTo(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH)
                    .isEqualTo(26);
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH).isEqualTo(24);
        }

        @Test
        @DisplayName("the head length plus the card number is where the projection's tail begins")
        void headPlusCardNumberMeetsTheTail() {
            // Clause 17:1,262 fills output offsets 17-278, and the card number occupies 1-16, so the next free
            // output position is 279 - exactly where the third clause writes.
            assertThat(StatementTransaction.CARD_NUMBER_LENGTH + StatementTransaction.BASE_HEAD_LENGTH + 1)
                    .isEqualTo(StatementTransaction.BASE_TAIL_OFFSET)
                    .isEqualTo(279);
        }

        @Test
        @DisplayName("the base head stops at the merchant ZIP, which is why the card number can move to the front")
        void baseHeadStopsAtTheMerchantZip() {
            // app/cpy/CVTRA05Y.cpy:L5-L14: identifier through merchant ZIP is 16+2+4+10+100+11+9+50+50+10 =
            // 262 bytes, so base offset 263 is exactly where TRAN-CARD-NUM begins at :L15.
            final int identifierThroughZip = StatementTransaction.TRANSACTION_ID_LENGTH
                    + StatementTransaction.TYPE_CODE_LENGTH
                    + StatementTransaction.CATEGORY_CODE_LENGTH
                    + StatementTransaction.SOURCE_LENGTH
                    + StatementTransaction.DESCRIPTION_LENGTH
                    + StatementTransaction.AMOUNT_LENGTH
                    + StatementTransaction.MERCHANT_ID_LENGTH
                    + StatementTransaction.MERCHANT_NAME_LENGTH
                    + StatementTransaction.MERCHANT_CITY_LENGTH
                    + StatementTransaction.MERCHANT_ZIP_LENGTH;

            assertThat(identifierThroughZip)
                    .isEqualTo(StatementTransaction.BASE_HEAD_LENGTH)
                    .isEqualTo(262);
            assertThat(identifierThroughZip + 1)
                    .isEqualTo(StatementTransaction.BASE_CARD_NUMBER_OFFSET)
                    .isEqualTo(263);
        }

        @Test
        @DisplayName("the significant timestamp is the first twenty-four characters of the stored value")
        void significantTimestampTakesTheLeadingTwentyFour() {
            final StatementTransaction projected = new StatementTransaction(null, "TRAN000000000001",
                    null, null, null, null, null, null, null, null, null, null,
                    sizedTo26(LEGACY_TIMESTAMP), null);

            assertThat(projected.processingTimestamp())
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            assertThat(projected.significantProcessingTimestamp())
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .isEqualTo(LEGACY_TIMESTAMP.substring(0,
                            StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH));
        }

        @Test
        @DisplayName("the dropped characters are the trailing two, not the leading two")
        void theDroppedCharactersAreTrailing() {
            // Stated as an explicit direction check because a substring taken from the wrong end would still
            // produce a value of the right length and would still "look" truncated.
            final StatementTransaction projected = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, sizedTo26(LEGACY_TIMESTAMP), null);

            assertThat(LEGACY_TIMESTAMP).startsWith(projected.significantProcessingTimestamp());
            assertThat(LEGACY_TIMESTAMP).doesNotEndWith(projected.significantProcessingTimestamp());
        }

        @Test
        @DisplayName("a value already at or below the significant length passes through unpadded")
        void aTwentyFourCharacterValuePassesThroughUnaltered() {
            // This is the case the projection actually produces, and it is the reason the width guard is a
            // maximum rather than an equality: a 24-character value must be accepted and left alone.
            final String projectedValue = LEGACY_TIMESTAMP.substring(0,
                    StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH);
            final StatementTransaction projected = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, projectedValue, null);

            assertThat(projected.processingTimestamp()).isEqualTo(projectedValue).hasSize(24);
            assertThat(projected.significantProcessingTimestamp()).isEqualTo(projectedValue);
        }

        @Test
        @DisplayName("a null processing timestamp yields null rather than throwing or blanking")
        void aNullProcessingTimestampStaysNull() {
            final StatementTransaction projected = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);

            assertThat(projected.significantProcessingTimestamp()).isNull();
        }

        @Test
        @DisplayName("the twenty-byte filler is the part the projection drops entirely")
        void theFillerIsDroppedEntirely() {
            // Output positions 329-350 are never written: 329-330 are DFSORT pad inside the declared X(26),
            // and 331-350 is the FILLER X(20) of app/cpy/COSTM01.CPY:36.
            assertThat(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION
                    + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH
                    + StatementTransaction.FILLER_LENGTH)
                    .isEqualTo(StatementTransaction.RECORD_LENGTH)
                    .isEqualTo(350);
            assertThat(BASE_FILLER_COLUMN)
                    .isEqualTo(StatementTransaction.RECORD_LENGTH - StatementTransaction.FILLER_LENGTH + 1)
                    .isEqualTo(331);
        }
    }


    @Nested
    @DisplayName("3. Field typing: text stays text, money stays decimal, source stays unconstrained")
    class FieldTyping {

        @Test
        @DisplayName("the card number and the transaction identifier are text, so leading zeros survive")
        void keyComponentsAreText() {
            // app/cpy/COSTM01.CPY:22-23 declares both PIC X(16). A long or a BigInteger would strip a leading
            // zero and change the byte image, and the fixture proves leading zeros are real: cardxref.txt row 1
            // begins 0500024453765740.
            final List<String> componentTypes = componentTypeNames(StatementTransaction.class);

            assertThat(componentTypes.get(0)).isEqualTo(String.class.getName());
            assertThat(componentTypes.get(1)).isEqualTo(String.class.getName());
            assertThat(componentTypes)
                    .doesNotContain(long.class.getName(), Long.class.getName(),
                            "java.math.BigInteger", int.class.getName(), Integer.class.getName());
        }

        @Test
        @DisplayName("a leading-zero identifier and a leading-zero merchant identifier round-trip verbatim")
        void leadingZerosRoundTrip() {
            final StatementTransaction record = new StatementTransaction("0500024453765740",
                    "0000000000000001", "01", "0001", SOURCE_POS_TERMINAL, null, null, "000000001",
                    null, null, null, null, null, null);

            assertThat(record.transactionId()).isEqualTo("0000000000000001").hasSize(16);
            assertThat(record.merchantId()).isEqualTo("000000001").hasSize(9);
            assertThat(record.categoryCode()).isEqualTo("0001").hasSize(4);
        }

        @Test
        @DisplayName("the category code renders zero-padded to four characters, never as a bare digit")
        void categoryCodeIsZeroPadded() {
            // PIC 9(04) at app/cpy/COSTM01.CPY:26 is a numeric picture but a fixed-width one. All 300 rows of
            // app/data/ASCII/dailytran.txt carry the literal 0001, never 1.
            final StatementTransaction record = new StatementTransaction(null, null, null, "0001", null, null,
                    null, null, null, null, null, null, null, null);

            assertThat(record.categoryCode())
                    .isEqualTo("0001")
                    .hasSize(StatementTransaction.CATEGORY_CODE_LENGTH)
                    .isNotEqualTo("1");
            assertThat(componentTypeNames(StatementTransaction.class)
                    .get(componentNames(StatementTransaction.class).indexOf("categoryCode")))
                    .isEqualTo(String.class.getName());
        }

        @Test
        @DisplayName("no component of any statement layout is a floating-point type")
        void noFloatingPointAnywhere() {
            // Gate 6. A float or a double in a monetary field is a silent precision loss that no equality
            // assertion catches until the totals disagree with the baseline.
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(componentTypeNames(recordType))
                        .as("floating-point component on %s", recordType.getSimpleName())
                        .doesNotContain(float.class.getName(), double.class.getName(),
                                Float.class.getName(), Double.class.getName());
            }
        }

        @Test
        @DisplayName("the amount is a BigDecimal on both the record and the report lines")
        void amountIsBigDecimal() {
            assertThat(componentTypeNames(StatementTransaction.class))
                    .contains(BigDecimal.class.getName());
            assertThat(componentTypeNames(StatementTransaction.ReportDetailLine.class))
                    .contains(BigDecimal.class.getName());
            assertThat(componentTypeNames(StatementTransaction.ReportTotalsLine.class))
                    .contains(BigDecimal.class.getName());
        }

        @Test
        @DisplayName("the three known source values are the program literals padded to the declared X(10) width")
        void theThreeKnownSourceValuesArePaddedProgramLiterals() {
            // Single-sourcing the provenance rather than repeating the literals: 'System' is moved to TRAN-SOURCE
            // at app/cbl/CBACT04C.cbl:484 and 'POS TERM' at app/cbl/COBIL00C.cbl:222, each into a PIC X(10)
            // field, so each arrives space-padded to ten characters. OPERATOR appears in NO program literal
            // anywhere in the corpus - it reaches the record as data only, on 50 of the fixture's 300 rows.
            assertThat(SOURCE_SYSTEM).isEqualTo("System    ").hasSize(StatementTransaction.SOURCE_LENGTH);
            assertThat(SOURCE_SYSTEM.stripTrailing()).isEqualTo("System");
            assertThat(SOURCE_POS_TERMINAL).isEqualTo("POS TERM  ")
                    .hasSize(StatementTransaction.SOURCE_LENGTH);
            assertThat(SOURCE_POS_TERMINAL.stripTrailing()).isEqualTo("POS TERM");
            assertThat(SOURCE_OPERATOR).isEqualTo("OPERATOR  ").hasSize(StatementTransaction.SOURCE_LENGTH);
            assertThat(SOURCE_OPERATOR.stripTrailing()).isEqualTo("OPERATOR");
            assertThat(List.of(SOURCE_SYSTEM, SOURCE_POS_TERMINAL, SOURCE_OPERATOR)).doesNotHaveDuplicates();
        }

        @ParameterizedTest
        @ValueSource(strings = {SOURCE_SYSTEM, SOURCE_POS_TERMINAL, SOURCE_OPERATOR})
        @DisplayName("the source is an unconstrained ten-character string, not an enum")
        void sourceIsAnUnconstrainedString(final String sourceValue) {
            // PIC X(10) at app/cpy/COSTM01.CPY:27. The two program literals are 'System' at
            // app/cbl/CBACT04C.cbl:484 and 'POS TERM' at app/cbl/COBIL00C.cbl:222, but the fixture also carries
            // OPERATOR on 50 of its 300 rows and OPERATOR appears in no program literal anywhere in the corpus.
            // Binding this component to an enum would make that legacy value unreadable - Blocker severity.
            final StatementTransaction record = new StatementTransaction(null, null, null, null, sourceValue,
                    null, null, null, null, null, null, null, null, null);

            assertThat(record.source())
                    .isEqualTo(sourceValue)
                    .hasSize(StatementTransaction.SOURCE_LENGTH);
            assertThat(componentTypeNames(StatementTransaction.class)
                    .get(componentNames(StatementTransaction.class).indexOf("source")))
                    .isEqualTo(String.class.getName());
        }

        @Test
        @DisplayName("no statement layout references the TransactionSource enum at all")
        void noEnumTypeOnAnyLayout() {
            for (final Class<?> recordType : statementRecordTypes()) {
                for (final RecordComponent component : recordType.getRecordComponents()) {
                    assertThat(component.getType().isEnum())
                            .as("component %s of %s is an enum", component.getName(),
                                    recordType.getSimpleName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("an undocumented ten-character source value is accepted, because the source never validates it")
        void anUnknownSourceValueIsAccepted() {
            // app/cbl/COTRN02C.cbl:454 moves the screen field straight into TRAN-SOURCE with no lookup and no
            // domain check, so any ten-character value can legitimately reach the statement record.
            final StatementTransaction record = new StatementTransaction(null, null, null, null, "ZZZZZZZZZZ",
                    null, null, null, null, null, null, null, null, null);

            assertThat(record.source()).isEqualTo("ZZZZZZZZZZ");
        }
    }

    @Nested
    @DisplayName("4. The two layouts share a length and nothing else, so they are not interchangeable")
    class LayoutIdentity {

        @Test
        @DisplayName("both layouts are 350 bytes, which is precisely why confusing them is easy")
        void bothLayoutsAreTheSameLength() {
            // app/cpy/CVTRA05Y.cpy:2 and app/cpy/COSTM01.CPY both describe a 350-byte record. Equal length is
            // what makes a mistaken cast or a shared base class compile and then silently misread every field.
            assertThat(StatementTransaction.RECORD_LENGTH).isEqualTo(350);
            assertThat(StatementTransaction.BASE_CARD_NUMBER_OFFSET
                    + StatementTransaction.CARD_NUMBER_LENGTH - 1
                    + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_LENGTH
                    + StatementTransaction.FILLER_LENGTH)
                    .isEqualTo(StatementTransaction.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the card number sits at opposite ends of the two layouts")
        void theCardNumberMovesFromTheBackToTheFront() {
            // Base layout: TRAN-ID at 1-16 and TRAN-CARD-NUM at 263-278, app/cpy/CVTRA05Y.cpy:L5 and :L15.
            // Statement layout: TRNX-CARD-NUM at 1-16 and TRNX-ID at 17-32, app/cpy/COSTM01.CPY:L22-L23.
            assertThat(StatementTransaction.BASE_CARD_NUMBER_OFFSET).isEqualTo(263).isNotEqualTo(1);
            assertThat(BASE_TRANSACTION_ID_COLUMN).isEqualTo(1);
            assertThat(componentNames(StatementTransaction.class).indexOf("cardNumber"))
                    .isZero();
            assertThat(componentNames(StatementTransaction.class).indexOf("transactionId"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the statement record does not extend or implement anything, so no base class can assume an order")
        void theRecordSharesNoBaseType() {
            // A shared base class or a bidirectional converter between the two layouts would have to assume one
            // field order, and whichever it assumed would be wrong for the other layout - High severity.
            assertThat(StatementTransaction.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(StatementTransaction.class.getInterfaces()).isEmpty();
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(recordType.getSuperclass())
                        .as("%s has a base class other than Record", recordType.getSimpleName())
                        .isEqualTo(Record.class);
            }
        }

        @Test
        @DisplayName("reading the base layout's offsets with the statement layout's order transposes the key")
        void readingWithTheWrongOrderTransposesTheKey() {
            // A concrete demonstration rather than a claim. Projecting fixture row 0 correctly yields a key
            // whose two halves are distinguishable; swapping the two reads yields a different key, which is
            // what a layout-agnostic converter would silently produce.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final StatementTransaction correct = projectedFrom(daily, 0);
            final StatementTransaction transposed = new StatementTransaction(correct.transactionId(),
                    correct.cardNumber(), correct.typeCode(), correct.categoryCode(), correct.source(),
                    correct.description(), correct.amount(), correct.merchantId(), correct.merchantName(),
                    correct.merchantCity(), correct.merchantZip(), correct.originatingTimestamp(),
                    correct.processingTimestamp(), correct.filler());

            assertThat(transposed).isNotEqualTo(correct);
            assertThat(new StatementTransaction.Key(correct.cardNumber(), correct.transactionId()))
                    .isNotEqualTo(new StatementTransaction.Key(correct.transactionId(),
                            correct.cardNumber()));
        }
    }

    @Nested
    @DisplayName("5. Amount comparison uses value equality, never representational equality")
    class AmountValueEquality {

        @Test
        @DisplayName("the stored amount is normalised to the scale the PIC clause fixes")
        void amountIsNormalisedToScaleTwo() {
            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal("1234.5"), null, null, null, null, null, null, null);

            assertThat(record.amount().scale()).isEqualTo(StatementTransaction.AMOUNT_SCALE).isEqualTo(2);
            assertThat(record.amount()).isEqualByComparingTo("1234.50");
        }

        @ParameterizedTest
        @CsvSource({"1234.50, 1234.5", "0.00, 0", "-998.33, -998.330", "999.77, 999.7700"})
        @DisplayName("the same value at a different scale compares equal")
        void sameValueDifferentScaleComparesEqual(final String stored, final String compared) {
            // BigDecimal.equals compares scale as well as value, so 1234.50 and 1234.5 are unequal under it and
            // equal under compareTo. hasSameAmountAs must use the latter.
            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal(stored), null, null, null, null, null, null, null);

            assertThat(record.hasSameAmountAs(new BigDecimal(compared))).isTrue();
        }

        @Test
        @DisplayName("equals on the raw BigDecimal would disagree, which is why compareTo is the contract")
        void rawEqualsDisagreesWithCompareTo() {
            // Stated explicitly so that nobody "simplifies" hasSameAmountAs into an equals call. This asserts a
            // property of BigDecimal itself, which is the hazard being guarded against.
            final BigDecimal twoPlaces = new BigDecimal("1234.50");
            final BigDecimal onePlace = new BigDecimal("1234.5");

            assertThat(twoPlaces).isNotEqualTo(onePlace);
            assertThat(twoPlaces.compareTo(onePlace)).isZero();
        }

        @Test
        @DisplayName("genuinely different values compare unequal")
        void differentValuesCompareUnequal() {
            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal("100.00"), null, null, null, null, null, null, null);

            assertThat(record.hasSameAmountAs(new BigDecimal("100.01"))).isFalse();
            assertThat(record.hasSameAmountAs(new BigDecimal("-100.00"))).isFalse();
        }

        @Test
        @DisplayName("two absent amounts compare equal and one absent amount does not")
        void absentAmountsCompare() {
            final StatementTransaction absent = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
            final StatementTransaction present = new StatementTransaction(null, null, null, null, null, null,
                    BigDecimal.ZERO, null, null, null, null, null, null, null);

            assertThat(absent.hasSameAmountAs(null)).isTrue();
            assertThat(absent.hasSameAmountAs(BigDecimal.ZERO)).isFalse();
            assertThat(present.hasSameAmountAs(null)).isFalse();
            assertThat(present.amount()).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"-998.33", "-0.01", "-999999999.99", "-1.00"})
        @DisplayName("a negative amount is carried unaltered, because the fixtures genuinely contain them")
        void negativeAmountsSurviveUnaltered(final String negative) {
            // app/data/ASCII/dailytran.txt carries 50 negative rows with amounts down to -998.33, so no abs()
            // may appear anywhere on this path.
            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal(negative), null, null, null, null, null, null, null);

            assertThat(record.amount().signum()).isNegative();
            assertThat(record.amount()).isEqualByComparingTo(new BigDecimal(negative));
        }

        @Test
        @DisplayName("rounding is half-even, so a repeated half does not accumulate a bias")
        void roundingIsHalfEven() {
            // HALF_EVEN sends .125 down to .12 and .135 up to .14; HALF_UP would send both up.
            final StatementTransaction down = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal("0.125"), null, null, null, null, null, null, null);
            final StatementTransaction up = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal("0.135"), null, null, null, null, null, null, null);

            assertThat(down.amount()).isEqualByComparingTo("0.12");
            assertThat(up.amount()).isEqualByComparingTo("0.14");
        }

        @Test
        @DisplayName("a magnitude beyond nine integer digits is refused in both directions")
        void magnitudeIsBoundedSymmetrically() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction(null, null, null, null, null, null,
                            new BigDecimal("1000000000.00"), null, null, null, null, null, null, null))
                    .withMessageContaining("integer digits");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction(null, null, null, null, null, null,
                            new BigDecimal("-1000000000.00"), null, null, null, null, null, null, null))
                    .withMessageContaining("integer digits");
        }

        @Test
        @DisplayName("the largest nine-integer-digit value is accepted")
        void theLargestRepresentableValueIsAccepted() {
            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    new BigDecimal("999999999.99"), null, null, null, null, null, null, null);

            assertThat(record.amount()).isEqualByComparingTo("999999999.99");
        }
    }


    @Nested
    @DisplayName("6. The 133-byte report line and the layouts that do not fill it")
    class ReportLineGeometry {

        @Test
        @DisplayName("the report line is 133 bytes, on the copybook's own declaration and the dataset's")
        void theReportLineIs133Bytes() {
            // The authority is 01 TRANSACTION-HEADER-2 PIC X(133) at app/cpy/CVTRA07Y.cpy:48, corroborated by
            // DCB=(LRECL=133,...) at app/proc/TRANREPT.prc:76. It is NOT the sum of any detail line.
            assertThat(StatementTransaction.REPORT_LINE_LENGTH).isEqualTo(133);
        }

        @Test
        @DisplayName("the detail line's fields and separators sum to 114, not to the record width")
        void detailLineSumsTo114() {
            // app/cpy/CVTRA07Y.cpy:L15-L31: 16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2. There is exactly ONE
            // FILLER X(01) between the identifier and the account identifier, at :L17, and exactly one between
            // the account identifier and the type code, at :L19 - not two, which would make the line 115.
            //
            // Six single-character fillers in all: four spaces at :L17, :L19, :L23 and :L27, and two hyphen
            // separators at :L21 and :L25. They are counted separately because the hyphens are the
            // code-to-description separator asserted in CopybookLiterals, not padding.
            final int singleSpaceFillers = 4;
            final int hyphenSeparators = 2;
            final int fourSpaceFiller = 4;
            final int twoSpaceFiller = 2;
            final int sum = StatementTransaction.REPORT_TRANSACTION_ID_LENGTH
                    + StatementTransaction.REPORT_ACCOUNT_ID_LENGTH
                    + StatementTransaction.REPORT_TYPE_CODE_LENGTH
                    + StatementTransaction.REPORT_TYPE_DESCRIPTION_LENGTH
                    + StatementTransaction.REPORT_CATEGORY_CODE_LENGTH
                    + StatementTransaction.REPORT_CATEGORY_DESCRIPTION_LENGTH
                    + StatementTransaction.REPORT_SOURCE_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH
                    + singleSpaceFillers + hyphenSeparators + fourSpaceFiller + twoSpaceFiller;

            assertThat(sum).isEqualTo(StatementTransaction.REPORT_DETAIL_LINE_LENGTH).isEqualTo(114);
            assertThat(sum).isNotEqualTo(115);
        }

        @Test
        @DisplayName("the column header sums to 114 as well, so the two lines align")
        void columnHeaderSumsTo114() {
            // app/cpy/CVTRA07Y.cpy:L33-L46: 17+12+19+35+14+1+16 = 114. Alignment between the header and the
            // detail line is the whole point of both being 114.
            assertThat(17 + 12 + 19 + 35 + 14 + 1 + 16)
                    .isEqualTo(StatementTransaction.REPORT_COLUMN_HEADER_LENGTH)
                    .isEqualTo(114);
            assertThat(StatementTransaction.REPORT_COLUMN_HEADER_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_DETAIL_LINE_LENGTH);
        }

        @Test
        @DisplayName("no layout fills the 133-byte record, so 19 or 18 bytes of trailing space complete each line")
        void everyLayoutIsShorterThanTheRecord() {
            // 133 - 114 = 19 for the detail and header lines; 133 - 115 = 18 for the name header; 133 - 112 = 21
            // for a totals line. The writer right-pads; it must not stretch a field to close the gap, which
            // would shift every subsequent column - High severity.
            assertThat(StatementTransaction.REPORT_LINE_LENGTH
                    - StatementTransaction.REPORT_DETAIL_LINE_LENGTH).isEqualTo(19);
            assertThat(StatementTransaction.REPORT_LINE_LENGTH
                    - StatementTransaction.REPORT_COLUMN_HEADER_LENGTH).isEqualTo(19);
            assertThat(StatementTransaction.REPORT_LINE_LENGTH
                    - StatementTransaction.REPORT_NAME_HEADER_LENGTH).isEqualTo(18);
            assertThat(StatementTransaction.REPORT_LINE_LENGTH
                    - StatementTransaction.REPORT_TOTALS_LINE_LENGTH).isEqualTo(21);
            assertThat(StatementTransaction.REPORT_NAME_HEADER_LENGTH).isEqualTo(115);
            assertThat(StatementTransaction.REPORT_TOTALS_LINE_LENGTH).isEqualTo(112);
        }

        @Test
        @DisplayName("right-padding a 114-byte line to 133 leaves the content untouched")
        void rightPaddingLeavesContentIntact() {
            // Demonstrates padding rather than stretching: the first 114 characters must be byte-identical and
            // the remaining 19 must all be spaces.
            final String content = "X".repeat(StatementTransaction.REPORT_DETAIL_LINE_LENGTH);
            final String padded = content
                    + " ".repeat(StatementTransaction.REPORT_LINE_LENGTH - content.length());

            assertThat(padded).hasSize(StatementTransaction.REPORT_LINE_LENGTH);
            assertThat(padded.substring(0, StatementTransaction.REPORT_DETAIL_LINE_LENGTH))
                    .isEqualTo(content);
            assertThat(padded.substring(StatementTransaction.REPORT_DETAIL_LINE_LENGTH)).isBlank().hasSize(19);
        }

        @Test
        @DisplayName("all three totals layouts pad their label and dots to the same 97 bytes")
        void totalsLayoutsShareThePaddingInvariant() {
            // 11+86, 13+84 and 11+86 all equal 97, which is why one ReportTotalsLine type serves all three
            // layouts of app/cpy/CVTRA07Y.cpy:L50-L66.
            assertThat(StatementTransaction.PAGE_TOTAL_LABEL_LENGTH
                    + StatementTransaction.PAGE_TOTAL_DOTS_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH).isEqualTo(97);
            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH
                    + StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
            assertThat(StatementTransaction.GRAND_TOTAL_LABEL_LENGTH
                    + StatementTransaction.GRAND_TOTAL_DOTS_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
        }

        @Test
        @DisplayName("a totals line is the padded label and dots plus one amount mask")
        void totalsLineIsLabelDotsAndMask() {
            assertThat(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LINE_LENGTH).isEqualTo(112);
        }

        @Test
        @DisplayName("the report name header sums to 115 from its six declared fields")
        void nameHeaderSumsTo115() {
            // app/cpy/CVTRA07Y.cpy:L4-L13: 38+41+12+10+4+10 = 115.
            assertThat(StatementTransaction.REPORT_SHORT_NAME_LENGTH
                    + StatementTransaction.REPORT_LONG_NAME_LENGTH
                    + StatementTransaction.REPORT_DATE_HEADER.length()
                    + StatementTransaction.REPORT_DATE_LENGTH
                    + StatementTransaction.REPORT_DATE_SEPARATOR.length()
                    + StatementTransaction.REPORT_DATE_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_NAME_HEADER_LENGTH)
                    .isEqualTo(115);
        }

        @Test
        @DisplayName("the batch report's twenty-lines-per-page never appears on this type")
        void noBatchPageSizeOnThisType() {
            // app/cbl/CBTRN03C.cbl:131 declares WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20. Pagination is the batch
            // tier's concern: it needs a line counter and a control-break state that a value carrier has no
            // business holding - High severity if one appears here.
            final Set<String> staticNames = publicStaticFieldNames(StatementTransaction.class);

            assertThat(staticNames)
                    .noneMatch(name -> name.contains("PAGE_SIZE"))
                    .noneMatch(name -> name.contains("PAGESIZE"))
                    .noneMatch(name -> name.contains("LINES_PER_PAGE"))
                    .noneMatch(name -> name.contains("LINE_COUNT"));
            assertThat(componentNames(StatementTransaction.class))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("page"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("line"));
        }
    }

    @Nested
    @DisplayName("7. Copybook literals, byte-exact and including every embedded space")
    class CopybookLiterals {

        @Test
        @DisplayName("the date header keeps its trailing space inside the twelve-character field")
        void dateHeaderKeepsItsTrailingSpace() {
            // app/cpy/CVTRA07Y.cpy:L9-L10 declares PIC X(12) VALUE 'Date Range: '. Trimming it would close the
            // gap before the start date and shift the whole header - Blocker severity, because the parity gate
            // compares this text byte for byte.
            assertThat(StatementTransaction.REPORT_DATE_HEADER)
                    .isEqualTo("Date Range: ")
                    .hasSize(12)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("the date separator keeps both its leading and its trailing space")
        void dateSeparatorKeepsBothSpaces() {
            // app/cpy/CVTRA07Y.cpy:L12 declares FILLER PIC X(04) VALUE ' to '. Two of its four characters are
            // spaces, one on each side of the word.
            assertThat(StatementTransaction.REPORT_DATE_SEPARATOR)
                    .isEqualTo(" to ")
                    .hasSize(4)
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(StatementTransaction.REPORT_DATE_SEPARATOR.strip()).isEqualTo("to");
        }

        @Test
        @DisplayName("the report short and long names are carried verbatim inside their declared widths")
        void reportNamesAreVerbatim() {
            // app/cpy/CVTRA07Y.cpy:L5-L8. Each literal is shorter than the field that holds it, and the field
            // width is what the writer must emit.
            assertThat(StatementTransaction.REPORT_SHORT_NAME).isEqualTo("DALYREPT").hasSize(8);
            assertThat(StatementTransaction.REPORT_SHORT_NAME_LENGTH).isEqualTo(38);
            assertThat(StatementTransaction.REPORT_LONG_NAME)
                    .isEqualTo("Daily Transaction Report").hasSize(24);
            assertThat(StatementTransaction.REPORT_LONG_NAME_LENGTH).isEqualTo(41);
        }

        @Test
        @DisplayName("the column header's amount caption carries eight leading spaces")
        void amountCaptionCarriesEightLeadingSpaces() {
            // app/cpy/CVTRA07Y.cpy:L45-L46 declares FILLER PIC X(16) VALUE '        Amount'. The eight spaces
            // are what right-align the caption over the fifteen-character amount mask, so re-spacing the literal
            // misaligns the column - Blocker severity.
            final String caption = "        Amount";

            assertThat(caption).hasSize(14).endsWith("Amount");
            assertThat(caption.length() - caption.stripLeading().length()).isEqualTo(8);
            assertThat(caption.stripLeading()).isEqualTo("Amount");
        }

        @Test
        @DisplayName("the code-to-description separator is a single hyphen, twice over")
        void codeToDescriptionSeparatorIsAHyphen() {
            // app/cpy/CVTRA07Y.cpy:L21 and :L25 both declare FILLER PIC X(01) VALUE '-'. It is a separator
            // between a code and its description, not a minus sign, and it must not be confused with the sign
            // position of the amount mask.
            assertThat(StatementTransaction.REPORT_CODE_DESCRIPTION_SEPARATOR).isEqualTo("-").hasSize(1);
        }

        @Test
        @DisplayName("the 133-byte rule is exactly 133 hyphens")
        void theRuleIsExactly133Hyphens() {
            // app/cpy/CVTRA07Y.cpy:L48: 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.
            final String rule = "-".repeat(StatementTransaction.REPORT_LINE_LENGTH);

            assertThat(rule).hasSize(133).containsOnlyOnce("-".repeat(133));
            assertThat(rule.chars().allMatch(character -> character == '-')).isTrue();
        }

        @Test
        @DisplayName("the page and grand totals carry 86 dots and the account totals 84")
        void dotRunsAreCarriedAtTheirDeclaredLengths() {
            // app/cpy/CVTRA07Y.cpy:L53, :L59 and :L65 declare VALUE ALL '.' at widths 86, 84 and 86. The account
            // run is two shorter precisely because its label is two longer, which is the padding invariant.
            assertThat(StatementTransaction.PAGE_TOTAL_DOTS_LENGTH).isEqualTo(86);
            assertThat(StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH).isEqualTo(84);
            assertThat(StatementTransaction.GRAND_TOTAL_DOTS_LENGTH).isEqualTo(86);

            final String pageDots = ".".repeat(StatementTransaction.PAGE_TOTAL_DOTS_LENGTH);
            assertThat(pageDots).hasSize(86);
            assertThat(pageDots.chars().allMatch(character -> character == '.')).isTrue();
            assertThat(StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH)
                    .isEqualTo(StatementTransaction.PAGE_TOTAL_DOTS_LENGTH
                            - (StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH
                            - StatementTransaction.PAGE_TOTAL_LABEL_LENGTH));
        }

        @ParameterizedTest
        @CsvSource({"Page Total, 10, 11", "Account Total, 13, 13", "Grand Total, 11, 11"})
        @DisplayName("each totals label is carried verbatim inside its declared field width")
        void totalsLabelsAreVerbatim(final String label, final int literalLength, final int fieldWidth) {
            // app/cpy/CVTRA07Y.cpy:L51-L52, :L57-L58 and :L63-L64. 'Page Total' is ten characters in an X(11)
            // field and 'Account Total' is thirteen in an X(13) field, so the label lengths and the field widths
            // are not interchangeable either.
            final StatementTransaction.ReportTotalsLine line = switch (label) {
                case "Page Total" -> StatementTransaction.ReportTotalsLine.pageTotal(BigDecimal.ZERO);
                case "Account Total" -> StatementTransaction.ReportTotalsLine.accountTotal(BigDecimal.ZERO);
                default -> StatementTransaction.ReportTotalsLine.grandTotal(BigDecimal.ZERO);
            };

            assertThat(label).hasSize(literalLength);
            assertThat(line.label()).isEqualTo(label);
            assertThat(line.labelWidth()).isEqualTo(fieldWidth);
            assertThat(line.labelWidth() + line.dotsWidth())
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
        }

        @Test
        @DisplayName("the account totals label is preserved although the control break fires on the card number")
        void accountTotalLabelIsPreservedDespiteTheCardNumberBreak() {
            // app/cbl/CBTRN03C.cbl:181 breaks on IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM and then emits the
            // REPORT-ACCOUNT-TOTALS layout, so the label says "Account" while the break is by card. Parity is
            // the contract: the label must NOT be corrected to "Card Total".
            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL)
                    .isEqualTo("Account Total")
                    .isNotEqualTo("Card Total");
            assertThat(StatementTransaction.ReportTotalsLine.accountTotal(new BigDecimal("12.34")).label())
                    .isEqualTo("Account Total");
        }
    }

    @Nested
    @DisplayName("8. The two amount masks: Z suppresses, and the sign conventions differ")
    class EditMaskRendering {

        @Test
        @DisplayName("both masks are fifteen characters and differ at exactly one position")
        void masksDifferOnlyInTheirSignCharacter() {
            // app/cpy/CVTRA07Y.cpy:L30 declares -ZZZ,ZZZ,ZZZ.ZZ and :L54, :L60, :L66 declare +ZZZ,ZZZ,ZZZ.ZZ.
            // 1 + 3 + 1 + 3 + 1 + 3 + 1 + 2 = 15 for each.
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ")
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(StatementTransaction.TOTALS_AMOUNT_MASK)
                    .isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ")
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK.substring(1))
                    .isEqualTo(StatementTransaction.TOTALS_AMOUNT_MASK.substring(1));
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK.charAt(0)).isEqualTo('-');
            assertThat(StatementTransaction.TOTALS_AMOUNT_MASK.charAt(0)).isEqualTo('+');
        }

        @Test
        @DisplayName("each mask provides eleven digit positions, two commas and one decimal point")
        void maskStructureMatchesThePicClause() {
            for (final String mask : List.of(StatementTransaction.DETAIL_AMOUNT_MASK,
                    StatementTransaction.TOTALS_AMOUNT_MASK)) {
                assertThat(mask.chars().filter(character -> character == 'Z').count())
                        .as("Z positions in %s", mask)
                        .isEqualTo(StatementTransaction.AMOUNT_PRECISION).isEqualTo(11);
                assertThat(mask.indexOf(',')).isEqualTo(4);
                assertThat(mask.lastIndexOf(',')).isEqualTo(8);
                assertThat(mask.indexOf('.')).isEqualTo(12);
                assertThat(mask.chars().filter(character -> character == ',').count()).isEqualTo(2);
            }
        }

        @ParameterizedTest
        @CsvSource({
            "1.00,            '           1.00', '+          1.00'",
            "999.77,          '         999.77', '+        999.77'",
            "1234567.89,      '   1,234,567.89', '+  1,234,567.89'",
            "999999999.99,    ' 999,999,999.99', '+999,999,999.99'",
            "0.00,            '            .00', '+           .00'"
        })
        @DisplayName("a non-negative value renders with a blank sign on the detail mask and a plus on the totals mask")
        void nonNegativeValuesRenderWithTheirOwnSignConvention(final String value, final String detail,
                final String totals) {
            // This is the Blocker-severity difference: the same value renders differently through the two masks,
            // so they cannot be one formatter. The detail mask's leading '-' is blank when the value is not
            // negative; the totals mask's leading '+' is mandatory.
            final BigDecimal amount = new BigDecimal(value);

            assertThat(renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK, amount))
                    .isEqualTo(detail).hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(renderEditMask(StatementTransaction.TOTALS_AMOUNT_MASK, amount))
                    .isEqualTo(totals).hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK, amount))
                    .isNotEqualTo(renderEditMask(StatementTransaction.TOTALS_AMOUNT_MASK, amount));
        }

        @ParameterizedTest
        @CsvSource({
            "-998.33,          '-        998.33'",
            "-1.00,            '-          1.00'",
            "-1234567.89,      '-  1,234,567.89'",
            "-999999999.99,    '-999,999,999.99'"
        })
        @DisplayName("a negative value renders identically through both masks, because the sign is the value's own")
        void negativeValuesRenderIdenticallyThroughBothMasks(final String value, final String expected) {
            // The one case where the two masks agree: a negative value puts '-' in the sign position of either.
            // Asserting it stops the sign convention being "simplified" into a single unconditional mask.
            final BigDecimal amount = new BigDecimal(value);

            assertThat(renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK, amount)).isEqualTo(expected);
            assertThat(renderEditMask(StatementTransaction.TOTALS_AMOUNT_MASK, amount)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "1.00", "999.77", "1234567.89"})
        @DisplayName("Z suppresses a leading zero to a space, so no rendering ever carries a leading zero digit")
        void zeroSuppressionNeverEmitsALeadingZero(final String value) {
            // High severity if Z is treated as 9. A '9' picture would render 1.00 as 000,000,001.00; a 'Z'
            // picture renders it as ten spaces then 1.
            final BigDecimal amount = new BigDecimal(value);
            final String rendered = renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK, amount);
            final String integerRegion = rendered.substring(1, rendered.indexOf('.'));

            assertThat(integerRegion).hasSize(11);
            assertThat(integerRegion.stripLeading()).doesNotStartWith("0");
            assertThat(rendered).doesNotContain("000");
        }

        @Test
        @DisplayName("a comma inside the suppressed region is suppressed with it")
        void commasInsideTheSuppressedRegionAreSuppressed() {
            // 999.77 needs only three integer digits, so both inserted commas fall inside the suppressed region
            // and must render as spaces. A formatter that emits the separators unconditionally would produce
            // "    ,   ,999.77", which is a different byte image.
            final String small = renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK,
                    new BigDecimal("999.77"));
            final String large = renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK,
                    new BigDecimal("1234567.89"));

            assertThat(small).doesNotContain(",").isEqualTo("         999.77");
            assertThat(large).contains(",").isEqualTo("   1,234,567.89");
            assertThat(large.chars().filter(character -> character == ',').count()).isEqualTo(2);
        }

        @Test
        @DisplayName("the category code is the only unsuppressed numeric field on the detail line")
        void theCategoryCodeIsNotZeroSuppressed() {
            // TRAN-REPORT-CAT-CD PIC 9(04) at app/cpy/CVTRA07Y.cpy:24 is a '9' picture, so its leading zeros
            // are digits. The amount beside it is a 'Z' picture, so its leading zeros are spaces. The contrast
            // within one line is the point.
            final StatementTransaction.ReportDetailLine line = new StatementTransaction.ReportDetailLine(
                    "TRAN000000000001", "00000000011", "01", "Purchase", "0001", "Groceries",
                    SOURCE_POS_TERMINAL, new BigDecimal("1.00"));

            assertThat(line.categoryCode()).isEqualTo("0001").startsWith("0").hasSize(4);
            assertThat(renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK, line.amount()))
                    .isEqualTo("           1.00")
                    .doesNotContain("0001");
        }

        @Test
        @DisplayName("the root locale is the value that reproduces the copybook's own separators")
        void rootLocaleSeparatorsMatchTheCopybookMask() {
            // The reference renderer consults no Locale at all, which is stronger than passing one. Where a
            // caller must use a platform formatter, Locale.ROOT is the value that yields ',' for grouping and
            // '.' for the decimal point, exactly as app/cpy/CVTRA07Y.cpy:30 spells them. A default locale would
            // swap the two in much of Europe and silently break the byte-exact baseline.
            final BigDecimal amount = new BigDecimal("1234567.89");
            final String viaPlatformFormatter = String.format(Locale.ROOT, "%,.2f", amount);
            final String viaReferenceRenderer =
                    renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK, amount);

            assertThat(viaPlatformFormatter).isEqualTo("1,234,567.89");
            assertThat(viaReferenceRenderer.strip()).isEqualTo(viaPlatformFormatter);
        }

        @Test
        @DisplayName("the renderer refuses a mask of the wrong width and a value that overflows the digit positions")
        void theRendererValidatesItsInputs() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> renderEditMask("-ZZZ.ZZ", BigDecimal.ONE))
                    .withMessageContaining("15 characters");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> renderEditMask(StatementTransaction.DETAIL_AMOUNT_MASK,
                            new BigDecimal("1000000000.00")))
                    .withMessageContaining("digit positions");
        }
    }


    @Nested
    @DisplayName("9. Timestamps are text in three legacy shapes, resolved from an injected clock")
    class TimestampParity {

        @Test
        @DisplayName("both timestamp components are strings, never a temporal type")
        void timestampsAreStrings() {
            // High severity if a temporal type is used. A LocalDateTime or an Instant would either reject the
            // 24-character projected value outright or normalise it, and either outcome breaks the byte-exact
            // baseline. A 24-of-26-character value is not a parseable timestamp at all.
            final List<String> componentTypes = componentTypeNames(StatementTransaction.class);
            final List<String> names = componentNames(StatementTransaction.class);

            assertThat(componentTypes.get(names.indexOf("originatingTimestamp")))
                    .isEqualTo(String.class.getName());
            assertThat(componentTypes.get(names.indexOf("processingTimestamp")))
                    .isEqualTo(String.class.getName());
            assertThat(componentTypes)
                    .noneMatch(typeName -> typeName.startsWith("java.time."))
                    .doesNotContain("java.sql.Timestamp", "java.sql.Date", "java.util.Date",
                            "java.util.Calendar");
        }

        @Test
        @DisplayName("no statement layout carries a temporal component anywhere")
        void noLayoutCarriesATemporalComponent() {
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(componentTypeNames(recordType))
                        .as("temporal component on %s", recordType.getSimpleName())
                        .noneMatch(typeName -> typeName.startsWith("java.time."))
                        .noneMatch(typeName -> typeName.startsWith("java.sql."))
                        .doesNotContain("java.util.Date", "java.util.Calendar");
            }
        }

        @Test
        @DisplayName("the online shape round-trips: a space at position 11 and six literal zeros at 21-26")
        void theOnlineShapeRoundTrips() {
            // app/cbl/COBIL00C.cbl:L249-L267 builds it: ASKTIME, then FORMATTIME with DATESEP('-') and
            // TIMESEP(':'), then INITIALIZE WS-TIMESTAMP, then the date into (01:10) and the time into (12:08),
            // then MOVE ZEROS TO WS-TIMESTAMP-TM-MS6. Position 11 is never written, so INITIALIZE leaves it a
            // SPACE - not a dash and not a 'T'.
            final Clock clock = FixedClockProvider.canonicalClock();
            final String online = FixedClockProvider.onlineTimestamp(clock);

            assertThat(online)
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .hasSize(StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH);
            assertThat(online.charAt(10)).isEqualTo(' ');
            assertThat(online.charAt(19)).isEqualTo('.');
            assertThat(online.substring(20)).isEqualTo("000000").hasSize(6);

            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, online, null, null);
            assertThat(record.originatingTimestamp()).isEqualTo(online);
        }

        @Test
        @DisplayName("the batch shape round-trips: three dashes, hundredths, then four literal zeros")
        void theBatchShapeRoundTrips() {
            // app/cbl/CBTRN02C.cbl's Z-GET-DB2-FORMAT-TIMESTAMP executes MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2
            // DB2-STREEP-3, which is THREE dashes: a DASH separates DD from HH, not a space. DB2-MIL is
            // PIC 9(002) at :L173, so the fractional part is hundredths, and MOVE '0000' TO DB2-REST appends four
            // literal zeros - never nanoseconds. The documented shape at :L149 is EEEE-MM-DD-UU.MM.SS.HH0000.
            final Clock clock = FixedClockProvider.canonicalClock();
            final String batch = FixedClockProvider.batchTimestamp(clock);

            assertThat(batch)
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            assertThat(batch.chars().filter(character -> character == '-').count()).isEqualTo(3);
            assertThat(batch.charAt(10)).isEqualTo('-');
            assertThat(batch.chars().filter(character -> character == '.').count()).isEqualTo(3);
            assertThat(batch.substring(22)).isEqualTo("0000").hasSize(4);

            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, batch, null);
            assertThat(record.processingTimestamp()).isEqualTo(batch);
            assertThat(record.significantProcessingTimestamp())
                    .isEqualTo(batch.substring(0, StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH));
        }

        @Test
        @DisplayName("the online and batch shapes differ at position 11, which is the whole distinction")
        void theTwoGeneratedShapesDifferAtPositionEleven() {
            // Same instant, two renderings. The online form has a space between the date and the time; the batch
            // form has a dash. Confusing them produces a value of the right length that sorts differently.
            final Clock clock = FixedClockProvider.canonicalClock();
            final String online = FixedClockProvider.onlineTimestamp(clock);
            final String batch = FixedClockProvider.batchTimestamp(clock);

            assertThat(online).isNotEqualTo(batch);
            assertThat(online.substring(0, 10)).isEqualTo(batch.substring(0, 10));
            assertThat(online.charAt(10)).isEqualTo(' ');
            assertThat(batch.charAt(10)).isEqualTo('-');
        }

        @Test
        @DisplayName("the third shape is the projected 24-character value padded back out to 26")
        void theProjectedShapeRoundTrips() {
            // This is the shape app/jcl/CREASTMT.JCL:L54 actually produces. Padding it back to 26 is the COBOL
            // MOVE into a PIC X(26) receiving field, and the two pad characters must be spaces, not zeros.
            final Clock clock = FixedClockProvider.canonicalClock();
            final String projectedSignificant = FixedClockProvider.batchTimestamp(clock)
                    .substring(0, StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH);
            final String paddedBackTo26 = sizedTo26(projectedSignificant);

            assertThat(projectedSignificant).hasSize(24);
            assertThat(paddedBackTo26)
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH)
                    .startsWith(projectedSignificant)
                    .endsWith("  ");
            assertThat(paddedBackTo26.substring(
                    StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)).isEqualTo("  ");

            final StatementTransaction record = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, paddedBackTo26, null);
            assertThat(record.significantProcessingTimestamp()).isEqualTo(projectedSignificant);
        }

        @Test
        @DisplayName("a twenty-six-space timestamp round-trips and stays distinct from an absent one")
        void aBlankTimestampRoundTripsAndIsNotAbsent() {
            // All 300 rows of app/data/ASCII/dailytran.txt carry 26 blanks at columns 305-330, which is the
            // evidence that the staging column is CHAR(26) rather than a TIMESTAMP: a TIMESTAMP column cannot
            // hold 26 spaces at all. Blank means "not yet posted"; absent means "no value supplied".
            final String blank = " ".repeat(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            final StatementTransaction blankRecord = new StatementTransaction(null, null, null, null, null,
                    null, null, null, null, null, null, null, blank, null);
            final StatementTransaction absentRecord = new StatementTransaction(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(blankRecord.processingTimestamp()).isEqualTo(blank).hasSize(26).isBlank();
            // The significant reading applies the same 24-character truncation to a blank value as to any
            // other, because the truncation is a property of the projection rather than of the content: a
            // 26-character value loses its trailing two positions whatever they hold.
            assertThat(blankRecord.significantProcessingTimestamp())
                    .isBlank()
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .isEqualTo(" ".repeat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH));
            assertThat(absentRecord.processingTimestamp()).isNull();
            assertThat(absentRecord.significantProcessingTimestamp()).isNull();
            assertThat(blankRecord).isNotEqualTo(absentRecord);
        }

        @Test
        @DisplayName("the clock is injected, so a different instant yields a different rendering deterministically")
        void theClockIsInjectedRatherThanRead() {
            // Determinism, Rule 1 clause A. Nothing here calls Instant.now(), LocalDate.now() or
            // System.currentTimeMillis(), so the assertions cannot drift with the wall clock or the host zone.
            final Clock canonical = FixedClockProvider.canonicalClock();
            final Instant later = FixedClockProvider.CANONICAL_INSTANT.plusSeconds(1);
            final Clock oneSecondLater =
                    FixedClockProvider.fixedClock(later, FixedClockProvider.CANONICAL_ZONE);

            assertThat(FixedClockProvider.onlineTimestamp(canonical))
                    .isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(FixedClockProvider.onlineTimestamp(oneSecondLater))
                    .isEqualTo("2022-06-10 19:27:54.000000");
            assertThat(FixedClockProvider.onlineTimestamp(canonical))
                    .isEqualTo(FixedClockProvider.onlineTimestamp(canonical));
        }

        @Test
        @DisplayName("this type encodes no file status, because that is FileStatus's boundary and not a record's")
        void noFileStatusIsEncodedOnThisType() {
            // app/cbl/CBSTM03A.CBL's call idiom at :L347-L351 fills WS-M03B-RC PIC X(02) at :L80 and then accepts
            // '00' OR '04' as success, treats '10' as end of file and abends on anything else. That accepted
            // secondary status is one of only three exceptions to the general file-status mapping, the other two
            // being CBTRN02C's 2700-UPDATE-TCATBAL upsert and CBACT04C's default-group fallback. All of it lives
            // on the FileStatus enum and the file service, not on a value carrier: a record that also reported an
            // I/O outcome would blur the boundary and force every caller to check it.
            // The substring probes are deliberately specific rather than sweeping. A blanket search for "rc"
            // would match "source", "merchantId", "merchantName" and "merchantCity", all of which are legitimate
            // COSTM01 fields - a false positive that would make the assertion meaningless.
            assertThat(componentNames(StatementTransaction.class))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("status"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("returncode"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("responsecode"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("abend"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("reject"));
            assertThat(publicStaticFieldNames(StatementTransaction.class))
                    .noneMatch(name -> name.contains("FILE_STATUS"))
                    .noneMatch(name -> name.contains("RETURN_CODE"))
                    .noneMatch(name -> name.contains("ABEND"));
        }
    }

    @Nested
    @DisplayName("10. The sort's ascending order is a correctness dependency, not a presentation choice")
    class SortProjectionOrdering {

        /**
         * The two-key ascending comparator that reproduces
         * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}.
         *
         * <p>Character ascending on the card number, then character ascending on the transaction identifier -
         * which is exactly the {@code TRNX-KEY} order of {@code app/cpy/COSTM01.CPY:L21-L23}. It is a
         * {@link Comparator} rather than a spawned sort process: AAP transformation rule 9 forbids spawning an
         * external sort, and Rule 1 clause D forbids the {@code ProcessBuilder} that would do it.
         */
        private Comparator<StatementTransaction> sortOrder() {
            return Comparator.comparing(StatementTransaction::cardNumber)
                    .thenComparing(StatementTransaction::transactionId);
        }

        @Test
        @DisplayName("the sort's two keys are the key's two components, in the key's own order")
        void theSortKeysAreTheRecordKeyInOrder() {
            // Clause 263,16 is the card number and clause 1,16 is the identifier, in that order. That is the
            // reason COSTM01 leads with the card number: the sort key and the cluster key coincide.
            assertThat(StatementTransaction.BASE_CARD_NUMBER_OFFSET).isEqualTo(263);
            assertThat(StatementTransaction.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(BASE_TRANSACTION_ID_COLUMN).isEqualTo(1);
            assertThat(StatementTransaction.TRANSACTION_ID_LENGTH).isEqualTo(16);
            assertThat(componentNames(StatementTransaction.Key.class))
                    .containsExactly("cardNumber", "transactionId");
        }

        @Test
        @DisplayName("the comparator orders by card number and only then by transaction identifier")
        void theComparatorIsCardNumberMajor() {
            final StatementTransaction lowCardHighId = new StatementTransaction("0500024453765740",
                    "9999999999999999", null, null, null, null, null, null, null, null, null, null, null, null);
            final StatementTransaction highCardLowId = new StatementTransaction("9805583408996588",
                    "0000000000000001", null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(sortOrder().compare(lowCardHighId, highCardLowId)).isNegative();
            assertThat(sortOrder().compare(highCardLowId, lowCardHighId)).isPositive();
        }

        @Test
        @DisplayName("within one card the identifier breaks the tie, ascending")
        void theIdentifierBreaksTheTie() {
            final StatementTransaction first = new StatementTransaction("0500024453765740",
                    "0000000000000001", null, null, null, null, null, null, null, null, null, null, null, null);
            final StatementTransaction second = new StatementTransaction("0500024453765740",
                    "0000000000000002", null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(sortOrder().compare(first, second)).isNegative();
            assertThat(sortOrder().compare(first, first)).isZero();
        }

        @Test
        @DisplayName("the sort is character ascending, so a leading zero orders before a leading nine")
        void theSortIsCharacterAscendingNotNumeric() {
            // CH in the SORT specification means character, not zoned decimal, so ordering is by byte. With
            // fixed-width zero-padded values a character sort and a numeric sort agree, and asserting CH keeps a
            // future numeric conversion from silently reordering.
            final List<String> ascending = List.of("0500024453765740", "9805583408996588");

            assertThat(ascending).isSorted();
            assertThat("0500024453765740".compareTo("9805583408996588")).isNegative();
        }

        @Test
        @DisplayName("sorting preserves encounter order among equal keys, so the projection is stable")
        void theSortIsStable() {
            // List.sort is guaranteed stable, which matters because two records sharing a key must not swap: the
            // downstream linear scan takes the first match it finds.
            final StatementTransaction firstArrival = new StatementTransaction("0500024453765740",
                    "0000000000000001", "01", null, null, null, null, null, null, null, null, null, null, null);
            final StatementTransaction secondArrival = new StatementTransaction("0500024453765740",
                    "0000000000000001", "03", null, null, null, null, null, null, null, null, null, null, null);
            final List<StatementTransaction> ordered =
                    new ArrayList<>(List.of(firstArrival, secondArrival));

            ordered.sort(sortOrder());

            assertThat(ordered).containsExactly(firstArrival, secondArrival);
            assertThat(ordered.get(0).typeCode()).isEqualTo("01");
        }

        @Test
        @DisplayName("the cross-reference fixture is already ascending by card number, as the lookup requires")
        void theCrossReferenceFixtureIsAscending() {
            // app/cbl/CBSTM03A.CBL:L416-L419 scans linearly and exits early UNTIL CR-JMP > CR-CNT OR
            // (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM). That early exit is correct ONLY because the table is
            // ascending by card number. app/data/ASCII/cardxref.txt is the independent evidence that ascending
            // input is real rather than assumed: 50 records of 36 bytes, already in order.
            final FixtureLoader.FixtureData crossReference =
                    FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
            final List<String> cardNumbers = new ArrayList<>();
            for (int row = 0; row < crossReference.recordCount(); row++) {
                cardNumbers.add(crossReference.field(row, 1, StatementTransaction.CARD_NUMBER_LENGTH));
            }

            assertThat(crossReference.resourceName()).isEqualTo("cardxref.txt");
            assertThat(crossReference.recordCount()).isEqualTo(50);
            assertThat(crossReference.recordWidth()).isEqualTo(36);
            assertThat(crossReference.byteCount())
                    .isEqualTo(1_850)
                    .isEqualTo(crossReference.impliedByteCount());
            assertThat(cardNumbers).isSorted().doesNotHaveDuplicates();
            assertThat(crossReference.recordAt(0)).isEqualTo("050002445376574000000005000000000050");
        }

        @Test
        @DisplayName("the early exit would miss records if the input were not ascending")
        void theEarlyExitDependsOnTheOrdering() {
            // A direct demonstration of why the ordering guarantee cannot be dropped silently - High severity.
            // Scanning a descending list with the legacy early-exit predicate misses a record that is present.
            final String sought = "9805583408996588";
            final List<String> descending = List.of("9905583408996588", sought, "0500024453765740");
            boolean foundWithEarlyExit = false;
            for (final String candidate : descending) {
                if (candidate.compareTo(sought) > 0) {
                    break;
                }
                if (candidate.equals(sought)) {
                    foundWithEarlyExit = true;
                    break;
                }
            }

            assertThat(descending).contains(sought);
            assertThat(foundWithEarlyExit)
                    .as("the legacy early exit finds a present record in unsorted input")
                    .isFalse();

            final List<String> ascending = new ArrayList<>(descending);
            ascending.sort(Comparator.naturalOrder());
            boolean foundWhenSorted = false;
            for (final String candidate : ascending) {
                if (candidate.compareTo(sought) > 0) {
                    break;
                }
                if (candidate.equals(sought)) {
                    foundWhenSorted = true;
                    break;
                }
            }
            assertThat(foundWhenSorted).isTrue();
        }

        @Test
        @DisplayName("a card group preserves encounter order, so it can carry the sort's guarantee")
        void theCardGroupPreservesOrder() {
            // CardGroup is backed by a List, never a Map or a Set, so no hash iteration order can reorder the
            // records the sort placed in sequence.
            final List<StatementTransaction> ordered = new ArrayList<>();
            for (int sequence = 1; sequence <= 3; sequence++) {
                ordered.add(new StatementTransaction("0500024453765740",
                        String.format(Locale.ROOT, "%016d", sequence), null, null, null, null, null, null,
                        null, null, null, null, null, null));
            }
            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup("0500024453765740", ordered);

            assertThat(group.transactions()).containsExactlyElementsOf(ordered);
            assertThat(group.transactions().stream().map(StatementTransaction::transactionId).toList())
                    .containsExactly("0000000000000001", "0000000000000002", "0000000000000003")
                    .isSorted();
        }
    }


    @Nested
    @DisplayName("11. Fixture parity: all 300 daily transaction records project into this record")
    class DailyTransactionFixtureParity {

        @Test
        @DisplayName("the fixture is loaded by classpath resource name and proves its own census")
        void theFixtureProvesItsCensus() {
            // The resource name spells "daily" in full even though the mainframe DD name is DALYTRAN. A load of
            // "dalytran.txt" resolves to nothing. FixtureLoader.load additionally rejects a fixture whose byte
            // count, record count or record width has moved, so a whitespace cleanup that destroyed the
            // fixed-width geometry would fail here rather than silently produce wrong values.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThat(daily.resourceName()).isEqualTo("dailytran.txt").isNotEqualTo("dalytran.txt");
            assertThat(daily.recordCount()).isEqualTo(300);
            assertThat(daily.recordWidth())
                    .isEqualTo(350)
                    .isEqualTo(StatementTransaction.RECORD_LENGTH);
            assertThat(daily.byteCount()).isEqualTo(105_300).isEqualTo(daily.impliedByteCount());
            assertThat(daily.byteCount()).isEqualTo(300 * (350 + 1));
        }

        @Test
        @DisplayName("the fixture's record width is the statement record's own declared length")
        void theFixtureWidthMatchesTheRecordLength() {
            // app/cpy/CVTRA06Y.cpy is the staging layout and app/cpy/COSTM01.CPY the projected one; both are 350
            // bytes, which is what lets the projection be a pure reordering rather than a resize.
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.recordWidth())
                    .isEqualTo(StatementTransaction.RECORD_LENGTH);
        }

        @Test
        @DisplayName("every one of the 300 rows projects into a record whose widths all hold")
        void allThreeHundredRowsProject() {
            // The width guards are a maximum, so a row that overflows any field throws at construction. Running
            // all 300 rows through is therefore itself the assertion that the offset map is right: an off-by-one
            // offset would slide a 26-character timestamp into a 10-character ZIP and fail loudly.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final List<StatementTransaction> projected = new ArrayList<>();
            for (int row = 0; row < daily.recordCount(); row++) {
                projected.add(projectedFrom(daily, row));
            }

            assertThat(projected).hasSize(300).doesNotContainNull();
            for (int row = 0; row < projected.size(); row++) {
                final StatementTransaction record = projected.get(row);
                // Failures are identified by row index and field name; no card number is ever interpolated.
                assertThat(record.cardNumber()).as("cardNumber of row %d", row)
                        .hasSize(StatementTransaction.CARD_NUMBER_LENGTH);
                assertThat(record.transactionId()).as("transactionId of row %d", row)
                        .hasSize(StatementTransaction.TRANSACTION_ID_LENGTH);
                assertThat(record.description()).as("description of row %d", row)
                        .hasSize(StatementTransaction.DESCRIPTION_LENGTH);
                assertThat(record.originatingTimestamp()).as("originatingTimestamp of row %d", row)
                        .hasSize(StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH);
                assertThat(record.processingTimestamp()).as("processingTimestamp of row %d", row)
                        .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            }
        }

        @Test
        @DisplayName("the projection yields 300 distinct identifiers over 50 distinct cards")
        void identifiersAndCardsAreDistinctAsExpected() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> identifiers = new TreeSet<>();
            final Set<String> cards = new TreeSet<>();
            for (int row = 0; row < daily.recordCount(); row++) {
                final StatementTransaction record = projectedFrom(daily, row);
                identifiers.add(record.transactionId());
                cards.add(record.cardNumber());
            }

            assertThat(identifiers).hasSize(300);
            assertThat(cards).hasSize(50);
            // A TreeSet orders naturally, so this also confirms the card numbers form an orderable ascending set
            // - the precondition the downstream linear scan depends on.
            assertThat(List.copyOf(cards)).isSorted();
        }

        @Test
        @DisplayName("every projected card number is present in the cross-reference, so there are no orphans")
        void thereAreNoOrphanedCards() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final FixtureLoader.FixtureData crossReference =
                    FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
            final Set<String> knownCards = new TreeSet<>();
            for (int row = 0; row < crossReference.recordCount(); row++) {
                knownCards.add(crossReference.field(row, 1, StatementTransaction.CARD_NUMBER_LENGTH));
            }
            final Set<String> projectedCards = new TreeSet<>();
            for (int row = 0; row < daily.recordCount(); row++) {
                projectedCards.add(projectedFrom(daily, row).cardNumber());
            }

            assertThat(knownCards).hasSize(50);
            assertThat(projectedCards).isSubsetOf(knownCards);
        }

        @Test
        @DisplayName("the 250-to-50 partition by type, source and sign holds exactly")
        void theTypeSourceAndSignPartitionHolds() {
            // Verified against the fixture: 250 rows are (type 01, source "POS TERM  ", positive) and 50 are
            // (type 03, source "OPERATOR  ", negative). The partition is what makes the fixture exercise both
            // sign branches, and it is why no abs() may appear on this path.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            int purchases = 0;
            int operatorAdjustments = 0;
            for (int row = 0; row < daily.recordCount(); row++) {
                final StatementTransaction record = projectedFrom(daily, row);
                if ("01".equals(record.typeCode())) {
                    assertThat(record.source()).as("source of row %d", row).isEqualTo(SOURCE_POS_TERMINAL);
                    assertThat(record.amount().signum()).as("sign of row %d", row).isPositive();
                    purchases++;
                } else {
                    assertThat(record.typeCode()).as("typeCode of row %d", row).isEqualTo("03");
                    assertThat(record.source()).as("source of row %d", row).isEqualTo(SOURCE_OPERATOR);
                    assertThat(record.amount().signum()).as("sign of row %d", row).isNegative();
                    operatorAdjustments++;
                }
            }

            assertThat(purchases).isEqualTo(250);
            assertThat(operatorAdjustments).isEqualTo(50);
            assertThat(purchases + operatorAdjustments).isEqualTo(300);
        }

        @Test
        @DisplayName("the amounts span both signs, exercise every overpunch code and include no zero")
        void amountsSpanBothSignsAndCarryNoZero() {
            // All twenty overpunch characters appear in the sign position across the 300 rows: { and A-I for
            // positive, } and J-R for negative. The absence of any zero amount is what makes baseline evidence
            // for a zero report rendering "Not available" - see renderEditMask's contract.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<Character> overpunchCodes = new TreeSet<>();
            BigDecimal lowest = null;
            BigDecimal highest = null;
            for (int row = 0; row < daily.recordCount(); row++) {
                final String rawAmount = daily.field(row, BASE_AMOUNT_COLUMN,
                        FixtureLoader.AMOUNT_FIELD_WIDTH);
                overpunchCodes.add(rawAmount.charAt(rawAmount.length() - 1));
                final BigDecimal amount = projectedFrom(daily, row).amount();
                assertThat(amount.signum()).as("sign of row %d", row).isNotZero();
                assertThat(amount.scale()).as("scale of row %d", row)
                        .isEqualTo(StatementTransaction.AMOUNT_SCALE);
                lowest = lowest == null || amount.compareTo(lowest) < 0 ? amount : lowest;
                highest = highest == null || amount.compareTo(highest) > 0 ? amount : highest;
            }

            assertThat(overpunchCodes).hasSize(20);
            assertThat(lowest).isEqualByComparingTo("-998.33");
            assertThat(highest).isEqualByComparingTo("999.77");
        }

        @Test
        @DisplayName("overpunch decoding is position-aware, so letters inside merchant text are never signs")
        void overpunchDecodingIsPositionAware() {
            // Blocker severity if a global text substitution is used instead. The letters A-R that encode a sign
            // in the last position of a numeric field occur legitimately inside TRNX-DESC, TRNX-MERCHANT-NAME and
            // TRNX-MERCHANT-CITY, so decoding must be driven by the PIC clause offset and width alone. Decoding
            // a merchant-name slice as a zoned decimal must therefore FAIL rather than yield a number.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final String merchantName = daily.field(0, BASE_MERCHANT_NAME_COLUMN,
                    StatementTransaction.MERCHANT_NAME_LENGTH);

            assertThat(merchantName).containsAnyOf("A", "B", "C", "D", "E", "F", "G", "H", "I",
                    "J", "K", "L", "M", "N", "O", "P", "Q", "R");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(merchantName,
                            StatementTransaction.AMOUNT_SCALE));
            // The same decode at the amount's own offset and width succeeds, which is the contrast that proves
            // the decode is positional rather than content-driven.
            assertThat(daily.signedDecimal(0, BASE_AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .isNotNull();
        }

        @ParameterizedTest
        @CsvSource({"'{', 0.00", "'A', 0.01", "'I', 0.09", "'}', 0.00", "'J', -0.01", "'R', -0.09"})
        @DisplayName("each overpunch boundary character decodes to the digit and sign it denotes")
        void overpunchBoundaryCharactersDecodeCorrectly(final String overpunch, final String expected) {
            // The full table is { -> +0, A-I -> +1..+9, } -> -0, J-R -> -1..-9. The two zero cases are the
            // interesting ones: {  and } both denote a zero low-order digit and differ only in sign.
            final BigDecimal decoded = FixtureLoader.decodeZonedDecimal("0000000000" + overpunch,
                    StatementTransaction.AMOUNT_SCALE);

            assertThat(decoded).isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("the originating timestamp is the canonical online shape on every row")
        void everyRowCarriesTheCanonicalOnlineTimestamp() {
            // The fixture's columns 279-304 hold exactly one distinct value across all 300 rows, and it is the
            // rendering that FixedClockProvider's canonical instant produces. That is why the canonical instant
            // is 2022-06-10T19:27:53Z rather than an arbitrary choice.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> originatingValues = new TreeSet<>();
            for (int row = 0; row < daily.recordCount(); row++) {
                originatingValues.add(projectedFrom(daily, row).originatingTimestamp());
            }

            assertThat(originatingValues).hasSize(1);
            assertThat(originatingValues.iterator().next())
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .isEqualTo(FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock()));
        }

        @Test
        @DisplayName("the processing timestamp is 26 blanks on every row, which is not the same as absent")
        void everyRowCarriesABlankProcessingTimestamp() {
            // A staging record has not been posted yet, so its processing timestamp is blank rather than missing.
            // Collapsing blank into null would lose that distinction - Blocker severity.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int row = 0; row < daily.recordCount(); row++) {
                final StatementTransaction record = projectedFrom(daily, row);
                assertThat(record.processingTimestamp()).as("processingTimestamp of row %d", row)
                        .isNotNull()
                        .isBlank()
                        .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            }
        }

        @Test
        @DisplayName("the category code and merchant identifier keep their leading zeros on every row")
        void categoryCodeAndMerchantIdentifierKeepLeadingZeros() {
            // Every row carries category code 0001 and merchant identifier 800000000. The category code is the
            // leading-zero case that a numeric type would destroy.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int row = 0; row < daily.recordCount(); row++) {
                final StatementTransaction record = projectedFrom(daily, row);
                assertThat(record.categoryCode()).as("categoryCode of row %d", row).isEqualTo("0001");
                assertThat(record.merchantId()).as("merchantId of row %d", row).isEqualTo("800000000");
            }
        }

        @Test
        @DisplayName("merchant postal codes mix five-digit and ZIP+4 forms, so no fixed-length rule may be imposed")
        void merchantZipMixesFiveDigitAndZipPlusFour() {
            // Both forms occur in the fixture, so a digits-only or fixed-length constraint on the ZIP would
            // reject legitimate legacy data. The field is PIC X(10) and stays text.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<Integer> significantLengths = new TreeSet<>();
            for (int row = 0; row < daily.recordCount(); row++) {
                final String zip = projectedFrom(daily, row).merchantZip();
                assertThat(zip).as("merchantZip of row %d", row)
                        .hasSize(StatementTransaction.MERCHANT_ZIP_LENGTH);
                significantLengths.add(zip.strip().length());
            }

            assertThat(significantLengths).containsExactly(5, 10);
        }

        @Test
        @DisplayName("the fixture's trailing filler is blank, and the projection drops it rather than copying it")
        void theFillerIsBlankInTheFixtureAndAbsentInTheProjection() {
            // Two distinct facts, asserted separately so neither hides the other: the fixture's columns 331-350
            // are blank, and the projected record's filler is absent because output positions 331-350 are never
            // written by app/jcl/CREASTMT.JCL:L54.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int row = 0; row < daily.recordCount(); row++) {
                assertThat(daily.field(row, BASE_FILLER_COLUMN, StatementTransaction.FILLER_LENGTH))
                        .as("fixture filler of row %d", row)
                        .isBlank()
                        .hasSize(StatementTransaction.FILLER_LENGTH);
                assertThat(projectedFrom(daily, row).filler())
                        .as("projected filler of row %d", row)
                        .isNull();
            }
        }

        @Test
        @DisplayName("a projected record round-trips through the key without losing either half")
        void aProjectedRecordRoundTripsThroughItsKey() {
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final StatementTransaction record = projectedFrom(daily, 0);
            final StatementTransaction.Key key =
                    new StatementTransaction.Key(record.cardNumber(), record.transactionId());

            assertThat(key.cardNumber()).isEqualTo(record.cardNumber());
            assertThat(key.transactionId()).isEqualTo(record.transactionId());
            assertThat(key.cardNumber().length() + key.transactionId().length())
                    .isEqualTo(StatementTransaction.KEY_LENGTH);
        }

        @Test
        @DisplayName("grouping projected records by card yields unbounded groups, not ten-record ones")
        void groupingProjectedRecordsIsUnbounded() {
            // 300 records over 50 cards averages six per card, but the assertion that matters is that a group
            // larger than the legacy ten-slot limit is constructible at all.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final List<StatementTransaction> everyRecord = new ArrayList<>();
            for (int row = 0; row < daily.recordCount(); row++) {
                everyRecord.add(projectedFrom(daily, row));
            }
            final StatementTransaction.CardGroup oversized =
                    new StatementTransaction.CardGroup(everyRecord.get(0).cardNumber(), everyRecord);

            assertThat(oversized.transactions())
                    .hasSize(300)
                    .hasSizeGreaterThan(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD);
        }
    }


    @Nested
    @DisplayName("12. Absent, blank and low-values are three distinct states, and every width is bounded")
    class TriStateAndBoundaries {

        @Test
        @DisplayName("null, empty and blank are three different values and none is coerced into another")
        void nullEmptyAndBlankStayDistinct() {
            // app/cpy/CSSETATY.cpy is a COPY ... REPLACING PROCEDURE DIVISION template - procedural, so it gets
            // no class of its own - and it models exactly OK / NOT-OK / BLANK, with the markers firing only on
            // re-entry. app/cbl/COACTUPC.cbl:505-508 corroborates at message level: 'Credit Limit must be
            // supplied' for BLANK against 'Credit Limit is not valid' for NOT-OK. Two different messages means
            // two different states, so collapsing them is Blocker severity.
            final StatementTransaction absent = new StatementTransaction(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
            final StatementTransaction empty = new StatementTransaction("", "", "", "", "", "", null, "", "",
                    "", "", "", "", "");
            final StatementTransaction blank = new StatementTransaction(" ".repeat(16), " ".repeat(16), "  ",
                    "    ", " ".repeat(10), " ".repeat(100), null, " ".repeat(9), " ".repeat(50),
                    " ".repeat(50), " ".repeat(10), " ".repeat(26), " ".repeat(26), " ".repeat(20));

            assertThat(absent.cardNumber()).isNull();
            assertThat(empty.cardNumber()).isNotNull().isEmpty();
            assertThat(blank.cardNumber()).isNotNull().isNotEmpty().isBlank().hasSize(16);
            assertThat(absent).isNotEqualTo(empty);
            assertThat(empty).isNotEqualTo(blank);
            assertThat(absent).isNotEqualTo(blank);
        }

        @Test
        @DisplayName("a low-values image is preserved as given, distinct from both blank and absent")
        void lowValuesArePreserved() {
            // COBOL LOW-VALUES is a run of NUL characters, not spaces. It must survive untouched, because a
            // record initialised to LOW-VALUES is distinguishable from one initialised to SPACES.
            final String lowValues = "\u0000".repeat(StatementTransaction.CARD_NUMBER_LENGTH);
            final StatementTransaction record = new StatementTransaction(lowValues, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(record.cardNumber())
                    .isEqualTo(lowValues)
                    .hasSize(StatementTransaction.CARD_NUMBER_LENGTH)
                    .isNotEqualTo(" ".repeat(StatementTransaction.CARD_NUMBER_LENGTH))
                    .isNotEmpty();
            assertThat(record.cardNumber().charAt(0)).isEqualTo('\u0000');
        }

        @Test
        @DisplayName("no value is trimmed, upper-cased or lower-cased on the way in")
        void noValueIsNormalised() {
            // Trimming would destroy the fixed-width padding the byte-exact comparison depends on, and the hazard
            // is acute here: a trimmed 24-character processing timestamp is indistinguishable from a trimmed
            // 26-character one, which would erase the truncation evidence entirely.
            final String paddedMixedCase = "MiXeD cAsE   ";
            final StatementTransaction record = new StatementTransaction(null, null, null, null, null,
                    paddedMixedCase, null, null, null, null, null, null, null, null);

            assertThat(record.description())
                    .isEqualTo(paddedMixedCase)
                    .hasSize(paddedMixedCase.length())
                    .endsWith("   ");
        }

        @ParameterizedTest
        @CsvSource({
            "cardNumber, 16",
            "transactionId, 16",
            "typeCode, 2",
            "categoryCode, 4",
            "source, 10",
            "description, 100",
            "merchantId, 9",
            "merchantName, 50",
            "merchantCity, 50",
            "merchantZip, 10",
            "originatingTimestamp, 26",
            "processingTimestamp, 26",
            "filler, 20"
        })
        @DisplayName("every text component accepts one under, exactly at, and refuses one over its declared width")
        void everyTextComponentIsBoundedAtItsDeclaredWidth(final String fieldName, final int declaredWidth) {
            // One test covering all thirteen text components, driven by the copybook widths of
            // app/cpy/COSTM01.CPY:L22-L36. The guard is a MAXIMUM, so one under must be accepted and left
            // unpadded - that is exactly what lets the 24-character projected timestamp through.
            final String oneUnder = "X".repeat(declaredWidth - 1);
            final String exactly = "X".repeat(declaredWidth);
            final String oneOver = "X".repeat(declaredWidth + 1);

            assertThat(componentFor(fieldName, oneUnder)).isEqualTo(oneUnder).hasSize(declaredWidth - 1);
            assertThat(componentFor(fieldName, exactly)).isEqualTo(exactly).hasSize(declaredWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("one character over the declared width of %s", fieldName)
                    .isThrownBy(() -> componentFor(fieldName, oneOver))
                    .withMessageContaining(fieldName)
                    .withMessageContaining("exceeds its declared COBOL width")
                    .withMessageContaining(String.valueOf(declaredWidth + 1))
                    .withMessageContaining(String.valueOf(declaredWidth));
        }

        /**
         * Builds a record carrying {@code value} in the named component and reads that component back.
         *
         * <p>Keeps the boundary test above data-driven rather than repeating a fourteen-argument constructor
         * thirteen times, which is Rule 1 clause C's "avoid duplication" applied to a test.
         *
         * @param fieldName the record component to populate, spelled as the component name
         * @param value     the value to place in it
         * @return the value as the constructed record reports it
         * @throws IllegalArgumentException if the value exceeds the component's declared width, propagated from
         *                                  the record's own guard
         */
        private String componentFor(final String fieldName, final String value) {
            return switch (fieldName) {
                case "cardNumber" -> new StatementTransaction(value, null, null, null, null, null, null, null,
                        null, null, null, null, null, null).cardNumber();
                case "transactionId" -> new StatementTransaction(null, value, null, null, null, null, null,
                        null, null, null, null, null, null, null).transactionId();
                case "typeCode" -> new StatementTransaction(null, null, value, null, null, null, null, null,
                        null, null, null, null, null, null).typeCode();
                case "categoryCode" -> new StatementTransaction(null, null, null, value, null, null, null,
                        null, null, null, null, null, null, null).categoryCode();
                case "source" -> new StatementTransaction(null, null, null, null, value, null, null, null,
                        null, null, null, null, null, null).source();
                case "description" -> new StatementTransaction(null, null, null, null, null, value, null, null,
                        null, null, null, null, null, null).description();
                case "merchantId" -> new StatementTransaction(null, null, null, null, null, null, null, value,
                        null, null, null, null, null, null).merchantId();
                case "merchantName" -> new StatementTransaction(null, null, null, null, null, null, null,
                        null, value, null, null, null, null, null).merchantName();
                case "merchantCity" -> new StatementTransaction(null, null, null, null, null, null, null,
                        null, null, value, null, null, null, null).merchantCity();
                case "merchantZip" -> new StatementTransaction(null, null, null, null, null, null, null, null,
                        null, null, value, null, null, null).merchantZip();
                case "originatingTimestamp" -> new StatementTransaction(null, null, null, null, null, null,
                        null, null, null, null, null, value, null, null).originatingTimestamp();
                case "processingTimestamp" -> new StatementTransaction(null, null, null, null, null, null,
                        null, null, null, null, null, null, value, null).processingTimestamp();
                case "filler" -> new StatementTransaction(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, value).filler();
                default -> throw new IllegalArgumentException("no such component: " + fieldName);
            };
        }

        @Test
        @DisplayName("the report detail line bounds each of its own seven text components too")
        void theDetailLineBoundsItsTextComponents() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportDetailLine("X".repeat(17), null, null,
                            null, null, null, null, null))
                    .withMessageContaining("transactionId");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportDetailLine(null, "X".repeat(12), null,
                            null, null, null, null, null))
                    .withMessageContaining("accountId");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportDetailLine(null, null, null,
                            "X".repeat(16), null, null, null, null))
                    .withMessageContaining("typeDescription");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportDetailLine(null, null, null, null, null,
                            "X".repeat(30), null, null))
                    .withMessageContaining("categoryDescription");
        }

        @Test
        @DisplayName("the failure message names the field and never quotes the offending value")
        void theFailureMessageWithholdsTheValue() {
            // Rule 1 clause D names tests explicitly, and the card number is PII. The width guard's message must
            // therefore report the field, the supplied length and the permitted length - and never the content.
            //
            // The probe value is deliberately non-numeric and self-describing rather than a realistic card
            // number: the test is about the width guard, so the content is irrelevant, and a value that cannot
            // match any card-number shape keeps a PAN-like literal out of the source altogether.
            final String overlongCard = "NOT-A-REAL-PAN-17";

            assertThat(overlongCard).hasSize(StatementTransaction.CARD_NUMBER_LENGTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction(overlongCard, null, null, null, null, null,
                            null, null, null, null, null, null, null, null))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("17")
                    .withMessageContaining("16")
                    .withMessageNotContaining(overlongCard)
                    .withMessageNotContaining("NOT-A-REAL");
        }

        @Test
        @DisplayName("a card group's construction failures carry their own message and have no cause to lose")
        void groupConstructionFailuresCarryContext() {
            // Rule 1 clause B: no swallowed exception, and context preserved. These guards raise directly rather
            // than wrapping, so there is no root cause to keep - which the assertion states explicitly rather
            // than leaving ambiguous.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.CardGroup("0500024453765740", null))
                    .withMessageContaining("must not be null")
                    .withMessageContaining("empty list")
                    .withNoCause();
            final List<StatementTransaction> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.CardGroup("0500024453765740", withNull))
                    .withMessageContaining("null element")
                    .withNoCause();
        }

        @Test
        @DisplayName("the signedDecimal decode failure names the resource and offset and preserves its cause")
        void fixtureDecodeFailuresPreserveTheirCause() {
            // The other half of Rule 1 clause B: where a failure IS wrapped, the root cause must survive. The
            // fixture loader wraps a malformed-decimal IllegalArgumentException in an IllegalStateException that
            // names the resource, the row and the columns.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> daily.signedDecimal(0, BASE_MERCHANT_NAME_COLUMN,
                            StatementTransaction.MERCHANT_NAME_LENGTH))
                    .withMessageContaining("dailytran.txt")
                    .withMessageContaining("record 0")
                    .havingCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("no class-level constraint models a cross-field edit, because the source gates its own")
        void noClassLevelCrossFieldConstraint() {
            // app/cbl/COACTUPC.cbl:1667-1672 runs the two single-field edits and :L1674-L1678 runs the cross-field
            // edit ONLY when both have already passed. A class-level @AssertTrue fires unconditionally and would
            // therefore produce a different message set from the source - High severity. The record carries no
            // type-level annotation at all, which settles it.
            for (final Class<?> recordType : statementRecordTypes()) {
                final List<String> annotationNames = new ArrayList<>();
                for (final Annotation annotation : recordType.getAnnotations()) {
                    annotationNames.add(annotation.annotationType().getName());
                }
                assertThat(annotationNames)
                        .as("type-level annotations on %s", recordType.getSimpleName())
                        .noneMatch(name -> name.contains("AssertTrue"))
                        .noneMatch(name -> name.contains("AssertFalse"))
                        .noneMatch(name -> name.contains("ScriptAssert"));
            }
        }
    }

    @Nested
    @DisplayName("13. The preserved quirks and the labelled capacity deviation")
    class PreservedLegacyQuirks {

        @Test
        @DisplayName("the legacy ceiling is carried as three consistent figures")
        void theLegacyCeilingIsCarriedAsHistory() {
            // app/cbl/CBSTM03A.CBL:226 declares OCCURS 51 TIMES and :228 declares OCCURS 10 TIMES, so 51 x 10 =
            // 510. Asserting the product rather than the literal is what keeps the three figures consistent.
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN).isEqualTo(51);
            assertThat(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD).isEqualTo(10);
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN
                    * StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .isEqualTo(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN)
                    .isEqualTo(510);
        }

        @Test
        @DisplayName("the ceiling is recorded but NOT enforced, which is the labelled deviation")
        void theCeilingIsRecordedButNotEnforced() {
            // High severity either way: implementing a 510 cap would be a regression, and removing the ceiling
            // without labelling it would be a false parity claim. The deviation is justified in writing in this
            // class's docstring and in the record's own, and it is owed an entry in the planned DECISION_LOG.md;
            // the legacy figure is owed an entry in the planned TRACEABILITY_MATRIX.md as the historical capacity
            // limit. Here the removal is proven executable: a group larger than the ceiling is constructible.
            final List<StatementTransaction> beyondTheCeiling = new ArrayList<>();
            for (int sequence = 0;
                    sequence <= StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN;
                    sequence++) {
                beyondTheCeiling.add(new StatementTransaction("0500024453765740",
                        String.format(Locale.ROOT, "%016d", sequence), null, null, null, null, null, null,
                        null, null, null, null, null, null));
            }
            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup("0500024453765740", beyondTheCeiling);

            assertThat(group.transactions())
                    .hasSize(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN + 1)
                    .hasSize(511);
            assertThat(group.transactions().size())
                    .isGreaterThan(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN);
        }

        @Test
        @DisplayName("the group is backed by a list, so the removed ceiling did not become a hash-ordered bag")
        void theGroupIsListBackedRatherThanSetBacked() {
            // Removing a fixed table is only safe if what replaces it preserves order. A Set or a Map would
            // remove the ceiling and the ordering guarantee at once, and the ordering guarantee is a correctness
            // dependency of app/cbl/CBSTM03A.CBL:L416-L419.
            final List<String> componentTypes =
                    componentTypeNames(StatementTransaction.CardGroup.class);

            assertThat(componentTypes).contains(List.class.getName());
            assertThat(componentTypes)
                    .doesNotContain("java.util.Set", "java.util.Map", "java.util.HashSet",
                            "java.util.HashMap");
        }

        @Test
        @DisplayName("the redundant legacy index assignment is documented here and not reproduced as Java")
        void theRedundantIndexAssignmentIsNotReproduced() {
            // app/cbl/CBSTM03A.CBL:L316-L338 sets the outer subscript immediately before a VARYING loop that
            // re-initialises it anyway. It stays verbatim in the frozen source for fidelity, but reproducing it
            // in Java would create dead code that Rule 1 clause B forbids while adding nothing to parity - there
            // is no observable behaviour to preserve, because the second assignment overwrites the first.
            //
            // What IS observable is the ordering the loop produces, and that is asserted in
            // SortProjectionOrdering. This test records that the record type exposes no subscript, cursor or
            // index of its own for such an assignment to target.
            assertThat(componentNames(StatementTransaction.class))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("index"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("subscript"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("cursor"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("counter"));
        }
    }

    @Nested
    @DisplayName("14. Legacy defects logged with their locators, deliberately not repaired")
    class LoggedLegacyDefects {

        @Test
        @DisplayName("the 80-versus-100 HTML mismatch is resolved to the execution step, not harmonised away")
        void theHtmlRecordLengthMismatchIsLoggedNotHarmonised() {
            // app/jcl/CREASTMT.JCL:L69 declares LRECL=80 for HTMLFILE in the pre-delete step while :L94 declares
            // LRECL=100 for the same DD in the execution step. The execution step governs, and the 100 is
            // corroborated independently by HTML-FIXED-LN PIC X(100) at app/cbl/CBSTM03A.CBL:149 - which is
            // precisely why this is a defect to LOG rather than a signal to change the width. Harmonising the two
            // to a single value is High severity.
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH).isEqualTo(80);
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH
                    - StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH).isEqualTo(20);
        }

        @Test
        @DisplayName("the corrupted DD card and the procedure-name quirk are recorded without being modelled")
        void theCorruptedDdCardAndProcedureQuirkAreRecordedOnly() {
            // Two further frozen defects, Medium and Low: the corrupted STMTFILE DD card at
            // app/jcl/CREASTMT.JCL:L90, where SPACE=(CYL,(1,1),RLSE) is followed by fragments of unrelated text
            // on the same card; and app/proc/TRANREPT.prc:L1 declaring //REPROC PROC so the member's internal
            // name is REPROC while EXEC PROC=TRANREPT resolves the member name TRANREPT.
            //
            // Neither has any counterpart on this type, and inventing one would be worse than recording it. What
            // this test proves is that the widths those cards allocate are still carried correctly, so a reader
            // who finds the corrupted card does not conclude the width was lost with it.
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH).isEqualTo(80);
            assertThat(StatementTransaction.REPORT_LINE_LENGTH).isEqualTo(133);
            assertThat(publicStaticFieldNames(StatementTransaction.class))
                    .noneMatch(name -> name.contains("REPROC"))
                    .noneMatch(name -> name.contains("DD_NAME"));
        }

        @Test
        @DisplayName("nothing on this type renders markup, so the HTML width is a budget and not a payload")
        void nothingRendersMarkup() {
            // app/cbl/CBSTM03A.CBL:149 emits HTML through a single PIC X(100) field carrying one literal fragment
            // per 88-level, so HTML emission is a fixed-width TEXT writer over a constant map of fragments - never
            // a rendered interface. There is no HTML, CSS or JavaScript application anywhere in scope: the
            // 3270/BMS layer is a field contract only. A markup-bearing component here would be High severity.
            //
            // STATEMENT_HTML_RECORD_LENGTH names HTML but is a byte width, which is why this assertion is scoped
            // to record components rather than to static field names.
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(componentNames(recordType))
                        .as("markup-bearing component on %s", recordType.getSimpleName())
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("html"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("markup"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("tag"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("attribute"));
            }
            assertThat(publicStaticFieldNames(StatementTransaction.class))
                    .contains("STATEMENT_HTML_RECORD_LENGTH");
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("15. The nested key and group types, and the renderings that must not leak")
    class NestedTypes {

        @Test
        @DisplayName("the key carries both parts and refuses an over-width component")
        void theKeyCarriesBothPartsAndBoundsThem() {
            final StatementTransaction.Key key =
                    new StatementTransaction.Key("0500024453765740", "0000000000000001");

            assertThat(key.cardNumber()).hasSize(16);
            assertThat(key.transactionId()).hasSize(16);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.Key("X".repeat(17), null))
                    .withMessageContaining("cardNumber");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.Key(null, "X".repeat(17)))
                    .withMessageContaining("transactionId");
        }

        @Test
        @DisplayName("two keys with the same parts are equal, as a record's value semantics require")
        void keysAreValueBased() {
            // The key spans the whole 32 bytes, so equality must too: a key that ignored either half would
            // collide across cards or across transactions.
            final StatementTransaction.Key first =
                    new StatementTransaction.Key("0500024453765740", "0000000000000001");
            final StatementTransaction.Key same =
                    new StatementTransaction.Key("0500024453765740", "0000000000000001");
            final StatementTransaction.Key otherCard =
                    new StatementTransaction.Key("9805583408996588", "0000000000000001");
            final StatementTransaction.Key otherId =
                    new StatementTransaction.Key("0500024453765740", "0000000000000002");

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(otherCard).isNotEqualTo(otherId);
        }

        @Test
        @DisplayName("no rendering of the record, the key or the group discloses the card number")
        void noRenderingDisclosesTheCardNumber() {
            // Blocker severity. The card number is the FIRST component of the key, so the generated record
            // rendering would publish it and a naive key-oriented log line would too. All three renderings are
            // overridden to emit only non-sensitive values.
            final String cardNumber = "0500024453765740";
            final StatementTransaction record = new StatementTransaction(cardNumber, "0000000000000001",
                    "01", "0001", SOURCE_POS_TERMINAL, "GROCERY PURCHASE", new BigDecimal("12.34"),
                    "800000000", "ACME STORES", "SEATTLE", "98101", null, null, null);
            final StatementTransaction.Key key =
                    new StatementTransaction.Key(cardNumber, "0000000000000001");
            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup(cardNumber, List.of(record));

            for (final String rendering : List.of(record.toString(), key.toString(), group.toString())) {
                assertThat(rendering)
                        .doesNotContain(cardNumber)
                        .doesNotContain("0500")
                        .doesNotContain("765740");
            }
            assertThat(record.toString()).contains("0000000000000001");
            assertThat(group.toString()).contains("transactionCount=1");
        }

        @Test
        @DisplayName("no rendering discloses the merchant name, city or postal code either")
        void noRenderingDisclosesMerchantDetail() {
            final StatementTransaction record = new StatementTransaction("0500024453765740",
                    "0000000000000001", "01", "0001", SOURCE_POS_TERMINAL, "GROCERY PURCHASE",
                    new BigDecimal("12.34"), "800000000", "ACME STORES", "SEATTLE", "98101", null, null, null);

            assertThat(record.toString())
                    .doesNotContain("ACME STORES")
                    .doesNotContain("SEATTLE")
                    .doesNotContain("98101")
                    .doesNotContain("12.34");
            // Redacting the rendering must not redact the data: the accessors still return everything.
            assertThat(record.merchantName()).isEqualTo("ACME STORES");
            assertThat(record.merchantCity()).isEqualTo("SEATTLE");
            assertThat(record.merchantZip()).isEqualTo("98101");
        }

        @Test
        @DisplayName("the report line renderings disclose neither the account identifier nor any amount")
        void reportLineRenderingsDiscloseNoMoney() {
            // An account identifier beside a signed amount states whose money moved and how much, which is
            // customer financial activity whether or not a card number accompanies it. An account total is
            // attributable to exactly the account whose control break produced it, so an aggregate is not safer
            // than a single amount.
            final StatementTransaction.ReportDetailLine detail =
                    new StatementTransaction.ReportDetailLine("0000000000000001", "00000000011", "01",
                            "Purchase", "0001", "Groceries", SOURCE_POS_TERMINAL, new BigDecimal("4321.99"));
            final StatementTransaction.ReportTotalsLine totals =
                    StatementTransaction.ReportTotalsLine.accountTotal(new BigDecimal("87654.32"));

            assertThat(detail.toString())
                    .doesNotContain("00000000011")
                    .doesNotContain("4321.99")
                    .contains("0000000000000001");
            assertThat(totals.toString())
                    .doesNotContain("87654.32")
                    .contains("Account Total");
            assertThat(detail.accountId()).isEqualTo("00000000011");
            assertThat(totals.total()).isEqualByComparingTo("87654.32");
        }

        @Test
        @DisplayName("all five layout types override toString rather than inheriting the generated one")
        void allFiveTypesOverrideToString() {
            // The generated record rendering covers every component, so an inherited toString on any of the five
            // would publish whatever that layout holds. Checking the declaring class is what makes the guarantee
            // structural rather than incidental.
            for (final Class<?> recordType : statementRecordTypes()) {
                try {
                    assertThat(recordType.getDeclaredMethod("toString").getDeclaringClass())
                            .as("%s declares its own toString", recordType.getSimpleName())
                            .isEqualTo(recordType);
                } catch (final NoSuchMethodException missing) {
                    throw new AssertionError(recordType.getSimpleName()
                            + " does not override toString, so the generated rendering would publish every"
                            + " component including any sensitive one", missing);
                }
            }
        }

        @Test
        @DisplayName("a card group takes a defensive copy in both directions and stays unmodifiable")
        void theCardGroupIsDefensiveInBothDirections() {
            final List<StatementTransaction> mutable = new ArrayList<>();
            mutable.add(new StatementTransaction("0500024453765740", "0000000000000001", null, null, null,
                    null, null, null, null, null, null, null, null, null));
            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup("0500024453765740", mutable);

            mutable.clear();

            assertThat(group.transactions()).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> group.transactions().add(null));
        }

        @Test
        @DisplayName("a card group accepts an empty list, because a card may legitimately have no activity")
        void theCardGroupAcceptsAnEmptyList() {
            final StatementTransaction.CardGroup empty =
                    new StatementTransaction.CardGroup("0500024453765740", List.of());

            assertThat(empty.transactions()).isEmpty();
            assertThat(empty.toString()).contains("transactionCount=0");
        }

        @Test
        @DisplayName("only the three declared totals layouts are accepted, even when the widths sum correctly")
        void onlyDeclaredTotalsLayoutsAreAccepted() {
            // The padding invariant alone is not sufficient: 20 + 77 also sums to 97, but no layout in
            // app/cpy/CVTRA07Y.cpy:L50-L66 declares it.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine("Weekly Total", 20, 77,
                            BigDecimal.ZERO))
                    .withMessageContaining("do not match any totals layout");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine("Page Total", 13, 84,
                            BigDecimal.ZERO))
                    .withMessageContaining("do not match any totals layout");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine("Page Total", 11, 85,
                            BigDecimal.ZERO))
                    .withMessageContaining("must equal");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(null, 11, 86,
                            BigDecimal.ZERO))
                    .withMessageContaining("label must not be null");
        }

        @Test
        @DisplayName("a totals line accepts an absent total, because a period with no activity still prints")
        void aTotalsLineAcceptsAnAbsentTotal() {
            assertThat(StatementTransaction.ReportTotalsLine.pageTotal(null).total()).isNull();
            assertThat(StatementTransaction.ReportTotalsLine.accountTotal(null).total()).isNull();
            assertThat(StatementTransaction.ReportTotalsLine.grandTotal(null).total()).isNull();
        }

        @Test
        @DisplayName("a totals line normalises its total to the same scale the record uses")
        void aTotalsLineNormalisesItsTotal() {
            assertThat(StatementTransaction.ReportTotalsLine.grandTotal(new BigDecimal("1.5")).total().scale())
                    .isEqualTo(StatementTransaction.AMOUNT_SCALE);
            assertThat(StatementTransaction.ReportTotalsLine.grandTotal(new BigDecimal("1.5")).total())
                    .isEqualByComparingTo("1.50");
        }
    }

    @Nested
    @DisplayName("16. Absence proofs: what this type deliberately does not carry")
    class AbsenceProofs {

        @Test
        @DisplayName("no session-state or navigation field appears on any statement layout")
        void noSessionOrNavigationState() {
            // AAP transformation rule 7 requires no server-side session state. The COMMAREA fields with no Java
            // counterpart are CDEMO-FROM-TRANID, CDEMO-TO-TRANID, CDEMO-FROM-PROGRAM, CDEMO-TO-PROGRAM,
            // CDEMO-PGM-CONTEXT and the two screen-state fields CDEMO-LAST-MAP and CDEMO-LAST-MAPSET, both
            // PIC X(7) - seven, not eight - at app/cpy/COCOM01Y.cpy:L43-L44. Routing is URL-based and the
            // enter-versus-re-enter flag collapses into stateless request handling. High severity if one appears.
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(componentNames(recordType))
                        .as("session or navigation component on %s", recordType.getSimpleName())
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("tranid"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("program"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("mapset"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("lastmap"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("context"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("reenter"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("session"));
            }
        }

        @Test
        @DisplayName("no personally identifiable field beyond the card number is modelled at all")
        void noAdditionalSensitiveField() {
            // The card number is unavoidable - it is half the key - and is handled by redacting every rendering.
            // No password, hash, token, signing key, social security number, telephone number or date of birth
            // exists on this type to be handled at all, which is the stronger position.
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(componentNames(recordType))
                        .as("sensitive component on %s", recordType.getSimpleName())
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("password"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("secret"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("token"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("hash"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("ssn"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("phone"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("dob"))
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("birth"));
            }
        }

        @Test
        @DisplayName("no statement layout is serializable, so insecure deserialization has no entry point")
        void noLayoutIsSerializable() {
            // Rule 1 clause D flags insecure deserialization as a risky pattern. None of the five types
            // implements Serializable, and none declares an Externalizable or readObject surface either.
            for (final Class<?> recordType : statementRecordTypes()) {
                assertThat(Serializable.class.isAssignableFrom(recordType))
                        .as("%s implements Serializable", recordType.getSimpleName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no environment-specific literal is reachable through this type's published constants")
        void noEnvironmentSpecificLiteral() {
            // Rule 1 clause C: deterministic, with no environment-specific assumption. The published string
            // constants are copybook literals and edit masks only - no host, port, JDBC URL, cloud endpoint or
            // credential among them.
            final List<String> publishedStrings = List.of(
                    StatementTransaction.DETAIL_AMOUNT_MASK,
                    StatementTransaction.TOTALS_AMOUNT_MASK,
                    StatementTransaction.REPORT_SHORT_NAME,
                    StatementTransaction.REPORT_LONG_NAME,
                    StatementTransaction.REPORT_DATE_HEADER,
                    StatementTransaction.REPORT_DATE_SEPARATOR,
                    StatementTransaction.PAGE_TOTAL_LABEL,
                    StatementTransaction.ACCOUNT_TOTAL_LABEL,
                    StatementTransaction.GRAND_TOTAL_LABEL,
                    StatementTransaction.REPORT_CODE_DESCRIPTION_SEPARATOR);

            assertThat(publishedStrings)
                    .noneMatch(value -> value.contains("localhost"))
                    .noneMatch(value -> value.contains("127.0.0.1"))
                    .noneMatch(value -> value.contains("jdbc:"))
                    .noneMatch(value -> value.contains("amazonaws"))
                    .noneMatch(value -> value.contains("http"))
                    .noneMatch(value -> value.contains("://"));
        }

        @Test
        @DisplayName("the type publishes no logger, which for a card-number-bearing value carrier is deliberate")
        void noLoggerIsPublished() {
            // Rule 1 clause A asks for observability "where relevant". A pure value carrier is precisely where a
            // logger is not relevant, and here it would be actively harmful: a logger reachable from a type whose
            // leading key component is a card number is a leak waiting for a careless call site. Counters, timers
            // and spans for statement generation belong to the batch tier, where a job instance and a correlation
            // identifier are in scope.
            assertThat(componentTypeNames(StatementTransaction.class))
                    .noneMatch(typeName -> typeName.contains("Logger"))
                    .noneMatch(typeName -> typeName.contains("slf4j"))
                    .noneMatch(typeName -> typeName.contains("logback"));
            for (final Field field : StatementTransaction.class.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("field %s is a logger", field.getName())
                        .doesNotContain("Logger");
            }
        }

        @Test
        @DisplayName("every published static field is final, so none is global mutable state")
        void everyPublishedConstantIsFinal() {
            // Rule 1 clause B: avoid global mutable state. The legacy 51-by-10 table WAS global mutable state and
            // has no Java counterpart; nothing here reintroduces it.
            for (final Field field : StatementTransaction.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s is not final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("a symbolic-map census is Not available, because this type has no BMS map")
        void aSymbolicMapCensusIsNotAvailable() {
            // Rule 1 clause F: where information is missing, say so rather than invent it. This type is a batch
            // projection of app/cpy/COSTM01.CPY, not a screen, so no mapset in app/cpy-bms declares it and no
            // input-field census exists for it. Borrowing another map's census would be fabrication. Its field
            // count is fourteen COBOL data items, which is a record layout and not a field census.
            //
            // Were a common header ever to surface here, note that CURTIMEI is PIC X(8) on sixteen maps and
            // PIC X(9) only on app/cpy-bms/COSGN00.CPY:54, so no shared header helper would be sound.
            assertThat(componentNames(StatementTransaction.class)).hasSize(14);
            assertThat(componentNames(StatementTransaction.class))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("curtime"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("curdate"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("trnname"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("pgmname"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("title"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("errmsg"));
        }

        @Test
        @DisplayName("no persistence annotation appears, because this is a transfer object and not an entity")
        void noPersistenceAnnotation() {
            // Rule 1 clause F again: any DDL claim for this type is Not available. It maps to no table, it has no
            // identity column and no version column, and V1__create_schema.sql declares nothing for it. The
            // absence of a persistence annotation is what makes that provable rather than asserted.
            for (final Class<?> recordType : statementRecordTypes()) {
                final List<String> annotationNames = new ArrayList<>();
                for (final Annotation annotation : recordType.getAnnotations()) {
                    annotationNames.add(annotation.annotationType().getName());
                }
                assertThat(annotationNames)
                        .as("persistence annotation on %s", recordType.getSimpleName())
                        .noneMatch(name -> name.startsWith("jakarta.persistence"))
                        .noneMatch(name -> name.startsWith("javax.persistence"))
                        .noneMatch(name -> name.startsWith("org.springframework.data"));
            }
            assertThat(componentNames(StatementTransaction.class))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("version"));
        }
    }

}
