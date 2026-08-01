/*
 * ******************************************************************
 * Program     : TransactionDto.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Transaction detail projection plus ten-row list projection; page size 10.
 * Source      : app/cpy-bms/COTRN01.CPY (21 fields) @ 7756d89
 * Source      : app/cpy-bms/COTRN00.CPY (59 fields) @ 7756d89
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

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outbound projection of the two CardDemo transaction enquiry screens: the transaction detail view and the
 * paged transaction list.
 *
 * <p>The two maps are not independent screens over unrelated data; they are a summary and a detail rendering of
 * the same {@code TRAN-RECORD} declared at {@code app/cpy/CVTRA05Y.cpy}:4-18. Eight of the nine top-level list
 * fields are declared identically on both maps - the six-field header, the {@code TRNIDINI} search key and the
 * {@code ERRMSGI} trailer, each with the same name and the same width - so a second type would restate them,
 * and restating a field contract is how the two copies later disagree. The list projection therefore
 * contributes exactly one top-level field the detail projection lacks, {@code PAGENUMI}, plus the ten nested
 * rows. Declaring one type keeps a single authority for every shared width and holds the package to its budget
 * of eighteen files.
 *
 * <p>It is a pure data holder. It parses nothing, formats nothing, truncates nothing, and maps nothing; it
 * performs no paging arithmetic and issues no query. The DTO transports, the service projects.
 *
 * <p><b>What this type deliberately is not.</b> It is a pure data holder. It parses nothing, formats
 * nothing, truncates nothing, and maps nothing; it performs no paging arithmetic and issues no query.
 * The DTO transports, the service projects. Every component is assigned once by the canonical
 * constructor, the row list is defensively copied on the way in and on the way out, and the type is a
 * {@code record}, hence final, so an instance is safe to publish across threads with no further
 * synchronisation. There are no side effects of any kind.</p>
 *
 * <p><b>Absent, blank and populated are three distinct states.</b> {@code null} means the field was
 * not supplied, the empty string means it was supplied and is empty, and a blank-padded value means
 * the screen held spaces. Nothing here collapses those states: {@code null} is never coerced to
 * {@code ""}, {@code ""} is never coerced to {@code null}, and no component is trimmed, upper-cased
 * or lower-cased on the way in. The legacy screens distinguish these cases - {@code COTRN01C.cbl}:159
 * moves {@code SPACES} into {@code TRNIDI} to clear it, which is a populated blank rather than an
 * absence - so flattening them would discard information the source carries.</p>
 *
 * <p><b>Distinction 5 - two description widths, both required.</b> The same persisted description is
 * rendered at two different widths, and both are carried here because neither can be derived from the
 * other:</p>
 * <ul>
 *   <li>{@code app/cpy-bms/COTRN01.CPY}:96 declares {@code TDESCI} as {@code PIC X(60)}. The detail
 *       screen shows sixty characters.</li>
 *   <li>{@code app/cpy-bms/COTRN00.CPY} declares {@code TDESC01I} through {@code TDESC10I} as
 *       {@code PIC X(26)} at lines 90, 120, 150, 180, 210, 240, 270, 300, 330 and 360. Each list row
 *       shows twenty-six characters.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy}:9 declares the persisted {@code TRAN-DESC} as
 *       {@code PIC X(100)}.</li>
 * </ul>
 * <p>Both screen widths are therefore truncations, at different lengths, of one hundred persisted
 * characters. {@code app/cbl/COTRN01C.cbl}:184 performs the first of them, moving the
 * {@code PIC X(100)} field into the {@code PIC X(60)} field so that COBOL discards the trailing forty
 * characters. The widths are <b>not</b> unified to 60, to 26 or to 100, and the row description is
 * <b>not</b> computed from the top-level description at runtime: a twenty-six character prefix of a
 * sixty character prefix is not what the list screen displays, because the list screen truncates the
 * hundred-character original independently. Each width is validated against its own constant,
 * {@link #DESCRIPTION_LENGTH} and {@link #ROW_DESCRIPTION_LENGTH}, with
 * {@link #DESCRIPTION_PERSISTED_LENGTH} recording the original for reference.</p>
 *
 * <p><b>The merchant name and city are truncated the same way.</b>
 * {@code app/cpy/CVTRA05Y.cpy}:12-13 persists both as {@code PIC X(50)}, while
 * {@code app/cpy-bms/COTRN01.CPY}:126 and :132 declare {@code MNAMEI} as {@code PIC X(30)} and
 * {@code MCITYI} as {@code PIC X(25)}. The screen widths are authoritative here for the same reason
 * the description widths are.</p>
 *
 * <p><b>The transaction source is a {@code String} and never the {@code TransactionSource} enum.</b>
 * {@code app/cbl/COTRN02C.cbl}:454 reads {@code MOVE TRNSRCI OF COTRN2AI TO TRAN-SOURCE}: the screen
 * field moves straight into the record field with no validation, no table lookup and no domain check
 * whatsoever, and line 455 treats the description identically. The enum carries two constants - the
 * six-character {@code 'System'} written by the interest job and the eight-character
 * {@code 'POS TERM'} - both padded into {@code PIC X(10)}; but the source accepts <b>any</b> ten
 * characters on this path, so binding the field to an enum would convert an out-of-domain value into
 * a framework deserialisation failure and replace the legacy behaviour with an error the legacy
 * system never raises. Parity is the contract, so the field stays a {@code String}. The identical
 * reasoning governs {@code typeCode} at {@code PIC X(2)} and {@code categoryCode} at
 * {@code PIC X(4)}: raw text, no enum, no lookup at the DTO boundary. Note additionally that
 * {@code app/cpy/CVTRA05Y.cpy}:7 persists {@code TRAN-CAT-CD} as {@code PIC 9(04)} while
 * {@code app/cpy-bms/COTRN01.CPY}:84 declares the screen field as {@code PIC X(4)}, so a
 * character-typed component is also what preserves a leading zero such as {@code "0001"}.
 * Consequently <b>this file imports nothing from the model enum package</b>; see
 * {@code TransactionSource} there for the two constants the batch path does use.</p>
 *
 * <p><b>The amount is display text, with an optional arithmetic companion.</b>
 * {@code app/cpy-bms/COTRN01.CPY}:102 declares {@code TRNAMTI} as {@code PIC X(12)} and
 * {@code app/cpy-bms/COTRN00.CPY} declares {@code TAMT001I} through {@code TAMT010I} the same way, so
 * what the screen holds is an edited display rendering rather than a raw numeric. The rendering is
 * fixed by the mask {@code +99999999.99}, declared at {@code app/cbl/COTRN02C.cbl}:59 as
 * {@code 05 WS-TRAN-AMT-E PIC +99999999.99 VALUE ZEROS} beside its numeric partner at line 58,
 * {@code 05 WS-TRAN-AMT-N PIC S9(9)V99 VALUE ZERO}, and the round trip through them runs at lines
 * 385-386, {@code MOVE WS-TRAN-AMT-N TO WS-TRAN-AMT-E} followed by
 * {@code MOVE WS-TRAN-AMT-E TO TRNAMTI OF COTRN2AI}. The read path uses the same mask:
 * {@code app/cbl/COTRN01C.cbl}:49 declares {@code 05 WS-TRAN-AMT PIC +99999999.99} and lines 177 and
 * 183 move the persisted amount through it onto the screen. A mandatory sign, exactly eight integer
 * digits, a decimal point and two decimals total exactly twelve characters, which is precisely why
 * the field is {@code PIC X(12)}. <b>The formatting itself belongs to the service, not to this
 * type</b>; the mask is documented here so the response echo can be made to match.</p>
 *
 * <p><b>The arithmetic companion is not a wire field. (High, resolved.)</b> {@code amountValue} has no
 * counterpart in any symbolic map: {@code app/cpy-bms/COTRN01.CPY} declares 21 input fields and
 * {@code app/cpy-bms/COTRN00.CPY} declares 59, and neither includes a numeric amount. It exists only so
 * that a caller needing to compute rather than render has somewhere to put the value. It was previously
 * emitted as a JSON property, which invented a twenty-fourth field the contract does not contain and
 * offered a second, differently-formatted representation of the same money alongside {@code amount} - two
 * renderings of one value that a consumer could read inconsistently, since the display mask carries only
 * eight integer digits while this carries nine. It is therefore annotated {@code @JsonIgnore}: the
 * canonical constructor still accepts it and {@link #amountValue()} still returns it, so every in-process
 * use is unaffected, but it is neither serialised nor bound from a request body. {@code amount} remains
 * the only amount on the wire, exactly as the map declares.</p>
 *
 * <p><b>Amount arithmetic contract.</b> Where a numeric value is needed rather than a rendering, this
 * type carries {@code amountValue} as a {@link java.math.BigDecimal} at scale
 * {@value #AMOUNT_SCALE} and precision {@value #AMOUNT_PRECISION}, because
 * {@code app/cpy/CVTRA05Y.cpy}:10 declares {@code TRAN-AMT} as {@code PIC S9(09)V99}, which maps to
 * {@code NUMERIC(11,2)}. That is the <b>transaction</b> money precision and is deliberately distinct
 * from the account money precision of {@code NUMERIC(12,2)} that {@code PIC S9(10)V99} yields
 * elsewhere in the model. <b>No {@code float}, {@code double}, {@code Float} or {@code Double}
 * appears anywhere in this file</b>, which the security gate asserts by inspection. Amounts are
 * rounded {@link RoundingMode#HALF_EVEN} and must be compared with
 * {@link BigDecimal#compareTo(BigDecimal)} and never with {@link BigDecimal#equals(Object)}, because
 * {@code equals} additionally compares scale and would report two numerically equal amounts as
 * different. The sign is data: the daily fixture carries both positive and negative zoned-decimal
 * overpunch signs, so no absolute value is applied anywhere on this path.</p>
 *
 * <p><b>Two parsers exist in the source and must not be conflated.</b>
 * {@code app/cbl/COTRN02C.cbl} converts identifiers with plain {@code FUNCTION NUMVAL} - lines
 * 204-205 for the account identifier and lines 218-219 for the card number - but converts amounts
 * with {@code FUNCTION NUMVAL-C} at lines 383-384 and again at lines 456-457. The currency-aware form
 * additionally tolerates a currency symbol and thousands separators, so using one parser for both
 * accepts input the legacy system rejects, or rejects input it accepts. This type does not parse; it
 * simply declines to force a single representation that would make the distinction inexpressible,
 * which is why the amount is carried as text and the numeric companion is supplied by the caller that
 * chose the correct parser. Any service that does parse or format must pass {@link java.util.Locale}
 * {@code ROOT}: the platform default locale emits a comma decimal separator in some locales and would
 * silently break the parity baseline.</p>
 *
 * <p><b>Dates and timestamps are text, never a temporal type.</b> {@code originatingDate} and
 * {@code processingDate} are {@code PIC X(10)} at {@code app/cpy-bms/COTRN01.CPY}:108 and :114, and
 * each list row's date is {@code PIC X(8)}. All three are {@code String}. The persisted
 * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} at
 * {@code app/cpy/CVTRA05Y.cpy}:16-17 and are carried as text package-wide, never as a date, a
 * timestamp or an instant. Four verified facts force that representation:</p>
 * <ul>
 *   <li>The batch-generated timestamp is hundredths-of-a-second precision padded to twenty-six
 *       characters, so its final four digits are always zeros; a nanosecond-capable temporal type would
 *       render a different string. The fractional field is {@code DB2-MIL PIC 9(002)} at
 *       {@code app/cbl/CBTRN02C.cbl}:173, which is why the precision is hundredths and not
 *       milliseconds. {@code app/cpy/CSDAT01Y.cpy}:42-55 fixes the shape as four year digits, a
 *       dash, two month digits, a dash, two day digits, a space, then hours, minutes and seconds
 *       separated by colons and six fractional digits after a period.</li>
 *   <li>Expiry validation in the posting path compares the first ten characters as a <b>string</b>,
 *       not as a date, so the comparison semantics are lexicographic rather than chronological.</li>
 *   <li>The statement projection delivers a processing timestamp holding only twenty-four significant
 *       characters in a twenty-six byte field, which is not a parseable timestamp at all.</li>
 *   <li>The two screen renderings are different <b>formats</b>, not merely different widths. The
 *       detail fields are the leading ten characters of the persisted timestamp, so
 *       {@code YYYY-MM-DD} with dashes, as {@code app/cbl/COTRN01C.cbl}:185-186 shows by moving the
 *       {@code PIC X(26)} field into a {@code PIC X(10)} field. The list row date is rebuilt instead:
 *       {@code app/cbl/COTRN00C.cbl}:384-388 takes the last two year digits, the month and the day
 *       from the originating timestamp into {@code WS-CURDATE-MM-DD-YY}, whose group at
 *       {@code app/cpy/CSDAT01Y.cpy}:30-35 interleaves {@code '/'} separators, and moves the result
 *       into {@code WS-TRAN-DATE}, declared at {@code app/cbl/COTRN00C.cbl}:57 as
 *       {@code PIC X(08) VALUE '00/00/00'}. The row therefore shows {@code MM/DD/YY} with slashes and
 *       a two-digit year.</li>
 * </ul>
 * <p>A single temporal type would normalise away every one of those characteristics, and no temporal
 * type can hold two separator conventions and two century policies at once.</p>
 *
 * <p><b>The header sextet is declared inline, on purpose.</b> Six fields recur on every one of the
 * seventeen symbolic maps: {@code TRNNAME} at {@code PIC X(4)}, {@code TITLE01} at {@code PIC X(40)},
 * {@code CURDATE} at {@code PIC X(8)}, {@code PGMNAME} at {@code PIC X(8)}, {@code TITLE02} at
 * {@code PIC X(40)} and {@code CURTIME}. They are <b>not</b> extracted into a shared base class,
 * interface, mixin or helper, and the reason is factual rather than stylistic: the sixth field's width
 * is not constant across the corpus. Both maps this type serves declare {@code CURTIMEI} as
 * {@code PIC X(8)}, at {@code app/cpy-bms/COTRN01.CPY}:54 and {@code app/cpy-bms/COTRN00.CPY}:54, but
 * {@code app/cpy-bms/COSGN00.CPY}:54 alone declares it as {@code PIC X(9)}. A shared abstraction would
 * have to pick one width and would therefore be <b>wrong</b> for whichever map it did not pick, not
 * merely redundant. Declaring the six fields inline lets each map state its own contract.</p>
 *
 * <p><b>Paging: what belongs here and what belongs to {@code PageResponse}.</b> The page-number field
 * itself is a screen field and is carried here as text, {@code PAGENUMI} at {@code PIC X(8)} from
 * {@code app/cpy-bms/COTRN00.CPY}:60, which matches {@code app/cpy-bms/COUSR00.CPY}:60 exactly. It
 * does <b>not</b> match the card list: {@code app/cpy-bms/COCRDLI.CPY}:60 declares {@code PAGENOI} as
 * {@code PIC X(3)}, a different name <b>and</b> a different width. That divergence is deliberately not
 * normalised here, because normalising it would misrepresent one of the two screens; the reconciled
 * paging-metadata contract, which carries the page number as an {@code int} for exactly this reason,
 * lives in {@code PageResponse} and is documented there in full.</p>
 *
 * <p><b>Page size is 10 and only 10.</b> {@link #PAGE_SIZE} is anchored on
 * {@code app/cbl/COTRN00C.cbl}:65, {@code 10 CDEMO-CT00-PAGE-NUM PIC 9(08).}, declared inside the
 * paging group whose next-page flag follows at line 66 as
 * {@code 10 CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'.} with {@code 88 NEXT-PAGE-YES VALUE 'Y'.}
 * at line 67 and {@code 88 NEXT-PAGE-NO VALUE 'N'.} at line 68; the ten-row table of
 * {@code app/cpy-bms/COTRN00.CPY} corroborates it independently. <b>The card list's 7 and the batch
 * transaction report's 20 lines per page never appear in this file.</b> The report figure in
 * particular is a printed-page line count belonging to a batch program and is not a page size at
 * all.</p>
 *
 * <p><b>A genuine next-page divergence, documented rather than unified.</b> The two reference paging
 * programs disagree on the sentinel for "no next page". {@code app/cbl/COTRN00C.cbl}:66-68 uses the
 * literal {@code 'N'}. {@code app/cbl/COCRDLIC.cbl}:243 instead declares
 * {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.} over the {@code PIC X(1)} indicator at line
 * 242 - binary zeros, not spaces and not {@code 'N'} - with {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.}
 * at line 244, which makes that field a three-state indicator: binary zeros, {@code 'Y'}, or anything
 * else. No sentinel character is transported by this type at all, so neither convention is
 * privileged; the boolean next-page indicator lives on {@code PageResponse} and each adapter maps it
 * onto whichever sentinel its own screen requires.</p>
 *
 * <p><b>Row ordering is positional and deterministic.</b> The ten row slots are the screen's row
 * one through row ten, in that order. Rows are held in a {@link java.util.List} and returned in
 * exactly the order supplied. <b>No hash-ordered collection governs row order anywhere in this
 * type</b> - there is no {@code Map} and no {@code Set} - so iteration order cannot vary between
 * runs, between JVMs or between serialisations.</p>
 *
 * <p><b>Personally identifiable information.</b> The detail projection carries a card number,
 * {@code CARDNUMI} at {@code app/cpy-bms/COTRN01.CPY}:72, and every list row carries a transaction
 * identifier that is card-adjacent. {@link #toString()} is therefore overridden to emit only the
 * transaction identifier and the program name, and {@link TransactionListRow#toString()} to emit only
 * its transaction identifier. <b>The card number appears in no diagnostic rendering: not in full, not
 * masked, and not as a last-four.</b> The override exists precisely because the rendering a
 * {@code record} generates by default would publish every component including the card number, which
 * would make an accidental log statement a disclosure. A central masking rule would be a second line
 * of defence, but no {@code logback-spring.xml} exists under {@code src/main/resources} yet, so these
 * overrides are the only defence rather than the first of two.
 * The merchant name, city and postal code are not on the never-emit list, yet they are still kept out
 * of validation messages: no failure message raised here quotes a field value. This type carries no
 * password, no hash, no token and no signing key, and none may be added.</p>
 *
 * <p><b>Findings, classified by severity.</b></p>
 * <ul>
 *   <li><b>Medium, closed - corpus census correction.</b> Prior-generation plan prose stated that the
 *       seventeen symbolic maps carry 460 input fields in total. A field-by-field count of all
 *       seventeen files in {@code app/cpy-bms} returns <b>441</b>. The two maps this type implements
 *       are correct as stated, {@code COTRN01.CPY} at 21 and {@code COTRN00.CPY} at 59, so the
 *       discrepancy never affected this file. The count that differed by more than rounding is
 *       {@code COACTVW.CPY}, which holds 37 rather than the 36 the prior prose gave. The remediation
 *       has been applied: {@code docs/technical-specifications.md} publishes 441 and 37 and lists both
 *       supersessions in its section 0.2.2.1 corrections table, verified on 1 August 2026. No gate in
 *       the pinned build asserts either figure, because no gate is wired yet. This type implements the
 *       verified figures.</li>
 *   <li><b>Low - screen picture versus persisted picture.</b> Three fields are persisted numeric and
 *       displayed as characters: {@code TRAN-CAT-CD} at {@code PIC 9(04)} against {@code TCATCDI} at
 *       {@code PIC X(4)}, and {@code TRAN-MERCHANT-ID} at {@code PIC 9(09)} against {@code MIDI} at
 *       {@code PIC X(9)}. Modelling them as text is the remediation, not the defect: it is what keeps
 *       a leading zero intact.</li>
 *   <li><b>Closed - the package contract document now exists.</b> {@code package-info.java} for this
 *       package was not present when this file was authored and is now on disk, so the earlier
 *       "not available" record is withdrawn. This file has been re-read against it and the two agree;
 *       the contract originally taken from the migration plan and the sibling types in this package is
 *       unchanged by that re-read. The field widths are additionally asserted against
 *       {@code app/cpy-bms/COTRN01.CPY} by the {@code BmsSymbolicMap} test oracle, so the contract now
 *       rests on the frozen corpus rather than on agreement among generated files.</li>
 * </ul>
 *
 * <p><b>Error modes.</b> Nothing is thrown during normal operation, and no exception is ever
 * swallowed. The canonical constructors reject structurally impossible arguments with
 * {@link IllegalArgumentException}, and there are exactly four such conditions: a component longer
 * than the COBOL width it represents; a row list holding a {@code null} element; a row list longer
 * than {@value #PAGE_SIZE} entries, which no ten-slot screen can display; and an amount whose
 * magnitude needs more than {@value #AMOUNT_INTEGER_DIGITS} integer digits, which
 * {@code PIC S9(09)V99} cannot hold. <b>Every message names the offending field and reports the
 * limit, never the value</b>, so that a card number cannot reach a log through an exception message.
 * A {@code null} component is always accepted, because absence is meaningful. There is no other
 * failure mode: no I/O, no clock, no locale and no configuration is touched.</p>
 *
 * <p><b>Building, testing, configuration and troubleshooting.</b> This type belongs to the single
 * Maven module at the repository root. Build it with {@code ./mvnw -B clean compile} and exercise it
 * with {@code ./mvnw -B clean test}; the module compiles under {@code -Xlint:all} with {@code -Werror}
 * and {@code failOnWarning}, so any warning introduced here fails the build rather than being
 * reported. Unit tests for this type belong under {@code src/test/java/com/cardemo/unit/model}, not
 * beside it, and <strong>none exists at this commit</strong> - measured 1 August 2026 there is no
 * {@code TransactionDtoTest} and this type is not referenced anywhere under {@code src/test/java}.
 * There is nothing to configure: the type reads no property, no environment variable and
 * no configuration file, and the only defaults it publishes are the constants declared below.
 * Round-tripping through JSON relies on the build's {@code -parameters} compiler flag together with
 * the parameter-names module the framework registers by default, which is why the type declares
 * exactly one canonical constructor and no serialisation annotation. The failure most likely to be
 * seen in practice is an {@link IllegalArgumentException} from a service that assembled a value wider
 * than its screen field, typically by passing a persisted hundred-character description straight into
 * the sixty-character detail field or the twenty-six-character row field; the remedy is to project in
 * the service, since this type deliberately will not truncate on the caller's behalf. This type does
 * not implement {@link java.io.Serializable}: Java serialisation is a risky deserialisation surface
 * and nothing here needs it.</p>
 *
 * @param transactionName    {@code TRNNAMEI}, {@code PIC X(4)}, {@code app/cpy-bms/COTRN01.CPY}:24
 *                           and {@code app/cpy-bms/COTRN00.CPY}:24. The CICS transaction identifier
 *                           shown in the screen header. May be {@code null}; must not exceed 4
 *                           characters.
 * @param title01            {@code TITLE01I}, {@code PIC X(40)}, {@code app/cpy-bms/COTRN01.CPY}:30
 *                           and {@code app/cpy-bms/COTRN00.CPY}:30. First header title line. May be
 *                           {@code null}; must not exceed 40 characters.
 * @param currentDate        {@code CURDATEI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN01.CPY}:36
 *                           and {@code app/cpy-bms/COTRN00.CPY}:36. The header date the screen
 *                           displayed, text rather than a temporal type. May be {@code null}; must
 *                           not exceed 8 characters.
 * @param programName        {@code PGMNAMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN01.CPY}:42
 *                           and {@code app/cpy-bms/COTRN00.CPY}:42. The COBOL program name shown in
 *                           the header; one of the two components {@link #toString()} may emit. May
 *                           be {@code null}; must not exceed 8 characters.
 * @param title02            {@code TITLE02I}, {@code PIC X(40)}, {@code app/cpy-bms/COTRN01.CPY}:48
 *                           and {@code app/cpy-bms/COTRN00.CPY}:48. Second header title line. May be
 *                           {@code null}; must not exceed 40 characters.
 * @param currentTime        {@code CURTIMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN01.CPY}:54
 *                           and {@code app/cpy-bms/COTRN00.CPY}:54. The header time, text rather than
 *                           a temporal type. Note that {@code app/cpy-bms/COSGN00.CPY}:54 declares
 *                           this field as {@code PIC X(9)} instead, which is why no shared header
 *                           abstraction exists. May be {@code null}; must not exceed 8 characters.
 * @param transactionIdInput {@code TRNIDINI}, {@code PIC X(16)}, {@code app/cpy-bms/COTRN01.CPY}:60
 *                           and {@code app/cpy-bms/COTRN00.CPY}:60. The <b>search key</b> the operator
 *                           typed, echoed back so the response is self-describing. This is a distinct
 *                           field from {@code transactionId} and the two are deliberately not
 *                           collapsed: the key may be present while no record was found. May be
 *                           {@code null}; must not exceed 16 characters.
 * @param transactionId      {@code TRNIDI}, {@code PIC X(16)}, {@code app/cpy-bms/COTRN01.CPY}:66.
 *                           The <b>retrieved</b> identifier, populated by
 *                           {@code app/cbl/COTRN01C.cbl}:178 from {@code TRAN-ID}; line 159 clears it
 *                           to {@code SPACES} when no record is displayed. One of the two components
 *                           {@link #toString()} may emit. May be {@code null}; must not exceed 16
 *                           characters.
 * @param cardNumber         {@code CARDNUMI}, {@code PIC X(16)}, {@code app/cpy-bms/COTRN01.CPY}:72,
 *                           populated by {@code app/cbl/COTRN01C.cbl}:179 from {@code TRAN-CARD-NUM}.
 *                           <b>Personally identifiable information: never rendered by
 *                           {@link #toString()} and never quoted in a failure message.</b> May be
 *                           {@code null}; must not exceed 16 characters.
 * @param typeCode           {@code TTYPCDI}, {@code PIC X(2)}, {@code app/cpy-bms/COTRN01.CPY}:78,
 *                           populated by {@code app/cbl/COTRN01C.cbl}:180. Raw text, deliberately not
 *                           an enum and not validated against a lookup table at this boundary. May be
 *                           {@code null}; must not exceed 2 characters.
 * @param categoryCode       {@code TCATCDI}, {@code PIC X(4)}, {@code app/cpy-bms/COTRN01.CPY}:84,
 *                           populated by {@code app/cbl/COTRN01C.cbl}:181. Text despite the persisted
 *                           {@code PIC 9(04)} of {@code app/cpy/CVTRA05Y.cpy}:7, so a leading zero
 *                           such as {@code "0001"} survives. May be {@code null}; must not exceed 4
 *                           characters.
 * @param source             {@code TRNSRCI}, {@code PIC X(10)}, {@code app/cpy-bms/COTRN01.CPY}:90,
 *                           populated by {@code app/cbl/COTRN01C.cbl}:182. An unconstrained
 *                           {@code String}, <b>never</b> the {@code TransactionSource} enum, because
 *                           {@code app/cbl/COTRN02C.cbl}:454 moves this field into the record with no
 *                           domain check at all. May be {@code null}; must not exceed 10 characters.
 * @param description        {@code TDESCI}, {@code PIC X(60)},
 *                           {@code app/cpy-bms/COTRN01.CPY}:96 - the <b>detail</b> width. Populated by
 *                           {@code app/cbl/COTRN01C.cbl}:184, which truncates the
 *                           {@code PIC X(100)} {@code TRAN-DESC} of {@code app/cpy/CVTRA05Y.cpy}:9.
 *                           Independent of {@link TransactionListRow#description()}, which is
 *                           {@code PIC X(26)}. May be {@code null}; must not exceed 60 characters.
 * @param amount             {@code TRNAMTI}, {@code PIC X(12)},
 *                           {@code app/cpy-bms/COTRN01.CPY}:102. The <b>display rendering</b> under
 *                           the mask {@value #AMOUNT_EDITED_MASK}, twelve characters wide, populated
 *                           by {@code app/cbl/COTRN01C.cbl}:177 and :183. Carried verbatim and never
 *                           reformatted here. May be {@code null}; must not exceed 12 characters.
 * @param originatingDate    {@code TORIGDTI}, {@code PIC X(10)},
 *                           {@code app/cpy-bms/COTRN01.CPY}:108. The leading ten characters of the
 *                           {@code PIC X(26)} {@code TRAN-ORIG-TS}, so {@code YYYY-MM-DD} with
 *                           dashes, moved by {@code app/cbl/COTRN01C.cbl}:185. Text, never a temporal
 *                           type. May be {@code null}; must not exceed 10 characters.
 * @param processingDate     {@code TPROCDTI}, {@code PIC X(10)},
 *                           {@code app/cpy-bms/COTRN01.CPY}:114. The leading ten characters of the
 *                           {@code PIC X(26)} {@code TRAN-PROC-TS}, moved by
 *                           {@code app/cbl/COTRN01C.cbl}:186. Text, never a temporal type. May be
 *                           {@code null}; must not exceed 10 characters.
 * @param merchantId         {@code MIDI}, {@code PIC X(9)}, {@code app/cpy-bms/COTRN01.CPY}:120. Text
 *                           despite the persisted {@code PIC 9(09)} of
 *                           {@code app/cpy/CVTRA05Y.cpy}:11, so {@code "000000001"} survives. May be
 *                           {@code null}; must not exceed 9 characters.
 * @param merchantName       {@code MNAMEI}, {@code PIC X(30)}, {@code app/cpy-bms/COTRN01.CPY}:126, a
 *                           truncation of the persisted {@code PIC X(50)} of
 *                           {@code app/cpy/CVTRA05Y.cpy}:12. May be {@code null}; must not exceed 30
 *                           characters.
 * @param merchantCity       {@code MCITYI}, {@code PIC X(25)}, {@code app/cpy-bms/COTRN01.CPY}:132, a
 *                           truncation of the persisted {@code PIC X(50)} of
 *                           {@code app/cpy/CVTRA05Y.cpy}:13. May be {@code null}; must not exceed 25
 *                           characters.
 * @param merchantZip        {@code MZIPI}, {@code PIC X(10)}, {@code app/cpy-bms/COTRN01.CPY}:138.
 *                           May be {@code null}; must not exceed 10 characters.
 * @param errorMessage       {@code ERRMSGI}, {@code PIC X(78)},
 *                           {@code app/cpy-bms/COTRN01.CPY}:144 and
 *                           {@code app/cpy-bms/COTRN00.CPY}:372. The screen's error line, carried
 *                           verbatim so the legacy wording survives. May be {@code null}; must not
 *                           exceed 78 characters.
 * @param pageNumber         {@code PAGENUMI}, {@code PIC X(8)},
 *                           {@code app/cpy-bms/COTRN00.CPY}:60. <b>List projection only</b>; the
 *                           detail map declares no counterpart. Text, matching the screen field;
 *                           {@code PageResponse} carries the reconciled numeric page number. May be
 *                           {@code null}; must not exceed 8 characters.
 * @param rows               The ten row slots of the list projection, {@code SEL0001I} at
 *                           {@code app/cpy-bms/COTRN00.CPY}:72 through {@code TAMT010I} at line 366,
 *                           in positional screen order. <b>List projection only.</b> {@code null}
 *                           means this instance carries no list projection, which is what a detail
 *                           response looks like; an empty list means a list projection that displayed
 *                           no rows. Those two states are distinct and are never conflated. When
 *                           non-{@code null} the list is defensively copied and exposed unmodifiable,
 *                           must contain no {@code null} element, and must hold at most
 *                           {@value #PAGE_SIZE} entries.
 * @param amountValue        The arithmetic companion to {@code amount}, at scale
 *                           {@value #AMOUNT_SCALE} and precision {@value #AMOUNT_PRECISION} from the
 *                           {@code PIC S9(09)V99} of {@code app/cpy/CVTRA05Y.cpy}:10. Supplied by the
 *                           caller, which alone knows whether the currency-aware parser applied;
 *                           never parsed from {@code amount} here. Normalised to scale
 *                           {@value #AMOUNT_SCALE} with {@link RoundingMode#HALF_EVEN}, sign
 *                           preserved. May be {@code null} when no numeric value is offered; the
 *                           magnitude must fit {@value #AMOUNT_INTEGER_DIGITS} integer digits.
 *                           <b>Annotated {@code @JsonIgnore}: it is an in-process companion, not a
 *                           wire field.</b> No symbolic map declares it, so serialising it would
 *                           publish a field the field contract does not contain - see the
 *                           "arithmetic companion" section on this type.
 */
