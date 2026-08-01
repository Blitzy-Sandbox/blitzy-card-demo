/*
 * ******************************************************************
 * Program     : CardDto.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Card detail and card-list row projections; page size 7.
 * Source      : app/cpy-bms/COCRDSL.CPY (15 fields) @ 7756d89
 * Source      : app/cpy-bms/COCRDLI.CPY (45 fields) @ 7756d89
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable card payload carrying both the card-detail projection and the card-list row projection
 * of the legacy CardDemo card screens.
 *
 * <p><b>What this type does.</b> Two BMS symbolic maps describe the two card read screens, and this
 * one type serves both of them. {@code app/cpy-bms/COCRDSL.CPY} declares the card-detail screen
 * behind transaction {@code CCDL} and program {@code COCRDSLC}, with <b>exactly 15</b> input fields
 * inside its {@code 01 CCRDSLAI.} group, which begins at line 17 and ends where
 * {@code 01 CCRDSLAO REDEFINES CCRDSLAI.} begins at line 109.
 * {@code app/cpy-bms/COCRDLI.CPY} declares the card-list screen behind transaction {@code CCLI} and
 * program {@code COCRDLIC}, with <b>exactly 45</b> input fields inside its {@code 01 CCRDLIAI.}
 * group, which begins at line 17 and ends where {@code 01 CCRDLIAO REDEFINES CCRDLIAI.} begins at
 * line 289. Both counts were established by counting the {@code 02 &lt;name&gt;I PIC} declarations
 * lying strictly inside those input groups, and both are published as {@link #DETAIL_FIELD_COUNT}
 * and {@link #LIST_FIELD_COUNT} so that they are machine-checkable rather than asserted in prose.
 *
 * <p><b>Why one type serves two maps.</b> The two screens overlap heavily: the six-field terminal
 * header is identical, and the account identifier, the card number, the information message and the
 * error message appear on both. Splitting them into a detail payload and a list payload would
 * duplicate ten of the seventeen members below, and duplication is what Rule 1 Clause C forbids. The
 * fields that belong to only one screen are therefore modelled as legitimately absent on the other,
 * and every accessor documents which projection populates it. Absence is represented by
 * {@code null}, never by a substitute value.
 *
 * <p><b>Field declaration order is a merge of both maps, not a compromise.</b> The seventeen members
 * are declared in an order that is simultaneously consistent with the declaration order of
 * {@code app/cpy-bms/COCRDSL.CPY} and with that of {@code app/cpy-bms/COCRDLI.CPY}: reading only the
 * fifteen detail members yields the exact COCRDSL order, and reading only the list members yields
 * the exact COCRDLI order. The two factory methods make each order explicit in a signature -
 * {@link #detail} takes the fifteen detail fields in COCRDSL order, and {@link #list} takes the list
 * fields in COCRDLI order - so neither contract has to be reconstructed by a reader.
 *
 * <p><b>DISTINCTION 3 - there is no {@code CRDSTP1I}, and inventing one breaks the contract.</b>
 * The card-list map declares a row selector-type field for rows 2 through 7 only. The six
 * declarations are {@code CRDSTP2I} at {@code app/cpy-bms/COCRDLI.CPY:108}, {@code CRDSTP3I} at
 * {@code app/cpy-bms/COCRDLI.CPY:138}, {@code CRDSTP4I} at {@code app/cpy-bms/COCRDLI.CPY:168},
 * {@code CRDSTP5I} at {@code app/cpy-bms/COCRDLI.CPY:198}, {@code CRDSTP6I} at
 * {@code app/cpy-bms/COCRDLI.CPY:228} and {@code CRDSTP7I} at
 * {@code app/cpy-bms/COCRDLI.CPY:258}, each {@code PIC X(1)}. <b>A token search for
 * {@code CRDSTP1} across the whole member returns zero occurrences: row 1 genuinely has no
 * selector-type field.</b> Row 1 therefore carries 4 input fields while rows 2 through 7 carry 5
 * each, and that asymmetry is exactly what makes the census arithmetic close:
 * 9 preamble + 4 for row 1 + (6 rows &times; 5) + 2 trailer = 45. Adding a {@code CRDSTP1I} to make
 * the row array uniform would raise the count to 46 and would misreport the map, so
 * {@link CardListRow} enforces the absence structurally: its constructor rejects a non-null
 * selector type on row 1 rather than merely documenting that none is expected.
 *
 * <p>Two further observations close this point. First, the output-overlay fields
 * {@code CRDSTP2C}, {@code CRDSTP2P}, {@code CRDSTP2H}, {@code CRDSTP2V} and {@code CRDSTP2O} that
 * appear from {@code app/cpy-bms/COCRDLI.CPY:376} onward lie inside the
 * {@code 01 CCRDLIAO REDEFINES CCRDLIAI.} overlay that starts at line 289; overlay fields are not
 * part of the input-field budget and are not modelled here. Second, a search for {@code CRDSTP}
 * across {@code app/cbl} returns <b>no matches at all</b>: the selector-type fields are declared on
 * the mapset and are never read or written by any program in the corpus. The field set is vestigial,
 * which is why its row 1 member was never declared in the first place. This is a <b>preserved source
 * characteristic, not a defect</b>, and it is reproduced rather than repaired.
 *
 * <p><b>DISTINCTION 4 - the card-detail map has no expiry day at all.</b> A token search for
 * {@code EXPDAY} across {@code app/cpy-bms/COCRDSL.CPY} returns zero occurrences; the member runs
 * {@code EXPMONI PIC X(2)} at line 84 and {@code EXPYEARI PIC X(4)} at line 90 and declares nothing
 * further for expiry. The card-update map does declare one: {@code app/cpy-bms/COCRDUP.CPY:96}
 * declares {@code 02 EXPDAYI PIC X(2).} <b>No expiry-day member is added to this type</b>, not as
 * {@code null}, not as an optional and not for symmetry with the update path; the update-side field
 * belongs to {@code CardUpdateRequest}, where it is already modelled.
 *
 * <p>The distinction is sharper than a missing token, and the sharper form is the reason it must be
 * respected. The underlying record does hold a day: {@code app/cpy/CVACT02Y.cpy:9} declares
 * {@code CARD-EXPIRAION-DATE PIC X(10)} - the misspelling is part of the field contract - and the
 * detail program decomposes it positionally at {@code app/cbl/COCRDSLC.cbl:85-90} into
 * {@code CARD-EXPIRY-YEAR PIC X(4)} at line 86, a one-byte separator at line 87,
 * {@code CARD-EXPIRY-MONTH PIC X(2)} at line 88, a second separator at line 89 and
 * {@code CARD-EXPIRY-DAY PIC X(2)} at line 90. So the day is present in the data and even named in
 * the detail program's own working storage, yet the detail <em>screen</em> deliberately omits it.
 * The map, not the record and not the work area, is the contract this projection reproduces.
 *
 * <p><b>Width and field divergences between the two maps.</b> Four differences exist between the two
 * maps this single type serves. They are the contract and none of them is silently unified:
 * <ul>
 *   <li><b>Information message.</b> {@code INFOMSGI PIC X(40)} at
 *       {@code app/cpy-bms/COCRDSL.CPY:96} against {@code INFOMSGI PIC X(45)} at
 *       {@code app/cpy-bms/COCRDLI.CPY:282}. One member serves both and is sized to the wider
 *       declaration, 45; the detail screen renders it into a 40-byte field and therefore
 *       <b>truncates the last five bytes</b>.</li>
 *   <li><b>Error message.</b> {@code ERRMSGI PIC X(80)} at
 *       {@code app/cpy-bms/COCRDSL.CPY:102} against {@code ERRMSGI PIC X(78)} at
 *       {@code app/cpy-bms/COCRDLI.CPY:288}. One member serves both and is sized to the wider
 *       declaration, 80; the list screen renders it into a 78-byte field and therefore
 *       <b>truncates the last two bytes</b>.</li>
 *   <li><b>Function keys.</b> {@code FKEYSI PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108} is
 *       declared on the detail map and is <b>absent from the list map entirely</b>. It is
 *       {@code null} on a list payload.</li>
 *   <li><b>Page number.</b> {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60} is
 *       declared on the list map and is <b>absent from the detail map entirely</b>. It is
 *       {@code null} on a detail payload.</li>
 * </ul>
 *
 * <p>The page-number field diverges a second time, across screens rather than within them: the other
 * two paging maps name it differently <em>and</em> size it differently, declaring
 * {@code PAGENUMI PIC X(8)} at both {@code app/cpy-bms/COTRN00.CPY:60} and
 * {@code app/cpy-bms/COUSR00.CPY:60} against this map's {@code PAGENOI PIC X(3)}. This type makes no
 * attempt to normalise that; the paging metadata contract, including the full divergence, belongs to
 * {@code PageResponse}, which is the envelope a card-list response travels in.
 *
 * <p><b>No shared header helper exists, deliberately.</b> The six terminal-header fields are
 * declared inline on this type rather than factored into a common header component, because the
 * header is not in fact common across the corpus: {@code CURTIMEI} is {@code PIC X(8)} on both card
 * maps, at {@code app/cpy-bms/COCRDSL.CPY:54} and {@code app/cpy-bms/COCRDLI.CPY:54}, but
 * {@code app/cpy-bms/COSGN00.CPY:54} declares it {@code PIC X(9)}. A shared helper would have to
 * pick one of those two widths and would misreport whichever screen it did not pick, so no helper is
 * created.
 *
 * <p><b>Page size.</b> The card list shows seven rows per page. The anchor is
 * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at {@code app/cbl/COCRDLIC.cbl:177-178},
 * published here as {@link #CARD_LIST_PAGE_SIZE} and corroborated three times over inside the same
 * program: {@code WS-EDIT-SELECT-FLAGS PIC X(7)} at {@code app/cbl/COCRDLIC.cbl:72}, its redefinition
 * {@code WS-EDIT-SELECT PIC X(1) OCCURS 7 TIMES} at {@code app/cbl/COCRDLIC.cbl:75-76}, and
 * {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} at {@code app/cbl/COCRDLIC.cbl:86} - as well as by
 * the seven row groups on the map itself. <b>Seven is the only page size that may appear in this
 * type.</b> The transaction list and the user list both page by ten, evidenced by the row table at
 * {@code app/cbl/COUSR00C.cbl:57} and the paging fields at {@code app/cbl/COTRN00C.cbl:65}, and the
 * transaction report's twenty-lines-per-page is a batch report-line count and not a page size at
 * all; none of those figures is represented here, and each belongs to its own type.
 *
 * <p><b>Card numbers, identifiers and expiry components are all {@code String}.</b> The card number
 * is alphanumeric in the record of authority: {@code app/cpy/CVACT02Y.cpy:5} declares
 * {@code CARD-NUM PIC X(16)}, and the card cluster key length is 16.
 * <b>A type divergence exists and is recorded rather than resolved:</b>
 * {@code app/cpy/COCOM01Y.cpy:41} declares the COMMAREA copy of the same value as
 * {@code CDEMO-CARD-NUM PIC 9(16)}, which is <em>numeric</em>. The two spellings are not unified
 * here; {@code String} is the one representation that survives both, and it is the only one that
 * preserves a significant leading zero. The same reasoning fixes the account identifier as a
 * {@code String} of width 11: the seeded identifiers are zero-padded - the first account record is
 * {@code 00000000001} - and a numeric type would strip that padding and break byte-exact comparison
 * against the legacy baseline. The expiry month and year are {@code String} of widths 2 and 4 and
 * are deliberately <b>not</b> a date or year-month type: they are positional substrings of a
 * {@code PIC X(10)} value, taken by {@code MOVE CARD-EXPIRY-MONTH TO EXPMONO} at
 * {@code app/cbl/COCRDSLC.cbl:480} and {@code MOVE CARD-EXPIRY-YEAR TO EXPYEARO} at
 * {@code app/cbl/COCRDSLC.cbl:482}, and the expiry comparison in the batch corpus is a string
 * comparison, so a date type would normalise away the representation the comparison depends on.
 * Neither card map declares a monetary field, so no decimal type is needed; the package-wide
 * prohibition on IEEE-754 binary approximate numeric types nevertheless holds, and this file declares
 * no such type anywhere - every member is a {@code String} apart from the structural row number, so a
 * plain substring search of this file for either forbidden type name returns nothing at all.
 *
 * <p><b>The card status code is a raw one-character {@code String}, not an enumeration.</b> It comes
 * from {@code CARD-ACTIVE-STATUS PIC X(01)} by way of
 * {@code MOVE CARD-ACTIVE-STATUS TO CRDSTCDO} at {@code app/cbl/COCRDSLC.cbl:484}. Binding it to an
 * enumeration would convert an out-of-domain code into a framework deserialisation failure and would
 * replace the legacy screen's own message for that condition, so no enumeration is imported or
 * referenced.
 *
 * <p><b>Protected data, and why this type declares no {@code toString}.</b> This payload carries the
 * heaviest concentration of card numbers in the package: one on the detail projection
 * ({@code CARDSIDI} at {@code app/cpy-bms/COCRDSL.CPY:66}), one filter value on the list projection
 * ({@code CARDSIDI} at {@code app/cpy-bms/COCRDLI.CPY:72}) and one per list row
 * ({@code CRDNUM1I} through {@code CRDNUM7I}), for eight in total, alongside the cardholder name
 * ({@code CRDNAMEI PIC X(50)} at {@code app/cpy-bms/COCRDSL.CPY:72}), which is personal data.
 * <b>Neither this class nor {@link CardListRow} declares {@code toString}</b>, so both inherit the
 * {@code Object} rendering, which discloses a class name and an identity hash and no field value
 * whatsoever. That is also why this type is a class rather than a record: a record's generated
 * {@code toString} emits every component, so a record would have put all eight card numbers and the
 * cardholder name into any log line, stack trace or error page that rendered this payload, and would
 * have left the protection depending on an override that a later edit could remove. Declining the
 * record removes the hazard instead of guarding it. Central log masking is configured as a second
 * line of defence, but the defence that matters is never emitting the values at all. This type
 * carries no password, no hash, no token and no signing key, the card maps declare none, and none
 * may be added.
 *
 * <p><b>Inputs, outputs and side effects.</b> This is a pure data holder and is primarily outbound.
 * It performs no filtering, no paging arithmetic, no mapping, no ordering and no comparison; the
 * calling service does all of that. Every member is assigned once during construction and never
 * mutated, the row list is defensively copied on the way in and exposed only as an unmodifiable
 * view, and the class is {@code final}, so an instance may be shared across threads without further
 * synchronisation. There are no side effects of any kind: nothing is logged, nothing is read from
 * configuration, and no environment variable or system property is consulted.
 *
 * <p><b>No value equality is published, deliberately.</b> Neither {@code equals} nor
 * {@code hashCode} is overridden, so instances compare by identity. This type is a transport
 * carrier, and the migration plan places every business comparison - notably the field-by-field
 * snapshot comparison of the account update path - in the service layer rather than in a payload.
 * Callers that need to compare two payloads compare the accessors they care about, which also keeps
 * a card number out of any incidental comparison. The omission is a decision, not an oversight.
 *
 * <p><b>Three states stay distinct.</b> A screen field may be absent, may be blank, or may hold
 * {@code LOW-VALUES}, that is binary zeros, and the source treats those as different things:
 * {@code app/cbl/COCRDLIC.cbl:77-83} declares {@code 88 SELECT-BLANK} with the two values
 * {@code SPACE} and {@code LOW-VALUES} side by side, and the same program initialises
 * {@code WS-EDIT-SELECT-FLAGS} to {@code LOW-VALUES} at {@code app/cbl/COCRDLIC.cbl:72-73}.
 * Accordingly this type never coerces {@code null} to an empty string, never coerces an empty string
 * to {@code null}, and performs <b>no</b> trimming and no case folding when a value is accepted.
 * Where a service needs a case-insensitive comparison it applies the case function itself, passing
 * {@code Locale.ROOT} so the result cannot vary with the platform default locale.
 *
 * <p><b>Error modes.</b> Nothing is thrown once an instance exists; every accessor is total.
 * Construction rejects structurally impossible arguments with {@code IllegalArgumentException}, and
 * <b>the message names the offending field and never reproduces its value</b>, because several
 * members are card numbers or personal data. The rejected conditions are: a character value longer
 * than the width its map declares; a row-list longer than {@link #CARD_LIST_PAGE_SIZE}; a
 * {@code null} element inside the row list; a row list whose positions do not run 1, 2, 3 and so on
 * in order; a row number outside 1 through {@link #CARD_LIST_PAGE_SIZE}; and a selector type
 * supplied for row 1, which has none. See {@link #CardDto} and {@link CardListRow#CardListRow} for
 * the exact conditions. Width checks are structural guards on a fixed-width contract and are not a
 * substitute for inbound business validation, which this read-path payload deliberately does not
 * carry.
 *
 * <p><b>Findings and severities</b>, classified per Rule 1 Clause F:
 * <ul>
 *   <li><b>Medium - input-field census correction.</b> The technical specification states that the
 *       seventeen BMS symbolic maps declare 460 input fields in total. Counting the
 *       {@code 02 &lt;name&gt;I PIC} declarations strictly inside each {@code 01 ...AI.} input group
 *       across all seventeen members of {@code app/cpy-bms} yields <b>441</b>. The two maps this
 *       type serves are unaffected and are exactly 15 and 45 as stated; the discrepancy lies
 *       elsewhere in the census, and it is reported for the repository-root decision log. Remedy:
 *       correct the total to 441 in the specification. No code change follows from it.</li>
 *   <li><b>Low - input group names.</b> The two input groups are named {@code 01 CCRDSLAI.} and
 *       {@code 01 CCRDLIAI.}, both at line 17 of their members, following the BMS map names
 *       {@code CCRDSLA} and {@code CCRDLIA} declared as {@code LIT-THISMAP} at
 *       {@code app/cbl/COCRDSLC.cbl:169-170} and {@code app/cbl/COCRDLIC.cbl:185-186}. Some prose
 *       elsewhere names them after the mapsets instead. The names above are the on-disk ones.
 *       Remedy: documentation only.</li>
 *   <li><b>Not a defect - preserved source characteristic.</b> The absent {@code CRDSTP1I} described
 *       above is a property of the system of record, reproduced faithfully. It is recorded here so
 *       that it is not mistaken for an omission in this file and repaired by a later edit.</li>
 * </ul>
 *
 * <p><b>Building, testing and troubleshooting.</b> This type belongs to the single Maven module at
 * the repository root. Build it with {@code mvn -B clean compile} and exercise it with
 * {@code mvn -B clean test}. The module compiles under {@code -Xlint:all} with {@code -Werror} and
 * {@code failOnWarning}, so any warning introduced here fails the build rather than being reported.
 * Unit tests for this type live under {@code src/test/java/com/cardemo/unit/model}. The only defaults
 * this type publishes are the constants below; it reads no configuration, so there is nothing to
 * configure and nothing to tune. The failure most likely to be met in practice is an
 * {@code IllegalArgumentException} from a service that assembled more than
 * {@link #CARD_LIST_PAGE_SIZE} rows, numbered its rows from zero instead of one, or supplied a
 * selector type for row 1; in each case the message names the field, and the remedy is to correct
 * the caller rather than to relax the check. A payload that renders as
 * {@code com.cardemo.model.dto.CardDto} followed by an identity hash is behaving correctly - that is
 * the deliberate absence of {@code toString} described above, not a serialisation fault.
 */
