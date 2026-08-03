/*
 * ******************************************************************
 * Program     : ValidationLookupService.java
 * Application : CardDemo
 * Type        : Spring @Service (shared, lookup-table validation)
 * Function    : Replaces the CSLKPCDY 88-level lookup tables with classpath JSON resources
 * Source      : app/cpy/CSLKPCDY.cpy (1318 lines, 0 paragraphs - data only) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L602, L2296-L2298, L2493-L2495, L2535-L2542 @ 7756d89 - the
 *               only program in the corpus that COPYs CSLKPCDY, and its three call sites
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
package com.cardemo.service.shared;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.SequencedSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * The CardDemo lookup-table validation service: the one bean that replaces the five {@code 88}-level
 * condition names of the frozen COBOL copybook {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>{@code app/cpy/CSLKPCDY.cpy} is 1318 lines long and contains no {@code PROCEDURE DIVISION} code at
 * all - it is three {@code 01} level data items carrying five {@code 88} level condition names, and
 * nothing else. Evaluating one of those condition names was how the legacy program asked "is this value
 * in the list?". This service answers exactly that question, and only that question: a membership test
 * against the corresponding table. It performs no formatting, no persistence, no message assembly and
 * no business rule, because each of those belongs to the caller.
 *
 * <p>This is the <strong>second collapse rule</strong> of the migration. Five condition names spread
 * over 1318 source lines collapse into one injectable bean over three classpath JSON resources. The
 * five tables remain five independent tables; the collapse is of the <em>mechanism</em>, never of the
 * membership.
 *
 * <p><strong>Why the data is a resource and not a generated constants class.</strong> Transcribing 1276 literals into
 * Java constants would add well over a thousand lines of source that no compiler check makes safer, would put
 * reference data inside a compilation unit, and would make the element counts hard to audit against the copybook.
 * Rule 1 Clause C forbids that duplication and Clause A asks for minimal complexity; Clause A's performance clause is
 * satisfied either way, because membership is an immutable hash-set probe in both designs and the tables are read
 * once at startup. Externalising the data is therefore the justified tradeoff, and it is owed an entry in the planned
 * {@code DECISION_LOG.md}. Generating a constants class instead is a <strong>High</strong> severity defect.
 *
 * <h3>1.1 The source structure, verbatim</h3>
 *
 * <p>Three {@code 01} items, two {@code 02} items and five {@code 88} condition names, at the locators
 * this file's author re-derived from the copybook rather than inherited:
 *
 * <pre>
 * 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.                  &lt;- L24
 *    88 VALID-PHONE-AREA-CODE      VALUES '201', ...          &lt;- L30    ends "'999'." at L520
 *    88 VALID-GENERAL-PURP-CODE    VALUES '201', ...          &lt;- L521   ends "'989'." at L930
 *    88 VALID-EASY-RECOG-AREA-CODE VALUES  '200', ...         &lt;- L931   ends "'999'." at L1010
 * 01 US-STATE-CODE-TO-EDIT  PIC X(2).                        &lt;- L1012
 *    88 VALID-US-STATE-CODE VALUES ...                        &lt;- L1013  ends "'VI'." at L1069
 * 01 US-STATE-ZIPCODE-TO-EDIT.                               &lt;- L1071
 *    02 US-STATE-AND-FIRST-ZIP2 PIC X(4).                     &lt;- L1072
 *       88 VALID-US-STATE-ZIP-CD2-COMBO VALUES ...            &lt;- L1073  ends "'WY83'." at L1313
 *    02 LAST-3-OF-ZIP           PIC X(3).                     &lt;- L1314  never validated
 * </pre>
 *
 * <h3>1.2 The three real call sites</h3>
 *
 * <p>Exactly one program copies this copybook - {@code app/cbl/COACTUPC.cbl:L602} - and it evaluates
 * three of the five condition names, all inside the customer telephone and address edits:
 *
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl:L2296-L2298} moves the trimmed area code into
 *       {@code WS-US-PHONE-AREA-CODE-TO-EDIT} and tests {@code VALID-GENERAL-PURP-CODE} - the
 *       <em>general purpose</em> table, not the full one. The preceding edits at
 *       {@code app/cbl/COACTUPC.cbl:L2266-L2294} have already required three digits and rejected zero,
 *       so the condition name is only ever reached with a well formed three character value. That is
 *       why this service treats the declared width as a precondition and leaves digit checking to the
 *       caller: see section 3.4.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:L2493-L2495}, paragraph {@code 1270-EDIT-US-STATE-CD}, moves
 *       {@code ACUP-NEW-CUST-ADDR-STATE-CD} into {@code US-STATE-CODE-TO-EDIT} and tests
 *       {@code VALID-US-STATE-CODE}.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl:L2535-L2542}, paragraph {@code 1280-EDIT-US-STATE-ZIP-CD}, whose
 *       preceding comment reads {@code *A crude zip code edit based on data from USPS web site},
 *       concatenates {@code ACUP-NEW-CUST-ADDR-STATE-CD} with {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} into
 *       {@code US-STATE-AND-FIRST-ZIP2} and tests {@code VALID-US-STATE-ZIP-CD2-COMBO}. That
 *       concatenation is reproduced by {@link #isValidStateAndZipCode(String, String)}.</li>
 *   </ul>
 *
 * <p>{@code VALID-PHONE-AREA-CODE} and {@code VALID-EASY-RECOG-AREA-CODE} are declared but evaluated
 * nowhere in the corpus. They are still modelled, because the source declares them as independent
 * condition names and a table that exists in the field contract must exist here; dropping either, or
 * deriving one from the other two, is a <strong>Medium</strong> severity defect.
 *
 * <h3>1.3 The failure literals belong to the caller</h3>
 *
 * <p>When a condition name is false the legacy program sets {@code INPUT-ERROR}, sets a per field flag
 * and assembles a screen message - {@code ': Not valid North America general purpose area code'},
 * {@code ': is not a valid state code'}, {@code 'Invalid zip code for state'}. Those literals, the
 * flags and the cursor behaviour are owned by the service that replaces {@code COACTUPC}, not by this
 * one. Reproducing them here would be message formatting inside a lookup bean, which Rule 1 Clause A
 * rules out on separation of concerns and Clause C rules out as duplication. This service returns a
 * boolean; the caller decides what the boolean means to a user.
 *
 * <h2>2. How to build and test it</h2>
 *
 * <p>Java 25 with {@code maven.compiler.release} 25 and no preview features, Apache Maven 3.9.11, and
 * the Spring Boot 3.5.11 parent. The compiler runs {@code -Xlint:all -Werror} with
 * {@code failOnWarning}, so a raw type, an unchecked cast or a deprecated call is a build failure rather
 * than a warning - which is why the resource binding below is explicitly typed throughout. An unused
 * import is forbidden by Rule 1 Clause B but is not mechanically detected: {@code javac} 25.0.3 publishes
 * no lint key for one. Build with {@code ./mvnw -B clean compile}, run the unit tier with
 * {@code ./mvnw -B clean test}
 * and the full gate with {@code ./mvnw -B clean verify}, where JaCoCo enforces an 80 percent line floor
 * with no exclusion for this class.
 *
 * <p>Tests for this service live under {@code src/test/java/com/cardemo/unit/} and nowhere else. The
 * assertions that matter are the five element counts, the disjointness of the two area-code subsets,
 * the concatenation identity between them and the full table, the preserved source ordering of the
 * state table, the first and last members of the state-and-zip table, and the fail-fast behaviour on a
 * missing resource. Those are the checks that catch a truncated or re-ordered resource, which no
 * compiler check can catch.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <h3>3.1 There is no configuration</h3>
 *
 * <p>Deliberately none. No property, no profile, no environment variable and no system property alters
 * which resources are read or what they contain: the three classpath locations are compile-time
 * constants of this class. Rule 1 Clause C asks for determinism and freedom from environment-specific
 * assumptions, and the strongest way to satisfy that is to have nothing to configure. This class reads no
 * environment variable and no system property, resolves no filesystem path, and opens no network
 * connection of any kind.
 *
 * <h3>3.2 The three resources and the five tables they carry</h3>
 *
 * <ul>
 *   <li>{@code validation/nanpa-area-codes.json} carries {@code VALID-PHONE-AREA-CODE} declared at
 *       {@code app/cpy/CSLKPCDY.cpy:L30} with <strong>490</strong> members,
 *       {@code VALID-GENERAL-PURP-CODE} at {@code :L521} with <strong>410</strong>, and
 *       {@code VALID-EASY-RECOG-AREA-CODE} at {@code :L931} with <strong>80</strong>. Every member is
 *       three characters, from {@code PIC XXX} - which is the copybook's own spelling of
 *       {@code PIC X(3)} and is quoted verbatim throughout this file rather than normalised, so that
 *       every citation matches the frozen source character for character.</li>
 *   <li>{@code validation/us-state-codes.json} carries {@code VALID-US-STATE-CODE} at {@code :L1013}
 *       with <strong>56</strong> members, each two characters, from {@code PIC X(2)}.</li>
 *   <li>{@code validation/state-zip-prefixes.json} carries {@code VALID-US-STATE-ZIP-CD2-COMBO} at
 *       {@code :L1073} with <strong>240</strong> members, each four characters, from
 *       {@code PIC X(4)}.</li>
 *   </ul>
 *
 * <p>Each document is a JSON object whose members are keyed by the hyphenated condition name exactly as
 * the copybook spells it. Each document also carries a {@code _metadata} member which is documentation
 * and <strong>not</strong> data: this service reads only the named table members, so {@code _metadata}
 * is ignored by construction and can never be mistaken for a table.
 *
 * <h3>3.3 The invariants those counts encode</h3>
 *
 * <p>The two area-code subsets are disjoint, and together they are the full table element for element:
 * 410 plus 80 is 490, with no stray in either direction. The full table is therefore the disjoint
 * partition of the other two. That identity is the strongest available guard against a transcription
 * error in the resources and is the unit tier's most important assertion - one
 * {@code src/test/java/com/cardemo/unit/service/ValidationLookupServiceTest.java} makes, pinning the tables
 * at 490 and 410 members and asserting the order-preserving disjoint partition together with the single
 * descent at the 409-to-410 seam; an earlier revision said no such class existed, which is false and is
 * withdrawn. A second suite,
 * {@code src/test/java/com/cardemo/unit/validation/ValidationLookupServiceTest.java}, covers the same class
 * from the validation side; the two are complementary rather than duplicates. It is emphatically
 * <em>not</em> a
 * licence to derive one table from the others, for the reason given in section 1.2.
 *
 * <p>Ordering is source ordering and is preserved, which is why every table is exposed as a
 * {@link SequencedSet} rather than a plain set - the order guarantee is in the type, so no caller can
 * accidentally depend on hash iteration order. The state table is ordered alphabetically by full state
 * name and then by territory, so it ends {@code WY}, {@code DC}, {@code AS}, {@code GU}, {@code MP},
 * {@code PR}, {@code VI}; sorting it by code would destroy that and is a <strong>Medium</strong>
 * severity defect. The state-and-zip table runs from {@code AA34} to {@code WY83} with genuine numeric
 * gaps and one descending run, all preserved.
 *
 * <h3>3.4 The argument contract, and why width is a precondition</h3>
 *
 * <p>In COBOL a caller physically cannot present a wrong-width value: the receiving item is a fixed
 * {@code PIC} field, so a shorter value is space padded and a longer one truncated before the condition
 * name is ever evaluated. Java has no such receiving field, so the width has to be asserted, and this
 * service asserts it: {@code null}, blank and any length other than the declared width are contract
 * violations and raise {@code ValidationException}, carrying the offending argument's <em>name</em> and
 * its observed <em>length</em> and never its value. A value of the correct width that is simply not in
 * the table is an ordinary lookup miss and returns {@code false}; a miss is never an exception.
 *
 * <p>Two things this service deliberately does <strong>not</strong> do. It does not fold case, because
 * the source does not: an {@code 88} level comparison is byte exact, so {@code al} is not {@code AL}
 * and returns {@code false}. Having no case operation at all is also the strongest form of Clause A
 * determinism, since it leaves no locale-sensitive operation in the file to get wrong. And it does not
 * check that an area code is numeric, because {@code app/cbl/COACTUPC.cbl:L2266-L2294} already performs
 * that edit before reaching the condition name; a non-numeric triple of the right width is simply not a
 * member and returns {@code false}.
 *
 * <h3>3.5 LAST-3-OF-ZIP is never validated</h3>
 *
 * <p>{@code 02 LAST-3-OF-ZIP PIC X(3)} at {@code app/cpy/CSLKPCDY.cpy:L1314} is the second subfield of
 * the {@code 01 US-STATE-ZIPCODE-TO-EDIT} group, and it carries no {@code 88} level here or anywhere
 * else in the corpus. The condition name sits on {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} alone, so the
 * legacy system validates a state code and the first two digits of a zip code and never the last three.
 *
 * <p><strong>Retained parity artefact, severity Low, owed an entry in the planned {@code DECISION_LOG.md}.</strong> The
 * field is reproduced here as documentation and as the width constant
 * {@link #LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED}, and it is intentionally never consulted by any lookup.
 * Rule 1 Clause B forbids <em>untracked</em> dead code; this is tracked, cited and justified. Inventing
 * a rule for it would add a validation the legacy system does not perform, which is a behaviour change
 * rather than a correction.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A resource is missing from the classpath.</em> Startup fails with
 *       {@code FatalProcessingException} naming the resource path. It must never degrade to an empty
 *       table, because an empty table silently rejects every telephone number and every address.
 *       <strong>Remedy:</strong> confirm the file is present under {@code target/classes/validation/}
 *       after a build; the resources are owned by {@code src/main/resources/validation/}.</li>
 *   <li><em>A resource is malformed, or a table member is absent, is not an array, is empty, holds a
 *       non-string, holds a blank, holds a wrong-width value or holds a duplicate.</em> Startup fails
 *       the same way, with the resource path, the member name and the offending index in the message
 *       and the original failure preserved as the cause. <strong>Remedy:</strong> re-validate the file
 *       with a strict JSON parser, then re-derive it from {@code app/cpy/CSLKPCDY.cpy}.</li>
 *   <li><em>A resource loads but a count is wrong.</em> Nothing fails at startup, because a plausible
 *       count cannot be distinguished from a correct one without the copybook. The startup log line
 *       publishes all five sizes - expect {@code 490}, {@code 410}, {@code 80}, {@code 56},
 *       {@code 240} - and the unit tier asserts them exactly, together with the partition identity.
 *       <strong>Remedy:</strong> compare against the copybook line ranges; the copybook always
 *       wins.</li>
 *   <li><em>A value the legacy system accepted is now rejected.</em> Check it against the copybook line
 *       range and never against an external registry. The usual causes are a re-cased value, a value
 *       trimmed to the wrong width by the caller, and an extractor that lost the first member of a
 *       table whose {@code 88} clause line carries no value.</li>
 *   <li><em>A caller receives {@code ValidationException} where it expected {@code false}.</em> The
 *       argument violated the declared width; see section 3.4. <strong>Remedy:</strong> fix the caller
 *       to present the field at its declared width, as the legacy receiving item guaranteed.</li>
 *   <li><em>Iteration order is not what a caller expected.</em> It is source order, by design, and the
 *       accessors return {@link SequencedSet} to say so. Do not sort, do not deduplicate and do not
 *       fill the numeric gaps.</li>
 *   </ul>
 *
 * <h2>5. Findings carried forward, classified by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - a table that loads silently empty because a resource failed without failing
 *       startup. It would reject every input, or, if a caller inverted the test, accept every input. <em>Remedy:</em>
 *       the loader below throws on a missing, unreadable, malformed or empty table and never substitutes a
 *       default.</li>
 *   <li><strong>High</strong> - generating a constants class from the 1318 line copybook; inventing or omitting a
 *       single code; enabling polymorphic Jackson typing; fetching the numbering-plan registry over the network.
 *       <em>Remedy:</em> none of the four is present, and each is argued against explicitly above and in the planned
 *       {@code DECISION_LOG.md}.</li>
 *   <li><strong>Medium</strong> - merging the full area-code table into its two subsets and dropping a condition
 *       name; sorting the state table instead of preserving source order; depending on hash iteration order for any
 *       exposed ordering. <em>Remedy:</em> five tables are modelled separately and every exposed table is a
 *       {@link SequencedSet} backed by insertion order.</li>
 *   <li><strong>Low</strong> - four cosmetic irregularities in the frozen source, recorded as evidence and
 *       deliberately not propagated: {@code app/cpy/CSLKPCDY.cpy:L1011} carries the stale comment
 *       {@code *Search list of valid Phone area codes} immediately above {@code 01 US-STATE-CODE-TO-EDIT}, which is a
 *       state table and not a phone table; 1033 of the 1318 lines are indented with horizontal tabs, whereas the JSON
 *       resources and this file use spaces per the repository {@code .editorconfig}; the {@code VALUES} keyword at
 *       {@code :L931} is followed by two spaces rather than one before {@code '200'}; and {@code LAST-3-OF-ZIP} at
 *       {@code :L1314} is declared but never validated, as section 3.5 records.</li>
 *   </ul>
 *
 * <h2>6. Not available</h2>
 *
 * <ul>
 *   <li><em>Any validation rule for {@code LAST-3-OF-ZIP}.</em> Not available, and none exists: no
 *       {@code 88} level is declared on it anywhere in the corpus. What would be needed is a source
 *       condition name that is absent, or an authoritative rule from the system owner. None is
 *       invented.</li>
 *   <li><em>The semantic distinction the source intends between "general purpose" and "easily
 *       recognisable" area codes, beyond the proven disjoint partition.</em> Not available. The
 *       copybook declares both sets and documents no rule. What would be needed is numbering-plan
 *       documentation, which this repository does not contain. No behaviour is inferred from the
 *       condition names.</li>
 *   <li><em>Any latency or throughput objective for a lookup.</em> Not available. The legacy source
 *       publishes no service level of any kind, so none is invented here; the performance gate records
 *       a measured baseline rather than a target. What would be needed is a stated objective from the
 *       system owner.</li>
 *   </ul>
 *
 * <h2>7. Thread safety and lifecycle</h2>
 *
 * <p>The five tables are loaded once, in the constructor, into unmodifiable insertion-ordered sets whose
 * backing collections never escape. After construction the bean holds no mutable state, declares no
 * static mutable field, and every method on it is a pure function of its arguments and those tables, so
 * it is safe to share across request and batch threads without synchronisation. It reads three
 * read-only classpath resources and nothing else: no credential, no writable path, no outbound
 * connection.
 */
