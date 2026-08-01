/*
 * ******************************************************************
 * Program     : ReportRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Report-submission request; three periods plus six custom date components in source order.
 * Source      : app/cpy-bms/CORPT00.CPY (17 fields) @ 7756d89
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
package com.cardemo.model.dto;

import jakarta.validation.constraints.Size;

/**
 * Inbound request payload for transaction-report submission: the stateless Java replacement for the
 * BMS conversation of CICS transaction {@code CR00}, program {@code app/cbl/CORPT00C.cbl}.
 *
 * <p><strong>Purpose.</strong> This type is a pure, immutable data holder. It transports the screen
 * fields of the report-submission map from an HTTP client to {@code ReportSubmissionService} and
 * nothing more. It performs no date assembly, no date validation, no range computation, no period
 * derivation, no queue-message construction and no mapping; all of that behaviour belongs to the
 * service, in keeping with the separation-of-concerns requirement.</p>
 *
 * <h2>Field contract: exactly 17 fields</h2>
 *
 * <p>Every component name, type and width derives from the BMS symbolic map
 * {@code app/cpy-bms/CORPT00.CPY}. Its input group declares <strong>exactly 17 fields</strong>,
 * counted directly as the {@code 02 nnnI PIC} entries that precede the
 * {@code 01 CORPT0AO REDEFINES} group at line 121. Each screen field is generated in the map as a
 * quintuple - a length halfword, an attribute byte, a redefined attribute alias, four reserved
 * bytes and the data field - and only the trailing data field carries a value, which is why the
 * quintuple collapses to a single component here.</p>
 *
 * <p>The 17 components are declared below in that same source order. The order is load-bearing, not
 * cosmetic; see the next section.</p>
 *
 * <h2>The six custom date components are declared MM, DD, YYYY - and that IS the validation order</h2>
 *
 * <p>Components 10 to 15 decompose the two custom-range dates into <strong>six discrete screen
 * fields</strong>, and the order within each date is <strong>month, then day, then year</strong> -
 * emphatically <em>not</em> year-month-day:</p>
 *
 * <ul>
 *   <li>{@code SDTMMI}, {@code SDTDDI}, {@code SDTYYYYI} at lines 78, 84 and 90</li>
 *   <li>{@code EDTMMI}, {@code EDTDDI}, {@code EDTYYYYI} at lines 96, 102 and 108</li>
 * </ul>
 *
 * <p>This is not an incidental layout detail. {@code app/cbl/CORPT00C.cbl:259-300} tests the six
 * components for emptiness inside a single {@code EVALUATE TRUE} whose branches appear in exactly
 * that order, so only the <em>first</em> failing component is reported, each with its own distinct
 * literal - {@code 'Start Date - Month can NOT be empty...'}, then {@code '...Day...'}, then
 * {@code '...Year...'}, then the three matching end-date literals. Preserving the declaration order
 * is therefore what makes the observable message order reproducible. Reordering these six
 * components to year-month-day would silently change which message a caller sees first.</p>
 *
 * <p><strong>They must not be collapsed</strong> into two date objects, two string dates or a range
 * type. The source validates each component individually - emptiness at
 * {@code app/cbl/CORPT00C.cbl:259-300}, then a currency-tolerant numeric conversion at
 * {@code :305-327}, then per-component range tests at {@code :329-379} where the month is rejected
 * when it is not numeric or is greater than {@code '12'} and the day when it is not numeric or is
 * greater than {@code '31'} - and only afterwards assembles. A collapsed representation makes
 * per-component validation and per-component error marking impossible to express.</p>
 *
 * <p>Assembly is a service concern. {@code app/cbl/CORPT00C.cbl:60-71} declares the two working
 * dates as groups of a four-character year, a literal {@code '-'}, a two-character month, a second
 * literal {@code '-'} and a two-character day, so the assembled form is dash-separated
 * year-month-day even though the screen fields are declared month, day, year. That asymmetry is the
 * whole reason this type carries components while the service assembles them; the moves that
 * perform the assembly are at {@code :381-386}.</p>
 *
 * <h2>Three report periods</h2>
 *
 * <p>The service selects a period from the three one-character selectors. The source evaluates them
 * in a single {@code EVALUATE TRUE} at {@code app/cbl/CORPT00C.cbl:213}, {@code :239} and
 * {@code :256}, each tested for being neither spaces nor low-values:</p>
 *
 * <ul>
 *   <li><strong>Monthly</strong> ({@code app/cbl/CORPT00C.cbl:213-238}) - the start date is the
 *       current year and month with day {@code '01'}. The end date is then produced by in-place
 *       arithmetic at {@code :223-230}: the day is set to 1, the month is advanced by one, the year
 *       rolls when the month passes 12, and one day is subtracted from the resulting packed
 *       year-month-day value. Because {@code app/cpy/CSDAT01Y.cpy:20-23} declares that value as a
 *       redefinition of the same year, month and day subfields, the moves at {@code :232-234} emit
 *       the <strong>last day of the current month</strong>. The monthly period is therefore a full
 *       calendar month, first day through last day. See <em>Findings</em> below: the plan prose
 *       describes this as month-to-date, which the source does not support.</li>
 *   <li><strong>Yearly</strong> ({@code app/cbl/CORPT00C.cbl:239-255}) - the first through the last
 *       day of the current year, built from the literals {@code '01'}/{@code '01'} and
 *       {@code '12'}/{@code '31'}.</li>
 *   <li><strong>Custom</strong> ({@code app/cbl/CORPT00C.cbl:256} and {@code :381-410}) - the six
 *       discrete components described above.</li>
 * </ul>
 *
 * <p>Deriving the monthly or yearly period must not consult the platform default time zone. The
 * boundary of a period is an observable output, so resolving "today" against an ambient zone would
 * make it environment-dependent and non-reproducible. The service resolves the current date against
 * an explicit, configured zone.</p>
 *
 * <h2>Date-validation contract</h2>
 *
 * <p>Each assembled composite is validated against an explicit format literal,
 * {@code 'YYYY-MM-DD'}, declared at {@code app/cbl/CORPT00C.cbl:72}. The utility parameter block at
 * {@code app/cbl/CORPT00C.cbl:129-135} is <strong>a date, a format, and a result group of a
 * severity code, a filler and a message number</strong> - widths of 10, 10, then 4, 11 and 4 - and
 * that triple is exactly the linkage of {@code app/cbl/CSUTLDTC.cbl}, whose procedure division
 * receives a ten-character date, a ten-character format and an eighty-character result. That result
 * shape is what the Java date-validation service returns; <strong>this type models none of
 * it</strong>. The caller treats a severity code of {@code '0000'} as valid and, per
 * {@code app/cbl/CORPT00C.cbl:396-406}, tolerates one specific message number rather than failing
 * on every non-zero severity.</p>
 *
 * <h2>The three selectors are three independent one-character fields</h2>
 *
 * <p>They are deliberately <strong>not</strong> a single enum, not a single report-type field and
 * <strong>not</strong> booleans:</p>
 *
 * <ul>
 *   <li>The map declares three independent {@code X(1)} fields, so three is the field contract.</li>
 *   <li>The source can receive <em>more than one</em> selector at once. Because the branches are
 *       ordered, monthly wins over yearly and yearly over custom, and the extra selections are
 *       carried without complaint. A single enum cannot represent "two selected simultaneously",
 *       so it would have to reject or silently discard input the source accepts.</li>
 *   <li>None-selected is its own condition, reached by the {@code WHEN OTHER} branch at
 *       {@code app/cbl/CORPT00C.cbl:437-442} with the literal
 *       {@code 'Select a report type to print report...'}.</li>
 *   <li>Each selector is {@code X(1)} with blank, selected and invalid states. A {@code boolean}
 *       collapses three states into two.</li>
 * </ul>
 *
 * <p>Consequently nothing from the enum package is imported here.</p>
 *
 * <h2>Confirmation is four-state, and the invalid path echoes the value</h2>
 *
 * <p>{@code CONFIRMI} at line 114 drives a handshake with four distinct outcomes, each with its own
 * message and cursor behaviour, verified at {@code app/cbl/CORPT00C.cbl:464-494}:</p>
 *
 * <ul>
 *   <li>blank - spaces or low-values at {@code :464} - prompts for confirmation and positions the
 *       cursor on the confirmation field;</li>
 *   <li>affirmative - {@code 'Y'} or {@code 'y'} at {@code :478} - proceeds to submission;</li>
 *   <li>negative - {@code 'N'} or {@code 'n'} at {@code :480} - clears the screen fields and
 *       re-displays;</li>
 *   <li>invalid - any other character at {@code :484-490} - builds a message that
 *       <strong>quotes the offending character back to the caller</strong>.</li>
 * </ul>
 *
 * <p>It is therefore modelled as a raw one-character string. A {@code boolean} would collapse four
 * states into two, and a {@code Boolean} would collapse four into three while silently mapping an
 * invalid character to {@code null}. Because the invalid path echoes the submitted character, and
 * because the affirmative and negative branches match both letter cases explicitly, the value must
 * survive transport <strong>byte-identically</strong>: no case folding, no trimming, no coercion.</p>
 *
 * <p>Echoing a submitted value is safe <em>here specifically</em> because a confirmation character
 * is neither personal data nor a credential. It is not a general licence to echo input; validation
 * messages elsewhere must never quote a personal-data or credential value.</p>
 *
 * <h2>What this type deliberately does not carry: the batch queue boundary</h2>
 *
 * <p>The source submits the report by writing an entire job deck to a transient data queue. The
 * deck is a run of eighty-byte literal constants at {@code app/cbl/CORPT00C.cbl:80-127}, redefined
 * as an array of card images; the two date values are injected through named subfields sitting
 * inside filler groups whose widths are chosen so that <strong>each card totals exactly eighty
 * bytes</strong> - 18 plus 10 plus 52, 16 plus 10 plus 54, and 10 plus 1 plus 10 plus 59.</p>
 *
 * <p>The submission loop at {@code app/cbl/CORPT00C.cbl:498-508} walks that array, raises its
 * termination flag when a card is the terminating marker or is blank or low-values, and then writes
 * the card, so <strong>the terminating card is written before the loop exits</strong>. The write
 * paragraph at {@code app/cbl/CORPT00C.cbl:515-537} is named {@code WIRTE-JOBSUB-TDQ}; that
 * misspelling is in the source and is preserved verbatim in citations. It writes a fixed
 * eighty-byte record, the width of the queue definition itself.</p>
 *
 * <p>In the target those card images collapse into <strong>a single typed queue message carrying
 * the report name and the two dates</strong>, published to a FIFO queue whose record contract
 * remains eighty bytes and fixed-format. <strong>This type carries no job-deck field, no card
 * array, no message field and no queue metadata</strong>; the service builds the message. Note also
 * that the source injects dates into control text by substitution, whereas the target emits a typed
 * message - so no injection surface exists here, and none may be reintroduced by building control
 * text from strings anywhere downstream.</p>
 *
 * <p>When publication fails, the source reports the exact literal
 * {@code 'Unable to Write TDQ (JOBS)...'} ({@code app/cbl/CORPT00C.cbl:531-532}). That string is
 * part of the observable contract, and {@code errorMessage} is merely the field that transports
 * it.</p>
 *
 * <h2>Every date component is text, never a temporal type</h2>
 *
 * <p>All six components, and the header date and time fields, are {@code String}. No
 * {@code java.time} type is imported or declared anywhere in this file. Three independent reasons,
 * all package-wide:</p>
 *
 * <ul>
 *   <li>generated timestamps always end in four zero digits, a rendering a temporal type would not
 *       reproduce;</li>
 *   <li>batch expiry validation compares the first ten characters of a timestamp as a
 *       <em>string</em>, not as an instant;</li>
 *   <li>the statement projection delivers a processing timestamp with only 24 significant
 *       characters in a 26-byte field, which is not a parseable timestamp at all.</li>
 * </ul>
 *
 * <p>Parsing at the boundary would also destroy the blank and invalid states that the source
 * reports on distinctly.</p>
 *
 * <h2>Absent, blank and marked are three distinct states</h2>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is a parameterised {@code COPY ... REPLACING} procedure-division
 * template, not a data layout, so it has no class of its own; it maps onto bean validation plus
 * per-field error markers. Its body is:</p>
 *
 * <pre>
 * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 * </pre>
 *
 * <p>The model is <strong>OK / NOT-OK / BLANK</strong>. BLANK is a state distinct from NOT-OK and
 * additionally stamps an asterisk into the field, and markers fire only on re-entry, never on first
 * display. This program corroborates the distinction at message level: a component that is empty
 * yields {@code '... can NOT be empty...'} ({@code :259-300}) whereas a component that is present
 * but wrong yields {@code '... Not a valid ...'} ({@code :329-379}) - two different literals for the
 * same field.</p>
 *
 * <p>Therefore {@code null}, the empty string and a marked value are <strong>three distinct
 * states</strong>, and this type never coerces between them. In particular, coercing a blank month
 * component to {@code "00"} or to zero would submit an invalid date where the source would have
 * reported a blank one - a different message and a different marker.</p>
 *
 * <h2>Header fields are declared inline, deliberately</h2>
 *
 * <p>The six recurring header fields are declared inline here and are <strong>not</strong> factored
 * into a shared helper, base class, interface or mixin. The reason is factual rather than stylistic:
 * {@code CURTIMEI} is {@code PIC X(8)} in this map at line 54, whereas
 * {@code app/cpy-bms/COSGN00.CPY:54} alone declares it {@code PIC X(9)}. A shared abstraction would
 * therefore assert a width that one map contradicts, making it wrong rather than merely
 * redundant.</p>
 *
 * <h2>Validation policy: match the source, no stricter and no looser</h2>
 *
 * <p>The only constraint applied is a maximum length per component, mirroring each declared width
 * one-for-one. The literal widths are written out rather than hidden behind constants so that the
 * correspondence to each {@code PIC} clause is auditable line by line. Specifically absent, and
 * intentionally so:</p>
 *
 * <ul>
 *   <li><strong>No digit pattern</strong> on the month, day or year components. The source converts
 *       them with a currency-tolerant numeric intrinsic and then validates them through the date
 *       utility, which yields a severity code and a message number - not a regular-expression
 *       verdict. A stricter pattern would reject input the source accepts.</li>
 *   <li><strong>No cross-field constraint asserting that the start date precedes the end date.</strong>
 *       The program contains no such test at all: {@code :259-300} checks emptiness,
 *       {@code :329-379} checks each component's range, and {@code :388-426} validates each
 *       assembled date independently. A class-level assertion would fire in cases the source never
 *       reports. Any range comparison a sibling program performs is gated behind the single-field
 *       flags already being valid, the pattern at {@code app/cbl/COACTUPC.cbl:1667-1672}.</li>
 *   <li><strong>No assertion that exactly one period is selected</strong>, because the source
 *       receives whatever the screen sends and resolves it by branch order.</li>
 *   <li><strong>No non-null requirement</strong> anywhere: the source tolerates spaces and
 *       low-values on every one of these fields, so a required-field constraint would be stricter
 *       than the behaviour being reproduced.</li>
 * </ul>
 *
 * <p>For the same reason the canonical constructor performs no argument checking, no normalisation
 * and no coercion: with every field legitimately blank, any check would be an invented rule.
 * Rejecting unrecognised JSON properties is a deliberate binding decision too, but it belongs to
 * the application's deserialisation configuration rather than to this type, which stays free of
 * serialisation-framework annotations.</p>
 *
 * <h2>Inputs, outputs, side effects and error modes</h2>
 *
 * <ul>
 *   <li><strong>Inputs</strong> - the 17 components below, each optional and each a raw screen
 *       value.</li>
 *   <li><strong>Outputs</strong> - the record accessors, returning exactly what was supplied.</li>
 *   <li><strong>Side effects</strong> - none. The type is immutable, holds no mutable or static
 *       state, opens no resource and touches no clock.</li>
 *   <li><strong>Error modes</strong> - construction cannot fail; no accessor throws. A component
 *       longer than its declared width is reported by bean validation at the request boundary as a
 *       constraint violation naming the field, and never quoting a personal-data or credential
 *       value. Semantic outcomes - a blank component, an out-of-range month or day, an unparseable
 *       composite, an unselected or multiply-selected period, an unconfirmed or invalidly confirmed
 *       submission, and a failed queue publication - are all decided by the service, which reports
 *       them through {@code errorMessage} using the source literals.</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 *
 * <p>This payload carries <strong>no personal data at all</strong> - no card number, name, national
 * identifier, date of birth, telephone number, government identifier or funds-transfer account -
 * and no password, hash, token or signing key, none of which the report screen exposes. It
 * deliberately does not implement serialisation interfaces, avoiding that class of deserialisation
 * risk, and it introduces no dependency beyond the framework-managed validation API.</p>
 *
 * <h2>Build, test and configuration</h2>
 *
 * <p>Compiled by the project Maven build with a release level of 25 and with all lint categories
 * enabled and warnings escalated to errors, so this file must compile warning-free. Its unit tests
 * live under the unit model test tree, not beside it. Two configuration values shape how instances
 * arrive and are checked: the deserialisation strictness that decides whether unrecognised
 * properties are rejected, and the explicit zone the service uses when resolving the current date
 * for the monthly and yearly periods. Common failure modes when working on this type: adding a
 * temporal type or a {@code java.time} import; adding a constraint the source does not perform, of
 * which a start-before-end assertion is the most tempting; collapsing the six date components or the
 * three selectors; normalising a value on ingest, which breaks the echoed confirmation character;
 * and leaving an unused import, which fails the build outright rather than warning.</p>
 *
 * <p>The file header follows the source-banner convention that is universal in the legacy corpus - a
 * rule of asterisks, the component identification lines, a second rule, then the copyright and the
 * Apache License 2.0 notice - evidenced canonically at {@code app/cbl/CBACT04C.cbl:1-21}, with the
 * copyright line reproducing {@code NOTICE} verbatim. Extending an existing convention rather than
 * inventing one is deliberate. No formatter or linter configuration existed in the repository to
 * inherit, so layout follows the {@code .editorconfig} established for the Java tree: UTF-8, LF line
 * endings, four-space indentation, a trailing newline and a 120-column limit.</p>
 *
 * <h2>Findings</h2>
 *
 * <p>Recorded here because the surrounding plan prose and the source disagree, and the source is the
 * authority. None of the three affects the code of this type.</p>
 *
 * <ul>
 *   <li><strong>Medium - monthly period described as month-to-date.</strong> The plan states that
 *       the monthly end date is the current year, month and day. The source computes the last day of
 *       the current month, as traced above through {@code app/cbl/CORPT00C.cbl:223-234} and
 *       {@code app/cpy/CSDAT01Y.cpy:20-23}; the December rollover confirms it, since month 13
 *       becomes January of the following year and one day less is the 31st of December. The prose
 *       error is explainable: the moves at {@code :232-234} name the current-date subfields, which
 *       look like today, but {@code :223-230} has already overwritten them in place. It is also the
 *       reading consistent with the yearly period being a full calendar year. <em>Remediation</em>:
 *       the service must implement the arithmetic at {@code :223-234} rather than the prose
 *       description. No impact here, since this type performs no date arithmetic.</li>
 *   <li><strong>Medium - symbolic-map field census.</strong> The plan prose totals 460 input fields
 *       across the seventeen symbolic maps; counting the input groups directly gives
 *       <strong>441</strong>. The plan's own per-map table sums to 440, because it records one map
 *       as having 36 input fields where that copybook declares 37. This map is unaffected: its count
 *       is 17 both in the table and on disk, which is the figure implemented here.
 *       <em>Remediation</em>: correct the corpus total in the evidence artefacts to 441.</li>
 *   <li><strong>Medium - job-deck card count.</strong> The plan describes eighteen card images;
 *       {@code app/cbl/CORPT00C.cbl:80-127} declares <strong>seventeen</strong> - fourteen plain
 *       eighty-byte literals plus three named multi-part groups, each summing to eighty bytes.
 *       <em>Remediation</em>: correct the count where the deck is described. No impact here, since
 *       this type carries no card array.</li>
 * </ul>
 *
 * @param transactionName {@code TRNNAMEI}, {@code PIC X(4)}, {@code app/cpy-bms/CORPT00.CPY:24} -
 *        the originating transaction identifier echoed in the screen header. Header field, declared
 *        inline; see the header section above.
 * @param title01 {@code TITLE01I}, {@code PIC X(40)}, {@code app/cpy-bms/CORPT00.CPY:30} - the first
 *        header title line. Header field, declared inline.
 * @param currentDate {@code CURDATEI}, {@code PIC X(8)}, {@code app/cpy-bms/CORPT00.CPY:36} - the
 *        header date as displayed text, never a temporal type. Header field, declared inline.
 * @param programName {@code PGMNAMEI}, {@code PIC X(8)}, {@code app/cpy-bms/CORPT00.CPY:42} - the
 *        originating program name echoed in the header. Header field, declared inline.
 * @param title02 {@code TITLE02I}, {@code PIC X(40)}, {@code app/cpy-bms/CORPT00.CPY:48} - the
 *        second header title line. Header field, declared inline.
 * @param currentTime {@code CURTIMEI}, {@code PIC X(8)}, {@code app/cpy-bms/CORPT00.CPY:54} - the
 *        header time as displayed text. This is the width that differs from
 *        {@code app/cpy-bms/COSGN00.CPY:54}, which declares {@code PIC X(9)}, and is the reason the
 *        header fields are not factored into a shared type.
 * @param monthlySelected {@code MONTHLYI}, {@code PIC X(1)}, {@code app/cpy-bms/CORPT00.CPY:60} -
 *        the monthly-period selector, evaluated first at {@code app/cbl/CORPT00C.cbl:213}. An
 *        independent raw character, not a boolean and not an enum constant; blank, selected and
 *        invalid are three distinct states.
 * @param yearlySelected {@code YEARLYI}, {@code PIC X(1)}, {@code app/cpy-bms/CORPT00.CPY:66} - the
 *        yearly-period selector, evaluated second at {@code app/cbl/CORPT00C.cbl:239}.
 * @param customSelected {@code CUSTOMI}, {@code PIC X(1)}, {@code app/cpy-bms/CORPT00.CPY:72} - the
 *        custom-range selector, evaluated third at {@code app/cbl/CORPT00C.cbl:256}. When set, the
 *        six components below apply.
 * @param startDateMonth {@code SDTMMI}, {@code PIC X(2)}, {@code app/cpy-bms/CORPT00.CPY:78} - the
 *        start-date month, the <strong>first</strong> custom component in source order and hence the
 *        first emptiness test at {@code app/cbl/CORPT00C.cbl:259-265}. Text; a blank value stays
 *        blank and is never coerced to {@code "00"}.
 * @param startDateDay {@code SDTDDI}, {@code PIC X(2)}, {@code app/cpy-bms/CORPT00.CPY:84} - the
 *        start-date day, the second custom component, tested at {@code app/cbl/CORPT00C.cbl:266-272}.
 * @param startDateYear {@code SDTYYYYI}, {@code PIC X(4)}, {@code app/cpy-bms/CORPT00.CPY:90} - the
 *        start-date year, the third custom component, tested at
 *        {@code app/cbl/CORPT00C.cbl:273-279}.
 * @param endDateMonth {@code EDTMMI}, {@code PIC X(2)}, {@code app/cpy-bms/CORPT00.CPY:96} - the
 *        end-date month, the fourth custom component, tested at
 *        {@code app/cbl/CORPT00C.cbl:280-286}.
 * @param endDateDay {@code EDTDDI}, {@code PIC X(2)}, {@code app/cpy-bms/CORPT00.CPY:102} - the
 *        end-date day, the fifth custom component, tested at {@code app/cbl/CORPT00C.cbl:287-293}.
 * @param endDateYear {@code EDTYYYYI}, {@code PIC X(4)}, {@code app/cpy-bms/CORPT00.CPY:108} - the
 *        end-date year, the sixth and last custom component, tested at
 *        {@code app/cbl/CORPT00C.cbl:294-300}.
 * @param confirmation {@code CONFIRMI}, {@code PIC X(1)}, {@code app/cpy-bms/CORPT00.CPY:114} - the
 *        four-state confirmation character driving the handshake at
 *        {@code app/cbl/CORPT00C.cbl:464-494}. Carried byte-identically, because the invalid branch
 *        quotes it back to the caller.
 * @param errorMessage {@code ERRMSGI}, {@code PIC X(78)}, {@code app/cpy-bms/CORPT00.CPY:120} - the
 *        screen message line, the transport for the source literals including the queue-write
 *        failure text at {@code app/cbl/CORPT00C.cbl:531-532}.
 */