public final class CardDto {

    /**
     * Rows displayed per page by the card list, namely 7.
     *
     * <p>Source field {@code WS-MAX-SCREEN-LINES}, declared
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at
     * {@code app/cbl/COCRDLIC.cbl:177-178}. Corroborated inside the same program by
     * {@code WS-EDIT-SELECT-FLAGS PIC X(7)} at {@code app/cbl/COCRDLIC.cbl:72}, by its redefinition
     * {@code WS-EDIT-SELECT PIC X(1) OCCURS 7 TIMES} at {@code app/cbl/COCRDLIC.cbl:75-76} and by
     * {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} at {@code app/cbl/COCRDLIC.cbl:86}, and on the
     * map itself by the seven row groups running from {@code app/cpy-bms/COCRDLI.CPY:78} to
     * {@code app/cpy-bms/COCRDLI.CPY:276}.</p>
     *
     * <p>This is the only page size that belongs to the card list. The other two online lists page
     * by ten and the transaction report paginates a printed listing by twenty lines; neither figure
     * appears in this type, because neither describes this screen.</p>
     */
    public static final int CARD_LIST_PAGE_SIZE = 7;

    /**
     * The number of input fields the card-detail map declares, namely 15.
     *
     * <p>The figure is the verified count of the {@code 02 &lt;name&gt;I PIC} declarations lying
     * strictly inside the {@code 01 CCRDSLAI.} group of {@code app/cpy-bms/COCRDSL.CPY}, which
     * begins at line 17 and ends where {@code 01 CCRDSLAO REDEFINES CCRDSLAI.} begins at line 109.
     * It is published so that the count is machine-checkable and cannot drift silently.</p>
     */
    public static final int DETAIL_FIELD_COUNT = 15;

