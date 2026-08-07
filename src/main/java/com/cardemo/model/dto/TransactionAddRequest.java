/*
 ******************************************************************
 * Program     : TransactionAddRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Transaction-add request; identifiers use the strict parser, amounts the currency-tolerant one.
 * Source      : app/cpy-bms/COTRN02.CPY (21 fields) @ 7756d89
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

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.Size;

/**
 * Inbound request payload for the transaction-add screen, CICS transaction {@code CT02}, whose handler is
 * {@code app/cbl/COTRN02C.cbl}. The 21 components below are transcribed from the generated symbolic map
 * {@code app/cpy-bms/COTRN02.CPY} in its own declaration order, each citing its COBOL field, PIC clause and
 * line.
 *
 * <p>Every value arrives as text, including the amount and the two dates, because the source receives them as
 * screen characters and because the two numeric conversions it applies are <strong>not
 * interchangeable</strong>: the account identifier and card number go through the strict conversion at
 * {@code app/cbl/COTRN02C.cbl:204} and :218, which accepts digits only, while the amount goes through the
 * currency-tolerant conversion at :383 and :456, which additionally admits a currency symbol and thousands
 * separators. Binding either field to a numeric type here would collapse that distinction and would accept or
 * reject input the legacy screen did not.
 *
 * <p>The accepted amount is echoed back on the {@value #AMOUNT_DISPLAY_MASK} edited mask declared at
 * {@code app/cbl/COTRN02C.cbl:59}, which is one integer digit narrower than the underlying
 * {@code PIC S9(9)V99} field at :58 - hence the two distinct ceilings {@link #AMOUNT_MASK_MAX} and
 * {@link #AMOUNT_VALUE_MAX}. This type performs neither parse: it holds the presented characters verbatim,
 * applies no trimming, case folding or padding, and leaves both conversions, the two-phase confirmation gate
 * and identifier generation to {@code TransactionAddService}.
 *
 * <p>This type is a pure transport record. It parses nothing, formats nothing, computes nothing,
 * generates no identifier and maps to no entity. Every one of the 21 components is carried as
 * {@code String}, byte-for-byte as supplied, because the legacy program applies a
 * <em>different</em> conversion to different fields on the same screen and a Java type chosen at the
 * binding layer would silently collapse that distinction. See the two-parser section below.
 *
 * <h2>Field map, in exact source order</h2>
 *
 * <table>
 * <caption>The 21 input fields of app/cpy-bms/COTRN02.CPY and their record components</caption>
 * <tr><th scope="col">#</th><th scope="col">BMS field</th><th scope="col">PIC</th>
 *     <th scope="col">Line</th><th scope="col">Component</th></tr>
 * <tr><td>1</td><td>TRNNAMEI</td><td>X(4)</td><td>L24</td><td>{@code transactionName}</td></tr>
 * <tr><td>2</td><td>TITLE01I</td><td>X(40)</td><td>L30</td><td>{@code title01}</td></tr>
 * <tr><td>3</td><td>CURDATEI</td><td>X(8)</td><td>L36</td><td>{@code currentDate}</td></tr>
 * <tr><td>4</td><td>PGMNAMEI</td><td>X(8)</td><td>L42</td><td>{@code programName}</td></tr>
 * <tr><td>5</td><td>TITLE02I</td><td>X(40)</td><td>L48</td><td>{@code title02}</td></tr>
 * <tr><td>6</td><td>CURTIMEI</td><td>X(8)</td><td>L54</td><td>{@code currentTime}</td></tr>
 * <tr><td>7</td><td>ACTIDINI</td><td>X(11)</td><td>L60</td><td>{@code accountId}</td></tr>
 * <tr><td>8</td><td>CARDNINI</td><td>X(16)</td><td>L66</td><td>{@code cardNumber}</td></tr>
 * <tr><td>9</td><td>TTYPCDI</td><td>X(2)</td><td>L72</td><td>{@code typeCode}</td></tr>
 * <tr><td>10</td><td>TCATCDI</td><td>X(4)</td><td>L78</td><td>{@code categoryCode}</td></tr>
 * <tr><td>11</td><td>TRNSRCI</td><td>X(10)</td><td>L84</td><td>{@code source}</td></tr>
 * <tr><td>12</td><td>TDESCI</td><td>X(60)</td><td>L90</td><td>{@code description}</td></tr>
 * <tr><td>13</td><td>TRNAMTI</td><td>X(12)</td><td>L96</td><td>{@code amount}</td></tr>
 * <tr><td>14</td><td>TORIGDTI</td><td>X(10)</td><td>L102</td><td>{@code originatingDate}</td></tr>
 * <tr><td>15</td><td>TPROCDTI</td><td>X(10)</td><td>L108</td><td>{@code processingDate}</td></tr>
 * <tr><td>16</td><td>MIDI</td><td>X(9)</td><td>L114</td><td>{@code merchantId}</td></tr>
 * <tr><td>17</td><td>MNAMEI</td><td>X(30)</td><td>L120</td><td>{@code merchantName}</td></tr>
 * <tr><td>18</td><td>MCITYI</td><td>X(25)</td><td>L126</td><td>{@code merchantCity}</td></tr>
 * <tr><td>19</td><td>MZIPI</td><td>X(10)</td><td>L132</td><td>{@code merchantZip}</td></tr>
 * <tr><td>20</td><td>CONFIRMI</td><td>X(1)</td><td>L138</td><td>{@code confirmation}</td></tr>
 * <tr><td>21</td><td>ERRMSGI</td><td>X(78)</td><td>L144</td><td>{@code errorMessage}</td></tr>
 * </table>
 *
 * <p>The names are those of <em>this</em> map. The transaction-detail map
 * {@code app/cpy-bms/COTRN01.CPY} spells the corresponding fields {@code TRNIDINI}, {@code TRNIDI}
 * and {@code CARDNUMI}; those names are deliberately <em>not</em> borrowed here, because the
 * transaction-add map declares {@code ACTIDINI} and {@code CARDNINI} and the field contract is taken
 * from the map that the program actually receives.
 *
 * <h2>The two-parser asymmetry (the contract this type exists to preserve)</h2>
 *
 * <p>{@code app/cbl/COTRN02C.cbl} uses <strong>two different numeric intrinsics on one screen</strong>,
 * chosen per field. This is deliberate behaviour, not an inconsistency to tidy away.
 *
 * <ul>
 * <li><strong>Plain {@code FUNCTION NUMVAL} for identifiers only</strong> - digits, no currency
 *     tolerance. {@code app/cbl/COTRN02C.cbl:204-205} computes
 *     {@code WS-ACCT-ID-N = FUNCTION NUMVAL(ACTIDINI OF COTRN2AI)} and L206 moves the result to
 *     {@code XREF-ACCT-ID}; {@code app/cbl/COTRN02C.cbl:218-219} computes
 *     {@code WS-CARD-NUM-N = FUNCTION NUMVAL(CARDNINI OF COTRN2AI)} and L220 moves the result to
 *     {@code XREF-CARD-NUM}. Both are gated by a preceding {@code IS NOT NUMERIC} test, at L197 and
 *     L211 respectively.</li>
 * <li><strong>{@code FUNCTION NUMVAL-C} for amounts only</strong> - tolerates a currency symbol and
 *     thousands separators. {@code app/cbl/COTRN02C.cbl:383-384} and
 *     {@code app/cbl/COTRN02C.cbl:456-457} both compute
 *     {@code WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI OF COTRN2AI)}.</li>
 * </ul>
 *
 * <p>The supporting working-storage declarations, verified at {@code app/cbl/COTRN02C.cbl:56-60}:
 *
 * <pre>{@code
 * 05  WS-CARD-NUM-N     PIC 9(16)         VALUE 0.               <- L56
 * 05  WS-TRAN-ID-N      PIC 9(16)         VALUE ZEROS.           <- L57
 * 05  WS-TRAN-AMT-N     PIC S9(9)V99      VALUE ZERO.            <- L58
 * 05  WS-TRAN-AMT-E     PIC +99999999.99  VALUE ZEROS.           <- L59
 * 05  WS-DATE-FORMAT    PIC X(10)         VALUE 'YYYY-MM-DD'.    <- L60
 * }</pre>
 *
 * <p><strong>The service must therefore implement two distinct parsers:</strong> a strict
 * digits-only parser for {@code accountId}, {@code cardNumber} and {@code merchantId}, and a
 * currency-tolerant parser for {@code amount}. <strong>Failure mode:</strong> using one parser for
 * both either accepts input the legacy program rejects, or rejects input it accepts. A strict parser
 * applied to {@code amount} rejects a currency-decorated value that {@code NUMVAL-C} accepts; a
 * tolerant parser applied to {@code accountId} accepts a decorated value that the L197
 * {@code IS NOT NUMERIC} guard rejects with the message
 * {@code 'Account ID must be Numeric...'}. Carrying text keeps that distinction expressible
 * downstream; a numeric component would destroy it at the binding layer, before the service ever
 * sees the value.
 *
 * <p>Two further consequences of carrying identifiers as text. Significant leading zeros survive:
 * the seeded account identifiers are zero-padded to the catalogued key length of 11, the first
 * account record being {@code 00000000001}, and a numeric component would render it as {@code 1} and
 * break byte-exact comparison against the legacy baseline. And no validation that pre-empts either
 * parser is declared here: there is no {@code @Digits} constraint and no digits-only
 * {@code @Pattern}, because the source performs no such check at the map boundary.
 *
 * <h2>Amount representation and the legacy edited mask</h2>
 *
 * <p>{@code amount} is the <strong>display text</strong> of BMS field {@code TRNAMTI PIC X(12)}
 * ({@code app/cpy-bms/COTRN02.CPY:96}) and is carried as {@code String}. It is never a numeric type
 * on the wire.
 *
 * <p>The persisted field behind it is {@code TRAN-AMT PIC S9(09)V99}
 * ({@code app/cpy/CVTRA05Y.cpy:10}), which becomes {@code NUMERIC(11,2)} - the
 * <strong>transaction</strong> money precision, distinct from the account money precision of
 * {@code NUMERIC(12,2)} behind {@code S9(10)V99}. Any arithmetic value the service derives is a
 * {@code BigDecimal} at scale {@value #AMOUNT_SCALE} and precision {@value #AMOUNT_PRECISION},
 * rounded {@code HALF_EVEN} and compared with {@code compareTo} rather than {@code equals}. There is
 * no {@code float}, {@code double}, {@code Float} or {@code Double} anywhere in this file.
 *
 * <p>The response echo is <strong>masked, not raw</strong>. At
 * {@code app/cbl/COTRN02C.cbl:385-386} the parsed value is moved into the edited field
 * {@code WS-TRAN-AMT-E PIC +99999999.99} and the edited field is moved straight back into
 * {@code TRNAMTI OF COTRN2AI}, so what the screen redisplays is the masked rendering. The mask
 * carries a mandatory sign, exactly eight integer digits and two decimals, while the numeric field
 * behind it admits nine integer digits - see {@link #AMOUNT_MASK_MAX} and
 * {@link #AMOUNT_VALUE_MAX}. That asymmetry is documented and preserved, never corrected.
 *
 * <p>The same masked rendering is what the read side carries: {@code TransactionDto}, derived from
 * {@code app/cpy-bms/COTRN01.CPY}, presents the amount on this legacy display mask rather than as a
 * raw numeric. Both directions therefore agree on {@link #AMOUNT_DISPLAY_MASK}, and neither this
 * record nor that one performs the formatting itself.
 *
 * <p>Formatting and parsing both belong to the service; this record only transports. Wherever the
 * service does parse or format, it must pass {@code Locale.ROOT}. The platform default locale emits
 * a comma decimal separator in many locales and would silently break byte-exact comparison against
 * the legacy baseline, which Rule 1 Clause C forbids as an environment-specific assumption.
 *
 * <h2>The transaction source is a String, never an enum</h2>
 *
 * <p>{@code app/cbl/COTRN02C.cbl:454} reads {@code MOVE TRNSRCI OF COTRN2AI TO TRAN-SOURCE}: the
 * screen field moves <strong>directly</strong> into the record field with no validation, no table
 * lookup and no domain check of any kind. L455 does the same for the description,
 * {@code MOVE TDESCI OF COTRN2AI TO TRAN-DESC}.
 *
 * <p>{@code source} is therefore a plain {@code String} of width 10 and is deliberately
 * <strong>not</strong> bound to {@code TransactionSource}. That enum carries exactly two constants -
 * {@code 'System'}, six characters, used by the interest job {@code app/cbl/CBACT04C.cbl}, and
 * {@code 'POS TERM'}, eight characters - both space-padded into {@code X(10)}. This path, however,
 * accepts any ten-character value. Binding to an enum would turn an out-of-domain value into a
 * Jackson deserialization failure, replacing the source's own behaviour with a framework error. That
 * is a behaviour change, and parity is the contract. The same reasoning applies to
 * {@code typeCode X(2)} and {@code categoryCode X(4)}: raw {@code String}, no enum, no lookup at the
 * DTO boundary. Consequently this file imports nothing from the model enum package.
 *
 * <h2>Dates and timestamps are text</h2>
 *
 * <p>{@code originatingDate} ({@code TORIGDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:102}) and
 * {@code processingDate} ({@code TPROCDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:108}) are
 * {@code String}. They are never {@code LocalDate}, {@code LocalDateTime}, {@code Instant},
 * {@code Date} or {@code Timestamp}. The validation format is a literal handed to a routine, not a
 * Java type: see {@link #DATE_VALIDATION_FORMAT}.
 *
 * <p>The persisted fields behind them are {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, both
 * {@code PIC X(26)} ({@code app/cpy/CVTRA05Y.cpy:16-17}), and both are carried package-wide as text.
 * Three independent facts force that choice, and a date or timestamp type would normalise every one
 * of them away:
 *
 * <ul>
 * <li>The generated timestamp's final four digits are always zeros - it is hundredths-of-a-second
 *     precision padded to 26 characters, neither nanosecond nor millisecond precision, the fractional
 *     field being {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:173}.</li>
 * <li>The batch expiry validation performs a <strong>string comparison</strong> on the first ten
 *     characters of the originating timestamp, not a temporal comparison.</li>
 * <li>The statement projection delivers a processing timestamp with only <strong>24</strong>
 *     significant characters in a 26-byte field, which is not a parseable timestamp at all.</li>
 * </ul>
 *
 * <p>Timestamps therefore pass through as text, exactly as the source stores them.
 *
 * <h2>The confirmation field: four states, not a boolean</h2>
 *
 * <p>{@code confirmation} ({@code CONFIRMI PIC X(1)}, {@code app/cpy-bms/COTRN02.CPY:138})
 * implements a two-phase confirmation gate and is modelled as a raw one-character {@code String}. It
 * is deliberately <strong>not</strong> a {@code boolean}: the source distinguishes blank, affirmative,
 * negative and invalid, each with its own screen message and its own cursor behaviour, and a
 * {@code boolean} collapses four states into two. Combined with an absent value, the component
 * carries five distinguishable inputs: {@code null}, the empty string, an affirmative character, a
 * negative character and anything else.
 *
 * <h2>Identifier generation happens in the service, not here</h2>
 *
 * <p>This record carries <strong>no</strong> generated identifier component, which is consistent with
 * the map: {@code app/cpy-bms/COTRN02.CPY} declares no transaction-identifier input field at all.
 *
 * <p>The idiom the service reproduces is at {@code app/cbl/COTRN02C.cbl:444-451}: move high values
 * into {@code TRAN-ID}, start a browse, read the <strong>previous</strong> record, end the browse,
 * move the retrieved identifier into {@code WS-TRAN-ID-N} and add one. On an empty file the retrieved
 * identifier is zeros, so <strong>the first generated identifier is 1</strong>. The algorithm is
 * inherently racy under concurrency, exactly as the browse was. The parity-preserving choice is to
 * keep it and let the primary-key constraint surface a collision, rather than substituting a database
 * sequence, which would change the generated values and break comparison against the legacy
 * baseline.
 *
 * <h2>The six recurring header fields are declared inline, on purpose</h2>
 *
 * <p>{@code transactionName}, {@code title01}, {@code currentDate}, {@code programName},
 * {@code title02} and {@code currentTime} recur on all seventeen symbolic maps, and they are still
 * declared inline here rather than hoisted into a shared base class, interface, helper or mixin. Such
 * an abstraction would be <em>factually wrong</em>, not merely redundant: {@code CURTIMEI} is
 * {@code PIC X(8)} on this map ({@code app/cpy-bms/COTRN02.CPY:54}) but
 * {@code PIC X(9)} on the sign-on map ({@code app/cpy-bms/COSGN00.CPY:54}), which is the sole
 * outlier in the corpus. A shared header type would have to pick one width and would misstate the
 * contract of every map that uses the other.
 *
 * <h2>Validation: the three-state model, matched to the source and no stricter</h2>
 *
 * <p>Each component carries exactly one {@code @Size(max = ...)} constraint whose maximum is the PIC
 * width from the field map above. Nothing stricter is invented: there is no {@code @Digits}, no
 * digits-only {@code @Pattern} on {@code amount}, no {@code @NotNull} where the source tolerates
 * blank, no enum binding and no cross-field date assertion the source does not perform
 * unconditionally.
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is a parameterised {@code COPY ... REPLACING} PROCEDURE DIVISION
 * template - parameters {@code (TESTVAR1)}, {@code (SCRNVAR2)}, {@code (MAPNAME3)} - whose verified
 * body is:
 *
 * <pre>{@code
 * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 * }</pre>
 *
 * <p>The model is <strong>OK / NOT-OK / BLANK</strong>. BLANK is a state distinct from NOT-OK and
 * additionally stamps an asterisk into the screen field, and the markers fire <strong>only on
 * re-entry</strong>, never on first display. Being procedural, that copybook gets no class of its
 * own; it maps onto bean validation plus per-field error markers.
 *
 * <p>The consequence for this record is precise: <strong>absent, blank and marked are three distinct
 * states.</strong> No component is normalised on ingest - there is no {@code trim}, no
 * {@code toUpperCase} and no {@code toLowerCase} anywhere in this file. {@code null} is never coerced
 * to the empty string, the empty string is never coerced to {@code null}, and neither is ever coerced
 * to a sentinel or to zero. A DTO that collapses absent, blank and invalid into one state diverges
 * from the source.
 *
 * <h2>Security posture</h2>
 *
 * <p>{@code cardNumber} is a primary account number and is treated as never-emit. It appears in no
 * rendering produced by this type: see {@link #toString()}, which is overridden precisely to suppress
 * the leak a record's generated {@code toString()} would otherwise produce. The merchant name, city
 * and postal code are not on the never-emit list but are likewise never echoed - every validation
 * message names its field and quotes no value.
 *
 * <p>This type carries no password, hash, token or signing key, and none may be added: the
 * transaction-add screen exposes no such field, and Rule 1 Clause D's least-privilege requirement
 * forbids widening the payload. It does not implement {@code Serializable}, because Clause D flags
 * insecure deserialization as a risky pattern and a Java-serializable request payload is an
 * unnecessary attack surface. It adds no dependency - no Lombok, nothing beyond the framework-managed
 * bean-validation API - declares no static mutable state, and contains no {@code Runtime.exec}, no
 * {@code ProcessBuilder} and no string-concatenated query of any kind.
 *
 * <h2>Inputs, outputs, side effects and error modes</h2>
 *
 * <ul>
 * <li><strong>Inputs.</strong> A JSON object whose 21 members are the components below. Every member
 *     is optional at the transport level, because the source tolerates a blank field and renders it
 *     with an error marker rather than refusing the request.</li>
 * <li><strong>Outputs.</strong> An immutable value: 21 accessors returning exactly the strings that
 *     were bound, plus the amount and date contract constants.</li>
 * <li><strong>Error handling posture.</strong> This file contains no {@code throw} and no
 *     {@code catch}, so no exception can be swallowed and no root cause can be lost. The canonical
 *     constructor deliberately performs <strong>no</strong> argument check: any such check would be
 *     stricter than the source, which tolerates a blank or absent field and truncates on a
 *     {@code MOVE} into a narrower field rather than refusing the value, and a check here would
 *     additionally pre-empt the two parsers described above. Every rejection is therefore raised by
 *     the layer that the source raises it in - the bean-validation layer for a width breach, the
 *     service for a parse or date failure - and each of those is enumerated below.</li>
 * <li><strong>Side effects.</strong> None. Construction performs no I/O, no logging, no parsing and
 *     no mutation of any shared state, and instances are safe to share across threads.</li>
 * <li><strong>Error mode - oversized component.</strong> A component longer than its PIC width fails
 *     its {@code @Size} constraint. The violation names the component and never quotes the value; the
 *     service maps it to the legacy per-field error marker.</li>
 * <li><strong>Error mode - non-numeric identifier.</strong> Not detected here. It is detected by the
 *     service's strict parser, reproducing {@code app/cbl/COTRN02C.cbl:197} and
 *     {@code app/cbl/COTRN02C.cbl:211}, whose messages are
 *     {@code 'Account ID must be Numeric...'} and {@code 'Card Number must be Numeric...'}.</li>
 * <li><strong>Error mode - malformed date.</strong> Not detected here. It is detected by the date
 *     validation service using {@link #DATE_VALIDATION_FORMAT}, reproducing
 *     {@code app/cbl/COTRN02C.cbl:389-395}.</li>
 * <li><strong>Error mode - unknown JSON member.</strong> This type declares no catch-all map and no
 *     any-setter, so an unknown member cannot be absorbed silently by the type itself. Rejecting it
 *     is a deserialization setting, not a type-level one: see the configuration note below.</li>
 * <li><strong>Error mode - duplicate identifier.</strong> Surfaced by the primary-key constraint when
 *     the retained generation race loses, as described above; it is never masked by an upsert.</li>
 * </ul>
 *
 * <h2>Build, test and configuration</h2>
 *
 * <ul>
 * <li><strong>Build.</strong> {@code ./mvnw -B clean compile}. The compiler runs with
 *     {@code -Xlint:all -Werror} and {@code failOnWarning}, so a deprecation or a raw type in this file
 *     is a hard build failure. An unused import is not - {@code javac} 25.0.3 publishes no lint key for
 *     one - so that prohibition is review-enforced.</li>
 * <li><strong>Test.</strong> {@code ./mvnw -B clean test}. The unit contract for this type lives in
 *     {@code src/test/java/com/cardemo/unit/model} as {@code TransactionAddRequestTest}; an earlier
 *     revision of this bullet said no such class existed and that this type was unreferenced from the test
 *     tree, and both halves are false and withdrawn. That contract asserts the component count of 21, that
 *     {@code amount} carries a currency-decorated value such as a dollar-prefixed thousands-separated
 *     figure without a binding failure while {@code accountId} and {@code cardNumber} are carried
 *     verbatim, that {@code accountId} round-trips {@code 00000000001} with its leading zeros intact,
 *     that {@code source} accepts an out-of-domain ten-character value without a deserialization
 *     failure, that {@code confirmation} distinguishes its five carried values, that no component is
 *     a floating-point, date or timestamp type, and that {@code toString()} leaks no card
 *     number.</li>
 * <li><strong>Key configuration.</strong> One setting governs how this payload binds, and one
 *     behaviour that used to depend on configuration no longer does. Rejection of an unknown JSON
 *     member is now enforced by this type itself, through {@link #rejectUnrecognisedProperty}, and not
 *     by {@code spring.jackson.deserialization.fail-on-unknown-properties}. That is deliberate on two
 *     grounds: the framework disables that setting by default and this repository publishes no
 *     {@code application*.yml} in which to enable it, so relying on it would leave the payload open;
 *     and a type-level guard cannot be switched off by a configuration change made elsewhere. It is
 *     true that {@code @JsonIgnoreProperties} can only suppress the check and never enable it, which
 *     is why that annotation is not used here - an any-setter that throws is the mechanism that
 *     actually rejects. Compilation must retain
 *     formal parameter names, which {@code pom.xml} guarantees with the compiler's
 *     {@code parameters} flag, so that constraint-violation paths and binding both resolve component
 *     names.</li>
 * <li><strong>Troubleshooting.</strong> An amount echoed with a missing leading digit is the
 *     eight-versus-nine digit mask asymmetry, not a defect - see {@link #AMOUNT_MASK_MAX}. An account
 *     identifier that arrives as {@code 1} instead of {@code 00000000001} means a numeric type was
 *     introduced somewhere on the path and the leading zeros were stripped. A rejected
 *     currency-decorated amount means the strict identifier parser was applied to
 *     {@code amount}.</li>
 * </ul>
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><strong>No Blocker and no High finding applies to this file.</strong> Every field contract below
 * was verified directly against the map and the program, the module compiles with zero warnings under
 * a warnings-as-errors configuration, and the type carries no credential and no floating-point
 * financial field. The findings that do apply are recorded in full rather than summarised:
 *
 * <ul>
 * <li><strong>Medium, resolved - unrecognised JSON properties were silently discarded.</strong> This
 *     type previously relied on {@code spring.jackson.deserialization.fail-on-unknown-properties} to
 *     reject a JSON member outside the 21-field contract. Two successive readings of that reliance were
 *     wrong and both are withdrawn: it is not true that no {@code application*.yml} exists in which the
 *     setting could be enabled, and it is not true that the setting is unset. {@code application.yml} sets
 *     {@code spring.jackson.deserialization.fail-on-unknown-properties} to {@code true} and no profile
 *     overlay disables it. What remains true is that the guarantee would be one configuration edit deep,
 *     and that the framework default is permissive, so a misspelled {@code confirmation} would bind as
 *     absent - which the program reads as "not yet confirmed" - the moment the property changed.
 *     Remediation retained: {@link #rejectUnrecognisedProperty} refuses any undeclared property on the type
 *     itself, so the guard holds under a lenient mapper as well as a strict one.</li>
 * <li><strong>Medium, closed - corpus census correction.</strong> The prior-generation plan prose
 *     aggregate of 460 BMS input fields was overstated. Counting the {@code 02 xxxI PIC} entries in the
 *     input group of each of the seventeen symbolic maps yields <strong>441</strong>, and the account-view
 *     map contributes 37 rather than the 36 that prose recorded. This map's own contribution of 21 is
 *     unaffected and is verified directly, so no field contract in this file depends on the aggregate.
 *     The remediation has been applied to {@code docs/technical-specifications.md}, which cites 441 and 37
 *     and records both supersessions in its section 0.2.2.1 corrections table, verified on 1 August 2026.
 *     {@code TRACEABILITY_MATRIX.md} is <strong>authored at the repository root</strong>; an earlier
 *     revision recorded it, and the e2e {@code GateVerificationTest} named by the plan, as not available,
 *     and both records are withdrawn. That test derives the census mechanically and publishes it as
 *     {@code gate7.screenInputFields}, so 441 is measured on every run rather than transcribed. No code
 *     change is required.</li>
 * <li><strong>Low - mask and field width disagree by one digit.</strong> {@code WS-TRAN-AMT-N} admits
 *     nine integer digits and {@code WS-TRAN-AMT-E} renders eight
 *     ({@code app/cbl/COTRN02C.cbl:58-59}), so the echo of an amount of a hundred million or more
 *     loses its leading digit. Remediation: none. The asymmetry is legacy behaviour, is reproduced
 *     deliberately, and is recorded here and in {@link #AMOUNT_MASK_MAX}.</li>
 * <li><strong>Low - screen widths are narrower than record widths.</strong> {@code TDESCI} is
 *     {@code X(60)} while {@code TRAN-DESC} is {@code X(100)}; {@code MNAMEI} is {@code X(30)} while
 *     {@code TRAN-MERCHANT-NAME} is {@code X(50)}; {@code MCITYI} is {@code X(25)} while
 *     {@code TRAN-MERCHANT-CITY} is {@code X(50)}. The screen widths govern this record because it
 *     models the map, and the entity retains the wider record widths. Remediation: none; do not widen
 *     these components to the record widths, which would accept input the screen cannot supply.</li>
 * <li><strong>Closed - the package contract file now exists.</strong>
 *     {@code src/main/java/com/cardemo/model/dto/package-info.java} was absent when this file was
 *     authored and is now present, so the earlier "not available" record is withdrawn. Its invariants
 *     have been cross-checked against the ones documented here and they agree; the field-width contract
 *     is additionally asserted against {@code app/cpy-bms/COTRN02.CPY} by the {@code BmsSymbolicMap}
 *     test oracle rather than against this class's own constants. It declares no symbol, so nothing is
 *     imported from it and compilation is unaffected either way.</li>
 * <li><strong>Not available - service-level objectives.</strong> The legacy corpus publishes no
 *     latency or throughput target for the transaction-add path, so none is asserted or invented for
 *     it. What is needed to close this item: a measured baseline from the performance gate.</li>
 * </ul>
 *
 * @param transactionName BMS {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COTRN02.CPY:24}. The
 *                        recurring header field carrying the CICS transaction identifier, which is
 *                        {@code 'CT02'} for this screen ({@code app/cbl/COTRN02C.cbl:37}). Declared
 *                        inline, never hoisted into a shared header type
 * @param title01         BMS {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COTRN02.CPY:30}. The
 *                        first recurring header title line, sourced from
 *                        {@code app/cpy/COTTL01Y.cpy}
 * @param currentDate     BMS {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COTRN02.CPY:36}. The
 *                        recurring header date, carried as text and never as a date type
 * @param programName     BMS {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COTRN02.CPY:42}. The
 *                        recurring header program name, {@code 'COTRN02C'} for this screen
 *                        ({@code app/cbl/COTRN02C.cbl:36}). One of only two components rendered by
 *                        {@link #toString()}
 * @param title02         BMS {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COTRN02.CPY:48}. The
 *                        second recurring header title line
 * @param currentTime     BMS {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COTRN02.CPY:54}. The
 *                        recurring header time. Width 8 <strong>on this map</strong>; the sign-on map
 *                        alone declares {@code PIC X(9)} at {@code app/cpy-bms/COSGN00.CPY:54}, which
 *                        is why the six header fields are declared inline
 * @param accountId       BMS {@code ACTIDINI PIC X(11)}, {@code app/cpy-bms/COTRN02.CPY:60}. Carried
 *                        as text so that significant leading zeros survive and so that the
 *                        <strong>strict digits-only</strong> parser can be applied downstream, per
 *                        the plain {@code FUNCTION NUMVAL} at {@code app/cbl/COTRN02C.cbl:204-205}
 *                        guarded by the {@code IS NOT NUMERIC} test at
 *                        {@code app/cbl/COTRN02C.cbl:197}
 * @param cardNumber      BMS {@code CARDNINI PIC X(16)}, {@code app/cpy-bms/COTRN02.CPY:66}.
 *                        <strong>Personally identifiable; never emitted in any rendering.</strong>
 *                        Carried as text for the <strong>strict digits-only</strong> parser, per the
 *                        plain {@code FUNCTION NUMVAL} at {@code app/cbl/COTRN02C.cbl:218-219}
 *                        guarded by the {@code IS NOT NUMERIC} test at
 *                        {@code app/cbl/COTRN02C.cbl:211}. Moved verbatim into
 *                        {@code TRAN-CARD-NUM} at {@code app/cbl/COTRN02C.cbl:459}
 * @param typeCode        BMS {@code TTYPCDI PIC X(2)}, {@code app/cpy-bms/COTRN02.CPY:72}. Raw text,
 *                        no enum and no lookup at this boundary; moved into {@code TRAN-TYPE-CD} at
 *                        {@code app/cbl/COTRN02C.cbl:452}
 * @param categoryCode    BMS {@code TCATCDI PIC X(4)}, {@code app/cpy-bms/COTRN02.CPY:78}. Raw text,
 *                        no enum and no lookup at this boundary; moved into {@code TRAN-CAT-CD} at
 *                        {@code app/cbl/COTRN02C.cbl:453}
 * @param source          BMS {@code TRNSRCI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:84}. A plain
 *                        {@code String} and <strong>never</strong> the {@code TransactionSource}
 *                        enum: {@code app/cbl/COTRN02C.cbl:454} moves it directly into
 *                        {@code TRAN-SOURCE} with no validation, no lookup and no domain check
 * @param description     BMS {@code TDESCI PIC X(60)}, {@code app/cpy-bms/COTRN02.CPY:90}. Moved
 *                        directly into {@code TRAN-DESC} at {@code app/cbl/COTRN02C.cbl:455}. The
 *                        screen width of 60 governs here even though the record field
 *                        {@code TRAN-DESC} is {@code X(100)} at {@code app/cpy/CVTRA05Y.cpy:9}
 * @param amount          BMS {@code TRNAMTI PIC X(12)}, {@code app/cpy-bms/COTRN02.CPY:96}. The
 *                        amount's <strong>display text</strong>, carried as {@code String} so that
 *                        the <strong>currency-tolerant</strong> parser can be applied downstream, per
 *                        {@code FUNCTION NUMVAL-C} at {@code app/cbl/COTRN02C.cbl:383-384} and
 *                        {@code app/cbl/COTRN02C.cbl:456-457}. No {@code @Digits} and no digits-only
 *                        {@code @Pattern} is declared, because either would defeat that parser. The
 *                        echo is re-rendered through {@link #AMOUNT_DISPLAY_MASK}
 * @param originatingDate BMS {@code TORIGDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:102}. Text,
 *                        never a date type; validated with {@link #DATE_VALIDATION_FORMAT} at
 *                        {@code app/cbl/COTRN02C.cbl:389-395} and moved into the 26-character
 *                        {@code TRAN-ORIG-TS} at {@code app/cbl/COTRN02C.cbl:464}
 * @param processingDate  BMS {@code TPROCDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:108}. Text,
 *                        never a date type; component-validated at
 *                        {@code app/cbl/COTRN02C.cbl:370-376} and moved into the 26-character
 *                        {@code TRAN-PROC-TS} at {@code app/cbl/COTRN02C.cbl:465}
 * @param merchantId      BMS {@code MIDI PIC X(9)}, {@code app/cpy-bms/COTRN02.CPY:114}. Carried as
 *                        text, never a numeric type, so that leading zeros survive into the
 *                        {@code TRAN-MERCHANT-ID PIC 9(09)} field it feeds at
 *                        {@code app/cbl/COTRN02C.cbl:460}
 * @param merchantName    BMS {@code MNAMEI PIC X(30)}, {@code app/cpy-bms/COTRN02.CPY:120}. Never
 *                        echoed in a validation message. The screen width of 30 governs here even
 *                        though {@code TRAN-MERCHANT-NAME} is {@code X(50)} at
 *                        {@code app/cpy/CVTRA05Y.cpy:12}
 * @param merchantCity    BMS {@code MCITYI PIC X(25)}, {@code app/cpy-bms/COTRN02.CPY:126}. Never
 *                        echoed in a validation message. The screen width of 25 governs here even
 *                        though {@code TRAN-MERCHANT-CITY} is {@code X(50)} at
 *                        {@code app/cpy/CVTRA05Y.cpy:13}
 * @param merchantZip     BMS {@code MZIPI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:132}. Never
 *                        echoed in a validation message; moved into {@code TRAN-MERCHANT-ZIP} at
 *                        {@code app/cbl/COTRN02C.cbl:463}
 * @param confirmation    BMS {@code CONFIRMI PIC X(1)}, {@code app/cpy-bms/COTRN02.CPY:138}. The
 *                        two-phase confirmation gate, a raw one-character {@code String} and
 *                        <strong>never</strong> a {@code boolean}: blank, affirmative, negative and
 *                        invalid are four distinct states with distinct messages and cursor
 *                        behaviour, and an absent value is a fifth
 * @param errorMessage    BMS {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COTRN02.CPY:144}. The
 *                        screen's error line. It is part of the map's input group and is therefore
 *                        part of the field contract, so it is carried; a client-supplied value is
 *                        never trusted as a diagnostic and never logged
 */