@Service
public final class ValidationLookupService {

    /**
     * The declared width of {@code LAST-3-OF-ZIP PIC X(3)} at {@code app/cpy/CSLKPCDY.cpy:L1314}.
     */
    public static final int LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED = 3;

    /**
     * Logger for the one-time startup line that publishes the five loaded table sizes.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ValidationLookupService.class);

    /**
     * The {@code classpath:} scheme prefix handed to {@link ResourceLoader#getResource(String)}.
     */
    private static final String CLASSPATH_SCHEME = "classpath:";

    /**
     * Classpath location of the resource carrying the three North American Numbering Plan tables. Declared at
     * {@code app/cpy/CSLKPCDY.cpy:L24}.
     */
    private static final String AREA_CODES_RESOURCE = "validation/nanpa-area-codes.json";

    /**
     * Classpath location of the resource carrying the United States state-code table. Declared at
     * {@code app/cpy/CSLKPCDY.cpy:L1012}.
     */
    private static final String STATE_CODES_RESOURCE = "validation/us-state-codes.json";

    /**
     * Classpath location of the resource carrying the state-and-first-two-zip-digits table. Declared at
     * {@code app/cpy/CSLKPCDY.cpy:L1072}.
     */
    private static final String STATE_ZIP_RESOURCE = "validation/state-zip-prefixes.json";

