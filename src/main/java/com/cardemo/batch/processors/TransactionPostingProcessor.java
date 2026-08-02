/*
 * ******************************************************************
 * Component   : TransactionPostingProcessor.java
 * Application : CardDemo
 * Type        : Spring Batch ItemProcessor (Java 25 / Spring Boot 3.5.11)
 * Function    : Daily transaction validation cascade and posting.
 * Source      : app/cbl/CBTRN02C.cbl (731 lines, 27 paragraphs) @ 7756d89
 *               app/jcl/POSTTRAN.jcl - DALYREJS LRECL=430
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
package com.cardemo.batch.processors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * The per-record validation cascade and posting logic of the daily transaction posting job, translated
 * paragraph by paragraph from {@code app/cbl/CBTRN02C.cbl} - 731 lines, 27 paragraphs, read at commit
 * {@code 7756d89}.
 *
 * <h2>What it does</h2>
 * <p>
 * For one 350-byte daily-transaction staging row it reproduces exactly what
 * {@code app/cbl/CBTRN02C.cbl:L205-L216} does for one {@code DALYTRAN} record: it resets the validation
 * reason code, runs the two-lookup validation cascade, and then either posts the transaction across three
 * relations in one unit of work or classifies the record as rejected. It returns a {@link PostingResult}
 * describing which of those two things happened. It never decides where the record is written, never
 * assembles the 430-byte reject image, and never throws for a business rejection.
 * <p>
 * The class is the direct Java form of the shaded region of the legacy main loop. The loop itself, the six
 * {@code OPEN}s, the six {@code CLOSE}s, the sequential {@code READ}, the reject write and the end-of-run
 * counters belong to the job and the writers, not here; the disposition table below names every one of the
 * 27 paragraphs so that the traceability audit can see that each was considered.
 *
 * <h2>Paragraph disposition - all 27 paragraphs of app/cbl/CBTRN02C.cbl</h2>
 * <p>
 * AAP transformation rule 2 requires one private Java method per source paragraph with no consolidation
 * across paragraphs. The ten paragraphs that constitute per-record processing are translated here, one
 * method each. The other seventeen are named with their reason for living elsewhere, because "not
 * translated" and "not considered" must not look the same to a reviewer.
 * <table border="1">
 * <caption>Paragraph to method mapping</caption>
 * <tr><th>Paragraph</th><th>Lines</th><th>Disposition</th></tr>
 * <tr><td>{@code FILE-CONTROL}</td><td>28-60</td>
 *     <td>Six DD-to-dataset bindings; replaced by the four injected repositories and by
 *         {@code app/jcl/POSTTRAN.jcl:L28-L42}. Not a paragraph of executable logic.</td></tr>
 * <tr><td>{@code 0000-DALYTRAN-OPEN}</td><td>236-252</td><td>Step scope. The canonical I/O guard idiom;
 *     its Java form is {@link FileStatusMapper#requireSuccess(String, String, String)}.</td></tr>
 * <tr><td>{@code 0100-TRANFILE-OPEN}</td><td>254-271</td><td>Step scope, as above.</td></tr>
 * <tr><td>{@code 0200-XREFFILE-OPEN}</td><td>273-289</td><td>Step scope, as above.</td></tr>
 * <tr><td>{@code 0300-DALYREJS-OPEN}</td><td>291-307</td><td>Step scope; the reject sink is opened by the
 *     reject writer.</td></tr>
 * <tr><td>{@code 0400-ACCTFILE-OPEN}</td><td>309-325</td><td>Step scope, as above.</td></tr>
 * <tr><td>{@code 0500-TCATBALF-OPEN}</td><td>327-343</td><td>Step scope, as above.</td></tr>
 * <tr><td>{@code 1000-DALYTRAN-GET-NEXT}</td><td>345-369</td><td>Reader scope. Maps {@code '00'} to 0 and
 *     {@code '10'} to 16, so end of file terminates the loop and is never an exception; the Java form is
 *     {@link FileStatusMapper#requireSuccessOrEndOfFile(String, String, String)}.</td></tr>
 * <tr><td>{@code 1500-VALIDATE-TRAN}</td><td>370-378</td><td>{@link #validateTran(DailyTransaction)}</td></tr>
 * <tr><td>{@code 1500-A-LOOKUP-XREF}</td><td>380-392</td><td>{@link #lookupXref(DailyTransaction)}</td></tr>
 * <tr><td>{@code 1500-B-LOOKUP-ACCT}</td><td>393-422</td>
 *     <td>{@link #lookupAcct(DailyTransaction, CardCrossReference)}</td></tr>
 * <tr><td>{@code 2000-POST-TRANSACTION}</td><td>424-444</td>
 *     <td>{@link #postTransaction(DailyTransaction, CardCrossReference, Account)}</td></tr>
 * <tr><td>{@code 2500-WRITE-REJECT-REC}</td><td>446-465</td><td>Writer scope. Assembles the 430-byte
 *     record; this class only supplies the two trailer halves. See the reject contract below.</td></tr>
 * <tr><td>{@code 2700-UPDATE-TCATBAL}</td><td>467-501</td>
 *     <td>{@link #updateTcatbal(DailyTransaction, CardCrossReference)}</td></tr>
 * <tr><td>{@code 2700-A-CREATE-TCATBAL-REC}</td><td>503-524</td>
 *     <td>{@link #createTcatbalRec(DailyTransaction, TransactionCategoryBalanceId)}</td></tr>
 * <tr><td>{@code 2700-B-UPDATE-TCATBAL-REC}</td><td>526-542</td>
 *     <td>{@link #updateTcatbalRec(DailyTransaction, TransactionCategoryBalance)}</td></tr>
 * <tr><td>{@code 2800-UPDATE-ACCOUNT-REC}</td><td>545-560</td>
 *     <td>{@link #updateAccountRec(DailyTransaction, Account)}</td></tr>
 * <tr><td>{@code 2900-WRITE-TRANSACTION-FILE}</td><td>562-579</td>
 *     <td>{@link #writeTransactionFile(Transaction)}</td></tr>
 * <tr><td>{@code 9000-DALYTRAN-CLOSE}</td><td>582-598</td><td>Step scope.</td></tr>
 * <tr><td>{@code 9100-TRANFILE-CLOSE}</td><td>600-617</td><td>Step scope.</td></tr>
 * <tr><td>{@code 9200-XREFFILE-CLOSE}</td><td>619-635</td><td>Step scope.</td></tr>
 * <tr><td>{@code 9300-DALYREJS-CLOSE}</td><td>637-653</td><td>Step scope.</td></tr>
 * <tr><td>{@code 9400-ACCTFILE-CLOSE}</td><td>655-672</td><td>Step scope.</td></tr>
 * <tr><td>{@code 9500-TCATBALF-CLOSE}</td><td>674-690</td><td>Step scope.</td></tr>
 * <tr><td>{@code Z-GET-DB2-FORMAT-TIMESTAMP}</td><td>692-705</td>
 *     <td>{@link #getDb2FormatTimestamp()}</td></tr>
 * <tr><td>{@code 9999-ABEND-PROGRAM}</td><td>707-712</td><td>Shared scope. Moves 999 into the abend code
 *     and calls the language-environment abend service; the Java form is
 *     {@link FatalProcessingException}, which already publishes {@code BATCH_ABEND_CODE} 999 and
 *     {@code BATCH_RETURN_CODE} 12. Neither literal is redeclared here.</td></tr>
 * <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td><td>714-731</td><td>Shared scope. The four-character status
 *     rendering has exactly one implementation tree-wide -
 *     {@link FileStatus#renderIoStatus04(String)} behind
 *     {@link FileStatusMapper#displayIoStatus(String)} - and it is not here.</td></tr>
 * </table>
 *
 * <h2>Inputs and output</h2>
 * <p>
 * <b>Input:</b> one {@link DailyTransaction}, the 350-byte {@code DALYTRAN-RECORD} of
 * {@code app/cpy/CVTRA06Y.cpy} staged into relation {@code daily_transaction}. Its field sequence is
 * identical to {@code app/cpy/CVTRA05Y.cpy} with {@code DALYTRAN-} prefixes, and it carries no version
 * column and no foreign key, because the legacy dataset is a physical sequential file
 * ({@code app/jcl/POSTTRAN.jcl:L30-L31}, {@code AWS.M2.CARDDEMO.DALYTRAN.PS}) with no referential
 * integrity of its own.
 * <p>
 * <b>Output:</b> exactly one {@link PostingResult}, never {@code null}. Returning {@code null} from a
 * Spring Batch {@code ItemProcessor} filters the item out of the chunk, and the source filters nothing:
 * every record either posts or is rejected, and both outcomes must reach the writer. The result carries
 * the originating {@link DailyTransaction} on both paths, because
 * {@code app/cbl/CBTRN02C.cbl:L447 MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} makes the input record the
 * first 350 bytes of the reject image.
 *
 * <h2>PARITY TRAP 1 - the unguarded 102 to 103 overwrite</h2>
 * <p>
 * At {@code app/cbl/CBTRN02C.cbl:L413} an {@code END-IF} closes the over-limit test and at {@code :L414}
 * an {@code IF} opens the expiry test. <b>Nothing sits between them</b> - no guard, no early exit, no
 * {@code ELSE}. Both tests assign into the same {@code WS-VALIDATION-FAIL-REASON} field, so when a record
 * is simultaneously over limit and past expiry, <b>103 overwrites 102 and exactly one reject record
 * bearing 103 is written</b>. {@link #lookupAcct(DailyTransaction, CardCrossReference)} reproduces this
 * with a single method-local variable and two unguarded sequential assignments. Guarding the second test
 * behind "if the reason is still zero", emitting two reject records, or accumulating a list of codes would
 * each diverge.
 *
 * <h2>PARITY TRAP 2 - the expiry test is a string comparison against the originating timestamp</h2>
 * <p>
 * {@code app/cbl/CBTRN02C.cbl:L414} reads
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}. Two things follow. It compares against the
 * <b>originating</b> timestamp, not the processing timestamp. And it is an alphanumeric comparison of a
 * {@code PIC X(10)} field against a ten-character reference modification of a {@code PIC X(26)} field, not
 * a date comparison: neither side is parsed, so a malformed value orders lexicographically rather than
 * raising. {@link #lookupAcct(DailyTransaction, CardCrossReference)} therefore uses
 * {@link String#compareTo(String)} over {@link #origTsDatePrefix(DailyTransaction)} and never constructs a
 * {@code LocalDate}.
 *
 * <h2>PARITY TRAP 3 - the retained ACCT-EXPIRAION-DATE misspelling</h2>
 * <p>
 * The field is spelled {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11} - the {@code T} of
 * "EXPIRATION" is missing - and it is used under that spelling at {@code app/cbl/CBTRN02C.cbl:L414}. The
 * misspelling is a field contract, not a typo to be corrected: {@link Account} exposes it as
 * {@link Account#getExpiraionDate()} and {@code src/main/resources/db/migration/V1__create_schema.sql}
 * declares the column as {@code acct_expiraion_date CHAR(10)}. Renaming it here would break both. It is
 * retained deliberately, as documented provenance.
 *
 * <h2>PARITY TRAP 4 - the over-limit formula is transcribed, not rearranged</h2>
 * <p>
 * {@code app/cbl/CBTRN02C.cbl:L403-L405} computes
 * {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} and {@code :L407} tests
 * {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}. The subtraction looks wrong until
 * {@code app/cbl/CBTRN02C.cbl:L550-L551} is read: a <b>negative</b> amount is added to the debit
 * accumulator, so {@code ACCT-CURR-CYC-DEBIT} legitimately holds negative values and subtracting it adds
 * their magnitude back. The expression is therefore transcribed exactly, in that order, with no algebraic
 * rearrangement, no folding into a single comparison and <b>no absolute value anywhere in the posting
 * path</b>.
 * <p>
 * A width note worth recording, severity Low. {@code WS-TEMP-BAL} is {@code PIC S9(09)V99}, that is
 * {@code NUMERIC(11,2)} at {@code app/cbl/CBTRN02C.cbl:L187}, while both operands it is computed from are
 * {@code PIC S9(10)V99}, that is {@code NUMERIC(12,2)}, at {@code app/cpy/CVACT01Y.cpy:L13-L14}. <b>The
 * COMPUTE target is one digit narrower than its operands</b>, so the legacy program can silently truncate
 * the high-order digit of an intermediate above 999,999,999.99 and then compare the truncated value
 * against the credit limit. {@link BigDecimal} has no fixed width and cannot truncate, so the Java form is
 * strictly safer than the source at that boundary. No record in
 * {@code app/data/ASCII/dailytran.txt} reaches it, so the difference is unobservable against the parity
 * fixture; it is recorded rather than relied upon.
 *
 * <h2>The scoped '00' OR '23' leniency</h2>
 * <p>
 * {@code app/cbl/CBTRN02C.cbl:L481} reads {@code IF  TCATBALF-STATUS = '00'  OR '23'} and is the only
 * literal {@code '23'} file-status test in the corpus outside {@code app/cbl/CBACT04C.cbl}. The leniency
 * is scoped to <b>that read alone</b>: the {@code WRITE} guard at {@code :L512} and the {@code REWRITE}
 * guard at {@code :L530} accept {@code '00'} only. This class routes the read decision through
 * {@link FileStatusMapper#requireCategoryBalanceReadSuccess(String)}, which is the sanctioned owner of
 * that carve-out, rather than testing statuses locally.
 * <p>
 * The two branches are kept as two methods because they are genuinely different: they guard different
 * verbs and log different text - {@code 'ERROR WRITING TRANSACTION BALANCE FILE'} at {@code :L520} versus
 * {@code 'ERROR REWRITING TRANSACTION BALANCE FILE'} at {@code :L538}. A native
 * {@code INSERT ... ON CONFLICT DO UPDATE} would collapse both distinctions and is not used.
 *
 * <h2>Side effects</h2>
 * <p>
 * On the validated path, and only on that path, three relations are written in this order:
 * {@code transaction_category_balance} first, then {@code account}, then {@code transaction} - the order of
 * {@code app/cbl/CBTRN02C.cbl:L440-L442}. On the rejected path nothing is written at all, matching
 * {@code :L211-L216} where {@code 2000-POST-TRANSACTION} is simply not performed. The input row is never
 * modified. Beyond the log stream there is no other observable effect.
 *
 * <h2>The transaction boundary, and why the annotation sits on the public method</h2>
 * <p>
 * The source commits the three writes independently. AAP transformation rule 13 collapses them into one
 * unit of work, so {@link #process(DailyTransaction)} carries
 * {@code @Transactional(rollbackFor = Exception.class)}.
 * <p>
 * <b>The annotation is deliberately on the public entry point rather than on the private
 * {@code postTransaction} paragraph method.</b> Spring's declarative transaction support is proxy-based: a
 * call from one method of a bean to another method of the same bean does not pass through the proxy, so an
 * annotation on an internally invoked method is silently ignored and no transaction is started. Annotating
 * the paragraph method would therefore have produced code that reads as transactional and is not - the
 * worst of the available outcomes. {@link #process(DailyTransaction)} is invoked by the chunk-oriented step
 * from outside the bean, so the proxy applies, and every one of the three writes is reached from it. The
 * write order is still preserved even though the commit is now atomic, because the order is observable in
 * the log stream that the boundary-parity gate compares.
 *
 * <h2>Two labelled DEVIATIONS, both destined for DECISION_LOG.md</h2>
 * <ol>
 * <li><b>DEVIATION 1 - the orphaned-write hazard is closed as a side effect.</b> In the source, a failed
 * account rewrite at {@code app/cbl/CBTRN02C.cbl:L554-L559} sets reason code 109 and falls straight
 * through to {@code 2900-WRITE-TRANSACTION-FILE}. Because the three writes are three independent commits,
 * that path leaves an <b>orphaned category-balance row and an orphaned transaction row</b> behind. The
 * single Java transaction rolls all three back together, so the hazard cannot occur. This is a genuine
 * behavioural <b>improvement, not parity</b>, and it is recorded as such rather than presented as
 * equivalence.</li>
 * <li><b>DEVIATION 2 - a failed account write aborts the record instead of continuing.</b> Following from
 * DEVIATION 1: where the source continues to the transaction write after a failed rewrite, this class
 * cannot, because a failed flush inside a JPA unit of work poisons that unit of work. The store failure is
 * translated into a typed exception preserving its cause and the record fails. The 109 assignment itself is
 * retained - see the next section.</li>
 * </ol>
 *
 * <h2>Reject code 109 is assigned and never consumed</h2>
 * <p>
 * {@code app/cbl/CBTRN02C.cbl:L556-L558} moves 109 and its description into the validation reason fields.
 * That path is reachable, so the constant is real code and {@link RejectCode} correctly holds exactly five
 * constants. But the value is never consumed as a reject outcome: {@code 2800-UPDATE-ACCOUNT-REC} runs only
 * from inside {@code 2000-POST-TRANSACTION}, which {@code :L211} enters only when the reason code was
 * already zero, and the assignment has <b>no status guard, no {@code 9910} and no {@code 9999}</b>. No
 * reject record is written, {@code WS-REJECT-COUNT} is not incremented, and {@code :L208} clears the field
 * on the next iteration. {@link #updateAccountRec(DailyTransaction, Account)} retains the assignment,
 * cites it, and marks it: the value reaches diagnostic text only. It never becomes a
 * {@link PostingResult} reject code, never increments the reject count and never produces a reject record.
 * <p>
 * This is the one place in this file where clause B1 of Rule 1, which forbids dead code, meets the parity
 * mandate, which requires reproducing reachable defect paths. <b>Parity governs</b>, because B1 forbids
 * <i>untracked</i> dead code and this artefact is cited, marked and logged in {@code DECISION_LOG.md}.
 *
 * <h2>Reject codes are business outcomes, never exceptions</h2>
 * <p>
 * {@code app/cbl/CBTRN02C.cbl:L229-L231} is the only {@code MOVE 4 TO RETURN-CODE} in the corpus, and it
 * fires <b>if and only if</b> {@code WS-REJECT-COUNT} exceeds zero. Nothing else determines return code 4.
 * A reject is therefore an ordinary outcome that must remain countable, so this class never throws for one:
 * it returns {@link PostingResult#rejected(DailyTransaction, RejectCode)} and lets the step count and
 * classify. Exceptions are reserved for conditions the source itself treats as fatal, where it displays a
 * message, renders the status through {@code 9910} and abends through {@code 9999}.
 *
 * <h2>The reject-record contract this class honours half of</h2>
 * <p>
 * {@code app/cbl/CBTRN02C.cbl:L176-L178} declares {@code REJECT-RECORD} as
 * {@code REJECT-TRAN-DATA PIC X(350)} plus {@code VALIDATION-TRAILER PIC X(80)}, and {@code :L180-L182}
 * decomposes the trailer into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. That is 430 bytes, independently confirmed by
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl:L36}.
 * <p>
 * <b>Assembling those 430 bytes belongs to {@code com.cardemo.batch.writers.RejectWriter}, not here.</b>
 * This class honours its half of the contract by exposing, on {@link PostingResult}, the four-digit reason
 * field, the 76-character description field and the originating record whose image is the first 350 bytes.
 * It renders no fixed-width record itself.
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 * <li><b>Clock.</b> The public constructor defaults to {@link Clock#systemDefaultZone()}. That is the
 * parity-preserving choice, not {@link Clock#systemUTC()}, because
 * {@code app/cbl/CBTRN02C.cbl:L693 MOVE FUNCTION CURRENT-DATE TO COBOL-TS} returns the local date and time
 * of the system the program runs on; reading it in another zone would stamp a value the source could not
 * have produced. It matches the precedent already set by {@code MainMenuService} and
 * {@code AdminMenuService}. Deployments needing a different zone set it for the process. Tests supply a
 * fixed clock through the package-private constructor.</li>
 * <li><b>Chunk size and the reader and writer types.</b> {@code Not available}. See the disclosure
 * below.</li>
 * <li><b>Rounding.</b> {@link RoundingMode#HALF_EVEN} at scale 2, applied wherever a monetary result is
 * rescaled, per AAP transformation rule 1. No {@code float} and no {@code double} appears anywhere in this
 * file, and monetary comparison uses {@link BigDecimal#compareTo(BigDecimal)} rather than
 * {@code equals}.</li>
 * <li><b>No metric is registered here.</b> Only four Micrometer instruments are sanctioned tree-wide -
 * records processed, records rejected tagged by reject code, authentication attempts and total transaction
 * amount - and {@code com.cardemo.observability.MetricsConfig} owns them. Registering a counter here would
 * duplicate that ownership and risk a fifth instrument, so this class instead exposes the reject code on
 * {@link PostingResult} so the sanctioned counter can be tagged by it, and confines its own observability
 * to the log stream. Reject codes are a five-value domain, so tagging by one is bounded; the card number
 * and the account identifier are not, and are never used as tags.</li>
 * </ul>
 *
 * <h2>How to build, run and test it</h2>
 * <p>
 * Build with {@code mvn -B clean compile}, which compiles this file under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} against release 25. Run the unit tier with {@code mvn -B clean test}. The
 * integration tier needs a container runtime because it stands up PostgreSQL 16 and LocalStack through
 * Testcontainers. {@code mvn -B clean verify} additionally runs the OWASP dependency check, which fails at
 * the CVSS 7 gate on the AAP-pinned dependency set for reasons unrelated to this file.
 * <p>
 * This class is deliberately shaped to be unit-testable without a container: its four repositories, its
 * status mapper and its clock are all constructor-injected, it holds no static mutable state, and every
 * decision it makes is reachable through {@link #process(DailyTransaction)}. Its test lives at
 * {@code src/test/java/com/cardemo/unit/batch/TransactionPostingProcessorTest.java} and is authored
 * separately. The named test obligation is the trap-1 case: drive one record that is simultaneously over
 * limit and past expiry and assert the resulting code is 103 - not 102, and not both.
 *
 * <h2>Error modes</h2>
 * <p>
 * The authoritative status-to-exception map, which {@link FileStatusMapper} owns and this class does not
 * reimplement: {@code '00'} continues; {@code '10'} is end of file and terminates the reader loop rather
 * than throwing; {@code '22'} raises {@link DuplicateRecordException}; {@code '23'} raises
 * {@code RecordNotFoundException} <i>except</i> at the three scoped leniency sites, of which
 * {@code app/cbl/CBTRN02C.cbl:L481} is the one reached from this class; {@code '35'} raises
 * {@code FileUnavailableException}; the {@code '9x'} family raises {@code FileAccessException} carrying the
 * four-character expanded status; anything else raises {@link FatalProcessingException} with abend code 999
 * and return code 12.
 * <p>
 * The store-level conditions this class translates, all preserving the original as cause, and none
 * swallowed:
 * <ul>
 * <li>{@link DuplicateKeyException} on the transaction insert - {@code pk_transaction} - or on the
 * category-balance insert - {@code pk_transaction_category_balance} - becomes
 * {@link DuplicateRecordException}.</li>
 * <li>{@link DataIntegrityViolationException} becomes {@link DataIntegrityException}. On
 * {@code transaction} the candidates declared by {@code V1__create_schema.sql} are
 * {@code fk04_transaction_card}, {@code fk05_transaction_type} and {@code fk06_transaction_category}; on
 * {@code transaction_category_balance} they are {@code fk07_tcatbal_account} and
 * {@code fk08_tcatbal_category}.</li>
 * <li>Any other {@link DataAccessException} becomes {@link FatalProcessingException}, which is the Java
 * form of the {@code 9999-ABEND-PROGRAM} arm of the guard idiom.</li>
 * <li>A structurally impossible input - a {@code null} item, or a {@code null} amount or originating
 * timestamp on a record whose columns are {@code NOT NULL} - becomes a typed exception rather than a
 * business reject, because inventing a reject code for a condition the 350-byte fixed-width source record
 * cannot express would fabricate output the legacy system never produced.</li>
 * </ul>
 * <p>
 * Three members of that map are deliberately never raised here, and their absence is a decision rather than
 * an omission. {@code RecordNotFoundException} has no site because the only {@code '23'} this program
 * tolerates is the category-balance read at {@code app/cbl/CBTRN02C.cbl:L481}, whose guard
 * {@link FileStatusMapper} owns, while a missing cross-reference and a missing account are reject codes 100
 * and 101 rather than exceptions. {@code FileUnavailableException} has no site because {@code '35'} answers
 * an {@code OPEN}, and every {@code OPEN} in this program lives in the step's paragraphs
 * ({@code :L236-L343}), not in per-record processing. {@code FileAccessException} has no site because it
 * exists to transport a <b>raw two-character {@code FILE STATUS}</b> and the four characters the legacy
 * renderer produced from it; a store failure arriving as a {@link DataAccessException} carries no such
 * status, and synthesising a {@code '9x'} value to satisfy the constructor would forge a
 * {@code FILE STATUS IS: NNNN} line that no legacy run ever emitted - which would corrupt the very baseline
 * comparison the rendering exists to serve. The honest translation of that arm is the abend the guard
 * actually reaches.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><em>Every record rejects with 100.</em> The cross-reference relation is empty or the card numbers do
 * not match. {@code app/cbl/CBTRN02C.cbl:L382-L383} reads {@code XREF-FILE} on its <b>primary</b> key
 * {@code FD-XREF-CARD-NUM}, so the seed must load {@code app/data/ASCII/cardxref.txt} before the posting
 * job runs. Check that {@code V3__seed_data.sql} applied.</li>
 * <li><em>Every record rejects with 103.</em> The account expiry dates and the originating timestamps are
 * not in the same lexical form. The comparison is a plain string ordering of two ten-character values, so
 * {@code 2022-01-01} and {@code 01/01/2022} do not order comparably. Both sides come from fixed-width
 * source data and must be loaded byte-exactly.</li>
 * <li><em>Amounts post with the wrong sign, or the over-limit test never fires.</em> The zoned-decimal
 * overpunch signs were normalised on load. {@code app/data/ASCII/dailytran.txt} column 143 censuses to 250
 * positive and 50 negative sign characters, so both arms of {@code :L548-L551} are genuinely exercised;
 * decoding must be position-aware from the PIC clauses, never a global text replacement.</li>
 * <li><em>A constraint violation names {@code fk08_tcatbal_category}.</em> The daily record carries a type
 * and category pair that {@code transaction_category} does not hold. The legacy VSAM dataset had no such
 * constraint, so this surfaces in Java earlier than it did on the mainframe; fix the seed rather than
 * relaxing the constraint.</li>
 * <li><em>The three writes are not rolling back together.</em> The transaction proxy is not applied.
 * Confirm that this bean is obtained from the context rather than constructed directly, and that no caller
 * reaches the posting logic other than through {@link #process(DailyTransaction)}.</li>
 * <li><em>A generated processing timestamp is not 26 characters.</em> The formatter was changed. It must
 * emit hundredths of a second followed by the four literal characters {@code 0000}; see
 * {@link #getDb2FormatTimestamp()}.</li>
 * </ul>
 *
 * <h2>Not available</h2>
 * <p>
 * Recorded explicitly rather than guessed, per clause F4 of Rule 1.
 * <ul>
 * <li><b>Not available:</b> the concrete reader and writer types and the chunk size of the posting step.
 * {@code com.cardemo.batch.jobs}, {@code com.cardemo.batch.readers} and {@code com.cardemo.batch.writers}
 * are unplanned in this branch, so no type name can be cited. What is needed is
 * {@code DailyTransactionPostingJob}, {@code DailyTransactionReader} and {@code RejectWriter}. This class
 * is deliberately independent of all three: it consumes one {@link DailyTransaction} and returns one
 * {@link PostingResult}, which is the whole of its contract.</li>
 * <li><b>Not available:</b> {@code com.cardemo.observability.MetricsConfig}, so the identifiers of the four
 * sanctioned counters cannot be cited. What is needed is that class. No instrument is created here in the
 * meantime.</li>
 * <li><b>Not available:</b> any service-level objective for this step. The legacy corpus publishes no
 * throughput or latency target anywhere, so none is asserted and none may be invented; the performance gate
 * records a measured baseline instead.</li>
 * <li><b>Not available:</b> the time zone of the z/OS system that ran {@code CBTRN02C}, which
 * {@code FUNCTION CURRENT-DATE} at {@code :L693} implicitly depended on. What is needed is the legacy
 * region configuration. The documented default is the deployment's own zone, chosen for the reason given
 * above.</li>
 * </ul>
 * <p>
 * Two inputs to this translation proved stale against the frozen corpus and are corrected here rather than
 * repeated. Both were re-established by direct inspection at {@code 7756d89}, and in each case the source
 * won.
 * <ul>
 * <li><b>Severity Medium - locator correction.</b> The over-limit {@code COMPUTE} is at
 * {@code app/cbl/CBTRN02C.cbl:L403-L405}, <b>not</b> at {@code L400-L402} as an upstream requirement stated.
 * {@code L401-L402} are commented-out {@code DISPLAY} statements. Every citation of the formula in this
 * class therefore reads {@code L403-L405}. Remediation, already applied: cite the verified lines. Anyone
 * re-deriving this file from the stale locator would document the wrong three lines and, worse, might
 * conclude the formula had moved and go looking for a different one.</li>
 * <li><b>Severity Low - stale absence claim.</b>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} <b>is present</b> in this branch, though an
 * upstream requirement listed it as unavailable. The column and constraint names cited above are therefore
 * read from real DDL rather than declared unavailable. Remediation, already applied: name the real
 * constraints, so a store failure reported by this class points at a constraint that actually exists.</li>
 * </ul>
 * <p>
 * A third divergence is environmental rather than textual and so is recorded in the delivery report rather
 * than here: the toolchain needed to compile this file is present on the host, so the compile evidence was
 * produced directly by the pinned {@code mvn} and JDK rather than through a container.
 *
 * <h2>Bean registration</h2>
 * <p>
 * Registered by {@link Component} scanning, like its sibling processor. It is stateless and therefore safe
 * as a singleton: every value that the legacy program held in {@code WORKING-STORAGE} -
 * {@code WS-VALIDATION-FAIL-REASON}, {@code WS-VALIDATION-FAIL-REASON-DESC},
 * {@code WS-CREATE-TRANCAT-REC} and {@code WS-TEMP-BAL} - is a method-local here, which is the natural Java
 * form of the per-record reset at {@code app/cbl/CBTRN02C.cbl:L208-L209}. The two counters,
 * {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT} at {@code :L185-L186}, are step-scoped and live
 * with the job. Every field of this class is {@code final} and there is no static mutable state.
 */
