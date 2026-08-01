/*
 * ******************************************************************
 * Program     : UserSecurityDtoTest.java
 * Application : CardDemo
 * Type        : Java 25 / JUnit 5 unit test (pure JVM tier)
 * Function    : Verifies the user-list projection carries all 59 map fields, ten rows per page,
 *               and no credential of any kind.
 * Source      : app/cpy-bms/COUSR00.CPY (59 input fields, group COUSR0AI) + app/cbl/COUSR00C.cbl
 *               + app/cpy/CSUSR01Y.cpy @ 7756d89
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
package com.cardemo.unit.model;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.dto.UserSecurityDto.UserDeleteScreen;
import com.cardemo.model.dto.UserSecurityDto.UserRow;
import com.cardemo.model.enums.UserType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link UserSecurityDto}, the outbound projection of the CICS {@code CU00} user-list
 * screen.
 *
 * <h2>What this test does</h2>
 *
 * <p>It holds {@link UserSecurityDto} to the field contract of the generated symbolic map
 * {@code app/cpy-bms/COUSR00.CPY}, input group {@code COUSR0AI}, and to the screen behaviour of the
 * program that drives it, {@code app/cbl/COUSR00C.cbl}. Everything asserted here was verified by direct
 * inspection of the frozen corpus at commit {@code 7756d89}; nothing is inferred by analogy from a
 * sibling map, because the three list maps disagree with one another in ways set out below.
 *
 * <p><b>The 59-field census.</b> The input group opens at {@code app/cpy-bms/COUSR00.CPY}:17 with a
 * twelve-byte terminal I/O area {@code FILLER} at :18 and closes at :372, immediately before the output
 * redefinition {@code COUSR0AO} at :373. Within it every screen field is generated as a quintuple - a
 * {@code COMP PIC S9(4)} length field, an attribute byte, a redefined attribute alias, four reserved
 * bytes, then the data field - and it is the <em>data field</em> of each quintuple that this projection
 * carries. A count of the data fields in that range yields exactly <b>59</b>, decomposing without
 * remainder as {@code 8 + (10 x 5) + 1}:
 *
 * <ul>
 *   <li>Six recurring header fields: {@code TRNNAMEI PIC X(4)} at :24, {@code TITLE01I PIC X(40)} at
 *       :30, {@code CURDATEI PIC X(8)} at :36, {@code PGMNAMEI PIC X(8)} at :42,
 *       {@code TITLE02I PIC X(40)} at :48 and {@code CURTIMEI PIC X(8)} at :54.</li>
 *   <li>Two preamble fields: {@code PAGENUMI PIC X(8)} at :60 and {@code USRIDINI PIC X(8)} at :66.</li>
 *   <li>Ten uniform row groups of five fields each - {@code SEL000nI PIC X(1)},
 *       {@code USRIDnnI PIC X(8)}, {@code FNAMEnnI PIC X(20)}, {@code LNAMEnnI PIC X(20)} and
 *       {@code UTYPEnnI PIC X(1)} - spanning :72 through :366. Row one is at :72, :78, :84, :90, :96 and
 *       each later row follows at a thirty-line stride, so row ten is at :342, :348, :354, :360 and
 *       :366.</li>
 *   <li>One trailer field: {@code ERRMSGI PIC X(78)} at :372, the last input field. Its width is
 *       <b>78</b>, not 79 and not 80.</li>
 * </ul>
 *
 * <p>Two details of that census are asserted precisely because they are the ones a reasonable reader
 * would get wrong. First, <b>the ten row groups are uniform</b>: every one declares all five fields.
 * That is not true of the card list, where {@code app/cpy-bms/COCRDLI.CPY} omits the row-one status
 * field, so its row one carries four fields against five for rows two through seven. A non-uniform-array
 * assumption carried across from that map would be wrong here. Second, <b>no data field in this map uses
 * the {@code PICTURE} spelling</b>; the attribute bytes and reserved fillers do, the data fields never
 * do, so a census keyed on {@code PIC} alone is complete.
 *
 * <h2>Page size, and the paging divergences this test refuses to unify</h2>
 *
 * <p><b>Ten rows per page.</b> {@code app/cbl/COUSR00C.cbl}:57 declares
 * {@code 02 USER-REC OCCURS 10 TIMES.}, and the map's ten row groups corroborate it. Ten is asserted as
 * a literal against {@link UserSecurityDto#PAGE_SIZE}. The corpus holds three different page sizes and
 * none of them may be unified: the card list paints <b>seven</b> rows
 * ({@code app/cbl/COCRDLIC.cbl}:177-178 declares
 * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} beside
 * {@code 05 LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'.}), the transaction list paints <b>ten</b>
 * ({@code app/cbl/COTRN00C.cbl}:63-68) and the user list paints <b>ten</b>. The batch transaction
 * report's twenty lines per page is a printed-page concern of a batch program and has no business
 * reaching any data transfer object at all.
 *
 * <p><b>The next-page sentinel, determined rather than assumed.</b> The corpus carries two mutually
 * incompatible encodings of "is there another page":
 *
 * <ul>
 *   <li>The <em>{@code 'N'} family</em> - {@code app/cbl/COTRN00C.cbl}:63-68 declares
 *       {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'} with
 *       {@code 88 NEXT-PAGE-YES VALUE 'Y'} and {@code 88 NEXT-PAGE-NO VALUE 'N'}.</li>
 *   <li>The <em>{@code LOW-VALUES} family</em> - {@code app/cbl/COCRDLIC.cbl}:239-244 declares
 *       {@code 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.} against
 *       {@code 88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.}, alongside a counter-intuitive companion flag where
 *       {@code 88 CA-LAST-PAGE-SHOWN VALUE 0.} and {@code 88 CA-LAST-PAGE-NOT-SHOWN VALUE 9.} - zero
 *       meaning shown and nine meaning not shown.</li>
 * </ul>
 *
 * <p><b>Finding, established by reading the driving program rather than reasoning by analogy:</b> the
 * user list belongs to the {@code 'N'} family. {@code app/cbl/COUSR00C.cbl}:71 declares
 * {@code 10 CDEMO-CU00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'.} with {@code 88 NEXT-PAGE-YES VALUE 'Y'.} at
 * :72 and {@code 88 NEXT-PAGE-NO VALUE 'N'.} at :73, and the program sets those condition names at
 * :313, :315 and :318. Binary zeros never appear as a next-page value anywhere in that program. The
 * projection itself resolves the risk of conflation structurally: it declares <b>no next-page component
 * at all</b>, so neither encoding can leak into it, and the typed paging contract lives in
 * {@code PageResponse} where the three list transactions are reconciled once. This test asserts that
 * absence rather than restating the paging contract, which belongs to {@code PageResponseTest}.
 *
 * <p><b>Pagination is not COMMAREA state.</b> {@code app/cpy/COCOM01Y.cpy} is 47 lines and declares no
 * page-number field and no next-page flag - a case-insensitive search of it for "page" or "next" returns
 * nothing. Paging state lives in per-program working storage, {@code WS-PAGE-NUM} at
 * {@code app/cbl/COUSR00C.cbl}:54, and in the program-specific COMMAREA extension {@code CDEMO-CU00-INFO}
 * declared beneath the {@code COPY} at :66. Any claim that a paging type derives from
 * {@code COCOM01Y} is therefore unsupported by the source; the provenance cited here is working storage.
 *
 * <p><b>No server-side session state.</b> Stateless request handling replaces the pseudo-conversational
 * COMMAREA, so this test asserts the projection carries none of {@code CDEMO-FROM-TRANID PIC X(04)},
 * {@code CDEMO-FROM-PROGRAM PIC X(08)}, {@code CDEMO-TO-TRANID PIC X(04)},
 * {@code CDEMO-TO-PROGRAM PIC X(08)} ({@code app/cpy/COCOM01Y.cpy}:21-24),
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} (:29) or {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET}
 * (:43-44, both {@code PIC X(7)} - seven characters, not eight).
 *
 * <h2>Security: no credential is carried, and none can be</h2>
 *
 * <p>The project's security standard forbids secrets in code, logs, <em>tests</em> and configuration -
 * the clause names tests explicitly, which makes it the strictest constraint reaching this file. The
 * assertions here treat the absence of a credential as a structural property to be proved, not a
 * convention to be trusted:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR00.CPY} declares no password field anywhere across its 59 fields, so the
 *       read projection has none either - not as a component, not as an accessor, and not as a
 *       permanently {@code null} placeholder added for symmetry.</li>
 *   <li>A plaintext password does cross the wire on the sibling write screens, {@code PASSWDI PIC X(8)}
 *       at {@code app/cpy-bms/COUSR01.CPY}:78 and at {@code app/cpy-bms/COUSR02.CPY}:78. Those are
 *       represented by {@code UserCreateRequest} and {@code UserUpdateRequest} and are covered by their
 *       own tests. Adding such a field here "for symmetry" is the single most likely way to break this
 *       type, so it is asserted against directly.</li>
 *   <li>The persisted record is the 80-byte layout at {@code app/cpy/CSUSR01Y.cpy}:17-23 -
 *       {@code SEC-USR-ID PIC X(08)}, {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)},
 *       {@code SEC-USR-PWD PIC X(08)} at :21, {@code SEC-USR-TYPE PIC X(01)} and a named
 *       {@code SEC-USR-FILLER PIC X(23)} at :23, which is {@code 8 + 20 + 20 + 8 + 1 = 57} populated
 *       bytes in an 80-byte record over an eight-byte key. In the migrated schema that eight-byte
 *       credential column becomes a 60-character BCrypt digest column at strength 10; an eight-character
 *       column would truncate every digest. Neither the credential nor its digest is projected here, and
 *       this test proves no component of a fully populated instance can hold a digest-shaped value.</li>
 *   <li>The ten seeded users exist only as inline {@code SYSUT1 DD *} card images at
 *       {@code app/jcl/DUSRSECJ.jcl}:35-44, fed through {@code IEBGENER} into the cluster defined at
 *       :65-66 with {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)}. Five carry user type {@code A} and
 *       five carry {@code U}. Their identifiers and names are used here as realistic fixtures because
 *       they are not secrets. <b>The one plaintext credential all ten share appears nowhere in this
 *       file</b> - not in a value, not in a comment, not in an assertion message - and no BCrypt digest
 *       literal appears either; digest shape is detected by pattern, never by example.</li>
 * </ul>
 *
 * <p>Names are not treated as secrets, but they are identifying, so this test also proves that the
 * diagnostic renderings of both the page and its rows disclose neither a name nor an identifier.
 *
 * <h2>The row user type is a raw code, and why</h2>
 *
 * <p>{@code UTYPEnnI} is {@code PIC X(1)} and the domain is exactly two values, fixed by the
 * {@code 88}-level condition names at {@code app/cpy/COCOM01Y.cpy}:26-28 -
 * {@code CDEMO-USER-TYPE PIC X(01)} with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} and
 * {@code 88 CDEMO-USRTYP-USER VALUE 'U'.}
 *
 * <p><b>Decision, asserted explicitly below: the projection's row type property is a raw
 * {@link String}, not {@link UserType}.</b> The rationale is parity, and it is specific to the direction
 * of this type. This is a read projection over stored data, so whatever is stored must be carried and
 * surfaced as data. Binding the column to the enum would convert an out-of-domain stored byte into a
 * serialisation failure - the row would fail to render at all rather than rendering a value an operator
 * could see and correct - which is a different behaviour from the legacy screen, that paints whatever
 * byte the record holds. The enum remains the authority on the domain, and this test uses it as such:
 * it resolves the raw codes through {@link UserType#fromCode(String)} to prove the projection's domain
 * and the copybook's domain are the same set, and it exercises
 * {@link UserType#requireFromCode(char)} to show that an unrecognised code is named rather than silently
 * defaulted. What it does not do is let the enum reject stored data on the read path.
 *
 * <h2>Three distinct states, never collapsed</h2>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is a {@code COPY ... REPLACING} procedure-division template - it is
 * procedural, not a data layout, so it has no class of its own - parameterised on
 * {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)}, and it models exactly three field
 * states: OK, NOT-OK and BLANK. Its markers fire only on re-entry, gated by
 * {@code CDEMO-PGM-REENTER}. The driving program keeps the same three-way distinction: the row selection
 * predicate at {@code app/cbl/COUSR00C.cbl}:152-179 reads
 * {@code WHEN SEL000nI OF COUSR0AI NOT = SPACES AND LOW-VALUES} for all ten rows, and the filter test at
 * :218 reads {@code IF USRIDINI OF COUSR0AI = SPACES OR LOW-VALUES}. Spaces and binary zeros are
 * therefore both "not supplied", and they are distinct from a genuine value. In the projection that
 * becomes four states which this test keeps apart: {@code null} for absent, {@code ""} for present and
 * empty, a blank string for the spaces a fixed-width field carries, and a low-values string for binary
 * zeros. Collapsing any of them into another is the failure this test exists to prevent.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Compile and run from the repository root with the pinned wrapper:
 * {@code ./mvnw -B clean test} for this tier, {@code ./mvnw -B clean verify} for the coverage and
 * dependency gates. This class is collected by <b>Surefire 3.5.4</b>, whose configured includes are
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} with {@code **}{@code /integration/**}
 * and {@code **}{@code /e2e/**} excluded; it therefore runs under Surefire and never under Failsafe.
 * Both the class name suffix and the location under {@code src/test/java/com/cardemo/unit} are load
 * bearing: a test class outside that tree matching neither include is collected by neither plugin and
 * silently never runs, producing a green build with no error and no warning. Evidence that it did run is
 * {@code target/surefire-reports/com.cardemo.unit.model.UserSecurityDtoTest.txt}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This is the pure-JVM tier: no container, no Spring context, no database, no network. The only
 * configuration is determinism.
 *
 * <ul>
 *   <li><b>Injected fixed clock.</b> Every temporal value comes from {@link FixedClockProvider} in this
 *       package, never from {@code Instant.now()}, {@code LocalDate.now()} or
 *       {@code System.currentTimeMillis()}. {@link FixedClockProvider#canonicalClock()} is fixed at
 *       {@link FixedClockProvider#CANONICAL_INSTANT} in {@link FixedClockProvider#CANONICAL_ZONE}. This
 *       matters because the legacy header is genuinely wall-clock derived:
 *       {@code app/cbl/COUSR00C.cbl}:564 executes
 *       {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} and :571-575 and :577-581 then render
 *       {@code CURDATEO} and {@code CURTIMEO} through the group layouts at {@code app/cpy/CSDAT01Y.cpy}
 *       :30-35 and :36-41, which are {@code MM/DD/YY} and {@code HH:MM:SS} - eight characters each,
 *       matching {@code CURDATEI} and {@code CURTIMEI}.</li>
 *   <li><b>Default locale and zone are never read.</b> Every format and case operation passes
 *       {@link Locale#ROOT} explicitly, and the zone always comes from the injected clock.</li>
 *   <li><b>Mockito strictness: not applicable, and deliberately so.</b> No mock, spy or stub is created
 *       anywhere in this class. {@link UserSecurityDto} is an immutable record with no collaborator to
 *       stand in for, so there is nothing to mock; had a collaborator existed, the pinned Mockito 5.17.0
 *       would be driven through its JUnit 5 extension whose default is strict stubs. Importing Mockito
 *       here to no purpose would fail the build outright, as explained next.</li>
 *   <li><b>No global mutable state.</b> Every fixture is produced by a pure static factory returning
 *       immutable values; there is no mutable static field, no shared instance state between tests and
 *       no ordering dependency, so the class is order-independent by construction.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><b>The build fails on a raw type or a deprecation.</b> The compiler runs with
 *       {@code -Xlint:all -Werror} at {@code release 25} and that configuration reaches test
 *       compilation, so a single raw type or deprecation warning is a hard build failure, not a warning.
 *       Fix the expression rather than suppressing the warning. An <em>unused</em> import is a separate
 *       matter: {@code javac} 25.0.3 publishes no lint key for one, so it will not fail the build and is
 *       caught at review - remove it there.</li>
 *   <li><b>A password or hash component is added for symmetry with the add and update maps.</b> This is
 *       the highest-severity regression available here. The write maps have {@code PASSWDI}; this read
 *       map does not, and the assertions in the security group fail loudly if one appears.</li>
 *   <li><b>The card list's page size of seven is reused.</b> Seven belongs to
 *       {@code app/cbl/COCRDLIC.cbl}:177-178. Ten belongs here, by
 *       {@code app/cbl/COUSR00C.cbl}:57.</li>
 *   <li><b>The {@code 'N'} and {@code LOW-VALUES} sentinels are conflated.</b> They are mutually
 *       incompatible encodings from different programs. This map follows {@code 'N'}
 *       ({@code app/cbl/COUSR00C.cbl}:71-73) and the projection declares no sentinel component, which is
 *       what makes conflation impossible rather than merely discouraged.</li>
 *   <li><b>{@code COCOM01Y} is cited as the origin of pagination.</b> It has no page or next-page field.
 *       Cite {@code app/cbl/COUSR00C.cbl}:54 working storage instead.</li>
 *   <li><b>A self-delete guard is invented.</b> See the note below; the guard does not exist in the
 *       source and must not be introduced.</li>
 * </ol>
 *
 * <h2>Preserved legacy quirk</h2>
 *
 * <p>{@code app/cbl/COUSR03C.cbl}, the 359-line user-delete program, contains <b>no self-delete
 * guard</b>: it never compares the target user identifier against the signed-on identifier, so an
 * administrator can delete their own record. That absent guard is preserved deliberately under the
 * parity mandate and is recorded in {@code DECISION_LOG.md}. This test therefore asserts that a row
 * whose identifier equals the signed-on identifier is carried without objection, and it asserts no
 * self-delete restriction of any kind, because inventing one would be a behaviour change.
 *
 * <h2>Findings, by severity</h2>
 *
 * <ul>
 *   <li><b>Blocker</b> - a password or hash component on this projection; the seeded plaintext
 *       credential appearing anywhere under {@code src/}; the absent, empty, blank and low-values states
 *       collapsed into one. <em>Remediation:</em> delete the component and let
 *       {@code UserCreateRequest} or {@code UserUpdateRequest} carry the credential; remove the literal
 *       and assert digest shape by pattern; carry every value exactly as supplied without coercion.</li>
 *   <li><b>High</b> - a page size other than ten; the two next-page encodings conflated; a shared
 *       header or paging abstraction; an invented self-delete guard; a session-state component; a
 *       class-level cross-field constraint. <em>Remediation:</em> take each figure from the program that
 *       owns it, keep the header fields declared inline, and gate any cross-field rule the way
 *       {@code app/cbl/COACTUPC.cbl}:1665-1669 gates its own.</li>
 *   <li><b>Medium, closed</b> - prior-generation plan prose stated 460 input fields across the seventeen
 *       symbolic maps while a direct count totals <b>441</b>, and its own per-map table summed to 440.
 *       Separately, that prose attributed paging metadata to {@code app/cpy/COCOM01Y.cpy}, which declares
 *       no such field. This map's own count is unaffected and independently verified at <b>59</b>.
 *       <em>Remediation, applied:</em> {@code docs/technical-specifications.md} now publishes 441 and
 *       re-attributes the paging fields to the program WORKING-STORAGE and COMMAREA extensions that
 *       actually declare them, verified on 1 August 2026; no code change follows.</li>
 *   <li><b>Low</b> - the {@code USRSEC} record carries 57 populated bytes in an 80-byte slot; the
 *       23-byte named filler at {@code app/cpy/CSUSR01Y.cpy}:23 is not modelled, because no screen field
 *       occupies it. <em>Remediation:</em> none required.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <ul>
 *   <li><b>A user seed fixture.</b> There is no {@code usrsec.txt} under {@code app/data/ASCII}, which
 *       holds nine fixtures and none for users; the ten rows exist only inside
 *       {@code app/jcl/DUSRSECJ.jcl}:35-44. The EBCDIC {@code .PS} member of the same name is codepage
 *       reference only and is never parsed by the build. Consequently no fixture loader is used by this
 *       test and none is invented. <em>What would be needed:</em> an ASCII fixture in the
 *       {@code CSUSR01Y} 80-byte layout, with credentials already replaced by digests.</li>
 *   <li><b>Planned children of the schema migration.</b> No child artefact of
 *       {@code V1__create_schema.sql} is planned, so no column-level schema assertion is made here.
 *       <em>What would be needed:</em> the generated DDL, which is a database-tier concern and outside
 *       this pure-JVM tier in any case.</li>
 *   <li><b>The production owner of the row-selection rule.</b> {@code isSelected} classifies a row
 *       marker exactly as {@code app/cbl/COUSR00C.cbl}:152-179 classifies it, but it is a test-local
 *       oracle rather than a delegation. The services that will own that rule,
 *       {@code UserListService} from {@code app/cbl/COUSR00C.cbl} and {@code UserDeleteService} from
 *       {@code app/cbl/COUSR03C.cbl}, do not exist at this checkpoint, so the oracle is retained
 *       deliberately to record the contract rather than lose it, and this entry is its tracking record.
 *       <em>What would be needed:</em> those two services; the moment either arrives, re-point every
 *       assertion that calls {@code isSelected} at the production method and delete the oracle.</li>
 * </ul>
 *
 * @see UserSecurityDto
 * @see UserType
 * @see FixedClockProvider
 */