    /**
     * JSON member name for {@code 88 VALID-PHONE-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy:L30}.
     */
    private static final String PHONE_AREA_CODE_MEMBER = "VALID-PHONE-AREA-CODE";

    /**
     * JSON member name for {@code 88 VALID-GENERAL-PURP-CODE} at {@code app/cpy/CSLKPCDY.cpy:L521}.
     */
    private static final String GENERAL_PURPOSE_CODE_MEMBER = "VALID-GENERAL-PURP-CODE";

    /**
     * JSON member name for {@code 88 VALID-EASY-RECOG-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy:L931}.
     */
    private static final String EASILY_RECOGNISABLE_CODE_MEMBER = "VALID-EASY-RECOG-AREA-CODE";

    /**
     * JSON member name for {@code 88 VALID-US-STATE-CODE} at {@code app/cpy/CSLKPCDY.cpy:L1013}.
     */
    private static final String US_STATE_CODE_MEMBER = "VALID-US-STATE-CODE";

    /**
     * JSON member name for {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at {@code app/cpy/CSLKPCDY.cpy:L1073}.
     */
    private static final String STATE_ZIP_COMBO_MEMBER = "VALID-US-STATE-ZIP-CD2-COMBO";

    /**
     * Declared width of an area code, from {@code PIC XXX} at {@code app/cpy/CSLKPCDY.cpy:L24}.
     */
    public static final int AREA_CODE_WIDTH = 3;

