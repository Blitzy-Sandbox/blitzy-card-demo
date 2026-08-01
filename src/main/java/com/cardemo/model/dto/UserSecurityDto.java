/*
 * ******************************************************************
 * Program     : UserSecurityDto.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : User-list response; ten rows per page; carries no password and no hash.
 * Source      : app/cpy-bms/COUSR00.CPY (59 fields) @ 7756d89
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

import java.util.List;

/**
 * Immutable user-list response payload for the administrator-only user administration surface, projecting
 * the {@code USRSEC} security file onto JSON.
 *
 * <p><b>What this type does.</b> The legacy user list is CICS transaction {@code CU00} running
 * {@code app/cbl/COUSR00C.cbl}, which paints one screen of user records at a time onto the mapset whose
 * generated symbolic map is {@code app/cpy-bms/COUSR00.CPY}. That symbolic map is the field contract
 * reproduced here. It declares <b>59</b> input fields, verified by direct inspection at commit
 * {@code 7756d89}, and the arithmetic is {@code 8 + 50 + 1 = 59}: eight preamble fields at
 * {@code app/cpy-bms/COUSR00.CPY}:24, :30, :36, :42, :48, :54, :60 and :66; then ten rows of five fields
 * each, spanning :72 through :366; then one trailer field at :372. This type carries those values and
 * nothing more - it applies no filtering, no paging arithmetic, no mapping and no comparison, every one
 * of which belongs to the service layer.
 *
 * <p><b>This payload carries no password and no hash.</b> That is the single most important property of
 * this type, and it is a property of the source at least as much as it is a policy of the target:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR00.CPY} - the map this type is derived from - declares no password field
 *       at all. The row quintuple is selector, user identifier, given name, family name and user type,
 *       and nothing else.</li>
 *   <li>{@code app/cpy-bms/COUSR03.CPY}, the sibling user-delete map, likewise declares no password
 *       field anywhere across its eleven fields, corroborating that the read-side maps never carried
 *       one.</li>
 *   <li>Only the add and update maps declare one: {@code PASSWDI PIC X(8)} at
 *       {@code app/cpy-bms/COUSR01.CPY}:78 and at {@code app/cpy-bms/COUSR02.CPY}:78. Those are inbound
 *       write paths and are represented by {@code UserCreateRequest} and {@code UserUpdateRequest}, not
 *       by this type.</li>
 *   <li>The persisted record is the 80-byte layout {@code app/cpy/CSUSR01Y.cpy}: identifier
 *       {@code PIC X(08)}, given name {@code PIC X(20)}, family name {@code PIC X(20)}, credential
 *       {@code PIC X(08)}, type {@code PIC X(01)} and 23 bytes of filler. In the migrated schema that
 *       eight-byte credential column becomes a 60-character BCrypt digest column. <b>Neither the
 *       credential nor its digest is ever projected onto this type</b>, so no accessor, no serialised
 *       field and no diagnostic rendering of this type is able to disclose one.</li>
 * </ul>
 *
 * <p>There is therefore deliberately no component here capable of holding a credential or a digest - not
 * even a permanently {@code null} placeholder added "for symmetry" with the create and update requests.
 * A BCrypt digest is recognisable by the version-tagged prefix it writes ahead of its cost factor; no
 * value of that shape can arise from any component declared below, because no component is ever populated
 * from the credential column. The structured logging configuration masks credentials and digests
 * profile-invariantly as a second line of defence, but the primary defence is this one: never carrying
 * them in the first place.
 *
 * <p><b>Seed data provenance.</b> The ten seeded users are held in no fixture under
 * {@code app/data/ASCII}; they exist only as inline {@code SYSUT1 DD *} card images inside
 * {@code app/jcl/DUSRSECJ.jcl}, fed through {@code IEBGENER}. Five carry user type {@code A}
 * (administrator) and five carry user type {@code U} (standard user). Each card image also carries a
 * plaintext credential, which the seed migration replaces with a BCrypt digest before it ever reaches the
 * database; that literal value is deliberately not reproduced in this file, in any test fixture for this
 * type, or in any log line. The same job defines the cluster this type projects -
 * {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED} at {@code app/jcl/DUSRSECJ.jcl}:65-68 - which is why
 * every user identifier below is exactly eight characters wide.
 *
 * <p><b>The user type stays a raw one-character code.</b> Each row's user type is the raw
 * {@code PIC X(1)} value, modelled as a {@code String} and never as the {@code UserType} enum. The enum
 * has exactly two constants, {@code ADMIN} for {@code 'A'} and {@code USER} for {@code 'U'}, derived from
 * the {@code 88}-level condition names at {@code app/cpy/COCOM01Y.cpy}:27-28. Binding a stored
 * one-character code to that enum would convert an out-of-domain stored value into a serialisation or
 * deserialisation failure of the JSON binder rather than surfacing it as data, and this type is a list
 * projection over stored data: parity requires that whatever is stored be carried, not rejected. In this
 * package only {@code CommArea}, {@code SignOnResponse} and {@code MenuResponse} bind the enum;
 * {@code UserSecurityDto}, {@code UserCreateRequest} and {@code UserUpdateRequest} all carry the raw
 * one-character code.
 *
 * <p><b>Page size is ten.</b> {@code app/cbl/COUSR00C.cbl}:57 declares
 * {@code 02 USER-REC OCCURS 10 TIMES.}, so the user list paints ten rows per screen and
 * {@link #PAGE_SIZE} is fixed at that figure. The page sizes of the other list transactions are
 * deliberately absent from this type: the card list paints seven rows per screen
 * ({@code app/cbl/COCRDLIC.cbl}:177-178) and the batch transaction report paginates by printed lines,
 * neither of which has any bearing on this map.
 *
 * <p><b>Recorded field-contract divergences.</b> Three divergences across the corpus are documented here
 * rather than normalised away, because normalising any of them would silently break parity:
 *
 * <ul>
 *   <li><i>Page-number field.</i> This map declares {@code PAGENUMI PIC X(8)} at
 *       {@code app/cpy-bms/COUSR00.CPY}:60, matching {@code app/cpy-bms/COTRN00.CPY}:60. The card list
 *       instead declares {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY}:60 - a different
 *       name <i>and</i> a different width. The eight-character form is carried verbatim here; the typed
 *       paging-metadata contract, which reconciles the three list transactions, lives in
 *       {@code PageResponse} and is not duplicated in this type.</li>
 *   <li><i>Next-page indicator.</i> The transaction list uses the literal {@code 'N'} for "no next page"
 *       ({@code app/cbl/COTRN00C.cbl}:66 declares
 *       {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}), whereas the card list uses
 *       {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.} at {@code app/cbl/COCRDLIC.cbl}:243 -
 *       binary zeros, not spaces and not {@code 'N'}. That makes the indicator a three-state value
 *       across the corpus. It is a paging concern and is modelled in {@code PageResponse}; this map
 *       declares no next-page field of its own.</li>
 *   <li><i>Header time field.</i> {@code CURTIMEI} is {@code PIC X(8)} here at
 *       {@code app/cpy-bms/COUSR00.CPY}:54, but {@code app/cpy-bms/COSGN00.CPY}:54 alone declares
 *       {@code PIC X(9)}. Because that one map disagrees, extracting the six recurring header fields
 *       into a shared base class, interface or mixin would be factually wrong rather than merely
 *       redundant - a shared abstraction would have to assert a single width that the corpus does not
 *       have. All six header fields are therefore declared inline on every map DTO, including this
 *       one.</li>
 * </ul>
 *
 * <p><b>Why the row type is not shared.</b> {@link UserRow} carries five values per row, which
 * coincidentally matches the five-per-row shape of the transaction-list projection. The coincidence is
 * structural only: the field names differ entirely and so do the widths. A shared row abstraction would
 * therefore assert a contract that neither map actually has, so the row type is nested inside this file
 * and used by this type alone.
 *
 * <p><b>Findings and severities.</b> Classified per the project's output standard:
 *
 * <ul>
 *   <li><b>Medium</b> - corpus census correction. The migration plan's prose states 460 input fields
 *       across the seventeen symbolic maps in {@code app/cpy-bms}, while a direct count of the maps
 *       totals <b>441</b> (and the plan's own per-map table sums to 440, differing from its prose). The
 *       count for this map is unaffected and independently verified at <b>59</b>, so the correction
 *       carries no build or behavioural impact and is recorded for inventory accuracy only.</li>
 *   <li><b>Low</b> - the fourteen bytes of slack between the populated fields and the declared record
 *       size of the security cluster are not modelled, because no source field occupies them.</li>
 * </ul>
 *
 * <p><b>Null, blank and marked values are three distinct states.</b> Every textual component is carried
 * exactly as supplied. A {@code null} means the field was absent, an empty string means it was present
 * and empty, and a field carrying low-values or spaces means the legacy screen marked it. No component
 * is trimmed, upper-cased or lower-cased on ingest, and {@code null} is never coerced to an empty string
 * nor an empty string to {@code null}. Where the service layer does apply a case function it must pass
 * {@code java.util.Locale#ROOT}, because the sign-on program upper-cases both the identifier and the
 * credential before comparison and a locale-sensitive conversion would change authentication outcomes.
 *
 * <p><b>Direction and validation.</b> This is an outbound projection, so it carries no inbound bean
 * validation constraints that the read paths would never exercise. The only checks applied are the
 * structural invariants of the row collection, described on the canonical constructor.
 *
 * <p><b>Error modes.</b> The canonical constructor is the only member that rejects input. It throws
 * {@link IllegalArgumentException} when {@code rows} is {@code null}, when {@code rows} contains a
 * {@code null} element, or when {@code rows} holds more than {@link #PAGE_SIZE} elements. Each message
 * names the offending component and, where applicable, the offending index, but never the offending
 * value, because every row value is either directly identifying or screen-control state. No other member
 * throws, no exception is swallowed anywhere in this type, and every accessor is total: it returns the
 * value it was constructed with, and {@link #rows()} returns an empty list rather than {@code null} when
 * the page carries no rows.
 *
 * <p><b>Build, test and configuration.</b> This type has no configuration of its own and no runtime
 * dependency beyond {@code java.util.List}; its only default is {@link #PAGE_SIZE}. It is compiled by
 * {@code ./mvnw -B clean compile} under {@code -Xlint:all -Werror}, so any warning here fails the build;
 * it is exercised by {@code ./mvnw -B test} from {@code src/test/java/com/cardemo/unit/model}; and the
 * line-coverage gate is applied at {@code ./mvnw -B verify}. The most common troubleshooting case is a
 * caller handing in more than {@link #PAGE_SIZE} rows after paging in the service layer went wrong - the
 * constructor rejects that eagerly rather than emitting an over-long page.
 *
 * @param transactionName the four-character transaction identifier of the screen,
 *                        {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COUSR00.CPY}:24; may be
 *                        {@code null} when absent
 * @param title01         the first title line of the screen header, {@code TITLE01I PIC X(40)} at
 *                        {@code app/cpy-bms/COUSR00.CPY}:30; may be {@code null} when absent
 * @param currentDate     the header date as the screen rendered it, {@code CURDATEI PIC X(8)} at
 *                        {@code app/cpy-bms/COUSR00.CPY}:36; may be {@code null} when absent
 * @param programName     the eight-character name of the program that produced the screen,
 *                        {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY}:42; may be
 *                        {@code null} when absent
 * @param title02         the second title line of the screen header, {@code TITLE02I PIC X(40)} at
 *                        {@code app/cpy-bms/COUSR00.CPY}:48; may be {@code null} when absent
 * @param currentTime     the header time as the screen rendered it, {@code CURTIMEI PIC X(8)} at
 *                        {@code app/cpy-bms/COUSR00.CPY}:54 - eight characters here, against the nine of
 *                        {@code app/cpy-bms/COSGN00.CPY}:54; may be {@code null} when absent
 * @param pageNumber      the page number exactly as the map carries it, {@code PAGENUMI PIC X(8)} at
 *                        {@code app/cpy-bms/COUSR00.CPY}:60, deliberately left as the raw eight-character
 *                        value rather than normalised against the three-character {@code PAGENOI} of the
 *                        card list; the typed paging contract lives in {@code PageResponse}. May be
 *                        {@code null} when absent
 * @param userIdInput     the eight-character user identifier the operator typed as the search key,
 *                        {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY}:66, matching the
 *                        {@code USRSEC} cluster key length of eight; may be {@code null} when the list
 *                        was requested unfiltered
 * @param rows            the page of user rows in positional order, row one first, spanning
 *                        {@code app/cpy-bms/COUSR00.CPY}:72 through :366; must not be {@code null}, must
 *                        contain no {@code null} element and must hold at most {@link #PAGE_SIZE}
 *                        elements. May be empty, which is a meaningful state meaning the page matched no
 *                        user
 * @param errorMessage    the screen's error message line, {@code ERRMSGI PIC X(78)} at
 *                        {@code app/cpy-bms/COUSR00.CPY}:372; may be {@code null} when the request
 *                        succeeded
 */