    /**
     * The number of input fields the card-list map declares, namely 45.
     *
     * <p>The figure is the verified count of the {@code 02 &lt;name&gt;I PIC} declarations lying
     * strictly inside the {@code 01 CCRDLIAI.} group of {@code app/cpy-bms/COCRDLI.CPY}, which
     * begins at line 17 and ends where {@code 01 CCRDLIAO REDEFINES CCRDLIAI.} begins at line 289.
     * It decomposes as 9 preamble fields, 4 for row 1, 5 for each of rows 2 through 7 and 2 trailer
     * fields: 9 + 4 + 30 + 2 = 45. The row 1 shortfall is the absent {@code CRDSTP1I} described on
     * this class; a uniform row array would give 46 and would misreport the map.</p>
     */
    public static final int LIST_FIELD_COUNT = 45;

    /**
     * The number of the first card-list row, namely 1, because the map numbers its row groups from
     * one: the first row's fields are {@code CRDSEL1I}, {@code ACCTNO1I}, {@code CRDNUM1I} and
     * {@code CRDSTS1I} at {@code app/cpy-bms/COCRDLI.CPY:78}, 84, 90 and 96.
     */
    public static final int FIRST_ROW_NUMBER = 1;

    /**
     * The one row number whose selector-type field the card-list map does not declare, namely 1.
     *
     * <p>{@code CRDSTP2I} through {@code CRDSTP7I} exist at {@code app/cpy-bms/COCRDLI.CPY:108},
     * 138, 168, 198, 228 and 258, and a token search for {@code CRDSTP1} across the member returns
     * zero occurrences. The constant exists so that the asymmetry is enforced in one place -
     * {@link CardListRow} consults it - rather than repeated as a magic number, and so that a test
     * can assert the asymmetry directly.</p>
     */
    public static final int ROW_NUMBER_WITHOUT_SELECTOR_TYPE = 1;

    /**
     * The number of input fields a card-list row other than row 1 declares, namely 5:
     * {@code CRDSEL&lt;n&gt;I}, {@code CRDSTP&lt;n&gt;I}, {@code ACCTNO&lt;n&gt;I},
     * {@code CRDNUM&lt;n&gt;I} and {@code CRDSTS&lt;n&gt;I}.
     */
    public static final int ROW_FIELD_COUNT_WITH_SELECTOR_TYPE = 5;

    /**
     * The number of input fields row 1 of the card list declares, namely 4:
     * {@code CRDSEL1I} at {@code app/cpy-bms/COCRDLI.CPY:78}, {@code ACCTNO1I} at line 84,
     * {@code CRDNUM1I} at line 90 and {@code CRDSTS1I} at line 96. There is no fifth field, because
     * no {@code CRDSTP1I} is declared anywhere in the member.
     */
    public static final int ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE = 4;

    /** Declared width of {@code TRNNAMEI}: {@code PIC X(4)} on both card maps, at line 24. */
    private static final int WIDTH_TRANSACTION_NAME = 4;

    /**
     * Declared width of {@code TITLE01I} and {@code TITLE02I}: {@code PIC X(40)} on both card maps,
     * at lines 30 and 48.
     */
    private static final int WIDTH_TITLE = 40;

    /** Declared width of {@code CURDATEI}: {@code PIC X(8)} on both card maps, at line 36. */
    private static final int WIDTH_CURRENT_DATE = 8;

    /** Declared width of {@code PGMNAMEI}: {@code PIC X(8)} on both card maps, at line 42. */
    private static final int WIDTH_PROGRAM_NAME = 8;

    /**
     * Declared width of {@code CURTIMEI}: {@code PIC X(8)} on both card maps, at line 54. The
     * sign-on map declares the same field one byte wider, {@code PIC X(9)} at
     * {@code app/cpy-bms/COSGN00.CPY:54}, which is why this width is local to this type.
     */
    private static final int WIDTH_CURRENT_TIME = 8;

    /**
     * Declared width of {@code PAGENOI}: {@code PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60}.
     * The other two paging maps declare {@code PAGENUMI PIC X(8)} instead; that divergence is
     * documented by {@code PageResponse} and is not reconciled here.
     */
    private static final int WIDTH_PAGE_NUMBER = 3;

    /**
     * Declared width of {@code ACCTSIDI} and of each row's {@code ACCTNO&lt;n&gt;I}:
     * {@code PIC X(11)}, at {@code app/cpy-bms/COCRDSL.CPY:60} and
     * {@code app/cpy-bms/COCRDLI.CPY:66}, and at {@code app/cpy-bms/COCRDLI.CPY:84} onward for the
     * rows. It matches the 11-byte key of the account cluster.
     */
    private static final int WIDTH_ACCOUNT_ID = 11;

    /**
     * Declared width of {@code CARDSIDI} and of each row's {@code CRDNUM&lt;n&gt;I}:
     * {@code PIC X(16)}, at {@code app/cpy-bms/COCRDSL.CPY:66} and
     * {@code app/cpy-bms/COCRDLI.CPY:72}, and at {@code app/cpy-bms/COCRDLI.CPY:90} onward for the
     * rows. It matches {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:5} and the 16-byte
     * key of the card cluster.
     */
    private static final int WIDTH_CARD_NUMBER = 16;

    /** Declared width of {@code CRDNAMEI}: {@code PIC X(50)} at {@code app/cpy-bms/COCRDSL.CPY:72}. */
    private static final int WIDTH_CARDHOLDER_NAME = 50;

    /**
     * Declared width of every single-character screen field on the two card maps:
     * {@code CRDSTCDI} at {@code app/cpy-bms/COCRDSL.CPY:78}, and each row's
     * {@code CRDSEL&lt;n&gt;I}, {@code CRDSTP&lt;n&gt;I} and {@code CRDSTS&lt;n&gt;I} on the list
     * map. All are {@code PIC X(1)}.
     */
    private static final int WIDTH_SINGLE_CHARACTER = 1;

    /** Declared width of {@code EXPMONI}: {@code PIC X(2)} at {@code app/cpy-bms/COCRDSL.CPY:84}. */
    private static final int WIDTH_EXPIRY_MONTH = 2;

    /** Declared width of {@code EXPYEARI}: {@code PIC X(4)} at {@code app/cpy-bms/COCRDSL.CPY:90}. */
    private static final int WIDTH_EXPIRY_YEAR = 4;

    /**
     * Width applied to the information message: 45, the <em>wider</em> of the two declarations.
     *
     * <p>{@code app/cpy-bms/COCRDLI.CPY:282} declares {@code INFOMSGI PIC X(45)} and
     * {@code app/cpy-bms/COCRDSL.CPY:96} declares {@code INFOMSGI PIC X(40)}. Sizing to the wider
     * declaration means a list message is never rejected; a detail screen renders the same value
     * into 40 bytes and truncates the remainder.</p>
     */
    private static final int WIDTH_INFORMATION_MESSAGE = 45;