    /**
     * Declared width of a state code, from {@code PIC X(2)} at {@code app/cpy/CSLKPCDY.cpy:L1012}.
     */
    public static final int STATE_CODE_WIDTH = 2;

    /**
     * Declared width of the state-and-zip key, from {@code PIC X(4)} at {@code app/cpy/CSLKPCDY.cpy:L1072}: two
     * characters of state code then two digits of zip code.
     */
    public static final int STATE_ZIP_KEY_WIDTH = 4;

    /**
     * Number of leading zip-code characters that take part in the key, from {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}
     * at {@code app/cbl/COACTUPC.cbl:L2538}.
     */
    public static final int ZIP_PREFIX_LENGTH = 2;

    /**
     * Smallest zip code {@link #isValidStateAndZipCode(String, String)} accepts as a whole zip code.
     * {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14}.
     */
    public static final int MINIMUM_ZIP_CODE_LENGTH = 5;

    /**
     * Argument name reported when an area-code argument violates its width contract. Declared at
     * {@code app/cpy/CSLKPCDY.cpy:L24}.
     */
    private static final String AREA_CODE_ARGUMENT = "WS-US-PHONE-AREA-CODE-TO-EDIT";

    /**
     * Argument name for a state code, from {@code app/cpy/CSLKPCDY.cpy:L1012}.
     */
    private static final String STATE_CODE_ARGUMENT = "US-STATE-CODE-TO-EDIT";

