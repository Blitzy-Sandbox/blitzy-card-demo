/*
 ******************************************************************
 * Program     : S3GenerationKeyIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 AWS integration test (Failsafe tier)
 * Function    : Verifies GDG (+1)/(0) generation semantics as S3 key prefixes and byte-exact record
 *               lengths 350/430/133/100/80 plus the 32-byte statement key.
 * Source      : app/jcl/DEFGDGB.jcl:24-59 + app/jcl/DALYREJS.jcl:24-28 @ 7756d89 (7 GDG bases)
 * Source      : app/cbl/CBTRN02C.cbl:176-182 @ 7756d89 (REJECT-RECORD 350 + 80 = 430)
 * Source      : app/jcl/POSTTRAN.jcl:34-38 @ 7756d89 (DALYREJS DCB=(RECFM=F,LRECL=430,BLKSIZE=0),
 *               DSN=AWS.M2.CARDDEMO.DALYREJS(+1))
 * Source      : app/proc/TRANREPT.prc:29,76 @ 7756d89 (LRECL=350 input, LRECL=133 report line)
 * Source      : app/jcl/CREASTMT.JCL:30,32,50,69,89,94 @ 7756d89 (KEYS(32 0), RECORDSIZE(350 350),
 *               LRECL 80 and 100)
 * Source      : app/cpy/COSTM01.CPY:20-24 @ 7756d89 (TRNX-KEY 16+16=32, TRNX-REST 318, total 350)
 * Source      : app/csd/CARDDEMO.CSD:502-503 @ 7756d89 (RECORDSIZE(80) RECORDFORMAT(FIXED))
 * Source      : app/cpy/CVTRA05Y.cpy:4-18 @ 7756d89 (the 350-byte transaction offset map)
 * Note        : No COBOL analogue - relative generation references become deterministic S3 key prefixes.
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
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Proves the two halves of transformation invariant 11 against a real object store: that a relative
 * generation reference becomes a deterministic key prefix, and that record length survives the object-store
 * boundary byte for byte.
 *
 * <h2>1. What it does</h2>
 *
 * <p>The legacy system addressed its sequential output through generation data groups. A write named the
 * <em>next</em> generation and a read named the <em>current</em> one, and the catalogue resolved both. There
 * is no catalogue here, so the resolution has to be reconstructed from key ordering - and that
 * reconstruction is only correct if two properties hold, neither of which is self-evident. This class
 * establishes both, and nothing else: bucket existence, bucket roles, versioning configuration and the
 * seven-base accounting belong to the sibling provisioning test, and the first-in-first-out queue belongs to
 * the sibling queue test. A versioned bucket is created here as a <em>precondition</em> only, never as the
 * subject of an assertion.
 *
 * <dl>
 *   <dt>A next-generation write lands under a monotonically increasing prefix</dt>
 *   <dd>{@code app/jcl/POSTTRAN.jcl:34-38} allocates the reject dataset as
 *       {@code DISP=(NEW,CATLG,DELETE)} with {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)} - a literal relative
 *       next-generation reference, and the most direct evidence in the corpus that a write creates a new
 *       generation rather than replacing one. {@code app/proc/TRANREPT.prc:31,53,78} does the same for the
 *       transaction backup, the daily extract and the report. Each becomes a new object under a greater
 *       prefix, and the prefixes are asserted strictly increasing by <strong>explicit comparison</strong>,
 *       never by trusting the order a listing happens to return.</dd>
 *   <dt>A current-generation read resolves the lexicographically greatest prefix</dt>
 *   <dd>{@code app/proc/TRANREPT.prc:37,64} reads the generation the previous step wrote. Resolution is by
 *       <strong>prefix comparison, never by last-modified time</strong>: two objects written inside one
 *       millisecond carry the same timestamp, so a time-ordered resolution is not a function of the data.
 *       The decisive test writes the generations <em>out of order</em> - the greatest first and a lesser one
 *       last - so that a last-modified resolution and a prefix resolution disagree, and only the prefix
 *       resolution can pass.</dd>
 * </dl>
 *
 * <p>The ordering property has a boundary that is easy to miss and fatal when missed. Lexicographic order
 * and numeric order coincide only while the ordinal is rendered at a fixed width with leading zeros:
 * unpadded, {@code "10"} sorts below {@code "9"}, so the tenth generation stops being the current one. That
 * padding is asserted directly, and so is the bound beyond which it stops holding.
 *
 * <p><strong>Both properties above are properties of the key <em>convention</em>, and establishing them is
 * not the same thing as establishing that production implements it.</strong> The distinction is the subject
 * of the finding below, and of the third group in this class.
 *
 * <dl>
 *   <dt>The production handoff: a key composed by the writer, carried in a context, resolved by the reader</dt>
 *   <dd><strong>Finding, severity High, RESOLVED.</strong> An earlier revision of this class established the
 *       convention and nothing else: every key it asserted was one it had built itself, from
 *       {@link AbstractAwsIntegrationTest#generationPrefix(String, int)} or from this class's own
 *       {@code jobInstancePrefix}, and every resolution it asserted was
 *       {@link AbstractAwsIntegrationTest#currentGenerationKey(String, String)} - a listing this class
 *       performed. It imported no production writer and no production reader, so a regression in
 *       {@code com.cardemo.batch.writers.TransactionWriter}'s key composition or in
 *       {@code com.cardemo.batch.readers.TransactionBackupReader}'s resolution precedence could land with
 *       every test in the class still green. That is false green: the emulator was being treated as the
 *       implementation under test rather than as the external boundary.
 *       <p><em>Remediation, applied:</em> {@code ProductionGenerationKeyHandoff} drives the production
 *       writer, reads the concrete key back out of the {@code ExecutionContext} the writer published it into,
 *       and hands <em>that exact key</em> to the production reader over the emulator. Nothing in that group
 *       recomputes a key. It also covers the two precedence rules that only exist in production - a promoted
 *       key outranking the lexicographically greatest one, and a checkpointed key outranking a promoted one -
 *       and the absent-generation abend.
 *       <p><em>Evidence that the false green is closed, by mutation:</em> pinning the writer's ordinal to a
 *       constant, so two writes collide on one key, fails three of the seven handoff tests; deleting the
 *       reader's promoted-key precedence arm fails one. In both cases <strong>every one of the thirty tests
 *       that predate the group still passed</strong>, which is precisely the exposure the finding named.
 *       <p>The two convention groups are kept rather than deleted, because the convention is a real
 *       property and the boundary cases they cover - the padding bound, out-of-order writes, per-base
 *       scoping, retention non-enforcement, the absent-bucket failure - are not reachable through the
 *       production classes. What changed is that the harness helper is now <em>cross-checked against</em> the
 *       production resolution instead of standing in for it: the handoff group asserts that
 *       {@code currentGenerationKey} names the same key the production reader resolves, so the two cannot
 *       drift apart silently.</dd>
 * </dl>
 *
 * <p>The geometry half proves every width the corpus declares, each against its own locator, by writing a
 * synthetic payload and reading it back:
 *
 * <ul>
 *   <li><strong>350</strong> - the transaction and daily-transaction image, and the statement work record.
 *       {@code app/proc/TRANREPT.prc:29} declares {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)};
 *       {@code app/jcl/CREASTMT.JCL:32} declares {@code RECORDSIZE(350 350)} and {@code :50} declares
 *       {@code DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)}; and {@code app/cpy/COSTM01.CPY:20-24} composes a
 *       32-byte key with a 318-byte remainder, which is 350.</li>
 *   <li><strong>430</strong> - the reject record, and the one width that decomposes.
 *       {@code app/cbl/CBTRN02C.cbl:176-178} declares {@code 01 REJECT-RECORD.} as
 *       {@code 05 REJECT-TRAN-DATA PIC X(350).} followed by {@code 05 VALIDATION-TRAILER PIC X(80).}, and
 *       {@code :180-182} splits that trailer into {@code 05 WS-VALIDATION-FAIL-REASON PIC 9(04).} and
 *       {@code 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).} Independently corroborated by
 *       {@code app/jcl/POSTTRAN.jcl:36}, {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} - note
 *       {@code RECFM=F}, fixed <em>unblocked</em>, not {@code FB}.</li>
 *   <li><strong>133</strong> - the printed report line. {@code app/proc/TRANREPT.prc:76} declares
 *       {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} on the output of {@code :57},
 *       {@code //STEP10R EXEC PGM=CBTRN03C}.</li>
 *   <li><strong>100</strong> - the statement markup line. {@code app/jcl/CREASTMT.JCL:94} declares
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}, and {@code app/cbl/CBSTM03A.CBL:149} independently
 *       confirms it with {@code 05 HTML-FIXED-LN PIC X(100).}</li>
 *   <li><strong>80</strong> - the statement text line at {@code app/jcl/CREASTMT.JCL:89},
 *       {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)}; and, separately, the job-submission parameter record
 *       at {@code app/csd/CARDDEMO.CSD:502-503}, {@code RECORDSIZE(80)} with
 *       {@code RECORDFORMAT(FIXED)}. Equal values, different meanings, asserted apart.</li>
 *   <li><strong>32</strong> - the statement work-cluster key. {@code app/jcl/CREASTMT.JCL:30} declares
 *       {@code KEYS(32 0)} and {@code app/cpy/COSTM01.CPY:21-23} composes it from
 *       {@code 10 TRNX-CARD-NUM PIC X(16).} and {@code 10 TRNX-ID PIC X(16).}</li>
 * </ul>
 *
 * <p>Payloads are built <strong>synthetically</strong>, by the harness's fixed-width helpers. No file under
 * {@code app/data} and no test resource is read, copied or edited; the nine ASCII fixtures are owned
 * elsewhere. For the record, their geometry corroborates these widths exactly - the invariant across all
 * nine is that a fixture's byte count equals its row count multiplied by one more than its record width, and
 * the 350-byte one among them is spelled {@code dailytran.txt}, with "daily" in full, never with the
 * dataset's own {@code DALYTRAN} spelling. Nothing here transcodes the mainframe code page, and nothing here
 * decodes a zoned-decimal overpunch sign: that decoding belongs to the seed migration, where it is
 * position-aware from the picture clauses, and a second implementation could disagree with it without either
 * being obviously wrong.
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Run the whole suite with {@code ./mvnw -B -ntp clean verify}. <strong>This class is collected by
 * Failsafe, not Surefire.</strong> The root build binds {@code maven-failsafe-plugin} to
 * {@code **}{@code /integration/}{@code **}{@code /*Test.java} at {@code integration-test} and
 * {@code verify}, even though the class keeps the {@code Test} suffix, while {@code maven-surefire-plugin}
 * excludes that tree. A class moved up to {@code com/cardemo/integration}, to {@code com/cardemo}, or into a
 * sub-package invented beneath this one, matches neither include set: it is collected by neither plugin and
 * <strong>silently never runs</strong>, with both plugins reporting success and the build staying green.
 * That is the worst failure mode available here, so the path and the class name are fixed. After any change,
 * confirm the Failsafe report names this class; if Surefire names it, or neither does, that is a
 * <strong>Blocker</strong>.
 *
 * <p><strong>A reachable Docker socket is a prerequisite.</strong> The harness starts a PostgreSQL 16
 * container and a LocalStack container, and there is no in-memory substitute for either - an in-memory
 * object store would not exercise the key ordering that the whole generation model rests on. Where no daemon
 * or socket is available the correct report is that the gate is <em>blocked</em>, never an untested pass. The
 * environment this class was authored and executed against supplies Docker Engine 29.7.0 with Compose
 * v5.3.1, and host {@code java} 25.0.3 and {@code mvn} 3.9.11 are both on the path, so Maven runs directly
 * on the host; an earlier note claiming the host toolchain was absent and that Maven had to run inside a
 * pinned container is withdrawn as stale. Severity of what that stale note left behind: <strong>Low</strong>
 * - a wrong instruction, never a wrong artefact.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>The {@code test} profile is active, contributed by the harness. Container images are pinned rather
 *       than tagged {@code latest}: PostgreSQL 16 by content digest and LocalStack by exact release.</li>
 *   <li>The object-store client and template are Spring beans published by
 *       {@code com.cardemo.config.AwsConfig} and reached through the harness's accessors.
 *       <strong>No client is constructed here, no endpoint override is called, and no region, access key or
 *       secret key is supplied.</strong> The endpoint arrives from the emulator container the harness
 *       started, and it is written nowhere in this source.</li>
 *   <li>The three bucket names arrive as {@code carddemo.aws.s3.batch-input-bucket},
 *       {@code carddemo.aws.s3.batch-output-bucket} and {@code carddemo.aws.s3.statements-bucket}. This
 *       class does not write into them; it creates and destroys its own bucket so that it cannot disturb a
 *       sibling.</li>
 *   <li><strong>Time is pinned.</strong> Every generation prefix is derived from the harness's fixed clock
 *       and an explicit ordinal - never from the wall clock, a random value or a hash. The seven generation
 *       bases are configured as {@code carddemo.aws.s3.gdg-prefixes}, and the two this class exercises are
 *       the reject base and the report base.</li>
 *   <li><strong>Retention is documented, not enforced.</strong> {@code carddemo.aws.s3.gdg-retention-
 *       generations} records the resolved value; no lifecycle rule is created and none is asserted.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code Could not find a valid Docker environment}</dt>
 *   <dd>No reachable container runtime. Start one; a green build must never be obtainable by having no
 *       daemon.</dd>
 *   <dt>A dependency fails to resolve under {@code org.testcontainers}</dt>
 *   <dd>The <strong>Testcontainers 2.0.3 coordinate trap</strong>, severity <strong>Blocker</strong>. Only
 *       the prefixed module coordinates exist at 2.0.3 - {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. The bare {@code postgresql}, {@code localstack} and
 *       {@code junit-jupiter} identifiers under that group do not exist there and fail resolution outright.
 *       Compounding it, Spring Boot 3.5.11 already imports the Testcontainers bill of materials at a 1.x
 *       version, so importing a second one yields ordering-dependent resolution that may silently select
 *       1.x. Remediation is two-part and <em>both</em> parts are required: override the managed version
 *       through the {@code testcontainers.version} property rather than importing a second bill of
 *       materials, and use only prefixed coordinates. Overriding without renaming resolves artefacts that do
 *       not exist; renaming without overriding resolves the wrong version. The root build already does both.
 *       It is owned elsewhere, so a regression is repaired there and <strong>never</strong> by adding a
 *       dependency from this tier.</dd>
 *   <dt>The build fails on a warning that looks harmless</dt>
 *   <dd>{@code maven-compiler-plugin} runs at release 25 with {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning}, so one unused import, raw type, unchecked cast or deprecation fails the
 *       build. That is deliberate and is not to be relaxed.</dd>
 *   <dt>A bucket will not delete, reporting that it is not empty</dt>
 *   <dd>The bucket this class creates has versioning enabled, because object versioning is what retains
 *       what a generation replaced. A versioned bucket cannot be removed while any object
 *       <em>version</em> or delete marker survives, and deleting the visible objects is not enough. The
 *       harness's version-aware teardown handles it; a bucket created any other way will strand.</dd>
 *   <dt>The tenth generation is not resolved as the current one</dt>
 *   <dd>A prefix whose ordinal is not zero-padded to a fixed width. Unpadded, {@code "10"} sorts below
 *       {@code "9"}, so the current generation silently becomes the ninth and stays there. This is asserted
 *       directly rather than assumed.</dd>
 *   <dt>Every record is one byte longer than its declaration</dt>
 *   <dd>A line separator appended per record. A fixed-width record carries no separator: the reader locates
 *       record <em>n</em> by multiplying, so an added byte shifts everything after the first record. This
 *       class asserts that a multi-record object is an exact multiple of its record length and that no
 *       carriage return or line feed appears anywhere in it.</dd>
 *   <dt>Context startup aborts with {@code Could not resolve placeholder} naming the token signing key</dt>
 *   <dd>Severity <strong>High</strong>, and the defect is not in this file.
 *       {@code src/main/resources/application.yml} maps the signing key to an environment variable with no
 *       default, so that no deployment can boot with a key an attacker already knows, and
 *       {@code src/main/resources/application-test.yml} supplies no test value, so the context refresh
 *       aborts before any test runs. Remediation, for the owner of that file: supply a non-production test
 *       value in the {@code test} profile. Until it does, the harness <em>generates</em> an ephemeral one per
 *       context rather than declaring a literal, so nothing is committed. It is reported here and
 *       <strong>never patched from here</strong>, and no key material is written anywhere.</dd>
 * </dl>
 *
 * <h2>Legacy defects reproduced or logged, never repaired</h2>
 *
 * <p>Parity is the contract, so a defect in the system of record is preserved rather than corrected. Four
 * bear on this class, and each is logged with its locator instead of being quietly reconciled.
 *
 * <ol>
 *   <li><strong>The statement markup width is declared twice, inconsistently, inside one job.</strong>
 *       {@code app/jcl/CREASTMT.JCL:69} declares {@code DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)} for
 *       {@code HTMLFILE} in the {@code STEP030} pre-delete, while {@code :94} declares
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} for the same data set in the {@code STEP040}
 *       execution. <strong>100 is the real width</strong>, independently confirmed by
 *       {@code app/cbl/CBSTM03A.CBL:149}. This class asserts 100 and asserts that 80 is
 *       <em>not</em> that width, which is how the mismatch is recorded rather than resolved. Severity:
 *       <strong>Low</strong> - the pre-delete step only removes the data set, so the wrong length there has
 *       no effect. Remediation is deliberately not applied: {@code app/} is frozen.</li>
 *   <li><strong>A corrupted data-definition line survives in the same job.</strong>
 *       {@code app/jcl/CREASTMT.JCL:90} reads, verbatim,
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - a splice of three
 *       fragments. Logged, never fixed. Severity: <strong>Low</strong>; the surrounding
 *       {@code STMTFILE} allocation still carries its own {@code LRECL=80} at {@code :89}, which is the
 *       value used.</li>
 *   <li><strong>A procedure's internal name differs from the member that resolves it.</strong>
 *       {@code app/proc/TRANREPT.prc:21} is {@code //STEP01R EXEC PROC=REPROC,} while the member an
 *       {@code EXEC PROC=TRANREPT} resolves is {@code TRANREPT}. Logged, never fixed. Severity:
 *       <strong>Low</strong>.</li>
 *   <li><strong>The statement projection silently truncates two bytes and drops the filler.</strong>
 *       {@code app/jcl/CREASTMT.JCL:44} is {@code //STEP010  EXEC PGM=SORT}, and its control cards are
 *       {@code :53}, {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, and {@code :54},
 *       {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}. That projection emits the 16-byte card
 *       number, then 262 bytes of the record head, then 50 bytes from offset 279 - which is the whole
 *       26-byte originating timestamp plus only the <em>first 24</em> of the 26 processing-timestamp bytes,
 *       and it discards the 20-byte trailing filler entirely, for 328 bytes of a 350-byte record. The
 *       350-byte offset map that makes this readable, taken from {@code app/cpy/CVTRA05Y.cpy:4-18} and
 *       consistent with the {@code SYMNAMES} at {@code app/proc/TRANREPT.prc:39-40}
 *       ({@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}), is: identifier 1-16, type
 *       17-18, category 19-22, source 23-32, description 33-132, amount 133-143, merchant identifier
 *       144-152, merchant name 153-202, merchant city 203-252, merchant postcode 253-262, card number
 *       263-278, originating timestamp 279-304, processing timestamp 305-330, filler 331-350.
 *       <strong>The projection is documented here and deliberately not asserted</strong>: asserting it
 *       would require reproducing the truncation exactly, and asserting a corrected projection is
 *       forbidden. This class asserts the widths, which is the part it owns. Severity:
 *       <strong>Medium</strong> - a Java projection that "fixed" the truncation would differ from the
 *       legacy output in a way that reads like a Java defect and is not one. Remediation for the owner of
 *       the statement job: reproduce the 328-byte shape verbatim.</li>
 * </ol>
 *
 * <p><strong>Medium - the report base's retention limit is declared twice, inconsistently, and this is the
 * one legacy inconsistency actually resolved.</strong> {@code app/jcl/DEFGDGB.jcl:37-39} declares
 * {@code (NAME(AWS.M2.CARDDEMO.TRANREPT) LIMIT(5) SCRATCH)} while {@code app/jcl/REPTFILE.jcl:25-28}
 * declares {@code (NAME(AWS.M2.CARDDEMO.TRANREPT) LIMIT(10))} for the same base, with no {@code SCRATCH}.
 * A single lifecycle value has to be chosen, so it is <strong>resolved to 10</strong>, the larger, so that
 * nothing the legacy system would have kept is discarded; the other six bases all declare {@code LIMIT(5)}.
 * Retention then becomes a <em>documented</em> lifecycle rule and not an enforced one, so
 * <strong>no lifecycle configuration is created and none is asserted</strong>. What is asserted instead is
 * the consequence: writing more generations than the resolved limit trims nothing, and the current
 * generation is still the greatest. Every resource is created idempotently and destroyed deterministically,
 * following the legacy precedent that {@code app/jcl/DEFGDGB.jcl} sets by following <em>each</em> of its six
 * {@code DEFINE GENERATIONDATAGROUP} statements with {@code IF LASTCC=12 THEN SET MAXCC=0}, at {@code :29},
 * {@code :35}, {@code :41}, {@code :47}, {@code :53} and {@code :59}. That is what lets this class pass when
 * run alone, in any order, and twice in succession.
 *
 * <h2>Information that is Not available</h2>
 *
 * <p>Three things are missing rather than merely undocumented, and each is stated plainly with what would be
 * needed to close it, because filling a gap with an invention is worse than leaving it open.
 *
 * <ol>
 *   <li><strong>The boundary-parity expected-output baseline is Not available.</strong> A search across
 *       expected, baseline, golden, system-output and per-data-set name patterns returned only data-set
 *       <em>definition</em> job control and zero captured data, and a byte-size sweep for 430-byte and
 *       133-byte artefacts returned nothing. <em>What is needed:</em> a captured 430-byte {@code DALYREJS}
 *       reject data set together with the resulting {@code TRANSACT}, {@code ACCTDATA} and
 *       {@code TCATBALF} images from a real {@code POSTTRAN} execution at a known input state.
 *       <strong>No baseline file is created here and none may be invented.</strong> A baseline produced by
 *       running the Java implementation and then asserted against is circular and is forbidden: it would
 *       prove only that the code agrees with itself. <strong>That is precisely why this class asserts
 *       round-trip byte-exactness against a synthetic payload</strong> - a claim that is true or false
 *       independently of the implementation - rather than against a fabricated golden file.</li>
 *   <li><strong>A file-unavailable exercise is Not available.</strong> A census across {@code app/cbl}
 *       found the file status {@code '35'} literal zero times and the corresponding not-open response code
 *       zero times, so the corpus never takes that path and no test for it is fabricated.
 *       <em>What is needed:</em> a legacy program that handles that status, or a stated requirement that
 *       the Java tier introduce the path as new behaviour.</li>
 *   <li><strong>A source locator for the CICS transaction-identifier field is Not available</strong>,
 *       severity <strong>Medium</strong>. That field name occurs zero times anywhere under {@code app/};
 *       the complete exchange-interface-block census in {@code app/cbl} is the communication-area length
 *       field 49 times and the attention-identifier field 16 times, and nothing else. It is supplied by the
 *       transaction monitor rather than by this corpus, so <strong>no {@code app/...} line reference for it
 *       may be fabricated</strong>. <em>What is needed:</em> nothing from this repository - the citation
 *       belongs to the vendor's own copybook, which is not part of it. A related finding, severity
 *       <strong>Medium</strong>, is reported and not patched: one sibling unit test does carry an
 *       {@code app/csd} attribution for that field, and the field does not appear in that member.
 *       Remediation for that file's owner: drop the {@code app/...} attribution and record the field as
 *       monitor-supplied.</li>
 * </ol>
 *
 * <p>Two further findings are recorded so that a reader is not left to rediscover them. <strong>Medium:</strong>
 * the coverage plugin's pinned coordinate is 0.8.12 and a prior record cites 0.8.14; the pinned value
 * governs and the divergence is recorded rather than resolved unilaterally. <strong>Low:</strong> the job
 * name on {@code app/jcl/OPENFIL.jcl:1} is misspelled relative to its member name; it is preserved and never
 * repaired.
 */
