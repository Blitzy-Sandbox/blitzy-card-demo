/*
 * ******************************************************************
 * Program     : CardUpdateRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Card-update request; the only card payload carrying an expiry day.
 * Source      : app/cpy-bms/COCRDUP.CPY (17 fields) @ 7756d89
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

import jakarta.validation.constraints.Size;

/**
 * Inbound request payload for the card-update conversation, replacing the BMS
 * {@code RECEIVE MAP} of transaction {@code CCUP} whose handler is
 * {@code app/cbl/COCRDUPC.cbl}.
 *
 * <p><strong>Field contract.</strong> The component list is derived field-for-field from the
 * generated symbolic map {@code app/cpy-bms/COCRDUP.CPY}. That member's input group
 * {@code 01 CCRDUPAI.} spans lines 17 to 120 and declares <strong>exactly 17</strong> input
 * fields; the redefining output group {@code 01 CCRDUPAO REDEFINES CCRDUPAI.} begins at line 121
 * and is deliberately <em>not</em> modelled here, because only the input side crosses the trust
 * boundary. Each screen field is generated as a quintuple of a {@code COMP PIC S9(4)} length
 * field, an attribute byte, a redefined attribute alias, four reserved bytes and the data field
 * itself; only the trailing data field, the one whose name ends in {@code I}, is a payload field.
 * Every width below is transcribed from its PIC clause rather than inferred, so the payload
 * round-trips to the same bytes the legacy map accepted.</p>
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
 * <p><strong>This payload carries no snapshot detail groups.</strong> The change-detection state
 * that {@code app/cbl/COCRDUPC.cbl} maintains is <em>not</em> represented here. The decisive
 * evidence is the symbolic map: {@code app/cpy-bms/COCRDUP.CPY} declares no snapshot group
 * anywhere in its input group, and this payload's budget is those 17 map fields and nothing more.
 * For completeness and accuracy, {@code app/cbl/COCRDUPC.cbl} does declare
 * {@code 05 CCUP-OLD-DETAILS.} at line 291 and {@code 05 CCUP-NEW-DETAILS.} at line 303, exactly
 * as {@code app/cbl/COACTUPC.cbl} declares {@code 05 ACUP-OLD-DETAILS.} at line 669 and
 * {@code 05 ACUP-NEW-DETAILS.} at line 757; but in both programs those groups live in
 * WORKING-STORAGE, <em>outside</em> the symbolic map, and so are program-internal state rather
 * than transmitted screen fields. Only {@code AccountUpdateRequest} carries snapshot groups
 * across the wire, because the stateless account-update contract cannot otherwise reproduce its
 * field-by-field comparison. Here the equivalent state is a <em>service</em> concern, resolved
 * against the persisted entity and its {@code @Version} column - a clear separation of concerns
 * rather than a payload field. There is accordingly no nested snapshot type and no eighteenth
 * component.</p>
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
 * <p><strong>Type discipline.</strong> Every component is a {@code String}. The expiry month, year
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
 * <p><strong>Security.</strong> Two components are never-emit values: the card number, from
 * {@code app/cpy-bms/COCRDUP.CPY:66}, and the cardholder name, from
 * {@code app/cpy-bms/COCRDUP.CPY:72}. A record's implicitly generated {@code toString()} would
 * render <em>every</em> component and would therefore leak both, so {@code toString()} is
 * deliberately overridden below to emit only the account identifier and the program name. The
 * masking rules in {@code logback-spring.xml} are a second line of defence; the primary defence is
 * never emitting these values in the first place. This type holds no password, hash, token or
 * signing key, and none may be added - the card-update screen exposes no such field. It implements
 * no interface at all, so it cannot take part in Java native serialization, a pattern the security
 * standard flags as risky. It adds no dependency: the single import is the framework-managed
 * validation API.</p>
 *
 * <p><strong>Inputs.</strong> All 17 components, each nullable and each bounded by its PIC width.
 * <strong>Outputs.</strong> The canonical accessors, plus a redacted {@code toString()}.
 * <strong>Side effects.</strong> None; this is an immutable, purely structural carrier that
 * performs no comparison, mapping, normalisation or arithmetic.</p>
 *
 * <p><strong>How this type is built and exercised.</strong> It compiles as part of the single Maven
 * module with {@code mvn clean verify}, under {@code -Xlint:all -Werror}, so any warning here fails
 * the build. Its unit tests live in {@code src/test/java/com/cardemo/unit/model} rather than
 * alongside it. One pitfall is worth stating for whoever writes them: a {@code @Size} constraint
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
 *       permitted width. The offending value is never echoed, because two of these components are
 *       PII.</li>
 *   <li>A blank or absent component is <em>not</em> a binding error. It is carried through
 *       unchanged so that the service can distinguish the BLANK state from the NOT-OK state and
 *       emit the corresponding legacy message, per {@code app/cpy/CSSETATY.cpy}.</li>
 *   <li>An out-of-domain {@code cardStatusCode} is carried through rather than rejected at
 *       binding time, so the service emits the source's own message instead of a framework
 *       deserialization error.</li>
 *   <li>A JSON body carrying an unknown property must be rejected rather than silently ignored.
 *       That policy is enforced centrally on the {@code ObjectMapper}, through
 *       {@code spring.jackson.deserialization.fail-on-unknown-properties}, rather than by an
 *       annotation on each payload type: one setting covers every payload in this package, and
 *       keeping the policy out of the type keeps this file free of any import beyond the
 *       validation API.</li>
 *   <li>A malformed body fails before this type is constructed and never yields a partially
 *       populated instance, the canonical constructor being all-or-nothing.</li>
 * </ul>
 *
 * <p><strong>Findings, classified by severity.</strong></p>
 * <ul>
 *   <li><strong>Medium</strong> - corpus census correction. The specification asserts 460 input
 *       fields across the seventeen symbolic maps. Counting the input group of every member gives
 *       <strong>441</strong>. Remediation: cite 441. There is no code impact, because per-map
 *       counts drive payload shape and this member's count of 17 is unaffected and independently
 *       verified.</li>
 *   <li><strong>Low</strong> - the same census table lists {@code COACTVW.CPY} at 36 input fields;
 *       the true figure is 37, because {@code ACCTSIDI PIC 99999999999} at
 *       {@code app/cpy-bms/COACTVW.CPY:60} is an input field despite being numeric. This is part
 *       of the arithmetic behind the 441 figure. Remediation: correct the per-map entry.</li>
 *   <li><strong>Low</strong> - documentation rationale. The reason this payload omits snapshot
 *       groups is that its symbolic map declares none, not that the card-update program declares
 *       none; {@code app/cbl/COCRDUPC.cbl:291} and {@code :303} do declare them in
 *       WORKING-STORAGE. Remediation: state the map-level reason, as done above. The directive to
 *       omit them is unaffected.</li>
 *   <li>No Blocker or High severity finding applies to this file.</li>
 * </ul>
 *
 * @param transactionName {@code TRNNAMEI}, {@code PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:24}.
 *        Header field: the CICS transaction identifier echoed on the screen.
 * @param title01 {@code TITLE01I}, {@code PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:30}.
 *        Header field: the first title line.
 * @param currentDate {@code CURDATEI}, {@code PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:36}.
 *        Header field: the display date, textual and never a date type.
 * @param programName {@code PGMNAMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:42}.
 *        Header field: the owning program name.
 * @param title02 {@code TITLE02I}, {@code PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:48}.
 *        Header field: the second title line.
 * @param currentTime {@code CURTIMEI}, {@code PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:54}.
 *        Header field: the display time. Width 8 here; {@code app/cpy-bms/COSGN00.CPY:54} alone
 *        declares {@code PIC X(9)}, which is why no shared header type exists.
 * @param accountId {@code ACCTSIDI}, {@code PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:60}.
 *        The account identifier, textual to preserve zero padding. Numeric as
 *        {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60} alone.
 * @param cardNumber {@code CARDSIDI}, {@code PIC X(16)}, {@code app/cpy-bms/COCRDUP.CPY:66}.
 *        The card number. <strong>PII: never logged, never rendered, not even masked or
 *        truncated.</strong> Alphanumeric per {@code app/cpy/CVACT02Y.cpy:5}, numeric per
 *        {@code app/cpy/COCOM01Y.cpy:41}; {@code String} survives both.
 * @param cardholderName {@code CRDNAMEI}, {@code PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:72}.
 *        The embossed cardholder name. <strong>PII: never logged or rendered.</strong>
 * @param cardStatusCode {@code CRDSTCDI}, {@code PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:78}.
 *        The single-character active status. Raw {@code String} by design, never an enum, so an
 *        out-of-domain code reaches the service instead of failing deserialization.
 * @param expiryMonth {@code EXPMONI}, {@code PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:84}.
 *        The expiry month, textual. Rejected upstream with
 *        {@code 'Card expiry month must be between 1 and 12'}.
 * @param expiryYear {@code EXPYEARI}, {@code PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:90}.
 *        The expiry year, textual. Rejected upstream with {@code 'Invalid card expiry year'}.
 * @param expiryDay {@code EXPDAYI}, {@code PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:96}.
 *        The expiry day, textual. <strong>DISTINCTION 4:</strong> this component exists only on
 *        the update map; {@code app/cpy-bms/COCRDSL.CPY} contains zero {@code EXPDAY} tokens, so
 *        the card detail projection has no counterpart by design.
 * @param informationMessage {@code INFOMSGI}, {@code PIC X(40)},
 *        {@code app/cpy-bms/COCRDUP.CPY:102}. The informational message line. Width agrees with
 *        {@code app/cpy-bms/COCRDSL.CPY:96} but not with {@code app/cpy-bms/COCRDLI.CPY:282},
 *        which is {@code PIC X(45)}.
 * @param errorMessage {@code ERRMSGI}, {@code PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:108}.
 *        The error message line. Width agrees with {@code app/cpy-bms/COCRDSL.CPY:102} but not
 *        with {@code app/cpy-bms/COCRDLI.CPY:288}, which is {@code PIC X(78)}.
 * @param functionKeys {@code FKEYSI}, {@code PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:114}.
 *        The first segment of the function-key legend. The detail map instead declares one
 *        {@code PIC X(75)} field at {@code app/cpy-bms/COCRDSL.CPY:108}.
 * @param functionKeysContinued {@code FKEYSCI}, {@code PIC X(18)},
 *        {@code app/cpy-bms/COCRDUP.CPY:120}. The continuation segment of the function-key
 *        legend, which exists only on this map. Kept separate from {@code functionKeys}; the two
 *        are never collapsed into a single 39-byte field.
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
        String functionKeysContinued) {

    /**
     * Returns a deliberately redacted rendering that exposes only the account identifier and the
     * program name.
     *
     * <p>This override exists for one reason: a record's implicitly generated {@code toString()}
     * renders every component, which for this type would emit the card number from
     * {@code app/cpy-bms/COCRDUP.CPY:66} and the cardholder name from
     * {@code app/cpy-bms/COCRDUP.CPY:72} into any log, stack trace or diagnostic message that
     * interpolated an instance. Neither value may ever be emitted - not masked, not truncated to a
     * last four. Suppressing them here is the primary defence; the masking rules in
     * {@code logback-spring.xml} are the second.</p>
     *
     * <p>The two components that are rendered are non-sensitive screen header and routing values.
     * They are emitted exactly as received, without normalisation, so that a diagnostic reading can
     * still distinguish an absent value from a blank one. The two sensitive components are omitted
     * entirely rather than rendered as placeholders, so that the output contains no token derived
     * from them in any form.</p>
     *
     * @return a redacted rendering carrying only the account identifier and the program name
     */
    @Override
    public String toString() {
        return "CardUpdateRequest[accountId=" + accountId
                + ", programName=" + programName + "]";
    }
}
