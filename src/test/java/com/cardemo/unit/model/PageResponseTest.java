/*
 * ******************************************************************
 * Program     : PageResponseTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM, no container, no Spring
 *               context, no database
 * Function    : Pins the response-metadata contract of
 *               com.cardemo.model.dto.PageResponse against the three
 *               legacy CardDemo list transactions. Asserts the two
 *               mutually incompatible next-page sentinels, the inverted
 *               last-page-displayed encoding, the diverging paging-field
 *               names and widths, the three unmerged page sizes, the
 *               absent / blank / LOW-VALUES tri-state, unmodifiable and
 *               insertion-ordered rows, every input boundary, and the
 *               absence of both server-side session state and any row
 *               payload in the diagnostic rendering.
 * Source      : app/cbl/COUSR00C.cbl:L54 WS-PAGE-NUM +
 *               app/cbl/COTRN00C.cbl:L63-L68 +
 *               app/cbl/COCRDLIC.cbl:L239-L244
 *               (WORKING-STORAGE, NOT COCOM01Y) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.dto.PageResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PageResponse}, the response-metadata type that replaces the screen-resident
 * paging state of the three legacy CardDemo list transactions.
 *
 * <h2>1. What this test class does</h2>
 *
 * <p>It pins the contract of a single data transfer object against the frozen COBOL corpus. Every
 * expectation below is anchored to a locator that was read at commit {@code 7756d89} rather than
 * inferred, because the corpus is the parity oracle and this class is one of the places the oracle is
 * mechanically checked. The class asserts seven things, in this order: the exact declared surface of
 * the type, the divergence of the paging field across the three list screens, the two incompatible
 * next-page sentinels, the three page sizes, the keyset boundary contract, immutability and row
 * ordering, and the input boundaries together with the diagnostic rendering.</p>
 *
 * <h3>1.1 Source attribution - where the paging fields are actually declared</h3>
 *
 * <p>The paging contract this type carries is declared per list program rather than in one shared record.
 * {@code app/cpy/COCOM01Y.cpy} is a 47-line, 160-byte communication area whose complete field list occupies
 * lines 19 to 44, and a case-insensitive search of that member for {@code PAGE} or {@code NEXT} returns
 * nothing. Each list program declares its own paging fields as level-05 items placed immediately after its
 * {@code COPY COCOM01Y.} statement, so those items extend the copybook's {@code 01 CARDDEMO-COMMAREA} group
 * while being declared in the program: see {@code app/cbl/COTRN00C.cbl:L61} followed by {@code :L62-L70},
 * and {@code app/cbl/COUSR00C.cbl:L66} followed by {@code :L67-L75}. An expanded listing therefore shows the
 * paging fields inside the COMMAREA group even though the copybook does not declare them, which is exactly
 * why the widths and the sentinels have to be cited program by program - they diverge, and section 2 of this
 * class asserts that divergence.</p>
 *
 * <p>The canonical anchor for this type is {@code app/cbl/COUSR00C.cbl:L54},
 * {@code 05 WS-PAGE-NUM PIC S9(04) COMP VALUE ZEROS.}, supported by
 * {@code app/cbl/COTRN00C.cbl:L63-L68} and {@code app/cbl/COCRDLIC.cbl:L239-L244}.</p>
 *
 * <h3>1.2 Every locator this class relies on</h3>
 *
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl:L54} - {@code WS-PAGE-NUM PIC S9(04) COMP}, the canonical
 *       WORKING-STORAGE origin of the page number.</li>
 *   <li>{@code app/cbl/COUSR00C.cbl:L57} - {@code 02 USER-REC OCCURS 10 TIMES.}, the user list page
 *       size of 10.</li>
 *   <li>{@code app/cbl/COTRN00C.cbl:L63-L68} - the {@code 'N'} sentinel family, with the
 *       {@code PIC X(16)} keyset boundaries and the {@code PIC 9(08)} page number.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:L177-L178} - {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7},
 *       the card list page size of 7.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl:L239-L244} - the {@code LOW-VALUES} sentinel family and the
 *       inverted last-page-displayed encoding.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl:L131} - {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}, a batch
 *       report line count that must never reach this type.</li>
 *   <li>{@code app/cpy-bms/COCRDLI.CPY:60} - {@code PAGENOI PIC X(3)}.</li>
 *   <li>{@code app/cpy-bms/COTRN00.CPY:60} - {@code PAGENUMI PIC X(8)}.</li>
 *   <li>{@code app/cpy-bms/COUSR00.CPY:60} - {@code PAGENUMI PIC X(8)}.</li>
 *   <li>{@code app/cpy-bms/COSGN00.CPY:54} - {@code CURTIMEI PIC X(9)}, the sole nine-character
 *       instance of a header field that is {@code X(8)} on the other sixteen maps.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:L41} - {@code CDEMO-CARD-NUM PIC 9(16)}, numeric.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:L43-L44} - {@code CDEMO-LAST-MAP} and
 *       {@code CDEMO-LAST-MAPSET}, both {@code PIC X(7)}.</li>
 *   <li>{@code app/cpy/CVACT02Y.cpy:L5} - {@code CARD-NUM PIC X(16)}, alphanumeric.</li>
 *   <li>{@code app/cpy/CSSETATY.cpy} - the {@code COPY ... REPLACING} procedure-division template
 *       whose parameters are {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)}, and which
 *       models the OK / NOT-OK / BLANK tri-state with markers firing only on re-entry. Procedural, not
 *       a data layout, so it maps to no class.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:505-508} - {@code CRED-LIMIT-IS-BLANK} against
 *       {@code CRED-LIMIT-IS-NOT-VALID}, the tri-state corroborated at message level.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:1665-1678} - the gated cross-field edit. The gate is
 *       {@code IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID} at {@code :1665-1666}, the gated edit is
 *       the {@code PERFORM 1280-EDIT-US-STATE-ZIP-CD} at {@code :1667-1668}, and the outcome is set at
 *       {@code :1674}.</li>
 *   <li>{@code app/data/ASCII/cardxref.txt} - 1850 bytes, 50 rows of 36 data bytes each, already
 *       strictly ascending by card number, which is the precondition that makes the early-exit scan in
 *       {@code app/cbl/CBSTM03A.CBL} correct.</li>
 *   </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B clean test} runs this class; {@code ./mvnw -B -o test -Dtest=PageResponseTest} runs it
 * alone against the warm local repository. <b>Collection depends on the path and the name together.</b>
 * The root {@code pom.xml} binds {@code maven-surefire-plugin} 3.5.4 to {@code **}{@code /*Test.java}
 * and {@code **}{@code /*Tests.java} while excluding {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}, and it binds {@code maven-failsafe-plugin} 3.5.4 to exactly those two
 * excluded trees. A class that leaves {@code src/test/java/com/cardemo/unit} for an integration or
 * end-to-end package, or that loses its {@code Test} suffix, is therefore collected by <b>neither</b>
 * plugin: the build stays green, both plugins report success, and the class silently never executes.</p>
 *
 * <p><b>How to confirm this class actually ran, and one report artefact not to be misled by.</b>
 * Surefire attributes a {@code @Nested} test to its group's display name rather than to the enclosing
 * class, and it does not roll those counts up into the enclosing element. Measured on this build, the
 * {@code tests} attribute of {@code <testsuite>} in
 * {@code target/surefire-reports/TEST-com.cardemo.unit.model.PageResponseTest.xml} therefore reads
 * {@code 0}, and the summary line of the sibling {@code .txt} reads {@code Tests run: 0}, while the
 * same XML file holds 58 {@code <testcase>} elements that all passed. Zero in that one attribute is a
 * reporting artefact of nesting and is <b>not</b> evidence of non-collection. Confirm collection by
 * counting test cases rather than by reading the summary counter:
 * {@code grep -c '<testcase' target/surefire-reports/TEST-com.cardemo.unit.model.PageResponseTest.xml}.
 * The two tests declared directly on this class rather than inside a group are reported under the real
 * fully qualified class name, so
 * {@code grep -c 'classname="com.cardemo.unit.model.PageResponseTest"' <that file>} is a second, and
 * name-exact, confirmation. Where a local toolchain is unavailable the pinned image reproduces all of
 * it: {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>There is no external configuration: this is a pure-JVM tier that starts no container, no Spring
 * context and no database, opens no socket, reads no environment variable and holds no credential, so
 * no endpoint is reachable and no privilege is required.</p>
 *
 * <p><b>Time.</b> The only sanctioned time source is {@link FixedClockProvider} in this package.
 * {@code Instant.now()}, {@code LocalDate.now()} and {@code System.currentTimeMillis()} are never
 * called, and the JVM default zone is never consulted;
 * {@link FixedClockProvider#CANONICAL_ZONE} is stated explicitly instead. Every parse and format
 * passes {@link java.util.Locale#ROOT}, so no assertion can shift with the platform locale.</p>
 *
 * <p><b>Test doubles.</b> None. {@code mockito-core} 5.17.0 is on the test classpath and its
 * {@code MockitoExtension} default strictness is {@code STRICT_STUBS}, which fails a test on an unused
 * stub. That default is honoured here by having nothing to honour: {@link PageResponse} is a pure data
 * holder with zero collaborators, so a mock would add a moving part without adding coverage. Real
 * values are used throughout.</p>
 *
 * <p><b>Reflection.</b> Several assertions read the declared surface of {@link PageResponse}. They all
 * filter synthetic members, which is load bearing rather than defensive: {@code jacoco-maven-plugin}
 * instruments classes through a Java agent during {@code verify} and adds a synthetic
 * {@code $jacocoData} field and {@code $jacocoInit} method, so an unfiltered
 * {@code getDeclaredFields()} assertion passes under {@code ./mvnw test} and fails under
 * {@code ./mvnw verify}. Reflection here is read-only: nothing is made accessible and nothing is
 * invoked.</p>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>The build fails on something trivial-looking.</b> Compilation runs with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that reaches test
 *       compilation, so one raw type or one deprecation is an error rather than a
 *       warning. An unused import is not - {@code javac} 25.0.3 publishes no lint key for one - so that is
 *       caught at review. Reproduce with {@code ./mvnw -o -q test-compile}.</li>
 *   <li><b>An assertion cites {@code COCOM01Y} for pagination.</b> The copybook has no
 *       paging field; see &sect;1.1 above and cite {@code app/cbl/COUSR00C.cbl:L54} instead.</li>
 *   <li><b>The two next-page sentinels get conflated.</b> {@code 'N'} at
 *       {@code app/cbl/COTRN00C.cbl:L68} and {@code LOW-VALUES} at
 *       {@code app/cbl/COCRDLIC.cbl:L243} both mean "no next page" but are different bytes, and no
 *       single character can serve both. This type transports neither, which is what keeps both
 *       adapters lossless.</li>
 *   <li><b>The last-page encoding gets inverted.</b>
 *       {@code app/cbl/COCRDLIC.cbl:L240-L241} defines {@code CA-LAST-PAGE-SHOWN VALUE 0} and
 *       {@code CA-LAST-PAGE-NOT-SHOWN VALUE 9}, so zero means the last page <b>is</b> shown. Reading
 *       zero as false is exactly backwards.</li>
 *   <li><b>The three page sizes get unified.</b> 7, 10 and 10 are three separate
 *       source facts that happen to include a repeated value; collapsing them to one constant loses
 *       the card list.</li>
 *   <li><b>One paging-field width gets baked in.</b> {@code PAGENOI} is
 *       {@code X(3)} and {@code PAGENUMI} is {@code X(8)}; a shared abstraction that assumes either
 *       breaks the other screen.</li>
 *   <li><b>A Spring Data type is imported.</b> {@code Page}, {@code Slice} and
 *       {@code Pageable} belong to the repository tier under {@code src/test/java/com/cardemo/
 *       integration/repository}, which is where the VSAM browse verbs are translated. This tier
 *       asserts only the contract of the object in front of it.</li>
 *   <li><b>A row payload reaches a log or an assertion message.</b> A page of card
 *       rows carries primary account numbers and a page of user rows carries identities.
 *       {@link PageResponse#toString()} emits page number, page size and the next-page indicator only,
 *       and this class proves it.</li>
 *   </ul>
 *
 * <h2>5. Deliberately not asserted - "Not available"</h2>
 *
 * <ul>
 *   <li><b>Any database or DDL claim: Not available.</b> No column type, length, index or constraint
 *       is asserted here, because {@link PageResponse} is response metadata that is never persisted:
 *       it has no table, no migration and no repository. Asserting a schema fact from this tier would
 *       require the containerised migration tier, which is a different tier by design. What would be
 *       needed: the integration tier and a live PostgreSQL container.</li>
 *   <li><b>A single copybook defining a pagination record: Not available.</b> No such member exists
 *       anywhere in the corpus. The three origins are program WORKING-STORAGE, cited individually
 *       above, and nothing is invented to stand in for a copybook that was never written.</li>
 *   <li><b>Total element count and total page count: Not available.</b> The type offers neither and
 *       this class asserts neither, because the legacy programs browse a key forwards and backwards
 *       and learn only whether a further record exists; no cardinality is ever computed. What would be
 *       needed: a product decision to add a count, an agreed cost for the extra aggregate on every
 *       page request, and a decision-log entry recording the deviation from parity.</li>
 *   <li><b>The wall-clock zone of the legacy region: Not available.</b> Neither producer stores an
 *       offset. {@link FixedClockProvider#CANONICAL_ZONE} records the chosen zone explicitly rather
 *       than leaving it implicit.</li>
 *   <li><b>Why the sentinel encodings are re-implemented here.</b> The six helpers
 *       {@code moveToAlphanumericField}, {@code toFamilyA}, {@code toFamilyB}, {@code fromFamilyA},
 *       {@code fromFamilyB} and {@code toFamilyBLastPageMarker} re-implement, as test-local oracles,
 *       the {@code 'Y'} / {@code 'N'} family of {@code app/cbl/COTRN00C.cbl:L63-L68} and the inverted
 *       zero-or-nine last-page family of {@code app/cbl/COCRDLIC.cbl:L239-L244}. The production
 *       encodings belong to {@code com.cardemo.service.transaction.TransactionListService} and
 *       {@code com.cardemo.service.card.CardListService}, and are asserted against those services by
 *       {@code TransactionListServiceTest} and {@code CardListServiceTest}, so this file is not the only
 *       record of the contract. Neither service exposes a <em>public encoder</em> to delegate to - the
 *       sentinels are produced inside {@code listCards} and the list-screen methods - so these six helpers
 *       remain test-local constructors of sentinel values for this DTO tier, not a second copy of the
 *       contract. Deleting them would leave these DTO assertions with no way to build an input; re-pointing
 *       them would require widening the production API purely for tests. What would be needed to remove
 *       them: a public
 *       encoder on either service, or moving these assertions up to the service tier that already owns it.
 *       Re-derive with
 *       {@code ls src/main/java/com/cardemo/service/card/CardListService.java} and
 *       {@code ls src/test/java/com/cardemo/unit/service/CardListServiceTest.java}.</li>
 *   </ul>
 */