@DisplayName("S3 generation keys - GDG (+1)/(0) semantics and byte-exact record geometry")
class S3GenerationKeyIntegrationTest extends AbstractAwsIntegrationTest {

    /**
     * The context's own status mapper, needed by the production writer under test.
     *
     * <p>Injected rather than constructed, so the writer is driven with the same collaborator the
     * application wires. A hand-built mapper could translate a status the real one does not.
     */
    @Autowired
    private FileStatusMapper fileStatusMapper;

    /**
     * The context's own metric registrar, needed by the production writer under test.
     *
     * <p>Injected for the same reason: {@code MetricsConfig} is the single registrar of the four named
     * counters, and a second instance built here would register a second set on a registry nothing scrapes.
     */
    @Autowired
    private MetricsConfig metricsConfig;

    /**
     * The reject generation prefix, bound from the same configuration key the writer binds.
     *
     * <p>The writer takes it as a constructor argument with no inline default, so a harness that invented a
     * literal here could pass while the configured value was wrong. Binding the key proves the two agree.
     */
    @Value("${carddemo.aws.s3.gdg-prefixes.daly-rejs}")
    private String configuredRejectGdgPrefix;

    /**
     * Width of the zero-padded identifier segments the production writer renders.
     *
     * <p>Nineteen digits, because that is the widest decimal a {@code long} identifier can present and
     * padding to it is what makes the keys sort in numeric order as strings. This is the width the
     * production writer uses, and it is stated here so that a change to either side fails this suite rather
     * than silently reordering generations.
     */
    private final int productionIdentifierDigits = 19;

    // =================================================================================================
    // Named values. Instance fields, never static: Rule 1 Clause B forbids global mutable state, and the
    // harness permits no static field here beyond the two container holders it owns itself. Every one is
    // final, is a literal or is derived from a literal, and carries the locator that fixes it.
    // =================================================================================================

    /**
     * Key prefix of the reject generation base.
     *
     * <p>{@code app/jcl/DALYREJS.jcl:24-28} defines {@code (NAME(AWS.M2.CARDDEMO.DALYREJS) LIMIT(5)
     * SCRATCH)}, and {@code app/jcl/POSTTRAN.jcl:38} writes {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)} into
     * it. The spelling matches the {@code carddemo.aws.s3.gdg-prefixes} entry for that base so that an
     * object listing and the configured value read alike.
     */
    private final String rejectGenerationBase = "gdg/dalyrejs";

    /**
     * Key prefix of the report generation base.
     *
     * <p>The base whose retention limit is declared inconsistently - {@code app/jcl/DEFGDGB.jcl:37-39}
     * against {@code app/jcl/REPTFILE.jcl:25-28} - and whose output {@code app/proc/TRANREPT.prc:78} writes
     * as {@code DSN=AWS.M2.CARDDEMO.TRANREPT(+1)}. It is present so that base scoping can be proved: the
     * seven bases share one bucket, so resolving one must never reach into another.
     */
    private final String reportGenerationBase = "gdg/tranrept";

    /**
     * Object name written under a generation prefix, matching the data-definition name of the legacy step.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:34} names the reject allocation {@code //DALYREJS DD}. The generation
     * ordinal lives in the prefix, not in this name, which is what keeps one logical data set's generations
     * adjacent in a listing.
     */
    private final String rejectObjectName = "DALYREJS";

    /**
     * Resolved retention limit for the report base, in generations.
     *
     * <p>Ten, the larger of the two conflicting declarations, chosen because a single lifecycle value has to
     * exist. It is a <em>documented</em> value: no lifecycle rule is created from it and none is asserted.
     * What is asserted is that nothing trims a generation, which is what "documented, not enforced" means in
     * practice.
     */
    private final int resolvedRetentionGenerations = 10;

    /**
     * Number of records packed into a multi-record object.
     *
     * <p>Three is the smallest count that distinguishes a correct exact multiple from an off-by-one
     * separator defect at every width: with one record a stray separator is indistinguishable from a wider
     * record, and with two the arithmetic coincides too often to be persuasive.
     */
    private final int multiRecordCount = 3;

    /**
     * Deterministic job-instance identifier used for the job-instance-prefixed generation variant.
     *
     * <p>A literal, never a sequence value or a random number. The transformation maps the reject and report
     * outputs onto "a job-instance prefix", and a prefix that changes between runs would make an ordering
     * assertion unreproducible - which is the determinism Rule 1 Clause A requires and the reason a failure
     * here can be re-run and re-observed rather than merely re-hit.
     */
    private final long deterministicJobInstanceId = 42L;

    /**
     * Width the job-instance ordinal is rendered at, in digits.
     *
     * <p>Ten digits, wide enough that the padding cannot be exhausted by a realistic instance count, which
     * is what keeps lexicographic order and chronological order aligned for the job-instance rendering in
     * the same way the harness's four-digit generation ordinal does for the generation rendering.
     */
    private final int jobInstanceDigits = 10;

    // =================================================================================================
    // The production handoff. These are the collaborators and named values the ProductionGenerationKeyHandoff
    // group needs so that a generation key is COMPOSED by the production writer and RESOLVED by the
    // production reader, with the emulator as the external boundary rather than as the subject.
    // =================================================================================================

    /** The relation the writer inserts into; injected so the rows it creates can be removed again. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Generation prefix handed to the production writer and reader for the handoff group.
     *
     * <p>{@code TransactionBackupReader.DEFAULT_GENERATION_PREFIX}, the {@code TRANSACT.BKUP} base of
     * {@code app/proc/TRANREPT.prc:L27-L31}, so both ends of the handoff are configured with the value the
     * pipeline actually uses. It is handed in as a constructor argument rather than read from the context's
     * configuration, because the objects land in this class's own bucket and must not touch the shared one.
     */
    private final String handoffGenerationPrefix = TransactionBackupReader.DEFAULT_GENERATION_PREFIX;

    /**
     * Name of the execution-context entry the resolved generation key travels in.
     *
     * <p>{@code TransactionBackupReader} declares this constant privately, so there is no accessor to read it
     * from and the literal has to be restated here. It is not left to drift: the handoff tests assert that
     * {@code update(ExecutionContext)} writes the resolved key under <em>this</em> name and that seeding it
     * under this name changes what {@code open(ExecutionContext)} resolves, so a production rename fails here
     * rather than passing silently.
     */
    private final String generationObjectKeyContextEntry = "carddemo.gdg.transact-bkup.objectKey";

    /**
     * A card number the seed data actually holds, so the writer's insert satisfies {@code fk04_transaction_card}.
     *
     * <p>The first row of {@code app/data/ASCII/carddata.txt} as loaded by {@code V3__seed_data.sql}. A
     * fabricated number would be refused by the constraint and the failure would look like a writer defect.
     */
    private final String seededCardNumber = "0500024453765740";

    /** A transaction type the seed data holds, satisfying {@code fk05_transaction_type}. */
    private final String seededTypeCode = "01";

    /** A category the seed data holds for that type, satisfying {@code fk06_transaction_category}. */
    private final int seededCategoryCode = 1;

