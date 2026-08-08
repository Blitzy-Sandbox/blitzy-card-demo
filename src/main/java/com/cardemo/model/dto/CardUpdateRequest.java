/*
 * ******************************************************************
 * Program     : CardUpdateRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Card-update request; the only card payload carrying an expiry day, plus the
 *               old and new snapshot groups the stateless change detection requires.
 * Source      : app/cpy-bms/COCRDUP.CPY (17 input fields) and
 *               app/cbl/COCRDUPC.cbl:291-313 (CCUP-OLD-DETAILS / CCUP-NEW-DETAILS) @ 7756d89
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

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Inbound request payload for the card-update conversation, replacing the BMS {@code RECEIVE MAP} of
 * transaction {@code CCUP} whose handler is {@code app/cbl/COCRDUPC.cbl}.
 *
 * <p>The component list is derived field-for-field from the generated symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY}. That member's input group {@code 01 CCRDUPAI.} spans lines 17 to 120 and
 * declares <strong>exactly 17</strong> input fields; the redefining output group
 * {@code 01 CCRDUPAO REDEFINES CCRDUPAI.} begins at line 121 and is deliberately <em>not</em> modelled here,
 * because only the input side crosses the trust boundary. Each screen field is generated as a quintuple of a
 * {@code COMP PIC S9(4)} length field, an attribute byte, a redefined attribute alias, four reserved bytes and
 * the data field itself; only the trailing data field, the one whose name ends in {@code I}, is a payload
 * field. Every width below is transcribed from its PIC clause rather than inferred, so the payload round-trips
 * to the same bytes the legacy map accepted.
 *
 * <p>Two further components, {@code snapshot} and {@code newDetails}, are <em>not</em> screen
 * fields and are deliberately excluded from that census of 17. They carry the WORKING-STORAGE
 * snapshot groups of {@code app/cbl/COCRDUPC.cbl}, whose widths come from that program's own
 * declarations at lines 291 to 313 rather than from the map - {@code newDetails} as the group itself, and
 * {@code snapshot} as one sealed opaque string standing in for {@code CCUP-OLD-DETAILS}. The reason they are
 * carried on the wire at all, and the reason one of them is sealed, are developed below.</p>
 *
 * <p><strong>DISTINCTION 4 - the expiry day exists here and nowhere in the detail map.</strong>
 * {@code app/cpy-bms/COCRDUP.CPY:96} declares {@code 02 EXPDAYI PIC X(2).}, so {@code expiryDay}
 * is present below at position 13, immediately after {@code expiryYear}. Conversely a
 * repository-wide token search over {@code app/cpy-bms/COCRDSL.CPY} returns <strong>zero</strong>
 * occurrences of {@code EXPDAY}: that member runs {@code EXPYEARI PIC X(4)} at line 90 straight
 * into {@code INFOMSGL} at line 91, with no expiry-day quintuple at all. The card detail
 * projection {@code CardDto} therefore has <strong>no counterpart to this component, by design</strong>.
 * That asymmetry is the contract and is not an oversight to be harmonised in either direction:
 * adding an expiry day to the detail projection would invent a field the source never sent, and
 * dropping it from this request would reduce the verified count from 17 to 16.</p>
 *
 * <p><strong>Function-key divergence between the two card maps.</strong> This update map splits the
 * function-key legend into <em>two</em> fields, {@code FKEYSI PIC X(21)} at
 * {@code app/cpy-bms/COCRDUP.CPY:114} and {@code FKEYSCI PIC X(18)} at
 * {@code app/cpy-bms/COCRDUP.CPY:120}, totalling 39 bytes. The detail map declares a
 * <em>single</em> field {@code FKEYSI PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108} and has no
 * {@code FKEYSCI} at all. Both fields are modelled separately below; they are not collapsed into
 * one, and the detail map's single 75-byte shape is not borrowed.</p>
 *
 * <p><strong>Message-width relationship across the three card maps.</strong> The information and
 * error message widths <em>agree</em> between this map and the detail map -
 * {@code INFOMSGI PIC X(40)} at {@code app/cpy-bms/COCRDUP.CPY:102} against
 * {@code app/cpy-bms/COCRDSL.CPY:96}, and {@code ERRMSGI PIC X(80)} at
 * {@code app/cpy-bms/COCRDUP.CPY:108} against {@code app/cpy-bms/COCRDSL.CPY:102}. They
 * <em>disagree</em> with the card list map, which declares {@code INFOMSGI PIC X(45)} at
 * {@code app/cpy-bms/COCRDLI.CPY:282} and {@code ERRMSGI PIC X(78)} at
 * {@code app/cpy-bms/COCRDLI.CPY:288}. Agreement between two members is therefore a coincidence of
 * those two members and never a licence to share a width across the corpus.</p>
 *
 * <p><strong>No shared header helper exists, and none may be created.</strong> Six header fields
 * recur across all seventeen symbolic maps, but their widths are not uniform.
 * {@code CURTIMEI} is {@code PIC X(8)} here at {@code app/cpy-bms/COCRDUP.CPY:54} and equally
 * {@code PIC X(8)} at {@code app/cpy-bms/COCRDSL.CPY:54}, yet
 * {@code app/cpy-bms/COSGN00.CPY:54} alone declares {@code PIC X(9)}. A shared header type would
 * have to pick one width and would therefore be <em>factually wrong</em> for at least one map,
 * not merely redundant. The six header fields are consequently declared inline below.</p>
 *
 * <p><strong>Account identifier type divergence.</strong> {@code ACCTSIDI} is alphanumeric
 * {@code PIC X(11)} here at {@code app/cpy-bms/COCRDUP.CPY:60} and likewise at
 * {@code app/cpy-bms/COCRDSL.CPY:60}, {@code app/cpy-bms/COCRDLI.CPY:66} and
 * {@code app/cpy-bms/COACTUP.CPY:60}, but {@code app/cpy-bms/COACTVW.CPY:60} declares it numeric
 * as {@code PIC 99999999999} - the sole numeric declaration of this field in the corpus. The
 * component below is a {@code String}, which survives both spellings. A numeric Java type would
 * strip the zero padding that the seeded fixtures rely on, the first account record in
 * {@code app/data/ASCII/acctdata.txt} being {@code 00000000001}, and byte-exact baseline
 * comparison would fail.</p>
 *
 * <p><strong>Card number type divergence.</strong> The persisted record layout
 * {@code app/cpy/CVACT02Y.cpy:5} declares {@code 05 CARD-NUM PIC X(16)} - <em>alphanumeric</em> -
 * whereas the shared communication area {@code app/cpy/COCOM01Y.cpy:41} declares
 * {@code 10 CDEMO-CARD-NUM PIC 9(16)} - <em>numeric</em> - for the same logical value. The two
 * are deliberately <strong>not silently unified</strong>. {@code String} is the only type that
 * survives both and that preserves a significant leading zero, so {@code cardNumber} is a
 * {@code String} of width 16.</p>
 *
 * <p><strong>The snapshot detail groups are carried, because a stateless server has nowhere else
 * to keep them.</strong> {@code app/cbl/COCRDUPC.cbl:291} declares {@code 05 CCUP-OLD-DETAILS.}
 * and {@code :303} declares {@code 05 CCUP-NEW-DETAILS.}, exactly as
 * {@code app/cbl/COACTUPC.cbl:669} and {@code :757} declare their account counterparts. Both card
 * groups live in WORKING-STORAGE rather than in the symbolic map, which is why the map's field
 * census of 17 is unaffected by them; but living outside the map does not make them dispensable.
 * {@code 9000-READ-DATA} at {@code app/cbl/COCRDUPC.cbl:1343-1370} captures the old group from the
 * record at the moment the screen is first populated, and {@code 9300-CHECK-CHANGE-IN-REC} at
 * {@code :1498-1521} compares the freshly re-read record against that captured group field by
 * field before any rewrite is attempted, abandoning the write with
 * {@code 'Record changed by some one else. Please review'} ({@code :207-208}) when they differ.
 * Under CICS the captured group survives from display to submit inside the pseudo-conversational
 * task state; over HTTP nothing survives between two requests, so the snapshot has to travel in
 * the request body. It travels <strong>sealed</strong>, as the single opaque {@code snapshot} member: the
 * value {@code GET /api/cards/detail} returned, echoed back unchanged. There is no readable
 * {@code oldDetails} member on this contract, and the reason is that a group the caller can rewrite is not a
 * snapshot at all - replacing it with the live row makes {@code 9300-CHECK-CHANGE-IN-REC} unconditionally
 * true, so the guarantee it exists to provide would hold only for callers who chose not to defeat it.
 * {@code com.cardemo.security.SnapshotTokenService} seals the group with AES-256-GCM bound to the operation,
 * the two identifiers, the authenticated principal and an expiry, and the update service opens it server-side.
 * The edited group is modelled below as {@code newDetails}, mirroring the source hierarchy leaf for leaf.</p>
 *
 * <p><strong>The store-level version column is retained and is not a substitute.</strong> A
 * version counter detects <em>that</em> a row changed; the source detects <em>which business
 * fields</em> changed. A concurrent write that set a field back to its original value passes the
 * source's test and fails a version test, so the two guarantees are different and neither replaces
 * the other. Both layers are consequently required, per the two-layer optimistic-concurrency rule
 * of the migration plan. The comparison itself remains a service concern; this type's whole
 * responsibility is to carry the values that comparison needs.</p>
 *
 * <p><strong>Which fields participate in which comparison.</strong> The two comparisons the source
 * performs read different subsets, and conflating them changes which updates are accepted.</p>
 * <ul>
 *   <li>The concurrency test at {@code app/cbl/COCRDUPC.cbl:1503-1508} compares the embossed name, the
 *       three {@code EXPIRAION} components and the active status. It does <em>not</em> compare the
 *       account identifier or the card number, those two being the read key rather than mutable data.
 *       The source additionally compared a card verification value at {@code :1503}; this type declares
 *       no such component, and the predicate is gone with it - see the note below.</li>
 *   <li>The did-anything-change test at {@code :679-683} compares the whole
 *       {@code CCUP-NEW-CARDDATA} group against {@code CCUP-OLD-CARDDATA} to decide
 *       {@code 'No change detected with respect to values fetched.'} ({@code :187-188}). That group
 *       is the embossed name, the {@code EXPIRAION} components and the active status only; the account
 *       identifier and the card number sit outside it at {@code :292-293} and {@code :304-305} and are
 *       therefore excluded, as was the verification value at {@code :294} and {@code :306}. The nested
 *       {@code CardData} type below reproduces exactly that grouping, so the subset is expressed by
 *       the shape rather than left to a comment.</li>
 *   </ul>
 *
 * <p><strong>The expiry date is compared component-wise, never as a whole string.</strong> The
 * live record field is {@code CARD-EXPIRAION-DATE PIC X(10)} in dash-separated form, so the source
 * addresses its parts by reference modification at offsets 1, 6 and 9 -
 * {@code CARD-EXPIRAION-DATE(1:4)}, {@code (6:2)} and {@code (9:2)} at
 * {@code app/cbl/COCRDUPC.cbl:1505-1507} - while the snapshot holds three discrete fields with no
 * separators. Comparing the two as whole strings would report a change on every request. This is
 * the card counterpart of the offset asymmetry the migration plan records for the account program,
 * and it is why the three components below are separately addressable rather than folded into one
 * ten-character value.</p>
 *
 * <p><strong>Case handling is asymmetric, and record equality is not the source's test.</strong>
 * The embossed name alone is upper-cased on <em>both</em> sides - once when the snapshot is
 * captured, at {@code app/cbl/COCRDUPC.cbl:1356-1358}, and again on the live value immediately
 * before the comparison, at {@code :1499-1501} - so that one field compares case-insensitively
 * while every other field compares exactly. {@code FUNCTION UPPER-CASE} on both operands at
 * {@code :680-681} makes the group test case-insensitive in the same way. Two consequences follow
 * for the comparing service. Java record equality is case-sensitive throughout and is therefore
 * <em>not</em> the source's test; and a COBOL group comparison pads every leaf to its PIC width, so
 * {@code "AB"} and {@code "AB "} compare equal there and unequal here. The service must upper-case
 * with {@code Locale.ROOT} and pad to the declared widths before comparing. This type performs
 * neither operation, because it performs no comparison at all.</p>
 *
 * <p><strong>The verification value has no component, and one preserved quirk in the new group.</strong>
 * {@code CCUP-NEW-CVV-CD} is never populated from the screen, because {@code app/cpy-bms/COCRDUP.CPY}
 * declares no such field at all: {@code 1100-RECEIVE-MAP} at {@code app/cbl/COCRDUPC.cbl:586-635}
 * populates every other new leaf from the map and leaves that one as {@code INITIALIZE} left it,
 * whereupon {@code :1464-1465} moves the unpopulated field into {@code CARD-CVV-CD-X PIC X(03)} and
 * reinterprets it through the redefining alias {@code CARD-CVV-CD-N PIC 9(03)} ({@code :107-109}) on the
 * way to {@code CARD-UPDATE-CVV-CD PIC 9(03)} ({@code :317}) - destroying the stored value on every
 * successful update. That path is <strong>not</strong> reproduced and no component exists for it. The value
 * is stored here - {@code card_cvv_cd CHAR(3) NOT NULL} in {@code V1__create_schema.sql}, mapped on
 * {@link com.cardemo.model.entity.Card} and seeded by {@code V3__seed_data.sql}'s {@code card} insert -
 * so this is a labelled
 * deviation and not an absence: reproducing the two {@code MOVE}s would overwrite live authentication data
 * with spaces, which Rule 1 Clause D forbids, and the entity's field is write-once with no getter, so there
 * is no read path to obtain the operand through either. No component is declared here for the same reason
 * the screen has none - {@code app/cpy-bms/COCRDUP.CPY} declares no verification field, so no caller could
 * supply one, and accepting one would put authentication data on the wire. The quirk that IS preserved is the
 * second one: {@code CCUP-NEW-EXPDAY} is moved unconditionally at {@code :621}, whereas every other new
 * leaf is guarded by an {@code '*'}-or-SPACES test that substitutes LOW-VALUES. That asymmetry is
 * contract, not oversight.</p>
 *
 * <p><strong>Validation matches the source: no stricter, no looser.</strong> Each component
 * carries only {@code @Size(max = ...)} at exactly its PIC width. No regex is imposed that the
 * source does not perform, and no component carries a presence or non-blank constraint of any
 * kind, because the legacy map tolerates a blank field and reports it with its own message rather
 * than rejecting the transmission. No class-level cross-field expiry assertion is declared either.
 * The analogous cross-field edit in {@code app/cbl/COACTUPC.cbl} is <em>gated</em> on both
 * single-field flags already being valid: lines 1665 and 1666 read {@code IF FLG-STATE-ISVALID}
 * and {@code AND FLG-ZIPCODE-ISVALID} before the guarded
 * {@code PERFORM 1280-EDIT-US-STATE-ZIP-CD} at lines 1667 and 1668. An unconditional assertion
 * would therefore fire in cases the source never reports.</p>
 *
 * <p>The same discipline governs the two snapshot members. Each is marked {@code @Valid} so that
 * the constraints declared on its own components take effect during binding rather than being
 * inert decoration - a cascade omitted is a cascade that never fires - and each leaf inside carries
 * {@code @Size(max = ...)} at exactly the width its COBOL declaration gives it. Neither member is
 * marked as required: {@code app/cbl/COCRDUPC.cbl:1345} and {@code :586} both begin by executing
 * {@code INITIALIZE} on the group, so an entirely unpopulated group is a state the source itself
 * produces and reports on, rather than a transmission it refuses.</p>
 *
 * <p><strong>{@code cardStatusCode} is deliberately not bound to an enum.</strong> It remains a raw
 * one-character {@code String}. No card-status enum exists in the target's four-constant enum set,
 * and binding an inbound code to an enum would turn an out-of-domain value such as {@code "Z"}
 * into a Jackson deserialization failure that <em>replaces the source's own message</em> with a
 * framework error. The out-of-domain value is therefore carried through and judged by the service
 * that owns the rule.</p>
 *
 * <p><strong>The three-state model must be preserved: absent, blank and marked are distinct.</strong>
 * {@code app/cpy/CSSETATY.cpy} is a parameterised {@code COPY ... REPLACING} PROCEDURE DIVISION
 * template, taking the parameters {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)},
 * whose body at lines 18 to 27 is:</p>
 *
 * <pre>
 * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 * </pre>
 *
 * <p>The model is OK, NOT-OK and BLANK, in which <strong>BLANK is a state distinct from
 * NOT-OK</strong> that additionally stamps a literal {@code '*'} into the screen field, and in
 * which the markers fire <em>only</em> on re-entry and never on first display. Being procedural,
 * {@code CSSETATY} gets no class of its own; it maps onto validation annotations plus per-field
 * error markers. The same program proves the distinction at message level, carrying two different
 * literals for one field: {@code 88 CRED-LIMIT-IS-BLANK} takes the value
 * {@code 'Credit Limit must be supplied'} at {@code app/cbl/COACTUPC.cbl:505-506} while
 * {@code 88 CRED-LIMIT-IS-NOT-VALID} takes {@code 'Credit Limit is not valid'} at
 * {@code app/cbl/COACTUPC.cbl:507-508}. Consequently this type performs <strong>no ingest-time
 * normalisation whatsoever</strong>: {@code null}, the empty string and a {@code '*'}-marked value
 * remain three distinguishable states. {@code null} is never coerced to {@code ""}, {@code ""} is
 * never coerced to {@code null}, and neither is ever coerced to a sentinel. Nothing is stripped of
 * surrounding spaces and no case folding is applied on ingest; where a service later applies a case
 * function it passes {@code Locale.ROOT} so that the result cannot vary with the default locale of
 * the host.</p>
 *
 * <p><strong>Observable message contract for the expiry fields.</strong> The literals the legacy
 * validator emits for the same logical fields are, character-for-character,
 * {@code 'Card expiry month must be between 1 and 12'} at {@code app/cbl/COACTUPC.cbl:509-510}
 * and {@code 'Invalid card expiry year'} at {@code app/cbl/COACTUPC.cbl:511-512}. They are
 * reproduced exactly by the service that emits them; they are recorded here because the parity
 * comparison is byte-for-byte on message text, so paraphrasing either one is a defect.</p>
 *
 * <p><strong>Type discipline.</strong> Every leaf is a {@code String}; the only non-{@code String}
 * components anywhere in this file are the three nested record types that reproduce the source's own
 * group hierarchy, and each of those bottoms out in {@code String} leaves. The expiry month, year
 * and day stay textual at widths 2, 4 and 2 and are <em>never</em> promoted to a calendar or
 * year-month type: the corresponding batch check is a string comparison against the leading ten
 * characters of a timestamp, and a calendar type would normalise away the very representation that
 * the comparison depends on. This map carries no monetary field, so no fixed-scale decimal type
 * arises here; and no IEEE 754 approximate binary numeric type, in either primitive or boxed form,
 * appears anywhere in this file, the package-wide prohibition on them being absolute because a
 * financial value must never be approximated. Date-and-time values are held as text package-wide;
 * none appears on this map and none is introduced. Each of these prohibited type names is described
 * rather than written out, so that a mechanical audit of this file for prohibited numeric or
 * calendar types returns no match at all.</p>
 *
 * <p><strong>Security.</strong> Two screen components are never-emit values: the card number, from
 * {@code app/cpy-bms/COCRDUP.CPY:66}, and the cardholder name, from
 * {@code app/cpy-bms/COCRDUP.CPY:72}. The snapshot groups carry their own copies of the card number, the
 * embossed name and the card expiry components. The graver value the legacy groups also carried, the
 * verification value of {@code app/cbl/COCRDUPC.cbl:294} and {@code :306}, is handled by not being
 * declared on the wire: a component that does not exist cannot be emitted, and cannot be accepted either.
 * The value itself is stored, and unreadable where it is stored. A
 * record's implicitly generated
 * {@code toString()} renders <em>every</em> component, so an override is mandatory on this type and
 * on each of the three nested snapshot types; each emits only values that carry no cardholder or
 * authentication data, and omits the sensitive ones outright rather than masking or truncating
 * them, since a mask still discloses length. {@code src/main/resources/logback-spring.xml} supplies a
 * masking rule as the second line of defence, applied identically in every profile, but never emitting
 * these values remains the first: a mask matches only the field names and value shapes it was given.
 * This type holds no password, hash, token or signing key, and none may be added - the card-update
 * screen exposes no such field. Neither it nor any nested type implements any interface, so none
 * can take part in Java native serialization, a pattern the security standard flags as risky. Its
 * imports are limited to the framework-managed validation API and the one Jackson annotation that
 * makes the unknown-property rejection below effective; no third-party dependency is introduced.</p>
 *
 * <p><strong>Inputs.</strong> All 17 screen components, each nullable and each bounded by its PIC
 * width, plus the sealed {@code snapshot} member and the edited group {@code newDetails}, both nullable, the
 * group cascaded with {@code @Valid} and bounded leaf by leaf and the sealed member bounded by length.
 * <strong>Outputs.</strong> The canonical accessors, plus a redacted {@code toString()} on this
 * type and on all three nested types.
 * <strong>Side effects.</strong> None; this is an immutable, purely structural carrier that
 * performs no comparison, mapping, normalisation or arithmetic. It rejects an unrecognised JSON
 * property by throwing, which stores nothing.</p>
 *
 * <p><strong>How this type is built and exercised.</strong> It compiles as part of the single Maven
 * module with {@code ./mvnw clean verify}, under {@code -Xlint:all -Werror}, so any warning here fails
 * the build. Its unit tests belong in {@code src/test/java/com/cardemo/unit/model} rather than
 * alongside it, and {@code CardUpdateRequestTest} is present there; the type is additionally referenced
 * from four test classes, so neither the class nor its coverage is absent.
 * One pitfall is worth stating for whoever extends them: a {@code @Size} constraint
 * declared on a record component is <em>not</em> readable through
 * {@code RecordComponent.getAnnotation}, because the constraint's own target list omits record
 * components; the compiler propagates it to the backing field, the accessor and the canonical
 * constructor parameter instead. Assertions about the widths must therefore read the field or the
 * accessor, or they will report a false failure against a correct class.</p>
 *
 * <p><strong>Error modes.</strong></p>
 * <ul>
 *   <li>A component longer than its PIC width fails {@code @Size} during
 *       {@code @Valid} binding, surfacing as a constraint violation naming the component and its
 *       permitted width. The offending value is never echoed, because several of these components
 *       carry cardholder or authentication data.</li>
 *   <li>A blank or absent component is <em>not</em> a binding error. It is carried through
 *       unchanged so that the service can distinguish the BLANK state from the NOT-OK state and
 *       emit the corresponding legacy message, per {@code app/cpy/CSSETATY.cpy}.</li>
 *   <li>An out-of-domain {@code cardStatusCode} is carried through rather than rejected at
 *       binding time, so the service emits the source's own message instead of a framework
 *       deserialization error.</li>
 *   <li>A leaf of {@code newDetails} longer than the width its COBOL
 *       declaration gives it fails {@code @Size} during the {@code @Valid} cascade, naming the
 *       leaf and its permitted width and never echoing the value - which matters most for the card
 *       number.</li>
 *   <li>A JSON body carrying an unknown property is rejected by
 *       {@link #rejectUnrecognisedProperty(String, Object)}, which always throws. The rejection is
 *       local and therefore unconditional. The declarative alternative does not hold here: the
 *       framework disables failure on unknown properties by default, no profile in this repository
 *       re-enables it, and a type-level annotation that depends on that disabled setting would read
 *       as protection while being inert - which is worse than absent.</li>
 *   <li>A malformed body fails before this type is constructed and never yields a partially
 *       populated instance, the canonical constructor being all-or-nothing.</li>
 *   </ul>
 *
 * <p><strong>Findings, classified by severity.</strong></p>
 * <ul>
 *   <li><strong>Medium, closed</strong> - corpus census correction. Prior-generation plan prose asserted
 *       460 input fields across the seventeen symbolic maps. Counting the input group of every member
 *       gives <strong>441</strong>. The remediation has been applied: {@code docs/technical-specifications.md}
 *       cites 441 and records the supersession in its section 0.2.2.1 corrections table, verified on
 *       1 August 2026. There is no code impact, because per-map counts drive payload shape and this
 *       member's count of 17 is unaffected and independently verified.</li>
 *   <li><strong>Low, closed</strong> - the per-map table accompanying that prose listed
 *       {@code COACTVW.CPY} at 36 input fields; the true figure is 37, because
 *       {@code ACCTSIDI PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60} is an input field despite
 *       being numeric. This is part of the arithmetic behind the 441 figure, and the specification's
 *       per-map entry now reads 37.</li>
 *   <li><strong>Blocker</strong> - stateless concurrency. Omitting the snapshot groups and leaving
 *       change detection to the store-level version
 *       column alone, on the reasoning that {@code app/cpy-bms/COCRDUP.CPY} declares no snapshot
 *       field, is sound about the map and wrong about the contract: the map bounds
 *       the <em>screen</em> fields, whereas {@code 9300-CHECK-CHANGE-IN-REC} at
 *       {@code app/cbl/COCRDUPC.cbl:1498-1521} compares business field values that a stateless
 *       server cannot otherwise retain between the display and the submit. Applied
 *       here: carry both groups as immutable, width-bounded, {@code @Valid}-cascaded members, and
 *       keep the version column as the second layer.</li>
 *   <li>No unresolved Blocker or High severity finding applies to this file.</li>
 * </ul>
 *
 * @param transactionName {@code TRNNAMEI}, {@code PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:24}.
 * @param title01 {@code TITLE01I}, {@code PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:30}.
 * @param currentDate {@code CURDATEI}, {@code PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:36}.
 * @param programName {@code PGMNAMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:42}.
 * @param title02 {@code TITLE02I}, {@code PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:48}.
 * @param currentTime {@code CURTIMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:54}.
 * @param accountId {@code ACCTSIDI}, {@code PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:60}.
 * @param cardNumber {@code CARDSIDI}, {@code PIC X(16)}, {@code app/cpy-bms/COCRDUP.CPY:66}.
 * @param cardholderName {@code CRDNAMEI}, {@code PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:72}.
 * @param cardStatusCode {@code CRDSTCDI}, {@code PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:78}.
 * @param expiryMonth {@code EXPMONI}, {@code PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:84}.
 * @param expiryYear {@code EXPYEARI}, {@code PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:90}.
 * @param expiryDay {@code EXPDAYI}, {@code PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:96}.
 * @param informationMessage {@code INFOMSGI}, {@code PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:102}.
 * @param errorMessage {@code ERRMSGI}, {@code PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:108}.
 * @param functionKeys {@code FKEYSI}, {@code PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:114}.
 * @param functionKeysContinued {@code FKEYSCI}, {@code PIC X(18)}, {@code app/cpy-bms/COCRDUP.CPY:120}.
 * @param snapshot the sealed {@code 05 CCUP-OLD-DETAILS} of {@code app/cbl/COCRDUPC.cbl:291-301}. Not a
 *     screen field: it carries, as one opaque authenticated string, the snapshot that
 *     {@code 9000-READ-DATA} captures at {@code :1343-1370} and that {@code 9300-CHECK-CHANGE-IN-REC}
 *     compares at {@code :1503-1508}. Echoed back from {@code GET /api/cards/detail} unaltered - it is an
 *     authentication tag over specific bytes, so any change at all makes it fail to open. May be
 *     {@code null}, which the service reports as an unconfirmed change rather than comparing against
 *     nothing.
 * @param newDetails {@code 05 CCUP-NEW-DETAILS}, {@code app/cbl/COCRDUPC.cbl:303-313}. Not a screen field:
 *     {@code 1100-RECEIVE-MAP} derives every leaf but the CVV code from the map at {@code :586-635}.
 *     May be {@code null}.
 */