public record TransactionAddRequest(
        @Size(max = 4, message = "transactionName must not exceed 4 characters") String transactionName,
        @Size(max = 40, message = "title01 must not exceed 40 characters") String title01,
        @Size(max = 8, message = "currentDate must not exceed 8 characters") String currentDate,
        @Size(max = 8, message = "programName must not exceed 8 characters") String programName,
        @Size(max = 40, message = "title02 must not exceed 40 characters") String title02,
        @Size(max = 8, message = "currentTime must not exceed 8 characters") String currentTime,
        @Size(max = 11, message = "accountId must not exceed 11 characters") String accountId,
        @Size(max = 16, message = "cardNumber must not exceed 16 characters") String cardNumber,
        @Size(max = 2, message = "typeCode must not exceed 2 characters") String typeCode,
        @Size(max = 4, message = "categoryCode must not exceed 4 characters") String categoryCode,
        @Size(max = 10, message = "source must not exceed 10 characters") String source,
        @Size(max = 60, message = "description must not exceed 60 characters") String description,
        @Size(max = 12, message = "amount must not exceed 12 characters") String amount,
        @Size(max = 10, message = "originatingDate must not exceed 10 characters") String originatingDate,
        @Size(max = 10, message = "processingDate must not exceed 10 characters") String processingDate,
        @Size(max = 9, message = "merchantId must not exceed 9 characters") String merchantId,
        @Size(max = 30, message = "merchantName must not exceed 30 characters") String merchantName,
        @Size(max = 25, message = "merchantCity must not exceed 25 characters") String merchantCity,
        @Size(max = 10, message = "merchantZip must not exceed 10 characters") String merchantZip,
        @Size(max = 1, message = "confirmation must not exceed 1 character") String confirmation,
        @Size(max = 78, message = "errorMessage must not exceed 78 characters") String errorMessage) {

    /**
     * The legacy edited display mask for the transaction amount, {@code PIC +99999999.99}, declared at
     * {@code app/cbl/COTRN02C.cbl:59} as {@code WS-TRAN-AMT-E}.
     */
    public static final String AMOUNT_DISPLAY_MASK = "+99999999.99";

    /**
     * Scale of the persisted transaction amount: 2, from {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:10}.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * Precision of the persisted transaction amount: 11, giving the column type {@code NUMERIC(11,2)} for
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10}.
     */
    public static final int AMOUNT_PRECISION = 11;

    /**
     * The rounding mode mandated for every computation on a transaction amount.
     */
    public static final RoundingMode AMOUNT_ROUNDING_MODE = RoundingMode.HALF_EVEN;

    /**
     * The largest magnitude the legacy edited mask {@code PIC +99999999.99} can render: {@code 99999999.99},
     * eight integer digits.
     */
    public static final BigDecimal AMOUNT_MASK_MAX = new BigDecimal("99999999.99");

    /**
     * The largest magnitude the legacy numeric field {@code WS-TRAN-AMT-N PIC S9(9)V99} admits:
     * {@code 999999999.99}, nine integer digits, declared at {@code app/cbl/COTRN02C.cbl:58}.
     */
    public static final BigDecimal AMOUNT_VALUE_MAX = new BigDecimal("999999999.99");

    /**
     * The date format literal the legacy program hands to its date-validation routine: {@code 'YYYY-MM-DD'},
     * declared as {@code WS-DATE-FORMAT PIC X(10)} at {@code app/cbl/COTRN02C.cbl:60} and moved into
     * {@code CSUTLDTC-DATE-FORMAT} at {@code app/cbl/COTRN02C.cbl:390} before the {@code CALL 'CSUTLDTC'} at
     * {@code app/cbl/COTRN02C.cbl:393-395}.
     */
    public static final String DATE_VALIDATION_FORMAT = "YYYY-MM-DD";

    /**
     * Returns a diagnostic rendering that deliberately omits every personally identifiable field.
     *
     * <p>Every field below is passed through {@link ApiMasking#forDiagnostics(String)}. All of them are
     * declared {@code String} and all arrive from a JSON request body, so a caller controls their bytes; a CR
     * or LF concatenated straight in here would forge log records. The escaping also makes this rendering safe
     * on an instance that failed validation, which is the usual reason something renders one - {@code @Size}
     * runs after Jackson has already constructed the record.
     *
     * @return a rendering containing only the account identifier and the originating program name, and no
     *     character that could terminate a log record
     */
    @Override
    public String toString() {
        return "TransactionAddRequest[accountId=" + ApiMasking.forDiagnostics(accountId)
                + ", programName=" + ApiMasking.forDiagnostics(programName) + "]";
    }

    /**
     * Rejects any JSON property that is not one of the 21 this type declares.
     *
     * <p>{@code 01 COTRN2AI} declares exactly 21 input data items and the terminal could send nothing
     * else, so the map is a closed field contract. Discarding an unrecognised property silently would
     * break it in the direction that hides mistakes: a client that misspells {@code confirmation}
     * would otherwise submit a transaction whose confirmation state is absent, which
     * {@code app/cbl/COTRN02C.cbl} reads as "not yet confirmed" rather than as the malformed request it
     * is.</p>
     *
     * <p>This guard is declared on the type rather than left to the object mapper. The mapper-level
     * setting is in force - {@code application.yml} sets
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} to {@code true} and no profile
     * overlay disables it - but the framework default is to ignore unknown properties, so the strict
     * behaviour is a property value rather than a property of this type. Declaring the guard here means it
     * holds under a lenient mapper as well as a strict one, and it does so by rejecting rather than merely
     * by declining to suppress.</p>
     *
     * <p>Neither the offending name nor the offending value is echoed. Both are untrusted input, and
     * this type's whole security posture rests on never echoing a value that reached it from a client.</p>
     *
     * @param name  the unrecognised property name, accepted only so that Jackson can invoke this
     *              method; deliberately never read
     * @param value the unrecognised property value, accepted only so that Jackson can invoke this
     *              method; deliberately never read
     * @throws IllegalArgumentException always, because no unrecognised property is acceptable
     */
    @JsonAnySetter
    void rejectUnrecognisedProperty(final String name, final Object value) {
        throw new IllegalArgumentException(
                "TransactionAddRequest accepts only the 21 fields declared by"
                        + " app/cpy-bms/COTRN02.CPY, and an unrecognised property was supplied. The"
                        + " offending name and value are withheld because they are untrusted input.");
    }
}