    /**
     * Transaction identifiers this class inserted, so that {@link #removeTheRowsTheWriterInserted()} removes
     * exactly those and nothing else.
     *
     * <p>The harness declares no class-level {@code @Transactional}, so nothing rolls back for us. Deleting by
     * recorded identifier rather than truncating keeps this class from disturbing a sibling that shares the
     * cached context.
     */
    private final List<String> insertedTransactionIds = new ArrayList<>();

    /**
     * Sole constructor, used by the JUnit Platform.
     *
     * <p>Explicit and empty. Every collaborator is injected into the harness and reached through its
     * accessors, so there is nothing for a constructor to do and nothing it may be given.
     */
    S3GenerationKeyIntegrationTest() {
        super();
    }

    // =================================================================================================
    // Private helpers. Pure where they can be; each one names the property it exists to make provable.
    // =================================================================================================

    /**
     * Creates this class's own versioned bucket, idempotently, and registers it for teardown.
     *
     * <p>Versioning is a <strong>precondition</strong> here and never a subject: object versioning is what
     * retains the content a generation replaced, so a generation model asserted over an unversioned bucket
     * would be asserting less than the model claims. Whether the application's own output bucket is
     * versioned is the sibling provisioning test's assertion, not this one's.
     *
     * <p>The bucket is this class's own rather than one of the three configured ones, so that nothing here
     * can disturb a sibling and so that teardown can remove it outright.
     *
     * @param role short suffix naming what the bucket is for; must be lowercase letters, digits and hyphens
     * @return the bucket name, never {@code null}
     */
    private String ownVersionedBucket(final String role) {
        return createVersionedBucket(scopedResourceName(role));
    }

    /**
     * Builds the object key a generation write lands on, expressing the <em>convention</em>.
     *
     * <p><strong>This is not a stand-in for the production key builder and must never be read as one.</strong>
     * It exists so that the boundary cases of the convention - the padding bound, an out-of-order write
     * sequence, per-base scoping, retention non-enforcement - can be constructed directly, which the
     * production writer cannot be made to do because it composes its own ordinal from the step's write count.
     * The claim that production <em>implements</em> this convention is established separately and only by
     * {@code ProductionGenerationKeyHandoff}, which asserts keys production produced and never one built
     * here.
     *
     * @param generationBase the per-base key prefix
     * @param generation the generation ordinal, 0 through the harness's alignment bound
     * @param objectName the data-set name written under the generation prefix
     * @return the full object key, never {@code null}
     */
    private String generationObjectKey(final String generationBase, final int generation,
            final String objectName) {
        return generationPrefix(generationBase, generation) + objectName;
    }

    /**
     * Builds the job-instance-scoped prefix a generation write lands under, expressing the <em>convention</em>.
     *
     * <p>The identifier is rendered zero-padded at a fixed width for exactly the reason the generation
     * ordinal is: unpadded, instance 10 would sort below instance 9 and the greatest key would stop being
     * the latest run.
     *
     * <p><strong>Not a stand-in for the production key builder</strong>, for the reason given on
     * {@link #generationObjectKey(String, int, String)}. That production pads the job-instance identifier at
     * all is proved in {@code ProductionGenerationKeyHandoff} by asserting that the writer's own successive
     * keys sort in creation order, not by rebuilding one here and comparing.
     *
     * @param generationBase the per-base key prefix
     * @param jobInstanceId the job-instance identifier; must be non-negative
     * @return the prefix, ending in a separator so a key can be appended directly
     */
    private String jobInstancePrefix(final String generationBase, final long jobInstanceId) {
        return generationBase + "/job-"
                + String.format(Locale.ROOT, "%0" + this.jobInstanceDigits + "d", jobInstanceId) + "/";
    }

    /**
     * Writes a payload to the object store and reads it straight back.
     *
     * <p>The payload crosses the boundary and returns as bytes. <strong>Nothing between the two ends trims,
     * strips, normalises, re-encodes or otherwise touches it</strong>, because trailing blanks are load
     * bearing: a COBOL {@code PIC X(n)} field is blank-padded on the right and a downstream reader locates
     * the next field by offset, so a lost trailing space silently shifts every field after it.
     *
     * <p><strong>Side effects.</strong> Creates one object in the emulator container, inside a bucket this
     * test created and will destroy.
     *
     * @param bucket the bucket to write into
     * @param key the object key to write at
     * @param payload the exact bytes to write
     * @return the exact bytes read back
     */
    private byte[] putAndGet(final String bucket, final String key, final byte[] payload) {
        s3Client().putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(payload));