    /**
     * Argument name for the composed state-and-zip key, from {@code app/cpy/CSLKPCDY.cpy:L1072}.
     */
    private static final String STATE_ZIP_KEY_ARGUMENT = "US-STATE-AND-FIRST-ZIP2";

    /**
     * Argument name for the state code supplied to the composing lookup, from COACTUPC:L2537.
     */
    private static final String CUSTOMER_STATE_ARGUMENT = "ACUP-NEW-CUST-ADDR-STATE-CD";

    /**
     * Argument name for the whole zip code supplied to the composing lookup, from COACTUPC:L2538.
     */
    private static final String CUSTOMER_ZIP_ARGUMENT = "ACUP-NEW-CUST-ADDR-ZIP";

    /**
     * Value carried as {@code ABEND-CULPRIT PIC X(8)} when a resource cannot be loaded. Declared at
     * {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final String ABEND_CULPRIT = "CSLKPCDY";

    /**
     * Value carried as {@code ABEND-REASON PIC X(50)} when a resource cannot be loaded. Declared at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    private static final String ABEND_REASON = "ValidationLookupService lookup table load failure";

    /**
     * {@code 88 VALID-PHONE-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L30}, in source order.
     */
    private final SequencedSet<String> validPhoneAreaCodes;

    /**
     * {@code 88 VALID-GENERAL-PURP-CODE} of {@code app/cpy/CSLKPCDY.cpy:L521}, in source order.
     */
    private final SequencedSet<String> validGeneralPurposeAreaCodes;

    /**
     * {@code 88 VALID-EASY-RECOG-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L931}, in source order.
     */
    private final SequencedSet<String> validEasilyRecognisableAreaCodes;

    /**
     * {@code 88 VALID-US-STATE-CODE} of {@code app/cpy/CSLKPCDY.cpy:L1013}, in source order.
     */
    private final SequencedSet<String> validUsStateCodes;

    /**
     * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} of {@code app/cpy/CSLKPCDY.cpy:L1073}, in source order.
     */
    private final SequencedSet<String> validStateZipCodeCombinations;

    /**
     * Loads all five lookup tables from the three classpath resources, once, and publishes their sizes.
     *
     * @param resourceLoader the Spring resource loader used to resolve the three {@code classpath:} locations.
     * @param objectMapper the Jackson mapper used to parse the three documents into a concrete {@link JsonNode}
     * tree.
     * @throws FatalProcessingException if any resource is absent, unreadable, not well-formed JSON, not a JSON
     * object, missing its table member, carrying a non-array or empty member, or carrying an element that is
     * not a string, is blank, has the wrong width or repeats an earlier element.
     */
    public ValidationLookupService(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.validPhoneAreaCodes = loadTable(resourceLoader, objectMapper, AREA_CODES_RESOURCE,
                PHONE_AREA_CODE_MEMBER, AREA_CODE_WIDTH);
        this.validGeneralPurposeAreaCodes = loadTable(resourceLoader, objectMapper, AREA_CODES_RESOURCE,
                GENERAL_PURPOSE_CODE_MEMBER, AREA_CODE_WIDTH);
        this.validEasilyRecognisableAreaCodes = loadTable(resourceLoader, objectMapper,
                AREA_CODES_RESOURCE, EASILY_RECOGNISABLE_CODE_MEMBER, AREA_CODE_WIDTH);
        this.validUsStateCodes = loadTable(resourceLoader, objectMapper, STATE_CODES_RESOURCE,
                US_STATE_CODE_MEMBER, STATE_CODE_WIDTH);
        this.validStateZipCodeCombinations = loadTable(resourceLoader, objectMapper, STATE_ZIP_RESOURCE,
                STATE_ZIP_COMBO_MEMBER, STATE_ZIP_KEY_WIDTH);

        LOG.info("CSLKPCDY lookup tables loaded: {}={}, {}={}, {}={}, {}={}, {}={}",
                PHONE_AREA_CODE_MEMBER, this.validPhoneAreaCodes.size(),
                GENERAL_PURPOSE_CODE_MEMBER, this.validGeneralPurposeAreaCodes.size(),
                EASILY_RECOGNISABLE_CODE_MEMBER, this.validEasilyRecognisableAreaCodes.size(),
                US_STATE_CODE_MEMBER, this.validUsStateCodes.size(),
                STATE_ZIP_COMBO_MEMBER, this.validStateZipCodeCombinations.size());
    }