    /**
     * Width applied to the error message: 80, the <em>wider</em> of the two declarations.
     *
     * <p>{@code app/cpy-bms/COCRDSL.CPY:102} declares {@code ERRMSGI PIC X(80)} and
     * {@code app/cpy-bms/COCRDLI.CPY:288} declares {@code ERRMSGI PIC X(78)}. Sizing to the wider
     * declaration means a detail message is never rejected; a list screen renders the same value
     * into 78 bytes and truncates the remainder.</p>
     */
    private static final int WIDTH_ERROR_MESSAGE = 80;

    /** Declared width of {@code FKEYSI}: {@code PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108}. */
    private static final int WIDTH_FUNCTION_KEYS = 75;

    /**
     * The transaction identifier shown in the screen header. Detail field 1, list field 1.
     *
     * <p>{@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COCRDSL.CPY:24} and
     * {@code app/cpy-bms/COCRDLI.CPY:24}. The detail screen carries {@code 'CCDL'}
     * ({@code LIT-THISTRANID} at {@code app/cbl/COCRDSLC.cbl:165-166}) and the list screen carries
     * {@code 'CCLI'} ({@code LIT-THISTRANID} at {@code app/cbl/COCRDLIC.cbl:181-182}). May be
     * {@code null}.</p>
     */
    private final String transactionName;

    /**
     * The first header title line. Detail field 2, list field 2.
     *
     * <p>{@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:30} and
     * {@code app/cpy-bms/COCRDLI.CPY:30}. May be {@code null}.</p>
     */
    private final String title01;

    /**
     * The current date as the screen renders it. Detail field 3, list field 3.
     *
     * <p>{@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COCRDSL.CPY:36} and
     * {@code app/cpy-bms/COCRDLI.CPY:36}. Carried as display text rather than a date type, because
     * the screen contract is eight bytes in the mask the legacy program applied. May be
     * {@code null}.</p>
     */
    private final String currentDate;

    /**
     * The name of the program that produced the screen. Detail field 4, list field 4.
     *
     * <p>{@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COCRDSL.CPY:42} and
     * {@code app/cpy-bms/COCRDLI.CPY:42}. The legacy values are {@code 'COCRDSLC'}
     * ({@code app/cbl/COCRDSLC.cbl:163-164}) and {@code 'COCRDLIC'}
     * ({@code app/cbl/COCRDLIC.cbl:179-180}). May be {@code null}.</p>
     */
    private final String programName;

    /**
     * The second header title line. Detail field 5, list field 5.
     *
     * <p>{@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:48} and
     * {@code app/cpy-bms/COCRDLI.CPY:48}. May be {@code null}.</p>
     */
    private final String title02;

    /**
     * The current time as the screen renders it. Detail field 6, list field 6.
     *
     * <p>{@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COCRDSL.CPY:54} and
     * {@code app/cpy-bms/COCRDLI.CPY:54}. The sign-on map declares the same field
     * {@code PIC X(9)} at {@code app/cpy-bms/COSGN00.CPY:54}, so this width is local to the card
     * screens and no shared header component exists. May be {@code null}.</p>
     */
    private final String currentTime;

    /**
     * The page number of a card-list page. <b>List field 7; absent from the detail map.</b>
     *
     * <p>{@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60}. Carried as display text of
     * at most three bytes so that the field is reproduced exactly as this map declares it. The other
     * two paging maps declare {@code PAGENUMI PIC X(8)} at
     * {@code app/cpy-bms/COTRN00.CPY:60} and {@code app/cpy-bms/COUSR00.CPY:60} - a different name
     * and a different width - and that divergence is documented in full by {@code PageResponse},
     * which also carries the arithmetic page number. This member exists only to reproduce the card
     * screen's own field. Always {@code null} on a detail payload.</p>
     */
    private final String pageNumber;

    /**
     * The account identifier. Detail field 7; list field 8, where it is the account filter.
     *
     * <p>{@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COCRDSL.CPY:60} and at
     * {@code app/cpy-bms/COCRDLI.CPY:66}. A {@code String} rather than a numeric type because the
     * seeded identifiers are zero-padded to eleven digits and a numeric type would strip the
     * padding. May be {@code null}; on the list projection a {@code null} or blank value means the
     * account filter was not supplied.</p>
     */
    private final String accountId;

    /**
     * The card number. Detail field 8; list field 9, where it is the card-number filter.
     *
     * <p>{@code CARDSIDI PIC X(16)} at {@code app/cpy-bms/COCRDSL.CPY:66} and at
     * {@code app/cpy-bms/COCRDLI.CPY:72}. Alphanumeric per {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:5}; note the divergent COMMAREA spelling
     * {@code CDEMO-CARD-NUM PIC 9(16)} at {@code app/cpy/COCOM01Y.cpy:41}, which is not reconciled
     * here. <b>Protected data: never emitted in any rendering of this payload.</b> May be
     * {@code null}.</p>
     */
    private final String cardNumber;

    /**
     * The embossed cardholder name. <b>Detail field 9; absent from the list map.</b>
     *
     * <p>{@code CRDNAMEI PIC X(50)} at {@code app/cpy-bms/COCRDSL.CPY:72}, populated from
     * {@code CARD-EMBOSSED-NAME} by the move at {@code app/cbl/COCRDSLC.cbl:476}.
     * <b>Personal data: never emitted in any rendering, and never quoted in a diagnostic
     * message.</b> Always {@code null} on a list payload.</p>
     */
    private final String cardholderName;

    /**
     * The card status code, a raw single character. <b>Detail field 10; absent from the list
     * map.</b>
     *
     * <p>{@code CRDSTCDI PIC X(1)} at {@code app/cpy-bms/COCRDSL.CPY:78}, populated from
     * {@code CARD-ACTIVE-STATUS PIC X(01)} by the move at {@code app/cbl/COCRDSLC.cbl:484}. Kept as
     * a {@code String} and deliberately not bound to an enumeration, so that an out-of-domain code
     * reaches the service and is reported by the legacy message rather than being turned into a
     * deserialisation failure. Always {@code null} on a list payload; the per-row status is on
     * {@link CardListRow#getStatusCode()} instead.</p>
     */
    private final String cardStatusCode;

    /**
     * The expiry month as two display characters. <b>Detail field 11; absent from the list map.</b>
     *
     * <p>{@code EXPMONI PIC X(2)} at {@code app/cpy-bms/COCRDSL.CPY:84}, populated by
     * {@code MOVE CARD-EXPIRY-MONTH TO EXPMONO} at {@code app/cbl/COCRDSLC.cbl:480} from the
     * positional decomposition of {@code CARD-EXPIRAION-DATE PIC X(10)} declared at
     * {@code app/cpy/CVACT02Y.cpy:9} and redefined at {@code app/cbl/COCRDSLC.cbl:88}. A
     * {@code String} and never a date or year-month type, because the expiry test in the batch
     * corpus is a string comparison. Always {@code null} on a list payload.</p>
     */
    private final String expiryMonth;

    /**
     * The expiry year as four display characters. <b>Detail field 12; absent from the list map.</b>
     *
     * <p>{@code EXPYEARI PIC X(4)} at {@code app/cpy-bms/COCRDSL.CPY:90}, populated by
     * {@code MOVE CARD-EXPIRY-YEAR TO EXPYEARO} at {@code app/cbl/COCRDSLC.cbl:482} from the
     * redefinition at {@code app/cbl/COCRDSLC.cbl:86}. A {@code String} for the same reason as the
     * month. <b>There is no expiry-day member on this type</b>: the detail map declares no
     * {@code EXPDAY} field of any kind, whereas {@code app/cpy-bms/COCRDUP.CPY:96} declares
     * {@code EXPDAYI PIC X(2)} for the update screen. Always {@code null} on a list payload.</p>
     */
    private final String expiryYear;

    /**
     * The card-list rows in screen order, or {@code null} on a detail payload.
     *
     * <p>List fields 10 through 43: seven row groups running from
     * {@code app/cpy-bms/COCRDLI.CPY:78} to {@code app/cpy-bms/COCRDLI.CPY:276}, of which row 1
     * declares four fields and rows 2 through 7 declare five each, for 34 fields in total. Held in a
     * {@code List} so that order is positional and deterministic, never in a hash-ordered
     * collection; the element at index 0 is row 1. Stored as an unmodifiable view over a private
     * defensive copy.</p>
     *
     * <p><b>{@code null} and empty mean different things and are both meaningful.</b> A
     * {@code null} row list means this payload is a detail projection and carries no row array at
     * all, because the detail map declares none. An empty list means this is a list projection whose
     * query matched nothing. Neither state is coerced into the other, exactly as a blank screen field
     * is not coerced into {@code LOW-VALUES}.</p>
     */
    private final List<CardListRow> rows;

    /**
     * The screen information message. Detail field 13, list field 44.
     *
     * <p>{@code INFOMSGI} at {@code app/cpy-bms/COCRDSL.CPY:96}, where it is {@code PIC X(40)}, and
     * at {@code app/cpy-bms/COCRDLI.CPY:282}, where it is {@code PIC X(45)}. This member is sized to
     * the wider of the two, 45, so a list message is never rejected; a detail screen renders the
     * same value into 40 bytes and <b>truncates the last five</b>. May be {@code null}.</p>
     */
    private final String informationMessage;

