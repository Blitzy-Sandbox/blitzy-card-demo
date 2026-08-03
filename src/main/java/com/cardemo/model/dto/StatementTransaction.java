/*
 ******************************************************************
 * Program     : StatementTransaction.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Projected statement record - key 32, remainder 318, total 350; procTs carries 24 of 26.
 * Source      : app/cpy/COSTM01.CPY (350-byte projected record) @ 7756d89
 * Source      : app/cpy/CVTRA07Y.cpy (133-byte report lines) @ 7756d89
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
import java.math.RoundingMode;
import java.util.List;

/**
 * Immutable carrier for one <em>projected</em> statement transaction record, published together with the
 * fixed-width geometry constants that {@code StatementProcessor} and {@code StatementWriter} depend on.
 *
 * <h2>What this type is</h2>
 *
 * <p>The record layout is {@code app/cpy/COSTM01.CPY} - note the <strong>uppercase {@code .CPY}</strong>
 * extension, which makes it the sole uppercase member among the 28 copybooks in {@code app/cpy}; the other 27
 * are lowercase {@code .cpy}. A case-sensitive {@code *.cpy} glob silently drops it. The copybook's own header
 * comment at {@code app/cpy/COSTM01.CPY:2} reads "CardDemo - Transaction altered Layout for use in reporting";
 * the word <em>altered</em> is the whole story, and it is unpacked under "The projection" below.
 *
 * <p>This is the only data transfer object in the package whose contract is <strong>byte geometry</strong>
 * rather than a BMS symbolic-map field census. It carries values and publishes measurements. It performs no
 * fixed-width rendering, no projection, no truncation, no sorting, no aggregation, no HTML emission and no
 * mapping - all of that belongs to {@code StatementProcessor} and {@code StatementWriter} (Rule 1 Clause A,
 * "clear separation of concerns").
 *
 * <h2>The geometry: key 32 + remainder 318 = 350</h2>
 *
 * <p>Field by field, from {@code app/cpy/COSTM01.CPY:20-36}:
 *
 * <pre>
 * offsets     len  COBOL field         PIC            line   group
 *   1-  16     16  TRNX-CARD-NUM       X(16)          L22    TRNX-KEY   (L21)
 *  17-  32     16  TRNX-ID             X(16)          L23    TRNX-KEY   (L21)
 *  33-  34      2  TRNX-TYPE-CD        X(02)          L25    TRNX-REST  (L24)
 *  35-  38      4  TRNX-CAT-CD         9(04)          L26    TRNX-REST
 *  39-  48     10  TRNX-SOURCE         X(10)          L27    TRNX-REST
 *  49- 148    100  TRNX-DESC           X(100)         L28    TRNX-REST
 * 149- 159     11  TRNX-AMT            S9(09)V99      L29    TRNX-REST
 * 160- 168      9  TRNX-MERCHANT-ID    9(09)          L30    TRNX-REST
 * 169- 218     50  TRNX-MERCHANT-NAME  X(50)          L31    TRNX-REST
 * 219- 268     50  TRNX-MERCHANT-CITY  X(50)          L32    TRNX-REST
 * 269- 278     10  TRNX-MERCHANT-ZIP   X(10)          L33    TRNX-REST
 * 279- 304     26  TRNX-ORIG-TS        X(26)          L34    TRNX-REST
 * 305- 330     26  TRNX-PROC-TS        X(26)          L35    TRNX-REST  only 305-328 significant
 * 331- 350     20  FILLER              X(20)          L36    TRNX-REST  never written
 *
 * key       = 16 + 16                                                          = 32
 * remainder = 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 26 + 26 + 20          = 318
 * total     = 32 + 318                                                         = 350
 * </pre>
 *
 * <p>Both figures are corroborated independently, and by three separate artefacts rather than one:
 *
 * <ul>
 *   <li>{@code app/jcl/CREASTMT.JCL:30} declares {@code KEYS(32 0)} on the work cluster - the 32-byte key.</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:32} declares {@code RECORDSIZE(350 350)} - the 350-byte total.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:230} declares {@code WS-TRAN-REST PIC X(318)} - the 318-byte remainder,
 *       stated as a literal width by the consuming program itself.</li>
 *   </ul>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:51} contains {@code COPY COSTM01.}, so the statement program consumes exactly
 * this layout; {@code app/jcl/CREASTMT.JCL:50} sets the sorted sequential output to {@code LRECL=350} to match.
 *
 * <h2>Why the field order differs from the base transaction record</h2>
 *
 * <p>The base layout {@code app/cpy/CVTRA05Y.cpy:4-18} is also 350 bytes, but its offset map is different:
 *
 * <pre>
 * offsets     len  COBOL field         PIC            line
 *   1-  16     16  TRAN-ID             X(16)          L5
 *  17-  18      2  TRAN-TYPE-CD        X(02)          L6
 *  19-  22      4  TRAN-CAT-CD         9(04)          L7
 *  23-  32     10  TRAN-SOURCE         X(10)          L8
 *  33- 132    100  TRAN-DESC           X(100)         L9
 * 133- 143     11  TRAN-AMT            S9(09)V99      L10
 * 144- 152      9  TRAN-MERCHANT-ID    9(09)          L11
 * 153- 202     50  TRAN-MERCHANT-NAME  X(50)          L12
 * 203- 252     50  TRAN-MERCHANT-CITY  X(50)          L13
 * 253- 262     10  TRAN-MERCHANT-ZIP   X(10)          L14
 * 263- 278     16  TRAN-CARD-NUM       X(16)          L15
 * 279- 304     26  TRAN-ORIG-TS        X(26)          L16
 * 305- 330     26  TRAN-PROC-TS        X(26)          L17
 * 331- 350     20  FILLER              X(20)          L18
 * </pre>
 *
 * <p>In the base record the transaction identifier leads and the card number sits at 263. In {@code COSTM01}
 * the card number leads at position 1 and the identifier follows at 17 - the reverse. That is not a redundant
 * restatement of the same record: <strong>{@code COSTM01} is the output of a DFSORT projection</strong>, and
 * the reordering is what gives the work cluster a card-number-major key so statements can be produced per card.
 *
 * <h2>The projection, offset by offset - and the truncation it hides</h2>
 *
 * <p>{@code app/jcl/CREASTMT.JCL:53} sorts on two keys, card number then transaction identifier:
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}. {@code app/jcl/CREASTMT.JCL:54} then reshapes each record:
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}. Resolved clause by clause:
 *
 * <pre>
 * OUTREC clause   output pos   source pos     content
 * 1:263,16          1- 16      base 263-278   TRAN-CARD-NUM, relocated to the front
 * 17:1,262         17-278      base   1-262   TRAN-ID through TRAN-MERCHANT-ZIP, verbatim
 * 279:279,50      279-328      base 279-328   TRAN-ORIG-TS, all 26 bytes
 *                                             + TRAN-PROC-TS, only the FIRST 24 of its 26 bytes
 * (none)          329-330      -              DFSORT blank pad, inside the declared X(26)
 * (none)          331-350      -              the declared FILLER X(20), never populated
 * </pre>
 *
 * <p><strong>Consequence one: only 24 of the 26 declared characters are data.</strong>
 * {@code TRNX-PROC-TS} is declared {@code PIC X(26)} at {@code app/cpy/COSTM01.CPY:35} yet only 24 of
 * those characters are data. The projection's third clause copies
 * 50 bytes from base offset 279; base 279-304 is the 26-byte originating timestamp, which leaves 24 bytes for a
 * processing timestamp that occupies base 305-330. Output positions 329-330 are sort padding, not value.
 * <strong>Reproduce this exactly. Do not "fix" it.</strong> Statement output that differs from the legacy
 * baseline here <strong>looks like a Java bug and is not</strong>. See
 * {@link #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} and {@link #significantProcessingTimestamp()}, which exist
 * precisely so that no downstream caller silently assumes 26.
 *
 * <p><strong>Consequence two: the trailing filler is never written.</strong> The base record's 20-byte filler, base
 * 331-350, is <strong>never written at all</strong>: the projection stops at output position 328 and DFSORT
 * blank-pads the rest to {@code LRECL=350}. The {@code FILLER X(20)} at {@code app/cpy/COSTM01.CPY:36} exists only to
 * make the declared record 350 bytes. It is modelled as {@link #filler()} so the 14-field census and the 318-byte
 * arithmetic are both exact and testable, and it is expected to be absent or blank in every projected record.
 *
 * <h2>Timestamps are text, on three independent proofs</h2>
 *
 * <p>Both timestamp components are {@code String} of declared width 26. They are <strong>never</strong>
 * {@code LocalDateTime}, {@code Instant}, {@code Timestamp}, {@code OffsetDateTime} or {@code Date}, and this
 * file imports nothing from {@code java.time}. A temporal type would either reject a 24-character value
 * outright or normalise it, and either outcome breaks the byte-exact baseline comparison. Three unrelated
 * pieces of evidence force the same conclusion:
 *
 * <ol>
 *   <li>The generated timestamp's final four digits are <em>always</em> literal zeros.
 *       {@code app/cbl/CBTRN02C.cbl:701} executes {@code MOVE '0000' TO DB2-REST} inside
 *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBTRN02C.cbl:692}, and the format is documented at
 *       {@code app/cbl/CBTRN02C.cbl:149} as {@code EEEE-MM-DD-UU.MM.SS.HH0000}. The value is
 *       hundredths-of-a-second precision followed by four zeros - neither nanosecond nor millisecond
 *       precision, the fractional field being {@code DB2-MIL PIC 9(002)} at
 *       {@code app/cbl/CBTRN02C.cbl:173}.</li>
 *   <li>Batch expiry validation compares strings, not instants. {@code app/cbl/CBTRN02C.cbl:414} evaluates
 *       {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} - a character comparison over the first ten
 *       characters of the originating timestamp. The misspelled account field name is part of the field
 *       contract and is reproduced elsewhere as found.</li>
 *   <li>This record itself: a 24-of-26-character value is not a parseable timestamp at all.</li>
 * </ol>
 *
 * <h2>Money precision: the transaction scale, not the account scale</h2>
 *
 * <p>{@code TRNX-AMT} is {@code PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:29} - eleven characters, nine
 * integer digits and two decimals - mapping to {@code NUMERIC(11,2)}. It matches {@code TRAN-AMT} at
 * {@code app/cpy/CVTRA05Y.cpy:10} and is <strong>not</strong> the account money precision: {@code ACCT-CURR-BAL},
 * {@code ACCT-CREDIT-LIMIT} and their siblings in {@code app/cpy/CVACT01Y.cpy} are {@code PIC S9(10)V99},
 * mapping to {@code NUMERIC(12,2)}. Conflating the two is a silent widening that no test catches unless the
 * contract is asserted, which is why {@link #AMOUNT_PRECISION} and {@link #AMOUNT_INTEGER_DIGITS} are published.
 *
 * <p>The amount is a {@link BigDecimal} at scale {@value #AMOUNT_SCALE}. There is <strong>no</strong>
 * {@code float}, {@code double}, {@code Float} or {@code Double} anywhere in this file - the security gate
 * asserts that directly. Values are normalised to scale {@value #AMOUNT_SCALE} with
 * {@link RoundingMode#HALF_EVEN} on construction, which is the rounding the migration mandates and which also
 * makes the generated {@code equals} deterministic. For business comparison use
 * {@link #hasSameAmountAs(BigDecimal)}, which delegates to {@link BigDecimal#compareTo(BigDecimal)}; never
 * compare amounts with {@link BigDecimal#equals(Object)}, which is scale-sensitive.
 *
 * <p><strong>No absolute-value normalisation is applied anywhere.</strong> The corpus legitimately carries
 * negative amounts - the daily fixture {@code app/data/ASCII/dailytran.txt} carries both positive and negative
 * zoned-decimal overpunch signs - so the sign is data, and it round-trips untouched.
 *
 * <p>{@code TRNX-CAT-CD} at {@code app/cpy/COSTM01.CPY:26} and {@code TRNX-MERCHANT-ID} at
 * {@code app/cpy/COSTM01.CPY:30} carry <em>numeric</em> pictures, {@code 9(04)} and {@code 9(09)}, but are
 * modelled as {@code String} of widths 4 and 9. A numeric type would strip leading zeros and change the byte
 * image; {@code "0001"} and {@code "000000001"} must round-trip exactly as written.
 *
 * <p>Nothing in this file parses or formats a number, so no {@link java.util.Locale} is required and none is
 * imported. Where the writer does format - the edit masks below - it must pass {@code Locale.ROOT}, because a
 * platform default locale would emit a comma decimal separator in some locales and silently break the
 * byte-exact baseline (Rule 1 Clause C, "avoid environment-specific assumptions").
 *
 * <h2>{@code source} is a raw ten-character string, not an enum</h2>
 *
 * <p>{@code TRNX-SOURCE} at {@code app/cpy/COSTM01.CPY:27} is {@code PIC X(10)} and is modelled as an
 * unconstrained {@code String}. It is deliberately <strong>not</strong> bound to {@code TransactionSource}, and
 * this file imports nothing from {@code com.cardemo.model.enums}. The reason is in the source:
 * {@code app/cbl/COTRN02C.cbl:454} executes {@code MOVE TRNSRCI OF COTRN2AI TO TRAN-SOURCE} with no validation,
 * no lookup and no domain check, so any ten-character value can reach the statement record. Binding to an enum
 * would turn an out-of-domain stored value into a deserialization failure and make a legacy record unreadable.
 *
 * <h2>The 133-byte report line</h2>
 *
 * <p>The report geometry comes from {@code app/cpy/CVTRA07Y.cpy}. The authority for the 133-byte width is a
 * single declaration - {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} at
 * {@code app/cpy/CVTRA07Y.cpy:48} - and <strong>not</strong> the sum of any detail line. That distinction
 * matters, because no layout in the copybook actually occupies all 133 bytes:
 *
 * <pre>
 * layout                     lines      width   composition
 * REPORT-NAME-HEADER         L4-L13       115   38 + 41 + 12 + 10 + 4 + 10
 * TRANSACTION-DETAIL-REPORT  L15-L31      114   16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2
 * TRANSACTION-HEADER-1       L33-L46      114   17 + 12 + 19 + 35 + 14 + 1 + 16
 * TRANSACTION-HEADER-2       L48          133   one PIC X(133) of hyphens
 * REPORT-PAGE-TOTALS         L50-L54      112   11 + 86 + 15
 * REPORT-ACCOUNT-TOTALS      L56-L60      112   13 + 84 + 15
 * REPORT-GRAND-TOTALS        L62-L66      112   11 + 86 + 15
 * </pre>
 *
 * <p>The record is 133 bytes and the writer space-pads every shorter line to it. Note the invariant that holds
 * across all three totals lines: label width plus dots width is always
 * {@value #REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH}, which is why one
 * {@link ReportTotalsLine} type serves all three.
 *
 * <p><strong>Medium.</strong> A prior description of the detail line listed two consecutive
 * {@code FILLER X(01)} items between {@code TRAN-REPORT-ACCOUNT-ID} and {@code TRAN-REPORT-TYPE-CD}, which
 * would make the line 115 bytes. The copybook has exactly one, at {@code app/cpy/CVTRA07Y.cpy:19}, so the
 * detail line is {@value #REPORT_DETAIL_LINE_LENGTH} bytes. The source governs and the divergence is recorded
 * here.
 *
 * <h2>Two edit masks, deliberately not harmonised</h2>
 *
 * <p>The detail-line amount mask is <strong>minus-leading</strong>, {@code -ZZZ,ZZZ,ZZZ.ZZ} at
 * {@code app/cpy/CVTRA07Y.cpy:30}. All three totals masks are <strong>plus-leading</strong>,
 * {@code +ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy:54}, {@code :60} and {@code :66}. Both forms are
 * published, as {@link #DETAIL_AMOUNT_MASK} and {@link #TOTALS_AMOUNT_MASK}, and they are <strong>not</strong>
 * unified. Each is 15 characters wide.
 *
 * <h2>The "Account Total" label that breaks on the card number</h2>
 *
 * <p>{@link #ACCOUNT_TOTAL_LABEL} is the literal {@code Account Total}, exactly as declared at
 * {@code app/cpy/CVTRA07Y.cpy:57-58}, and it is <strong>preserved even though the control break fires on the
 * card number rather than the account</strong>. The evidence is in {@code app/cbl/CBTRN03C.cbl}: the break
 * condition at {@code app/cbl/CBTRN03C.cbl:181} is {@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}, comparing
 * the {@code PIC X(16)} card number held at {@code app/cbl/CBTRN03C.cbl:137}, and it performs
 * {@code 1120-WRITE-ACCOUNT-TOTALS} at {@code app/cbl/CBTRN03C.cbl:183}, which emits the
 * {@code REPORT-ACCOUNT-TOTALS} layout at {@code app/cbl/CBTRN03C.cbl:306-310}. The mismatch is a legacy quirk
 * that is <strong>intentionally not corrected</strong>, because behavioural parity is the contract. The label
 * must not be renamed to "Card Total".
 *
 * <h2>Statement output geometries, and three legacy defects logged rather than repaired</h2>
 *
 * <p>The statement job writes two outputs at two different record lengths:
 * {@value #STATEMENT_TEXT_RECORD_LENGTH} bytes for plain text, per {@code app/jcl/CREASTMT.JCL:89}, and
 * {@value #STATEMENT_HTML_RECORD_LENGTH} bytes for HTML, per {@code app/jcl/CREASTMT.JCL:94}. The HTML width is
 * corroborated independently by {@code app/cbl/CBSTM03A.CBL:149}, where {@code HTML-FIXED-LN PIC X(100)} is the
 * single hundred-character field that every markup fragment is emitted through as an 88-level literal.
 *
 * <p>Three defects exist in that job stream and are deliberately <strong>not</strong> repaired, because
 * repairing them would change the behaviour the parity comparison is measured against:
 *
 * <ul>
 *   <li><strong>Medium.</strong> A corrupted DD statement at {@code app/jcl/CREASTMT.JCL:90}, where a
 *       {@code SPACE=} parameter is followed by fragments of unrelated text on the same card.</li>
 *   <li><strong>Medium.</strong> An 80-versus-100 record-length mismatch for the HTML output between the
 *       pre-delete step, {@code app/jcl/CREASTMT.JCL:69} declaring {@code LRECL=80}, and the execution step,
 *       {@code app/jcl/CREASTMT.JCL:94} declaring {@code LRECL=100}. The execution step governs, so
 *       {@link #STATEMENT_HTML_RECORD_LENGTH} is 100.</li>
 *   <li><strong>Low.</strong> A procedure whose internal name differs from the member name the execute
 *       statement resolves: {@code app/proc/TRANREPT.prc:1} declares {@code //REPROC PROC} while
 *       {@code EXEC PROC=TRANREPT} resolves the member {@code TRANREPT}.</li>
 *   </ul>
 *
 * <p>This type models none of the three. Its only obligation is to publish the two widths as measured and not
 * to "helpfully" normalise them to a single value.
 *
 * <h2>The removed 510-transaction ceiling is a labelled deviation, not parity</h2>
 *
 * <p>The legacy statement program holds its entire working set in a fixed table declared at
 * {@code app/cbl/CBSTM03A.CBL:225-230}: {@code WS-CARD-TBL OCCURS 51 TIMES} at
 * {@code app/cbl/CBSTM03A.CBL:226}, each entry containing {@code WS-TRAN-TBL OCCURS 10 TIMES} at
 * {@code app/cbl/CBSTM03A.CBL:228}. That is a hard maximum of
 * {@value #LEGACY_MAX_TRANSACTIONS_PER_RUN} transactions per run, and the building loop at
 * {@code app/cbl/CBSTM03A.CBL:828-829} increments both subscripts with <em>no bounds check whatsoever</em> - a
 * latent storage-overrun defect.
 *
 * <p>{@link CardGroup} uses an unbounded {@link List} instead, which removes a silent truncation-and-corruption
 * hazard. That is a <strong>behavioural improvement rather than parity</strong>, so it is labelled explicitly
 * here rather than passed off as equivalence. <strong>This type imposes no 510-record
 * limit.</strong> The ceiling was removed deliberately, not overlooked, and the three legacy figures remain
 * published as {@link #LEGACY_MAX_CARDS_PER_RUN}, {@link #LEGACY_MAX_TRANSACTIONS_PER_CARD} and
 * {@link #LEGACY_MAX_TRANSACTIONS_PER_RUN} so the limit stays discoverable.
 *
 * <h2>Ordering is a correctness dependency, not a presentation choice</h2>
 *
 * <p>The legacy transaction lookup at {@code app/cbl/CBSTM03A.CBL:416-419} is a linear scan whose loop
 * terminates on {@code UNTIL CR-JMP > CR-CNT OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM)}. That early exit is
 * correct <em>only</em> because the table is ascending by card number, a guarantee supplied upstream by the
 * sort at {@code app/jcl/CREASTMT.JCL:53}. {@link CardGroup} therefore preserves encounter order through a
 * {@link List}; no {@code Map} or {@code Set} governs record order anywhere in this file, and no hash iteration
 * order is ever relied upon. If an implementation makes that lookup order-independent instead, the change is a
 * divergence worth recording, because it alters which records are found when the input is not sorted.
 *
 * <h2>Personally identifiable information</h2>
 *
 * <p>{@code TRNX-CARD-NUM} at {@code app/cpy/COSTM01.CPY:22} is a never-emit value, and it is the
 * <strong>first component of the composite key</strong> - so any naive key-oriented rendering leaks it. That
 * hazard is specific to this type and is handled deliberately rather than by omission: because a Java record
 * generates a {@code toString} covering every component, <em>not</em> overriding it here would publish the card
 * number. {@link #toString()}, {@link Key#toString()} and {@link CardGroup#toString()} are therefore each
 * overridden to emit only non-sensitive values - the transaction identifier and a count - and never the card
 * number, not masked and not as a last-four fragment. {@code ReportDetailLine} and {@code ReportTotalsLine}
 * carry no card number, so their generated renderings are safe as they stand.
 *
 * <p>Merchant name, city and postal code are not never-emit values, but they are not echoed in validation
 * messages either. Argument failures raise {@link IllegalArgumentException} naming the offending
 * <em>field</em> and never its <em>value</em>. This type carries no password, hash, token or signing key, does
 * not implement {@code Serializable} - insecure deserialization being a flagged risky pattern - and adds no
 * dependency. {@code src/main/resources/logback-spring.xml} does provide a second line of defence - a
 * masking decorator with field-name paths and value-mask regexes applied identically in every profile - but
 * never emitting the card number remains the primary control, because a mask can only match a field name or
 * a value shape it was taught and this record's own rendering is what decides whether either is ever
 * presented to it (Rule 1 Clause D).
 *
 * <h2>Observability posture</h2>
 *
 * <p>Rule 1 Clause A asks for structured logs, meaningful errors and measurable behaviour "where relevant". A
 * pure data holder is precisely where a logger is <em>not</em> relevant, and here it would be actively harmful:
 * a logger reachable from a type whose leading key component is a card number is a leak waiting for a careless
 * call site. This type therefore declares <strong>no logger and performs no logging</strong>, by decision rather
 * than by oversight. The clause is satisfied through its other two mechanisms instead. Errors are meaningful:
 * every rejection names the field it concerns and the width or digit budget it violated. Behaviour is
 * measurable: the geometry constants published above are what let a caller assert byte-exactness numerically
 * rather than by eye, and {@link #significantProcessingTimestamp()} makes the one non-obvious measurement, 24
 * significant characters rather than 26, directly assertable. Counters, timers, spans and structured log events
 * for statement generation belong to the batch tier that consumes this type, where a job instance and a
 * correlation identifier are in scope; none of that context exists inside a record component.
 *
 * <h2>Validation, error modes and boundary behaviour</h2>
 *
 * <p>Every {@code String} component is checked against its declared width on construction. The check is a
 * <strong>maximum</strong>, not an equality: a shorter value is legal and is space-padded later by the writer,
 * which is exactly what lets the 24-character projected processing timestamp through unaltered.
 *
 * <ul>
 *   <li><strong>No trimming, upper-casing or lower-casing is ever applied.</strong> Trimming would destroy the
 *       fixed-width padding that the byte-exact comparison depends on. The hazard is acute here: a trimmed
 *       24-character processing timestamp is indistinguishable from a trimmed 26-character one, which would
 *       silently erase the truncation evidence described above.</li>
 *   <li>Absent, blank and low-values remain <strong>three distinct states</strong>. {@code null} is never
 *       coerced to {@code ""} and {@code ""} is never coerced to {@code null}; a run of NUL characters, the
 *       COBOL {@code LOW-VALUES} image, is preserved as given.</li>
 *   <li><strong>Error mode.</strong> {@link IllegalArgumentException} when a value exceeds its declared width,
 *       when the amount needs more than {@value #AMOUNT_INTEGER_DIGITS} integer digits, or when a
 *       {@link ReportTotalsLine} is built from a label and width triple that no totals layout declares.
 *       {@link CardGroup} additionally rejects a {@code null} transaction list or a {@code null} element. No
 *       exception is swallowed and no catch block is empty.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This type reads <strong>no</strong> external configuration. It binds no Spring property, no profile key, no
 * environment variable and no system property, so nothing about its behaviour differs between the local, test
 * and production profiles - which is itself the determinism Rule 1 Clause C asks for. Its "configuration" is
 * therefore entirely compile-time constants plus constructor behaviour, and these are the defaults a caller
 * inherits without asking:
 *
 * <ul>
 *   <li>Geometry defaults to the projected record: {@value #KEY_LENGTH} plus {@value #REMAINDER_LENGTH} equals
 *       {@value #RECORD_LENGTH} bytes, with the processing timestamp significant at
 *       {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} of its {@value #PROCESSING_TIMESTAMP_LENGTH} declared
 *       characters.</li>
 *   <li>{@code amount} is normalised to scale {@value #AMOUNT_SCALE} using {@link RoundingMode#HALF_EVEN}. A
 *       {@code null} amount stays {@code null} and is never defaulted to zero, because absent and zero are
 *       different facts.</li>
 *   <li>Width checking defaults to a maximum rather than an equality, so a short value is accepted and left
 *       unpadded; padding to the declared width is the writer's responsibility.</li>
 *   <li>Every component accepts {@code null}, and no component is ever defaulted, trimmed or case-folded.</li>
 *   <li>{@link CardGroup#transactions()} defaults to an unmodifiable defensive copy in encounter order, and its
 *       capacity default is unbounded: the legacy {@value #LEGACY_MAX_TRANSACTIONS_PER_RUN}-record ceiling is
 *       deliberately not reinstated.</li>
 *   <li>{@link ReportTotalsLine} admits only the three layouts the copybook declares, reached through
 *       {@link ReportTotalsLine#pageTotal(BigDecimal)}, {@link ReportTotalsLine#accountTotal(BigDecimal)} and
 *       {@link ReportTotalsLine#grandTotal(BigDecimal)}; any other label or width pair is rejected.</li>
 *   <li>Emission widths default to {@value #REPORT_LINE_LENGTH} bytes for a report line and
 *       {@value #STATEMENT_TEXT_RECORD_LENGTH} and {@value #STATEMENT_HTML_RECORD_LENGTH} bytes for the two
 *       statement outputs.</li>
 *   </ul>
 *
 * <h2>How to build and test</h2>
 *
 * <p>Built by the root {@code pom.xml} against Java 25 with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so any warning in a category {@code javac} 25 publishes fails the build; an
 * unused import is not such a category and must be spotted by hand. Compile with
 * {@code ./mvnw -B clean compile} and exercise the unit tier with {@code ./mvnw -B test}; coverage is enforced at
 * {@code verify}. The tests for this type live in {@code src/test/java/com/cardemo/unit/model} and assert,
 * at minimum, that the three geometry constants are 32, 318 and 350 and sum correctly, that the remainder field
 * widths add to 318, that a 24-character processing timestamp is accepted without being padded to 26, that the
 * amount is a scale-2 {@link BigDecimal} whose negative sign survives a round trip, that {@code "0001"} and
 * {@code "000000001"} keep their leading zeros, that the report and statement constants are 133, 80 and 100,
 * and that the account-total label is exactly {@code Account Total}.
 *
 * <h2>Troubleshooting</h2>
 *
 * <ul>
 *   <li>Statement output differing from the baseline in the last two characters of the processing timestamp is
 *       <em>expected</em>: those positions are pad, not data. Compare only the first
 *       {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} characters, via
 *       {@link #significantProcessingTimestamp()}.</li>
 *   <li>An amount comparison that fails for values that print identically is a scale artefact. Use
 *       {@link #hasSameAmountAs(BigDecimal)} rather than {@link BigDecimal#equals(Object)}.</li>
 *   <li>A category code or merchant identifier that has lost its leading zeros indicates that some caller
 *       converted it to a numeric type. Both are {@code String} by contract.</li>
 *   <li>A record rejected at construction reports the field name only; the value is withheld on purpose because
 *       the card number is PII.</li>
 *   </ul>
 *
 * <p>No service-level objective for statement generation exists anywhere in the corpus, so none is asserted
 * here. Establishing one would require a measured baseline from a running system.
 *
 * @param cardNumber          {@code TRNX-CARD-NUM}, {@code PIC X(16)}, {@code app/cpy/COSTM01.CPY:22}, 16
 *                            bytes, offsets 1-16, leading component of the 32-byte key. PII: never emitted.
 *                            May be {@code null}; must not exceed 16 characters.
 * @param transactionId       {@code TRNX-ID}, {@code PIC X(16)}, {@code app/cpy/COSTM01.CPY:23}, 16 bytes,
 *                            offsets 17-32, trailing component of the 32-byte key. May be {@code null}; must
 *                            not exceed 16 characters.
 * @param typeCode            {@code TRNX-TYPE-CD}, {@code PIC X(02)}, {@code app/cpy/COSTM01.CPY:25}, 2 bytes,
 *                            offsets 33-34. May be {@code null}; must not exceed 2 characters.
 * @param categoryCode        {@code TRNX-CAT-CD}, {@code PIC 9(04)}, {@code app/cpy/COSTM01.CPY:26}, 4 bytes,
 *                            offsets 35-38. A {@code String} despite the numeric picture, so that leading zeros
 *                            such as {@code "0001"} survive the fixed-width emission. May be {@code null}; must
 *                            not exceed 4 characters.
 * @param source              {@code TRNX-SOURCE}, {@code PIC X(10)}, {@code app/cpy/COSTM01.CPY:27}, 10 bytes,
 *                            offsets 39-48. An unconstrained {@code String}, deliberately not the
 *                            {@code TransactionSource} enum. May be {@code null}; must not exceed 10
 *                            characters.
 * @param description         {@code TRNX-DESC}, {@code PIC X(100)}, {@code app/cpy/COSTM01.CPY:28}, 100 bytes,
 *                            offsets 49-148. May be {@code null}; must not exceed 100 characters.
 * @param amount              {@code TRNX-AMT}, {@code PIC S9(09)V99}, {@code app/cpy/COSTM01.CPY:29}, 11
 *                            bytes, offsets 149-159, mapping to {@code NUMERIC(11,2)}. Normalised to scale
 *                            {@value #AMOUNT_SCALE} with {@link RoundingMode#HALF_EVEN}. The sign is data and is
 *                            never normalised away. May be {@code null}; the magnitude must fit
 *                            {@value #AMOUNT_INTEGER_DIGITS} integer digits.
 * @param merchantId          {@code TRNX-MERCHANT-ID}, {@code PIC 9(09)}, {@code app/cpy/COSTM01.CPY:30}, 9
 *                            bytes, offsets 160-168. A {@code String} despite the numeric picture, so that
 *                            {@code "000000001"} survives. May be {@code null}; must not exceed 9 characters.
 * @param merchantName        {@code TRNX-MERCHANT-NAME}, {@code PIC X(50)}, {@code app/cpy/COSTM01.CPY:31}, 50
 *                            bytes, offsets 169-218. May be {@code null}; must not exceed 50 characters.
 * @param merchantCity        {@code TRNX-MERCHANT-CITY}, {@code PIC X(50)}, {@code app/cpy/COSTM01.CPY:32}, 50
 *                            bytes, offsets 219-268. May be {@code null}; must not exceed 50 characters.
 * @param merchantZip         {@code TRNX-MERCHANT-ZIP}, {@code PIC X(10)}, {@code app/cpy/COSTM01.CPY:33}, 10
 *                            bytes, offsets 269-278. May be {@code null}; must not exceed 10 characters.
 * @param originatingTimestamp {@code TRNX-ORIG-TS}, {@code PIC X(26)}, {@code app/cpy/COSTM01.CPY:34}, 26
 * bytes, offsets 279-304.
 * @param processingTimestamp {@code TRNX-PROC-TS}, {@code PIC X(26)}, {@code app/cpy/COSTM01.CPY:35}, 26 bytes
 * declared at offsets 305-330, of which <strong>only the first
 * {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} are significant</strong>.
 * @param filler {@code FILLER}, {@code PIC X(20)}, {@code app/cpy/COSTM01.CPY:36}, 20 bytes, offsets 331-350.
 */