        final ResponseBytes<GetObjectResponse> retrieved = s3Client().getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return retrieved.asByteArray();
    }

    /**
     * Reads back the bytes already stored at a key.
     *
     * @param bucket the bucket to read from
     * @param key the object key to read
     * @return the exact bytes stored
     */
    private byte[] get(final String bucket, final String key) {
        return s3Client().getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    /**
     * Lists every key under a prefix, ordered by an <strong>explicit</strong> comparator.
     *
     * <p>The service's own response order is not relied on anywhere. The listing is paginated by the
     * client's paginator rather than read as a single page, so a prefix holding more objects than one page
     * returns is still enumerated completely - the bounded-work form of Rule 1 Clause A's performance
     * requirement, and the boundary its Clause B counterpart asks about.
     *
     * @param bucket the bucket to search
     * @param prefix the key prefix to search under
     * @return the keys found, in ascending lexicographic order, never {@code null}
     */
    private List<String> keysUnder(final String bucket, final String prefix) {
        final List<String> keys = new ArrayList<>();
        s3Client().listObjectsV2Paginator(
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents()
                .stream()
                .map(S3Object::key)
                .forEach(keys::add);
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    /**
     * Builds a fixed-width record of exactly one declared length, filled with identifiable content.
     *
     * <p>The content names the record so that a failure message shows which record a mismatched byte came
     * from, and the harness's helper pads the remainder with blanks and <strong>refuses</strong> content
     * that would overflow the field rather than truncating it.
     *
     * @param label short content identifying the record
     * @param length the declared record length in bytes
     * @return exactly {@code length} bytes
     */
    private byte[] recordOf(final String label, final int length) {
        return fixedWidthBytes(label, length);
    }

    /**
     * Concatenates fixed-width records with <strong>no separator of any kind</strong>.
     *
     * <p>This is the whole point of a fixed-width file and the single easiest thing to get wrong. A reader
     * finds record <em>n</em> by multiplying its length, so a line feed appended per record inflates every
     * record by one byte and shifts every record after the first. The choice is therefore explicit here
     * rather than incidental, and it is asserted rather than trusted.
     *
     * @param label short content identifying the records
     * @param length the declared record length in bytes
     * @param count how many records to pack
     * @return exactly {@code length * count} bytes
     */
    private byte[] packedRecords(final String label, final int length, final int count) {
        final byte[] packed = new byte[length * count];
        for (int index = 0; index < count; index++) {
            final byte[] record = recordOf(label + "-" + index, length);
            System.arraycopy(record, 0, packed, index * length, length);
        }
        return packed;
    }

    // =================================================================================================
    // Production-handoff helpers. Each one WIRES a production collaborator or supplies data for it; not one
    // of them re-implements a key rule, a prefix rule or a resolution rule. That distinction is the whole
    // point of the group below: every key asserted there is a value production produced.
    // =================================================================================================

    /**
     * Removes the rows the production writer inserted, exactly those, after each test.
     *
     * <p>The writer's contract is a relation insert <em>and</em> an object emission in one call, so exercising
     * its key publication necessarily creates rows. The harness declares no class-level {@code @Transactional},
     * so they are removed here by recorded identifier - never by truncation, which would disturb a sibling
     * class sharing the cached context.
     *
     * <p><strong>Error modes.</strong> A row that is already gone is an accepted control path, because a test
     * that failed before its insert leaves nothing to delete. Any other failure propagates, so a cleanup that
     * cannot do its job fails loudly instead of leaving rows behind for whatever runs next.
     */
    @AfterEach
    void removeTheRowsTheWriterInserted() {
        if (insertedTransactionIds.isEmpty()) {
            return;
        }
        final List<String> toRemove = List.copyOf(insertedTransactionIds);
        insertedTransactionIds.clear();
        transactionRepository.deleteAllById(toRemove);
        transactionRepository.flush();
    }

    /**
     * Builds the production writer, aimed at a bucket and prefix this class owns.
     *
     * <p>The constructor is public and takes its bucket and prefix as arguments, which is what makes this
     * possible without touching the three shared buckets. Every collaborator is the context's own bean, so the
     * key composition, the encoding and the emission are all production behaviour.
     *
     * @param bucket the bucket this class created and will destroy
     * @return a writer wired exactly as the container wires it, never {@code null}
     */
    private TransactionWriter productionWriter(final String bucket) {
        return new TransactionWriter(transactionRepository, s3Template(), fileStatusMapper, metricsConfig,
                bucket, handoffGenerationPrefix, TransactionWriter.DEFAULT_MAX_INDEXED_OBJECT_KEYS);
    }

    /**
     * Builds the production reader on its object-storage path, aimed at the same bucket and prefix.
     *
     * @param bucket the bucket this class created and will destroy
     * @param promotedGenerationObjectKey the key a prior step promoted, or {@code null} to exercise the
     *     standalone current-generation resolution
     * @return a reader wired exactly as the container wires it, never {@code null}
     */
    private TransactionBackupReader productionReader(final String bucket,
            final String promotedGenerationObjectKey) {

        return new TransactionBackupReader(transactionRepository, s3Template(), s3Client(), fileStatusMapper,
                "object-storage", TransactionBackupReader.DEFAULT_PAGE_SIZE, bucket, handoffGenerationPrefix,
                promotedGenerationObjectKey);
    }

    /**
     * Writes one transaction through the production writer and advances the step's write count.
     *
     * <p>The ordinal segment of the key is {@code StepExecution.getWriteCount()}, which the framework advances
     * between chunks and which nothing advances here. Advancing it explicitly is therefore what makes two
     * successive writes land on two distinct generations instead of overwriting one - the same thing the
     * framework does, done visibly.
     *
     * <p><strong>Side effects.</strong> Inserts one row, records its identifier for teardown, and creates one
     * object in the emulator.
     *
     * @param writer the production writer
     * @param step the step execution the writer is running under
     * @param posted the transaction to write
     */
    private void writeThrough(final TransactionWriter writer, final StepExecution step,
            final Transaction posted) {

        writer.beforeStep(step);
        insertedTransactionIds.add(posted.getTransactionId());
        try {
            writer.write(new Chunk<>(List.of(posted)));
        } catch (final RuntimeException failure) {
            throw failure;
        } catch (final Exception declaredByTheInterface) {
            // ItemWriter.write declares Exception; every failure this writer raises is a com.cardemo runtime
            // subtype, so this branch is unreachable in practice. It is wrapped rather than swallowed so the
            // cause survives if that ever stops being true.
            throw new IllegalStateException(
                    "the production writer raised a checked exception, which its contract does not produce",
                    declaredByTheInterface);
        }
        step.setWriteCount(step.getWriteCount() + 1L);
    }

    /**
     * A sixteen-digit transaction identifier that increases with its ordinal.
     *
     * @param ordinal a small non-negative number distinguishing one record from another
     * @return the identifier, exactly sixteen characters
     */
    private String handoffTransactionId(final int ordinal) {
        return String.format(Locale.ROOT, "99000000000000%02d", ordinal);
    }

    /**
     * A transaction whose foreign keys the seed data satisfies, so the insert the writer performs succeeds.
     *
     * <p>The two timestamps are the batch form {@code yyyy-MM-dd-HH.mm.ss.SS0000} that
     * {@code app/cbl/CBTRN02C.cbl:L458} produces, with the four trailing zeros the source always emits.
     *
     * @param transactionId the sixteen-character identifier
     * @return a fully populated transaction, never {@code null}
     */
    private Transaction handoffTransaction(final String transactionId) {
        return new Transaction(transactionId, seededTypeCode, Integer.valueOf(seededCategoryCode),
                "POS TERM", "A HANDOFF RECORD", new BigDecimal("123.45"), Long.valueOf(9L),
                "MERCHANT NAME", "MERCHANT CITY", "12345", seededCardNumber,
                "2022-06-10-19.27.53.000000", "2022-06-10-19.27.53.000000");
    }

    /**
     * The next-generation half: {@code (+1)} writes a new object under a monotonically increasing prefix.
     *
     * <p>Evidenced most directly by {@code app/jcl/POSTTRAN.jcl:34-38}, which allocates
     * {@code DISP=(NEW,CATLG,DELETE)} against {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)}, and by
     * {@code app/proc/TRANREPT.prc:31,53,78}, which does the same for the transaction backup, the daily
     * extract and the report.
     */


    @Nested
    @DisplayName("(+1) - a next-generation write lands under a monotonically increasing prefix")
    class NextGenerationWrites {

        /** Sole constructor, used by the JUnit Platform. */
        NextGenerationWrites() {
            super();
        }

        /**
         * Three successive generations produce three strictly increasing prefixes.
         *
         * <p>Proved by <strong>explicit comparison</strong> of the prefixes themselves. Listing order is
         * deliberately not used as the evidence: a listing that happened to arrive sorted would make this
         * pass without the prefixes having the property, and the whole current-generation resolution rests on
         * the property rather than on the service's response order.
         */
        @Test
        @DisplayName("three successive generations are strictly increasing in lexicographic order")
        void threeSuccessiveGenerationsAreStrictlyIncreasing() {
            final String first = generationPrefix(rejectGenerationBase, 0);
            final String second = generationPrefix(rejectGenerationBase, 1);
            final String third = generationPrefix(rejectGenerationBase, 2);

            assertThat(first.compareTo(second))
                    .as("generation 0 must sort strictly below generation 1, because a current-generation "
                            + "read resolves the greatest prefix. Prefixes were '%s' and '%s'.", first, second)
                    .isNegative();
            assertThat(second.compareTo(third))
                    .as("generation 1 must sort strictly below generation 2. Prefixes were '%s' and '%s'.",
                            second, third)
                    .isNegative();
            assertThat(first.compareTo(third))
                    .as("ordering must be transitive across generations 0 and 2, or the greatest prefix is "
                            + "not the latest generation. Prefixes were '%s' and '%s'.", first, third)
                    .isNegative();

            assertThat(List.of(first, second, third))
                    .as("each generation must occupy its own prefix, so that a write can never overwrite an "
                            + "earlier generation - the property app/jcl/POSTTRAN.jcl:38's (+1) reference "
                            + "expresses")
                    .doesNotHaveDuplicates()
                    .allSatisfy(prefix -> assertThat(prefix)
                            .startsWith(rejectGenerationBase + "/")
                            .endsWith("/"));
        }

        /**
         * The generation ordinal is zero-padded, and the padding is what keeps the ordering sound.
         *
         * <p>This is the boundary condition Rule 1 Clause B asks about, and it is not decorative: the same
         * two ordinals are compared here both padded and unpadded, and they order <em>oppositely</em>. An
         * implementation that rendered the ordinal without padding would still pass every ordering assertion
         * for the first ten generations and then silently freeze the current generation at the ninth.
         */
        @Test
        @DisplayName("the ordinal is zero-padded, and unpadded it would sort 10 below 9")
        void theGenerationOrdinalIsZeroPaddedSoOrderingSurvivesADigitCarry() {
            final String ninth = generationPrefix(rejectGenerationBase, 9);
            final String tenth = generationPrefix(rejectGenerationBase, 10);

            assertThat(ninth)
                    .as("ordinal 9 must be rendered zero-padded to a fixed width; prefix was '%s'", ninth)
                    .contains("-0009/");
            assertThat(tenth)
                    .as("ordinal 10 must be rendered at the same fixed width; prefix was '%s'", tenth)
                    .contains("-0010/");

            assertThat(ninth.compareTo(tenth))
                    .as("padded, generation 9 must sort below generation 10, so the greatest prefix is the "
                            + "latest generation. Prefixes were '%s' and '%s'.", ninth, tenth)
                    .isNegative();
            assertThat(Integer.toString(9).compareTo(Integer.toString(10)))
                    .as("unpadded, '9' sorts ABOVE '10' - which is exactly why the padding above is load "
                            + "bearing rather than cosmetic, and why it is asserted rather than assumed")
                    .isPositive();
        }

        /**
         * The prefix discriminator comes from the injected fixed clock, never from the wall clock.
         *
         * <p>Asserted two ways: the injected time source is the pinned one, and the prefix carries that
         * instant's rendering. A wall-clock-derived prefix would make the expected key a function of the
         * minute the suite ran, so a failure could not be reproduced and this assertion could not exist at
         * all.
         */
        @Test
        @DisplayName("the prefix discriminator is derived from the injected fixed clock")
        void thePrefixDiscriminatorComesFromTheFixedClock() {
            assertThat(clock().instant())
                    .as("the context's time source must be the pinned one, or an expectation computed in a "
                            + "test and a value computed in production code are reading different clocks")
                    .isEqualTo(FIXED_INSTANT);

            final String expectedDiscriminator = DateTimeFormatter
                    .ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
                    .withZone(ZoneOffset.UTC)
                    .format(FIXED_INSTANT);

            assertThat(generationPrefix(rejectGenerationBase, 0))
                    .as("the prefix must carry the fixed instant's compact UTC rendering '%s', so that the "
                            + "same source produces the same key on every machine and every run",
                            expectedDiscriminator)
                    .isEqualTo(rejectGenerationBase + "/" + expectedDiscriminator + "-0000/");
        }

        /**
         * An ordinal outside the alignment bound is refused, with a message that says why.
         *
         * <p>Beyond the bound the zero-padding widens and lexicographic order stops tracking numeric order,
         * so the greatest key stops being the latest generation. Refusing there is the difference between a
         * loud failure and a silently wrong current-generation resolution.
         */
        @Test
        @DisplayName("an ordinal outside the alignment bound is refused rather than silently misordered")
        void anOrdinalOutsideTheAlignmentBoundIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an ordinal above the bound must be refused, because its rendering would sort below "
                            + "its predecessor")
                    .isThrownBy(() -> generationPrefix(rejectGenerationBase, MAX_GENERATION_ORDINAL + 1))
                    .withMessageContaining(Integer.toString(MAX_GENERATION_ORDINAL))
                    .withMessageContaining("lexicographically greatest")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a negative ordinal must be refused; there is no generation before the first")
                    .isThrownBy(() -> generationPrefix(rejectGenerationBase, -1))
                    .withMessageContaining("between 0 and")
                    .withNoCause();

            assertThat(generationPrefix(rejectGenerationBase, MAX_GENERATION_ORDINAL))
                    .as("the bound itself must remain usable, or the range is off by one")
                    .contains("-" + MAX_GENERATION_ORDINAL + "/");
        }

        /**
         * A null or blank generation base is refused explicitly, at the call site that knows both names.
         *
         * <p>Rule 1 Clause B requires null and empty cases to be handled explicitly rather than left to fail
         * somewhere less informative. A blank prefix would otherwise produce a key at the bucket root, where
         * every base's generations would collide with every other base's.
         */
        @Test
        @DisplayName("a null or blank generation base is refused explicitly")
        void aNullOrBlankGenerationBaseIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .as("a null base must be refused by name rather than surfacing later as a null key")
                    .isThrownBy(() -> generationPrefix(null, 0))
                    .withMessageContaining("gdgPrefix")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a blank base must be refused: it would place every base's generations at the bucket "
                            + "root, where they would collide with one another")
                    .isThrownBy(() -> generationPrefix("   ", 0))
                    .withMessageContaining("must not be blank")
                    .withNoCause();
        }

        /**
         * Each generation of one logical data set lands as its own object, under its own prefix.
         *
         * <p>The end-to-end form of the assertion: three writes against the emulator produce three distinct
         * keys with three distinct contents, and none of them replaced another. The keys are re-ordered here
         * by an explicit comparator, so the service's listing order is still not the evidence.
         */
        @Test
        @DisplayName("three generations of one data set land as three distinct objects")
        void eachGenerationLandsAsItsOwnObject() {
            final String bucket = ownVersionedBucket("gdg");
            final List<String> written = new ArrayList<>();

            for (int generation = 0; generation < multiRecordCount; generation++) {
                final String key = generationObjectKey(rejectGenerationBase, generation, rejectObjectName);
                putAndGet(bucket, key, recordOf("REJECT-GENERATION-" + generation, REJECT_RECORD_LENGTH));
                written.add(key);
            }

            final List<String> discovered = keysUnder(bucket, rejectGenerationBase);
            assertThat(discovered)
                    .as("every generation written must survive as its own object; a (+1) write creates a "
                            + "generation and never replaces one - app/jcl/POSTTRAN.jcl:38")
                    .containsExactlyElementsOf(written);

            for (int generation = 0; generation < multiRecordCount; generation++) {
                final byte[] stored = get(bucket, discovered.get(generation));
                assertFixedWidth(stored, REJECT_RECORD_LENGTH, "app/cbl/CBTRN02C.cbl:176-178");
                assertThat(stored)
                        .as("generation %d must still hold its own content, not a later generation's",
                                generation)
                        .isEqualTo(recordOf("REJECT-GENERATION-" + generation, REJECT_RECORD_LENGTH));
            }
        }

        /**
         * A job-instance-prefixed generation orders the same way, and its padding matters the same way.
         *
         * <p>The transformation maps the reject and report outputs onto "a job-instance prefix", so the
         * ordering property has to hold under that rendering too. The identifier is a literal, so the keys
         * are the same on every run.
         */
        @Test
        @DisplayName("a job-instance-prefixed generation is ordered by the same padded rendering")
        void aJobInstancePrefixedGenerationOrdersByItsPaddedRendering() {
            final String earlier = jobInstancePrefix(rejectGenerationBase, deterministicJobInstanceId);
            final String later = jobInstancePrefix(rejectGenerationBase, deterministicJobInstanceId + 1);

            assertThat(earlier)
                    .as("the job-instance identifier must be zero-padded to %d digits; prefix was '%s'",
                            jobInstanceDigits, earlier)
                    .isEqualTo(rejectGenerationBase + "/job-0000000042/");
            assertThat(earlier.compareTo(later))
                    .as("an earlier job instance must sort strictly below a later one. Prefixes were '%s' "
                            + "and '%s'.", earlier, later)
                    .isNegative();

            final String ninth = jobInstancePrefix(rejectGenerationBase, 9L);
            final String tenth = jobInstancePrefix(rejectGenerationBase, 10L);
            assertThat(ninth.compareTo(tenth))
                    .as("the job-instance rendering must survive a digit carry for the same reason the "
                            + "generation ordinal does. Prefixes were '%s' and '%s'.", ninth, tenth)
                    .isNegative();
        }
    }

    /**
     * The current-generation half: {@code (0)} reads the lexicographically greatest existing prefix.
     *
     * <p>{@code app/proc/TRANREPT.prc:37} reads {@code DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} as the sort
     * input the previous step wrote, and {@code :64} reads {@code DSN=AWS.M2.CARDDEMO.TRANSACT.DALY(+1)} as
     * the report input. Resolution is by <strong>prefix comparison</strong> throughout; last-modified time is
     * never consulted, and one test here exists solely to make a time-ordered implementation fail.
     */
    @Nested
    @DisplayName("(0) - a current-generation read resolves the lexicographically greatest prefix")
    class CurrentGenerationResolution {

        /** Sole constructor, used by the JUnit Platform. */
        CurrentGenerationResolution() {
            super();
        }

        /**
         * After three writes, the current generation is the third, by content and by key.
         */
        @Test
        @DisplayName("the current generation is the greatest prefix, and holds the latest content")
        void theCurrentGenerationIsTheGreatestPrefix() {
            final String bucket = ownVersionedBucket("gdg");

            for (int generation = 0; generation < multiRecordCount; generation++) {
                putAndGet(bucket,
                        generationObjectKey(rejectGenerationBase, generation, rejectObjectName),
                        recordOf("REJECT-GENERATION-" + generation, REJECT_RECORD_LENGTH));
            }

            final int latest = multiRecordCount - 1;
            final Optional<String> current = currentGenerationKey(bucket, rejectGenerationBase);

            assertThat(current)
                    .as("a current-generation read over a populated base must resolve, never return empty")
                    .isPresent()
                    .contains(generationObjectKey(rejectGenerationBase, latest, rejectObjectName));
            assertThat(get(bucket, current.orElseThrow()))
                    .as("the resolved generation must hold the latest content, not an earlier generation's")
                    .isEqualTo(recordOf("REJECT-GENERATION-" + latest, REJECT_RECORD_LENGTH));
        }

        /**
         * Resolution is by prefix comparison and not by last-modified time.
         *
         * <p><strong>The decisive test.</strong> The generations are written deliberately out of order - the
         * greatest first and a lesser one last - so a last-modified resolution and a prefix resolution
         * disagree by construction and only the prefix resolution can pass. This matters beyond tidiness: two
         * objects written inside one millisecond share a timestamp, so a time-ordered resolution is not even
         * a function of the data and would be non-deterministic rather than merely wrong.
         */
        @Test
        @DisplayName("resolution is by prefix comparison, not by last-modified time")
        void resolutionIsByPrefixComparisonAndNotByLastModifiedTime() {
            final String bucket = ownVersionedBucket("gdg");
            final int greatest = 2;
            final int writtenLast = 1;

            for (final int generation : new int[] {greatest, 0, writtenLast}) {
                putAndGet(bucket,
                        generationObjectKey(rejectGenerationBase, generation, rejectObjectName),
                        recordOf("REJECT-GENERATION-" + generation, REJECT_RECORD_LENGTH));
            }

            final Optional<String> current = currentGenerationKey(bucket, rejectGenerationBase);

            assertThat(current)
                    .as("the greatest prefix must win even though generation %d was written last; a "
                            + "last-modified resolution would have returned generation %d",
                            writtenLast, writtenLast)
                    .contains(generationObjectKey(rejectGenerationBase, greatest, rejectObjectName));
            assertThat(get(bucket, current.orElseThrow()))
                    .as("the content resolved must be generation %d's, which is the evidence that ordering "
                            + "came from the key and not from the clock", greatest)
                    .isEqualTo(recordOf("REJECT-GENERATION-" + greatest, REJECT_RECORD_LENGTH));
        }

        /**
         * A single generation resolves to itself.
         *
         * <p>The lower boundary of a populated base. A resolution that only worked once a comparison had two
         * candidates would fail here and nowhere else.
         */
        @Test
        @DisplayName("a base holding a single generation resolves to that generation")
        void aSingleGenerationResolvesToItself() {
            final String bucket = ownVersionedBucket("gdg");
            final String only = generationObjectKey(rejectGenerationBase, 0, rejectObjectName);
            putAndGet(bucket, only, recordOf("REJECT-GENERATION-0", REJECT_RECORD_LENGTH));

            assertThat(currentGenerationKey(bucket, rejectGenerationBase))
                    .as("one generation must resolve to itself; there is nothing for it to be compared "
                            + "against and that is not an error")
                    .isPresent()
                    .contains(only);
        }

        /**
         * A base with zero generations resolves to an explicitly empty result - never a null, never an
         * empty byte array.
         *
         * <p>This is the boundary Rule 1 Clause B names, and the outcome is deliberately a value rather than
         * an exception: referencing a generation on a base that has none is the state a base is in before its
         * first run, which is legitimate rather than exceptional. Returning a null would push the decision to
         * a caller that cannot see it, and returning an empty byte array would be worse still - a zero-length
         * record is indistinguishable from a successful read of an empty generation, and would be written on
         * as though it were data.
         */
        @Test
        @DisplayName("a base with zero generations resolves to an explicitly empty result, not a null")
        void aBaseWithZeroGenerationsResolvesToAnExplicitlyEmptyResult() {
            final String bucket = ownVersionedBucket("gdg");
            final Optional<String> current = currentGenerationKey(bucket, rejectGenerationBase);

            assertThat(current)
                    .as("the resolution must be a present-or-absent value and must never itself be null")
                    .isNotNull();
            assertThat(current)
                    .as("a base with no generation written must resolve empty - the state every base is in "
                            + "before its first run, which app/jcl/DEFGDGB.jcl defines but does not populate")
                    .isEmpty();
            assertThat(keysUnder(bucket, rejectGenerationBase))
                    .as("and nothing may have been created as a side effect of resolving nothing")
                    .isEmpty();
        }

        /**
         * Resolution is scoped to its own generation base and cannot reach into another.
         *
         * <p>The seven bases of {@code app/jcl/DEFGDGB.jcl:24-59} and {@code app/jcl/DALYREJS.jcl:24-28}
         * share one bucket, so per-base scoping is what stops the report generations from being resolved as
         * reject generations. Their own prefixes are what provide it, and it is asserted in both directions.
         */
        @Test
        @DisplayName("resolution is scoped to its own base and never reaches into another")
        void resolutionIsScopedToItsOwnGenerationBase() {
            final String bucket = ownVersionedBucket("gdg");

            final String rejectKey = generationObjectKey(rejectGenerationBase, 0, rejectObjectName);
            final String reportKey = generationObjectKey(reportGenerationBase, 7, "TRANREPT");
            putAndGet(bucket, rejectKey, recordOf("REJECT", REJECT_RECORD_LENGTH));
            putAndGet(bucket, reportKey, recordOf("REPORT", REPORT_LINE_LENGTH));

            assertThat(currentGenerationKey(bucket, rejectGenerationBase))
                    .as("the reject base must resolve its own generation even though the report base holds a "
                            + "numerically greater ordinal in the same bucket")
                    .contains(rejectKey);
            assertThat(currentGenerationKey(bucket, reportGenerationBase))
                    .as("and the report base must resolve its own")
                    .contains(reportKey);
        }

        /**
         * A job-instance-prefixed generation resolves the same way as an ordinal-prefixed one.
         */
        @Test
        @DisplayName("a job-instance-prefixed generation resolves to the greatest job instance")
        void aJobInstancePrefixedGenerationResolvesToTheGreatestInstance() {
            final String bucket = ownVersionedBucket("gdg");
            final long earlierInstance = deterministicJobInstanceId;
            final long laterInstance = deterministicJobInstanceId + 1;

            final String laterKey =
                    jobInstancePrefix(rejectGenerationBase, laterInstance) + rejectObjectName;
            final String earlierKey =
                    jobInstancePrefix(rejectGenerationBase, earlierInstance) + rejectObjectName;

            putAndGet(bucket, laterKey, recordOf("REJECT-JOB-" + laterInstance, REJECT_RECORD_LENGTH));
            putAndGet(bucket, earlierKey, recordOf("REJECT-JOB-" + earlierInstance, REJECT_RECORD_LENGTH));

            assertThat(currentGenerationKey(bucket, rejectGenerationBase))
                    .as("the greatest job instance must resolve even though the earlier one was written "
                            + "last, which a last-modified resolution would have returned instead")
                    .contains(laterKey);
        }

        /**
         * Retention is documented and not enforced, so nothing trims a generation.
         *
         * <p>The report base carries two conflicting limits - {@code app/jcl/DEFGDGB.jcl:37-39} says
         * {@code LIMIT(5)} with {@code SCRATCH} and {@code app/jcl/REPTFILE.jcl:25-28} says {@code LIMIT(10)}
         * with none - resolved to ten. What is asserted is <strong>not</strong> that a lifecycle rule exists:
         * no lifecycle configuration is created here and none is read. What is asserted is the consequence of
         * the resolution being documentary, namely that writing past the limit discards nothing and the
         * greatest prefix is still the current generation.
         */
        @Test
        @DisplayName("writing past the resolved retention limit trims nothing, because retention is "
                + "documented and not enforced")
        void writingPastTheResolvedRetentionLimitTrimsNothing() {
            final String bucket = ownVersionedBucket("gdg");
            final int beyondTheLimit = resolvedRetentionGenerations + 2;

            for (int generation = 0; generation < beyondTheLimit; generation++) {
                putAndGet(bucket,
                        generationObjectKey(reportGenerationBase, generation, "TRANREPT"),
                        recordOf("REPORT-GENERATION-" + generation, REPORT_LINE_LENGTH));
            }

            assertThat(keysUnder(bucket, reportGenerationBase))
                    .as("all %d generations must survive: the resolved limit of %d is a documented lifecycle "
                            + "value, not something this tier enforces by trimming",
                            beyondTheLimit, resolvedRetentionGenerations)
                    .hasSize(beyondTheLimit);
            assertThat(currentGenerationKey(bucket, reportGenerationBase))
                    .as("and the current generation must still be the greatest, which is only true because "
                            + "the ordinal is padded past the digit carry at 9 to 10")
                    .contains(generationObjectKey(reportGenerationBase, beyondTheLimit - 1, "TRANREPT"));
        }

        /**
         * Resolving against a bucket that does not exist fails loudly, with its code, its message and its
         * cause all asserted.
         *
         * <p>Rule 1 Clause B forbids swallowing a failure and requires the root cause to be preserved. The
         * service fault <em>is</em> the root cause here and arrives unwrapped, so the cause asserted is that
         * there is no intervening one: nothing re-threw it with the original discarded, which is the property
         * the clause is really about. An absent bucket is distinguished from an absent generation on purpose -
         * the first is a misconfiguration and the second is a legitimate empty base.
         *
         * <p>The fault's <strong>structured</strong> error code is asserted rather than a substring of its
         * rendered message. The message embeds a per-call request identifier, so an assertion over the whole
         * of it would not be reproducible; only the prose fragment is stable, and it is asserted separately
         * from the code.
         */
        @Test
        @DisplayName("resolving against an absent bucket fails loudly with its code, message and cause "
                + "asserted")
        void resolvingAgainstAnAbsentBucketFailsLoudly() {
            final String neverCreated = scopedResourceName("absent-bucket");

            final NoSuchBucketException failure = catchThrowableOfType(NoSuchBucketException.class,
                    () -> currentGenerationKey(neverCreated, rejectGenerationBase));

            assertThat(failure)
                    .as("an absent bucket must be a loud failure, never an empty result: an empty result "
                            + "would make a misconfigured bucket name indistinguishable from a base awaiting "
                            + "its first generation")
                    .isNotNull();
            assertThat(failure.awsErrorDetails().errorCode())
                    .as("the structured error code identifies the fault; it is asserted instead of a message "
                            + "substring because the message carries a per-call request identifier")
                    .isEqualTo("NoSuchBucket");
            assertThat(failure.statusCode())
                    .as("and the transport status must be not-found rather than a server fault, which is "
                            + "what distinguishes a wrong name from a broken emulator")
                    .isEqualTo(404);
            assertThat(failure.getMessage())
                    .as("the rendered message must still say plainly what went wrong")
                    .contains("does not exist");
            assertThat(failure.getCause())
                    .as("and no intervening cause may exist: the service fault is the root cause and is "
                            + "propagated intact, never re-thrown with the original discarded")
                    .isNull();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and a blank bucket name must be refused before any call is made, at the site that "
                            + "knows the parameter's name")
                    .isThrownBy(() -> currentGenerationKey("  ", rejectGenerationBase))
                    .withMessageContaining("bucketName")
                    .withNoCause();
        }
    }

    /**
     * Record length is preserved byte-exactly at the object-store boundary, at every width the corpus
     * declares.
     *
     * <p>Each width is written and read back, and the assertion is on <strong>bytes</strong> rather than on a
     * string. A string round-trip can normalise a line ending or lose a trailing space, either of which would
     * turn a broken record into a passing test - and in a fixed-width record a lost trailing space shifts
     * every field after it.
     */
    @Nested
    @DisplayName("byte-exact record geometry across the object-store boundary")
    class ByteExactRecordGeometry {

        /** Sole constructor, used by the JUnit Platform. */
        ByteExactRecordGeometry() {
            super();
        }

        /**
         * The transaction and daily-transaction image round-trips as exactly 350 bytes.
         */
        @Test
        @DisplayName("a transaction image round-trips as exactly 350 bytes")
        void aTransactionImageRoundTripsAsExactly350Bytes() {
            final String bucket = ownVersionedBucket("geometry");
            final byte[] written = recordOf("TRANSACTION-IMAGE", TRANSACTION_RECORD_LENGTH);
            final byte[] read = putAndGet(bucket,
                    generationObjectKey(rejectGenerationBase, 0, "TRANSACT"), written);

            assertFixedWidth(read, TRANSACTION_RECORD_LENGTH, "app/proc/TRANREPT.prc:29");
            assertThat(read)
                    .as("the 350-byte image must return byte for byte, as declared at "
                            + "app/proc/TRANREPT.prc:29, app/jcl/CREASTMT.JCL:32 and :50, and composed by "
                            + "app/cpy/COSTM01.CPY:20-24 as 32 + 318")
                    .isEqualTo(written);
        }

        /**
         * The reject record round-trips as exactly 430 bytes and still decomposes as the copybook declares.
         *
         * <p>The one width that is arithmetic rather than a single declaration: 350 bytes of transaction
         * image plus an 80-byte trailer, and that trailer a 4-digit reason plus a 76-character description.
         * The reason is a {@code PIC 9} field, so it is <strong>zero-filled on the left</strong>, while every
         * {@code PIC X} field here is blank-padded on the right. Conflating the two is the single most likely
         * way to get this record wrong, so both paddings are asserted on the bytes that came back.
         */
        @Test
        @DisplayName("a reject record round-trips as exactly 430 bytes and still splits 350 + 4 + 76")
        void aRejectRecordRoundTripsAsExactly430BytesAndStillDecomposes() {
            final String bucket = ownVersionedBucket("geometry");
            final int failReason = 102;
            final String failDescription = "OVERLIMIT TRANSACTION";
            final String transactionImage = "REJECTED-TRANSACTION-IMAGE";

            final byte[] written = fixedWidthBytes(
                    rejectRecord(transactionImage, failReason, failDescription), REJECT_RECORD_LENGTH);
            final byte[] read = putAndGet(bucket,
                    generationObjectKey(rejectGenerationBase, 0, rejectObjectName), written);

            assertFixedWidth(read, REJECT_RECORD_LENGTH, "app/jcl/POSTTRAN.jcl:36");
            assertThat(read)
                    .as("the 430-byte reject record must return byte for byte, as declared at "
                            + "app/cbl/CBTRN02C.cbl:176-178 and corroborated at app/jcl/POSTTRAN.jcl:36 "
                            + "with RECFM=F, fixed unblocked")
                    .isEqualTo(written);

            assertThat(REJECT_RECORD_LENGTH)
                    .as("430 must be exactly 350 + 80, per app/cbl/CBTRN02C.cbl:177-178")
                    .isEqualTo(TRANSACTION_RECORD_LENGTH + REJECT_TRAILER_LENGTH);
            assertThat(REJECT_TRAILER_LENGTH)
                    .as("the 80-byte trailer must be exactly 4 + 76, per app/cbl/CBTRN02C.cbl:181-182")
                    .isEqualTo(REJECT_FAIL_REASON_LENGTH + REJECT_FAIL_REASON_DESC_LENGTH);

            final String storedImage = new String(read, 0, TRANSACTION_RECORD_LENGTH,
                    StandardCharsets.US_ASCII);
            final String storedReason = new String(read, TRANSACTION_RECORD_LENGTH,
                    REJECT_FAIL_REASON_LENGTH, StandardCharsets.US_ASCII);
            final String storedDescription = new String(read,
                    TRANSACTION_RECORD_LENGTH + REJECT_FAIL_REASON_LENGTH,
                    REJECT_FAIL_REASON_DESC_LENGTH, StandardCharsets.US_ASCII);

            assertThat(storedImage)
                    .as("the transaction image must occupy the first 350 bytes, blank-padded on the right "
                            + "because app/cbl/CBTRN02C.cbl:177 declares it PIC X(350)")
                    .hasSize(TRANSACTION_RECORD_LENGTH)
                    .startsWith(transactionImage)
                    .endsWith(" ");
            assertThat(storedReason)
                    .as("the reason must be zero-filled on the LEFT, because app/cbl/CBTRN02C.cbl:181 "
                            + "declares it PIC 9(04) rather than PIC X")
                    .isEqualTo("0102");
            assertThat(storedDescription)
                    .as("the description must be blank-padded on the RIGHT to 76 bytes, because "
                            + "app/cbl/CBTRN02C.cbl:182 declares it PIC X(76)")
                    .hasSize(REJECT_FAIL_REASON_DESC_LENGTH)
                    .startsWith(failDescription)
                    .endsWith(" ");
        }

        /**
         * The printed report line round-trips as exactly 133 bytes.
         */
        @Test
        @DisplayName("a report line round-trips as exactly 133 bytes")
        void aReportLineRoundTripsAsExactly133Bytes() {
            final String bucket = ownVersionedBucket("geometry");
            final byte[] written = recordOf("TRANSACTION REPORT LINE", REPORT_LINE_LENGTH);
            final byte[] read = putAndGet(bucket,
                    generationObjectKey(reportGenerationBase, 0, "TRANREPT"), written);

            assertFixedWidth(read, REPORT_LINE_LENGTH, "app/proc/TRANREPT.prc:76");
            assertThat(read)
                    .as("the 133-byte report line must return byte for byte, as declared at "
                            + "app/proc/TRANREPT.prc:76 for the output of //STEP10R EXEC PGM=CBTRN03C at :57")
                    .isEqualTo(written);
        }

        /**
         * The statement markup line round-trips as exactly 100 bytes, and 80 is recorded as not being that
         * width.
         *
         * <p>The width is declared twice and inconsistently inside one job -
         * {@code app/jcl/CREASTMT.JCL:69} says {@code LRECL=80} in the pre-delete step and {@code :94} says
         * {@code LRECL=100} in the execution step. The execution value is the real one, independently
         * confirmed by {@code app/cbl/CBSTM03A.CBL:149}, {@code 05 HTML-FIXED-LN PIC X(100).} The mismatch is
         * <strong>logged, never reconciled in the source</strong>, and the way it is logged here is by
         * asserting that a line at the pre-delete's 80 is a different length from the real record - so a
         * future reader who "fixes" the width in either direction fails a test that explains why.
         */
        @Test
        @DisplayName("a statement markup line round-trips as exactly 100 bytes, and 80 is not that width")
        void aStatementMarkupLineRoundTripsAsExactly100Bytes() {
            final String bucket = ownVersionedBucket("geometry");
            final byte[] written = recordOf("STATEMENT MARKUP LINE", STATEMENT_MARKUP_LINE_LENGTH);
            final byte[] read = putAndGet(bucket, "statements/markup/STATEMNT.HTML", written);

            assertFixedWidth(read, STATEMENT_MARKUP_LINE_LENGTH, "app/jcl/CREASTMT.JCL:94");
            assertThat(read)
                    .as("the 100-byte markup line must return byte for byte, as declared at "
                            + "app/jcl/CREASTMT.JCL:94 and confirmed by app/cbl/CBSTM03A.CBL:149")
                    .isEqualTo(written);

            assertThat(STATEMENT_MARKUP_LINE_LENGTH)
                    .as("the markup width must be 100 and NOT the 80 that app/jcl/CREASTMT.JCL:69 declares "
                            + "for the same data set in the STEP030 pre-delete. The two disagree in the "
                            + "source; the disagreement is logged and preserved, never repaired, because "
                            + "app/ is frozen")
                    .isNotEqualTo(STATEMENT_TEXT_LINE_LENGTH);
        }

        /**
         * The statement text line round-trips as exactly 80 bytes, and the job-submission record is asserted
         * separately even though it shares the value.
         *
         * <p>{@code app/jcl/CREASTMT.JCL:89} declares the statement text line and
         * {@code app/csd/CARDDEMO.CSD:502-503} declares the queue's parameter record as
         * {@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}. Equal values, unrelated meanings: they are
         * asserted apart so that changing one cannot silently change the other.
         */
        @Test
        @DisplayName("a statement text line round-trips as exactly 80 bytes, asserted apart from the queue "
                + "record that shares the value")
        void aStatementTextLineRoundTripsAsExactly80Bytes() {
            final String bucket = ownVersionedBucket("geometry");
            final byte[] written = recordOf("STATEMENT TEXT LINE", STATEMENT_TEXT_LINE_LENGTH);
            final byte[] read = putAndGet(bucket, "statements/text/STATEMNT.PS", written);

            assertFixedWidth(read, STATEMENT_TEXT_LINE_LENGTH, "app/jcl/CREASTMT.JCL:89");
            assertThat(read)
                    .as("the 80-byte text line must return byte for byte, as declared at "
                            + "app/jcl/CREASTMT.JCL:89")
                    .isEqualTo(written);

            assertThat(JOB_SUBMISSION_RECORD_LENGTH)
                    .as("the job-submission parameter record of app/csd/CARDDEMO.CSD:502-503 is also 80 "
                            + "bytes, but it is a different contract and is declared separately so that one "
                            + "cannot drift into the other")
                    .isEqualTo(STATEMENT_TEXT_LINE_LENGTH);
        }

        /**
         * The statement work-cluster key composes as 16 + 16 and survives inside a stored record.
         *
         * <p>{@code app/jcl/CREASTMT.JCL:30} declares {@code KEYS(32 0)} on the work cluster and
         * {@code app/cpy/COSTM01.CPY:21-23} composes the same 32 bytes from
         * {@code 10 TRNX-CARD-NUM PIC X(16).} and {@code 10 TRNX-ID PIC X(16).} Both components are
         * {@code PIC X}, so both are blank-padded on the right and the card number is <em>not</em> zero-filled
         * - which is the opposite of the reject reason's padding and is asserted here so the two cannot be
         * confused.
         */
        @Test
        @DisplayName("the statement work key composes as 16 + 16 = 32 and survives the round trip")
        void theStatementWorkKeyComposesAs16Plus16() {
            final String bucket = ownVersionedBucket("geometry");
            final String cardNumber = "4111111111111111";
            final String transactionId = "0000000000000001";
            final String key = statementWorkKey(cardNumber, transactionId);

            assertThat(STATEMENT_WORK_KEY_LENGTH)
                    .as("32 must be exactly 16 + 16, per app/cpy/COSTM01.CPY:21-23 and app/jcl/"
                            + "CREASTMT.JCL:30's KEYS(32 0)")
                    .isEqualTo(STATEMENT_KEY_CARD_NUMBER_LENGTH + STATEMENT_KEY_TRANSACTION_ID_LENGTH);
            assertThat(key)
                    .as("the composed key must be exactly 32 characters")
                    .hasSize(STATEMENT_WORK_KEY_LENGTH);

            final byte[] written = fixedWidthBytes(key, TRANSACTION_RECORD_LENGTH);
            final byte[] read = putAndGet(bucket, "statements/work/TRXFL", written);

            assertFixedWidth(read, TRANSACTION_RECORD_LENGTH, "app/jcl/CREASTMT.JCL:32");
            final String storedKey = new String(read, 0, STATEMENT_WORK_KEY_LENGTH,
                    StandardCharsets.US_ASCII);
            assertThat(storedKey.substring(0, STATEMENT_KEY_CARD_NUMBER_LENGTH))
                    .as("bytes 1 to 16 must be the card number component, blank-padded on the right as a "
                            + "PIC X field and never zero-filled on the left")
                    .isEqualTo(cardNumber);
            assertThat(storedKey.substring(STATEMENT_KEY_CARD_NUMBER_LENGTH))
                    .as("bytes 17 to 32 must be the identifier component")
                    .isEqualTo(transactionId);
            assertThat(read.length - STATEMENT_WORK_KEY_LENGTH)
                    .as("the remainder must be 318 bytes, which is what makes the work record 350 - "
                            + "app/cpy/COSTM01.CPY:24-36")
                    .isEqualTo(318);
        }

        /**
         * A trailing blank survives the round trip untouched.
         *
         * <p>Trailing blanks are load bearing, so this asserts the negative: nothing between the two ends
         * trims, strips or normalises. A helper that called {@code trim()} anywhere would pass every length
         * assertion above and fail only here.
         */
        @Test
        @DisplayName("a trailing blank survives the round trip, because nothing trims a fixed-width payload")
        void aTrailingBlankSurvivesTheRoundTrip() {
            final String bucket = ownVersionedBucket("geometry");
            final byte[] written = recordOf("SHORT", TRANSACTION_RECORD_LENGTH);
            final byte[] read = putAndGet(bucket, "geometry/trailing-blank", written);

            assertFixedWidth(read, TRANSACTION_RECORD_LENGTH, "app/proc/TRANREPT.prc:29");
            assertThat(read[read.length - 1])
                    .as("the final byte must still be a blank: a COBOL PIC X(n) field is padded on the "
                            + "right, and a reader locates the next field by offset, so a trimmed trailing "
                            + "blank shifts every field after it")
                    .isEqualTo((byte) ' ');
            assertThat(read)
                    .as("and the whole payload must be identical, not merely the same length")
                    .isEqualTo(written);
        }

        /**
         * The two explicit single-byte encodings agree over the alphabet these payloads use.
         *
         * <p>Every conversion in this class names its charset, and none uses the platform default - a default
         * would make a record's byte length depend on the machine that produced it, which is the one property
         * this tier exists to pin. The harness encodes with Latin-1 and this class decodes with US-ASCII, so
         * that mixture is <strong>proved</strong> rather than assumed: over the ASCII subset the synthetic
         * payloads use, the two produce identical bytes.
         */
        @Test
        @DisplayName("the explicit encodings agree byte for byte over the payload alphabet")
        void theExplicitEncodingsAgreeOverThePayloadAlphabet() {
            final String content = fixedWidth("ENCODING AGREEMENT 0123456789", TRANSACTION_RECORD_LENGTH);

            assertThat(content.getBytes(StandardCharsets.US_ASCII))
                    .as("Latin-1 and US-ASCII must produce identical bytes for this alphabet, which is what "
                            + "makes encoding with one and decoding with the other sound rather than lucky")
                    .isEqualTo(content.getBytes(StandardCharsets.ISO_8859_1));
            assertThat(content.getBytes(StandardCharsets.US_ASCII).length)
                    .as("and a character count must equal a byte count, which is what makes a declared "
                            + "record length meaningful at all")
                    .isEqualTo(content.length());
        }

        /**
         * Over-long content is refused rather than truncated, and a non-positive width is refused outright.
         *
         * <p>Silent truncation is how record geometry drifts unnoticed, and a test that quietly shortened its
         * own input would prove nothing about the boundary it was written to exercise. An all-blank field of a
         * declared width is legitimate and is asserted alongside, so the refusal cannot be mistaken for a
         * rejection of empty content.
         */
        @Test
        @DisplayName("over-long content is refused rather than truncated, and a zero width is refused")
        void overLongContentIsRefusedRatherThanTruncated() {
            final String tooLong = "X".repeat(TRANSACTION_RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("content wider than its field must be refused, not truncated")
                    .isThrownBy(() -> fixedWidth(tooLong, TRANSACTION_RECORD_LENGTH))
                    .withMessageContaining("refused rather than truncated")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a non-positive width must be refused: every field in the corpus has a declared "
                            + "width, so a zero-length record is never a legitimate request")
                    .isThrownBy(() -> fixedWidth("", 0))
                    .withMessageContaining("length must be positive")
                    .withNoCause();

            assertThatExceptionOfType(NullPointerException.class)
                    .as("null content must be refused by name; an empty string is how an all-blank field is "
                            + "requested")
                    .isThrownBy(() -> fixedWidth(null, TRANSACTION_RECORD_LENGTH))
                    .withMessageContaining("content must not be null")
                    .withNoCause();

            assertThat(fixedWidthBytes("", TRANSACTION_RECORD_LENGTH))
                    .as("an all-blank field of a declared width IS legitimate, and must be exactly that "
                            + "width")
                    .hasSize(TRANSACTION_RECORD_LENGTH);
        }

        /**
         * Reading a generation that was never written fails loudly rather than returning empty bytes.
         *
         * <p>A zero-length byte array would be indistinguishable from a successful read of an empty
         * generation and would be processed as though it were data, which is the failure mode Rule 1 Clause B
         * calls swallowing. The service fault is the root cause and arrives unwrapped, so the cause asserted
         * is that nothing intervened to discard it. As with the absent bucket, the <strong>structured</strong>
         * error code is what is asserted: the rendered message carries a per-call request identifier and only
         * its prose fragment is reproducible.
         */
        @Test
        @DisplayName("reading a generation that was never written fails loudly, never with empty bytes")
        void readingAnAbsentGenerationFailsLoudly() {
            final String bucket = ownVersionedBucket("geometry");
            final String neverWritten = generationObjectKey(rejectGenerationBase, 0, rejectObjectName);

            final NoSuchKeyException failure =
                    catchThrowableOfType(NoSuchKeyException.class, () -> get(bucket, neverWritten));

            assertThat(failure)
                    .as("an absent generation must raise the service's own not-found fault; returning a "
                            + "zero-length record would be indistinguishable from a successful read of an "
                            + "empty generation and would be written on as though it were data")
                    .isNotNull();
            assertThat(failure.awsErrorDetails().errorCode())
                    .as("the structured error code identifies the fault, and is asserted instead of a "
                            + "message substring because the message carries a per-call request identifier")
                    .isEqualTo("NoSuchKey");
            assertThat(failure.statusCode())
                    .as("and the transport status must be not-found")
                    .isEqualTo(404);
            assertThat(failure.getMessage())
                    .as("the rendered message must still say plainly what went wrong")
                    .contains("does not exist");
            assertThat(failure.getCause())
                    .as("and no intervening cause may exist: the service fault is propagated intact")
                    .isNull();
        }
    }

    /**
     * Multi-record objects, decimal arithmetic inside a record, and keys that are hostile by construction.
     *
     * <p>A generation object holds many records, so the geometry claim only means something if it holds at
     * the whole-object level too: the object must be an exact multiple of its record length, with no
     * separator anywhere. And because a record carries money, the arithmetic that produces it has to be
     * decimal - the corpus's three precision tiers are never conflated and no binary floating-point type
     * appears anywhere in this class.
     */
    @Nested
    @DisplayName("multi-record objects, decimal arithmetic and hostile keys")
    class MultiRecordObjectsAndUntrustedInput {

        /** Sole constructor, used by the JUnit Platform. */
        MultiRecordObjectsAndUntrustedInput() {
            super();
        }

        /**
         * A multi-record generation object is an exact multiple of its record length, at every width.
         *
         * <p>All three widths that appear as a generation's content are checked in one place because the
         * arithmetic is the assertion: a reader finds record <em>n</em> by multiplying, so an object that is
         * not an exact multiple cannot be read at all beyond its first record.
         */
        @Test
        @DisplayName("a multi-record object is an exact multiple of 350, 430 and 133")
        void aMultiRecordObjectIsAnExactMultipleOfItsRecordLength() {
            final String bucket = ownVersionedBucket("geometry");

            final byte[] transactions =
                    packedRecords("TRANSACTION", TRANSACTION_RECORD_LENGTH, multiRecordCount);
            final byte[] rejects = packedRecords("REJECT", REJECT_RECORD_LENGTH, multiRecordCount);
            final byte[] reportLines = packedRecords("REPORT", REPORT_LINE_LENGTH, multiRecordCount);

            final byte[] storedTransactions =
                    putAndGet(bucket, "geometry/multi/TRANSACT", transactions);
            final byte[] storedRejects = putAndGet(bucket, "geometry/multi/DALYREJS", rejects);
            final byte[] storedReportLines = putAndGet(bucket, "geometry/multi/TRANREPT", reportLines);

            assertFixedWidth(storedTransactions, TRANSACTION_RECORD_LENGTH * multiRecordCount,
                    "app/proc/TRANREPT.prc:29");
            assertFixedWidth(storedRejects, REJECT_RECORD_LENGTH * multiRecordCount,
                    "app/jcl/POSTTRAN.jcl:36");
            assertFixedWidth(storedReportLines, REPORT_LINE_LENGTH * multiRecordCount,
                    "app/proc/TRANREPT.prc:76");

            assertThat(storedTransactions).isEqualTo(transactions);
            assertThat(storedRejects).isEqualTo(rejects);
            assertThat(storedReportLines).isEqualTo(reportLines);
        }

        /**
         * No separator and no trailing line ending is added to a fixed-width object.
         *
         * <p>Stated as an explicit choice rather than left implicit, because it is the defect that hides best:
         * a line feed appended per record inflates every record by one byte, and the total still looks
         * plausible until someone divides it by the declared length. Asserted twice over - the total is an
         * exact multiple, and no carriage return or line feed appears anywhere in the bytes.
         */
        @Test
        @DisplayName("no separator and no trailing line ending is added to a fixed-width object")
        void noSeparatorOrTrailingLineEndingIsAdded() {
            final String bucket = ownVersionedBucket("geometry");
            final byte[] packed = packedRecords("NO-SEPARATOR", REJECT_RECORD_LENGTH, multiRecordCount);
            final byte[] stored = putAndGet(bucket, "geometry/no-separator/DALYREJS", packed);

            assertThat(stored.length % REJECT_RECORD_LENGTH)
                    .as("a %d-record object of %d-byte records must divide exactly; the stored length was "
                            + "%d bytes and the declaration is app/jcl/POSTTRAN.jcl:36",
                            multiRecordCount, REJECT_RECORD_LENGTH, stored.length)
                    .isZero();
            assertThat(stored.length / REJECT_RECORD_LENGTH)
                    .as("and it must divide into exactly %d records, not %d plus a remainder",
                            multiRecordCount, multiRecordCount)
                    .isEqualTo(multiRecordCount);
            assertThat(stored)
                    .as("no line feed may appear anywhere: one per record would inflate every record by a "
                            + "byte and shift every record after the first")
                    .doesNotContain((byte) '\n');
            assertThat(stored)
                    .as("and no carriage return either, for the same reason")
                    .doesNotContain((byte) '\r');
        }

        /**
         * A monetary value embedded in a record is decimal, and the three precision tiers stay distinct.
         *
         * <p><strong>Zero {@code float} and zero {@code double} appear anywhere in this class.</strong> Two
         * decimals that differ only in trailing zeros are equal in value but not equal as objects, so a
         * comparison must use {@code compareTo} and never {@code equals} - which is asserted here directly
         * rather than left as folklore. The tiers are the account money column, the transaction amount and
         * category balance, and the interest rate, and they are never conflated: a value that overflows its
         * tier is refused before it can reach a column.
         */
        @Test
        @DisplayName("an embedded monetary value is decimal, compared with compareTo, at its own precision")
        void anEmbeddedMonetaryValueIsDecimalAtItsOwnPrecision() {
            final BigDecimal sameValueDifferentScale = new BigDecimal("194.0");
            final BigDecimal amount = new BigDecimal("194.00");

            assertThat(amount.compareTo(sameValueDifferentScale))
                    .as("compareTo must report these equal in value, which is why every comparison in this "
                            + "migration uses it")
                    .isZero();
            assertThat(amount.equals(sameValueDifferentScale))
                    .as("equals must report them different, because it compares scale as well as value - "
                            + "this is the trap compareTo exists to avoid, and it is asserted so nobody has "
                            + "to take it on trust")
                    .isFalse();

            assertThat(TRANSACTION_AMOUNT_PRECISION)
                    .as("the transaction amount and category balance tier must stay distinct from the "
                            + "account money tier; app/cpy/CVTRA05Y.cpy:10 declares TRAN-AMT as "
                            + "PIC S9(09)V99 while an account money field is S9(10)V99")
                    .isNotEqualTo(ACCOUNT_MONEY_PRECISION);
            assertThat(INTEREST_RATE_PRECISION)
                    .as("and the interest-rate tier must stay distinct from both")
                    .isNotEqualTo(TRANSACTION_AMOUNT_PRECISION)
                    .isNotEqualTo(ACCOUNT_MONEY_PRECISION);

            assertThat(decimal("194.005", TRANSACTION_AMOUNT_PRECISION))
                    .as("rounding must be half-to-even at scale 2, so 194.005 becomes 194.00 and not 194.01")
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("194.00"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a value that overflows its declared precision must be refused here rather than at "
                            + "insert time, where the message would name a column instead of a contract")
                    .isThrownBy(() -> decimal("12345.67", INTEREST_RATE_PRECISION))
                    .withMessageContaining("does not fit")
                    .withNoCause();

            final String bucket = ownVersionedBucket("geometry");
            final byte[] written = fixedWidthBytes(
                    "AMOUNT " + amount.toPlainString(), TRANSACTION_RECORD_LENGTH);
            final byte[] stored = putAndGet(bucket, "geometry/amount/TRANSACT", written);

            assertFixedWidth(stored, TRANSACTION_RECORD_LENGTH, "app/proc/TRANREPT.prc:29");
            assertThat(new String(stored, StandardCharsets.US_ASCII))
                    .as("the decimal's plain rendering must survive the round trip with its trailing zero "
                            + "intact, because a scale is part of a fixed-width money field's contract")
                    .startsWith("AMOUNT 194.00");
        }

        /**
         * A hostile resource name is refused deterministically and is never silently sanitised.
         *
         * <p>Rule 1 Clause A requires inputs to be treated as untrusted. The three shapes that matter are a
         * traversal segment, a control character and an over-long segment, and all three are <strong>refused
         * with a message that names the constraint</strong> rather than quietly rewritten into something
         * acceptable. Quiet rewriting is worse than refusal here: two different hostile names can normalise to
         * the same acceptable one, at which point two tests share a resource and neither can be trusted.
         */
        @Test
        @DisplayName("a hostile resource name is refused with a message, never silently sanitised")
        void aHostileResourceNameIsRefusedAndNeverSilentlySanitised() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a traversal segment must be refused; a path separator and a dot are not in the "
                            + "alphabet the object store permits in a bucket name")
                    .isThrownBy(() -> scopedResourceName("../evil"))
                    .withMessageContaining("lowercase letters, digits and hyphens")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a control character must be refused rather than stripped, so that two hostile "
                            + "inputs cannot collapse onto one accepted name")
                    .isThrownBy(() -> scopedResourceName("\u0001injected"))
                    .withMessageContaining("lowercase letters, digits and hyphens")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an over-long segment must be refused rather than truncated to fit, because a "
                            + "truncated name can collide with another test's")
                    .isThrownBy(() -> scopedResourceName("a".repeat(64)))
                    .withMessageContaining("Shorten the role rather than truncating the name")
                    .withNoCause();

            assertThatExceptionOfType(NullPointerException.class)
                    .as("a null role must be refused by name")
                    .isThrownBy(() -> scopedResourceName(null))
                    .withMessageContaining("role must not be null")
                    .withNoCause();

            assertThat(scopedResourceName("gdg"))
                    .as("and a well-formed role must still produce a name that is stable across runs and "
                            + "unique to this class, so a failure can be attributed and re-observed")
                    .isEqualTo(scopedResourceName("gdg"))
                    .matches("[a-z0-9-]{3,63}");
        }

        /**
         * A traversal-shaped object key is stored verbatim, and prefix scoping is not defeated by it.
         *
         * <p>An object key is an opaque byte sequence and not a filesystem path, so {@code ..} carries no
         * meaning to the service and is neither resolved nor rewritten. That is asserted in both directions:
         * the key comes back exactly as written, so nothing normalised it behind the caller's back, and a
         * prefix query for a <em>different</em> generation base still cannot see it - which is the property
         * that keeps the seven bases isolated inside one bucket.
         */
        @Test
        @DisplayName("a traversal-shaped key is stored verbatim and cannot escape its prefix scope")
        void aTraversalShapedKeyIsStoredVerbatimAndCannotEscapeItsScope() {
            final String bucket = ownVersionedBucket("gdg");
            final String hostileKey = rejectGenerationBase + "/../" + rejectObjectName;
            final byte[] written = recordOf("TRAVERSAL-SHAPED-KEY", REJECT_RECORD_LENGTH);

            final byte[] stored = putAndGet(bucket, hostileKey, written);
            assertFixedWidth(stored, REJECT_RECORD_LENGTH, "app/cbl/CBTRN02C.cbl:176-178");
            assertThat(stored)
                    .as("the payload must be unaffected by the shape of the key it was stored under")
                    .isEqualTo(written);

            assertThat(keysUnder(bucket, rejectGenerationBase))
                    .as("the key must be stored VERBATIM, with its traversal segment intact: an object key "
                            + "is opaque bytes and not a path, so nothing resolves or rewrites it, and a "
                            + "caller can therefore reason about exactly what it wrote")
                    .containsExactly(hostileKey);
            assertThat(keysUnder(bucket, reportGenerationBase))
                    .as("and a query scoped to a different generation base must not see it, which is what "
                            + "keeps the seven bases of app/jcl/DEFGDGB.jcl:24-59 and "
                            + "app/jcl/DALYREJS.jcl:24-28 isolated inside one bucket")
                    .isEmpty();
            assertThat(currentGenerationKey(bucket, reportGenerationBase))
                    .as("nor may a current-generation read on another base resolve it")
                    .isEmpty();
        }
    }

    // =================================================================================================
    // The production writer itself, driven end to end against the emulator.
    //
    // FINDING, SEVERITY HIGH - raised against this file and remediated here. Every generation assertion
    // above was built on the harness's synthetic four-digit ordinal helper, so none of them touched the
    // key the application actually writes. The production writer renders NINETEEN-digit zero-padded
    // identifiers, which is a different width, a different composition and a different sort domain; a
    // suite that only ever exercised the synthetic shape could not have failed on a defect in the real
    // one. These tests construct the real writer, drive its real ItemStream lifecycle and assert against
    // the key it produces.
    //
    // FINDING, SEVERITY MEDIUM - the unique reject-geometry assertions of a seventh AWS test that
    // duplicated this suite through the batch tier's harness are migrated here and that file retired, so
    // the tier starts one container set rather than two and the assertions live beside the generation
    // semantics they depend on.
    // =================================================================================================

    @Nested
    @DisplayName("the production writer - real 19-digit generation keys, byte-exact geometry")
    class ProductionWriterGenerationKeys {

        @Test
        @DisplayName("the key is the configured prefix, a padded job instance, a padded execution and .dat")
        void theProductionKeyCarriesNineteenDigitIdentifiers() {
            final long jobInstanceId = 7001L;
            final long jobExecutionId = 90011L;
            final StepExecution stepExecution = stepExecutionFor(jobInstanceId, jobExecutionId);

            final String objectKey = writeOneRejectGeneration(stepExecution, "0000000000000001",
                    RejectCode.INVALID_CARD_NUMBER);

            assertThat(objectKey)
                    .as("the key must begin at the CONFIGURED prefix, not at a literal this test chose. "
                            + "The writer binds the same key, so a mismatch here means the two disagree")
                    .startsWith(configuredRejectGdgPrefix + "/")
                    .endsWith(".dat");
            assertThat(objectKey)
                    .as("both identifiers are rendered zero-padded to %d digits. That padding is the whole "
                            + "mechanism by which a lexicographic listing resolves the LATEST generation: "
                            + "unpadded, execution 10 would sort below execution 9 and a current-generation "
                            + "read would silently return a stale object",
                            productionIdentifierDigits)
                    .contains(padded(jobInstanceId))
                    .contains(padded(jobExecutionId));
            assertThat(objectKey)
                    .as("and the composition is prefix / instance / execution.dat, in that order, so every "
                            + "generation of one job instance groups under one listable prefix")
                    .isEqualTo(instancePrefix(jobInstanceId)
                            + padded(jobExecutionId) + ".dat");
        }

        @Test
        @DisplayName("one run writes exactly ONE object, so a (0) read resolves the whole generation")
        void oneRunWritesExactlyOneGenerationObject() {
            final String bucket = batchOutputBucket();
            final long jobInstanceId = 7002L;
            final StepExecution stepExecution = stepExecutionFor(jobInstanceId, 90021L);

            final RejectWriter writer = productionRejectWriter(stepExecution);
            writer.open(stepExecution.getExecutionContext());
            // Three separate chunks. A per-chunk object would produce three keys here, which is exactly
            // the fragmentation that made a current-generation read resolve only the final part.
            writer.writeReject(dailyTransaction("0000000000000001"), RejectCode.INVALID_CARD_NUMBER);
            writer.update(stepExecution.getExecutionContext());
            writer.writeReject(dailyTransaction("0000000000000002"), RejectCode.ACCOUNT_RECORD_NOT_FOUND);
            writer.update(stepExecution.getExecutionContext());
            writer.writeReject(dailyTransaction("0000000000000003"), RejectCode.OVERLIMIT_TRANSACTION);
            writer.update(stepExecution.getExecutionContext());
            writer.close();

            final List<String> keys = keysUnder(bucket,
                    instancePrefix(jobInstanceId));

            assertThat(keys)
                    .as("a GDG (+1) allocates ONE generation per run. Three chunks are three writes into "
                            + "that one generation, not three generations, so a (0) reference resolves the "
                            + "run's whole output rather than its last fragment")
                    .hasSize(1);
            assertThat(get(bucket, keys.get(0)))
                    .as("and the single object holds all three records at %d bytes each",
                            REJECT_RECORD_LENGTH)
                    .hasSize(3 * REJECT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a reject record written through the production writer lands as exactly 430 bytes")
        void aRejectRecordLandsAtItsDeclaredLength() {
            final StepExecution stepExecution = stepExecutionFor(7003L, 90031L);

            final String objectKey = writeOneRejectGeneration(stepExecution, "0000000000000001",
                    RejectCode.INVALID_CARD_NUMBER);

            assertThat(get(batchOutputBucket(), objectKey))
                    .as("the length that matters for parity is the length of the object that actually "
                            + "landed, after the SDK encoded and streamed it - not the length of the string "
                            + "handed to the writer. app/jcl/POSTTRAN.jcl declares LRECL=430 and "
                            + "app/cbl/CBTRN02C.cbl:L176-L182 composes it as 350 data bytes plus an "
                            + "80-byte trailer")
                    .hasSize(REJECT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the stored trailer still reads reason-then-description after the round trip")
        void theStoredTrailerPreservesItsFieldOrder() {
            final StepExecution stepExecution = stepExecutionFor(7004L, 90041L);

            final String objectKey = writeOneRejectGeneration(stepExecution, "0000000000000042",
                    RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);

            final String stored = new String(
                    get(batchOutputBucket(), objectKey), StandardCharsets.ISO_8859_1);
            final String trailer = stored.substring(TRANSACTION_RECORD_LENGTH);

            assertThat(trailer)
                    .as("the trailer is a four-digit reason followed by a 76-character description, in that "
                            + "order. Reversed, it would still be 80 bytes and still round-trip, which is "
                            + "why the order is asserted rather than only the length")
                    .hasSize(REJECT_TRAILER_LENGTH)
                    .startsWith(String.format(Locale.ROOT, "%04d",
                            RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getCode()));
            assertThat(trailer.substring(REJECT_FAIL_REASON_LENGTH).strip())
                    .as("and the description is the code's own literal, which app/cbl/CBTRN02C.cbl emits "
                            + "verbatim and Gate 1 compares byte for byte")
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getDescription());
        }

        @Test
        @DisplayName("the writer publishes the prefix, the count and the exact key it wrote")
        void theWriterPublishesItsGenerationContext() {
            final long jobInstanceId = 7005L;
            final StepExecution stepExecution = stepExecutionFor(jobInstanceId, 90051L);

            final String objectKey = writeOneRejectGeneration(stepExecution, "0000000000000007",
                    RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(stepExecution.getExecutionContext()
                            .getString(RejectWriter.REJECT_GENERATION_PREFIX_CONTEXT_KEY))
                    .as("the prefix is what makes the generation LISTABLE by a downstream step, so it is "
                            + "published as well as the key")
                    .isEqualTo(instancePrefix(jobInstanceId));
            assertThat(stepExecution.getExecutionContext()
                            .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .as("and the key names the object PRECISELY, so a downstream step need not guess which "
                            + "of several objects under the prefix is this run's")
                    .isEqualTo(objectKey);
            assertThat(stepExecution.getExecutionContext()
                            .getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .as("the count is WS-REJECT-COUNT, which app/cbl/CBTRN02C.cbl:L229-L231 uses to decide "
                            + "return code 4, so it must be published even for a single record")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("a second execution of the same instance writes a distinct, greater generation")
        void aRestartWritesItsOwnGeneration() {
            final String bucket = batchOutputBucket();
            final long jobInstanceId = 7006L;
            final String firstKey = writeOneRejectGeneration(
                    stepExecutionFor(jobInstanceId, 90061L), "0000000000000001",
                    RejectCode.INVALID_CARD_NUMBER);

            // A restart is a NEW job execution of the SAME job instance, which is why the instance segment
            // is held constant here and only the execution segment moves.
            final String restartedKey = writeOneRejectGeneration(
                    stepExecutionFor(jobInstanceId, 90062L), "0000000000000002",
                    RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(restartedKey)
                    .as("a restart must not overwrite the failed attempt's output. Reusing the key would "
                            + "destroy the evidence of the first attempt, and a GDG never does that - each "
                            + "attempt is its own generation")
                    .isNotEqualTo(firstKey);
            assertThat(restartedKey.compareTo(firstKey))
                    .as("and the later execution must sort GREATER, because that ordering is how a (0) "
                            + "reference identifies the current generation")
                    .isPositive();
            assertThat(keysUnder(bucket, instancePrefix(jobInstanceId)))
                    .as("both generations remain present and grouped under the one job instance")
                    .containsExactlyInAnyOrder(firstKey, restartedKey);
            assertThat(currentGenerationKey(bucket,
                            instancePrefix(jobInstanceId)))
                    .as("and the current-generation read resolves the restart, not the first attempt")
                    .contains(restartedKey);
        }
    }

    /**
     * Builds the production writer with the context's own collaborators.
     *
     * @param stepExecution the execution whose identifiers compose the generation key
     * @return the writer, never {@code null}
     */
    private RejectWriter productionRejectWriter(final StepExecution stepExecution) {
        return new RejectWriter(s3Template(), metricsConfig, fileStatusMapper,
                batchOutputBucket(), configuredRejectGdgPrefix, stepExecution);
    }

    /**
     * Drives one complete generation through the production writer: open, one write, update, close.
     *
     * <p>The full {@code ItemStream} lifecycle is used rather than a bare write, because the generation
     * object is committed on {@code close} and the key is published there. A test that wrote without
     * closing would observe neither.
     *
     * @param stepExecution the execution to write under
     * @param transactionId the identifier of the rejected record
     * @param rejectCode the outcome to record
     * @return the published object key, never {@code null}
     */
    private String writeOneRejectGeneration(final StepExecution stepExecution, final String transactionId,
            final RejectCode rejectCode) {

        final RejectWriter writer = productionRejectWriter(stepExecution);
        writer.open(stepExecution.getExecutionContext());
        writer.writeReject(dailyTransaction(transactionId), rejectCode);
        writer.update(stepExecution.getExecutionContext());
        writer.close();
        return stepExecution.getExecutionContext()
                .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);
    }

    /**
     * Renders an identifier the way the production writer renders it.
     *
     * @param identifier the identifier to render
     * @return the zero-padded rendering, never {@code null}
     */
    private String padded(final long identifier) {
        return String.format(Locale.ROOT, "%0" + productionIdentifierDigits + "d", identifier);
    }

    /**
     * Builds a step execution with explicit identifiers, so each test owns its own generation key space.
     *
     * <p>The factory's no-argument form returns the <em>same</em> default instance and execution identifiers
     * on every call, so two tests using it write to the same key and each observes the other's objects. That
     * is not a hypothetical: it was observed here as a listing that found two objects where one was expected,
     * and it would have been read as a defect in the writer rather than in the tests. Stating both
     * identifiers per test makes the suite order-independent and makes each assertion about its own writes.
     *
     * @param jobInstanceId the instance identifier, distinct per test
     * @param jobExecutionId the execution identifier, distinct per write
     * @return a step execution carrying those identifiers, never {@code null}
     */
    private static StepExecution stepExecutionFor(final long jobInstanceId, final long jobExecutionId) {
        return MetaDataInstanceFactory.createStepExecution(
                MetaDataInstanceFactory.createJobExecution("posttran", jobInstanceId, jobExecutionId),
                "rejectStep",
                jobExecutionId);
    }

    /**
     * Composes the job-instance prefix a generation lands under, exactly as the production writer does.
     *
     * <p>The separator is supplied here because the CONFIGURED prefix carries none - it is
     * {@code gdg/dalyrejs}, and the writer appends {@code "/"} before the padded instance. That is worth
     * stating rather than absorbing: a test that assumed a trailing separator in configuration would
     * assemble a key one character different from the real one and fail for a reason that looks like a
     * production defect.
     *
     * @param jobInstanceId the job instance whose generations group under this prefix
     * @return the prefix, ending in a separator so a key can be appended directly
     */
    private String instancePrefix(final long jobInstanceId) {
        return configuredRejectGdgPrefix + "/" + padded(jobInstanceId) + "/";
    }

    /**
     * A rejected daily transaction, shaped as the 350-byte staging layout requires.
     *
     * @param id the transaction identifier
     * @return the record, never {@code null}
     */
    private static DailyTransaction dailyTransaction(final String id) {
        return new DailyTransaction(1L, id, "01", 5, "System", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT NAME", "MERCHANT CITY", "12345",
                "4111111111111111", "2022-06-10-19.27.53.000000", "2022-06-10-19.27.53.000000");
    }

    @Nested
    @DisplayName("the production handoff - the writer's own key, carried through a context, read by the "
            + "production reader")
    class ProductionGenerationKeyHandoff {

        /** Sole constructor, used by the JUnit Platform. */
        ProductionGenerationKeyHandoff() {
            super();
        }

        /**
         * The key the production writer publishes is the key the object actually landed under.
         *
         * <p>Nothing here re-derives a key. The prefix and bucket are configuration handed <em>to</em>
         * {@link TransactionWriter}, the ordinal and the job-instance segment are composed by the writer, and
         * the assertion reads the value the writer published rather than a value the test computed. That is
         * the whole point: if the writer's key composition changes, this fails, whereas a test that rebuilds
         * the key from the same rule would keep passing while production and test drifted together.
         */
        @Test
        @DisplayName("the writer publishes the concrete key it created, and the object is there, 350 bytes")
        void theWriterPublishesTheConcreteKeyItCreated() {
            final String bucket = ownVersionedBucket("gdg");
            final Transaction posted = handoffTransaction(handoffTransactionId(0));
            final StepExecution step = MetaDataInstanceFactory.createStepExecution();
            final TransactionWriter writer = productionWriter(bucket);

            writeThrough(writer, step, posted);

            final String published =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            assertThat(published)
                    .as("the writer must publish the key it created under '%s'; without it a later step has "
                            + "nothing to read and the (+1)-then-read handoff cannot happen at all",
                            TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY)
                    .isNotBlank()
                    // The prefix constant is already separator-terminated, and the writer strips a trailing
                    // separator before composing so that its own template supplies exactly one. Appending
                    // another here would demand a doubled separator - the empty key segment the consuming
                    // reader is right to refuse.
                    .startsWith(handoffGenerationPrefix)
                    .doesNotContain("//");

            assertThat(keysUnder(bucket, handoffGenerationPrefix))
                    .as("the object must exist at exactly the published key - not at a key the test derived")
                    .containsExactly(published);
            assertThat(get(bucket, published))
                    .as("app/cpy/CVTRA05Y.cpy declares RECLN = 350, and the length that matters is the "
                            + "length of the object that landed after the client encoded and streamed it")
                    .hasSize(TRANSACTION_RECORD_LENGTH)
                    .isEqualTo(writer.composeFixedWidthImage(posted)
                            .getBytes(StandardCharsets.ISO_8859_1));
        }

        /**
         * The ordered job-scoped generation the writer publishes is complete and in creation order.
         *
         * <p>{@code app/proc/TRANREPT.prc:L31} writes {@code (+1)} and {@code :L37} reads the same spelling,
         * so a consumer needs the exact keys and not a count. Both the indexed entries and their count come
         * from production; the test asserts they agree with what the object store holds.
         */
        @Test
        @DisplayName("the ordered job-scoped generation lists every key the writer created, in order")
        void theOrderedGenerationListsEveryKeyInCreationOrder() {
            final String bucket = ownVersionedBucket("gdg");
            final StepExecution step = MetaDataInstanceFactory.createStepExecution();
            final TransactionWriter writer = productionWriter(bucket);

            final List<String> created = new ArrayList<>();
            for (int chunk = 0; chunk < multiRecordCount; chunk++) {
                writeThrough(writer, step, handoffTransaction(handoffTransactionId(chunk)));
                created.add(step.getExecutionContext()
                        .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY));
            }

            final ExecutionContext jobContext = step.getJobExecution().getExecutionContext();
            assertThat(jobContext.getLong(TransactionWriter.OBJECT_KEYS_COUNT_ENTRY, -1L))
                    .as("the count under '%s' must equal the number of objects created",
                            TransactionWriter.OBJECT_KEYS_COUNT_ENTRY)
                    .isEqualTo(multiRecordCount);

            final List<String> promoted = new ArrayList<>();
            for (int index = 0; index < multiRecordCount; index++) {
                promoted.add(jobContext.getString(TransactionWriter.objectKeysIndexEntry(index)));
            }
            assertThat(promoted)
                    .as("the indexed entries must reproduce creation order exactly, because a consumer reads "
                            + "index 0 through count-1 rather than re-resolving 'the latest'")
                    .containsExactlyElementsOf(created);
            assertThat(promoted)
                    .as("and creation order must agree with lexicographic order, which is what makes the "
                            + "greatest key the current generation")
                    .isSortedAccordingTo(Comparator.naturalOrder());
            assertThat(keysUnder(bucket, handoffGenerationPrefix))
                    .as("every published key must name an object that exists")
                    .containsExactlyElementsOf(created);
        }

        /**
         * The promoted key is what the production reader opens, and the record round-trips.
         *
         * <p>This closes the loop the class previously left open: the writer composes and publishes a key,
         * the context carries it, and {@link TransactionBackupReader} - not a test helper - resolves and
         * decodes it. LocalStack is the external boundary; both ends of the handoff are production code.
         */
        @Test
        @DisplayName("the production reader opens the promoted key and decodes the record the writer wrote")
        void theProductionReaderConsumesThePromotedKey() {
            final String bucket = ownVersionedBucket("gdg");
            final String transactionId = handoffTransactionId(0);
            final Transaction posted = handoffTransaction(transactionId);
            final StepExecution step = MetaDataInstanceFactory.createStepExecution();

            writeThrough(productionWriter(bucket), step, posted);
            final String promoted =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);

            final TransactionBackupReader reader = productionReader(bucket, promoted);
            final ExecutionContext readerContext = new ExecutionContext();
            reader.open(readerContext);
            try {
                final Transaction decoded = reader.read();

                assertThat(decoded)
                        .as("the reader must return the record the writer emitted, not null: a promoted key "
                                + "that decodes to nothing is the silent-empty-report failure "
                                + "app/proc/TRANREPT.prc:L27-L37 makes impossible on the mainframe")
                        .isNotNull();
                assertThat(decoded.getTransactionId().strip()).isEqualTo(transactionId);
                assertThat(decoded.getCardNumber().strip()).isEqualTo(posted.getCardNumber().strip());
                assertThat(decoded.getAmount())
                        .as("a monetary value crosses the boundary as a decimal and is compared with "
                                + "compareTo, never with equals")
                        .usingComparator(BigDecimal::compareTo)
                        .isEqualTo(posted.getAmount());
                assertThat(reader.read())
                        .as("one record was written, so the second read is a clean end of data - null, "
                                + "which is the Spring Batch exhaustion signal and not an error")
                        .isNull();
                assertThat(reader.getRecordsRead())
                        .as("exactly one record must be counted as read")
                        .isEqualTo(1L);

                reader.update(readerContext);
                assertThat(readerContext.getString(generationObjectKeyContextEntry, ""))
                        .as("the resolved generation must be checkpointed under '%s' so a restart resumes on "
                                + "the identical generation rather than on whatever is newest by then",
                                generationObjectKeyContextEntry)
                        .isEqualTo(promoted);
            } finally {
                reader.close();
            }
        }

        /**
         * A promoted key outranks the lexicographically greatest one.
         *
         * <p><strong>The decisive test, and one that cannot be written without production code on both
         * ends.</strong> The writer creates two generations; the <em>earlier</em> one is promoted. A reader
         * that treated {@code (+1)} on the read side as "whatever is newest" would open the later key and
         * pass every isolated test while racing in the pipeline. Only the promoted key may win.
         */
        @Test
        @DisplayName("a promoted key outranks the lexicographically greatest, which is the precedence a "
                + "concurrent producer would otherwise break")
        void aPromotedKeyOutranksTheLexicalGreatest() {
            final String bucket = ownVersionedBucket("gdg");
            final String earlierId = handoffTransactionId(0);
            final String laterId = handoffTransactionId(1);
            final StepExecution step = MetaDataInstanceFactory.createStepExecution();
            final TransactionWriter writer = productionWriter(bucket);

            writeThrough(writer, step, handoffTransaction(earlierId));
            final String earlierKey =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            writeThrough(writer, step, handoffTransaction(laterId));
            final String laterKey =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);

            assertThat(earlierKey)
                    .as("the two generations must be distinct and ordered, or the precedence below proves "
                            + "nothing")
                    .isLessThan(laterKey);

            final TransactionBackupReader reader = productionReader(bucket, earlierKey);
            reader.open(new ExecutionContext());
            try {
                assertThat(reader.read())
                        .extracting(decoded -> decoded.getTransactionId().strip())
                        .as("the reader must open the PROMOTED generation '%s'; resolving the greatest key "
                                + "instead would have returned transaction %s and would have selected a "
                                + "concurrent job's generation between two steps of this one",
                                earlierKey, laterId)
                        .isEqualTo(earlierId);
            } finally {
                reader.close();
            }
        }

        /**
         * A checkpointed key outranks a promoted one, so a restart cannot change generation mid-flight.
         */
        @Test
        @DisplayName("a checkpointed key outranks a promoted one, so a restart resumes on the same "
                + "generation")
        void aCheckpointedKeyOutranksAPromotedKey() {
            final String bucket = ownVersionedBucket("gdg");
            final String checkpointedId = handoffTransactionId(0);
            final String promotedId = handoffTransactionId(1);
            final StepExecution step = MetaDataInstanceFactory.createStepExecution();
            final TransactionWriter writer = productionWriter(bucket);

            writeThrough(writer, step, handoffTransaction(checkpointedId));
            final String checkpointedKey =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            writeThrough(writer, step, handoffTransaction(promotedId));
            final String promotedKey =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);

            final ExecutionContext restarted = new ExecutionContext();
            restarted.putString(generationObjectKeyContextEntry, checkpointedKey);

            final TransactionBackupReader reader = productionReader(bucket, promotedKey);
            reader.open(restarted);
            try {
                assertThat(reader.read())
                        .extracting(decoded -> decoded.getTransactionId().strip())
                        .as("the checkpointed generation must win over the promoted one; the alternative is "
                                + "a restart that silently switches generation and double-counts or skips")
                        .isEqualTo(checkpointedId);
            } finally {
                reader.close();
            }
        }

        /**
         * With nothing promoted and nothing checkpointed, the production reader resolves the current
         * generation - and it resolves the key the production writer created.
         *
         * <p>This is the {@code (0)} semantic proved through production code on both ends rather than
         * through a listing the test performed itself.
         */
        @Test
        @DisplayName("with nothing promoted, the reader resolves the current generation - the greatest key "
                + "the writer created")
        void theStandaloneResolutionLandsOnTheWritersGreatestKey() {
            final String bucket = ownVersionedBucket("gdg");
            final String earlierId = handoffTransactionId(0);
            final String latestId = handoffTransactionId(1);
            final StepExecution step = MetaDataInstanceFactory.createStepExecution();
            final TransactionWriter writer = productionWriter(bucket);

            writeThrough(writer, step, handoffTransaction(earlierId));
            writeThrough(writer, step, handoffTransaction(latestId));
            final String greatestKey =
                    step.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);

            final TransactionBackupReader reader = productionReader(bucket, null);
            final ExecutionContext readerContext = new ExecutionContext();
            reader.open(readerContext);
            try {
                assertThat(reader.read())
                        .extracting(decoded -> decoded.getTransactionId().strip())
                        .as("the standalone (0) semantic must resolve the greatest key, which is the last "
                                + "one the writer created")
                        .isEqualTo(latestId);
                reader.update(readerContext);
                assertThat(readerContext.getString(generationObjectKeyContextEntry, ""))
                        .as("and the key it resolved must be the one production published, never one the "
                                + "test recomputed")
                        .isEqualTo(greatestKey);
            } finally {
                reader.close();
            }

            assertThat(currentGenerationKey(bucket, handoffGenerationPrefix))
                    .as("the harness helper is kept honest by being compared against the production "
                            + "resolution rather than standing in for it: both must name the same key, so a "
                            + "change to either side fails here instead of drifting silently")
                    .contains(greatestKey);
        }

        /**
         * A generation base with nothing under it fails loudly on open, through the production reader.
         *
         * <p>{@code app/proc/TRANREPT.prc:L27-L31} creates the generation before {@code :L36-L37} reads it,
         * so an absent generation means the backup step did not run. Reporting on nothing would look like a
         * quiet day's trading, so it is file status {@code '35'} and an abend rather than an empty read.
         */
        @Test
        @DisplayName("an absent generation abends on open rather than reading zero records")
        void anAbsentGenerationAbendsOnOpen() {
            final String bucket = ownVersionedBucket("gdg");
            final TransactionBackupReader reader = productionReader(bucket, null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .as("an empty generation base is a failure, not a zero-row read")
                    .withMessageContaining(handoffGenerationPrefix)
                    .withMessageContaining(bucket);

            assertThat(keysUnder(bucket, handoffGenerationPrefix))
                    .as("and nothing may have been created as a side effect of failing to resolve")
                    .isEmpty();
        }
    }

    /**
     * Uploads whose body is produced lazily, and therefore declare no content length.
     *
     * <p><strong>Why this group exists.</strong> {@code CREASTMT STEP010} projects the transaction relation
     * straight into the upload rather than collecting it first, so the body's size is not known until the last
     * record has been produced and no {@code contentLength} can be declared. That is a real change in how the
     * object-storage boundary is used - every other writer in this system hands over a {@code byte[]} whose
     * length it knows - and a unit test over a mocked {@code S3Operations} cannot prove the store accepts it.
     * These two tests are the ones that can, because they use the context's real template against the
     * emulator.
     *
     * <p>The second test crosses the buffering threshold deliberately. Below it the transfer is a single
     * {@code PutObject} and the absent length is trivially fine; above it the transfer becomes a multipart
     * upload, which is the mechanism that makes peak memory independent of object size and therefore the
     * mechanism the resource-consumption remedy actually rests on. Asserting only the small case would leave
     * the load-bearing half untested.
     */
    @Nested
    @DisplayName("streamed uploads that declare no content length, as the statement projection does")
    class StreamedUploadWithoutContentLength {

        /** Sole constructor, used by the JUnit Platform. */
        StreamedUploadWithoutContentLength() {
            super();
        }

        /**
         * A short body with no declared length round-trips byte for byte.
         */
        @Test
        @DisplayName("an upload with no declared content length round-trips byte for byte")
        void anUploadWithNoDeclaredContentLengthRoundTripsByteForByte() {
            final String bucket = ownVersionedBucket("streamed");
            final String key = generationObjectKey(rejectGenerationBase, 0, "TRXFL.SEQ");
            final byte[] written = fixedWidthRecords(3);

            s3Template().upload(bucket, key, new ByteArrayInputStream(written),
                    ObjectMetadata.builder().contentType("application/octet-stream").build());

            assertThat(get(bucket, key))
                    .as("the store determines the length as it transfers, so omitting it neither truncates "
                            + "the body nor alters a byte of it - and trailing blanks are load bearing in a "
                            + "350-byte fixed-width record")
                    .isEqualTo(written);
        }

        /**
         * A body larger than one buffered part round-trips byte for byte, which is the multipart path.
         */
        @Test
        @DisplayName("a body larger than one buffered part round-trips byte for byte, as multipart")
        void aBodyLargerThanOneBufferedPartRoundTripsByteForByte() {
            final String bucket = ownVersionedBucket("streamed");
            final String key = generationObjectKey(rejectGenerationBase, 1, "TRXFL.SEQ");
            // 15,001 records of 350 bytes is 5,250,350 bytes: just over the 5 MB minimum part size, so the
            // transfer must become multipart rather than a single PutObject.
            final byte[] written = fixedWidthRecords(15_001);

            s3Template().upload(bucket, key, new ByteArrayInputStream(written),
                    ObjectMetadata.builder().contentType("application/octet-stream").build());

            final byte[] read = get(bucket, key);
            assertThat(read.length)
                    .as("the reassembled object must be an exact multiple of the 350-byte record length "
                            + "declared by RECORDSIZE(350 350) at app/jcl/CREASTMT.JCL:32, with no part "
                            + "boundary artefact")
                    .isEqualTo(written.length)
                    .satisfies(length -> assertThat(length.intValue() % TRANSACTION_RECORD_LENGTH).isZero());
            assertThat(read)
                    .as("multipart reassembly must be byte-exact; a shifted part boundary would move every "
                            + "field offset after it and corrupt the statement output silently")
                    .isEqualTo(written);
        }

        /**
         * Builds {@code count} synthetic 350-byte records whose content varies per record.
         *
         * <p>The content varies deliberately: a payload of repeated identical bytes would round-trip
         * correctly even if a part boundary duplicated or dropped a whole record, so it could not detect the
         * failure this test exists to detect.
         *
         * @param count how many records to build
         * @return the concatenated bytes, exactly {@code count * 350} long
         */
        private byte[] fixedWidthRecords(final int count) {
            final StringBuilder image = new StringBuilder(count * TRANSACTION_RECORD_LENGTH);
            for (int index = 1; index <= count; index++) {
                final String marker = String.format(Locale.ROOT, "RECORD-%016d", Integer.valueOf(index));
                image.append(marker)
                        .append(" ".repeat(TRANSACTION_RECORD_LENGTH - marker.length()));
            }
            return image.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }
}