public record TransactionDto(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String transactionIdInput,
        String transactionId,
        String cardNumber,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        String amount,
        String originatingDate,
        String processingDate,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String errorMessage,
        String pageNumber,
        List<TransactionListRow> rows,

        @JsonIgnore
        BigDecimal amountValue) {

    /**
     * Rows displayed per page by the transaction list, namely 10, from the row loop bound
     * {@code UNTIL WS-IDX > 10} at {@code app/cbl/COTRN00C.cbl:290}.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * Fields in the transaction detail projection, namely 21, counted inside the {@code 01 COTRN1AI} input group
     * at {@code app/cpy-bms/COTRN01.CPY:17-144}.
     */
    public static final int DETAIL_FIELD_COUNT = 21;

    /**
     * Top-level fields preceding the row table on the list map, namely 8: {@code app/cpy-bms/COTRN00.CPY:24}
     * through :60, the last of them {@code PAGENUMI}.
     */
    public static final int LIST_PREAMBLE_FIELD_COUNT = 8;

    /**
     * Fields in one list row, namely 5, the ten rows spanning {@code app/cpy-bms/COTRN00.CPY:78-366}.
     */
    public static final int LIST_ROW_FIELD_COUNT = 5;

    /**
     * Top-level fields following the row table on the list map, namely 1: {@code ERRMSGI} at
     * {@code app/cpy-bms/COTRN00.CPY:372}.
     */
    public static final int LIST_TRAILER_FIELD_COUNT = 1;

    /**
     * Fields in the transaction list projection, namely 59.
     */
    public static final int LIST_FIELD_COUNT =
            LIST_PREAMBLE_FIELD_COUNT + (PAGE_SIZE * LIST_ROW_FIELD_COUNT) + LIST_TRAILER_FIELD_COUNT;

    /**
     * {@code TRNNAMEI}, {@code PIC X(4)}, {@code app/cpy-bms/COTRN01.CPY}:24.
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * {@code TITLE01I} and {@code TITLE02I}, both {@code PIC X(40)}, {@code app/cpy-bms/COTRN01.CPY}:30 and
     * :48. One constant because the two widths are genuinely identical on both maps, not because either was
     * assumed.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CURDATEI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN01.CPY}:36.
     */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN01.CPY}:42.
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * {@code CURTIMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN01.CPY}:54 and
     * {@code app/cpy-bms/COTRN00.CPY}:54.
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * {@code TRNIDINI}, {@code TRNIDI} and every row's {@code TRNIDnnI}, all {@code PIC X(16)},
     * {@code app/cpy-bms/COTRN01.CPY}:60 and :66 and {@code app/cpy-bms/COTRN00.CPY}:66, 78, 108, 138, 168,
     * 198, 228, 258, 288, 318 and 348. Matches the persisted {@code TRAN-ID} of {@code app/cpy/CVTRA05Y.cpy}:5,
     * so no truncation occurs on this field.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * {@code CARDNUMI}, {@code PIC X(16)}, {@code app/cpy-bms/COTRN01.CPY}:72, matching the persisted
     * {@code TRAN-CARD-NUM} of {@code app/cpy/CVTRA05Y.cpy}:15. Personally identifiable information.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * {@code TTYPCDI}, {@code PIC X(2)}, {@code app/cpy-bms/COTRN01.CPY}:78.
     */
    public static final int TYPE_CODE_LENGTH = 2;

    /**
     * {@code TCATCDI}, {@code PIC X(4)}, {@code app/cpy-bms/COTRN01.CPY}:84. The persisted field is
     * {@code PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy}:7; text preserves a leading zero.
     */
    public static final int CATEGORY_CODE_LENGTH = 4;

    /**
     * {@code TRNSRCI}, {@code PIC X(10)}, {@code app/cpy-bms/COTRN01.CPY}:90, matching the persisted
     * {@code TRAN-SOURCE} of {@code app/cpy/CVTRA05Y.cpy}:8. Ten unconstrained characters, never an enum
     * domain.
     */
    public static final int SOURCE_LENGTH = 10;

    /**
     * {@code TDESCI}, {@code PIC X(60)}, {@code app/cpy-bms/COTRN01.CPY}:96 - the <b>detail</b> description
     * width. Distinct from {@link #ROW_DESCRIPTION_LENGTH} and from {@link #DESCRIPTION_PERSISTED_LENGTH}; see
     * the class documentation for why all three exist.
     */
    public static final int DESCRIPTION_LENGTH = 60;

    /**
     * {@code TDESC01I} through {@code TDESC10I}, all {@code PIC X(26)}, {@code app/cpy-bms/COTRN00.CPY}:90,
     * 120, 150, 180, 210, 240, 270, 300, 330 and 360 - the <b>list row</b> description width. Never derived
     * from {@link #DESCRIPTION_LENGTH}: both are independent truncations of the same persisted field.
     */
    public static final int ROW_DESCRIPTION_LENGTH = 26;

    /**
     * {@code TRAN-DESC}, {@code PIC X(100)}, {@code app/cpy/CVTRA05Y.cpy}:9 - the <b>persisted</b> description
     * width, recorded so the two screen truncations are traceable to their origin. No component of this type
     * accepts 100 characters.
     */
    public static final int DESCRIPTION_PERSISTED_LENGTH = 100;

    /**
     * {@code TRNAMTI} and every row's {@code TAMT0nnI}, all {@code PIC X(12)},
     * {@code app/cpy-bms/COTRN01.CPY}:102 and {@code app/cpy-bms/COTRN00.CPY}:96, 126, 156, 186, 216, 246, 276,
     * 306, 336 and 366. Twelve characters is exactly the width of {@value #AMOUNT_EDITED_MASK}.
     */
    public static final int AMOUNT_DISPLAY_LENGTH = 12;

    /**
     * {@code TORIGDTI} and {@code TPROCDTI}, both {@code PIC X(10)}, {@code app/cpy-bms/COTRN01.CPY}:108 and
     * :114. The leading ten characters of a {@value #PERSISTED_TIMESTAMP_LENGTH}-character timestamp, rendered
     * {@code YYYY-MM-DD}.
     */
    public static final int DETAIL_DATE_LENGTH = 10;

    /**
     * {@code TDATE01I} through {@code TDATE10I}, all {@code PIC X(8)}, {@code app/cpy-bms/COTRN00.CPY}:84, 114,
     * 144, 174, 204, 234, 264, 294, 324 and 354. Rendered {@code MM/DD/YY} with slash separators and a
     * two-digit year, per {@code app/cbl/COTRN00C.cbl}:57 and 384-388 with {@code app/cpy/CSDAT01Y.cpy}:30-35.
     * A different <b>format</b> from {@link #DETAIL_DATE_LENGTH}, not merely a different width.
     */
    public static final int ROW_DATE_LENGTH = 8;

    /**
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, both {@code PIC X(26)},
     * {@code app/cpy/CVTRA05Y.cpy}:16-17. Recorded so the screen date widths are traceable to the timestamps
     * they are prefixes of; no component of this type accepts 26 characters.
     */
    public static final int PERSISTED_TIMESTAMP_LENGTH = 26;

    /**
     * {@code MIDI}, {@code PIC X(9)}, {@code app/cpy-bms/COTRN01.CPY}:120. The persisted field is
     * {@code PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy}:11; text preserves leading zeros.
     */
    public static final int MERCHANT_ID_LENGTH = 9;

    /**
     * {@code MNAMEI}, {@code PIC X(30)}, {@code app/cpy-bms/COTRN01.CPY}:126, a truncation of the persisted
     * {@code PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy}:12.
     */
    public static final int MERCHANT_NAME_LENGTH = 30;

    /**
     * {@code MCITYI}, {@code PIC X(25)}, {@code app/cpy-bms/COTRN01.CPY}:132, a truncation of the persisted
     * {@code PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy}:13.
     */
    public static final int MERCHANT_CITY_LENGTH = 25;

    /**
     * {@code MZIPI}, {@code PIC X(10)}, {@code app/cpy-bms/COTRN01.CPY}:138, matching the persisted
     * {@code TRAN-MERCHANT-ZIP} of {@code app/cpy/CVTRA05Y.cpy}:14.
     */
    public static final int MERCHANT_ZIP_LENGTH = 10;

    /**
     * {@code ERRMSGI}, {@code PIC X(78)}, {@code app/cpy-bms/COTRN01.CPY}:144 and
     * {@code app/cpy-bms/COTRN00.CPY}:372.
     */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * {@code PAGENUMI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN00.CPY}:60, matching
     * {@code app/cpy-bms/COUSR00.CPY}:60 but <b>not</b> {@code app/cpy-bms/COCRDLI.CPY}:60, which declares
     * {@code PAGENOI} at {@code PIC X(3)}.
     */
    public static final int PAGE_NUMBER_LENGTH = 8;

    /**
     * {@code SEL0001I} through {@code SEL0010I}, all {@code PIC X(1)}, {@code app/cpy-bms/COTRN00.CPY}:72, 102,
     * 132, 162, 192, 222, 252, 282, 312 and 342. The per-row selection marker the operator types beside a row.
     */
    public static final int SELECTION_FLAG_LENGTH = 1;

    /**
     * Decimal places in a transaction amount, namely 2, from the {@code V99} of {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy}:10.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * Integer digits in a transaction amount, namely 9, from the {@code S9(09)} of {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy}:10.
     */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

    /**
     * Total significant digits in a transaction amount, namely 11, so the column type is {@code NUMERIC(11,2)}.
     */
    public static final int AMOUNT_PRECISION = AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE;

    /**
     * Rounding applied whenever an amount is rescaled, namely {@link RoundingMode#HALF_EVEN}.
     */
    public static final RoundingMode AMOUNT_ROUNDING_MODE = RoundingMode.HALF_EVEN;

    /**
     * The legacy edited display mask for an amount, {@code +99999999.99}, declared at
     * {@code app/cbl/COTRN02C.cbl:59}.
     */
    public static final String AMOUNT_EDITED_MASK = "+99999999.99";

    /**
     * Exclusive magnitude bound for an amount: the smallest value needing a tenth integer digit.
     */
    private static final BigDecimal AMOUNT_MAGNITUDE_LIMIT = new BigDecimal("1000000000");

    /**
     * Validates every declared width, takes an unmodifiable defensive copy of the row list and canonicalises
     * the amount's scale.
     *
     * @throws IllegalArgumentException if any component is longer than the COBOL field it represents, if
     * {@code rows} contains a {@code null} element, if {@code rows} holds more than {@value #PAGE_SIZE}
     * entries, or if {@code amountValue} needs more than {@value #AMOUNT_INTEGER_DIGITS} integer digits.
     */
    public TransactionDto {
        requireWithinWidth(transactionName, TRANSACTION_NAME_LENGTH, "transactionName");
        requireWithinWidth(title01, TITLE_LENGTH, "title01");
        requireWithinWidth(currentDate, CURRENT_DATE_LENGTH, "currentDate");
        requireWithinWidth(programName, PROGRAM_NAME_LENGTH, "programName");
        requireWithinWidth(title02, TITLE_LENGTH, "title02");
        requireWithinWidth(currentTime, CURRENT_TIME_LENGTH, "currentTime");
        requireWithinWidth(transactionIdInput, TRANSACTION_ID_LENGTH, "transactionIdInput");
        requireWithinWidth(transactionId, TRANSACTION_ID_LENGTH, "transactionId");
        requireWithinWidth(cardNumber, CARD_NUMBER_LENGTH, "cardNumber");
        requireWithinWidth(typeCode, TYPE_CODE_LENGTH, "typeCode");
        requireWithinWidth(categoryCode, CATEGORY_CODE_LENGTH, "categoryCode");
        requireWithinWidth(source, SOURCE_LENGTH, "source");
        requireWithinWidth(description, DESCRIPTION_LENGTH, "description");
        requireWithinWidth(amount, AMOUNT_DISPLAY_LENGTH, "amount");
        requireWithinWidth(originatingDate, DETAIL_DATE_LENGTH, "originatingDate");
        requireWithinWidth(processingDate, DETAIL_DATE_LENGTH, "processingDate");
        requireWithinWidth(merchantId, MERCHANT_ID_LENGTH, "merchantId");
        requireWithinWidth(merchantName, MERCHANT_NAME_LENGTH, "merchantName");
        requireWithinWidth(merchantCity, MERCHANT_CITY_LENGTH, "merchantCity");
        requireWithinWidth(merchantZip, MERCHANT_ZIP_LENGTH, "merchantZip");
        requireWithinWidth(errorMessage, ERROR_MESSAGE_LENGTH, "errorMessage");
        requireWithinWidth(pageNumber, PAGE_NUMBER_LENGTH, "pageNumber");
        rows = copyRows(rows);
        amountValue = normaliseAmount(amountValue);
    }

    /**
     * Returns the list row slots in positional screen order, row one first.
     *
     * @return an unmodifiable, order-preserving view of the row slots, or {@code null} if this instance carries
     * no list projection
     */
    @Override
    public List<TransactionListRow> rows() {
        return rows == null ? null : Collections.unmodifiableList(rows);
    }

    /**
     * Returns the {@value #DETAIL_FIELD_COUNT} detail fields in the declaration order of
     * {@code app/cpy-bms/COTRN01.CPY}, from {@code TRNNAMEI} at line 24 to {@code ERRMSGI} at line 144.
     *
     * @return an unmodifiable list of exactly {@value #DETAIL_FIELD_COUNT} values, in map declaration order,
     * possibly containing {@code null} elements
     */
    public List<String> detailProjection() {
        final List<String> projection = new ArrayList<>(DETAIL_FIELD_COUNT);
        projection.add(transactionName);
        projection.add(title01);
        projection.add(currentDate);
        projection.add(programName);
        projection.add(title02);
        projection.add(currentTime);
        projection.add(transactionIdInput);
        projection.add(transactionId);
        projection.add(cardNumber);
        projection.add(typeCode);
        projection.add(categoryCode);
        projection.add(source);
        projection.add(description);
        projection.add(amount);
        projection.add(originatingDate);
        projection.add(processingDate);
        projection.add(merchantId);
        projection.add(merchantName);
        projection.add(merchantCity);
        projection.add(merchantZip);
        projection.add(errorMessage);
        return Collections.unmodifiableList(projection);
    }

    /**
     * Returns the {@value #LIST_FIELD_COUNT} list fields in the declaration order of
     * {@code app/cpy-bms/COTRN00.CPY}, from {@code TRNNAMEI} at line 24 to {@code ERRMSGI} at line 372.
     *
     * @return an unmodifiable list of exactly {@value #LIST_FIELD_COUNT} values, in map declaration order,
     * possibly containing {@code null} elements
     */
    public List<String> listProjection() {
        final List<String> projection = new ArrayList<>(LIST_FIELD_COUNT);
        projection.add(transactionName);
        projection.add(title01);
        projection.add(currentDate);
        projection.add(programName);
        projection.add(title02);
        projection.add(currentTime);
        projection.add(pageNumber);
        projection.add(transactionIdInput);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            final TransactionListRow row = rowAt(slot);
            projection.add(row == null ? null : row.selectionFlag());
            projection.add(row == null ? null : row.transactionId());
            projection.add(row == null ? null : row.transactionDate());
            projection.add(row == null ? null : row.description());
            projection.add(row == null ? null : row.amount());
        }
        projection.add(errorMessage);
        return Collections.unmodifiableList(projection);
    }

    /**
     * Returns the row occupying a given zero-based screen slot, or {@code null} if that slot is unpopulated.
     *
     * @param slot the zero-based slot index, where 0 is the screen's first row
     * @return the row in that slot, or {@code null} if the slot is unpopulated or this instance carries no list
     * projection
     * @throws IllegalArgumentException if {@code slot} is negative or not less than {@value #PAGE_SIZE}, since
     * no such slot exists on the screen
     */
    public TransactionListRow rowAt(final int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            throw new IllegalArgumentException("slot must be at least 0 and less than " + PAGE_SIZE
                    + " because the transaction list screen declares that many row slots, but was " + slot);
        }
        if (rows == null || slot >= rows.size()) {
            return null;
        }
        return rows.get(slot);
    }

    /**
     * Returns a diagnostic rendering that deliberately omits the card number and every other business field.
     *
     * @return the type name, the transaction identifier and the program name, and nothing else
     */
    @Override
    public String toString() {
        return "TransactionDto[transactionId=" + transactionId
                + ", programName=" + programName
                + ", protectedFieldsOmitted=true]";
    }

    /**
     * Rejects a value that cannot fit its fixed-width field.
     *
     * @param value the candidate value, possibly {@code null}
     * @param maxLength the declared width of the COBOL field in bytes
     * @param fieldName the Java component name, used verbatim in the failure message
     * @throws IllegalArgumentException if {@code value} is longer than {@code maxLength}.
     */
    private static void requireWithinWidth(final String value, final int maxLength, final String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds its declared COBOL width: "
                    + value.length() + " characters supplied, " + maxLength + " permitted");
        }
    }

    /**
     * Takes an unmodifiable defensive copy of the row slots, preserving the distinction between an absent list
     * and an empty one.
     *
     * @param rows the caller's list, possibly {@code null}
     * @return {@code null} if {@code rows} is {@code null}, otherwise an unmodifiable copy in the supplied
     * order
     * @throws IllegalArgumentException if any element is {@code null}, or if more rows are supplied than the
     * screen has slots.
     */
    private static List<TransactionListRow> copyRows(final List<TransactionListRow> rows) {
        if (rows == null) {
            return null;
        }
        if (rows.size() > PAGE_SIZE) {
            throw new IllegalArgumentException("rows holds " + rows.size()
                    + " elements, which exceeds the " + PAGE_SIZE
                    + " row slots the transaction list screen declares");
        }
        final List<TransactionListRow> defensiveCopy = new ArrayList<>(rows.size());
        for (final TransactionListRow row : rows) {
            if (row == null) {
                throw new IllegalArgumentException(
                        "rows must not contain a null element, but index " + defensiveCopy.size() + " was null");
            }
            defensiveCopy.add(row);
        }
        return Collections.unmodifiableList(defensiveCopy);
    }

    /**
     * Canonicalises an amount to scale {@value #AMOUNT_SCALE} and rejects a magnitude that
     * {@code PIC S9(09)V99} cannot hold.
     *
     * @param value the candidate amount, possibly {@code null}
     * @return {@code null} if {@code value} is {@code null}, otherwise {@code value} at scale
     * {@value #AMOUNT_SCALE}
     * @throws IllegalArgumentException if the magnitude needs more than {@value #AMOUNT_INTEGER_DIGITS} integer
     * digits.
     */
    private static BigDecimal normaliseAmount(final BigDecimal value) {
        if (value == null) {
            return null;
        }
        final BigDecimal scaled = value.setScale(AMOUNT_SCALE, AMOUNT_ROUNDING_MODE);
        // abs() bounds the magnitude symmetrically; it never rewrites the sign of the value returned below.
        if (scaled.abs().compareTo(AMOUNT_MAGNITUDE_LIMIT) >= 0) {
            throw new IllegalArgumentException("amountValue exceeds the " + AMOUNT_INTEGER_DIGITS
                    + " integer digits permitted by PIC S9(09)V99");
        }
        return scaled;
    }

    /**
     * One of the ten row slots of the transaction list screen.
     *
     * @param selectionFlag {@code SEL000nI}, {@code PIC X(1)}, {@code app/cpy-bms/COTRN00.CPY}:72 for row one.
     * @param transactionId {@code TRNIDnnI}, {@code PIC X(16)}, {@code app/cpy-bms/COTRN00.CPY}:78 for row one,
     * populated by {@code app/cbl/COTRN00C.cbl}:392 from {@code TRAN-ID}.
     * @param transactionDate {@code TDATEnnI}, {@code PIC X(8)}, {@code app/cpy-bms/COTRN00.CPY}:84 for row
     * one.
     * @param description {@code TDESCnnI}, {@code PIC X(26)}, {@code app/cpy-bms/COTRN00.CPY}:90 for row one,
     * populated by {@code app/cbl/COTRN00C.cbl}:395.
     * @param amount {@code TAMT0nnI}, {@code PIC X(12)}, {@code app/cpy-bms/COTRN00.CPY}:96 for row one.
     */
    public record TransactionListRow(
            String selectionFlag,
            String transactionId,
            String transactionDate,
            String description,
            String amount) {

        /**
         * Validates every declared row width.
         *
         * @throws IllegalArgumentException if any component is longer than the COBOL field it represents.
         */
        public TransactionListRow {
            requireWithinWidth(selectionFlag, SELECTION_FLAG_LENGTH, "selectionFlag");
            requireWithinWidth(transactionId, TRANSACTION_ID_LENGTH, "transactionId");
            requireWithinWidth(transactionDate, ROW_DATE_LENGTH, "transactionDate");
            requireWithinWidth(description, ROW_DESCRIPTION_LENGTH, "description");
            requireWithinWidth(amount, AMOUNT_DISPLAY_LENGTH, "amount");
        }

        /**
         * Returns a diagnostic rendering carrying only the transaction identifier.
         *
         * @return the type name and the transaction identifier, and nothing else
         */
        @Override
        public String toString() {
            return "TransactionDto.TransactionListRow[transactionId=" + transactionId + "]";
        }
    }
}