@Component
public class TransactionPostingProcessor
        implements ItemProcessor<DailyTransaction, TransactionPostingProcessor.PostingResult> {

    /**
     * Structured log sink. Replaces the {@code DISPLAY} statements of the source, which were the only
     * instrumentation the legacy program had.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    /**
     * Width of {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L17}. A COBOL alphanumeric
     * field is fixed width and space padded, which is what makes the reference modification at
     * {@code app/cbl/CBTRN02C.cbl:L414} total safe regardless of the data.
     */
    public static final int ORIG_TS_WIDTH = 26;

    /**
     * Length of the reference modification {@code DALYTRAN-ORIG-TS (1:10)} at
     * {@code app/cbl/CBTRN02C.cbl:L414}, and equally the width of
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}. The two are compared
     * directly, so they must be the same length.
     */
    public static final int EXPIRY_COMPARISON_LENGTH = 10;

    /**
     * Width of {@code DB2-FORMAT-TS PIC X(26)} at {@code app/cbl/CBTRN02C.cbl:L159}, corroborated by the
     * redefinition at {@code :L160-L174} whose components sum to exactly 26.
     */
    public static final int DB2_FORMAT_TS_WIDTH = 26;

    /**
     * Scale of every monetary field this class touches: {@code V99} in
     * {@code PIC S9(09)V99} and {@code PIC S9(10)V99}.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * The trailing literal of {@code MOVE '0000' TO DB2-REST} at {@code app/cbl/CBTRN02C.cbl:L701}, filling
     * {@code DB2-REST PIC X(04)} at {@code :L174}.
     */
    private static final String DB2_REST = "0000";

    /**
     * The timestamp shape proven by the format comment at {@code app/cbl/CBTRN02C.cbl:L149} -
     * {@code EEEE-MM-DD-UU.MM.SS.HH0000} - and by the field widths at {@code :L160-L174}. Two fraction
     * digits, because {@code COB-MIL PIC X(2)} at {@code :L157} receives hundredths of a second from
     * {@code FUNCTION CURRENT-DATE} and {@code DB2-MIL} at {@code :L173} is {@code PIC 9(002)}.
     *
     * <p>{@link DateTimeFormatter} is immutable and thread safe, so a shared static instance is correct for
     * a singleton bean. {@link Locale#ROOT} is explicit so that no host locale can alter the rendering.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS", Locale.ROOT);

    /**
     * The {@code '00'} file status, taken from {@link FileStatus} rather than written as a literal so that
     * the status vocabulary keeps exactly one definition tree-wide.
     */
    private static final String STATUS_SUCCESS = FileStatus.SUCCESS.code()
            .orElseThrow(() -> new IllegalStateException(
                    "FileStatus.SUCCESS must expose an exact two character code"));

    /**
     * The {@code '23'} file status, taken from {@link FileStatus} for the same reason as
     * {@link #STATUS_SUCCESS}.
     */
    private static final String STATUS_RECORD_NOT_FOUND = FileStatus.RECORD_NOT_FOUND.code()
            .orElseThrow(() -> new IllegalStateException(
                    "FileStatus.RECORD_NOT_FOUND must expose an exact two character code"));

    /**
     * {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy}, which is the abend work-area
     * copybook. The value is the program name at {@code app/cbl/CBTRN02C.cbl:L23}, which is exactly eight
     * characters.
     */
    private static final String ABEND_CULPRIT = "CBTRN02C";

    /**
     * Logical name of the transaction dataset: DD {@code TRANFILE} at {@code app/jcl/POSTTRAN.jcl:L28} over
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.
     */
    private static final String DD_TRANFILE = "TRANFILE";

    /**
     * Logical name of the category-balance dataset: DD {@code TCATBALF} at
     * {@code app/jcl/POSTTRAN.jcl:L41}.
     */
    private static final String DD_TCATBALF = "TCATBALF";

    /**
     * Logical name of the account dataset: DD {@code ACCTFILE} at {@code app/jcl/POSTTRAN.jcl:L39}.
     */
    private static final String DD_ACCTFILE = "ACCTFILE";

    /**
     * Relation behind {@link #DD_TRANFILE}, named by {@code V1__create_schema.sql}. Quoted in the DDL
     * because {@code transaction} is a reserved word; the unquoted spelling is used in diagnostics.
     */
    private static final String RELATION_TRANSACTION = "transaction";

    /**
     * Relation behind {@link #DD_TCATBALF}, named by {@code V1__create_schema.sql}.
     */
    private static final String RELATION_TCATBAL = "transaction_category_balance";

    /**
     * Relation behind {@link #DD_ACCTFILE}, named by {@code V1__create_schema.sql}.
     */
    private static final String RELATION_ACCOUNT = "account";

    /**
     * Relation behind DD {@code DALYTRAN} at {@code app/jcl/POSTTRAN.jcl:L30-L31}
     * ({@code AWS.M2.CARDDEMO.DALYTRAN.PS}), named by {@code V1__create_schema.sql}. Named only in
     * diagnostics: this class never reads or writes the staging relation, because the reader supplies the
     * record.
     */
    private static final String RELATION_DAILY_TRANSACTION = "daily_transaction";

    /**
     * {@code ABEND-REASON PIC X(50)} of {@code app/cpy/CSMSG02Y.cpy}, for the abends this class raises. Both
     * of its abend sites are wiring or configuration defects rather than data conditions, so one reason
     * serves both and it is stated as a constant rather than derived from the message. Thirty-one
     * characters, inside the fifty the field allows.
     */
    private static final String ABEND_REASON = "DAILY TRANSACTION POSTING ABEND";

    /**
     * {@code DISPLAY 'ERROR WRITING TO TRANSACTION FILE'} at {@code app/cbl/CBTRN02C.cbl:L574}, verbatim
     * including case.
     */
    private static final String TRANFILE_WRITE_FAILURE_TEXT = "ERROR WRITING TO TRANSACTION FILE";

    /**
     * {@code DISPLAY 'ERROR WRITING TRANSACTION BALANCE FILE'} at {@code app/cbl/CBTRN02C.cbl:L520},
     * verbatim. Distinct from {@link #TCATBAL_REWRITE_FAILURE_TEXT} because the source distinguishes the
     * create branch from the update branch, and collapsing them would lose that distinction.
     */
    private static final String TCATBAL_WRITE_FAILURE_TEXT = "ERROR WRITING TRANSACTION BALANCE FILE";

    /**
     * {@code DISPLAY 'ERROR REWRITING TRANSACTION BALANCE FILE'} at {@code app/cbl/CBTRN02C.cbl:L538},
     * verbatim.
     */
    private static final String TCATBAL_REWRITE_FAILURE_TEXT = "ERROR REWRITING TRANSACTION BALANCE FILE";

    /**
     * First half of {@code DISPLAY 'TCATBAL record not found for key : '} at
     * {@code app/cbl/CBTRN02C.cbl:L476}. The mixed case and both trailing spaces are part of the literal
     * and are reproduced rather than normalised.
     */
    private static final String TCATBAL_CREATING_PREFIX = "TCATBAL record not found for key : ";

    /**
     * Second half of the same {@code DISPLAY}, at {@code app/cbl/CBTRN02C.cbl:L477}. The two leading dots
     * are part of the literal.
     */
    private static final String TCATBAL_CREATING_SUFFIX = ".. Creating.";

    /**
     * The operation names reported alongside a translated store failure. They are diagnostic identities
     * only and name the COBOL verb that the Java call replaces.
     */
    private static final String OPERATION_WRITE = "WRITE";

    /**
     * Companion of {@link #OPERATION_WRITE} for {@code REWRITE} sites.
     */
    private static final String OPERATION_REWRITE = "REWRITE";

    /**
     * {@code XREF-FILE}, read on its primary key at {@code app/cbl/CBTRN02C.cbl:L383}. Keyed by the
     * sixteen-character card number per {@code FILE-CONTROL} at {@code :L28-L60}.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * {@code ACCOUNT-FILE}, read at {@code app/cbl/CBTRN02C.cbl:L395} and rewritten at {@code :L554}.
     */
    private final AccountRepository accountRepository;

    /**
     * {@code TCATBAL-FILE}, read at {@code app/cbl/CBTRN02C.cbl:L474}, written at {@code :L510} and
     * rewritten at {@code :L528}.
     */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * {@code TRANSACT-FILE}, written at {@code app/cbl/CBTRN02C.cbl:L564}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The single owner of the file-status vocabulary, including the {@code '00' OR '23'} carve-out at
     * {@code app/cbl/CBTRN02C.cbl:L481}.
     */
    private final FileStatusMapper fileStatusMapper;

    /**
     * Backs {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS} at {@code app/cbl/CBTRN02C.cbl:L693}.
     */
    private final Clock clock;

    /**
     * Constructs the processor over its four datasets and the shared status mapper, defaulting the clock.
     *
     * <p>This is the constructor the container uses, and it is marked {@code @Autowired} to say so
     * explicitly. The marker is load bearing rather than decorative: implicit constructor selection applies
     * only when a bean declares exactly one candidate, and this class declares two - this one and the
     * package-private test seam below. Faced with two unannotated candidates the container does not prefer
     * the public one; it falls back to a no-argument constructor, finds none, and fails context refresh with
     * {@code BeanInstantiationException: No default constructor found}. Removing the marker therefore breaks
     * startup even though the class still compiles and every unit test still passes.
     *
     * <p>The clock is defaulted rather than contributed because no {@code Clock} bean exists in this
     * application and none should be required for this bean to wire.
     * {@link Clock#systemDefaultZone()} is chosen over {@link Clock#systemUTC()} deliberately:
     * {@code FUNCTION CURRENT-DATE} at {@code app/cbl/CBTRN02C.cbl:L693} yields the local date and time of
     * the running system, so any other zone would stamp {@code TRAN-PROC-TS} with a value the source could
     * not have produced.
     *
     * <p>Side effects: none. Every argument is checked by a static helper, so no overridable instance
     * method runs and no partially constructed reference can escape.
     *
     * @param cardCrossReferenceRepository access to {@code XREF-FILE}; must not be {@code null}
     * @param accountRepository access to {@code ACCOUNT-FILE}; must not be {@code null}
     * @param transactionCategoryBalanceRepository access to {@code TCATBAL-FILE}; must not be {@code null}
     * @param transactionRepository access to {@code TRANSACT-FILE}; must not be {@code null}
     * @param fileStatusMapper the shared file-status translator; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}, which is a wiring defect rather than a
     *                             data condition and so is reported immediately
     */
    @Autowired
    public TransactionPostingProcessor(
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final AccountRepository accountRepository,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final TransactionRepository transactionRepository,
            final FileStatusMapper fileStatusMapper) {
        this(cardCrossReferenceRepository, accountRepository, transactionCategoryBalanceRepository,
                transactionRepository, fileStatusMapper, Clock.systemDefaultZone());
    }

    /**
     * Test seam. Creates the processor over a caller-supplied clock.
     *
     * <p>It is package-private because production never uses it and must not be able to: the zone choice
     * documented on the public constructor is a parity decision, not a preference. It is not dead code -
     * the unit test fixes the clock so that the generated {@code TRAN-PROC-TS} of
     * {@code app/cbl/CBTRN02C.cbl:L437-L438} is deterministic and can be asserted byte for byte.
     *
     * @param cardCrossReferenceRepository access to {@code XREF-FILE}; must not be {@code null}
     * @param accountRepository access to {@code ACCOUNT-FILE}; must not be {@code null}
     * @param transactionCategoryBalanceRepository access to {@code TCATBAL-FILE}; must not be {@code null}
     * @param transactionRepository access to {@code TRANSACT-FILE}; must not be {@code null}
     * @param fileStatusMapper the shared file-status translator; must not be {@code null}
     * @param clock the clock supplying the current instant for the generated processing timestamp; must not
     *              be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    TransactionPostingProcessor(
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final AccountRepository accountRepository,
            final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            final TransactionRepository transactionRepository,
            final FileStatusMapper fileStatusMapper,
            final Clock clock) {
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper,
                "fileStatusMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The outcome of processing one daily-transaction record: either a posted transaction or a reject
     * classification, never both and never neither.
     *
     * <p>This is the Java form of the branch at {@code app/cbl/CBTRN02C.cbl:L211-L216}, where a record with
     * a zero reason code is posted and a record with a non-zero reason code is counted and written to the
     * reject dataset. It is a nested {@code record} rather than a separate file on purpose: it has no
     * meaning outside this processor's contract, and the package must not gain another top-level type.
     *
     * <p>It carries the originating {@link DailyTransaction} on <b>both</b> paths, because
     * {@code app/cbl/CBTRN02C.cbl:L447 MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} makes the input record the
     * first 350 bytes of the 430-byte reject image, and because a writer needs the record it is writing on
     * the posted path too.
     *
     * <p><b>What this type deliberately does not do:</b> it does not assemble the 430-byte reject record.
     * That belongs to {@code com.cardemo.batch.writers.RejectWriter}. It exposes the two trailer halves -
     * {@link #failReasonField()} at four characters and {@link #failReasonDescField()} at 76 - and stops
     * there.
     *
     * @param source the record that was processed, never {@code null}
     * @param postedTransaction the transaction built by {@code 2000-POST-TRANSACTION} and persisted, or
     *                          {@code null} when the record was rejected
     * @param rejectCode the reject classification, or {@code null} when the record was posted; when
     *                   present it is one of the five constants of {@link RejectCode}
     */
    public record PostingResult(DailyTransaction source, Transaction postedTransaction,
                                RejectCode rejectCode) {

        /**
         * Enforces the invariant that exactly one of the two outcomes is present.
         *
         * @throws NullPointerException if {@code source} is {@code null}
         * @throws IllegalArgumentException if both outcomes are present or both are absent, which would
         *                                  describe a state the source cannot reach
         */
        public PostingResult {
            Objects.requireNonNull(source, "source must not be null");
            if ((postedTransaction == null) == (rejectCode == null)) {
                throw new IllegalArgumentException(
                        "A PostingResult must carry either a posted transaction or a reject code, never "
                                + "both and never neither: app/cbl/CBTRN02C.cbl:L211 branches on "
                                + "WS-VALIDATION-FAIL-REASON = 0 and the two arms are exclusive");
            }
        }

        /**
         * Builds the posted outcome, the {@code THEN} arm of {@code app/cbl/CBTRN02C.cbl:L211-L212}.
         *
         * @param source the record that was posted; must not be {@code null}
         * @param postedTransaction the persisted transaction; must not be {@code null}
         * @return the outcome, never {@code null}
         */
        public static PostingResult posted(final DailyTransaction source,
                                           final Transaction postedTransaction) {
            return new PostingResult(source,
                    Objects.requireNonNull(postedTransaction, "postedTransaction must not be null"),
                    null);
        }

        /**
         * Builds the rejected outcome, the {@code ELSE} arm of {@code app/cbl/CBTRN02C.cbl:L213-L215}.
         *
         * @param source the record that was rejected; must not be {@code null}
         * @param rejectCode the classification; must not be {@code null}
         * @return the outcome, never {@code null}
         */
        public static PostingResult rejected(final DailyTransaction source, final RejectCode rejectCode) {
            return new PostingResult(source, null,
                    Objects.requireNonNull(rejectCode, "rejectCode must not be null"));
        }

        /**
         * Reports whether the record posted.
         *
         * @return {@code true} when a transaction was persisted
         */
        public boolean isPosted() {
            return postedTransaction != null;
        }

        /**
         * Reports whether the record was rejected, which is what the step counts to decide whether return
         * code 4 applies per {@code app/cbl/CBTRN02C.cbl:L229-L231}.
         *
         * @return {@code true} when the record carries a reject classification
         */
        public boolean isRejected() {
            return rejectCode != null;
        }

        /**
         * Returns the numeric reason code held by {@code WS-VALIDATION-FAIL-REASON} at
         * {@code app/cbl/CBTRN02C.cbl:L181}.
         *
         * @return the reject code, or {@link RejectCode#NO_REJECT_REASON_CODE} for a posted record, which
         * is the value {@code :L208} leaves in the field
         */
        public int failReasonCode() {
            return rejectCode == null ? RejectCode.NO_REJECT_REASON_CODE : rejectCode.getCode();
        }

        /**
         * Returns the first half of the 80-byte validation trailer:
         * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181}, zero padded to
         * four characters.
         *
         * @return exactly {@link RejectCode#FAIL_REASON_LENGTH} characters, never {@code null}
         */
        public String failReasonField() {
            return rejectCode == null
                    ? RejectCode.renderFailReason(RejectCode.NO_REJECT_REASON_CODE)
                    : rejectCode.toFailReasonField();
        }

        /**
         * Returns the second half of the trailer:
         * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code app/cbl/CBTRN02C.cbl:L182}, space
         * padded to 76 characters.
         *
         * <p>The blank form is taken from {@link RejectCode#noRejectTrailer()} rather than built from a
         * local format string, so that the padding rule keeps exactly one definition. {@link RejectCode}
         * owns the trailer geometry; re-declaring the pad width here would duplicate it.
         *
         * @return exactly {@link RejectCode#FAIL_REASON_DESC_LENGTH} characters, never {@code null}
         */
        public String failReasonDescField() {
            return rejectCode == null
                    ? RejectCode.noRejectTrailer().substring(RejectCode.FAIL_REASON_LENGTH)
                    : rejectCode.toFailReasonDescField();
        }
    }

    /**
     * The state that {@code 1500-VALIDATE-TRAN} leaves behind: the records its two lookups resolved, and the
     * reason code they set.
     *
     * <p>In the source these live in {@code WORKING-STORAGE} - {@code CARD-XREF-RECORD} and
     * {@code ACCOUNT-RECORD} are filled by {@code READ ... INTO} at {@code app/cbl/CBTRN02C.cbl:L383} and
     * {@code :L395}, and {@code WS-VALIDATION-FAIL-REASON} is set at {@code :L385}, {@code :L397},
     * {@code :L410} and {@code :L417}. Holding them as fields on a singleton bean would reintroduce the
     * global mutable state that clause B3 of Rule 1 forbids and would make the bean unsafe to share, so they
     * are returned as an immutable value instead. This is why the type is a private nested {@code record}:
     * it is a translation artefact of the validation cascade and has no meaning to any caller.
     *
     * @param crossReference the resolved cross-reference record, or {@code null} when the card number did
     *                       not resolve
     * @param account the resolved account record, or {@code null} when validation stopped before or at the
     *                account lookup
     * @param rejectCode the reason code, or {@code null} to mean {@code WS-VALIDATION-FAIL-REASON = 0}
     */
    private record ValidationOutcome(CardCrossReference crossReference, Account account,
                                     RejectCode rejectCode) {

        /**
         * Records a failed validation, whichever of the four assignment sites produced it.
         *
         * @param rejectCode the reason code that was moved into {@code WS-VALIDATION-FAIL-REASON}
         * @return the outcome, never {@code null}
         */
        static ValidationOutcome rejected(final RejectCode rejectCode) {
            return new ValidationOutcome(null, null, rejectCode);
        }

        /**
         * Records the state after {@code 1500-A-LOOKUP-XREF} succeeded but before
         * {@code 1500-B-LOOKUP-ACCT} has run.
         *
         * @param crossReference the record read into {@code CARD-XREF-RECORD}
         * @return the outcome, never {@code null}
         */
        static ValidationOutcome crossReferenceFound(final CardCrossReference crossReference) {
            return new ValidationOutcome(crossReference, null, null);
        }

        /**
         * Records a fully validated record: both lookups resolved and no reason code was set.
         *
         * @param crossReference the record read into {@code CARD-XREF-RECORD}
         * @param account the record read into {@code ACCOUNT-RECORD}
         * @return the outcome, never {@code null}
         */
        static ValidationOutcome validated(final CardCrossReference crossReference, final Account account) {
            return new ValidationOutcome(crossReference, account, null);
        }

        /**
         * Tests {@code WS-VALIDATION-FAIL-REASON = 0}, the condition at
         * {@code app/cbl/CBTRN02C.cbl:L372} and again at {@code :L211}.
         *
         * @return {@code true} when no reason code has been set
         */
        boolean isFailReasonZero() {
            return rejectCode == null;
        }
    }

    /**
     * Processes one daily-transaction record: validate, then either post or reject.
     *
     * <p>This is the body of the legacy inner loop at {@code app/cbl/CBTRN02C.cbl:L205-L216}, minus the two
     * things that belong to the step. {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code :L206} and
     * {@code ADD 1 TO WS-REJECT-COUNT} at {@code :L214} are step-scoped counters, and
     * {@code PERFORM 2500-WRITE-REJECT-REC} at {@code :L215} is the reject writer's work. What remains is
     * exactly: reset the reason fields, validate, branch.
     *
     * <p>The per-record reset of {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code :L208} and
     * {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC} at {@code :L209} needs no statement here: the
     * reason code is a fresh method-local on every invocation, which is what the reset exists to guarantee.
     * That is the whole reason this class holds no per-record field.
     *
     * <p><b>Transaction boundary.</b> The annotation is on this method rather than on
     * {@link #postTransaction(DailyTransaction, CardCrossReference, Account)} because Spring's declarative
     * transaction support is proxy-based and does not intercept self-invocation; an annotation on the
     * internally called paragraph method would be silently ignored. This method is invoked by the
     * chunk-oriented step from outside the bean, so the proxy applies, and all three writes of
     * {@code :L440-L442} are reached from here and therefore share one unit of work. On the rejected path no
     * write occurs, so the unit of work is empty and the boundary costs nothing.
     *
     * <p><b>A reject is never an exception.</b> {@code :L229-L231} sets return code 4 if and only if the
     * reject count exceeds zero, so a reject has to stay countable. All five reject codes are returned, not
     * thrown.
     *
     * @param item the staged 350-byte record of {@code app/cpy/CVTRA06Y.cpy}; must not be {@code null},
     *             because the legacy loop only enters this logic after a successful sequential read at
     *             {@code app/cbl/CBTRN02C.cbl:L204-L205} and so cannot present an absent record
     * @return the outcome, never {@code null}; returning {@code null} would filter the item out of the chunk
     * and the source filters nothing
     * @throws FatalProcessingException if {@code item} is {@code null}, or if a store failure occurs that
     *                                  the source would have answered with {@code 9999-ABEND-PROGRAM}
     * @throws DuplicateRecordException if a primary key collides on one of the two inserts
     * @throws DataIntegrityException if a foreign key or check constraint rejects a write, or if the record
     *                                omits a value its {@code NOT NULL} column requires
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostingResult process(final DailyTransaction item) {
        if (item == null) {
            throw abend("The daily transaction reader supplied no record. app/cbl/CBTRN02C.cbl:L205 enters "
                    + "per-record processing only when END-OF-FILE is 'N', so an absent record is a wiring "
                    + "defect in the step rather than a data condition, and no reject code describes it.",
                    null);
        }

        // :L210 PERFORM 1500-VALIDATE-TRAN
        ValidationOutcome outcome = validateTran(item);

        // :L211 IF WS-VALIDATION-FAIL-REASON = 0
        if (outcome.isFailReasonZero()) {
            // :L212 PERFORM 2000-POST-TRANSACTION
            Transaction posted = postTransaction(item, outcome.crossReference(), outcome.account());
            return PostingResult.posted(item, posted);
        }

        // :L213-L215 ELSE ADD 1 TO WS-REJECT-COUNT / PERFORM 2500-WRITE-REJECT-REC. The counter and the
        // 430-byte write are the step's and the writer's; this class reports the classification that drives
        // both.
        RejectCode rejectCode = outcome.rejectCode();
        LOG.info("Daily transaction rejected with reason {} {}", rejectCode.toFailReasonField(),
                rejectCode.getDescription());
        return PostingResult.rejected(item, rejectCode);
    }

    /**
     * {@code 1500-VALIDATE-TRAN}, {@code app/cbl/CBTRN02C.cbl:L370-L378}.
     *
     * <p>The whole paragraph is four statements: perform the cross-reference lookup, then perform the
     * account lookup only if the reason code is still zero, then exit. It is reproduced literally, including
     * the {@code ELSE CONTINUE} at {@code :L374-L375}, which in Java is simply the outcome standing
     * unchanged.
     *
     * <p><b>There are exactly two lookups.</b> The comment at {@code :L377} reads
     * {@code * ADD MORE VALIDATIONS HERE} and is reproduced below, because it is part of the source and
     * records that the author knew the cascade was incomplete. It is not an invitation to add validations:
     * doing so would reject records the legacy system accepts.
     *
     * @param item the record being validated, already known to be non-{@code null}
     * @return the resolved records and reason code, never {@code null}
     */
    private ValidationOutcome validateTran(final DailyTransaction item) {
        // :L371 PERFORM 1500-A-LOOKUP-XREF.
        ValidationOutcome afterXref = lookupXref(item);

        // :L372 IF WS-VALIDATION-FAIL-REASON = 0
        if (afterXref.isFailReasonZero()) {
            // :L373 PERFORM 1500-B-LOOKUP-ACCT
            return lookupAcct(item, afterXref.crossReference());
        }

        // :L374-L376 ELSE CONTINUE END-IF - the reason code set by the cross-reference lookup stands.
        // :L377 * ADD MORE VALIDATIONS HERE
        //        Reproduced verbatim from app/cbl/CBTRN02C.cbl:L377. The cascade ends here in the source and
        //        must end here in the translation; adding a third validation would diverge.
        // :L378 EXIT.
        return afterXref;
    }

    /**
     * {@code 1500-A-LOOKUP-XREF}, {@code app/cbl/CBTRN02C.cbl:L380-L392}.
     *
     * <p>{@code :L382-L383} move the card number into {@code FD-XREF-CARD-NUM} and read {@code XREF-FILE}
     * into {@code CARD-XREF-RECORD}. {@code FILE-CONTROL} at {@code :L28-L60} declares that file
     * {@code INDEXED} with {@code ACCESS MODE RANDOM} and {@code RECORD KEY IS FD-XREF-CARD-NUM}, so this is
     * a <b>primary key</b> read of the sixteen-character card number, not a use of the
     * {@code CARDXREF.VSAM.AIX} alternate index. The Java form is therefore
     * {@code findById}, and {@code findByAccountIdOrderByCardNumberAsc} - which does model the alternate
     * index - is deliberately not used.
     *
     * <p>{@code :L385-L387} answer {@code INVALID KEY} with reason code 100 and the description
     * {@code 'INVALID CARD NUMBER FOUND'}; {@code :L390} answers a hit with {@code CONTINUE}. Both literals
     * live on {@link RejectCode#INVALID_CARD_NUMBER} and are not restated here.
     *
     * <p>Boundary conditions, clause B2. A {@code null} or blank card number is classified as reason code
     * 100 without touching the store. That is the faithful outcome and not a shortcut: a
     * {@code PIC X(16)} field holding spaces cannot match any key in a keyed dataset, so the legacy read
     * returns {@code INVALID KEY} and assigns 100. Passing a {@code null} identifier to a repository would
     * instead raise a store exception, converting a business reject into a failure.
     *
     * <p>No card number is written to the log on any path here or anywhere else in this class. The primary
     * account number is the most sensitive value in the record, and the tree-wide convention - stated on
     * {@code Transaction} itself - is that not emitting it is the defence and log masking is only the
     * backstop.
     *
     * @param item the record being validated, already known to be non-{@code null}
     * @return an outcome carrying the resolved cross-reference record, or reason code 100
     */
    private ValidationOutcome lookupXref(final DailyTransaction item) {
        // :L382 MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
        String cardNumber = item.getCardNumber();
        if (cardNumber == null || cardNumber.isBlank()) {
            // A blank key cannot match a keyed dataset, so :L384-L387 INVALID KEY applies.
            LOG.info("Daily transaction carries no card number to look up; classifying as reason {}",
                    RejectCode.INVALID_CARD_NUMBER.toFailReasonField());
            return ValidationOutcome.rejected(RejectCode.INVALID_CARD_NUMBER);
        }

        // :L383 READ XREF-FILE INTO CARD-XREF-RECORD
        Optional<CardCrossReference> crossReference = cardCrossReferenceRepository.findById(cardNumber);
        if (crossReference.isEmpty()) {
            // :L384-L387 INVALID KEY -> MOVE 100 / MOVE 'INVALID CARD NUMBER FOUND'
            return ValidationOutcome.rejected(RejectCode.INVALID_CARD_NUMBER);
        }

        // :L388-L390 NOT INVALID KEY -> CONTINUE
        return ValidationOutcome.crossReferenceFound(crossReference.get());
    }

    /**
     * {@code 1500-B-LOOKUP-ACCT}, {@code app/cbl/CBTRN02C.cbl:L393-L422}. <b>The highest-risk paragraph in
     * this file.</b>
     *
     * <p>{@code :L394-L395} move {@code XREF-ACCT-ID} into {@code FD-ACCT-ID} and read
     * {@code ACCOUNT-FILE}. {@code :L396-L399} answer {@code INVALID KEY} with reason code 101 and the
     * description {@code 'ACCOUNT RECORD NOT FOUND'}. The read is a plain {@code READ}, not a
     * {@code READ ... UPDATE}, so {@code findById} is correct and the pessimistic
     * {@code findByIdForUpdate} - which exists for the online account-update program - is deliberately not
     * used.
     *
     * <p><b>Both remaining tests sit inside the {@code NOT INVALID KEY} clause at {@code :L400-L420}.</b>
     * That is load bearing: when the account is absent, reason code 101 is set and neither the over-limit
     * arithmetic nor the expiry comparison is evaluated at all. Returning immediately reproduces that.
     *
     * <p><b>PARITY TRAP 4 - the formula.</b> {@code :L403-L405} is
     * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}, transcribed
     * below in that exact order. It is not rearranged, not folded into the comparison and no operand has its
     * absolute value taken. The subtraction is correct precisely because
     * {@link #updateAccountRec(DailyTransaction, Account)} adds negative amounts into the debit accumulator,
     * so that accumulator holds negative values and subtracting it restores their magnitude. Any
     * "equivalent-looking" rewrite changes the result for a record with negative cycle debit, and 50 of the
     * 300 records in {@code app/data/ASCII/dailytran.txt} carry a negative amount.
     *
     * <p><b>PARITY TRAP 1 - the unguarded overwrite.</b> {@code :L413} closes the over-limit
     * {@code END-IF} and {@code :L414} opens the expiry {@code IF} with <b>nothing between them</b>. Both
     * write the same field, so a record that fails both tests ends with 103 and produces exactly one reject
     * record. The two {@code if} statements below are therefore sequential and unguarded, writing one local
     * variable. Do not add a guard, an early return or a list of codes.
     *
     * <p><b>PARITY TRAP 2 - the expiry test is a string comparison.</b> {@code :L414} compares
     * {@code ACCT-EXPIRAION-DATE} against {@code DALYTRAN-ORIG-TS (1:10)} - the <b>originating</b>
     * timestamp, not the processing timestamp - as alphanumeric data. Neither side is parsed into a date, so
     * {@link String#compareTo(String)} is the faithful operator and orders byte by byte.
     * {@code >=} in COBOL becomes {@code compareTo(...) >= 0}, and the sense of the test is preserved
     * exactly: the reject fires when the expiry date is <b>less than</b> the originating date prefix.
     *
     * <p><b>PARITY TRAP 3 - the retained misspelling.</b> {@link Account#getExpiraionDate()} is spelled as
     * {@code ACCT-EXPIRAION-DATE} is spelled at {@code app/cpy/CVACT01Y.cpy:L11}, without the {@code T} of
     * "EXPIRATION", and the column is {@code acct_expiraion_date}. It is used here under that spelling
     * because the field name is a contract shared with the copybook, the entity and the schema.
     *
     * <p>Boundary conditions, clause B2. A {@code null} account identifier on the cross-reference record and
     * a {@code null} monetary field are each impossible in a fixed-width source record and in a
     * {@code NOT NULL} column, so each is reported as a typed integrity failure rather than silently
     * defaulted to zero - defaulting would let a corrupt record pass the credit-limit test. The originating
     * timestamp is space padded to its declared width before the ten-character prefix is taken, so no
     * bounds violation is possible; see {@link #origTsDatePrefix(DailyTransaction)}.
     *
     * @param item the record being validated, already known to be non-{@code null}
     * @param crossReference the record resolved by {@code 1500-A-LOOKUP-XREF}, never {@code null}
     * @return an outcome carrying the resolved account, or reason code 101, 102 or 103
     * @throws DataIntegrityException if the cross-reference carries no account identifier, or if the account
     *                                or the record omits a monetary value its column requires
     */
    private ValidationOutcome lookupAcct(final DailyTransaction item,
                                         final CardCrossReference crossReference) {
        // :L394 MOVE XREF-ACCT-ID TO FD-ACCT-ID
        Long accountId = crossReference.getAccountId();
        if (accountId == null) {
            throw integrityFailure(RELATION_ACCOUNT,
                    "The cross-reference record resolved for this transaction carries no XREF-ACCT-ID. "
                            + "app/cpy/CVACT03Y.cpy:L7 declares it PIC 9(11) and the column is NOT NULL, so "
                            + "an absent value cannot be read as an account key.", null);
        }

        // :L395 READ ACCOUNT-FILE INTO ACCOUNT-RECORD
        Optional<Account> located = accountRepository.findById(accountId);
        if (located.isEmpty()) {
            // :L396-L399 INVALID KEY -> MOVE 101 / MOVE 'ACCOUNT RECORD NOT FOUND'. The over-limit and
            // expiry tests are inside the NOT INVALID KEY clause at :L400-L420 and so do not run.
            return ValidationOutcome.rejected(RejectCode.ACCOUNT_RECORD_NOT_FOUND);
        }
        Account account = located.get();

        // WS-VALIDATION-FAIL-REASON, method-local per :L208-L209. Both tests below write this one variable.
        RejectCode failReason = null;

        // :L403-L405 COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        // Transcribed in source order. No rearrangement, no absolute value. WS-TEMP-BAL is PIC S9(09)V99,
        // one digit narrower than its PIC S9(10)V99 operands, so the source can truncate here and this
        // BigDecimal form cannot; the Java result is strictly safer at that boundary.
        BigDecimal currentCycleCredit = requireMoney(account.getCurrentCycleCredit(),
                "ACCT-CURR-CYC-CREDIT", RELATION_ACCOUNT);
        BigDecimal currentCycleDebit = requireMoney(account.getCurrentCycleDebit(),
                "ACCT-CURR-CYC-DEBIT", RELATION_ACCOUNT);
        BigDecimal amount = transactionAmount(item);
        BigDecimal tempBal = currentCycleCredit.subtract(currentCycleDebit).add(amount);

        // :L407 IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
        BigDecimal creditLimit = requireMoney(account.getCreditLimit(), "ACCT-CREDIT-LIMIT",
                RELATION_ACCOUNT);
        if (creditLimit.compareTo(tempBal) >= 0) {
            // :L408 CONTINUE - the source's explicit no-op. The condition is transcribed in the source's own
            // sense rather than inverted, so that the >= of :L407 remains visible in the translation.
            LOG.trace("Credit limit accommodates the computed temporary balance");
        } else {
            // :L410-L412 MOVE 102 / MOVE 'OVERLIMIT TRANSACTION'
            failReason = RejectCode.OVERLIMIT_TRANSACTION;
        }
        // :L413 END-IF

        // :L414 IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
        // PARITY TRAP 1: nothing separates :L413 from :L414 in the source - no guard on the reason code and
        // no early exit - so when both tests fail this assignment overwrites 102 with 103 and one reject
        // record bearing 103 is produced.
        String expiraionDate = expiraionDate(account, accountId);
        String origTsDatePrefix = origTsDatePrefix(item);
        if (expiraionDate.compareTo(origTsDatePrefix) >= 0) {
            // :L415 CONTINUE - a no-op that deliberately leaves any reason code already set by the
            // over-limit test in place, exactly as the source does.
            LOG.trace("Account expiry date is not earlier than the originating date prefix");
        } else {
            // :L417-L419 MOVE 103 / MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
            // This assignment is unconditional within its branch and so overwrites 102 when both tests fail.
            failReason = RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION;
        }
        // :L420 END-IF, :L421 END-READ, :L422 EXIT.

        return failReason == null
                ? ValidationOutcome.validated(crossReference, account)
                : ValidationOutcome.rejected(failReason);
    }

    /**
     * {@code 2000-POST-TRANSACTION}, {@code app/cbl/CBTRN02C.cbl:L424-L444}.
     *
     * <p>Thirteen assignments, then three writes. {@code :L425-L435} move eleven fields from the daily
     * record to the transaction record in this order: identifier, type code, category code, source,
     * description, amount, merchant identifier, merchant name, merchant city, merchant postal code, card
     * number. {@code :L436} moves the originating timestamp as a <b>pure pass-through</b> with no
     * reformatting whatsoever. {@code :L437-L438} generate the processing timestamp and move it.
     *
     * <p>The thirteen assignments are expressed as the thirteen arguments of the {@link Transaction}
     * all-arguments constructor, in the same order. That is not a shortcut: Java evaluates constructor
     * arguments strictly left to right, so the originating timestamp of {@code :L436} is still read before
     * {@link #getDb2FormatTimestamp()} is called for {@code :L437}, and the observable ordering of the
     * paragraph is preserved. Using the constructor rather than thirteen setter calls also means the
     * transaction is never observable in a half-populated state.
     *
     * <p><b>The three writes keep the source's order</b> - {@code :L440} category balance, {@code :L441}
     * account, {@code :L442} transaction. The source commits each independently; here they share the one
     * unit of work opened by {@link #process(DailyTransaction)}. The order is retained even though the commit
     * is now atomic, because it is observable in the log stream that the boundary-parity gate compares
     * against the legacy baseline.
     *
     * <p><b>The 109 assignment is discarded on purpose.</b>
     * {@link #updateAccountRec(DailyTransaction, Account)} returns the reason code that
     * {@code :L556} moves into {@code WS-VALIDATION-FAIL-REASON}, and this method does not read it - which is
     * precisely what the source does with it. See that method for the full disposition.
     *
     * @param item the record being posted, already known to be non-{@code null}
     * @param crossReference the record resolved by {@code 1500-A-LOOKUP-XREF}, never {@code null}; it
     *                       supplies the account identifier of the category-balance key at {@code :L469}
     * @param account the record resolved by {@code 1500-B-LOOKUP-ACCT}, never {@code null}
     * @return the persisted transaction, never {@code null}
     * @throws DuplicateRecordException if {@code pk_transaction} or
     *                                  {@code pk_transaction_category_balance} collides
     * @throws DataIntegrityException if a foreign key rejects a write, or if a required value is absent
     * @throws FatalProcessingException if the store fails for any other reason
     */
    private Transaction postTransaction(final DailyTransaction item,
                                        final CardCrossReference crossReference,
                                        final Account account) {
        // :L425-L438. Thirteen assignments in source order; argument evaluation is left to right, so the
        // originating timestamp of :L436 is read before the processing timestamp of :L437 is generated.
        Transaction transaction = new Transaction(
                item.getTransactionId(),        // :L425 MOVE DALYTRAN-ID            TO TRAN-ID
                item.getTypeCode(),             // :L426 MOVE DALYTRAN-TYPE-CD       TO TRAN-TYPE-CD
                item.getCategoryCode(),         // :L427 MOVE DALYTRAN-CAT-CD        TO TRAN-CAT-CD
                item.getTransactionSource(),    // :L428 MOVE DALYTRAN-SOURCE        TO TRAN-SOURCE
                item.getDescription(),          // :L429 MOVE DALYTRAN-DESC          TO TRAN-DESC
                item.getAmount(),               // :L430 MOVE DALYTRAN-AMT           TO TRAN-AMT
                item.getMerchantId(),           // :L431 MOVE DALYTRAN-MERCHANT-ID   TO TRAN-MERCHANT-ID
                item.getMerchantName(),         // :L432 MOVE DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME
                item.getMerchantCity(),         // :L433 MOVE DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY
                item.getMerchantZip(),          // :L434 MOVE DALYTRAN-MERCHANT-ZIP  TO TRAN-MERCHANT-ZIP
                item.getCardNumber(),           // :L435 MOVE DALYTRAN-CARD-NUM      TO TRAN-CARD-NUM
                item.getOrigTs(),               // :L436 MOVE DALYTRAN-ORIG-TS       TO TRAN-ORIG-TS
                getDb2FormatTimestamp());       // :L437-L438 PERFORM Z-GET ... / MOVE DB2-FORMAT-TS

        // :L440 PERFORM 2700-UPDATE-TCATBAL
        updateTcatbal(item, crossReference);

        // :L441 PERFORM 2800-UPDATE-ACCOUNT-REC. The returned reason code is the retained 109 assignment of
        // :L556-L558, which the source writes and never reads; it is deliberately not consumed here either.
        updateAccountRec(item, account);

        // :L442 PERFORM 2900-WRITE-TRANSACTION-FILE
        writeTransactionFile(transaction);

        // :L444 EXIT.
        return transaction;
    }

    /**
     * {@code 2700-UPDATE-TCATBAL}, {@code app/cbl/CBTRN02C.cbl:L467-L501}. The upsert in which a record not
     * found is success rather than an error.
     *
     * <p><b>The composite key is assembled from three different places</b>, which is easy to get wrong.
     * {@code :L469} takes the account identifier from the <b>cross-reference record</b>
     * ({@code XREF-ACCT-ID}), while {@code :L470} and {@code :L471} take the type code and the category code
     * from the <b>daily-transaction input</b>. The component order is the one declared at
     * {@code app/cpy/CVTRA01Y.cpy:L6-L8} - {@code TRANCAT-ACCT-ID PIC 9(11)},
     * {@code TRANCAT-TYPE-CD PIC X(02)}, {@code TRANCAT-CD PIC 9(04)}, seventeen bytes in total - and
     * {@code V1__create_schema.sql} declares {@code pk_transaction_category_balance} over
     * {@code (acct_id, tran_type_cd, tran_cat_cd)} in exactly that order.
     *
     * <p><b>The read guard is the one lenient guard in this file.</b> {@code :L481} reads
     * {@code IF  TCATBALF-STATUS = '00'  OR '23'}, so a missing record is an accepted control path that
     * selects the create branch. The decision is delegated to
     * {@link FileStatusMapper#requireCategoryBalanceReadSuccess(String)}, which is the sanctioned owner of
     * that carve-out and which cites this very paragraph. Delegating rather than testing statuses locally
     * keeps the leniency scoped: were it ever widened, it would be widened in one place under review, not
     * silently here.
     *
     * <p>{@code WS-CREATE-TRANCAT-REC} at {@code :L190} - the {@code 'N'} or {@code 'Y'} flag set at
     * {@code :L473} and {@code :L478} and tested at {@code :L495} - is the boolean returned by that mapper
     * call. It is a method-local, never a field.
     *
     * <p>{@code :L476-L477} display {@code 'TCATBAL record not found for key : '}, the key, and
     * {@code '.. Creating.'}. The mixed case, the spaces around the colon and the two leading dots are
     * reproduced exactly, because the boundary-parity gate compares the log stream. The key is rendered from
     * its three components; it contains no card number.
     *
     * <p><b>Why this is not an {@code INSERT ... ON CONFLICT DO UPDATE}.</b> The two branches guard
     * different verbs - {@code WRITE} at {@code :L510} against {@code REWRITE} at {@code :L528} - and log
     * different text at {@code :L520} and {@code :L538}. A native upsert would collapse both distinctions
     * into one statement and lose them. The branch decision belongs to this processor, not to the
     * repository, which is why the repository exposes only {@code findById} and {@code save}.
     *
     * @param item the record being posted, already known to be non-{@code null}
     * @param crossReference the resolved cross-reference record, never {@code null}
     * @throws DataIntegrityException if the key cannot be assembled, or if a foreign key rejects the write
     * @throws DuplicateRecordException if {@code pk_transaction_category_balance} collides
     * @throws FatalProcessingException if the store fails for any other reason
     */
    private void updateTcatbal(final DailyTransaction item, final CardCrossReference crossReference) {
        // :L469 MOVE XREF-ACCT-ID     TO FD-TRANCAT-ACCT-ID   <- from the cross-reference record
        // :L470 MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD   <- from the daily input
        // :L471 MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD        <- from the daily input
        TransactionCategoryBalanceId key = categoryBalanceKey(item, crossReference);

        // :L474 READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
        Optional<TransactionCategoryBalance> located = transactionCategoryBalanceRepository.findById(key);

        // :L473 MOVE 'N' TO WS-CREATE-TRANCAT-REC, then :L478 MOVE 'Y' on INVALID KEY. In JPA the read
        // outcome is an Optional rather than a status byte, so the status the guard is applied to is derived
        // from it: present is '00' and absent is '23'.
        String tcatbalfStatus = located.isPresent() ? STATUS_SUCCESS : STATUS_RECORD_NOT_FOUND;
        if (located.isEmpty()) {
            // :L476-L477 DISPLAY 'TCATBAL record not found for key : ' FD-TRAN-CAT-KEY '.. Creating.'
            LOG.info("{}{}{}", TCATBAL_CREATING_PREFIX, renderTranCatKey(key), TCATBAL_CREATING_SUFFIX);
        }

        // :L481-L493 IF TCATBALF-STATUS = '00' OR '23' ... ELSE DISPLAY + 9910 + 9999. Returns true when the
        // status is '23' and the create branch applies, false when it is '00', and throws for anything else.
        boolean createTrancatRec = fileStatusMapper.requireCategoryBalanceReadSuccess(tcatbalfStatus);

        // :L495-L499 IF WS-CREATE-TRANCAT-REC = 'Y' PERFORM 2700-A ELSE PERFORM 2700-B
        if (createTrancatRec) {
            createTcatbalRec(item, key);
        } else {
            updateTcatbalRec(item, located.get());
        }
        // :L501 EXIT.
    }

    /**
     * {@code 2700-A-CREATE-TCATBAL-REC}, {@code app/cbl/CBTRN02C.cbl:L503-L524}. The create branch of the
     * upsert.
     *
     * <p>{@code :L504} initialises the record, which zeroes {@code TRAN-CAT-BAL}. {@code :L505-L507} move
     * the three key components into the record - note that the key is written into the record itself, not
     * only into the file key. {@code :L508} then adds the transaction amount to the now-zero balance, so the
     * created row's balance is the amount. {@code :L510} writes it.
     *
     * <p><b>The write guard accepts {@code '00'} only</b> at {@code :L512}; the {@code '23'} leniency of
     * {@code :L481} does not extend here. A failure displays
     * {@code 'ERROR WRITING TRANSACTION BALANCE FILE'} at {@code :L520}, renders the status through
     * {@code 9910} and abends through {@code 9999}. In Java the store reports failure by throwing, so the
     * guard is the {@code catch} below, which translates and preserves the cause rather than swallowing it.
     *
     * <p>The initialise-then-add of {@code :L504} and {@code :L508} is expressed as
     * {@link BigDecimal#ZERO} plus the amount, rescaled to two decimal places with
     * {@link RoundingMode#HALF_EVEN}. The rescale is explicit because {@code TRAN-CAT-BAL} is
     * {@code PIC S9(09)V99}, that is {@code NUMERIC(11,2)}, and the entity rejects a value carrying more than
     * two decimal digits rather than letting the column round it silently.
     *
     * <p>This paragraph has no {@code EXIT} statement in the source - it ends at the {@code END-IF} of
     * {@code :L524} - which is a stylistic inconsistency with its siblings and has no bearing on behaviour.
     *
     * @param item the record being posted, already known to be non-{@code null}
     * @param key the composite key assembled by {@link #updateTcatbal}, never {@code null}
     * @throws DuplicateRecordException if {@code pk_transaction_category_balance} collides
     * @throws DataIntegrityException if {@code fk07_tcatbal_account} or {@code fk08_tcatbal_category} rejects
     *                                the row
     * @throws FatalProcessingException if the store fails for any other reason
     */
    private void createTcatbalRec(final DailyTransaction item, final TransactionCategoryBalanceId key) {
        // :L504 INITIALIZE TRAN-CAT-BAL-RECORD - zeroes the balance.
        // :L505-L507 move the three key components into the record.
        // :L508 ADD DALYTRAN-AMT TO TRAN-CAT-BAL - so the created balance is zero plus the amount.
        BigDecimal balance = scaledMoney(BigDecimal.ZERO.add(transactionAmount(item)));
        TransactionCategoryBalance created = new TransactionCategoryBalance(key, balance);

        // :L510 WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
        try {
            transactionCategoryBalanceRepository.save(created);
        } catch (DataAccessException storeFailure) {
            // :L512-L524 guard accepting '00' only, then DISPLAY + 9910 + 9999.
            throw translateStoreFailure(storeFailure, TCATBAL_WRITE_FAILURE_TEXT, DD_TCATBALF,
                    RELATION_TCATBAL, OPERATION_WRITE, renderTranCatKey(key));
        }
        LOG.debug("Created transaction category balance for key {}", renderTranCatKey(key));
    }

    /**
     * {@code 2700-B-UPDATE-TCATBAL-REC}, {@code app/cbl/CBTRN02C.cbl:L526-L542}. The update branch of the
     * upsert.
     *
     * <p>{@code :L527} adds the transaction amount to the balance just read, and {@code :L528} rewrites the
     * record. Both branches of the upsert add the amount; only the verb and the failure text differ.
     *
     * <p><b>The rewrite guard accepts {@code '00'} only</b> at {@code :L530}, and a failure displays
     * {@code 'ERROR REWRITING TRANSACTION BALANCE FILE'} at {@code :L538} - text distinct from the create
     * branch's, which is one of the two reasons the branches are not merged.
     *
     * <p>The entity was read inside the current unit of work and is therefore managed, so mutating it is
     * what makes the update happen; {@code save} is called anyway so that the {@code REWRITE} of
     * {@code :L528} has a visible counterpart and so that a store rejection surfaces at this call site
     * rather than only at commit.
     *
     * <p>Like its sibling, this paragraph has no {@code EXIT} statement in the source.
     *
     * @param item the record being posted, already known to be non-{@code null}
     * @param existing the row read at {@code :L474}, never {@code null}
     * @throws DataIntegrityException if a constraint rejects the update
     * @throws DuplicateRecordException if a key collides, which a rewrite normally cannot cause
     * @throws FatalProcessingException if the store fails for any other reason
     */
    private void updateTcatbalRec(final DailyTransaction item, final TransactionCategoryBalance existing) {
        // :L527 ADD DALYTRAN-AMT TO TRAN-CAT-BAL
        BigDecimal current = requireMoney(existing.getBalance(), "TRAN-CAT-BAL", RELATION_TCATBAL);
        existing.setBalance(scaledMoney(current.add(transactionAmount(item))));

        // :L528 REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
        try {
            transactionCategoryBalanceRepository.save(existing);
        } catch (DataAccessException storeFailure) {
            // :L530-L542 guard accepting '00' only, then DISPLAY + 9910 + 9999.
            throw translateStoreFailure(storeFailure, TCATBAL_REWRITE_FAILURE_TEXT, DD_TCATBALF,
                    RELATION_TCATBAL, OPERATION_REWRITE, renderTranCatKey(existing.getId()));
        }
        LOG.debug("Updated transaction category balance for key {}", renderTranCatKey(existing.getId()));
    }

    /**
     * {@code 2800-UPDATE-ACCOUNT-REC}, {@code app/cbl/CBTRN02C.cbl:L545-L560}. The sign branch, and the home
     * of reject code 109.
     *
     * <p>{@code :L547} adds the amount to the current balance. {@code :L548-L552} then add it to one of the
     * two cycle accumulators: to {@code ACCT-CURR-CYC-CREDIT} when the amount is
     * {@code >= 0} and to {@code ACCT-CURR-CYC-DEBIT} otherwise. {@code :L554} rewrites the record.
     *
     * <p><b>The boundary is inclusive.</b> {@code IF DALYTRAN-AMT >= 0} at {@code :L548} sends a zero amount
     * to the <b>credit</b> accumulator, not the debit one. That is a named boundary case.
     *
     * <p><b>A negative amount is added to the debit accumulator, not subtracted from it.</b> So
     * {@code ACCT-CURR-CYC-DEBIT} legitimately holds negative values, and that is exactly why
     * {@link #lookupAcct(DailyTransaction, CardCrossReference)} <i>subtracts</i> it when computing the
     * over-limit temporary balance. <b>No absolute value is taken anywhere in this path</b>, and none may be
     * introduced: normalising the sign here would silently break the over-limit test there. The parity
     * fixture exercises both arms genuinely - {@code app/data/ASCII/dailytran.txt} column 143 carries 250
     * positive and 50 negative zoned-decimal overpunch signs.
     *
     * <p><b>Reject code 109 is assigned and never consumed.</b> {@code :L555-L558} answer
     * {@code INVALID KEY} on the rewrite by moving 109 and {@code 'ACCOUNT RECORD NOT FOUND'} into the
     * validation reason fields. That path is reachable, so the constant is real code and
     * {@link RejectCode} rightly holds five constants - but the value is never used as a reject outcome.
     * This paragraph runs only from {@code 2000-POST-TRANSACTION}, which {@code :L211} enters only when the
     * reason code was already zero, and the assignment carries <b>no status guard, no {@code 9910} and no
     * {@code 9999}</b>: the source falls straight through to {@code 2900-WRITE-TRANSACTION-FILE}, writes no
     * reject record, does not increment {@code WS-REJECT-COUNT}, and clears the field at {@code :L208} on the
     * next iteration. The assignment below is therefore retained and marked; the value reaches diagnostic
     * text only and is never returned as a {@link PostingResult} reject code. This is the tracked artefact
     * that resolves the clause B1 conflict in favour of parity, and it belongs in {@code DECISION_LOG.md}.
     *
     * <p><b>DEVIATION, and it must not be presented as equivalence.</b> Two things differ from the source
     * here, both consequences of the single unit of work:
     * <ol>
     * <li>Because the source's three writes are three independent commits, its 109 path leaves an
     * <b>orphaned category-balance row and an orphaned transaction row</b> behind. The Java transaction rolls
     * all three back together, so the hazard cannot occur. That is a behavioural <b>improvement, not
     * parity</b>.</li>
     * <li>Consequently this method throws where the source continues. A failed flush poisons the unit of
     * work, so continuing to the transaction write would be neither possible nor honest.</li>
     * </ol>
     * Both are recorded in {@code DECISION_LOG.md}.
     *
     * @param item the record being posted, already known to be non-{@code null}
     * @param account the record resolved by {@code 1500-B-LOOKUP-ACCT}, never {@code null}
     * @return {@code null} on success, which is the value {@code WS-VALIDATION-FAIL-REASON} retains; the
     * method never returns a non-{@code null} code, because the only assignment site throws
     * @throws DataIntegrityException if a constraint rejects the update, or if a required value is absent
     * @throws DuplicateRecordException if a key collides, which an update normally cannot cause
     * @throws FatalProcessingException if the store fails for any other reason
     */
    private RejectCode updateAccountRec(final DailyTransaction item, final Account account) {
        BigDecimal amount = transactionAmount(item);

        // :L547 ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        BigDecimal currentBalance = requireMoney(account.getCurrentBalance(), "ACCT-CURR-BAL",
                RELATION_ACCOUNT);
        account.setCurrentBalance(scaledMoney(currentBalance.add(amount)));

        // :L548 IF DALYTRAN-AMT >= 0 - inclusive, so a zero amount goes to the credit accumulator.
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            // :L549 ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
            BigDecimal cycleCredit = requireMoney(account.getCurrentCycleCredit(), "ACCT-CURR-CYC-CREDIT",
                    RELATION_ACCOUNT);
            account.setCurrentCycleCredit(scaledMoney(cycleCredit.add(amount)));
        } else {
            // :L551 ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT - the amount is negative and is ADDED, so this
            // accumulator holds negative values. No absolute value is taken; lookupAcct subtracts it.
            BigDecimal cycleDebit = requireMoney(account.getCurrentCycleDebit(), "ACCT-CURR-CYC-DEBIT",
                    RELATION_ACCOUNT);
            account.setCurrentCycleDebit(scaledMoney(cycleDebit.add(amount)));
        }
        // :L552 END-IF

        // :L554 REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        try {
            accountRepository.save(account);
        } catch (DataAccessException storeFailure) {
            // :L555-L558 INVALID KEY -> MOVE 109 / MOVE 'ACCOUNT RECORD NOT FOUND'.
            // RETAINED, CITED AND ASSIGNED-BUT-NEVER-CONSUMED. The source sets this reason code and falls
            // straight through to 2900 without writing a reject record or incrementing the reject count, and
            // clears it at :L208 on the next iteration. The value below therefore reaches diagnostic text
            // only: it is never returned as a PostingResult reject code and never counted. See the DEVIATION
            // on this method - the throw replaces the source's fall-through because the unit of work is
            // already poisoned. Tracked in DECISION_LOG.md.
            RejectCode neverConsumedFailReason = RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE;
            throw translateStoreFailure(storeFailure,
                    "ACCOUNT REWRITE FAILED, app/cbl/CBTRN02C.cbl:L554 would have set reason "
                            + neverConsumedFailReason.toFailReasonField() + " "
                            + neverConsumedFailReason.getDescription() + " and continued",
                    DD_ACCTFILE, RELATION_ACCOUNT, OPERATION_REWRITE, renderAccountKey(account));
        }
        LOG.debug("Applied transaction amount to account {}", renderAccountKey(account));

        // :L560 EXIT. On the success path the source leaves WS-VALIDATION-FAIL-REASON untouched at zero.
        return null;
    }

    /**
     * {@code 2900-WRITE-TRANSACTION-FILE}, {@code app/cbl/CBTRN02C.cbl:L562-L579}.
     *
     * <p>{@code :L563} primes {@code APPL-RESULT} with 8, {@code :L564} writes the transaction record, and
     * {@code :L566-L578} apply the guard: {@code '00'} continues, anything else displays
     * {@code 'ERROR WRITING TO TRANSACTION FILE'} at {@code :L574}, renders the status through
     * {@code 9910} and abends through {@code 9999}. The priming value and the {@code APPL-AOK} and
     * {@code APPL-EOF} condition names are published by {@link FileStatusMapper} and are not restated here.
     *
     * <p>This is the last of the three writes, so a duplicate transaction identifier surfaces here.
     * {@code V1__create_schema.sql} declares {@code pk_transaction} on {@code tran_id} plus three foreign
     * keys - {@code fk04_transaction_card}, {@code fk05_transaction_type} and
     * {@code fk06_transaction_category} - any of which can reject the row. Each is translated to a typed
     * exception carrying the driver's own detail as cause; none is retried and none is silently upserted,
     * because {@code :L564} is a {@code WRITE} and a keyed dataset rejects a repeated key.
     *
     * @param transaction the record built by {@code 2000-POST-TRANSACTION}, never {@code null}
     * @throws DuplicateRecordException if {@code pk_transaction} collides
     * @throws DataIntegrityException if one of the three foreign keys rejects the row
     * @throws FatalProcessingException if the store fails for any other reason
     */
    private void writeTransactionFile(final Transaction transaction) {
        // :L563 MOVE 8 TO APPL-RESULT / :L564 WRITE FD-TRANFILE-REC FROM TRAN-RECORD
        try {
            transactionRepository.save(transaction);
        } catch (DataAccessException storeFailure) {
            // :L566-L578 guard accepting '00' only, then DISPLAY + 9910 + 9999.
            throw translateStoreFailure(storeFailure, TRANFILE_WRITE_FAILURE_TEXT, DD_TRANFILE,
                    RELATION_TRANSACTION, OPERATION_WRITE,
                    "TRAN-ID " + transaction.getTransactionId());
        }
        // :L579 EXIT.
        LOG.debug("Posted transaction {}", transaction.getTransactionId());
    }

    /**
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}, {@code app/cbl/CBTRN02C.cbl:L692-L705}.
     *
     * <p>The shape is proven three ways and getting the precision wrong is the easiest mistake in this file.
     * The format comment at {@code :L149} reads
     * {@code EEEE-MM-DD-UU.MM.SS.HH0000}. The receiving field {@code DB2-FORMAT-TS} is
     * {@code PIC X(26)} at {@code :L159}, and its redefinition at {@code :L160-L174} decomposes into four,
     * one, two, one, two, one, two, one, two, one, two, one, two and four characters, which sums to exactly
     * 26. And {@code COB-MIL} at {@code :L157} is {@code PIC X(2)} receiving the seventh component of
     * {@code FUNCTION CURRENT-DATE}, which is <b>hundredths</b> of a second, moved into
     * {@code DB2-MIL PIC 9(002)} at {@code :L173}.
     *
     * <p>So the value is {@code yyyy-MM-dd-HH.mm.ss.} followed by <b>two</b> fraction digits and then the
     * four literal characters {@code 0000} that {@code :L701} moves into {@code DB2-REST PIC X(04)}. It is
     * hundredths plus {@code 0000} - <b>not</b> milliseconds, and <b>not</b> nanoseconds. Emitting three or
     * nine fraction digits would produce a 27- or 33-character value and every generated timestamp would
     * differ from the legacy baseline.
     *
     * <p>The result is a {@link String} because {@code TRAN-PROC-TS} is {@code PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L18} over a {@code CHAR(26)} column. It is never a
     * {@code LocalDateTime}, a {@code Timestamp} or an {@code Instant}: those types cannot represent the
     * four trailing zeros, and round-tripping through them would lose the very characters the format
     * mandates.
     *
     * <p>The current instant comes from the injected {@link Clock} rather than from
     * {@code LocalDateTime.now()}, so the value is deterministic under test and the zone is a documented
     * decision rather than an ambient one. {@link Locale#ROOT} is fixed on the formatter so no host locale
     * can alter the digits.
     *
     * <p>The length is asserted rather than assumed. The contract is exactly
     * {@value #DB2_FORMAT_TS_WIDTH} characters; a violation can only mean the pattern was edited, so it is
     * reported as fatal rather than silently truncated or padded.
     *
     * @return the 26-character timestamp, never {@code null}
     * @throws FatalProcessingException if the rendered value is not exactly {@value #DB2_FORMAT_TS_WIDTH}
     *                                  characters, which would mean the format constant no longer matches
     *                                  the field it fills
     */
    private String getDb2FormatTimestamp() {
        // :L693 MOVE FUNCTION CURRENT-DATE TO COBOL-TS, then :L694-L700 the seven component moves and
        // :L702-L703 the separators - all of which the pattern expresses - and :L701 MOVE '0000' TO DB2-REST.
        String timestamp = DB2_TIMESTAMP_FORMAT.format(LocalDateTime.now(clock)) + DB2_REST;

        if (timestamp.length() != DB2_FORMAT_TS_WIDTH) {
            throw abend(String.format(Locale.ROOT,
                    "Generated processing timestamp is %d characters, but DB2-FORMAT-TS at "
                            + "app/cbl/CBTRN02C.cbl:L159 is PIC X(26) and TRAN-PROC-TS is CHAR(26). The "
                            + "format must emit yyyy-MM-dd-HH.mm.ss. plus two hundredths digits plus the "
                            + "literal 0000.",
                    timestamp.length()), null);
        }
        // :L705 EXIT.
        return timestamp;
    }

    /**
     * Reads {@code DALYTRAN-AMT} ({@code app/cpy/CVTRA06Y.cpy:L10}, {@code PIC S9(09)V99}) from the staged
     * record.
     *
     * <p>Every arithmetic site in the translation goes through here rather than calling
     * {@link DailyTransaction#getAmount()} directly, so the untrusted-input guard of clause A2 is applied
     * once and cannot be forgotten at a call site. {@code dalytran_amt} is {@code NUMERIC(11,2) NOT NULL} in
     * {@code V1__create_schema.sql}, so an absent value can only mean the fixed-width decode of the
     * 350-byte record failed - a data-integrity condition, not a reject outcome. No reject code in
     * {@link RejectCode} describes it, and inventing one would add a sixth constant.
     *
     * <p>The value is returned exactly as read: <b>no absolute value is taken and no sign is normalised</b>,
     * because {@code app/cbl/CBTRN02C.cbl:L548-L552} depends on the sign and
     * {@code :L403-L405} depends on the debit accumulator holding negative values.
     *
     * @param item the staged record, never {@code null} at any call site
     * @return the signed amount, exactly as staged
     * @throws DataIntegrityException if the amount is absent
     */
    private BigDecimal transactionAmount(final DailyTransaction item) {
        return requireMoney(item.getAmount(), "DALYTRAN-AMT", RELATION_DAILY_TRANSACTION);
    }

    /**
     * Guards a monetary value that the schema declares {@code NOT NULL} before it is used in arithmetic.
     *
     * <p>This is a null guard on a column that cannot be null, not a re-validation of scale or magnitude:
     * the entities already enforce those in their own setters, and duplicating the check here would put the
     * same rule in two places. A {@code null} reaching this method therefore means the row was assembled
     * outside the entity - by a partial decode or by a test double - and the condition is reported with the
     * COBOL field name so the failing field is identifiable from the log alone.
     *
     * <p>Clause B2 is satisfied structurally rather than by inspection: because every arithmetic operand in
     * this class is read through this method, there is no path on which a {@code null} can reach
     * {@link BigDecimal#add(BigDecimal)}.
     *
     * @param value the value as read from the entity, possibly {@code null}
     * @param cobolField the COBOL field name to report, for example {@code ACCT-CURR-CYC-DEBIT}
     * @param relation the relation the value was read from, for the diagnostic
     * @return {@code value}, unchanged and unrounded
     * @throws DataIntegrityException if {@code value} is {@code null}
     */
    private BigDecimal requireMoney(final BigDecimal value, final String cobolField, final String relation) {
        if (value == null) {
            throw integrityFailure(relation, String.format(Locale.ROOT,
                    "%s is absent. V1__create_schema.sql declares the column NOT NULL and the COBOL field "
                            + "is a signed packed-decimal, so no value cannot be read as zero without "
                            + "changing the arithmetic of app/cbl/CBTRN02C.cbl.", cobolField), null);
        }
        return value;
    }

    /**
     * Fixes a computed monetary value at the two decimal places every {@code V99} field in this path
     * declares.
     *
     * <p>{@link RoundingMode#HALF_EVEN} is stated explicitly rather than left to a default, per clause A1.
     * In practice no rounding occurs: both operands of every addition here already carry scale two, so the
     * sum does too, and this call only normalises the scale so the value matches its
     * {@code NUMERIC(p,2)} column exactly. The mode is nevertheless fixed so that behaviour cannot change if
     * an operand ever arrives with a longer scale, and so that no site in this class can throw
     * {@link ArithmeticException} from an unrounded {@code setScale}.
     *
     * <p>{@link BigDecimal} is used throughout. There is no {@code float} or {@code double} anywhere in this
     * class, which is the property the security-audit gate asserts across the tree.
     *
     * @param value the computed value, never {@code null}
     * @return the value at scale {@value #MONEY_SCALE}
     */
    private BigDecimal scaledMoney(final BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Reads {@code ACCT-EXPIRAION-DATE} for the string comparison at {@code app/cbl/CBTRN02C.cbl:L414}.
     *
     * <p><b>PARITY TRAP 3 - the misspelling is deliberate and is retained.</b>
     * {@code app/cpy/CVACT01Y.cpy:L11} declares the field {@code ACCT-EXPIRAION-DATE PIC X(10)}, without the
     * {@code T} in {@code EXPIRATION}. {@code app/cbl/CBTRN02C.cbl:L414} references it under that spelling,
     * {@link Account} exposes it as {@link Account#getExpiraionDate()} and
     * {@code V1__create_schema.sql} names the column {@code acct_expiraion_date}. Correcting the spelling
     * anywhere in that chain would break the field contract the copybook establishes, so it is carried
     * through as documented provenance.
     *
     * <p>The value is normalised to exactly {@value #EXPIRY_COMPARISON_LENGTH} characters by
     * {@link #fixedWidth(String, int)}, which is what the {@code PIC X(10)} field guarantees on the
     * mainframe. That makes the comparison at {@code :L414} a comparison of two equal-length fixed-width
     * fields, exactly as it is in the source, and it is why no bounds check is needed at the comparison
     * itself.
     *
     * @param account the record read at {@code :L395}, never {@code null}
     * @param accountId the key it was read by, for the diagnostic; never {@code null}
     * @return the expiry date as exactly {@value #EXPIRY_COMPARISON_LENGTH} characters
     * @throws DataIntegrityException if the value is absent
     */
    private String expiraionDate(final Account account, final Long accountId) {
        String value = account.getExpiraionDate();
        if (value == null) {
            throw integrityFailure(RELATION_ACCOUNT, String.format(Locale.ROOT,
                    "ACCT-EXPIRAION-DATE is absent for the account read at "
                            + "app/cbl/CBTRN02C.cbl:L395 by key %s. app/cpy/CVACT01Y.cpy:L11 declares it "
                            + "PIC X(10) and V1__create_schema.sql declares acct_expiraion_date "
                            + "CHAR(10) NOT NULL, so the comparison at :L414 has no left operand. The "
                            + "field name retains the copybook's misspelling of EXPIRATION deliberately.",
                    renderAccountId(accountId)), null);
        }
        return fixedWidth(value, EXPIRY_COMPARISON_LENGTH);
    }

    /**
     * Reads {@code DALYTRAN-ORIG-TS (1:10)} for the string comparison at
     * {@code app/cbl/CBTRN02C.cbl:L414}.
     *
     * <p><b>PARITY TRAP 2 - this is the originating timestamp, not the processing timestamp, and the
     * comparison is a string comparison.</b> {@code :L414} takes a reference-modified ten-character slice of
     * {@code DALYTRAN-ORIG-TS}, which {@code app/cpy/CVTRA06Y.cpy:L16} declares {@code PIC X(26)}. Neither
     * side of that comparison is parsed into a date, and neither may be: the caller compares the two
     * ten-character strings with {@link String#compareTo(String)} so that a malformed value orders exactly
     * as the source orders it rather than throwing a parse error the source cannot raise.
     *
     * <p><b>The bounds check is structural, so no {@code substring} in this class can throw.</b> The value is
     * first normalised to {@value #ORIG_TS_WIDTH} characters - the width of the {@code PIC X(26)} field it
     * was read from - and only then sliced to {@value #EXPIRY_COMPARISON_LENGTH}. A value shorter than ten
     * characters is therefore space-filled on the right and compares as a space-filled field would, which is
     * precisely the mainframe behaviour: because a space sorts below every digit, such a record passes the
     * expiry test. That is reported at warning level so the condition is observable, per clause A4, but it is
     * not escalated - escalating would reject a record the source accepts.
     *
     * <p>The card number is deliberately absent from that warning. The staged record carries a sixteen-digit
     * card number and the tree's convention is that never emitting it is the primary defence rather than
     * masking it after the fact, so the transaction identifier locates the record instead.
     *
     * @param item the staged record, never {@code null}
     * @return the first {@value #EXPIRY_COMPARISON_LENGTH} characters of the originating timestamp
     * @throws DataIntegrityException if the originating timestamp is absent
     */
    private String origTsDatePrefix(final DailyTransaction item) {
        String origTs = item.getOrigTs();
        if (origTs == null) {
            throw integrityFailure(RELATION_DAILY_TRANSACTION, String.format(Locale.ROOT,
                    "DALYTRAN-ORIG-TS is absent for TRAN-ID %s. app/cpy/CVTRA06Y.cpy:L16 declares it "
                            + "PIC X(26) and V1__create_schema.sql declares dalytran_orig_ts CHAR(26) "
                            + "NOT NULL, so the comparison at app/cbl/CBTRN02C.cbl:L414 has no right "
                            + "operand and the pass-through at :L436 has no source value.",
                    renderTransactionId(item.getTransactionId())), null);
        }
        if (origTs.length() < EXPIRY_COMPARISON_LENGTH) {
            LOG.warn("DALYTRAN-ORIG-TS for TRAN-ID {} is {} characters, shorter than the {} the expiry "
                            + "comparison at app/cbl/CBTRN02C.cbl:L414 slices; it is space-filled to the "
                            + "PIC X(26) width and compares as the mainframe field would, which passes the "
                            + "expiry test because a space sorts below every digit",
                    renderTransactionId(item.getTransactionId()), origTs.length(), EXPIRY_COMPARISON_LENGTH);
        }
        return fixedWidth(fixedWidth(origTs, ORIG_TS_WIDTH), EXPIRY_COMPARISON_LENGTH);
    }

    /**
     * Applies the COBOL alphanumeric {@code MOVE} semantics of a {@code PIC X(n)} receiving field: pad on
     * the right with spaces when the value is shorter, truncate on the right when it is longer.
     *
     * <p>One method covers both fixed-width reads in this class because it is one concept, not two. It is
     * private and local, so the prohibition on a shared helper or utility component is not engaged; what
     * that prohibition forbids is a separate file, and duplicating this logic per call site would breach
     * clause C3 instead.
     *
     * <p>Truncation is not defensive padding dressed up as a guard - it is the receiving field's documented
     * behaviour, and reproducing it is what keeps a comparison against a {@code PIC X(10)} field a
     * comparison of ten characters on both sides.
     *
     * @param value the value read from the entity, never {@code null}
     * @param width the receiving field width, always positive
     * @return a value of exactly {@code width} characters
     */
    private static String fixedWidth(final String value, final int width) {
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Assembles {@code FD-TRAN-CAT-KEY} from the three sources {@code app/cbl/CBTRN02C.cbl:L469-L471} read
     * it from.
     *
     * <p>The component order is the one {@code app/cpy/CVTRA01Y.cpy:L5-L8} declares:
     * {@code TRANCAT-ACCT-ID PIC 9(11)}, {@code TRANCAT-TYPE-CD PIC X(02)}, {@code TRANCAT-CD PIC 9(04)},
     * seventeen bytes in total - which is the key length the catalogue records for {@code TCATBALF} in
     * {@code app/catlg/LISTCAT.txt}. The order is preserved because it is the browse order the interest
     * calculation's account-level control break depends on, so it is a contract rather than a convention.
     *
     * <p><b>The account identifier comes from the cross-reference record, not from the staged
     * transaction.</b> {@code :L469} moves {@code XREF-ACCT-ID}; only {@code :L470} and {@code :L471} read
     * the staged record. Taking all three from one place is the easy mistake here, and it would silently
     * post to the wrong account whenever the staged record's own account view disagreed with the
     * cross-reference.
     *
     * <p>All three components are guarded. Each is {@code NOT NULL} in {@code V1__create_schema.sql}, so an
     * absent value cannot be defaulted without fabricating a key, and a fabricated key would post a balance
     * to a row the source would never have touched.
     *
     * @param item the staged record, supplying the type and category codes
     * @param crossReference the record resolved at {@code :L383}, supplying the account identifier
     * @return the composite key, never {@code null}
     * @throws DataIntegrityException if any of the three components is absent
     */
    private TransactionCategoryBalanceId categoryBalanceKey(final DailyTransaction item,
                                                            final CardCrossReference crossReference) {
        // :L469 MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID
        Long accountId = crossReference.getAccountId();
        if (accountId == null) {
            throw integrityFailure(RELATION_TCATBAL,
                    "XREF-ACCT-ID is absent on the cross-reference record, so the TRANCAT-ACCT-ID "
                            + "component of the key moved at app/cbl/CBTRN02C.cbl:L469 cannot be formed. "
                            + "app/cpy/CVACT03Y.cpy:L7 declares it PIC 9(11).", null);
        }

        // :L470 MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
        String typeCode = item.getTypeCode();
        if (typeCode == null || typeCode.isBlank()) {
            throw integrityFailure(RELATION_TCATBAL, String.format(Locale.ROOT,
                    "DALYTRAN-TYPE-CD is absent or blank for TRAN-ID %s, so the TRANCAT-TYPE-CD component "
                            + "of the key moved at app/cbl/CBTRN02C.cbl:L470 cannot be formed. "
                            + "app/cpy/CVTRA06Y.cpy:L6 declares it PIC X(02) and fk08_tcatbal_category "
                            + "requires it to resolve.", renderTransactionId(item.getTransactionId())), null);
        }

        // :L471 MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD
        Integer categoryCode = item.getCategoryCode();
        if (categoryCode == null) {
            throw integrityFailure(RELATION_TCATBAL, String.format(Locale.ROOT,
                    "DALYTRAN-CAT-CD is absent for TRAN-ID %s, so the TRANCAT-CD component of the key "
                            + "moved at app/cbl/CBTRN02C.cbl:L471 cannot be formed. "
                            + "app/cpy/CVTRA06Y.cpy:L7 declares it PIC 9(04) and fk08_tcatbal_category "
                            + "requires it to resolve.", renderTransactionId(item.getTransactionId())), null);
        }

        return new TransactionCategoryBalanceId(accountId, typeCode, categoryCode);
    }

    /**
     * Renders {@code FD-TRAN-CAT-KEY} as the seventeen characters {@code app/cbl/CBTRN02C.cbl:L476-L477}
     * displays.
     *
     * <p>That {@code DISPLAY} emits the key group directly, so its rendering is the group's own storage
     * image: {@code TRANCAT-ACCT-ID PIC 9(11)} zero-filled to eleven digits, {@code TRANCAT-TYPE-CD PIC
     * X(02)} space-padded to two characters, and {@code TRANCAT-CD PIC 9(04)} zero-filled to four digits.
     * Reproducing the widths keeps the log line comparable with the legacy baseline character for character,
     * which is what the boundary-parity gate diffs.
     *
     * <p>None of the three components can be absent - {@link #categoryBalanceKey(DailyTransaction,
     * CardCrossReference)} rejects that before a key is constructed - but this method is also called with a
     * key read back from a persisted row, so each component is rendered defensively rather than dereferenced
     * blind. A diagnostic must never be the thing that throws.
     *
     * @param key the composite key, never {@code null}
     * @return the seventeen-character key image
     */
    private static String renderTranCatKey(final TransactionCategoryBalanceId key) {
        Long accountId = key.getAccountId();
        String typeCd = key.getTypeCd();
        Integer catCd = key.getCatCd();

        return renderAccountId(accountId)
                + fixedWidth(typeCd == null ? "" : typeCd, 2)
                + (catCd == null ? "????" : String.format(Locale.ROOT, "%04d", catCd));
    }

    /**
     * Renders the key of the account being updated, for the diagnostics of
     * {@code 2800-UPDATE-ACCOUNT-REC}.
     *
     * @param account the record read at {@code app/cbl/CBTRN02C.cbl:L395}, never {@code null}
     * @return the eleven-digit account key image
     */
    private static String renderAccountKey(final Account account) {
        return renderAccountId(account.getAccountId());
    }

    /**
     * Renders an account identifier as {@code ACCT-ID PIC 9(11)} stores it: eleven zero-filled digits.
     *
     * @param accountId the identifier, or {@code null} if it could not be read
     * @return the eleven-character image, or a fixed placeholder of the same width when the identifier is
     * absent, so that a diagnostic never throws and never varies in width
     */
    private static String renderAccountId(final Long accountId) {
        return accountId == null ? "???????????" : String.format(Locale.ROOT, "%011d", accountId);
    }

    /**
     * Renders a transaction identifier for a diagnostic.
     *
     * <p>{@code DALYTRAN-ID} is {@code PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:L5}, so it is emitted as
     * read rather than reformatted. It is the only field this class uses to locate a record in a log line:
     * <b>the card number is never emitted</b>, because the staged record carries a sixteen-digit primary
     * account number and the tree's stated convention is that not emitting it is the primary defence, with
     * masking only the backstop.
     *
     * @param transactionId the identifier, or {@code null} if it could not be read
     * @return the identifier, or a fixed placeholder when it is absent
     */
    private static String renderTransactionId(final String transactionId) {
        return transactionId == null ? "(absent)" : transactionId;
    }

    /**
     * Builds the abend that {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBTRN02C.cbl:L707-L712}) raises.
     *
     * <p>{@code :L710} moves {@code 999} into {@code ABEND-CODE} and {@code :L711} calls
     * {@code CEE3ABD}, which yields process return code {@code 12}. Both values are published by
     * {@link FatalProcessingException} as {@link FatalProcessingException#BATCH_ABEND_CODE} and
     * {@link FatalProcessingException#BATCH_RETURN_CODE} and are <b>not redeclared here</b>: that exception
     * owns the abend contract, exactly as {@link FileStatusMapper} owns the status-to-exception decision and
     * the four-character {@code FILE STATUS IS: NNNN} rendering. The payload is the four-field
     * {@code CABENDD.CPY} group.
     *
     * <p>The exception is returned rather than thrown so that the {@code throw} stays visible at the call
     * site and the compiler can see that control leaves there.
     *
     * @param abendMessage what happened, carrying {@code ABEND-MSG PIC X(72)}
     * @param cause the underlying throwable, or {@code null} when the condition originated here; when
     * present it is always preserved, per clause B4
     * @return the abend to throw
     */
    private static FatalProcessingException abend(final String abendMessage, final Throwable cause) {
        LOG.error(abendMessage, cause);
        return new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT, ABEND_REASON, abendMessage, cause);
    }

    /**
     * Builds the exception for a value the schema declares present but which is absent, or for a
     * relationship the data cannot satisfy.
     *
     * <p>These conditions are distinct from the five reject codes. A reject is a <b>business outcome</b> that
     * {@code app/cbl/CBTRN02C.cbl:L213-L215} counts and writes to the rejects dataset, and it drives return
     * code 4; an absent {@code NOT NULL} column is a defect in the staged data or in the reader that no
     * reject code describes. Reporting it as a reject would inflate the reject count and so corrupt the
     * return-code decision at {@code :L229-L231}, which is why it is a typed exception instead.
     *
     * <p>{@link DataIntegrityException} is chosen over {@link FatalProcessingException} because the condition
     * is specific and attributable to one relation, and the exception carries that relation for the caller.
     *
     * @param relation the relation the value belongs to
     * @param detail what specifically is absent or unsatisfiable, with its copybook and DDL citation
     * @param cause the underlying throwable, or {@code null} when the condition originated here
     * @return the exception to throw
     */
    private static DataIntegrityException integrityFailure(final String relation, final String detail,
                                                          final Throwable cause) {
        String message = String.format(Locale.ROOT,
                "Daily transaction posting cannot proceed against relation %s: %s", relation, detail);
        LOG.error(message, cause);
        return new DataIntegrityException(message, null, relation, cause);
    }

    /**
     * Translates a store failure on one of the three writes into the typed exception its {@code FILE STATUS}
     * guard implies.
     *
     * <p>Each of {@code 2700-A} ({@code :L512}), {@code 2700-B} ({@code :L530}),
     * {@code 2800} ({@code :L555}) and {@code 2900} ({@code :L566}) applies the same guard shape, and this
     * method is the one place the source's outcome is reproduced: display the paragraph's own failure text,
     * then abend. The mapping follows the authoritative status-to-exception table -
     * {@code '22'} is a duplicate key, {@code '23'} is a record not found, and anything unexpected is the
     * abend of {@code 9999-ABEND-PROGRAM}:
     *
     * <ul>
     * <li>{@link DuplicateKeyException} carries {@code FILE STATUS '22'} and becomes
     * {@link DuplicateRecordException}. The colliding constraint is {@code pk_transaction} or
     * {@code pk_transaction_category_balance}. It is never retried and never silently upserted: the source
     * verb is a {@code WRITE} to a keyed dataset, which rejects a repeated key.</li>
     * <li>{@link DataIntegrityViolationException} becomes {@link DataIntegrityException}. The driver does not
     * name the constraint in a portable field, so the message names the candidates that
     * {@code V1__create_schema.sql} declares on the relation and the retained cause carries the driver's own
     * detail.</li>
     * <li>Anything else becomes {@link FatalProcessingException} with abend code
     * {@value FatalProcessingException#BATCH_ABEND_CODE} and return code
     * {@value FatalProcessingException#BATCH_RETURN_CODE}, which is what the guard's
     * {@code PERFORM 9999-ABEND-PROGRAM} does.</li>
     * </ul>
     *
     * <p><b>{@code FILE STATUS '23'} is never produced here</b>, and its absence is deliberate. The only
     * site in this program that tolerates a record not found is the category-balance <b>read</b> at
     * {@code :L481}, whose {@code '00' OR '23'} guard is delegated to
     * {@link FileStatusMapper#requireCategoryBalanceReadSuccess(String)}. The {@code WRITE} at {@code :L512}
     * and the {@code REWRITE} at {@code :L530} accept {@code '00'} only, so a failure on either is an error
     * and not a control path.
     *
     * <p>The cause is preserved on every branch, per clause B4, and the failure text is emitted verbatim so
     * the log line matches the legacy {@code DISPLAY}. The reported key is an account or transaction
     * identifier or a category-balance key image - never a card number.
     *
     * @param storeFailure the failure raised by the store, never {@code null}
     * @param failureText the paragraph's own {@code DISPLAY} literal, verbatim including case
     * @param ddName the DD name of the dataset the paragraph writes
     * @param relation the relation behind that DD name
     * @param operation {@code WRITE} or {@code REWRITE}, matching the source verb
     * @param key the key image being written, for the diagnostic
     * @return the exception to throw
     */
    private static CardDemoException translateStoreFailure(final DataAccessException storeFailure,
                                                          final String failureText, final String ddName,
                                                          final String relation, final String operation,
                                                          final String key) {
        if (storeFailure instanceof DuplicateKeyException) {
            String message = String.format(Locale.ROOT,
                    "%s. The %s of DD %s (relation %s) for key %s was rejected as a duplicate key, which is "
                            + "FILE STATUS '22'. The COBOL verb writes a keyed dataset, so a repeated key "
                            + "fails the step; do not retry and do not upsert.",
                    failureText, operation, ddName, relation, key);
            LOG.error(message);
            return new DuplicateRecordException(message, ddName, key, storeFailure);
        }

        if (storeFailure instanceof DataIntegrityViolationException) {
            String message = String.format(Locale.ROOT,
                    "%s. A constraint rejected the %s of DD %s (relation %s) for key %s. The exception does "
                            + "not name the constraint in a portable field, so it is one of those "
                            + "V1__create_schema.sql declares on this relation, with a duplicate key "
                            + "excluded because that condition is handled separately; the retained cause "
                            + "carries the driver's own constraint detail.",
                    failureText, operation, ddName, relation, key);
            LOG.error(message);
            return new DataIntegrityException(message, null, relation, storeFailure);
        }

        return abend(String.format(Locale.ROOT,
                "%s. The %s of DD %s (relation %s) for key %s failed, and the condition is neither a "
                        + "duplicate key nor a constraint violation, so the guard in "
                        + "app/cbl/CBTRN02C.cbl reaches PERFORM 9999-ABEND-PROGRAM.",
                failureText, operation, ddName, relation, key), storeFailure);
    }
}