public record UserSecurityDto(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String pageNumber,
        String userIdInput,
        List<UserRow> rows,
        String errorMessage) {

    /**
     * The number of user rows one page of this projection carries: ten.
     *
     * <p>Fixed by {@code app/cbl/COUSR00C.cbl}:57, which declares
     * {@code 02 USER-REC OCCURS 10 TIMES.} in the screen work area, and corroborated by the symbolic map
     * {@code app/cpy-bms/COUSR00.CPY}, whose ten row groups run from :72 through :366. This is the upper
     * bound the canonical constructor enforces on {@link #rows()}. The page sizes of the sibling list
     * transactions are deliberately not declared here.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * The number of preamble fields the symbolic map declares ahead of the row groups: eight.
     *
     * <p>The six recurring header fields at {@code app/cpy-bms/COUSR00.CPY}:24, :30, :36, :42, :48 and
     * :54, followed by the page number at :60 and the search key at :66.
     */
    public static final int PREAMBLE_FIELD_COUNT = 8;

    /**
     * The number of fields each row group declares: five.
     *
     * <p>Selector, user identifier, given name, family name and user type, in that order, as modelled by
     * {@link UserRow}.
     */
    public static final int ROW_FIELD_COUNT = 5;

    /**
     * The number of trailer fields the symbolic map declares after the row groups: one.
     *
     * <p>The error message line {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COUSR00.CPY}:372.
     */
    public static final int TRAILER_FIELD_COUNT = 1;

    /**
     * The total number of input fields the symbolic map declares: {@code 8 + 50 + 1 = 59}.
     *
     * <p>Derived from the three counts above rather than written as a literal, so the arithmetic that
     * proves the field contract is visible in the source and cannot drift from its parts. The figure is
     * independently verified against {@code app/cpy-bms/COUSR00.CPY} at commit {@code 7756d89}.
     */
    public static final int MAP_FIELD_COUNT =
            PREAMBLE_FIELD_COUNT + (PAGE_SIZE * ROW_FIELD_COUNT) + TRAILER_FIELD_COUNT;

    /**
     * Validates the row collection and stores it as an unmodifiable defensive copy.
     *
     * <p>The row list is copied on construction, so a later mutation of the list handed in cannot be
     * observed through this instance, and {@link #rows()} exposes only an unmodifiable copy. Row order is
     * preserved exactly as supplied, which is what makes row position meaningful: the rows are positional
     * one through ten, matching the ten row groups of the symbolic map, and no hash-ordered structure is
     * involved at any point.
     *
     * <p>An empty row list is a valid and meaningful state - it means the page matched no user - and is
     * deliberately distinct from a {@code null} list, which is rejected rather than silently coerced to
     * empty. Every other component is carried exactly as supplied, including {@code null}, because
     * absent, empty and marked are three distinct states of a screen field.
     *
     * @throws IllegalArgumentException if {@code rows} is {@code null}, if {@code rows} contains a
     *                                  {@code null} element, or if {@code rows} holds more than
     *                                  {@link #PAGE_SIZE} elements. The message names the component and,
     *                                  for a {@code null} element, its index; it never names a value,
     *                                  because every row value is either directly identifying or
     *                                  screen-control state
     */
    public UserSecurityDto {
        if (rows == null) {
            throw new IllegalArgumentException(
                    "rows must not be null; supply an empty list to represent a page that matched no user");
        }
        if (rows.size() > PAGE_SIZE) {
            throw new IllegalArgumentException("rows holds " + rows.size()
                    + " elements, which exceeds the user-list page size of " + PAGE_SIZE
                    + "; a page cannot carry more rows than the screen paints");
        }

        // Rejecting a null element here, rather than letting List.copyOf raise NullPointerException,
        // is what allows the failure to name the component and the offending index.
        int index = 0;
        for (final UserRow row : rows) {
            if (row == null) {
                throw new IllegalArgumentException(
                        "rows must not contain a null element, but index " + index + " was null");
            }
            index++;
        }

        rows = List.copyOf(rows);
    }

    /**
     * Returns the page's user rows in positional order, row one first.
     *
     * <p>The backing list is never exposed. The canonical constructor already stored an immutable copy,
     * and {@link List#copyOf(java.util.Collection)} is applied again here so the defensive copy holds on
     * access as well as on construction; because the stored list is already unmodifiable this second call
     * does not produce a further copy, so the guarantee costs nothing. Every mutating operation on the
     * returned list throws {@link UnsupportedOperationException}.
     *
     * @return an unmodifiable, order-preserving copy of the page's rows, never {@code null}; an empty
     *         list means the page matched no user
     */
    @Override
    public List<UserRow> rows() {
        return List.copyOf(rows);
    }

    /**
     * Returns a diagnostic rendering that discloses nothing about any user.
     *
     * <p>Overridden deliberately. The rendering a record generates for itself would expand every
     * component, and so would publish the search key together with the given name, family name and
     * identifier of all ten rows - straight into any log line, exception message or debugger frame that
     * happened to interpolate this object. Only {@code programName} is emitted, which names the screen
     * that produced the response and cannot identify a person. No name, no identifier, no page content
     * and nothing credential-adjacent appears, and there is no numeric or date formatting in the result,
     * so the output cannot vary with the platform locale.
     *
     * @return the type name and the originating program name only, never any user data
     */
    @Override
    public String toString() {
        return "UserSecurityDto[programName=" + programName + "]";
    }

    /**
     * One row of the user list: the five fields the symbolic map declares per row group.
     *
     * <p>{@code app/cpy-bms/COUSR00.CPY} declares ten identical row groups, each of five input fields in
     * the fixed order selector, user identifier, given name, family name, user type, with widths
     * {@code PIC X(1)}, {@code PIC X(8)}, {@code PIC X(20)}, {@code PIC X(20)} and {@code PIC X(1)}
     * respectively. Ten groups of five accounts for fifty of the map's fifty-nine fields. The row type is
     * nested here, and used by {@link UserSecurityDto} alone, because the five-per-row shape it shares
     * with the transaction-list projection is a structural coincidence: the field names differ entirely
     * and so do the widths, so a shared row abstraction would assert a contract that neither map has.
     *
     * <p>The generated field names follow the pattern {@code SEL000nI}, {@code USRIDnnI},
     * {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI}. Note that the selector's pattern breaks at
     * the last row: rows one through nine are {@code SEL0001I} through {@code SEL0009I}, while row ten is
     * {@code SEL0010I} rather than a five-digit form.
     *
     * <table>
     *   <caption>Row group line numbers in {@code app/cpy-bms/COUSR00.CPY}</caption>
     *   <tr>
     *     <th scope="col">Row</th><th scope="col">selector</th><th scope="col">user identifier</th>
     *     <th scope="col">given name</th><th scope="col">family name</th><th scope="col">user type</th>
     *   </tr>
     *   <tr><td>1</td><td>:72</td><td>:78</td><td>:84</td><td>:90</td><td>:96</td></tr>
     *   <tr><td>2</td><td>:102</td><td>:108</td><td>:114</td><td>:120</td><td>:126</td></tr>
     *   <tr><td>3</td><td>:132</td><td>:138</td><td>:144</td><td>:150</td><td>:156</td></tr>
     *   <tr><td>4</td><td>:162</td><td>:168</td><td>:174</td><td>:180</td><td>:186</td></tr>
     *   <tr><td>5</td><td>:192</td><td>:198</td><td>:204</td><td>:210</td><td>:216</td></tr>
     *   <tr><td>6</td><td>:222</td><td>:228</td><td>:234</td><td>:240</td><td>:246</td></tr>
     *   <tr><td>7</td><td>:252</td><td>:258</td><td>:264</td><td>:270</td><td>:276</td></tr>
     *   <tr><td>8</td><td>:282</td><td>:288</td><td>:294</td><td>:300</td><td>:306</td></tr>
     *   <tr><td>9</td><td>:312</td><td>:318</td><td>:324</td><td>:330</td><td>:336</td></tr>
     *   <tr><td>10</td><td>:342</td><td>:348</td><td>:354</td><td>:360</td><td>:366</td></tr>
     * </table>
     *
     * <p><b>No credential field appears on a row.</b> The quintuple above is the whole of the row group;
     * {@code app/cpy-bms/COUSR00.CPY} declares no password field anywhere, and neither does the sibling
     * delete map {@code app/cpy-bms/COUSR03.CPY}. Only the add and update maps do, at
     * {@code app/cpy-bms/COUSR01.CPY}:78 and {@code app/cpy-bms/COUSR02.CPY}:78. Nothing on this row is
     * derived from the credential column of {@code app/cpy/CSUSR01Y.cpy}, nor from the BCrypt digest
     * column that replaces it in the migrated schema.
     *
     * <p><b>User type is the raw one-character code.</b> {@code UTYPEnnI} is {@code PIC X(1)} and is
     * carried as a {@code String}, never bound to the {@code UserType} enum whose two constants derive
     * from {@code app/cpy/COCOM01Y.cpy}:27-28. A stored value outside that domain must surface as data
     * rather than as a serialisation failure, so the code is carried verbatim.
     *
     * <p><b>Null, blank and marked are three distinct states.</b> Every component may be {@code null},
     * meaning the field was absent from the response; an empty string means the field was present and
     * empty; and a value of spaces or low-values means the legacy screen marked it. No component is
     * trimmed or case-folded on ingest, and no component is coerced between {@code null} and empty. This
     * type is an outbound projection, so it applies no width or domain validation that the read paths
     * would never exercise.
     *
     * <p><b>Error modes.</b> None. No member of this type validates, rejects or throws; every accessor
     * returns exactly the value the row was constructed with.
     *
     * @param selectionFlag the row selection marker the operator may set, {@code SEL000nI PIC X(1)}, at
     *                      the selector line of the row's group in the table above; screen-control state
     *                      carrying no user data, and may be {@code null} when absent
     * @param userId        the eight-character user identifier, {@code USRIDnnI PIC X(8)}, matching the
     *                      {@code USRSEC} cluster key length of eight declared at
     *                      {@code app/jcl/DUSRSECJ.jcl}:65; may be {@code null} when the row is unused
     * @param firstName     the user's given name, {@code FNAMEnnI PIC X(20)}, projected from
     *                      {@code SEC-USR-FNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy}; may be
     *                      {@code null} when the row is unused
     * @param lastName      the user's family name, {@code LNAMEnnI PIC X(20)}, projected from
     *                      {@code SEC-USR-LNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy}; may be
     *                      {@code null} when the row is unused
     * @param userType      the raw one-character user type code, {@code UTYPEnnI PIC X(1)}, projected
     *                      from {@code SEC-USR-TYPE PIC X(01)} of {@code app/cpy/CSUSR01Y.cpy}; the
     *                      seeded data uses {@code A} for an administrator and {@code U} for a standard
     *                      user, but any stored value is carried rather than rejected, and it may be
     *                      {@code null} when the row is unused
     */
    public record UserRow(
            String selectionFlag,
            String userId,
            String firstName,
            String lastName,
            String userType) {

        /**
         * Returns a diagnostic rendering that discloses nothing about the user.
         *
         * <p>Overridden for the same reason as {@link UserSecurityDto#toString()}: the rendering a record
         * generates for itself would publish the user's identifier, given name and family name. Every
         * component of a row is either directly identifying - the identifier and the two names - or
         * screen-control state of no diagnostic value, namely the selector and the one-character type, so
         * no component is emitted at all and only the type name is returned. The row's position within
         * the page, which is the one detail a reader might legitimately want, is a property of the
         * enclosing list rather than of the row.
         *
         * @return the type name only, never any user data
         */
        @Override
        public String toString() {
            return "UserSecurityDto.UserRow";
        }
    }
}