class PageResponseTest {

    /**
     * Width of {@code PAGENOI PIC X(3)} on the card list screen, {@code app/cpy-bms/COCRDLI.CPY:60}.
     */
    private static final int CARD_LIST_PAGING_FIELD_WIDTH = 3;

    /**
     * Width of {@code PAGENUMI PIC X(8)} on the transaction and user list screens,
     * {@code app/cpy-bms/COTRN00.CPY:60} and {@code app/cpy-bms/COUSR00.CPY:60}. Also the digit count of
     * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COTRN00C.cbl:L65}.
     */
    private static final int WIDE_LIST_PAGING_FIELD_WIDTH = 8;

    /**
     * Largest value {@code PIC 9(08)} can hold, {@code app/cbl/COTRN00C.cbl:L65}.
     */
    private static final int MAX_EIGHT_DIGIT_PAGE_NUMBER = 99_999_999;

    /**
     * Smallest value that overflows {@code PIC 9(08)}; the deliberate one-over case.
     */
    private static final int NINE_DIGIT_PAGE_NUMBER = 100_000_000;

    /**
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'.} - {@code app/cbl/COTRN00C.cbl:L67}.
     */
    private static final char FAMILY_A_NEXT_PAGE_YES = 'Y';

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'.} - {@code app/cbl/COTRN00C.cbl:L68}.
     */
    private static final char FAMILY_A_NEXT_PAGE_NO = 'N';

    /**
     * {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.} - {@code app/cbl/COCRDLIC.cbl:L244}.
     */
    private static final char FAMILY_B_NEXT_PAGE_EXISTS = 'Y';

    /**
     * {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.} - {@code app/cbl/COCRDLIC.cbl:L243}.
     */
    private static final char FAMILY_B_NEXT_PAGE_NOT_EXISTS = '\u0000';

    /**
     * {@code 88 CA-LAST-PAGE-SHOWN VALUE 0.} - {@code app/cbl/COCRDLIC.cbl:L240}.
     */
    private static final int FAMILY_B_LAST_PAGE_SHOWN = 0;

    /**
     * {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9.} - {@code app/cbl/COCRDLIC.cbl:L241}.
     */
    private static final int FAMILY_B_LAST_PAGE_NOT_SHOWN = 9;

    /**
     * {@code 05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20.} - {@code app/cbl/CBTRN03C.cbl:L131}.
     */
    private static final int BATCH_REPORT_LINES_PER_PAGE = 20;

    /**
     * Width of {@code CDEMO-CT00-TRNID-FIRST} and {@code -TRNID-LAST}, {@code app/cbl/COTRN00C.cbl:L63-L64}.
     */
    private static final int TRANSACTION_KEY_WIDTH = 16;

    /**
     * Width of {@code CDEMO-CU00-USRID-FIRST} and {@code -USRID-LAST}, {@code app/cbl/COUSR00C.cbl:L68-L69}.
     */
    private static final int USER_KEY_WIDTH = 8;

    /**
     * Width of {@code WS-CA-LAST-CARDKEY}, {@code app/cbl/COCRDLIC.cbl:L230-L232}: a
     * {@code WS-CA-LAST-CARD-NUM PIC X(16)} followed by a {@code WS-CA-LAST-CARD-ACCT-ID PIC 9(11)}, so 27
     * bytes in total. {@code WS-CA-FIRST-CARDKEY} at {@code :L233-L235} mirrors it.
     */
    private static final int CARD_KEY_WIDTH = 27;

    /**
     * Digits of {@code CDEMO-CARD-NUM PIC 9(16)}, numeric, {@code app/cpy/COCOM01Y.cpy:L41}.
     */
    private static final int COMMAREA_CARD_NUMBER_DIGITS = 16;

    /**
     * Characters of {@code CARD-NUM PIC X(16)}, alphanumeric, {@code app/cpy/CVACT02Y.cpy:L5}.
     */
    private static final int CARD_ENTITY_CARD_NUMBER_WIDTH = 16;