public record CardUpdateRequest(

        // 1. TRNNAMEI  PIC X(4)   app/cpy-bms/COCRDUP.CPY:24  - header, transaction identifier
        @Size(max = 4, message = "transactionName must be at most 4 characters")
        String transactionName,

        // 2. TITLE01I  PIC X(40)  app/cpy-bms/COCRDUP.CPY:30  - header, first title line
        @Size(max = 40, message = "title01 must be at most 40 characters")
        String title01,

        // 3. CURDATEI  PIC X(8)   app/cpy-bms/COCRDUP.CPY:36  - header, display date as text
        @Size(max = 8, message = "currentDate must be at most 8 characters")
        String currentDate,

        // 4. PGMNAMEI  PIC X(8)   app/cpy-bms/COCRDUP.CPY:42  - header, owning program name
        @Size(max = 8, message = "programName must be at most 8 characters")
        String programName,

        // 5. TITLE02I  PIC X(40)  app/cpy-bms/COCRDUP.CPY:48  - header, second title line
        @Size(max = 40, message = "title02 must be at most 40 characters")
        String title02,

        // 6. CURTIMEI  PIC X(8)   app/cpy-bms/COCRDUP.CPY:54  - header; X(9) at COSGN00.CPY:54
        @Size(max = 8, message = "currentTime must be at most 8 characters")
        String currentTime,

        // 7. ACCTSIDI  PIC X(11)  app/cpy-bms/COCRDUP.CPY:60  - text, preserves zero padding
        @Size(max = 11, message = "accountId must be at most 11 characters")
        String accountId,

        // 8. CARDSIDI  PIC X(16)  app/cpy-bms/COCRDUP.CPY:66  - PII, never logged or rendered
        @Size(max = 16, message = "cardNumber must be at most 16 characters")
        String cardNumber,

        // 9. CRDNAMEI  PIC X(50)  app/cpy-bms/COCRDUP.CPY:72  - PII, never logged or rendered
        @Size(max = 50, message = "cardholderName must be at most 50 characters")
        String cardholderName,

        // 10. CRDSTCDI PIC X(1)   app/cpy-bms/COCRDUP.CPY:78  - raw String, never an enum
        @Size(max = 1, message = "cardStatusCode must be at most 1 character")
        String cardStatusCode,

        // 11. EXPMONI  PIC X(2)   app/cpy-bms/COCRDUP.CPY:84  - text, never a date type
        @Size(max = 2, message = "expiryMonth must be at most 2 characters")
        String expiryMonth,

        // 12. EXPYEARI PIC X(4)   app/cpy-bms/COCRDUP.CPY:90  - text, never a date type
        @Size(max = 4, message = "expiryYear must be at most 4 characters")
        String expiryYear,

        // 13. EXPDAYI  PIC X(2)   app/cpy-bms/COCRDUP.CPY:96  - DISTINCTION 4: absent from
        //     app/cpy-bms/COCRDSL.CPY, which holds zero EXPDAY tokens. Never a date type.
        @Size(max = 2, message = "expiryDay must be at most 2 characters")
        String expiryDay,

        // 14. INFOMSGI PIC X(40)  app/cpy-bms/COCRDUP.CPY:102 - X(45) at COCRDLI.CPY:282
        @Size(max = 40, message = "informationMessage must be at most 40 characters")
        String informationMessage,

        // 15. ERRMSGI  PIC X(80)  app/cpy-bms/COCRDUP.CPY:108 - X(78) at COCRDLI.CPY:288
        @Size(max = 80, message = "errorMessage must be at most 80 characters")
        String errorMessage,

        // 16. FKEYSI   PIC X(21)  app/cpy-bms/COCRDUP.CPY:114 - one X(75) field at COCRDSL.CPY:108
        @Size(max = 21, message = "functionKeys must be at most 21 characters")
        String functionKeys,

        // 17. FKEYSCI  PIC X(18)  app/cpy-bms/COCRDUP.CPY:120 - no counterpart on COCRDSL.CPY
        @Size(max = 18, message = "functionKeysContinued must be at most 18 characters")
        String functionKeysContinued,

        // 05 CCUP-OLD-DETAILS. app/cbl/COCRDUPC.cbl:291-301 - not a screen field, and not a readable group
        //    either: it travels as one sealed opaque string. The snapshot 9000-READ-DATA captures at
        //    :1343-1370 and 9300-CHECK-CHANGE-IN-REC compares at :1503-1508 is recovered from it by the
        //    service. The bound is a shape check on an untrusted body member, not a domain rule: the sealed
        //    form is base64url(nonce || AES-256-GCM(JSON envelope)) over eight fixed-width leaves, so a
        //    longer value did not come from this server and is refused before it reaches the cipher.
        @Size(max = MAX_SNAPSHOT_LENGTH,
                message = "snapshot must not exceed 4096 characters; a longer value was not issued by this"
                        + " server")
        String snapshot,

        // 05 CCUP-NEW-DETAILS. app/cbl/COCRDUPC.cbl:303-313 - not a screen field. 1100-RECEIVE-MAP
        //    derives every leaf this type declares from the map at :586-635; the legacy leaf it did NOT
        //    derive was the verification value, which this type does not declare.
        @Valid CardDetails newDetails) {

    /**
     * Upper bound on the sealed snapshot member, in characters.
     *
     * <p>Not a domain rule and not tuned to a measurement: the sealed form is
     * {@code base64url(12-byte nonce || AES-256-GCM ciphertext and tag)} over a JSON envelope holding eight
     * fixed-width leaves, so a value this server issued cannot come near this bound. It exists so that an
     * arbitrarily long string is refused by bean validation before it reaches the base64 decoder and the
     * cipher, which is the boundary check Rule 1 Clause B requires of an untrusted body member. It matches
     * {@code AccountUpdateRequest.MAX_SNAPSHOT_LENGTH} so that one client-facing rule covers both
     * surfaces.</p>
     */
    public static final int MAX_SNAPSHOT_LENGTH = 4096;

    /**
     * Returns a deliberately redacted rendering that exposes only the account identifier and the program name.
     *
     * <p>The edited group is omitted as well. It carries its own copy of the card number and the embossed
     * name, and it has its own redacted rendering; excluding it here rather than delegating keeps this
     * method's output bounded and removes any possibility that a future change to a nested rendering widens
     * what this one emits. The sealed member is omitted for the same boundedness reason, and it discloses
     * nothing in any case.</p>
     *
     * <p>Every field below is passed through {@link ApiMasking#forDiagnostics(String)}. All of them are
     * declared {@code String} and all arrive from a JSON request body, so a caller controls their bytes; a CR
     * or LF concatenated straight in here would forge log records. The escaping also makes this rendering safe
     * on an instance that failed validation, which is the usual reason something renders one - {@code @Size}
     * runs after Jackson has already constructed the record.
     *
     * @return a redacted rendering carrying only the account identifier and the program name, and no
     *     character that could terminate a log record
     */
    @Override
    public String toString() {
        return "CardUpdateRequest[accountId=" + ApiMasking.forDiagnostics(accountId)
                + ", programName=" + ApiMasking.forDiagnostics(programName) + "]";
    }

    /**
     * Rejects any JSON property that is not one of the 17 screen fields declared by
     * {@code app/cpy-bms/COCRDUP.CPY} or one of the two snapshot groups declared by
     * {@code app/cbl/COCRDUPC.cbl:291-313}, so that a request carrying an unexpected property fails
     * loudly instead of being bound with that property silently discarded.
     *
     * <p>The guard is local rather than declarative because the declarative alternative does not
     * hold. Jackson's type-level unknown-property setting is consulted only while the object mapper
     * still has failure on unknown properties enabled, the framework disables that by default, and
     * no profile in this repository re-enables it; a type-level annotation would therefore be inert
     * here, which is worse than absent because it would read as protection that is not in force. An
     * always-effective rejection makes the behaviour independent of mapper configuration.</p>
     *
     * <p>Neither the offending property name nor its value is reproduced in the thrown message.
     * Both are untrusted input, and copying either into a message that reaches a log record would
     * let a caller forge log content; on this payload the same rule is what keeps a rejected card
     * number out of the logs. The message instead names this type and its two sources,
     * which is what a caller needs in order to correct the payload. Nothing is stored: this type is
     * immutable, and the method exists only to fail.</p>
     *
     * @param name  the unrecognised property name supplied by the caller, deliberately neither
     *              stored nor reproduced in the thrown message
     * @param value the unrecognised property value supplied by the caller, deliberately neither
     *              stored nor reproduced in the thrown message
     * @throws IllegalArgumentException always, because an unrecognised property is never acceptable
     *                                 on this request
     */
    @JsonAnySetter
    void rejectUnrecognisedProperty(String name, Object value) {
        throw new IllegalArgumentException(
                "CardUpdateRequest accepts only the 17 fields declared by app/cpy-bms/COCRDUP.CPY "
                        + "and the two snapshot groups declared by app/cbl/COCRDUPC.cbl:291-313, "
                        + "and the request contained a property that is not one of them. The "
                        + "offending name and value are withheld because they are untrusted input.");
    }

    /**
     * One card snapshot group, transcribing {@code 05 CCUP-OLD-DETAILS.} at
     * {@code app/cbl/COCRDUPC.cbl:291-301} and {@code 05 CCUP-NEW-DETAILS.} at {@code :303-313}.
     *
     * <p><strong>One type serves both groups, because the two declarations are identical.</strong>
     * Leaf for leaf, at the same levels and the same PIC widths, the only difference between them is
     * the {@code OLD} or {@code NEW} infix in the COBOL data names. Declaring two Java types would
     * duplicate eight widths and invite them to drift apart; the enclosing components
     * {@code snapshot} and {@code newDetails} carry the distinction that matters, which is the
     * role each group plays rather than its shape - one sealed and server-issued, one submitted and
     * validated.</p>
     *
     * <p><strong>The nesting is the source's nesting, and it is load-bearing.</strong> The account
     * identifier and the card number sit directly under the group at {@code :292-293} - as did the
     * verification value at {@code :294}, which this type does not declare - whereas the embossed name,
     * the expiry components and the active status sit one level deeper inside
     * {@code 10 CCUP-OLD-CARDDATA.} at {@code :295-301}. That is not cosmetic: the
     * did-anything-change test at {@code :679-683} compares the {@code CARDDATA} group as a whole, so
     * the leaves inside it participate and the leaves outside it do not. Modelling {@link CardData} as a
     * separate type makes that subset structural rather than a comment a future reader could miss.</p>
     *
     * <p><strong>Validation.</strong> Every leaf carries {@code @Size(max = ...)} at exactly the
     * width its COBOL declaration gives it, and the nested group member is marked {@code @Valid} so
     * the cascade reaches those leaves. Nothing is required, nothing is trimmed, padded or
     * case-folded, and {@code null}, the empty string and a value remain three distinct states -
     * the source itself begins by executing {@code INITIALIZE} on the group at {@code :1345} and
     * {@code :586}, so a wholly unpopulated group is a state it produces rather than refuses.</p>
     *
     * <p><strong>Security.</strong> The card number is a never-emit value. The card verification value
     * that this group would otherwise have carried is handled more strongly than by never emitting it:
     * it is not declared at all, so this type cannot accept, compare or render one. It is stored on
     * {@link com.cardemo.model.entity.Card}, where it is write-once and has no read path. The generated
     * rendering for a record lists every component, so {@link #toString()} is overridden to emit only
     * the account identifier and the nested group, whose own rendering is equally redacted. Equality
     * and hash code are left as the compiler generates them; they consider every
     * component, which is correct for value semantics and discloses nothing because neither renders
     * a value.</p>
     *
     * @param accountId  {@code CCUP-OLD-ACCTID} / {@code CCUP-NEW-ACCTID}, {@code PIC X(11)},
     *                   {@code app/cbl/COCRDUPC.cbl:292} and {@code :304}. Part of the read key, so
     *                   the concurrency test at {@code :1503-1508} does not compare it.
     * @param cardNumber {@code CCUP-OLD-CARDID} / {@code CCUP-NEW-CARDID}, {@code PIC X(16)},
     *                   {@code app/cbl/COCRDUPC.cbl:293} and {@code :305}. Part of the read key and
     *                   likewise not compared. <strong>Never logged or rendered.</strong>
     * @param cardData   {@code 10 CCUP-OLD-CARDDATA.} / {@code 10 CCUP-NEW-CARDDATA.},
     *                   {@code app/cbl/COCRDUPC.cbl:295-301} and {@code :307-313}. The subgroup the
     *                   source compares as a unit.
     */
    public record CardDetails(

            // 10 CCUP-xxx-ACCTID  PIC X(11)  app/cbl/COCRDUPC.cbl:292 / :304 - read key
            @Size(max = 11, message = "accountId must be at most 11 characters")
            String accountId,

            // 10 CCUP-xxx-CARDID  PIC X(16)  app/cbl/COCRDUPC.cbl:293 / :305 - read key, PII
            @Size(max = 16, message = "cardNumber must be at most 16 characters")
            String cardNumber,

            // 10 CCUP-xxx-CVV-CD  PIC X(3)   app/cbl/COCRDUPC.cbl:294 / :306 - DELIBERATELY ABSENT.
            //    The value IS persisted: V1__create_schema.sql declares card_cvv_cd CHAR(3) NOT NULL,
            //    com.cardemo.model.entity.Card maps it and V3__seed_data.sql's card insert seeds it.
            //    What is
            //    withheld is the READ path - the entity field is write-once with no getter of any
            //    visibility, recorded in the card_cvv_cd IS DECLARED comment of
            //    V1__create_schema.sql - so no snapshot can be projected
            //    from it. And app/cpy-bms/COCRDUP.CPY declares no CVV field, so the operator never typed
            //    one and no caller could echo one: no symbolic-map contract is lost by omitting it.
            //    Accepting one over the wire would be a retention problem one hop earlier, and a
            //    component accepted then ignored fails silently, which is the worst available behaviour.
            //    Both source predicates that used it (:1503 and its restores at :1354 and :1512) had
            //    server-side operands only, so the question they asked - did the row change between the
            //    display read and the write read - is answered by @Version on the entity.

            // 10 CCUP-xxx-CARDDATA  app/cbl/COCRDUPC.cbl:295-301 / :307-313 - compared as a group
            @Valid CardData cardData) {

        /**
         * Returns a redacted rendering that omits the card number entirely.
         *
         * <p>The omitted value is cardholder data that may never reach a log record, a stack trace or a
         * diagnostic message - not masked and not truncated to a last four, because a mask still
         * discloses length. The account identifier is emitted, matching the treatment the enclosing
         * request already gives it, and the nested group is delegated to because its own rendering is
         * redacted in the same way.</p>
         *
         * @return a rendering carrying only the account identifier and the nested card data group
         */
        @Override
        public String toString() {
            return "CardDetails[accountId=" + accountId + ", cardData=" + cardData + "]";
        }

        /**
         * Rejects any JSON property that is not one of the three components this group declares.
         *
         * <p>The rationale is the enclosing request's, given at
         * {@link CardUpdateRequest#rejectUnrecognisedProperty(String, Object)}: a nested object is
         * exactly where a silently discarded property does the most damage, because a caller
         * misspelling {@code cardNumber} would otherwise submit a snapshot the service believes to be
         * absent and compare against nothing. It also refuses a {@code cvvCode} property outright,
         * which is the behaviour a caller migrating from the legacy field shape should see: a clear
         * rejection rather than a silently ignored verification value.</p>
         *
         * @param name  the unrecognised property name, deliberately neither stored nor reproduced
         * @param value the unrecognised property value, deliberately neither stored nor reproduced
         * @throws IllegalArgumentException always, an unrecognised property never being acceptable
         */
        @JsonAnySetter
        void rejectUnrecognisedProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "CardDetails accepts only the three leaves it declares from "
                            + "app/cbl/COCRDUPC.cbl:291-301, and the request contained a property "
                            + "that is not one of them. The offending name and value are withheld "
                            + "because they are untrusted input.");
        }
    }

    /**
     * The comparable subgroup of a card snapshot, transcribing {@code 10 CCUP-OLD-CARDDATA.} at
     * {@code app/cbl/COCRDUPC.cbl:295-301} and {@code 10 CCUP-NEW-CARDDATA.} at {@code :307-313}.
     *
     * <p><strong>Why this subgroup has a type of its own.</strong> The source compares it as a
     * single value: {@code app/cbl/COCRDUPC.cbl:680-681} reads
     * {@code IF (FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL FUNCTION UPPER-CASE(
     * CCUP-OLD-CARDDATA))} and sets {@code NO-CHANGES-DETECTED}
     * ({@code 'No change detected with respect to values fetched.'}, {@code :187-188}) when they
     * match. Exactly these three members participate; the account identifier, the card number and
     * the CVV code lie outside the subgroup and are excluded. Giving the subgroup a type puts that
     * boundary in the type system.</p>
     *
     * <p><strong>Two fidelity notes for whoever implements that comparison.</strong> The source
     * comparison is case-insensitive, because both operands pass through
     * {@code FUNCTION UPPER-CASE}; the generated {@link #equals(Object)} here is case-sensitive and
     * is therefore <em>not</em> the source's test. And a COBOL group comparison operates on leaves
     * already padded to their PIC widths, so {@code "AB"} and {@code "AB "} are equal there and
     * unequal here. A service reproducing the test must upper-case with {@code Locale.ROOT} and pad
     * to widths 50, 8 and 1 first. This type does neither, carrying values exactly as received.</p>
     *
     * @param cardholderName {@code CCUP-OLD-CRDNAME} / {@code CCUP-NEW-CRDNAME},
     *                       {@code PIC X(50)}, {@code app/cbl/COCRDUPC.cbl:296} and {@code :308}.
     *                       Compared at {@code :1504} after both sides are upper-cased in place, at
     *                       {@code :1356-1358} on capture and {@code :1499-1501} on comparison -
     *                       the one field that compares case-insensitively.
     *                       <strong>Never logged or rendered.</strong>
     * @param expiraionDate  {@code 20 CCUP-OLD-EXPIRAION-DATE.} / {@code 20
     *                       CCUP-NEW-EXPIRAION-DATE.}, {@code app/cbl/COCRDUPC.cbl:297} and
     *                       {@code :309}. The misspelling is the source's and is preserved
     *                       deliberately, here and on the wire.
     * @param cardStatusCode {@code CCUP-OLD-CRDSTCD} / {@code CCUP-NEW-CRDSTCD}, {@code PIC X(1)},
     *                       {@code app/cbl/COCRDUPC.cbl:301} and {@code :313}. The active-status
     *                       flag, compared at {@code :1508}. Rejected upstream with
     *                       {@code 'Card Active Status must be Y or N'} ({@code :195-196}), so it is
     *                       carried raw rather than bound to an enum.
     */
    public record CardData(

            // 20 CCUP-xxx-CRDNAME  PIC X(50)  app/cbl/COCRDUPC.cbl:296 / :308 - PII, upper-cased
            //    on both sides of the comparison by the service, never here
            @Size(max = 50, message = "cardholderName must be at most 50 characters")
            String cardholderName,

            // 20 CCUP-xxx-EXPIRAION-DATE  app/cbl/COCRDUPC.cbl:297 / :309 - source misspelling kept
            @Valid ExpiraionDate expiraionDate,

            // 20 CCUP-xxx-CRDSTCD  PIC X(1)  app/cbl/COCRDUPC.cbl:301 / :313 - raw, never an enum
            @Size(max = 1, message = "cardStatusCode must be at most 1 character")
            String cardStatusCode) {

        /**
         * Returns a redacted rendering that omits the embossed cardholder name.
         *
         * <p>The name is personal data with no diagnostic value and is omitted outright. The
         * active-status flag is a single non-identifying character and is emitted as received. The
         * expiry group is delegated to, its own rendering being value-free.</p>
         *
         * @return a rendering carrying the active-status flag and the redacted expiry group
         */
        @Override
        public String toString() {
            return "CardData[cardStatusCode=" + cardStatusCode
                    + ", expiraionDate=" + expiraionDate + "]";
        }

        /**
         * Rejects any JSON property that is not one of the three members this subgroup declares.
         *
         * <p>The rationale is the enclosing request's, given at
         * {@link CardUpdateRequest#rejectUnrecognisedProperty(String, Object)}. It matters
         * particularly here because {@code expiraionDate} carries a deliberate misspelling: a
         * caller who "corrects" it would otherwise send a group that binds to nothing and defeats
         * the did-anything-change test without any error being raised.</p>
         *
         * @param name  the unrecognised property name, deliberately neither stored nor reproduced
         * @param value the unrecognised property value, deliberately neither stored nor reproduced
         * @throws IllegalArgumentException always, an unrecognised property never being acceptable
         */
        @JsonAnySetter
        void rejectUnrecognisedProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "CardData accepts only the three members declared by "
                            + "app/cbl/COCRDUPC.cbl:295-301, whose date group is spelled "
                            + "expiraionDate as the source spells it, and the request contained a "
                            + "property that is not one of them. The offending name and value are "
                            + "withheld because they are untrusted input.");
        }
    }

    /**
     * The three expiry components of a card snapshot, transcribing
     * {@code 20 CCUP-OLD-EXPIRAION-DATE.} at {@code app/cbl/COCRDUPC.cbl:297-300} and
     * {@code 20 CCUP-NEW-EXPIRAION-DATE.} at {@code :309-312}.
     *
     * <p><strong>The misspelling is preserved on purpose.</strong> {@code EXPIRAION} is how the
     * frozen corpus spells it - in this program at {@code app/cbl/COCRDUPC.cbl:297} and
     * {@code :309}, in the persisted record layout as {@code CARD-EXPIRAION-DATE} at
     * {@code app/cpy/CVACT02Y.cpy}, and in this program's own update record as
     * {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)} at {@code :319}. Correcting it in the Java
     * identifier
     * would silently rename the JSON property and break the traceability the migration is measured
     * on, so the spelling is carried through to the wire unchanged.</p>
     *
     * <p><strong>Three components, not one ten-character value.</strong> The concurrency test reads
     * the live field by reference modification - {@code CARD-EXPIRAION-DATE(1:4)}, {@code (6:2)}
     * and {@code (9:2)} at {@code app/cbl/COCRDUPC.cbl:1505-1507} - against these three discrete
     * fields, which hold no separators. Folding them into one value would force a whole-string
     * comparison against a dash-separated live value and report a change on every request. The
     * source itself only ever assembles the ten-character form at the point of writing, with the
     * {@code STRING ... DELIMITED BY SIZE} at {@code :1467-1473} interposing a literal {@code '-'}
     * between year and month and again between month and day.</p>
     *
     * <p><strong>Component order is the group's, which is not the screen's.</strong> The group
     * declares year, then month, then day, which is what makes that concatenation yield
     * {@code YYYY-MM-DD}; the symbolic map declares them in the order month, year, day, at
     * {@code app/cpy-bms/COCRDUP.CPY:84}, {@code :90} and {@code :96}. Each order is preserved
     * where it belongs rather than harmonised.</p>
     *
     * <p><strong>Security.</strong> A card expiry date is cardholder data, so
     * {@link #toString()} is overridden to report only which components were supplied and never
     * their values.</p>
     *
     * @param expiryYear  {@code CCUP-OLD-EXPYEAR} / {@code CCUP-NEW-EXPYEAR}, {@code PIC X(4)},
     *                    {@code app/cbl/COCRDUPC.cbl:298} and {@code :310}. Compared against
     *                    {@code CARD-EXPIRAION-DATE(1:4)}. Rejected upstream with
     *                    {@code 'Invalid card expiry year'} ({@code :199-200}).
     * @param expiryMonth {@code CCUP-OLD-EXPMON} / {@code CCUP-NEW-EXPMON}, {@code PIC X(2)},
     *                    {@code app/cbl/COCRDUPC.cbl:299} and {@code :311}. Compared against
     *                    {@code CARD-EXPIRAION-DATE(6:2)}. Rejected upstream with
     *                    {@code 'Card expiry month must be between 1 and 12'} ({@code :197-198}).
     * @param expiryDay   {@code CCUP-OLD-EXPDAY} / {@code CCUP-NEW-EXPDAY}, {@code PIC X(2)},
     *                    {@code app/cbl/COCRDUPC.cbl:300} and {@code :312}. Compared against
     *                    {@code CARD-EXPIRAION-DATE(9:2)}. The one new leaf moved unconditionally,
     *                    at {@code :621}, without the {@code '*'}-or-SPACES substitution its
     *                    siblings receive.
     */
    public record ExpiraionDate(

            // 25 CCUP-xxx-EXPYEAR  PIC X(4)  app/cbl/COCRDUPC.cbl:298 / :310 - group order is
            //    year, month, day; the map's order is month, year, day
            @Size(max = 4, message = "expiryYear must be at most 4 characters")
            String expiryYear,

            // 25 CCUP-xxx-EXPMON  PIC X(2)  app/cbl/COCRDUPC.cbl:299 / :311
            @Size(max = 2, message = "expiryMonth must be at most 2 characters")
            String expiryMonth,

            // 25 CCUP-xxx-EXPDAY  PIC X(2)  app/cbl/COCRDUPC.cbl:300 / :312 - moved unconditionally
            @Size(max = 2, message = "expiryDay must be at most 2 characters")
            String expiryDay) {

        /**
         * Returns a value-free rendering that reports only which components were supplied.
         *
         * <p>A card expiry date is cardholder data, so no component value is emitted. What is
         * emitted is structural: whether each component is present, meaning non-{@code null}. That
         * distinction is the one a diagnostic reading actually needs, because an absent group and a
         * group of empty strings behave differently in the comparison the service performs, and it
         * discloses nothing about the card.</p>
         *
         * @return a rendering reporting the presence of each component and none of their values
         */
        @Override
        public String toString() {
            return "ExpiraionDate[expiryYearPresent=" + (expiryYear != null)
                    + ", expiryMonthPresent=" + (expiryMonth != null)
                    + ", expiryDayPresent=" + (expiryDay != null) + "]";
        }

        /**
         * Rejects any JSON property that is not one of the three components this group declares.
         *
         * <p>The rationale is the enclosing request's, given at
         * {@link CardUpdateRequest#rejectUnrecognisedProperty(String, Object)}.</p>
         *
         * @param name  the unrecognised property name, deliberately neither stored nor reproduced
         * @param value the unrecognised property value, deliberately neither stored nor reproduced
         * @throws IllegalArgumentException always, an unrecognised property never being acceptable
         */
        @JsonAnySetter
        void rejectUnrecognisedProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "ExpiraionDate accepts only the three components declared by "
                            + "app/cbl/COCRDUPC.cbl:297-300, and the request contained a property "
                            + "that is not one of them. The offending name and value are withheld "
                            + "because they are untrusted input.");
        }
    }
}
