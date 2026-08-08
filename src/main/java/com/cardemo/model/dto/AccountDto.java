/*
 * ******************************************************************
 * Program     : AccountDto.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Account-view response payload; account and customer fields
 *               interleaved as in the source.
 * Source      : app/cpy-bms/COACTVW.CPY (37 fields) @ 7756d89
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * Immutable account-view response payload: the Java replacement for the CICS {@code EXEC CICS SEND MAP}
 * conversation of screen {@code CACTVWA}, transaction {@code CAVW}.
 *
 * <p>This record transports, verbatim, the 37 screen fields that {@code COACTVWC} populates for the
 * account-view screen. It is an outbound payload only: the REST layer serialises it to JSON in place of writing
 * a 3270 map. Every component is declared in the exact order the symbolic map declares it, because field order
 * is part of the migrated contract and not an implementation detail.
 *
 * <p><strong>Provenance.</strong> Field names, types and lengths are taken from the generated
 * symbolic map {@code app/cpy-bms/COACTVW.CPY}, group {@code 01 CACTVWAI.} (line 17) up to
 * {@code 01 CACTVWAO REDEFINES CACTVWAI.} (line 241). The values themselves are assembled by
 * paragraph {@code 1200-SETUP-SCREEN-VARS} of {@code app/cbl/COACTVWC.cbl} (line 460), drawing
 * on the account layout {@code app/cpy/CVACT01Y.cpy} and the customer layout
 * {@code app/cpy/CVCUS01Y.cpy}. Each component's Javadoc below cites its COBOL field, its PIC
 * clause and its line number, so the mapping is verifiable by inspection rather than by
 * assertion.</p>
 *
 * <p><strong>Verified field census, and a superseded prior figure (severity: Medium, closed).</strong>
 * Prior-generation plan prose stated that {@code COACTVW} carries 36 input fields. That figure is
 * wrong. A count of the {@code 02 <name>I PIC} declarations lying strictly inside the
 * {@code 01 CACTVWAI.} group yields <strong>37</strong>, and all 37 are declared here. The corpus-wide
 * census is likewise 441 input fields across the seventeen symbolic maps, not the 460 the prior prose
 * stated. This class implements the verified count of 37, exposed as {@link #FIELD_COUNT} so the figure
 * is machine-checkable. Nothing remains outstanding against the specification:
 * {@code docs/technical-specifications.md} publishes 37 for this map and 441 corpus-wide, and lists both
 * supersessions in its section 0.2.2.1 corrections table, verified on 1 August 2026. The repository-root
 * decision log the plan nominates for cross-cutting findings is <strong>not available</strong> and has
 * not been authored, so that corrections table is the register of record. Impact is confined to
 * documentation accuracy, hence Medium rather than High: no runtime behaviour depends on the figure.</p>
 *
 * <p><strong>Distinction: {@code ACCTSIDI} is numeric on this map and on no other.</strong>
 * {@code app/cpy-bms/COACTVW.CPY:60} declares {@code 02 ACCTSIDI PIC 99999999999} - an
 * eleven-digit numeric picture. Every other map declares the same screen field as alphanumeric
 * {@code PIC X(11)}: {@code app/cpy-bms/COACTUP.CPY:60},
 * {@code app/cpy-bms/COCRDLI.CPY:66}, {@code app/cpy-bms/COCRDSL.CPY:60} and
 * {@code app/cpy-bms/COCRDUP.CPY:60}. This map is the sole numeric declaration in the corpus.
 * The divergence is recorded rather than unified, and neither source is corrected.</p>
 *
 * <p><strong>Resolution, and why a numeric Java type would be wrong.</strong> The
 * {@link #accountId()} component is a {@code String} of up to 11 characters. Three independent
 * facts make that the only faithful mapping:</p>
 * <ul>
 *   <li>The underlying key {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:5} is
 *       zero-padded in the seeded data - the first account record is {@code 00000000001} - and
 *       a numeric type would strip the padding, breaking byte-exact comparison against the
 *       parity baseline.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl:466} moves {@code LOW-VALUES} into that field when the
 *       account filter is blank. No numeric Java type can hold binary zeros.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl:563} moves the literal {@code '*'} into that field when the
 *       filter is blank and the program is re-entered. No numeric Java type can hold an
 *       asterisk. The declared numeric picture is therefore contradicted by the program's own
 *       writes, which settles the question from source rather than by preference.</li>
 *   </ul>
 *
 * <p><strong>No shared header helper exists, deliberately.</strong> Six header fields recur on
 * all seventeen maps, but not identically. {@code CURTIMEI} is {@code PIC X(8)} here
 * ({@code app/cpy-bms/COACTVW.CPY:54}) and on fifteen further maps, whereas
 * {@code app/cpy-bms/COSGN00.CPY:54} declares {@code PIC X(9)} - the sole nine-byte outlier.
 * Extracting the six recurring fields into a base class, interface or mixin would therefore
 * encode a width that is factually wrong for one map, so all six are declared inline here. The
 * header values are supplied by {@code CCDA-TITLE01} and {@code CCDA-TITLE02}
 * ({@code app/cbl/COACTVWC.cbl:436} and line 437), {@code LIT-THISTRANID} and
 * {@code LIT-THISPGM} (lines 438 and 439), and the {@code mm/dd/yy} and {@code hh:mm:ss}
 * renderings {@code WS-CURDATE-MM-DD-YY} and {@code WS-CURTIME-HH-MM-SS} of
 * {@code app/cpy/CSDAT01Y.cpy} (lines 30 and 36, applied at
 * {@code app/cbl/COACTVWC.cbl:447} and line 453).</p>
 *
 * <p><strong>No shared balance helper exists, deliberately.</strong> {@code ACURBALI} is
 * {@code PIC X(15)} here ({@code app/cpy-bms/COACTVW.CPY:102}) and at
 * {@code app/cpy-bms/COACTUP.CPY:138}, but the conceptually similar current balance on the
 * bill-payment map is a differently named and differently sized field,
 * {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66}. Different name and
 * different width means a shared abstraction would be wrong, not merely redundant. The money
 * conversion offered by {@link #toAmount(String, String)} is scoped to this map's 15-byte
 * width for exactly that reason and must not be promoted to a cross-map utility.</p>
 *
 * <p><strong>Three ordering quirks are preserved, not tidied.</strong> The symbolic map
 * interleaves account and customer fields, and interleaves limits with dates. Three orderings
 * in particular look like mistakes and are not:</p>
 * <ul>
 *   <li>the state code sits <em>between</em> address line 1 and address line 2
 *       ({@code ACSADL1I}, {@code ACSSTTEI}, {@code ACSADL2I} at lines 168, 174 and 180);</li>
 *   <li>the city comes <em>after</em> the ZIP ({@code ACSZIPCI} at line 186 then
 *       {@code ACSCITYI} at line 192);</li>
 *   <li>the government-issued identifier is interleaved <em>between</em> the two phone numbers
 *       ({@code ACSPHN1I} at line 204, {@code ACSGOVTI} at line 210, {@code ACSPHN2I} at
 *       line 216).</li>
 *   </ul>
 *
 * <p><strong>Legacy narrowing is preserved, never widened.</strong> Three fields are narrower
 * on the map than in the record they are copied from, so the legacy screen silently truncates.
 * The truncated width is the contract this payload carries:</p>
 * <ul>
 *   <li>{@code CUST-ADDR-ZIP PIC X(10)} moves into {@code ACSZIPCI PIC X(5)}
 *       ({@code app/cbl/COACTVWC.cbl:515}) - five bytes are dropped;</li>
 *   <li>{@code CUST-PHONE-NUM-1 PIC X(15)} moves into {@code ACSPHN1I PIC X(13)}
 *       ({@code app/cbl/COACTVWC.cbl:517}) - two bytes are dropped;</li>
 *   <li>{@code CUST-PHONE-NUM-2 PIC X(15)} moves into {@code ACSPHN2I PIC X(13)}
 *       ({@code app/cbl/COACTVWC.cbl:518}) - two bytes are dropped.</li>
 *   </ul>
 *
 * <p>The width enforcement described below therefore uses the <em>map</em> width for all three, not
 * the record width. Accepting ten characters of postal code or fifteen of telephone number would
 * accept a value this screen never displayed, and the payload is compared against what the screen
 * produced. Truncation is not performed here either: an over-width value is rejected rather than cut
 * down, because the caller assembling this payload is the one that must apply the legacy move.</p>
 *
 * <p>Two further transformations are worth stating because the component values are not raw
 * record contents. The social security number is re-formatted, not copied: a raw
 * {@code MOVE CUST-SSN} is commented out at {@code app/cbl/COACTVWC.cbl:495} and replaced by a
 * {@code STRING} of {@code CUST-SSN(1:3)}, a dash, {@code CUST-SSN(4:2)}, a dash and
 * {@code CUST-SSN(6:4)} ({@code app/cbl/COACTVWC.cbl:496} to line 504), producing eleven
 * characters in a twelve-byte field from an underlying {@code PIC 9(09)}. And the field this
 * map calls the city is in fact address line 3: {@code MOVE CUST-ADDR-LINE-3 TO ACSCITYO}
 * at {@code app/cbl/COACTVWC.cbl:513}.</p>
 *
 * <p><strong>Money representation contract.</strong> The five monetary components are
 * {@code PIC X(15)} edited display representations, not raw numerics; the underlying amounts
 * are {@code PIC S9(10)V99} in {@code app/cpy/CVACT01Y.cpy} and map to {@code NUMERIC(12,2)}
 * in the relational target. This record carries the display text exactly as the screen showed
 * it. It performs no conversion on ingest: no parsing, no formatting, no
 * {@code trim}, no case folding. Where an arithmetic value is required the caller obtains it
 * through {@link #toAmount(String, String)}, which yields {@code java.math.BigDecimal} at
 * scale {@value #MONEY_SCALE} with {@code RoundingMode.HALF_EVEN} and a precision ceiling of
 * {@value #MONEY_PRECISION}. There is no {@code float} and no {@code double} anywhere in this
 * file, in any component or any signature; the security gate asserts that property. Two amounts
 * must be compared with {@code BigDecimal.compareTo}, never with
 * {@code BigDecimal.equals}, because {@code equals} distinguishes scale.</p>
 *
 * <p>The same caution applies to this record's own generated {@code equals} and
 * {@code hashCode}: they compare the transported text, so the display strings {@code 1.50} and
 * {@code 1.5} are unequal as payloads even though they denote the same amount. Text equality is
 * a transport-level check and is not a monetary comparison.</p>
 *
 * <p><strong>Not available: the legacy byte rendering of a monetary field.</strong>
 * {@code app/cbl/COACTVWC.cbl:475} moves a {@code PIC S9(10)V99} field into a {@code PIC X(15)}
 * one, and the byte image that alphanumeric move produces - the justification within the field,
 * the padding character, and whether a decimal point or an overpunch sign appears at all - is
 * fixed by the compiler rather than by anything in the corpus, and no artefact in this
 * repository records it. This class therefore does not attempt the reverse direction: it offers
 * no display formatter, because any justification or padding convention it chose would be
 * invented rather than derived, and inventing one would place a fabricated value in front of a
 * parity comparison. Rendering an amount into this payload is the service layer's
 * responsibility, working from the entity. Closing the gap would need either a captured sample
 * of the legacy screen output or the compiler's documented behaviour for a
 * numeric-to-alphanumeric move; neither is present.</p>
 *
 * <p><strong>Date representation contract.</strong> The three account dates and the customer
 * date of birth are {@code PIC X(10)} dash-separated text and are carried as {@code String},
 * never as {@code LocalDate}, {@code Date} or {@code Instant}. The reason is an offset
 * asymmetry in the source that a date type would normalise away: the live account and customer
 * records hold dash-separated {@code X(10)} values whose year, month and day components sit at
 * offsets 1, 6 and 9, while the account-update snapshot holds the same logical dates in a
 * compact {@code X(08)} form with components at offsets 1, 5 and 7. Text is the only
 * representation that round-trips both forms unchanged. Timestamps are text package-wide for
 * the same reason: {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} with
 * three mutually incompatible producers.</p>
 *
 * <p><strong>Absent, blank and low-values are three distinct states.</strong> A {@code null}
 * component means the screen field was never populated; an all-blank component means the screen
 * showed spaces; a component of binary zeros means the map carried {@code LOW-VALUES}, which
 * {@code app/cbl/COACTVWC.cbl:466} writes deliberately. This record never coerces one state
 * into another - {@code null} is not replaced by an empty string - because the distinction is
 * observable on the legacy screen. Only {@link #toAmount(String, String)} collapses all three,
 * and only for the narrow purpose of arithmetic, where each of them means "no amount".</p>
 *
 * <p><strong>Personally identifiable information: never-emit posture.</strong> This payload
 * carries six groups of protected data - the social security number (line 132), the date of
 * birth (line 138), both telephone numbers (lines 204 and 216), the government-issued
 * identifier (line 210), the electronic funds transfer account identifier (line 222) and the
 * three customer name fields (lines 150, 156 and 162). {@link #toString()} is overridden
 * precisely so that none of them can reach a log: a record's generated {@code toString} would
 * emit every component, so relying on the default would leak all six groups on the first
 * logged payload. The override emits only the account identifier, the account status and the
 * program name. No validation or exception message produced by this class ever quotes a field
 * value; messages name the field only. Never emitting the values is the <em>primary</em> defence
 * this payload has, and {@code src/main/resources/logback-spring.xml} stands behind it as the second,
 * with a masking decorator applied identically in every profile. The override is still load-bearing
 * rather than belt-and-braces, because a mask recognises only the field names and value shapes it was
 * given and this override decides which of them are ever presented to it, which is why the omission
 * is asserted by test rather than left to review. Consistent
 * with least privilege, this payload carries no password and no password hash - the account-view
 * map declares no such field - and it must not acquire one.</p>
 *
 * <p><strong>Width enforcement (severity of the gap it closes: High).</strong> Every component
 * documents the PIC clause it derives from, but for a time none of those widths was enforced: the
 * record's implicit constructor accepted any string, so an instance could carry an
 * {@code addressStateCode} of seven characters or an {@code errorMessage} of two hundred and still
 * serialise cleanly, describing a screen state {@code app/cpy-bms/COACTVW.CPY} cannot represent.
 * Since the symbolic map is the migrated contract, all 37 widths are now checked in the canonical
 * constructor, which is the single point every instance passes through. The check rejects and never
 * repairs: no trimming, no padding, no case folding and no coercion, because each of those would
 * alter a value the parity comparison reads byte for byte. Absence remains legitimate - see the
 * three-state paragraph above - so {@code null}, empty, blank and {@code LOW-VALUES} all pass, and
 * only over-width fails, because only over-width is impossible on the source screen.</p>
 *
 * <p><strong>Error modes.</strong> Two failure surfaces exist, both throwing
 * {@code IllegalArgumentException} and neither ever quoting a field value. Construction fails when a
 * component exceeds the width its screen field declares; the message names the component and quotes
 * the COBOL field, PIC clause and source locator so the width can be verified without leaving the
 * stack trace. {@link #toAmount(String, String)} fails when the supplied text cannot denote an
 * account amount, and preserves the underlying {@code NumberFormatException} as the cause so the
 * root cause is not swallowed. Nothing else in this class throws. This class deliberately does not
 * implement {@code Serializable}: it needs no Java-native serialisation, and given the protected
 * data it carries, exposing it to native deserialisation would add risk for no benefit.</p>
 *
 * <p><strong>Configuration, build and troubleshooting.</strong> This record has no
 * configuration of its own and no defaults to tune; it holds no state beyond its components and
 * declares no mutable static field. It is serialised as JSON by the Jackson instance the web
 * starter contributes, which binds record components by name, so the JSON property names are
 * the component names below. Build and test it with the project build: {@code ./mvnw -B compile}
 * compiles it under {@code -Xlint:all -Werror} with warnings failing the build, and
 * {@code ./mvnw -B test} runs the unit suite. Two failure modes are worth knowing. First, if a
 * caller sees a monetary comparison behave unexpectedly, check whether it is comparing display
 * text rather than the value returned by {@link #toAmount(String, String)}; text and amount
 * equality are not the same relation. Second, if a date appears to lose its separators, the
 * cause is a conversion to a date type somewhere downstream, not this payload, which preserves
 * the dash-separated form byte for byte.</p>
 *
 * @param transactionName the screen header transaction identifier. {@code TRNNAMEI PIC X(4)} at
 *                        {@code app/cpy-bms/COACTVW.CPY:24}, populated from {@code LIT-THISTRANID} at
 *                        {@code app/cbl/COACTVWC.cbl:438}. May be {@code null}; rejected if wider than the declaration
 *                        cited.
 * @param title01 the first screen title line. {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COACTVW.CPY:30},
 *                populated from {@code CCDA-TITLE01} at {@code app/cbl/COACTVWC.cbl:436}. May be {@code null}; rejected
 *                if wider than the declaration cited.
 * @param currentDate the header date rendered {@code mm/dd/yy}. {@code CURDATEI PIC X(8)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:36}, populated from {@code WS-CURDATE-MM-DD-YY} at
 *                    {@code app/cbl/COACTVWC.cbl:447}. May be {@code null}; rejected if wider than the declaration
 *                    cited.
 * @param programName the screen header program name. {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COACTVW.CPY:42},
 *                    populated from {@code LIT-THISPGM} at {@code app/cbl/COACTVWC.cbl:439}. May be {@code null};
 *                    rejected if wider than the declaration cited.
 * @param title02 the second screen title line. {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COACTVW.CPY:48},
 *                populated from {@code CCDA-TITLE02} at {@code app/cbl/COACTVWC.cbl:437}. May be {@code null}; rejected
 *                if wider than the declaration cited.
 * @param currentTime the header time rendered {@code hh:mm:ss}. {@code CURTIMEI PIC X(8)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:54} - eight bytes on this map, nine at
 *                    {@code app/cpy-bms/COSGN00.CPY:54} - populated from {@code WS-CURTIME-HH-MM-SS} at
 *                    {@code app/cbl/COACTVWC.cbl:453}. May be {@code null}; rejected if wider than the declaration
 *                    cited.
 * @param accountId the eleven-character account identifier, zero-padded and carried as text so that leading zeros,
 *                  {@code LOW-VALUES} and the {@code '*'} marker all survive. {@code ACCTSIDI PIC 99999999999} at
 *                  {@code app/cpy-bms/COACTVW.CPY:60} - the corpus's sole numeric declaration of this field - keyed on
 *                  {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:5}. May be {@code null}; rejected if wider
 *                  than the declaration cited.
 * @param accountStatus the single-character active status. {@code ACSTTUSI PIC X(1)} at
 *                      {@code app/cpy-bms/COACTVW.CPY:66}, from {@code ACCT-ACTIVE-STATUS} at
 *                      {@code app/cbl/COACTVWC.cbl:473}. May be {@code null}; rejected if wider than the declaration
 *                      cited.
 * @param openDate the account open date as dash-separated text. {@code ADTOPENI PIC X(10)} at
 *                 {@code app/cpy-bms/COACTVW.CPY:72}, from {@code ACCT-OPEN-DATE} at {@code app/cbl/COACTVWC.cbl:487}.
 *                 May be {@code null}; rejected if wider than the declaration cited.
 * @param creditLimit the credit limit as display text. {@code ACRDLIMI PIC X(15)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:78}, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
 *                    {@code app/cbl/COACTVWC.cbl:477}. May be {@code null}; rejected if wider than the declaration
 *                    cited.
 * @param expiryDate the account expiry date as dash-separated text. {@code AEXPDTI PIC X(10)} at
 *                   {@code app/cpy-bms/COACTVW.CPY:84}, from {@code ACCT-EXPIRAION-DATE} at
 *                   {@code app/cbl/COACTVWC.cbl:488}; the misspelling of that copybook field is part of the field
 *                   contract and is not corrected. May be {@code null}; rejected if wider than the declaration cited.
 * @param cashCreditLimit the cash credit limit as display text. {@code ACSHLIMI PIC X(15)} at
 *                        {@code app/cpy-bms/COACTVW.CPY:90}, from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
 *                        {@code app/cbl/COACTVWC.cbl:479}. May be {@code null}; rejected if wider than the declaration
 *                        cited.
 * @param reissueDate the card reissue date as dash-separated text. {@code AREISDTI PIC X(10)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:96}, from {@code ACCT-REISSUE-DATE} at
 *                    {@code app/cbl/COACTVWC.cbl:489}. May be {@code null}; rejected if wider than the declaration
 *                    cited.
 * @param currentBalance the current balance as display text. {@code ACURBALI PIC X(15)} at
 *                       {@code app/cpy-bms/COACTVW.CPY:102} - fifteen bytes here and at
 *                       {@code app/cpy-bms/COACTUP.CPY:138}, against {@code CURBALI PIC X(14)} at
 *                       {@code app/cpy-bms/COBIL00.CPY:66} - from {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                       {@code app/cbl/COACTVWC.cbl:475}. May be {@code null}; rejected if wider than the declaration
 *                       cited.
 * @param currentCycleCredit the current cycle credit as display text. {@code ACRCYCRI PIC X(15)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:108}, from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *                           {@code app/cbl/COACTVWC.cbl:482}. May be {@code null}; rejected if wider than the
 *                           declaration cited.
 * @param accountGroupId the disclosure group identifier. {@code AADDGRPI PIC X(10)} at
 *                       {@code app/cpy-bms/COACTVW.CPY:114}, from {@code ACCT-GROUP-ID} at
 *                       {@code app/cbl/COACTVWC.cbl:490}. May be {@code null}; rejected if wider than the declaration
 *                       cited.
 * @param currentCycleDebit the current cycle debit as display text, which legitimately carries negative amounts and is
 *                          never normalised to an absolute value. {@code ACRCYDBI PIC X(15)} at
 *                          {@code app/cpy-bms/COACTVW.CPY:120}, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *                          {@code app/cbl/COACTVWC.cbl:485}. May be {@code null}; rejected if wider than the
 *                          declaration cited.
 * @param customerId the nine-character customer identifier. {@code ACSTNUMI PIC X(9)} at
 *                   {@code app/cpy-bms/COACTVW.CPY:126}, from {@code CUST-ID PIC 9(09)} at
 *                   {@code app/cbl/COACTVWC.cbl:494}. May be {@code null}; rejected if wider than the declaration
 *                   cited.
 * @param customerSsn the dash-formatted social security number - protected data, never emitted.
 *                    {@code ACSTSSNI PIC X(12)} at {@code app/cpy-bms/COACTVW.CPY:132}, assembled by the {@code STRING}
 *                    of {@code CUST-SSN PIC 9(09)} at {@code app/cbl/COACTVWC.cbl:496} to line 504 into eleven
 *                    characters. May be {@code null}; rejected if wider than the declaration cited.
 * @param customerDateOfBirth the date of birth as dash-separated text - protected data, never emitted.
 *                            {@code ACSTDOBI PIC X(10)} at {@code app/cpy-bms/COACTVW.CPY:138}, from
 *                            {@code CUST-DOB-YYYY-MM-DD} at {@code app/cbl/COACTVWC.cbl:507}. May be {@code null};
 *                            rejected if wider than the declaration cited.
 * @param customerFicoScore the FICO credit score. {@code ACSTFCOI PIC X(3)} at {@code app/cpy-bms/COACTVW.CPY:144},
 *                          from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cbl/COACTVWC.cbl:505}. May be
 *                          {@code null}; rejected if wider than the declaration cited.
 * @param customerFirstName the customer first name - protected data, never emitted. {@code ACSFNAMI PIC X(25)} at
 *                          {@code app/cpy-bms/COACTVW.CPY:150}, from {@code CUST-FIRST-NAME} at
 *                          {@code app/cbl/COACTVWC.cbl:508}. May be {@code null}; rejected if wider than the
 *                          declaration cited.
 * @param customerMiddleName the customer middle name - protected data, never emitted. {@code ACSMNAMI PIC X(25)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:156}, from {@code CUST-MIDDLE-NAME} at
 *                           {@code app/cbl/COACTVWC.cbl:509}. May be {@code null}; rejected if wider than the
 *                           declaration cited.
 * @param customerLastName the customer last name - protected data, never emitted. {@code ACSLNAMI PIC X(25)} at
 *                         {@code app/cpy-bms/COACTVW.CPY:162}, from {@code CUST-LAST-NAME} at
 *                         {@code app/cbl/COACTVWC.cbl:510}. May be {@code null}; rejected if wider than the declaration
 *                         cited.
 * @param addressLine1 the first address line. {@code ACSADL1I PIC X(50)} at {@code app/cpy-bms/COACTVW.CPY:168}, from
 *                     {@code CUST-ADDR-LINE-1} at {@code app/cbl/COACTVWC.cbl:511}. May be {@code null}; rejected if
 *                     wider than the declaration cited.
 * @param addressStateCode the two-character state code, which the map declares between the two address lines rather
 *                         than after them. {@code ACSSTTEI PIC X(2)} at {@code app/cpy-bms/COACTVW.CPY:174}, from
 *                         {@code CUST-ADDR-STATE-CD PIC X(02)} at {@code app/cbl/COACTVWC.cbl:514}. May be
 *                         {@code null}; rejected if wider than the declaration cited.
 * @param addressLine2 the second address line. {@code ACSADL2I PIC X(50)} at {@code app/cpy-bms/COACTVW.CPY:180}, from
 *                     {@code CUST-ADDR-LINE-2} at {@code app/cbl/COACTVWC.cbl:512}. May be {@code null}; rejected if
 *                     wider than the declaration cited.
 * @param addressZip the postal code, five bytes on this map, into which the ten-byte {@code CUST-ADDR-ZIP PIC X(10)} is
 *                   truncated by the legacy move. {@code ACSZIPCI PIC X(5)} at {@code app/cpy-bms/COACTVW.CPY:186},
 *                   from {@code app/cbl/COACTVWC.cbl:515}. May be {@code null}; rejected if wider than the declaration
 *                   cited.
 * @param addressCity the city, which the map declares after the postal code and which is in fact address line 3.
 *                    {@code ACSCITYI PIC X(50)} at {@code app/cpy-bms/COACTVW.CPY:192}, from {@code CUST-ADDR-LINE-3}
 *                    at {@code app/cbl/COACTVWC.cbl:513}. May be {@code null}; rejected if wider than the declaration
 *                    cited.
 * @param addressCountryCode the three-character country code. {@code ACSCTRYI PIC X(3)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:198}, from {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
 *                           {@code app/cbl/COACTVWC.cbl:516}. May be {@code null}; rejected if wider than the
 *                           declaration cited.
 * @param phoneNumber1 the first telephone number as a whole formatted value, thirteen bytes on this map, into which the
 *                     fifteen-byte {@code CUST-PHONE-NUM-1 PIC X(15)} is truncated - protected data, never emitted.
 *                     {@code ACSPHN1I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY:204}, from
 *                     {@code app/cbl/COACTVWC.cbl:517}. May be {@code null}; rejected if wider than the declaration
 *                     cited.
 * @param governmentIssuedId the government-issued identifier, which the map declares between the two telephone numbers
 *                           - protected data, never emitted. {@code ACSGOVTI PIC X(20)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:210}, from {@code CUST-GOVT-ISSUED-ID} at
 *                           {@code app/cbl/COACTVWC.cbl:519}. May be {@code null}; rejected if wider than the
 *                           declaration cited.
 * @param phoneNumber2 the second telephone number as a whole formatted value, thirteen bytes on this map, into which
 *                     the fifteen-byte {@code CUST-PHONE-NUM-2 PIC X(15)} is truncated - protected data, never emitted.
 *                     {@code ACSPHN2I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY:216}, from
 *                     {@code app/cbl/COACTVWC.cbl:518}. May be {@code null}; rejected if wider than the declaration
 *                     cited.
 * @param eftAccountId the electronic funds transfer account identifier - protected data, never emitted.
 *                     {@code ACSEFTCI PIC X(10)} at {@code app/cpy-bms/COACTVW.CPY:222}, from
 *                     {@code CUST-EFT-ACCOUNT-ID} at {@code app/cbl/COACTVWC.cbl:520}. May be {@code null}; rejected if
 *                     wider than the declaration cited.
 * @param primaryCardHolderIndicator the primary card holder indicator, a raw one-character code with no enumerated
 *                                   counterpart. {@code ACSPFLGI PIC X(1)} at {@code app/cpy-bms/COACTVW.CPY:228}, from
 *                                   {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at {@code app/cbl/COACTVWC.cbl:521}. May
 *                                   be {@code null}; rejected if wider than the declaration cited.
 * @param informationMessage the screen information message. {@code INFOMSGI PIC X(45)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:234}, from {@code WS-INFO-MSG} at
 *                           {@code app/cbl/COACTVWC.cbl:534}. May be {@code null}; rejected if wider than the
 *                           declaration cited.
 * @param errorMessage the screen error message. {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COACTVW.CPY:240}, from
 *                     {@code WS-RETURN-MSG} at {@code app/cbl/COACTVWC.cbl:532}. May be {@code null}; rejected if wider
 *                     than the declaration cited.
 * @see #AccountDto(String, String, String, String, String, String, String, String, String, String, String, String,
 *      String, String, String, String, String, String, String, String, String, String, String, String, String,
 *      String, String, String, String, String, String, String, String, String, String, String, String) for the width
 *      enforcement every component is subject to
 */
