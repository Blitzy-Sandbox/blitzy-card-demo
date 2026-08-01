/*
 * ******************************************************************
 * Program     : BillPaymentRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Bill-payment request; no amount field - the payment is
 *               always the full balance.
 * Source      : app/cpy-bms/COBIL00.CPY (10 fields) @ 7756d89
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
 * Inbound request payload for the online bill-payment conversation, replacing the BMS screen
 * exchange of CICS transaction {@code CB00} and its program {@code app/cbl/COBIL00C.cbl}.
 *
 * <p><strong>Purpose.</strong> This type transports the ten screen fields of the bill-payment map
 * from an HTTP client to {@code BillPaymentService}. It is a pure data holder: it performs no
 * arithmetic, no parsing, no formatting, no lookup and no mapping. Those are service concerns, and
 * keeping them out of here is what preserves the separation of concerns the legacy screen program
 * blurred by holding presentation, validation and posting logic in one 572-line member.</p>
 *
 * <p><strong>Field contract.</strong> The field set is taken verbatim from the generated symbolic
 * map {@code app/cpy-bms/COBIL00.CPY}, which declares <strong>exactly 10 input fields</strong> -
 * a verified census of its {@code 02 ...I PIC} declarations, at lines 24, 30, 36, 42, 48, 54, 60,
 * 66, 72 and 78. That makes this the smallest data transfer object in the package: four business
 * fields beyond the six recurring header fields. Names, Java types and declared widths below are
 * derived from that map and from nothing else, and they are listed in source order.</p>
 *
 * <p><strong>THERE IS NO AMOUNT FIELD, AND THERE MUST NEVER BE ONE.</strong> The map declares no
 * payment-amount field of any kind: after the header sextet it declares only the account
 * identifier, the current balance, a confirmation flag and an error message. There is nowhere for
 * a caller to state how much to pay, because the legacy screen never accepted such a value. The
 * program's behaviour, verified line by line, is:</p>
 * <ul>
 *   <li>{@code app/cbl/COBIL00C.cbl:193} captures the account's current balance from the account
 *       record into working storage, and line 194 moves that value out to the screen field - the
 *       balance travels from the store to the screen, never the other way.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:198} rejects the request when the balance is at or below zero,
 *       with the literal message {@code You have nothing to pay...} and the cursor repositioned to
 *       the account-identifier field.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:224} moves the <em>entire</em> current balance into the
 *       transaction amount. <strong>The payment is always the full balance, never partial.</strong>
 *       </li>
 *   <li>{@code app/cbl/COBIL00C.cbl:234} subtracts that amount from the balance, driving the
 *       balance to exactly zero.</li>
 * </ul>
 * <p>Adding an amount field - optional, nullable, "for future flexibility" or to enable partial
 * payments - would invent capability the system of record does not have, break behavioural parity
 * at lines 224 and 234, and widen the request surface beyond what the screen exposed. Any such
 * change is a <strong>Blocker</strong>. No partial-payment path may be introduced here or
 * downstream.</p>
 *
 * <p><strong>The submitted balance is display text, not an authoritative value.</strong> Because
 * line 193 reads the balance from the account record and line 194 merely echoes it to the screen,
 * {@code currentBalance} is an output field that happens to round-trip. This is an inbound payload
 * and therefore the trust boundary, so the value a caller sends is untrusted input.
 * {@code BillPaymentService} <strong>must re-read the authoritative balance from the account</strong>
 * and must never compute a payment, or evaluate the at-or-below-zero rejection, from the submitted
 * {@code currentBalance}. Doing so would let a caller choose its own balance; that defect is
 * classified <strong>High</strong>.</p>
 *
 * <p><strong>Distinction: the balance field differs from the account maps in name and in width.</strong>
 * {@code app/cpy-bms/COBIL00.CPY:66} declares {@code CURBALI PIC X(14)}, while the conceptually
 * similar field on the two account maps is {@code ACURBALI PIC X(15)} at
 * {@code app/cpy-bms/COACTUP.CPY:138} and {@code app/cpy-bms/COACTVW.CPY:102}. The name differs and
 * the width differs. The width used here is therefore <strong>14, not 15</strong>, and it is not
 * widened for the sake of consistency. This is also precisely why no shared balance helper, base
 * class, interface or mixin exists for balance fields anywhere in this package: a shared
 * abstraction would be factually wrong rather than merely redundant, because there is no single
 * balance contract to share.</p>
 *
 * <p><strong>The six header fields are declared inline for the same reason.</strong>
 * {@code TRNNAME X(4)}, {@code TITLE01 X(40)}, {@code CURDATE X(8)}, {@code PGMNAME X(8)},
 * {@code TITLE02 X(40)} and {@code CURTIME} recur on all seventeen symbolic maps, but they are not
 * uniform: {@code CURTIMEI} is {@code X(8)} here at line 54 and on fifteen other maps, yet
 * {@code app/cpy-bms/COSGN00.CPY:54} alone declares {@code X(9)}. A shared header type would have to
 * pick one width and would misstate the other, so the sextet is repeated inline on every map's data
 * transfer object by deliberate decision.</p>
 *
 * <p><strong>The confirmation gate has four states, so it is not a boolean.</strong>
 * {@code app/cbl/COBIL00C.cbl:173} evaluates the one-character confirmation field into four
 * distinct outcomes, each with its own message and cursor behaviour: {@code Y} or {@code y}
 * proceeds with the payment; {@code N} or {@code n} clears the screen and abandons it; spaces or
 * low-values is the first-phase prompt, which re-displays the screen asking for confirmation; and
 * anything else is invalid, producing the literal {@code Invalid value. Valid values are (Y/N)...}
 * with the cursor repositioned to the confirmation field. That is a two-phase confirmation gate,
 * not a yes-or-no answer. The field is consequently carried as a raw one-character
 * {@code String}: a {@code boolean} would collapse four states into two, and a {@code Boolean}
 * would collapse them into three while silently mapping an invalid character onto {@code null}.
 * The exact character submitted must survive transport unmodified - no case folding, no trimming,
 * no coercion - because the invalid branch is defined in terms of the value that was actually
 * sent.</p>
 *
 * <p><strong>Transaction identifier generation is a service concern and is documented, not
 * implemented, here.</strong> The map declares no identifier field, so neither does this type.
 * {@code app/cbl/COBIL00C.cbl:212-219} generates the payment transaction's identifier by the
 * descending-browse maximum-key idiom: move high values into the key, start a browse, read the
 * <em>previous</em> record, end the browse, then add one to the identifier retrieved. The
 * empty-file case is explicit at {@code app/cbl/COBIL00C.cbl:472-496}: an end-of-file response
 * moves zeros into the key, so <strong>the first generated identifier is 1</strong>; any other
 * response produces the literal {@code Unable to lookup Transaction...} and repositions the cursor.
 * The idiom is inherently racy under concurrency, exactly as the browse was, and that race is
 * retained deliberately: substituting a database sequence would change the generated values and
 * break byte-exact comparison against the legacy baseline, so a collision is instead surfaced by
 * the primary-key constraint as a duplicate-record failure.</p>
 *
 * <p><strong>Money contract.</strong> {@code currentBalance} is carried as its {@code X(14)}
 * display text, a {@code String}, exactly as the map declares it; this type never holds a numeric
 * balance. The underlying value is {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy:7}, which is the <strong>account</strong> money precision and maps to
 * {@code NUMERIC(12,2)}. Wherever an arithmetic value is exposed in this system it is a
 * {@code BigDecimal} at scale 2 - precision 12 for this balance - and there is
 * <strong>no {@code float}, {@code double}, {@code Float} or {@code Double} anywhere</strong>, which
 * the security gate asserts. Comparison uses {@code compareTo}, never {@code equals}, and rounding
 * is {@code RoundingMode.HALF_EVEN}. In particular the at-or-below-zero test of line 198 is a sign
 * comparison and must be written as {@code compareTo(BigDecimal.ZERO)} yielding zero or less;
 * {@code equals(BigDecimal.ZERO)} would answer false for {@code 0.00} against {@code 0} and let a
 * settled account be paid again. Note also the precision the service must switch to: transaction
 * money, {@code TRAN-AMT PIC S9(09)V99}, is {@code NUMERIC(11,2)}, so the payment transaction built
 * at line 224 uses the transaction precision even though the balance it is derived from uses the
 * account precision. Any parse or format performed by the service must pass
 * {@code Locale.ROOT}, never the platform default, which in some locales would emit a comma decimal
 * separator and silently break the baseline. This type performs neither: it transports, and the
 * service converts.</p>
 *
 * <p><strong>The account identifier is text, never a number.</strong> {@code ACTIDINI} is
 * {@code X(11)} at {@code app/cpy-bms/COBIL00.CPY:60} and is carried as an eleven-character
 * {@code String}. The seeded identifiers are zero-padded - the first account record in
 * {@code app/data/ASCII/acctdata.txt} is {@code 00000000001} - so a numeric type would strip the
 * padding and break byte-exact baseline comparison. A related divergence in the corpus is recorded
 * for completeness: the account-identifier field named {@code ACCTSIDI} is alphanumeric
 * {@code X(11)} on {@code COACTUP.CPY:60}, {@code COCRDUP.CPY:60}, {@code COCRDSL.CPY:60} and
 * {@code COCRDLI.CPY:66}, but is numeric {@code PIC 99999999999} at
 * {@code app/cpy-bms/COACTVW.CPY:60} - the sole numeric declaration of it in the corpus. The
 * divergence is cited, not unified. It does not apply to this map directly, because
 * {@code COBIL00.CPY} declares no {@code ACCTSIDI} at all; its account identifier is
 * {@code ACTIDINI}. That correction is classified <strong>Low</strong>.</p>
 *
 * <p><strong>Absent, blank and marked are three distinct states.</strong> The legacy field-error
 * model is not two-valued. {@code app/cpy/CSSETATY.cpy} is a parameterised
 * {@code COPY ... REPLACING} template copied into the procedure division, with parameters
 * {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)}, and its body at lines 18 to 27
 * is:</p>
 * <pre>{@code
 * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 * }</pre>
 * <p>The model is therefore OK, NOT-OK and BLANK, where BLANK is a state distinct from NOT-OK and
 * additionally stamps an asterisk into the screen field, and where the markers fire only on
 * re-entry, never on first display. Being procedural, that copybook has no class of its own; it
 * maps onto bean validation plus per-field error markers, and this type is the half that carries
 * the values. The consequence is binding here: a missing field, an empty field and a field holding
 * a marker are three different things, so {@code null} is never coerced to the empty string, the
 * empty string is never coerced to {@code null}, and neither is ever coerced to a sentinel or to
 * zero. <strong>Coercing a blank balance to a numeric zero would be a defect with business
 * consequences</strong>, because a zero balance triggers the rejection path of line 198: a field
 * the caller simply left empty would become the answer "you have nothing to pay". The corpus
 * corroborates the distinction at the message level too - {@code app/cbl/COACTUPC.cbl:505-508}
 * declares two different literals for the same field, one for blank and one for invalid.</p>
 *
 * <p><strong>Validation matches the source: no stricter, no looser.</strong> Each component carries
 * a {@code jakarta.validation} size constraint whose maximum is exactly the width the map declares,
 * and nothing else:</p>
 * <ul>
 *   <li>No {@code NotNull} and no {@code NotBlank} anywhere, because the source tolerates spaces
 *       and low-values on every one of these fields - blank confirmation is a legitimate state at
 *       line 173, and blank account identifier is explicitly tolerated by the guard at line 199.
 *       </li>
 *   <li>No positive, minimum or decimal-minimum constraint on the balance. The at-or-below-zero
 *       rejection is a business rule evaluated against the re-read account balance at line 198, not
 *       a constraint on submitted input. Declaring it here would reject the request before the
 *       service ever read the authoritative balance, and would emit a framework message in place of
 *       the source's own literal.</li>
 *   <li>No digits-only pattern on the account identifier. The source converts identifiers with the
 *       plain numeric intrinsic and reports its own message on failure, so the request must not
 *       pre-empt that decision. The package's two-parser contract applies: a strict digits-only
 *       parser for identifiers and card numbers, a currency-tolerant parser for amounts. Using one
 *       for both would accept input the legacy rejects, or reject input it accepts.</li>
 *   <li>No normalisation on ingest - no trimming, no case folding - so the three-state model and
 *       the exact confirmation character survive transport.</li>
 * </ul>
 *
 * <p><strong>Configuration this type depends on.</strong> Unknown JSON properties must be rejected
 * rather than silently ignored, so that a payload carrying, say, an invented amount property fails
 * loudly instead of being quietly dropped. That cannot be expressed on this type: Jackson only
 * fails on an unknown property when the mapper has {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled, and
 * an {@code ignoreUnknown = false} annotation does not re-enable it once the mapper has it off,
 * which is Spring Boot's default. The rejection is therefore configured centrally, by
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} set true in every profile and
 * by the web configuration that builds the mapper. This type deliberately adds no serialisation
 * annotation and no dependency of its own.</p>
 *
 * <p><strong>Error modes.</strong> A component longer than its declared width raises a bean
 * validation constraint violation, surfaced by the controller advice as a 400-class response; the
 * message names the field and never quotes the submitted value, so no personally identifiable or
 * financial value reaches a log or an error body. A balance at or below zero, a missing account, an
 * absent card cross-reference and a confirmation character outside the four accepted states are all
 * <em>business</em> outcomes decided by {@code BillPaymentService} against re-read state, each
 * reproducing the legacy literal message, and none of them is a validation failure of this type. A
 * duplicate generated transaction identifier surfaces as a duplicate-record failure from the
 * primary-key constraint, which is the deliberate consequence of retaining the legacy identifier
 * race. This type itself throws nothing: it has no constructor logic, no argument coercion and no
 * behaviour that can fail.</p>
 *
 * <p><strong>What this type carries, and what it must not be logged as.</strong> This is the
 * lightest payload in the package for sensitive data: it holds no password, no password hash, no
 * token, no signing key, no card number, no customer name, no social security number, no date of
 * birth, no telephone number, no government identifier and no electronic funds account identifier.
 * No explicit {@code toString} is declared; the implicit one a record provides renders the
 * component values, which is why callers should log the individual fields they need rather than the
 * whole object - the balance is financial data even though it is not personally identifying. The
 * type is deliberately not serialisable, so no Java deserialisation path can reach it.</p>
 *
 * <p><strong>Build, run and test.</strong> The whole tree builds with {@code ./mvnw -B clean verify}
 * on JDK 25 and Maven 3.9.11; compilation runs with {@code -Xlint:all -Werror}, so any warning is a
 * build failure. The unit tests that pin this contract live in
 * {@code src/test/java/com/cardemo/unit/model}, not beside this file, and they assert by reflection
 * that the component count is exactly ten, that no component name contains "amount", "amt" or
 * "payment" in any case, that the balance size maximum is 14 rather than 15, that the confirmation
 * component is declared as {@code String} and carries {@code null}, empty, {@code Y}, {@code N} and
 * an invalid character as five distinguishable values, that the account identifier round-trips
 * {@code 00000000001} with its leading zeros intact, that no positive or minimum constraint exists
 * on the balance, that a blank balance stays distinguishable from {@code 0} and {@code 0.00}, and
 * that no component is a floating-point type.</p>
 *
 * <p><strong>Findings recorded for this file, classified by severity.</strong></p>
 * <ul>
 *   <li><strong>Blocker</strong> - introducing any payment-amount component, or any partial-payment
 *       path derived from one. Remediation: do not add it; the full-balance semantics of lines 224
 *       and 234 are the contract.</li>
 *   <li><strong>High</strong> - computing a payment, or evaluating the at-or-below-zero rejection,
 *       from the submitted {@code currentBalance} instead of the re-read account balance.
 *       Remediation: the service reads the account record first, exactly as line 193 does, and uses
 *       only that value.</li>
 *   <li><strong>Medium</strong> - a corpus census correction. A direct count of the
 *       {@code 02 ...I PIC} declarations across all seventeen symbolic maps in
 *       {@code app/cpy-bms} totals <strong>441</strong> input fields, of which this map contributes
 *       10, and not the 460 asserted elsewhere; the per-map table that accompanies that figure
 *       itself sums to 440 because it counts {@code COACTVW.CPY} as 36 while the file declares 37,
 *       the extra one being the numeric {@code ACCTSIDI} at line 60. Documentation only, no code
 *       impact. Remediation: cite 441.</li>
 *   <li><strong>Low</strong> - {@code ACCTSIDI} does not appear on this map, so the alphanumeric
 *       against numeric divergence recorded above concerns the four maps that declare it plus
 *       {@code COACTVW.CPY:60}, and not this one, whose account identifier is {@code ACTIDINI} at
 *       line 60. Remediation: the wording is corrected above.</li>
 * </ul>
 *
 * <p><strong>Not available in the source, and deliberately not invented.</strong> There is no
 * payment-amount input, no partial-payment capability and no client-supplied transaction identifier
 * anywhere in {@code app/cpy-bms/COBIL00.CPY} - the verified census of 10 fields is exhaustive.
 * Introducing any of them would require changing that map, which is frozen. There is likewise no
 * service-level objective for this operation anywhere in the corpus, so no latency or throughput
 * threshold is asserted here; what the validation gates record is a measured baseline, not a
 * target.</p>
 *
 * @param transactionName {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COBIL00.CPY:24}. Screen
 *                        header field carrying the four-character CICS transaction identifier,
 *                        {@code CB00} for this conversation. Echoed, never authoritative: routing is
 *                        by URL and identity is by token claim.
 * @param title01         {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:30}. First
 *                        screen title line, a presentation constant on the legacy map.
 * @param currentDate     {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:36}. Screen
 *                        header date as the terminal displayed it, eight characters. It is display
 *                        text, not a temporal type, and the server never trusts it as the
 *                        processing date.
 * @param programName     {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:42}. Screen
 *                        header field carrying the owning program name, {@code COBIL00C} on the
 *                        legacy map.
 * @param title02         {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:48}. Second
 *                        screen title line, a presentation constant on the legacy map.
 * @param currentTime     {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:54}. Screen
 *                        header time as the terminal displayed it. Eight characters here, which is
 *                        why the header sextet is declared inline: {@code COSGN00.CPY:54} declares
 *                        the same field as nine.
 * @param accountId       {@code ACTIDINI PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:60}. The
 *                        account to be paid, eleven characters of zero-padded text, never a numeric
 *                        type. Blank is tolerated, exactly as the guard at
 *                        {@code app/cbl/COBIL00C.cbl:199} tolerates spaces and low-values.
 * @param currentBalance  {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66}. The
 *                        balance as last displayed, fourteen characters of display text - not
 *                        fifteen, which is the unrelated {@code ACURBALI} field on the account maps.
 *                        Untrusted echo only: the service re-reads the authoritative balance from
 *                        the account record, as {@code app/cbl/COBIL00C.cbl:193} does, and pays that
 *                        balance in full. A blank value is not a zero value.
 * @param confirmation    {@code CONFIRMI PIC X(1)} at {@code app/cpy-bms/COBIL00.CPY:72}. The
 *                        two-phase confirmation gate of {@code app/cbl/COBIL00C.cbl:173}, carried
 *                        raw and unmodified as one character because it has four states - blank,
 *                        affirmative, negative and invalid - and is therefore not a boolean.
 * @param errorMessage    {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COBIL00.CPY:78}. The
 *                        screen's error line, seventy-eight characters. Present because the map
 *                        declares it; the service composes outbound messages itself and never
 *                        echoes a value submitted here back to a caller.
 */
public record BillPaymentRequest(
        @Size(max = 4) String transactionName,     // TRNNAMEI PIC X(4)   at COBIL00.CPY:24
        @Size(max = 40) String title01,            // TITLE01I PIC X(40)  at COBIL00.CPY:30
        @Size(max = 8) String currentDate,         // CURDATEI PIC X(8)   at COBIL00.CPY:36
        @Size(max = 8) String programName,         // PGMNAMEI PIC X(8)   at COBIL00.CPY:42
        @Size(max = 40) String title02,            // TITLE02I PIC X(40)  at COBIL00.CPY:48
        @Size(max = 8) String currentTime,         // CURTIMEI PIC X(8)   at COBIL00.CPY:54
        @Size(max = 11) String accountId,          // ACTIDINI PIC X(11)  at COBIL00.CPY:60
        @Size(max = 14) String currentBalance,     // CURBALI  PIC X(14)  at COBIL00.CPY:66 - display only
        @Size(max = 1) String confirmation,        // CONFIRMI PIC X(1)   at COBIL00.CPY:72 - four states
        @Size(max = 78) String errorMessage) {     // ERRMSGI  PIC X(78)  at COBIL00.CPY:78
}