final class UserSecurityDtoTest {

    /**
     * The eight-character screen rendering of a page number, {@code PAGENUMI PIC X(8)} at
     * {@code app/cpy-bms/COUSR00.CPY}:60. {@code app/cbl/COUSR00C.cbl}:327 moves
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} into it, so the on-screen form is a zero-padded eight-digit value
     * rather than a trimmed number.
     */
    private static final String PAGE_ONE = "00000001";

    /**
     * The eight-character program name the user-list screen reports, {@code PGMNAMEI PIC X(8)}.
     */
    private static final String PROGRAM_NAME = "COUSR00C";

    /**
     * The four-character transaction identifier of the user list, {@code TRNNAMEI PIC X(4)}.
     */
    private static final String TRANSACTION_NAME = "CU00";

    /**
     * A single space: the value a fixed-width screen field carries when the operator supplied nothing. Distinct
     * from {@code null} and from the empty string, per {@code app/cbl/COUSR00C.cbl}:218.
     */
    private static final String BLANK = " ";

    /**
     * A single binary zero: the Java rendering of COBOL {@code LOW-VALUES}. The driving program treats it as
     * "not supplied" alongside spaces - {@code app/cbl/COUSR00C.cbl}:152 tests
     * {@code NOT = SPACES AND LOW-VALUES} - while keeping it a distinct byte value.
     */
    private static final String LOW_VALUES = "\u0000";

    /**
     * Recognises the shape of a BCrypt digest: a version tag, a two-digit cost factor and a 53-character
     * radix-64 payload.
     *
     * <p>A detector, never an example: it lets the security assertions prove that no component of a fully
     * populated instance can hold a digest without any digest literal appearing in this file. The migrated
     * credential column is 60 characters wide, against {@code SEC-USR-PWD PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L21}, so the two cannot be confused by width either.
     */
    private static final Pattern BCRYPT_SHAPE = Pattern.compile("^\\$2[abxy]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    /**
     * Component and accessor names that would indicate a credential had reached this projection.
     */
    private static final List<String> CREDENTIAL_NAME_FRAGMENTS =
            List.of("passwd", "password", "pwd", "secret", "credential", "hash", "digest", "token", "salt");

    /**
     * The three methods of the {@link Object} contract that every record generates.
     */
    private static final List<String> OBJECT_CONTRACT_METHODS = List.of("equals", "hashCode", "toString");

    /**
     * Legacy COMMAREA field names whose presence would reintroduce server-side session state.
     *
     * <p>Drawn from {@code app/cpy/COCOM01Y.cpy}:21-24, :29 and :43-44. Matched case insensitively with
     * separators removed, so {@code fromTranId}, {@code from_tran_id} and {@code FROMTRANID} all match.
     */
    private static final List<String> SESSION_STATE_NAME_FRAGMENTS =
            List.of("fromtranid", "totranid", "fromprogram", "toprogram", "pgmcontext", "lastmap", "lastmapset");

    /**
     * Names, in declaration order, of the fields the ten row groups repeat.
     *
     * <p>{@code SEL0001I}, {@code USRID01I}, {@code FNAME01I}, {@code LNAME01I} and {@code UTYPE01I} at
     * {@code app/cpy-bms/COUSR00.CPY}:72, :78, :84, :90 and :96 for row one.
     */
    private static final List<String> ROW_COMPONENT_NAMES =
            List.of("selectionFlag", "userId", "firstName", "lastName", "userType");

    /**
     * Names, in declaration order, of the projection's ten components.
     */
    private static final List<String> DTO_COMPONENT_NAMES = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "pageNumber", "userIdInput", "rows", "errorMessage");

