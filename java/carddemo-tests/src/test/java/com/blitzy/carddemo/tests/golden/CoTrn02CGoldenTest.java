/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.golden;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte golden-record parity test for {@code COTRN02C}
 * (Add Transaction Online).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COTRN02C.cbl} &mdash; the
 * {@code PROGRAM-ID COTRN02C} ({@code app/cbl/COTRN02C.cbl:L23}) online-CICS
 * program backing transaction {@code CT02}
 * ({@code WS-TRANID PIC X(04) VALUE 'CT02'} at
 * {@code app/cbl/COTRN02C.cbl:L37}, mapset {@code COTRN02} via
 * {@code COPY COTRN02.} at {@code app/cbl/COTRN02C.cbl:L82}). This is the
 * <strong>MOST COMPLEX online program</strong> in the CardDemo application
 * &mdash; per AAP &sect;0.6.11 the program captures every input field from
 * the COTRN2A BMS map, validates twenty-one distinct precondition checks
 * producing seventeen distinct verbatim error messages, dispatches the
 * {@code CSUTLDTC} date validation routine twice (once for the origin date
 * field {@code TORIGDTI}, once for the processing date field
 * {@code TPROCDTI}), performs CARDXREF AIX lookup by EITHER account id OR
 * card number, reads the next sequential transaction id via the
 * {@code STARTBR / READPREV / ENDBR} idiom against the {@code TRANSACT}
 * VSAM KSDS, and finally writes a fully-populated CVTRA05Y
 * {@code TRAN-RECORD} (350-byte fixed-width record) with byte-for-byte
 * fidelity to the COBOL baseline.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.transaction.CoTrn02C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COTRN02C is translated into the
 * {@code application/transaction/} subpackage co-located with its sibling
 * translations {@code CoTrn00C} (transaction list, transaction {@code CT00})
 * and {@code CoTrn01C} (transaction view, transaction {@code CT01}). The
 * Java translation has a constructor that injects four collaborators
 * ({@link com.blitzy.carddemo.domain.port.TransactionRepository},
 * {@link com.blitzy.carddemo.domain.port.CardXrefRepository},
 * {@link com.blitzy.carddemo.application.ProgramRegistry}, and
 * {@link com.blitzy.carddemo.application.util.DateValidator}) matching the
 * COBOL collaborator surface (TRANSACT KSDS port, CARDXREF AIX port,
 * dynamic-CALL routing for {@code EXEC CICS XCTL} dispatch to
 * {@code COMEN01C}, and the {@code CSUTLDTC} date-validation utility). The
 * base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against file-backed
 * adapters wired to the six auxiliary fixtures returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>Verbatim 17-Error Validation Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>Per AAP &sect;0.7.1 ("preserve all 17 validation errors verbatim;
 * preserve trailing '...' on errors") the following seventeen distinct
 * error message literals MUST be preserved byte-for-byte by the Java
 * translation and appear in the captured fixtures. Each carries the exact
 * trailing {@code ...} ellipsis from the COBOL source; ANY normalization
 * (trimming the ellipsis, collapsing whitespace, recasing) breaks byte
 * parity and blocks the PR:</p>
 * <ol>
 *   <li>{@code 'Account ID must be Numeric...'} at
 *       {@code app/cbl/COTRN02C.cbl:L199} (emitted when the
 *       {@code ACTIDINI} field is non-numeric).</li>
 *   <li>{@code 'Card Number must be Numeric...'} at
 *       {@code app/cbl/COTRN02C.cbl:L213} (emitted when the
 *       {@code CARDNINI} field is non-numeric).</li>
 *   <li>{@code 'Account or Card Number must be entered...'} at
 *       {@code app/cbl/COTRN02C.cbl:L226} (emitted when both
 *       {@code ACTIDINI} AND {@code CARDNINI} are blank; the
 *       cross-field check).</li>
 *   <li>{@code 'Type CD can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L254} (mixed-case {@code 'can NOT'}
 *       preserved verbatim).</li>
 *   <li>{@code 'Category CD can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L260}.</li>
 *   <li>{@code 'Source can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L266}.</li>
 *   <li>{@code 'Description can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L272}.</li>
 *   <li>{@code 'Amount can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L278}.</li>
 *   <li>{@code 'Orig Date can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L284} (note abbreviated
 *       {@code 'Orig'}, not {@code 'Original'}).</li>
 *   <li>{@code 'Proc Date can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L290} (note abbreviated
 *       {@code 'Proc'}, not {@code 'Processing'}).</li>
 *   <li>{@code 'Merchant ID can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L296}.</li>
 *   <li>{@code 'Merchant Name can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L302}.</li>
 *   <li>{@code 'Merchant City can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L308}.</li>
 *   <li>{@code 'Merchant Zip can NOT be empty...'} at
 *       {@code app/cbl/COTRN02C.cbl:L314}.</li>
 *   <li>{@code 'Type CD must be Numeric...'} at
 *       {@code app/cbl/COTRN02C.cbl:L325}.</li>
 *   <li>{@code 'Category CD must be Numeric...'} at
 *       {@code app/cbl/COTRN02C.cbl:L331}.</li>
 *   <li>{@code 'Merchant ID must be Numeric...'} at
 *       {@code app/cbl/COTRN02C.cbl:L432}.</li>
 * </ol>
 *
 * <h2>Additional Verbatim Messages (Lookup, Write, Success)</h2>
 *
 * <p>Beyond the 17 input-validation errors above, COTRN02C also emits the
 * following six verbatim messages on the lookup / write paths that the
 * captured fixtures exercise:</p>
 * <ul>
 *   <li>{@code 'Account ID NOT found...'} at
 *       {@code app/cbl/COTRN02C.cbl:L593} (CARDXREF AIX miss by acctId).</li>
 *   <li>{@code 'Card Number NOT found...'} at
 *       {@code app/cbl/COTRN02C.cbl:L626} (CARDXREF miss by cardNumber).</li>
 *   <li>{@code 'Unable to lookup Acct in XREF AIX file...'} at
 *       {@code app/cbl/COTRN02C.cbl:L600}; counterpart
 *       {@code 'Unable to lookup Card # in XREF file...'} at
 *       {@code app/cbl/COTRN02C.cbl:L633}; counterpart
 *       {@code 'Unable to lookup Transaction...'} at
 *       {@code app/cbl/COTRN02C.cbl:L664,L693} (the dual-occurrence
 *       message at the {@code STARTBR} and {@code READPREV} sites).</li>
 *   <li>{@code 'Tran ID already exist...'} at
 *       {@code app/cbl/COTRN02C.cbl:L738} (emitted on
 *       {@code DFHRESP(DUPKEY)} or {@code DFHRESP(DUPREC)} from the
 *       {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)} call).</li>
 *   <li>{@code 'Unable to Add Transaction...'} at
 *       {@code app/cbl/COTRN02C.cbl:L745} (emitted on any other
 *       {@code WS-RESP-CD} value from the WRITE).</li>
 *   <li><strong>Success message</strong> at
 *       {@code app/cbl/COTRN02C.cbl:L728-L733}: the COBOL
 *       {@code STRING 'Transaction added successfully. '} (note the
 *       trailing space INSIDE the literal) followed by
 *       {@code ' Your Tran ID is '} (note the leading space INSIDE the
 *       second literal) concatenates to produce the verbatim
 *       <strong>{@code 'Transaction added successfully.  Your Tran ID
 *       is X.'}</strong>. The DOUBLE space between {@code 'successfully.'}
 *       and {@code 'Your'} is significant &mdash; ANY normalization to a
 *       single space breaks byte parity per AAP &sect;0.7.1. The Java
 *       translation must emit the message via
 *       {@code String.format("Transaction added successfully.  Your "
 *       + "Tran ID is %s.", tranId)} (concatenation operator, NOT
 *       a single-space template) or an equivalent constant string
 *       with the two-space sequence baked in.</li>
 * </ul>
 *
 * <h2>Numeric Regex Contracts (AAP &sect;0.6.1)</h2>
 *
 * <p>The COBOL program validates two scalar fields against fixed format
 * conventions; the Java translation enforces these via {@code Pattern}
 * objects with the exact regular expressions documented here:</p>
 * <ul>
 *   <li><strong>Amount</strong>: {@code ^[+-][0-9]{8}\.[0-9]{2}$} matching
 *       the COBOL {@code WS-TRAN-AMT PIC +99999999.99} display picture at
 *       {@code app/cbl/COTRN02C.cbl:L53} (eight integer digits, fixed
 *       decimal point, exactly two fraction digits, sign-prefixed). The
 *       Java translation reads the matched group into a
 *       {@link java.math.BigDecimal} via the
 *       {@link com.blitzy.carddemo.domain.util.Decimals} facade with
 *       {@link java.math.MathContext#DECIMAL128} and explicit
 *       {@code setScale(2, RoundingMode.UNNECESSARY)} per AAP &sect;0.6.1
 *       (no {@code double}, no {@code float}). Trailing zeros MUST be
 *       preserved &mdash; a BigDecimal value of {@code 1.20} MUST NOT be
 *       normalized to {@code 1.2} or the encoded TRAN-AMT in
 *       {@code transact.txt} will differ from the COBOL baseline.</li>
 *   <li><strong>Date</strong>: {@code ^[0-9]{4}-[0-9]{2}-[0-9]{2}$}
 *       matching the COBOL {@code WS-DATE-FORMAT PIC X(10) VALUE
 *       'YYYY-MM-DD'} at {@code app/cbl/COTRN02C.cbl:L60}. The Java
 *       translation routes each matching value through
 *       {@link com.blitzy.carddemo.application.util.DateValidator} (the
 *       Java translation of CSUTLDTC) which uses
 *       {@link java.time.LocalDate#parse(CharSequence,
 *       java.time.format.DateTimeFormatter)} with a strict
 *       {@link java.time.format.ResolverStyle#STRICT} resolver style;
 *       Calendar/Date are FORBIDDEN per AAP &sect;0.6.4.</li>
 * </ul>
 *
 * <h2>CSUTLDTC Called Twice (Origin and Processing Dates)</h2>
 *
 * <p>The COBOL program invokes {@code CALL 'CSUTLDTC'} twice per submit
 * &mdash; once for {@code TORIGDTI} (the origin date) and once for
 * {@code TPROCDTI} (the processing date). Each call is independent: a
 * failure on the origin date does NOT short-circuit the processing date
 * check; both errors can fire on the same submit. The Java translation
 * preserves this behaviour by invoking
 * {@link com.blitzy.carddemo.application.util.DateValidator} twice in
 * sequence with no early return; both
 * {@link com.blitzy.carddemo.domain.validation.DateValidationWork.DateValidationResult}
 * outcomes are folded into the response message before the screen is
 * re-displayed. The captured fixture's {@code input_scenario.txt} must
 * exercise both the single-date-error (one CSUTLDTC failure) and the
 * dual-date-error (both CSUTLDTC failures) paths to maximize coverage.</p>
 *
 * <h2>CARDXREF AIX Lookup &mdash; Both Paths (acctId and cardNumber)</h2>
 *
 * <p>The COBOL program supports TWO lookup paths into the {@code CXACAIX}
 * alternate-index secondary key on the {@code CCXREF} file
 * ({@code WS-CXACAIX-FILE} and {@code WS-CCXREF-FILE} at
 * {@code app/cbl/COTRN02C.cbl:L41-L42}):</p>
 * <ul>
 *   <li><strong>By account id</strong>: when the operator types a value
 *       into {@code ACTIDINI}, the program performs
 *       {@code EXEC CICS READ DATASET(WS-CXACAIX-FILE)} with the
 *       11-character {@code WS-ACCT-ID-N} as the AIX key. A
 *       {@code DFHRESP(NORMAL)} resolves the corresponding card number.
 *       Misses emit {@code 'Account ID NOT found...'} at COBOL L593.</li>
 *   <li><strong>By card number</strong>: when the operator types a value
 *       into {@code CARDNINI} (or when {@code ACTIDINI} is blank), the
 *       program performs {@code EXEC CICS READ DATASET(WS-CCXREF-FILE)}
 *       with the 16-character {@code WS-CARD-NUM-N} as the primary key.
 *       A {@code DFHRESP(NORMAL)} resolves the corresponding account id.
 *       Misses emit {@code 'Card Number NOT found...'} at COBOL L626.</li>
 * </ul>
 *
 * <p>Per the cross-field check at COBOL L226 the operator MUST supply
 * one or the other (or both consistent); if both are blank the verbatim
 * {@code 'Account or Card Number must be entered...'} message fires.
 * The captured fixture's {@code input_scenario.txt} exercises BOTH
 * lookup paths, the cross-field empty error, AND the two
 * {@code NOT found} miss paths to fully cover the AIX/CCXREF dispatch.
 * The {@code cardxref.txt} auxiliary fixture at
 * {@code app/data/ASCII/cardxref.txt} drives both paths from the same
 * port adapter (the file-backed implementation of
 * {@link com.blitzy.carddemo.domain.port.CardXrefRepository}).</p>
 *
 * <h2>STARTBR / READPREV / ENDBR &mdash; Next-Tran-Id Computation</h2>
 *
 * <p>Once all 21 validation checks pass and the CARDXREF AIX lookup
 * succeeds, the COBOL program computes the next sequential transaction
 * id by browsing the {@code TRANSACT} KSDS in REVERSE: it positions to
 * the end of the file via {@code EXEC CICS STARTBR DATASET(
 * WS-TRANSACT-FILE) RIDFLD(HIGH-VALUES) GTEQ}, reads ONE record
 * backwards via {@code EXEC CICS READPREV DATASET(WS-TRANSACT-FILE)}
 * &mdash; which retrieves the highest existing {@code TRAN-ID} as a
 * 16-character string &mdash; and closes the browse via
 * {@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE)}. The Java
 * translation collapses these three CICS verbs into the single port
 * call {@link com.blitzy.carddemo.domain.port.TransactionRepository}
 * {@code .findHighestId()}. The harness asserts that the next id
 * written to {@code transact.txt} is exactly {@code MAX(existing) + 1}
 * with the COBOL-padded 16-character format
 * ({@code TRAN-ID PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:§TRAN-RECORD} with the typical
 * zero-prefixed numeric encoding documented in the
 * {@link com.blitzy.carddemo.domain.record.TranRecord} record).</p>
 *
 * <h2>WRITE Disposition &mdash; NORMAL, DUPKEY/DUPREC, OTHER</h2>
 *
 * <p>The {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)} EVALUATE at
 * {@code app/cbl/COTRN02C.cbl:L723-L749} dispatches on {@code WS-RESP-CD}
 * to three branches:</p>
 * <ul>
 *   <li><strong>{@code DFHRESP(NORMAL)}</strong> &mdash; the write
 *       succeeded; the program emits the verbatim success message
 *       described above (with DOUBLE space) and re-displays the screen
 *       with the new id. The {@code transact.txt} auxiliary fixture is
 *       extended with one new 350-byte record.</li>
 *   <li><strong>{@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}</strong>
 *       &mdash; the primary key collided; the program emits the verbatim
 *       {@code 'Tran ID already exist...'} message at COBOL L738 and
 *       re-displays the screen WITHOUT appending to {@code transact.txt}.
 *       Note the WHEN clauses fall through (COBOL idiom &mdash; both
 *       resps share the same WHEN list).</li>
 *   <li><strong>WHEN OTHER</strong> &mdash; any other RESP value emits a
 *       {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} (the only
 *       DISPLAY in the COBOL source for COTRN02C) followed by the
 *       verbatim {@code 'Unable to Add Transaction...'} message at L745.
 *       The captured {@code stdout.txt} preserves the DISPLAY values for
 *       parity.</li>
 * </ul>
 *
 * <h2>AID-Key Dispatch (Enter, PF3, PF4)</h2>
 *
 * <p>The COBOL EIBAID EVALUATE at {@code app/cbl/COTRN02C.cbl:L122-L155}
 * accepts three AID values plus a {@code WHEN OTHER} catch-all:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; invoke
 *       {@code PROCESS-ENTER-KEY}: run all 21 validation checks; on
 *       success perform CARDXREF AIX lookup, compute next tran-id, and
 *       WRITE the TRANSACT record.</li>
 *   <li><strong>PF3 (Back)</strong> &mdash; emit
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-FROM-PROGRAM)} defaulting
 *       to {@code COMEN01C} (the main menu) when blank, matching the
 *       sibling {@code COTRN01C} PF3 convention.</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; invoke
 *       {@code CLEAR-CURRENT-SCREEN} which {@code PERFORM}s
 *       {@code INITIALIZE-ALL-FIELDS} (MOVE SPACES to all 14 detail
 *       fields plus {@code ACTIDINI}/{@code CARDNINI}) and re-SEND-MAPs
 *       an empty entry screen.</li>
 *   <li><strong>WHEN OTHER</strong> &mdash; any AID other than ENTER /
 *       PF3 / PF4 emits the verbatim {@code CCDA-MSG-INVALID-KEY}
 *       ({@code 'Invalid key pressed. Please see below...'}) at COBOL
 *       L150 and re-displays the screen.</li>
 * </ul>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cotrn02c/expected/} encodes a sequence
 * of terminal submissions exercising the full state space of the
 * add-transaction online program (per AAP &sect;0.6.11 the scenario
 * MUST include one row per validation error to maximize coverage):</p>
 * <ol>
 *   <li><strong>Initial empty entry</strong> ({@code EIBCALEN = 0}, no
 *       commarea) &mdash; SEND MAP shows an empty entry screen.</li>
 *   <li><strong>Valid new transaction by acctId</strong> &mdash; all 21
 *       validations pass; CARDXREF AIX lookup by acctId resolves to a
 *       known card; STARTBR/READPREV/ENDBR computes next id; WRITE
 *       NORMAL succeeds; the success message with DOUBLE space appears
 *       in {@code stdout.txt}; the new record appears appended in
 *       {@code transact.txt}.</li>
 *   <li><strong>Valid new transaction by cardNumber</strong> &mdash;
 *       the alternative lookup path; CARDXREF lookup by cardNumber
 *       resolves to a known acctId; otherwise identical to the acctId
 *       path.</li>
 *   <li><strong>Each of the 17 distinct validation errors</strong>
 *       &mdash; one submit per error verbatim, in the COBOL paragraph
 *       order, with the trailing {@code ...} ellipsis preserved
 *       byte-for-byte.</li>
 *   <li><strong>CSUTLDTC failure on origin date</strong>
 *       ({@code TORIGDTI} format violation against the
 *       {@code YYYY-MM-DD} regex).</li>
 *   <li><strong>CSUTLDTC failure on processing date</strong>
 *       ({@code TPROCDTI} format violation; verifies the second
 *       CSUTLDTC call is independent of the first).</li>
 *   <li><strong>CSUTLDTC failure on BOTH dates simultaneously</strong>
 *       &mdash; verifies the COBOL non-short-circuit semantics: both
 *       errors fire on the same submit.</li>
 *   <li><strong>{@code Account ID NOT found}</strong> &mdash; AIX
 *       lookup miss path (L593).</li>
 *   <li><strong>{@code Card Number NOT found}</strong> &mdash; CCXREF
 *       lookup miss path (L626).</li>
 *   <li><strong>DUPKEY / DUPREC on WRITE</strong> &mdash; emits the
 *       verbatim {@code 'Tran ID already exist...'} at L738; no
 *       append to {@code transact.txt}.</li>
 *   <li><strong>PF3 (Back)</strong> &mdash; the outcome is an XCTL to
 *       {@code COMEN01C} (main menu, the default when
 *       {@code CDEMO-FROM-PROGRAM} is blank).</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; blanks all 14 detail
 *       fields plus {@code ACTIDINI}/{@code CARDNINI} and re-displays
 *       an empty entry screen.</li>
 *   <li><strong>Invalid AID key</strong> (e.g.&nbsp;PF1 or PA1) on a
 *       populated screen &mdash; the {@code WHEN OTHER} branch emits
 *       {@code CCDA-MSG-INVALID-KEY}
 *       ({@code 'Invalid key pressed. Please see below...'}) and
 *       re-displays the screen.</li>
 * </ol>
 *
 * <p>Each submission produces a serialized {@code CoTrn02Output} screen
 * state plus zero or more SLF4J log lines plus zero or one appended
 * 350-byte CVTRA05Y record to {@code transact.txt}; the harness
 * concatenates all screen states into {@code bms_output.txt}, all log
 * lines into {@code stdout.txt}, and captures the post-run state of
 * {@code transact.txt} for byte-for-byte comparison.</p>
 *
 * <h2>Auxiliary Input Fixtures (Six Files)</h2>
 *
 * <p>COTRN02C has the BROADEST collaborator surface of any online
 * program in the CardDemo application &mdash; it reads SIX auxiliary
 * fixtures via five port adapters:</p>
 * <ul>
 *   <li>{@code app/data/ASCII/acctdata.txt} &mdash; ACCTDATA file
 *       ({@code WS-ACCTDAT-FILE} at COBOL L40); 300-byte CVACT01Y
 *       account records for context (the acctId resolved by the CARDXREF
 *       AIX lookup must exist in this file).</li>
 *   <li>{@code app/data/ASCII/carddata.txt} &mdash; CARDDATA file;
 *       150-byte CVACT02Y card records for context (the cardNumber
 *       resolved by the CCXREF lookup must exist).</li>
 *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; CARDXREF file with
 *       its CXACAIX alternate index ({@code WS-CCXREF-FILE} at COBOL
 *       L41 and {@code WS-CXACAIX-FILE} at COBOL L42); 50-byte CVACT03Y
 *       records driving BOTH lookup paths (by acctId via the AIX, by
 *       cardNumber via the primary key).</li>
 *   <li>{@code app/data/ASCII/trantype.txt} &mdash; TRANTYPE file;
 *       transaction-type codes (TRAN-TYPE-CD) validated by paragraph
 *       {@code VALIDATE-INPUT-DATA-FIELDS}.</li>
 *   <li>{@code app/data/ASCII/trancatg.txt} &mdash; TRANCATG file;
 *       transaction-category codes (TRAN-CAT-CD) validated by the same
 *       paragraph.</li>
 *   <li>{@code app/data/ASCII/dailytran.txt} &mdash; DALYTRAN file
 *       (sibling of TRANSACT with the same 350-byte CVTRA05Y/CVTRA06Y
 *       layout); used as the surrogate for the TRANSACT KSDS that
 *       COTRN02C reads via STARTBR/READPREV/ENDBR and writes new
 *       records to. The harness's {@code runProgram(...)} hook copies
 *       this fixture into a temp directory before the run so the
 *       immutable {@code app/data/ASCII/} fixtures are never mutated;
 *       the post-run copy is captured for the {@code transact.txt}
 *       byte-parity comparison.</li>
 * </ul>
 *
 * <h2>PAN Masking Verification (AAP &sect;0.7.2)</h2>
 *
 * <p>The captured fixtures verify two separate code paths with different
 * PAN visibility, mirroring the
 * {@link com.blitzy.carddemo.application.transaction.CoTrn01C}
 * convention:</p>
 * <ul>
 *   <li>{@code stdout.txt} &mdash; SLF4J / DISPLAY trace. Per AAP
 *       &sect;0.7.2 ("no card PAN logged in full; mask all but last 4
 *       digits in logs and error messages"), every log line mentioning
 *       a card number must show only the last 4 digits (12 leading mask
 *       characters such as {@code "************1234"}). The Java
 *       translation routes PAN through the {@code maskPan} helper on
 *       {@link com.blitzy.carddemo.application.transaction.CoTrn02C}
 *       before SLF4J emission; the captured stdout fixture asserts the
 *       masked form byte-for-byte.</li>
 *   <li>{@code bms_output.txt} and {@code transact.txt} &mdash; the BMS
 *       screen serialization and the TRANSACT record stream
 *       respectively. Both display / persist the FULL PAN
 *       ({@code TRAN-CARD-NUM PIC X(16)} from CVTRA05Y) because the BMS
 *       screen is an authorized rendering surface and the TRANSACT
 *       record is the canonical source of truth for downstream
 *       reporting. Storage and logging are different surfaces per AAP
 *       &sect;0.1.1 surfaced implicit requirement.</li>
 * </ul>
 *
 * <h2>Expected Outputs (Three-Way Multi-Output Scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the single-output
 * default), this test declares THREE byte-for-byte parity targets
 * &mdash; the highest output count of any online-program test in the
 * harness, reflecting COTRN02C's role as the canonical write-side
 * online program:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the PAN-masked SLF4J/DISPLAY trace.
 *       Verifies the AAP &sect;0.7.2 logging policy (last 4 digits
 *       only) plus the verbatim 17 validation errors plus the success
 *       message with DOUBLE space plus the DUPKEY message plus the
 *       WHEN-OTHER {@code DISPLAY 'RESP:' ... 'REAS:' ...} on the WRITE
 *       error branch.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@code CoTrn02Output} screen states (one per submission in the
 *       scenario) with FULL PAN. The BMS screen is an authorized
 *       rendering surface distinct from the logging surface.</li>
 *   <li>{@code transact.txt} &mdash; the post-run snapshot of the
 *       (copied) DALYTRAN file. After all NORMAL writes from the
 *       scenario, the file is extended with one 350-byte CVTRA05Y
 *       record per successful submission, in submission order. DUPKEY,
 *       OTHER, and validation-error submissions do NOT append; the
 *       file remains byte-identical to its pre-test state for those
 *       paths.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11 and the MOST COMPLEX online-program parity test in the
 * harness. Any deviation in the 17 verbatim validation error messages
 * (with trailing {@code ...} ellipsis), the DOUBLE-space success
 * message, the amount regex {@code ^[+-][0-9]{8}\.[0-9]{2}$} with
 * BigDecimal scale 2 and preserved trailing zeros, the date regex
 * {@code ^[0-9]{4}-[0-9]{2}-[0-9]{2}$} with CSUTLDTC=DateValidator
 * called twice independently, the CARDXREF AIX lookup tries-acctId-then-
 * tries-cardNumber order, the STARTBR/READPREV/ENDBR
 * MAX(existing)+1 next-id computation, the WRITE NORMAL/DUPKEY/OTHER
 * disposition catalog, the PF3/PF4 actions, the BigDecimal trailing-zero
 * preservation ({@code 1.20} NOT {@code 1.2}), or the PAN masking
 * boundary (masked in {@code stdout.txt}, full in {@code bms_output.txt}
 * and {@code transact.txt}) breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below is
 * annotated {@code @Disabled} with an 11-point invariant checklist
 * citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * under {@code src/test/resources/golden/cotrn02c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.transaction.CoTrn02C
 * @since 25
 */
@DisplayName("COTRN02C \u2014 Add Transaction Golden-Record Parity (17 validation errors)")
public class CoTrn02CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COTRN02C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cotrn02c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cotrn02c/expected/}. Encodes the
     * multi-submission sequence (empty entry; valid new tran by acctId;
     * valid new tran by cardNumber; one submit per each of the 17
     * distinct validation errors; CSUTLDTC failures on origin/processing
     * dates individually and jointly; CARDXREF/CCXREF lookup misses;
     * DUPKEY on WRITE; PF3 back; PF4 clear; invalid AID key) consumed
     * by the harness orchestrator to drive the CoTrn02C online-CICS
     * state machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cotrn02c/expected/}. Records the
     * PAN-masked SLF4J/DISPLAY emissions for the scenario (including
     * the {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} on the
     * {@code WHEN OTHER} branch of the WRITE-TRANSACT-FILE EVALUATE at
     * COBOL line 743). Per AAP &sect;0.7.2 every PAN reference in this
     * file shows the last 4 digits only (12 leading mask characters).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cotrn02c/expected/}. Records the
     * serialized {@code CoTrn02Output} screen states (one per
     * submission, concatenated in submission order). Per AAP
     * &sect;0.7.2 these records display FULL PAN because the BMS
     * screen is an authorized rendering surface distinct from the
     * SLF4J logging surface.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the captured TRANSACT file under
     * {@code src/test/resources/golden/cotrn02c/expected/}. Records the
     * post-run state of the (copied) DALYTRAN fixture: the pre-run
     * content of {@code app/data/ASCII/dailytran.txt} extended with one
     * 350-byte CVTRA05Y record per successful WRITE NORMAL in the
     * scenario, in submission order. DUPKEY, OTHER, and
     * validation-error submissions do NOT append; the file remains
     * byte-identical to its pre-test state for those paths.
     */
    private static final String TRANSACT_TXT = "transact.txt";

    /**
     * Name of the ACCTDATA baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.AccountRepository} used
     * for context (the acctId resolved by the CARDXREF AIX lookup must
     * exist in this file). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String ACCTDATA_TXT = "acctdata.txt";

    /**
     * Name of the CARDDATA baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardRepository} used for
     * context (the cardNumber resolved by the CCXREF lookup must exist
     * in this file). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied per AAP &sect;0.4.1.
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the CARDXREF baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
     * which COTRN02C reads via BOTH the CXACAIX alternate index (by
     * acctId) AND the CCXREF primary key (by cardNumber). Read
     * directly via {@link GoldenRecordTest#resolveAppDataPath(String)}
     * &mdash; NOT copied per AAP &sect;0.4.1.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * Name of the TRANTYPE baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.TransactionTypeRepository}
     * used by paragraph {@code VALIDATE-INPUT-DATA-FIELDS} to validate
     * the {@code TTYPCDI} input. Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied per AAP &sect;0.4.1.
     */
    private static final String TRANTYPE_TXT = "trantype.txt";

    /**
     * Name of the TRANCATG baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.TransactionCategoryRepository}
     * used by paragraph {@code VALIDATE-INPUT-DATA-FIELDS} to validate
     * the {@code TCATCDI} input. Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied per AAP &sect;0.4.1.
     */
    private static final String TRANCATG_TXT = "trancatg.txt";

    /**
     * Name of the DALYTRAN baseline fixture under
     * {@code app/data/ASCII/}. The 350-byte CVTRA05Y/CVTRA06Y records
     * surrogate for the TRANSACT KSDS that COTRN02C reads via
     * STARTBR/READPREV/ENDBR and writes new records to. Backs the
     * {@link com.blitzy.carddemo.domain.port.TransactionRepository}
     * constructor dependency injected into
     * {@link com.blitzy.carddemo.application.transaction.CoTrn02C}.
     * Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} (the
     * harness's {@code runProgram(...)} hook copies the file into a
     * temp directory before the run so the immutable
     * {@code app/data/ASCII/} fixtures are never mutated); the
     * post-run copy is captured for the {@code transact.txt}
     * byte-parity comparison.
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.transaction.CoTrn02C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests} declares
     * a test-scope dependency on {@code carddemo-application}
     * (transitively via {@code carddemo-app}) in
     * {@code java/carddemo-tests/pom.xml} per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.transaction.CoTrn02C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cotrn02c/expected/input_scenario.txt}
     * via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This synthesized scenario file encodes the multi-submission
     * sequence (empty entry; valid new transaction by acctId; valid
     * new transaction by cardNumber; one submit per each of the 17
     * distinct validation errors; CSUTLDTC failures on origin/processing
     * dates individually and jointly; CARDXREF/CCXREF lookup misses;
     * DUPKEY on WRITE; PF3 back; PF4 clear; invalid AID key) for the
     * online-CICS pseudo-conversational test; it lives alongside the
     * expected outputs under the per-program {@code cotrn02c/}
     * subtree because it is a harness-internal fixture (not part of
     * the immutable {@code app/data/ASCII/} dataset).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_SCENARIO_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at
     * {@code src/test/resources/golden/cotrn02c/expected/stdout.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is retained for harness backward compatibility (single-output
     * convention); the actual byte-for-byte parity assertions iterate
     * the three-element list returned by {@link #expectedOutputs()}
     * rather than this single path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable six-element {@link List} of auxiliary
     * input fixture paths reflecting COTRN02C's broad collaborator
     * surface (the highest of any online program in the CardDemo
     * application):</p>
     * <ol>
     *   <li>{@code app/data/ASCII/acctdata.txt} &mdash; ACCTDATA file
     *       ({@code WS-ACCTDAT-FILE} at COBOL L40); 300-byte CVACT01Y
     *       account records.</li>
     *   <li>{@code app/data/ASCII/carddata.txt} &mdash; CARDDATA file;
     *       150-byte CVACT02Y card records.</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; CARDXREF file
     *       with the CXACAIX alternate index
     *       ({@code WS-CCXREF-FILE}/{@code WS-CXACAIX-FILE} at COBOL
     *       L41-L42); 50-byte CVACT03Y records driving both lookup
     *       paths (by acctId via AIX, by cardNumber via primary key).</li>
     *   <li>{@code app/data/ASCII/trantype.txt} &mdash; TRANTYPE file;
     *       transaction-type codes validated by
     *       {@code VALIDATE-INPUT-DATA-FIELDS}.</li>
     *   <li>{@code app/data/ASCII/trancatg.txt} &mdash; TRANCATG file;
     *       transaction-category codes validated by the same
     *       paragraph.</li>
     *   <li>{@code app/data/ASCII/dailytran.txt} &mdash; DALYTRAN file
     *       (surrogate for the TRANSACT KSDS that COTRN02C reads via
     *       STARTBR/READPREV/ENDBR and writes new records to).</li>
     * </ol>
     *
     * <p>All six fixtures are read via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} per AAP
     * &sect;0.4.1 and &sect;0.6.11; NOT copied into this module. The
     * harness's {@code runProgram(...)} hook copies the DALYTRAN
     * fixture into a temp directory before the run so any WRITE
     * appends do NOT pollute the immutable {@code app/data/ASCII/}
     * fixtures; the post-run copy is captured for the
     * {@code transact.txt} byte-parity comparison.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(ACCTDATA_TXT),
            resolveAppDataPath(CARDDATA_TXT),
            resolveAppDataPath(CARDXREF_TXT),
            resolveAppDataPath(TRANTYPE_TXT),
            resolveAppDataPath(TRANCATG_TXT),
            resolveAppDataPath(DAILYTRAN_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the three byte-for-byte parity targets for COTRN02C
     * &mdash; the highest output count of any online-program test in
     * the harness, reflecting COTRN02C's role as the canonical
     * write-side online program. Overriding this method (rather than
     * relying on the base class's single-output default) is the AAP
     * &sect;0.6.11 idiom for multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2. Every
     *       PAN reference is masked to its last 4 digits before
     *       emission (12 leading mask characters such as
     *       {@code "************1234"}). Captures the 17 verbatim
     *       validation errors, the DOUBLE-space success message, the
     *       DUPKEY message, and the WHEN-OTHER
     *       {@code DISPLAY 'RESP:' ... 'REAS:' ...} on the WRITE error
     *       branch.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoTrn02Output} screen states (one
     *       per submission in the scenario) with FULL PAN. The BMS
     *       screen is an authorized rendering surface distinct from
     *       the logging surface; PAN masking does NOT apply to this
     *       output per AAP &sect;0.7.2.</li>
     *   <li>{@link #TRANSACT_TXT} ({@code transact.txt}) &mdash; the
     *       post-run snapshot of the (copied) DALYTRAN fixture. After
     *       all NORMAL writes from the scenario, the file is extended
     *       with one 350-byte CVTRA05Y record per successful
     *       submission, in submission order. DUPKEY, OTHER, and
     *       validation-error submissions do NOT append; the file
     *       remains byte-identical to its pre-test state for those
     *       paths. BigDecimal scale 2 trailing zeros MUST be
     *       preserved in the encoded {@code TRAN-AMT}
     *       ({@code PIC S9(09)V99} at
     *       {@code app/cpy/CVTRA05Y.cpy:§TRAN-RECORD}) per AAP
     *       &sect;0.6.1; {@code 1.20} MUST NOT collapse to
     *       {@code 1.2} or this output diverges.</li>
     * </ol>
     *
     * <p>Returned list is
     * {@link List#of(Object, Object, Object)} immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT)),
            new ExpectedOutput(TRANSACT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, TRANSACT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL COTRN02C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cotrn02c/expected/} per the
     * capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * dependencyManagement per AAP &sect;0.5.1), the JUnit Platform's
     * annotation lookup does NOT inherit {@code @Test} when a subclass
     * overrides a parent's {@code @Test}-annotated method &mdash;
     * running surefire with {@code -Dtest=CoTrn02CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdSlCGoldenTest}, {@link CoCrdUpCGoldenTest},
     * {@link CoTrn00CGoldenTest}, and {@link CoTrn01CGoldenTest}.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook, or
     *                   any {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL COTRN02C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 11 invariants must hold before "
            + "removing @Disabled): "
            + "(1) All 17 distinct validation errors emit verbatim with "
            + "trailing '...' ellipsis preserved per AAP \u00a70.7.1 "
            + "(Account ID must be Numeric...; Card Number must be "
            + "Numeric...; Account or Card Number must be entered...; "
            + "Type CD can NOT be empty...; Category CD can NOT be "
            + "empty...; Source can NOT be empty...; Description can NOT "
            + "be empty...; Amount can NOT be empty...; Orig Date can NOT "
            + "be empty...; Proc Date can NOT be empty...; Merchant ID "
            + "can NOT be empty...; Merchant Name can NOT be empty...; "
            + "Merchant City can NOT be empty...; Merchant Zip can NOT "
            + "be empty...; Type CD must be Numeric...; Category CD must "
            + "be Numeric...; Merchant ID must be Numeric...). Mixed-case "
            + "'can NOT' and abbreviated 'Orig'/'Proc' preserved per "
            + "COBOL source. "
            + "(2) Success message preserves the DOUBLE space between "
            + "'successfully.' and 'Your': 'Transaction added "
            + "successfully.  Your Tran ID is X.' (constructed by "
            + "COBOL STRING at app/cbl/COTRN02C.cbl:L728-L733 "
            + "concatenating 'Transaction added successfully. ' with "
            + "' Your Tran ID is ' &mdash; the trailing space of the "
            + "first literal plus the leading space of the second "
            + "produce the two-space sequence). ANY normalization to a "
            + "single space breaks byte parity. "
            + "(3) Amount regex ^[+-][0-9]{8}\\.[0-9]{2}$ enforced "
            + "matching WS-TRAN-AMT PIC +99999999.99 at COBOL L53; "
            + "BigDecimal scale = 2 with MathContext.DECIMAL128 and "
            + "explicit setScale(2, RoundingMode.UNNECESSARY) per AAP "
            + "\u00a70.6.1; trailing zeros preserved (+00000001.20 NOT "
            + "+00000001.2); no double or float anywhere on the monetary "
            + "code path. "
            + "(4) Date regex ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ enforced "
            + "matching WS-DATE-FORMAT 'YYYY-MM-DD' at COBOL L60; "
            + "CSUTLDTC=DateValidator routed through LocalDate.parse "
            + "with ResolverStyle.STRICT per AAP \u00a70.6.4; invoked "
            + "TWICE per submit (once for TORIGDTI origin date, once "
            + "for TPROCDTI processing date) with no early return so "
            + "both errors can fire simultaneously on a single submit. "
            + "(5) CARDXREF AIX lookup tries acctId path FIRST via "
            + "WS-CXACAIX-FILE (the alternate index at COBOL L42) AND "
            + "FALLS BACK to cardNumber path via WS-CCXREF-FILE (the "
            + "primary key at COBOL L41) when acctId is blank; either "
            + "path succeeds when the operator supplies one or the "
            + "other; the cross-field empty check at COBOL L226 emits "
            + "'Account or Card Number must be entered...' when both "
            + "are blank. Both NOT-found error paths (L593 acctId, L626 "
            + "cardNumber) are exercised by the scenario. "
            + "(6) STARTBR/READPREV/ENDBR collapses into the single "
            + "TransactionRepository.findHighestId() port call; the "
            + "computed next id MUST equal MAX(existing) + 1 with the "
            + "16-character TRAN-ID PIC X(16) padding from CVTRA05Y; "
            + "any divergence from the COBOL increment direction "
            + "(MAX+1 not 0001) or padding (zero-prefixed not "
            + "space-prefixed) breaks byte parity in transact.txt. "
            + "(7) WRITE NORMAL appends ONE 350-byte CVTRA05Y record to "
            + "transact.txt per successful submission, in submission "
            + "order; the encoded TRAN-AMT preserves BigDecimal scale 2 "
            + "trailing zeros; the DOUBLE-space success message fires "
            + "in stdout.txt; the new id appears in bms_output.txt. "
            + "(8) WRITE DUPKEY emits 'Tran ID already exist...' at "
            + "COBOL L738 without appending to transact.txt; the WHEN "
            + "clauses for DFHRESP(DUPKEY) and DFHRESP(DUPREC) fall "
            + "through to the same handler per COBOL idiom at L735-L741. "
            + "WHEN-OTHER emits the DISPLAY 'RESP:' WS-RESP-CD 'REAS:' "
            + "WS-REAS-CD at L743 (captured in stdout.txt) followed by "
            + "'Unable to Add Transaction...' at L745. "
            + "(9) PF3 (Back) emits Outcome.Xctl with target "
            + "CDEMO-FROM-PROGRAM defaulting to COMEN01C (main menu) "
            + "when blank; PF4 (Clear) invokes CLEAR-CURRENT-SCREEN "
            + "which PERFORMs INITIALIZE-ALL-FIELDS (MOVE SPACES to "
            + "all 14 detail fields plus ACTIDINI/CARDNINI) and "
            + "re-SEND-MAPs an empty entry screen; WHEN-OTHER AID emits "
            + "CCDA-MSG-INVALID-KEY 'Invalid key pressed. Please see "
            + "below...' at COBOL L150. "
            + "(10) BigDecimal trailing-zero preservation enforced "
            + "throughout: any monetary value of scale 2 MUST encode as "
            + "exactly two decimal digits (e.g., +00000001.20 NOT "
            + "+00000001.2); no BigDecimal.stripTrailingZeros() or "
            + "equivalent normalization on the encode path per AAP "
            + "\u00a70.1.3 surfaced implicit requirement. "
            + "(11) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in FULL in bms_output.txt "
            + "(the BMS screen is an authorized rendering surface) AND "
            + "in transact.txt (the canonical CVTRA05Y record stream "
            + "for downstream reporting). Storage and logging are "
            + "different surfaces per AAP \u00a70.1.1 surfaced implicit "
            + "requirement."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