    /**
     * Evaluates {@code 88 VALID-PHONE-AREA-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L30} on
     * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at {@code :L24}.
     *
     * @param areaCode the candidate area code, exactly {@value #AREA_CODE_WIDTH} characters, compared byte for
     * byte and with no case folding, exactly as the {@code 88} level compares it.
     * @return {@code true} when the value is a member of the table.
     * @throws ValidationException if {@code areaCode} is {@code null} or blank, reported as
     * {@code FailureKind.BLANK}, or is not exactly {@value #AREA_CODE_WIDTH} characters, reported as
     * {@code FailureKind.INVALID}.
     */
    public boolean isValidPhoneAreaCode(String areaCode) {
        requireExactWidth(areaCode, AREA_CODE_WIDTH, AREA_CODE_ARGUMENT);
        return validPhoneAreaCodes.contains(areaCode);
    }

    /**
     * Evaluates {@code 88 VALID-GENERAL-PURP-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L521} on
     * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at {@code :L24}.
     *
     * @param areaCode the candidate area code, exactly {@value #AREA_CODE_WIDTH} characters, compared byte for
     * byte with no case folding.
     * @return {@code true} when the value is a member of the table, {@code false} otherwise
     * @throws ValidationException if {@code areaCode} is {@code null}, blank, or not exactly
     * {@value #AREA_CODE_WIDTH} characters, as described on {@link #isValidPhoneAreaCode(String)}
     */
    public boolean isValidGeneralPurposeAreaCode(String areaCode) {
        requireExactWidth(areaCode, AREA_CODE_WIDTH, AREA_CODE_ARGUMENT);
        return validGeneralPurposeAreaCodes.contains(areaCode);
    }

    /**
     * Evaluates {@code 88 VALID-EASY-RECOG-AREA-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L931} on
     * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at {@code :L24}.
     *
     * @param areaCode the candidate area code, exactly {@value #AREA_CODE_WIDTH} characters, compared byte for
     * byte with no case folding.
     * @return {@code true} when the value is a member of the table, {@code false} otherwise
     * @throws ValidationException if {@code areaCode} is {@code null}, blank, or not exactly
     * {@value #AREA_CODE_WIDTH} characters, as described on {@link #isValidPhoneAreaCode(String)}
     */
    public boolean isValidEasilyRecognisableAreaCode(String areaCode) {
        requireExactWidth(areaCode, AREA_CODE_WIDTH, AREA_CODE_ARGUMENT);
        return validEasilyRecognisableAreaCodes.contains(areaCode);
    }

    /**
     * Evaluates {@code 88 VALID-US-STATE-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L1013} on
     * {@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} at {@code :L1012}.
     *
     * @param stateCode the candidate state, district or territory code, exactly {@value #STATE_CODE_WIDTH}
     * uppercase characters.
     * @return {@code true} when the value is a member of the table.
     * @throws ValidationException if {@code stateCode} is {@code null} or blank, reported as
     * {@code FailureKind.BLANK}, or is not exactly {@value #STATE_CODE_WIDTH} characters, reported as
     * {@code FailureKind.INVALID}.
     */
    public boolean isValidUsStateCode(String stateCode) {
        requireExactWidth(stateCode, STATE_CODE_WIDTH, STATE_CODE_ARGUMENT);
        return validUsStateCodes.contains(stateCode);
    }

    /**
     * Evaluates {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, declared at {@code app/cpy/CSLKPCDY.cpy:L1073} on
     * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at {@code :L1072}.
     *
     * @param stateAndFirstTwoZipDigits the candidate key, exactly {@value #STATE_ZIP_KEY_WIDTH} characters,
     * compared byte for byte with no case folding.
     * @return {@code true} when the value is a member of the table, {@code false} otherwise
     * @throws ValidationException if the argument is {@code null} or blank, reported as
     * {@code FailureKind.BLANK}, or is not exactly {@value #STATE_ZIP_KEY_WIDTH} characters, reported as
     * {@code FailureKind.INVALID}
     */
    public boolean isValidStateZipCodeCombination(String stateAndFirstTwoZipDigits) {
        requireExactWidth(stateAndFirstTwoZipDigits, STATE_ZIP_KEY_WIDTH, STATE_ZIP_KEY_ARGUMENT);
        return validStateZipCodeCombinations.contains(stateAndFirstTwoZipDigits);
    }