    /**
     * Ten <strong>synthetic</strong> stand-ins for the seeded user identifiers, in the order the card
     * images appear at {@code app/jcl/DUSRSECJ.jcl}:35-44: five administrators then five standard users.
     * Each is exactly eight characters, matching the {@code KEYS(8,0)} cluster key at :65.
     *
     * <p>No seeded identity is transcribed under {@code src/}. These fixtures carry the structure the
     * tests assert - key width, ordering, distinctness - and nothing else; the real values are reachable
     * by citation at {@code app/jcl/DUSRSECJ.jcl:L35-L44}.
     */
    private static final List<String> SEEDED_USER_IDS = List.of(
            "ADMNUSR1", "ADMNUSR2", "ADMNUSR3", "ADMNUSR4", "ADMNUSR5",
            "STDUSR01", "STDUSR02", "STDUSR03", "STDUSR04", "STDUSR05");

    /**
     * The given names of the ten seeded users, positionally aligned with {@link #SEEDED_USER_IDS}. Names are
     * identifying but are not secrets; the credential the card images also carry is deliberately absent from
     * this file.
     */
    private static final List<String> SEEDED_FIRST_NAMES = List.of(
            "FNAMEAA1", "FNAMEA2", "FNAMEA3", "FNAMEAA4", "FNAMEAAA5",
            "FNAMEAA6", "FNAM7", "FNAMEA8", "FNAMEAA9", "FN0");

    /**
     * The family names of the ten seeded users, positionally aligned with {@link #SEEDED_USER_IDS}.
     */
    private static final List<String> SEEDED_LAST_NAMES = List.of(
            "LNM1", "LNAMEA2", "LNAMEAA3", "LNAMEAA4", "LNAMEAAAA5",
            "LNAME6", "LNAM7", "LNM8", "LNAM9", "LN10");

    /**
     * Builds one row, leaving every value exactly as supplied.
     *
     * @param selectionFlag the row marker, {@code SEL000nI PIC X(1)}.
     * @param userId the identifier, {@code USRIDnnI PIC X(8)}.
     * @param firstName the given name, {@code FNAMEnnI PIC X(20)}.
     * @param lastName the family name, {@code LNAMEnnI PIC X(20)}.
     * @param userType the raw one-character type code, {@code UTYPEnnI PIC X(1)}.
     * @return the row, never {@code null}
     */
    private static UserRow row(final String selectionFlag, final String userId, final String firstName,
            final String lastName, final String userType) {
        return new UserRow(selectionFlag, userId, firstName, lastName, userType);
    }

