/*
 ******************************************************************
 * Program     : AccountUpdateRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Account-update request; carries the 54 screen fields plus
 *               both snapshot detail groups.
 * Source      : app/cpy-bms/COACTUP.CPY (54 fields) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:669,757 (snapshot groups) @ 7756d89
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.model.dto;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Account-update request payload: the stateless replacement for the CICS
 * pseudo-conversation driven by {@code app/cbl/COACTUPC.cbl}, the 4,236-line
 * account-update program.
 *
 * <p>The object has exactly three top-level parts:</p>
 * <ul>
 *   <li>the <strong>54 screen fields</strong> of the symbolic map
 *       {@code app/cpy-bms/COACTUP.CPY}, in that copybook's declaration order;</li>
 *   <li>{@code oldDetails} &mdash; the snapshot group {@code ACUP-OLD-DETAILS}
 *       declared at {@code app/cbl/COACTUPC.cbl:669};</li>
 *   <li>{@code newDetails} &mdash; the edited group {@code ACUP-NEW-DETAILS}
 *       declared at {@code app/cbl/COACTUPC.cbl:757}.</li>
 * </ul>
 *
 * <h2>Why the request carries a snapshot</h2>
 * <p>The target is stateless: no server-side session, no COMMAREA, no screen
 * state retained between requests. The COBOL program nevertheless detects
 * change by comparing against a snapshot captured when the screen was first
 * populated. With no session, that snapshot has nowhere to live on the server,
 * so the request body must carry it. Both detail groups therefore travel with
 * every request.</p>
 *
 * <h2>Blocker &mdash; the date-of-birth offset asymmetry</h2>
 * <p><strong>Severity: Blocker.</strong> {@code 9700-CHECK-CHANGE-IN-REC}
 * occupies {@code app/cbl/COACTUPC.cbl:4109-4193} and is invoked at
 * {@code :3947-3948}. At {@code :4174-4179} it reads, verbatim:</p>
 * <pre>
 * AND CUST-DOB-YYYY-MM-DD (1:4) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (1:4)
 * AND CUST-DOB-YYYY-MM-DD (6:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)
 * AND CUST-DOB-YYYY-MM-DD (9:2) EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)
 * </pre>
 * <p>The two sides use <strong>different offsets</strong>:</p>
 * <ul>
 *   <li>the live customer field {@code CUST-DOB-YYYY-MM-DD} is
 *       {@code PIC X(10)} and dash-separated
 *       ({@code app/cpy/CVCUS01Y.cpy:19}), so its year, month and day sit at
 *       <strong>1 / 6 / 9</strong>;</li>
 *   <li>the snapshot field {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} is
 *       {@code PIC X(08)} &mdash; compact, no separators, despite the
 *       dash-implying name ({@code app/cbl/COACTUPC.cbl:746}, parts at
 *       {@code :749-751}) &mdash; so its year, month and day sit at
 *       <strong>1 / 5 / 7</strong>. The NEW side mirrors this exactly:
 *       {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code :837} with
 *       parts at {@code :840-842}.</li>
 * </ul>
 * <p>Consequently the snapshot date of birth is stored here in its
 * <strong>compact eight-character {@code yyyymmdd} form on both groups</strong>,
 * and each group exposes 4/2/2 component views
 * ({@code dateOfBirthYear()}, {@code dateOfBirthMonth()},
 * {@code dateOfBirthDay()}) so the service can compare component-wise without
 * substring arithmetic scattered across the codebase. The live, dash-separated
 * ten-character form is never stored into these fields, and the two forms are
 * never compared as whole strings.</p>
 * <p><strong>Failure mode.</strong> A naive whole-string comparison, or storing
 * the dash-separated form in the snapshot, reports a change on every single
 * request, because {@code 2020-01-15} can never equal {@code 20200115}. The
 * change-detection guard then fires unconditionally, every update is rejected
 * with {@code Record changed by some one else. Please review}
 * ({@code app/cbl/COACTUPC.cbl:521-522}), and the account-update endpoint
 * becomes permanently unusable. To make that misuse loud rather than silently
 * wrong, the compact-date component views reject a backing value longer than
 * eight characters with an {@code IllegalArgumentException} that names the
 * field and never echoes the value.</p>
 *
 * <h2>The two comparison regimes</h2>
 * <p><strong>Severity: Medium</strong> &mdash; the specification describes one
 * comparison paragraph. There are two, and they are semantically different.</p>
 * <ul>
 *   <li>{@code 1205-COMPARE-OLD-NEW}, {@code app/cbl/COACTUPC.cbl:1681-1777},
 *       invoked at {@code :1460-1461} and escaping at {@code :1704} and
 *       {@code :1772}, answers <em>"did the user change anything on screen?"</em>
 *       Its operands are NEW versus OLD &mdash; both inside this request body.
 *       It opens with {@code SET NO-CHANGES-FOUND TO TRUE} at {@code :1682};
 *       the outcome flag is {@code 88 CHANGE-HAS-OCCURRED VALUE '1'} at
 *       {@code :170}.</li>
 *   <li>{@code 9700-CHECK-CHANGE-IN-REC}, {@code app/cbl/COACTUPC.cbl:4109-4193},
 *       invoked at {@code :3947-3948}, answers <em>"did someone else change the
 *       record while the user was editing?"</em> Its operands are the live
 *       record versus the OLD snapshot.</li>
 * </ul>
 * <p>The regimes disagree field by field, which is why no shared comparison
 * helper can serve both. Selected contrasts, all verified in the source:
 * active status is upper-cased on both sides at {@code :1685-1688} but compared
 * plainly at {@code :4115}; the money fields are compared as {@code X(12)} text
 * at {@code :1689-1691} and {@code :1695-1696} but through their
 * {@code S9(10)V99} numeric REDEFINES at {@code :4117}, {@code :4119},
 * {@code :4121}, {@code :4123} and {@code :4125}; the three account dates are
 * compared whole at {@code :1692-1694} but as three substrings at
 * {@code :4127-4137}; the account group identifier is
 * {@code FUNCTION UPPER-CASE(FUNCTION TRIM())} at {@code :1697-1700} and
 * {@code FUNCTION LOWER-CASE()} with no trim at {@code :4139-4140} &mdash; the
 * same field, two opposite case functions, in the same program; the postal code
 * is upper-cased and trimmed ending at {@code :1747} but carries no case
 * function at all at {@code :4168}; the two telephone numbers are compared part
 * by part at {@code :1748-1753} but whole as {@code X(15)} at {@code :4169-4170};
 * and the social security number is compared as a whole group at {@code :1754}
 * but through its numeric REDEFINES at {@code :4171}.</p>
 * <p>Six consequences follow for the shape of this object. Each is a place where
 * a tidier design would silently break one of the two regimes.</p>
 * <ul>
 *   <li>(i) the date of birth is compact eight-character on both groups, which
 *       makes {@code 1205}'s whole-string equality correct at {@code :1759-1760}
 *       and {@code 9700}'s 1/5/7 offsets correct at the same time;</li>
 *   <li>(ii) the three account dates are compact {@code X(08)} plus 4/2/2
 *       component views, because {@code 1205} compares them whole while
 *       {@code 9700} compares them as three substrings against the live
 *       dash-separated {@code PIC X(10)} values of
 *       {@code app/cpy/CVACT01Y.cpy:10-12};</li>
 *   <li>(iii) each telephone number carries both representations &mdash; the
 *       whole fifteen-character formatted value and the area, prefix and line
 *       parts &mdash; because {@code 9700} compares the whole {@code X(15)} at
 *       {@code app/cbl/COACTUPC.cbl:4169-4170} while {@code 1205} compares the
 *       parts one at a time at {@code :1748-1753}. The layout is fixed by the
 *       REDEFINES at {@code app/cbl/COACTUPC.cbl:723-731}, whose fillers place a
 *       single byte before the area code, one between area code and prefix, one
 *       between prefix and line number, and two at the end; the literal
 *       separators are not declared there, but every populated value in
 *       {@code app/data/ASCII/custdata.txt} resolves them &mdash; all 100
 *       telephone values across the 50 customer records read exactly
 *       {@code (NNN)NNN-NNNN} followed by two spaces. Both representations are
 *       still carried, because a blank or partially supplied number has no
 *       formatted equivalent to derive from and vice versa, and normalising
 *       either way would destroy one of the two regimes;</li>
 *   <li>(iv) each money field carries both the {@code X(12)} display text and a
 *       {@code BigDecimal}, so the snapshot round-trips the displayed
 *       representation and not merely the value;</li>
 *   <li>(v) the account group identifier is stored raw and untransformed,
 *       because one regime upper-cases it and the other lower-cases it, so only
 *       the raw value serves both;</li>
 *   <li>(vi) there is <strong>no ingest-time normalisation whatsoever</strong>
 *       &mdash; no eager trimming, no eager case folding, no eager padding and
 *       no coercion between {@code null} and the empty string. Any of those
 *       would destroy one regime. Where the service applies a case function it
 *       must pass {@code java.util.Locale#ROOT}, because a Turkish-locale
 *       upper-casing maps {@code i} to a dotted capital and would silently
 *       change comparison outcomes.</li>
 * </ul>
 *
 * <h2>The OLD / NEW social security number asymmetry</h2>
 * <p>{@code oldDetails} carries one flat nine-character field
 * ({@code ACUP-OLD-CUST-SSN-X PIC X(09)}, {@code app/cbl/COACTUPC.cbl:742},
 * with a {@code PIC 9(09)} REDEFINES at {@code :743-744}). {@code newDetails}
 * carries three parts of three, two and four characters
 * ({@code app/cbl/COACTUPC.cbl:830-833}) beneath a group item, with a
 * {@code PIC 9(09)} REDEFINES over them at {@code :834-835}. The asymmetry is
 * modelled exactly as declared: neither side is flattened to match the other.
 * {@code 1205} compares the whole group on both sides at {@code :1754}, which
 * is meaningful only because the NEW parts are contiguous.</p>
 *
 * <h2>Validation</h2>
 * <p>{@code oldDetails} carries <strong>no validation annotation at all</strong>.
 * The entire OLD group contains zero {@code 88}-level condition names
 * ({@code app/cbl/COACTUPC.cbl:669-756}); it is a purely passive snapshot.
 * Constraining it would reject a snapshot the source accepted unconditionally
 * and would make a previously saved record unresubmittable. The field is
 * therefore also not marked for cascading validation.</p>
 * <p>{@code newDetails} carries the constraints and is marked
 * {@code @Valid} so they cascade. The only range in the source is
 * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}
 * ({@code app/cbl/COACTUPC.cbl:848-849}), declared on the NEW side only, and it
 * is enforced on the NEW side only. Note precisely which member that
 * {@code 88} level qualifies: it sits on the NUMERIC REDEFINES
 * {@code ACUP-NEW-CUST-FICO-SCORE} PIC 9(03) at {@code :846-847}, not on the
 * text member {@code ACUP-NEW-CUST-FICO-SCORE-X} PIC X(03) at {@code :845}. The
 * range constraint is therefore carried by {@code newDetails.ficoScoreValue}
 * and the text member carries only its width contract &mdash; the same
 * text-plus-numeric split the five money members use, and for the same reason
 * given in consequence (iv) above. Everything else is an exact width contract
 * taken from the declared PIC clause. No stricter validation is invented: there
 * is no pattern the source lacks, no {@code @NotNull} where the source tolerates
 * blank, no enum binding and no coercion. Account status and the primary card
 * holder indicator stay raw one-character strings, because binding an inbound
 * one-character code to a Java enum would turn an out-of-domain value into a
 * deserialization failure and replace the source's own message with a framework
 * error.</p>
 * <p>The cross-field state-and-postal-code edit is <strong>gated</strong> and is
 * deliberately <em>not</em> modelled as a class-level constraint. The source
 * runs it only when both single-field flags are already valid
 * ({@code app/cbl/COACTUPC.cbl:1664-1669}: the comment
 * {@code * Cross field edits begin here} at {@code :1664}, then
 * {@code IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID} at {@code :1665-1666}
 * guarding {@code PERFORM 1280-EDIT-US-STATE-ZIP-CD} at {@code :1667-1668}).
 * A class-level assertion would fire even when one field is individually
 * invalid, producing a message the source never emits. The gating belongs to
 * the service, which afterwards evaluates
 * {@code IF INPUT-ERROR CONTINUE ELSE SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE}
 * at {@code :1671-1675}. The related single-field edits and their exact labels
 * are {@code 1220-EDIT-YESNO} for the primary card holder
 * ({@code 'Primary Card Holder'}, {@code :1657}),
 * {@code 1245-EDIT-NUM-REQD} for the electronic funds account identifier
 * ({@code 'EFT Account Id'}, {@code :1648}, with
 * {@code MOVE 10 TO WS-EDIT-ALPHANUM-LENGTH} at {@code :1651}),
 * {@code 1260-EDIT-US-PHONE-NUM} ({@code 'Phone Number 2'}, {@code :1640}) and
 * {@code 1280-EDIT-US-STATE-ZIP-CD}.</p>
 * <p>Every constraint message names its field and never quotes the offending
 * value, because this object carries personally identifiable data throughout.</p>
 *
 * <h2>The absent / blank / low-values tri-state</h2>
 * <p>The source models three states, not two. When a screen field holds
 * {@code '*'} or spaces, the program moves {@code LOW-VALUES} &mdash; binary
 * zeros, not spaces &mdash; into the corresponding NEW field. This is verified
 * for the social security number parts at {@code app/cbl/COACTUPC.cbl:1235},
 * {@code :1242} and {@code :1249} (with the ordinary value moved at
 * {@code :1237}, {@code :1244} and {@code :1251}), for the date-of-birth parts
 * at {@code :1256-1261}, {@code :1263-1268} and {@code :1270-1275}, and for the
 * credit score from {@code :1279}.</p>
 * <p>Absent, blank and low-values are therefore three distinguishable states
 * here: {@code null} is never coerced to the empty string, the empty string is
 * never coerced to {@code null}, and neither is ever coerced to a sentinel.
 * This matches {@code app/cpy/CSSETATY.cpy}, the parameterised
 * {@code COPY ... REPLACING} procedure-division template whose model is
 * OK / NOT-OK / BLANK, where BLANK is a state distinct from NOT-OK and
 * additionally stamps {@code '*'} into the screen field, and whose markers fire
 * only on re-entry and never on first display. The message table proves the
 * same three-state model: {@code 88 CRED-LIMIT-IS-BLANK} carries
 * {@code 'Credit Limit must be supplied'}
 * ({@code app/cbl/COACTUPC.cbl:505-506}) while
 * {@code 88 CRED-LIMIT-IS-NOT-VALID} carries
 * {@code 'Credit Limit is not valid'} ({@code :507-508}) &mdash; two different
 * literals for the same field, one for blank and one for invalid. A payload that
 * collapsed blank into invalid could not produce both messages.</p>
 *
 * <h2>Observable outcomes the service reproduces</h2>
 * <p>This object holds no exception type and imports nothing from the exception
 * package; it merely carries {@code informationMessage} and
 * {@code errorMessage}. The outcome flags the REST layer must keep
 * distinguishable are declared at {@code app/cbl/COACTUPC.cbl:665-668}:
 * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'},
 * {@code 88 ACUP-CHANGES-FAILED VALUES 'L', 'F'},
 * {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} and
 * {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}. The message literals the
 * service reproduces character for character are declared at {@code :509-528}:
 * {@code 'Card expiry month must be between 1 and 12'} ({@code :509-510}),
 * {@code 'Invalid card expiry year'} ({@code :511-512}),
 * {@code 'Did not find this account in cards database'} ({@code :513-514}),
 * {@code 'Did not find cards for this search condition'} ({@code :515-516}),
 * {@code 'Could not lock account record for update'} ({@code :517-518}),
 * {@code 'Could not lock customer record for update'} ({@code :519-520}),
 * {@code 'Record changed by some one else. Please review'} ({@code :521-522},
 * where "some one" is two words, sic),
 * {@code 'Update of record failed'} ({@code :523-524}),
 * {@code 'Error reading Card Data File'} ({@code :525-526}) and
 * {@code 'Looks Good.... so far'} ({@code :527-528}, with four dots, sic).</p>
 *
 * <h2>Field-width divergences that must not be unified</h2>
 * <ul>
 *   <li>{@code ACCTSIDI} is {@code PIC X(11)} at
 *       {@code app/cpy-bms/COACTUP.CPY:60} but the identically named field is
 *       numeric {@code PIC 99999999999} at
 *       {@code app/cpy-bms/COACTVW.CPY:60}. Both are cited; neither is
 *       normalised to the other.</li>
 *   <li>{@code CURTIMEI} is {@code PIC X(8)} at
 *       {@code app/cpy-bms/COACTUP.CPY:54} but {@code PIC X(9)} at
 *       {@code app/cpy-bms/COSGN00.CPY:54}. Because the six header fields are
 *       not uniform across the seventeen symbolic maps, no shared header helper
 *       exists and the header fields are declared inline here.</li>
 *   <li>The snapshot postal code is {@code ACUP-OLD-CUST-ADDR-ZIP PIC X(10)}
 *       ({@code app/cbl/COACTUPC.cbl:721}, matching the customer record at
 *       {@code app/cpy/CVCUS01Y.cpy:14}) while the screen field is
 *       {@code ACSZIPCI PIC X(5)} ({@code app/cpy-bms/COACTUP.CPY:246}). The
 *       snapshot follows the record width and the screen field follows the map
 *       width; the divergence is preserved.</li>
 *   <li>The screen decomposes what the account-view map keeps whole: dates into
 *       year, month and day, the social security number into three parts and
 *       each telephone number into three parts. That decomposition difference is
 *       itself the contract, so this payload carries components where
 *       {@code AccountDto} carries whole values.</li>
 *   <li>{@code ACUP-OLD-EXPIRAION-DATE} ({@code app/cbl/COACTUPC.cbl:690}) and
 *       {@code ACUP-NEW-EXPIRAION-DATE} ({@code :778}) are misspelled in the
 *       source, as is {@code ACCT-EXPIRAION-DATE} at
 *       {@code app/cpy/CVACT01Y.cpy:11} (sic, both). The misspelling is
 *       preserved in every citation; the Java member is named
 *       {@code expirationDate}.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>This is the highest personally-identifiable-data payload in the package,
 * three times over: once on the screen fields, once on {@code oldDetails} and
 * once on {@code newDetails}. Social security numbers, dates of birth, both
 * telephone numbers, the government-issued identifier, the electronic funds
 * account identifier and all customer names are present in each of the three.
 * Three defences follow. No {@code toString} is declared anywhere in this file,
 * on the outer type or on either nested group, so the inherited implementation
 * emits nothing but a type name and an identity hash and no accessor is invoked
 * to build a log line. No type here implements {@code java.io.Serializable},
 * because insecure deserialization is a flagged risky pattern and this is the
 * object least suited to it. And no credential or hash member exists, because
 * the account-update map declares none and least privilege forbids adding one.
 * Log-level masking is a second line of defence, not the first: the first is
 * never emitting these values at all.</p>
 *
 * <h2>Consuming this payload</h2>
 * <p><strong>Binding.</strong> The payload arrives as JSON on the request body and is bound member by
 * member. Every readable property is also settable, so the object round-trips through a plain
 * serializer without depending on any framework leniency setting. The compact-date and credit-score
 * component views are derived accessors rather than properties, so they never appear in the wire
 * format. The High-severity finding below records why that invariant is load-bearing.</p>
 * <p><strong>Validation.</strong> Constraints cascade into {@code newDetails} only, because the edited
 * group is the only one the COBOL program constrains; {@code oldDetails} is a passive snapshot and
 * carries none. Validation is triggered by the consuming controller parameter, and cascades through
 * the annotation declared on the {@code newDetails} member.</p>
 * <p><strong>Comparison.</strong> Both regimes described above belong to the consuming service, not to
 * this class; the payload's only job is to make both expressible. Any case function the service
 * applies must pass {@code Locale.ROOT}, because a Turkish-locale upper-case maps {@code i} to a
 * dotted capital and would silently change which updates are accepted.</p>
 * <p><strong>Defaults that must not be changed.</strong> No member is normalised on ingest &mdash; no
 * trim, no case folding, no padding, and no coercion between {@code null} and the empty string &mdash;
 * so the tri-state survives to the service. Every width defaults to the PIC clause of the originating
 * COBOL field. The credit-score range 300 through 850 applies to the numeric member of
 * {@code newDetails} alone.</p>
 * <p><strong>Build and verification.</strong> The class compiles under {@code -Xlint:all -Werror} with
 * documentation checked by {@code -Xdoclint:all}, so an unused import or an unbalanced tag fails the
 * build. Behavioural cover belongs to the model unit tests under
 * {@code src/test/java/com/cardemo/unit/model}, whose mandatory regression asserts that the snapshot
 * component offsets 1/5/7 yield the same year, month and day as offsets 1/6/9 taken from the
 * dash-separated live form.</p>
 *
 * <h2>Error modes</h2>
 * <ul>
 *   <li><strong>Constraint violation.</strong> A screen field or a
 *       {@code newDetails} member wider than its declared PIC clause, or a
 *       credit score outside 300 through 850, produces a Bean Validation
 *       violation whose message names the field and never quotes the value.</li>
 *   <li><strong>Malformed compact date.</strong> Asking a compact-date component
 *       view for its year, month or day when the backing value is longer than
 *       eight characters throws {@code IllegalArgumentException} naming the
 *       field, because the 1/5/7 offsets cannot be applied to a dash-separated
 *       value and silently returning a fragment of a separator would reproduce
 *       the Blocker described above.</li>
 *   <li><strong>Absent or blank input.</strong> Never an error here. A
 *       {@code null} member stays {@code null} and a blank member stays blank,
 *       so the service can still distinguish the source's blank message from its
 *       not-valid message.</li>
 * </ul>
 *
 * <h2>Findings and severities</h2>
 * <p>Classified per the project's output standard, with the remediation each one
 * received in this file. Every claim below was established by direct inspection
 * of the corpus at commit {@code 7756d89}, not inherited from prose.</p>
 * <ul>
 *   <li><strong>Blocker &mdash; the date-of-birth offset asymmetry.</strong> The
 *       live and snapshot dates of birth are compared at different offsets
 *       ({@code app/cbl/COACTUPC.cbl:4174-4179}), because the live field is
 *       {@code PIC X(10)} and dash-separated
 *       ({@code app/cpy/CVCUS01Y.cpy:19}) while the snapshot field is
 *       {@code PIC X(08)} and compact ({@code app/cbl/COACTUPC.cbl:746} and
 *       {@code :837}). Remediation: the snapshot date of birth is stored compact
 *       on both groups, component views expose the 1/5/7 slices, and a backing
 *       value longer than eight characters is rejected outright rather than
 *       sliced into a separator fragment.</li>
 *   <li><strong>High &mdash; a read-only derived property broke JSON
 *       round-tripping.</strong> An earlier revision of this class exposed the
 *       numeric credit score as a computed getter with no matching setter. A
 *       serializer emits such a getter as a property but cannot bind it back, so
 *       a client that returned the representation it had just been given was
 *       rejected with a framework error instead of the source's own message. That
 *       is fatal here specifically, because the whole reason this payload carries
 *       a snapshot is that the client must send it back. Relying on a framework
 *       default that silently discards unknown properties was rejected as an
 *       environment-specific assumption. Remediation: the numeric reading is a
 *       stored, settable member carrying the range constraint, which also proved
 *       the more faithful model &mdash; see the note on
 *       {@code 88 FICO-RANGE-IS-VALID} below. The general invariant now holds and
 *       is asserted: every property this class exposes for reading is also
 *       settable, so any payload it produces can be sent back to it unchanged.
 *       The range constraint sits on the numeric member because
 *       {@code app/cbl/COACTUPC.cbl:848-849} declares the {@code 88} level on the
 *       {@code PIC 9(03)} REDEFINES at {@code :846-847} rather than on the
 *       {@code PIC X(03)} text member at {@code :845}; constraining the text
 *       member would have applied a range the source does not declare there.</li>
 *   <li><strong>Medium &mdash; a second comparison paragraph, undocumented in the
 *       specification.</strong> The specification describes one comparison
 *       paragraph; there are two, and they normalise the same fields
 *       differently. {@code 1205-COMPARE-OLD-NEW}
 *       ({@code app/cbl/COACTUPC.cbl:1681-1777}) asks whether the user changed
 *       anything, comparing NEW against OLD; {@code 9700-CHECK-CHANGE-IN-REC}
 *       ({@code :4109-4193}) asks whether someone else changed the record,
 *       comparing the live record against the OLD snapshot. Remediation: this
 *       payload carries both representations of every field the two regimes read
 *       differently &mdash; text and decimal for money, whole and parts for the
 *       telephone numbers, compact and component for the dates &mdash; and it
 *       normalises nothing, so neither regime has to reconstruct what the other
 *       would have destroyed. The finding belongs in the root decision log at
 *       Medium; no document is created in this package.</li>
 *   <li><strong>Medium &mdash; the input-field census is overstated.</strong> The
 *       specification reports 460 input fields across the seventeen symbolic maps
 *       and 36 for the account-view map. Counting the generated input fields of
 *       every map under {@code app/cpy-bms} at this commit yields
 *       <strong>441</strong> in total and <strong>37</strong> for
 *       {@code app/cpy-bms/COACTVW.CPY}. The figure for this map is unaffected:
 *       {@code app/cpy-bms/COACTUP.CPY} declares <strong>54</strong> input
 *       fields, verified independently, and all 54 are present here. Remediation:
 *       the verified figures are used in this file and the divergence is reported
 *       rather than silently absorbed.</li>
 *   <li><strong>Low &mdash; preserved source misspellings.</strong>
 *       {@code ACUP-OLD-EXPIRAION-DATE} ({@code app/cbl/COACTUPC.cbl:690}),
 *       {@code ACUP-NEW-EXPIRAION-DATE} ({@code :778}) and
 *       {@code ACCT-EXPIRAION-DATE} ({@code app/cpy/CVACT01Y.cpy:11}) are
 *       misspelled in the source, and two message literals read
 *       {@code 'Record changed by some one else. Please review'} and
 *       {@code 'Looks Good.... so far'}. Remediation: all are reproduced exactly
 *       and marked sic; none is corrected, because parity is the contract.</li>
 * </ul>
 *
 * <p><strong>Not available.</strong> One fact this class would otherwise assert
 * from example data cannot be: there is <strong>no populated date-of-birth
 * example anywhere in the ASCII fixtures</strong>. All 50 customer records in
 * {@code app/data/ASCII/custdata.txt} carry ten zero characters in the
 * {@code CUST-DOB-YYYY-MM-DD} field, so the fixtures neither confirm nor refute
 * the dash-separated live form. That form is established structurally instead,
 * and the structural evidence is conclusive: the field is {@code PIC X(10)} at
 * {@code app/cpy/CVCUS01Y.cpy:19}, and {@code 9700-CHECK-CHANGE-IN-REC} reads it
 * at offsets 1, 6 and 9 ({@code app/cbl/COACTUPC.cbl:4174-4179}), which are the
 * year, month and day positions of a ten-character value only when separator
 * bytes occupy positions 5 and 8. To close the gap by example rather than by
 * construction, what is needed is either a customer fixture with a populated date
 * of birth, or the screen-to-record MOVE statements that assemble the live value
 * from the three input components. Neither exists in the corpus at commit
 * {@code 7756d89}. No value is invented here: the compact snapshot form is stored
 * exactly as its eight-character declaration requires, and the dash-separated
 * live form is never stored by this class at all.</p>

 *
 * <h2>Design notes</h2>
 * <p>This is a pure data holder. It performs no comparison, no normalisation, no
 * mapping and no arithmetic; both comparison regimes live in the account-update
 * service, and this object's only job is to make both of them expressible.
 * Component views that slice a stored string are part of that job; a
 * change-detection method would not be. Ordinary members are exposed through
 * conventional JavaBean accessors, whereas derived component views are named
 * without a {@code get} prefix so that they are not treated as bean properties
 * and are never invoked by JSON binding. Money is {@code BigDecimal} only, at
 * the scale of 2 and precision of 12 that {@code PIC S9(10)V99}
 * ({@code app/cpy/CVACT01Y.cpy:7-9,13-14}) dictates; no binary floating-point
 * type appears anywhere in this file, and the service compares amounts with
 * {@code compareTo} rather than {@code equals} and rounds half-even. There are
 * no collection members, so no defensive copying is required and no iteration
 * order can leak into field or row ordering. There are no mutable static
 * members. Both nested groups are declared inside this file rather than as
 * separate compilation units, keeping the package to its budgeted file count.</p>
 */
