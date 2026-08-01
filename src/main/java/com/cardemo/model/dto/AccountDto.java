/*
 * **************************************************************************
 * Program     : AccountDto.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Account-view response payload; account and customer fields
 *               interleaved as in the source.
 * Source      : app/cpy-bms/COACTVW.CPY (37 fields) @ 7756d89
 * **************************************************************************
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
 * **************************************************************************
 */
package com.cardemo.model.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * Immutable account-view response payload: the Java replacement for the CICS
 * {@code EXEC CICS SEND MAP} conversation of screen {@code CACTVWA}, transaction {@code CAVW}.
 *
 * <p><strong>Purpose.</strong> This record transports, verbatim, the 37 screen fields that
 * {@code COACTVWC} populates for the account-view screen. It is an outbound payload only: the
 * REST layer serialises it to JSON in place of writing a 3270 map. Every component is declared
 * in the exact order the symbolic map declares it, because field order is part of the migrated
 * contract and not an implementation detail.</p>
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
 * <p><strong>Verified field census, and a specification defect (severity: Medium).</strong>
 * The body of the technical specification states that {@code COACTVW} carries 36 input fields.
 * That figure is wrong. A count of the {@code 02 <name>I PIC} declarations lying strictly
 * inside the {@code 01 CACTVWAI.} group yields <strong>37</strong>, and all 37 are declared
 * here. The corpus-wide census is likewise 441 input fields across the seventeen symbolic maps,
 * not the 460 the specification body states. Remediation: this class implements the verified
 * count of 37, exposed as {@link #FIELD_COUNT} so the figure is machine-checkable; both
 * corrections are recorded in the repository-root decision log, which is the artefact for
 * cross-cutting findings. Impact is confined to documentation accuracy, hence Medium rather
 * than High: no runtime behaviour depends on the erroneous figure.</p>
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
 * </ul>
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
 * </ul>
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
 * </ul>
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
 * value; messages name the field only. Log masking is configured centrally as a second line of
 * defence, but the primary defence is never emitting the values in the first place. Consistent
 * with least privilege, this payload carries no password and no password hash - the account-view
 * map declares no such field - and it must not acquire one.</p>
 *
 * <p><strong>Error modes.</strong> Construction cannot fail: every component is a nullable
 * {@code String} and no argument is rejected, because absence is a legitimate state of a screen
 * field. Nothing in this class throws during normal operation. The only failure surface is
 * {@link #toAmount(String, String)}, which throws {@code IllegalArgumentException} when the
 * supplied text cannot denote an account amount; it names the offending field, never its value,
 * and preserves the underlying {@code NumberFormatException} as the cause so the root cause is
 * not swallowed. This class deliberately does not implement {@code Serializable}: it needs no
 * Java-native serialisation, and given the protected data it carries, exposing it to native
 * deserialisation would add risk for no benefit.</p>
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
 *                        {@code app/cpy-bms/COACTVW.CPY:24}, populated from
 *                        {@code LIT-THISTRANID} at {@code app/cbl/COACTVWC.cbl:438}. May be
 *                        {@code null}.
 * @param title01 the first screen title line. {@code TITLE01I PIC X(40)} at
 *                {@code app/cpy-bms/COACTVW.CPY:30}, populated from {@code CCDA-TITLE01} at
 *                {@code app/cbl/COACTVWC.cbl:436}. May be {@code null}.
 * @param currentDate the header date rendered {@code mm/dd/yy}. {@code CURDATEI PIC X(8)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:36}, populated from
 *                    {@code WS-CURDATE-MM-DD-YY} at {@code app/cbl/COACTVWC.cbl:447}. May be
 *                    {@code null}.
 * @param programName the screen header program name. {@code PGMNAMEI PIC X(8)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:42}, populated from {@code LIT-THISPGM} at
 *                    {@code app/cbl/COACTVWC.cbl:439}. May be {@code null}.
 * @param title02 the second screen title line. {@code TITLE02I PIC X(40)} at
 *                {@code app/cpy-bms/COACTVW.CPY:48}, populated from {@code CCDA-TITLE02} at
 *                {@code app/cbl/COACTVWC.cbl:437}. May be {@code null}.
 * @param currentTime the header time rendered {@code hh:mm:ss}. {@code CURTIMEI PIC X(8)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:54} - eight bytes on this map, nine at
 *                    {@code app/cpy-bms/COSGN00.CPY:54} - populated from
 *                    {@code WS-CURTIME-HH-MM-SS} at {@code app/cbl/COACTVWC.cbl:453}. May be
 *                    {@code null}.
 * @param accountId the eleven-character account identifier, zero-padded and carried as text so
 *                  that leading zeros, {@code LOW-VALUES} and the {@code '*'} marker all
 *                  survive. {@code ACCTSIDI PIC 99999999999} at
 *                  {@code app/cpy-bms/COACTVW.CPY:60} - the corpus's sole numeric declaration
 *                  of this field - keyed on {@code ACCT-ID PIC 9(11)} at
 *                  {@code app/cpy/CVACT01Y.cpy:5}. May be {@code null}.
 * @param accountStatus the single-character active status. {@code ACSTTUSI PIC X(1)} at
 *                      {@code app/cpy-bms/COACTVW.CPY:66}, from {@code ACCT-ACTIVE-STATUS} at
 *                      {@code app/cbl/COACTVWC.cbl:473}. May be {@code null}.
 * @param openDate the account open date as dash-separated text. {@code ADTOPENI PIC X(10)} at
 *                 {@code app/cpy-bms/COACTVW.CPY:72}, from {@code ACCT-OPEN-DATE} at
 *                 {@code app/cbl/COACTVWC.cbl:487}. May be {@code null}.
 * @param creditLimit the credit limit as display text. {@code ACRDLIMI PIC X(15)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:78}, from
 *                    {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
 *                    {@code app/cbl/COACTVWC.cbl:477}. May be {@code null}.
 * @param expiryDate the account expiry date as dash-separated text. {@code AEXPDTI PIC X(10)}
 *                   at {@code app/cpy-bms/COACTVW.CPY:84}, from
 *                   {@code ACCT-EXPIRAION-DATE} at {@code app/cbl/COACTVWC.cbl:488}; the
 *                   misspelling of that copybook field is part of the field contract and is not
 *                   corrected. May be {@code null}.
 * @param cashCreditLimit the cash credit limit as display text. {@code ACSHLIMI PIC X(15)} at
 *                        {@code app/cpy-bms/COACTVW.CPY:90}, from
 *                        {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
 *                        {@code app/cbl/COACTVWC.cbl:479}. May be {@code null}.
 * @param reissueDate the card reissue date as dash-separated text. {@code AREISDTI PIC X(10)}
 *                    at {@code app/cpy-bms/COACTVW.CPY:96}, from {@code ACCT-REISSUE-DATE} at
 *                    {@code app/cbl/COACTVWC.cbl:489}. May be {@code null}.
 * @param currentBalance the current balance as display text. {@code ACURBALI PIC X(15)} at
 *                       {@code app/cpy-bms/COACTVW.CPY:102} - fifteen bytes here and at
 *                       {@code app/cpy-bms/COACTUP.CPY:138}, against
 *                       {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66} - from
 *                       {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                       {@code app/cbl/COACTVWC.cbl:475}. May be {@code null}.
 * @param currentCycleCredit the current cycle credit as display text.
 *                           {@code ACRCYCRI PIC X(15)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:108}, from
 *                           {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *                           {@code app/cbl/COACTVWC.cbl:482}. May be {@code null}.
 * @param accountGroupId the disclosure group identifier. {@code AADDGRPI PIC X(10)} at
 *                       {@code app/cpy-bms/COACTVW.CPY:114}, from {@code ACCT-GROUP-ID} at
 *                       {@code app/cbl/COACTVWC.cbl:490}. May be {@code null}.
 * @param currentCycleDebit the current cycle debit as display text, which legitimately carries
 *                          negative amounts and is never normalised to an absolute value.
 *                          {@code ACRCYDBI PIC X(15)} at
 *                          {@code app/cpy-bms/COACTVW.CPY:120}, from
 *                          {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *                          {@code app/cbl/COACTVWC.cbl:485}. May be {@code null}.
 * @param customerId the nine-character customer identifier. {@code ACSTNUMI PIC X(9)} at
 *                   {@code app/cpy-bms/COACTVW.CPY:126}, from {@code CUST-ID PIC 9(09)} at
 *                   {@code app/cbl/COACTVWC.cbl:494}. May be {@code null}.
 * @param customerSsn the dash-formatted social security number - protected data, never
 *                    emitted. {@code ACSTSSNI PIC X(12)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:132}, assembled by the {@code STRING} of
 *                    {@code CUST-SSN PIC 9(09)} at {@code app/cbl/COACTVWC.cbl:496} to
 *                    line 504 into eleven characters. May be {@code null}.
 * @param customerDateOfBirth the date of birth as dash-separated text - protected data, never
 *                            emitted. {@code ACSTDOBI PIC X(10)} at
 *                            {@code app/cpy-bms/COACTVW.CPY:138}, from
 *                            {@code CUST-DOB-YYYY-MM-DD} at
 *                            {@code app/cbl/COACTVWC.cbl:507}. May be {@code null}.
 * @param customerFicoScore the FICO credit score. {@code ACSTFCOI PIC X(3)} at
 *                          {@code app/cpy-bms/COACTVW.CPY:144}, from
 *                          {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
 *                          {@code app/cbl/COACTVWC.cbl:505}. May be {@code null}.
 * @param customerFirstName the customer first name - protected data, never emitted.
 *                          {@code ACSFNAMI PIC X(25)} at
 *                          {@code app/cpy-bms/COACTVW.CPY:150}, from
 *                          {@code CUST-FIRST-NAME} at {@code app/cbl/COACTVWC.cbl:508}. May be
 *                          {@code null}.
 * @param customerMiddleName the customer middle name - protected data, never emitted.
 *                           {@code ACSMNAMI PIC X(25)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:156}, from
 *                           {@code CUST-MIDDLE-NAME} at {@code app/cbl/COACTVWC.cbl:509}. May
 *                           be {@code null}.
 * @param customerLastName the customer last name - protected data, never emitted.
 *                         {@code ACSLNAMI PIC X(25)} at
 *                         {@code app/cpy-bms/COACTVW.CPY:162}, from {@code CUST-LAST-NAME} at
 *                         {@code app/cbl/COACTVWC.cbl:510}. May be {@code null}.
 * @param addressLine1 the first address line. {@code ACSADL1I PIC X(50)} at
 *                     {@code app/cpy-bms/COACTVW.CPY:168}, from {@code CUST-ADDR-LINE-1} at
 *                     {@code app/cbl/COACTVWC.cbl:511}. May be {@code null}.
 * @param addressStateCode the two-character state code, which the map declares between the two
 *                         address lines rather than after them. {@code ACSSTTEI PIC X(2)} at
 *                         {@code app/cpy-bms/COACTVW.CPY:174}, from
 *                         {@code CUST-ADDR-STATE-CD PIC X(02)} at
 *                         {@code app/cbl/COACTVWC.cbl:514}. May be {@code null}.
 * @param addressLine2 the second address line. {@code ACSADL2I PIC X(50)} at
 *                     {@code app/cpy-bms/COACTVW.CPY:180}, from {@code CUST-ADDR-LINE-2} at
 *                     {@code app/cbl/COACTVWC.cbl:512}. May be {@code null}.
 * @param addressZip the postal code, five bytes on this map, into which the ten-byte
 *                   {@code CUST-ADDR-ZIP PIC X(10)} is truncated by the legacy move.
 *                   {@code ACSZIPCI PIC X(5)} at {@code app/cpy-bms/COACTVW.CPY:186}, from
 *                   {@code app/cbl/COACTVWC.cbl:515}. May be {@code null}.
 * @param addressCity the city, which the map declares after the postal code and which is in
 *                    fact address line 3. {@code ACSCITYI PIC X(50)} at
 *                    {@code app/cpy-bms/COACTVW.CPY:192}, from {@code CUST-ADDR-LINE-3} at
 *                    {@code app/cbl/COACTVWC.cbl:513}. May be {@code null}.
 * @param addressCountryCode the three-character country code. {@code ACSCTRYI PIC X(3)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:198}, from
 *                           {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
 *                           {@code app/cbl/COACTVWC.cbl:516}. May be {@code null}.
 * @param phoneNumber1 the first telephone number as a whole formatted value, thirteen bytes on
 *                     this map, into which the fifteen-byte {@code CUST-PHONE-NUM-1 PIC X(15)}
 *                     is truncated - protected data, never emitted.
 *                     {@code ACSPHN1I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY:204}, from
 *                     {@code app/cbl/COACTVWC.cbl:517}. May be {@code null}.
 * @param governmentIssuedId the government-issued identifier, which the map declares between
 *                           the two telephone numbers - protected data, never emitted.
 *                           {@code ACSGOVTI PIC X(20)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:210}, from
 *                           {@code CUST-GOVT-ISSUED-ID} at
 *                           {@code app/cbl/COACTVWC.cbl:519}. May be {@code null}.
 * @param phoneNumber2 the second telephone number as a whole formatted value, thirteen bytes on
 *                     this map, into which the fifteen-byte {@code CUST-PHONE-NUM-2 PIC X(15)}
 *                     is truncated - protected data, never emitted.
 *                     {@code ACSPHN2I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY:216}, from
 *                     {@code app/cbl/COACTVWC.cbl:518}. May be {@code null}.
 * @param eftAccountId the electronic funds transfer account identifier - protected data, never
 *                     emitted. {@code ACSEFTCI PIC X(10)} at
 *                     {@code app/cpy-bms/COACTVW.CPY:222}, from
 *                     {@code CUST-EFT-ACCOUNT-ID} at {@code app/cbl/COACTVWC.cbl:520}. May be
 *                     {@code null}.
 * @param primaryCardHolderIndicator the primary card holder indicator, a raw one-character code
 *                                   with no enumerated counterpart.
 *                                   {@code ACSPFLGI PIC X(1)} at
 *                                   {@code app/cpy-bms/COACTVW.CPY:228}, from
 *                                   {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at
 *                                   {@code app/cbl/COACTVWC.cbl:521}. May be {@code null}.
 * @param informationMessage the screen information message. {@code INFOMSGI PIC X(45)} at
 *                           {@code app/cpy-bms/COACTVW.CPY:234}, from {@code WS-INFO-MSG} at
 *                           {@code app/cbl/COACTVWC.cbl:534}. May be {@code null}.
 * @param errorMessage the screen error message. {@code ERRMSGI PIC X(78)} at
 *                     {@code app/cpy-bms/COACTVW.CPY:240}, from {@code WS-RETURN-MSG} at
 *                     {@code app/cbl/COACTVWC.cbl:532}. May be {@code null}.
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
     * The number of screen fields this payload declares, and therefore the number of components
     * on this record.
     *
     * <p>The figure is the verified count of the {@code 02 <name>I PIC} declarations lying
     * strictly inside the {@code 01 CACTVWAI.} group of {@code app/cpy-bms/COACTVW.CPY}, which
     * begins at line 17 and ends where {@code 01 CACTVWAO REDEFINES CACTVWAI.} begins at
     * line 241. It is exposed so that the count is machine-checkable, and so that the
     * Medium-severity specification defect described on this class - a stated figure of 36 -
     * cannot be reintroduced silently by a later edit.</p>
     */
    public static final int FIELD_COUNT = 37;

    /**
     * The declared width, in bytes, of every monetary field on this map: {@code PIC X(15)}.
     *
     * <p>The width belongs to this map alone. The bill-payment map declares its current balance
     * as {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66} - a different name and
     * a different width - so neither this constant nor {@link #toAmount(String, String)} may be
     * promoted to a cross-map utility.</p>
     */
    public static final int MONEY_DISPLAY_LENGTH = 15;

    /**
     * The decimal scale of every account amount: two fractional digits, taken from the
     * {@code V99} of {@code PIC S9(10)V99} in {@code app/cpy/CVACT01Y.cpy}.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The greatest number of significant digits an account amount may carry: ten integer digits
     * plus two fractional digits from {@code PIC S9(10)V99}, which is the {@code NUMERIC(12,2)}
     * column width in the relational target.
     */
    public static final int MONEY_PRECISION = 12;

    /**
     * The rounding mode for every monetary conversion.
     *
     * <p>Banker's rounding is applied uniformly so that a converted amount does not depend on
     * the order in which conversions happen, and so that repeated conversion of the same
     * display text is idempotent.</p>
     */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

    /**
     * The character a {@code LOW-VALUES} screen field decodes to: a binary zero.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:466} moves {@code LOW-VALUES} into the account identifier
     * field when the account filter is blank, so a component may legitimately arrive as binary
     * zeros rather than as blanks or as {@code null}. Binary zeros are not whitespace, so they
     * are not removed by stripping and have to be neutralised explicitly before a numeric
     * conversion can recognise the field as empty.</p>
     */
    private static final char LOW_VALUE = '\u0000';

    /**
     * Converts one of this payload's five monetary display fields into an arithmetic value.
     *
     * <p>This is the sanctioned way to obtain a number from a monetary component, and it is
     * deliberately <em>not</em> applied when the record is constructed. The components keep the
     * display text exactly as the screen produced it, and the caller converts only where it
     * actually needs to compute; that division is what keeps this record a pure transport type
     * and keeps the payload byte-comparable against the legacy baseline.</p>
     *
     * <p>Absence is returned, not signalled. A {@code null} argument, an argument of blanks and
     * an argument of binary zeros - the {@code LOW-VALUES} state described on
     * {@link #LOW_VALUE} - all yield an empty result, because for the purpose of arithmetic each
     * of the three means "no amount". The three states stay distinguishable on the record
     * itself; only this conversion collapses them, and only for that narrow purpose.</p>
     *
     * <p>Parsing is locale-independent by construction. The text is handed to
     * {@code new BigDecimal(String)}, whose grammar is fixed by specification;
     * {@code java.text.NumberFormat} is deliberately avoided because it honours the platform
     * default locale and would therefore accept a comma decimal separator on some hosts and
     * reject it on others. Diagnostic messages are rendered with {@code Locale.ROOT} for the
     * same reason, so that a failure reads identically on every machine. Grouping separators and
     * currency symbols are rejected rather than tolerated: the account-view screen is populated
     * by a move from an unedited {@code PIC S9(10)V99} field, so it never produces them, and the
     * currency-tolerant conversion belongs to the transaction-add input path instead.</p>
     *
     * <p>The result is scaled to {@value #MONEY_SCALE} fractional digits using
     * {@link #MONEY_ROUNDING} and is rejected if it needs more than
     * {@value #MONEY_PRECISION} significant digits, which is the ceiling the underlying
     * {@code PIC S9(10)V99} field imposes. Negative amounts are returned as negative values and
     * are never normalised to an absolute value: the current cycle debit legitimately
     * accumulates negative amounts, and the over-limit arithmetic of the posting path depends on
     * that sign surviving.</p>
     *
     * <p>Compare two results with {@code BigDecimal.compareTo} and never with
     * {@code BigDecimal.equals}, because {@code equals} also compares scale and would report two
     * equal amounts as different.</p>
     *
     * @param displayText the monetary display text taken from one of this record's five monetary
     *                    components; may be {@code null}, blank or binary zeros, each of which
     *                    is treated as an absent amount
     * @param fieldName   the name of the component being converted, used to identify the
     *                    offending field in a diagnostic message without ever quoting its value;
     *                    must be neither {@code null} nor blank
     * @return the amount scaled to {@value #MONEY_SCALE} fractional digits, or an empty result
     *         when the field carries no value
     * @throws IllegalArgumentException if {@code fieldName} is {@code null} or blank; if
     *                                  {@code displayText} is longer than the
     *                                  {@value #MONEY_DISPLAY_LENGTH} bytes this map declares
     *                                  and so cannot have come from it; if the text does not
     *                                  denote a decimal number, in which case the originating
     *                                  {@code NumberFormatException} is preserved as the cause;
     *                                  or if the amount needs more than
     *                                  {@value #MONEY_PRECISION} significant digits. The message
     *                                  names the field and never reproduces its value, because
     *                                  several fields on this payload are protected data.
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
     * <p>This override exists for a security reason and not a cosmetic one. A record's generated
     * {@code toString} emits every component, so leaving it in place would put the social
     * security number, the date of birth, both telephone numbers, the government-issued
     * identifier, the electronic funds transfer account identifier and all three customer names
     * into any log line, stack trace or error page that rendered this payload. Central log
     * masking is configured as a second line of defence, but the defence that matters is never
     * emitting the values at all.</p>
     *
     * <p>Only the account identifier, the account status and the program name are rendered, and
     * the rendering states plainly that fields were withheld so that a reader does not mistake
     * it for a complete dump.</p>
     *
     * @return a rendering containing only the account identifier, the account status and the
     *         program name, any of which may read {@code null} when its screen field was not
     *         populated
     */
    @Override
    public String toString() {
        return "AccountDto[accountId=" + accountId
                + ", accountStatus=" + accountStatus
                + ", programName=" + programName
                + ", protectedFieldsOmitted=true]";
    }
}