    /**
     * The screen error message. Detail field 14, list field 45.
     *
     * <p>{@code ERRMSGI} at {@code app/cpy-bms/COCRDSL.CPY:102}, where it is {@code PIC X(80)}, and
     * at {@code app/cpy-bms/COCRDLI.CPY:288}, where it is {@code PIC X(78)}. This member is sized to
     * the wider of the two, 80, so a detail message is never rejected; a list screen renders the
     * same value into 78 bytes and <b>truncates the last two</b>. May be {@code null}.</p>
     */
    private final String errorMessage;

    /**
     * The function-key legend line. <b>Detail field 15; absent from the list map.</b>
     *
     * <p>{@code FKEYSI PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108}. The card-list map declares
     * no equivalent field, so this member is always {@code null} on a list payload.</p>
     */
    private final String functionKeys;

    /**
     * Constructs a card payload from every member of both projections.
     *
     * <p>This is the single canonical constructor. It exists so that one code path performs all
     * validation and all defensive copying, and so that a JSON binder has exactly one creator to
     * choose. Most callers should prefer {@link #detail} or {@link #list}, which take only the
     * members their projection declares, in the order their map declares them, and pass
     * {@code null} for the rest.</p>
     *
     * <p>Every character argument is optional and may be {@code null}, blank, or a run of binary
     * zeros; the three states are preserved distinctly and no argument is trimmed or case-folded.
     * Each is length-checked against the width its map declares, which is a structural guard on a
     * fixed-width contract rather than business validation. The row list is defensively copied, so a
     * later mutation of the list handed in cannot be observed through this instance.</p>
     *
     * @param transactionName    {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:24} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:24}; at most 4 characters, or
     *                           {@code null}
     * @param title01            {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:30} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:30}; at most 40 characters, or
     *                           {@code null}
     * @param currentDate        {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:36} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:36}; at most 8 characters, or
     *                           {@code null}
     * @param programName        {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:42} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:42}; at most 8 characters, or
     *                           {@code null}
     * @param title02            {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:48} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:48}; at most 40 characters, or
     *                           {@code null}
     * @param currentTime        {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:54} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:54}; at most 8 characters, or
     *                           {@code null}. Not 9 - that width belongs to
     *                           {@code app/cpy-bms/COSGN00.CPY:54}
     * @param pageNumber         {@code PAGENOI PIC X(3)}, {@code app/cpy-bms/COCRDLI.CPY:60}; at
     *                           most 3 characters, or {@code null}. List projection only
     * @param accountId          {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDSL.CPY:60} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:66}; at most 11 characters, or
     *                           {@code null}. Zero padding is significant and is preserved
     * @param cardNumber         {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDSL.CPY:66} and
     *                           {@code app/cpy-bms/COCRDLI.CPY:72}; at most 16 characters, or
     *                           {@code null}. Protected data; leading zeros are significant and are
     *                           preserved
     * @param cardholderName     {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDSL.CPY:72}; at
     *                           most 50 characters, or {@code null}. Personal data. Detail
     *                           projection only
     * @param cardStatusCode     {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDSL.CPY:78}; at
     *                           most 1 character, or {@code null}. Detail projection only
     * @param expiryMonth        {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDSL.CPY:84}; at
     *                           most 2 characters, or {@code null}. Detail projection only
     * @param expiryYear         {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:90}; at
     *                           most 4 characters, or {@code null}. Detail projection only. There is
     *                           deliberately no expiry-day parameter
     * @param rows               the card-list rows in screen order, at most
     *                           {@link #CARD_LIST_PAGE_SIZE} of them, whose row numbers must run
     *                           {@link #FIRST_ROW_NUMBER} upward without a gap; {@code null} for a
     *                           detail payload, empty for a list payload that matched nothing
     * @param informationMessage {@code INFOMSGI}, {@code PIC X(40)} at
     *                           {@code app/cpy-bms/COCRDSL.CPY:96} and {@code PIC X(45)} at
     *                           {@code app/cpy-bms/COCRDLI.CPY:282}; at most 45 characters - the
     *                           wider declaration - or {@code null}
     * @param errorMessage       {@code ERRMSGI}, {@code PIC X(80)} at
     *                           {@code app/cpy-bms/COCRDSL.CPY:102} and {@code PIC X(78)} at
     *                           {@code app/cpy-bms/COCRDLI.CPY:288}; at most 80 characters - the
     *                           wider declaration - or {@code null}
     * @param functionKeys       {@code FKEYSI PIC X(75)}, {@code app/cpy-bms/COCRDSL.CPY:108}; at
     *                           most 75 characters, or {@code null}. Detail projection only
     * @throws IllegalArgumentException if any character argument is longer than the width its map
     *                                  declares; if {@code rows} holds more than
     *                                  {@link #CARD_LIST_PAGE_SIZE} elements; if {@code rows}
     *                                  contains a {@code null} element; or if the row numbers in
     *                                  {@code rows} are not the consecutive sequence beginning at
     *                                  {@link #FIRST_ROW_NUMBER}. The message names the offending
     *                                  field and never reproduces its value, because several
     *                                  arguments are card numbers or personal data
     */
    public CardDto(final String transactionName, final String title01, final String currentDate,
            final String programName, final String title02, final String currentTime,
            final String pageNumber, final String accountId, final String cardNumber,
            final String cardholderName, final String cardStatusCode, final String expiryMonth,
            final String expiryYear, final List<CardListRow> rows,
            final String informationMessage, final String errorMessage, final String functionKeys) {

        this.transactionName = requireWidth(transactionName, WIDTH_TRANSACTION_NAME, "transactionName");
        this.title01 = requireWidth(title01, WIDTH_TITLE, "title01");
        this.currentDate = requireWidth(currentDate, WIDTH_CURRENT_DATE, "currentDate");
        this.programName = requireWidth(programName, WIDTH_PROGRAM_NAME, "programName");
        this.title02 = requireWidth(title02, WIDTH_TITLE, "title02");
        this.currentTime = requireWidth(currentTime, WIDTH_CURRENT_TIME, "currentTime");
        this.pageNumber = requireWidth(pageNumber, WIDTH_PAGE_NUMBER, "pageNumber");
        this.accountId = requireWidth(accountId, WIDTH_ACCOUNT_ID, "accountId");
        this.cardNumber = requireWidth(cardNumber, WIDTH_CARD_NUMBER, "cardNumber");
        this.cardholderName = requireWidth(cardholderName, WIDTH_CARDHOLDER_NAME, "cardholderName");
        this.cardStatusCode = requireWidth(cardStatusCode, WIDTH_SINGLE_CHARACTER, "cardStatusCode");
        this.expiryMonth = requireWidth(expiryMonth, WIDTH_EXPIRY_MONTH, "expiryMonth");
        this.expiryYear = requireWidth(expiryYear, WIDTH_EXPIRY_YEAR, "expiryYear");
        this.rows = copyRows(rows);
        this.informationMessage =
                requireWidth(informationMessage, WIDTH_INFORMATION_MESSAGE, "informationMessage");
        this.errorMessage = requireWidth(errorMessage, WIDTH_ERROR_MESSAGE, "errorMessage");
        this.functionKeys = requireWidth(functionKeys, WIDTH_FUNCTION_KEYS, "functionKeys");
    }

    /**
     * Creates a card-detail payload from the {@value #DETAIL_FIELD_COUNT} fields of
     * {@code app/cpy-bms/COCRDSL.CPY}, in the order that member declares them.
     *
     * <p>The parameter list is the card-detail contract: reading it top to bottom reproduces lines
     * 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102 and 108 of the member in sequence.
     * <b>There is no expiry-day parameter</b>, because the member declares no {@code EXPDAY} field;
     * see the discussion of that distinction on this class. The page number and the row list are set
     * to {@code null}, because the detail map declares neither.</p>
     *
     * @param transactionName    {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:24}
     * @param title01            {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:30}
     * @param currentDate        {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:36}
     * @param programName        {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:42}
     * @param title02            {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:48}
     * @param currentTime        {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:54}
     * @param accountId          {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDSL.CPY:60}
     * @param cardNumber         {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDSL.CPY:66};
     *                           protected data
     * @param cardholderName     {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDSL.CPY:72};
     *                           personal data
     * @param cardStatusCode     {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDSL.CPY:78}
     * @param expiryMonth        {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDSL.CPY:84}
     * @param expiryYear         {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:90}
     * @param informationMessage {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:96}; a
     *                           value longer than 40 characters is accepted because the wider list
     *                           declaration governs this member, and the detail screen truncates it
     * @param errorMessage       {@code ERRMSGI PIC X(80)}, {@code app/cpy-bms/COCRDSL.CPY:102}
     * @param functionKeys       {@code FKEYSI PIC X(75)}, {@code app/cpy-bms/COCRDSL.CPY:108}
     * @return a detail payload whose page number and row list are {@code null}, never {@code null}
     *         itself
     * @throws IllegalArgumentException if any argument exceeds the width recorded above, subject to
     *                                  the information-message note; the message names the field and
     *                                  never its value
     */
    public static CardDto detail(final String transactionName, final String title01,
            final String currentDate, final String programName, final String title02,
            final String currentTime, final String accountId, final String cardNumber,
            final String cardholderName, final String cardStatusCode, final String expiryMonth,
            final String expiryYear, final String informationMessage, final String errorMessage,
            final String functionKeys) {

        return new CardDto(transactionName, title01, currentDate, programName, title02, currentTime,
                null, accountId, cardNumber, cardholderName, cardStatusCode, expiryMonth, expiryYear,
                null, informationMessage, errorMessage, functionKeys);
    }