    /**
     * Composes the four character state-and-zip key from a state code and a whole zip code, then evaluates
     * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} against it.
     *
     * @param stateCode the state, district or territory code, exactly {@value #STATE_CODE_WIDTH} characters.
     * @param zipCode the whole zip code, at least {@value #MINIMUM_ZIP_CODE_LENGTH} characters as the screen
     * field and the preceding edit require.
     * @return {@code true} when the composed key is a member of the table, {@code false} otherwise
     * @throws ValidationException if {@code stateCode} is {@code null}, blank or not exactly
     * {@value #STATE_CODE_WIDTH} characters, or {@code zipCode} is {@code null}, blank or shorter than
     * {@value #MINIMUM_ZIP_CODE_LENGTH} characters.
     */
    public boolean isValidStateAndZipCode(String stateCode, String zipCode) {
        requireExactWidth(stateCode, STATE_CODE_WIDTH, CUSTOMER_STATE_ARGUMENT);
        requireMinimumLength(zipCode, MINIMUM_ZIP_CODE_LENGTH, CUSTOMER_ZIP_ARGUMENT);
        return validStateZipCodeCombinations.contains(stateCode + zipCode.substring(0, ZIP_PREFIX_LENGTH));
    }

    /**
     * Returns {@code 88 VALID-PHONE-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L30} as an unmodifiable,
     * source-ordered set of 490 three character codes.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidPhoneAreaCodes() {
        return validPhoneAreaCodes;
    }

    /**
     * Returns {@code 88 VALID-GENERAL-PURP-CODE} of {@code app/cpy/CSLKPCDY.cpy:L521} as an unmodifiable,
     * source-ordered set of 410 three character codes.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidGeneralPurposeAreaCodes() {
        return validGeneralPurposeAreaCodes;
    }

    /**
     * Returns {@code 88 VALID-EASY-RECOG-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L931} as an unmodifiable,
     * source-ordered set of 80 three character codes.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidEasilyRecognisableAreaCodes() {
        return validEasilyRecognisableAreaCodes;
    }

    /**
     * Returns {@code 88 VALID-US-STATE-CODE} of {@code app/cpy/CSLKPCDY.cpy:L1013} as an unmodifiable,
     * source-ordered set of 56 two character codes.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidUsStateCodes() {
        return validUsStateCodes;
    }

    /**
     * Returns {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} of {@code app/cpy/CSLKPCDY.cpy:L1073} as an unmodifiable,
     * source-ordered set of 240 four character keys.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidStateZipCodeCombinations() {
        return validStateZipCodeCombinations;
    }

    /**
     * Asserts that a lookup argument is present and exactly as wide as its source {@code PIC} clause declares.
     *
     * @param value the argument to check; {@code null} and blank are both handled explicitly
     * @param expectedWidth the width declared by the source {@code PIC} clause
     * @param argumentName the COBOL data item name reported as the failing field
     * @throws ValidationException with {@code FailureKind.BLANK} when {@code value} is {@code null} or blank,
     * and with {@code FailureKind.INVALID} when its length is anything other than {@code expectedWidth}
     */
    private static void requireExactWidth(String value, int expectedWidth, String argumentName) {
        requirePresent(value, argumentName);
        if (value.length() != expectedWidth) {
            throw new ValidationException(argumentName + " must be exactly " + expectedWidth
                    + " characters wide, as its PIC clause declares, but " + value.length()
                    + " were supplied", argumentName, ValidationException.FailureKind.INVALID);
        }
    }

    /**
     * Asserts that a lookup argument is present and at least as long as the shortest value the source would
     * have accepted.
     *
     * @param value the argument to check; {@code null} and blank are both handled explicitly
     * @param minimumLength the shortest acceptable length
     * @param argumentName the COBOL data item name reported as the failing field
     * @throws ValidationException with {@code FailureKind.BLANK} when {@code value} is {@code null} or blank,
     * and with {@code FailureKind.INVALID} when it is shorter than {@code minimumLength}
     */
    private static void requireMinimumLength(String value, int minimumLength, String argumentName) {
        requirePresent(value, argumentName);
        if (value.length() < minimumLength) {
            throw new ValidationException(argumentName + " must be at least " + minimumLength
                    + " characters long, as its screen field and preceding edit require, but "
                    + value.length() + " were supplied", argumentName,
                    ValidationException.FailureKind.INVALID);
        }
    }

    /**
     * Rejects a {@code null} or blank lookup argument, the two absence cases shared by every predicate.
     *
     * @param value the argument to check
     * @param argumentName the COBOL data item name reported as the failing field
     * @throws ValidationException with {@code FailureKind.BLANK} when {@code value} is {@code null} or contains
     * only whitespace
     */
    private static void requirePresent(String value, String argumentName) {
        if (value == null) {
            throw new ValidationException(argumentName + " must be supplied", argumentName,
                    ValidationException.FailureKind.BLANK);
        }
        if (value.isBlank()) {
            throw new ValidationException(argumentName + " must not be blank", argumentName,
                    ValidationException.FailureKind.BLANK);
        }
    }