public record StatementTransaction(
        String cardNumber,
        String transactionId,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        BigDecimal amount,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String originatingTimestamp,
        String processingTimestamp,
        String filler) {

    /**
     * Length in bytes of the composite key {@code TRNX-KEY}, {@code app/cpy/COSTM01.CPY:21-23}: the 16-byte
     * card number plus the 16-byte transaction identifier. Corroborated by {@code KEYS(32 0)} on the work
     * cluster at {@code app/jcl/CREASTMT.JCL:30}. Value {@value}.
     */
    public static final int KEY_LENGTH = 32;

    /**
     * Length in bytes of {@code TRNX-REST}, {@code app/cpy/COSTM01.CPY:24-36}, being
     * {@code 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 26 + 26 + 20}. Corroborated as a literal width by
     * {@code WS-TRAN-REST PIC X(318)} at {@code app/cbl/CBSTM03A.CBL:230}. Value {@value}.
     */
    public static final int REMAINDER_LENGTH = 318;

    /**
     * Total record length in bytes, {@link #KEY_LENGTH} plus {@link #REMAINDER_LENGTH}. Corroborated by
     * {@code RECORDSIZE(350 350)} at {@code app/jcl/CREASTMT.JCL:32} and by the sorted sequential output's
     * {@code LRECL=350} at {@code app/jcl/CREASTMT.JCL:50}. Value {@value}.
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * {@code TRNX-CARD-NUM PIC X(16)}, {@code app/cpy/COSTM01.CPY:22}, offsets 1-16. Value {@value}.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * {@code TRNX-ID PIC X(16)}, {@code app/cpy/COSTM01.CPY:23}, offsets 17-32. Value {@value}.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * {@code TRNX-TYPE-CD PIC X(02)}, {@code app/cpy/COSTM01.CPY:25}, offsets 33-34. Value {@value}.
     */
    public static final int TYPE_CODE_LENGTH = 2;

    /**
     * {@code TRNX-CAT-CD PIC 9(04)}, {@code app/cpy/COSTM01.CPY:26}, offsets 35-38. Value {@value}.
     */
    public static final int CATEGORY_CODE_LENGTH = 4;

    /**
     * {@code TRNX-SOURCE PIC X(10)}, {@code app/cpy/COSTM01.CPY:27}, offsets 39-48. Value {@value}.
     */
    public static final int SOURCE_LENGTH = 10;

    /**
     * {@code TRNX-DESC PIC X(100)}, {@code app/cpy/COSTM01.CPY:28}, offsets 49-148. Value {@value}.
     */
    public static final int DESCRIPTION_LENGTH = 100;

    /**
     * Byte width of {@code TRNX-AMT PIC S9(09)V99}, {@code app/cpy/COSTM01.CPY:29}, offsets 149-159: nine
     * integer digits plus two decimal digits, the sign carried as a zoned-decimal overpunch on the final
     * character rather than as a separate byte. Value {@value}.
     */
    public static final int AMOUNT_LENGTH = 11;

    /**
     * Decimal scale of {@code TRNX-AMT}, from the {@code V99} of {@code PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:29}. Value {@value}.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * Total significant digits of {@code TRNX-AMT}: {@value #AMOUNT_INTEGER_DIGITS} integer digits plus
     * {@value #AMOUNT_SCALE} decimal digits, giving the SQL type {@code NUMERIC(11,2)}. Distinct from account
     * money, which is {@code PIC S9(10)V99} in {@code app/cpy/CVACT01Y.cpy} and therefore
     * {@code NUMERIC(12,2)}. Value {@value}.
     */
    public static final int AMOUNT_PRECISION = 11;

    /**
     * Integer digits available to {@code TRNX-AMT}, from the {@code S9(09)} of {@code PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:29}. A magnitude needing more than this cannot be emitted in 11 bytes and is
     * rejected at construction. Value {@value}.
     */
    public static final int AMOUNT_INTEGER_DIGITS = 9;

    /**
     * {@code TRNX-MERCHANT-ID PIC 9(09)}, {@code app/cpy/COSTM01.CPY:30}, offsets 160-168. Value {@value}.
     */
    public static final int MERCHANT_ID_LENGTH = 9;

    /**
     * {@code TRNX-MERCHANT-NAME PIC X(50)}, {@code app/cpy/COSTM01.CPY:31}, offsets 169-218. Value {@value}.
     */
    public static final int MERCHANT_NAME_LENGTH = 50;

    /**
     * {@code TRNX-MERCHANT-CITY PIC X(50)}, {@code app/cpy/COSTM01.CPY:32}, offsets 219-268. Value {@value}.
     */
    public static final int MERCHANT_CITY_LENGTH = 50;

    /**
     * {@code TRNX-MERCHANT-ZIP PIC X(10)}, {@code app/cpy/COSTM01.CPY:33}, offsets 269-278. Value {@value}.
     */
    public static final int MERCHANT_ZIP_LENGTH = 10;

    /**
     * {@code TRNX-ORIG-TS PIC X(26)}, {@code app/cpy/COSTM01.CPY:34}, offsets 279-304. All 26 characters are
     * significant: the projection at {@code app/jcl/CREASTMT.JCL:54} copies this field whole. Value {@value}.
     */
    public static final int ORIGINATING_TIMESTAMP_LENGTH = 26;

    /**
     * width of {@code TRNX-PROC-TS PIC X(26)}, {@code app/cpy/COSTM01.CPY:35}, offsets 305-330. Only
     * {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} of these characters carry data; see
     * {@link #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH}. Value {@value}.
     */
    public static final int PROCESSING_TIMESTAMP_LENGTH = 26;

    /**
     * Characters of {@code TRNX-PROC-TS} the projection actually writes, {@value}, against the
     * {@value #PROCESSING_TIMESTAMP_LENGTH}-byte width the record declares at {@code app/cpy/COSTM01.CPY:35}.
     * The shortfall is the truncation that {@code app/jcl/CREASTMT.JCL:STEP010} imposes.
     */
    public static final int PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH = 24;

    /**
     * Trailing characters of {@code TRNX-PROC-TS} that the projection never writes,
     * {@value #PROCESSING_TIMESTAMP_LENGTH} minus {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH}. Value
     * {@value}.
     */
    public static final int PROCESSING_TIMESTAMP_PAD_LENGTH = 2;

    /**
     * {@code FILLER PIC X(20)}, {@code app/cpy/COSTM01.CPY:36}, offsets 331-350. Counted in
     * {@link #REMAINDER_LENGTH} so the declared record reaches {@link #RECORD_LENGTH}, but never populated: the
     * projection stops at output position {@value #PROJECTION_LAST_WRITTEN_POSITION}. Value {@value}.
     */
    public static final int FILLER_LENGTH = 20;

    /**
     * Offset of {@code TRAN-CARD-NUM} in the <em>base</em> record {@code app/cpy/CVTRA05Y.cpy:15}, which the
     * first {@code OUTREC} clause {@code 1:263,16} relocates to position 1, and on which
     * {@code SORT FIELDS=(263,16,CH,A,...)} at {@code app/jcl/CREASTMT.JCL:53} takes its major key. Value
     * {@value}.
     */
    public static final int BASE_CARD_NUMBER_OFFSET = 263;

    /**
     * Bytes copied verbatim by the second {@code OUTREC} clause {@code 17:1,262} of
     * {@code app/jcl/CREASTMT.JCL:STEP010}: base offsets 1-262,
     * {@code TRAN-ID} through {@code TRAN-MERCHANT-ZIP}, landing at output offsets 17-278. Value {@value}.
     */
    public static final int BASE_HEAD_LENGTH = 262;

    /**
     * Source and destination offset of the third {@code OUTREC} clause {@code 279:279,50} of
     * {@code app/jcl/CREASTMT.JCL:STEP010}. Value {@value}.
     */
    public static final int BASE_TAIL_OFFSET = 279;

    /**
     * Bytes copied by the third {@code OUTREC} clause {@code 279:279,50} of
     * {@code app/jcl/CREASTMT.JCL:STEP010}: the 26-byte originating timestamp
     * plus only {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} of the processing timestamp's
     * {@value #PROCESSING_TIMESTAMP_LENGTH}. This constant is where the truncation is arithmetically visible.
     * Value {@value}.
     */
    public static final int BASE_TAIL_LENGTH = 50;

    /**
     * Last output position the projection writes. Everything from {@value #PROJECTION_LAST_WRITTEN_POSITION}
     * plus one to {@link #RECORD_LENGTH} is DFSORT blank padding: the final two characters of the declared
     * processing timestamp and the whole trailing filler. Value {@value}.
     */
    public static final int PROJECTION_LAST_WRITTEN_POSITION = 328;

    /**
     * Report record length in bytes. The authority is the single declaration
     * {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} at {@code app/cpy/CVTRA07Y.cpy:48}, and
     * <strong>not</strong> the sum of any other layout - no other layout in the copybook fills the record, so
     * the writer space-pads each of them to this width. Value {@value}.
     */
    public static final int REPORT_LINE_LENGTH = 133;

    /**
     * Width of {@code REPORT-NAME-HEADER}, {@code app/cpy/CVTRA07Y.cpy:4-13}, being
     * {@code 38 + 41 + 12 + 10 + 4 + 10}. Value {@value}.
     */
    public static final int REPORT_NAME_HEADER_LENGTH = 115;

    /**
     * Width of {@code TRANSACTION-DETAIL-REPORT}, {@code app/cpy/CVTRA07Y.cpy:15-31}, being
     * {@code 16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2}. Note that the copybook declares exactly one
     * {@code FILLER X(01)} between {@code TRAN-REPORT-ACCOUNT-ID} and {@code TRAN-REPORT-TYPE-CD}, at
     * {@code app/cpy/CVTRA07Y.cpy:19}; a prior description double-counted it and reported 115. Value {@value}.
     */
    public static final int REPORT_DETAIL_LINE_LENGTH = 114;

    /**
     * Width of {@code TRANSACTION-HEADER-1}, {@code app/cpy/CVTRA07Y.cpy:33-46}, being
     * {@code 17 + 12 + 19 + 35 + 14 + 1 + 16}. Equal to {@link #REPORT_DETAIL_LINE_LENGTH}, which is what keeps
     * the column captions aligned over the detail rows. Value {@value}.
     */
    public static final int REPORT_COLUMN_HEADER_LENGTH = 114;

    /**
     * Width shared by all three totals layouts, {@code app/cpy/CVTRA07Y.cpy:50-66}: label plus dots plus a
     * {@value #AMOUNT_MASK_LENGTH}-character mask. Value {@value}.
     */
    public static final int REPORT_TOTALS_LINE_LENGTH = 112;

    /**
     * Invariant shared by all three totals layouts: label width plus dots width. It holds for {@code 11 + 86},
     * {@code 13 + 84} and {@code 11 + 86} alike, which is why a single {@link ReportTotalsLine} type serves
     * every totals line. Value {@value}.
     */
    public static final int REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH = 97;

    /**
     * Width of both edit masks, {@code -ZZZ,ZZZ,ZZZ.ZZ} and {@code +ZZZ,ZZZ,ZZZ.ZZ}. Value {@value}.
     */
    public static final int AMOUNT_MASK_LENGTH = 15;

    /**
     * The <strong>minus-leading</strong> edit mask of {@code TRAN-REPORT-AMT} on the detail line,
     * {@code app/cpy/CVTRA07Y.cpy:30}. Deliberately <strong>not</strong> harmonised with
     * {@link #TOTALS_AMOUNT_MASK}, which leads with a plus. Value {@value}.
     */
    public static final String DETAIL_AMOUNT_MASK = "-ZZZ,ZZZ,ZZZ.ZZ";

    /**
     * The <strong>plus-leading</strong> edit mask shared by all three totals lines,
     * {@code app/cpy/CVTRA07Y.cpy:54}, {@code :60} and {@code :66}. Deliberately <strong>not</strong>
     * harmonised with {@link #DETAIL_AMOUNT_MASK}, which leads with a minus. Value {@value}.
     */
    public static final String TOTALS_AMOUNT_MASK = "+ZZZ,ZZZ,ZZZ.ZZ";

    /**
     * {@code REPT-SHORT-NAME}, {@code app/cpy/CVTRA07Y.cpy:5-6}, declared {@code PIC X(38)} with this exact
     * literal value. Value {@value}.
     */
    public static final String REPORT_SHORT_NAME = "DALYREPT";

    /**
     * Declared width of {@code REPT-SHORT-NAME}, {@code app/cpy/CVTRA07Y.cpy:5}. Value {@value}.
     */
    public static final int REPORT_SHORT_NAME_LENGTH = 38;

    /**
     * {@code REPT-LONG-NAME}, {@code app/cpy/CVTRA07Y.cpy:7-8}, declared {@code PIC X(41)} with this exact
     * literal value. Value {@value}.
     */
    public static final String REPORT_LONG_NAME = "Daily Transaction Report";

    /**
     * Declared width of {@code REPT-LONG-NAME}, {@code app/cpy/CVTRA07Y.cpy:7}. Value {@value}.
     */
    public static final int REPORT_LONG_NAME_LENGTH = 41;

    /**
     * {@code REPT-DATE-HEADER}, {@code app/cpy/CVTRA07Y.cpy:9-10}, declared {@code PIC X(12)}. The trailing
     * space is part of the literal. Value {@value}.
     */
    public static final String REPORT_DATE_HEADER = "Date Range: ";

    /**
     * The {@code FILLER X(04)} separator between the two report dates, {@code app/cpy/CVTRA07Y.cpy:12}. Both
     * surrounding spaces are part of the literal. Value {@value}.
     */
    public static final String REPORT_DATE_SEPARATOR = " to ";

    /**
     * Declared width of {@code REPT-START-DATE} and {@code REPT-END-DATE}, {@code app/cpy/CVTRA07Y.cpy:11} and
     * {@code :13}, both initialised to spaces. Value {@value}.
     */
    public static final int REPORT_DATE_LENGTH = 10;

    /**
     * {@code REPORT-PAGE-TOTALS} label, {@code app/cpy/CVTRA07Y.cpy:51-52}. Value {@value}.
     */
    public static final String PAGE_TOTAL_LABEL = "Page Total";

    /**
     * Declared width of the page-total label, {@code app/cpy/CVTRA07Y.cpy:51}. Value {@value}.
     */
    public static final int PAGE_TOTAL_LABEL_LENGTH = 11;

    /**
     * Width of the page-total dot run, {@code X(86) VALUE ALL '.'}, {@code app/cpy/CVTRA07Y.cpy:53}. {@value}
     */
    public static final int PAGE_TOTAL_DOTS_LENGTH = 86;

    /**
     * {@code REPORT-ACCOUNT-TOTALS} label, {@code app/cpy/CVTRA07Y.cpy:57-58}, preserved verbatim even though
     * the control break that emits it fires on the <strong>card number</strong>: see
     * {@code app/cbl/CBTRN03C.cbl:181} and {@code app/cbl/CBTRN03C.cbl:306-310}. Intentional legacy quirk; must
     * not be renamed to "Card Total". Value {@value}.
     */
    public static final String ACCOUNT_TOTAL_LABEL = "Account Total";

    /**
     * Declared width of the account-total label, {@code app/cpy/CVTRA07Y.cpy:57}. Value {@value}.
     */
    public static final int ACCOUNT_TOTAL_LABEL_LENGTH = 13;

    /**
     * Width of the account-total dot run, {@code X(84) VALUE ALL '.'}, {@code app/cpy/CVTRA07Y.cpy:59}.
     * {@value}
     */
    public static final int ACCOUNT_TOTAL_DOTS_LENGTH = 84;

    /**
     * {@code REPORT-GRAND-TOTALS} label, {@code app/cpy/CVTRA07Y.cpy:63-64}. Value {@value}.
     */
    public static final String GRAND_TOTAL_LABEL = "Grand Total";

    /**
     * Declared width of the grand-total label, {@code app/cpy/CVTRA07Y.cpy:63}. Value {@value}.
     */
    public static final int GRAND_TOTAL_LABEL_LENGTH = 11;

    /**
     * Width of the grand-total dot run, {@code X(86) VALUE ALL '.'}, {@code app/cpy/CVTRA07Y.cpy:65}. {@value}
     */
    public static final int GRAND_TOTAL_DOTS_LENGTH = 86;

    /**
     * {@code TRAN-REPORT-TRANS-ID PIC X(16)}, {@code app/cpy/CVTRA07Y.cpy:16}. Value {@value}.
     */
    public static final int REPORT_TRANSACTION_ID_LENGTH = 16;

    /**
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, {@code app/cpy/CVTRA07Y.cpy:18}. Value {@value}.
     */
    public static final int REPORT_ACCOUNT_ID_LENGTH = 11;

    /**
     * {@code TRAN-REPORT-TYPE-CD PIC X(02)}, {@code app/cpy/CVTRA07Y.cpy:20}. Value {@value}.
     */
    public static final int REPORT_TYPE_CODE_LENGTH = 2;

    /**
     * {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, {@code app/cpy/CVTRA07Y.cpy:22}. Value {@value}.
     */
    public static final int REPORT_TYPE_DESCRIPTION_LENGTH = 15;

    /**
     * {@code TRAN-REPORT-CAT-CD PIC 9(04)}, {@code app/cpy/CVTRA07Y.cpy:24}. Value {@value}.
     */
    public static final int REPORT_CATEGORY_CODE_LENGTH = 4;

    /**
     * {@code TRAN-REPORT-CAT-DESC PIC X(29)}, {@code app/cpy/CVTRA07Y.cpy:26}. Value {@value}.
     */
    public static final int REPORT_CATEGORY_DESCRIPTION_LENGTH = 29;

    /**
     * {@code TRAN-REPORT-SOURCE PIC X(10)}, {@code app/cpy/CVTRA07Y.cpy:28}. Value {@value}.
     */
    public static final int REPORT_SOURCE_LENGTH = 10;

    /**
     * The {@code FILLER X(01) VALUE '-'} that separates a code from its description on the detail line,
     * {@code app/cpy/CVTRA07Y.cpy:21} and {@code :25}. Value {@value}.
     */
    public static final String REPORT_CODE_DESCRIPTION_SEPARATOR = "-";

    /**
     * Record length of the plain-text statement output, from {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} on
     * the {@code STMTFILE} DD of the execution step at {@code app/jcl/CREASTMT.JCL:89}. Value {@value}.
     */
    public static final int STATEMENT_TEXT_RECORD_LENGTH = 80;

    /**
     * Record length of the HTML statement output, from {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} on the
     * {@code HTMLFILE} DD of the execution step at {@code app/jcl/CREASTMT.JCL:94}, corroborated by
     * {@code HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:149} - the single hundred-character field
     * through which every markup fragment is emitted.
     */
    public static final int STATEMENT_HTML_RECORD_LENGTH = 100;

    /**
     * {@code WS-CARD-TBL OCCURS 51 TIMES}, {@code app/cbl/CBSTM03A.CBL:226}. Value {@value}.
     */
    public static final int LEGACY_MAX_CARDS_PER_RUN = 51;

    /**
     * {@code WS-TRAN-TBL OCCURS 10 TIMES}, {@code app/cbl/CBSTM03A.CBL:228}. Value {@value}.
     */
    public static final int LEGACY_MAX_TRANSACTIONS_PER_CARD = 10;

    /**
     * The legacy hard ceiling, {@value #LEGACY_MAX_CARDS_PER_RUN} cards times
     * {@value #LEGACY_MAX_TRANSACTIONS_PER_CARD} transactions each, on a table whose building loop at
     * {@code app/cbl/CBSTM03A.CBL:828-829} increments both subscripts with no bounds check at all.
     */
    public static final int LEGACY_MAX_TRANSACTIONS_PER_RUN = 510;

    /**
     * Exclusive bound on the magnitude of {@link #amount()}, being ten raised to
     * {@value #AMOUNT_INTEGER_DIGITS}. A value at or above this needs a tenth integer digit and cannot be
     * emitted in the {@value #AMOUNT_LENGTH} bytes that {@code PIC S9(09)V99} allows. Immutable, so it is a
     * constant and not global mutable state.
     */
    private static final BigDecimal AMOUNT_MAGNITUDE_LIMIT = new BigDecimal("1000000000");

    /**
     * Validates each component against its declared width and normalises the amount to the scale the PIC clause
     * dictates.
     *
     * @throws IllegalArgumentException if any component exceeds its declared width, or if the amount's
     * magnitude needs more than {@value #AMOUNT_INTEGER_DIGITS} integer digits.
     */
    public StatementTransaction {
        requireWithinWidth(cardNumber, CARD_NUMBER_LENGTH, "cardNumber");
        requireWithinWidth(transactionId, TRANSACTION_ID_LENGTH, "transactionId");
        requireWithinWidth(typeCode, TYPE_CODE_LENGTH, "typeCode");
        requireWithinWidth(categoryCode, CATEGORY_CODE_LENGTH, "categoryCode");
        requireWithinWidth(source, SOURCE_LENGTH, "source");
        requireWithinWidth(description, DESCRIPTION_LENGTH, "description");
        requireWithinWidth(merchantId, MERCHANT_ID_LENGTH, "merchantId");
        requireWithinWidth(merchantName, MERCHANT_NAME_LENGTH, "merchantName");
        requireWithinWidth(merchantCity, MERCHANT_CITY_LENGTH, "merchantCity");
        requireWithinWidth(merchantZip, MERCHANT_ZIP_LENGTH, "merchantZip");
        requireWithinWidth(originatingTimestamp, ORIGINATING_TIMESTAMP_LENGTH, "originatingTimestamp");
        requireWithinWidth(processingTimestamp, PROCESSING_TIMESTAMP_LENGTH, "processingTimestamp");
        requireWithinWidth(filler, FILLER_LENGTH, "filler");
        amount = normaliseAmount(amount);
    }

    /**
     * Rejects a value that cannot fit its fixed-width field. A {@code null} value is accepted, because absence
     * is a distinct and meaningful state that must not be coerced to the empty string.
     *
     * @param value the candidate value, possibly {@code null}
     * @param maxLength the declared width of the COBOL field in bytes
     * @param fieldName the Java component name, used verbatim in the failure message
     * @throws IllegalArgumentException if {@code value} is longer than {@code maxLength}.
     */
    private static void requireWithinWidth(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds its declared COBOL width: "
                    + value.length() + " characters supplied, " + maxLength + " permitted");
        }
    }

    /**
     * Normalises an amount to scale {@value #AMOUNT_SCALE} using {@link RoundingMode#HALF_EVEN} and rejects a
     * magnitude that {@code PIC S9(09)V99} cannot hold.
     *
     * @param value the candidate amount, possibly {@code null}
     * @return {@code null} if {@code value} is {@code null}, otherwise {@code value} at scale
     * {@value #AMOUNT_SCALE}
     * @throws IllegalArgumentException if the magnitude needs more than {@value #AMOUNT_INTEGER_DIGITS} integer
     * digits.
     */
    private static BigDecimal normaliseAmount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        // abs() bounds the magnitude symmetrically; it never rewrites the sign of the value returned below.
        if (scaled.abs().compareTo(AMOUNT_MAGNITUDE_LIMIT) >= 0) {
            throw new IllegalArgumentException("amount exceeds the " + AMOUNT_INTEGER_DIGITS
                    + " integer digits permitted by PIC S9(09)V99");
        }
        return scaled;
    }

    /**
     * Returns the {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} characters of
     * {@link #processingTimestamp()} that the DFSORT projection actually wrote, discarding the trailing
     * {@value #PROCESSING_TIMESTAMP_PAD_LENGTH} pad positions if they are present.
     *
     * @return {@code null} when the timestamp is absent, the value unchanged when it is already at or below the
     * significant length, otherwise its leading {@value #PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH} characters.
     */
    public String significantProcessingTimestamp() {
        if (processingTimestamp == null || processingTimestamp.length() <= PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH) {
            return processingTimestamp;
        }
        return processingTimestamp.substring(0, PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH);
    }

    /**
     * Compares this record's amount with another by numeric value, using
     * {@link BigDecimal#compareTo(BigDecimal)} rather than {@link BigDecimal#equals(Object)}.
     *
     * @param other the amount to compare against, possibly {@code null}
     * @return {@code true} if both amounts are absent, or if both are present and numerically equal.
     */
    public boolean hasSameAmountAs(BigDecimal other) {
        if (amount == null || other == null) {
            return amount == null && other == null;
        }
        return amount.compareTo(other) == 0;
    }

    /**
     * Returns a diagnostic rendering that deliberately omits the card number.
     *
     * @return the type name and the transaction identifier only.
     */
    @Override
    public String toString() {
        return "StatementTransaction[transactionId=" + transactionId + "]";
    }

    /**
     * The {@value #KEY_LENGTH}-byte composite key {@code TRNX-KEY}, {@code app/cpy/COSTM01.CPY:21-23}.
     *
     * @param cardNumber {@code TRNX-CARD-NUM}, {@code PIC X(16)}, {@code app/cpy/COSTM01.CPY:22}, 16 bytes, key
     * offsets 1-16.
     * @param transactionId {@code TRNX-ID}, {@code PIC X(16)}, {@code app/cpy/COSTM01.CPY:23}, 16 bytes, key
     * offsets 17-32.
     */
    public record Key(String cardNumber, String transactionId) {

        /**
         * Validates both components against their declared widths.
         *
         * @throws IllegalArgumentException if either component exceeds 16 characters.
         */
        public Key {
            requireWithinWidth(cardNumber, CARD_NUMBER_LENGTH, "cardNumber");
            requireWithinWidth(transactionId, TRANSACTION_ID_LENGTH, "transactionId");
        }

        /**
         * Returns a diagnostic rendering that deliberately omits the card number.
         *
         * @return the type name and the transaction identifier only, never the card number
         */
        @Override
        public String toString() {
            return "StatementTransaction.Key[transactionId=" + transactionId + "]";
        }
    }

    /**
     * One card's statement transactions, in encounter order - the Java counterpart of a single
     * {@code WS-CARD-TBL} entry at {@code app/cbl/CBSTM03A.CBL:226-230}.
     *
     * @param cardNumber the card these transactions belong to, {@code WS-CARD-NUM PIC X(16)} at
     * {@code app/cbl/CBSTM03A.CBL:227}, 16 bytes.
     * @param transactions the card's transactions in encounter order, the Java counterpart of the ten
     * {@code WS-TRAN-TBL} slots at {@code app/cbl/CBSTM03A.CBL:228-230}; never {@code null} and never containing
     * a {@code null} element.
     */
    public record CardGroup(String cardNumber, List<StatementTransaction> transactions) {

        /**
         * Validates the card number width and takes an unmodifiable defensive copy of the transaction list.
         *
         * @throws IllegalArgumentException if the card number exceeds 16 characters, if {@code transactions} is
         * {@code null}, or if any element is {@code null}.
         */
        public CardGroup {
            requireWithinWidth(cardNumber, CARD_NUMBER_LENGTH, "cardNumber");
            if (transactions == null) {
                throw new IllegalArgumentException("transactions must not be null; use an empty list instead");
            }
            for (StatementTransaction transaction : transactions) {
                if (transaction == null) {
                    throw new IllegalArgumentException("transactions must not contain a null element");
                }
            }
            transactions = List.copyOf(transactions);
        }

        /**
         * Returns the card's transactions as an unmodifiable list, in encounter order.
         *
         * @return an unmodifiable, order-preserving view of the card's transactions, never {@code null}
         */
        public List<StatementTransaction> transactions() {
            return List.copyOf(transactions);
        }

        /**
         * Returns a diagnostic rendering that deliberately omits the card number.
         *
         * @return the type name and the transaction count only, never the card number
         */
        @Override
        public String toString() {
            return "StatementTransaction.CardGroup[transactionCount=" + transactions.size() + "]";
        }
    }

    /**
     * Values for one {@value #REPORT_DETAIL_LINE_LENGTH}-byte detail line of the transaction report,
     * {@code 01 TRANSACTION-DETAIL-REPORT} at {@code app/cpy/CVTRA07Y.cpy:15-31}.
     *
     * @param transactionId {@code TRAN-REPORT-TRANS-ID}, {@code PIC X(16)}, {@code app/cpy/CVTRA07Y.cpy:16}, 16
     * bytes.
     * @param accountId {@code TRAN-REPORT-ACCOUNT-ID}, {@code PIC X(11)}, {@code app/cpy/CVTRA07Y.cpy:18}, 11
     * bytes.
     * @param typeCode {@code TRAN-REPORT-TYPE-CD}, {@code PIC X(02)}, {@code app/cpy/CVTRA07Y.cpy:20}, 2 bytes.
     * @param typeDescription {@code TRAN-REPORT-TYPE-DESC}, {@code PIC X(15)}, {@code app/cpy/CVTRA07Y.cpy:22},
     * 15 bytes.
     * @param categoryCode {@code TRAN-REPORT-CAT-CD}, {@code PIC 9(04)}, {@code app/cpy/CVTRA07Y.cpy:24}, 4
     * bytes.
     * @param categoryDescription {@code TRAN-REPORT-CAT-DESC}, {@code PIC X(29)},
     * {@code app/cpy/CVTRA07Y.cpy:26}, 29 bytes.
     * @param source {@code TRAN-REPORT-SOURCE}, {@code PIC X(10)}, {@code app/cpy/CVTRA07Y.cpy:28}, 10 bytes.
     * @param amount {@code TRAN-REPORT-AMT}, {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}, {@code app/cpy/CVTRA07Y.cpy:30},
     * rendered through {@link #DETAIL_AMOUNT_MASK}.
     */
    public record ReportDetailLine(
            String transactionId,
            String accountId,
            String typeCode,
            String typeDescription,
            String categoryCode,
            String categoryDescription,
            String source,
            BigDecimal amount) {

        /**
         * Validates every component against its declared width and normalises the amount to scale
         * {@value #AMOUNT_SCALE}.
         *
         * @throws IllegalArgumentException if any component exceeds its declared width, or if the amount's
         * magnitude needs more than {@value #AMOUNT_INTEGER_DIGITS} integer digits.
         */
        public ReportDetailLine {
            requireWithinWidth(transactionId, REPORT_TRANSACTION_ID_LENGTH, "transactionId");
            requireWithinWidth(accountId, REPORT_ACCOUNT_ID_LENGTH, "accountId");
            requireWithinWidth(typeCode, REPORT_TYPE_CODE_LENGTH, "typeCode");
            requireWithinWidth(typeDescription, REPORT_TYPE_DESCRIPTION_LENGTH, "typeDescription");
            requireWithinWidth(categoryCode, REPORT_CATEGORY_CODE_LENGTH, "categoryCode");
            requireWithinWidth(categoryDescription, REPORT_CATEGORY_DESCRIPTION_LENGTH, "categoryDescription");
            requireWithinWidth(source, REPORT_SOURCE_LENGTH, "source");
            amount = normaliseAmount(amount);
        }

        /**
         * Returns a deliberately redacted rendering that exposes only the transaction identifier.
         *
         * <p>Overridden for the same reason as {@link StatementTransaction#toString()}, reached by a different
         * route. This layout declares no card number, so it holds none of the values that method suppresses;
         * what it does hold is an account identifier at {@code app/cpy/CVTRA07Y.cpy:18} beside a signed amount
         * at {@code :30}. Rendering the pair states whose money moved and how much, which is customer financial
         * activity whether or not a card number accompanies it. The amount is omitted rather than rounded or
         * bucketed, because a rounded amount beside an account identifier is still that account's activity.
         *
         * <p>The four classification components - type code, type description, category code and category
         * description - are omitted as well. They are not sensitive in themselves, but including them would
         * describe what the customer bought, which is the same disclosure by another route.
         *
         * @return the type name and the transaction identifier only, never the account identifier, the amount
         *         or the transaction's classification
         */
        @Override
        public String toString() {
            return "StatementTransaction.ReportDetailLine[transactionId=" + transactionId + "]";
        }
    }

    /**
     * Values for one {@value #REPORT_TOTALS_LINE_LENGTH}-byte totals line of the transaction report, whose layout
     * is declared at {@code app/cpy/CVTRA07Y.cpy}.
     *
     * @param label the layout's literal label, exactly as the copybook declares it - one of
     * {@link #PAGE_TOTAL_LABEL}, {@link #ACCOUNT_TOTAL_LABEL} or {@link #GRAND_TOTAL_LABEL}.
     * @param labelWidth the declared width of the label field.
     * @param dotsWidth the declared width of the {@code VALUE ALL '.'} run that follows.
     * @param total the accumulated amount, held at scale {@value #AMOUNT_SCALE}.
     */
    public record ReportTotalsLine(String label, int labelWidth, int dotsWidth, BigDecimal total) {

        /**
         * Validates that the label and its two widths form one of exactly three declared triples, and
         * normalises the total to scale {@value #AMOUNT_SCALE}.
         *
         * @throws IllegalArgumentException if {@code label} is {@code null}, if the label and widths do not
         * match a triple declared in {@code app/cpy/CVTRA07Y.cpy:50-66}, or if the total's magnitude needs more
         * than {@value #AMOUNT_INTEGER_DIGITS} integer digits.
         */
        public ReportTotalsLine {
            if (label == null) {
                throw new IllegalArgumentException("label must not be null");
            }
            if (labelWidth + dotsWidth != REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH) {
                throw new IllegalArgumentException("labelWidth plus dotsWidth must equal "
                        + REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH + " for every totals layout declared in"
                        + " app/cpy/CVTRA07Y.cpy:50-66");
            }
            if (!isDeclaredTotalsLayout(label, labelWidth)) {
                throw new IllegalArgumentException("label and labelWidth do not match any totals layout"
                        + " declared in app/cpy/CVTRA07Y.cpy:50-66");
            }
            total = normaliseAmount(total);
        }

        /**
         * Returns a deliberately redacted rendering that exposes only the layout's label.
         *
         * <p>The generated record rendering would publish {@code total}, which on the account-totals layout at
         * {@code app/cpy/CVTRA07Y.cpy:56-60} is one customer's accumulated activity for the reporting window,
         * and on the grand-totals layout at {@code :62-66} is the portfolio figure for the whole run. Neither
         * belongs in a log record, a stack trace or a diagnostic message, and an aggregate is not safer than a
         * single amount merely because it is a sum - the account total is attributable to exactly the account
         * whose control break produced it.
         *
         * <p>The label is safe to render, provably rather than by judgement: the canonical constructor accepts
         * it only when it equals {@link #PAGE_TOTAL_LABEL}, {@link #ACCOUNT_TOTAL_LABEL} or
         * {@link #GRAND_TOTAL_LABEL}, so it is one of three copybook literals and carries no caller input at
         * all. It is also the one component a diagnostic reading actually needs, because it says which of the
         * three totals layouts the instance is. The two widths are omitted as redundant: each is determined by
         * the label.
         *
         * @return the type name and the layout label only, never the accumulated total
         */
        @Override
        public String toString() {
            return "StatementTransaction.ReportTotalsLine[label=" + label + "]";
        }

        /**
         * Reports whether a label and label width pair matches one of the three declared totals layouts.
         *
         * @param label the candidate label, never {@code null} at the call site
         * @param labelWidth the candidate label width
         * @return {@code true} if the pair is declared in {@code app/cpy/CVTRA07Y.cpy:50-66}
         */
        private static boolean isDeclaredTotalsLayout(String label, int labelWidth) {
            return (PAGE_TOTAL_LABEL.equals(label) && labelWidth == PAGE_TOTAL_LABEL_LENGTH)
                    || (ACCOUNT_TOTAL_LABEL.equals(label) && labelWidth == ACCOUNT_TOTAL_LABEL_LENGTH)
                    || (GRAND_TOTAL_LABEL.equals(label) && labelWidth == GRAND_TOTAL_LABEL_LENGTH);
        }

        /**
         * Builds the {@code REPORT-PAGE-TOTALS} line, {@code app/cpy/CVTRA07Y.cpy:50-54}.
         *
         * @param total the page total, possibly {@code null}
         * @return a totals line labelled {@link #PAGE_TOTAL_LABEL} with the copybook's declared widths
         */
        public static ReportTotalsLine pageTotal(BigDecimal total) {
            return new ReportTotalsLine(PAGE_TOTAL_LABEL, PAGE_TOTAL_LABEL_LENGTH, PAGE_TOTAL_DOTS_LENGTH, total);
        }

        /**
         * Builds the {@code REPORT-ACCOUNT-TOTALS} line, {@code app/cpy/CVTRA07Y.cpy:56-60}.
         *
         * @param total the per-card accumulation that the legacy program labels as an account total, possibly
         * {@code null}
         * @return a totals line labelled {@link #ACCOUNT_TOTAL_LABEL} with the copybook's declared widths
         */
        public static ReportTotalsLine accountTotal(BigDecimal total) {
            return new ReportTotalsLine(ACCOUNT_TOTAL_LABEL, ACCOUNT_TOTAL_LABEL_LENGTH,
                    ACCOUNT_TOTAL_DOTS_LENGTH, total);
        }

        /**
         * Builds the {@code REPORT-GRAND-TOTALS} line, {@code app/cpy/CVTRA07Y.cpy:62-66}.
         *
         * @param total the grand total, possibly {@code null}
         * @return a totals line labelled {@link #GRAND_TOTAL_LABEL} with the copybook's declared widths
         */
        public static ReportTotalsLine grandTotal(BigDecimal total) {
            return new ReportTotalsLine(GRAND_TOTAL_LABEL, GRAND_TOTAL_LABEL_LENGTH,
                    GRAND_TOTAL_DOTS_LENGTH, total);
        }
    }
}