public class AccountUpdateRequest {

    /**
     * Declared width of every compact snapshot date, from
     * {@code PIC X(08)} at {@code app/cbl/COACTUPC.cbl:684}, {@code :690},
     * {@code :696} and {@code :746}.
     */
    private static final int COMPACT_DATE_LENGTH = 8;

    /** Zero-based start of the year component, COBOL reference-modifier offset 1. */
    private static final int DATE_YEAR_BEGIN = 0;

    /** Zero-based end of the year component; the year is {@code PIC X(4)}. */
    private static final int DATE_YEAR_END = 4;

    /** Zero-based start of the month component, COBOL reference-modifier offset 5. */
    private static final int DATE_MONTH_BEGIN = 4;

    /** Zero-based end of the month component; the month is {@code PIC X(2)}. */
    private static final int DATE_MONTH_END = 6;

    /** Zero-based start of the day component, COBOL reference-modifier offset 7. */
    private static final int DATE_DAY_BEGIN = 6;

    /** Zero-based end of the day component; the day is {@code PIC X(2)}. */
    private static final int DATE_DAY_END = 8;

    /** Lowest credit score {@code 88 FICO-RANGE-IS-VALID} accepts. */
    private static final int FICO_SCORE_MINIMUM = 300;

    /** Highest credit score {@code 88 FICO-RANGE-IS-VALID} accepts. */
    private static final int FICO_SCORE_MAXIMUM = 850;

    /**
     * Extracts one component of a compact eight-character snapshot date without
     * modifying, trimming or case-folding the stored value.
     *
     * <p>The snapshot dates are declared {@code PIC X(08)} and hold
     * {@code yyyymmdd} with no separators, so their year, month and day sit at
     * COBOL offsets 1, 5 and 7 &mdash; not at 1, 6 and 9 where the live
     * dash-separated {@code PIC X(10)} values keep theirs. See the Blocker
     * section of this class's documentation.</p>
     *
     * <p>The method is total with respect to the absent and blank states so that
     * the tri-state described in this class's documentation survives: a
     * {@code null} backing value yields {@code null}, and a backing value too
     * short to reach the requested component yields the empty string. Nothing is
     * fabricated and nothing is padded.</p>
     *
     * <p>A backing value <em>longer</em> than eight characters is rejected
     * rather than sliced, because that is exactly the mistake of storing the
     * live dash-separated form into the compact snapshot field: slicing
     * {@code 2020-01-15} at offset 5 would silently yield a fragment containing
     * a separator, the change-detection guard would then fire on every request,
     * and the endpoint would become permanently unusable. Failing loudly is the
     * only behaviour that cannot be mistaken for correct.</p>
     *
     * @param compactDate the stored eight-character value; may be {@code null}
     * @param beginIndex  zero-based, inclusive start of the component
     * @param endIndex    zero-based, exclusive end of the component
     * @param fieldName   name of the member being read, used in the failure
     *                    message; the offending value is deliberately never
     *                    included because this object carries personally
     *                    identifiable data
     * @return the requested component, {@code null} when the backing value is
     *         absent, or the empty string when the backing value does not reach
     *         the component
     * @throws IllegalArgumentException when the backing value is longer than the
     *         declared eight characters and therefore is not the compact form
     */
    private static String compactDatePart(final String compactDate, final int beginIndex,
            final int endIndex, final String fieldName) {
        if (compactDate == null) {
            return null;
        }
        if (compactDate.length() > COMPACT_DATE_LENGTH) {
            throw new IllegalArgumentException(fieldName
                    + " must hold at most " + COMPACT_DATE_LENGTH
                    + " characters, because app/cbl/COACTUPC.cbl declares the snapshot date as"
                    + " PIC X(08) holding yyyymmdd with no separators; the component offsets"
                    + " 1/5/7 cannot be applied to a longer value. The offending value is not"
                    + " echoed because this request carries personally identifiable data.");
        }
        if (compactDate.length() <= beginIndex) {
            return "";
        }
        return compactDate.substring(beginIndex, Math.min(endIndex, compactDate.length()));
    }

    // ----------------------------------------------------------------
    // Screen fields, in the declaration order of app/cpy-bms/COACTUP.CPY.
    // All 54 of them, including the three ordering quirks the map declares:
    // the state code between the two address lines, the city after the
    // postal code, and the government-issued identifier interleaved between
    // the two telephone numbers.
    // ----------------------------------------------------------------

    /**
     * {@code TRNNAMEI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:24}. Transaction identifier echoed into the
     * screen header.
     */
    @Size(max = 4,
            message = "transactionName must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:24)")
    private String transactionName;

    /**
     * {@code TITLE01I} PIC X(40) &mdash; {@code app/cpy-bms/COACTUP.CPY:30}. First header title line.
     */
    @Size(max = 40,
            message = "title01 must not exceed its declared width of 40 characters"
                    + " (app/cpy-bms/COACTUP.CPY:30)")
    private String title01;

    /**
     * {@code CURDATEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:36}. Header date as rendered on the screen.
     */
    @Size(max = 8,
            message = "currentDate must not exceed its declared width of 8 characters"
                    + " (app/cpy-bms/COACTUP.CPY:36)")
    private String currentDate;

    /**
     * {@code PGMNAMEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:42}. Name of the program that produced the
     * screen.
     */
    @Size(max = 8,
            message = "programName must not exceed its declared width of 8 characters"
                    + " (app/cpy-bms/COACTUP.CPY:42)")
    private String programName;

    /**
     * {@code TITLE02I} PIC X(40) &mdash; {@code app/cpy-bms/COACTUP.CPY:48}. Second header title line.
     */
    @Size(max = 40,
            message = "title02 must not exceed its declared width of 40 characters"
                    + " (app/cpy-bms/COACTUP.CPY:48)")
    private String title02;

    /**
     * {@code CURTIMEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:54}. Header time as rendered on the screen.
     * Eight characters here; the sign-on map declares nine at {@code app/cpy-bms/COSGN00.CPY:54}.
     */
    @Size(max = 8,
            message = "currentTime must not exceed its declared width of 8 characters"
                    + " (app/cpy-bms/COACTUP.CPY:54)")
    private String currentTime;

    /**
     * {@code ACCTSIDI} PIC X(11) &mdash; {@code app/cpy-bms/COACTUP.CPY:60}. Account identifier. Text here; the
     * identically named field is numeric {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60}.
     */
    @Size(max = 11,
            message = "accountId must not exceed its declared width of 11 characters"
                    + " (app/cpy-bms/COACTUP.CPY:60)")
    private String accountId;