    /**
     * Creates a card-list payload from the fields of {@code app/cpy-bms/COCRDLI.CPY}, in the order
     * that member declares them.
     *
     * <p>The parameter list is the card-list contract: the six header parameters reproduce lines 24
     * through 54, the page number is line 60, the two filters are lines 66 and 72, the row list
     * carries the seven row groups spanning lines 78 through 276, and the two messages are lines 282
     * and 288. That is {@value #LIST_FIELD_COUNT} input fields in total once the rows are expanded.
     * The four detail-only members - cardholder name, status code, expiry month and expiry year - and
     * the detail-only function-key line are set to {@code null}, because the list map declares
     * none of them.</p>
     *
     * @param transactionName    {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDLI.CPY:24}
     * @param title01            {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDLI.CPY:30}
     * @param currentDate        {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDLI.CPY:36}
     * @param programName        {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDLI.CPY:42}
     * @param title02            {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDLI.CPY:48}
     * @param currentTime        {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDLI.CPY:54}
     * @param pageNumber         {@code PAGENOI PIC X(3)}, {@code app/cpy-bms/COCRDLI.CPY:60}
     * @param accountId          {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDLI.CPY:66}; the
     *                           account filter
     * @param cardNumber         {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDLI.CPY:72}; the
     *                           card-number filter, protected data
     * @param rows               the seven row groups spanning
     *                           {@code app/cpy-bms/COCRDLI.CPY:78} to 276, in screen order; at most
     *                           {@link #CARD_LIST_PAGE_SIZE} rows, numbered consecutively from
     *                           {@link #FIRST_ROW_NUMBER}. Pass an empty list when the query matched
     *                           nothing; passing {@code null} would declare the payload a detail
     *                           projection instead
     * @param informationMessage {@code INFOMSGI PIC X(45)}, {@code app/cpy-bms/COCRDLI.CPY:282}
     * @param errorMessage       {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COCRDLI.CPY:288}; a
     *                           value longer than 78 characters is accepted because the wider detail
     *                           declaration governs this member, and the list screen truncates it
     * @return a list payload whose four detail-only members and function-key line are {@code null},
     *         never {@code null} itself
     * @throws IllegalArgumentException if any argument exceeds the width recorded above, subject to
     *                                  the error-message note, or if {@code rows} violates the size,
     *                                  nullity or numbering conditions described on
     *                                  {@link #CardDto}; the message names the field and never its
     *                                  value
     */
    public static CardDto list(final String transactionName, final String title01,
            final String currentDate, final String programName, final String title02,
            final String currentTime, final String pageNumber, final String accountId,
            final String cardNumber, final List<CardListRow> rows, final String informationMessage,
            final String errorMessage) {

        return new CardDto(transactionName, title01, currentDate, programName, title02, currentTime,
                pageNumber, accountId, cardNumber, null, null, null, null, rows, informationMessage,
                errorMessage, null);
    }

    /**
     * Returns the header transaction identifier, {@code TRNNAMEI} at line 24 of either map.
     *
     * @return at most {@value #WIDTH_TRANSACTION_NAME} characters, or {@code null} when the screen
     *         field was not populated
     */
    public String getTransactionName() {
        return transactionName;
    }

    /**
     * Returns the first header title line, {@code TITLE01I} at line 30 of either map.
     *
     * @return at most {@value #WIDTH_TITLE} characters, or {@code null} when the screen field was not
     *         populated
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Returns the current date as display text, {@code CURDATEI} at line 36 of either map.
     *
     * @return at most {@value #WIDTH_CURRENT_DATE} characters, or {@code null} when the screen field
     *         was not populated
     */
    public String getCurrentDate() {
        return currentDate;
    }

    /**
     * Returns the originating program name, {@code PGMNAMEI} at line 42 of either map.
     *
     * @return at most {@value #WIDTH_PROGRAM_NAME} characters, or {@code null} when the screen field
     *         was not populated
     */
    public String getProgramName() {
        return programName;
    }

    /**
     * Returns the second header title line, {@code TITLE02I} at line 48 of either map.
     *
     * @return at most {@value #WIDTH_TITLE} characters, or {@code null} when the screen field was not
     *         populated
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Returns the current time as display text, {@code CURTIMEI} at line 54 of either map.
     *
     * @return at most {@value #WIDTH_CURRENT_TIME} characters - not 9, which is the sign-on map's
     *         width at {@code app/cpy-bms/COSGN00.CPY:54} - or {@code null} when the screen field was
     *         not populated
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * Returns the card-list page number as display text, {@code PAGENOI} at
     * {@code app/cpy-bms/COCRDLI.CPY:60}.
     *
     * <p>The arithmetic page number, together with the next-page indicator and the page size, travels
     * on {@code PageResponse} instead; this accessor reproduces the card screen's own three-byte
     * field and nothing more.</p>
     *
     * @return at most {@value #WIDTH_PAGE_NUMBER} characters, or {@code null} - always {@code null}
     *         on a detail payload, since the detail map declares no page-number field
     */
    public String getPageNumber() {
        return pageNumber;
    }

    /**
     * Returns the account identifier, {@code ACCTSIDI} at {@code app/cpy-bms/COCRDSL.CPY:60} on the
     * detail map and at {@code app/cpy-bms/COCRDLI.CPY:66} on the list map, where it is the account
     * filter.
     *
     * @return at most {@value #WIDTH_ACCOUNT_ID} characters with any zero padding intact, or
     *         {@code null} when the screen field was not populated
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the card number, {@code CARDSIDI} at {@code app/cpy-bms/COCRDSL.CPY:66} on the detail
     * map and at {@code app/cpy-bms/COCRDLI.CPY:72} on the list map, where it is the card-number
     * filter.
     *
     * <p><b>Protected data.</b> The value is returned verbatim so that the payload round-trips
     * byte-exactly, including any significant leading zero, but it must never be written to a log, a
     * diagnostic message or an error page. No rendering of this type discloses it.</p>
     *
     * @return at most {@value #WIDTH_CARD_NUMBER} characters with any leading zeros intact, or
     *         {@code null} when the screen field was not populated
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Returns the embossed cardholder name, {@code CRDNAMEI} at
     * {@code app/cpy-bms/COCRDSL.CPY:72}.
     *
     * <p><b>Personal data.</b> Never write this value to a log or quote it in a message; a message
     * may name the field but not reproduce its contents.</p>
     *
     * @return at most {@value #WIDTH_CARDHOLDER_NAME} characters, or {@code null} - always
     *         {@code null} on a list payload, since the list map declares no cardholder-name field
     */
    public String getCardholderName() {
        return cardholderName;
    }

    /**
     * Returns the card status code, {@code CRDSTCDI} at {@code app/cpy-bms/COCRDSL.CPY:78}.
     *
     * @return a single raw character, unvalidated and unmapped so that an out-of-domain code survives
     *         to the service that reports it, or {@code null} - always {@code null} on a list
     *         payload, whose per-row status is {@link CardListRow#getStatusCode()} instead
     */
    public String getCardStatusCode() {
        return cardStatusCode;
    }

    /**
     * Returns the expiry month as display text, {@code EXPMONI} at
     * {@code app/cpy-bms/COCRDSL.CPY:84}.
     *
     * @return at most {@value #WIDTH_EXPIRY_MONTH} characters, never a date or year-month value, or
     *         {@code null} - always {@code null} on a list payload
     */
    public String getExpiryMonth() {
        return expiryMonth;
    }

    /**
     * Returns the expiry year as display text, {@code EXPYEARI} at
     * {@code app/cpy-bms/COCRDSL.CPY:90}.
     *
     * <p>There is no companion expiry-day accessor, and adding one would misreport the map: the
     * detail member declares no {@code EXPDAY} field, while
     * {@code app/cpy-bms/COCRDUP.CPY:96} declares {@code EXPDAYI PIC X(2)} for the update
     * screen.</p>
     *
     * @return at most {@value #WIDTH_EXPIRY_YEAR} characters, never a date or year-month value, or
     *         {@code null} - always {@code null} on a list payload
     */
    public String getExpiryYear() {
        return expiryYear;
    }

    /**
     * Returns the card-list rows in screen order.
     *
     * <p>The returned list is an unmodifiable view over this instance's private copy, so every
     * mutating operation on it throws {@code UnsupportedOperationException} and no caller can alter
     * the payload. Order is positional: index 0 is row 1, matching the row groups from
     * {@code app/cpy-bms/COCRDLI.CPY:78} onward.</p>
     *
     * <p><b>{@code null} and empty are different answers.</b> {@code null} means this payload is a
     * detail projection and has no row array at all; an empty list means it is a list projection whose
     * query matched nothing. Callers must distinguish the two rather than treating a missing array as
     * an empty one.</p>
     *
     * @return an unmodifiable, order-preserving view holding at most {@link #CARD_LIST_PAGE_SIZE}
     *         rows, or {@code null} on a detail payload
     */
    public List<CardListRow> getRows() {
        return rows;
    }

    /**
     * Returns the screen information message, {@code INFOMSGI} at
     * {@code app/cpy-bms/COCRDSL.CPY:96} and {@code app/cpy-bms/COCRDLI.CPY:282}.
     *
     * @return at most {@value #WIDTH_INFORMATION_MESSAGE} characters, that being the wider of the two
     *         declarations; a detail screen renders only the first 40 and truncates the remainder.
     *         {@code null} when the screen field was not populated
     */
    public String getInformationMessage() {
        return informationMessage;
    }