    /**
     * Builds the first {@code count} seeded rows, unselected, in card-image order.
     *
     * @param count how many rows the page carries, from zero to {@link UserSecurityDto#PAGE_SIZE}
     * @return an immutable, order-preserving list of exactly {@code count} rows
     */
    private static List<UserRow> seededRows(final int count) {
        final List<UserRow> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(row(BLANK, SEEDED_USER_IDS.get(index), SEEDED_FIRST_NAMES.get(index),
                    SEEDED_LAST_NAMES.get(index), index < 5 ? "A" : "U"));
        }
        return List.copyOf(rows);
    }

    /**
     * Builds a projection carrying the supplied rows beneath a canonical, deterministic header.
     *
     * @param rows the page's rows; passed through to the canonical constructor unchanged, including
     * {@code null}, so that its validation can be exercised
     * @return the projection, when the constructor accepts {@code rows}
     */
    private static UserSecurityDto pageOf(final List<UserRow> rows) {
        return new UserSecurityDto(TRANSACTION_NAME, "User List", headerDate(), PROGRAM_NAME,
                "CardDemo", headerTime(), PAGE_ONE, BLANK, rows, null);
    }

    /**
     * Renders the fixed clock as {@code CURDATEI PIC X(8)}.
     *
     * <p>Reproduces {@code WS-CURDATE-MM-DD-YY} at {@code app/cpy/CSDAT01Y.cpy}:30-35, which
     * {@code app/cbl/COUSR00C.cbl}:575 moves into the header field. The value comes from the injected clock,
     * never the wall clock, and the pattern resolves against {@link Locale#ROOT}.
     *
     * @return the eight-character header date, never {@code null}
     */
    private static String headerDate() {
        final Clock clock = FixedClockProvider.canonicalClock();
        return DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT)
                .format(clock.instant().atZone(clock.getZone()));
    }

    /**
     * Renders the fixed clock as {@code CURTIMEI PIC X(8)}.
     *
     * <p>Reproduces {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy}:36-41, which
     * {@code app/cbl/COUSR00C.cbl}:581 moves into the header field. Eight is this map's width; the sign-on map
     * alone declares nine.
     *
     * @return the eight-character header time, never {@code null}
     */
    private static String headerTime() {
        final Clock clock = FixedClockProvider.canonicalClock();
        return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                .format(clock.instant().atZone(clock.getZone()));
    }

    /**
     * Reduces an identifier to lower case with separators removed, for tolerant name matching.
     *
     * @param identifier the component or method name to reduce.
     * @return the reduced form, never {@code null}
     */
    private static String canonicalName(final String identifier) {
        return identifier.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Decides whether a method name indicates an accessor capable of reading a credential.
     *
     * @param methodName the declared method name to classify.
     * @return {@code true} when the name indicates a credential accessor
     */
    private static boolean isCredentialAccessorName(final String methodName) {
        if (OBJECT_CONTRACT_METHODS.contains(methodName)) {
            return false;
        }
        final String reduced = canonicalName(methodName);
        return CREDENTIAL_NAME_FRAGMENTS.stream().anyMatch(reduced::contains);
    }

    /**
     * Classifies a row marker the way {@code app/cbl/COUSR00C.cbl}:152-179 classifies it.
     *
     * @param marker the raw marker value; may be {@code null}
     * @return {@code true} only when the marker is a genuine selection
     */
    private static boolean isSelected(final String marker) {
        return marker != null && !marker.isEmpty() && !BLANK.equals(marker) && !LOW_VALUES.equals(marker);
    }

    /**
     * Returns the record component of {@code recordType} with the given name.
     *
     * @param recordType the record class to inspect.
     * @param name the component name to find
     * @return the matching component, never {@code null}
     * @throws IllegalArgumentException if {@code recordType} declares no component of that name, which means
     * the field contract has drifted
     */
    private static RecordComponent componentOf(final Class<?> recordType, final String name) {
        for (final RecordComponent component : recordType.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new IllegalArgumentException(recordType.getSimpleName() + " declares no record component named '"
                + name + "'; the field contract of app/cpy-bms/COUSR00.CPY has drifted");
    }

    /**
     * Component names of {@code recordType} in declaration order.
     */
    private static List<String> componentNamesOf(final Class<?> recordType) {
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    /**
     * Every non-null textual value a projection and its rows expose, for leak and shape scanning.
     *
     * @param page the projection to harvest.
     * @return the values in traversal order - the projection's own components first, then each row's five
     * components in row order - never {@code null}
     */
    private static List<String> allTextValuesOf(final UserSecurityDto page) {
        final List<String> values = new ArrayList<>();
        addIfPresent(values, page.transactionName());
        addIfPresent(values, page.title01());
        addIfPresent(values, page.currentDate());
        addIfPresent(values, page.programName());
        addIfPresent(values, page.title02());
        addIfPresent(values, page.currentTime());
        addIfPresent(values, page.pageNumber());
        addIfPresent(values, page.userIdInput());
        addIfPresent(values, page.errorMessage());
        for (final UserRow current : page.rows()) {
            addIfPresent(values, current.selectionFlag());
            addIfPresent(values, current.userId());
            addIfPresent(values, current.firstName());
            addIfPresent(values, current.lastName());
            addIfPresent(values, current.userType());
        }
        return List.copyOf(values);
    }

    /**
     * Collects {@code value} only when it is present, keeping the harvested list free of nulls.
     */
    private static void addIfPresent(final List<String> target, final String value) {
        if (value != null) {
            target.add(value);
        }
    }

    /**
     * Reads the field of {@code request} at the given map position.
     *
     * @param request the request to read
     * @param index   the zero-based map position, 0 for {@code TRNNAMEI} through 10 for {@code ERRMSGI}
     * @return the field value, which may be {@code null}
     */
    private static String valueOf(final UserDeleteScreen request, final int index) {
        return switch (index) {
            case 0 -> request.transactionName();
            case 1 -> request.title01();
            case 2 -> request.currentDate();
            case 3 -> request.programName();
            case 4 -> request.title02();
            case 5 -> request.currentTime();
            case 6 -> request.userIdInput();
            case 7 -> request.firstName();
            case 8 -> request.lastName();
            case 9 -> request.userType();
            case 10 -> request.errorMessage();
            default -> throw new IllegalArgumentException(
                    "app/cpy-bms/COUSR03.CPY declares 11 fields, so there is no position " + index);
        };
    }

    /**
     * The field contract of {@code app/cpy-bms/COUSR00.CPY}, input group {@code COUSR0AI}: exactly 59 data
     * fields, in declaration order, at their declared widths.
     */

    @Nested
    @DisplayName("Field contract: 59 input fields of COUSR0AI")
    class FieldContract {

        @Test
        @DisplayName("declares exactly 59 input fields, decomposing as 8 + (10 x 5) + 1")
        void declaresFiftyNineInputFields() {
            assertThat(UserSecurityDto.MAP_FIELD_COUNT)
                    .as("app/cpy-bms/COUSR00.CPY declares 59 data fields between :17 and :372")
                    .isEqualTo(59);
            assertThat(UserSecurityDto.PREAMBLE_FIELD_COUNT)
                    .as("six header fields at :24 :30 :36 :42 :48 :54 plus PAGENUMI :60 and USRIDINI :66")
                    .isEqualTo(8);
            assertThat(UserSecurityDto.ROW_FIELD_COUNT)
                    .as("SEL000nI, USRIDnnI, FNAMEnnI, LNAMEnnI, UTYPEnnI per row group")
                    .isEqualTo(5);
            assertThat(UserSecurityDto.TRAILER_FIELD_COUNT)
                    .as("ERRMSGI at :372 is the only trailer field")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the census arithmetic is derived from its parts, so it cannot drift from them")
        void censusArithmeticIsDerivedFromItsParts() {
            final int derived = UserSecurityDto.PREAMBLE_FIELD_COUNT
                    + (UserSecurityDto.PAGE_SIZE * UserSecurityDto.ROW_FIELD_COUNT)
                    + UserSecurityDto.TRAILER_FIELD_COUNT;
            assertThat(UserSecurityDto.MAP_FIELD_COUNT).isEqualTo(derived);
        }

        @Test
        @DisplayName("exposes ten components in the map's declaration order")
        void exposesTenComponentsInMapDeclarationOrder() {
            assertThat(componentNamesOf(UserSecurityDto.class))
                    .as("declaration order must follow app/cpy-bms/COUSR00.CPY :24 through :372")
                    .containsExactlyElementsOf(DTO_COMPONENT_NAMES);
        }

        @Test
        @DisplayName("each row exposes five components in the row group's declaration order")
        void eachRowExposesFiveComponentsInDeclarationOrder() {
            assertThat(componentNamesOf(UserRow.class))
                    .as("row one is declared at :72 :78 :84 :90 :96 in exactly this order")
                    .containsExactlyElementsOf(ROW_COMPONENT_NAMES);
            assertThat(componentNamesOf(UserRow.class)).hasSize(UserSecurityDto.ROW_FIELD_COUNT);
        }

        @ParameterizedTest(name = "{0} PIC X({1}) round-trips at exactly {1} characters")
        @CsvSource({
            "transactionName, 4",
            "title01, 40",
            "currentDate, 8",
            "programName, 8",
            "title02, 40",
            "currentTime, 8",
            "pageNumber, 8",
            "userIdInput, 8",
            "errorMessage, 78"
        })
        @DisplayName("carries each non-row field at its declared PIC width, byte-exactly")
        void carriesEachNonRowFieldAtItsDeclaredWidth(final String component, final int width) {
            final String atWidth = "X".repeat(width);
            final UserSecurityDto page = switch (component) {
                case "transactionName" -> new UserSecurityDto(atWidth, null, null, null, null, null, null,
                        null, List.of(), null);
                case "title01" -> new UserSecurityDto(null, atWidth, null, null, null, null, null, null,
                        List.of(), null);
                case "currentDate" -> new UserSecurityDto(null, null, atWidth, null, null, null, null, null,
                        List.of(), null);
                case "programName" -> new UserSecurityDto(null, null, null, atWidth, null, null, null, null,
                        List.of(), null);
                case "title02" -> new UserSecurityDto(null, null, null, null, atWidth, null, null, null,
                        List.of(), null);
                case "currentTime" -> new UserSecurityDto(null, null, null, null, null, atWidth, null, null,
                        List.of(), null);
                case "pageNumber" -> new UserSecurityDto(null, null, null, null, null, null, atWidth, null,
                        List.of(), null);
                case "userIdInput" -> new UserSecurityDto(null, null, null, null, null, null, null, atWidth,
                        List.of(), null);
                case "errorMessage" -> new UserSecurityDto(null, null, null, null, null, null, null, null,
                        List.of(), atWidth);
                default -> throw new IllegalArgumentException("unmapped component '" + component + "'");
            };

            assertThat(allTextValuesOf(page))
                    .as("%s must carry all %d characters without truncation or padding", component, width)
                    .containsExactly(atWidth);
            assertThat(allTextValuesOf(page).get(0)).hasSize(width);
        }

        @ParameterizedTest(name = "row field {0} PIC X({1}) round-trips at exactly {1} characters")
        @CsvSource({"selectionFlag, 1", "userId, 8", "firstName, 20", "lastName, 20", "userType, 1"})
        @DisplayName("carries each row field at its declared PIC width, byte-exactly")
        void carriesEachRowFieldAtItsDeclaredWidth(final String component, final int width) {
            final String atWidth = "X".repeat(width);
            final UserRow populated = switch (component) {
                case "selectionFlag" -> row(atWidth, null, null, null, null);
                case "userId" -> row(null, atWidth, null, null, null);
                case "firstName" -> row(null, null, atWidth, null, null);
                case "lastName" -> row(null, null, null, atWidth, null);
                case "userType" -> row(null, null, null, null, atWidth);
                default -> throw new IllegalArgumentException("unmapped row component '" + component + "'");
            };

            final List<String> carried = allTextValuesOf(pageOf(List.of(populated)));
            assertThat(carried).contains(atWidth);
            assertThat(carried.stream().filter(atWidth::equals).findFirst().orElseThrow()).hasSize(width);
        }

        @Test
        @DisplayName("the error line is 78 characters wide, not 79 and not 80")
        void errorLineIsSeventyEightCharactersWide() {
            final String seventyEight = "E".repeat(78);
            final UserSecurityDto page = new UserSecurityDto(null, null, null, null, null, null, null, null,
                    List.of(), seventyEight);

            assertThat(page.errorMessage()).hasSize(78).isEqualTo(seventyEight);
            assertThat(page.errorMessage())
                    .as("ERRMSGI at :372 is PIC X(78); 79 and 80 are both wrong widths")
                    .isNotEqualTo("E".repeat(79))
                    .isNotEqualTo("E".repeat(80));
        }

        @Test
        @DisplayName("the ten row groups are structurally uniform and indexable one through ten")
        void tenRowGroupsAreUniformAndIndexable() {
            final UserSecurityDto page = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));

            assertThat(page.rows()).hasSize(10);
            for (int position = 0; position < 10; position++) {
                final UserRow current = page.rows().get(position);
                assertThat(current.selectionFlag()).as("row %d selector", position + 1).isNotNull();
                assertThat(current.userId()).as("row %d identifier", position + 1)
                        .isEqualTo(SEEDED_USER_IDS.get(position));
                assertThat(current.firstName()).as("row %d given name", position + 1)
                        .isEqualTo(SEEDED_FIRST_NAMES.get(position));
                assertThat(current.lastName()).as("row %d family name", position + 1)
                        .isEqualTo(SEEDED_LAST_NAMES.get(position));
                assertThat(current.userType()).as("row %d type", position + 1).isNotNull().hasSize(1);
            }
        }

        @Test
        @DisplayName("every row group has five fields, unlike COCRDLI where row one has four")
        void everyRowGroupHasFiveFieldsUnlikeTheCardList() {
            assertThat(UserRow.class.getRecordComponents())
                    .as("COCRDLI.CPY omits CRDSTP1I so its row one has four fields; this map has no such gap")
                    .hasSize(5);
            final UserSecurityDto page = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));
            for (final UserRow current : page.rows()) {
                assertThat(current.getClass().getRecordComponents()).hasSize(5);
            }
        }
    }

    /**
     * The six recurring header fields, declared inline because the corpus does not agree on their widths.
     */
    @Nested
    @DisplayName("Screen header: six fields declared inline, no shared abstraction")
    class ScreenHeader {

        @Test
        @DisplayName("declares all six header fields directly on the record")
        void declaresAllSixHeaderFieldsDirectly() {
            assertThat(componentNamesOf(UserSecurityDto.class))
                    .containsSubsequence("transactionName", "title01", "currentDate", "programName",
                            "title02", "currentTime");
        }

        @Test
        @DisplayName("inherits no header-bearing supertype, so no shared header abstraction exists")
        void inheritsNoHeaderBearingSupertype() {
            assertThat(UserSecurityDto.class.getSuperclass())
                    .as("a record's only supertype is java.lang.Record")
                    .isEqualTo(Record.class);
            assertThat(UserSecurityDto.class.getInterfaces())
                    .as("CURTIMEI is X(8) here but X(9) on COSGN00.CPY:54, so a shared header contract "
                            + "would have to assert a width the corpus does not have")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries an eight-character header time and refuses the sign-on map's nine")
        void carriesEightCharacterHeaderTimeAndRefusesNine() {
            final UserSecurityDto eight = pageOf(List.of());
            assertThat(eight.currentTime()).as("CURTIMEI PIC X(8) at COUSR00.CPY:54").hasSize(8);
            assertThat(UserSecurityDto.TIME_WIDTH).isEqualTo(8);

            final String nine = "19:27:53 ";
            assertThatThrownBy(() -> new UserSecurityDto(null, null, null, null, null, nine, null,
                    null, List.of(), null))
                    .as("this is the sharpest available proof that no shared header abstraction exists: "
                            + "CURTIMEI is X(8) at COUSR00.CPY:54 and X(9) at COSGN00.CPY:54, so a "
                            + "nine-character time did not come from this map's field, and accepting it - "
                            + "or trimming it to eight - would each be a data error rather than a kindness")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentTime")
                    .hasMessageContaining("PIC X(8)")
                    .hasMessageContaining("9")
                    .hasNoCause();

            assertThatCode(() -> new UserSecurityDto(null, null, null, null, null, "19:27:53", null, null,
                    List.of(), null))
                    .as("eight is the boundary and is accepted, so the bound is not off by one")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("derives the header date and time from the injected fixed clock, never the wall clock")
        void derivesHeaderDateAndTimeFromTheFixedClock() {
            final UserSecurityDto page = pageOf(List.of());

            assertThat(page.currentDate())
                    .as("WS-CURDATE-MM-DD-YY at CSDAT01Y.cpy:30-35, moved at COUSR00C.cbl:575")
                    .isEqualTo("06/10/22")
                    .hasSize(8);
            assertThat(page.currentTime())
                    .as("WS-CURTIME-HH-MM-SS at CSDAT01Y.cpy:36-41, moved at COUSR00C.cbl:581")
                    .isEqualTo("19:27:53")
                    .hasSize(8);
        }

        @Test
        @DisplayName("the fixed clock makes the header reproducible across repeated construction")
        void fixedClockMakesTheHeaderReproducible() {
            assertThat(pageOf(List.of()).currentDate()).isEqualTo(pageOf(List.of()).currentDate());
            assertThat(pageOf(List.of()).currentTime()).isEqualTo(pageOf(List.of()).currentTime());
            assertThat(FixedClockProvider.canonicalClock().instant())
                    .as("COUSR00C.cbl:564 MOVE FUNCTION CURRENT-DATE is replaced by a fixed instant")
                    .isEqualTo(FixedClockProvider.CANONICAL_INSTANT);
        }
    }

    /**
     * Page size, the page-number field and the two paging constructs this projection deliberately does not
     * carry.
     */
    @Nested
    @DisplayName("Pagination: ten rows, raw page number, no sentinel, no session state")
    class Pagination {

        @Test
        @DisplayName("page size is exactly ten, per COUSR00C.cbl:57")
        void pageSizeIsExactlyTen() {
            assertThat(UserSecurityDto.PAGE_SIZE)
                    .as("app/cbl/COUSR00C.cbl:57 declares 02 USER-REC OCCURS 10 TIMES.")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("page size is neither the card list's seven nor the batch report's twenty")
        void pageSizeIsNeitherSevenNorTwenty() {
            assertThat(UserSecurityDto.PAGE_SIZE)
                    .as("seven belongs to WS-MAX-SCREEN-LINES at app/cbl/COCRDLIC.cbl:177-178")
                    .isNotEqualTo(7);
            assertThat(UserSecurityDto.PAGE_SIZE)
                    .as("twenty lines per page is a batch printed-page concern and must never reach a DTO")
                    .isNotEqualTo(20);
        }

        @ParameterizedTest(name = "a page of {0} rows is accepted")
        @ValueSource(ints = {0, 1, 2, 5, 9, 10})
        @DisplayName("accepts any page from empty through exactly ten rows")
        void acceptsAnyPageUpToTenRows(final int rowCount) {
            final UserSecurityDto page = pageOf(seededRows(rowCount));
            assertThat(page.rows()).hasSize(rowCount);
        }

        @ParameterizedTest(name = "a page of {0} rows is rejected")
        @ValueSource(ints = {11, 12, 20})
        @DisplayName("rejects a page carrying more rows than the screen paints")
        void rejectsAPageCarryingMoreRowsThanTheScreenPaints(final int rowCount) {
            final List<UserRow> overlong = new ArrayList<>(seededRows(UserSecurityDto.PAGE_SIZE));
            while (overlong.size() < rowCount) {
                overlong.add(row(BLANK, "OVERFLOW", "OVER", "FLOW", "U"));
            }

            assertThatThrownBy(() -> pageOf(overlong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rows holds " + rowCount + " elements")
                    .hasMessageContaining("exceeds the user-list page size of " + UserSecurityDto.PAGE_SIZE)
                    .as("the failure is the root cause; nothing was swallowed or re-wrapped")
                    .hasNoCause();
        }

        @Test
        @DisplayName("carries the page number as the raw eight-character value, not the card list's three")
        void carriesThePageNumberAsTheRawEightCharacterValue() {
            final UserSecurityDto page = pageOf(List.of());

            assertThat(page.pageNumber())
                    .as("PAGENUMI PIC X(8) at COUSR00.CPY:60, populated from PIC 9(08) at COUSR00C.cbl:327")
                    .isEqualTo(PAGE_ONE)
                    .hasSize(8);
            assertThat(page.pageNumber())
                    .as("PAGENOI PIC X(3) at COCRDLI.CPY:60 is a different name and width; no unification")
                    .hasSizeGreaterThan(3);
        }

        @Test
        @DisplayName("declares no next-page sentinel, so the 'N' and LOW-VALUES encodings cannot conflate")
        void declaresNoNextPageSentinel() {
            final List<String> reduced = componentNamesOf(UserSecurityDto.class).stream()
                    .map(UserSecurityDtoTest::canonicalName)
                    .toList();

            assertThat(reduced)
                    .as("COUSR00C.cbl:71-73 puts the user list in the 'N' family while COCRDLIC.cbl:243 "
                            + "uses LOW-VALUES; carrying no sentinel here makes conflation impossible")
                    .noneMatch(name -> name.contains("nextpage"))
                    .noneMatch(name -> name.contains("pageflg"))
                    .noneMatch(name -> name.contains("lastpage"))
                    .noneMatch(name -> name.contains("sentinel"));
        }

        @Test
        @DisplayName("declares no COMMAREA session-state field")
        void declaresNoCommAreaSessionStateField() {
            final List<String> reduced = componentNamesOf(UserSecurityDto.class).stream()
                    .map(UserSecurityDtoTest::canonicalName)
                    .toList();

            for (final String forbidden : SESSION_STATE_NAME_FRAGMENTS) {
                assertThat(reduced)
                        .as("stateless request handling replaces the COMMAREA, so '%s' "
                                + "(app/cpy/COCOM01Y.cpy:21-24, :29, :43-44) must not appear", forbidden)
                        .noneMatch(name -> name.contains(forbidden));
            }
        }

        @Test
        @DisplayName("declares no class-level cross-field constraint, so an all-absent page still builds")
        void declaresNoClassLevelCrossFieldConstraint() {
            for (final Annotation annotation : UserSecurityDto.class.getAnnotations()) {
                assertThat(canonicalName(annotation.annotationType().getSimpleName()))
                        .as("app/cbl/COACTUPC.cbl:1665-1669 runs its cross-field edit only after both "
                                + "single-field edits passed; a class-level constraint fires unconditionally")
                        .doesNotContain("asserttrue")
                        .doesNotContain("assertfalse");
            }

            assertThatCode(() -> new UserSecurityDto(null, null, null, null, null, null, null, null,
                    List.of(), null))
                    .as("a page with every textual field absent is a legitimate state")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The security contract: no credential is carried, and the diagnostic renderings disclose nothing.
     */
    @Nested
    @DisplayName("Security: no password, no hash, no disclosure")
    class Security {

        @Test
        @DisplayName("declares no credential-named component on the projection")
        void declaresNoCredentialNamedComponentOnTheProjection() {
            final List<String> reduced = componentNamesOf(UserSecurityDto.class).stream()
                    .map(UserSecurityDtoTest::canonicalName)
                    .toList();

            for (final String forbidden : CREDENTIAL_NAME_FRAGMENTS) {
                assertThat(reduced)
                        .as("COUSR00.CPY declares no password field; PASSWDI lives only on "
                                + "COUSR01.CPY:78 and COUSR02.CPY:78, so '%s' must not appear here", forbidden)
                        .noneMatch(name -> name.contains(forbidden));
            }
        }

        @Test
        @DisplayName("declares no credential-named component on a row")
        void declaresNoCredentialNamedComponentOnARow() {
            assertThat(componentNamesOf(UserRow.class))
                    .as("the row quintuple is exactly selector, identifier, given name, family name, type")
                    .containsExactlyElementsOf(ROW_COMPONENT_NAMES);

            final List<String> reduced = componentNamesOf(UserRow.class).stream()
                    .map(UserSecurityDtoTest::canonicalName)
                    .toList();
            for (final String forbidden : CREDENTIAL_NAME_FRAGMENTS) {
                assertThat(reduced)
                        .as("app/cpy-bms/COUSR00.CPY declares no row password field, so '%s' "
                                + "must not appear", forbidden)
                        .noneMatch(name -> name.contains(forbidden));
            }
        }

        @Test
        @DisplayName("exposes no credential-named accessor on either type")
        void exposesNoCredentialNamedAccessor() {
            assertThatNoCredentialAccessorIsDeclaredBy(UserSecurityDto.class);
            assertThatNoCredentialAccessorIsDeclaredBy(UserRow.class);
        }

        @Test
        @DisplayName("the accessor scan still catches a credential-shaped name, so it is not vacuous")
        void theAccessorScanIsNotVacuous() {
            assertThat(isCredentialAccessorName("passwordHash")).isTrue();
            assertThat(isCredentialAccessorName("getPasswd")).isTrue();
            assertThat(isCredentialAccessorName("secUsrPwd")).isTrue();
            assertThat(isCredentialAccessorName("bcryptDigest")).isTrue();
            assertThat(isCredentialAccessorName("userId")).isFalse();
            assertThat(isCredentialAccessorName("hashCode"))
                    .as("the Object contract method every record generates is structural, not an accessor")
                    .isFalse();
        }

        /**
         * Asserts that no method {@code recordType} declares can read a credential.
         */
        private void assertThatNoCredentialAccessorIsDeclaredBy(final Class<?> recordType) {
            for (final Method declared : recordType.getDeclaredMethods()) {
                assertThat(isCredentialAccessorName(declared.getName()))
                        .as("%s.%s() must not be able to read a credential; the read projection of "
                                        + "app/cpy-bms/COUSR00.CPY carries none",
                                recordType.getSimpleName(), declared.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no value a fully populated page can carry is digest-shaped")
        void noValueAFullyPopulatedPageCanCarryIsDigestShaped() {
            final UserSecurityDto page = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));

            assertThat(allTextValuesOf(page))
                    .as("the credential column of app/cpy/CSUSR01Y.cpy:21 becomes a 60-character digest "
                            + "column that is never projected onto this type")
                    .isNotEmpty()
                    .noneMatch(value -> BCRYPT_SHAPE.matcher(value).matches());
        }

        @Test
        @DisplayName("renders only the originating program name, never any user data")
        void rendersOnlyTheOriginatingProgramName() {
            final UserSecurityDto page = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));

            assertThat(page.toString()).isEqualTo("UserSecurityDto[programName=" + PROGRAM_NAME + "]");
            assertThat(page.toString()).doesNotContain(SEEDED_USER_IDS);
            assertThat(page.toString()).doesNotContain(SEEDED_FIRST_NAMES);
            assertThat(page.toString()).doesNotContain(SEEDED_LAST_NAMES);
        }

        @Test
        @DisplayName("a row renders its type name only, disclosing neither identifier nor name")
        void aRowRendersItsTypeNameOnly() {
            final UserRow first = pageOf(seededRows(1)).rows().get(0);

            assertThat(first.toString()).isEqualTo("UserSecurityDto.UserRow");
            assertThat(first.toString())
                    .doesNotContain(SEEDED_USER_IDS.get(0))
                    .doesNotContain(SEEDED_FIRST_NAMES.get(0))
                    .doesNotContain(SEEDED_LAST_NAMES.get(0));
        }

        @Test
        @DisplayName("the search key never reaches the rendering, even when an operator filtered by it")
        void theSearchKeyNeverReachesTheRendering() {
            final UserSecurityDto page = new UserSecurityDto(TRANSACTION_NAME, "User List", headerDate(),
                    PROGRAM_NAME, "CardDemo", headerTime(), PAGE_ONE, SEEDED_USER_IDS.get(0),
                    seededRows(1), "User not found");

            assertThat(page.userIdInput()).isEqualTo(SEEDED_USER_IDS.get(0));
            assertThat(page.toString())
                    .as("USRIDINI is an operator-supplied identifier and stays out of diagnostics")
                    .doesNotContain(SEEDED_USER_IDS.get(0))
                    .doesNotContain("User not found");
        }

        @Test
        @DisplayName("names are carried as data rather than suppressed, while staying out of diagnostics")
        void namesAreCarriedAsDataButStayOutOfDiagnostics() {
            final UserRow first = pageOf(seededRows(1)).rows().get(0);

            assertThat(first.firstName())
                    .as("a name is identifying but is not a secret; the list screen paints it")
                    .isEqualTo(SEEDED_FIRST_NAMES.get(0));
            assertThat(first.lastName()).isEqualTo(SEEDED_LAST_NAMES.get(0));
            assertThat(first.toString()).doesNotContain(SEEDED_FIRST_NAMES.get(0));
        }
    }

    /**
     * The row user type: a raw one-character code whose domain is the copybook's, and the row selection
     * marker's three distinguishable states.
     */
    @Nested
    @DisplayName("User type and row selection marker")
    class UserTypeAndSelectionMarker {

        @Test
        @DisplayName("the row user type is a raw String, deliberately not the UserType enum")
        void theRowUserTypeIsARawStringNotTheEnum() {
            final RecordComponent userType = componentOf(UserRow.class, "userType");

            assertThat(userType.getType())
                    .as("UTYPEnnI PIC X(1) is carried verbatim so a stored out-of-domain byte surfaces as "
                            + "data, exactly as the legacy screen paints it, rather than failing to bind")
                    .isEqualTo(String.class)
                    .isNotEqualTo(UserType.class);
        }

        @ParameterizedTest(name = "the code \"{0}\" round-trips byte-exactly")
        @ValueSource(strings = {"A", "U"})
        @DisplayName("round-trips a recognised one-character code byte-exactly")
        void roundTripsARecognisedCodeByteExactly(final String code) {
            final UserRow only = row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), code);

            assertThat(pageOf(List.of(only)).rows().get(0).userType()).isEqualTo(code).hasSize(1);
        }

        @Test
        @DisplayName("the carried codes are exactly the domain of the copybook's two condition names")
        void theCarriedCodesAreExactlyTheCopybookDomain() {
            final String adminCode = String.valueOf(UserType.ADMIN.getCode());
            final String userCode = String.valueOf(UserType.USER.getCode());

            final UserSecurityDto page = pageOf(List.of(
                    row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0), SEEDED_LAST_NAMES.get(0),
                            adminCode),
                    row(BLANK, SEEDED_USER_IDS.get(5), SEEDED_FIRST_NAMES.get(5), SEEDED_LAST_NAMES.get(5),
                            userCode)));

            assertThat(UserType.fromCode(page.rows().get(0).userType()))
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A'. at app/cpy/COCOM01Y.cpy:27")
                    .contains(UserType.ADMIN);
            assertThat(UserType.fromCode(page.rows().get(1).userType()))
                    .as("88 CDEMO-USRTYP-USER VALUE 'U'. at app/cpy/COCOM01Y.cpy:28")
                    .contains(UserType.USER);
        }

        @Test
        @DisplayName("the ten seeded rows carry five administrators then five standard users")
        void theTenSeededRowsCarryFiveAdministratorsThenFiveStandardUsers() {
            final UserSecurityDto page = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));

            for (int position = 0; position < 5; position++) {
                assertThat(UserType.fromCode(page.rows().get(position).userType()))
                        .as("app/jcl/DUSRSECJ.jcl:%d is an administrator", 35 + position)
                        .contains(UserType.ADMIN);
            }
            for (int position = 5; position < 10; position++) {
                assertThat(UserType.fromCode(page.rows().get(position).userType()))
                        .as("app/jcl/DUSRSECJ.jcl:%d is a standard user", 35 + position)
                        .contains(UserType.USER);
            }
        }

        @ParameterizedTest(name = "an unsupplied type of [{0}] stays unsupplied, with no default invented")
        @ValueSource(strings = {" ", "\u0000", ""})
        @DisplayName("carries an unsupplied type verbatim rather than fabricating a default")
        void carriesAnUnsuppliedTypeVerbatimRatherThanFabricatingADefault(final String unsupplied) {
            final UserRow only = row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), unsupplied);
            final String carried = pageOf(List.of(only)).rows().get(0).userType();

            assertThat(carried).isEqualTo(unsupplied);
            assertThat(carried)
                    .as("no default is invented: an unsupplied code is neither 'U' nor 'A'")
                    .isNotEqualTo(String.valueOf(UserType.USER.getCode()))
                    .isNotEqualTo(String.valueOf(UserType.ADMIN.getCode()));
            assertThat(UserType.fromCode(carried))
                    .as("the enum agrees the value is out of domain, without repairing it")
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent type stays null and is never coerced to a code")
        void anAbsentTypeStaysNull() {
            final UserRow only = row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), null);

            assertThat(pageOf(List.of(only)).rows().get(0).userType()).isNull();
            assertThat(UserType.fromCode((String) null)).isEmpty();
        }

        @Test
        @DisplayName("an unrecognised code is carried as data, while the enum guard names it rather than "
                + "falling back")
        void anUnrecognisedCodeIsCarriedAsDataWhileTheGuardNamesIt() {
            final String unrecognised = "X";
            final UserRow only = row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), unrecognised);

            assertThatCode(() -> pageOf(List.of(only)))
                    .as("a read projection carries whatever the record holds; it does not reject stored data")
                    .doesNotThrowAnyException();
            assertThat(pageOf(List.of(only)).rows().get(0).userType()).isEqualTo(unrecognised);

            assertThatThrownBy(() -> UserType.requireFromCode(unrecognised.charAt(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("code point 0x58")
                    .hasMessageContaining("app/cpy/COCOM01Y.cpy")
                    .as("the guard names the offending code by its code point; there is no silent "
                            + "fallback to USER or ADMIN, and no echo of the rejected character either. "
                            + "The value reaches this guard from a persisted column, so a control "
                            + "character among those bytes reproduced verbatim in a message bound for a "
                            + "log would let corrupt data forge a log entry")
                    .hasNoCause();
            assertThatThrownBy(() -> UserType.requireFromCode(unrecognised.charAt(0)))
                    .as("the quoted raw character is absent, which is the form a reader would copy out "
                            + "of a log")
                    .hasMessageNotContaining("'" + unrecognised + "'");
        }

        @Test
        @DisplayName("a blank selection marker never selects")
        void aBlankSelectionMarkerNeverSelects() {
            assertThat(isSelected(BLANK))
                    .as("app/cbl/COUSR00C.cbl:152 tests NOT = SPACES AND LOW-VALUES, so spaces do not select")
                    .isFalse();
            assertThat(isSelected(LOW_VALUES))
                    .as("binary zeros do not select either, by the same predicate")
                    .isFalse();
            assertThat(isSelected(null)).as("a field the response never carried was never marked").isFalse();
            assertThat(isSelected("")).as("an empty marker carries no selection character").isFalse();
        }

        @Test
        @DisplayName("blank, selected and unexpected markers are three distinguishable states")
        void blankSelectedAndUnexpectedMarkersAreThreeDistinguishableStates() {
            final UserSecurityDto page = pageOf(List.of(
                    row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0), SEEDED_LAST_NAMES.get(0), "A"),
                    row("S", SEEDED_USER_IDS.get(1), SEEDED_FIRST_NAMES.get(1), SEEDED_LAST_NAMES.get(1), "A"),
                    row("?", SEEDED_USER_IDS.get(2), SEEDED_FIRST_NAMES.get(2), SEEDED_LAST_NAMES.get(2), "A")));

            assertThat(page.rows().get(0).selectionFlag()).isEqualTo(BLANK);
            assertThat(page.rows().get(1).selectionFlag()).isEqualTo("S");
            assertThat(page.rows().get(2).selectionFlag()).isEqualTo("?");
            assertThat(List.of(page.rows().get(0).selectionFlag(), page.rows().get(1).selectionFlag(),
                    page.rows().get(2).selectionFlag()))
                    .as("the three markers remain distinct values, never normalised to one")
                    .doesNotHaveDuplicates();

            assertThat(isSelected(page.rows().get(0).selectionFlag())).isFalse();
            assertThat(isSelected(page.rows().get(1).selectionFlag())).isTrue();
            assertThat(isSelected(page.rows().get(2).selectionFlag()))
                    .as("COUSR00C.cbl:152 selects on any character other than spaces or low-values, so an "
                            + "unexpected character selects and is then rejected downstream, not here")
                    .isTrue();
        }

        @Test
        @DisplayName("no self-delete restriction is invented, because COUSR03C.cbl has none")
        void noSelfDeleteRestrictionIsInvented() {
            final String signedOnUser = SEEDED_USER_IDS.get(0);
            final UserRow ownRow = row("S", signedOnUser, SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), "A");

            assertThatCode(() -> pageOf(List.of(ownRow)))
                    .as("app/cbl/COUSR03C.cbl never compares the target identifier against the signed-on "
                            + "one; that absent guard is preserved and recorded in DECISION_LOG.md")
                    .doesNotThrowAnyException();
            assertThat(pageOf(List.of(ownRow)).rows().get(0).userId()).isEqualTo(signedOnUser);
            assertThat(isSelected(pageOf(List.of(ownRow)).rows().get(0).selectionFlag()))
                    .as("an administrator may select their own row; no restriction is added here")
                    .isTrue();
        }
    }

    /**
     * Absent, empty, blank and low-values are four distinct states, and no value is trimmed, padded or case
     * folded on ingest.
     */
    @Nested
    @DisplayName("Field states: absent, empty, blank and low-values stay distinct")
    class FieldStates {

        @Test
        @DisplayName("keeps absent, empty, blank and low-values distinct on the search key")
        void keepsTheFourStatesDistinctOnTheSearchKey() {
            final String absent = new UserSecurityDto(null, null, null, null, null, null, null, null,
                    List.of(), null).userIdInput();
            final String empty = new UserSecurityDto(null, null, null, null, null, null, null, "",
                    List.of(), null).userIdInput();
            final String blank = new UserSecurityDto(null, null, null, null, null, null, null, BLANK,
                    List.of(), null).userIdInput();
            final String lowValues = new UserSecurityDto(null, null, null, null, null, null, null,
                    LOW_VALUES, List.of(), null).userIdInput();

            assertThat(absent).as("USRIDINI absent from the response").isNull();
            assertThat(empty).as("USRIDINI present and empty").isEmpty();
            assertThat(blank).as("app/cbl/COUSR00C.cbl:218 tests = SPACES OR LOW-VALUES").isEqualTo(BLANK);
            assertThat(lowValues).as("binary zeros are a distinct byte value").isEqualTo(LOW_VALUES);
            assertThat(List.of(empty, blank, lowValues))
                    .as("app/cpy/CSSETATY.cpy models OK, NOT-OK and BLANK as separate states")
                    .doesNotHaveDuplicates();
        }

        @ParameterizedTest(name = "the page number state [{0}] is carried verbatim")
        @ValueSource(strings = {"", " ", "\u0000", "00000001", "        "})
        @DisplayName("carries every page-number state verbatim, applying no parsing")
        void carriesEveryPageNumberStateVerbatim(final String state) {
            final UserSecurityDto page = new UserSecurityDto(null, null, null, null, null, null, state,
                    null, List.of(), null);

            assertThat(page.pageNumber()).isEqualTo(state).hasSameSizeAs(state);
        }

        @Test
        @DisplayName("keeps an absent page number distinct from a blank one")
        void keepsAnAbsentPageNumberDistinctFromABlankOne() {
            final UserSecurityDto absent = new UserSecurityDto(null, null, null, null, null, null, null,
                    null, List.of(), null);
            final UserSecurityDto blank = new UserSecurityDto(null, null, null, null, null, null,
                    " ".repeat(8), null, List.of(), null);

            assertThat(absent.pageNumber()).isNull();
            assertThat(blank.pageNumber()).isEqualTo(" ".repeat(8)).hasSize(8);
        }

        @Test
        @DisplayName("keeps an absent error line distinct from a blank one")
        void keepsAnAbsentErrorLineDistinctFromABlankOne() {
            final UserSecurityDto succeeded = new UserSecurityDto(null, null, null, null, null, null, null,
                    null, List.of(), null);
            final UserSecurityDto cleared = new UserSecurityDto(null, null, null, null, null, null, null,
                    null, List.of(), " ".repeat(78));

            assertThat(succeeded.errorMessage()).as("no error line at all").isNull();
            assertThat(cleared.errorMessage()).as("an ERRMSGI of 78 spaces").isEqualTo(" ".repeat(78));
            assertThat(succeeded.errorMessage()).isNotEqualTo(cleared.errorMessage());
        }

        @ParameterizedTest(name = "row field {0} keeps absent, empty, blank and low-values distinct")
        @CsvSource({"selectionFlag", "userId", "firstName", "lastName", "userType"})
        @DisplayName("keeps the four states distinct on every one of the five row fields")
        void keepsTheFourStatesDistinctOnEveryRowField(final String component) {
            final String absent = rowValueOf(component, null);
            final String empty = rowValueOf(component, "");
            final String blank = rowValueOf(component, BLANK);
            final String lowValues = rowValueOf(component, LOW_VALUES);

            assertThat(absent).as("%s absent", component).isNull();
            assertThat(empty).as("%s present and empty", component).isEmpty();
            assertThat(blank).as("%s carrying spaces", component).isEqualTo(BLANK);
            assertThat(lowValues).as("%s carrying binary zeros", component).isEqualTo(LOW_VALUES);
            assertThat(List.of(empty, blank, lowValues)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("preserves trailing space padding exactly, on the key and on a row")
        void preservesTrailingSpacePaddingExactly() {
            final String padded = "STDU1   ";
            final UserRow only = row(BLANK, padded, "FN0                 ", "LN10                ", "U");
            final UserSecurityDto page = new UserSecurityDto(TRANSACTION_NAME, null, null, PROGRAM_NAME,
                    null, null, PAGE_ONE, padded, List.of(only), null);

            assertThat(page.userIdInput()).isEqualTo(padded).hasSize(8);
            assertThat(page.rows().get(0).userId())
                    .as("a fixed-width USRIDnnI is blank padded; trimming it would change the key")
                    .isEqualTo(padded)
                    .isNotEqualTo(padded.trim());
            assertThat(page.rows().get(0).firstName()).hasSize(20);
            assertThat(page.rows().get(0).lastName()).hasSize(20);
        }

        @Test
        @DisplayName("applies no case folding on ingest, and folds only with Locale.ROOT when asked to")
        void appliesNoCaseFoldingOnIngest() {
            final String mixedCase = "aDmNuSr1";
            final UserRow only = row(BLANK, mixedCase, "Fnameaa1", "Lnm1", "a");
            final UserRow carried = pageOf(List.of(only)).rows().get(0);

            assertThat(carried.userId()).as("no upper-casing on ingest").isEqualTo(mixedCase);
            assertThat(carried.firstName()).isEqualTo("Fnameaa1");
            assertThat(carried.userType())
                    .as("a lower-case type code is out of domain and is carried, not repaired")
                    .isEqualTo("a");
            assertThat(UserType.fromCode(carried.userType())).isEmpty();

            assertThat(carried.userId().toUpperCase(Locale.ROOT))
                    .as("the sign-on program upper-cases both identifier and credential, so any case "
                            + "operation must pass Locale.ROOT to stay host-independent")
                    .isEqualTo("ADMNUSR1");
        }

        /**
         * Builds a row with only {@code component} set to {@code value}, and returns that value back.
         */
        private String rowValueOf(final String component, final String value) {
            final UserRow only = switch (component) {
                case "selectionFlag" -> row(value, null, null, null, null);
                case "userId" -> row(null, value, null, null, null);
                case "firstName" -> row(null, null, value, null, null);
                case "lastName" -> row(null, null, null, value, null);
                case "userType" -> row(null, null, null, null, value);
                default -> throw new IllegalArgumentException("unmapped row component '" + component + "'");
            };
            final UserRow carried = pageOf(List.of(only)).rows().get(0);
            return switch (component) {
                case "selectionFlag" -> carried.selectionFlag();
                case "userId" -> carried.userId();
                case "firstName" -> carried.firstName();
                case "lastName" -> carried.lastName();
                case "userType" -> carried.userType();
                default -> throw new IllegalArgumentException("unmapped row component '" + component + "'");
            };
        }
    }

    /**
     * The row collection: rejection, ordering, immutability and defensive copying.
     */
    @Nested
    @DisplayName("Row collection: validated, ordered, immutable")
    class RowCollection {

        @Test
        @DisplayName("rejects an absent row collection and points at the empty-list alternative")
        void rejectsAnAbsentRowCollection() {
            assertThatThrownBy(() -> pageOf(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rows must not be null")
                    .hasMessageContaining("empty list")
                    .as("absent is rejected rather than silently coerced to empty")
                    .hasNoCause();
        }

        @ParameterizedTest(name = "a null element at index {0} is rejected by index")
        @ValueSource(ints = {0, 1, 4, 9})
        @DisplayName("rejects a null row element and names the offending index")
        void rejectsANullRowElementAndNamesTheIndex(final int nullIndex) {
            final List<UserRow> withHole = new ArrayList<>(seededRows(UserSecurityDto.PAGE_SIZE));
            withHole.set(nullIndex, null);

            assertThatThrownBy(() -> pageOf(withHole))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not contain a null element")
                    .hasMessageContaining("index " + nullIndex)
                    .as("the message names the index but never a row value, which is identifying")
                    .hasNoCause();
        }

        @Test
        @DisplayName("the null-element failure names no user data")
        void theNullElementFailureNamesNoUserData() {
            final List<UserRow> withHole = new ArrayList<>(seededRows(2));
            withHole.set(1, null);

            assertThatThrownBy(() -> pageOf(withHole))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(SEEDED_USER_IDS.get(0))
                    .hasMessageNotContaining(SEEDED_FIRST_NAMES.get(0))
                    .hasMessageNotContaining(SEEDED_LAST_NAMES.get(0));
        }

        @Test
        @DisplayName("an empty page is valid, is not null, and means the filter matched no user")
        void anEmptyPageIsValidAndNotNull() {
            final UserSecurityDto page = pageOf(List.of());

            assertThat(page.rows()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("preserves insertion order and never sorts or hashes the rows")
        void preservesInsertionOrderAndNeverSortsOrHashes() {
            final UserRow third = row(BLANK, "STDUSR03", "FNAMEA8", "LNM8", "U");
            final UserRow first = row(BLANK, "ADMNUSR1", "FNAMEAA1", "LNM1", "A");
            final UserRow second = row(BLANK, "STDUSR01", "FNAMEAA6", "LNAME6", "U");

            final UserSecurityDto page = pageOf(List.of(third, first, second));

            assertThat(page.rows())
                    .as("row position is meaningful; a sorted or hash-ordered collection would reorder these")
                    .containsExactly(third, first, second);
            assertThat(page.rows().stream().map(UserRow::userId).toList())
                    .containsExactly("STDUSR03", "ADMNUSR1", "STDUSR01");
        }

        @Test
        @DisplayName("returns the same order on every call")
        void returnsTheSameOrderOnEveryCall() {
            final UserSecurityDto page = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));

            assertThat(page.rows()).containsExactlyElementsOf(page.rows());
            assertThat(page.rows().stream().map(UserRow::userId).toList())
                    .containsExactlyElementsOf(SEEDED_USER_IDS);
        }

        @Test
        @DisplayName("copies the row collection defensively, so later mutation is not observable")
        void copiesTheRowCollectionDefensively() {
            final List<UserRow> mutable = new ArrayList<>(seededRows(2));
            final UserSecurityDto page = pageOf(mutable);

            mutable.clear();
            mutable.add(row("S", "INTRUDER", "MALLORY", "SMITH", "A"));

            assertThat(page.rows()).hasSize(2);
            assertThat(page.rows().stream().map(UserRow::userId).toList())
                    .containsExactly(SEEDED_USER_IDS.get(0), SEEDED_USER_IDS.get(1))
                    .doesNotContain("INTRUDER");
        }

        @Test
        @DisplayName("exposes an unmodifiable row collection")
        void exposesAnUnmodifiableRowCollection() {
            final List<UserRow> exposed = pageOf(seededRows(2)).rows();
            final UserRow extra = row(BLANK, "STDUSR05", "FN0", "LN10", "U");

            assertThatThrownBy(() -> exposed.add(extra))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> exposed.set(0, extra))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> exposed.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(exposed::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * Hostile and out-of-contract input. Every case is carried verbatim: this is an outbound projection, so it
     * validates no width, and asserting that explicitly is what would catch a future silent truncation.
     */
    @Nested
    @DisplayName("Hostile input: refused when over-wide, carried verbatim when within width")
    class HostileInput {

        @Test
        @DisplayName("refuses a nine-character identifier against USRIDnnI PIC X(8) rather than truncating")
        void refusesANineCharacterIdentifierRatherThanTruncating() {
            final String tooLong = "ADMNUSR19";

            assertThatThrownBy(() -> row(BLANK, tooLong, "FNAMEAA1", "LNM1", "A"))
                    .as("silent truncation to the X(8) width would be an undetectable data change, so the "
                            + "row constructor refuses the value instead")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userId")
                    .hasMessageContaining("PIC X(8)")
                    .hasMessageContaining("9")
                    .hasNoCause();

            assertThatThrownBy(() -> new UserSecurityDto(TRANSACTION_NAME, null, null, PROGRAM_NAME,
                    null, null, PAGE_ONE, tooLong, List.of(), null))
                    .as("USRIDINI at :66 is X(8) too, so the search key is bounded on the same terms")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userIdInput")
                    .hasMessageContaining("PIC X(8)")
                    .hasNoCause();
        }

        @Test
        @DisplayName("refuses a value that names no user, and never quotes the offending value")
        void refusalNeverQuotesTheOffendingValue() {
            final String identifying = "FNAMEAA1-LNM1OVERLNG";

            assertThatThrownBy(() -> row(BLANK, identifying, "FNAMEAA1", "LNM1", "A"))
                    .as("three of the five row components are directly identifying, so a failure message "
                            + "must name the component and its length but never echo the value")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(identifying);
        }

        @Test
        @DisplayName("refuses a seventy-nine character error line against ERRMSGI PIC X(78)")
        void refusesASeventyNineCharacterErrorLine() {
            final String tooLong = "E".repeat(79);

            assertThatThrownBy(() -> new UserSecurityDto(null, null, null, null, null, null, null,
                    null, List.of(), tooLong))
                    .as("the declared width is 78; a 79-character line is refused rather than quietly "
                            + "clipped to fit the fixed-width screen field")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorMessage")
                    .hasMessageContaining("PIC X(78)")
                    .hasMessageContaining("79")
                    .hasNoCause();

            assertThatCode(() -> new UserSecurityDto(null, null, null, null, null, null, null, null,
                    List.of(), "E".repeat(78)))
                    .as("seventy-eight is the boundary and is accepted, so the bound is not off by one")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "a non-numeric page number of [{0}] is carried verbatim")
        @ValueSource(strings = {"ABCDEFGH", "-0000001", "1.5     ", "        ", "0000000A"})
        @DisplayName("carries a non-numeric page number against PAGENUMI PIC X(8) without parsing")
        void carriesANonNumericPageNumberWithoutParsing(final String nonNumeric) {
            final UserSecurityDto page = new UserSecurityDto(null, null, null, null, null, null,
                    nonNumeric, null, List.of(), null);

            assertThat(page.pageNumber())
                    .as("PAGENUMI is alphanumeric; COUSR00C.cbl:327 moves a PIC 9(08) into it but the "
                            + "field itself imposes no numeric domain, so no parsing happens here")
                    .isEqualTo(nonNumeric)
                    .hasSize(8);
        }

        @ParameterizedTest(name = "an unexpected user type of [{0}] is carried verbatim")
        @ValueSource(strings = {"X", "1", "a", "u", "@"})
        @DisplayName("carries an unexpected user type code without rejecting or defaulting it")
        void carriesAnUnexpectedUserTypeCode(final String unexpected) {
            final UserRow only = row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), unexpected);
            final String carried = pageOf(List.of(only)).rows().get(0).userType();

            assertThat(carried).isEqualTo(unexpected);
            assertThat(UserType.fromCode(carried))
                    .as("the comparison is case sensitive and exact, so 'a' and 'u' are out of domain too")
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses an over-wide user type code and an over-wide selector rather than clipping")
        void refusesAnOverWideUserTypeCodeAndSelector() {
            assertThatThrownBy(() -> row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), "AU"))
                    .as("UTYPEnnI is X(1); a two-character code is refused rather than trimmed to fit")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userType")
                    .hasMessageContaining("PIC X(1)");

            assertThatThrownBy(() -> row("SS", SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), "A"))
                    .as("SEL000nI is X(1), and a selector is screen-control state rather than a free string")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selectionFlag")
                    .hasMessageContaining("PIC X(1)");
        }

        @Test
        @DisplayName("a one-character code outside the A and U domain is still carried, because width is "
                + "the only rule")
        void aSingleCharacterCodeOutsideTheDomainIsStillCarried() {
            final UserRow only = row("X", SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), "Z");
            final UserRow carried = pageOf(List.of(only)).rows().get(0);

            assertThat(carried.userType())
                    .as("a stored code outside app/cpy/COCOM01Y.cpy:L27-L28 must surface as data rather "
                            + "than as a serialisation failure, so the domain is deliberately not enforced")
                    .isEqualTo("Z")
                    .hasSize(1);
            assertThat(carried.selectionFlag()).isEqualTo("X").hasSize(1);
            assertThat(UserType.fromCode(carried.userType())).isEmpty();
        }

        @Test
        @DisplayName("every one of the nine projection fields and five row fields is bounded, none omitted")
        void everyTextualComponentIsBounded() {
            assertThatThrownBy(() -> new UserSecurityDto("TOOLONG", null, null, null, null, null, null,
                    null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionName");
            assertThatThrownBy(() -> new UserSecurityDto(null, "T".repeat(41), null, null, null, null, null,
                    null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title01");
            assertThatThrownBy(() -> new UserSecurityDto(null, null, "D".repeat(9), null, null, null, null,
                    null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currentDate");
            assertThatThrownBy(() -> new UserSecurityDto(null, null, null, "P".repeat(9), null, null, null,
                    null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("programName");
            assertThatThrownBy(() -> new UserSecurityDto(null, null, null, null, "T".repeat(41), null, null,
                    null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title02");
            assertThatThrownBy(() -> new UserSecurityDto(null, null, null, null, null, "T".repeat(9), null,
                    null, List.of(), null))
                    .as("CURTIMEI is X(8) on this map even though COSGN00.CPY:L54 declares X(9)")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currentTime");
            assertThatThrownBy(() -> new UserSecurityDto(null, null, null, null, null, null, "N".repeat(9),
                    null, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pageNumber");
            assertThatThrownBy(() -> row(null, null, "F".repeat(21), null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstName");
            assertThatThrownBy(() -> row(null, null, null, "L".repeat(21), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lastName");
        }

        @Test
        @DisplayName("absence, emptiness and marking survive the width guard untouched")
        void absenceEmptinessAndMarkingSurviveTheWidthGuard() {
            assertThatCode(() -> new UserSecurityDto(null, null, null, null, null, null, null, null,
                    List.of(row(null, null, null, null, null)), null))
                    .as("a bound on length must never turn into a requirement to be present")
                    .doesNotThrowAnyException();

            final UserRow empties = row("", "", "", "", "");
            assertThat(empties.userId()).isEmpty();
            assertThat(empties.selectionFlag()).isEmpty();

            final UserRow marked = row(BLANK, "        ", " ".repeat(20), LOW_VALUES, BLANK);
            assertThat(marked.userId()).isEqualTo("        ").hasSize(8);
            assertThat(marked.firstName()).hasSize(20);
            assertThat(marked.lastName())
                    .as("a low-values marker is one byte, so it is within X(20) and passes untouched")
                    .isEqualTo(LOW_VALUES);
        }
    }

    /**
     * Value semantics across the defensive copy.
     */
    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two structurally identical pages are equal and share a hash code")
        void twoStructurallyIdenticalPagesAreEqual() {
            final UserSecurityDto left = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));
            final UserSecurityDto right = pageOf(seededRows(UserSecurityDto.PAGE_SIZE));

            assertThat(left)
                    .as("the defensive copy must not break the record's value semantics")
                    .isEqualTo(right)
                    .hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("pages differing in one row are not equal")
        void pagesDifferingInOneRowAreNotEqual() {
            final UserSecurityDto unselected = pageOf(List.of(
                    row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0), SEEDED_LAST_NAMES.get(0),
                            "A")));
            final UserSecurityDto selected = pageOf(List.of(
                    row("S", SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0), SEEDED_LAST_NAMES.get(0),
                            "A")));

            assertThat(unselected).isNotEqualTo(selected);
        }

        @Test
        @DisplayName("a page differing only in row order is not equal, because position is meaningful")
        void aPageDifferingOnlyInRowOrderIsNotEqual() {
            final UserRow admin = row(BLANK, SEEDED_USER_IDS.get(0), SEEDED_FIRST_NAMES.get(0),
                    SEEDED_LAST_NAMES.get(0), "A");
            final UserRow user = row(BLANK, SEEDED_USER_IDS.get(5), SEEDED_FIRST_NAMES.get(5),
                    SEEDED_LAST_NAMES.get(5), "U");

            assertThat(pageOf(List.of(admin, user))).isNotEqualTo(pageOf(List.of(user, admin)));
        }

        @Test
        @DisplayName("a page whose error line differs is not equal, so absent and blank do not collapse")
        void aPageWhoseErrorLineDiffersIsNotEqual() {
            final UserSecurityDto succeeded = pageOf(List.of());
            final UserSecurityDto failed = new UserSecurityDto(TRANSACTION_NAME, "User List", headerDate(),
                    PROGRAM_NAME, "CardDemo", headerTime(), PAGE_ONE, BLANK, List.of(), BLANK);

            assertThat(succeeded.errorMessage()).isNull();
            assertThat(failed.errorMessage()).isEqualTo(BLANK);
            assertThat(succeeded).isNotEqualTo(failed);
        }
    }

    /**
     * The eleven-field user-delete screen of {@code app/cpy-bms/COUSR03.CPY}, which transaction {@code CU03}
     * paints for confirmation before a delete. It is a sibling contract to the list, not a one-row list.
     */
    @Nested
    @DisplayName("Delete screen: 11 input fields of COUSR3AI")
    class DeleteScreen {

        /** The eleven component names in map order, matching :24 through :84. */
        private static final List<String> MAP_ORDER = List.of(
                "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
                "userIdInput", "firstName", "lastName", "userType", "errorMessage");

        /** A fully populated screen, every field at or under its declared width. */
        private static UserDeleteScreen populated() {
            return new UserDeleteScreen("CU03", "Delete User", "01/02/26", "COUSR03C", "CardDemo",
                    "10:20:30", "ADMNUSR1", "FNAMEAA1", "LNM1", "A", null);
        }

        @Test
        @DisplayName("declares exactly the eleven map fields, in the order the copybook declares them")
        void declaresExactlyTheElevenMapFieldsInMapOrder() {
            assertThat(componentNamesOf(UserDeleteScreen.class))
                    .as("app/cpy-bms/COUSR03.CPY declares eleven 02-level input fields between the "
                            + "01 COUSR3AI group at :17 and the 01 COUSR3AO redefinition at :85, at :24, "
                            + ":30, :36, :42, :48, :54, :60, :66, :72, :78 and :84")
                    .containsExactlyElementsOf(MAP_ORDER);
        }

        @Test
        @DisplayName("the field-count arithmetic is derived from its parts and totals eleven")
        void theFieldCountArithmeticTotalsEleven() {
            assertThat(UserDeleteScreen.HEADER_FIELD_COUNT).isEqualTo(6);
            assertThat(UserDeleteScreen.DETAIL_FIELD_COUNT)
                    .as("four, not the list row's five: this screen declares no selection marker")
                    .isEqualTo(4)
                    .isEqualTo(UserSecurityDto.ROW_FIELD_COUNT - 1);
            assertThat(UserDeleteScreen.MAP_FIELD_COUNT)
                    .isEqualTo(11)
                    .isEqualTo(UserDeleteScreen.HEADER_FIELD_COUNT
                            + UserDeleteScreen.DETAIL_FIELD_COUNT + 1)
                    .isEqualTo(MAP_ORDER.size());
            assertThat(UserDeleteScreen.MAP_FIELD_COUNT)
                    .as("eleven fields on COUSR03.CPY against fifty-nine on COUSR00.CPY - two different "
                            + "maps, two different facts, so the constants must not be conflated")
                    .isNotEqualTo(UserSecurityDto.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("is not a one-row list: it declares no selector and reuses no row type")
        void isNotAOneRowList() {
            assertThat(componentNamesOf(UserDeleteScreen.class))
                    .as("app/cpy-bms/COUSR03.CPY declares no SEL field: the user being deleted is the one "
                            + "the operator keyed, so a selector here would be an invented field")
                    .doesNotContain("selectionFlag");

            assertThat(UserDeleteScreen.class.getInterfaces())
                    .as("it shares no supertype with UserRow, so the two contracts cannot drift together")
                    .isEmpty();
            assertThat(componentNamesOf(UserRow.class))
                    .as("the list row generates USRIDnnI, FNAMEnnI, LNAMEnnI, UTYPEnnI plus a selector, "
                            + "against this map's USRIDINI, FNAMEI, LNAMEI and USRTYPEI")
                    .hasSize(UserSecurityDto.ROW_FIELD_COUNT)
                    .contains("selectionFlag");
        }

        @Test
        @DisplayName("carries each field at its declared PIC width, byte-exactly")
        void carriesEachFieldAtItsDeclaredWidth() {
            final UserDeleteScreen screen = new UserDeleteScreen(
                    "X".repeat(UserSecurityDto.TRANSACTION_NAME_WIDTH),
                    "X".repeat(UserSecurityDto.TITLE_WIDTH),
                    "X".repeat(UserSecurityDto.DATE_WIDTH),
                    "X".repeat(UserSecurityDto.PROGRAM_NAME_WIDTH),
                    "X".repeat(UserSecurityDto.TITLE_WIDTH),
                    "X".repeat(UserSecurityDto.TIME_WIDTH),
                    "X".repeat(UserSecurityDto.USER_ID_WIDTH),
                    "X".repeat(UserSecurityDto.NAME_WIDTH),
                    "X".repeat(UserSecurityDto.NAME_WIDTH),
                    "X".repeat(UserSecurityDto.USER_TYPE_WIDTH),
                    "X".repeat(UserSecurityDto.ERROR_MESSAGE_WIDTH));

            assertThat(screen.transactionName()).hasSize(4);
            assertThat(screen.title01()).hasSize(40);
            assertThat(screen.currentDate()).hasSize(8);
            assertThat(screen.programName()).hasSize(8);
            assertThat(screen.title02()).hasSize(40);
            assertThat(screen.currentTime())
                    .as("CURTIMEI is X(8) at COUSR03.CPY:54, matching COUSR00.CPY:54 and differing from "
                            + "the X(9) of COSGN00.CPY:54")
                    .hasSize(8);
            assertThat(screen.userIdInput()).hasSize(8);
            assertThat(screen.firstName()).hasSize(20);
            assertThat(screen.lastName()).hasSize(20);
            assertThat(screen.userType()).hasSize(1);
            assertThat(screen.errorMessage()).hasSize(78);
        }

        @Test
        @DisplayName("refuses every over-wide field rather than truncating it")
        void refusesEveryOverWideField() {
            assertThatThrownBy(() -> new UserDeleteScreen("TOOLONG", null, null, null, null, null, null,
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionName");
            assertThatThrownBy(() -> new UserDeleteScreen(null, "T".repeat(41), null, null, null, null, null,
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title01");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, "D".repeat(9), null, null, null, null,
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currentDate");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, "P".repeat(9), null, null, null,
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("programName");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, "T".repeat(41), null, null,
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title02");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, null, "T".repeat(9), null,
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currentTime");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, null, null, "ADMNUSR19",
                    null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userIdInput");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, null, null, null,
                    "F".repeat(21), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstName");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, null, null, null, null,
                    "L".repeat(21), null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lastName");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, null, null, null, null,
                    null, "AU", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userType");
            assertThatThrownBy(() -> new UserDeleteScreen(null, null, null, null, null, null, null, null,
                    null, null, "E".repeat(79)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");
        }

        @Test
        @DisplayName("absent, empty and marked stay distinct, and every field may be absent")
        void absentEmptyAndMarkedStayDistinct() {
            assertThatCode(() -> new UserDeleteScreen(null, null, null, null, null, null, null, null, null,
                    null, null))
                    .as("a screen painted before the operator keyed anything has every field absent")
                    .doesNotThrowAnyException();

            final UserDeleteScreen absent = new UserDeleteScreen(null, null, null, null, null, null, null,
                    null, null, null, null);
            final UserDeleteScreen empty = new UserDeleteScreen(null, null, null, null, null, null, "",
                    null, null, null, null);
            final UserDeleteScreen marked = new UserDeleteScreen(null, null, null, null, null, null, BLANK,
                    null, null, null, null);

            assertThat(absent.userIdInput()).isNull();
            assertThat(empty.userIdInput()).isEmpty();
            assertThat(marked.userIdInput()).isEqualTo(BLANK);
            assertThat(absent).isNotEqualTo(empty);
            assertThat(empty).isNotEqualTo(marked);
        }

        @Test
        @DisplayName("carries no credential field and declares no credential accessor")
        void carriesNoCredentialField() {
            assertThat(componentNamesOf(UserDeleteScreen.class))
                    .as("app/cpy-bms/COUSR03.CPY declares no password field anywhere across its eleven "
                            + "fields; only COUSR01.CPY:78 and COUSR02.CPY:78 do")
                    .noneMatch(UserSecurityDtoTest::isCredentialAccessorName);

            for (final Method declared : UserDeleteScreen.class.getDeclaredMethods()) {
                assertThat(isCredentialAccessorName(declared.getName()))
                        .as("UserDeleteScreen.%s() must not be able to read a credential",
                                declared.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("declares no self-delete guard, because app/cbl/COUSR03C.cbl declares none")
        void declaresNoSelfDeleteGuard() {
            final List<String> members = new ArrayList<>(componentNamesOf(UserDeleteScreen.class));
            for (final Method declared : UserDeleteScreen.class.getDeclaredMethods()) {
                members.add(declared.getName());
            }

            assertThat(members)
                    .as("COUSR03C never compares the target identifier against the signed-on identifier, "
                            + "so no field, flag or accessor here may express such a guard - parity is the "
                            + "contract and adding one would be a behaviour change")
                    .noneMatch(name -> {
                        final String folded = name.toLowerCase(Locale.ROOT);
                        return folded.contains("signedon") || folded.contains("selfdelete")
                                || folded.contains("currentuser") || folded.contains("sameuser");
                    });
        }

        @Test
        @DisplayName("its rendering names only the program, never the user it is about to delete")
        void renderingNamesOnlyTheProgram() {
            final String rendering = populated().toString();

            assertThat(rendering)
                    .as("this is the code path where a failure is most likely to be logged, so the three "
                            + "identifying components must not appear")
                    .contains("COUSR03C")
                    .doesNotContain("ADMNUSR1")
                    .doesNotContain("FNAMEAA1")
                    .doesNotContain("LNM1");
        }

        @Test
        @DisplayName("carries no annotation, so no class-level constraint fires unconditionally")
        void carriesNoAnnotation() {
            assertThat(UserDeleteScreen.class.getDeclaredAnnotations())
                    .as("an outbound projection carries no declarative constraint; its widths are enforced "
                            + "in the constructor, where no validator has to be invoked for them to hold")
                    .isEmpty();
        }

        @Test
        @DisplayName("is a value: equal contents are equal and share a hash code")
        void isAValue() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(populated())
                    .isNotEqualTo(new UserDeleteScreen("CU03", "Delete User", "01/02/26", "COUSR03C",
                            "CardDemo", "10:20:30", "ADMNUSR2", "FNAMEAA1", "LNM1", "A", null));
        }
    }
}