    /**
     * Width of {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}, {@code app/cpy/COCOM01Y.cpy:L43-L44}.
     */
    private static final int COMMAREA_LAST_MAP_WIDTH = 7;

    /**
     * Width of {@code CURTIMEI} on sixteen of the seventeen symbolic maps.
     */
    private static final int COMMON_HEADER_TIME_WIDTH = 8;

    /**
     * Width of {@code CURTIMEI PIC X(9)} on the sign-on map alone, {@code app/cpy-bms/COSGN00.CPY:54}.
     */
    private static final int SIGN_ON_HEADER_TIME_WIDTH = 9;

    /**
     * Elementary field count of {@code app/cpy/COCOM01Y.cpy}, lines 19 to 44. None is a paging field.
     */
    private static final int COMMAREA_ELEMENTARY_FIELD_COUNT = 16;

    /**
     * Digits of {@code WS-CA-SCREEN-NUM PIC 9(1)} at {@code app/cbl/COCRDLIC.cbl:L237}.
     */
    private static final int CARD_LIST_PAGE_NUMBER_DIGITS = 1;

    /**
     * Digits of {@code WS-PAGE-NUM PIC S9(04) COMP} at {@code app/cbl/COUSR00C.cbl:L54}.
     */
    private static final int USER_LIST_WORKING_PAGE_NUMBER_DIGITS = 4;

    /**
     * A deliberately synthetic row payload. It is not a primary account number, not a name and not a
     * credential, so it can be asserted absent from {@link PageResponse#toString()} without a failure message
     * ever carrying real data.
     */
    private static final String ROW_SENTINEL = "ROW-PAYLOAD-MUST-NOT-BE-LOGGED";

    /**
     * A 16-character transaction identifier with leading zeros, shaped like
     * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)} but obviously synthetic. Leading zeros are the point: they are
     * what a numeric type would silently discard.
     */
    private static final String TRANSACTION_KEY_WITH_LEADING_ZEROS = "0000000000000001";