public record AccountDto(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String accountId,
        String accountStatus,
        String openDate,
        String creditLimit,
        String expiryDate,
        String cashCreditLimit,
        String reissueDate,
        String currentBalance,
        String currentCycleCredit,
        String accountGroupId,
        String currentCycleDebit,
        String customerId,
        String customerSsn,
        String customerDateOfBirth,
        String customerFicoScore,
        String customerFirstName,
        String customerMiddleName,
        String customerLastName,
        String addressLine1,
        String addressStateCode,
        String addressLine2,
        String addressZip,
        String addressCity,
        String addressCountryCode,
        String phoneNumber1,
        String governmentIssuedId,
        String phoneNumber2,
        String eftAccountId,
        String primaryCardHolderIndicator,
        String informationMessage,
        String errorMessage) {

    /**
     * The number of screen fields this payload declares, and therefore the number of components on this record: a
     * census of the {@code 02 ...I PIC} declarations inside the {@code 01 CACTVWAI} input group at
     * {@code app/cpy-bms/COACTVW.CPY:17-240}.
     */
    public static final int FIELD_COUNT = 37;

    /**
     * The declared width, in bytes, of every monetary field on this map: {@code PIC X(15)}, as at
     * {@code ACRDLIMI}, {@code app/cpy-bms/COACTVW.CPY:78}.
     */
    public static final int MONEY_DISPLAY_LENGTH = 15;

    /**
     * The decimal scale of every account amount: two fractional digits, taken from the {@code V99} of
     * {@code PIC S9(10)V99} in {@code app/cpy/CVACT01Y.cpy}.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The greatest number of significant digits an account amount may carry: ten integer digits plus two
     * fractional digits from {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy}, which is the
     * {@code NUMERIC(12,2)} column width in the
     * relational target.
     */
    public static final int MONEY_PRECISION = 12;

    /**
     * The rounding mode for every monetary conversion.
     */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

    /** {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COACTVW.CPY:24}. */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * {@code TITLE01I} at {@code app/cpy-bms/COACTVW.CPY:30} and {@code TITLE02I} at {@code :48}, both
     * {@code PIC X(40)}. The two title lines share one constant because they share one declared width on
     * this map, not by convention.
     */
    public static final int TITLE_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COACTVW.CPY:36}. */
    public static final int CURRENT_DATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COACTVW.CPY:42}. */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COACTVW.CPY:54}.
     *
     * <p>Declared separately from {@link #CURRENT_DATE_LENGTH} and {@link #PROGRAM_NAME_LENGTH} even though
     * all three are eight bytes, because the widths coincide rather than derive from one another. The census
     * behind that caution: the header time is {@code X(8)} on sixteen of the seventeen symbolic maps and
     * {@code X(9)} on {@code app/cpy-bms/COSGN00.CPY:54}, so a shared cross-map constant would be wrong on
     * exactly one map.</p>
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * {@code ACCTSIDI PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60} - eleven digit positions.
     *
     * <p>This is the corpus's sole numeric declaration of the field. The component remains text so that
     * leading zeros, {@code LOW-VALUES} and the {@code '*'} marker survive; the width is the digit count.</p>
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /** {@code ACSTTUSI PIC X(1)} at {@code app/cpy-bms/COACTVW.CPY:66}. */
    public static final int ACCOUNT_STATUS_LENGTH = 1;

    /**
     * The width of every dash-separated date on this map: {@code PIC X(10)}.
     *
     * <p>Shared by {@code ADTOPENI} at {@code app/cpy-bms/COACTVW.CPY:72}, {@code AEXPDTI} at {@code :84},
     * {@code AREISDTI} at {@code :96} and {@code ACSTDOBI} at {@code :138}, all four of which carry the same
     * {@code yyyy-mm-dd} form drawn from a {@code PIC X(10)} record field.</p>
     */
    public static final int DATE_TEXT_LENGTH = 10;

    /** {@code AADDGRPI PIC X(10)} at {@code app/cpy-bms/COACTVW.CPY:114}, from {@code ACCT-GROUP-ID}. */
    public static final int ACCOUNT_GROUP_ID_LENGTH = 10;

    /** {@code ACSTNUMI PIC X(9)} at {@code app/cpy-bms/COACTVW.CPY:126}. */
    public static final int CUSTOMER_ID_LENGTH = 9;

    /**
     * {@code ACSTSSNI PIC X(12)} at {@code app/cpy-bms/COACTVW.CPY:132}.
     *
     * <p>The map declares twelve bytes; the {@code STRING} at {@code app/cbl/COACTVWC.cbl:496-504} assembles
     * eleven. Twelve is the contract, because it is what the field can hold.</p>
     */
    public static final int SSN_DISPLAY_LENGTH = 12;

    /** {@code ACSTFCOI PIC X(3)} at {@code app/cpy-bms/COACTVW.CPY:144}. */
    public static final int FICO_SCORE_LENGTH = 3;

    /**
     * The width of every customer name component: {@code PIC X(25)}.
     *
     * <p>Shared by {@code ACSFNAMI} at {@code app/cpy-bms/COACTVW.CPY:150}, {@code ACSMNAMI} at {@code :156}
     * and {@code ACSLNAMI} at {@code :162}, matching {@code app/cpy/CVCUS01Y.cpy:3-5}.</p>
     */
    public static final int NAME_LENGTH = 25;

    /**
     * The width of every address line on this map: {@code PIC X(50)}.
     *
     * <p>Shared by {@code ACSADL1I} at {@code app/cpy-bms/COACTVW.CPY:168}, {@code ACSADL2I} at {@code :180}
     * and {@code ACSCITYI} at {@code :192}. The third is address line 3 despite its name, as the component
     * documentation records.</p>
     */
    public static final int ADDRESS_LINE_LENGTH = 50;

    /** {@code ACSSTTEI PIC X(2)} at {@code app/cpy-bms/COACTVW.CPY:174}, from {@code CUST-ADDR-STATE-CD}. */
    public static final int STATE_CODE_LENGTH = 2;

    /**
     * {@code ACSZIPCI PIC X(5)} at {@code app/cpy-bms/COACTVW.CPY:186}.
     *
     * <p>Five bytes, into which the legacy move truncates the ten-byte {@code CUST-ADDR-ZIP PIC X(10)} of
     * {@code app/cpy/CVCUS01Y.cpy:11}. The narrower map width is the contract for this payload; widening it
     * to ten would accept a value the screen could not display.</p>
     */
    public static final int ZIP_LENGTH = 5;

    /** {@code ACSCTRYI PIC X(3)} at {@code app/cpy-bms/COACTVW.CPY:198}, from {@code CUST-ADDR-COUNTRY-CD}. */
    public static final int COUNTRY_CODE_LENGTH = 3;

    /**
     * The width of both telephone numbers on this map: {@code PIC X(13)}.
     *
     * <p>{@code ACSPHN1I} at {@code app/cpy-bms/COACTVW.CPY:204} and {@code ACSPHN2I} at {@code :216}.
     * Thirteen bytes, into which the fifteen-byte {@code CUST-PHONE-NUM-1 PIC X(15)} of
     * {@code app/cpy/CVCUS01Y.cpy:12-13} is truncated - the two trailing filler bytes of the record field are
     * lost. The account-update map decomposes the same data into three components instead, which is why no
     * telephone width is shared across the two maps.</p>
     */
    public static final int PHONE_NUMBER_LENGTH = 13;

    /** {@code ACSGOVTI PIC X(20)} at {@code app/cpy-bms/COACTVW.CPY:210}. */
    public static final int GOVERNMENT_ID_LENGTH = 20;

    /** {@code ACSEFTCI PIC X(10)} at {@code app/cpy-bms/COACTVW.CPY:222}. */
    public static final int EFT_ACCOUNT_ID_LENGTH = 10;

    /** {@code ACSPFLGI PIC X(1)} at {@code app/cpy-bms/COACTVW.CPY:228}. */
    public static final int CARD_HOLDER_INDICATOR_LENGTH = 1;

    /**
     * {@code INFOMSGI PIC X(45)} at {@code app/cpy-bms/COACTVW.CPY:234}.
     *
     * <p>Forty-five bytes on this map. The card-detail map declares its information message as
     * {@code X(40)} and the card-list map as {@code X(45)}, so this width is map-specific and must not be
     * promoted to a shared constant.</p>
     */
    public static final int INFORMATION_MESSAGE_LENGTH = 45;

    /**
     * {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COACTVW.CPY:240}.
     *
     * <p>Seventy-eight bytes on this map, against {@code X(80)} on {@code app/cpy-bms/COCRDSL.CPY:102}, for
     * the same reason.</p>
     */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * The character a {@code LOW-VALUES} screen field decodes to: a binary zero.
     */
    private static final char LOW_VALUE = '\u0000';

    /**
     * Enforces the declared width of every component, rejecting a value the screen field could not hold.
     *
     * <p><strong>Why a canonical constructor is required at all.</strong> Each component's width is fixed by
     * the symbolic map, and every component documents its own PIC clause, but until this constructor existed
     * none of those widths was enforced. A record's implicit constructor accepts any string, so a caller
     * could build an {@code AccountDto} whose {@code errorMessage} ran to two hundred characters or whose
     * {@code addressStateCode} held seven, and the payload would serialise cleanly while describing a screen
     * state {@code app/cpy-bms/COACTVW.CPY} cannot represent. The map is the migrated contract, so it is
     * enforced here, once, at the only point through which every instance passes - including instances
     * produced by deserialisation and by {@code withers} in any future revision.</p>
     *
     * <p><strong>What the guard does not do.</strong> It rejects; it never repairs. There is no trimming, no
     * padding to the declared width, no case folding, no numeric coercion and no substitution of a default,
     * because each of those would alter a value that the parity comparison reads byte for byte. The
     * account-view screen is a display surface built by {@code 1200-SETUP-SCREEN-VARS}
     * ({@code app/cbl/COACTVWC.cbl:460}) from unedited record fields, so a component either fits the field it
     * came from or did not come from it.</p>
     *
     * <p><strong>Absence is not a violation.</strong> {@code null} passes, the empty string passes, blanks
     * pass and {@code LOW-VALUES} passes - see {@link #LOW_VALUE} for why the last of these is a real state
     * on this map rather than a theoretical one. The source distinguishes all four, its screen-attribute
     * template {@code app/cpy/CSSETATY.cpy} models a blank field as a third state alongside valid and
     * invalid, and collapsing any of them into another would erase a distinction the consuming service reads.
     * Only over-width is an error, because only over-width is impossible.</p>
     *
     * <p><strong>Widths are compared in characters.</strong> Every declaration involved is
     * {@code PIC X(n)} or, for the account identifier, {@code PIC 9(11)}, which are single-byte character
     * positions on the host, so one character is one position. No component carries a numeric type, so no
     * precision or scale check belongs here; monetary text is converted on demand by
     * {@link #toAmount(String, String)}, which applies the {@code PIC S9(10)V99} precision ceiling at the
     * point of conversion rather than at construction.</p>
     *
     * @throws IllegalArgumentException if any component is longer than the width
     *         {@code app/cpy-bms/COACTVW.CPY} declares for its screen field. The message names the component
     *         and quotes the declaration, and never reproduces the offending value, because this payload
     *         carries a social security number, a date of birth, both telephone numbers, a government-issued
     *         identifier, an electronic funds transfer account identifier and three customer names.
     */
    public AccountDto {
        requireWidthWithinLimit(transactionName, TRANSACTION_NAME_LENGTH, "transactionName",
                "TRNNAMEI PIC X(4) at app/cpy-bms/COACTVW.CPY:24");
        requireWidthWithinLimit(title01, TITLE_LENGTH, "title01", "TITLE01I PIC X(40) at app/cpy-bms/COACTVW.CPY:30");
        requireWidthWithinLimit(currentDate, CURRENT_DATE_LENGTH, "currentDate",
                "CURDATEI PIC X(8) at app/cpy-bms/COACTVW.CPY:36");
        requireWidthWithinLimit(programName, PROGRAM_NAME_LENGTH, "programName",
                "PGMNAMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:42");
        requireWidthWithinLimit(title02, TITLE_LENGTH, "title02", "TITLE02I PIC X(40) at app/cpy-bms/COACTVW.CPY:48");
        requireWidthWithinLimit(currentTime, CURRENT_TIME_LENGTH, "currentTime",
                "CURTIMEI PIC X(8) at app/cpy-bms/COACTVW.CPY:54");
        requireWidthWithinLimit(accountId, ACCOUNT_ID_LENGTH, "accountId",
                "ACCTSIDI PIC 99999999999 at app/cpy-bms/COACTVW.CPY:60");
        requireWidthWithinLimit(accountStatus, ACCOUNT_STATUS_LENGTH, "accountStatus",
                "ACSTTUSI PIC X(1) at app/cpy-bms/COACTVW.CPY:66");
        requireWidthWithinLimit(openDate, DATE_TEXT_LENGTH, "openDate",
                "ADTOPENI PIC X(10) at app/cpy-bms/COACTVW.CPY:72");
        requireWidthWithinLimit(creditLimit, MONEY_DISPLAY_LENGTH, "creditLimit",
                "ACRDLIMI PIC X(15) at app/cpy-bms/COACTVW.CPY:78");
        requireWidthWithinLimit(expiryDate, DATE_TEXT_LENGTH, "expiryDate",
                "AEXPDTI PIC X(10) at app/cpy-bms/COACTVW.CPY:84");
        requireWidthWithinLimit(cashCreditLimit, MONEY_DISPLAY_LENGTH, "cashCreditLimit",
                "ACSHLIMI PIC X(15) at app/cpy-bms/COACTVW.CPY:90");
        requireWidthWithinLimit(reissueDate, DATE_TEXT_LENGTH, "reissueDate",
                "AREISDTI PIC X(10) at app/cpy-bms/COACTVW.CPY:96");
        requireWidthWithinLimit(currentBalance, MONEY_DISPLAY_LENGTH, "currentBalance",
                "ACURBALI PIC X(15) at app/cpy-bms/COACTVW.CPY:102");
        requireWidthWithinLimit(currentCycleCredit, MONEY_DISPLAY_LENGTH, "currentCycleCredit",
                "ACRCYCRI PIC X(15) at app/cpy-bms/COACTVW.CPY:108");
        requireWidthWithinLimit(accountGroupId, ACCOUNT_GROUP_ID_LENGTH, "accountGroupId",
                "AADDGRPI PIC X(10) at app/cpy-bms/COACTVW.CPY:114");
        requireWidthWithinLimit(currentCycleDebit, MONEY_DISPLAY_LENGTH, "currentCycleDebit",
                "ACRCYDBI PIC X(15) at app/cpy-bms/COACTVW.CPY:120");
        requireWidthWithinLimit(customerId, CUSTOMER_ID_LENGTH, "customerId",
                "ACSTNUMI PIC X(9) at app/cpy-bms/COACTVW.CPY:126");
        requireWidthWithinLimit(customerSsn, SSN_DISPLAY_LENGTH, "customerSsn",
                "ACSTSSNI PIC X(12) at app/cpy-bms/COACTVW.CPY:132");
        requireWidthWithinLimit(customerDateOfBirth, DATE_TEXT_LENGTH, "customerDateOfBirth",
                "ACSTDOBI PIC X(10) at app/cpy-bms/COACTVW.CPY:138");
        requireWidthWithinLimit(customerFicoScore, FICO_SCORE_LENGTH, "customerFicoScore",
                "ACSTFCOI PIC X(3) at app/cpy-bms/COACTVW.CPY:144");
        requireWidthWithinLimit(customerFirstName, NAME_LENGTH, "customerFirstName",
                "ACSFNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:150");
        requireWidthWithinLimit(customerMiddleName, NAME_LENGTH, "customerMiddleName",
                "ACSMNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:156");
        requireWidthWithinLimit(customerLastName, NAME_LENGTH, "customerLastName",
                "ACSLNAMI PIC X(25) at app/cpy-bms/COACTVW.CPY:162");
        requireWidthWithinLimit(addressLine1, ADDRESS_LINE_LENGTH, "addressLine1",
                "ACSADL1I PIC X(50) at app/cpy-bms/COACTVW.CPY:168");
        requireWidthWithinLimit(addressStateCode, STATE_CODE_LENGTH, "addressStateCode",
                "ACSSTTEI PIC X(2) at app/cpy-bms/COACTVW.CPY:174");
        requireWidthWithinLimit(addressLine2, ADDRESS_LINE_LENGTH, "addressLine2",
                "ACSADL2I PIC X(50) at app/cpy-bms/COACTVW.CPY:180");
        requireWidthWithinLimit(addressZip, ZIP_LENGTH, "addressZip",
                "ACSZIPCI PIC X(5) at app/cpy-bms/COACTVW.CPY:186");
        requireWidthWithinLimit(addressCity, ADDRESS_LINE_LENGTH, "addressCity",
                "ACSCITYI PIC X(50) at app/cpy-bms/COACTVW.CPY:192");
        requireWidthWithinLimit(addressCountryCode, COUNTRY_CODE_LENGTH, "addressCountryCode",
                "ACSCTRYI PIC X(3) at app/cpy-bms/COACTVW.CPY:198");
        requireWidthWithinLimit(phoneNumber1, PHONE_NUMBER_LENGTH, "phoneNumber1",
                "ACSPHN1I PIC X(13) at app/cpy-bms/COACTVW.CPY:204");
        requireWidthWithinLimit(governmentIssuedId, GOVERNMENT_ID_LENGTH, "governmentIssuedId",
                "ACSGOVTI PIC X(20) at app/cpy-bms/COACTVW.CPY:210");
        requireWidthWithinLimit(phoneNumber2, PHONE_NUMBER_LENGTH, "phoneNumber2",
                "ACSPHN2I PIC X(13) at app/cpy-bms/COACTVW.CPY:216");
        requireWidthWithinLimit(eftAccountId, EFT_ACCOUNT_ID_LENGTH, "eftAccountId",
                "ACSEFTCI PIC X(10) at app/cpy-bms/COACTVW.CPY:222");
        requireWidthWithinLimit(primaryCardHolderIndicator, CARD_HOLDER_INDICATOR_LENGTH, "primaryCardHolderIndicator",
                "ACSPFLGI PIC X(1) at app/cpy-bms/COACTVW.CPY:228");
        requireWidthWithinLimit(informationMessage, INFORMATION_MESSAGE_LENGTH, "informationMessage",
                "INFOMSGI PIC X(45) at app/cpy-bms/COACTVW.CPY:234");
        requireWidthWithinLimit(errorMessage, ERROR_MESSAGE_LENGTH, "errorMessage",
                "ERRMSGI PIC X(78) at app/cpy-bms/COACTVW.CPY:240");
    }

    /**
     * Rejects a component value wider than the screen field it is declared from.
     *
     * @param value         the component value as supplied; {@code null}, empty, blank and
     *                      {@code LOW-VALUES} are all accepted, because the source distinguishes them and
     *                      none of them can overflow a field
     * @param maxLength     the width the symbolic map declares, in character positions
     * @param componentName the name of the record component being checked, used to identify the offending
     *                      field in the diagnostic message
     * @param provenance    the COBOL field name, PIC clause and source locator, quoted in the diagnostic
     *                      message so that a reader can verify the width without leaving the stack trace
     * @throws IllegalArgumentException if {@code value} is longer than {@code maxLength}; the offending
     *         value is deliberately never reproduced, because several components on this payload are
     *         protected data
     */
    private static void requireWidthWithinLimit(String value, int maxLength, String componentName,
            String provenance) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Component %s holds %d characters, but the symbolic map declares %s, so a value this "
                            + "long cannot have come from that screen field. The offending value is not "
                            + "reproduced because this payload carries protected data.",
                    componentName, value.length(), provenance));
        }
    }

    /**
     * Converts one of this payload's five monetary display fields into an arithmetic value.
     *
     * @param displayText the monetary display text taken from one of this record's five monetary components.
     * @param fieldName the name of the component being converted, used to identify the offending field in a
     * diagnostic message without ever quoting its value.
     * @return the amount scaled to {@value #MONEY_SCALE} fractional digits, or an empty result when the field
     * carries no value
     * @throws IllegalArgumentException if {@code fieldName} is {@code null} or blank.
     */
    public static Optional<BigDecimal> toAmount(String displayText, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException(
                    "fieldName is required so that a conversion failure can identify the offending field "
                            + "without quoting its value");
        }
        if (displayText == null) {
            return Optional.empty();
        }
        if (displayText.length() > MONEY_DISPLAY_LENGTH) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Field %s cannot be a COACTVW monetary field: its display text is longer than the %d "
                            + "bytes app/cpy-bms/COACTVW.CPY declares",
                    fieldName, MONEY_DISPLAY_LENGTH));
        }
        String candidate = displayText.replace(LOW_VALUE, ' ').strip();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(candidate);
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Field %s does not hold a decimal amount; app/cpy-bms/COACTVW.CPY declares it as a "
                            + "PIC X(%d) display field",
                    fieldName, MONEY_DISPLAY_LENGTH), cause);
        }
        BigDecimal scaled = parsed.setScale(MONEY_SCALE, MONEY_ROUNDING);
        if (scaled.precision() > MONEY_PRECISION) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Field %s needs more than the %d significant digits the underlying PIC S9(10)V99 "
                            + "account amount allows",
                    fieldName, MONEY_PRECISION));
        }
        return Optional.of(scaled);
    }

    /**
     * Returns a diagnostic rendering that deliberately omits every protected field.
     *
     * @return a rendering containing only the account identifier, the account status and the program name, any
     * of which may read {@code null} when its screen field was not populated
     */
    @Override
    public String toString() {
        return "AccountDto[accountId=" + accountId
                + ", accountStatus=" + accountStatus
                + ", programName=" + programName
                + ", protectedFieldsOmitted=true]";
    }
}