    /**
     * {@code ACSTTUSI} PIC X(1) &mdash; {@code app/cpy-bms/COACTUP.CPY:66}. Account active status, a raw
     * one-character code and never an enum.
     */
    @Size(max = 1,
            message = "accountStatus must not exceed its declared width of 1 character"
                    + " (app/cpy-bms/COACTUP.CPY:66)")
    private String accountStatus;

    /**
     * {@code OPNYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:72}. Year component of the account open
     * date.
     */
    @Size(max = 4,
            message = "openDateYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:72)")
    private String openDateYear;

    /**
     * {@code OPNMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:78}. Month component of the account open
     * date.
     */
    @Size(max = 2,
            message = "openDateMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:78)")
    private String openDateMonth;

    /**
     * {@code OPNDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:84}. Day component of the account open date.
     */
    @Size(max = 2,
            message = "openDateDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:84)")
    private String openDateDay;

    /**
     * {@code ACRDLIMI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:90}. Credit limit as displayed. Fifteen
     * characters on the screen against the twelve-character snapshot form at {@code app/cbl/COACTUPC.cbl:678}.
     */
    @Size(max = 15,
            message = "creditLimit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:90)")
    private String creditLimit;

    /**
     * {@code EXPYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:96}. Year component of the account expiry
     * date.
     */
    @Size(max = 4,
            message = "expiryDateYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:96)")
    private String expiryDateYear;

    /**
     * {@code EXPMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:102}. Month component of the account expiry
     * date.
     */
    @Size(max = 2,
            message = "expiryDateMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:102)")
    private String expiryDateMonth;

    /**
     * {@code EXPDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:108}. Day component of the account expiry
     * date.
     */
    @Size(max = 2,
            message = "expiryDateDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:108)")
    private String expiryDateDay;

    /**
     * {@code ACSHLIMI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:114}. Cash credit limit as displayed.
     */
    @Size(max = 15,
            message = "cashCreditLimit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:114)")
    private String cashCreditLimit;

    /**
     * {@code RISYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:120}. Year component of the account reissue
     * date.
     */
    @Size(max = 4,
            message = "reissueDateYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:120)")
    private String reissueDateYear;

    /**
     * {@code RISMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:126}. Month component of the account reissue
     * date.
     */
    @Size(max = 2,
            message = "reissueDateMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:126)")
    private String reissueDateMonth;

    /**
     * {@code RISDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:132}. Day component of the account reissue
     * date.
     */
    @Size(max = 2,
            message = "reissueDateDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:132)")
    private String reissueDateDay;

    /**
     * {@code ACURBALI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:138}. Current balance as displayed.
     */
    @Size(max = 15,
            message = "currentBalance must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:138)")
    private String currentBalance;

    /**
     * {@code ACRCYCRI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:144}. Current cycle credit as displayed.
     */
    @Size(max = 15,
            message = "currentCycleCredit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:144)")
    private String currentCycleCredit;

    /**
     * {@code AADDGRPI} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:150}. Account group identifier, carried raw
     * because the two comparison regimes case-fold it in opposite directions, upper at {@code
     * app/cbl/COACTUPC.cbl:1697-1700} and lower at {@code :4139-4140}.
     */
    @Size(max = 10,
            message = "accountGroupId must not exceed its declared width of 10 characters"
                    + " (app/cpy-bms/COACTUP.CPY:150)")
    private String accountGroupId;

    /**
     * {@code ACRCYDBI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:156}. Current cycle debit as displayed. The
     * source lets this accumulator hold negative amounts, so no sign normalisation is applied.
     */
    @Size(max = 15,
            message = "currentCycleDebit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:156)")
    private String currentCycleDebit;

    /**
     * {@code ACSTNUMI} PIC X(9) &mdash; {@code app/cpy-bms/COACTUP.CPY:162}. Customer identifier.
     */
    @Size(max = 9,
            message = "customerId must not exceed its declared width of 9 characters"
                    + " (app/cpy-bms/COACTUP.CPY:162)")
    private String customerId;

    /**
     * {@code ACTSSN1I} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:168}. First part of the social security
     * number. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1235}.
     */
    @Size(max = 3,
            message = "customerSsnPart1 must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:168)")
    private String customerSsnPart1;

    /**
     * {@code ACTSSN2I} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:174}. Second part of the social security
     * number. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1242}.
     */
    @Size(max = 2,
            message = "customerSsnPart2 must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:174)")
    private String customerSsnPart2;

    /**
     * {@code ACTSSN3I} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:180}. Third part of the social security
     * number. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1249}.
     */
    @Size(max = 4,
            message = "customerSsnPart3 must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:180)")
    private String customerSsnPart3;

    /**
     * {@code DOBYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:186}. Year component of the date of birth.
     * Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1258}.
     */
    @Size(max = 4,
            message = "dateOfBirthYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:186)")
    private String dateOfBirthYear;

    /**
     * {@code DOBMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:192}. Month component of the date of birth.
     * Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1265}.
     */
    @Size(max = 2,
            message = "dateOfBirthMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:192)")
    private String dateOfBirthMonth;

    /**
     * {@code DOBDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:198}. Day component of the date of birth.
     * Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1272}.
     */
    @Size(max = 2,
            message = "dateOfBirthDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:198)")
    private String dateOfBirthDay;

    /**
     * {@code ACSTFCOI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:204}. Credit score as keyed. Blank or a
     * single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1281}.
     */
    @Size(max = 3,
            message = "customerFicoScore must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:204)")
    private String customerFicoScore;

    /**
     * {@code ACSFNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:210}. Customer first name.
     */
    @Size(max = 25,
            message = "customerFirstName must not exceed its declared width of 25 characters"
                    + " (app/cpy-bms/COACTUP.CPY:210)")
    private String customerFirstName;

    /**
     * {@code ACSMNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:216}. Customer middle name.
     */
    @Size(max = 25,
            message = "customerMiddleName must not exceed its declared width of 25 characters"
                    + " (app/cpy-bms/COACTUP.CPY:216)")
    private String customerMiddleName;

    /**
     * {@code ACSLNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:222}. Customer last name.
     */
    @Size(max = 25,
            message = "customerLastName must not exceed its declared width of 25 characters"
                    + " (app/cpy-bms/COACTUP.CPY:222)")
    private String customerLastName;

    /**
     * {@code ACSADL1I} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:228}. First address line.
     */
    @Size(max = 50,
            message = "addressLine1 must not exceed its declared width of 50 characters"
                    + " (app/cpy-bms/COACTUP.CPY:228)")
    private String addressLine1;

    /**
     * {@code ACSSTTEI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:234}. State code. The source declares it
     * between the two address lines and that order is preserved.
     */
    @Size(max = 2,
            message = "addressStateCode must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:234)")
    private String addressStateCode;

    /**
     * {@code ACSADL2I} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:240}. Second address line.
     */
    @Size(max = 50,
            message = "addressLine2 must not exceed its declared width of 50 characters"
                    + " (app/cpy-bms/COACTUP.CPY:240)")
    private String addressLine2;

    /**
     * {@code ACSZIPCI} PIC X(5) &mdash; {@code app/cpy-bms/COACTUP.CPY:246}. Postal code. Five characters on the
     * screen against the ten-character snapshot form at {@code app/cbl/COACTUPC.cbl:721} and the record form at
     * {@code app/cpy/CVCUS01Y.cpy:14}.
     */
    @Size(max = 5,
            message = "addressZip must not exceed its declared width of 5 characters"
                    + " (app/cpy-bms/COACTUP.CPY:246)")
    private String addressZip;

    /**
     * {@code ACSCITYI} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:252}. City, declared after the postal code
     * in the source and kept in that order. It corresponds to the third address line of the snapshot groups, {@code
     * ACUP-OLD-CUST-ADDR-LINE-3} at {@code app/cbl/COACTUPC.cbl:718}.
     */
    @Size(max = 50,
            message = "addressCity must not exceed its declared width of 50 characters"
                    + " (app/cpy-bms/COACTUP.CPY:252)")
    private String addressCity;

    /**
     * {@code ACSCTRYI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:258}. Country code.
     */
    @Size(max = 3,
            message = "addressCountryCode must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:258)")
    private String addressCountryCode;

    /**
     * {@code ACSPH1AI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:264}. Area code of the first telephone
     * number.
     */
    @Size(max = 3,
            message = "phone1AreaCode must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:264)")
    private String phone1AreaCode;

    /**
     * {@code ACSPH1BI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:270}. Prefix of the first telephone number.
     */
    @Size(max = 3,
            message = "phone1Prefix must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:270)")
    private String phone1Prefix;

    /**
     * {@code ACSPH1CI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:276}. Line number of the first telephone
     * number.
     */
    @Size(max = 4,
            message = "phone1LineNumber must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:276)")
    private String phone1LineNumber;

    /**
     * {@code ACSGOVTI} PIC X(20) &mdash; {@code app/cpy-bms/COACTUP.CPY:282}. Government-issued identifier. The
     * source interleaves it between the two telephone numbers and that order is preserved.
     */
    @Size(max = 20,
            message = "governmentIssuedId must not exceed its declared width of 20 characters"
                    + " (app/cpy-bms/COACTUP.CPY:282)")
    private String governmentIssuedId;

    /**
     * {@code ACSPH2AI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:288}. Area code of the second telephone
     * number.
     */
    @Size(max = 3,
            message = "phone2AreaCode must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:288)")
    private String phone2AreaCode;

    /**
     * {@code ACSPH2BI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:294}. Prefix of the second telephone number.
     */
    @Size(max = 3,
            message = "phone2Prefix must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:294)")
    private String phone2Prefix;

    /**
     * {@code ACSPH2CI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:300}. Line number of the second telephone
     * number.
     */
    @Size(max = 4,
            message = "phone2LineNumber must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:300)")
    private String phone2LineNumber;

    /**
     * {@code ACSEFTCI} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:306}. Electronic funds transfer account
     * identifier, edited by {@code 1245-EDIT-NUM-REQD} under the label {@code 'EFT Account Id'} at {@code
     * app/cbl/COACTUPC.cbl:1648}.
     */
    @Size(max = 10,
            message = "eftAccountId must not exceed its declared width of 10 characters"
                    + " (app/cpy-bms/COACTUP.CPY:306)")
    private String eftAccountId;

    /**
     * {@code ACSPFLGI} PIC X(1) &mdash; {@code app/cpy-bms/COACTUP.CPY:312}. Primary card holder indicator, a raw
     * one-character code edited by {@code 1220-EDIT-YESNO} under the label {@code 'Primary Card Holder'} at {@code
     * app/cbl/COACTUPC.cbl:1657}.
     */
    @Size(max = 1,
            message = "primaryCardHolderIndicator must not exceed its declared width of 1 character"
                    + " (app/cpy-bms/COACTUP.CPY:312)")
    private String primaryCardHolderIndicator;

    /**
     * {@code INFOMSGI} PIC X(45) &mdash; {@code app/cpy-bms/COACTUP.CPY:318}. Informational message line, the
     * carrier for literals such as {@code 'Looks Good.... so far'} ({@code app/cbl/COACTUPC.cbl:527-528}).
     */
    @Size(max = 45,
            message = "informationMessage must not exceed its declared width of 45 characters"
                    + " (app/cpy-bms/COACTUP.CPY:318)")
    private String informationMessage;

    /**
     * {@code ERRMSGI} PIC X(78) &mdash; {@code app/cpy-bms/COACTUP.CPY:324}. Error message line, the carrier for
     * literals such as {@code 'Record changed by some one else. Please review'} ({@code
     * app/cbl/COACTUPC.cbl:521-522}).
     */
    @Size(max = 78,
            message = "errorMessage must not exceed its declared width of 78 characters"
                    + " (app/cpy-bms/COACTUP.CPY:324)")
    private String errorMessage;

    /**
     * {@code FKEYSI} PIC X(21) &mdash; {@code app/cpy-bms/COACTUP.CPY:330}. Function key legend line.
     */
    @Size(max = 21,
            message = "functionKeys must not exceed its declared width of 21 characters"
                    + " (app/cpy-bms/COACTUP.CPY:330)")
    private String functionKeys;

    /**
     * {@code FKEY05I} PIC X(7) &mdash; {@code app/cpy-bms/COACTUP.CPY:336}. Legend for the fifth function key.
     */
    @Size(max = 7,
            message = "functionKey05 must not exceed its declared width of 7 characters"
                    + " (app/cpy-bms/COACTUP.CPY:336)")
    private String functionKey05;

    /**
     * {@code FKEY12I} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:342}. Legend for the twelfth function key.
     */
    @Size(max = 10,
            message = "functionKey12 must not exceed its declared width of 10 characters"
                    + " (app/cpy-bms/COACTUP.CPY:342)")
    private String functionKey12;

    /**
     * Snapshot of the record as the screen was first populated:
     * {@code ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl:669-756}.
     *
     * <p>Deliberately <strong>not</strong> marked for cascading validation. The
     * OLD group declares zero {@code 88}-level condition names, so it is a
     * purely passive snapshot; constraining it would reject a snapshot the
     * source accepted unconditionally and would make a previously saved record
     * unresubmittable.</p>
     */
    private OldDetails oldDetails;

    /**
     * The edited values: {@code ACUP-NEW-DETAILS} at
     * {@code app/cbl/COACTUPC.cbl:757-849}.
     *
     * <p>Marked {@code @Valid} so the declared width contracts and the
     * 300 through 850 credit-score range of
     * {@code 88 FICO-RANGE-IS-VALID} ({@code app/cbl/COACTUPC.cbl:848-849})
     * cascade into the nested group.</p>
     */
    @Valid
    private NewDetails newDetails;

    /**
     * Creates an empty request. JSON binding populates the members through the
     * accessors below; nothing is defaulted, so an absent member stays absent.
     */
    public AccountUpdateRequest() {
        // Intentionally empty: no member may be defaulted, because absent,
        // blank and low-values are three distinguishable states here.
    }

    // ----------------------------------------------------------------
    // Accessors for the screen fields, in the same order as the
    // declarations above.
    // ----------------------------------------------------------------

    /**
     * Returns {@code TRNNAMEI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:24}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getTransactionName() {
        return transactionName;
    }

    /**
     * Sets {@code TRNNAMEI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:24}.
     *
     * @param transactionName the value to store verbatim; {@code null} and the empty string are retained as the
     *                        distinct states they are
     */
    public void setTransactionName(final String transactionName) {
        this.transactionName = transactionName;
    }

    /**
     * Returns {@code TITLE01I}, PIC X(40) at {@code app/cpy-bms/COACTUP.CPY:30}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets {@code TITLE01I}, PIC X(40) at {@code app/cpy-bms/COACTUP.CPY:30}.
     *
     * @param title01 the value to store verbatim; {@code null} and the empty string are retained as the distinct
     *                states they are
     */
    public void setTitle01(final String title01) {
        this.title01 = title01;
    }

    /**
     * Returns {@code CURDATEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:36}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCurrentDate() {
        return currentDate;
    }

    /**
     * Sets {@code CURDATEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:36}.
     *
     * @param currentDate the value to store verbatim; {@code null} and the empty string are retained as the
     *                    distinct states they are
     */
    public void setCurrentDate(final String currentDate) {
        this.currentDate = currentDate;
    }

    /**
     * Returns {@code PGMNAMEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:42}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getProgramName() {
        return programName;
    }

    /**
     * Sets {@code PGMNAMEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:42}.
     *
     * @param programName the value to store verbatim; {@code null} and the empty string are retained as the
     *                    distinct states they are
     */
    public void setProgramName(final String programName) {
        this.programName = programName;
    }

    /**
     * Returns {@code TITLE02I}, PIC X(40) at {@code app/cpy-bms/COACTUP.CPY:48}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets {@code TITLE02I}, PIC X(40) at {@code app/cpy-bms/COACTUP.CPY:48}.
     *
     * @param title02 the value to store verbatim; {@code null} and the empty string are retained as the distinct
     *                states they are
     */
    public void setTitle02(final String title02) {
        this.title02 = title02;
    }

    /**
     * Returns {@code CURTIMEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:54}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * Sets {@code CURTIMEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:54}.
     *
     * @param currentTime the value to store verbatim; {@code null} and the empty string are retained as the
     *                    distinct states they are
     */
    public void setCurrentTime(final String currentTime) {
        this.currentTime = currentTime;
    }

    /**
     * Returns {@code ACCTSIDI}, PIC X(11) at {@code app/cpy-bms/COACTUP.CPY:60}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets {@code ACCTSIDI}, PIC X(11) at {@code app/cpy-bms/COACTUP.CPY:60}.
     *
     * @param accountId the value to store verbatim; {@code null} and the empty string are retained as the distinct
     *                  states they are
     */
    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns {@code ACSTTUSI}, PIC X(1) at {@code app/cpy-bms/COACTUP.CPY:66}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Sets {@code ACSTTUSI}, PIC X(1) at {@code app/cpy-bms/COACTUP.CPY:66}.
     *
     * @param accountStatus the value to store verbatim; {@code null} and the empty string are retained as the
     *                      distinct states they are
     */
    public void setAccountStatus(final String accountStatus) {
        this.accountStatus = accountStatus;
    }

    /**
     * Returns {@code OPNYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:72}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getOpenDateYear() {
        return openDateYear;
    }

    /**
     * Sets {@code OPNYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:72}.
     *
     * @param openDateYear the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setOpenDateYear(final String openDateYear) {
        this.openDateYear = openDateYear;
    }

    /**
     * Returns {@code OPNMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:78}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getOpenDateMonth() {
        return openDateMonth;
    }

    /**
     * Sets {@code OPNMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:78}.
     *
     * @param openDateMonth the value to store verbatim; {@code null} and the empty string are retained as the
     *                      distinct states they are
     */
    public void setOpenDateMonth(final String openDateMonth) {
        this.openDateMonth = openDateMonth;
    }