    /**
     * Builds a mutable list of {@code count} distinct synthetic rows, ascending and insertion ordered.
     *
     * <p>Mutable on purpose: it is the input to the defensive copy assertions. Ascending on purpose:
     * {@code app/data/ASCII/cardxref.txt} is already strictly ascending by key, which is the precondition that
     * makes the early exit scan of {@code app/cbl/CBSTM03A.CBL} correct. Values are row markers, never card
     * numbers.
     *
     * @param count how many rows to build; zero yields an empty list
     * @return a new mutable list holding {@code count} rows in ascending insertion order
     */
    private static List<String> rows(final int count) {
        final List<String> built = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            built.add(String.format(Locale.ROOT, "ROW-%03d", index));
        }
        return built;
    }

    /**
     * Renders a page number into a fixed-width alphanumeric screen field the way a COBOL move does.
     *
     * @param digits the already-rendered page number digits
     * @param width the receiving field width
     * @return {@code digits} truncated on the right to at most {@code width} characters
     */
    private static String moveToAlphanumericField(final String digits, final int width) {
        return digits.length() <= width ? digits : digits.substring(0, width);
    }

    /**
     * Encodes this type's next-page indicator into the {@code 'Y'} / {@code 'N'} sentinel family.
     *
     * <p>{@code app/cbl/COTRN00C.cbl:L66-L68}, used by the transaction and user list adapters.
     *
     * @param nextPageAvailable the indicator carried by a {@link PageResponse}
     * @return {@code 'Y'} when a further page exists, {@code 'N'} otherwise
     */
    private static char toFamilyA(final boolean nextPageAvailable) {
        return nextPageAvailable ? FAMILY_A_NEXT_PAGE_YES : FAMILY_A_NEXT_PAGE_NO;
    }

    /**
     * Encodes this type's next-page indicator into the {@code 'Y'} / {@code LOW-VALUES} sentinel family.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L242-L244}, used by the card list adapter.
     *
     * @param nextPageAvailable the indicator carried by a {@link PageResponse}
     * @return {@code 'Y'} when a further page exists, a {@code LOW-VALUES} zero byte otherwise
     */
    private static char toFamilyB(final boolean nextPageAvailable) {
        return nextPageAvailable ? FAMILY_B_NEXT_PAGE_EXISTS : FAMILY_B_NEXT_PAGE_NOT_EXISTS;
    }

    /**
     * Decodes a {@code 'Y'} / {@code 'N'} sentinel back into this type's indicator.
     *
     * @param sentinel the character held by {@code CDEMO-CT00-NEXT-PAGE-FLG}
     * @return {@code true} for {@code 'Y'}, {@code false} for {@code 'N'}
     * @throws IllegalArgumentException when the character belongs to neither condition name, so a foreign
     * sentinel can never be silently read as "no next page"
     */
    private static boolean fromFamilyA(final char sentinel) {
        if (sentinel == FAMILY_A_NEXT_PAGE_YES) {
            return true;
        }
        if (sentinel == FAMILY_A_NEXT_PAGE_NO) {
            return false;
        }
        throw new IllegalArgumentException("not a COTRN00C next-page sentinel: code point " + (int) sentinel);
    }

    /**
     * Decodes a {@code 'Y'} / {@code LOW-VALUES} sentinel back into this type's indicator.
     *
     * @param sentinel the character held by {@code WS-CA-NEXT-PAGE-IND}
     * @return {@code true} for {@code 'Y'}, {@code false} for a {@code LOW-VALUES} zero byte
     * @throws IllegalArgumentException when the character belongs to neither condition name, which is precisely
     * what a {@code 'N'} from the other family would hit
     */
    private static boolean fromFamilyB(final char sentinel) {
        if (sentinel == FAMILY_B_NEXT_PAGE_EXISTS) {
            return true;
        }
        if (sentinel == FAMILY_B_NEXT_PAGE_NOT_EXISTS) {
            return false;
        }
        throw new IllegalArgumentException("not a COCRDLIC next-page sentinel: code point " + (int) sentinel);
    }

    /**
     * Derives the inverted {@code WS-CA-LAST-PAGE-DISPLAYED} marker from the next-page indicator.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L239-L241}. The inversion is the whole point: no further page means the
     * last page <b>is</b> displayed, which the source encodes as zero.
     *
     * @param nextPageAvailable the indicator carried by a {@link PageResponse}
     * @return {@code 0} when this is the last page, {@code 9} when a further page exists
     */
    private static int toFamilyBLastPageMarker(final boolean nextPageAvailable) {
        return nextPageAvailable ? FAMILY_B_LAST_PAGE_NOT_SHOWN : FAMILY_B_LAST_PAGE_SHOWN;
    }

    // Top-level tests. These two are declared on the outer class rather than inside a @Nested group
    // for two reasons. First, they are statements about the type as a whole - where its contract comes
    // from, and that one generic type really does serve all three lists - so no single group owns them.
    // Second, they put this class's real fully qualified name into the Surefire report. Surefire
    // attributes a @Nested test to its group's display name, so with every test nested the report file
    // contained no testcase carrying classname="com.cardemo.unit.model.PageResponseTest" at all, which
    // left "did this class run?" answerable only through display names. These two are reported under
    // the real name. Note that the tests attribute of the enclosing <testsuite> element still reads 0
    // regardless, because Surefire does not roll nested counts up into it; see section 2 of the class
    // documentation for how to confirm collection without being misled by that counter.

    /**
     * Records the provenance of this type positively, and with it the specification defect described in
     * &sect;1.1 of the class documentation.
     */
    @Test
    @DisplayName("pagination provenance is program WORKING-STORAGE, not the COMMAREA copybook")
    void paginationProvenanceIsProgramWorkingStorageNotTheCommareaCopybook() {
        assertThat(CARD_LIST_PAGE_NUMBER_DIGITS)
                .isNotEqualTo(USER_LIST_WORKING_PAGE_NUMBER_DIGITS)
                .isNotEqualTo(WIDE_LIST_PAGING_FIELD_WIDTH);
        assertThat(USER_LIST_WORKING_PAGE_NUMBER_DIGITS).isNotEqualTo(WIDE_LIST_PAGING_FIELD_WIDTH);
        assertThat(List.of(CARD_LIST_PAGE_NUMBER_DIGITS, USER_LIST_WORKING_PAGE_NUMBER_DIGITS,
                WIDE_LIST_PAGING_FIELD_WIDTH)).containsExactly(1, 4, 8).doesNotHaveDuplicates();

        assertThat(COMMAREA_ELEMENTARY_FIELD_COUNT).isEqualTo(16);

        // Every width above is representable by this type, which is the whole reason it carries an int
        // rather than any one program's field.
        assertThat(new PageResponse<>(rows(0), 9, PageResponse.PAGE_SIZE_CARD_LIST, false)
                .getPageNumber()).isEqualTo(9);
        assertThat(new PageResponse<>(rows(0), 9999, PageResponse.PAGE_SIZE_USER_LIST, false)
                .getPageNumber()).isEqualTo(9999);
        assertThat(new PageResponse<>(rows(0), MAX_EIGHT_DIGIT_PAGE_NUMBER,
                PageResponse.PAGE_SIZE_TRANSACTION_LIST, false).getPageNumber())
                .isEqualTo(MAX_EIGHT_DIGIT_PAGE_NUMBER);
    }

    /**
     * One generic type serves all three paged lists at once, each with its own page size, its own boundary key
     * width and its own sentinel family, and without a shared paging abstraction that would have to assume one
     * shape. This is the integrative statement the seven groups below then examine facet by facet.
     */
    @Test
    @DisplayName("one generic type serves all three lists without assuming a single shape")
    void oneGenericTypeServesAllThreeListsWithoutAssumingASingleShape() {
        final PageResponse<String> cardPage = new PageResponse<>(
                rows(PageResponse.PAGE_SIZE_CARD_LIST), 1, PageResponse.PAGE_SIZE_CARD_LIST, true);
        final PageResponse<String> transactionPage = new PageResponse<>(
                rows(PageResponse.PAGE_SIZE_TRANSACTION_LIST), 1,
                PageResponse.PAGE_SIZE_TRANSACTION_LIST, true, TRANSACTION_KEY_WITH_LEADING_ZEROS,
                "0000000000000010");
        final PageResponse<String> userPage = new PageResponse<>(
                rows(PageResponse.PAGE_SIZE_USER_LIST), 1, PageResponse.PAGE_SIZE_USER_LIST, false,
                "STDUSR01", "STDUSR10");

        assertThat(cardPage.getPageSize()).isEqualTo(7);
        assertThat(transactionPage.getPageSize()).isEqualTo(10);
        assertThat(userPage.getPageSize()).isEqualTo(10);

        // Three different boundary key shapes: absent, sixteen characters, eight characters.
        assertThat(cardPage.getFirstKey()).isNull();
        assertThat(transactionPage.getFirstKey()).hasSize(TRANSACTION_KEY_WIDTH);
        assertThat(userPage.getFirstKey()).hasSize(USER_KEY_WIDTH);

        // The card list reads its indicator through the LOW-VALUES family and the other two through
        // the 'N' family, from the same boolean, with neither sentinel reaching the other screen.
        assertThat(toFamilyB(cardPage.isNextPageAvailable())).isEqualTo('Y');
        assertThat(toFamilyA(transactionPage.isNextPageAvailable())).isEqualTo('Y');
        assertThat(toFamilyA(userPage.isNextPageAvailable())).isEqualTo('N');
        assertThat(toFamilyB(userPage.isNextPageAvailable())).isEqualTo('\u0000');
    }

    /**
     * Pins the exact declared surface of the type. A single assertion over the whole member set is stronger
     * than a list of "does not have" checks, because it also fails when something new and unexamined is added.
     */
    @Nested
    @DisplayName("Declared surface")
    class DeclaredSurface {

        @Test
        @DisplayName("declares exactly the six paging metadata fields and nothing else")
        void declaresExactlyTheSixPagingMetadataFields() {
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, false))
                    .containsExactlyInAnyOrder("rows", "pageNumber", "pageSize", "nextPageAvailable",
                            "firstKey", "lastKey");
        }

        @Test
        @DisplayName("declares exactly the six accessors plus equals, hashCode and toString")
        void declaresExactlyTheSixAccessorsPlusValueSemantics() {
            assertThat(ReflectionCensus.declaredMethodNames(PageResponse.class))
                    .containsExactlyInAnyOrder("getRows", "getPageNumber", "getPageSize",
                            "isNextPageAvailable", "getFirstKey", "getLastKey", "equals", "hashCode",
                            "toString");
        }

        @Test
        @DisplayName("declares exactly the three page sizes and the page-number origin as constants")
        void declaresExactlyTheThreePageSizesAndThePageNumberOrigin() {
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, true))
                    .containsExactlyInAnyOrder("PAGE_SIZE_CARD_LIST", "PAGE_SIZE_TRANSACTION_LIST",
                            "PAGE_SIZE_USER_LIST", "FIRST_PAGE_NUMBER");
        }

        /**
         * Transformation rule 7 of the migration plan is "No server-side session state".
         */
        @Test
        @DisplayName("carries no COMMAREA routing or screen-state field")
        void carriesNoCommareaRoutingOrScreenStateField() {
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, false))
                    .doesNotContain("fromTranId", "fromTranid", "toTranId", "toTranid", "fromProgram",
                            "toProgram", "pgmContext", "programContext", "lastMap", "lastMapset",
                            "lastMapSet", "commArea", "commarea");
            assertThat(ReflectionCensus.declaredMethodNames(PageResponse.class))
                    .doesNotContain("getFromTranId", "getToTranId", "getFromProgram", "getToProgram",
                            "getPgmContext", "getLastMap", "getLastMapset", "getCommArea");
        }

        /**
         * {@code CURTIMEI} is {@code PIC X(8)} on sixteen symbolic maps but {@code PIC X(9)} on
         * {@code app/cpy-bms/COSGN00.CPY:54} alone, so a shared common-header helper cannot be correct for all
         * seventeen.
         */
        @Test
        @DisplayName("carries no BMS common-header field, whose widths are not uniform either")
        void carriesNoBmsCommonHeaderField() {
            assertThat(SIGN_ON_HEADER_TIME_WIDTH).isNotEqualTo(COMMON_HEADER_TIME_WIDTH);
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, false))
                    .doesNotContain("trnName", "title01", "title02", "curDate", "curTime", "pgmName");
            assertThat(ReflectionCensus.declaredMethodNames(PageResponse.class))
                    .doesNotContain("getTrnName", "getTitle01", "getTitle02", "getCurDate",
                            "getCurTime", "getPgmName");
        }

        /**
         * The plan maps the VSAM browse verbs onto {@code Pageable} and {@code Slice} queries in the repository
         * tier, which is a different tier with a different test package.
         */
        @Test
        @DisplayName("exposes no Spring Data type anywhere on its surface")
        void exposesNoSpringDataTypeAnywhereOnItsSurface() {
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(PageResponse.class))
                    .isNotEmpty()
                    .allSatisfy(typeName -> assertThat(typeName).doesNotContain("org.springframework"));
        }

        /**
         * A class-level constraint fires unconditionally, whereas the reference pattern at
         * {@code app/cbl/COACTUPC.cbl:1665-1668} runs the cross-field edit only once both single-field edits
         * have passed, with the outcome set at {@code :1674}.
         */
        @Test
        @DisplayName("carries no validation annotation, so no cross-field edit fires unconditionally")
        void carriesNoValidationAnnotation() {
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(PageResponse.class)).isEmpty();
        }

        @Test
        @DisplayName("is final and offers exactly the two documented constructors and no setter")
        void isFinalAndOffersExactlyTheTwoDocumentedConstructors() {
            assertThat(Modifier.isFinal(PageResponse.class.getModifiers())).isTrue();

            int constructorCount = 0;
            for (final Constructor<?> constructor : PageResponse.class.getDeclaredConstructors()) {
                if (!constructor.isSynthetic()) {
                    constructorCount++;
                }
            }
            assertThat(constructorCount).isEqualTo(2);
            assertThat(ReflectionCensus.declaredMethodNames(PageResponse.class))
                    .noneMatch(name -> name.startsWith("set"));
        }
    }

    /**
     * The three list screens agree on neither the name nor the width of their paging field. That is a verified
     * inconsistency in the corpus, not an inference, and it is the reason this type carries the page number as
     * an {@code int} and leaves rendering to each adapter.
     */
    @Nested
    @DisplayName("Paging field width divergence")
    class PagingFieldWidthDivergence {

        /**
         * {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60} against {@code PAGENUMI PIC X(8)} at
         * {@code app/cpy-bms/COTRN00.CPY:60} and {@code app/cpy-bms/COUSR00.CPY:60}.
         */
        @Test
        @DisplayName("the card list field is three characters wide and the other two are eight")
        void theCardListFieldIsThreeCharactersWideAndTheOtherTwoAreEight() {
            assertThat(CARD_LIST_PAGING_FIELD_WIDTH).isEqualTo(3);
            assertThat(WIDE_LIST_PAGING_FIELD_WIDTH).isEqualTo(8);
            assertThat(CARD_LIST_PAGING_FIELD_WIDTH).isNotEqualTo(WIDE_LIST_PAGING_FIELD_WIDTH);
        }

        /**
         * The page number is an {@code int}, so no screen width is baked in. Both a value that only the wide
         * field can hold and the widest value the wide field can hold are accepted unchanged.
         * @throws NoSuchMethodException if {@code getPageNumber} is no longer declared.
         */
        @Test
        @DisplayName("carries the page number as an int, so neither screen width is baked in")
        void carriesThePageNumberAsAnIntSoNeitherScreenWidthIsBakedIn() throws NoSuchMethodException {
            assertThat(PageResponse.class.getDeclaredMethod("getPageNumber").getReturnType())
                    .isEqualTo(int.class);

            final int tooWideForTheCardListField = 1234;
            assertThat(new PageResponse<>(rows(0), tooWideForTheCardListField,
                    PageResponse.PAGE_SIZE_CARD_LIST, false).getPageNumber())
                    .isEqualTo(tooWideForTheCardListField);
            assertThat(new PageResponse<>(rows(0), MAX_EIGHT_DIGIT_PAGE_NUMBER,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, false).getPageNumber())
                    .isEqualTo(MAX_EIGHT_DIGIT_PAGE_NUMBER);
        }

        /**
         * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COTRN00C.cbl:L65} is eight numeric digits, so
         * page 1 reaches the screen as {@code 00000001}. The zero padding is part of the field contract, and
         * {@link Locale#ROOT} keeps the digits locale independent.
         */
        @Test
        @DisplayName("page 1 renders as 00000001 on the eight-digit transaction and user path")
        void pageOneRendersAsEightZeroPaddedDigitsOnTheWidePath() {
            final PageResponse<String> page = new PageResponse<>(rows(0),
                    PageResponse.FIRST_PAGE_NUMBER, PageResponse.PAGE_SIZE_TRANSACTION_LIST, false);

            final String rendered = String.format(Locale.ROOT, "%08d", page.getPageNumber());

            assertThat(rendered).isEqualTo("00000001").hasSize(WIDE_LIST_PAGING_FIELD_WIDTH);
            assertThat(String.format(Locale.ROOT, "%08d", MAX_EIGHT_DIGIT_PAGE_NUMBER))
                    .isEqualTo("99999999")
                    .hasSize(WIDE_LIST_PAGING_FIELD_WIDTH);
        }

        /**
         * A four-character page number cannot survive {@code PAGENOI PIC X(3)}: the alphanumeric move is left
         * justified and truncates on the right. This is exactly why no single width may be assumed, and the
         * truncation belongs to the card list adapter rather than to this type.
         */
        @Test
        @DisplayName("a four-character page number truncates to three on the card list field")
        void aFourCharacterPageNumberTruncatesToThreeOnTheCardListField() {
            final PageResponse<String> page = new PageResponse<>(rows(0), 1234,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            final String digits = String.format(Locale.ROOT, "%d", page.getPageNumber());
            final String rendered = moveToAlphanumericField(digits, CARD_LIST_PAGING_FIELD_WIDTH);

            assertThat(digits).isEqualTo("1234").hasSize(4);
            assertThat(rendered).isEqualTo("123").hasSize(CARD_LIST_PAGING_FIELD_WIDTH);
            assertThat(rendered).isNotEqualTo(digits);
            assertThat(page.getPageNumber()).isEqualTo(1234);
        }

        /**
         * Page 7 fits the narrow field, which shows the truncation above is a width consequence rather than a
         * defect in the helper.
         */
        @Test
        @DisplayName("a single-digit page number survives the three-character card list field")
        void aSingleDigitPageNumberSurvivesTheNarrowCardListField() {
            final PageResponse<String> page = new PageResponse<>(rows(0), 7,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            assertThat(moveToAlphanumericField(String.format(Locale.ROOT, "%03d",
                    page.getPageNumber()), CARD_LIST_PAGING_FIELD_WIDTH))
                    .isEqualTo("007")
                    .hasSize(CARD_LIST_PAGING_FIELD_WIDTH);
        }

        /**
         * {@code CDEMO-CARD-NUM} is numeric {@code PIC 9(16)} at {@code app/cpy/COCOM01Y.cpy:L41} whereas the
         * card entity's {@code CARD-NUM} is alphanumeric {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}:
         * the same sixteen positions with two different representations. Equal widths, unequal types - and
         * neither is this type's business, which is what this test records.
         */
        @Test
        @DisplayName("the COMMAREA card number and the card entity card number diverge in type only")
        void theCommareaCardNumberAndTheCardEntityCardNumberDivergeInTypeOnly() {
            assertThat(COMMAREA_CARD_NUMBER_DIGITS).isEqualTo(CARD_ENTITY_CARD_NUMBER_WIDTH);
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, false))
                    .doesNotContain("cardNum", "cardNumber");
            assertThat(ReflectionCensus.declaredMethodNames(PageResponse.class))
                    .doesNotContain("getCardNum", "getCardNumber");
        }

        /**
         * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} are {@code PIC X(7)} at
         * {@code app/cpy/COCOM01Y.cpy:L43-L44}, not {@code X(8)} as an eye accustomed to program names would
         * assume. They are screen state, so they are absent here; the width is asserted so the correction is on
         * the record.
         */
        @Test
        @DisplayName("the COMMAREA last-map fields are seven characters wide and are absent here")
        void theCommareaLastMapFieldsAreSevenCharactersWideAndAreAbsentHere() {
            assertThat(COMMAREA_LAST_MAP_WIDTH).isEqualTo(7);
            assertThat(COMMAREA_LAST_MAP_WIDTH).isNotEqualTo(WIDE_LIST_PAGING_FIELD_WIDTH);
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, false))
                    .doesNotContain("lastMap", "lastMapset");
        }
    }

    /**
     * The central trap of this DTO.
     */
    @Nested
    @DisplayName("Next-page sentinels")
    class NextPageSentinels {

        /**
         * {@code app/cbl/COTRN00C.cbl:L66-L68}. Round-trip in both directions so the mapping is proven lossless
         * rather than merely one-way.
         */
        @Test
        @DisplayName("family A round-trips losslessly through 'Y' and 'N'")
        void familyARoundTripsLosslesslyThroughYAndN() {
            final PageResponse<String> hasNext = new PageResponse<>(rows(10),
                    PageResponse.FIRST_PAGE_NUMBER, PageResponse.PAGE_SIZE_TRANSACTION_LIST, true);
            final PageResponse<String> lastPage = new PageResponse<>(rows(4), 2,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, false);

            assertThat(toFamilyA(hasNext.isNextPageAvailable())).isEqualTo('Y');
            assertThat(toFamilyA(lastPage.isNextPageAvailable())).isEqualTo('N');
            assertThat(fromFamilyA(toFamilyA(hasNext.isNextPageAvailable()))).isTrue();
            assertThat(fromFamilyA(toFamilyA(lastPage.isNextPageAvailable()))).isFalse();
        }

        /**
         * {@code app/cbl/COCRDLIC.cbl:L242-L244}. The absent-page sentinel is {@code LOW-VALUES}, a zero byte,
         * and specifically not a space - a space would be a third state.
         */
        @Test
        @DisplayName("family B round-trips losslessly through 'Y' and LOW-VALUES")
        void familyBRoundTripsLosslesslyThroughYAndLowValues() {
            final PageResponse<String> hasNext = new PageResponse<>(rows(7),
                    PageResponse.FIRST_PAGE_NUMBER, PageResponse.PAGE_SIZE_CARD_LIST, true);
            final PageResponse<String> lastPage = new PageResponse<>(rows(3), 2,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            assertThat(toFamilyB(hasNext.isNextPageAvailable())).isEqualTo('Y');
            assertThat(toFamilyB(lastPage.isNextPageAvailable())).isEqualTo('\u0000');
            assertThat(toFamilyB(lastPage.isNextPageAvailable())).isNotEqualTo(' ');
            assertThat(fromFamilyB(toFamilyB(hasNext.isNextPageAvailable()))).isTrue();
            assertThat(fromFamilyB(toFamilyB(lastPage.isNextPageAvailable()))).isFalse();
        }

        /**
         * The two families share the {@code 'Y'} affirmative but not the negative, so a single character cannot
         * serve both.
         */
        @Test
        @DisplayName("the two negative sentinels are different bytes and are not interchangeable")
        void theTwoNegativeSentinelsAreDifferentBytesAndAreNotInterchangeable() {
            assertThat(FAMILY_A_NEXT_PAGE_NO).isNotEqualTo(FAMILY_B_NEXT_PAGE_NOT_EXISTS);
            assertThat((int) FAMILY_A_NEXT_PAGE_NO).isEqualTo(78);
            assertThat((int) FAMILY_B_NEXT_PAGE_NOT_EXISTS).isZero();
            assertThat(FAMILY_A_NEXT_PAGE_YES).isEqualTo(FAMILY_B_NEXT_PAGE_EXISTS);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fromFamilyB(FAMILY_A_NEXT_PAGE_NO))
                    .withMessage("not a COCRDLIC next-page sentinel: code point 78")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fromFamilyA(FAMILY_B_NEXT_PAGE_NOT_EXISTS))
                    .withMessage("not a COTRN00C next-page sentinel: code point 0")
                    .withNoCause();
        }

        /**
         * Because this type transports a {@code boolean} rather than a character, the same page can be rendered
         * into either family without either adapter ever seeing the other's sentinel. That is the property that
         * makes one reusable DTO safe across the two conventions.
         * @throws NoSuchMethodException if {@code isNextPageAvailable} is no longer declared.
         */
        @Test
        @DisplayName("one page renders into either family without leaking the other's sentinel")
        void onePageRendersIntoEitherFamilyWithoutLeakingTheOthersSentinel() throws NoSuchMethodException {
            assertThat(PageResponse.class.getDeclaredMethod("isNextPageAvailable").getReturnType())
                    .isEqualTo(boolean.class);

            final PageResponse<String> lastPage = new PageResponse<>(rows(2), 3,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            assertThat(toFamilyA(lastPage.isNextPageAvailable())).isEqualTo('N');
            assertThat(toFamilyB(lastPage.isNextPageAvailable())).isEqualTo('\u0000');
            assertThat(toFamilyA(lastPage.isNextPageAvailable()))
                    .isNotEqualTo(toFamilyB(lastPage.isNextPageAvailable()));
        }

        /**
         * {@code app/cbl/COCRDLIC.cbl:L239-L241}.
         */
        @Test
        @DisplayName("family B encodes 0 as last page SHOWN and 9 as last page NOT shown")
        void familyBEncodesZeroAsLastPageShownAndNineAsNotShown() {
            assertThat(FAMILY_B_LAST_PAGE_SHOWN).isZero();
            assertThat(FAMILY_B_LAST_PAGE_NOT_SHOWN).isEqualTo(9);

            final PageResponse<String> lastPage = new PageResponse<>(rows(3), 4,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);
            final PageResponse<String> hasNext = new PageResponse<>(rows(7), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, true);

            assertThat(toFamilyBLastPageMarker(lastPage.isNextPageAvailable()))
                    .isEqualTo(FAMILY_B_LAST_PAGE_SHOWN);
            assertThat(toFamilyBLastPageMarker(hasNext.isNextPageAvailable()))
                    .isEqualTo(FAMILY_B_LAST_PAGE_NOT_SHOWN);
        }

        /**
         * The marker is a screen-resident scroll flag, so it is session state and stays off the wire. It is
         * fully derivable from the next-page indicator, which is why nothing is lost by omitting it. See
         * {@code app/cbl/COCRDLIC.cbl:L913-L915}, where the program itself derives one from the other.
         */
        @Test
        @DisplayName("the last-page marker is derivable and is therefore not carried")
        void theLastPageMarkerIsDerivableAndIsThereforeNotCarried() {
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, false))
                    .doesNotContain("lastPageDisplayed", "lastPageShown", "screenNum", "screenNumber");
            assertThat(ReflectionCensus.declaredMethodNames(PageResponse.class))
                    .doesNotContain("getLastPageDisplayed", "isLastPageShown", "getScreenNum");
            assertThat(toFamilyBLastPageMarker(false)).isNotEqualTo(toFamilyBLastPageMarker(true));
        }

        /**
         * The indicator is a primitive {@code boolean}, so the "null next-page indicator" boundary is
         * unrepresentable by construction rather than merely rejected at run time. Both states are reachable
         * and distinct.
         */
        @Test
        @DisplayName("the indicator is a primitive boolean, so a null indicator cannot be constructed")
        void theIndicatorIsAPrimitiveBooleanSoANullIndicatorCannotBeConstructed() {
            assertThat(new PageResponse<>(rows(1), 1, PageResponse.PAGE_SIZE_USER_LIST, true)
                    .isNextPageAvailable()).isTrue();
            assertThat(new PageResponse<>(rows(1), 1, PageResponse.PAGE_SIZE_USER_LIST, false)
                    .isNextPageAvailable()).isFalse();
        }
    }

    /**
     * Three page sizes exist in the source.
     */
    @Nested
    @DisplayName("Page sizes")
    class PageSizes {

        /**
         * 7 from {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:L177-L178},
         * corroborated by the seven-row selection array {@code CRDSEL1I} through {@code CRDSEL7I} in
         * {@code app/cpy-bms/COCRDLI.CPY}.
         */
        @Test
        @DisplayName("the card list page size is 7")
        void theCardListPageSizeIsSeven() {
            assertThat(PageResponse.PAGE_SIZE_CARD_LIST).isEqualTo(7);
        }

        /**
         * 10 from the ten-row screen array {@code TRNID01I} through {@code TRNID10I} with {@code TDESC01I}
         * through {@code TDESC10I} in {@code app/cpy-bms/COTRN00.CPY}, whose paging fields are at
         * {@code app/cbl/COTRN00C.cbl:L63-L68}.
         */
        @Test
        @DisplayName("the transaction list page size is 10")
        void theTransactionListPageSizeIsTen() {
            assertThat(PageResponse.PAGE_SIZE_TRANSACTION_LIST).isEqualTo(10);
        }

        /**
         * 10 from {@code 02 USER-REC OCCURS 10 TIMES.} at {@code app/cbl/COUSR00C.cbl:L57}, corroborated by
         * {@code USRID01I} through {@code USRID10I} in {@code app/cpy-bms/COUSR00.CPY}.
         */
        @Test
        @DisplayName("the user list page size is 10")
        void theUserListPageSizeIsTen() {
            assertThat(PageResponse.PAGE_SIZE_USER_LIST).isEqualTo(10);
        }

        /**
         * Asserted as three separate facts that are not folded into one constant.
         */
        @Test
        @DisplayName("the three page sizes stay three separate constants")
        void theThreePageSizesStayThreeSeparateConstants() {
            assertThat(PageResponse.PAGE_SIZE_CARD_LIST)
                    .isNotEqualTo(PageResponse.PAGE_SIZE_TRANSACTION_LIST)
                    .isNotEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
            assertThat(PageResponse.PAGE_SIZE_TRANSACTION_LIST)
                    .isEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
            assertThat(ReflectionCensus.declaredFieldNames(PageResponse.class, true).stream()
                    .filter(name -> name.startsWith("PAGE_SIZE_"))
                    .toList())
                    .containsExactlyInAnyOrder("PAGE_SIZE_CARD_LIST", "PAGE_SIZE_TRANSACTION_LIST",
                            "PAGE_SIZE_USER_LIST");
        }

        /**
         * {@code 05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20.} at {@code app/cbl/CBTRN03C.cbl:L131} is a printed
         * report line count for the batch transaction report, not an online page size.
         */
        @Test
        @DisplayName("the batch report's 20 lines per page never appears as a page size")
        void theBatchReportTwentyLinesPerPageNeverAppearsAsAPageSize() {
            assertThat(BATCH_REPORT_LINES_PER_PAGE).isEqualTo(20);
            assertThat(BATCH_REPORT_LINES_PER_PAGE)
                    .isNotEqualTo(PageResponse.PAGE_SIZE_CARD_LIST)
                    .isNotEqualTo(PageResponse.PAGE_SIZE_TRANSACTION_LIST)
                    .isNotEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
            assertThat(List.of(PageResponse.PAGE_SIZE_CARD_LIST,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, PageResponse.PAGE_SIZE_USER_LIST))
                    .doesNotContain(BATCH_REPORT_LINES_PER_PAGE);
        }

        /**
         * A final page legitimately holds fewer rows than the size applied, which is why the size is carried
         * explicitly instead of being inferred from the row count.
         */
        @Test
        @DisplayName("a partially filled final page keeps the applied page size")
        void aPartiallyFilledFinalPageKeepsTheAppliedPageSize() {
            final PageResponse<String> page = new PageResponse<>(rows(3), 8,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            assertThat(page.getRows()).hasSize(3);
            assertThat(page.getPageSize()).isEqualTo(PageResponse.PAGE_SIZE_CARD_LIST);
            assertThat(page.getRows().size()).isLessThan(page.getPageSize());
            assertThat(page.isNextPageAvailable()).isFalse();
        }

        /**
         * An empty page is a valid, meaningful state - a list with no matching rows, or a page beyond the end
         * of the data - and is distinguishable from both a partial and a full page.
         */
        @Test
        @DisplayName("an empty page is valid and is distinguishable from a partial page")
        void anEmptyPageIsValidAndIsDistinguishableFromAPartialPage() {
            final PageResponse<String> empty = new PageResponse<>(rows(0),
                    PageResponse.FIRST_PAGE_NUMBER, PageResponse.PAGE_SIZE_USER_LIST, false);
            final PageResponse<String> partial = new PageResponse<>(rows(4),
                    PageResponse.FIRST_PAGE_NUMBER, PageResponse.PAGE_SIZE_USER_LIST, false);

            assertThat(empty.getRows()).isEmpty();
            assertThat(empty.getPageSize()).isEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
            assertThat(empty).isNotEqualTo(partial);
        }

        /**
         * An exactly full page is accepted at each of the three sizes, and is distinguishable from a partial
         * page of the same size.
         */
        @Test
        @DisplayName("an exactly full page is accepted at each of the three page sizes")
        void anExactlyFullPageIsAcceptedAtEachOfTheThreePageSizes() {
            final PageResponse<String> fullCards = new PageResponse<>(
                    rows(PageResponse.PAGE_SIZE_CARD_LIST), 1, PageResponse.PAGE_SIZE_CARD_LIST, true);
            final PageResponse<String> fullTransactions = new PageResponse<>(
                    rows(PageResponse.PAGE_SIZE_TRANSACTION_LIST), 1,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, true);
            final PageResponse<String> fullUsers = new PageResponse<>(
                    rows(PageResponse.PAGE_SIZE_USER_LIST), 1, PageResponse.PAGE_SIZE_USER_LIST, true);

            assertThat(fullCards.getRows()).hasSize(PageResponse.PAGE_SIZE_CARD_LIST);
            assertThat(fullCards.getRows()).hasSameSizeAs(rows(fullCards.getPageSize()));
            assertThat(fullTransactions.getRows()).hasSize(PageResponse.PAGE_SIZE_TRANSACTION_LIST);
            assertThat(fullUsers.getRows()).hasSize(PageResponse.PAGE_SIZE_USER_LIST);
            assertThat(fullCards.getRows().size()).isEqualTo(fullCards.getPageSize());
        }

        /**
         * A page can never hold more rows than the size applied to it. The message names both figures so a
         * caller can see which side is wrong.
         */
        @Test
        @DisplayName("a page holding more rows than its page size is rejected")
        void aPageHoldingMoreRowsThanItsPageSizeIsRejected() {
            final List<String> tooMany = rows(PageResponse.PAGE_SIZE_CARD_LIST + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(tooMany, 1,
                            PageResponse.PAGE_SIZE_CARD_LIST, false))
                    .withMessage("rows holds 8 elements, which exceeds pageSize 7; "
                            + "a page cannot hold more rows than its size")
                    .withNoCause();
        }
    }

    /**
     * The legacy programs page by browsing a key, not by offset, and the boundary key widths differ per list:
     * {@code PIC X(16)} for transactions, {@code PIC X(08)} for users, and a 27-byte composite for cards. An
     * opaque {@code String} is the only representation that holds all three without loss.
     */
    @Nested
    @DisplayName("Keyset boundaries")
    class KeysetBoundaries {

        /**
         * A numeric type would discard the leading zeros of {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}, so the
         * accessors must return {@code String}.
         * @throws NoSuchMethodException if either boundary-key accessor is no longer declared.
         */
        @Test
        @DisplayName("boundary keys are opaque Strings, never a numeric type")
        void boundaryKeysAreOpaqueStringsNeverANumericType() throws NoSuchMethodException {
            assertThat(PageResponse.class.getDeclaredMethod("getFirstKey").getReturnType())
                    .isEqualTo(String.class);
            assertThat(PageResponse.class.getDeclaredMethod("getLastKey").getReturnType())
                    .isEqualTo(String.class);
        }

        /**
         * The sixteen characters survive byte for byte, leading zeros included. The companion assertion shows
         * what a numeric round trip would have cost, which is why the type is a {@code String}.
         */
        @Test
        @DisplayName("a 16-character transaction key keeps its leading zeros")
        void aSixteenCharacterTransactionKeyKeepsItsLeadingZeros() {
            final PageResponse<String> page = new PageResponse<>(rows(10), 1,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, true,
                    TRANSACTION_KEY_WITH_LEADING_ZEROS, "0000000000000010");

            assertThat(page.getFirstKey())
                    .isEqualTo(TRANSACTION_KEY_WITH_LEADING_ZEROS)
                    .hasSize(TRANSACTION_KEY_WIDTH)
                    .startsWith("0");
            assertThat(page.getLastKey()).isEqualTo("0000000000000010").hasSize(TRANSACTION_KEY_WIDTH);
            assertThat(String.valueOf(Long.parseLong(TRANSACTION_KEY_WITH_LEADING_ZEROS)))
                    .isEqualTo("1")
                    .isNotEqualTo(page.getFirstKey());
        }

        /**
         * {@code CDEMO-CU00-USRID-FIRST PIC X(08)} at {@code app/cbl/COUSR00C.cbl:L68} is eight characters, not
         * sixteen, so the boundary width is per list. Nothing pads it here.
         */
        @Test
        @DisplayName("an 8-character user key survives unchanged and unpadded")
        void anEightCharacterUserKeySurvivesUnchangedAndUnpadded() {
            final PageResponse<String> page = new PageResponse<>(rows(10), 1,
                    PageResponse.PAGE_SIZE_USER_LIST, true, "STDUSR01", "STDUSR10");

            assertThat(page.getFirstKey()).isEqualTo("STDUSR01").hasSize(USER_KEY_WIDTH);
            assertThat(page.getLastKey()).isEqualTo("STDUSR10").hasSize(USER_KEY_WIDTH);
            assertThat(USER_KEY_WIDTH).isNotEqualTo(TRANSACTION_KEY_WIDTH);
        }

        /**
         * {@code WS-CA-LAST-CARDKEY} at {@code app/cbl/COCRDLIC.cbl:L230-L232} is a {@code PIC X(16)} card
         * number followed by a {@code PIC 9(11)} account identifier, so 27 bytes. The composite survives as one
         * opaque value; this type never parses it into parts. The digits used here are a synthetic filler, not
         * a card number.
         */
        @Test
        @DisplayName("a 27-character composite card key survives as one opaque value")
        void aTwentySevenCharacterCompositeCardKeySurvivesAsOneOpaqueValue() {
            final String compositeKey = "0".repeat(TRANSACTION_KEY_WIDTH) + "00000000001";
            final PageResponse<String> page = new PageResponse<>(rows(7), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, true, compositeKey, compositeKey);

            assertThat(page.getFirstKey()).isEqualTo(compositeKey).hasSize(CARD_KEY_WIDTH);
            assertThat(page.getLastKey()).isEqualTo(compositeKey).hasSize(CARD_KEY_WIDTH);
            assertThat(CARD_KEY_WIDTH).isEqualTo(TRANSACTION_KEY_WIDTH + 11);
        }

        /**
         * The card list path pages by number in its simplest form, so the absence of a boundary key must be
         * representable. The four-argument constructor exists for exactly that case.
         */
        @Test
        @DisplayName("absent boundary keys are representable through the short constructor")
        void absentBoundaryKeysAreRepresentableThroughTheShortConstructor() {
            final PageResponse<String> page = new PageResponse<>(rows(7), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, true);

            assertThat(page.getFirstKey()).isNull();
            assertThat(page.getLastKey()).isNull();
            assertThat(page).isEqualTo(new PageResponse<>(rows(7), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, true, null, null));
        }

        /**
         * {@code app/cpy/CSSETATY.cpy} models OK, NOT-OK and BLANK as three separate states, and
         * {@code app/cbl/COACTUPC.cbl:L304-L306} implements that literally with {@code VALUE LOW-VALUES},
         * {@code VALUE '0'} and {@code VALUE 'B'} - so {@code LOW-VALUES} is a live sentinel here rather than
         * an abstraction.
         */
        @Test
        @DisplayName("absent, blank and LOW-VALUES are three distinct boundary key states")
        void absentBlankAndLowValuesAreThreeDistinctBoundaryKeyStates() {
            final PageResponse<String> absent = new PageResponse<>(rows(0), 1, 7, false, null, null);
            final PageResponse<String> blank = new PageResponse<>(rows(0), 1, 7, false, "", "");
            final PageResponse<String> spaces = new PageResponse<>(rows(0), 1, 7, false, " ", " ");
            final PageResponse<String> lowValues = new PageResponse<>(rows(0), 1, 7, false,
                    String.valueOf(FAMILY_B_NEXT_PAGE_NOT_EXISTS),
                    String.valueOf(FAMILY_B_NEXT_PAGE_NOT_EXISTS));

            assertThat(absent.getFirstKey()).isNull();
            assertThat(blank.getFirstKey()).isEmpty();
            assertThat(spaces.getFirstKey()).isEqualTo(" ").isNotEmpty();
            assertThat(lowValues.getFirstKey()).hasSize(1).isNotEqualTo(" ").isNotEqualTo("");

            assertThat(absent).isNotEqualTo(blank).isNotEqualTo(spaces).isNotEqualTo(lowValues);
            assertThat(blank).isNotEqualTo(spaces).isNotEqualTo(lowValues);
            assertThat(spaces).isNotEqualTo(lowValues);
        }
    }

    /**
     * This is a response type, so its rows are read-only once assembled.
     */
    @Nested
    @DisplayName("Immutability and ordering")
    class ImmutabilityAndOrdering {

        @Test
        @DisplayName("the row collection is unmodifiable")
        void theRowCollectionIsUnmodifiable() {
            final PageResponse<String> page = new PageResponse<>(rows(7), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);
            final List<String> exposed = page.getRows();

            assertThat(exposed).isUnmodifiable();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.add(ROW_SENTINEL));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.set(0, ROW_SENTINEL));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(exposed::clear);
        }

        /**
         * The iterator is checked separately because a collection can be unmodifiable through its own methods
         * while still leaking mutation through its iterator.
         */
        @Test
        @DisplayName("the row iterator cannot remove")
        void theRowIteratorCannotRemove() {
            final PageResponse<String> page = new PageResponse<>(rows(3), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);
            final Iterator<String> iterator = page.getRows().iterator();

            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.next()).isEqualTo("ROW-001");
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(iterator::remove);
            assertThat(page.getRows()).hasSize(3);
        }

        /**
         * The list handed to the constructor is copied, so a caller that keeps and mutates its own list cannot
         * reach inside a page it already handed out.
         */
        @Test
        @DisplayName("mutating the supplied list after construction does not affect the page")
        void mutatingTheSuppliedListAfterConstructionDoesNotAffectThePage() {
            final List<String> supplied = rows(2);
            final PageResponse<String> page = new PageResponse<>(supplied, 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            supplied.add(ROW_SENTINEL);
            supplied.set(0, ROW_SENTINEL);

            assertThat(page.getRows()).containsExactly("ROW-001", "ROW-002");
            assertThat(page.getRows()).doesNotContain(ROW_SENTINEL);
            assertThat(supplied).hasSize(3);
        }

        /**
         * Ascending input arrives ascending, exactly as the frozen cross-reference fixture does. The assertion
         * is on the exact sequence, not on membership, because a set-style assertion would pass on a
         * reordering.
         */
        @Test
        @DisplayName("ascending rows keep their ascending order")
        void ascendingRowsKeepTheirAscendingOrder() {
            final PageResponse<String> page = new PageResponse<>(rows(7), 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, true);

            assertThat(page.getRows()).containsExactly("ROW-001", "ROW-002", "ROW-003", "ROW-004",
                    "ROW-005", "ROW-006", "ROW-007");
            assertThat(page.getRows()).isSorted();
        }

        /**
         * Deliberately unsorted input is reported unchanged. The DTO reports position; it does not page, sort
         * or normalise, and no hash-ordered collection is involved, so iteration order cannot vary between runs
         * or JVMs.
         */
        @Test
        @DisplayName("unsorted rows are reported in the order supplied, never reordered")
        void unsortedRowsAreReportedInTheOrderSuppliedNeverReordered() {
            final List<String> unsorted = new ArrayList<>();
            unsorted.add("ROW-003");
            unsorted.add("ROW-001");
            unsorted.add("ROW-002");

            final PageResponse<String> page = new PageResponse<>(unsorted, 1,
                    PageResponse.PAGE_SIZE_CARD_LIST, false);

            assertThat(page.getRows()).containsExactly("ROW-003", "ROW-001", "ROW-002");
            assertThat(page.getRows().get(0)).isGreaterThan(page.getRows().get(1));
            assertThat(page.getRows()).isEqualTo(unsorted);
        }

        /**
         * Equality is order sensitive, which is the observable consequence of holding rows in a list. Two pages
         * carrying the same rows in a different order are different pages.
         */
        @Test
        @DisplayName("equality is order sensitive")
        void equalityIsOrderSensitive() {
            final List<String> ascending = new ArrayList<>();
            ascending.add("ROW-001");
            ascending.add("ROW-002");
            final List<String> descending = new ArrayList<>();
            descending.add("ROW-002");
            descending.add("ROW-001");

            final PageResponse<String> first = new PageResponse<>(ascending, 1, 7, false);
            final PageResponse<String> second = new PageResponse<>(descending, 1, 7, false);

            assertThat(first).isNotEqualTo(second);
            assertThat(first).isEqualTo(new PageResponse<>(ascending, 1, 7, false));
            assertThat(first).hasSameHashCodeAs(new PageResponse<>(ascending, 1, 7, false));
        }

        /**
         * The hash code is stable across invocations and across equal instances, so nothing here depends on a
         * hash iteration order or on identity.
         */
        @Test
        @DisplayName("the hash code is stable and value based")
        void theHashCodeIsStableAndValueBased() {
            final PageResponse<String> page = new PageResponse<>(rows(2), 3, 10, true, "AAAAAAAA",
                    "BBBBBBBB");

            assertThat(page.hashCode()).isEqualTo(page.hashCode());
            assertThat(page).isEqualTo(page);
            assertThat(page).isNotEqualTo(null);
            assertThat(page).isNotEqualTo("not a page");
            assertThat(new PageResponse<>(rows(2), 3, 10, true, "AAAAAAAA", "BBBBBBBB"))
                    .isEqualTo(page)
                    .hasSameHashCodeAs(page);
        }
    }

    /**
     * Every input boundary the source exposes, asserted explicitly: page zero, page one, the widest eight-digit
     * page number, the one-over nine-digit case, a negative page number, a page size below one, a null row list
     * and a null row element. Each rejection is asserted on its exact message and on the absence of a cause, so
     * nothing is swallowed and no context is lost.
     */
    @Nested
    @DisplayName("Boundaries and determinism")
    class BoundariesAndDeterminism {

        /**
         * {@code 88 CA-FIRST-PAGE VALUE 1} at {@code app/cbl/COCRDLIC.cbl:L238} makes numbering one-based, so
         * page one is the floor and page zero is not a page.
         */
        @Test
        @DisplayName("page numbering is one-based, so page 1 is accepted")
        void pageNumberingIsOneBasedSoPageOneIsAccepted() {
            assertThat(PageResponse.FIRST_PAGE_NUMBER).isEqualTo(1);
            assertThat(new PageResponse<>(rows(1), PageResponse.FIRST_PAGE_NUMBER, 7, false)
                    .getPageNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("page number 0 is rejected with a message naming the field")
        void pageNumberZeroIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(rows(0), 0, 7, false))
                    .withMessage("pageNumber must be at least 1 because page numbering is one-based, "
                            + "but was 0")
                    .withNoCause();
        }

        @Test
        @DisplayName("a negative page number is rejected and the message reports the value received")
        void aNegativePageNumberIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(rows(0), -1, 7, false))
                    .withMessage("pageNumber must be at least 1 because page numbering is one-based, "
                            + "but was -1")
                    .withNoCause();
        }

        /**
         * The widest value {@code PIC 9(08)} holds is accepted and still renders into eight digits.
         */
        @Test
        @DisplayName("the maximum eight-digit page number is accepted")
        void theMaximumEightDigitPageNumberIsAccepted() {
            final PageResponse<String> page = new PageResponse<>(rows(0),
                    MAX_EIGHT_DIGIT_PAGE_NUMBER, PageResponse.PAGE_SIZE_TRANSACTION_LIST, false);

            assertThat(page.getPageNumber()).isEqualTo(MAX_EIGHT_DIGIT_PAGE_NUMBER);
            assertThat(String.format(Locale.ROOT, "%d", page.getPageNumber()))
                    .hasSize(WIDE_LIST_PAGING_FIELD_WIDTH);
        }

        /**
         * The one-over case, asserted as two separate facts rather than one. This type imposes no screen width,
         * so it accepts a nine-digit page number; the screen field cannot hold it, which is the adapter's
         * problem and is why the width is not baked in here.
         */
        @Test
        @DisplayName("a nine-digit page number is accepted here yet overflows the eight-digit field")
        void aNineDigitPageNumberIsAcceptedHereYetOverflowsTheEightDigitField() {
            final PageResponse<String> page = new PageResponse<>(rows(0), NINE_DIGIT_PAGE_NUMBER,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, false);

            assertThat(page.getPageNumber()).isEqualTo(NINE_DIGIT_PAGE_NUMBER);
            assertThat(String.format(Locale.ROOT, "%d", page.getPageNumber()))
                    .hasSize(9)
                    .isEqualTo("100000000");
            assertThat(NINE_DIGIT_PAGE_NUMBER).isGreaterThan(MAX_EIGHT_DIGIT_PAGE_NUMBER);
        }

        @Test
        @DisplayName("a page size below one is rejected")
        void aPageSizeBelowOneIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(rows(0), 1, 0, false))
                    .withMessage("pageSize must be at least 1, but was 0")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(rows(0), 1, -7, false))
                    .withMessage("pageSize must be at least 1, but was -7")
                    .withNoCause();
        }

        /**
         * A null row list is rejected rather than coerced to empty, because an empty page is a distinct and
         * meaningful state and silently conflating the two would hide a caller defect.
         */
        @Test
        @DisplayName("a null row list is rejected rather than coerced to empty")
        void aNullRowListIsRejectedRatherThanCoercedToEmpty() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<String>(null, 1, 7, false))
                    .withMessage("rows must not be null; supply an empty list to represent a page "
                            + "with no rows")
                    .withNoCause();
        }

        /**
         * A null element is rejected and the message names its index, so the caller can find it without a
         * debugger.
         */
        @Test
        @DisplayName("a null row element is rejected and the message names its index")
        void aNullRowElementIsRejectedAndTheMessageNamesItsIndex() {
            final List<String> withNullAtIndexOne = new ArrayList<>();
            withNullAtIndexOne.add("ROW-001");
            withNullAtIndexOne.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(withNullAtIndexOne, 1, 7, false))
                    .withMessage("rows must not contain a null element, but index 1 was null")
                    .withNoCause();

            final List<String> withNullAtIndexZero = new ArrayList<>();
            withNullAtIndexZero.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PageResponse<>(withNullAtIndexZero, 1, 7, false))
                    .withMessage("rows must not contain a null element, but index 0 was null")
                    .withNoCause();
        }

        /**
         * The diagnostic rendering carries page number, page size and the next-page indicator only.
         */
        @Test
        @DisplayName("toString omits every row payload and both boundary keys")
        void toStringOmitsEveryRowPayloadAndBothBoundaryKeys() {
            final List<String> sensitiveRows = new ArrayList<>();
            sensitiveRows.add(ROW_SENTINEL);

            final PageResponse<String> page = new PageResponse<>(sensitiveRows, 3,
                    PageResponse.PAGE_SIZE_CARD_LIST, true, TRANSACTION_KEY_WITH_LEADING_ZEROS,
                    TRANSACTION_KEY_WITH_LEADING_ZEROS);

            assertThat(page.toString())
                    .isEqualTo("PageResponse[pageNumber=3, pageSize=7, nextPageAvailable=true]")
                    .doesNotContain(ROW_SENTINEL)
                    .doesNotContain(TRANSACTION_KEY_WITH_LEADING_ZEROS)
                    .doesNotContain("firstKey")
                    .doesNotContain("lastKey")
                    .doesNotContain("rows");
        }

        /**
         * The rendering is built with {@link Locale#ROOT}, so it is identical on every host and the false state
         * prints as {@code false} rather than a localised word.
         */
        @Test
        @DisplayName("toString is locale independent for both indicator states")
        void toStringIsLocaleIndependentForBothIndicatorStates() {
            final PageResponse<String> lastPage = new PageResponse<>(rows(4), 12,
                    PageResponse.PAGE_SIZE_USER_LIST, false);

            assertThat(lastPage.toString())
                    .isEqualTo("PageResponse[pageNumber=12, pageSize=10, nextPageAvailable=false]");
            assertThat(lastPage.toString())
                    .isEqualTo(String.format(Locale.ROOT,
                            "PageResponse[pageNumber=%d, pageSize=%d, nextPageAvailable=%b]",
                            lastPage.getPageNumber(), lastPage.getPageSize(),
                            lastPage.isNextPageAvailable()));
        }

        /**
         * A page of transaction rows carries the legacy 26-character timestamps, and the only sanctioned source
         * of those is {@link FixedClockProvider}: the ambient clock is never read, so this assertion cannot
         * become flaky on a second, day or month boundary. The page passes the rendered text through untouched
         * - it neither parses nor reformats a row - and the text never reaches the diagnostic rendering.
         */
        @Test
        @DisplayName("rows carrying fixed-clock timestamps pass through untouched and unlogged")
        void rowsCarryingFixedClockTimestampsPassThroughUntouchedAndUnlogged() {
            final Clock clock = FixedClockProvider.canonicalClock();
            final String onlineTimestamp = FixedClockProvider.onlineTimestamp(clock);
            final String batchTimestamp = FixedClockProvider.batchTimestamp(clock);

            final List<String> timestampRows = new ArrayList<>();
            timestampRows.add(onlineTimestamp);
            timestampRows.add(batchTimestamp);

            final PageResponse<String> page = new PageResponse<>(timestampRows, 1,
                    PageResponse.PAGE_SIZE_TRANSACTION_LIST, false);

            assertThat(onlineTimestamp)
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(batchTimestamp).hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(onlineTimestamp).isNotEqualTo(batchTimestamp);
            assertThat(page.getRows()).containsExactly(onlineTimestamp, batchTimestamp);
            assertThat(page.toString())
                    .doesNotContain(onlineTimestamp)
                    .doesNotContain(batchTimestamp);
        }
    }
}