    /**
     * Returns the screen error message, {@code ERRMSGI} at {@code app/cpy-bms/COCRDSL.CPY:102} and
     * {@code app/cpy-bms/COCRDLI.CPY:288}.
     *
     * @return at most {@value #WIDTH_ERROR_MESSAGE} characters, that being the wider of the two
     *         declarations; a list screen renders only the first 78 and truncates the remainder.
     *         {@code null} when the screen field was not populated
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the function-key legend line, {@code FKEYSI} at
     * {@code app/cpy-bms/COCRDSL.CPY:108}.
     *
     * @return at most {@value #WIDTH_FUNCTION_KEYS} characters, or {@code null} - always {@code null}
     *         on a list payload, since the list map declares no function-key field
     */
    public String getFunctionKeys() {
        return functionKeys;
    }

    /**
     * Verifies that a character value fits the fixed width its BMS field declares, and returns it
     * unchanged.
     *
     * <p>The check is a structural guard on a fixed-width contract, not business validation: a value
     * that cannot fit the screen field cannot have come from it, and letting it through would break
     * byte-exact comparison against the legacy baseline. Nothing is normalised - the value is not
     * trimmed, not case-folded and not padded - so an absent value, a blank value and a value of
     * binary zeros remain three distinguishable states, exactly as
     * {@code 88 SELECT-BLANK VALUES SPACE, LOW-VALUES} at {@code app/cbl/COCRDLIC.cbl:80-83} treats
     * them. A {@code null} value is passed straight through, because absence is legitimate on every
     * field of both maps.</p>
     *
     * @param value     the candidate value; {@code null} is accepted and returned unchanged
     * @param maxWidth  the width the BMS field declares, in characters
     * @param fieldName the name of the field being checked, used to identify it in a diagnostic
     *                  message without reproducing its value
     * @return {@code value}, unchanged and unnormalised
     * @throws IllegalArgumentException if {@code value} is longer than {@code maxWidth}. The message
     *                                  names the field and states the declared width, and
     *                                  deliberately omits the value, because a card number or a
     *                                  cardholder name must never reach a log or an error page
     */
    private static String requireWidth(final String value, final int maxWidth, final String fieldName) {
        if (value != null && value.length() > maxWidth) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Field %s holds %d characters but the BMS map declares it as PIC X(%d); the value is "
                            + "withheld from this message because the card maps carry protected data",
                    fieldName, value.length(), maxWidth));
        }
        return value;
    }

    /**
     * Validates and defensively copies the card-list rows, returning an unmodifiable view.
     *
     * <p>The copy is what keeps this payload immutable: a caller that mutates the list it passed in
     * cannot be observed through the instance, and the view returned to callers rejects mutation.
     * Row order is preserved exactly and is required to be positional, so a reader can rely on index
     * 0 being row 1 without consulting {@link CardListRow#getRowNumber()}.</p>
     *
     * @param rows the rows to copy; {@code null} is accepted and returned as {@code null}, which is
     *             how a detail payload records that the detail map declares no row array
     * @return an unmodifiable copy preserving the supplied order, or {@code null} when {@code rows}
     *         was {@code null}
     * @throws IllegalArgumentException if more than {@link #CARD_LIST_PAGE_SIZE} rows are supplied,
     *                                  if any element is {@code null}, or if the row numbers are not
     *                                  the consecutive sequence beginning at
     *                                  {@link #FIRST_ROW_NUMBER}. The message names the field and the
     *                                  position at fault and never a field value
     */
    private static List<CardListRow> copyRows(final List<CardListRow> rows) {
        if (rows == null) {
            return null;
        }
        if (rows.size() > CARD_LIST_PAGE_SIZE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Field rows holds %d rows but the card list shows at most %d per page, per "
                            + "WS-MAX-SCREEN-LINES at app/cbl/COCRDLIC.cbl:177-178",
                    rows.size(), CARD_LIST_PAGE_SIZE));
        }

        // Pre-sized so the copy is allocated once at its final length and never resized while it is
        // built. The size check above proves the page size is an upper bound on the row count.
        final List<CardListRow> defensiveCopy = new ArrayList<>(rows.size());
        for (final CardListRow row : rows) {
            // defensiveCopy.size() is the index within rows of the element being examined, so the
            // expected row number is that index plus the one-based origin of the map's row groups.
            final int position = defensiveCopy.size();
            if (row == null) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Field rows must not contain a null element, but index %d was null", position));
            }
            final int expectedRowNumber = position + FIRST_ROW_NUMBER;
            if (row.getRowNumber() != expectedRowNumber) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Field rows must be positional: index %d must carry row number %d but carries %d; "
                                + "the card list map numbers its row groups consecutively from %d",
                        position, expectedRowNumber, row.getRowNumber(), FIRST_ROW_NUMBER));
            }
            defensiveCopy.add(row);
        }
        return Collections.unmodifiableList(defensiveCopy);
    }

    /**
     * One row of the card list, carrying the fields of a single row group of
     * {@code app/cpy-bms/COCRDLI.CPY}.
     *
     * <p><b>What this type does.</b> The card-list map declares seven row groups spanning lines 78
     * through 276, and this type is one of them. It is nested inside {@code CardDto} rather than
     * declared as a separate file because it has no meaning apart from the list payload that owns it,
     * and because the data-transfer package has a fixed artefact budget.</p>
     *
     * <p><b>Row 1 has four fields; rows 2 through 7 have five.</b> This is the asymmetry described at
     * length on the enclosing class, and this type is where it is enforced rather than merely
     * described. The selector-type field is declared as {@code CRDSTP2I} through {@code CRDSTP7I} at
     * {@code app/cpy-bms/COCRDLI.CPY:108}, 138, 168, 198, 228 and 258 - six declarations - and a
     * token search for {@code CRDSTP1} across the member returns zero occurrences.
     * <b>Row 1 therefore has no selector type, and this constructor rejects one.</b> The absence is
     * structural: {@link #hasSelectorType()} reports it, {@link #getBmsFieldCount()} counts it, and
     * {@link #getSelectorType()} is contractually {@code null} for row 1 and can never be anything
     * else. Inventing a {@code CRDSTP1I} would make the row array uniform, raise the map's field
     * count from {@value CardDto#LIST_FIELD_COUNT} to 46 and misreport the source.</p>
     *
     * <p><b>Inputs, outputs and side effects.</b> A pure data holder. All six members are assigned
     * once during construction and never mutated, and the type is {@code final}, so an instance may
     * be shared across threads. No value is trimmed, case-folded or padded, so absence, blankness and
     * binary zeros stay three distinct states. Nothing is logged and there are no side effects.</p>
     *
     * <p><b>Protected data.</b> Each row carries a card number, so seven rows carry seven of the eight
     * card numbers on a full list payload. <b>This type declares no {@code toString}</b>, and
     * therefore inherits the {@code Object} rendering, which discloses no field value. It is a class
     * rather than a record for exactly that reason: a record's generated {@code toString} would emit
     * the card number.</p>
     *
     * <p><b>Error modes.</b> Every accessor is total and throws nothing. The constructor throws
     * {@code IllegalArgumentException}, naming the field but never its value, when the row number
     * lies outside {@link CardDto#FIRST_ROW_NUMBER} through {@link CardDto#CARD_LIST_PAGE_SIZE}, when
     * a selector type is supplied for row {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}, or when
     * any character value exceeds the width its map declares.</p>
     */
    public static final class CardListRow {

        /**
         * The one-based position of this row within the page, from
         * {@link CardDto#FIRST_ROW_NUMBER} to {@link CardDto#CARD_LIST_PAGE_SIZE}.
         *
         * <p>This is structural metadata and <b>not</b> a BMS input field, so it does not count
         * towards {@value CardDto#LIST_FIELD_COUNT}. It exists because the map does not declare a
         * uniform row array: the row number is what determines whether a selector-type field exists,
         * and it is what lets the enclosing payload verify that the rows it was handed are
         * positional.</p>
         */
        private final int rowNumber;

        /**
         * The row selection flag, {@code CRDSEL&lt;n&gt;I PIC X(1)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:78}, then rows 2 through 7 at lines 102, 132, 162, 192, 222
         * and 252.
         *
         * <p>The legacy program interprets it through {@code 88} conditions at
         * {@code app/cbl/COCRDLIC.cbl:77-83}: {@code 'S'} requests the detail view, {@code 'U'}
         * requests the update screen, and a space or {@code LOW-VALUES} means the row was not
         * selected. The two "not selected" sentinels are declared as separate values there, which is
         * why no normalisation is applied here. Kept as a raw character rather than an enumeration so
         * that an out-of-domain flag reaches the service that reports it. May be {@code null}.</p>
         */
        private final String selectionFlag;

        /**
         * The row selector-type field, {@code CRDSTP&lt;n&gt;I PIC X(1)} for rows 2 through 7 at
         * {@code app/cpy-bms/COCRDLI.CPY:108}, 138, 168, 198, 228 and 258.
         *
         * <p><b>Always {@code null} for row {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE},
         * because the map declares no {@code CRDSTP1I}.</b> The constructor enforces that; there is
         * no way to construct a row 1 that carries one. For rows 2 through 7 the value may still be
         * {@code null}, which means the screen field was not populated - a different thing from the
         * field not existing.</p>
         *
         * <p>No program in the corpus reads or writes these fields: a search for {@code CRDSTP}
         * across {@code app/cbl} returns no matches. They are reproduced because the map declares
         * them, not because any behaviour depends on them.</p>
         */
        private final String selectorType;

        /**
         * The row account number, {@code ACCTNO&lt;n&gt;I PIC X(11)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:84}, then rows 2 through 7 at lines 114, 144, 174, 204, 234
         * and 264.
         *
         * <p>A {@code String} so that the zero padding of an eleven-digit identifier survives. May be
         * {@code null}.</p>
         */
        private final String accountNumber;

        /**
         * The row card number, {@code CRDNUM&lt;n&gt;I PIC X(16)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:90}, then rows 2 through 7 at lines 120, 150, 180, 210, 240
         * and 270.
         *
         * <p>Alphanumeric per {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:5}, so
         * leading zeros are significant and are preserved. <b>Protected data: never emitted in any
         * rendering.</b> May be {@code null}.</p>
         */
        private final String cardNumber;

        /**
         * The row card status, {@code CRDSTS&lt;n&gt;I PIC X(1)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:96}, then rows 2 through 7 at lines 126, 156, 186, 216, 246
         * and 276.
         *
         * <p>A raw character rather than an enumeration, for the same reason as the detail map's
         * {@code CRDSTCDI}. May be {@code null}.</p>
         */
        private final String statusCode;

        /**
         * Constructs one card-list row.
         *
         * <p>Row 1 must be constructed with a {@code null} selector type, because
         * {@code app/cpy-bms/COCRDLI.CPY} declares no {@code CRDSTP1I}. Supplying one is rejected
         * rather than ignored, so that the map's field count cannot be inflated by a caller. Every
         * character argument is optional; none is trimmed or case-folded, and each is length-checked
         * against the width its map declares.</p>
         *
         * @param rowNumber     the one-based position of this row within the page; must be between
         *                      {@link CardDto#FIRST_ROW_NUMBER} and
         *                      {@link CardDto#CARD_LIST_PAGE_SIZE} inclusive. Structural metadata,
         *                      not a BMS field
         * @param selectionFlag {@code CRDSEL&lt;n&gt;I PIC X(1)}, first declared at
         *                      {@code app/cpy-bms/COCRDLI.CPY:78}; at most 1 character, or
         *                      {@code null}
         * @param selectorType  {@code CRDSTP&lt;n&gt;I PIC X(1)}, declared for rows 2 through 7 only
         *                      at {@code app/cpy-bms/COCRDLI.CPY:108}, 138, 168, 198, 228 and 258; at
         *                      most 1 character, or {@code null}. <b>Must be {@code null} when
         *                      {@code rowNumber} is
         *                      {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}</b>
         * @param accountNumber {@code ACCTNO&lt;n&gt;I PIC X(11)}, first declared at
         *                      {@code app/cpy-bms/COCRDLI.CPY:84}; at most 11 characters, or
         *                      {@code null}
         * @param cardNumber    {@code CRDNUM&lt;n&gt;I PIC X(16)}, first declared at
         *                      {@code app/cpy-bms/COCRDLI.CPY:90}; at most 16 characters, or
         *                      {@code null}. Protected data
         * @param statusCode    {@code CRDSTS&lt;n&gt;I PIC X(1)}, first declared at
         *                      {@code app/cpy-bms/COCRDLI.CPY:96}; at most 1 character, or
         *                      {@code null}
         * @throws IllegalArgumentException if {@code rowNumber} is outside
         *                                  {@link CardDto#FIRST_ROW_NUMBER} through
         *                                  {@link CardDto#CARD_LIST_PAGE_SIZE}; if a non-null
         *                                  {@code selectorType} is supplied for row
         *                                  {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}; or if
         *                                  any character argument exceeds the width recorded above.
         *                                  The message names the offending field and never
         *                                  reproduces its value
         */
        public CardListRow(final int rowNumber, final String selectionFlag, final String selectorType,
                final String accountNumber, final String cardNumber, final String statusCode) {

            if (rowNumber < FIRST_ROW_NUMBER || rowNumber > CARD_LIST_PAGE_SIZE) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Field rowNumber must be between %d and %d because app/cpy-bms/COCRDLI.CPY "
                                + "declares exactly %d row groups, but was %d",
                        FIRST_ROW_NUMBER, CARD_LIST_PAGE_SIZE, CARD_LIST_PAGE_SIZE, rowNumber));
            }
            if (rowNumber == ROW_NUMBER_WITHOUT_SELECTOR_TYPE && selectorType != null) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Field selectorType must be null for row %d: app/cpy-bms/COCRDLI.CPY declares "
                                + "CRDSTP2I through CRDSTP7I at lines 108, 138, 168, 198, 228 and 258 and "
                                + "declares no CRDSTP1I anywhere, so row %d has %d fields and not %d",
                        ROW_NUMBER_WITHOUT_SELECTOR_TYPE, ROW_NUMBER_WITHOUT_SELECTOR_TYPE,
                        ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE, ROW_FIELD_COUNT_WITH_SELECTOR_TYPE));
            }

            this.rowNumber = rowNumber;
            this.selectionFlag = requireWidth(selectionFlag, WIDTH_SINGLE_CHARACTER, "selectionFlag");
            this.selectorType = requireWidth(selectorType, WIDTH_SINGLE_CHARACTER, "selectorType");
            this.accountNumber = requireWidth(accountNumber, WIDTH_ACCOUNT_ID, "accountNumber");
            this.cardNumber = requireWidth(cardNumber, WIDTH_CARD_NUMBER, "cardNumber");
            this.statusCode = requireWidth(statusCode, WIDTH_SINGLE_CHARACTER, "statusCode");
        }

        /**
         * Returns the one-based position of this row within the page.
         *
         * @return a value between {@link CardDto#FIRST_ROW_NUMBER} and
         *         {@link CardDto#CARD_LIST_PAGE_SIZE} inclusive
         */
        public int getRowNumber() {
            return rowNumber;
        }

        /**
         * Returns the row selection flag, {@code CRDSEL&lt;n&gt;I}.
         *
         * @return a single raw character - {@code 'S'} for a view request or {@code 'U'} for an
         *         update request, per {@code app/cbl/COCRDLIC.cbl:78-79} - or {@code null} when the
         *         screen field was not populated. A blank is a legitimate value and is not the same
         *         as {@code null}
         */
        public String getSelectionFlag() {
            return selectionFlag;
        }

        /**
         * Returns the row selector-type field, {@code CRDSTP&lt;n&gt;I}.
         *
         * <p><b>Contractually {@code null} for row
         * {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}</b>, which has no such field on the map;
         * the constructor makes any other outcome unconstructable. For rows 2 through 7 a
         * {@code null} means the field exists but was not populated. Use
         * {@link #hasSelectorType()} to tell the two situations apart.</p>
         *
         * @return a single raw character, or {@code null} - always {@code null} for row
         *         {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}
         */
        public String getSelectorType() {
            return selectorType;
        }

        /**
         * Reports whether the card-list map declares a selector-type field for this row at all.
         *
         * <p>This distinguishes "the field does not exist" from "the field exists but is empty",
         * which a {@code null} from {@link #getSelectorType()} alone cannot do. It is {@code false}
         * for row {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE} and {@code true} for rows 2
         * through 7, following the six {@code CRDSTP2I} through {@code CRDSTP7I} declarations at
         * {@code app/cpy-bms/COCRDLI.CPY:108}, 138, 168, 198, 228 and 258.</p>
         *
         * @return {@code false} for row {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE},
         *         {@code true} for every other row
         */
        public boolean hasSelectorType() {
            return rowNumber != ROW_NUMBER_WITHOUT_SELECTOR_TYPE;
        }

        /**
         * Returns the number of BMS input fields the card-list map declares for this row.
         *
         * <p>{@value CardDto#ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE} for row
         * {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE} and
         * {@value CardDto#ROW_FIELD_COUNT_WITH_SELECTOR_TYPE} for rows 2 through 7. Summing this over
         * a full page gives 4 + (6 &times; 5) = 34, which with the 9 preamble fields and the 2
         * trailer fields makes the map's {@value CardDto#LIST_FIELD_COUNT}. The row number itself is
         * structural metadata and is not counted.</p>
         *
         * @return {@value CardDto#ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE} for row
         *         {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}, otherwise
         *         {@value CardDto#ROW_FIELD_COUNT_WITH_SELECTOR_TYPE}
         */
        public int getBmsFieldCount() {
            return hasSelectorType()
                    ? ROW_FIELD_COUNT_WITH_SELECTOR_TYPE
                    : ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE;
        }

        /**
         * Returns the row account number, {@code ACCTNO&lt;n&gt;I}.
         *
         * @return at most 11 characters with any zero padding intact, or {@code null} when the screen
         *         field was not populated
         */
        public String getAccountNumber() {
            return accountNumber;
        }

        /**
         * Returns the row card number, {@code CRDNUM&lt;n&gt;I}.
         *
         * <p><b>Protected data.</b> Returned verbatim so the row round-trips byte-exactly, including
         * any significant leading zero, but never to be logged or quoted in a message.</p>
         *
         * @return at most 16 characters with any leading zeros intact, or {@code null} when the screen
         *         field was not populated
         */
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Returns the row card status, {@code CRDSTS&lt;n&gt;I}.
         *
         * @return a single raw character, unvalidated and unmapped, or {@code null} when the screen
         *         field was not populated
         */
        public String getStatusCode() {
            return statusCode;
        }
    }
}