    /**
     * Returns {@code OPNDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:84}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getOpenDateDay() {
        return openDateDay;
    }

    /**
     * Sets {@code OPNDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:84}.
     *
     * @param openDateDay the value to store verbatim; {@code null} and the empty string are retained as the
     *                    distinct states they are
     */
    public void setOpenDateDay(final String openDateDay) {
        this.openDateDay = openDateDay;
    }

    /**
     * Returns {@code ACRDLIMI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:90}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets {@code ACRDLIMI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:90}.
     *
     * @param creditLimit the value to store verbatim; {@code null} and the empty string are retained as the
     *                    distinct states they are
     */
    public void setCreditLimit(final String creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns {@code EXPYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:96}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getExpiryDateYear() {
        return expiryDateYear;
    }

    /**
     * Sets {@code EXPYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:96}.
     *
     * @param expiryDateYear the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setExpiryDateYear(final String expiryDateYear) {
        this.expiryDateYear = expiryDateYear;
    }

    /**
     * Returns {@code EXPMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:102}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getExpiryDateMonth() {
        return expiryDateMonth;
    }

    /**
     * Sets {@code EXPMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:102}.
     *
     * @param expiryDateMonth the value to store verbatim; {@code null} and the empty string are retained as the
     *                        distinct states they are
     */
    public void setExpiryDateMonth(final String expiryDateMonth) {
        this.expiryDateMonth = expiryDateMonth;
    }

    /**
     * Returns {@code EXPDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:108}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getExpiryDateDay() {
        return expiryDateDay;
    }

    /**
     * Sets {@code EXPDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:108}.
     *
     * @param expiryDateDay the value to store verbatim; {@code null} and the empty string are retained as the
     *                      distinct states they are
     */
    public void setExpiryDateDay(final String expiryDateDay) {
        this.expiryDateDay = expiryDateDay;
    }

    /**
     * Returns {@code ACSHLIMI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:114}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets {@code ACSHLIMI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:114}.
     *
     * @param cashCreditLimit the value to store verbatim; {@code null} and the empty string are retained as the
     *                        distinct states they are
     */
    public void setCashCreditLimit(final String cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns {@code RISYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:120}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getReissueDateYear() {
        return reissueDateYear;
    }

    /**
     * Sets {@code RISYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:120}.
     *
     * @param reissueDateYear the value to store verbatim; {@code null} and the empty string are retained as the
     *                        distinct states they are
     */
    public void setReissueDateYear(final String reissueDateYear) {
        this.reissueDateYear = reissueDateYear;
    }

    /**
     * Returns {@code RISMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:126}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getReissueDateMonth() {
        return reissueDateMonth;
    }

    /**
     * Sets {@code RISMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:126}.
     *
     * @param reissueDateMonth the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setReissueDateMonth(final String reissueDateMonth) {
        this.reissueDateMonth = reissueDateMonth;
    }

    /**
     * Returns {@code RISDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:132}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getReissueDateDay() {
        return reissueDateDay;
    }

    /**
     * Sets {@code RISDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:132}.
     *
     * @param reissueDateDay the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setReissueDateDay(final String reissueDateDay) {
        this.reissueDateDay = reissueDateDay;
    }

    /**
     * Returns {@code ACURBALI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:138}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Sets {@code ACURBALI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:138}.
     *
     * @param currentBalance the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setCurrentBalance(final String currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * Returns {@code ACRCYCRI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:144}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Sets {@code ACRCYCRI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:144}.
     *
     * @param currentCycleCredit the value to store verbatim; {@code null} and the empty string are retained as the
     *                           distinct states they are
     */
    public void setCurrentCycleCredit(final String currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * Returns {@code AADDGRPI}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:150}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * Sets {@code AADDGRPI}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:150}.
     *
     * @param accountGroupId the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setAccountGroupId(final String accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    /**
     * Returns {@code ACRCYDBI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:156}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Sets {@code ACRCYDBI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:156}.
     *
     * @param currentCycleDebit the value to store verbatim; {@code null} and the empty string are retained as the
     *                          distinct states they are
     */
    public void setCurrentCycleDebit(final String currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * Returns {@code ACSTNUMI}, PIC X(9) at {@code app/cpy-bms/COACTUP.CPY:162}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Sets {@code ACSTNUMI}, PIC X(9) at {@code app/cpy-bms/COACTUP.CPY:162}.
     *
     * @param customerId the value to store verbatim; {@code null} and the empty string are retained as the distinct
     *                   states they are
     */
    public void setCustomerId(final String customerId) {
        this.customerId = customerId;
    }

    /**
     * Returns {@code ACTSSN1I}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:168}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerSsnPart1() {
        return customerSsnPart1;
    }

    /**
     * Sets {@code ACTSSN1I}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:168}.
     *
     * @param customerSsnPart1 the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setCustomerSsnPart1(final String customerSsnPart1) {
        this.customerSsnPart1 = customerSsnPart1;
    }

    /**
     * Returns {@code ACTSSN2I}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:174}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerSsnPart2() {
        return customerSsnPart2;
    }

    /**
     * Sets {@code ACTSSN2I}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:174}.
     *
     * @param customerSsnPart2 the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setCustomerSsnPart2(final String customerSsnPart2) {
        this.customerSsnPart2 = customerSsnPart2;
    }

    /**
     * Returns {@code ACTSSN3I}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:180}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerSsnPart3() {
        return customerSsnPart3;
    }

    /**
     * Sets {@code ACTSSN3I}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:180}.
     *
     * @param customerSsnPart3 the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setCustomerSsnPart3(final String customerSsnPart3) {
        this.customerSsnPart3 = customerSsnPart3;
    }

    /**
     * Returns {@code DOBYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:186}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getDateOfBirthYear() {
        return dateOfBirthYear;
    }

    /**
     * Sets {@code DOBYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:186}.
     *
     * @param dateOfBirthYear the value to store verbatim; {@code null} and the empty string are retained as the
     *                        distinct states they are
     */
    public void setDateOfBirthYear(final String dateOfBirthYear) {
        this.dateOfBirthYear = dateOfBirthYear;
    }

    /**
     * Returns {@code DOBMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:192}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getDateOfBirthMonth() {
        return dateOfBirthMonth;
    }

    /**
     * Sets {@code DOBMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:192}.
     *
     * @param dateOfBirthMonth the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setDateOfBirthMonth(final String dateOfBirthMonth) {
        this.dateOfBirthMonth = dateOfBirthMonth;
    }

    /**
     * Returns {@code DOBDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:198}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getDateOfBirthDay() {
        return dateOfBirthDay;
    }

    /**
     * Sets {@code DOBDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:198}.
     *
     * @param dateOfBirthDay the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setDateOfBirthDay(final String dateOfBirthDay) {
        this.dateOfBirthDay = dateOfBirthDay;
    }

    /**
     * Returns {@code ACSTFCOI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:204}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerFicoScore() {
        return customerFicoScore;
    }

    /**
     * Sets {@code ACSTFCOI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:204}.
     *
     * @param customerFicoScore the value to store verbatim; {@code null} and the empty string are retained as the
     *                          distinct states they are
     */
    public void setCustomerFicoScore(final String customerFicoScore) {
        this.customerFicoScore = customerFicoScore;
    }

    /**
     * Returns {@code ACSFNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:210}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerFirstName() {
        return customerFirstName;
    }

    /**
     * Sets {@code ACSFNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:210}.
     *
     * @param customerFirstName the value to store verbatim; {@code null} and the empty string are retained as the
     *                          distinct states they are
     */
    public void setCustomerFirstName(final String customerFirstName) {
        this.customerFirstName = customerFirstName;
    }

    /**
     * Returns {@code ACSMNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:216}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerMiddleName() {
        return customerMiddleName;
    }

    /**
     * Sets {@code ACSMNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:216}.
     *
     * @param customerMiddleName the value to store verbatim; {@code null} and the empty string are retained as the
     *                           distinct states they are
     */
    public void setCustomerMiddleName(final String customerMiddleName) {
        this.customerMiddleName = customerMiddleName;
    }

    /**
     * Returns {@code ACSLNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:222}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getCustomerLastName() {
        return customerLastName;
    }

    /**
     * Sets {@code ACSLNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:222}.
     *
     * @param customerLastName the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setCustomerLastName(final String customerLastName) {
        this.customerLastName = customerLastName;
    }

    /**
     * Returns {@code ACSADL1I}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:228}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAddressLine1() {
        return addressLine1;
    }

    /**
     * Sets {@code ACSADL1I}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:228}.
     *
     * @param addressLine1 the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setAddressLine1(final String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    /**
     * Returns {@code ACSSTTEI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:234}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAddressStateCode() {
        return addressStateCode;
    }

    /**
     * Sets {@code ACSSTTEI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:234}.
     *
     * @param addressStateCode the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setAddressStateCode(final String addressStateCode) {
        this.addressStateCode = addressStateCode;
    }

    /**
     * Returns {@code ACSADL2I}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:240}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAddressLine2() {
        return addressLine2;
    }

    /**
     * Sets {@code ACSADL2I}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:240}.
     *
     * @param addressLine2 the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setAddressLine2(final String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    /**
     * Returns {@code ACSZIPCI}, PIC X(5) at {@code app/cpy-bms/COACTUP.CPY:246}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets {@code ACSZIPCI}, PIC X(5) at {@code app/cpy-bms/COACTUP.CPY:246}.
     *
     * @param addressZip the value to store verbatim; {@code null} and the empty string are retained as the distinct
     *                   states they are
     */
    public void setAddressZip(final String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * Returns {@code ACSCITYI}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:252}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAddressCity() {
        return addressCity;
    }

    /**
     * Sets {@code ACSCITYI}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:252}.
     *
     * @param addressCity the value to store verbatim; {@code null} and the empty string are retained as the
     *                    distinct states they are
     */
    public void setAddressCity(final String addressCity) {
        this.addressCity = addressCity;
    }

    /**
     * Returns {@code ACSCTRYI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:258}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getAddressCountryCode() {
        return addressCountryCode;
    }

    /**
     * Sets {@code ACSCTRYI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:258}.
     *
     * @param addressCountryCode the value to store verbatim; {@code null} and the empty string are retained as the
     *                           distinct states they are
     */
    public void setAddressCountryCode(final String addressCountryCode) {
        this.addressCountryCode = addressCountryCode;
    }

    /**
     * Returns {@code ACSPH1AI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:264}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPhone1AreaCode() {
        return phone1AreaCode;
    }

    /**
     * Sets {@code ACSPH1AI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:264}.
     *
     * @param phone1AreaCode the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setPhone1AreaCode(final String phone1AreaCode) {
        this.phone1AreaCode = phone1AreaCode;
    }

    /**
     * Returns {@code ACSPH1BI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:270}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPhone1Prefix() {
        return phone1Prefix;
    }

    /**
     * Sets {@code ACSPH1BI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:270}.
     *
     * @param phone1Prefix the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setPhone1Prefix(final String phone1Prefix) {
        this.phone1Prefix = phone1Prefix;
    }

    /**
     * Returns {@code ACSPH1CI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:276}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPhone1LineNumber() {
        return phone1LineNumber;
    }

    /**
     * Sets {@code ACSPH1CI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:276}.
     *
     * @param phone1LineNumber the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setPhone1LineNumber(final String phone1LineNumber) {
        this.phone1LineNumber = phone1LineNumber;
    }

    /**
     * Returns {@code ACSGOVTI}, PIC X(20) at {@code app/cpy-bms/COACTUP.CPY:282}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    /**
     * Sets {@code ACSGOVTI}, PIC X(20) at {@code app/cpy-bms/COACTUP.CPY:282}.
     *
     * @param governmentIssuedId the value to store verbatim; {@code null} and the empty string are retained as the
     *                           distinct states they are
     */
    public void setGovernmentIssuedId(final String governmentIssuedId) {
        this.governmentIssuedId = governmentIssuedId;
    }

    /**
     * Returns {@code ACSPH2AI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:288}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPhone2AreaCode() {
        return phone2AreaCode;
    }

    /**
     * Sets {@code ACSPH2AI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:288}.
     *
     * @param phone2AreaCode the value to store verbatim; {@code null} and the empty string are retained as the
     *                       distinct states they are
     */
    public void setPhone2AreaCode(final String phone2AreaCode) {
        this.phone2AreaCode = phone2AreaCode;
    }

    /**
     * Returns {@code ACSPH2BI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:294}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPhone2Prefix() {
        return phone2Prefix;
    }

    /**
     * Sets {@code ACSPH2BI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:294}.
     *
     * @param phone2Prefix the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setPhone2Prefix(final String phone2Prefix) {
        this.phone2Prefix = phone2Prefix;
    }

    /**
     * Returns {@code ACSPH2CI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:300}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPhone2LineNumber() {
        return phone2LineNumber;
    }

    /**
     * Sets {@code ACSPH2CI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:300}.
     *
     * @param phone2LineNumber the value to store verbatim; {@code null} and the empty string are retained as the
     *                         distinct states they are
     */
    public void setPhone2LineNumber(final String phone2LineNumber) {
        this.phone2LineNumber = phone2LineNumber;
    }

    /**
     * Returns {@code ACSEFTCI}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:306}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets {@code ACSEFTCI}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:306}.
     *
     * @param eftAccountId the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setEftAccountId(final String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns {@code ACSPFLGI}, PIC X(1) at {@code app/cpy-bms/COACTUP.CPY:312}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /**
     * Sets {@code ACSPFLGI}, PIC X(1) at {@code app/cpy-bms/COACTUP.CPY:312}.
     *
     * @param primaryCardHolderIndicator the value to store verbatim; {@code null} and the empty string are retained
     *                                   as the distinct states they are
     */
    public void setPrimaryCardHolderIndicator(final String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
    }

    /**
     * Returns {@code INFOMSGI}, PIC X(45) at {@code app/cpy-bms/COACTUP.CPY:318}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getInformationMessage() {
        return informationMessage;
    }

    /**
     * Sets {@code INFOMSGI}, PIC X(45) at {@code app/cpy-bms/COACTUP.CPY:318}.
     *
     * @param informationMessage the value to store verbatim; {@code null} and the empty string are retained as the
     *                           distinct states they are
     */
    public void setInformationMessage(final String informationMessage) {
        this.informationMessage = informationMessage;
    }

    /**
     * Returns {@code ERRMSGI}, PIC X(78) at {@code app/cpy-bms/COACTUP.CPY:324}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets {@code ERRMSGI}, PIC X(78) at {@code app/cpy-bms/COACTUP.CPY:324}.
     *
     * @param errorMessage the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setErrorMessage(final String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Returns {@code FKEYSI}, PIC X(21) at {@code app/cpy-bms/COACTUP.CPY:330}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getFunctionKeys() {
        return functionKeys;
    }

    /**
     * Sets {@code FKEYSI}, PIC X(21) at {@code app/cpy-bms/COACTUP.CPY:330}.
     *
     * @param functionKeys the value to store verbatim; {@code null} and the empty string are retained as the
     *                     distinct states they are
     */
    public void setFunctionKeys(final String functionKeys) {
        this.functionKeys = functionKeys;
    }

    /**
     * Returns {@code FKEY05I}, PIC X(7) at {@code app/cpy-bms/COACTUP.CPY:336}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getFunctionKey05() {
        return functionKey05;
    }

    /**
     * Sets {@code FKEY05I}, PIC X(7) at {@code app/cpy-bms/COACTUP.CPY:336}.
     *
     * @param functionKey05 the value to store verbatim; {@code null} and the empty string are retained as the
     *                      distinct states they are
     */
    public void setFunctionKey05(final String functionKey05) {
        this.functionKey05 = functionKey05;
    }

    /**
     * Returns {@code FKEY12I}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:342}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied; {@code null}
     *         when absent
     */
    public String getFunctionKey12() {
        return functionKey12;
    }

    /**
     * Sets {@code FKEY12I}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:342}.
     *
     * @param functionKey12 the value to store verbatim; {@code null} and the empty string are retained as the
     *                      distinct states they are
     */
    public void setFunctionKey12(final String functionKey12) {
        this.functionKey12 = functionKey12;
    }

    /**
     * Returns the snapshot of the record as the screen was first populated.
     *
     * @return the {@code ACUP-OLD-DETAILS} group of
     *         {@code app/cbl/COACTUPC.cbl:669}, or {@code null} when the caller
     *         supplied none
     */
    public OldDetails getOldDetails() {
        return oldDetails;
    }

    /**
     * Sets the snapshot of the record as the screen was first populated.
     *
     * @param oldDetails the {@code ACUP-OLD-DETAILS} group to store; retained
     *                   verbatim and never validated, because the source group
     *                   declares no condition names
     */
    public void setOldDetails(final OldDetails oldDetails) {
        this.oldDetails = oldDetails;
    }

    /**
     * Returns the edited values.
     *
     * @return the {@code ACUP-NEW-DETAILS} group of
     *         {@code app/cbl/COACTUPC.cbl:757}, or {@code null} when the caller
     *         supplied none
     */
    public NewDetails getNewDetails() {
        return newDetails;
    }

    /**
     * Sets the edited values.
     *
     * @param newDetails the {@code ACUP-NEW-DETAILS} group to store; its
     *                   declared width contracts and the credit-score range
     *                   cascade from this member
     */
    public void setNewDetails(final NewDetails newDetails) {
        this.newDetails = newDetails;
    }

    /**
     * The snapshot group {@code ACUP-OLD-DETAILS}, declared at
     * {@code app/cbl/COACTUPC.cbl:669-756}: the record exactly as it stood when
     * the screen was first populated.
     *
     * <p>The group is split in the source into an account block at
     * {@code :670-708} and a customer block at {@code :709-756}, and both are
     * reproduced here in declaration order.</p>
     *
     * <p><strong>This group carries no validation annotation whatsoever.</strong>
     * A scan of {@code :669-756} finds zero {@code 88}-level condition names: it
     * is a purely passive snapshot that the source accepts unconditionally.
     * Constraining it would reject a snapshot the source accepted and would make
     * a previously saved record unresubmittable, so the enclosing member is also
     * not marked for cascading validation.</p>
     *
     * <p>Two shapes distinguish this group from its NEW counterpart. The social
     * security number is one flat nine-character field here
     * ({@code ACUP-OLD-CUST-SSN-X PIC X(09)} at {@code :742}) where the NEW group
     * decomposes it into three parts at {@code :830-833}. And the credit score
     * range of {@code 88 FICO-RANGE-IS-VALID} at {@code :848-849} is declared on
     * the NEW side only, so nothing here bounds it.</p>
     *
     * <p>Every member is stored exactly as received. No trimming, case folding
     * or padding is applied, because {@code 1205-COMPARE-OLD-NEW} and
     * {@code 9700-CHECK-CHANGE-IN-REC} normalise the same fields differently and
     * any eager transformation would destroy one of them.</p>
     */
    public static final class OldDetails {

        /**
         * {@code ACUP-OLD-ACCT-ID-X} PIC X(11) &mdash; {@code app/cbl/COACTUPC.cbl:671}. Account identifier. A
         * {@code PIC 9(11)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:672-673}; that numeric
         * reading is a view over this text and is interpreted by the service.
         */
        private String accountId;

        /**
         * {@code ACUP-OLD-ACTIVE-STATUS} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:674}. Account active status,
         * a raw one-character code. Compared with case folding on both sides at {@code
         * app/cbl/COACTUPC.cbl:1685-1688} but plainly at {@code :4115}, so it is stored untransformed.
         */
        private String activeStatus;

        /**
         * {@code ACUP-OLD-CURR-BAL} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:675}. Current balance in its
         * displayed twelve-character form, which is what {@code 1205-COMPARE-OLD-NEW} compares.
         */
        private String currentBalance;

        /**
         * {@code ACUP-OLD-CURR-BAL-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:676-677}. Current balance
         * as the {@code PIC S9(10)V99} REDEFINES declares it, which is what {@code 9700-CHECK-CHANGE-IN-REC}
         * compares.
         */
        private BigDecimal currentBalanceAmount;

        /**
         * {@code ACUP-OLD-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:678}. Credit limit in its
         * displayed twelve-character form.
         */
        private String creditLimit;

        /**
         * {@code ACUP-OLD-CREDIT-LIMIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:679-680}. Credit limit
         * as the {@code PIC S9(10)V99} REDEFINES declares it.
         */
        private BigDecimal creditLimitAmount;

        /**
         * {@code ACUP-OLD-CASH-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:681}. Cash credit limit
         * in its displayed twelve-character form.
         */
        private String cashCreditLimit;

        /**
         * {@code ACUP-OLD-CASH-CREDIT-LIMIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:682-683}. Cash
         * credit limit as the {@code PIC S9(10)V99} REDEFINES declares it.
         */
        private BigDecimal cashCreditLimitAmount;

        /**
         * {@code ACUP-OLD-OPEN-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:684}. Account open date in the
         * compact {@code yyyymmdd} form, with year, month and day parts declared at {@code
         * app/cbl/COACTUPC.cbl:687-689}. Compared whole at {@code :1692} and as three substrings against the live
         * dash-separated {@code PIC X(10)} value at {@code :4127-4129}.
         */
        private String openDate;

        /**
         * {@code ACUP-OLD-EXPIRAION-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:690}. Account expiry date
         * in the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:693-695}. The source
         * member name is misspelled (sic) and the misspelling is preserved in this citation. Compared whole at
         * {@code :1693} and as three substrings at {@code :4131-4133}.
         */
        private String expirationDate;

        /**
         * {@code ACUP-OLD-REISSUE-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:696}. Account reissue date in
         * the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:699-701}. Compared whole at
         * {@code :1694} and as three substrings at {@code :4135-4137}.
         */
        private String reissueDate;

        /**
         * {@code ACUP-OLD-CURR-CYC-CREDIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:702}. Current cycle credit
         * in its displayed twelve-character form.
         */
        private String currentCycleCredit;

        /**
         * {@code ACUP-OLD-CURR-CYC-CREDIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:703-704}. Current
         * cycle credit as the {@code PIC S9(10)V99} REDEFINES declares it.
         */
        private BigDecimal currentCycleCreditAmount;

        /**
         * {@code ACUP-OLD-CURR-CYC-DEBIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:705}. Current cycle debit
         * in its displayed twelve-character form.
         */
        private String currentCycleDebit;

        /**
         * {@code ACUP-OLD-CURR-CYC-DEBIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:706-707}. Current
         * cycle debit as the {@code PIC S9(10)V99} REDEFINES declares it. The source lets this accumulator carry
         * negative amounts, so no sign normalisation is applied here or anywhere on this path.
         */
        private BigDecimal currentCycleDebitAmount;

        /**
         * {@code ACUP-OLD-GROUP-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:708}. Account group identifier,
         * stored raw. {@code 1205-COMPARE-OLD-NEW} compares it upper-cased and trimmed at {@code
         * app/cbl/COACTUPC.cbl:1697-1700} while {@code 9700-CHECK-CHANGE-IN-REC} compares it lower-cased and
         * untrimmed at {@code :4139-4140}; only the untransformed value serves both.
         */
        private String groupId;

        /**
         * {@code ACUP-OLD-CUST-ID-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:710}. Customer identifier. A
         * {@code PIC 9(09)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:711-712}.
         */
        private String customerId;

        /**
         * {@code ACUP-OLD-CUST-FIRST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:713}. Customer first name.
         */
        private String firstName;

        /**
         * {@code ACUP-OLD-CUST-MIDDLE-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:714}. Customer middle
         * name.
         */
        private String middleName;

        /**
         * {@code ACUP-OLD-CUST-LAST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:715}. Customer last name.
         */
        private String lastName;

        /**
         * {@code ACUP-OLD-CUST-ADDR-LINE-1} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:716}. First address line.
         */
        private String addressLine1;

        /**
         * {@code ACUP-OLD-CUST-ADDR-LINE-2} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:717}. Second address
         * line.
         */
        private String addressLine2;

        /**
         * {@code ACUP-OLD-CUST-ADDR-LINE-3} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:718}. Third address line.
         * It corresponds to the city field of the symbolic map, {@code ACSCITYI} at {@code
         * app/cpy-bms/COACTUP.CPY:252}.
         */
        private String addressLine3;

        /**
         * {@code ACUP-OLD-CUST-ADDR-STATE-CD} PIC X(02) &mdash; {@code app/cbl/COACTUPC.cbl:719}. State code. Its
         * consistency with the postal code is edited only when both fields are individually valid, so no
         * cross-field constraint is declared here.
         */
        private String addressStateCode;

        /**
         * {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:720}. Country code.
         */
        private String addressCountryCode;

        /**
         * {@code ACUP-OLD-CUST-ADDR-ZIP} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:721}. Postal code, ten
         * characters here and on the customer record at {@code app/cpy/CVCUS01Y.cpy:14}, against five on the screen
         * field {@code ACSZIPCI} at {@code app/cpy-bms/COACTUP.CPY:246}. Compared upper-cased and trimmed by {@code
         * 1205-COMPARE-OLD-NEW} but with no case function at all at {@code app/cbl/COACTUPC.cbl:4168}.
         */
        private String addressZip;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-1} PIC X(15) &mdash; {@code app/cbl/COACTUPC.cbl:722}. First telephone
         * number as the whole formatted fifteen-character value, which is what {@code 9700-CHECK-CHANGE-IN-REC}
         * compares at {@code app/cbl/COACTUPC.cbl:4169}. The REDEFINES at {@code :723-731} interleaves three filler
         * runs between the parts, so the whole value carries the punctuation and cannot be derived from the parts.
         */
        private String phoneNumber1;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-1A} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:726}. Area code of the
         * first telephone number, compared part by part by {@code 1205-COMPARE-OLD-NEW} at {@code
         * app/cbl/COACTUPC.cbl:1748}.
         */
        private String phoneNumber1AreaCode;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-1B} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:728}. Prefix of the first
         * telephone number.
         */
        private String phoneNumber1Prefix;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-1C} PIC X(4) &mdash; {@code app/cbl/COACTUPC.cbl:730}. Line number of the
         * first telephone number.
         */
        private String phoneNumber1LineNumber;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-2} PIC X(15) &mdash; {@code app/cbl/COACTUPC.cbl:732}. Second telephone
         * number as the whole formatted fifteen-character value, compared whole at {@code
         * app/cbl/COACTUPC.cbl:4170}, with the same filler-interleaved REDEFINES at {@code :733-741}. Edited by
         * {@code 1260-EDIT-US-PHONE-NUM} under the label {@code 'Phone Number 2'} at {@code :1640}.
         */
        private String phoneNumber2;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-2A} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:736}. Area code of the
         * second telephone number, compared part by part at {@code app/cbl/COACTUPC.cbl:1751}.
         */
        private String phoneNumber2AreaCode;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-2B} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:738}. Prefix of the
         * second telephone number.
         */
        private String phoneNumber2Prefix;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-2C} PIC X(4) &mdash; {@code app/cbl/COACTUPC.cbl:740}. Line number of the
         * second telephone number.
         */
        private String phoneNumber2LineNumber;

        /**
         * {@code ACUP-OLD-CUST-SSN-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:742}. Social security number as
         * one flat nine-character field. This is the OLD side of the declared asymmetry: the NEW side decomposes
         * the same nine bytes into three parts at {@code app/cbl/COACTUPC.cbl:830-833}. A {@code PIC 9(09)}
         * REDEFINES overlays these bytes at {@code :743-744}, and that numeric reading is what {@code
         * 9700-CHECK-CHANGE-IN-REC} compares at {@code :4171}. Neither side is flattened or decomposed to match the
         * other.
         */
        private String ssn;

        /**
         * {@code ACUP-OLD-CUST-GOVT-ISSUED-ID} PIC X(20) &mdash; {@code app/cbl/COACTUPC.cbl:745}.
         * Government-issued identifier.
         */
        private String governmentIssuedId;

        /**
         * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:746}. Date of birth in
         * the compact {@code yyyymmdd} form, with parts declared at {@code app/cbl/COACTUPC.cbl:749-751}. Despite
         * the dash-implying name the field is eight characters and carries no separators, so its components sit at
         * offsets 1, 5 and 7 while the live {@code PIC X(10)} value of {@code app/cpy/CVCUS01Y.cpy:19} keeps its
         * own at 1, 6 and 9. This is the Blocker described in this file's class documentation; the dash-separated
         * form must never be stored here.
         */
        private String dateOfBirth;

        /**
         * {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:752}. Electronic funds
         * transfer account identifier. Compared plainly by both regimes, at {@code app/cbl/COACTUPC.cbl:1761-1762}
         * and {@code :4181-4182}, and edited by {@code 1245-EDIT-NUM-REQD} under the label {@code 'EFT Account Id'}
         * at {@code :1648} with a declared length of ten at {@code :1651}.
         */
        private String eftAccountId;

        /**
         * {@code ACUP-OLD-CUST-PRI-HOLDER-IND} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:753}. Primary card
         * holder indicator, a raw one-character code and never an enum. Edited by {@code 1220-EDIT-YESNO} under the
         * label {@code 'Primary Card Holder'} at {@code app/cbl/COACTUPC.cbl:1657}.
         */
        private String primaryCardHolderIndicator;

        /**
         * {@code ACUP-OLD-CUST-FICO-SCORE-X} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:754}. Credit score as
         * text, which is what {@code 1205-COMPARE-OLD-NEW} compares. A {@code PIC 9(03)} REDEFINES overlays the
         * same bytes at {@code app/cbl/COACTUPC.cbl:755-756}, and that numeric reading is what {@code
         * 9700-CHECK-CHANGE-IN-REC} compares at {@code :4186}.
         */
        private String ficoScore;

        /**
         * Creates an empty group. JSON binding populates the members through
         * the accessors below; nothing is defaulted, so an absent member stays
         * absent and a blank member stays blank.
         */
        public OldDetails() {
            // Intentionally empty: absent, blank and low-values are three
            // distinguishable states and none of them may be manufactured here.
        }

        /**
         * Returns {@code ACUP-OLD-ACCT-ID-X}, PIC X(11) at {@code app/cbl/COACTUPC.cbl:671}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Sets {@code ACUP-OLD-ACCT-ID-X}, PIC X(11) at {@code app/cbl/COACTUPC.cbl:671}.
         *
         * @param accountId the value to store verbatim; {@code null} and the empty string are retained as the
         *                  distinct states they are
         */
        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }

        /**
         * Returns {@code ACUP-OLD-ACTIVE-STATUS}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:674}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getActiveStatus() {
            return activeStatus;
        }

        /**
         * Sets {@code ACUP-OLD-ACTIVE-STATUS}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:674}.
         *
         * @param activeStatus the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setActiveStatus(final String activeStatus) {
            this.activeStatus = activeStatus;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-BAL}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:675}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Sets {@code ACUP-OLD-CURR-BAL}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:675}.
         *
         * @param currentBalance the value to store verbatim; {@code null} and the empty string are retained as the
         *                       distinct states they are
         */
        public void setCurrentBalance(final String currentBalance) {
            this.currentBalance = currentBalance;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-BAL-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:676-677}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCurrentBalanceAmount() {
            return currentBalanceAmount;
        }

        /**
         * Sets {@code ACUP-OLD-CURR-BAL-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:676-677}.
         *
         * @param currentBalanceAmount the value to store verbatim; {@code null} and the empty string are retained
         *                             as the distinct states they are
         */
        public void setCurrentBalanceAmount(final BigDecimal currentBalanceAmount) {
            this.currentBalanceAmount = currentBalanceAmount;
        }

        /**
         * Returns {@code ACUP-OLD-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:678}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCreditLimit() {
            return creditLimit;
        }

        /**
         * Sets {@code ACUP-OLD-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:678}.
         *
         * @param creditLimit the value to store verbatim; {@code null} and the empty string are retained as the
         *                    distinct states they are
         */
        public void setCreditLimit(final String creditLimit) {
            this.creditLimit = creditLimit;
        }

        /**
         * Returns {@code ACUP-OLD-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:679-680}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCreditLimitAmount() {
            return creditLimitAmount;
        }

        /**
         * Sets {@code ACUP-OLD-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:679-680}.
         *
         * @param creditLimitAmount the value to store verbatim; {@code null} and the empty string are retained as
         *                          the distinct states they are
         */
        public void setCreditLimitAmount(final BigDecimal creditLimitAmount) {
            this.creditLimitAmount = creditLimitAmount;
        }

        /**
         * Returns {@code ACUP-OLD-CASH-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:681}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCashCreditLimit() {
            return cashCreditLimit;
        }

        /**
         * Sets {@code ACUP-OLD-CASH-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:681}.
         *
         * @param cashCreditLimit the value to store verbatim; {@code null} and the empty string are retained as the
         *                        distinct states they are
         */
        public void setCashCreditLimit(final String cashCreditLimit) {
            this.cashCreditLimit = cashCreditLimit;
        }

        /**
         * Returns {@code ACUP-OLD-CASH-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:682-683}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCashCreditLimitAmount() {
            return cashCreditLimitAmount;
        }

        /**
         * Sets {@code ACUP-OLD-CASH-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:682-683}.
         *
         * @param cashCreditLimitAmount the value to store verbatim; {@code null} and the empty string are retained
         *                              as the distinct states they are
         */
        public void setCashCreditLimitAmount(final BigDecimal cashCreditLimitAmount) {
            this.cashCreditLimitAmount = cashCreditLimitAmount;
        }

        /**
         * Returns {@code ACUP-OLD-OPEN-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:684}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getOpenDate() {
            return openDate;
        }

        /**
         * Sets {@code ACUP-OLD-OPEN-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:684}.
         *
         * @param openDate the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setOpenDate(final String openDate) {
            this.openDate = openDate;
        }

        /**
         * Returns {@code ACUP-OLD-EXPIRAION-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:690}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getExpirationDate() {
            return expirationDate;
        }

        /**
         * Sets {@code ACUP-OLD-EXPIRAION-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:690}.
         *
         * @param expirationDate the value to store verbatim; {@code null} and the empty string are retained as the
         *                       distinct states they are
         */
        public void setExpirationDate(final String expirationDate) {
            this.expirationDate = expirationDate;
        }

        /**
         * Returns {@code ACUP-OLD-REISSUE-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:696}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getReissueDate() {
            return reissueDate;
        }

        /**
         * Sets {@code ACUP-OLD-REISSUE-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:696}.
         *
         * @param reissueDate the value to store verbatim; {@code null} and the empty string are retained as the
         *                    distinct states they are
         */
        public void setReissueDate(final String reissueDate) {
            this.reissueDate = reissueDate;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-CREDIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:702}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCurrentCycleCredit() {
            return currentCycleCredit;
        }

        /**
         * Sets {@code ACUP-OLD-CURR-CYC-CREDIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:702}.
         *
         * @param currentCycleCredit the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setCurrentCycleCredit(final String currentCycleCredit) {
            this.currentCycleCredit = currentCycleCredit;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-CREDIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:703-704}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCurrentCycleCreditAmount() {
            return currentCycleCreditAmount;
        }

        /**
         * Sets {@code ACUP-OLD-CURR-CYC-CREDIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:703-704}.
         *
         * @param currentCycleCreditAmount the value to store verbatim; {@code null} and the empty string are
         *                                 retained as the distinct states they are
         */
        public void setCurrentCycleCreditAmount(final BigDecimal currentCycleCreditAmount) {
            this.currentCycleCreditAmount = currentCycleCreditAmount;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-DEBIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:705}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCurrentCycleDebit() {
            return currentCycleDebit;
        }

        /**
         * Sets {@code ACUP-OLD-CURR-CYC-DEBIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:705}.
         *
         * @param currentCycleDebit the value to store verbatim; {@code null} and the empty string are retained as
         *                          the distinct states they are
         */
        public void setCurrentCycleDebit(final String currentCycleDebit) {
            this.currentCycleDebit = currentCycleDebit;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-DEBIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:706-707}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCurrentCycleDebitAmount() {
            return currentCycleDebitAmount;
        }

        /**
         * Sets {@code ACUP-OLD-CURR-CYC-DEBIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:706-707}.
         *
         * @param currentCycleDebitAmount the value to store verbatim; {@code null} and the empty string are
         *                                retained as the distinct states they are
         */
        public void setCurrentCycleDebitAmount(final BigDecimal currentCycleDebitAmount) {
            this.currentCycleDebitAmount = currentCycleDebitAmount;
        }

        /**
         * Returns {@code ACUP-OLD-GROUP-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:708}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * Sets {@code ACUP-OLD-GROUP-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:708}.
         *
         * @param groupId the value to store verbatim; {@code null} and the empty string are retained as the
         *                distinct states they are
         */
        public void setGroupId(final String groupId) {
            this.groupId = groupId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ID-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:710}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCustomerId() {
            return customerId;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ID-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:710}.
         *
         * @param customerId the value to store verbatim; {@code null} and the empty string are retained as the
         *                   distinct states they are
         */
        public void setCustomerId(final String customerId) {
            this.customerId = customerId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-FIRST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:713}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-FIRST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:713}.
         *
         * @param firstName the value to store verbatim; {@code null} and the empty string are retained as the
         *                  distinct states they are
         */
        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-MIDDLE-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:714}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getMiddleName() {
            return middleName;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-MIDDLE-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:714}.
         *
         * @param middleName the value to store verbatim; {@code null} and the empty string are retained as the
         *                   distinct states they are
         */
        public void setMiddleName(final String middleName) {
            this.middleName = middleName;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-LAST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:715}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getLastName() {
            return lastName;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-LAST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:715}.
         *
         * @param lastName the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setLastName(final String lastName) {
            this.lastName = lastName;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-LINE-1}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:716}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressLine1() {
            return addressLine1;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ADDR-LINE-1}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:716}.
         *
         * @param addressLine1 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setAddressLine1(final String addressLine1) {
            this.addressLine1 = addressLine1;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-LINE-2}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:717}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressLine2() {
            return addressLine2;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ADDR-LINE-2}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:717}.
         *
         * @param addressLine2 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setAddressLine2(final String addressLine2) {
            this.addressLine2 = addressLine2;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-LINE-3}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:718}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressLine3() {
            return addressLine3;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ADDR-LINE-3}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:718}.
         *
         * @param addressLine3 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setAddressLine3(final String addressLine3) {
            this.addressLine3 = addressLine3;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-STATE-CD}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:719}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressStateCode() {
            return addressStateCode;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ADDR-STATE-CD}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:719}.
         *
         * @param addressStateCode the value to store verbatim; {@code null} and the empty string are retained as
         *                         the distinct states they are
         */
        public void setAddressStateCode(final String addressStateCode) {
            this.addressStateCode = addressStateCode;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:720}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressCountryCode() {
            return addressCountryCode;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:720}.
         *
         * @param addressCountryCode the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setAddressCountryCode(final String addressCountryCode) {
            this.addressCountryCode = addressCountryCode;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-ZIP}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:721}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressZip() {
            return addressZip;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-ADDR-ZIP}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:721}.
         *
         * @param addressZip the value to store verbatim; {@code null} and the empty string are retained as the
         *                   distinct states they are
         */
        public void setAddressZip(final String addressZip) {
            this.addressZip = addressZip;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:722}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1() {
            return phoneNumber1;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-1}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:722}.
         *
         * @param phoneNumber1 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setPhoneNumber1(final String phoneNumber1) {
            this.phoneNumber1 = phoneNumber1;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:726}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1AreaCode() {
            return phoneNumber1AreaCode;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-1A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:726}.
         *
         * @param phoneNumber1AreaCode the value to store verbatim; {@code null} and the empty string are retained
         *                             as the distinct states they are
         */
        public void setPhoneNumber1AreaCode(final String phoneNumber1AreaCode) {
            this.phoneNumber1AreaCode = phoneNumber1AreaCode;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:728}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1Prefix() {
            return phoneNumber1Prefix;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-1B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:728}.
         *
         * @param phoneNumber1Prefix the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setPhoneNumber1Prefix(final String phoneNumber1Prefix) {
            this.phoneNumber1Prefix = phoneNumber1Prefix;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:730}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1LineNumber() {
            return phoneNumber1LineNumber;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-1C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:730}.
         *
         * @param phoneNumber1LineNumber the value to store verbatim; {@code null} and the empty string are retained
         *                               as the distinct states they are
         */
        public void setPhoneNumber1LineNumber(final String phoneNumber1LineNumber) {
            this.phoneNumber1LineNumber = phoneNumber1LineNumber;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:732}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2() {
            return phoneNumber2;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-2}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:732}.
         *
         * @param phoneNumber2 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setPhoneNumber2(final String phoneNumber2) {
            this.phoneNumber2 = phoneNumber2;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:736}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2AreaCode() {
            return phoneNumber2AreaCode;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-2A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:736}.
         *
         * @param phoneNumber2AreaCode the value to store verbatim; {@code null} and the empty string are retained
         *                             as the distinct states they are
         */
        public void setPhoneNumber2AreaCode(final String phoneNumber2AreaCode) {
            this.phoneNumber2AreaCode = phoneNumber2AreaCode;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:738}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2Prefix() {
            return phoneNumber2Prefix;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-2B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:738}.
         *
         * @param phoneNumber2Prefix the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setPhoneNumber2Prefix(final String phoneNumber2Prefix) {
            this.phoneNumber2Prefix = phoneNumber2Prefix;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:740}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2LineNumber() {
            return phoneNumber2LineNumber;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PHONE-NUM-2C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:740}.
         *
         * @param phoneNumber2LineNumber the value to store verbatim; {@code null} and the empty string are retained
         *                               as the distinct states they are
         */
        public void setPhoneNumber2LineNumber(final String phoneNumber2LineNumber) {
            this.phoneNumber2LineNumber = phoneNumber2LineNumber;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-SSN-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:742}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getSsn() {
            return ssn;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-SSN-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:742}.
         *
         * @param ssn the value to store verbatim; {@code null} and the empty string are retained as the distinct
         *            states they are
         */
        public void setSsn(final String ssn) {
            this.ssn = ssn;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-GOVT-ISSUED-ID}, PIC X(20) at {@code app/cbl/COACTUPC.cbl:745}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getGovernmentIssuedId() {
            return governmentIssuedId;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-GOVT-ISSUED-ID}, PIC X(20) at {@code app/cbl/COACTUPC.cbl:745}.
         *
         * @param governmentIssuedId the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setGovernmentIssuedId(final String governmentIssuedId) {
            this.governmentIssuedId = governmentIssuedId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:746}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getDateOfBirth() {
            return dateOfBirth;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:746}.
         *
         * @param dateOfBirth the value to store verbatim; {@code null} and the empty string are retained as the
         *                    distinct states they are
         */
        public void setDateOfBirth(final String dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:752}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getEftAccountId() {
            return eftAccountId;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:752}.
         *
         * @param eftAccountId the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setEftAccountId(final String eftAccountId) {
            this.eftAccountId = eftAccountId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PRI-HOLDER-IND}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:753}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPrimaryCardHolderIndicator() {
            return primaryCardHolderIndicator;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-PRI-HOLDER-IND}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:753}.
         *
         * @param primaryCardHolderIndicator the value to store verbatim; {@code null} and the empty string are
         *                                   retained as the distinct states they are
         */
        public void setPrimaryCardHolderIndicator(final String primaryCardHolderIndicator) {
            this.primaryCardHolderIndicator = primaryCardHolderIndicator;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-FICO-SCORE-X}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:754}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getFicoScore() {
            return ficoScore;
        }

        /**
         * Sets {@code ACUP-OLD-CUST-FICO-SCORE-X}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:754}.
         *
         * @param ficoScore the value to store verbatim; {@code null} and the empty string are retained as the
         *                  distinct states they are
         */
        public void setFicoScore(final String ficoScore) {
            this.ficoScore = ficoScore;
        }

        /**
         * Returns the year component of the account open date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at {@code
         * app/cbl/COACTUPC.cbl:687-689}.
         *
         * This is a derived view, not a stored member: it slices {@code openDate} and is deliberately not named as
         * a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code openDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateYear() {
            return compactDatePart(openDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.openDate");
        }

        /**
         * Returns the month component of the account open date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:687-689}.
         *
         * This is a derived view, not a stored member: it slices {@code openDate} and is deliberately not named as
         * a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code openDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateMonth() {
            return compactDatePart(openDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.openDate");
        }

        /**
         * Returns the day component of the account open date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:687-689}.
         *
         * This is a derived view, not a stored member: it slices {@code openDate} and is deliberately not named as
         * a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code openDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateDay() {
            return compactDatePart(openDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.openDate");
        }

        /**
         * Returns the year component of the account expiry date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at {@code
         * app/cbl/COACTUPC.cbl:693-695}.
         *
         * This is a derived view, not a stored member: it slices {@code expirationDate} and is deliberately not
         * named as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code expirationDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         *         characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expirationDateYear() {
            return compactDatePart(expirationDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.expirationDate");
        }

        /**
         * Returns the month component of the account expiry date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:693-695}.
         *
         * This is a derived view, not a stored member: it slices {@code expirationDate} and is deliberately not
         * named as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code expirationDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         *         characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expirationDateMonth() {
            return compactDatePart(expirationDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.expirationDate");
        }

        /**
         * Returns the day component of the account expiry date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:693-695}.
         *
         * This is a derived view, not a stored member: it slices {@code expirationDate} and is deliberately not
         * named as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code expirationDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         *         characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expirationDateDay() {
            return compactDatePart(expirationDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.expirationDate");
        }

        /**
         * Returns the year component of the account reissue date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at {@code
         * app/cbl/COACTUPC.cbl:699-701}.
         *
         * This is a derived view, not a stored member: it slices {@code reissueDate} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code reissueDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateYear() {
            return compactDatePart(reissueDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.reissueDate");
        }

        /**
         * Returns the month component of the account reissue date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:699-701}.
         *
         * This is a derived view, not a stored member: it slices {@code reissueDate} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code reissueDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateMonth() {
            return compactDatePart(reissueDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.reissueDate");
        }

        /**
         * Returns the day component of the account reissue date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:699-701}.
         *
         * This is a derived view, not a stored member: it slices {@code reissueDate} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code reissueDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateDay() {
            return compactDatePart(reissueDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.reissueDate");
        }

        /**
         * Returns the year component of the date of birth, taken from COBOL offset 1 of the compact eight-character
         * value, matching the {@code PIC X(4)} part declared at {@code app/cbl/COACTUPC.cbl:749-751}.
         *
         * This is a derived view, not a stored member: it slices {@code dateOfBirth} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code dateOfBirth} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthYear() {
            return compactDatePart(dateOfBirth, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.dateOfBirth");
        }

        /**
         * Returns the month component of the date of birth, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:749-751}.
         *
         * This is a derived view, not a stored member: it slices {@code dateOfBirth} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code dateOfBirth} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthMonth() {
            return compactDatePart(dateOfBirth, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.dateOfBirth");
        }

        /**
         * Returns the day component of the date of birth, taken from COBOL offset 7 of the compact eight-character
         * value, matching the {@code PIC X(2)} part declared at {@code app/cbl/COACTUPC.cbl:749-751}.
         *
         * This is a derived view, not a stored member: it slices {@code dateOfBirth} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code dateOfBirth} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthDay() {
            return compactDatePart(dateOfBirth, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.dateOfBirth");
        }

    }

    /**
     * The edited group {@code ACUP-NEW-DETAILS}, declared at
     * {@code app/cbl/COACTUPC.cbl:757-849}: the values the user supplied.
     *
     * <p>The group is split in the source into an account block at
     * {@code :758-796} and a customer block at {@code :797-849}, and both are
     * reproduced here in declaration order. The layout mirrors
     * {@code OldDetails} with one declared difference: the social security
     * number is three parts of three, two and four characters beneath a group
     * item ({@code :830-833}), whereas the OLD side keeps one flat nine-character
     * field ({@code :742}). That asymmetry is modelled exactly as declared.</p>
     *
     * <p>This group carries the width contracts and the one range the source
     * declares, {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code :848-849} &mdash; the only {@code 88}-level condition name in the
     * whole group, and the reason the credit-score bound appears here and
     * nowhere else. No stricter validation is added: there is no pattern the
     * source lacks, nothing is required that the source lets be blank, and no
     * one-character code is bound to an enum.</p>
     *
     * <p>Members that the screen may blank keep their three-state character.
     * When a screen field holds {@code '*'} or spaces the source moves
     * {@code LOW-VALUES} into the corresponding member here &mdash; the social
     * security number parts at {@code :1235}, {@code :1242} and {@code :1249},
     * the date-of-birth parts at {@code :1258}, {@code :1265} and {@code :1272},
     * and the credit score at {@code :1281} &mdash; so absent, blank and
     * low-values remain distinguishable and are never coerced into one
     * another.</p>
     */
    public static final class NewDetails {

        /**
         * {@code ACUP-NEW-ACCT-ID-X} PIC X(11) &mdash; {@code app/cbl/COACTUPC.cbl:759}. Account identifier. A
         * {@code PIC 9(11)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:760-761}; that numeric
         * reading is a view over this text and is interpreted by the service.
         */
        @Size(max = 11,
                message = "accountId must not exceed its declared width of 11 characters"
                        + " (app/cbl/COACTUPC.cbl:759)")
        private String accountId;

        /**
         * {@code ACUP-NEW-ACTIVE-STATUS} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:762}. Account active status,
         * a raw one-character code. Compared with case folding on both sides at {@code
         * app/cbl/COACTUPC.cbl:1685-1688} but plainly at {@code :4115}, so it is stored untransformed.
         */
        @Size(max = 1,
                message = "activeStatus must not exceed its declared width of 1 character"
                        + " (app/cbl/COACTUPC.cbl:762)")
        private String activeStatus;

        /**
         * {@code ACUP-NEW-CURR-BAL} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:763}. Current balance in its
         * displayed twelve-character form, which is what {@code 1205-COMPARE-OLD-NEW} compares.
         */
        @Size(max = 12,
                message = "currentBalance must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:763)")
        private String currentBalance;

        /**
         * {@code ACUP-NEW-CURR-BAL-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:764-765}. Current balance
         * as the {@code PIC S9(10)V99} REDEFINES declares it, which is what {@code 9700-CHECK-CHANGE-IN-REC}
         * compares.
         */
        @Digits(integer = 10, fraction = 2,
                message = "currentBalanceAmount must fit the PIC S9(10)V99 contract of"
                        + " app/cbl/COACTUPC.cbl:764-765: at most 10 integer digits and 2 decimals")
        private BigDecimal currentBalanceAmount;

        /**
         * {@code ACUP-NEW-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:766}. Credit limit in its
         * displayed twelve-character form.
         */
        @Size(max = 12,
                message = "creditLimit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:766)")
        private String creditLimit;

        /**
         * {@code ACUP-NEW-CREDIT-LIMIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:767-768}. Credit limit
         * as the {@code PIC S9(10)V99} REDEFINES declares it.
         */
        @Digits(integer = 10, fraction = 2,
                message = "creditLimitAmount must fit the PIC S9(10)V99 contract of"
                        + " app/cbl/COACTUPC.cbl:767-768: at most 10 integer digits and 2 decimals")
        private BigDecimal creditLimitAmount;

        /**
         * {@code ACUP-NEW-CASH-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:769}. Cash credit limit
         * in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "cashCreditLimit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:769)")
        private String cashCreditLimit;

        /**
         * {@code ACUP-NEW-CASH-CREDIT-LIMIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:770-771}. Cash
         * credit limit as the {@code PIC S9(10)V99} REDEFINES declares it.
         */
        @Digits(integer = 10, fraction = 2,
                message = "cashCreditLimitAmount must fit the PIC S9(10)V99 contract of"
                        + " app/cbl/COACTUPC.cbl:770-771: at most 10 integer digits and 2 decimals")
        private BigDecimal cashCreditLimitAmount;

        /**
         * {@code ACUP-NEW-OPEN-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:772}. Account open date in the
         * compact {@code yyyymmdd} form, with year, month and day parts declared at {@code
         * app/cbl/COACTUPC.cbl:775-777}. Compared whole at {@code :1692} and as three substrings against the live
         * dash-separated {@code PIC X(10)} value at {@code :4127-4129}.
         */
        @Size(max = 8,
                message = "openDate must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:772)")
        private String openDate;

        /**
         * {@code ACUP-NEW-EXPIRAION-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:778}. Account expiry date
         * in the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:781-783}. The source
         * member name is misspelled (sic) and the misspelling is preserved in this citation. Compared whole at
         * {@code :1693} and as three substrings at {@code :4131-4133}.
         */
        @Size(max = 8,
                message = "expirationDate must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:778)")
        private String expirationDate;

        /**
         * {@code ACUP-NEW-REISSUE-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:784}. Account reissue date in
         * the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:787-789}. Compared whole at
         * {@code :1694} and as three substrings at {@code :4135-4137}.
         */
        @Size(max = 8,
                message = "reissueDate must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:784)")
        private String reissueDate;

        /**
         * {@code ACUP-NEW-CURR-CYC-CREDIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:790}. Current cycle credit
         * in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "currentCycleCredit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:790)")
        private String currentCycleCredit;

        /**
         * {@code ACUP-NEW-CURR-CYC-CREDIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:791-792}. Current
         * cycle credit as the {@code PIC S9(10)V99} REDEFINES declares it.
         */
        @Digits(integer = 10, fraction = 2,
                message = "currentCycleCreditAmount must fit the PIC S9(10)V99 contract of"
                        + " app/cbl/COACTUPC.cbl:791-792: at most 10 integer digits and 2 decimals")
        private BigDecimal currentCycleCreditAmount;

        /**
         * {@code ACUP-NEW-CURR-CYC-DEBIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:793}. Current cycle debit
         * in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "currentCycleDebit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:793)")
        private String currentCycleDebit;

        /**
         * {@code ACUP-NEW-CURR-CYC-DEBIT-N} PIC S9(10)V99 &mdash; {@code app/cbl/COACTUPC.cbl:794-795}. Current
         * cycle debit as the {@code PIC S9(10)V99} REDEFINES declares it. The source lets this accumulator carry
         * negative amounts, so no sign normalisation is applied here or anywhere on this path.
         */
        @Digits(integer = 10, fraction = 2,
                message = "currentCycleDebitAmount must fit the PIC S9(10)V99 contract of"
                        + " app/cbl/COACTUPC.cbl:794-795: at most 10 integer digits and 2 decimals")
        private BigDecimal currentCycleDebitAmount;

        /**
         * {@code ACUP-NEW-GROUP-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:796}. Account group identifier,
         * stored raw. {@code 1205-COMPARE-OLD-NEW} compares it upper-cased and trimmed at {@code
         * app/cbl/COACTUPC.cbl:1697-1700} while {@code 9700-CHECK-CHANGE-IN-REC} compares it lower-cased and
         * untrimmed at {@code :4139-4140}; only the untransformed value serves both.
         */
        @Size(max = 10,
                message = "groupId must not exceed its declared width of 10 characters"
                        + " (app/cbl/COACTUPC.cbl:796)")
        private String groupId;

        /**
         * {@code ACUP-NEW-CUST-ID-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:798}. Customer identifier. A
         * {@code PIC 9(09)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:799-800}.
         */
        @Size(max = 9,
                message = "customerId must not exceed its declared width of 9 characters"
                        + " (app/cbl/COACTUPC.cbl:798)")
        private String customerId;

        /**
         * {@code ACUP-NEW-CUST-FIRST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:801}. Customer first name.
         */
        @Size(max = 25,
                message = "firstName must not exceed its declared width of 25 characters"
                        + " (app/cbl/COACTUPC.cbl:801)")
        private String firstName;

        /**
         * {@code ACUP-NEW-CUST-MIDDLE-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:802}. Customer middle
         * name.
         */
        @Size(max = 25,
                message = "middleName must not exceed its declared width of 25 characters"
                        + " (app/cbl/COACTUPC.cbl:802)")
        private String middleName;

        /**
         * {@code ACUP-NEW-CUST-LAST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:803}. Customer last name.
         */
        @Size(max = 25,
                message = "lastName must not exceed its declared width of 25 characters"
                        + " (app/cbl/COACTUPC.cbl:803)")
        private String lastName;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-1} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:804}. First address line.
         */
        @Size(max = 50,
                message = "addressLine1 must not exceed its declared width of 50 characters"
                        + " (app/cbl/COACTUPC.cbl:804)")
        private String addressLine1;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-2} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:805}. Second address
         * line.
         */
        @Size(max = 50,
                message = "addressLine2 must not exceed its declared width of 50 characters"
                        + " (app/cbl/COACTUPC.cbl:805)")
        private String addressLine2;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-3} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:806}. Third address line.
         * It corresponds to the city field of the symbolic map, {@code ACSCITYI} at {@code
         * app/cpy-bms/COACTUP.CPY:252}.
         */
        @Size(max = 50,
                message = "addressLine3 must not exceed its declared width of 50 characters"
                        + " (app/cbl/COACTUPC.cbl:806)")
        private String addressLine3;

        /**
         * {@code ACUP-NEW-CUST-ADDR-STATE-CD} PIC X(02) &mdash; {@code app/cbl/COACTUPC.cbl:807}. State code. Its
         * consistency with the postal code is edited only when both fields are individually valid, so no
         * cross-field constraint is declared here.
         */
        @Size(max = 2,
                message = "addressStateCode must not exceed its declared width of 2 characters"
                        + " (app/cbl/COACTUPC.cbl:807)")
        private String addressStateCode;

        /**
         * {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:808}. Country code.
         */
        @Size(max = 3,
                message = "addressCountryCode must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:808)")
        private String addressCountryCode;

        /**
         * {@code ACUP-NEW-CUST-ADDR-ZIP} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:809}. Postal code, ten
         * characters here and on the customer record at {@code app/cpy/CVCUS01Y.cpy:14}, against five on the screen
         * field {@code ACSZIPCI} at {@code app/cpy-bms/COACTUP.CPY:246}. Compared upper-cased and trimmed by {@code
         * 1205-COMPARE-OLD-NEW} but with no case function at all at {@code app/cbl/COACTUPC.cbl:4168}.
         */
        @Size(max = 10,
                message = "addressZip must not exceed its declared width of 10 characters"
                        + " (app/cbl/COACTUPC.cbl:809)")
        private String addressZip;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1} PIC X(15) &mdash; {@code app/cbl/COACTUPC.cbl:810}. First telephone
         * number as the whole formatted fifteen-character value, which is what {@code 9700-CHECK-CHANGE-IN-REC}
         * compares at {@code app/cbl/COACTUPC.cbl:4169}. The REDEFINES at {@code :811-819} interleaves three filler
         * runs between the parts, so the whole value carries the punctuation and cannot be derived from the parts.
         */
        @Size(max = 15,
                message = "phoneNumber1 must not exceed its declared width of 15 characters"
                        + " (app/cbl/COACTUPC.cbl:810)")
        private String phoneNumber1;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1A} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:814}. Area code of the
         * first telephone number, compared part by part by {@code 1205-COMPARE-OLD-NEW} at {@code
         * app/cbl/COACTUPC.cbl:1748}.
         */
        @Size(max = 3,
                message = "phoneNumber1AreaCode must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:814)")
        private String phoneNumber1AreaCode;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1B} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:816}. Prefix of the first
         * telephone number.
         */
        @Size(max = 3,
                message = "phoneNumber1Prefix must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:816)")
        private String phoneNumber1Prefix;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1C} PIC X(4) &mdash; {@code app/cbl/COACTUPC.cbl:818}. Line number of the
         * first telephone number.
         */
        @Size(max = 4,
                message = "phoneNumber1LineNumber must not exceed its declared width of 4 characters"
                        + " (app/cbl/COACTUPC.cbl:818)")
        private String phoneNumber1LineNumber;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2} PIC X(15) &mdash; {@code app/cbl/COACTUPC.cbl:820}. Second telephone
         * number as the whole formatted fifteen-character value, compared whole at {@code
         * app/cbl/COACTUPC.cbl:4170}, with the same filler-interleaved REDEFINES at {@code :821-829}. Edited by
         * {@code 1260-EDIT-US-PHONE-NUM} under the label {@code 'Phone Number 2'} at {@code :1640}.
         */
        @Size(max = 15,
                message = "phoneNumber2 must not exceed its declared width of 15 characters"
                        + " (app/cbl/COACTUPC.cbl:820)")
        private String phoneNumber2;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2A} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:824}. Area code of the
         * second telephone number, compared part by part at {@code app/cbl/COACTUPC.cbl:1751}.
         */
        @Size(max = 3,
                message = "phoneNumber2AreaCode must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:824)")
        private String phoneNumber2AreaCode;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2B} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:826}. Prefix of the
         * second telephone number.
         */
        @Size(max = 3,
                message = "phoneNumber2Prefix must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:826)")
        private String phoneNumber2Prefix;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2C} PIC X(4) &mdash; {@code app/cbl/COACTUPC.cbl:828}. Line number of the
         * second telephone number.
         */
        @Size(max = 4,
                message = "phoneNumber2LineNumber must not exceed its declared width of 4 characters"
                        + " (app/cbl/COACTUPC.cbl:828)")
        private String phoneNumber2LineNumber;

        /**
         * {@code ACUP-NEW-CUST-SSN-1} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:831}. First part of the social
         * security number. This is the NEW side of the declared asymmetry: the OLD side keeps one flat
         * nine-character field at {@code app/cbl/COACTUPC.cbl:742}. Blank or a single asterisk on the screen
         * becomes LOW-VALUES here, at {@code :1235}.
         */
        @Size(max = 3,
                message = "ssnPart1 must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:831)")
        private String ssnPart1;

        /**
         * {@code ACUP-NEW-CUST-SSN-2} PIC X(02) &mdash; {@code app/cbl/COACTUPC.cbl:832}. Second part of the social
         * security number. Blank or a single asterisk on the screen becomes LOW-VALUES here, at {@code
         * app/cbl/COACTUPC.cbl:1242}.
         */
        @Size(max = 2,
                message = "ssnPart2 must not exceed its declared width of 2 characters"
                        + " (app/cbl/COACTUPC.cbl:832)")
        private String ssnPart2;

        /**
         * {@code ACUP-NEW-CUST-SSN-3} PIC X(04) &mdash; {@code app/cbl/COACTUPC.cbl:833}. Third part of the social
         * security number. Blank or a single asterisk on the screen becomes LOW-VALUES here, at {@code
         * app/cbl/COACTUPC.cbl:1249}. The three parts sit beneath the group item {@code ACUP-NEW-CUST-SSN-X} at
         * {@code :830} with a {@code PIC 9(09)} REDEFINES over them at {@code :834-835}; {@code
         * 1205-COMPARE-OLD-NEW} compares that whole group at {@code :1754}, which is meaningful only because the
         * parts are contiguous.
         */
        @Size(max = 4,
                message = "ssnPart3 must not exceed its declared width of 4 characters"
                        + " (app/cbl/COACTUPC.cbl:833)")
        private String ssnPart3;

        /**
         * {@code ACUP-NEW-CUST-GOVT-ISSUED-ID} PIC X(20) &mdash; {@code app/cbl/COACTUPC.cbl:836}.
         * Government-issued identifier.
         */
        @Size(max = 20,
                message = "governmentIssuedId must not exceed its declared width of 20 characters"
                        + " (app/cbl/COACTUPC.cbl:836)")
        private String governmentIssuedId;

        /**
         * {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:837}. Date of birth in
         * the compact {@code yyyymmdd} form, with parts declared at {@code app/cbl/COACTUPC.cbl:840-842}. Despite
         * the dash-implying name the field is eight characters and carries no separators, so its components sit at
         * offsets 1, 5 and 7 while the live {@code PIC X(10)} value of {@code app/cpy/CVCUS01Y.cpy:19} keeps its
         * own at 1, 6 and 9. This is the Blocker described in this file's class documentation; the dash-separated
         * form must never be stored here.
         */
        @Size(max = 8,
                message = "dateOfBirth must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:837)")
        private String dateOfBirth;

        /**
         * {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:843}. Electronic funds
         * transfer account identifier. Compared plainly by both regimes, at {@code app/cbl/COACTUPC.cbl:1761-1762}
         * and {@code :4181-4182}, and edited by {@code 1245-EDIT-NUM-REQD} under the label {@code 'EFT Account Id'}
         * at {@code :1648} with a declared length of ten at {@code :1651}.
         */
        @Size(max = 10,
                message = "eftAccountId must not exceed its declared width of 10 characters"
                        + " (app/cbl/COACTUPC.cbl:843)")
        private String eftAccountId;

        /**
         * {@code ACUP-NEW-CUST-PRI-HOLDER-IND} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:844}. Primary card
         * holder indicator, a raw one-character code and never an enum. Edited by {@code 1220-EDIT-YESNO} under the
         * label {@code 'Primary Card Holder'} at {@code app/cbl/COACTUPC.cbl:1657}.
         */
        @Size(max = 1,
                message = "primaryCardHolderIndicator must not exceed its declared width of 1 character"
                        + " (app/cbl/COACTUPC.cbl:844)")
        private String primaryCardHolderIndicator;

        /**
         * {@code ACUP-NEW-CUST-FICO-SCORE-X} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:845}. Credit score as
         * text, which is what {@code 1205-COMPARE-OLD-NEW} compares. A {@code PIC 9(03)} REDEFINES overlays the
         * same bytes at {@code app/cbl/COACTUPC.cbl:846-847}, and that numeric reading is what {@code
         * 9700-CHECK-CHANGE-IN-REC} compares at {@code :4186}.
         */
        @Size(max = 3,
                message = "ficoScore must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:845)")
        private String ficoScore;

        /**
         * {@code ACUP-NEW-CUST-FICO-SCORE} PIC 9(03) &mdash; {@code app/cbl/COACTUPC.cbl:846-847}. The credit score
         * as the {@code PIC 9(03)} REDEFINES declares it, carried alongside the text member for exactly the reason
         * the money members carry both representations: {@code 1205-COMPARE-OLD-NEW} compares the TEXT member
         * {@code ACUP-NEW-CUST-FICO-SCORE-X} at {@code :1767-1768}, while {@code 9700-CHECK-CHANGE-IN-REC} compares
         * this NUMERIC member at {@code :4186}. Neither reading is derivable from the other once a non-numeric or
         * blank value has been entered, so both are stored.
         *
         * <p>{@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at {@code app/cbl/COACTUPC.cbl:848-849} is
         * declared on THIS numeric member, not on the text member, so the range constraint is applied here and
         * nowhere else. It is declared on the NEW side only and therefore enforced on the NEW side only; the OLD
         * snapshot group carries no constraint of any kind.</p>
         *
         * <p>A {@code null} value means the score was not supplied. A range constraint treats {@code null} as
         * valid, so the absent and blank cases still reach the service and still receive the source's own distinct
         * blank and not-valid messages rather than a framework error &mdash; the three-state model of
         * {@code app/cpy/CSSETATY.cpy} is preserved.</p>
         */
        @Min(value = FICO_SCORE_MINIMUM,
                message = "ficoScoreValue must not be below 300, the lower bound"
                        + " of 88 FICO-RANGE-IS-VALID at app/cbl/COACTUPC.cbl:848-849")
        @Max(value = FICO_SCORE_MAXIMUM,
                message = "ficoScoreValue must not be above 850, the upper bound"
                        + " of 88 FICO-RANGE-IS-VALID at app/cbl/COACTUPC.cbl:848-849")
        private Integer ficoScoreValue;

        /**
         * Creates an empty group. JSON binding populates the members through
         * the accessors below; nothing is defaulted, so an absent member stays
         * absent and a blank member stays blank.
         */
        public NewDetails() {
            // Intentionally empty: absent, blank and low-values are three
            // distinguishable states and none of them may be manufactured here.
        }

        /**
         * Returns {@code ACUP-NEW-ACCT-ID-X}, PIC X(11) at {@code app/cbl/COACTUPC.cbl:759}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Sets {@code ACUP-NEW-ACCT-ID-X}, PIC X(11) at {@code app/cbl/COACTUPC.cbl:759}.
         *
         * @param accountId the value to store verbatim; {@code null} and the empty string are retained as the
         *                  distinct states they are
         */
        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }

        /**
         * Returns {@code ACUP-NEW-ACTIVE-STATUS}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:762}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getActiveStatus() {
            return activeStatus;
        }

        /**
         * Sets {@code ACUP-NEW-ACTIVE-STATUS}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:762}.
         *
         * @param activeStatus the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setActiveStatus(final String activeStatus) {
            this.activeStatus = activeStatus;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-BAL}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:763}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Sets {@code ACUP-NEW-CURR-BAL}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:763}.
         *
         * @param currentBalance the value to store verbatim; {@code null} and the empty string are retained as the
         *                       distinct states they are
         */
        public void setCurrentBalance(final String currentBalance) {
            this.currentBalance = currentBalance;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-BAL-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:764-765}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCurrentBalanceAmount() {
            return currentBalanceAmount;
        }

        /**
         * Sets {@code ACUP-NEW-CURR-BAL-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:764-765}.
         *
         * @param currentBalanceAmount the value to store verbatim; {@code null} and the empty string are retained
         *                             as the distinct states they are
         */
        public void setCurrentBalanceAmount(final BigDecimal currentBalanceAmount) {
            this.currentBalanceAmount = currentBalanceAmount;
        }

        /**
         * Returns {@code ACUP-NEW-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:766}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCreditLimit() {
            return creditLimit;
        }

        /**
         * Sets {@code ACUP-NEW-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:766}.
         *
         * @param creditLimit the value to store verbatim; {@code null} and the empty string are retained as the
         *                    distinct states they are
         */
        public void setCreditLimit(final String creditLimit) {
            this.creditLimit = creditLimit;
        }

        /**
         * Returns {@code ACUP-NEW-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:767-768}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCreditLimitAmount() {
            return creditLimitAmount;
        }

        /**
         * Sets {@code ACUP-NEW-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:767-768}.
         *
         * @param creditLimitAmount the value to store verbatim; {@code null} and the empty string are retained as
         *                          the distinct states they are
         */
        public void setCreditLimitAmount(final BigDecimal creditLimitAmount) {
            this.creditLimitAmount = creditLimitAmount;
        }

        /**
         * Returns {@code ACUP-NEW-CASH-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:769}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCashCreditLimit() {
            return cashCreditLimit;
        }

        /**
         * Sets {@code ACUP-NEW-CASH-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:769}.
         *
         * @param cashCreditLimit the value to store verbatim; {@code null} and the empty string are retained as the
         *                        distinct states they are
         */
        public void setCashCreditLimit(final String cashCreditLimit) {
            this.cashCreditLimit = cashCreditLimit;
        }

        /**
         * Returns {@code ACUP-NEW-CASH-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:770-771}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCashCreditLimitAmount() {
            return cashCreditLimitAmount;
        }

        /**
         * Sets {@code ACUP-NEW-CASH-CREDIT-LIMIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:770-771}.
         *
         * @param cashCreditLimitAmount the value to store verbatim; {@code null} and the empty string are retained
         *                              as the distinct states they are
         */
        public void setCashCreditLimitAmount(final BigDecimal cashCreditLimitAmount) {
            this.cashCreditLimitAmount = cashCreditLimitAmount;
        }

        /**
         * Returns {@code ACUP-NEW-OPEN-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:772}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getOpenDate() {
            return openDate;
        }

        /**
         * Sets {@code ACUP-NEW-OPEN-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:772}.
         *
         * @param openDate the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setOpenDate(final String openDate) {
            this.openDate = openDate;
        }

        /**
         * Returns {@code ACUP-NEW-EXPIRAION-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:778}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getExpirationDate() {
            return expirationDate;
        }

        /**
         * Sets {@code ACUP-NEW-EXPIRAION-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:778}.
         *
         * @param expirationDate the value to store verbatim; {@code null} and the empty string are retained as the
         *                       distinct states they are
         */
        public void setExpirationDate(final String expirationDate) {
            this.expirationDate = expirationDate;
        }

        /**
         * Returns {@code ACUP-NEW-REISSUE-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:784}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getReissueDate() {
            return reissueDate;
        }

        /**
         * Sets {@code ACUP-NEW-REISSUE-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:784}.
         *
         * @param reissueDate the value to store verbatim; {@code null} and the empty string are retained as the
         *                    distinct states they are
         */
        public void setReissueDate(final String reissueDate) {
            this.reissueDate = reissueDate;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-CREDIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:790}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCurrentCycleCredit() {
            return currentCycleCredit;
        }

        /**
         * Sets {@code ACUP-NEW-CURR-CYC-CREDIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:790}.
         *
         * @param currentCycleCredit the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setCurrentCycleCredit(final String currentCycleCredit) {
            this.currentCycleCredit = currentCycleCredit;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-CREDIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:791-792}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCurrentCycleCreditAmount() {
            return currentCycleCreditAmount;
        }

        /**
         * Sets {@code ACUP-NEW-CURR-CYC-CREDIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:791-792}.
         *
         * @param currentCycleCreditAmount the value to store verbatim; {@code null} and the empty string are
         *                                 retained as the distinct states they are
         */
        public void setCurrentCycleCreditAmount(final BigDecimal currentCycleCreditAmount) {
            this.currentCycleCreditAmount = currentCycleCreditAmount;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-DEBIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:793}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCurrentCycleDebit() {
            return currentCycleDebit;
        }

        /**
         * Sets {@code ACUP-NEW-CURR-CYC-DEBIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:793}.
         *
         * @param currentCycleDebit the value to store verbatim; {@code null} and the empty string are retained as
         *                          the distinct states they are
         */
        public void setCurrentCycleDebit(final String currentCycleDebit) {
            this.currentCycleDebit = currentCycleDebit;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-DEBIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:794-795}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public BigDecimal getCurrentCycleDebitAmount() {
            return currentCycleDebitAmount;
        }

        /**
         * Sets {@code ACUP-NEW-CURR-CYC-DEBIT-N}, PIC S9(10)V99 at {@code app/cbl/COACTUPC.cbl:794-795}.
         *
         * @param currentCycleDebitAmount the value to store verbatim; {@code null} and the empty string are
         *                                retained as the distinct states they are
         */
        public void setCurrentCycleDebitAmount(final BigDecimal currentCycleDebitAmount) {
            this.currentCycleDebitAmount = currentCycleDebitAmount;
        }

        /**
         * Returns {@code ACUP-NEW-GROUP-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:796}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * Sets {@code ACUP-NEW-GROUP-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:796}.
         *
         * @param groupId the value to store verbatim; {@code null} and the empty string are retained as the
         *                distinct states they are
         */
        public void setGroupId(final String groupId) {
            this.groupId = groupId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ID-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:798}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getCustomerId() {
            return customerId;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ID-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:798}.
         *
         * @param customerId the value to store verbatim; {@code null} and the empty string are retained as the
         *                   distinct states they are
         */
        public void setCustomerId(final String customerId) {
            this.customerId = customerId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-FIRST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:801}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-FIRST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:801}.
         *
         * @param firstName the value to store verbatim; {@code null} and the empty string are retained as the
         *                  distinct states they are
         */
        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-MIDDLE-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:802}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getMiddleName() {
            return middleName;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-MIDDLE-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:802}.
         *
         * @param middleName the value to store verbatim; {@code null} and the empty string are retained as the
         *                   distinct states they are
         */
        public void setMiddleName(final String middleName) {
            this.middleName = middleName;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-LAST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:803}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getLastName() {
            return lastName;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-LAST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:803}.
         *
         * @param lastName the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setLastName(final String lastName) {
            this.lastName = lastName;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-LINE-1}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:804}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressLine1() {
            return addressLine1;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ADDR-LINE-1}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:804}.
         *
         * @param addressLine1 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setAddressLine1(final String addressLine1) {
            this.addressLine1 = addressLine1;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-LINE-2}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:805}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressLine2() {
            return addressLine2;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ADDR-LINE-2}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:805}.
         *
         * @param addressLine2 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setAddressLine2(final String addressLine2) {
            this.addressLine2 = addressLine2;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-LINE-3}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:806}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressLine3() {
            return addressLine3;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ADDR-LINE-3}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:806}.
         *
         * @param addressLine3 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setAddressLine3(final String addressLine3) {
            this.addressLine3 = addressLine3;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-STATE-CD}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:807}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressStateCode() {
            return addressStateCode;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ADDR-STATE-CD}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:807}.
         *
         * @param addressStateCode the value to store verbatim; {@code null} and the empty string are retained as
         *                         the distinct states they are
         */
        public void setAddressStateCode(final String addressStateCode) {
            this.addressStateCode = addressStateCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:808}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressCountryCode() {
            return addressCountryCode;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:808}.
         *
         * @param addressCountryCode the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setAddressCountryCode(final String addressCountryCode) {
            this.addressCountryCode = addressCountryCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-ZIP}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:809}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getAddressZip() {
            return addressZip;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-ADDR-ZIP}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:809}.
         *
         * @param addressZip the value to store verbatim; {@code null} and the empty string are retained as the
         *                   distinct states they are
         */
        public void setAddressZip(final String addressZip) {
            this.addressZip = addressZip;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:810}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1() {
            return phoneNumber1;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-1}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:810}.
         *
         * @param phoneNumber1 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setPhoneNumber1(final String phoneNumber1) {
            this.phoneNumber1 = phoneNumber1;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:814}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1AreaCode() {
            return phoneNumber1AreaCode;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-1A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:814}.
         *
         * @param phoneNumber1AreaCode the value to store verbatim; {@code null} and the empty string are retained
         *                             as the distinct states they are
         */
        public void setPhoneNumber1AreaCode(final String phoneNumber1AreaCode) {
            this.phoneNumber1AreaCode = phoneNumber1AreaCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:816}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1Prefix() {
            return phoneNumber1Prefix;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-1B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:816}.
         *
         * @param phoneNumber1Prefix the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setPhoneNumber1Prefix(final String phoneNumber1Prefix) {
            this.phoneNumber1Prefix = phoneNumber1Prefix;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:818}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber1LineNumber() {
            return phoneNumber1LineNumber;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-1C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:818}.
         *
         * @param phoneNumber1LineNumber the value to store verbatim; {@code null} and the empty string are retained
         *                               as the distinct states they are
         */
        public void setPhoneNumber1LineNumber(final String phoneNumber1LineNumber) {
            this.phoneNumber1LineNumber = phoneNumber1LineNumber;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:820}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2() {
            return phoneNumber2;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-2}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:820}.
         *
         * @param phoneNumber2 the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setPhoneNumber2(final String phoneNumber2) {
            this.phoneNumber2 = phoneNumber2;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:824}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2AreaCode() {
            return phoneNumber2AreaCode;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-2A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:824}.
         *
         * @param phoneNumber2AreaCode the value to store verbatim; {@code null} and the empty string are retained
         *                             as the distinct states they are
         */
        public void setPhoneNumber2AreaCode(final String phoneNumber2AreaCode) {
            this.phoneNumber2AreaCode = phoneNumber2AreaCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:826}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2Prefix() {
            return phoneNumber2Prefix;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-2B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:826}.
         *
         * @param phoneNumber2Prefix the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setPhoneNumber2Prefix(final String phoneNumber2Prefix) {
            this.phoneNumber2Prefix = phoneNumber2Prefix;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:828}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPhoneNumber2LineNumber() {
            return phoneNumber2LineNumber;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PHONE-NUM-2C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:828}.
         *
         * @param phoneNumber2LineNumber the value to store verbatim; {@code null} and the empty string are retained
         *                               as the distinct states they are
         */
        public void setPhoneNumber2LineNumber(final String phoneNumber2LineNumber) {
            this.phoneNumber2LineNumber = phoneNumber2LineNumber;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-SSN-1}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:831}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getSsnPart1() {
            return ssnPart1;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-SSN-1}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:831}.
         *
         * @param ssnPart1 the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setSsnPart1(final String ssnPart1) {
            this.ssnPart1 = ssnPart1;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-SSN-2}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:832}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getSsnPart2() {
            return ssnPart2;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-SSN-2}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:832}.
         *
         * @param ssnPart2 the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setSsnPart2(final String ssnPart2) {
            this.ssnPart2 = ssnPart2;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-SSN-3}, PIC X(04) at {@code app/cbl/COACTUPC.cbl:833}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getSsnPart3() {
            return ssnPart3;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-SSN-3}, PIC X(04) at {@code app/cbl/COACTUPC.cbl:833}.
         *
         * @param ssnPart3 the value to store verbatim; {@code null} and the empty string are retained as the
         *                 distinct states they are
         */
        public void setSsnPart3(final String ssnPart3) {
            this.ssnPart3 = ssnPart3;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-GOVT-ISSUED-ID}, PIC X(20) at {@code app/cbl/COACTUPC.cbl:836}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getGovernmentIssuedId() {
            return governmentIssuedId;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-GOVT-ISSUED-ID}, PIC X(20) at {@code app/cbl/COACTUPC.cbl:836}.
         *
         * @param governmentIssuedId the value to store verbatim; {@code null} and the empty string are retained as
         *                           the distinct states they are
         */
        public void setGovernmentIssuedId(final String governmentIssuedId) {
            this.governmentIssuedId = governmentIssuedId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:837}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getDateOfBirth() {
            return dateOfBirth;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:837}.
         *
         * @param dateOfBirth the value to store verbatim; {@code null} and the empty string are retained as the
         *                    distinct states they are
         */
        public void setDateOfBirth(final String dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:843}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getEftAccountId() {
            return eftAccountId;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:843}.
         *
         * @param eftAccountId the value to store verbatim; {@code null} and the empty string are retained as the
         *                     distinct states they are
         */
        public void setEftAccountId(final String eftAccountId) {
            this.eftAccountId = eftAccountId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PRI-HOLDER-IND}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:844}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getPrimaryCardHolderIndicator() {
            return primaryCardHolderIndicator;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-PRI-HOLDER-IND}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:844}.
         *
         * @param primaryCardHolderIndicator the value to store verbatim; {@code null} and the empty string are
         *                                   retained as the distinct states they are
         */
        public void setPrimaryCardHolderIndicator(final String primaryCardHolderIndicator) {
            this.primaryCardHolderIndicator = primaryCardHolderIndicator;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-FICO-SCORE-X}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:845}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         *         applied; {@code null} when absent
         */
        public String getFicoScore() {
            return ficoScore;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-FICO-SCORE-X}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:845}.
         *
         * @param ficoScore the value to store verbatim; {@code null} and the empty string are retained as the
         *                  distinct states they are
         */
        public void setFicoScore(final String ficoScore) {
            this.ficoScore = ficoScore;
        }

        /**
         * Returns the year component of the account open date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at {@code
         * app/cbl/COACTUPC.cbl:775-777}.
         *
         * This is a derived view, not a stored member: it slices {@code openDate} and is deliberately not named as
         * a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code openDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateYear() {
            return compactDatePart(openDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.openDate");
        }

        /**
         * Returns the month component of the account open date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:775-777}.
         *
         * This is a derived view, not a stored member: it slices {@code openDate} and is deliberately not named as
         * a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code openDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateMonth() {
            return compactDatePart(openDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.openDate");
        }

        /**
         * Returns the day component of the account open date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:775-777}.
         *
         * This is a derived view, not a stored member: it slices {@code openDate} and is deliberately not named as
         * a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code openDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateDay() {
            return compactDatePart(openDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.openDate");
        }

        /**
         * Returns the year component of the account expiry date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at {@code
         * app/cbl/COACTUPC.cbl:781-783}.
         *
         * This is a derived view, not a stored member: it slices {@code expirationDate} and is deliberately not
         * named as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code expirationDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         *         characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expirationDateYear() {
            return compactDatePart(expirationDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.expirationDate");
        }

        /**
         * Returns the month component of the account expiry date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:781-783}.
         *
         * This is a derived view, not a stored member: it slices {@code expirationDate} and is deliberately not
         * named as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code expirationDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         *         characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expirationDateMonth() {
            return compactDatePart(expirationDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.expirationDate");
        }

        /**
         * Returns the day component of the account expiry date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:781-783}.
         *
         * This is a derived view, not a stored member: it slices {@code expirationDate} and is deliberately not
         * named as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code expirationDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         *         characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expirationDateDay() {
            return compactDatePart(expirationDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.expirationDate");
        }

        /**
         * Returns the year component of the account reissue date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at {@code
         * app/cbl/COACTUPC.cbl:787-789}.
         *
         * This is a derived view, not a stored member: it slices {@code reissueDate} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code reissueDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateYear() {
            return compactDatePart(reissueDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.reissueDate");
        }

        /**
         * Returns the month component of the account reissue date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:787-789}.
         *
         * This is a derived view, not a stored member: it slices {@code reissueDate} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code reissueDate} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateMonth() {
            return compactDatePart(reissueDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.reissueDate");
        }

        /**
         * Returns the day component of the account reissue date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:787-789}.
         *
         * This is a derived view, not a stored member: it slices {@code reissueDate} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code reissueDate} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateDay() {
            return compactDatePart(reissueDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.reissueDate");
        }

        /**
         * Returns the year component of the date of birth, taken from COBOL offset 1 of the compact eight-character
         * value, matching the {@code PIC X(4)} part declared at {@code app/cbl/COACTUPC.cbl:840-842}.
         *
         * This is a derived view, not a stored member: it slices {@code dateOfBirth} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code dateOfBirth} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthYear() {
            return compactDatePart(dateOfBirth, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.dateOfBirth");
        }

        /**
         * Returns the month component of the date of birth, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at {@code
         * app/cbl/COACTUPC.cbl:840-842}.
         *
         * This is a derived view, not a stored member: it slices {@code dateOfBirth} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code dateOfBirth} is absent, or the empty string when
         *         the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthMonth() {
            return compactDatePart(dateOfBirth, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.dateOfBirth");
        }

        /**
         * Returns the day component of the date of birth, taken from COBOL offset 7 of the compact eight-character
         * value, matching the {@code PIC X(2)} part declared at {@code app/cbl/COACTUPC.cbl:840-842}.
         *
         * This is a derived view, not a stored member: it slices {@code dateOfBirth} and is deliberately not named
         * as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code dateOfBirth} is absent, or the empty string when the
         *         stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight characters,
         *         which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthDay() {
            return compactDatePart(dateOfBirth, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.dateOfBirth");
        }

        /**
         * Returns {@code ACUP-NEW-CUST-FICO-SCORE}, PIC 9(03) at {@code app/cbl/COACTUPC.cbl:846-847}, the numeric
         * REDEFINES reading that {@code 9700-CHECK-CHANGE-IN-REC} compares at {@code :4186} and that
         * {@code 88 FICO-RANGE-IS-VALID} at {@code :848-849} constrains to 300 through 850 inclusive.
         *
         * @return the stored numeric credit score exactly as received, with no clamping or rounding applied;
         *         {@code null} when the score was not supplied
         */
        public Integer getFicoScoreValue() {
            return ficoScoreValue;
        }

        /**
         * Sets {@code ACUP-NEW-CUST-FICO-SCORE}, PIC 9(03) at {@code app/cbl/COACTUPC.cbl:846-847}.
         *
         * @param ficoScoreValue the value to store verbatim; {@code null} is retained as the distinct absent state
         *                       it is, and out-of-range values are reported by the declared range constraint rather
         *                       than corrected here
         */
        public void setFicoScoreValue(final Integer ficoScoreValue) {
            this.ficoScoreValue = ficoScoreValue;
        }

    }

}