    /**
     * The one loading mechanism, shared by all five tables and all three resources.
     *
     * @param resourceLoader resolves the {@code classpath:} location.
     * @param objectMapper parses the document into a {@link JsonNode} tree.
     * @param resourcePath the classpath-relative location, without the {@code classpath:} scheme
     * @param memberName the JSON member name, which is the {@code 88}-level condition name exactly as
     * {@code app/cpy/CSLKPCDY.cpy} spells it
     * @param elementWidth the width every element must have, taken from the source {@code PIC} clause
     * @return the table as an unmodifiable, insertion-ordered {@link SequencedSet} whose backing collection is
     * unreachable, never {@code null} and never empty
     * @throws FatalProcessingException on any of the failures listed above, carrying the resource path, the
     * member name, the defect and the preserved cause
     */
    private static SequencedSet<String> loadTable(ResourceLoader resourceLoader, ObjectMapper objectMapper,
            String resourcePath, String memberName, int elementWidth) {
        Resource resource = resourceLoader.getResource(CLASSPATH_SCHEME + resourcePath);
        if (!resource.exists()) {
            throw cannotLoad(resourcePath, memberName, "the resource is absent from the classpath", null);
        }

        JsonNode root;
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            root = objectMapper.readTree(reader);
        } catch (JsonProcessingException e) {
            throw cannotLoad(resourcePath, memberName, "the resource is not well-formed JSON", e);
        } catch (IOException e) {
            throw cannotLoad(resourcePath, memberName, "the resource could not be read", e);
        }

        if (root == null || !root.isObject()) {
            throw cannotLoad(resourcePath, memberName, "the document root is not a JSON object", null);
        }

        JsonNode member = root.get(memberName);
        if (member == null || member.isNull()) {
            throw cannotLoad(resourcePath, memberName, "the table member is absent", null);
        }
        if (!member.isArray()) {
            throw cannotLoad(resourcePath, memberName, "the table member is not a JSON array", null);
        }
        if (member.isEmpty()) {
            throw cannotLoad(resourcePath, memberName, "the table member is an empty array, which would "
                    + "reject every input", null);
        }

        LinkedHashSet<String> table = LinkedHashSet.newLinkedHashSet(member.size());
        int index = 0;
        for (JsonNode element : member) {
            if (!element.isTextual()) {
                throw cannotLoad(resourcePath, memberName, "element " + index + " is not a JSON string, "
                        + "but the source field is character data", null);
            }
            String value = element.textValue();
            if (value.isBlank()) {
                throw cannotLoad(resourcePath, memberName, "element " + index + " is blank", null);
            }
            if (value.length() != elementWidth) {
                throw cannotLoad(resourcePath, memberName, "element " + index + " is " + value.length()
                        + " characters wide but the source PIC clause declares " + elementWidth, null);
            }
            if (!table.add(value)) {
                throw cannotLoad(resourcePath, memberName, "element " + index + " repeats an earlier "
                        + "element, but the source table has no duplicate", null);
            }
            index++;
        }

        return Collections.unmodifiableSequencedSet(table);
    }

    /**
     * Builds the fail-fast abend raised when a lookup resource cannot be turned into a table.
     *
     * <p>Returns the exception rather than throwing it so that every call site above reads
     * {@code throw cannotLoad(...)}, which keeps the compiler's flow analysis exact and leaves no
     * doubt at a glance that the method never falls through to a default.
     *
     * <p>{@code FatalProcessingException} is the right type because the condition is terminal: it is
     * raised during construction, so it fails Spring context refresh and therefore startup. Recovery
     * would mean an empty or partial table, which is the outcome the type exists to prevent.
     *
     * <p><strong>How the four abend fields are populated, and why one of them is not.</strong>
     *
     * <ul>
     *   <li>{@code ABEND-CODE PIC X(4)} is left {@code null}. The corpus publishes three abend codes -
     *       {@code '0001'} and {@code '9999'} online, and the binary {@code 999} in batch - and none of
     *       them describes a classpath resource, because a COBOL copybook is compiled in and has no
     *       runtime load to fail. Rather than invent a fourth code, the field is left unset:
     *       <em>Not available</em>, and what would be needed is a code assigned by the system owner.</li>
     *   <li>{@code ABEND-CULPRIT PIC X(8)} carries {@link #ABEND_CULPRIT}, naming the frozen copybook an
     *       operator must consult.</li>
     *   <li>{@code ABEND-REASON PIC X(50)} carries {@link #ABEND_REASON}, naming this Java component.</li>
     *   <li>{@code ABEND-MSG PIC X(72)} carries the diagnostic. It is allowed to exceed seventy-two
     *       characters, because the resource path is required in the message and truncating to the legacy
     *       width would discard exactly the part an operator needs. The constructor stores the message
     *       unchanged, so the widening is contained here and is documented at this declaration. It is owed an
     *       entry for {@code DECISION_LOG.md}, which is <strong>not available</strong> as measured
     *       1 August 2026, so this Javadoc is the record until that file is authored.</li>
     * </ul>
     *
     * @param resourcePath the classpath-relative resource that could not be turned into a table
     * @param memberName the table member being loaded when the failure was detected
     * @param detail what specifically was wrong, phrased so it names no element value
     * @param cause the underlying failure, or {@code null} when the defect is structural and there is no
     * throwable to attribute.
     * @return the exception to throw, never {@code null}
     */
    private static FatalProcessingException cannotLoad(String resourcePath, String memberName,
            String detail, Throwable cause) {
        String message = "Cannot load CSLKPCDY lookup table " + memberName + " from classpath resource "
                + resourcePath + ": " + detail;
        return new FatalProcessingException(null, ABEND_CULPRIT, ABEND_REASON, message, cause);
    }
}