public record ReportRequest(

        // 1. TRNNAMEI   PIC X(4)    app/cpy-bms/CORPT00.CPY:24   header
        @Size(max = 4) String transactionName,

        // 2. TITLE01I   PIC X(40)   app/cpy-bms/CORPT00.CPY:30   header
        @Size(max = 40) String title01,

        // 3. CURDATEI   PIC X(8)    app/cpy-bms/CORPT00.CPY:36   header, text not temporal
        @Size(max = 8) String currentDate,

        // 4. PGMNAMEI   PIC X(8)    app/cpy-bms/CORPT00.CPY:42   header
        @Size(max = 8) String programName,

        // 5. TITLE02I   PIC X(40)   app/cpy-bms/CORPT00.CPY:48   header
        @Size(max = 40) String title02,

        // 6. CURTIMEI   PIC X(8)    app/cpy-bms/CORPT00.CPY:54   header; X(9) in COSGN00.CPY:54
        @Size(max = 8) String currentTime,

        // 7. MONTHLYI   PIC X(1)    app/cpy-bms/CORPT00.CPY:60   selector 1 of 3, CORPT00C.cbl:213
        @Size(max = 1) String monthlySelected,

        // 8. YEARLYI    PIC X(1)    app/cpy-bms/CORPT00.CPY:66   selector 2 of 3, CORPT00C.cbl:239
        @Size(max = 1) String yearlySelected,

        // 9. CUSTOMI    PIC X(1)    app/cpy-bms/CORPT00.CPY:72   selector 3 of 3, CORPT00C.cbl:256
        @Size(max = 1) String customSelected,

        // ---- Custom range: six discrete components, declared MM, DD, YYYY - start then end. ----
        // This order is the source validation order (CORPT00C.cbl:259-300). Do not reorder,
        // do not merge into date objects, and do not coerce a blank component to "00".

        // 10. SDTMMI    PIC X(2)    app/cpy-bms/CORPT00.CPY:78   start month, CORPT00C.cbl:259-265
        @Size(max = 2) String startDateMonth,

        // 11. SDTDDI    PIC X(2)    app/cpy-bms/CORPT00.CPY:84   start day,   CORPT00C.cbl:266-272
        @Size(max = 2) String startDateDay,

        // 12. SDTYYYYI  PIC X(4)    app/cpy-bms/CORPT00.CPY:90   start year,  CORPT00C.cbl:273-279
        @Size(max = 4) String startDateYear,

        // 13. EDTMMI    PIC X(2)    app/cpy-bms/CORPT00.CPY:96   end month,   CORPT00C.cbl:280-286
        @Size(max = 2) String endDateMonth,

        // 14. EDTDDI    PIC X(2)    app/cpy-bms/CORPT00.CPY:102  end day,     CORPT00C.cbl:287-293
        @Size(max = 2) String endDateDay,

        // 15. EDTYYYYI  PIC X(4)    app/cpy-bms/CORPT00.CPY:108  end year,    CORPT00C.cbl:294-300
        @Size(max = 4) String endDateYear,

        // 16. CONFIRMI  PIC X(1)    app/cpy-bms/CORPT00.CPY:114  four-state, CORPT00C.cbl:464-494
        @Size(max = 1) String confirmation,

        // 17. ERRMSGI   PIC X(78)   app/cpy-bms/CORPT00.CPY:120  screen message transport
        @Size(max = 78) String errorMessage) {
}
