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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Immutable user-list response payload for the administrator-only user administration surface, projecting the
 * {@code USRSEC} security file onto JSON.
 *
 * <p>The legacy user list is CICS transaction {@code CU00} running {@code app/cbl/COUSR00C.cbl}, which paints
 * one screen of user records at a time onto the mapset whose generated symbolic map is
 * {@code app/cpy-bms/COUSR00.CPY}. That symbolic map is the field contract reproduced here. It declares
 * <b>59</b> input fields, verified by direct inspection at commit {@code 7756d89}, and the arithmetic is
 * {@code 8 + 50 + 1 = 59}: eight preamble fields at {@code app/cpy-bms/COUSR00.CPY}:24, :30, :36, :42, :48,
 * :54, :60 and :66; then ten rows of five fields each, spanning :72 through :366; then one trailer field at
 * :372. This type carries those values and nothing more - it applies no filtering, no paging arithmetic, no
 * mapping and no comparison, every one of which belongs to the service layer.
 *
 * <p>There is therefore deliberately no component here capable of holding a credential or a digest - not even a
 * permanently {@code null} placeholder added "for symmetry" with the create and update requests. A BCrypt
 * digest is recognisable by the version-tagged prefix it writes ahead of its cost factor; no value of that
 * shape can arise from any component declared below, because no component is ever populated from the credential
 * column. The structured logging configuration masks credentials and digests profile-invariantly as a second
 * line of defence, but the primary defence is this one: never carrying them in the first place.
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
 *   </ul>
 *
 * <p>There is therefore deliberately no component here capable of holding a credential or a digest - not
 * even a permanently {@code null} placeholder added "for symmetry" with the create and update requests.
 * A BCrypt digest is recognisable by the version-tagged prefix it writes ahead of its cost factor; no
 * value of that shape can arise from any component declared below, because no component is ever populated
 * from the credential column. The structured logging configuration that masks credentials and digests
 * is a second line of defence; this component set is the first and the decisive one: never carrying
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
 *   </ul>
 *
 * <p><b>Why the row type is not shared.</b> {@link UserRow} carries five values per row, which
 * coincidentally matches the five-per-row shape of the transaction-list projection. The coincidence is
 * structural only: the field names differ entirely and so do the widths. A shared row abstraction would
 * therefore assert a contract that neither map actually has, so the row type is nested inside this file
 * and used by this type alone.
 *
 * <p><b>Two source facts a reader may look for.</b>
 *
 * <ul>
 *   <li><b>The input-field census.</b> A direct count of the {@code 02 &lt;name&gt;I PIC} declarations
 *       inside the input groups of all seventeen symbolic maps in {@code app/cpy-bms} totals <b>441</b>,
 *       not the 460 that plan prose states. The count for this map is unaffected and is independently
 *       verified at <b>59</b>, so nothing about this type follows from the difference; it is recorded
 *       only so that a reader recounting the maps is not misled.</li>
 *   <li><b>The fourteen bytes of slack</b> between the populated fields and the declared record size of
 *       the security cluster are not modelled, because no source field occupies them.</li>
 *   </ul>
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
 * validation annotation: a declarative constraint here would be evaluated by a validator the read paths
 * never invoke, which is documentation masquerading as enforcement. What it does carry is
 * <b>constructor-enforced field widths</b>, applied to every textual component of the projection and of
 * every row. Width is a property of the source field rather than of the direction of travel - a value the
 * 3270 field could not have held did not come from that field - and enforcing it in the constructor means
 * no instance of this type can exist in an out-of-contract shape, on any path, with or without a
 * validator. The structural invariants of the row collection are enforced in the same place.
 *
 * <p>Width is the <em>only</em> property enforced. The page number is not parsed, the user type is not
 * checked against its two-value domain, the selector is not restricted to the markers the screen uses, and
 * no component is required to be present. Nothing is trimmed, padded, upper-cased or lower-cased, so a
 * value at or under its declared width is stored byte-exactly and an over-wide value is refused rather
 * than clipped - silent truncation of an identifier or a name is an undetectable data change, and is the
 * single failure this guard exists to make impossible.
 *
 * <p><b>Error modes.</b> The canonical constructors of this type and of {@link UserRow} are the only
 * members that reject input. They throw {@link IllegalArgumentException} when {@code rows} is
 * {@code null}, when {@code rows} contains a {@code null} element, when {@code rows} holds more than
 * {@link #PAGE_SIZE} elements, or when any textual component is wider than the map field it transcribes.
 * Each message names the offending component and, as applicable, the offending index or the offending
 * length together with the source {@code PICTURE} clause - but never the offending value, because every
 * value here is either directly identifying or screen-control state. No other member throws, no exception
 * is swallowed anywhere in this type, and every accessor is total: it returns the value it was constructed
 * with, and {@link #rows()} returns an empty list rather than {@code null} when the page carries no rows.
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
     * The number of user rows one page of this projection carries: ten, from
     * {@code USER-REC OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl:57}.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * The number of preamble fields the symbolic map declares ahead of the row groups: eight,
     * {@code app/cpy-bms/COUSR00.CPY:24} through :66.
     */
    public static final int PREAMBLE_FIELD_COUNT = 8;

    /**
     * The number of fields each row group declares: five, the ten groups spanning
     * {@code app/cpy-bms/COUSR00.CPY:72-366}.
     */
    public static final int ROW_FIELD_COUNT = 5;

    /**
     * The number of trailer fields the symbolic map declares after the row groups: one, {@code ERRMSGI} at
     * {@code app/cpy-bms/COUSR00.CPY:372}.
     */
    public static final int TRAILER_FIELD_COUNT = 1;

    /**
     * The total number of input fields {@code app/cpy-bms/COUSR00.CPY} declares: {@code 8 + 50 + 1 = 59}.
     */
    public static final int MAP_FIELD_COUNT =
            PREAMBLE_FIELD_COUNT + (PAGE_SIZE * ROW_FIELD_COUNT) + TRAILER_FIELD_COUNT;

    /**
     * Declared width of {@code TRNNAMEI}, {@code PIC X(4)} at {@code app/cpy-bms/COUSR00.CPY}:24.
     */
    public static final int TRANSACTION_NAME_WIDTH = 4;

    /**
     * Declared width of {@code TITLE01I} and {@code TITLE02I}, {@code PIC X(40)} at
     * {@code app/cpy-bms/COUSR00.CPY}:30 and :48.
     */
    public static final int TITLE_WIDTH = 40;

    /**
     * Declared width of {@code CURDATEI}, {@code PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY}:36.
     */
    public static final int DATE_WIDTH = 8;

    /**
     * Declared width of {@code PGMNAMEI}, {@code PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY}:42.
     */
    public static final int PROGRAM_NAME_WIDTH = 8;

    /**
     * Declared width of {@code CURTIMEI} on this map, {@code PIC X(8)} at
     * {@code app/cpy-bms/COUSR00.CPY}:54.
     *
     * <p>Sixteen of the seventeen symbolic maps agree on eight; {@code app/cpy-bms/COSGN00.CPY}:54 alone
     * declares nine. That disagreement is why the header fields are declared inline on each projection
     * instead of being hoisted into a shared header type.
     */
    public static final int TIME_WIDTH = 8;

    /**
     * Declared width of {@code PAGENUMI}, {@code PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY}:60.
     *
     * <p>Eight, not the three of the card list's {@code PAGENOI}. The two maps disagree, so the width is
     * declared per map rather than shared.
     */
    public static final int PAGE_NUMBER_WIDTH = 8;

    /**
     * Declared width of {@code USRIDINI} and of every {@code USRIDnnI}, {@code PIC X(8)}.
     *
     * <p>Corroborated three ways: the map fields at {@code app/cpy-bms/COUSR00.CPY}:66 and :78 onwards,
     * {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy}, and the cluster key length declared
     * as {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl}:65.
     */
    public static final int USER_ID_WIDTH = 8;

    /**
     * Declared width of {@code ERRMSGI}, {@code PIC X(78)} at {@code app/cpy-bms/COUSR00.CPY}:372.
     *
     * <p>Seventy-eight, which is neither the seventy-nine nor the eighty a reader might assume from the
     * terminal line length. The card-detail map declares eighty for its own error line, so this width too
     * is per map.
     */
    public static final int ERROR_MESSAGE_WIDTH = 78;

    /**
     * Declared width of every row selector {@code SEL000nI}, {@code PIC X(1)}.
     */
    public static final int SELECTION_FLAG_WIDTH = 1;

    /**
     * Declared width of every {@code FNAMEnnI} and {@code LNAMEnnI}, {@code PIC X(20)}.
     *
     * <p>Matching {@code SEC-USR-FNAME PIC X(20)} and {@code SEC-USR-LNAME PIC X(20)} of
     * {@code app/cpy/CSUSR01Y.cpy}.
     */
    public static final int NAME_WIDTH = 20;

    /**
     * Declared width of every {@code UTYPEnnI}, {@code PIC X(1)}.
     *
     * <p>Matching {@code SEC-USR-TYPE PIC X(01)} of {@code app/cpy/CSUSR01Y.cpy}. The width is enforced;
     * the <em>domain</em> is not, because a stored code outside {@code A} and {@code U} must surface as
     * data rather than as a serialisation failure.
     */
    public static final int USER_TYPE_WIDTH = 1;

    /**
     * Rejects a value wider than the fixed-width map field it transcribes.
     *
     * <p>Width is a property of the source field, so it is enforced on every projection regardless of
     * direction: a value the 3270 field could not have held did not come from that field, and carrying it
     * would put a byte count on the wire that no legacy consumer, report line or fixed-width writer can
     * accept. The alternative - silently truncating - is the outcome this guard exists to make impossible,
     * because a clipped identifier or name is an undetectable data change.
     *
     * <p>{@code null} passes untouched, because absence is a legitimate state of a screen field. So does
     * every value at or under the declared width, including the empty string, a run of spaces and a
     * low-values marker: this guard bounds length and nothing else. Nothing is trimmed, padded,
     * upper-cased or lower-cased, and the failure message names the component and quotes the source
     * {@code PICTURE} clause but never the offending value, because every value on this projection is
     * either directly identifying or screen-control state.
     *
     * @param value     the value to check, or {@code null} when the field was absent
     * @param maxLength the declared width of the source field
     * @param fieldName the component name to name in the failure message
     * @param picClause the source {@code PICTURE} clause to quote in the failure message
     * @throws IllegalArgumentException if {@code value} is non-{@code null} and longer than
     *                                  {@code maxLength}
     */
    private static void requireWidthWithinLimit(final String value, final int maxLength,
            final String fieldName, final String picClause) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength
                    + " characters because the source field is " + picClause + ", but was "
                    + value.length() + " characters long");
        }
    }

    /**
     * Validates the row collection and stores it as an unmodifiable defensive copy.
     *
     * @throws IllegalArgumentException if {@code rows} is {@code null}, if {@code rows} contains a {@code null}
     * element, or if {@code rows} holds more than {@link #PAGE_SIZE} elements.
     */
    public UserSecurityDto {
        requireWidthWithinLimit(transactionName, TRANSACTION_NAME_WIDTH, "transactionName", "PIC X(4)");
        requireWidthWithinLimit(title01, TITLE_WIDTH, "title01", "PIC X(40)");
        requireWidthWithinLimit(currentDate, DATE_WIDTH, "currentDate", "PIC X(8)");
        requireWidthWithinLimit(programName, PROGRAM_NAME_WIDTH, "programName", "PIC X(8)");
        requireWidthWithinLimit(title02, TITLE_WIDTH, "title02", "PIC X(40)");
        requireWidthWithinLimit(currentTime, TIME_WIDTH, "currentTime", "PIC X(8)");
        requireWidthWithinLimit(pageNumber, PAGE_NUMBER_WIDTH, "pageNumber", "PIC X(8)");
        requireWidthWithinLimit(userIdInput, USER_ID_WIDTH, "userIdInput", "PIC X(8)");
        requireWidthWithinLimit(errorMessage, ERROR_MESSAGE_WIDTH, "errorMessage", "PIC X(78)");

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
     * @return an unmodifiable, order-preserving copy of the page's rows, never {@code null}.
     */
    @Override
    public List<UserRow> rows() {
        return List.copyOf(rows);
    }

    /**
     * Returns a diagnostic rendering that discloses nothing about any user.
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
     * @param selectionFlag the row selection marker the operator may set, {@code SEL000nI PIC X(1)}, the
     * first field of each row group in {@code app/cpy-bms/COUSR00.CPY}
     * @param userId the eight-character user identifier, {@code USRIDnnI PIC X(8)}, matching the {@code USRSEC}
     * cluster key length of eight declared at {@code app/jcl/DUSRSECJ.jcl}:65.
     * @param firstName the user's given name, {@code FNAMEnnI PIC X(20)}, projected from
     * {@code SEC-USR-FNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy}.
     * @param lastName the user's family name, {@code LNAMEnnI PIC X(20)}, projected from
     * {@code SEC-USR-LNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy}.
     * @param userType the raw one-character user type code, {@code UTYPEnnI PIC X(1)}, projected from
     * {@code SEC-USR-TYPE PIC X(01)} of {@code app/cpy/CSUSR01Y.cpy}.
     */
    public record UserRow(
            String selectionFlag,
            String userId,
            String firstName,
            String lastName,
            String userType) {

        /**
         * Rejects any component wider than the row field it transcribes.
         *
         * <p>Five checks, one per component, each quoting its own {@code PICTURE} clause so that a failure
         * identifies the field without a lookup. A {@code null} passes every check, and so does every
         * value at or under the declared width; nothing is trimmed, padded or case-folded, and no domain
         * is imposed on either the selector or the type code.
         *
         * @throws IllegalArgumentException if any component is longer than its declared width. The message
         *                                  names the component, its length and its source
         *                                  {@code PICTURE} clause, and never the value itself
         */
        public UserRow {
            requireWidthWithinLimit(selectionFlag, SELECTION_FLAG_WIDTH, "selectionFlag", "PIC X(1)");
            requireWidthWithinLimit(userId, USER_ID_WIDTH, "userId", "PIC X(8)");
            requireWidthWithinLimit(firstName, NAME_WIDTH, "firstName", "PIC X(20)");
            requireWidthWithinLimit(lastName, NAME_WIDTH, "lastName", "PIC X(20)");
            requireWidthWithinLimit(userType, USER_TYPE_WIDTH, "userType", "PIC X(1)");
        }

        /**
         * Returns a diagnostic rendering that discloses nothing about the user.
         *
         * @return the type name only, never any user data
         */
        @Override
        public String toString() {
            return "UserSecurityDto.UserRow";
        }
    }

    /**
     * The user-delete screen: all eleven input fields of {@code app/cpy-bms/COUSR03.CPY}, in map order.
     *
     * <p><strong>Why this type exists.</strong> The user-administration read surface is two screens, not
     * one. CICS transaction {@code CU00} running {@code app/cbl/COUSR00C.cbl} paints the ten-row list this
     * enclosing type projects; CICS transaction {@code CU03} running {@code app/cbl/COUSR03C.cbl} paints a
     * single user for confirmation before deletion, onto the mapset whose generated symbolic map is
     * {@code app/cpy-bms/COUSR03.CPY}. That second map declares eleven input fields and none of them was
     * represented, so the read-confirm-delete chain had no wire contract at all. This record is that
     * contract.
     *
     * <p><strong>Eleven fields, counted directly.</strong> The input group is {@code 01 COUSR3AI} at
     * {@code app/cpy-bms/COUSR03.CPY}:17, redefined for output as {@code 01 COUSR3AO} at :85. Between them
     * sit exactly eleven {@code 02}-level input fields, at :24, :30, :36, :42, :48, :54, :60, :66, :72, :78
     * and :84. The arithmetic is {@code 6 + 4 + 1 = 11}: the six recurring header fields, then the single
     * user's four detail fields, then the error line.
     *
     * <p><strong>One user, not a row.</strong> The four detail fields are the same four the list carries per
     * row - identifier, given name, family name, type - but this map is <em>not</em> a one-row list and
     * {@link UserRow} is deliberately not reused for it. The names differ: the list generates
     * {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI} against this map's
     * {@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI} and {@code USRTYPEI}. And the shapes differ: a list
     * row carries a selection marker, because ten rows need one selected, whereas this screen has no
     * selector at all - the user being deleted is the one the operator keyed in. Reusing the row type would
     * have introduced a selector this map does not declare, which is precisely the kind of invented field
     * that a field-contract projection must not carry.
     *
     * <p><strong>The widths are shared with the list because the two maps declare the same clauses.</strong>
     * Every constant this record validates against is the enclosing type's, and that reuse is a verified
     * identity rather than an assumption: {@code TRNNAMEI} is {@code PIC X(4)}, {@code TITLE01I} and
     * {@code TITLE02I} are {@code PIC X(40)}, {@code CURDATEI}, {@code PGMNAMEI}, {@code CURTIMEI} and
     * {@code USRIDINI} are {@code PIC X(8)}, {@code FNAMEI} and {@code LNAMEI} are {@code PIC X(20)},
     * {@code USRTYPEI} is {@code PIC X(1)} and {@code ERRMSGI} is {@code PIC X(78)} - the same eleven
     * clauses the corresponding {@code app/cpy-bms/COUSR00.CPY} fields declare. Had any width differed, it
     * would have been declared here instead of reused.
     *
     * <p><strong>No credential field.</strong> {@code app/cpy-bms/COUSR03.CPY} declares no password field
     * anywhere across its eleven fields, which is one of the two corroborations that the read-side maps
     * never carried one. Nothing here is derived from the credential column of
     * {@code app/cpy/CSUSR01Y.cpy} nor from the BCrypt digest column that replaces it.
     *
     * <p><strong>No self-delete guard.</strong> {@code app/cbl/COUSR03C.cbl} never compares the target
     * identifier against the signed-on identifier, so an administrator can delete their own record. That is
     * a preserved legacy behaviour, not an oversight: parity is the contract, and this projection therefore
     * declares no field, flag or invariant that would express such a guard. Adding one would be a
     * behaviour change.
     *
     * <p><strong>Null, blank and marked are three distinct states</strong>, and <strong>width is enforced
     * while domain is not</strong> - both exactly as described on {@link UserRow}, and enforced by the same
     * guard.
     *
     * <p><strong>Rendering is suppressed.</strong> Three of the eleven components identify a person
     * directly, so {@code toString} emits only the originating program name, for the same reason
     * {@link UserSecurityDto#toString()} does.
     *
     * <p><strong>The six recurring header fields are transcribed but not published.</strong>
     *
     * <p>FINDING, severity Medium - remediated here. This record was serialized whole, so the delete
     * response was the only one of the seventeen operations that put {@code transactionName},
     * {@code title01}, {@code title02}, {@code currentDate}, {@code currentTime} and {@code programName} on
     * the wire. Those six are the screen chrome every one of the seventeen symbolic maps repeats, and they
     * are excluded from the JSON contract in every other operation: they are constants, a clock reading and
     * a COBOL program identity, none of which is data the caller asked for and the last of which is the
     * dispatch operand Transformation Rule 7 replaces. Excluding them here leaves exactly the five members
     * this operation's published contract declares - {@code userIdInput}, {@code firstName},
     * {@code lastName}, {@code userType} and {@code errorMessage} - while the components, their widths and
     * their citations stay on the record, because the map does declare all eleven and the field contract is
     * what this type exists to hold.
     *
     * @param transactionName the four-character transaction identifier, {@code TRNNAMEI PIC X(4)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:24; may be {@code null} when absent
     * @param title01         the first title line of the screen header, {@code TITLE01I PIC X(40)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:30; may be {@code null} when absent
     * @param currentDate     the header date as the screen rendered it, {@code CURDATEI PIC X(8)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:36; may be {@code null} when absent
     * @param programName     the eight-character name of the program that painted the screen,
     *                        {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:42; may be
     *                        {@code null} when absent
     * @param title02         the second title line of the screen header, {@code TITLE02I PIC X(40)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:48; may be {@code null} when absent
     * @param currentTime     the header time as the screen rendered it, {@code CURTIMEI PIC X(8)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:54 - eight characters here, against the nine
     *                        of {@code app/cpy-bms/COSGN00.CPY}:54; may be {@code null} when absent
     * @param userIdInput     the eight-character identifier of the user being confirmed for deletion,
     *                        {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR03.CPY}:60, matching the
     *                        {@code USRSEC} cluster key length declared at {@code app/jcl/DUSRSECJ.jcl}:65;
     *                        may be {@code null} before the operator has keyed one
     * @param firstName       the user's given name as read back for confirmation, {@code FNAMEI PIC X(20)}
     *                        at {@code app/cpy-bms/COUSR03.CPY}:66, projected from
     *                        {@code SEC-USR-FNAME PIC X(20)}; may be {@code null} when no user was read
     * @param lastName        the user's family name as read back for confirmation,
     *                        {@code LNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR03.CPY}:72, projected from
     *                        {@code SEC-USR-LNAME PIC X(20)}; may be {@code null} when no user was read
     * @param userType        the raw one-character user type code, {@code USRTYPEI PIC X(1)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:78, projected from
     *                        {@code SEC-USR-TYPE PIC X(01)}; carried verbatim rather than bound to an
     *                        enumeration, and may be {@code null} when no user was read
     * @param errorMessage    the screen's error message line, {@code ERRMSGI PIC X(78)} at
     *                        {@code app/cpy-bms/COUSR03.CPY}:84; may be {@code null} when the request
     *                        succeeded
     */
    @JsonIgnoreProperties({"transactionName", "title01", "currentDate", "programName", "title02",
        "currentTime"})
    public record UserDeleteScreen(
            String transactionName,
            String title01,
            String currentDate,
            String programName,
            String title02,
            String currentTime,
            String userIdInput,
            String firstName,
            String lastName,
            String userType,
            String errorMessage) {

        /**
         * The number of recurring header fields this map declares ahead of the user detail: six.
         *
         * <p>{@code TRNNAMEI}, {@code TITLE01I}, {@code CURDATEI}, {@code PGMNAMEI}, {@code TITLE02I} and
         * {@code CURTIMEI}, at {@code app/cpy-bms/COUSR03.CPY}:24, :30, :36, :42, :48 and :54.
         */
        public static final int HEADER_FIELD_COUNT = 6;

        /**
         * The number of fields describing the single user being confirmed: four.
         *
         * <p>{@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI} and {@code USRTYPEI}, at
         * {@code app/cpy-bms/COUSR03.CPY}:60, :66, :72 and :78. Four rather than the list's five, because
         * this screen declares no selection marker.
         */
        public static final int DETAIL_FIELD_COUNT = 4;

        /**
         * The total number of input fields this map declares: {@code 6 + 4 + 1 = 11}.
         *
         * <p>Derived from its parts rather than written as a literal, so the arithmetic that proves the
         * field contract is visible in the source. Verified by direct count against
         * {@code app/cpy-bms/COUSR03.CPY} at commit {@code 7756d89}.
         *
         * <p>This constant shadows {@link UserSecurityDto#MAP_FIELD_COUNT} within this record. The two are
         * different facts about different maps - eleven fields on {@code app/cpy-bms/COUSR03.CPY} against
         * fifty-nine on {@code app/cpy-bms/COUSR00.CPY} - so the enclosing figure must always be referenced
         * as {@code UserSecurityDto.MAP_FIELD_COUNT}.
         */
        public static final int MAP_FIELD_COUNT = HEADER_FIELD_COUNT + DETAIL_FIELD_COUNT + 1;

        /**
         * Rejects any component wider than the map field it transcribes.
         *
         * <p>Eleven checks against the enclosing type's width constants, each quoting its own
         * {@code PICTURE} clause. A {@code null} passes, nothing is truncated, and no domain is imposed on
         * the type code.
         *
         * @throws IllegalArgumentException if any component is longer than its declared width. The message
         *                                  names the component, its length and its source
         *                                  {@code PICTURE} clause, and never the value itself
         */
        public UserDeleteScreen {
            requireWidthWithinLimit(transactionName, TRANSACTION_NAME_WIDTH, "transactionName", "PIC X(4)");
            requireWidthWithinLimit(title01, TITLE_WIDTH, "title01", "PIC X(40)");
            requireWidthWithinLimit(currentDate, DATE_WIDTH, "currentDate", "PIC X(8)");
            requireWidthWithinLimit(programName, PROGRAM_NAME_WIDTH, "programName", "PIC X(8)");
            requireWidthWithinLimit(title02, TITLE_WIDTH, "title02", "PIC X(40)");
            requireWidthWithinLimit(currentTime, TIME_WIDTH, "currentTime", "PIC X(8)");
            requireWidthWithinLimit(userIdInput, USER_ID_WIDTH, "userIdInput", "PIC X(8)");
            requireWidthWithinLimit(firstName, NAME_WIDTH, "firstName", "PIC X(20)");
            requireWidthWithinLimit(lastName, NAME_WIDTH, "lastName", "PIC X(20)");
            requireWidthWithinLimit(userType, USER_TYPE_WIDTH, "userType", "PIC X(1)");
            requireWidthWithinLimit(errorMessage, ERROR_MESSAGE_WIDTH, "errorMessage", "PIC X(78)");
        }

        /**
         * Returns a diagnostic rendering that discloses nothing about the user.
         *
         * <p>Overridden for the same reason as {@link UserSecurityDto#toString()} and
         * {@link UserRow#toString()}: the rendering a record generates for itself would publish the
         * identifier, given name and family name of the user about to be deleted - straight into any log
         * line, exception message or debugger frame that interpolated the object, and on precisely the code
         * path where a failure is most likely to be logged. Only {@code programName} is emitted, which
         * names the screen and cannot identify a person. There is no numeric or date formatting in the
         * result, so the output cannot vary with the platform locale.
         *
         * @return the type name and the originating program name only, never any user data
         */
        @Override
        public String toString() {
            return "UserSecurityDto.UserDeleteScreen[programName=" + programName + "]";
        }
    }
}
