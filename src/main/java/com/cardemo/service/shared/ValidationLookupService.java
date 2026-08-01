/*
 * ******************************************************************
 * Component   : ValidationLookupService
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
 * <p><strong>Why the data is a resource and not a generated constants class.</strong> Transcribing 1276
 * literals into Java constants would add well over a thousand lines of source that no compiler check
 * makes safer, would put reference data inside a compilation unit, and would make the element counts
 * hard to audit against the copybook. Rule 1 Clause C forbids that duplication and Clause A asks for
 * minimal complexity; Clause A's performance clause is satisfied either way, because membership is an
 * immutable hash-set probe in both designs and the tables are read once at startup. Externalising the
 * data is therefore the justified tradeoff, and it is recorded as such in {@code DECISION_LOG.md}.
 * Generating a constants class instead is a <strong>High</strong> severity defect.
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
 * </ul>
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
 * {@code failOnWarning}, so a raw type, an unchecked cast, an unused import or a deprecated call is a
 * build failure rather than a warning - which is why the resource binding below is explicitly typed
 * throughout. Build with {@code mvn -B clean compile}, run the unit tier with {@code mvn -B clean test}
 * and the full gate with {@code mvn -B clean verify}, where JaCoCo enforces an 80 percent line floor
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
 * </ul>
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
 * error in the resources and is asserted by the unit tier; it is emphatically <em>not</em> a licence to
 * derive one table from the others, for the reason given in section 1.2.
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
 * <p><strong>Retained parity artefact, severity Low, tracked in {@code DECISION_LOG.md}.</strong> The
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
 * </ul>
 *
 * <h2>5. Findings carried forward, classified by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - a table that loads silently empty because a resource failed without
 *       failing startup. It would reject every input, or, if a caller inverted the test, accept every
 *       input. <em>Remedy:</em> the loader below throws on a missing, unreadable, malformed or empty
 *       table and never substitutes a default.</li>
 *   <li><strong>High</strong> - generating a constants class from the 1318 line copybook; inventing or
 *       omitting a single code; enabling polymorphic Jackson typing; fetching the numbering-plan
 *       registry over the network. <em>Remedy:</em> none of the four is present, and each is argued
 *       against explicitly above and in {@code DECISION_LOG.md}.</li>
 *   <li><strong>Medium</strong> - merging the full area-code table into its two subsets and dropping a
 *       condition name; sorting the state table instead of preserving source order; depending on hash
 *       iteration order for any exposed ordering. <em>Remedy:</em> five tables are modelled separately
 *       and every exposed table is a {@link SequencedSet} backed by insertion order.</li>
 *   <li><strong>Low</strong> - four cosmetic irregularities in the frozen source, recorded as evidence
 *       and deliberately not propagated: {@code app/cpy/CSLKPCDY.cpy:L1011} carries the stale comment
 *       {@code *Search list of valid Phone area codes} immediately above {@code 01
 *       US-STATE-CODE-TO-EDIT}, which is a state table and not a phone table; 1033 of the 1318 lines
 *       are indented with horizontal tabs, whereas the JSON resources and this file use spaces per the
 *       repository {@code .editorconfig}; the {@code VALUES} keyword at {@code :L931} is followed by
 *       two spaces rather than one before {@code '200'}; and {@code LAST-3-OF-ZIP} at {@code :L1314} is
 *       declared but never validated, as section 3.5 records.</li>
 * </ul>
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
 * </ul>
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
     *
     * <p><strong>This constant is documentation, and it is intentionally never used in a lookup.</strong>
     * It is the retained parity artefact described in section 3.5 of the class documentation: the field
     * is the second subfield of {@code 01 US-STATE-ZIPCODE-TO-EDIT}, it carries no {@code 88} level
     * anywhere in the corpus, and the legacy system therefore never validates the last three digits of a
     * zip code. Publishing the width records the field contract without acting on it, which is what
     * lets Rule 1 Clause B's prohibition on <em>untracked</em> dead code and the parity mandate hold at
     * the same time. Tracked in {@code DECISION_LOG.md}; severity Low.
     *
     * <p>Consulting it from any lookup, or inventing a rule for the field, is a behaviour change rather
     * than a correction. The three characters it describes are simply not part of the key that
     * {@link #isValidStateZipCodeCombination(String)} tests.
     */
    public static final int LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED = 3;

    /**
     * Logger for the one-time startup line that publishes the five loaded table sizes.
     *
     * <p>It is the only logging this class performs. <strong>No lookup argument is ever logged</strong>:
     * an area code, a state code and a zip prefix are fragments of customer contact data, so Rule 1
     * Clause D keeps them out of log output entirely. The startup line carries the condition names and
     * the element counts, which are properties of the checked-in reference data and of nobody's record.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ValidationLookupService.class);

    /**
     * The {@code classpath:} scheme prefix handed to {@link ResourceLoader#getResource(String)}.
     *
     * <p>Naming the scheme explicitly is what guarantees a classpath read rather than a filesystem read,
     * whatever {@link ResourceLoader} implementation is injected. Rule 1 Clause C forbids
     * environment-specific assumptions, and an absolute or relative host path would be exactly that.
     */
    private static final String CLASSPATH_SCHEME = "classpath:";

    /**
     * Classpath location of the resource carrying the three North American Numbering Plan tables.
     *
     * <p>Derived from {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at
     * {@code app/cpy/CSLKPCDY.cpy:L24} and the three {@code 88} levels declared on it.
     */
    private static final String AREA_CODES_RESOURCE = "validation/nanpa-area-codes.json";

    /**
     * Classpath location of the resource carrying the United States state-code table.
     *
     * <p>Derived from {@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} at {@code app/cpy/CSLKPCDY.cpy:L1012}.
     */
    private static final String STATE_CODES_RESOURCE = "validation/us-state-codes.json";

    /**
     * Classpath location of the resource carrying the state-and-first-two-zip-digits table.
     *
     * <p>Derived from {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at
     * {@code app/cpy/CSLKPCDY.cpy:L1072}.
     */
    private static final String STATE_ZIP_RESOURCE = "validation/state-zip-prefixes.json";

    /** JSON member name for {@code 88 VALID-PHONE-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy:L30}. */
    private static final String PHONE_AREA_CODE_MEMBER = "VALID-PHONE-AREA-CODE";

    /** JSON member name for {@code 88 VALID-GENERAL-PURP-CODE} at {@code app/cpy/CSLKPCDY.cpy:L521}. */
    private static final String GENERAL_PURPOSE_CODE_MEMBER = "VALID-GENERAL-PURP-CODE";

    /**
     * JSON member name for {@code 88 VALID-EASY-RECOG-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy:L931}.
     *
     * <p>That source line is the one whose {@code VALUES} keyword is followed by two spaces rather than
     * one before {@code '200'} - a cosmetic legacy irregularity, severity Low, recorded here for
     * transcription accuracy and with no effect on the member name or the field contract.
     */
    private static final String EASILY_RECOGNISABLE_CODE_MEMBER = "VALID-EASY-RECOG-AREA-CODE";

    /** JSON member name for {@code 88 VALID-US-STATE-CODE} at {@code app/cpy/CSLKPCDY.cpy:L1013}. */
    private static final String US_STATE_CODE_MEMBER = "VALID-US-STATE-CODE";

    /**
     * JSON member name for {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at
     * {@code app/cpy/CSLKPCDY.cpy:L1073}.
     */
    private static final String STATE_ZIP_COMBO_MEMBER = "VALID-US-STATE-ZIP-CD2-COMBO";

    /**
     * Declared width of an area code, from {@code PIC XXX} at {@code app/cpy/CSLKPCDY.cpy:L24}.
     *
     * <p>Published because it is part of the argument contract of the three area-code lookups: a caller
     * shaping a request field or a validator needs the declared width, and reading it from here keeps it
     * from being restated as a magic number at every call site.
     */
    public static final int AREA_CODE_WIDTH = 3;

    /**
     * Declared width of a state code, from {@code PIC X(2)} at {@code app/cpy/CSLKPCDY.cpy:L1012}.
     *
     * <p>Published for the same reason as {@link #AREA_CODE_WIDTH}.
     */
    public static final int STATE_CODE_WIDTH = 2;

    /**
     * Declared width of the state-and-zip key, from {@code PIC X(4)} at
     * {@code app/cpy/CSLKPCDY.cpy:L1072}: two characters of state code then two digits of zip code.
     *
     * <p>Published for the same reason as {@link #AREA_CODE_WIDTH}.
     */
    public static final int STATE_ZIP_KEY_WIDTH = 4;

    /**
     * Number of leading zip-code characters that take part in the key, from
     * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} at {@code app/cbl/COACTUPC.cbl:L2538}.
     *
     * <p>Published so that a caller composing the key itself, rather than calling
     * {@link #isValidStateAndZipCode(String, String)}, cannot pick a different slice length.
     */
    public static final int ZIP_PREFIX_LENGTH = 2;

    /**
     * Smallest zip code {@link #isValidStateAndZipCode(String, String)} accepts as a whole zip code.
     *
     * <p>Five, from two independent pieces of evidence in the frozen corpus. The screen field is
     * {@code 02 ACSZIPCI PIC X(5)} at {@code app/cpy-bms/COACTUP.CPY:L246}, and the edit that precedes
     * the lookup moves the literal {@code 5} into the edit length at
     * {@code app/cbl/COACTUPC.cbl:L1606-L1608} before requiring a numeric value. A shorter value is
     * therefore not a zip code at all. It is a minimum rather than an exact width because the persisted
     * item is wider - {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14}, mirrored by
     * {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at {@code app/cbl/COACTUPC.cbl:L809} - and the source
     * slices its first two characters regardless of how the remainder is filled.
     *
     * <p>Published for the same reason as {@link #AREA_CODE_WIDTH}.
     */
    public static final int MINIMUM_ZIP_CODE_LENGTH = 5;

    /**
     * Argument name reported when an area-code argument violates its width contract.
     *
     * <p>It is the COBOL data item that received the value at {@code app/cpy/CSLKPCDY.cpy:L24}, which
     * makes it both a developer-chosen identifier and a traceability citation. It is never request data,
     * as {@code ValidationException} requires of a field name.
     */
    private static final String AREA_CODE_ARGUMENT = "WS-US-PHONE-AREA-CODE-TO-EDIT";

    /** Argument name for a state code, from {@code app/cpy/CSLKPCDY.cpy:L1012}. */
    private static final String STATE_CODE_ARGUMENT = "US-STATE-CODE-TO-EDIT";

    /** Argument name for the composed state-and-zip key, from {@code app/cpy/CSLKPCDY.cpy:L1072}. */
    private static final String STATE_ZIP_KEY_ARGUMENT = "US-STATE-AND-FIRST-ZIP2";

    /** Argument name for the state code supplied to the composing lookup, from COACTUPC:L2537. */
    private static final String CUSTOMER_STATE_ARGUMENT = "ACUP-NEW-CUST-ADDR-STATE-CD";

    /** Argument name for the whole zip code supplied to the composing lookup, from COACTUPC:L2538. */
    private static final String CUSTOMER_ZIP_ARGUMENT = "ACUP-NEW-CUST-ADDR-ZIP";

    /**
     * Value carried as {@code ABEND-CULPRIT PIC X(8)} when a resource cannot be loaded.
     *
     * <p>Exactly eight characters, honouring the width declared at {@code app/cpy/CSMSG02Y.cpy:L24},
     * and naming the frozen copybook whose externalised data failed to load - the artefact an operator
     * must consult. The Java component that raised the failure is named in {@link #ABEND_REASON}, in
     * this class's logger name and in the stack trace, so nothing is lost by spending the eight
     * characters on the copybook rather than on a truncated class name.
     */
    private static final String ABEND_CULPRIT = "CSLKPCDY";

    /**
     * Value carried as {@code ABEND-REASON PIC X(50)} when a resource cannot be loaded.
     *
     * <p>Fixed text, within the fifty characters declared at {@code app/cpy/CSMSG02Y.cpy:L26}, naming
     * the Java component that raised the failure. It describes the condition and never the data, as the
     * field's contract requires.
     */
    private static final String ABEND_REASON = "ValidationLookupService lookup table load failure";

    /**
     * {@code 88 VALID-PHONE-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L30}, in source order.
     *
     * <p>Unmodifiable and insertion ordered; the backing collection never escapes {@link #loadTable}.
     */
    private final SequencedSet<String> validPhoneAreaCodes;

    /** {@code 88 VALID-GENERAL-PURP-CODE} of {@code app/cpy/CSLKPCDY.cpy:L521}, in source order. */
    private final SequencedSet<String> validGeneralPurposeAreaCodes;

    /** {@code 88 VALID-EASY-RECOG-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L931}, in source order. */
    private final SequencedSet<String> validEasilyRecognisableAreaCodes;

    /** {@code 88 VALID-US-STATE-CODE} of {@code app/cpy/CSLKPCDY.cpy:L1013}, in source order. */
    private final SequencedSet<String> validUsStateCodes;

    /**
     * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} of {@code app/cpy/CSLKPCDY.cpy:L1073}, in source order.
     */
    private final SequencedSet<String> validStateZipCodeCombinations;

    /**
     * Loads all five lookup tables from the three classpath resources, once, and publishes their sizes.
     *
     * <p><strong>Purpose.</strong> This is the whole lifecycle of the bean. Every table is read here and
     * never again: there is no reload, no refresh, no cache expiry and no lazy path, because the data is
     * frozen reference data transcribed from a frozen copybook. Constructor injection is the only
     * injection style used - no field injection, no setter injection and no service locator - which is
     * what makes the five fields {@code final} and the instance immutable and thread safe the moment
     * construction returns.
     *
     * <p><strong>Side effects.</strong> Three read-only classpath resources are opened, read to
     * completion and closed. One {@code INFO} log line is emitted carrying the five condition names and
     * their element counts. Nothing else is read, nothing is written, and no lookup value is logged
     * because no lookup has happened yet.
     *
     * <p><strong>Error mode: fail fast, never degrade.</strong> Any problem with any of the three
     * resources raises {@code FatalProcessingException} from here, which fails Spring context refresh
     * and therefore fails application startup. That is deliberate and is the Blocker guard of section 5
     * of the class documentation: a table that quietly loaded empty would reject every telephone number
     * and every address, and the application would look healthy while doing it. There is no fallback
     * table, no inlined copy of the data and no empty-set default anywhere in this class.
     *
     * <p><strong>Expected startup line.</strong>
     * {@code VALID-PHONE-AREA-CODE=490, VALID-GENERAL-PURP-CODE=410, VALID-EASY-RECOG-AREA-CODE=80,
     * VALID-US-STATE-CODE=56, VALID-US-STATE-ZIP-CD2-COMBO=240}. Any other figure means a resource was
     * truncated or edited; see section 4 of the class documentation.
     *
     * @param resourceLoader the Spring resource loader used to resolve the three {@code classpath:}
     *                       locations. Supplied by the container; every Spring
     *                       {@code ApplicationContext} is itself a {@link ResourceLoader}. It is used
     *                       only to resolve the three compile-time constant locations of this class and
     *                       is never handed a caller-supplied location, so it cannot be steered at a
     *                       filesystem path, a URL or an arbitrary resource
     * @param objectMapper   the Jackson mapper used to parse the three documents into a concrete
     *                       {@link JsonNode} tree. Supplied by Spring Boot's auto-configuration. It is
     *                       used only for {@code readTree}, so no polymorphic type handling and no
     *                       default typing is required, requested or enabled by this class, and a
     *                       mapper on which they have been enabled elsewhere still cannot influence
     *                       this class: a tree read materialises no caller-named type
     * @throws FatalProcessingException if any resource is absent, unreadable, not well-formed JSON, not
     *                                  a JSON object, missing its table member, carrying a non-array or
     *                                  empty member, or carrying an element that is not a string, is
     *                                  blank, has the wrong width or repeats an earlier element. The
     *                                  message names the resource path, the table member and the
     *                                  specific defect, and any underlying failure is preserved as the
     *                                  cause
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
     * <p>The full 490 member North American Numbering Plan table: every area code the legacy validation
     * accepts, being the 410 general-purpose codes followed by the 80 easily-recognisable codes in that
     * order. This condition name is declared in the copybook but evaluated nowhere in the corpus; it is
     * modelled because it is part of the field contract and because a caller may legitimately want the
     * union test without choosing a subset.
     *
     * <p>Side effects: none. The method is a pure function of its argument and the immutable table.
     * Neither the argument nor the outcome is logged.
     *
     * @param areaCode the candidate area code, exactly {@value #AREA_CODE_WIDTH} characters, compared
     *                 byte for byte and with no case folding, exactly as the {@code 88} level compares
     *                 it. Treated as untrusted input
     * @return {@code true} when the value is a member of the table; {@code false} for any well-formed
     *         value that is not, including a non-numeric one. An unknown value is never treated as valid
     * @throws ValidationException if {@code areaCode} is {@code null} or blank, reported as
     *                             {@code FailureKind.BLANK}, or is not exactly
     *                             {@value #AREA_CODE_WIDTH} characters, reported as
     *                             {@code FailureKind.INVALID}. The exception carries the argument name
     *                             and the observed length and never the value
     */
    public boolean isValidPhoneAreaCode(String areaCode) {
        requireExactWidth(areaCode, AREA_CODE_WIDTH, AREA_CODE_ARGUMENT);
        return validPhoneAreaCodes.contains(areaCode);
    }

    /**
     * Evaluates {@code 88 VALID-GENERAL-PURP-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L521} on
     * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at {@code :L24}.
     *
     * <p><strong>This is the area-code test the legacy corpus actually performs.</strong>
     * {@code app/cbl/COACTUPC.cbl:L2296-L2298} moves the trimmed area code into the parent item and
     * tests this condition name - not {@link #isValidPhoneAreaCode(String)} - and on failure produces
     * the literal {@code ': Not valid North America general purpose area code'}. A caller replacing that
     * paragraph must call this method and must own that literal itself; see section 1.3 of the class
     * documentation.
     *
     * <p>410 members, disjoint from {@link #isValidEasilyRecognisableAreaCode(String)}. Side effects:
     * none; a pure function of its argument and the immutable table, and neither the argument nor the
     * outcome is logged.
     *
     * @param areaCode the candidate area code, exactly {@value #AREA_CODE_WIDTH} characters, compared
     *                 byte for byte with no case folding. Treated as untrusted input. The legacy
     *                 paragraph guarantees three digits and a non-zero value before reaching this test
     *                 by way of its own edits at {@code app/cbl/COACTUPC.cbl:L2266-L2294}, and those
     *                 edits remain the caller's responsibility
     * @return {@code true} when the value is a member of the table, {@code false} otherwise
     * @throws ValidationException if {@code areaCode} is {@code null}, blank, or not exactly
     *                             {@value #AREA_CODE_WIDTH} characters, as described on
     *                             {@link #isValidPhoneAreaCode(String)}
     */
    public boolean isValidGeneralPurposeAreaCode(String areaCode) {
        requireExactWidth(areaCode, AREA_CODE_WIDTH, AREA_CODE_ARGUMENT);
        return validGeneralPurposeAreaCodes.contains(areaCode);
    }

    /**
     * Evaluates {@code 88 VALID-EASY-RECOG-AREA-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L931} on
     * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at {@code :L24}.
     *
     * <p>80 members, disjoint from {@link #isValidGeneralPurposeAreaCode(String)}, and together with it
     * exactly the 490 of {@link #isValidPhoneAreaCode(String)}. Like the full table, this condition name
     * is declared in the copybook but evaluated nowhere in the corpus, and is modelled for the same
     * reason.
     *
     * <p>The 80 members happen to form a regular pattern, but that pattern is <strong>not</strong>
     * computed here: the membership is loaded from the resource, because the source is data and is kept
     * as data. Generating the set in production code would replace an auditable transcription with a
     * rule the copybook never states. The unit tier is the right place to assert the pattern, and it
     * does.
     *
     * <p>Side effects: none; a pure function of its argument and the immutable table, and neither the
     * argument nor the outcome is logged.
     *
     * @param areaCode the candidate area code, exactly {@value #AREA_CODE_WIDTH} characters, compared
     *                 byte for byte with no case folding. Treated as untrusted input
     * @return {@code true} when the value is a member of the table, {@code false} otherwise
     * @throws ValidationException if {@code areaCode} is {@code null}, blank, or not exactly
     *                             {@value #AREA_CODE_WIDTH} characters, as described on
     *                             {@link #isValidPhoneAreaCode(String)}
     */
    public boolean isValidEasilyRecognisableAreaCode(String areaCode) {
        requireExactWidth(areaCode, AREA_CODE_WIDTH, AREA_CODE_ARGUMENT);
        return validEasilyRecognisableAreaCodes.contains(areaCode);
    }

    /**
     * Evaluates {@code 88 VALID-US-STATE-CODE}, declared at {@code app/cpy/CSLKPCDY.cpy:L1013} on
     * {@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} at {@code :L1012}.
     *
     * <p>Replaces {@code app/cbl/COACTUPC.cbl:L2493-L2495}, paragraph {@code 1270-EDIT-US-STATE-CD},
     * which moves {@code ACUP-NEW-CUST-ADDR-STATE-CD} into the parent item and tests this condition
     * name, producing the literal {@code ': is not a valid state code'} on failure. That literal and the
     * {@code FLG-STATE-NOT-OK} flag belong to the caller.
     *
     * <p>56 members in source order: the fifty states ordered alphabetically by full state name, then
     * the District of Columbia, then five territories, so the table ends {@code WY}, {@code DC},
     * {@code AS}, {@code GU}, {@code MP}, {@code PR}, {@code VI}. That composition is commentary; the
     * rule applied here is membership and nothing else.
     *
     * <p>Comparison is byte exact with no case folding, because the {@code 88} level comparison is: a
     * lower-case code is simply not a member. Side effects: none; a pure function of its argument and
     * the immutable table, and neither the argument - a fragment of a customer address - nor the outcome
     * is logged.
     *
     * @param stateCode the candidate state, district or territory code, exactly
     *                  {@value #STATE_CODE_WIDTH} uppercase characters. Treated as untrusted input
     * @return {@code true} when the value is a member of the table; {@code false} for any well-formed
     *         value that is not, without throwing
     * @throws ValidationException if {@code stateCode} is {@code null} or blank, reported as
     *                             {@code FailureKind.BLANK}, or is not exactly
     *                             {@value #STATE_CODE_WIDTH} characters, reported as
     *                             {@code FailureKind.INVALID}. The exception carries the argument name
     *                             and the observed length and never the value
     */
    public boolean isValidUsStateCode(String stateCode) {
        requireExactWidth(stateCode, STATE_CODE_WIDTH, STATE_CODE_ARGUMENT);
        return validUsStateCodes.contains(stateCode);
    }

    /**
     * Evaluates {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, declared at {@code app/cpy/CSLKPCDY.cpy:L1073}
     * on {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at {@code :L1072}.
     *
     * <p>The direct form, taking the already-composed four character key: two characters of state code
     * followed by the first two digits of a zip code. Use
     * {@link #isValidStateAndZipCode(String, String)} to have the key composed the way
     * {@code app/cbl/COACTUPC.cbl:L2537-L2540} composes it.
     *
     * <p>240 members in source order, from {@code AA34} to {@code WY83}, with genuine numeric gaps and
     * one descending run, all preserved exactly as the copybook declares them.
     *
     * <p><strong>The last three digits of a zip code are not part of this key and are never
     * validated.</strong> The condition name sits on the four character subfield alone;
     * {@code 02 LAST-3-OF-ZIP PIC X(3)} at {@code :L1314} carries no condition name anywhere in the
     * corpus. See {@link #LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED} and section 3.5 of the class
     * documentation.
     *
     * <p>Side effects: none; a pure function of its argument and the immutable table, and neither the
     * argument - a fragment of a customer address - nor the outcome is logged.
     *
     * @param stateAndFirstTwoZipDigits the candidate key, exactly {@value #STATE_ZIP_KEY_WIDTH}
     *                                  characters, compared byte for byte with no case folding. Treated
     *                                  as untrusted input
     * @return {@code true} when the value is a member of the table, {@code false} otherwise
     * @throws ValidationException if the argument is {@code null} or blank, reported as
     *                             {@code FailureKind.BLANK}, or is not exactly
     *                             {@value #STATE_ZIP_KEY_WIDTH} characters, reported as
     *                             {@code FailureKind.INVALID}
     */
    public boolean isValidStateZipCodeCombination(String stateAndFirstTwoZipDigits) {
        requireExactWidth(stateAndFirstTwoZipDigits, STATE_ZIP_KEY_WIDTH, STATE_ZIP_KEY_ARGUMENT);
        return validStateZipCodeCombinations.contains(stateAndFirstTwoZipDigits);
    }

    /**
     * Composes the four character state-and-zip key from a state code and a whole zip code, then
     * evaluates {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} against it.
     *
     * <p>Reproduces {@code app/cbl/COACTUPC.cbl:L2536-L2542}, paragraph
     * {@code 1280-EDIT-US-STATE-ZIP-CD}, whose preceding comment reads {@code *A crude zip code edit
     * based on data from USPS web site}:
     *
     * <pre>
     * STRING ACUP-NEW-CUST-ADDR-STATE-CD
     *        ACUP-NEW-CUST-ADDR-ZIP(1:2)
     *   DELIMITED BY SIZE
     *   INTO US-STATE-AND-FIRST-ZIP2
     *
     * IF VALID-US-STATE-ZIP-CD2-COMBO
     * </pre>
     *
     * <p>Composing the key in one place is why this method exists: every caller would otherwise re-derive
     * the {@code (1:2)} slice, and Rule 1 Clause C forbids that duplication. On failure the legacy
     * paragraph produces the literal {@code 'Invalid zip code for state'} and sets both
     * {@code FLG-STATE-NOT-OK} and {@code FLG-ZIPCODE-NOT-OK}; both belong to the caller.
     *
     * <p><strong>Only the first {@value #ZIP_PREFIX_LENGTH} characters of the zip code are used, and the
     * last three are never validated</strong> - see {@link #isValidStateZipCodeCombination(String)}. Any
     * characters beyond the second are read past and ignored, exactly as the source's {@code (1:2)}
     * reference ignores the remainder of its ten character item.
     *
     * <p>Side effects: none; a pure function of its arguments and the immutable table. Neither argument
     * is logged, both being fragments of a customer address.
     *
     * @param stateCode the state, district or territory code, exactly {@value #STATE_CODE_WIDTH}
     *                  characters. Treated as untrusted input. It is <strong>not</strong> separately
     *                  checked against {@link #isValidUsStateCode(String)}: the source does not chain
     *                  the two tests, and six prefixes valid here are deliberately not accepted state
     *                  codes, so chaining them would change which addresses validate
     * @param zipCode   the whole zip code, at least {@value #MINIMUM_ZIP_CODE_LENGTH} characters as the
     *                  screen field and the preceding edit require. Treated as untrusted input
     * @return {@code true} when the composed key is a member of the table, {@code false} otherwise
     * @throws ValidationException if {@code stateCode} is {@code null}, blank or not exactly
     *                             {@value #STATE_CODE_WIDTH} characters, or {@code zipCode} is
     *                             {@code null}, blank or shorter than
     *                             {@value #MINIMUM_ZIP_CODE_LENGTH} characters. The exception carries
     *                             the offending argument's name and its observed length, never its value
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
     * <p>The return type is {@link SequencedSet} rather than {@code Set} on purpose: the order guarantee
     * belongs in the type, so a caller can rely on it and can reach the first and last members directly
     * instead of depending on hash iteration order, which Rule 1 Clause A rules out. The order is the
     * copybook's own: the general-purpose run followed by the easily-recognisable run, so the sequence is
     * deliberately not ascending overall.
     *
     * <p>Side effects: none. The returned view is unmodifiable, its backing collection is unreachable,
     * and every mutator throws {@link UnsupportedOperationException}, so no caller can corrupt the shared
     * table.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidPhoneAreaCodes() {
        return validPhoneAreaCodes;
    }

    /**
     * Returns {@code 88 VALID-GENERAL-PURP-CODE} of {@code app/cpy/CSLKPCDY.cpy:L521} as an
     * unmodifiable, source-ordered set of 410 three character codes.
     *
     * <p>Disjoint from {@link #getValidEasilyRecognisableAreaCodes()}, and together with it exactly
     * {@link #getValidPhoneAreaCodes()}. Ordering, immutability and side-effect guarantees are as
     * documented on {@link #getValidPhoneAreaCodes()}.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidGeneralPurposeAreaCodes() {
        return validGeneralPurposeAreaCodes;
    }

    /**
     * Returns {@code 88 VALID-EASY-RECOG-AREA-CODE} of {@code app/cpy/CSLKPCDY.cpy:L931} as an
     * unmodifiable, source-ordered set of 80 three character codes.
     *
     * <p>Disjoint from {@link #getValidGeneralPurposeAreaCodes()}. Ordering, immutability and
     * side-effect guarantees are as documented on {@link #getValidPhoneAreaCodes()}.
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
     * <p>The order is the copybook's: alphabetical by full state name, then the District of Columbia,
     * then five territories, ending {@code WY}, {@code DC}, {@code AS}, {@code GU}, {@code MP},
     * {@code PR}, {@code VI}. It is <strong>not</strong> alphabetical by code and must not be sorted.
     * Immutability and side-effect guarantees are as documented on {@link #getValidPhoneAreaCodes()}.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidUsStateCodes() {
        return validUsStateCodes;
    }

    /**
     * Returns {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} of {@code app/cpy/CSLKPCDY.cpy:L1073} as an
     * unmodifiable, source-ordered set of 240 four character keys.
     *
     * <p>The order is the copybook's, running from {@code AA34} to {@code WY83} with genuine numeric
     * gaps and one descending run; it must not be sorted, deduplicated or gap-filled. Immutability and
     * side-effect guarantees are as documented on {@link #getValidPhoneAreaCodes()}.
     *
     * <p>Each key is the flat four character source string. It is never split, grouped by state prefix,
     * separated or padded towards a five digit zip code, and the last three digits of a zip code appear
     * nowhere in it - see {@link #LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED}.
     *
     * @return the table, never {@code null} and never empty
     */
    public SequencedSet<String> getValidStateZipCodeCombinations() {
        return validStateZipCodeCombinations;
    }

    /**
     * Asserts that a lookup argument is present and exactly as wide as its source {@code PIC} clause
     * declares.
     *
     * <p>This is the Java stand-in for something COBOL got for free. A legacy caller moved its value
     * into a fixed {@code PIC} item, so the value presented to an {@code 88} level was always exactly
     * the declared width - padded or truncated by the {@code MOVE} itself. Java has no such receiving
     * item, so the width is asserted here instead, which keeps a caller from accidentally probing the
     * table with a value the legacy system could never have produced.
     *
     * <p>A width violation is a contract violation and not a lookup miss, which is why it raises rather
     * than returning {@code false}. A well-formed value that is simply absent from a table is the miss,
     * and the calling predicate returns {@code false} for it.
     *
     * <p><strong>The rejected value is never placed in the message.</strong> Only the argument's name -
     * always a developer-chosen COBOL data item name, never request data - and its observed length
     * appear, because exception messages are logged and an area code, a state code and a zip prefix are
     * all fragments of customer contact data. A length discloses nothing about the characters.
     *
     * @param value         the argument to check; {@code null} and blank are both handled explicitly
     * @param expectedWidth the width declared by the source {@code PIC} clause
     * @param argumentName  the COBOL data item name reported as the failing field
     * @throws ValidationException with {@code FailureKind.BLANK} when {@code value} is {@code null} or
     *                             blank, and with {@code FailureKind.INVALID} when its length is
     *                             anything other than {@code expectedWidth}
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
     * Asserts that a lookup argument is present and at least as long as the shortest value the source
     * would have accepted.
     *
     * <p>Used only for the whole zip code of {@link #isValidStateAndZipCode(String, String)}, where the
     * source item is ten characters wide but the screen field and the preceding edit both fix the
     * meaningful length at {@value #MINIMUM_ZIP_CODE_LENGTH} - see {@link #MINIMUM_ZIP_CODE_LENGTH} for
     * both citations. A minimum rather than an exact width is what lets a caller pass either the five
     * character screen value or the ten character persisted value and get the same answer, exactly as the
     * source's {@code (1:2)} reference does.
     *
     * <p>As with {@link #requireExactWidth(String, int, String)}, the rejected value never appears in
     * the message; only the argument name and the observed length do.
     *
     * @param value         the argument to check; {@code null} and blank are both handled explicitly
     * @param minimumLength the shortest acceptable length
     * @param argumentName  the COBOL data item name reported as the failing field
     * @throws ValidationException with {@code FailureKind.BLANK} when {@code value} is {@code null} or
     *                             blank, and with {@code FailureKind.INVALID} when it is shorter than
     *                             {@code minimumLength}
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
     * <p>Both map onto {@code FailureKind.BLANK}, which is the transcription of
     * {@code FLG-(TESTVAR1)-BLANK} at {@code app/cpy/CSSETATY.cpy:L19}: the claim being made is that the
     * input was not supplied, which is weaker and more accurate than claiming it was wrong. Whitespace
     * is treated as absence because a space-filled item is precisely how the legacy screen represented an
     * empty field.
     *
     * <p>Neither case can be answered with {@code false} without lying: the source never evaluated an
     * {@code 88} level against an unsupplied value, because its own blank edits ran first.
     *
     * @param value        the argument to check
     * @param argumentName the COBOL data item name reported as the failing field
     * @throws ValidationException with {@code FailureKind.BLANK} when {@code value} is {@code null} or
     *                             contains only whitespace
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
     * <p><strong>Purpose.</strong> Reads a named array member out of a classpath JSON document and
     * returns it as an unmodifiable, insertion-ordered set. There is deliberately one implementation
     * rather than three bespoke parsers: Rule 1 Clause C forbids that duplication, and a single reader
     * means a single place where the strictness below is enforced.
     *
     * <p><strong>It is {@code static}.</strong> That is not incidental. The constructor calls it five
     * times, and a {@code static} method cannot leak a partially constructed {@code this}, which keeps
     * the class clear of the {@code this-escape} warning that {@code -Xlint:all -Werror} would otherwise
     * turn into a build failure. It is also a pure function of its arguments, which is what makes the
     * five field assignments in the constructor independent of one another.
     *
     * <p><strong>Binding.</strong> The document is read as UTF-8 explicitly, never at the platform
     * default charset, and parsed into a concrete {@link JsonNode} tree. Only the member named by
     * {@code memberName} is read, which has three consequences worth stating: the {@code _metadata}
     * member each resource carries is documentation and is ignored by construction rather than by an
     * exclusion rule; no caller-named type is ever materialised, so <strong>no polymorphic type handling
     * and no default typing is used, requested or required</strong>, which is the direct answer to Rule 1
     * Clause D's insecure-deserialisation risk; and nothing is bound to {@code Object} or to a raw
     * collection, so there is no unchecked cast for {@code -Werror} to reject.
     *
     * <p><strong>Strictness, and why every check is here.</strong> The load fails on an absent resource,
     * an unreadable resource, malformed JSON, a root that is not an object, an absent or null member, a
     * member that is not an array, an <em>empty</em> array, an element that is not a string, a blank
     * element, an element of the wrong width, and a repeated element. Each of those would otherwise
     * produce a table that is silently wrong, and the empty case is the Blocker of section 5 of the class
     * documentation. Nothing is defaulted, nothing is skipped and nothing is repaired: a resource that
     * disagrees with {@code app/cpy/CSLKPCDY.cpy} is re-derived from the copybook, never patched here.
     *
     * <p><strong>Diagnostics.</strong> Every failure message names the resource path, the table member
     * and the specific defect, and reports an offending element by <em>index</em> rather than by value.
     * Any underlying {@link IOException} is preserved as the cause and never swallowed, which is Rule 1
     * Clause B's wrap-with-context requirement.
     *
     * @param resourceLoader resolves the {@code classpath:} location; only this class's own compile-time
     *                       constants are ever passed to it
     * @param objectMapper   parses the document into a {@link JsonNode} tree; used for {@code readTree}
     *                       and nothing else
     * @param resourcePath   the classpath-relative location, without the {@code classpath:} scheme
     * @param memberName     the JSON member name, which is the {@code 88}-level condition name exactly as
     *                       {@code app/cpy/CSLKPCDY.cpy} spells it
     * @param elementWidth   the width every element must have, taken from the source {@code PIC} clause
     * @return the table as an unmodifiable, insertion-ordered {@link SequencedSet} whose backing
     *         collection is unreachable, never {@code null} and never empty
     * @throws FatalProcessingException on any of the failures listed above, carrying the resource path,
     *                                 the member name, the defect and the preserved cause
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
     *       unchanged, so the widening is contained here and is recorded in {@code DECISION_LOG.md}.</li>
     * </ul>
     *
     * @param resourcePath the classpath-relative resource that could not be turned into a table
     * @param memberName   the table member being loaded when the failure was detected
     * @param detail       what specifically was wrong, phrased so it names no element value
     * @param cause        the underlying failure, or {@code null} when the defect is structural and there
     *                     is no throwable to attribute. A non-null cause is always preserved
     * @return the exception to throw, never {@code null}
     */
    private static FatalProcessingException cannotLoad(String resourcePath, String memberName,
            String detail, Throwable cause) {
        String message = "Cannot load CSLKPCDY lookup table " + memberName + " from classpath resource "
                + resourcePath + ": " + detail;
        return new FatalProcessingException(null, ABEND_CULPRIT, ABEND_REASON, message, cause);
    }
}
