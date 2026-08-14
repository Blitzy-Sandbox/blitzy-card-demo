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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable card payload carrying both the card-detail projection and the card-list row projection of the
 * legacy CardDemo card screens.
 *
 * <p>Two BMS symbolic maps describe the two card read screens, and this one type serves both of them.
 * {@code app/cpy-bms/COCRDSL.CPY} declares the card-detail screen behind transaction {@code CCDL} and program
 * {@code COCRDSLC}, with <b>exactly 15</b> input fields inside its {@code 01 CCRDSLAI.} group, which begins at
 * line 17 and ends where {@code 01 CCRDSLAO REDEFINES CCRDSLAI.} begins at line 109.
 * {@code app/cpy-bms/COCRDLI.CPY} declares the card-list screen behind transaction {@code CCLI} and program
 * {@code COCRDLIC}, with <b>exactly 45</b> input fields inside its {@code 01 CCRDLIAI.} group, which begins at
 * line 17 and ends where {@code 01 CCRDLIAO REDEFINES CCRDLIAI.} begins at line 289. Both counts were
 * established by counting the {@code 02 &lt;name&gt;I PIC} declarations lying strictly inside those input
 * groups, and both are published as {@link #DETAIL_FIELD_COUNT} and {@link #LIST_FIELD_COUNT} so that they are
 * machine-checkable rather than asserted in prose.
 *
 * <p>The two screens overlap heavily: the six-field terminal header is identical, and the account identifier,
 * the card number, the information message and the error message appear on both. Splitting them into a detail
 * payload and a list payload would duplicate ten of the seventeen members below, and duplication is what Rule 1
 * Clause C forbids. The fields that belong to only one screen are therefore modelled as legitimately absent on
 * the other, and every accessor documents which projection populates it. Absence is represented by
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
 * <p>Two observations close this point. The {@code CRDSTP2C}, {@code CRDSTP2P}, {@code CRDSTP2H},
 * {@code CRDSTP2V} and {@code CRDSTP2O} names appearing from {@code app/cpy-bms/COCRDLI.CPY:376}
 * onward lie inside the {@code 01 CCRDLIAO REDEFINES CCRDLIAI.} overlay that starts at line 289;
 * overlay fields are not part of the input-field budget and are not modelled here. And a search for
 * {@code CRDSTP} across {@code app/cbl} returns <b>no matches at all</b> - the selector-type fields
 * are declared on the mapset and never read or written by any program - so the field set is
 * vestigial, which is why its row 1 member was never declared. That is a preserved source
 * characteristic, reproduced rather than repaired.
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
 *       {@code app/cpy-bms/COCRDLI.CPY:282}. One member serves both, and it is validated against
 *       <b>the width of the projection it belongs to</b> - 40 on a detail payload, 45 on a list
 *       payload - not against whichever of the two is wider.</li>
 *   <li><b>Error message.</b> {@code ERRMSGI PIC X(80)} at
 *       {@code app/cpy-bms/COCRDSL.CPY:102} against {@code ERRMSGI PIC X(78)} at
 *       {@code app/cpy-bms/COCRDLI.CPY:288}. Validated per projection on the same terms - 80 on a
 *       detail payload, 78 on a list payload. Note that the two fields disagree in <b>opposite
 *       directions</b>: the list map declares the wider information message and the narrower error
 *       line, so sizing each member to whichever declaration was wider relaxed the contract on both
 *       maps at once and let a value through that neither screen could render without clipping it.
 *       Rejecting an over-wide value is the only outcome that cannot silently change data.</li>
 *   <li><b>Function keys.</b> {@code FKEYSI PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108} is
 *       declared on the detail map and is <b>absent from the list map entirely</b>. It is
 *       {@code null} on a list payload.</li>
 *   <li><b>Page number.</b> {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60} is
 *       declared on the list map and is <b>absent from the detail map entirely</b>. It is
 *       {@code null} on a detail payload.</li>
 *   </ul>
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
 * program - {@code WS-EDIT-SELECT-FLAGS PIC X(7)} at {@code app/cbl/COCRDLIC.cbl:72}, its
 * redefinition {@code WS-EDIT-SELECT PIC X(1) OCCURS 7 TIMES} at {@code :L75-L76} and
 * {@code WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES} at {@code :L86} - as well as by the seven row groups
 * on the map itself. <b>Seven is the only page size that may appear in this type.</b> The transaction
 * and user lists page by ten and the transaction report's twenty lines per page is a batch
 * report-line count rather than a page size; each belongs to its own type.
 *
 * <p><b>Card numbers, identifiers and expiry components are all {@code String}.</b> The card number
 * is alphanumeric in the record of authority: {@code app/cpy/CVACT02Y.cpy:5} declares
 * {@code CARD-NUM PIC X(16)}, and the card cluster key length is 16.
 * <b>A type divergence exists and is recorded rather than resolved:</b>
 * {@code app/cpy/COCOM01Y.cpy:41} declares the COMMAREA copy of the same value as
 * {@code CDEMO-CARD-NUM PIC 9(16)}, which is <em>numeric</em>. The two spellings are not unified
 * here; {@code String} is the one representation that survives both, and it is the only one that
 * preserves a significant leading zero. The same reasoning fixes the account identifier as a
 * {@code String} of width 11: the seeded identifiers are zero-padded to the full eleven characters,
 * and a numeric type would strip that padding and break byte-exact comparison
 * against the legacy baseline. The expiry month and year are {@code String} of widths 2 and 4 and
 * are deliberately <b>not</b> a date or year-month type: they are positional substrings of a
 * {@code PIC X(10)} value, taken by {@code MOVE CARD-EXPIRY-MONTH TO EXPMONO} at
 * {@code app/cbl/COCRDSLC.cbl:480} and {@code MOVE CARD-EXPIRY-YEAR TO EXPYEARO} at
 * {@code app/cbl/COCRDSLC.cbl:482}, and the expiry comparison in the batch corpus is a string
 * comparison, so a date type would normalise away the representation the comparison depends on.
 * Neither card map declares a monetary field, so no decimal type is needed, and every member is a
 * {@code String} apart from the structural row number - the package-wide prohibition on IEEE-754
 * binary approximate numeric types therefore has nothing here to bear on.
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
 * {@code Object} rendering, which discloses a class name and an identity hash and no field value. That
 * is also why this type is a class rather than a record: a record's generated {@code toString} emits
 * every component, so a record would have put all eight card numbers and the cardholder name into any
 * log line, stack trace or error page that rendered this payload, leaving the protection dependent on
 * an override a later edit could remove. The masking decorator in
 * {@code src/main/resources/logback-spring.xml} is the second line of defence, not the first. This
 * type carries no password, hash, token or signing key, the card maps declare none, and none may be
 * added.
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
 * {@code hashCode} is overridden, so instances compare by identity. This type is a transport carrier;
 * every business comparison - notably the field-by-field snapshot comparison of the account update
 * path - belongs to the service layer rather than to a payload. Callers that need to compare two
 * payloads compare the accessors they care about, which also keeps a card number out of any
 * incidental comparison. The omission is a decision, not an oversight.
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
 * <p><b>Two source facts a reader may look for:</b>
 * <ul>
 *   <li><b>Input group names.</b> The two input groups are named {@code 01 CCRDSLAI.} and
 *       {@code 01 CCRDLIAI.}, both at line 17 of their members, following the BMS map names
 *       {@code CCRDSLA} and {@code CCRDLIA} declared as {@code LIT-THISMAP} at
 *       {@code app/cbl/COCRDSLC.cbl:169-170} and {@code app/cbl/COCRDLIC.cbl:185-186}. Those are the
 *       group names to search for on disk; the mapset names are {@code COCRDSL} and
 *       {@code COCRDLI}.</li>
 *   <li><b>A preserved source characteristic, not a defect.</b> The absent {@code CRDSTP1I} described
 *       above is a property of the system of record, reproduced faithfully. It is recorded here so
 *       that it is not mistaken for an omission in this file and repaired by a later edit.</li>
 *   </ul>
 *
 * <p><b>Building, testing and troubleshooting.</b> This type belongs to the single Maven module at
 * the repository root. Build it with {@code ./mvnw -B clean compile} and exercise it with
 * {@code ./mvnw -B clean test}. The module compiles under {@code -Xlint:all} with {@code -Werror} and
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
     * Rows displayed per page by the card list, namely 7, from
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:177-178}.
     */
    public static final int CARD_LIST_PAGE_SIZE = 7;

    /**
     * The number of input fields the card-detail map declares, namely 15, counted inside the
     * {@code 01 CCRDSLAI} input group at {@code app/cpy-bms/COCRDSL.CPY:17-108}.
     */
    public static final int DETAIL_FIELD_COUNT = 15;

    /**
     * The number of input fields the card-list map declares, namely 45, counted inside the
     * {@code 01 CCRDLIAI} input group at {@code app/cpy-bms/COCRDLI.CPY:17-288}.
     */
    public static final int LIST_FIELD_COUNT = 45;

    /**
     * The number of the first card-list row, namely 1, because the map numbers its row groups from one: the
     * first row's fields are {@code CRDSEL1I}, {@code ACCTNO1I}, {@code CRDNUM1I} and {@code CRDSTS1I} at
     * {@code app/cpy-bms/COCRDLI.CPY:78}, 84, 90 and 96.
     */
    public static final int FIRST_ROW_NUMBER = 1;

    /**
     * The one row number whose selector-type field the card-list map does not declare, namely 1: a token search
     * over {@code app/cpy-bms/COCRDLI.CPY} returns no {@code CRDSTP1I}, though every other row declares one.
     */
    public static final int ROW_NUMBER_WITHOUT_SELECTOR_TYPE = 1;

    /**
     * The number of input fields a card-list row other than row 1 declares, namely 5, per
     * {@code app/cpy-bms/COCRDLI.CPY:78-276}: {@code CRDSEL&lt;n&gt;I},
     * {@code CRDSTP&lt;n&gt;I}, {@code ACCTNO&lt;n&gt;I}, {@code CRDNUM&lt;n&gt;I} and
     * {@code CRDSTS&lt;n&gt;I}.
     */
    public static final int ROW_FIELD_COUNT_WITH_SELECTOR_TYPE = 5;

    /**
     * The number of input fields row 1 of the card list declares, namely 4: {@code CRDSEL1I} at
     * {@code app/cpy-bms/COCRDLI.CPY:78}, {@code ACCTNO1I} at line 84, {@code CRDNUM1I} at line 90 and
     * {@code CRDSTS1I} at line 96. There is no fifth field, because no {@code CRDSTP1I} is declared anywhere in
     * the member.
     */
    public static final int ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE = 4;

    /**
     * Declared width of {@code TRNNAMEI}: {@code PIC X(4)} at line 24 of both
     * {@code app/cpy-bms/COCRDSL.CPY} and {@code app/cpy-bms/COCRDLI.CPY}.
     */
    private static final int WIDTH_TRANSACTION_NAME = 4;

    /**
     * Declared width of {@code TITLE01I} and {@code TITLE02I}: {@code PIC X(40)} on both card maps, at lines 30
     * and 48.
     */
    private static final int WIDTH_TITLE = 40;

    /**
     * Declared width of {@code CURDATEI}: {@code PIC X(8)} on both card maps, at line 36.
     */
    private static final int WIDTH_CURRENT_DATE = 8;

    /**
     * Declared width of {@code PGMNAMEI}: {@code PIC X(8)} on both card maps, at line 42.
     */
    private static final int WIDTH_PROGRAM_NAME = 8;

    /**
     * Declared width of {@code CURTIMEI}: {@code PIC X(8)} on both card maps, at line 54. The sign-on map
     * declares the same field one byte wider, {@code PIC X(9)} at {@code app/cpy-bms/COSGN00.CPY:54}, which is
     * why this width is local to this type.
     */
    private static final int WIDTH_CURRENT_TIME = 8;

    /**
     * Declared width of {@code PAGENOI}: {@code PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60}. The other two
     * paging maps declare {@code PAGENUMI PIC X(8)} instead; that divergence is documented by
     * {@code PageResponse} and is not reconciled here.
     */
    private static final int WIDTH_PAGE_NUMBER = 3;

    /**
     * Declared width of {@code ACCTSIDI} and of each row's {@code ACCTNO&lt;n&gt;I}: {@code PIC X(11)}, at
     * {@code app/cpy-bms/COCRDSL.CPY:60} and {@code app/cpy-bms/COCRDLI.CPY:66}, and at
     * {@code app/cpy-bms/COCRDLI.CPY:84} onward for the rows. It matches the 11-byte key of the account
     * cluster.
     */
    private static final int WIDTH_ACCOUNT_ID = 11;

    /**
     * Declared width of {@code CARDSIDI} and of each row's {@code CRDNUM&lt;n&gt;I}: {@code PIC X(16)}, at
     * {@code app/cpy-bms/COCRDSL.CPY:66} and {@code app/cpy-bms/COCRDLI.CPY:72}, and at
     * {@code app/cpy-bms/COCRDLI.CPY:90} onward for the rows. It matches {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:5} and the 16-byte key of the card cluster.
     */
    private static final int WIDTH_CARD_NUMBER = 16;

    /**
     * Declared width of {@code CRDNAMEI}: {@code PIC X(50)} at {@code app/cpy-bms/COCRDSL.CPY:72}.
     */
    private static final int WIDTH_CARDHOLDER_NAME = 50;

    /**
     * Declared width of every single-character screen field on the two card maps: {@code CRDSTCDI} at
     * {@code app/cpy-bms/COCRDSL.CPY:78}, and each row's {@code CRDSEL&lt;n&gt;I}, {@code CRDSTP&lt;n&gt;I} and
     * {@code CRDSTS&lt;n&gt;I} on the list map. All are {@code PIC X(1)}.
     */
    private static final int WIDTH_SINGLE_CHARACTER = 1;

    /**
     * Declared width of {@code EXPMONI}: {@code PIC X(2)} at {@code app/cpy-bms/COCRDSL.CPY:84}.
     */
    private static final int WIDTH_EXPIRY_MONTH = 2;

    /**
     * Declared width of {@code EXPYEARI}: {@code PIC X(4)} at {@code app/cpy-bms/COCRDSL.CPY:90}.
     */
    private static final int WIDTH_EXPIRY_YEAR = 4;

    /**
     * Width applied to the information message: 45, the <em>wider</em> of the two declarations, from
     * {@code app/cpy-bms/COCRDLI.CPY:282}.
     */
    private static final int WIDTH_DETAIL_INFORMATION_MESSAGE = 40;

    /**
     * Width applied to the error message: 80, the <em>wider</em> of the two declarations, from
     * {@code app/cpy-bms/COCRDSL.CPY:102}.
     */
    private static final int WIDTH_LIST_INFORMATION_MESSAGE = 45;

    /**
     * Declared width of {@code ERRMSGI} on the <b>detail</b> map: {@code PIC X(80)} at
     * {@code app/cpy-bms/COCRDSL.CPY:102}.
     */
    private static final int WIDTH_DETAIL_ERROR_MESSAGE = 80;

    /**
     * Declared width of {@code ERRMSGI} on the <b>list</b> map: {@code PIC X(78)} at
     * {@code app/cpy-bms/COCRDLI.CPY:288}.
     *
     * <p>Note that the two message fields disagree in <em>opposite directions</em>: the list map
     * declares the wider information message and the narrower error line. Sizing both to whichever
     * declaration happened to be wider therefore relaxed the contract on both maps at once, which is
     * why each width is now declared and applied separately.</p>
     */
    private static final int WIDTH_LIST_ERROR_MESSAGE = 78;

    /**
     * Declared width of {@code FKEYSI}: {@code PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108}.
     */
    private static final int WIDTH_FUNCTION_KEYS = 75;

    /**
     * The transaction identifier shown in the screen header. Detail field 1, list field 1. {@code TRNNAMEI PIC X(4)}
     * at {@code app/cpy-bms/COCRDSL.CPY:24} and {@code app/cpy-bms/COCRDLI.CPY:24}.
     */
    private final String transactionName;

    /**
     * The first header title line. Detail field 2, list field 2. {@code TITLE01I PIC X(40)} at
     * {@code app/cpy-bms/COCRDSL.CPY:30} and {@code app/cpy-bms/COCRDLI.CPY:30}.
     */
    private final String title01;

    /**
     * The current date as the screen renders it. Detail field 3, list field 3. {@code CURDATEI PIC X(8)} at
     * {@code app/cpy-bms/COCRDSL.CPY:36} and {@code app/cpy-bms/COCRDLI.CPY:36}.
     */
    private final String currentDate;

    /**
     * The name of the program that produced the screen. Detail field 4, list field 4. {@code PGMNAMEI PIC X(8)} at
     * {@code app/cpy-bms/COCRDSL.CPY:42} and {@code app/cpy-bms/COCRDLI.CPY:42}.
     */
    private final String programName;

    /**
     * The second header title line. Detail field 5, list field 5. {@code TITLE02I PIC X(40)} at
     * {@code app/cpy-bms/COCRDSL.CPY:48} and {@code app/cpy-bms/COCRDLI.CPY:48}.
     */
    private final String title02;

    /**
     * The current time as the screen renders it. Detail field 6, list field 6. {@code CURTIMEI PIC X(8)} at
     * {@code app/cpy-bms/COCRDSL.CPY:54} and {@code app/cpy-bms/COCRDLI.CPY:54}.
     */
    private final String currentTime;

    /**
     * The page number of a card-list page. <b>List field 7; absent from the detail map.</b> {@code PAGENOI PIC X(3)}
     * at {@code app/cpy-bms/COCRDLI.CPY:60}.
     */
    private final String pageNumber;

    /**
     * The account identifier. Detail field 7; list field 8, where it is the account filter.
     * {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COCRDSL.CPY:60}.
     */
    private final String accountId;

    /**
     * The card number. Detail field 8; list field 9, where it is the card-number filter. {@code CARDSIDI PIC X(16)}
     * at {@code app/cpy-bms/COCRDSL.CPY:66}.
     */
    private final String cardNumber;

    /**
     * The embossed cardholder name. <b>Detail field 9; absent from the list map.</b> {@code CRDNAMEI PIC X(50)} at
     * {@code app/cpy-bms/COCRDSL.CPY:72}.
     */
    private final String cardholderName;

    /**
     * The card status code, a raw single character. <b>Detail field 10; absent from the list map.</b>
     * {@code CRDSTCDI PIC X(1)} at {@code app/cpy-bms/COCRDSL.CPY:78}.
     */
    private final String cardStatusCode;

    /**
     * The expiry month as two display characters. <b>Detail field 11; absent from the list map.</b>
     * {@code EXPMONI PIC X(2)} at {@code app/cpy-bms/COCRDSL.CPY:84}.
     */
    private final String expiryMonth;

    /**
     * The expiry year as four display characters. <b>Detail field 12; absent from the list map.</b>
     * {@code EXPYEARI PIC X(4)} at {@code app/cpy-bms/COCRDSL.CPY:90}.
     */
    private final String expiryYear;

    /**
     * The card-list rows in screen order, or {@code null} on a detail payload. The list map declares seven row
     * groups, opening at {@code CRDSEL1I PIC X(1)}, {@code app/cpy-bms/COCRDLI.CPY:78}, and closing at
     * {@code CRDSTS7I}, {@code app/cpy-bms/COCRDLI.CPY:276}; the page size of seven is the parity contract set
     * at {@code app/cbl/COCRDLIC.cbl:177-178}.
     */
    private final List<CardListRow> rows;

    /**
     * The screen information message. Detail field 13, list field 44. {@code INFOMSGI} is
     * {@code PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:96} but {@code PIC X(45)} at
     * {@code app/cpy-bms/COCRDLI.CPY:282}, and the wider declaration governs.
     */
    private final String informationMessage;

    /**
     * The screen error message. Detail field 14, list field 45. {@code ERRMSGI} is {@code PIC X(80)} at
     * {@code app/cpy-bms/COCRDSL.CPY:102} but {@code PIC X(78)} at {@code app/cpy-bms/COCRDLI.CPY:288}, and the
     * wider declaration governs.
     */
    private final String errorMessage;

    /**
     * The function-key legend line. <b>Detail field 15; absent from the list map.</b> {@code FKEYSI PIC X(75)} at
     * {@code app/cpy-bms/COCRDSL.CPY:108}.
     */
    private final String functionKeys;

    /**
     * Constructs a card payload from every member of both projections.
     *
     * @param transactionName {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:24} and
     * {@code app/cpy-bms/COCRDLI.CPY:24}.
     * @param title01 {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:30} and
     * {@code app/cpy-bms/COCRDLI.CPY:30}.
     * @param currentDate {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:36} and
     * {@code app/cpy-bms/COCRDLI.CPY:36}.
     * @param programName {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:42} and
     * {@code app/cpy-bms/COCRDLI.CPY:42}.
     * @param title02 {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:48} and
     * {@code app/cpy-bms/COCRDLI.CPY:48}.
     * @param currentTime {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:54} and
     * {@code app/cpy-bms/COCRDLI.CPY:54}.
     * @param pageNumber {@code PAGENOI PIC X(3)}, {@code app/cpy-bms/COCRDLI.CPY:60}.
     * @param accountId {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDSL.CPY:60} and
     * {@code app/cpy-bms/COCRDLI.CPY:66}.
     * @param cardNumber {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDSL.CPY:66} and
     * {@code app/cpy-bms/COCRDLI.CPY:72}.
     * @param cardholderName {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDSL.CPY:72}.
     * @param cardStatusCode {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDSL.CPY:78}.
     * @param expiryMonth {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDSL.CPY:84}.
     * @param expiryYear {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:90}.
     * @param rows the card-list rows in screen order, at most {@link #CARD_LIST_PAGE_SIZE} of them, whose row
     * numbers must run {@link #FIRST_ROW_NUMBER} upward without a gap.
     * @param informationMessage {@code INFOMSGI}, {@code PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:96} and
     * {@code PIC X(45)} at {@code app/cpy-bms/COCRDLI.CPY:282}.
     * @param errorMessage {@code ERRMSGI}, {@code PIC X(80)} at {@code app/cpy-bms/COCRDSL.CPY:102} and
     * {@code PIC X(78)} at {@code app/cpy-bms/COCRDLI.CPY:288}.
     * @param functionKeys {@code FKEYSI PIC X(75)}, {@code app/cpy-bms/COCRDSL.CPY:108}.
     * @throws IllegalArgumentException if any character argument is longer than the width its map declares.
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

        // The two message fields are the only members whose declared width differs between the two
        // maps, so they are the only members validated against the projection rather than against a
        // single constant. A null row list means a detail payload and a non-null one means a list
        // payload, which is the same rule getRows() documents; the parameter is read rather than the
        // field only because the field assignment above has already normalised it.
        final boolean listProjection = rows != null;
        this.informationMessage = requireWidth(informationMessage,
                listProjection ? WIDTH_LIST_INFORMATION_MESSAGE : WIDTH_DETAIL_INFORMATION_MESSAGE,
                "informationMessage");
        this.errorMessage = requireWidth(errorMessage,
                listProjection ? WIDTH_LIST_ERROR_MESSAGE : WIDTH_DETAIL_ERROR_MESSAGE,
                "errorMessage");
        this.functionKeys = requireWidth(functionKeys, WIDTH_FUNCTION_KEYS, "functionKeys");
    }

    /**
     * Creates a card-detail payload from the {@value #DETAIL_FIELD_COUNT} fields of
     * {@code app/cpy-bms/COCRDSL.CPY}, in the order that member declares them.
     *
     * @param transactionName {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:24}
     * @param title01 {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:30}
     * @param currentDate {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:36}
     * @param programName {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:42}
     * @param title02 {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:48}
     * @param currentTime {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDSL.CPY:54}
     * @param accountId {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDSL.CPY:60}
     * @param cardNumber {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDSL.CPY:66}.
     * @param cardholderName {@code CRDNAMEI PIC X(50)}, {@code app/cpy-bms/COCRDSL.CPY:72}.
     * @param cardStatusCode {@code CRDSTCDI PIC X(1)}, {@code app/cpy-bms/COCRDSL.CPY:78}
     * @param expiryMonth {@code EXPMONI PIC X(2)}, {@code app/cpy-bms/COCRDSL.CPY:84}
     * @param expiryYear {@code EXPYEARI PIC X(4)}, {@code app/cpy-bms/COCRDSL.CPY:90}
     * @param informationMessage {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:96}.
     * @param errorMessage {@code ERRMSGI PIC X(80)}, {@code app/cpy-bms/COCRDSL.CPY:102}
     * @param functionKeys {@code FKEYSI PIC X(75)}, {@code app/cpy-bms/COCRDSL.CPY:108}
     * @return a detail payload whose page number and row list are {@code null}, never {@code null} itself
     * @throws IllegalArgumentException if any argument exceeds the width recorded above, subject to the
     * information-message note.
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
     * Creates a card-list payload from the fields of {@code app/cpy-bms/COCRDLI.CPY}, in the order that member
     * declares them.
     *
     * @param transactionName {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COCRDLI.CPY:24}
     * @param title01 {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COCRDLI.CPY:30}
     * @param currentDate {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COCRDLI.CPY:36}
     * @param programName {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COCRDLI.CPY:42}
     * @param title02 {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COCRDLI.CPY:48}
     * @param currentTime {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COCRDLI.CPY:54}
     * @param pageNumber {@code PAGENOI PIC X(3)}, {@code app/cpy-bms/COCRDLI.CPY:60}
     * @param accountId {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDLI.CPY:66}.
     * @param cardNumber {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDLI.CPY:72}.
     * @param rows the seven row groups spanning {@code app/cpy-bms/COCRDLI.CPY:78} to 276, in screen order.
     * @param informationMessage {@code INFOMSGI PIC X(45)}, {@code app/cpy-bms/COCRDLI.CPY:282}
     * @param errorMessage {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COCRDLI.CPY:288}.
     * @return a list payload whose four detail-only members and function-key line are {@code null}, never
     * {@code null} itself
     * @throws IllegalArgumentException if any argument exceeds the width recorded above, subject to the
     * error-message note, or if {@code rows} violates the size, nullity or numbering conditions described on
     * {@link #CardDto}.
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
     * @return at most {@value #WIDTH_TRANSACTION_NAME} characters, or {@code null} when the screen field was
     * not populated
     */
    public String getTransactionName() {
        return transactionName;
    }

    /**
     * Returns the first header title line, {@code TITLE01I} at line 30 of either map.
     *
     * @return at most {@value #WIDTH_TITLE} characters, or {@code null} when the screen field was not populated
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Returns the current date as display text, {@code CURDATEI} at line 36 of either map.
     *
     * @return at most {@value #WIDTH_CURRENT_DATE} characters, or {@code null} when the screen field was not
     * populated
     */
    public String getCurrentDate() {
        return currentDate;
    }

    /**
     * Returns the originating program name, {@code PGMNAMEI} at line 42 of either map.
     *
     * @return at most {@value #WIDTH_PROGRAM_NAME} characters, or {@code null} when the screen field was not
     * populated
     */
    public String getProgramName() {
        return programName;
    }

    /**
     * Returns the second header title line, {@code TITLE02I} at line 48 of either map.
     *
     * @return at most {@value #WIDTH_TITLE} characters, or {@code null} when the screen field was not populated
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Returns the current time as display text, {@code CURTIMEI} at line 54 of either map.
     *
     * @return at most {@value #WIDTH_CURRENT_TIME} characters - not 9, which is the sign-on map's width at
     * {@code app/cpy-bms/COSGN00.CPY:54} - or {@code null} when the screen field was not populated
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * Returns the card-list page number as display text, {@code PAGENOI} at {@code app/cpy-bms/COCRDLI.CPY:60}.
     *
     * @return at most {@value #WIDTH_PAGE_NUMBER} characters, or {@code null} - always {@code null} on a detail
     * payload, since the detail map declares no page-number field
     */
    public String getPageNumber() {
        return pageNumber;
    }

    /**
     * Returns the account identifier, {@code ACCTSIDI} at {@code app/cpy-bms/COCRDSL.CPY:60} on the detail map
     * and at {@code app/cpy-bms/COCRDLI.CPY:66} on the list map, where it is the account filter.
     *
     * @return at most {@value #WIDTH_ACCOUNT_ID} characters with any zero padding intact, or {@code null} when
     * the screen field was not populated
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the card number, {@code CARDSIDI} at {@code app/cpy-bms/COCRDSL.CPY:66} on the detail map and at
     * {@code app/cpy-bms/COCRDLI.CPY:72} on the list map, where it is the card-number filter.
     *
     * @return at most {@value #WIDTH_CARD_NUMBER} characters with any leading zeros intact, or {@code null}
     * when the screen field was not populated
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Returns the embossed cardholder name, {@code CRDNAMEI} at {@code app/cpy-bms/COCRDSL.CPY:72}.
     *
     * @return at most {@value #WIDTH_CARDHOLDER_NAME} characters, or {@code null} - always {@code null} on a
     * list payload, since the list map declares no cardholder-name field
     */
    public String getCardholderName() {
        return cardholderName;
    }

    /**
     * Returns the card status code, {@code CRDSTCDI} at {@code app/cpy-bms/COCRDSL.CPY:78}.
     *
     * @return a single raw character, unvalidated and unmapped so that an out-of-domain code survives to the
     * service that reports it, or {@code null} - always {@code null} on a list payload, whose per-row status is
     * {@link CardListRow#getStatusCode()} instead
     */
    public String getCardStatusCode() {
        return cardStatusCode;
    }

    /**
     * Returns the expiry month as display text, {@code EXPMONI} at {@code app/cpy-bms/COCRDSL.CPY:84}.
     *
     * @return at most {@value #WIDTH_EXPIRY_MONTH} characters, never a date or year-month value, or
     * {@code null} - always {@code null} on a list payload
     */
    public String getExpiryMonth() {
        return expiryMonth;
    }

    /**
     * Returns the expiry year as display text, {@code EXPYEARI} at {@code app/cpy-bms/COCRDSL.CPY:90}.
     *
     * @return at most {@value #WIDTH_EXPIRY_YEAR} characters, never a date or year-month value, or {@code null}
     * - always {@code null} on a list payload
     */
    public String getExpiryYear() {
        return expiryYear;
    }

    /**
     * Returns the card-list rows in screen order.
     *
     * @return an unmodifiable, order-preserving view holding at most {@link #CARD_LIST_PAGE_SIZE} rows, or
     * {@code null} on a detail payload
     */
    public List<CardListRow> getRows() {
        return rows;
    }

    /**
     * Returns the screen information message, {@code INFOMSGI} at {@code app/cpy-bms/COCRDSL.CPY:96} and
     * {@code app/cpy-bms/COCRDLI.CPY:282}.
     *
     * @return at most {@value #WIDTH_LIST_INFORMATION_MESSAGE} characters, that being the wider of the two
     * declarations.
     */
    public String getInformationMessage() {
        return informationMessage;
    }

    /**
     * Returns the screen error message, {@code ERRMSGI} at {@code app/cpy-bms/COCRDSL.CPY:102} and
     * {@code app/cpy-bms/COCRDLI.CPY:288}.
     *
     * @return at most {@value #WIDTH_DETAIL_ERROR_MESSAGE} characters, that being the wider of the two declarations.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the function-key legend line, {@code FKEYSI} at {@code app/cpy-bms/COCRDSL.CPY:108}.
     *
     * @return at most {@value #WIDTH_FUNCTION_KEYS} characters, or {@code null} - always {@code null} on a list
     * payload, since the list map declares no function-key field
     */
    public String getFunctionKeys() {
        return functionKeys;
    }

    /**
     * Verifies that a character value fits the fixed width its BMS field declares, and returns it unchanged.
     *
     * <p>The width is counted in Unicode code points, because a {@code PIC X(n)} clause declares n
     * character positions and the {@code character(n)} columns this payload is projected from pad to n
     * characters. A Java {@code String} counts UTF-16 code units, which differ for any supplementary-plane
     * character, so counting code units would reject a stored, padded value on the way out that the write
     * path had accepted on the way in. A code point count never exceeds a code unit count, so this is the
     * same bound rather than a looser one.
     *
     * @param value the candidate value; {@code null} is accepted and returned unchanged
     * @param maxWidth the width the BMS field declares, in character positions
     * @param fieldName the name of the field being checked, used to identify it in a diagnostic message without
     * reproducing its value
     * @return {@code value}, unchanged and unnormalised
     * @throws IllegalArgumentException if {@code value} holds more code points than {@code maxWidth}.
     */
    private static String requireWidth(final String value, final int maxWidth, final String fieldName) {
        if (value == null) {
            return null;
        }
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions > maxWidth) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Field %s holds %d characters but the BMS map declares it as PIC X(%d); the value is "
                            + "withheld from this message because the card maps carry protected data",
                    fieldName, Integer.valueOf(characterPositions), Integer.valueOf(maxWidth)));
        }
        return value;
    }

    /**
     * Validates and defensively copies the card-list rows, returning an unmodifiable view.
     *
     * @param rows the rows to copy; {@code null} is accepted and returned as {@code null}, which is how a
     * detail payload records that the detail map declares no row array
     * @return an unmodifiable copy preserving the supplied order, or {@code null} when {@code rows} was
     * {@code null}
     * @throws IllegalArgumentException if more than {@link #CARD_LIST_PAGE_SIZE} rows are supplied, if any
     * element is {@code null}, or if the row numbers are not the consecutive sequence beginning at
     * {@link #FIRST_ROW_NUMBER}.
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
     * One row of the card list, carrying the fields of a single row group of {@code app/cpy-bms/COCRDLI.CPY}.
     */
    public static final class CardListRow {

        /**
         * The one-based position of this row within the page, from {@link CardDto#FIRST_ROW_NUMBER} to
         * {@link CardDto#CARD_LIST_PAGE_SIZE}.
         */
        private final int rowNumber;

        /**
         * The row selection flag, {@code CRDSEL&lt;n&gt;I PIC X(1)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:78}, then rows 2 through 7 at lines 102, 132, 162, 192, 222 and 252.
         */
        private final String selectionFlag;

        /**
         * The row selector-type field, {@code CRDSTP&lt;n&gt;I PIC X(1)} for rows 2 through 7 at
         * {@code app/cpy-bms/COCRDLI.CPY:108}, 138, 168, 198, 228 and 258.
         */
        private final String selectorType;

        /**
         * The row account number, {@code ACCTNO&lt;n&gt;I PIC X(11)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:84}, then rows 2 through 7 at lines 114, 144, 174, 204, 234 and 264.
         */
        private final String accountNumber;

        /**
         * The row card number, {@code CRDNUM&lt;n&gt;I PIC X(16)} - row 1 at
         * {@code app/cpy-bms/COCRDLI.CPY:90}, then rows 2 through 7 at lines 120, 150, 180, 210, 240 and 270.
         */
        private final String cardNumber;

        /**
         * The row card status, {@code CRDSTS&lt;n&gt;I PIC X(1)} - row 1 at {@code app/cpy-bms/COCRDLI.CPY:96},
         * then rows 2 through 7 at lines 126, 156, 186, 216, 246 and 276.
         */
        private final String statusCode;

        /**
         * Constructs one card-list row.
         *
         * @param rowNumber the one-based position of this row within the page.
         * @param selectionFlag {@code CRDSEL&lt;n&gt;I PIC X(1)}, first declared at
         * {@code app/cpy-bms/COCRDLI.CPY:78}.
         * @param selectorType {@code CRDSTP&lt;n&gt;I PIC X(1)}, declared for rows 2 through 7 only at
         * {@code app/cpy-bms/COCRDLI.CPY:108}, 138, 168, 198, 228 and 258.
         * @param accountNumber {@code ACCTNO&lt;n&gt;I PIC X(11)}, first declared at
         * {@code app/cpy-bms/COCRDLI.CPY:84}.
         * @param cardNumber {@code CRDNUM&lt;n&gt;I PIC X(16)}, first declared at
         * {@code app/cpy-bms/COCRDLI.CPY:90}.
         * @param statusCode {@code CRDSTS&lt;n&gt;I PIC X(1)}, first declared at
         * {@code app/cpy-bms/COCRDLI.CPY:96}.
         * @throws IllegalArgumentException if {@code rowNumber} is outside {@link CardDto#FIRST_ROW_NUMBER}
         * through {@link CardDto#CARD_LIST_PAGE_SIZE}.
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
         * @return a value between {@link CardDto#FIRST_ROW_NUMBER} and {@link CardDto#CARD_LIST_PAGE_SIZE}
         * inclusive
         */
        @JsonIgnore
        public int getRowNumber() {
            return rowNumber;
        }

        /**
         * Returns the row selection flag, {@code CRDSEL&lt;n&gt;I}.
         *
         * @return a single raw character - {@code 'S'} for a view request or {@code 'U'} for an update request,
         * per {@code app/cbl/COCRDLIC.cbl:78-79} - or {@code null} when the screen field was not populated.
         */
        public String getSelectionFlag() {
            return selectionFlag;
        }

        /**
         * Returns the row selector-type field, {@code CRDSTP&lt;n&gt;I}.
         *
         * @return a single raw character, or {@code null} - always {@code null} for row
         * {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getSelectorType() {
            return selectorType;
        }

        /**
         * Reports whether the card-list map declares a selector-type field for this row at all.
         *
         * @return {@code false} for row {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}, {@code true} for
         * every other row
         */
        @JsonIgnore
        public boolean hasSelectorType() {
            return rowNumber != ROW_NUMBER_WITHOUT_SELECTOR_TYPE;
        }

        /**
         * Returns the number of BMS input fields the card-list map declares for this row.
         *
         * @return {@value CardDto#ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE} for row
         * {@value CardDto#ROW_NUMBER_WITHOUT_SELECTOR_TYPE}, otherwise
         * {@value CardDto#ROW_FIELD_COUNT_WITH_SELECTOR_TYPE}
         */
        @JsonIgnore
        public int getBmsFieldCount() {
            return hasSelectorType()
                    ? ROW_FIELD_COUNT_WITH_SELECTOR_TYPE
                    : ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE;
        }

        /**
         * Returns the row account number, {@code ACCTNO&lt;n&gt;I}.
         *
         * @return at most 11 characters with any zero padding intact, or {@code null} when the screen field was
         * not populated
         */
        public String getAccountNumber() {
            return accountNumber;
        }

        /**
         * Returns the row card number, {@code CRDNUM&lt;n&gt;I}.
         *
         * @return at most 16 characters with any leading zeros intact, or {@code null} when the screen field
         * was not populated
         */
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Returns the row card status, {@code CRDSTS&lt;n&gt;I}.
         *
         * @return a single raw character, unvalidated and unmapped, or {@code null} when the screen field was
         * not populated
         */
        public String getStatusCode() {
            return statusCode;
        }
    }
}
