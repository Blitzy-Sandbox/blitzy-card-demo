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

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Account-update request payload: the stateless replacement for the CICS pseudo-conversation driven by
 * {@code app/cbl/COACTUPC.cbl}, the 4,236-line account-update program.
 *
 * <p>The payload carries <strong>both</strong> the edited values and the snapshot the user was shown, because
 * {@code 9700-CHECK-CHANGE-IN-REC} accepts a write only when the live record still matches that snapshot field
 * by field. A version counter cannot express the same guarantee — it reports that some row changed, not which
 * business values differ from the user's view — and a stateless server cannot hold the snapshot between
 * requests, so it travels in the request body.
 *
 * <p><strong>The snapshot travels sealed, as the single opaque {@link #getSnapshot() snapshot} member.</strong>
 * It is the string the preceding {@code GET /api/accounts/{accountId}} returned, echoed back unchanged. It is
 * not a readable group and there is no {@code oldDetails} member on this wire contract, for two reasons that
 * are properties of the data rather than preferences. A group the caller can rewrite is not evidence of what
 * was displayed: rewriting it to the live row makes {@code 9700-CHECK-CHANGE-IN-REC} unconditionally true, so
 * the lost-update guard the source provides would be no guard at all. And the group carries the customer's
 * names, both telephone numbers, the social security number, the date of birth, the government-issued
 * identifier and the electronic funds account identifier, so emitting it in the clear on the preceding read
 * would publish protected personal data to any proxy, cache or browser tool on the path.
 * {@code com.cardemo.security.SnapshotTokenService} seals the group with AES-256-GCM, bound to the operation,
 * the account identifier, the authenticated principal and an expiry, and the update service opens it
 * server-side. What is compared is therefore byte for byte the group the read projected — which is also what
 * keeps the two properties below intact, since a caller never has to rebuild anything.
 *
 * <p>Two properties of the comparison are load-bearing and must not be tidied. The snapshot dates are compact
 * {@code PIC X(08)} values with no separators, while the live record holds them dash-separated, so the source
 * compares them component by component at different offsets on each side; a whole-string comparison would
 * report a change on every request and make the endpoint permanently unusable. And the case handling is
 * deliberately asymmetric — the account group identifier is compared lower-cased, the customer name and
 * address group upper-cased, and the postal code, telephone numbers, national identifier, funds account,
 * holder indicator and credit score with no case function at all.
 *
 * <p>This class validates and normalises nothing on ingest: no trimming, no case folding, no padding and no
 * default. Every field width comes from {@code app/cpy-bms/COACTUP.CPY}, widths that differ between the edited
 * group and the snapshot group stay different, and preserved source misspellings stay misspelled. Bean
 * validation cascades into the edited group only, since the snapshot is evidence of what was displayed rather
 * than input to be judged. The comparison itself, and every outcome it produces, belong to the consuming
 * service.
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
 *   </ul>
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
 *   <li>(iii) each telephone number is carried on the side the source actually
 *       assigns, and the other reading is derived &mdash; and the two groups take
 *       <em>opposite</em> sides. {@code oldDetails} stores the whole fifteen-byte
 *       value, because {@code 9000-READ-DATA} assigns it whole by
 *       {@code MOVE CUST-PHONE-NUM-1} at {@code app/cbl/COACTUPC.cbl:3876} and
 *       never assigns a part, and {@code 9700} compares it whole at
 *       {@code :4169-4170}. {@code newDetails} stores the three parts, because
 *       {@code 1100-RECEIVE-MAP} assigns only parts at {@code :1359-1396} and
 *       never assigns the whole, {@code 1205} compares the parts one at a time at
 *       {@code :1748-1753}, {@code 3000-SEND-MAP} returns them individually at
 *       {@code :2939-2944}, and the punctuated record value is assembled only at
 *       write time by {@code STRING} at {@code :4027-4041}. The layout is fixed by
 *       the REDEFINES at {@code app/cbl/COACTUPC.cbl:723-731} and {@code :811-819},
 *       whose fillers place a single byte before the area code, one between area
 *       code and prefix, one between prefix and line number, and two at the end.
 *       The filler content differs by group for the same reason: on the OLD side
 *       the separators arrive with the record, and every populated value in
 *       {@code app/data/ASCII/custdata.txt} resolves them &mdash; all 100
 *       telephone values across the 50 customer records read exactly
 *       {@code (NNN)NNN-NNNN} followed by two spaces &mdash; whereas on the NEW
 *       side {@code INITIALIZE ACUP-NEW-DETAILS} at {@code :1047} leaves them as
 *       spaces and nothing assigns them afterwards. Carrying both readings as
 *       independently writable properties would describe a byte state that
 *       cannot exist: a REDEFINES is one storage cell, so the whole and its
 *       parts can never disagree;</li>
 *   <li>(iv) each money field is carried once, as the {@code X(12)} display text
 *       the source stores, with the {@code S9(10)V99} numeric REDEFINES exposed as
 *       a derived view. The text is what round-trips, so the snapshot preserves the
 *       displayed representation and not merely the value, and the numeric reading
 *       cannot be made to contradict it;</li>
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
 *   </ul>
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
 * <p><strong>Both groups carry width contracts and both are marked {@code @Valid}
 * so they cascade.</strong> The OLD group contains zero {@code 88}-level condition
 * names ({@code app/cbl/COACTUPC.cbl:669-756}), so it carries no <em>domain</em>
 * rule, but that is not the same as carrying no constraint: every member has a
 * {@code PIC} clause, and a {@code PIC} clause is itself a width contract. A
 * three-character value cannot be placed in
 * {@code ACUP-OLD-CUST-ADDR-STATE-CD PIC X(02)} at {@code :719}, so enforcing that
 * width can only reject a snapshot the source was physically unable to produce.</p>
 * <p>The trust relationship is what makes the cascade worth keeping, and it is worth
 * stating precisely because an earlier revision of this class inverted it. In the source
 * the OLD group is filled by the program itself &mdash; {@code 9000-READ-DATA} does
 * {@code INITIALIZE ACUP-OLD-DETAILS} at {@code :3610} and then populates it from the
 * record it has just read &mdash; so it is trusted by construction. Here it is filled
 * from the sealed {@code snapshot} string this server itself minted on the preceding read
 * and re-attached through {@link #withOldDetails(OldDetails)}, so the same relationship
 * holds: the operand {@code 9700-CHECK-CHANGE-IN-REC} ({@code :4109-4193}) compares the
 * live record against is a value this server produced, never a value a client chose. The
 * widths therefore describe what this server may seal rather than what a client may send,
 * and they are retained as a second line of defence: a member wider than its {@code PIC}
 * clause is one the source could not physically have produced, so rejecting it can only
 * reject a snapshot the source never held.</p>
 * <p>The cascade deliberately does <strong>not</strong> import the NEW group's
 * domain rule. {@code 88 FICO-RANGE-IS-VALID} is declared on the NEW side only, so
 * {@link OldDetails} has no {@code ficoScoreIsInValidRange()} twin.</p>
 * <p>{@code newDetails} additionally carries the source's one range rule. The only
 * range in the source is
 * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}
 * ({@code app/cbl/COACTUPC.cbl:848-849}), declared on the NEW side only, and it
 * is enforced on the NEW side only. Note precisely which member that
 * {@code 88} level qualifies: it sits on the NUMERIC REDEFINES
 * {@code ACUP-NEW-CUST-FICO-SCORE} PIC 9(03) at {@code :846-847}, not on the
 * text member {@code ACUP-NEW-CUST-FICO-SCORE-X} PIC X(03) at {@code :845}, which
 * is the member the screen is moved into unvalidated at {@code :1283}.</p>
 * <p>The range is therefore <strong>not</strong> expressed as a bean constraint. It
 * is exposed as {@link NewDetails#ficoScoreIsInValidRange()}, a predicate over the
 * numeric reading, and the stored text member carries only its width contract. That
 * split is deliberate: the source stores whatever three characters were typed and
 * then emits its own message, so turning the range into a bean constraint would
 * substitute a framework rejection for the source's message and collapse the
 * three-state {@code app/cpy/CSSETATY.cpy} error model into a binary one. Everything
 * else is an exact width contract
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
 *       {@code expiraionDate}.</li>
 *   </ul>
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
 * A log-level masking rule is the second line of defence and
 * {@code src/main/resources/logback-spring.xml} supplies one, applied identically
 * in every profile; never emitting these values is still the first, because a mask
 * matches only the names and shapes it was given.</p>
 *
 * <h2>Consuming this payload</h2>
 * <p><strong>Binding.</strong> The payload arrives as JSON on the request body and is bound member by
 * member. The outer type and both nested groups are immutable: every instance field is {@code final},
 * no setter exists, and each type is built by a single all-arguments {@code @JsonCreator} constructor
 * whose every parameter carries an explicit {@code @JsonProperty} name. Binding therefore completes
 * before validation, and no value can change between validation and the snapshot comparison. Every
 * readable property round-trips through a plain serializer without depending on any framework leniency
 * setting. The compact-date, money, telephone-component and credit-score views are derived accessors
 * rather than properties, so they never appear in the wire format. The High-severity findings below
 * record why both invariants are load-bearing.</p>
 * <p><strong>Validation.</strong> Constraints cascade into <em>both</em> nested groups: each of
 * {@code oldDetails} and {@code newDetails} is marked {@code @Valid} and each member carries the width
 * contract of its declared PIC clause. Only the range rule is asymmetric, because only the NEW group
 * declares one. Validation is triggered by the consuming controller parameter, so on ingest it sees the
 * NEW group and the {@code snapshot} string only: {@code oldDetails} is absent from the bound instance
 * and is re-attached after the snapshot is opened, which is why its cascade governs what this server
 * may seal rather than what a client may send.</p>
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
 * {@code failOnWarning}, so a raw type, an unchecked cast or a deprecated call fails the build. Two
 * prohibitions are <em>not</em> enforced by {@code javac} and are stated here so they are not mistaken for
 * compiler gates. <strong>Documentation well-formedness is a Maven gate, but not a compiler one:</strong>
 * {@code javac} sees only {@code dangling-doc-comments}, so an unbalanced tag in this comment passes
 * compilation - and then fails {@code verify}, because {@code pom.xml} binds
 * {@code maven-javadoc-plugin} there as the execution {@code doclint-gate}, running
 * {@code javadoc-no-fork} with {@code doclint} set to {@code all} and {@code failOnWarnings} true over the
 * whole {@code src/main/java} tree. Reading {@code pom.xml} as declaring no {@code maven-javadoc-plugin}
 * and no {@code -Xdoclint}, so that an unbalanced tag fails no Maven phase, is therefore wrong: the gate is
 * bound and it fails the build. The explicit repository-owned doclint command published in
 * {@code docs/technical-specifications.md} remains useful because it also covers
 * {@code src/test/java}, which the bound execution does not. <strong>An unused-import check is
 * {@code Not available}:</strong> {@code javac} 25.0.3 publishes no {@code unused} lint key at all - as
 * {@code javac --help-lint} shows - and no Checkstyle or Error Prone analyser is in the pinned dependency
 * set, so {@code -Werror} cannot catch an unused import; the same is true of malformed Javadoc, of which
 * {@code javac} sees only {@code dangling-doc-comments}. Both prohibitions are therefore enforced by
 * review and by the separate doclint command, never by the compiler. Behavioural cover belongs to the
 * model unit tests under
 * {@code src/test/java/com/cardemo/unit/model}, whose mandatory regression is that the snapshot component
 * offsets 1/5/7 yield the same year, month and day as offsets 1/6/9 taken from the dash-separated live
 * form. That regression exists: {@code AccountUpdateRequestTest} asserts it from both directions -
 * {@code storesSnapshotDatesCompact} requires every snapshot date to be stored compact, and
 * {@code refusesDashSeparatedSnapshotDate} requires a dash-separated snapshot date to be refused because
 * the compact offsets cannot slice it, both citing
 * {@code app/cbl/COACTUPC.cbl:4174-4179}.</p>
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
 *   </ul>
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
 *   <li><strong>High &mdash; a REDEFINES overlay was exposed as two independently
 *       writable properties.</strong> Carrying both readings of every overlay
 *       as stored members - the {@code X(12)} text and the
 *       {@code S9(10)V99} number for each of the five money fields, the
 *       {@code X(15)} whole and the three components for each telephone number,
 *       and the {@code X(03)} text plus the {@code 9(03)} number for the credit
 *       score - lets a caller submit a text and a number that disagree, which
 *       is a byte state that cannot exist in the source, because a REDEFINES
 *       names one storage cell and not two members. Each overlay therefore
 *       carries exactly one stored member &mdash; the side the source actually
 *       assigns &mdash; and the other reading is a derived accessor
 *       deliberately not named as a bean property, so the serializer neither
 *       emits it nor binds it. Round-tripping is preserved, which is the
 *       objection this shape has to answer: the payload this class produces
 *       contains exactly the stored members, so it can be sent back unchanged.
 *       The stored side differs by group where the source differs &mdash; see
 *       consequence (iii) above &mdash; and an unrecognised property,
 *       including a derived view submitted as though it were a member, is
 *       rejected rather than silently discarded, because relying on a
 *       framework default that discards unknown properties is an
 *       environment-specific assumption. The credit-score range is exposed as a
 *       predicate rather than a constraint because
 *       {@code app/cbl/COACTUPC.cbl:848-849} declares the {@code 88} level on the
 *       {@code PIC 9(03)} REDEFINES at {@code :846-847} rather than on the
 *       {@code PIC X(03)} text member at {@code :845}, which is the member the
 *       screen is moved into unvalidated at {@code :1283}.</li>
 *   <li><strong>High &mdash; a payload mutable after validation.</strong>
 *       Exposing setters across the outer type and the two nested groups - 139
 *       of them, one per field - leaves a snapshot guard that can be rewritten
 *       between validation and comparison, which is not a guard, and on this
 *       payload the window spans the concurrency check that
 *       {@code 9700-CHECK-CHANGE-IN-REC} performs. Every field is therefore
 *       {@code final}, no setter is declared, and each type is constructed once
 *       by an all-arguments {@code @JsonCreator}.</li>
 *   <li><strong>High &mdash; the snapshot group was unvalidated.</strong> An
 *       earlier revision left {@code oldDetails} without a cascade and without a
 *       single width contract, on the argument that the OLD group declares no
 *       {@code 88} level. The argument does not hold: a {@code PIC} clause is
 *       itself a contract. Remediation: the cascade and the per-member widths
 *       described under Validation above.</li>
 *   <li><strong>Critical, closed &mdash; the snapshot group was caller-supplied.</strong>
 *       An earlier revision carried {@code oldDetails} as a readable wire member, so
 *       the operand of the concurrency comparison was chosen by the request the
 *       comparison exists to police, and the group's protected components reached the
 *       wire on the read that produced it. Remediation: the group left the wire
 *       contract entirely. The read seals it into the opaque {@code snapshot} string,
 *       the write echoes that string back, and the server opens it and re-attaches the
 *       group through {@link #withOldDetails(OldDetails)} before comparing. The widths
 *       above are retained and now bound what this server may seal.</li>
 *   <li><strong>Medium, closed &mdash; a second comparison paragraph, absent
 *       from prior-generation plan prose.</strong> That prose described one
 *       comparison paragraph; there are two, and they normalise the same fields
 *       differently. The current {@code docs/technical-specifications.md} states
 *       that there are two with different jobs, verified on 1 August 2026, so the
 *       gap is closed at the specification layer. {@code 1205-COMPARE-OLD-NEW}
 *       ({@code app/cbl/COACTUPC.cbl:1681-1777}) asks whether the user changed
 *       anything, comparing NEW against OLD; {@code 9700-CHECK-CHANGE-IN-REC}
 *       ({@code :4109-4193}) asks whether someone else changed the record,
 *       comparing the live record against the OLD snapshot. Remediation: this
 *       payload makes both representations of every such field <em>readable</em>
 *       &mdash; text and decimal for money, whole and parts for the telephone
 *       numbers, compact and component for the dates &mdash; by storing the side
 *       the source assigns and deriving the other, and it normalises nothing, so
 *       neither regime has to reconstruct what the other would have destroyed.
 *       Deriving rather than duplicating is what keeps the two readings from
 *       disagreeing; see the High-severity overlay finding above. The root
 *       {@code DECISION_LOG.md} the plan nominates for such findings is <strong>authored
 *       at the repository root</strong>. This docstring and the specification's section 0.2.2.1
 *       corrections table carry the finding at Medium, and no document is created in this
 *       package.</li>
 *   <li><strong>Medium, closed &mdash; the input-field census was
 *       overstated.</strong> Prior-generation plan prose reported 460 input fields
 *       across the seventeen symbolic maps and 36 for the account-view map; the
 *       specification now publishes 441 and 37. Counting the generated input fields of
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
 *   </ul>
 *
 * <p><strong>The dash-separated live form is confirmed by example, not merely by
 * construction.</strong> Every one of the 50 customer records in
 * {@code app/data/ASCII/custdata.txt} carries a populated, distinct
 * {@code YYYY-MM-DD} value in {@code CUST-DOB-YYYY-MM-DD}. The field is
 * {@code PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:19}, and summing the preceding
 * leaf pictures of that copybook places it at bytes 309 through 318 of the
 * 500-byte record, so it must be read position-aware rather than by scanning for
 * a pattern. Read that way, line 1 (customer {@code 000000001}) holds
 * {@code 1961-06-08} and line 50 (customer {@code 000000050}) holds
 * {@code 1960-12-01}; all 50 values are distinct, none is zero or blank, every
 * one carries a hyphen at positions 5 and 8, and the year components span 1960
 * to 2001.</p>
 *
 * <p>That example evidence and the structural evidence agree, which is what makes
 * the offset asymmetry this class implements safe to rely on. Structurally,
 * {@code 9700-CHECK-CHANGE-IN-REC} reads the live value at offsets 1, 6 and 9
 * ({@code app/cbl/COACTUPC.cbl:4174-4179}) — the year, month and day positions of
 * a ten-character value only when separator bytes occupy positions 5 and 8, which
 * the fixture rows above independently show they do. The snapshot side carries the
 * same date <em>without</em> separators and is therefore read at offsets 1, 5 and
 * 7. Comparing the two whole strings would report a change on every request; the
 * comparison is component-wise for exactly that reason. No value is invented here:
 * the compact snapshot form is stored exactly as its eight-character declaration
 * requires, and the dash-separated live form is never stored by this class at
 * all.</p>

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
     * Declared width of every compact snapshot date, from {@code PIC X(08)} at
     * {@code app/cbl/COACTUPC.cbl:684}, {@code :690}, {@code :696} and {@code :746}.
     */
    private static final int COMPACT_DATE_LENGTH = 8;

    /**
     * Upper bound on the sealed snapshot member, in characters.
     *
     * <p>Not a domain rule and not tuned to a measurement: the sealed form is
     * {@code base64url(12-byte nonce || AES-256-GCM ciphertext and tag)} over a JSON envelope holding
     * twenty-nine fixed-width members whose declared widths total under four hundred characters, so a value
     * this server issued cannot come near this bound. It exists so that an arbitrarily long string is refused
     * by bean validation before it reaches the base64 decoder and the cipher, which is the boundary check
     * Rule 1 Clause B requires of an untrusted body member.</p>
     */
    public static final int MAX_SNAPSHOT_LENGTH = 4096;

    /**
     * The characters a {@code PIC X(n)} free-text field can hold: {@code U+0020}-{@code U+007E} and
     * {@code U+00A0}-{@code U+00FF}.
     *
     * <p><strong>The field contract, not an invented rule.</strong> A {@code PIC X(n)} field is n
     * <em>bytes</em> in a single-byte code page, so a character with no single-byte representation is not a
     * value the field can hold. It matters here because the two address lines are the only free-text members
     * of this request that reach the customer record <em>without</em> a character-class check of their own:
     * {@code app/cbl/COACTUPC.cbl:1584-1590} validates the first address line for presence only - the source
     * comment in {@code AccountUpdateService} says so in as many words - and the second line the source does
     * not validate at all. Every other free-text member is already refused by the source's own rule, which is
     * why none of them carries this constraint: the three name members, the state, the city and the country
     * are alphabetic-only, and the postal code, the two telephone numbers, the social security number, the
     * date of birth, the funds-transfer account and the credit score are numeric.
     *
     * <p>What the absence cost is not hypothetical. {@code StatementProcessor} renders
     * {@code CUST-ADDR-LINE-1} and {@code CUST-ADDR-LINE-2} into the fixed-width statement lines of
     * {@code app/jcl/CREASTMT.JCL} - 80 bytes of text and 100 of markup, both encoded {@code ISO-8859-1} - so
     * an address line holding a character with no single-byte form is a statement run that fails on every
     * subsequent execution until an operator edits the row by hand. The same class of defect was reported
     * against the transaction-add boundary and is closed the same way, at the boundary the value enters
     * through.
     *
     * <p>The pattern is declared here rather than shared with {@code TransactionAddRequest}, which declares
     * its own. That mirrors this package's standing refusal to hoist a field contract into a shared type: the
     * contract belongs to the map whose field it describes, and a shared declaration would invite a future
     * edit to widen both at once.
     *
     * <p>An empty value matches, and {@code @Pattern} treats {@code null} as valid, so the absent, blank and
     * marked states this type distinguishes are untouched: the constraint fires on content, never on absence.
     */
    public static final String FIXED_WIDTH_TEXT_PATTERN = "[\\x20-\\x7E\\xA0-\\xFF]*";

    /**
     * Zero-based start of the year component, COBOL reference-modifier offset 1, per the snapshot comparison at
     * {@code app/cbl/COACTUPC.cbl:669-756}.
     */
    private static final int DATE_YEAR_BEGIN = 0;

    /**
     * Zero-based end of the year component; the year is {@code PIC X(4)}.
     */
    private static final int DATE_YEAR_END = 4;

    /**
     * Zero-based start of the month component, COBOL reference-modifier offset 5, per the snapshot comparison at
     * {@code app/cbl/COACTUPC.cbl:669-756}.
     */
    private static final int DATE_MONTH_BEGIN = 4;

    /**
     * Zero-based end of the month component; the month is {@code PIC X(2)}.
     */
    private static final int DATE_MONTH_END = 6;

    /**
     * Zero-based start of the day component, COBOL reference-modifier offset 7, per the snapshot comparison at
     * {@code app/cbl/COACTUPC.cbl:669-756}.
     */
    private static final int DATE_DAY_BEGIN = 6;

    /**
     * Zero-based end of the day component; the day is {@code PIC X(2)}.
     */
    private static final int DATE_DAY_END = 8;

    /**
     * Lowest credit score {@code 88 FICO-RANGE-IS-VALID} accepts, declared at
     * {@code app/cbl/COACTUPC.cbl:848} and tested at {@code app/cbl/COACTUPC.cbl:2515}.
     */
    private static final int FICO_SCORE_MINIMUM = 300;

    /**
     * Highest credit score {@code 88 FICO-RANGE-IS-VALID} accepts, declared at
     * {@code app/cbl/COACTUPC.cbl:848} and tested at {@code app/cbl/COACTUPC.cbl:2515}.
     */
    private static final int FICO_SCORE_MAXIMUM = 850;

    /** Digits after the decimal point in {@code PIC S9(10)V99}: the {@code V99} of the clause. */
    private static final int AMOUNT_SCALE = 2;

    /** Declared byte length of the snapshot money members, {@code PIC X(12)} / {@code PIC S9(10)V99}. */
    private static final int AMOUNT_LENGTH = 12;

    /** Declared byte length of the snapshot credit-score members, {@code PIC X(03)} / {@code PIC 9(03)}. */
    private static final int FICO_SCORE_LENGTH = 3;

    /** Declared byte length of the snapshot telephone members, {@code PIC X(15)}. */
    private static final int PHONE_LENGTH = 15;

    /** Zero-based start of the area code inside the telephone overlay; COBOL offset 2, after one filler byte. */
    private static final int PHONE_AREA_CODE_BEGIN = 1;

    /** Zero-based end of the area code; the part is {@code PIC X(3)}. */
    private static final int PHONE_AREA_CODE_END = 4;

    /** Zero-based start of the exchange prefix inside the telephone overlay; COBOL offset 6. */
    private static final int PHONE_PREFIX_BEGIN = 5;

    /** Zero-based end of the exchange prefix; the part is {@code PIC X(3)}. */
    private static final int PHONE_PREFIX_END = 8;

    /** Zero-based start of the line number inside the telephone overlay; COBOL offset 10. */
    private static final int PHONE_LINE_NUMBER_BEGIN = 9;

    /** Zero-based end of the line number; the part is {@code PIC X(4)}. */
    private static final int PHONE_LINE_NUMBER_END = 13;

    /** Zoned-decimal overpunch for a positive final digit of zero. */
    private static final char OVERPUNCH_POSITIVE_ZERO = '{';

    /** Zoned-decimal overpunch for a negative final digit of zero. */
    private static final char OVERPUNCH_NEGATIVE_ZERO = '}';

    /** Lowest zoned-decimal overpunch letter carrying a positive sign; {@code A} through {@code I} are 1 to 9. */
    private static final char OVERPUNCH_POSITIVE_FIRST = 'A';

    /** Highest zoned-decimal overpunch letter carrying a positive sign. */
    private static final char OVERPUNCH_POSITIVE_LAST = 'I';

    /** Lowest zoned-decimal overpunch letter carrying a negative sign; {@code J} through {@code R} are 1 to 9. */
    private static final char OVERPUNCH_NEGATIVE_FIRST = 'J';

    /** Highest zoned-decimal overpunch letter carrying a negative sign. */
    private static final char OVERPUNCH_NEGATIVE_LAST = 'R';

    /**
     * Extracts one component of a compact eight-character snapshot date without modifying, trimming or
     * case-folding the stored value.
     *
     * @param compactDate the stored eight-character value.
     * @param beginIndex zero-based, inclusive start of the component
     * @param endIndex zero-based, exclusive end of the component
     * @param fieldName name of the member being read, used in the failure message.
     * @return the requested component, {@code null} when the backing value is absent, or the empty string when
     * the backing value does not reach the component
     * @throws IllegalArgumentException when the backing value is longer than the declared eight characters and
     * therefore is not the compact form
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

    /**
     * Reads a snapshot money member through its {@code PIC S9(10)V99} REDEFINES view.
     *
     * <p>A REDEFINES is not a second field. {@code ACUP-OLD-CURR-BAL PIC X(12)} at
     * {@code app/cbl/COACTUPC.cbl:675} and {@code ACUP-OLD-CURR-BAL-N PIC S9(10)V99} at
     * {@code :676-677} name the <em>same twelve bytes</em>, and every money member of both
     * snapshot groups is declared that way. This method therefore interprets the stored text
     * rather than consulting a parallel member: there is no parallel member to consult, because
     * a state in which the text and the number disagree cannot exist in the source and must not
     * be constructible here.</p>
     *
     * <p><strong>Why the bytes are zoned decimal.</strong> The OLD group is filled by
     * {@code MOVE ACCT-CURR-BAL TO ACUP-OLD-CURR-BAL-N} at {@code app/cbl/COACTUPC.cbl:3821},
     * and the NEW group by {@code COMPUTE ACUP-NEW-CURR-BAL-N = FUNCTION NUMVAL-C(...)} at
     * {@code :1107}. Both write through the <em>signed numeric</em> view of a
     * {@code DISPLAY}-usage field, so the twelve bytes carry eleven leading digits followed by a
     * trailing overpunch character that encodes both the final digit and the sign. That is the
     * same encoding the seed fixtures use: {@code app/data/ASCII/acctdata.txt:1} records
     * {@code 00000001940&#123;} for a balance of {@code +194.00}.</p>
     *
     * <p>The decode table is the standard one: <code>&#123;</code> is {@code +0},
     * {@code A} through {@code I} are {@code +1} to {@code +9}, <code>&#125;</code> is
     * {@code -0}, and {@code J} through {@code R} are {@code -1} to {@code -9}. A plain digit in
     * the final position is read as unsigned, which is what an unsigned {@code MOVE} leaves
     * there.</p>
     *
     * <p>The method is total: it never throws and never mutates. Any image that is not a
     * zoned-decimal number of the declared length &mdash; absent, blank, low-values, or the raw
     * free-text a caller might mistakenly send &mdash; yields {@code null}, so the absent, blank
     * and invalid states all still reach the service and still receive the source's own distinct
     * messages rather than a framework error. Rejecting them here would collapse the three-state
     * model of {@code app/cpy/CSSETATY.cpy:17-27} into one.</p>
     *
     * @param image the stored twelve-character member; may be {@code null}
     * @return the amount at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
     *         not a zoned-decimal image of the declared length
     */
    private static BigDecimal zonedDecimalAmount(final String image) {
        if (image == null || image.length() != AMOUNT_LENGTH) {
            return null;
        }
        final char sign = image.charAt(AMOUNT_LENGTH - 1);
        final boolean negative = sign == OVERPUNCH_NEGATIVE_ZERO
                || (sign >= OVERPUNCH_NEGATIVE_FIRST && sign <= OVERPUNCH_NEGATIVE_LAST);
        final char finalDigit;
        if (sign >= '0' && sign <= '9') {
            finalDigit = sign;
        } else if (sign == OVERPUNCH_POSITIVE_ZERO || sign == OVERPUNCH_NEGATIVE_ZERO) {
            finalDigit = '0';
        } else if (sign >= OVERPUNCH_POSITIVE_FIRST && sign <= OVERPUNCH_POSITIVE_LAST) {
            finalDigit = (char) ('1' + (sign - OVERPUNCH_POSITIVE_FIRST));
        } else if (sign >= OVERPUNCH_NEGATIVE_FIRST && sign <= OVERPUNCH_NEGATIVE_LAST) {
            finalDigit = (char) ('1' + (sign - OVERPUNCH_NEGATIVE_FIRST));
        } else {
            return null;
        }
        final StringBuilder digits = new StringBuilder(AMOUNT_LENGTH);
        for (int index = 0; index < AMOUNT_LENGTH - 1; index++) {
            final char current = image.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
            digits.append(current);
        }
        digits.append(finalDigit);
        final BigDecimal magnitude = new BigDecimal(digits.toString()).movePointLeft(AMOUNT_SCALE);
        return negative ? magnitude.negate() : magnitude;
    }

    /**
     * Reads one component of a snapshot telephone member through its REDEFINES view.
     *
     * <p>{@code ACUP-OLD-CUST-PHONE-NUM-1 PIC X(15)} at {@code app/cbl/COACTUPC.cbl:722} is
     * redefined at {@code :723-731} as {@code FILLER X(1)}, area code {@code X(3)},
     * {@code FILLER X(1)}, prefix {@code X(3)}, {@code FILLER X(1)}, line number {@code X(4)},
     * {@code FILLER X(2)} &mdash; fifteen bytes in total, holding the punctuated
     * {@code (nnn)nnn-nnnn} rendering. The three filler bytes are the parentheses and the hyphen,
     * which is why the components sit at COBOL offsets 2, 6 and 10 rather than 1, 4 and 7.</p>
     *
     * <p>As with the money members these are views over one storage cell, not independent
     * members, so they are derived here instead of being carried as separate JSON properties. A
     * caller cannot submit an area code that contradicts the number it is part of, because in the
     * source no such state exists.</p>
     *
     * @param phoneNumber the stored fifteen-character member; may be {@code null}
     * @param beginIndex  zero-based, inclusive start of the component
     * @param endIndex    zero-based, exclusive end of the component
     * @param fieldName   name of the member being read, used in the failure message; the offending
     *                    value is deliberately never included because this object carries
     *                    personally identifiable data
     * @return the requested component, {@code null} when the stored member is absent, or the empty
     *         string when the stored member does not reach the component
     * @throws IllegalArgumentException when the stored member is longer than the declared fifteen
     *         characters and therefore is not the punctuated form the overlay describes
     */
    private static String phoneNumberPart(final String phoneNumber, final int beginIndex,
            final int endIndex, final String fieldName) {
        if (phoneNumber == null) {
            return null;
        }
        if (phoneNumber.length() > PHONE_LENGTH) {
            throw new IllegalArgumentException(fieldName
                    + " must hold at most " + PHONE_LENGTH
                    + " characters, because app/cbl/COACTUPC.cbl declares the snapshot telephone"
                    + " member as PIC X(15) whose REDEFINES places the area code, prefix and line"
                    + " number at offsets 2, 6 and 10; those offsets cannot be applied to a longer"
                    + " value. The offending value is not echoed because this request carries"
                    + " personally identifiable data.");
        }
        if (phoneNumber.length() <= beginIndex) {
            return "";
        }
        return phoneNumber.substring(beginIndex, Math.min(endIndex, phoneNumber.length()));
    }

    /**
     * Assembles the fifteen-byte telephone overlay image from its three component parts.
     *
     * <p>This is the inverse of {@link #phoneNumberPart(String, int, int, String)} and exists because the
     * two snapshot groups store opposite ends of the same overlay. {@code ACUP-OLD-CUST-PHONE-NUM-1} is
     * assigned as a whole, by {@code MOVE CUST-PHONE-NUM-1} at {@code app/cbl/COACTUPC.cbl:3876}, and its
     * parts are never assigned; {@code ACUP-NEW-CUST-PHONE-NUM-1A}, {@code -1B} and {@code -1C} are assigned
     * individually from the screen at {@code :1359-1375}, and their whole is never assigned. Each group
     * therefore stores what the source stores and derives the other reading, which is the only arrangement
     * in which a caller cannot submit a whole that contradicts its own parts.</p>
     *
     * <p>Each part is placed at the offset its {@code FILLER}-separated overlay assigns it
     * ({@code app/cbl/COACTUPC.cbl:811-819}) and padded on the right to its declared width, which is what
     * {@code MOVE} to a fixed-width alphanumeric item does. The three separator positions and the two
     * trailing positions are emitted as spaces, because {@code INITIALIZE ACUP-NEW-DETAILS} at {@code :1047}
     * sets them to spaces and nothing assigns them afterwards. An absent part contributes its own width in
     * spaces for the same reason.</p>
     *
     * @param areaCode   the three-character area code component; may be {@code null}
     * @param prefix     the three-character exchange prefix component; may be {@code null}
     * @param lineNumber the four-character line number component; may be {@code null}
     * @param fieldName  name of the whole being assembled, used in the failure message; the offending value
     *                   is deliberately never included because this object carries personally identifiable
     *                   data
     * @return the fifteen-character overlay image
     * @throws IllegalArgumentException when any part is longer than its declared width and therefore cannot
     *         be placed at the offset the overlay assigns it
     */
    private static String overlayTelephoneImage(final String areaCode, final String prefix,
            final String lineNumber, final String fieldName) {
        final StringBuilder image = new StringBuilder(PHONE_LENGTH);
        image.append(' ');
        appendOverlayPart(image, areaCode, PHONE_AREA_CODE_END - PHONE_AREA_CODE_BEGIN, fieldName);
        image.append(' ');
        appendOverlayPart(image, prefix, PHONE_PREFIX_END - PHONE_PREFIX_BEGIN, fieldName);
        image.append(' ');
        appendOverlayPart(image, lineNumber, PHONE_LINE_NUMBER_END - PHONE_LINE_NUMBER_BEGIN, fieldName);
        image.append("  ");
        return image.toString();
    }

    /**
     * Appends one component of a telephone overlay, right-padded with spaces to its declared width.
     *
     * <p><strong>This width is measured in {@code char} values, deliberately, and unlike the width guards
     * on the response projections.</strong> Those guards bound a declared {@code PIC X(n)} field against a
     * value that has round-tripped through a {@code character(n)} column, so they count character positions
     * as code points. This method is not that: it is assembling a positional image whose component offsets
     * are {@code substring} indices, and the padding it emits is computed from the same count it checks.
     * Measuring code points here while indexing in code units would make the check and the padding disagree
     * and could produce a negative repeat count. The three components are the numeric parts of a telephone
     * number, for which the two units coincide in any case.</p>
     *
     * @param image     the overlay image being assembled
     * @param part      the component value; may be {@code null}, in which case its width is emitted as
     *                  spaces
     * @param width     the component's declared {@code PIC X(n)} width
     * @param fieldName name of the whole being assembled, used in the failure message
     * @throws IllegalArgumentException when the component is longer than its declared width
     */
    private static void appendOverlayPart(final StringBuilder image, final String part, final int width,
            final String fieldName) {
        final String value = part == null ? "" : part;
        if (value.length() > width) {
            throw new IllegalArgumentException(fieldName
                    + " cannot be assembled, because one of its components holds " + value.length()
                    + " characters where app/cbl/COACTUPC.cbl:811-819 declares " + width
                    + "; the overlay has no room for it at the offset the REDEFINES assigns."
                    + " The offending value is not echoed because this request carries personally"
                    + " identifiable data.");
        }
        image.append(value);
        image.append(" ".repeat(width - value.length()));
    }

    /**
     * Reads a snapshot credit score through its {@code PIC 9(03)} REDEFINES view.
     *
     * <p>{@code ACUP-OLD-CUST-FICO-SCORE-X PIC X(03)} at {@code app/cbl/COACTUPC.cbl:754} and
     * {@code ACUP-OLD-CUST-FICO-SCORE PIC 9(03)} at {@code :755-756} are the same three bytes;
     * the NEW group repeats the pair at {@code :845} and {@code :846-847}. The clause is
     * <em>unsigned</em>, so unlike the money members there is no overpunch: the numeric reading is
     * three decimal digits or nothing.</p>
     *
     * <p>Both readings are genuinely used by the source, which is why the view exists rather than
     * being left implicit. {@code 1205-COMPARE-OLD-NEW} compares the <em>text</em> members at
     * {@code app/cbl/COACTUPC.cbl:1767-1768}, while {@code 9700-CHECK-CHANGE-IN-REC} compares the
     * live record against the <em>numeric</em> member at {@code :4186}. One storage cell, two
     * readings, two paragraphs.</p>
     *
     * <p>The method is total and never throws. The NEW group's member is raw screen input, moved
     * unvalidated by {@code MOVE ACSTFCOI OF CACTUPAI TO ACUP-NEW-CUST-FICO-SCORE-X} at
     * {@code :1283}, so a non-numeric value is a state the source accepts into storage and then
     * reports through its own message. Returning {@code null} preserves that; throwing would
     * replace the source's message with a framework error.</p>
     *
     * @param image the stored three-character member; may be {@code null}
     * @return the score, or {@code null} when the stored member is not three decimal digits
     */
    private static Integer unsignedDisplayScore(final String image) {
        if (image == null || image.length() != FICO_SCORE_LENGTH) {
            return null;
        }
        for (int index = 0; index < FICO_SCORE_LENGTH; index++) {
            final char current = image.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        return Integer.valueOf(image);
    }

    // Screen fields, in the declaration order of app/cpy-bms/COACTUP.CPY.
    // All 54 of them, including the three ordering quirks the map declares:
    // the state code between the two address lines, the city after the
    // postal code, and the government-issued identifier interleaved between
    // the two telephone numbers.

    /**
     * {@code TRNNAMEI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:24}. Transaction identifier echoed into
     * the screen header.
     */
    @Size(max = 4,
            message = "transactionName must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:24)")
    private final String transactionName;

    /**
     * {@code TITLE01I} PIC X(40) &mdash; {@code app/cpy-bms/COACTUP.CPY:30}. First header title line.
     */
    @Size(max = 40,
            message = "title01 must not exceed its declared width of 40 characters"
                    + " (app/cpy-bms/COACTUP.CPY:30)")
    private final String title01;

    /**
     * {@code CURDATEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:36}. Header date as rendered on the
     * screen.
     */
    @Size(max = 8,
            message = "currentDate must not exceed its declared width of 8 characters"
                    + " (app/cpy-bms/COACTUP.CPY:36)")
    private final String currentDate;

    /**
     * {@code PGMNAMEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:42}. Name of the program that produced
     * the screen.
     */
    @Size(max = 8,
            message = "programName must not exceed its declared width of 8 characters"
                    + " (app/cpy-bms/COACTUP.CPY:42)")
    private final String programName;

    /**
     * {@code TITLE02I} PIC X(40) &mdash; {@code app/cpy-bms/COACTUP.CPY:48}. Second header title line.
     */
    @Size(max = 40,
            message = "title02 must not exceed its declared width of 40 characters"
                    + " (app/cpy-bms/COACTUP.CPY:48)")
    private final String title02;

    /**
     * {@code CURTIMEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:54}. Header time as rendered on the
     * screen. Eight characters here; the sign-on map declares nine at {@code app/cpy-bms/COSGN00.CPY:54}.
     */
    @Size(max = 8,
            message = "currentTime must not exceed its declared width of 8 characters"
                    + " (app/cpy-bms/COACTUP.CPY:54)")
    private final String currentTime;

    /**
     * {@code ACCTSIDI} PIC X(11) &mdash; {@code app/cpy-bms/COACTUP.CPY:60}. Account identifier. Text here; the
     * identically named field is numeric {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60}.
     */
    @Size(max = 11,
            message = "accountId must not exceed its declared width of 11 characters"
                    + " (app/cpy-bms/COACTUP.CPY:60)")
    private final String accountId;

    /**
     * {@code ACSTTUSI} PIC X(1) &mdash; {@code app/cpy-bms/COACTUP.CPY:66}. Account active status, a raw
     * one-character code and never an enum.
     */
    @Size(max = 1,
            message = "accountStatus must not exceed its declared width of 1 character"
                    + " (app/cpy-bms/COACTUP.CPY:66)")
    private final String accountStatus;

    /**
     * {@code OPNYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:72}. Year component of the account open
     * date.
     */
    @Size(max = 4,
            message = "openDateYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:72)")
    private final String openDateYear;

    /**
     * {@code OPNMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:78}. Month component of the account open
     * date.
     */
    @Size(max = 2,
            message = "openDateMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:78)")
    private final String openDateMonth;

    /**
     * {@code OPNDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:84}. Day component of the account open
     * date.
     */
    @Size(max = 2,
            message = "openDateDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:84)")
    private final String openDateDay;

    /**
     * {@code ACRDLIMI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:90}. Credit limit as displayed. Fifteen
     * characters on the screen against the twelve-character snapshot form at {@code app/cbl/COACTUPC.cbl:678}.
     */
    @Size(max = 15,
            message = "creditLimit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:90)")
    private final String creditLimit;

    /**
     * {@code EXPYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:96}. Year component of the account
     * expiry date.
     */
    @Size(max = 4,
            message = "expiryDateYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:96)")
    private final String expiryDateYear;

    /**
     * {@code EXPMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:102}. Month component of the account
     * expiry date.
     */
    @Size(max = 2,
            message = "expiryDateMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:102)")
    private final String expiryDateMonth;

    /**
     * {@code EXPDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:108}. Day component of the account expiry
     * date.
     */
    @Size(max = 2,
            message = "expiryDateDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:108)")
    private final String expiryDateDay;

    /**
     * {@code ACSHLIMI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:114}. Cash credit limit as displayed.
     */
    @Size(max = 15,
            message = "cashCreditLimit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:114)")
    private final String cashCreditLimit;

    /**
     * {@code RISYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:120}. Year component of the account
     * reissue date.
     */
    @Size(max = 4,
            message = "reissueDateYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:120)")
    private final String reissueDateYear;

    /**
     * {@code RISMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:126}. Month component of the account
     * reissue date.
     */
    @Size(max = 2,
            message = "reissueDateMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:126)")
    private final String reissueDateMonth;

    /**
     * {@code RISDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:132}. Day component of the account
     * reissue date.
     */
    @Size(max = 2,
            message = "reissueDateDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:132)")
    private final String reissueDateDay;

    /**
     * {@code ACURBALI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:138}. Current balance as displayed.
     */
    @Size(max = 15,
            message = "currentBalance must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:138)")
    private final String currentBalance;

    /**
     * {@code ACRCYCRI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:144}. Current cycle credit as
     * displayed.
     */
    @Size(max = 15,
            message = "currentCycleCredit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:144)")
    private final String currentCycleCredit;

    /**
     * {@code AADDGRPI} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:150}. Account group identifier, carried
     * raw because the two comparison regimes case-fold it in opposite directions, upper at
     * {@code app/cbl/COACTUPC.cbl:1697-1700} and lower at {@code :4139-4140}.
     */
    @Size(max = 10,
            message = "accountGroupId must not exceed its declared width of 10 characters"
                    + " (app/cpy-bms/COACTUP.CPY:150)")
    private final String accountGroupId;

    /**
     * {@code ACRCYDBI} PIC X(15) &mdash; {@code app/cpy-bms/COACTUP.CPY:156}. Current cycle debit as displayed.
     * The source lets this accumulator hold negative amounts, so no sign normalisation is applied.
     */
    @Size(max = 15,
            message = "currentCycleDebit must not exceed its declared width of 15 characters"
                    + " (app/cpy-bms/COACTUP.CPY:156)")
    private final String currentCycleDebit;

    /**
     * {@code ACSTNUMI} PIC X(9) &mdash; {@code app/cpy-bms/COACTUP.CPY:162}. Customer identifier.
     */
    @Size(max = 9,
            message = "customerId must not exceed its declared width of 9 characters"
                    + " (app/cpy-bms/COACTUP.CPY:162)")
    private final String customerId;

    /**
     * {@code ACTSSN1I} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:168}. First part of the social security
     * number. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1235}.
     */
    @Size(max = 3,
            message = "customerSsnPart1 must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:168)")
    private final String customerSsnPart1;

    /**
     * {@code ACTSSN2I} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:174}. Second part of the social security
     * number. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1242}.
     */
    @Size(max = 2,
            message = "customerSsnPart2 must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:174)")
    private final String customerSsnPart2;

    /**
     * {@code ACTSSN3I} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:180}. Third part of the social security
     * number. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1249}.
     */
    @Size(max = 4,
            message = "customerSsnPart3 must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:180)")
    private final String customerSsnPart3;

    /**
     * {@code DOBYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:186}. Year component of the date of
     * birth. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1258}.
     */
    @Size(max = 4,
            message = "dateOfBirthYear must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:186)")
    private final String dateOfBirthYear;

    /**
     * {@code DOBMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:192}. Month component of the date of
     * birth. Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1265}.
     */
    @Size(max = 2,
            message = "dateOfBirthMonth must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:192)")
    private final String dateOfBirthMonth;

    /**
     * {@code DOBDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:198}. Day component of the date of birth.
     * Blank or a single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1272}.
     */
    @Size(max = 2,
            message = "dateOfBirthDay must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:198)")
    private final String dateOfBirthDay;

    /**
     * {@code ACSTFCOI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:204}. Credit score as keyed. Blank or a
     * single asterisk here becomes {@code LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:1281}.
     */
    @Size(max = 3,
            message = "customerFicoScore must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:204)")
    private final String customerFicoScore;

    /**
     * {@code ACSFNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:210}. Customer first name.
     */
    @Size(max = 25,
            message = "customerFirstName must not exceed its declared width of 25 characters"
                    + " (app/cpy-bms/COACTUP.CPY:210)")
    private final String customerFirstName;

    /**
     * {@code ACSMNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:216}. Customer middle name.
     */
    @Size(max = 25,
            message = "customerMiddleName must not exceed its declared width of 25 characters"
                    + " (app/cpy-bms/COACTUP.CPY:216)")
    private final String customerMiddleName;

    /**
     * {@code ACSLNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:222}. Customer last name.
     */
    @Size(max = 25,
            message = "customerLastName must not exceed its declared width of 25 characters"
                    + " (app/cpy-bms/COACTUP.CPY:222)")
    private final String customerLastName;

    /**
     * {@code ACSADL1I} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:228}. First address line.
     */
    @Size(max = 50,
            message = "addressLine1 must not exceed its declared width of 50 characters"
                    + " (app/cpy-bms/COACTUP.CPY:228)")
    @Pattern(regexp = FIXED_WIDTH_TEXT_PATTERN,
            message = "addressLine1 must contain only characters representable in the fixed-width record"
                    + " (U+0020-U+007E, U+00A0-U+00FF)")
    private final String addressLine1;

    /**
     * {@code ACSSTTEI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:234}. State code. The source declares it
     * between the two address lines and that order is preserved.
     */
    @Size(max = 2,
            message = "addressStateCode must not exceed its declared width of 2 characters"
                    + " (app/cpy-bms/COACTUP.CPY:234)")
    private final String addressStateCode;

    /**
     * {@code ACSADL2I} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:240}. Second address line.
     */
    @Size(max = 50,
            message = "addressLine2 must not exceed its declared width of 50 characters"
                    + " (app/cpy-bms/COACTUP.CPY:240)")
    @Pattern(regexp = FIXED_WIDTH_TEXT_PATTERN,
            message = "addressLine2 must contain only characters representable in the fixed-width record"
                    + " (U+0020-U+007E, U+00A0-U+00FF)")
    private final String addressLine2;

    /**
     * {@code ACSZIPCI} PIC X(5) &mdash; {@code app/cpy-bms/COACTUP.CPY:246}. Postal code. Five characters on
     * the screen against the ten-character snapshot form at {@code app/cbl/COACTUPC.cbl:721} and the record
     * form at {@code app/cpy/CVCUS01Y.cpy:14}.
     */
    @Size(max = 5,
            message = "addressZip must not exceed its declared width of 5 characters"
                    + " (app/cpy-bms/COACTUP.CPY:246)")
    private final String addressZip;

    /**
     * {@code ACSCITYI} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:252}. City, declared after the postal
     * code in the source and kept in that order. It corresponds to the third address line of the snapshot
     * groups, {@code ACUP-OLD-CUST-ADDR-LINE-3} at {@code app/cbl/COACTUPC.cbl:718}.
     */
    @Size(max = 50,
            message = "addressCity must not exceed its declared width of 50 characters"
                    + " (app/cpy-bms/COACTUP.CPY:252)")
    private final String addressCity;

    /**
     * {@code ACSCTRYI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:258}. Country code.
     */
    @Size(max = 3,
            message = "addressCountryCode must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:258)")
    private final String addressCountryCode;

    /**
     * {@code ACSPH1AI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:264}. Area code of the first telephone
     * number.
     */
    @Size(max = 3,
            message = "phone1AreaCode must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:264)")
    private final String phone1AreaCode;

    /**
     * {@code ACSPH1BI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:270}. Prefix of the first telephone
     * number.
     */
    @Size(max = 3,
            message = "phone1Prefix must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:270)")
    private final String phone1Prefix;

    /**
     * {@code ACSPH1CI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:276}. Line number of the first telephone
     * number.
     */
    @Size(max = 4,
            message = "phone1LineNumber must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:276)")
    private final String phone1LineNumber;

    /**
     * {@code ACSGOVTI} PIC X(20) &mdash; {@code app/cpy-bms/COACTUP.CPY:282}. Government-issued identifier. The
     * source interleaves it between the two telephone numbers and that order is preserved.
     */
    @Size(max = 20,
            message = "governmentIssuedId must not exceed its declared width of 20 characters"
                    + " (app/cpy-bms/COACTUP.CPY:282)")
    private final String governmentIssuedId;

    /**
     * {@code ACSPH2AI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:288}. Area code of the second telephone
     * number.
     */
    @Size(max = 3,
            message = "phone2AreaCode must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:288)")
    private final String phone2AreaCode;

    /**
     * {@code ACSPH2BI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:294}. Prefix of the second telephone
     * number.
     */
    @Size(max = 3,
            message = "phone2Prefix must not exceed its declared width of 3 characters"
                    + " (app/cpy-bms/COACTUP.CPY:294)")
    private final String phone2Prefix;

    /**
     * {@code ACSPH2CI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:300}. Line number of the second
     * telephone number.
     */
    @Size(max = 4,
            message = "phone2LineNumber must not exceed its declared width of 4 characters"
                    + " (app/cpy-bms/COACTUP.CPY:300)")
    private final String phone2LineNumber;

    /**
     * {@code ACSEFTCI} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:306}. Electronic funds transfer account
     * identifier, edited by {@code 1245-EDIT-NUM-REQD} under the label {@code 'EFT Account Id'} at
     * {@code app/cbl/COACTUPC.cbl:1648}.
     */
    @Size(max = 10,
            message = "eftAccountId must not exceed its declared width of 10 characters"
                    + " (app/cpy-bms/COACTUP.CPY:306)")
    private final String eftAccountId;

    /**
     * {@code ACSPFLGI} PIC X(1) &mdash; {@code app/cpy-bms/COACTUP.CPY:312}. Primary card holder indicator, a
     * raw one-character code edited by {@code 1220-EDIT-YESNO} under the label {@code 'Primary Card Holder'} at
     * {@code app/cbl/COACTUPC.cbl:1657}.
     */
    @Size(max = 1,
            message = "primaryCardHolderIndicator must not exceed its declared width of 1 character"
                    + " (app/cpy-bms/COACTUP.CPY:312)")
    private final String primaryCardHolderIndicator;

    /**
     * {@code INFOMSGI} PIC X(45) &mdash; {@code app/cpy-bms/COACTUP.CPY:318}. Informational message line, the
     * carrier for literals such as {@code 'Looks Good.... so far'} ({@code app/cbl/COACTUPC.cbl:527-528}).
     */
    @Size(max = 45,
            message = "informationMessage must not exceed its declared width of 45 characters"
                    + " (app/cpy-bms/COACTUP.CPY:318)")
    private final String informationMessage;

    /**
     * {@code ERRMSGI} PIC X(78) &mdash; {@code app/cpy-bms/COACTUP.CPY:324}. Error message line, the carrier
     * for literals such as {@code 'Record changed by some one else. Please review'}
     * ({@code app/cbl/COACTUPC.cbl:521-522}).
     */
    @Size(max = 78,
            message = "errorMessage must not exceed its declared width of 78 characters"
                    + " (app/cpy-bms/COACTUP.CPY:324)")
    private final String errorMessage;

    /**
     * {@code FKEYSI} PIC X(21) &mdash; {@code app/cpy-bms/COACTUP.CPY:330}. Function key legend line.
     */
    @Size(max = 21,
            message = "functionKeys must not exceed its declared width of 21 characters"
                    + " (app/cpy-bms/COACTUP.CPY:330)")
    private final String functionKeys;

    /**
     * {@code FKEY05I} PIC X(7) &mdash; {@code app/cpy-bms/COACTUP.CPY:336}. Legend for the fifth function key.
     */
    @Size(max = 7,
            message = "functionKey05 must not exceed its declared width of 7 characters"
                    + " (app/cpy-bms/COACTUP.CPY:336)")
    private final String functionKey05;

    /**
     * {@code FKEY12I} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:342}. Legend for the twelfth function
     * key.
     */
    @Size(max = 10,
            message = "functionKey12 must not exceed its declared width of 10 characters"
                    + " (app/cpy-bms/COACTUP.CPY:342)")
    private final String functionKey12;

    /**
     * The sealed as-displayed snapshot, exactly as the preceding read returned it.
     *
     * <p>This is the wire carrier for {@code ACUP-OLD-DETAILS} at
     * {@code app/cbl/COACTUPC.cbl:669-756}: one opaque base64url string produced by
     * {@code com.cardemo.security.SnapshotTokenService}, which the update service opens to recover the group
     * the read projected. A caller can neither read nor alter it, cannot present one issued for another
     * account, cannot present one issued to another principal, and cannot present one indefinitely.</p>
     *
     * <p>The declared bound is a shape check rather than a domain rule. The sealed form is
     * {@code base64url(nonce || AES-256-GCM(JSON envelope))} over a group of twenty-nine fixed-width members,
     * which cannot approach {@value #MAX_SNAPSHOT_LENGTH} characters; a longer value therefore did not come
     * from this server, and refusing it here keeps an unbounded string out of the cipher and the parser
     * (Rule 1 Clause B). Nothing is trimmed, folded or defaulted - a blank value stays blank and is reported
     * by the service as an absent snapshot, which is a different outcome from one that fails to open.</p>
     */
    @Size(max = MAX_SNAPSHOT_LENGTH,
            message = "snapshot must not exceed " + MAX_SNAPSHOT_LENGTH + " characters; a longer value was"
                    + " not issued by this server")
    private final String snapshot;

    /**
     * The opened as-displayed snapshot, present only on a screen this service projected internally.
     *
     * <p><strong>This member is not part of the wire contract in either direction.</strong> It carries no
     * {@code @JsonProperty}, the wire constructor always leaves it {@code null}, and
     * {@link #getOldDetails()} is annotated {@code @JsonIgnore}; the only way to populate it is
     * {@link #withOldDetails(OldDetails)}, which the account-update service calls when it projects a screen
     * for its own use. Its purpose is that this type doubles as the projected screen inside
     * {@code com.cardemo.service.account.AccountUpdateService} - {@code 9500-STORE-FETCHED-DATA} at
     * {@code app/cbl/COACTUPC.cbl:3805-3813} stores the group the read found, and the projection has to carry
     * it so the read entry point can seal it.</p>
     *
     * <p>In the source this group is never input: {@code 9000-READ-DATA} issues
     * {@code INITIALIZE ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl:3610} and then fills it member by
     * member from the record the program itself has just read, so it is trusted by construction. Holding it
     * off the wire restores exactly that relationship: the operand
     * {@code 9700-CHECK-CHANGE-IN-REC} ({@code :4109-4193}) compares against the live record is a value this
     * server produced, sealed and recovered, and never a value a client chose.</p>
     *
     * <p>The width contracts declared on {@link OldDetails} are retained, and so is the {@code @Valid}
     * cascade, so that they still apply on any validated instance that does carry the group. What they now
     * describe is what this server may seal rather than what a client may send: a member wider than its
     * {@code PIC} clause is one the source could not physically have produced. They deliberately do not
     * import the NEW group's domain rule - the single range in the source,
     * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at {@code app/cbl/COACTUPC.cbl:848-849}, is
     * declared on the NEW group's credit score and on nothing else, so {@link OldDetails} has no
     * {@link NewDetails#ficoScoreIsInValidRange()} twin. Widths are enforced; domains are not invented.</p>
     */
    @Valid
    private final OldDetails oldDetails;

    /**
     * The edited values: {@code ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl:757-849}.
     */
    @Valid
    private final NewDetails newDetails;

    /**
     * Binds one request. This is the single all-arguments {@code @JsonCreator} constructor, so every
     * member is assigned once and no setter exists; nothing is defaulted, so an absent member stays
     * absent. The {@code oldDetails} group is not a parameter here &mdash; it is re-attached by
     * {@link #withOldDetails(OldDetails)} once the sealed {@code snapshot} has been opened.
     *
     *  @param transactionName            {@code TRNNAMEI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:24}
     *  @param title01                    {@code TITLE01I} PIC X(40) &mdash; {@code app/cpy-bms/COACTUP.CPY:30}
     *  @param currentDate                {@code CURDATEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:36}
     *  @param programName                {@code PGMNAMEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:42}
     *  @param title02                    {@code TITLE02I} PIC X(40) &mdash; {@code app/cpy-bms/COACTUP.CPY:48}
     *  @param currentTime                {@code CURTIMEI} PIC X(8) &mdash; {@code app/cpy-bms/COACTUP.CPY:54}
     *  @param accountId                  {@code ACUP-NEW-ACCT-ID-X} PIC X(11) &mdash; {@code app/cbl/COACTUPC.cbl:759}
     *  @param accountStatus              {@code ACSTTUSI} PIC X(1) &mdash; {@code app/cpy-bms/COACTUP.CPY:66}
     *  @param openDateYear               {@code OPNYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:72}
     *  @param openDateMonth              {@code OPNMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:78}
     *  @param openDateDay                {@code OPNDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:84}
     *  @param creditLimit                {@code ACUP-NEW-CREDIT-LIMIT} PIC X(12) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:766}
     *  @param expiryDateYear             {@code EXPYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:96}
     *  @param expiryDateMonth            {@code EXPMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:102}
     *  @param expiryDateDay              {@code EXPDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:108}
     *  @param cashCreditLimit            {@code ACUP-NEW-CASH-CREDIT-LIMIT} PIC X(12) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:769}
     *  @param reissueDateYear            {@code RISYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:120}
     *  @param reissueDateMonth           {@code RISMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:126}
     *  @param reissueDateDay             {@code RISDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:132}
     *  @param currentBalance             {@code ACUP-NEW-CURR-BAL} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:763}
     *  @param currentCycleCredit         {@code ACUP-NEW-CURR-CYC-CREDIT} PIC X(12) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:790}
     *  @param accountGroupId             {@code AADDGRPI} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:150}
     *  @param currentCycleDebit          {@code ACUP-NEW-CURR-CYC-DEBIT} PIC X(12) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:793}
     *  @param customerId                 {@code ACUP-NEW-CUST-ID-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:798}
     *  @param customerSsnPart1           {@code ACTSSN1I} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:168}
     *  @param customerSsnPart2           {@code ACTSSN2I} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:174}
     *  @param customerSsnPart3           {@code ACTSSN3I} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:180}
     *  @param dateOfBirthYear            {@code DOBYEARI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:186}
     *  @param dateOfBirthMonth           {@code DOBMONI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:192}
     *  @param dateOfBirthDay             {@code DOBDAYI} PIC X(2) &mdash; {@code app/cpy-bms/COACTUP.CPY:198}
     *  @param customerFicoScore          {@code ACSTFCOI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:204}
     *  @param customerFirstName          {@code ACSFNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:210}
     *  @param customerMiddleName         {@code ACSMNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:216}
     *  @param customerLastName           {@code ACSLNAMI} PIC X(25) &mdash; {@code app/cpy-bms/COACTUP.CPY:222}
     *  @param addressLine1               {@code ACUP-NEW-CUST-ADDR-LINE-1} PIC X(50) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:804}
     *  @param addressStateCode           {@code ACUP-NEW-CUST-ADDR-STATE-CD} PIC X(02) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:807}
     *  @param addressLine2               {@code ACUP-NEW-CUST-ADDR-LINE-2} PIC X(50) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:805}
     *  @param addressZip                 {@code ACUP-NEW-CUST-ADDR-ZIP} PIC X(10) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:809}
     *  @param addressCity                {@code ACSCITYI} PIC X(50) &mdash; {@code app/cpy-bms/COACTUP.CPY:252}
     *  @param addressCountryCode         {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD} PIC X(03) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:808}
     *  @param phone1AreaCode             {@code ACSPH1AI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:264}
     *  @param phone1Prefix               {@code ACSPH1BI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:270}
     *  @param phone1LineNumber           {@code ACSPH1CI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:276}
     *  @param governmentIssuedId         {@code ACUP-NEW-CUST-GOVT-ISSUED-ID} PIC X(20) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:836}
     *  @param phone2AreaCode             {@code ACSPH2AI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:288}
     *  @param phone2Prefix               {@code ACSPH2BI} PIC X(3) &mdash; {@code app/cpy-bms/COACTUP.CPY:294}
     *  @param phone2LineNumber           {@code ACSPH2CI} PIC X(4) &mdash; {@code app/cpy-bms/COACTUP.CPY:300}
     *  @param eftAccountId               {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID} PIC X(10) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:843}
     *  @param primaryCardHolderIndicator {@code ACUP-NEW-CUST-PRI-HOLDER-IND} PIC X(01) &mdash; {@code
     *      app/cbl/COACTUPC.cbl:844}
     *  @param informationMessage         {@code INFOMSGI} PIC X(45) &mdash; {@code app/cpy-bms/COACTUP.CPY:318}
     *  @param errorMessage               {@code ERRMSGI} PIC X(78) &mdash; {@code app/cpy-bms/COACTUP.CPY:324}
     *  @param functionKeys               {@code FKEYSI} PIC X(21) &mdash; {@code app/cpy-bms/COACTUP.CPY:330}
     *  @param functionKey05              {@code FKEY05I} PIC X(7) &mdash; {@code app/cpy-bms/COACTUP.CPY:336}
     *  @param functionKey12              {@code FKEY12I} PIC X(10) &mdash; {@code app/cpy-bms/COACTUP.CPY:342}
     *  @param snapshot                   The sealed as-displayed snapshot, echoed back from the preceding
     *      read: the wire carrier for {@code ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl:669-756}. The
     *      opened group is attached separately by {@link #withOldDetails(OldDetails)} and is never bound from
     *      a request
     *  @param newDetails                 The edited values: {@code ACUP-NEW-DETAILS} at {@code
     *      app/cbl/COACTUPC.cbl:757-849}
     */
    @JsonCreator
    public AccountUpdateRequest(
            @JsonProperty("transactionName") final String transactionName,
            @JsonProperty("title01") final String title01,
            @JsonProperty("currentDate") final String currentDate,
            @JsonProperty("programName") final String programName,
            @JsonProperty("title02") final String title02,
            @JsonProperty("currentTime") final String currentTime,
            @JsonProperty("accountId") final String accountId,
            @JsonProperty("accountStatus") final String accountStatus,
            @JsonProperty("openDateYear") final String openDateYear,
            @JsonProperty("openDateMonth") final String openDateMonth,
            @JsonProperty("openDateDay") final String openDateDay,
            @JsonProperty("creditLimit") final String creditLimit,
            @JsonProperty("expiryDateYear") final String expiryDateYear,
            @JsonProperty("expiryDateMonth") final String expiryDateMonth,
            @JsonProperty("expiryDateDay") final String expiryDateDay,
            @JsonProperty("cashCreditLimit") final String cashCreditLimit,
            @JsonProperty("reissueDateYear") final String reissueDateYear,
            @JsonProperty("reissueDateMonth") final String reissueDateMonth,
            @JsonProperty("reissueDateDay") final String reissueDateDay,
            @JsonProperty("currentBalance") final String currentBalance,
            @JsonProperty("currentCycleCredit") final String currentCycleCredit,
            @JsonProperty("accountGroupId") final String accountGroupId,
            @JsonProperty("currentCycleDebit") final String currentCycleDebit,
            @JsonProperty("customerId") final String customerId,
            @JsonProperty("customerSsnPart1") final String customerSsnPart1,
            @JsonProperty("customerSsnPart2") final String customerSsnPart2,
            @JsonProperty("customerSsnPart3") final String customerSsnPart3,
            @JsonProperty("dateOfBirthYear") final String dateOfBirthYear,
            @JsonProperty("dateOfBirthMonth") final String dateOfBirthMonth,
            @JsonProperty("dateOfBirthDay") final String dateOfBirthDay,
            @JsonProperty("customerFicoScore") final String customerFicoScore,
            @JsonProperty("customerFirstName") final String customerFirstName,
            @JsonProperty("customerMiddleName") final String customerMiddleName,
            @JsonProperty("customerLastName") final String customerLastName,
            @JsonProperty("addressLine1") final String addressLine1,
            @JsonProperty("addressStateCode") final String addressStateCode,
            @JsonProperty("addressLine2") final String addressLine2,
            @JsonProperty("addressZip") final String addressZip,
            @JsonProperty("addressCity") final String addressCity,
            @JsonProperty("addressCountryCode") final String addressCountryCode,
            @JsonProperty("phone1AreaCode") final String phone1AreaCode,
            @JsonProperty("phone1Prefix") final String phone1Prefix,
            @JsonProperty("phone1LineNumber") final String phone1LineNumber,
            @JsonProperty("governmentIssuedId") final String governmentIssuedId,
            @JsonProperty("phone2AreaCode") final String phone2AreaCode,
            @JsonProperty("phone2Prefix") final String phone2Prefix,
            @JsonProperty("phone2LineNumber") final String phone2LineNumber,
            @JsonProperty("eftAccountId") final String eftAccountId,
            @JsonProperty("primaryCardHolderIndicator") final String primaryCardHolderIndicator,
            @JsonProperty("informationMessage") final String informationMessage,
            @JsonProperty("errorMessage") final String errorMessage,
            @JsonProperty("functionKeys") final String functionKeys,
            @JsonProperty("functionKey05") final String functionKey05,
            @JsonProperty("functionKey12") final String functionKey12,
            @JsonProperty("snapshot") final String snapshot,
            @JsonProperty("newDetails") final NewDetails newDetails) {
        this.transactionName = transactionName;
        this.title01 = title01;
        this.currentDate = currentDate;
        this.programName = programName;
        this.title02 = title02;
        this.currentTime = currentTime;
        this.accountId = accountId;
        this.accountStatus = accountStatus;
        this.openDateYear = openDateYear;
        this.openDateMonth = openDateMonth;
        this.openDateDay = openDateDay;
        this.creditLimit = creditLimit;
        this.expiryDateYear = expiryDateYear;
        this.expiryDateMonth = expiryDateMonth;
        this.expiryDateDay = expiryDateDay;
        this.cashCreditLimit = cashCreditLimit;
        this.reissueDateYear = reissueDateYear;
        this.reissueDateMonth = reissueDateMonth;
        this.reissueDateDay = reissueDateDay;
        this.currentBalance = currentBalance;
        this.currentCycleCredit = currentCycleCredit;
        this.accountGroupId = accountGroupId;
        this.currentCycleDebit = currentCycleDebit;
        this.customerId = customerId;
        this.customerSsnPart1 = customerSsnPart1;
        this.customerSsnPart2 = customerSsnPart2;
        this.customerSsnPart3 = customerSsnPart3;
        this.dateOfBirthYear = dateOfBirthYear;
        this.dateOfBirthMonth = dateOfBirthMonth;
        this.dateOfBirthDay = dateOfBirthDay;
        this.customerFicoScore = customerFicoScore;
        this.customerFirstName = customerFirstName;
        this.customerMiddleName = customerMiddleName;
        this.customerLastName = customerLastName;
        this.addressLine1 = addressLine1;
        this.addressStateCode = addressStateCode;
        this.addressLine2 = addressLine2;
        this.addressZip = addressZip;
        this.addressCity = addressCity;
        this.addressCountryCode = addressCountryCode;
        this.phone1AreaCode = phone1AreaCode;
        this.phone1Prefix = phone1Prefix;
        this.phone1LineNumber = phone1LineNumber;
        this.governmentIssuedId = governmentIssuedId;
        this.phone2AreaCode = phone2AreaCode;
        this.phone2Prefix = phone2Prefix;
        this.phone2LineNumber = phone2LineNumber;
        this.eftAccountId = eftAccountId;
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
        this.informationMessage = informationMessage;
        this.errorMessage = errorMessage;
        this.functionKeys = functionKeys;
        this.functionKey05 = functionKey05;
        this.functionKey12 = functionKey12;
        this.snapshot = snapshot;
        // Never bound from a request. The opened group is attached by withOldDetails, which only the
        // account-update service calls, and only for the screen it projects for its own use.
        this.oldDetails = null;
        this.newDetails = newDetails;
    }

    /**
     * Copy constructor that attaches an opened as-displayed snapshot to an otherwise identical request.
     *
     * <p>Private, and reachable only through {@link #withOldDetails(OldDetails)}. Every other member is
     * relayed by reference, which is what keeps the projected screen byte-identical to the one built from the
     * wire constructor.</p>
     *
     * @param source the request to copy; must not be {@code null}
     * @param openedOldDetails the opened snapshot group to attach; may be {@code null}
     */
    private AccountUpdateRequest(final AccountUpdateRequest source, final OldDetails openedOldDetails) {
        this.transactionName = source.transactionName;
        this.title01 = source.title01;
        this.currentDate = source.currentDate;
        this.programName = source.programName;
        this.title02 = source.title02;
        this.currentTime = source.currentTime;
        this.accountId = source.accountId;
        this.accountStatus = source.accountStatus;
        this.openDateYear = source.openDateYear;
        this.openDateMonth = source.openDateMonth;
        this.openDateDay = source.openDateDay;
        this.creditLimit = source.creditLimit;
        this.expiryDateYear = source.expiryDateYear;
        this.expiryDateMonth = source.expiryDateMonth;
        this.expiryDateDay = source.expiryDateDay;
        this.cashCreditLimit = source.cashCreditLimit;
        this.reissueDateYear = source.reissueDateYear;
        this.reissueDateMonth = source.reissueDateMonth;
        this.reissueDateDay = source.reissueDateDay;
        this.currentBalance = source.currentBalance;
        this.currentCycleCredit = source.currentCycleCredit;
        this.accountGroupId = source.accountGroupId;
        this.currentCycleDebit = source.currentCycleDebit;
        this.customerId = source.customerId;
        this.customerSsnPart1 = source.customerSsnPart1;
        this.customerSsnPart2 = source.customerSsnPart2;
        this.customerSsnPart3 = source.customerSsnPart3;
        this.dateOfBirthYear = source.dateOfBirthYear;
        this.dateOfBirthMonth = source.dateOfBirthMonth;
        this.dateOfBirthDay = source.dateOfBirthDay;
        this.customerFicoScore = source.customerFicoScore;
        this.customerFirstName = source.customerFirstName;
        this.customerMiddleName = source.customerMiddleName;
        this.customerLastName = source.customerLastName;
        this.addressLine1 = source.addressLine1;
        this.addressStateCode = source.addressStateCode;
        this.addressLine2 = source.addressLine2;
        this.addressZip = source.addressZip;
        this.addressCity = source.addressCity;
        this.addressCountryCode = source.addressCountryCode;
        this.phone1AreaCode = source.phone1AreaCode;
        this.phone1Prefix = source.phone1Prefix;
        this.phone1LineNumber = source.phone1LineNumber;
        this.governmentIssuedId = source.governmentIssuedId;
        this.phone2AreaCode = source.phone2AreaCode;
        this.phone2Prefix = source.phone2Prefix;
        this.phone2LineNumber = source.phone2LineNumber;
        this.eftAccountId = source.eftAccountId;
        this.primaryCardHolderIndicator = source.primaryCardHolderIndicator;
        this.informationMessage = source.informationMessage;
        this.errorMessage = source.errorMessage;
        this.functionKeys = source.functionKeys;
        this.functionKey05 = source.functionKey05;
        this.functionKey12 = source.functionKey12;
        this.snapshot = source.snapshot;
        this.oldDetails = openedOldDetails;
        this.newDetails = source.newDetails;
    }

    /**
     * Returns a copy of this request carrying an opened as-displayed snapshot group.
     *
     * <p><b>Server-side only.</b> The group is the one {@code 9500-STORE-FETCHED-DATA} stored at
     * {@code app/cbl/COACTUPC.cbl:3805-3813}, or the one
     * {@code com.cardemo.security.SnapshotTokenService#open} recovered from a sealed value; it is never a
     * value a client sent, because no request member binds to it. This exists so the projected screen can
     * carry the group the read found, which is what the read entry point seals.</p>
     *
     * <p><b>Side effects.</b> None; this type is immutable and a new instance is returned.</p>
     *
     * <p><strong>Privacy:</strong> the returned object carries the date of birth, the social security number,
     * both telephone numbers, the government-issued identifier and the electronic funds account identifier.
     * It must never be logged, rendered or serialised.</p>
     *
     * @param openedOldDetails the opened snapshot group; may be {@code null}, which yields a copy carrying no
     *     group
     * @return a copy of this request with the group attached; never {@code null}
     */
    public AccountUpdateRequest withOldDetails(final OldDetails openedOldDetails) {
        return new AccountUpdateRequest(this, openedOldDetails);
    }

    /**
     * Returns {@code TRNNAMEI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:24}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getTransactionName() {
        return transactionName;
    }

    /**
     * Returns {@code TITLE01I}, PIC X(40) at {@code app/cpy-bms/COACTUP.CPY:30}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Returns {@code CURDATEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:36}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCurrentDate() {
        return currentDate;
    }

    /**
     * Returns {@code PGMNAMEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:42}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getProgramName() {
        return programName;
    }

    /**
     * Returns {@code TITLE02I}, PIC X(40) at {@code app/cpy-bms/COACTUP.CPY:48}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Returns {@code CURTIMEI}, PIC X(8) at {@code app/cpy-bms/COACTUP.CPY:54}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * Returns {@code ACCTSIDI}, PIC X(11) at {@code app/cpy-bms/COACTUP.CPY:60}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns {@code ACSTTUSI}, PIC X(1) at {@code app/cpy-bms/COACTUP.CPY:66}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Returns {@code OPNYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:72}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getOpenDateYear() {
        return openDateYear;
    }

    /**
     * Returns {@code OPNMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:78}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getOpenDateMonth() {
        return openDateMonth;
    }

    /**
     * Returns {@code OPNDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:84}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getOpenDateDay() {
        return openDateDay;
    }

    /**
     * Returns {@code ACRDLIMI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:90}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns {@code EXPYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:96}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getExpiryDateYear() {
        return expiryDateYear;
    }

    /**
     * Returns {@code EXPMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:102}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getExpiryDateMonth() {
        return expiryDateMonth;
    }

    /**
     * Returns {@code EXPDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:108}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getExpiryDateDay() {
        return expiryDateDay;
    }

    /**
     * Returns {@code ACSHLIMI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:114}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Returns {@code RISYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:120}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getReissueDateYear() {
        return reissueDateYear;
    }

    /**
     * Returns {@code RISMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:126}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getReissueDateMonth() {
        return reissueDateMonth;
    }

    /**
     * Returns {@code RISDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:132}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getReissueDateDay() {
        return reissueDateDay;
    }

    /**
     * Returns {@code ACURBALI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:138}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Returns {@code ACRCYCRI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:144}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Returns {@code AADDGRPI}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:150}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * Returns {@code ACRCYDBI}, PIC X(15) at {@code app/cpy-bms/COACTUP.CPY:156}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Returns {@code ACSTNUMI}, PIC X(9) at {@code app/cpy-bms/COACTUP.CPY:162}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Returns {@code ACTSSN1I}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:168}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerSsnPart1() {
        return customerSsnPart1;
    }

    /**
     * Returns {@code ACTSSN2I}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:174}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerSsnPart2() {
        return customerSsnPart2;
    }

    /**
     * Returns {@code ACTSSN3I}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:180}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerSsnPart3() {
        return customerSsnPart3;
    }

    /**
     * Returns {@code DOBYEARI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:186}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getDateOfBirthYear() {
        return dateOfBirthYear;
    }

    /**
     * Returns {@code DOBMONI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:192}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getDateOfBirthMonth() {
        return dateOfBirthMonth;
    }

    /**
     * Returns {@code DOBDAYI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:198}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getDateOfBirthDay() {
        return dateOfBirthDay;
    }

    /**
     * Returns {@code ACSTFCOI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:204}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerFicoScore() {
        return customerFicoScore;
    }

    /**
     * Returns {@code ACSFNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:210}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerFirstName() {
        return customerFirstName;
    }

    /**
     * Returns {@code ACSMNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:216}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerMiddleName() {
        return customerMiddleName;
    }

    /**
     * Returns {@code ACSLNAMI}, PIC X(25) at {@code app/cpy-bms/COACTUP.CPY:222}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getCustomerLastName() {
        return customerLastName;
    }

    /**
     * Returns {@code ACSADL1I}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:228}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAddressLine1() {
        return addressLine1;
    }

    /**
     * Returns {@code ACSSTTEI}, PIC X(2) at {@code app/cpy-bms/COACTUP.CPY:234}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAddressStateCode() {
        return addressStateCode;
    }

    /**
     * Returns {@code ACSADL2I}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:240}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAddressLine2() {
        return addressLine2;
    }

    /**
     * Returns {@code ACSZIPCI}, PIC X(5) at {@code app/cpy-bms/COACTUP.CPY:246}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Returns {@code ACSCITYI}, PIC X(50) at {@code app/cpy-bms/COACTUP.CPY:252}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAddressCity() {
        return addressCity;
    }

    /**
     * Returns {@code ACSCTRYI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:258}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getAddressCountryCode() {
        return addressCountryCode;
    }

    /**
     * Returns {@code ACSPH1AI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:264}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPhone1AreaCode() {
        return phone1AreaCode;
    }

    /**
     * Returns {@code ACSPH1BI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:270}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPhone1Prefix() {
        return phone1Prefix;
    }

    /**
     * Returns {@code ACSPH1CI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:276}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPhone1LineNumber() {
        return phone1LineNumber;
    }

    /**
     * Returns {@code ACSGOVTI}, PIC X(20) at {@code app/cpy-bms/COACTUP.CPY:282}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    /**
     * Returns {@code ACSPH2AI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:288}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPhone2AreaCode() {
        return phone2AreaCode;
    }

    /**
     * Returns {@code ACSPH2BI}, PIC X(3) at {@code app/cpy-bms/COACTUP.CPY:294}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPhone2Prefix() {
        return phone2Prefix;
    }

    /**
     * Returns {@code ACSPH2CI}, PIC X(4) at {@code app/cpy-bms/COACTUP.CPY:300}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPhone2LineNumber() {
        return phone2LineNumber;
    }

    /**
     * Returns {@code ACSEFTCI}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:306}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Returns {@code ACSPFLGI}, PIC X(1) at {@code app/cpy-bms/COACTUP.CPY:312}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /**
     * Returns {@code INFOMSGI}, PIC X(45) at {@code app/cpy-bms/COACTUP.CPY:318}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getInformationMessage() {
        return informationMessage;
    }

    /**
     * Returns {@code ERRMSGI}, PIC X(78) at {@code app/cpy-bms/COACTUP.CPY:324}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns {@code FKEYSI}, PIC X(21) at {@code app/cpy-bms/COACTUP.CPY:330}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getFunctionKeys() {
        return functionKeys;
    }

    /**
     * Returns {@code FKEY05I}, PIC X(7) at {@code app/cpy-bms/COACTUP.CPY:336}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getFunctionKey05() {
        return functionKey05;
    }

    /**
     * Returns {@code FKEY12I}, PIC X(10) at {@code app/cpy-bms/COACTUP.CPY:342}.
     *
     * @return the stored value exactly as received, with no trimming, case folding or padding applied.
     */
    public String getFunctionKey12() {
        return functionKey12;
    }

    /**
     * Returns the sealed as-displayed snapshot exactly as received.
     *
     * @return the opaque value the preceding read issued, or {@code null} when the caller sent none. No
     * trimming, folding or defaulting is applied, so a blank value is returned blank and the consuming
     * service reports it as absent
     */
    public String getSnapshot() {
        return snapshot;
    }

    /**
     * Returns the opened as-displayed snapshot group, which is present only on a screen this application
     * projected for its own use.
     *
     * <p>Annotated {@code @JsonIgnore} so that this group can never reach or leave a wire through this type.
     * On any request bound from JSON it is {@code null}: the opened group is attached exclusively by
     * {@link #withOldDetails(OldDetails)}, and the wire contract carries the sealed
     * {@link #getSnapshot() snapshot} member instead.</p>
     *
     * @return the {@code ACUP-OLD-DETAILS} group of {@code app/cbl/COACTUPC.cbl:669} when this instance is a
     * server-side projection, and {@code null} on any request bound from a client
     */
    @JsonIgnore
    public OldDetails getOldDetails() {
        return oldDetails;
    }

    /**
     * Returns the edited values.
     *
     * @return the {@code ACUP-NEW-DETAILS} group of {@code app/cbl/COACTUPC.cbl:757}, or {@code null} when the
     * caller supplied none
     */
    public NewDetails getNewDetails() {
        return newDetails;
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
     * <p><strong>This group carries a width contract on every member and the
     * enclosing field is marked {@code @Valid} so they cascade.</strong> A scan of
     * {@code :669-756} finds zero {@code 88}-level condition names, so the group
     * carries no <em>domain</em> rule &mdash; but every member has a {@code PIC}
     * clause, and a {@code PIC} clause is a width contract in its own right. A
     * value wider than the clause is one the source could not physically have
     * produced, so rejecting it cannot reject a snapshot the source accepted.
     * The trust relationship matches the source rather than inverting it: there
     * {@code 9000-READ-DATA} fills this group itself, from the record it has just
     * read ({@code INITIALIZE} at {@code :3610}); here it is filled by opening the
     * sealed {@code snapshot} string this server minted on the preceding read, so
     * the operand {@code 9700-CHECK-CHANGE-IN-REC} ({@code :4109-4193}) compares
     * the live record against is again a value the server produced. This group is
     * not a wire member and a client cannot author it.</p>
     *
     * <p>Three shapes distinguish this group from its NEW counterpart. The social
     * security number is one flat nine-character field here
     * ({@code ACUP-OLD-CUST-SSN-X PIC X(09)} at {@code :742}) where the NEW group
     * decomposes it into three parts at {@code :830-833}. Each telephone number is
     * stored whole here, because {@code MOVE CUST-PHONE-NUM-1} at {@code :3876}
     * assigns the whole and never a part, where the NEW group stores the three
     * parts for the mirror-image reason; the components are exposed here as derived
     * views. And the credit score range of {@code 88 FICO-RANGE-IS-VALID} at
     * {@code :848-849} is declared on the NEW side only, so nothing here bounds it
     * and this type has no {@code ficoScoreIsInValidRange()} twin.</p>
     *
     * <p>Every member is stored exactly as received. No trimming, case folding
     * or padding is applied, because {@code 1205-COMPARE-OLD-NEW} and
     * {@code 9700-CHECK-CHANGE-IN-REC} normalise the same fields differently and
     * any eager transformation would destroy one of them.</p>
     */
    public static final class OldDetails {

        /**
         * {@code ACUP-OLD-ACCT-ID-X} PIC X(11) &mdash; {@code app/cbl/COACTUPC.cbl:671}. Account identifier. A
         * {@code PIC 9(11)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:672-673}; that
         * numeric reading is a view over this text and is interpreted by the service.
         */
        @Size(max = 11,
                message = "OldDetails.accountId must not exceed its declared width of 11 characters"
                        + " (ACUP-OLD-ACCT-ID-X, app/cbl/COACTUPC.cbl:671)")
        private final String accountId;

        /**
         * {@code ACUP-OLD-ACTIVE-STATUS} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:674}. Account active
         * status, a raw one-character code. Compared with case folding on both sides at
         * {@code app/cbl/COACTUPC.cbl:1685-1688} but plainly at {@code :4115}, so it is stored untransformed.
         */
        @Size(max = 1,
                message = "OldDetails.activeStatus must not exceed its declared width of 1 character"
                        + " (ACUP-OLD-ACTIVE-STATUS, app/cbl/COACTUPC.cbl:674)")
        private final String activeStatus;

        /**
         * {@code ACUP-OLD-CURR-BAL} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:675}. Current balance in its
         * displayed twelve-character form, which is what {@code 1205-COMPARE-OLD-NEW} compares.
         */
        @Size(max = 12,
                message = "OldDetails.currentBalance must not exceed its declared width of 12 characters"
                        + " (ACUP-OLD-CURR-BAL, app/cbl/COACTUPC.cbl:675)")
        private final String currentBalance;

        /**
         * {@code ACUP-OLD-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:678}. Credit limit in its
         * displayed twelve-character form.
         */
        @Size(max = 12,
                message = "OldDetails.creditLimit must not exceed its declared width of 12 characters"
                        + " (ACUP-OLD-CREDIT-LIMIT, app/cbl/COACTUPC.cbl:678)")
        private final String creditLimit;

        /**
         * {@code ACUP-OLD-CASH-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:681}. Cash credit
         * limit in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "OldDetails.cashCreditLimit must not exceed its declared width of 12 characters"
                        + " (ACUP-OLD-CASH-CREDIT-LIMIT, app/cbl/COACTUPC.cbl:681)")
        private final String cashCreditLimit;

        /**
         * {@code ACUP-OLD-OPEN-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:684}. Account open date in
         * the compact {@code yyyymmdd} form, with year, month and day parts declared at
         * {@code app/cbl/COACTUPC.cbl:687-689}. Compared whole at {@code :1692} and as three substrings against
         * the live dash-separated {@code PIC X(10)} value at {@code :4127-4129}.
         */
        @Size(max = 8,
                message = "OldDetails.openDate must not exceed its declared width of 8 characters"
                        + " (ACUP-OLD-OPEN-DATE, app/cbl/COACTUPC.cbl:684)")
        private final String openDate;

        /**
         * {@code ACUP-OLD-EXPIRAION-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:690}. Account expiry
         * date in the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:693-695}. The
         * source member name is misspelled (sic) and the misspelling is preserved in this citation. Compared
         * whole at {@code :1693} and as three substrings at {@code :4131-4133}.
         */
        @Size(max = 8,
                message = "OldDetails.expiraionDate must not exceed its declared width of 8 characters"
                        + " (ACUP-OLD-EXPIRAION-DATE, app/cbl/COACTUPC.cbl:690)")
        private final String expiraionDate;

        /**
         * {@code ACUP-OLD-REISSUE-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:696}. Account reissue
         * date in the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:699-701}.
         * Compared whole at {@code :1694} and as three substrings at {@code :4135-4137}.
         */
        @Size(max = 8,
                message = "OldDetails.reissueDate must not exceed its declared width of 8 characters"
                        + " (ACUP-OLD-REISSUE-DATE, app/cbl/COACTUPC.cbl:696)")
        private final String reissueDate;

        /**
         * {@code ACUP-OLD-CURR-CYC-CREDIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:702}. Current cycle
         * credit in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "OldDetails.currentCycleCredit must not exceed its declared width of 12 characters"
                        + " (ACUP-OLD-CURR-CYC-CREDIT, app/cbl/COACTUPC.cbl:702)")
        private final String currentCycleCredit;

        /**
         * {@code ACUP-OLD-CURR-CYC-DEBIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:705}. Current cycle
         * debit in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "OldDetails.currentCycleDebit must not exceed its declared width of 12 characters"
                        + " (ACUP-OLD-CURR-CYC-DEBIT, app/cbl/COACTUPC.cbl:705)")
        private final String currentCycleDebit;

        /**
         * {@code ACUP-OLD-GROUP-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:708}. Account group
         * identifier, stored raw. {@code 1205-COMPARE-OLD-NEW} compares it upper-cased and trimmed at
         * {@code app/cbl/COACTUPC.cbl:1697-1700} while {@code 9700-CHECK-CHANGE-IN-REC} compares it lower-cased
         * and untrimmed at {@code :4139-4140}; only the untransformed value serves both.
         */
        @Size(max = 10,
                message = "OldDetails.groupId must not exceed its declared width of 10 characters"
                        + " (ACUP-OLD-GROUP-ID, app/cbl/COACTUPC.cbl:708)")
        private final String groupId;

        /**
         * {@code ACUP-OLD-CUST-ID-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:710}. Customer identifier. A
         * {@code PIC 9(09)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:711-712}.
         */
        @Size(max = 9,
                message = "OldDetails.customerId must not exceed its declared width of 9 characters"
                        + " (ACUP-OLD-CUST-ID-X, app/cbl/COACTUPC.cbl:710)")
        private final String customerId;

        /**
         * {@code ACUP-OLD-CUST-FIRST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:713}. Customer first
         * name.
         */
        @Size(max = 25,
                message = "OldDetails.firstName must not exceed its declared width of 25 characters"
                        + " (ACUP-OLD-CUST-FIRST-NAME, app/cbl/COACTUPC.cbl:713)")
        private final String firstName;

        /**
         * {@code ACUP-OLD-CUST-MIDDLE-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:714}. Customer middle
         * name.
         */
        @Size(max = 25,
                message = "OldDetails.middleName must not exceed its declared width of 25 characters"
                        + " (ACUP-OLD-CUST-MIDDLE-NAME, app/cbl/COACTUPC.cbl:714)")
        private final String middleName;

        /**
         * {@code ACUP-OLD-CUST-LAST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:715}. Customer last
         * name.
         */
        @Size(max = 25,
                message = "OldDetails.lastName must not exceed its declared width of 25 characters"
                        + " (ACUP-OLD-CUST-LAST-NAME, app/cbl/COACTUPC.cbl:715)")
        private final String lastName;

        /**
         * {@code ACUP-OLD-CUST-ADDR-LINE-1} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:716}. First address
         * line.
         */
        @Size(max = 50,
                message = "OldDetails.addressLine1 must not exceed its declared width of 50 characters"
                        + " (ACUP-OLD-CUST-ADDR-LINE-1, app/cbl/COACTUPC.cbl:716)")
        private final String addressLine1;

        /**
         * {@code ACUP-OLD-CUST-ADDR-LINE-2} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:717}. Second address
         * line.
         */
        @Size(max = 50,
                message = "OldDetails.addressLine2 must not exceed its declared width of 50 characters"
                        + " (ACUP-OLD-CUST-ADDR-LINE-2, app/cbl/COACTUPC.cbl:717)")
        private final String addressLine2;

        /**
         * {@code ACUP-OLD-CUST-ADDR-LINE-3} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:718}. Third address
         * line. It corresponds to the city field of the symbolic map, {@code ACSCITYI} at
         * {@code app/cpy-bms/COACTUP.CPY:252}.
         */
        @Size(max = 50,
                message = "OldDetails.addressLine3 must not exceed its declared width of 50 characters"
                        + " (ACUP-OLD-CUST-ADDR-LINE-3, app/cbl/COACTUPC.cbl:718)")
        private final String addressLine3;

        /**
         * {@code ACUP-OLD-CUST-ADDR-STATE-CD} PIC X(02) &mdash; {@code app/cbl/COACTUPC.cbl:719}. State code.
         * Its consistency with the postal code is edited only when both fields are individually valid, so no
         * cross-field constraint is declared here.
         */
        @Size(max = 2,
                message = "OldDetails.addressStateCode must not exceed its declared width of 2 characters"
                        + " (ACUP-OLD-CUST-ADDR-STATE-CD, app/cbl/COACTUPC.cbl:719)")
        private final String addressStateCode;

        /**
         * {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:720}. Country
         * code.
         */
        @Size(max = 3,
                message = "OldDetails.addressCountryCode must not exceed its declared width of 3 characters"
                        + " (ACUP-OLD-CUST-ADDR-COUNTRY-CD, app/cbl/COACTUPC.cbl:720)")
        private final String addressCountryCode;

        /**
         * {@code ACUP-OLD-CUST-ADDR-ZIP} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:721}. Postal code, ten
         * characters here and on the customer record at {@code app/cpy/CVCUS01Y.cpy:14}, against five on the
         * screen field {@code ACSZIPCI} at {@code app/cpy-bms/COACTUP.CPY:246}. Compared upper-cased and
         * trimmed by {@code 1205-COMPARE-OLD-NEW} but with no case function at all at
         * {@code app/cbl/COACTUPC.cbl:4168}.
         */
        @Size(max = 10,
                message = "OldDetails.addressZip must not exceed its declared width of 10 characters"
                        + " (ACUP-OLD-CUST-ADDR-ZIP, app/cbl/COACTUPC.cbl:721)")
        private final String addressZip;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-1} PIC X(15) &mdash; {@code app/cbl/COACTUPC.cbl:722}. First telephone
         * number as the whole formatted fifteen-character value, which is what {@code 9700-CHECK-CHANGE-IN-REC}
         * compares at {@code app/cbl/COACTUPC.cbl:4169}. The REDEFINES at {@code :723-731} interleaves three
         * filler runs between the parts, so the whole value carries the punctuation and cannot be derived from
         * the parts.
         */
        @Size(max = 15,
                message = "OldDetails.phoneNumber1 must not exceed its declared width of 15 characters"
                        + " (ACUP-OLD-CUST-PHONE-NUM-1, app/cbl/COACTUPC.cbl:722)")
        private final String phoneNumber1;

        /**
         * {@code ACUP-OLD-CUST-PHONE-NUM-2} PIC X(15) &mdash; {@code app/cbl/COACTUPC.cbl:732}. Second
         * telephone number as the whole formatted fifteen-character value, compared whole at
         * {@code app/cbl/COACTUPC.cbl:4170}, with the same filler-interleaved REDEFINES at {@code :733-741}.
         * Edited by {@code 1260-EDIT-US-PHONE-NUM} under the label {@code 'Phone Number 2'} at {@code :1640}.
         */
        @Size(max = 15,
                message = "OldDetails.phoneNumber2 must not exceed its declared width of 15 characters"
                        + " (ACUP-OLD-CUST-PHONE-NUM-2, app/cbl/COACTUPC.cbl:732)")
        private final String phoneNumber2;

        /**
         * {@code ACUP-OLD-CUST-SSN-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:742}. Social security
         * number as one flat nine-character field. This is the OLD side of the declared asymmetry: the NEW side
         * decomposes the same nine bytes into three parts at {@code app/cbl/COACTUPC.cbl:830-833}. A
         * {@code PIC 9(09)} REDEFINES overlays these bytes at {@code :743-744}, and that numeric reading is
         * what {@code 9700-CHECK-CHANGE-IN-REC} compares at {@code :4171}. Neither side is flattened or
         * decomposed to match the other.
         */
        @Size(max = 9,
                message = "OldDetails.ssn must not exceed its declared width of 9 characters"
                        + " (ACUP-OLD-CUST-SSN-X, app/cbl/COACTUPC.cbl:742)")
        private final String ssn;

        /**
         * {@code ACUP-OLD-CUST-GOVT-ISSUED-ID} PIC X(20) &mdash; {@code app/cbl/COACTUPC.cbl:745}.
         * Government-issued identifier.
         */
        @Size(max = 20,
                message = "OldDetails.governmentIssuedId must not exceed its declared width of 20 characters"
                        + " (ACUP-OLD-CUST-GOVT-ISSUED-ID, app/cbl/COACTUPC.cbl:745)")
        private final String governmentIssuedId;

        /**
         * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:746}. Held compact, with
         * no separators, which is why the comparison against the dash-separated live value runs component by
         * component at different offsets on each side.
         */
        @Size(max = 8,
                message = "OldDetails.dateOfBirth must not exceed its declared width of 8 characters"
                        + " (ACUP-OLD-CUST-DOB-YYYY-MM-DD, app/cbl/COACTUPC.cbl:746)")
        private final String dateOfBirth;

        /**
         * {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:752}. Electronic
         * funds transfer account identifier. Compared plainly by both regimes, at
         * {@code app/cbl/COACTUPC.cbl:1761-1762} and {@code :4181-4182}, and edited by
         * {@code 1245-EDIT-NUM-REQD} under the label {@code 'EFT Account Id'} at {@code :1648} with a declared
         * length of ten at {@code :1651}.
         */
        @Size(max = 10,
                message = "OldDetails.eftAccountId must not exceed its declared width of 10 characters"
                        + " (ACUP-OLD-CUST-EFT-ACCOUNT-ID, app/cbl/COACTUPC.cbl:752)")
        private final String eftAccountId;

        /**
         * {@code ACUP-OLD-CUST-PRI-HOLDER-IND} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:753}. Primary card
         * holder indicator, a raw one-character code and never an enum. Edited by {@code 1220-EDIT-YESNO} under
         * the label {@code 'Primary Card Holder'} at {@code app/cbl/COACTUPC.cbl:1657}.
         */
        @Size(max = 1,
                message = "OldDetails.primaryCardHolderIndicator must not exceed its declared width of 1 character"
                        + " (ACUP-OLD-CUST-PRI-HOLDER-IND, app/cbl/COACTUPC.cbl:753)")
        private final String primaryCardHolderIndicator;

        /**
         * {@code ACUP-OLD-CUST-FICO-SCORE-X} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:754}. Credit score
         * as text, which is what {@code 1205-COMPARE-OLD-NEW} compares. A {@code PIC 9(03)} REDEFINES overlays
         * the same bytes at {@code app/cbl/COACTUPC.cbl:755-756}, and that numeric reading is what
         * {@code 9700-CHECK-CHANGE-IN-REC} compares at {@code :4186}.
         */
        @Size(max = 3,
                message = "OldDetails.ficoScore must not exceed its declared width of 3 characters"
                        + " (ACUP-OLD-CUST-FICO-SCORE-X, app/cbl/COACTUPC.cbl:754)")
        private final String ficoScore;

        /**
         * Creates an empty group. JSON binding populates the members through the accessors below; nothing is
         * defaulted, so an absent member stays absent and a blank member stays blank.
         *
         *  @param accountId                  {@code ACUP-NEW-ACCT-ID-X} PIC X(11) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:759}
         *  @param activeStatus               {@code ACUP-NEW-ACTIVE-STATUS} PIC X(01) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:762}
         *  @param currentBalance             {@code ACUP-NEW-CURR-BAL} PIC X(12) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:763}
         *  @param creditLimit                {@code ACUP-NEW-CREDIT-LIMIT} PIC X(12) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:766}
         *  @param cashCreditLimit            {@code ACUP-NEW-CASH-CREDIT-LIMIT} PIC X(12) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:769}
         *  @param openDate                   {@code ACUP-NEW-OPEN-DATE} PIC X(08) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:772}
         *  @param expiraionDate              {@code ACUP-NEW-EXPIRAION-DATE} PIC X(08) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:778}
         *  @param reissueDate                {@code ACUP-NEW-REISSUE-DATE} PIC X(08) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:784}
         *  @param currentCycleCredit         {@code ACUP-NEW-CURR-CYC-CREDIT} PIC X(12) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:790}
         *  @param currentCycleDebit          {@code ACUP-NEW-CURR-CYC-DEBIT} PIC X(12) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:793}
         *  @param groupId                    {@code ACUP-NEW-GROUP-ID} PIC X(10) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:796}
         *  @param customerId                 {@code ACUP-NEW-CUST-ID-X} PIC X(09) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:798}
         *  @param firstName                  {@code ACUP-NEW-CUST-FIRST-NAME} PIC X(25) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:801}
         *  @param middleName                 {@code ACUP-NEW-CUST-MIDDLE-NAME} PIC X(25) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:802}
         *  @param lastName                   {@code ACUP-NEW-CUST-LAST-NAME} PIC X(25) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:803}
         *  @param addressLine1               {@code ACUP-NEW-CUST-ADDR-LINE-1} PIC X(50) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:804}
         *  @param addressLine2               {@code ACUP-NEW-CUST-ADDR-LINE-2} PIC X(50) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:805}
         *  @param addressLine3               {@code ACUP-NEW-CUST-ADDR-LINE-3} PIC X(50) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:806}
         *  @param addressStateCode           {@code ACUP-NEW-CUST-ADDR-STATE-CD} PIC X(02) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:807}
         *  @param addressCountryCode         {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD} PIC X(03) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:808}
         *  @param addressZip                 {@code ACUP-NEW-CUST-ADDR-ZIP} PIC X(10) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:809}
         *  @param phoneNumber1               {@code ACUP-OLD-CUST-PHONE-NUM-1} PIC X(15) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:722}
         *  @param phoneNumber2               {@code ACUP-OLD-CUST-PHONE-NUM-2} PIC X(15) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:732}
         *  @param ssn                        {@code ACUP-OLD-CUST-SSN-X} PIC X(09) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:742}
         *  @param governmentIssuedId         {@code ACUP-NEW-CUST-GOVT-ISSUED-ID} PIC X(20) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:836}
         *  @param dateOfBirth                {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code
         *      app/cbl/COACTUPC.cbl:837}
         *  @param eftAccountId               {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID} PIC X(10) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:843}
         *  @param primaryCardHolderIndicator {@code ACUP-NEW-CUST-PRI-HOLDER-IND} PIC X(01) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:844}
         *  @param ficoScore                  {@code ACUP-NEW-CUST-FICO-SCORE-X} PIC X(03) &mdash; {@code
         *      app/cbl/COACTUPC.cbl:845}
         */
        @JsonCreator
        public OldDetails(
                @JsonProperty("accountId") final String accountId,
                @JsonProperty("activeStatus") final String activeStatus,
                @JsonProperty("currentBalance") final String currentBalance,
                @JsonProperty("creditLimit") final String creditLimit,
                @JsonProperty("cashCreditLimit") final String cashCreditLimit,
                @JsonProperty("openDate") final String openDate,
                @JsonProperty("expiraionDate") final String expiraionDate,
                @JsonProperty("reissueDate") final String reissueDate,
                @JsonProperty("currentCycleCredit") final String currentCycleCredit,
                @JsonProperty("currentCycleDebit") final String currentCycleDebit,
                @JsonProperty("groupId") final String groupId,
                @JsonProperty("customerId") final String customerId,
                @JsonProperty("firstName") final String firstName,
                @JsonProperty("middleName") final String middleName,
                @JsonProperty("lastName") final String lastName,
                @JsonProperty("addressLine1") final String addressLine1,
                @JsonProperty("addressLine2") final String addressLine2,
                @JsonProperty("addressLine3") final String addressLine3,
                @JsonProperty("addressStateCode") final String addressStateCode,
                @JsonProperty("addressCountryCode") final String addressCountryCode,
                @JsonProperty("addressZip") final String addressZip,
                @JsonProperty("phoneNumber1") final String phoneNumber1,
                @JsonProperty("phoneNumber2") final String phoneNumber2,
                @JsonProperty("ssn") final String ssn,
                @JsonProperty("governmentIssuedId") final String governmentIssuedId,
                @JsonProperty("dateOfBirth") final String dateOfBirth,
                @JsonProperty("eftAccountId") final String eftAccountId,
                @JsonProperty("primaryCardHolderIndicator") final String primaryCardHolderIndicator,
                @JsonProperty("ficoScore") final String ficoScore) {
            this.accountId = accountId;
            this.activeStatus = activeStatus;
            this.currentBalance = currentBalance;
            this.creditLimit = creditLimit;
            this.cashCreditLimit = cashCreditLimit;
            this.openDate = openDate;
            this.expiraionDate = expiraionDate;
            this.reissueDate = reissueDate;
            this.currentCycleCredit = currentCycleCredit;
            this.currentCycleDebit = currentCycleDebit;
            this.groupId = groupId;
            this.customerId = customerId;
            this.firstName = firstName;
            this.middleName = middleName;
            this.lastName = lastName;
            this.addressLine1 = addressLine1;
            this.addressLine2 = addressLine2;
            this.addressLine3 = addressLine3;
            this.addressStateCode = addressStateCode;
            this.addressCountryCode = addressCountryCode;
            this.addressZip = addressZip;
            this.phoneNumber1 = phoneNumber1;
            this.phoneNumber2 = phoneNumber2;
            this.ssn = ssn;
            this.governmentIssuedId = governmentIssuedId;
            this.dateOfBirth = dateOfBirth;
            this.eftAccountId = eftAccountId;
            this.primaryCardHolderIndicator = primaryCardHolderIndicator;
            this.ficoScore = ficoScore;
        }

        /**
         * Returns {@code ACUP-OLD-ACCT-ID-X}, PIC X(11) at {@code app/cbl/COACTUPC.cbl:671}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Returns {@code ACUP-OLD-ACTIVE-STATUS}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:674}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getActiveStatus() {
            return activeStatus;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-BAL}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:675}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Returns {@code ACUP-OLD-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:678}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCreditLimit() {
            return creditLimit;
        }

        /**
         * Returns {@code ACUP-OLD-CASH-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:681}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCashCreditLimit() {
            return cashCreditLimit;
        }

        /**
         * Returns {@code ACUP-OLD-OPEN-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:684}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getOpenDate() {
            return openDate;
        }

        /**
         * Returns {@code ACUP-OLD-EXPIRAION-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:690}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getExpiraionDate() {
            return expiraionDate;
        }

        /**
         * Returns {@code ACUP-OLD-REISSUE-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:696}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getReissueDate() {
            return reissueDate;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-CREDIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:702}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCurrentCycleCredit() {
            return currentCycleCredit;
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-DEBIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:705}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCurrentCycleDebit() {
            return currentCycleDebit;
        }

        /**
         * Returns {@code ACUP-OLD-GROUP-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:708}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ID-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:710}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCustomerId() {
            return customerId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-FIRST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:713}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-MIDDLE-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:714}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getMiddleName() {
            return middleName;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-LAST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:715}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getLastName() {
            return lastName;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-LINE-1}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:716}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressLine1() {
            return addressLine1;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-LINE-2}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:717}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressLine2() {
            return addressLine2;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-LINE-3}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:718}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressLine3() {
            return addressLine3;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-STATE-CD}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:719}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressStateCode() {
            return addressStateCode;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:720}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressCountryCode() {
            return addressCountryCode;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-ADDR-ZIP}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:721}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressZip() {
            return addressZip;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:722}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getPhoneNumber1() {
            return phoneNumber1;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2}, PIC X(15) at {@code app/cbl/COACTUPC.cbl:732}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getPhoneNumber2() {
            return phoneNumber2;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-SSN-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:742}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getSsn() {
            return ssn;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-GOVT-ISSUED-ID}, PIC X(20) at {@code app/cbl/COACTUPC.cbl:745}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getGovernmentIssuedId() {
            return governmentIssuedId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:746}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getDateOfBirth() {
            return dateOfBirth;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:752}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getEftAccountId() {
            return eftAccountId;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PRI-HOLDER-IND}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:753}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getPrimaryCardHolderIndicator() {
            return primaryCardHolderIndicator;
        }

        /**
         * Returns {@code ACUP-OLD-CUST-FICO-SCORE-X}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:754}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getFicoScore() {
            return ficoScore;
        }

        /**
         * Returns the year component of the account open date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:687-689}. This is a derived view, not a stored member: it slices
         * {@code openDate} and is deliberately not named as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code openDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         * which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateYear() {
            return compactDatePart(openDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.openDate");
        }

        /**
         * Returns the month component of the account open date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:687-689}. This is a derived view, not a stored member: it slices
         * {@code openDate} and is deliberately not named as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code openDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         * which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateMonth() {
            return compactDatePart(openDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.openDate");
        }

        /**
         * Returns the day component of the account open date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:687-689}. This is a derived view, not a stored member: it slices
         * {@code openDate} and is deliberately not named as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code openDate} is absent, or the empty string when the
         * stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         * which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateDay() {
            return compactDatePart(openDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.openDate");
        }

        /**
         * Returns the year component of the account expiry date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:693-695}. This is a derived view, not a stored member: it slices
         * {@code expirationDate} and is deliberately not named as a bean property, so JSON binding never
         * invokes it.
         *
         * @return the year component, {@code null} when {@code expirationDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expiraionDateYear() {
            return compactDatePart(expiraionDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.expiraionDate");
        }

        /**
         * Returns the month component of the account expiry date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:693-695}. This is a derived view, not a stored member: it slices
         * {@code expirationDate} and is deliberately not named as a bean property, so JSON binding never
         * invokes it.
         *
         * @return the month component, {@code null} when {@code expirationDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expiraionDateMonth() {
            return compactDatePart(expiraionDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.expiraionDate");
        }

        /**
         * Returns the day component of the account expiry date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:693-695}. This is a derived view, not a stored member: it slices
         * {@code expirationDate} and is deliberately not named as a bean property, so JSON binding never
         * invokes it.
         *
         * @return the day component, {@code null} when {@code expirationDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expiraionDateDay() {
            return compactDatePart(expiraionDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.expiraionDate");
        }

        /**
         * Returns the year component of the account reissue date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:699-701}. This is a derived view, not a stored member: it slices
         * {@code reissueDate} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the year component, {@code null} when {@code reissueDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateYear() {
            return compactDatePart(reissueDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.reissueDate");
        }

        /**
         * Returns the month component of the account reissue date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:699-701}. This is a derived view, not a stored member: it slices
         * {@code reissueDate} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the month component, {@code null} when {@code reissueDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateMonth() {
            return compactDatePart(reissueDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.reissueDate");
        }

        /**
         * Returns the day component of the account reissue date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:699-701}. This is a derived view, not a stored member: it slices
         * {@code reissueDate} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the day component, {@code null} when {@code reissueDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateDay() {
            return compactDatePart(reissueDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.reissueDate");
        }

        /**
         * Returns the year component of the date of birth, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:749-751}. This is a derived view, not a stored member: it slices
         * {@code dateOfBirth} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the year component, {@code null} when {@code dateOfBirth} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthYear() {
            return compactDatePart(dateOfBirth, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "OldDetails.dateOfBirth");
        }

        /**
         * Returns the month component of the date of birth, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:749-751}. This is a derived view, not a stored member: it slices
         * {@code dateOfBirth} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the month component, {@code null} when {@code dateOfBirth} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthMonth() {
            return compactDatePart(dateOfBirth, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "OldDetails.dateOfBirth");
        }

        /**
         * Returns the day component of the date of birth, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:749-751}. This is a derived view, not a stored member: it slices
         * {@code dateOfBirth} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the day component, {@code null} when {@code dateOfBirth} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthDay() {
            return compactDatePart(dateOfBirth, DATE_DAY_BEGIN, DATE_DAY_END,
                    "OldDetails.dateOfBirth");
        }

        /**
         * Returns {@code ACUP-OLD-CURR-BAL-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCurrentBalance()} declared at {@code app/cbl/COACTUPC.cbl:676-677}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-OLD-CURR-BAL} at {@code app/cbl/COACTUPC.cbl:675}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the current balance at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal currentBalanceAmount() {
            return zonedDecimalAmount(currentBalance);
        }

        /**
         * Returns {@code ACUP-OLD-CREDIT-LIMIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCreditLimit()} declared at {@code app/cbl/COACTUPC.cbl:679-680}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-OLD-CREDIT-LIMIT} at {@code app/cbl/COACTUPC.cbl:678}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the credit limit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal creditLimitAmount() {
            return zonedDecimalAmount(creditLimit);
        }

        /**
         * Returns {@code ACUP-OLD-CASH-CREDIT-LIMIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCashCreditLimit()} declared at {@code app/cbl/COACTUPC.cbl:682-683}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-OLD-CASH-CREDIT-LIMIT} at {@code app/cbl/COACTUPC.cbl:681}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the cash credit limit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal cashCreditLimitAmount() {
            return zonedDecimalAmount(cashCreditLimit);
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-CREDIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCurrentCycleCredit()} declared at {@code app/cbl/COACTUPC.cbl:703-704}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-OLD-CURR-CYC-CREDIT} at {@code app/cbl/COACTUPC.cbl:702}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the current cycle credit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal currentCycleCreditAmount() {
            return zonedDecimalAmount(currentCycleCredit);
        }

        /**
         * Returns {@code ACUP-OLD-CURR-CYC-DEBIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCurrentCycleDebit()} declared at {@code app/cbl/COACTUPC.cbl:706-707}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-OLD-CURR-CYC-DEBIT} at {@code app/cbl/COACTUPC.cbl:705}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the current cycle debit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal currentCycleDebitAmount() {
            return zonedDecimalAmount(currentCycleDebit);
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1A}, the area code component of
         * {@link #getPhoneNumber1()}, declared at {@code app/cbl/COACTUPC.cbl:726}.
         *
         * <p>This is a derived view, not a stored member: it slices the fifteen bytes of the telephone
         * overlay and is deliberately not named as a bean property, so JSON binding never invokes it.</p>
         *
         * @return the area code, {@code null} when {@code phoneNumber1} is absent, or the empty string
         *         when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code phoneNumber1} is longer than the declared
         *         fifteen characters and therefore is not the punctuated form the overlay describes
         */
        public String phoneNumber1AreaCode() {
            return phoneNumberPart(phoneNumber1, PHONE_AREA_CODE_BEGIN, PHONE_AREA_CODE_END,
                    "OldDetails.phoneNumber1");
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1B}, the exchange prefix component of
         * {@link #getPhoneNumber1()}, declared at {@code app/cbl/COACTUPC.cbl:728}.
         *
         * <p>This is a derived view, not a stored member: it slices the fifteen bytes of the telephone
         * overlay and is deliberately not named as a bean property, so JSON binding never invokes it.</p>
         *
         * @return the exchange prefix, {@code null} when {@code phoneNumber1} is absent, or the empty string
         *         when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code phoneNumber1} is longer than the declared
         *         fifteen characters and therefore is not the punctuated form the overlay describes
         */
        public String phoneNumber1Prefix() {
            return phoneNumberPart(phoneNumber1, PHONE_PREFIX_BEGIN, PHONE_PREFIX_END,
                    "OldDetails.phoneNumber1");
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-1C}, the line number component of
         * {@link #getPhoneNumber1()}, declared at {@code app/cbl/COACTUPC.cbl:730}.
         *
         * <p>This is a derived view, not a stored member: it slices the fifteen bytes of the telephone
         * overlay and is deliberately not named as a bean property, so JSON binding never invokes it.</p>
         *
         * @return the line number, {@code null} when {@code phoneNumber1} is absent, or the empty string
         *         when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code phoneNumber1} is longer than the declared
         *         fifteen characters and therefore is not the punctuated form the overlay describes
         */
        public String phoneNumber1LineNumber() {
            return phoneNumberPart(phoneNumber1, PHONE_LINE_NUMBER_BEGIN, PHONE_LINE_NUMBER_END,
                    "OldDetails.phoneNumber1");
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2A}, the area code component of
         * {@link #getPhoneNumber2()}, declared at {@code app/cbl/COACTUPC.cbl:736}.
         *
         * <p>This is a derived view, not a stored member: it slices the fifteen bytes of the telephone
         * overlay and is deliberately not named as a bean property, so JSON binding never invokes it.</p>
         *
         * @return the area code, {@code null} when {@code phoneNumber2} is absent, or the empty string
         *         when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code phoneNumber2} is longer than the declared
         *         fifteen characters and therefore is not the punctuated form the overlay describes
         */
        public String phoneNumber2AreaCode() {
            return phoneNumberPart(phoneNumber2, PHONE_AREA_CODE_BEGIN, PHONE_AREA_CODE_END,
                    "OldDetails.phoneNumber2");
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2B}, the exchange prefix component of
         * {@link #getPhoneNumber2()}, declared at {@code app/cbl/COACTUPC.cbl:738}.
         *
         * <p>This is a derived view, not a stored member: it slices the fifteen bytes of the telephone
         * overlay and is deliberately not named as a bean property, so JSON binding never invokes it.</p>
         *
         * @return the exchange prefix, {@code null} when {@code phoneNumber2} is absent, or the empty string
         *         when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code phoneNumber2} is longer than the declared
         *         fifteen characters and therefore is not the punctuated form the overlay describes
         */
        public String phoneNumber2Prefix() {
            return phoneNumberPart(phoneNumber2, PHONE_PREFIX_BEGIN, PHONE_PREFIX_END,
                    "OldDetails.phoneNumber2");
        }

        /**
         * Returns {@code ACUP-OLD-CUST-PHONE-NUM-2C}, the line number component of
         * {@link #getPhoneNumber2()}, declared at {@code app/cbl/COACTUPC.cbl:740}.
         *
         * <p>This is a derived view, not a stored member: it slices the fifteen bytes of the telephone
         * overlay and is deliberately not named as a bean property, so JSON binding never invokes it.</p>
         *
         * @return the line number, {@code null} when {@code phoneNumber2} is absent, or the empty string
         *         when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code phoneNumber2} is longer than the declared
         *         fifteen characters and therefore is not the punctuated form the overlay describes
         */
        public String phoneNumber2LineNumber() {
            return phoneNumberPart(phoneNumber2, PHONE_LINE_NUMBER_BEGIN, PHONE_LINE_NUMBER_END,
                    "OldDetails.phoneNumber2");
        }

        /**
         * Returns {@code ACUP-OLD-CUST-FICO-SCORE}, the {@code PIC 9(03)} REDEFINES view over
         * {@link #getFicoScore()} declared at {@code app/cbl/COACTUPC.cbl:755-756}.
         *
         * <p>This is a derived view, not a stored member: it reads the three bytes held by
         * {@code ACUP-OLD-CUST-FICO-SCORE-X} at {@code app/cbl/COACTUPC.cbl:754}, and it is
         * deliberately not named as a bean property, so JSON binding never invokes it. The source needs both
         * readings of the one cell: the text at {@code :1767-1768} and the number at {@code :4186}.</p>
         *
         * @return the credit score, or {@code null} when the stored member is not three decimal digits
         */
        public Integer ficoScoreValue() {
            return unsignedDisplayScore(ficoScore);
        }

        /**
         * Rejects any JSON property this type does not declare.
         *
         * <p>The guard is local rather than declarative because the declarative alternative does not hold.
         * Jackson's type-level unknown-property setting is consulted only while the object mapper still has
         * failure on unknown properties enabled, the framework disables that by default, and no profile in
         * this repository re-enables it; a type-level annotation would therefore be inert here, which is
         * worse than absent because it would read as protection that is not in force.</p>
         *
         * <p>The guard is also what makes the overlay canonicalisation enforceable rather than advisory. The
         * numeric and component readings of the snapshot members are derived views, not properties; without
         * this method a caller could still send {@code currentBalanceAmount} or {@code ficoScoreValue} and
         * have it silently discarded, which looks like acceptance. Rejection says plainly that one storage
         * cell has one wire representation.</p>
         *
         * <p>Neither the offending property name nor its value is reproduced in the thrown message. Both are
         * untrusted input, and copying either into a message that reaches a log record would let a caller
         * forge log content; on this payload the same rule keeps a rejected social security number, date of
         * birth or government-issued identifier out of the logs. Nothing is stored: this type is immutable,
         * and the method exists only to fail.</p>
         *
         * @param name  the unrecognised property name supplied by the caller, deliberately neither stored nor
         *              reproduced in the thrown message
         * @param value the unrecognised property value supplied by the caller, deliberately neither stored nor
         *              reproduced in the thrown message
         * @throws IllegalArgumentException always, because an unrecognised property is never acceptable here
         */
        @JsonAnySetter
        void rejectUnrecognisedProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "OldDetails accepts only the 29 properties declared by "
                            + "app/cbl/COACTUPC.cbl:669-756 for the ACUP-OLD-DETAILS snapshot group, "
                            + "and the payload contained a property that is not "
                            + "one of them. Numeric and component readings of a snapshot member are "
                            + "derived views rather than properties, because the source redefines one "
                            + "storage cell instead of declaring two. The offending name and value are "
                            + "withheld because they are untrusted input.");
        }

    }

    /**
     * The edited group {@code ACUP-NEW-DETAILS}, declared at {@code app/cbl/COACTUPC.cbl:757-849}: the values
     * the user supplied.
     */
    public static final class NewDetails {

        /**
         * {@code ACUP-NEW-ACCT-ID-X} PIC X(11) &mdash; {@code app/cbl/COACTUPC.cbl:759}. Account identifier. A
         * {@code PIC 9(11)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:760-761}; that
         * numeric reading is a view over this text and is interpreted by the service.
         */
        @Size(max = 11,
                message = "accountId must not exceed its declared width of 11 characters"
                        + " (app/cbl/COACTUPC.cbl:759)")
        private final String accountId;

        /**
         * {@code ACUP-NEW-ACTIVE-STATUS} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:762}. Account active
         * status, a raw one-character code. Compared with case folding on both sides at
         * {@code app/cbl/COACTUPC.cbl:1685-1688} but plainly at {@code :4115}, so it is stored untransformed.
         */
        @Size(max = 1,
                message = "activeStatus must not exceed its declared width of 1 character"
                        + " (app/cbl/COACTUPC.cbl:762)")
        private final String activeStatus;

        /**
         * {@code ACUP-NEW-CURR-BAL} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:763}. Current balance in its
         * displayed twelve-character form, which is what {@code 1205-COMPARE-OLD-NEW} compares.
         */
        @Size(max = 12,
                message = "currentBalance must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:763)")
        private final String currentBalance;

        /**
         * {@code ACUP-NEW-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:766}. Credit limit in its
         * displayed twelve-character form.
         */
        @Size(max = 12,
                message = "creditLimit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:766)")
        private final String creditLimit;

        /**
         * {@code ACUP-NEW-CASH-CREDIT-LIMIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:769}. Cash credit
         * limit in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "cashCreditLimit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:769)")
        private final String cashCreditLimit;

        /**
         * {@code ACUP-NEW-OPEN-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:772}. Account open date in
         * the compact {@code yyyymmdd} form, with year, month and day parts declared at
         * {@code app/cbl/COACTUPC.cbl:775-777}. Compared whole at {@code :1692} and as three substrings against
         * the live dash-separated {@code PIC X(10)} value at {@code :4127-4129}.
         */
        @Size(max = 8,
                message = "openDate must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:772)")
        private final String openDate;

        /**
         * {@code ACUP-NEW-EXPIRAION-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:778}. Account expiry
         * date in the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:781-783}. The
         * source member name is misspelled (sic) and the misspelling is preserved in this citation. Compared
         * whole at {@code :1693} and as three substrings at {@code :4131-4133}.
         */
        @Size(max = 8,
                message = "expiraionDate must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:778)")
        private final String expiraionDate;

        /**
         * {@code ACUP-NEW-REISSUE-DATE} PIC X(08) &mdash; {@code app/cbl/COACTUPC.cbl:784}. Account reissue
         * date in the compact {@code yyyymmdd} form, with parts at {@code app/cbl/COACTUPC.cbl:787-789}.
         * Compared whole at {@code :1694} and as three substrings at {@code :4135-4137}.
         */
        @Size(max = 8,
                message = "reissueDate must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:784)")
        private final String reissueDate;

        /**
         * {@code ACUP-NEW-CURR-CYC-CREDIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:790}. Current cycle
         * credit in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "currentCycleCredit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:790)")
        private final String currentCycleCredit;

        /**
         * {@code ACUP-NEW-CURR-CYC-DEBIT} PIC X(12) &mdash; {@code app/cbl/COACTUPC.cbl:793}. Current cycle
         * debit in its displayed twelve-character form.
         */
        @Size(max = 12,
                message = "currentCycleDebit must not exceed its declared width of 12 characters"
                        + " (app/cbl/COACTUPC.cbl:793)")
        private final String currentCycleDebit;

        /**
         * {@code ACUP-NEW-GROUP-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:796}. Account group
         * identifier, stored raw. {@code 1205-COMPARE-OLD-NEW} compares it upper-cased and trimmed at
         * {@code app/cbl/COACTUPC.cbl:1697-1700} while {@code 9700-CHECK-CHANGE-IN-REC} compares it lower-cased
         * and untrimmed at {@code :4139-4140}; only the untransformed value serves both.
         */
        @Size(max = 10,
                message = "groupId must not exceed its declared width of 10 characters"
                        + " (app/cbl/COACTUPC.cbl:796)")
        private final String groupId;

        /**
         * {@code ACUP-NEW-CUST-ID-X} PIC X(09) &mdash; {@code app/cbl/COACTUPC.cbl:798}. Customer identifier. A
         * {@code PIC 9(09)} REDEFINES overlays the same bytes at {@code app/cbl/COACTUPC.cbl:799-800}.
         */
        @Size(max = 9,
                message = "customerId must not exceed its declared width of 9 characters"
                        + " (app/cbl/COACTUPC.cbl:798)")
        private final String customerId;

        /**
         * {@code ACUP-NEW-CUST-FIRST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:801}. Customer first
         * name.
         */
        @Size(max = 25,
                message = "firstName must not exceed its declared width of 25 characters"
                        + " (app/cbl/COACTUPC.cbl:801)")
        private final String firstName;

        /**
         * {@code ACUP-NEW-CUST-MIDDLE-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:802}. Customer middle
         * name.
         */
        @Size(max = 25,
                message = "middleName must not exceed its declared width of 25 characters"
                        + " (app/cbl/COACTUPC.cbl:802)")
        private final String middleName;

        /**
         * {@code ACUP-NEW-CUST-LAST-NAME} PIC X(25) &mdash; {@code app/cbl/COACTUPC.cbl:803}. Customer last
         * name.
         */
        @Size(max = 25,
                message = "lastName must not exceed its declared width of 25 characters"
                        + " (app/cbl/COACTUPC.cbl:803)")
        private final String lastName;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-1} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:804}. First address
         * line.
         */
        @Size(max = 50,
                message = "addressLine1 must not exceed its declared width of 50 characters"
                        + " (app/cbl/COACTUPC.cbl:804)")
        private final String addressLine1;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-2} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:805}. Second address
         * line.
         */
        @Size(max = 50,
                message = "addressLine2 must not exceed its declared width of 50 characters"
                        + " (app/cbl/COACTUPC.cbl:805)")
        private final String addressLine2;

        /**
         * {@code ACUP-NEW-CUST-ADDR-LINE-3} PIC X(50) &mdash; {@code app/cbl/COACTUPC.cbl:806}. Third address
         * line. It corresponds to the city field of the symbolic map, {@code ACSCITYI} at
         * {@code app/cpy-bms/COACTUP.CPY:252}.
         */
        @Size(max = 50,
                message = "addressLine3 must not exceed its declared width of 50 characters"
                        + " (app/cbl/COACTUPC.cbl:806)")
        private final String addressLine3;

        /**
         * {@code ACUP-NEW-CUST-ADDR-STATE-CD} PIC X(02) &mdash; {@code app/cbl/COACTUPC.cbl:807}. State code.
         * Its consistency with the postal code is edited only when both fields are individually valid, so no
         * cross-field constraint is declared here.
         */
        @Size(max = 2,
                message = "addressStateCode must not exceed its declared width of 2 characters"
                        + " (app/cbl/COACTUPC.cbl:807)")
        private final String addressStateCode;

        /**
         * {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:808}. Country
         * code.
         */
        @Size(max = 3,
                message = "addressCountryCode must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:808)")
        private final String addressCountryCode;

        /**
         * {@code ACUP-NEW-CUST-ADDR-ZIP} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:809}. Postal code, ten
         * characters here and on the customer record at {@code app/cpy/CVCUS01Y.cpy:14}, against five on the
         * screen field {@code ACSZIPCI} at {@code app/cpy-bms/COACTUP.CPY:246}. Compared upper-cased and
         * trimmed by {@code 1205-COMPARE-OLD-NEW} but with no case function at all at
         * {@code app/cbl/COACTUPC.cbl:4168}.
         */
        @Size(max = 10,
                message = "addressZip must not exceed its declared width of 10 characters"
                        + " (app/cbl/COACTUPC.cbl:809)")
        private final String addressZip;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1A} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:814}. The area code of
         * telephone number 1.
         *
         * <p>This part is a stored member and the fifteen-byte whole is the derived view, which is the opposite of
         * {@link OldDetails}. The asymmetry belongs to the source, not to the model: {@code 1100-RECEIVE-MAP} assigns
         * only the parts, at {@code app/cbl/COACTUPC.cbl:1361} for this one and {@code :1359-1375} for the number as a
         * whole, and never assigns {@code ACUP-NEW-CUST-PHONE-NUM-1} itself; {@code 1205-COMPARE-OLD-NEW} compares the
         * parts one at a time at {@code :1748-1750}; {@code 3000-SEND-MAP} returns them to the screen individually at
         * {@code :2939-2941}; and the punctuated value that reaches the record is assembled only at write time by
         * {@code STRING} at {@code :4027-4033}. Carrying the whole instead would name a byte image this group never
         * holds.</p>
         */
        @Size(max = 3,
                message = "NewDetails.phoneNumber1AreaCode must not exceed its declared width of 3 characters"
                        + " (ACUP-NEW-CUST-PHONE-NUM-1A, app/cbl/COACTUPC.cbl:814)")
        private final String phoneNumber1AreaCode;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1B} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:816}. The exchange prefix of
         * telephone number 1.
         *
         * <p>This part is a stored member and the fifteen-byte whole is the derived view, which is the opposite of
         * {@link OldDetails}. The asymmetry belongs to the source, not to the model: {@code 1100-RECEIVE-MAP} assigns
         * only the parts, at {@code app/cbl/COACTUPC.cbl:1368} for this one and {@code :1359-1375} for the number as a
         * whole, and never assigns {@code ACUP-NEW-CUST-PHONE-NUM-1} itself; {@code 1205-COMPARE-OLD-NEW} compares the
         * parts one at a time at {@code :1748-1750}; {@code 3000-SEND-MAP} returns them to the screen individually at
         * {@code :2939-2941}; and the punctuated value that reaches the record is assembled only at write time by
         * {@code STRING} at {@code :4027-4033}. Carrying the whole instead would name a byte image this group never
         * holds.</p>
         */
        @Size(max = 3,
                message = "NewDetails.phoneNumber1Prefix must not exceed its declared width of 3 characters"
                        + " (ACUP-NEW-CUST-PHONE-NUM-1B, app/cbl/COACTUPC.cbl:816)")
        private final String phoneNumber1Prefix;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-1C} PIC X(4) &mdash; {@code app/cbl/COACTUPC.cbl:818}. The line number of
         * telephone number 1.
         *
         * <p>This part is a stored member and the fifteen-byte whole is the derived view, which is the opposite of
         * {@link OldDetails}. The asymmetry belongs to the source, not to the model: {@code 1100-RECEIVE-MAP} assigns
         * only the parts, at {@code app/cbl/COACTUPC.cbl:1375} for this one and {@code :1359-1375} for the number as a
         * whole, and never assigns {@code ACUP-NEW-CUST-PHONE-NUM-1} itself; {@code 1205-COMPARE-OLD-NEW} compares the
         * parts one at a time at {@code :1748-1750}; {@code 3000-SEND-MAP} returns them to the screen individually at
         * {@code :2939-2941}; and the punctuated value that reaches the record is assembled only at write time by
         * {@code STRING} at {@code :4027-4033}. Carrying the whole instead would name a byte image this group never
         * holds.</p>
         */
        @Size(max = 4,
                message = "NewDetails.phoneNumber1LineNumber must not exceed its declared width of 4 characters"
                        + " (ACUP-NEW-CUST-PHONE-NUM-1C, app/cbl/COACTUPC.cbl:818)")
        private final String phoneNumber1LineNumber;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2A} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:824}. The area code of
         * telephone number 2.
         *
         * <p>This part is a stored member and the fifteen-byte whole is the derived view, which is the opposite of
         * {@link OldDetails}. The asymmetry belongs to the source, not to the model: {@code 1100-RECEIVE-MAP} assigns
         * only the parts, at {@code app/cbl/COACTUPC.cbl:1382} for this one and {@code :1380-1396} for the number as a
         * whole, and never assigns {@code ACUP-NEW-CUST-PHONE-NUM-2} itself; {@code 1205-COMPARE-OLD-NEW} compares the
         * parts one at a time at {@code :1751-1753}; {@code 3000-SEND-MAP} returns them to the screen individually at
         * {@code :2942-2944}; and the punctuated value that reaches the record is assembled only at write time by
         * {@code STRING} at {@code :4035-4041}. Carrying the whole instead would name a byte image this group never
         * holds.</p>
         */
        @Size(max = 3,
                message = "NewDetails.phoneNumber2AreaCode must not exceed its declared width of 3 characters"
                        + " (ACUP-NEW-CUST-PHONE-NUM-2A, app/cbl/COACTUPC.cbl:824)")
        private final String phoneNumber2AreaCode;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2B} PIC X(3) &mdash; {@code app/cbl/COACTUPC.cbl:826}. The exchange prefix of
         * telephone number 2.
         *
         * <p>This part is a stored member and the fifteen-byte whole is the derived view, which is the opposite of
         * {@link OldDetails}. The asymmetry belongs to the source, not to the model: {@code 1100-RECEIVE-MAP} assigns
         * only the parts, at {@code app/cbl/COACTUPC.cbl:1389} for this one and {@code :1380-1396} for the number as a
         * whole, and never assigns {@code ACUP-NEW-CUST-PHONE-NUM-2} itself; {@code 1205-COMPARE-OLD-NEW} compares the
         * parts one at a time at {@code :1751-1753}; {@code 3000-SEND-MAP} returns them to the screen individually at
         * {@code :2942-2944}; and the punctuated value that reaches the record is assembled only at write time by
         * {@code STRING} at {@code :4035-4041}. Carrying the whole instead would name a byte image this group never
         * holds.</p>
         */
        @Size(max = 3,
                message = "NewDetails.phoneNumber2Prefix must not exceed its declared width of 3 characters"
                        + " (ACUP-NEW-CUST-PHONE-NUM-2B, app/cbl/COACTUPC.cbl:826)")
        private final String phoneNumber2Prefix;

        /**
         * {@code ACUP-NEW-CUST-PHONE-NUM-2C} PIC X(4) &mdash; {@code app/cbl/COACTUPC.cbl:828}. The line number of
         * telephone number 2.
         *
         * <p>This part is a stored member and the fifteen-byte whole is the derived view, which is the opposite of
         * {@link OldDetails}. The asymmetry belongs to the source, not to the model: {@code 1100-RECEIVE-MAP} assigns
         * only the parts, at {@code app/cbl/COACTUPC.cbl:1396} for this one and {@code :1380-1396} for the number as a
         * whole, and never assigns {@code ACUP-NEW-CUST-PHONE-NUM-2} itself; {@code 1205-COMPARE-OLD-NEW} compares the
         * parts one at a time at {@code :1751-1753}; {@code 3000-SEND-MAP} returns them to the screen individually at
         * {@code :2942-2944}; and the punctuated value that reaches the record is assembled only at write time by
         * {@code STRING} at {@code :4035-4041}. Carrying the whole instead would name a byte image this group never
         * holds.</p>
         */
        @Size(max = 4,
                message = "NewDetails.phoneNumber2LineNumber must not exceed its declared width of 4 characters"
                        + " (ACUP-NEW-CUST-PHONE-NUM-2C, app/cbl/COACTUPC.cbl:828)")
        private final String phoneNumber2LineNumber;

        /**
         * {@code ACUP-NEW-CUST-SSN-1} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:831}. First part of the
         * social security number. This is the NEW side of the declared asymmetry: the OLD side keeps one flat
         * nine-character field at {@code app/cbl/COACTUPC.cbl:742}. Blank or a single asterisk on the screen
         * becomes LOW-VALUES here, at {@code :1235}.
         */
        @Size(max = 3,
                message = "ssnPart1 must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:831)")
        private final String ssnPart1;

        /**
         * {@code ACUP-NEW-CUST-SSN-2} PIC X(02) &mdash; {@code app/cbl/COACTUPC.cbl:832}. Second part of the
         * social security number. Blank or a single asterisk on the screen becomes LOW-VALUES here, at
         * {@code app/cbl/COACTUPC.cbl:1242}.
         */
        @Size(max = 2,
                message = "ssnPart2 must not exceed its declared width of 2 characters"
                        + " (app/cbl/COACTUPC.cbl:832)")
        private final String ssnPart2;

        /**
         * {@code ACUP-NEW-CUST-SSN-3} PIC X(04) &mdash; {@code app/cbl/COACTUPC.cbl:833}. Third part of the
         * social security number. Blank or a single asterisk on the screen becomes LOW-VALUES here, at
         * {@code app/cbl/COACTUPC.cbl:1249}. The three parts sit beneath the group item
         * {@code ACUP-NEW-CUST-SSN-X} at {@code :830} with a {@code PIC 9(09)} REDEFINES over them at
         * {@code :834-835}; {@code 1205-COMPARE-OLD-NEW} compares that whole group at {@code :1754}, which is
         * meaningful only because the parts are contiguous.
         */
        @Size(max = 4,
                message = "ssnPart3 must not exceed its declared width of 4 characters"
                        + " (app/cbl/COACTUPC.cbl:833)")
        private final String ssnPart3;

        /**
         * {@code ACUP-NEW-CUST-GOVT-ISSUED-ID} PIC X(20) &mdash; {@code app/cbl/COACTUPC.cbl:836}.
         * Government-issued identifier.
         */
        @Size(max = 20,
                message = "governmentIssuedId must not exceed its declared width of 20 characters"
                        + " (app/cbl/COACTUPC.cbl:836)")
        private final String governmentIssuedId;

        /**
         * {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:837}.
         */
        @Size(max = 8,
                message = "dateOfBirth must not exceed its declared width of 8 characters"
                        + " (app/cbl/COACTUPC.cbl:837)")
        private final String dateOfBirth;

        /**
         * {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID} PIC X(10) &mdash; {@code app/cbl/COACTUPC.cbl:843}. Electronic
         * funds transfer account identifier. Compared plainly by both regimes, at
         * {@code app/cbl/COACTUPC.cbl:1761-1762} and {@code :4181-4182}, and edited by
         * {@code 1245-EDIT-NUM-REQD} under the label {@code 'EFT Account Id'} at {@code :1648} with a declared
         * length of ten at {@code :1651}.
         */
        @Size(max = 10,
                message = "eftAccountId must not exceed its declared width of 10 characters"
                        + " (app/cbl/COACTUPC.cbl:843)")
        private final String eftAccountId;

        /**
         * {@code ACUP-NEW-CUST-PRI-HOLDER-IND} PIC X(01) &mdash; {@code app/cbl/COACTUPC.cbl:844}. Primary card
         * holder indicator, a raw one-character code and never an enum. Edited by {@code 1220-EDIT-YESNO} under
         * the label {@code 'Primary Card Holder'} at {@code app/cbl/COACTUPC.cbl:1657}.
         */
        @Size(max = 1,
                message = "primaryCardHolderIndicator must not exceed its declared width of 1 character"
                        + " (app/cbl/COACTUPC.cbl:844)")
        private final String primaryCardHolderIndicator;

        /**
         * {@code ACUP-NEW-CUST-FICO-SCORE-X} PIC X(03) &mdash; {@code app/cbl/COACTUPC.cbl:845}. Credit score
         * as text, which is what {@code 1205-COMPARE-OLD-NEW} compares. A {@code PIC 9(03)} REDEFINES overlays
         * the same bytes at {@code app/cbl/COACTUPC.cbl:846-847}, and that numeric reading is what
         * {@code 9700-CHECK-CHANGE-IN-REC} compares at {@code :4186}.
         */
        @Size(max = 3,
                message = "ficoScore must not exceed its declared width of 3 characters"
                        + " (app/cbl/COACTUPC.cbl:845)")
        private final String ficoScore;

        /**
         * Creates an immutable {@code ACUP-NEW-DETAILS} edited group from the bound JSON payload.
         *
         * <p>The group is declared at {@code app/cbl/COACTUPC.cbl:757} and is populated in the source by
         * {@code INITIALIZE ACUP-NEW-DETAILS} at {@code :1047} followed by the guarded moves of
         * {@code 1100-RECEIVE-MAP}. Every member is assigned once here and never again.</p>
         *
         * @param accountId {@code ACUP-NEW-ACCT-ID-X} PIC X(11) at {@code app/cbl/COACTUPC.cbl:759}; retained exactly
         *                  as received, with no trimming, padding, case folding or null/blank coercion
         * @param activeStatus {@code ACUP-NEW-ACTIVE-STATUS} PIC X(01) at {@code app/cbl/COACTUPC.cbl:762}; retained
         *                     exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param currentBalance {@code ACUP-NEW-CURR-BAL} PIC X(12) at {@code app/cbl/COACTUPC.cbl:763}; retained
         *                       exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param creditLimit {@code ACUP-NEW-CREDIT-LIMIT} PIC X(12) at {@code app/cbl/COACTUPC.cbl:766}; retained
         *                    exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param cashCreditLimit {@code ACUP-NEW-CASH-CREDIT-LIMIT} PIC X(12) at {@code app/cbl/COACTUPC.cbl:769};
         *                        retained exactly as received, with no trimming, padding, case folding or null/blank
         *                        coercion
         * @param openDate {@code ACUP-NEW-OPEN-DATE} PIC X(08) at {@code app/cbl/COACTUPC.cbl:772}; retained exactly
         *                 as received, with no trimming, padding, case folding or null/blank coercion
         * @param expiraionDate {@code ACUP-NEW-EXPIRAION-DATE} PIC X(08) at {@code app/cbl/COACTUPC.cbl:778}; retained
         *                      exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param reissueDate {@code ACUP-NEW-REISSUE-DATE} PIC X(08) at {@code app/cbl/COACTUPC.cbl:784}; retained
         *                    exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param currentCycleCredit {@code ACUP-NEW-CURR-CYC-CREDIT} PIC X(12) at {@code app/cbl/COACTUPC.cbl:790};
         *                           retained exactly as received, with no trimming, padding, case folding or
         *                           null/blank coercion
         * @param currentCycleDebit {@code ACUP-NEW-CURR-CYC-DEBIT} PIC X(12) at {@code app/cbl/COACTUPC.cbl:793};
         *                          retained exactly as received, with no trimming, padding, case folding or null/blank
         *                          coercion
         * @param groupId {@code ACUP-NEW-GROUP-ID} PIC X(10) at {@code app/cbl/COACTUPC.cbl:796}; retained exactly as
         *                received, with no trimming, padding, case folding or null/blank coercion
         * @param customerId {@code ACUP-NEW-CUST-ID-X} PIC X(09) at {@code app/cbl/COACTUPC.cbl:798}; retained exactly
         *                   as received, with no trimming, padding, case folding or null/blank coercion
         * @param firstName {@code ACUP-NEW-CUST-FIRST-NAME} PIC X(25) at {@code app/cbl/COACTUPC.cbl:801}; retained
         *                  exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param middleName {@code ACUP-NEW-CUST-MIDDLE-NAME} PIC X(25) at {@code app/cbl/COACTUPC.cbl:802}; retained
         *                   exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param lastName {@code ACUP-NEW-CUST-LAST-NAME} PIC X(25) at {@code app/cbl/COACTUPC.cbl:803}; retained
         *                 exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param addressLine1 {@code ACUP-NEW-CUST-ADDR-LINE-1} PIC X(50) at {@code app/cbl/COACTUPC.cbl:804};
         *                     retained exactly as received, with no trimming, padding, case folding or null/blank
         *                     coercion
         * @param addressLine2 {@code ACUP-NEW-CUST-ADDR-LINE-2} PIC X(50) at {@code app/cbl/COACTUPC.cbl:805};
         *                     retained exactly as received, with no trimming, padding, case folding or null/blank
         *                     coercion
         * @param addressLine3 {@code ACUP-NEW-CUST-ADDR-LINE-3} PIC X(50) at {@code app/cbl/COACTUPC.cbl:806};
         *                     retained exactly as received, with no trimming, padding, case folding or null/blank
         *                     coercion
         * @param addressStateCode {@code ACUP-NEW-CUST-ADDR-STATE-CD} PIC X(02) at {@code app/cbl/COACTUPC.cbl:807};
         *                         retained exactly as received, with no trimming, padding, case folding or null/blank
         *                         coercion
         * @param addressCountryCode {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD} PIC X(03) at {@code
         *                           app/cbl/COACTUPC.cbl:808}; retained exactly as received, with no trimming,
         *                           padding, case folding or null/blank coercion
         * @param addressZip {@code ACUP-NEW-CUST-ADDR-ZIP} PIC X(10) at {@code app/cbl/COACTUPC.cbl:809}; retained
         *                   exactly as received, with no trimming, padding, case folding or null/blank coercion
         * @param phoneNumber1AreaCode {@code ACUP-NEW-CUST-PHONE-NUM-1A} PIC X(3) at
         *                             {@code app/cbl/COACTUPC.cbl:814}; retained exactly as received, with no
         *                             trimming, padding, case folding or null/blank coercion
         * @param phoneNumber1Prefix {@code ACUP-NEW-CUST-PHONE-NUM-1B} PIC X(3) at
         *                           {@code app/cbl/COACTUPC.cbl:816}; retained exactly as received, with no trimming,
         *                           padding, case folding or null/blank coercion
         * @param phoneNumber1LineNumber {@code ACUP-NEW-CUST-PHONE-NUM-1C} PIC X(4) at
         *                               {@code app/cbl/COACTUPC.cbl:818}; retained exactly as received, with no
         *                               trimming, padding, case folding or null/blank coercion
         * @param phoneNumber2AreaCode {@code ACUP-NEW-CUST-PHONE-NUM-2A} PIC X(3) at
         *                             {@code app/cbl/COACTUPC.cbl:824}; retained exactly as received, with no
         *                             trimming, padding, case folding or null/blank coercion
         * @param phoneNumber2Prefix {@code ACUP-NEW-CUST-PHONE-NUM-2B} PIC X(3) at
         *                           {@code app/cbl/COACTUPC.cbl:826}; retained exactly as received, with no trimming,
         *                           padding, case folding or null/blank coercion
         * @param phoneNumber2LineNumber {@code ACUP-NEW-CUST-PHONE-NUM-2C} PIC X(4) at
         *                               {@code app/cbl/COACTUPC.cbl:828}; retained exactly as received, with no
         *                               trimming, padding, case folding or null/blank coercion
         * @param ssnPart1 {@code ACUP-NEW-CUST-SSN-1} PIC X(03) at {@code app/cbl/COACTUPC.cbl:831}; retained exactly
         *                 as received, with no trimming, padding, case folding or null/blank coercion
         * @param ssnPart2 {@code ACUP-NEW-CUST-SSN-2} PIC X(02) at {@code app/cbl/COACTUPC.cbl:832}; retained exactly
         *                 as received, with no trimming, padding, case folding or null/blank coercion
         * @param ssnPart3 {@code ACUP-NEW-CUST-SSN-3} PIC X(04) at {@code app/cbl/COACTUPC.cbl:833}; retained exactly
         *                 as received, with no trimming, padding, case folding or null/blank coercion
         * @param governmentIssuedId {@code ACUP-NEW-CUST-GOVT-ISSUED-ID} PIC X(20) at {@code
         *                           app/cbl/COACTUPC.cbl:836}; retained exactly as received, with no trimming,
         *                           padding, case folding or null/blank coercion
         * @param dateOfBirth {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD} PIC X(08) at {@code app/cbl/COACTUPC.cbl:837};
         *                    retained exactly as received, with no trimming, padding, case folding or null/blank
         *                    coercion
         * @param eftAccountId {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID} PIC X(10) at {@code app/cbl/COACTUPC.cbl:843};
         *                     retained exactly as received, with no trimming, padding, case folding or null/blank
         *                     coercion
         * @param primaryCardHolderIndicator {@code ACUP-NEW-CUST-PRI-HOLDER-IND} PIC X(01) at {@code
         *                                   app/cbl/COACTUPC.cbl:844}; retained exactly as received, with no trimming,
         *                                   padding, case folding or null/blank coercion
         * @param ficoScore {@code ACUP-NEW-CUST-FICO-SCORE-X} PIC X(03) at {@code app/cbl/COACTUPC.cbl:845}; retained
         *                  exactly as received, with no trimming, padding, case folding or null/blank coercion
         */
        @JsonCreator
        public NewDetails(
                @JsonProperty("accountId") final String accountId,
                @JsonProperty("activeStatus") final String activeStatus,
                @JsonProperty("currentBalance") final String currentBalance,
                @JsonProperty("creditLimit") final String creditLimit,
                @JsonProperty("cashCreditLimit") final String cashCreditLimit,
                @JsonProperty("openDate") final String openDate,
                @JsonProperty("expiraionDate") final String expiraionDate,
                @JsonProperty("reissueDate") final String reissueDate,
                @JsonProperty("currentCycleCredit") final String currentCycleCredit,
                @JsonProperty("currentCycleDebit") final String currentCycleDebit,
                @JsonProperty("groupId") final String groupId,
                @JsonProperty("customerId") final String customerId,
                @JsonProperty("firstName") final String firstName,
                @JsonProperty("middleName") final String middleName,
                @JsonProperty("lastName") final String lastName,
                @JsonProperty("addressLine1") final String addressLine1,
                @JsonProperty("addressLine2") final String addressLine2,
                @JsonProperty("addressLine3") final String addressLine3,
                @JsonProperty("addressStateCode") final String addressStateCode,
                @JsonProperty("addressCountryCode") final String addressCountryCode,
                @JsonProperty("addressZip") final String addressZip,
                @JsonProperty("phoneNumber1AreaCode") final String phoneNumber1AreaCode,
                @JsonProperty("phoneNumber1Prefix") final String phoneNumber1Prefix,
                @JsonProperty("phoneNumber1LineNumber") final String phoneNumber1LineNumber,
                @JsonProperty("phoneNumber2AreaCode") final String phoneNumber2AreaCode,
                @JsonProperty("phoneNumber2Prefix") final String phoneNumber2Prefix,
                @JsonProperty("phoneNumber2LineNumber") final String phoneNumber2LineNumber,
                @JsonProperty("ssnPart1") final String ssnPart1,
                @JsonProperty("ssnPart2") final String ssnPart2,
                @JsonProperty("ssnPart3") final String ssnPart3,
                @JsonProperty("governmentIssuedId") final String governmentIssuedId,
                @JsonProperty("dateOfBirth") final String dateOfBirth,
                @JsonProperty("eftAccountId") final String eftAccountId,
                @JsonProperty("primaryCardHolderIndicator") final String primaryCardHolderIndicator,
                @JsonProperty("ficoScore") final String ficoScore) {
            this.accountId = accountId;
            this.activeStatus = activeStatus;
            this.currentBalance = currentBalance;
            this.creditLimit = creditLimit;
            this.cashCreditLimit = cashCreditLimit;
            this.openDate = openDate;
            this.expiraionDate = expiraionDate;
            this.reissueDate = reissueDate;
            this.currentCycleCredit = currentCycleCredit;
            this.currentCycleDebit = currentCycleDebit;
            this.groupId = groupId;
            this.customerId = customerId;
            this.firstName = firstName;
            this.middleName = middleName;
            this.lastName = lastName;
            this.addressLine1 = addressLine1;
            this.addressLine2 = addressLine2;
            this.addressLine3 = addressLine3;
            this.addressStateCode = addressStateCode;
            this.addressCountryCode = addressCountryCode;
            this.addressZip = addressZip;
            this.phoneNumber1AreaCode = phoneNumber1AreaCode;
            this.phoneNumber1Prefix = phoneNumber1Prefix;
            this.phoneNumber1LineNumber = phoneNumber1LineNumber;
            this.phoneNumber2AreaCode = phoneNumber2AreaCode;
            this.phoneNumber2Prefix = phoneNumber2Prefix;
            this.phoneNumber2LineNumber = phoneNumber2LineNumber;
            this.ssnPart1 = ssnPart1;
            this.ssnPart2 = ssnPart2;
            this.ssnPart3 = ssnPart3;
            this.governmentIssuedId = governmentIssuedId;
            this.dateOfBirth = dateOfBirth;
            this.eftAccountId = eftAccountId;
            this.primaryCardHolderIndicator = primaryCardHolderIndicator;
            this.ficoScore = ficoScore;
        }

        /**
         * Returns {@code ACUP-NEW-ACCT-ID-X}, PIC X(11) at {@code app/cbl/COACTUPC.cbl:759}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Returns {@code ACUP-NEW-ACTIVE-STATUS}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:762}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getActiveStatus() {
            return activeStatus;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-BAL}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:763}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Returns {@code ACUP-NEW-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:766}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCreditLimit() {
            return creditLimit;
        }

        /**
         * Returns {@code ACUP-NEW-CASH-CREDIT-LIMIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:769}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCashCreditLimit() {
            return cashCreditLimit;
        }

        /**
         * Returns {@code ACUP-NEW-OPEN-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:772}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getOpenDate() {
            return openDate;
        }

        /**
         * Returns {@code ACUP-NEW-EXPIRAION-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:778}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getExpiraionDate() {
            return expiraionDate;
        }

        /**
         * Returns {@code ACUP-NEW-REISSUE-DATE}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:784}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getReissueDate() {
            return reissueDate;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-CREDIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:790}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCurrentCycleCredit() {
            return currentCycleCredit;
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-DEBIT}, PIC X(12) at {@code app/cbl/COACTUPC.cbl:793}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCurrentCycleDebit() {
            return currentCycleDebit;
        }

        /**
         * Returns {@code ACUP-NEW-GROUP-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:796}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ID-X}, PIC X(09) at {@code app/cbl/COACTUPC.cbl:798}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getCustomerId() {
            return customerId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-FIRST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:801}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-MIDDLE-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:802}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getMiddleName() {
            return middleName;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-LAST-NAME}, PIC X(25) at {@code app/cbl/COACTUPC.cbl:803}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getLastName() {
            return lastName;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-LINE-1}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:804}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressLine1() {
            return addressLine1;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-LINE-2}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:805}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressLine2() {
            return addressLine2;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-LINE-3}, PIC X(50) at {@code app/cbl/COACTUPC.cbl:806}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressLine3() {
            return addressLine3;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-STATE-CD}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:807}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressStateCode() {
            return addressStateCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:808}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressCountryCode() {
            return addressCountryCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-ADDR-ZIP}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:809}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getAddressZip() {
            return addressZip;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:814}.
         *
         * @return the stored value exactly as received, with no trimming, case folding or padding applied;
         *         {@code null} when absent
         */
        public String getPhoneNumber1AreaCode() {
            return phoneNumber1AreaCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:816}.
         *
         * @return the stored value exactly as received, with no trimming, case folding or padding applied;
         *         {@code null} when absent
         */
        public String getPhoneNumber1Prefix() {
            return phoneNumber1Prefix;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:818}.
         *
         * @return the stored value exactly as received, with no trimming, case folding or padding applied;
         *         {@code null} when absent
         */
        public String getPhoneNumber1LineNumber() {
            return phoneNumber1LineNumber;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2A}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:824}.
         *
         * @return the stored value exactly as received, with no trimming, case folding or padding applied;
         *         {@code null} when absent
         */
        public String getPhoneNumber2AreaCode() {
            return phoneNumber2AreaCode;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2B}, PIC X(3) at {@code app/cbl/COACTUPC.cbl:826}.
         *
         * @return the stored value exactly as received, with no trimming, case folding or padding applied;
         *         {@code null} when absent
         */
        public String getPhoneNumber2Prefix() {
            return phoneNumber2Prefix;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2C}, PIC X(4) at {@code app/cbl/COACTUPC.cbl:828}.
         *
         * @return the stored value exactly as received, with no trimming, case folding or padding applied;
         *         {@code null} when absent
         */
        public String getPhoneNumber2LineNumber() {
            return phoneNumber2LineNumber;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-SSN-1}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:831}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getSsnPart1() {
            return ssnPart1;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-SSN-2}, PIC X(02) at {@code app/cbl/COACTUPC.cbl:832}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getSsnPart2() {
            return ssnPart2;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-SSN-3}, PIC X(04) at {@code app/cbl/COACTUPC.cbl:833}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getSsnPart3() {
            return ssnPart3;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-GOVT-ISSUED-ID}, PIC X(20) at {@code app/cbl/COACTUPC.cbl:836}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getGovernmentIssuedId() {
            return governmentIssuedId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD}, PIC X(08) at {@code app/cbl/COACTUPC.cbl:837}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getDateOfBirth() {
            return dateOfBirth;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID}, PIC X(10) at {@code app/cbl/COACTUPC.cbl:843}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getEftAccountId() {
            return eftAccountId;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PRI-HOLDER-IND}, PIC X(01) at {@code app/cbl/COACTUPC.cbl:844}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getPrimaryCardHolderIndicator() {
            return primaryCardHolderIndicator;
        }

        /**
         * Returns {@code ACUP-NEW-CUST-FICO-SCORE-X}, PIC X(03) at {@code app/cbl/COACTUPC.cbl:845}.
         *
         * @return the stored value exactly as received, with no trimming, case folding, padding or rounding
         * applied.
         */
        public String getFicoScore() {
            return ficoScore;
        }

        /**
         * Returns the year component of the account open date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:775-777}. This is a derived view, not a stored member: it slices
         * {@code openDate} and is deliberately not named as a bean property, so JSON binding never invokes it.
         *
         * @return the year component, {@code null} when {@code openDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         * which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateYear() {
            return compactDatePart(openDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.openDate");
        }

        /**
         * Returns the month component of the account open date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:775-777}. This is a derived view, not a stored member: it slices
         * {@code openDate} and is deliberately not named as a bean property, so JSON binding never invokes it.
         *
         * @return the month component, {@code null} when {@code openDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         * which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateMonth() {
            return compactDatePart(openDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.openDate");
        }

        /**
         * Returns the day component of the account open date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:775-777}. This is a derived view, not a stored member: it slices
         * {@code openDate} and is deliberately not named as a bean property, so JSON binding never invokes it.
         *
         * @return the day component, {@code null} when {@code openDate} is absent, or the empty string when the
         * stored value does not reach the component
         * @throws IllegalArgumentException when {@code openDate} is longer than the declared eight characters,
         * which means the dash-separated live form was stored into the compact snapshot field
         */
        public String openDateDay() {
            return compactDatePart(openDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.openDate");
        }

        /**
         * Returns the year component of the account expiry date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:781-783}. This is a derived view, not a stored member: it slices
         * {@code expirationDate} and is deliberately not named as a bean property, so JSON binding never
         * invokes it.
         *
         * @return the year component, {@code null} when {@code expirationDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expiraionDateYear() {
            return compactDatePart(expiraionDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.expiraionDate");
        }

        /**
         * Returns the month component of the account expiry date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:781-783}. This is a derived view, not a stored member: it slices
         * {@code expirationDate} and is deliberately not named as a bean property, so JSON binding never
         * invokes it.
         *
         * @return the month component, {@code null} when {@code expirationDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expiraionDateMonth() {
            return compactDatePart(expiraionDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.expiraionDate");
        }

        /**
         * Returns the day component of the account expiry date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:781-783}. This is a derived view, not a stored member: it slices
         * {@code expirationDate} and is deliberately not named as a bean property, so JSON binding never
         * invokes it.
         *
         * @return the day component, {@code null} when {@code expirationDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code expirationDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String expiraionDateDay() {
            return compactDatePart(expiraionDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.expiraionDate");
        }

        /**
         * Returns the year component of the account reissue date, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:787-789}. This is a derived view, not a stored member: it slices
         * {@code reissueDate} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the year component, {@code null} when {@code reissueDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateYear() {
            return compactDatePart(reissueDate, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.reissueDate");
        }

        /**
         * Returns the month component of the account reissue date, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:787-789}. This is a derived view, not a stored member: it slices
         * {@code reissueDate} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the month component, {@code null} when {@code reissueDate} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateMonth() {
            return compactDatePart(reissueDate, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.reissueDate");
        }

        /**
         * Returns the day component of the account reissue date, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:787-789}. This is a derived view, not a stored member: it slices
         * {@code reissueDate} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the day component, {@code null} when {@code reissueDate} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code reissueDate} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String reissueDateDay() {
            return compactDatePart(reissueDate, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.reissueDate");
        }

        /**
         * Returns the year component of the date of birth, taken from COBOL offset 1 of the compact
         * eight-character value, matching the {@code PIC X(4)} part declared at
         * {@code app/cbl/COACTUPC.cbl:840-842}. This is a derived view, not a stored member: it slices
         * {@code dateOfBirth} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the year component, {@code null} when {@code dateOfBirth} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthYear() {
            return compactDatePart(dateOfBirth, DATE_YEAR_BEGIN, DATE_YEAR_END,
                    "NewDetails.dateOfBirth");
        }

        /**
         * Returns the month component of the date of birth, taken from COBOL offset 5 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:840-842}. This is a derived view, not a stored member: it slices
         * {@code dateOfBirth} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the month component, {@code null} when {@code dateOfBirth} is absent, or the empty string
         * when the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthMonth() {
            return compactDatePart(dateOfBirth, DATE_MONTH_BEGIN, DATE_MONTH_END,
                    "NewDetails.dateOfBirth");
        }

        /**
         * Returns the day component of the date of birth, taken from COBOL offset 7 of the compact
         * eight-character value, matching the {@code PIC X(2)} part declared at
         * {@code app/cbl/COACTUPC.cbl:840-842}. This is a derived view, not a stored member: it slices
         * {@code dateOfBirth} and is deliberately not named as a bean property, so JSON binding never invokes
         * it.
         *
         * @return the day component, {@code null} when {@code dateOfBirth} is absent, or the empty string when
         * the stored value does not reach the component
         * @throws IllegalArgumentException when {@code dateOfBirth} is longer than the declared eight
         * characters, which means the dash-separated live form was stored into the compact snapshot field
         */
        public String dateOfBirthDay() {
            return compactDatePart(dateOfBirth, DATE_DAY_BEGIN, DATE_DAY_END,
                    "NewDetails.dateOfBirth");
        }

        /**
         * Returns {@code ACUP-NEW-CURR-BAL-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCurrentBalance()} declared at {@code app/cbl/COACTUPC.cbl:764-765}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-NEW-CURR-BAL} at {@code app/cbl/COACTUPC.cbl:763}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the current balance at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal currentBalanceAmount() {
            return zonedDecimalAmount(currentBalance);
        }

        /**
         * Returns {@code ACUP-NEW-CREDIT-LIMIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCreditLimit()} declared at {@code app/cbl/COACTUPC.cbl:767-768}.
         *
         * @return the credit limit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal creditLimitAmount() {
            return zonedDecimalAmount(creditLimit);
        }

        /**
         * Returns {@code ACUP-NEW-CASH-CREDIT-LIMIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCashCreditLimit()} declared at {@code app/cbl/COACTUPC.cbl:770-771}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-NEW-CASH-CREDIT-LIMIT} at {@code app/cbl/COACTUPC.cbl:769}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the cash credit limit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal cashCreditLimitAmount() {
            return zonedDecimalAmount(cashCreditLimit);
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-CREDIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCurrentCycleCredit()} declared at {@code app/cbl/COACTUPC.cbl:791-792}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-NEW-CURR-CYC-CREDIT} at {@code app/cbl/COACTUPC.cbl:790}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the current cycle credit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal currentCycleCreditAmount() {
            return zonedDecimalAmount(currentCycleCredit);
        }

        /**
         * Returns {@code ACUP-NEW-CURR-CYC-DEBIT-N}, the {@code PIC S9(10)V99} REDEFINES view over
         * {@link #getCurrentCycleDebit()} declared at {@code app/cbl/COACTUPC.cbl:794-795}.
         *
         * <p>This is a derived view, not a stored member: it decodes the twelve bytes held by
         * {@code ACUP-NEW-CURR-CYC-DEBIT} at {@code app/cbl/COACTUPC.cbl:793}, and it is deliberately not
         * named as a bean property, so JSON binding never invokes it and no caller can submit a numeric
         * reading that contradicts the text it redefines.</p>
         *
         * @return the current cycle debit at scale {@value #AMOUNT_SCALE}, or {@code null} when the stored member is
         *         absent, blank or not a zoned-decimal image of the declared twelve characters
         */
        public BigDecimal currentCycleDebitAmount() {
            return zonedDecimalAmount(currentCycleDebit);
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-1}, the fifteen-byte whole that the three stored parts
         * redefine, declared at {@code app/cbl/COACTUPC.cbl:810} and overlaid at {@code :811-819}.
         *
         * <p>This is a derived view, not a stored member, and it is deliberately not named as a bean
         * property, so JSON binding never reads or writes it. The direction of derivation is the opposite
         * of {@link OldDetails#phoneNumber1AreaCode()} and its siblings, and the asymmetry is the source's:
         * on this group every statement that assigns telephone storage assigns a <em>part</em>
         * ({@code app/cbl/COACTUPC.cbl:1359-1375} for this number), the parts are what
         * {@code 1205-COMPARE-OLD-NEW} compares one at a time at {@code :1748-1750}, and the parts are what
         * {@code 3000-SEND-MAP} returns to the screen at {@code :2939-2941}. The whole is referenced exactly
         * twice in the entire program, at {@code :1633} and {@code :1641}, and only to carry fifteen bytes
         * into {@code WS-EDIT-US-PHONE-NUM}, which is itself redefined into the same three parts at
         * {@code app/cbl/COACTUPC.cbl:83-100} so that {@code 1260-EDIT-US-PHONE-NUM} can read them back
         * out.</p>
         *
         * <p><strong>The filler bytes are spaces, not punctuation.</strong>
         * {@code INITIALIZE ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl:1047} sets all fifteen bytes to
         * spaces, and no statement anywhere in the program assigns the three {@code FILLER} positions
         * afterwards, so spaces are what the transport actually carries. The receiving overlay corroborates
         * this directly: its three filler positions carry {@code VALUE '('}, {@code VALUE ')'} and
         * {@code VALUE '-'} clauses that the author <em>commented out</em>, at
         * {@code app/cbl/COACTUPC.cbl:86}, {@code :91} and {@code :96}, leaving the bytes unvalued. The
         * punctuated
         * {@code (nnn)nnn-nnnn} rendering is a different value, assembled only at write time by
         * {@code STRING '(' ... ')' ... '-' ...} at {@code :4027-4033}; producing it here would describe a
         * byte image this group never holds.</p>
         *
         * @return the fifteen-character overlay image assembled from the three stored parts, each padded to
         *         its declared width, with the filler positions left as spaces; an absent part contributes
         *         its own width in spaces, exactly as {@code INITIALIZE} leaves it
         * @throws IllegalArgumentException when a stored part is longer than its declared width and so
         *         cannot be placed at the offset the overlay assigns it
         */
        public String phoneNumber1() {
            return overlayTelephoneImage(phoneNumber1AreaCode, phoneNumber1Prefix,
                    phoneNumber1LineNumber, "NewDetails.phoneNumber1");
        }

        /**
         * Returns {@code ACUP-NEW-CUST-PHONE-NUM-2}, the fifteen-byte whole that the three stored parts
         * redefine, declared at {@code app/cbl/COACTUPC.cbl:820} and overlaid at {@code :821-829}.
         *
         * <p>The contract is that of {@link #phoneNumber1()} applied to the second number: the parts are
         * assigned at {@code app/cbl/COACTUPC.cbl:1380-1396}, compared at {@code :1751-1753}, returned to
         * the screen at {@code :2942-2944}, and assembled into the punctuated record value only at
         * {@code :4035-4041}. The whole is referenced solely at {@code :1641}, as the transport into
         * {@code WS-EDIT-US-PHONE-NUM}.</p>
         *
         * @return the fifteen-character overlay image assembled from the three stored parts, each padded to
         *         its declared width, with the filler positions left as spaces; an absent part contributes
         *         its own width in spaces, exactly as {@code INITIALIZE} leaves it
         * @throws IllegalArgumentException when a stored part is longer than its declared width and so
         *         cannot be placed at the offset the overlay assigns it
         */
        public String phoneNumber2() {
            return overlayTelephoneImage(phoneNumber2AreaCode, phoneNumber2Prefix,
                    phoneNumber2LineNumber, "NewDetails.phoneNumber2");
        }

        /**
         * Returns {@code ACUP-NEW-CUST-FICO-SCORE}, the {@code PIC 9(03)} REDEFINES view over
         * {@link #getFicoScore()} declared at {@code app/cbl/COACTUPC.cbl:846-847}.
         *
         * <p>This is a derived view, not a stored member: it reads the three bytes held by
         * {@code ACUP-NEW-CUST-FICO-SCORE-X} at {@code app/cbl/COACTUPC.cbl:845}, and it is
         * deliberately not named as a bean property, so JSON binding never invokes it. The source needs both
         * readings of the one cell: the text at {@code :1767-1768} and the number at {@code :4186}.</p>
         *
         * @return the credit score, or {@code null} when the stored member is not three decimal digits
         */
        public Integer ficoScoreValue() {
            return unsignedDisplayScore(ficoScore);
        }

        /**
         * Reports whether the credit score satisfies {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850},
         * declared at {@code app/cbl/COACTUPC.cbl:848-849}.
         *
         * <p>The condition name is declared on the NEW group's numeric member and on no other member, so the
         * range is a NEW-side rule and {@link OldDetails} deliberately has no counterpart to this method.
         * It is expressed as a predicate rather than as a bean constraint because the source does not reject
         * an out-of-range score at move time: {@code MOVE ACSTFCOI OF CACTUPAI TO}
         * {@code ACUP-NEW-CUST-FICO-SCORE-X} at {@code :1283} stores whatever was typed, and the
         * validation paragraph then tests this condition and emits the source's own message. A bean
         * constraint would substitute a framework error for that message and would additionally collapse the
         * absent and blank states, which {@code app/cpy/CSSETATY.cpy:17-27} renders distinctly.</p>
         *
         * @return {@code true} only when the stored member reads as a number between
         *         {@value #FICO_SCORE_MINIMUM} and {@value #FICO_SCORE_MAXIMUM} inclusive;
         *         {@code false} when it is absent, blank, non-numeric or out of range
         */
        public boolean ficoScoreIsInValidRange() {
            final Integer score = ficoScoreValue();
            return score != null
                    && score.intValue() >= FICO_SCORE_MINIMUM
                    && score.intValue() <= FICO_SCORE_MAXIMUM;
        }

        /**
         * Rejects any JSON property this type does not declare.
         *
         * <p>The guard is local rather than declarative because the declarative alternative does not hold.
         * Jackson's type-level unknown-property setting is consulted only while the object mapper still has
         * failure on unknown properties enabled, the framework disables that by default, and no profile in
         * this repository re-enables it; a type-level annotation would therefore be inert here, which is
         * worse than absent because it would read as protection that is not in force.</p>
         *
         * <p>The guard is also what makes the overlay canonicalisation enforceable rather than advisory. The
         * numeric and component readings of the snapshot members are derived views, not properties; without
         * this method a caller could still send {@code currentBalanceAmount} or {@code ficoScoreValue} and
         * have it silently discarded, which looks like acceptance. Rejection says plainly that one storage
         * cell has one wire representation.</p>
         *
         * <p>Neither the offending property name nor its value is reproduced in the thrown message. Both are
         * untrusted input, and copying either into a message that reaches a log record would let a caller
         * forge log content; on this payload the same rule keeps a rejected social security number, date of
         * birth or government-issued identifier out of the logs. Nothing is stored: this type is immutable,
         * and the method exists only to fail.</p>
         *
         * @param name  the unrecognised property name supplied by the caller, deliberately neither stored nor
         *              reproduced in the thrown message
         * @param value the unrecognised property value supplied by the caller, deliberately neither stored nor
         *              reproduced in the thrown message
         * @throws IllegalArgumentException always, because an unrecognised property is never acceptable here
         */
        @JsonAnySetter
        void rejectUnrecognisedProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "NewDetails accepts only the 35 properties declared by "
                            + "app/cbl/COACTUPC.cbl:757-847 for the ACUP-NEW-DETAILS edited group, and "
                            + "the payload contained a property that is not "
                            + "one of them. Numeric and component readings of a snapshot member are "
                            + "derived views rather than properties, because the source redefines one "
                            + "storage cell instead of declaring two. The offending name and value are "
                            + "withheld because they are untrusted input.");
        }

    }

    /**
     * Rejects any JSON property this type does not declare.
     *
     * <p>The guard is local rather than declarative because the declarative alternative does not hold.
     * Jackson's type-level unknown-property setting is consulted only while the object mapper still has
     * failure on unknown properties enabled, the framework disables that by default, and no profile in
     * this repository re-enables it; a type-level annotation would therefore be inert here, which is
     * worse than absent because it would read as protection that is not in force.</p>
     *
     * <p>The guard is also what makes the overlay canonicalisation enforceable rather than advisory. The
     * numeric and component readings of the snapshot members are derived views, not properties; without
     * this method a caller could still send {@code currentBalanceAmount} or {@code ficoScoreValue} and
     * have it silently discarded, which looks like acceptance. Rejection says plainly that one storage
     * cell has one wire representation.</p>
     *
     * <p>Neither the offending property name nor its value is reproduced in the thrown message. Both are
     * untrusted input, and copying either into a message that reaches a log record would let a caller
     * forge log content; on this payload the same rule keeps a rejected social security number, date of
     * birth or government-issued identifier out of the logs. Nothing is stored: this type is immutable,
     * and the method exists only to fail.</p>
     *
     * @param name  the unrecognised property name supplied by the caller, deliberately neither stored nor
     *              reproduced in the thrown message
     * @param value the unrecognised property value supplied by the caller, deliberately neither stored nor
     *              reproduced in the thrown message
     * @throws IllegalArgumentException always, because an unrecognised property is never acceptable here
     */
    @JsonAnySetter
    void rejectUnrecognisedProperty(String name, Object value) {
        throw new IllegalArgumentException(
                "AccountUpdateRequest accepts only the 56 properties declared by "
                        + "app/cpy-bms/COACTUP.CPY for the 54 screen fields, plus the sealed snapshot "
                        + "and app/cbl/COACTUPC.cbl:757 for the edited group, and the payload "
                        + "contained a property that is not "
                        + "one of them. The as-displayed group of app/cbl/COACTUPC.cbl:669 is NOT among "
                        + "them: it arrives only inside the sealed snapshot, so a property named for it is "
                        + "refused here. Numeric and component readings of a snapshot member are "
                        + "derived views rather than properties, because the source redefines one "
                        + "storage cell instead of declaring two. The offending name and value are "
                        + "withheld because they are untrusted input.");
    }
}
