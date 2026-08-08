/*
 * ******************************************************************
 * Program     : DailyTransactionReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (fixed-width, position-aware)
 * Function    : Read the 350-byte DALYTRAN daily transaction records with
 *               position-aware zoned-decimal overpunch decoding, replacing
 *               the DALYTRAN input path of CBTRN02C.
 * Source      : app/cbl/CBTRN02C.cbl:L236-L252, L345-L369, L582-L598
 *                   (731 lines, 26 own paragraph labels; 4 belong to this reader)
 *               app/cbl/CBTRN02C.cbl:L29-L32 (keyless SEQUENTIAL SELECT,
 *                   no RECORD KEY) and :L66-L69 (FD, X(16) + X(334) = 350)
 *               app/cpy/CVTRA06Y.cpy (350-byte DALYTRAN-RECORD, RECLN = 350)
 *               app/jcl/POSTTRAN.jcl:L30-L31 (DD, no DCB/LRECL)
 *               app/catlg/LISTCAT.txt:L786 (NONVSAM entry only, no CLUSTER)
 *               app/data/ASCII/dailytran.txt (300 rows x 350 B, Gate 1)
 *               app/cbl/CBTRN01C.cbl (491 lines, read-only pre-flight)
 *               @ 7756d89
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
package com.cardemo.batch.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Sequential reader for the {@code DALYTRAN} daily-transaction input, reproducing the four DALYTRAN
 * paragraphs of the COBOL batch program {@code app/cbl/CBTRN02C.cbl} and owning the only
 * position-aware zoned-decimal decoder for the {@code app/cpy/CVTRA06Y.cpy} record layout.
 *
 * <h2>What it does</h2>
 * Hands one staged daily transaction to the step at a time, in file order, and answers {@code null}
 * when the input is exhausted. It performs no validation, no posting, no reject writing, no account
 * update and no category-balance arithmetic: those are the concern of the sibling classes named
 * below.
 *
 * <p><b>The DALYTRAN dataset is keyless and sequential.</b>
 * {@code app/cbl/CBTRN02C.cbl:L29-L32} declares
 * {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN / ORGANIZATION IS SEQUENTIAL / ACCESS MODE IS
 * SEQUENTIAL / FILE STATUS IS DALYTRAN-STATUS} and there is <b>no {@code RECORD KEY} clause</b>.
 * That absence is the source-level proof: the other four input files the same program opens are
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS RANDOM} and a {@code RECORD KEY}
 * ({@code TRANSACT} at {@code :L34-L38}, {@code XREF} at {@code :L40-L44}, {@code ACCOUNT} at
 * {@code :L51-L55}, {@code TCATBAL} at {@code :L57-L61}), and only {@code DALYTRAN} and the
 * {@code DALYREJS} output are keyless. There is therefore no reposition, no backward read and no
 * read-for-update against this dataset, and none is offered here.
 *
 * <p><b>The DALYTRAN key length and catalogued record length are Not available.</b>
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS} appears in {@code app/catlg/LISTCAT.txt} only as a
 * {@code NONVSAM} entry, at {@code :L786}, with its companion {@code .PS.INIT} at {@code :L801}.
 * There is no {@code CLUSTER} entry for it, no {@code KEYLEN} or {@code AVGLRECL} block and no
 * {@code KEYS(...)} clause anywhere in the repository; the catalogue summary at
 * {@code app/catlg/LISTCAT.txt:L3938-L3946} reports {@code CLUSTER 10}, {@code AIX 3} and
 * {@code GDG 7}, and this dataset is none of the three. <i>Prerequisite for stating either value:</i>
 * a {@code DEFINE CLUSTER} or LISTCAT entry for {@code AWS.M2.CARDDEMO.DALYTRAN.PS}, which does not
 * exist at {@code 7756d89}. No key length is fabricated here, and a keyless dataset has none to
 * state.
 *
 * <p><b>The 350-byte geometry rests on exactly three artefacts, and a DD record length is not among
 * them.</b> {@code app/jcl/POSTTRAN.jcl:L30-L31} declares the DD as
 * {@code //DALYTRAN DD DISP=SHR, / DSN=AWS.M2.CARDDEMO.DALYTRAN.PS} with <b>no {@code DCB}, no
 * {@code LRECL} and no {@code RECFM}</b>, so no record length may be attributed to it. The three
 * artefacts that do establish 350 are: the {@code RECLN = 350} declaration at
 * {@code app/cpy/CVTRA06Y.cpy:L2} together with the field widths at {@code :L5-L18} summing to 350;
 * the FD at {@code app/cbl/CBTRN02C.cbl:L66-L69}, {@code FD-TRAN-ID PIC X(16)} plus
 * {@code FD-CUST-DATA PIC X(334)}; and the measured fixture, {@code app/data/ASCII/dailytran.txt} at
 * 105,300 bytes, which is exactly 300 rows of 350 characters plus one terminator each. The reject
 * output of the very same job <i>does</i> carry a record length,
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl:L36}; that asymmetry is
 * deliberate and must not be transferred to the input.
 *
 * <p><b>The record layout, from {@code app/cpy/CVTRA06Y.cpy:L5-L18}.</b> Offsets are one-based and
 * inclusive and sum to exactly 350.
 * <ul>
 *   <li>{@code DALYTRAN-ID PIC X(16)}, bytes 1-16, to {@code transactionId}. Ordinary non-unique
 *       data, explicitly not the key.</li>
 *   <li>{@code DALYTRAN-TYPE-CD PIC X(02)}, bytes 17-18, to {@code typeCode}.</li>
 *   <li>{@code DALYTRAN-CAT-CD PIC 9(04)}, bytes 19-22, to {@code categoryCode}.</li>
 *   <li>{@code DALYTRAN-SOURCE PIC X(10)}, bytes 23-32, to {@code transactionSource} as a plain
 *       ten-character string.</li>
 *   <li>{@code DALYTRAN-DESC PIC X(100)}, bytes 33-132, to {@code description}.</li>
 *   <li><b>{@code DALYTRAN-AMT PIC S9(09)V99}, bytes 133-143</b>, to {@code amount}. Eleven
 *       characters, no separate sign byte, trailing-sign overpunch on byte 143. <b>The only signed
 *       field in the layout.</b></li>
 *   <li>{@code DALYTRAN-MERCHANT-ID PIC 9(09)}, bytes 144-152, to {@code merchantId}.</li>
 *   <li>{@code DALYTRAN-MERCHANT-NAME PIC X(50)}, bytes 153-202, to {@code merchantName}.</li>
 *   <li>{@code DALYTRAN-MERCHANT-CITY PIC X(50)}, bytes 203-252, to {@code merchantCity}.</li>
 *   <li>{@code DALYTRAN-MERCHANT-ZIP PIC X(10)}, bytes 253-262, to {@code merchantZip}.</li>
 *   <li>{@code DALYTRAN-CARD-NUM PIC X(16)}, bytes 263-278, to {@code cardNumber}. Never logged and
 *       never placed in an exception message or the execution context.</li>
 *   <li>{@code DALYTRAN-ORIG-TS PIC X(26)}, bytes 279-304, to {@code origTs} as 26 characters of
 *       text, never parsed.</li>
 *   <li>{@code DALYTRAN-PROC-TS PIC X(26)}, bytes 305-330, to {@code procTs} as 26 characters of
 *       text, blank in every fixture row and preserved blank.</li>
 *   <li>{@code FILLER PIC X(20)}, bytes 331-350. Carries no data and is deliberately not
 *       modelled.</li>
 * </ul>
 *
 * <p><b>Timestamps are text and are never parsed.</b> Both stamps are {@code PIC X(26)} and the
 * corpus contains mutually incompatible producers for them: the batch producer at
 * {@code app/cbl/CBTRN02C.cbl:L149-L174} composes {@code EEEE-MM-DD-UU.MM.SS.HH0000} - hyphen and
 * dot separators, two hundredths digits from {@code COB-MIL PIC X(02)} into
 * {@code DB2-MIL PIC 9(002)}, then four literal zeros from {@code DB2-REST PIC X(04)} - while every
 * row of {@code app/data/ASCII/dailytran.txt} carries the online form
 * {@code 2022-06-10 19:27:53.000000}, with a space separator, colons and six fractional digits. A
 * temporal type would have to choose one and would fail on the other, so both stamps stay
 * {@code String} and travel through unchanged.
 *
 * <p><b>Division of labour across the 27 paragraphs of {@code app/cbl/CBTRN02C.cbl}.</b> The program
 * holds 26 labelled {@code PROCEDURE DIVISION} paragraphs plus the unlabelled mainline at
 * {@code :L193-L234}. This reader implements four of them and reproduces one shared contract:
 * <ul>
 *   <li>{@code 0000-DALYTRAN-OPEN} ({@code :L236-L252}) as {@code openDailyTransactionFile()}.</li>
 *   <li>{@code 1000-DALYTRAN-GET-NEXT} ({@code :L345-L369}) as
 *       {@code getNextDailyTransaction()}.</li>
 *   <li>{@code 9000-DALYTRAN-CLOSE} ({@code :L582-L598}) as
 *       {@code closeDailyTransactionFile()}.</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS} ({@code :L714-L727}, shared) as
 *       {@code displayIoStatus(String)}.</li>
 *   <li>{@code 9999-ABEND-PROGRAM} ({@code :L707-L711}, shared) as
 *       {@code abendProgram(String, Throwable)}.</li>
 * </ul>
 * The remaining 23 belong elsewhere and are <b>not</b> implemented here:
 * {@code 1500-VALIDATE-TRAN}, {@code 1500-A-LOOKUP-XREF}, {@code 1500-B-LOOKUP-ACCT},
 * {@code 2000-POST-TRANSACTION}, {@code 2700-UPDATE-TCATBAL}, {@code 2700-A-CREATE-TCATBAL-REC},
 * {@code 2700-B-UPDATE-TCATBAL-REC}, {@code 2800-UPDATE-ACCOUNT-REC} and
 * {@code Z-GET-DB2-FORMAT-TIMESTAMP} to
 * {@link com.cardemo.batch.processors.TransactionPostingProcessor};
 * {@code 2900-WRITE-TRANSACTION-FILE} and {@code 0100-TRANFILE-OPEN} and
 * {@code 9100-TRANFILE-CLOSE} to {@link com.cardemo.batch.writers.TransactionWriter};
 * {@code 2500-WRITE-REJECT-REC} and {@code 0300-DALYREJS-OPEN} and {@code 9300-DALYREJS-CLOSE} to
 * {@link com.cardemo.batch.writers.RejectWriter}; and the mainline together with
 * {@code 0200-XREFFILE-OPEN}, {@code 0400-ACCTFILE-OPEN}, {@code 0500-TCATBALF-OPEN},
 * {@code 9200-XREFFILE-CLOSE}, {@code 9400-ACCTFILE-CLOSE} and {@code 9500-TCATBALF-CLOSE} to
 * {@code com.cardemo.batch.jobs.DailyTransactionPostingJob}; {@code com.cardemo.config.BatchConfig}
 * wires the step.
 *
 * <p><b>Return code 4 is not this reader's decision.</b> {@code app/cbl/CBTRN02C.cbl:L229-L231}
 * reads {@code IF WS-REJECT-COUNT &gt; 0 / MOVE 4 TO RETURN-CODE / END-IF}, and there is no other
 * determinant. It is set if and only if the reject count exceeds zero, it is not a failure, and it
 * belongs to the posting job.
 *
 * <p><b>{@code app/cbl/CBTRN01C.cbl} is context, not a responsibility.</b> That program, 491 lines,
 * is the read-only pre-flight over the same six datasets - six {@code SELECT} statements with a verb
 * inventory of {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY} only and zero
 * {@code WRITE}, {@code REWRITE} or {@code DELETE} anywhere. It has no JCL member and no distinct
 * target job; it contributes a labelled read-only pre-flight step to the posting job. None of its
 * paragraphs is implemented here.
 *
 * <h2>How to run, build and test</h2>
 * The bean is {@code @StepScope}, so one instance exists per step execution and nothing runs at
 * application start: every profile sets {@code spring.batch.job.enabled: false}. The {@code Job} and
 * {@code Step} that drive it are declared by {@code com.cardemo.config.BatchConfig}, which is
 * authored, and launched by {@code com.cardemo.batch.jobs.DailyTransactionPostingJob}, which
 * <strong>is also authored</strong>: the owning job is delivered, so this reader is reachable end to end
 * from its own step. The name-driven entry point above it,
 * {@link com.cardemo.batch.jobs.BatchPipelineOrchestrator}, is authored too. Neither is planned, and the
 * point either would make is one about sequencing rather than about this reader or its job.
 *
 * <p>Build and static gates, from the repository root:
 * {@code ./mvnw -B -ntp clean verify}. The compiler runs at
 * {@code release 25} with {@code -Xlint:all -Werror} and {@code failOnWarning}, and the
 * documentation gate runs {@code javadoc-no-fork} with {@code doclint=all},
 * {@code failOnWarnings=true} and {@code show=private}, so every private member of this file is
 * inside the gate. Compile only: {@code ./mvnw -B -ntp -Ddependency-check.skip=true -DskipTests
 * compile}. A pinned container is equivalent and needs no host toolchain:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests
 * compile}.
 *
 * <p>Tests belong in {@code src/test/java/com/cardemo/unit/batch} for the decoder and the status
 * rendering, {@code src/test/java/com/cardemo/integration/batch} for the step against a
 * Testcontainers PostgreSQL and a LocalStack emulator, and the Gate 1 end-to-end test drives all 300
 * rows of {@code app/data/ASCII/dailytran.txt}. Those suites must assert the worked decodes
 * {@code 0000005047G} to {@code 504.77}, {@code 0000009190}<code>}</code> to {@code -919.00} and
 * {@code 0000000678H} to {@code 67.88} at scale 2; that {@code procTs} is 26 spaces and is neither
 * {@code null} nor empty; that {@code OPERATOR} passes through as a ten-character source; and that
 * the type split is 250 rows of {@code 01} to 50 rows of {@code 03}. No test file is created by this
 * class.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 *   <li>{@code carddemo.batch.daily-transaction-reader.source} - the input selector, one of
 *       {@code repository} or {@code fixed-width}, case-insensitive, hyphen or underscore.
 *       <b>Default {@code repository}.</b> See {@link InputSource}; both values are reachable and
 *       neither branch is dead.</li>
 *   <li>{@code carddemo.batch.daily-transaction-reader.page-size} - rows per database round trip on
 *       the {@code repository} path, defaulting to {@value #DEFAULT_PAGE_SIZE}, matching the four
 *       sibling readers declared at {@code src/main/resources/application.yml:1219-1226}. A fetch
 *       size, not a pagination contract.</li>
 *   <li>{@code carddemo.batch.daily-transaction-reader.object-key} - the object holding the
 *       350-byte image on the {@code fixed-width} path, defaulting to
 *       {@value #DEFAULT_OBJECT_KEY}. The whole key is configurable, so the date-partitioned prefix
 *       of the target design is expressed by configuring a fuller key and needs no code change.</li>
 *   <li>{@code carddemo.aws.s3.batch-input-bucket} - the input bucket, declared at
 *       {@code src/main/resources/application.yml:1064} as
 *       {@code ${CARDDEMO_S3_BATCH_INPUT_BUCKET}} and provisioned by
 *       {@code localstack-init/init-aws.sh}. Bound here with an empty default and required non-blank
 *       <b>only</b> when the {@code fixed-width} path is selected, so the default path carries no
 *       cloud prerequisite.</li>
 *   <li>Record geometry is <b>not</b> configuration. 350 is a compile-time constant, as
 *       {@code src/main/resources/application.yml:1284-1300} records for every fixed-width length in
 *       the tree: a settable byte contract would let a deployment break parity by editing a
 *       profile.</li>
 *   <li>The charset is fixed in code at {@code ISO-8859-1} and passed explicitly at every decode
 *       site; no platform default, locale or time zone is ever consulted.</li>
 *   <li>{@code carddemo.security.jwt.signing-key} - the material the input object's authenticity key is
 *       derived from. <b>No default anywhere in this repository</b>, and required non-blank only when the
 *       {@code fixed-width} path is selected. See the authenticity section below.</li>
 *   <li>Chunk size and the step's commit interval come from {@code com.cardemo.config.BatchConfig}
 *       and {@code carddemo.batch.chunk-size}; they are not this class's to set.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} and {@code spring.jpa.open-in-view: false}
 *       hold in every profile, so the mapping is verified against the Flyway schema and no lazy
 *       load escapes the step.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><b>End of data is not an error.</b> {@link #read()} answers {@code null}, which is the
 *       Spring Batch end-of-input signal and the exact analogue of file status {@code '10'} driving
 *       {@code MOVE 16 TO APPL-RESULT} and {@code MOVE 'Y' TO END-OF-FILE} at
 *       {@code app/cbl/CBTRN02C.cbl:L351-L361}, where {@code 88 APPL-EOF VALUE 16} is declared at
 *       {@code :L144}. An empty input is a successful run with a row count of zero.</li>
 *   <li><b>Any other status abends.</b> A status that is neither {@code '00'} nor {@code '10'}
 *       renders {@code FILE STATUS IS: NNNN} followed by four characters and then throws
 *       {@link FatalProcessingException} with abend code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and process return
 *       code {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}.</li>
 *   <li><b>A row length other than 350</b> throws {@link DataIntegrityException} naming the row
 *       number, the observed length and the expected 350 - never the record content.</li>
 *   <li><b>A record separator inside the input object</b> throws {@link DataIntegrityException} naming
 *       the row number and the separator byte. The {@code fixed-width} path is undelimited, so an
 *       object whose length is not a whole multiple of 350 - a line-terminated file uploaded by
 *       mistake, or a transfer truncated part-way - is refused rather than read as though every record
 *       after the stray byte were still aligned. Remedy: upload the concatenated 350-byte images, or
 *       use the {@code repository} path, which is what ingests the line-terminated ASCII fixture.</li>
 *   <li><b>An unrecognised terminal overpunch character</b> in bytes 133-143 throws
 *       {@link DataIntegrityException} naming the row number and the offending byte position, never
 *       the record content.</li>
 *   <li><b>A {@code FileNotFoundException} or a missing-key failure mentioning
 *       {@code dalytran.txt} means the wrong fixture name was used.</b> The fixture is
 *       {@code app/data/ASCII/dailytran.txt}, spelled in full; {@code dalytran.txt} does not exist.
 *       The DD name and the dataset are {@code DALYTRAN}; the fixture is not.</li>
 *   <li><b>Amounts appearing positive where the fixture holds negatives</b> means a normalisation
 *       pass was wrongly introduced. There is none here: no absolute value, no sign stripping, no
 *       unconditional negation. The fixture's byte-143 census over all 300 rows is
 *       {@code C} 30, {@code B} 29, {@code D} 29, {@code A} 28, <code>{</code> 25, {@code G} 24,
 *       {@code I} 24, {@code E} 23, {@code F} 21, {@code H} 17, {@code R} 8, {@code P} 7,
 *       {@code M} 6, <code>}</code> 6, {@code K} 5, {@code L} 5, {@code O} 4, {@code Q} 4,
 *       {@code J} 3, {@code N} 2 - so 44 rows carry a non-zero negative code and 6 carry
 *       <code>}</code>, giving 50 negative amounts against 250 positive.</li>
 *   <li><b>A rejected {@code OPERATOR} source</b> means an enum was wrongly applied to a plain
 *       string column. {@code DailyTransaction.transactionSource} is a {@code CHAR(10)} string;
 *       {@code com.cardemo.model.enums.TransactionSource} holds exactly two constants and is neither
 *       imported nor referenced by this class. 50 of the 300 fixture rows carry
 *       {@code OPERATOR}.</li>
 *   <li><b>A null, empty or trimmed processing stamp</b> means the 26 spaces were wrongly
 *       normalised. All 300 fixture rows carry exactly 26 spaces there and it must survive as
 *       exactly 26 spaces.</li>
 *   <li><b>A blank bucket on the {@code fixed-width} path</b> fails construction with a message
 *       naming {@code CARDDEMO_S3_BATCH_INPUT_BUCKET}, before any read is attempted.</li>
 *   <li><b>Return code 4 is not a failure of this reader</b> and is not set by it; see the division
 *       of labour above.</li>
 * </ul>
 *
 * <h2>Parity structures preserved, and never to be deleted as dead code</h2>
 * <ol>
 *   <li><b>{@code app/cbl/CBTRN02C.cbl:L349} is {@code * DISPLAY DALYTRAN-RECORD}, commented
 *       out</b>, inside {@code 1000-DALYTRAN-GET-NEXT}. Reproduced as a Java comment at the
 *       corresponding position, never as executable code.</li>
 *   <li><b>{@code app/cbl/CBTRN02C.cbl:L207} is also {@code * DISPLAY DALYTRAN-RECORD}, commented
 *       out</b>, in the mainline. Consequently <b>this program emits no per-record dump at all</b>,
 *       unlike {@code app/cbl/CBACT03C.cbl} and {@code app/cbl/CBCUS01C.cbl}, which emit twice, and
 *       {@code app/cbl/CBACT02C.cbl}, which emits once. No per-record log event is added here, which
 *       is also why no masking question arises for the 16-character card number at bytes
 *       263-278.</li>
 *   <li><b>{@code MOVE 8 TO  APPL-RESULT.} at {@code :L583} carries two spaces after {@code TO}</b>,
 *       and this program uses {@code MOVE 8} on close where {@code app/cbl/CBACT01C.cbl:L152} and
 *       its three siblings use {@code ADD 8 TO ZERO GIVING}. Recorded, not normalised.</li>
 *   <li><b>{@code FD-CUST-DATA} at {@code :L69} is a misnomer in a transaction FD.</b> Cited as
 *       written.</li>
 *   <li><b>The error literals are inconsistent and stay inconsistent.</b>
 *       {@code 'ERROR OPENING DALYTRAN'} at {@code :L247} omits the word {@code FILE} that
 *       {@code 'ERROR READING DALYTRAN FILE'} at {@code :L363} and
 *       {@code 'ERROR CLOSING DALYTRAN FILE'} at {@code :L593} both carry.</li>
 * </ol>
 *
 * <h2>Authenticity of the input object, and the producer contract</h2>
 *
 * <p>On the {@code fixed-width} path this class reads an
 * object that something outside this application wrote. Parsing it correctly proves it is well formed, not
 * that it is ours: the bucket lives in an emulator whose community edition was <em>measured</em> to enforce no
 * authorisation at all - the evidence is recorded on the {@code localstack} service in
 * {@code docker-compose.yml} - so any principal able to reach the emulator port could replace this object
 * with 350-byte records that parse perfectly and post as real transactions. Network containment narrows who
 * can reach the port; it cannot tell one reachable principal from another.
 *
 * <p>The object is therefore refused unless it carries a keyed authenticity envelope over its own manifest
 * <em>and</em> its bytes match the digest that manifest vouches for. Both halves are necessary: the code alone
 * would accept a valid manifest copied onto a different body of the same length, and a digest alone would
 * accept a body and manifest that an attacker wrote together. There is <b>no unverified mode</b> - a control
 * that can be switched off by omitting configuration is a control that will be off - and the check runs
 * before the first record is parsed, because verifying while streaming would authenticate the tail only after
 * the head had already been posted.
 *
 * <p>The full producer contract, including the six-line canonical manifest, the three metadata members and
 * the two-step key derivation, is documented on {@link InputObjectEnvelope}. It is stated there in full and
 * kept executable through {@link InputObjectEnvelope#sign(String, String, long, String, String, String)},
 * because the producer of this object is outside this repository and a contract it cannot compute is not a
 * contract. The {@code repository} path authenticates nothing and needs no key, because nothing external is
 * read there.
 *
 * <h2>Log hygiene: what this class will not name</h2>
 *
 * <p>Three values are deliberately absent from every event this class emits, at every level, and each
 * absence is a decision rather than an omission.
 *
 * <ol>
 *   <li><b>The configured input object key.</b> The {@code fixed-width} path reads a date-partitioned key,
 *       so the key carries a business date; and because it is the same key for every record in a run,
 *       naming it once names it for the whole run. It is passed to object storage and to nothing else. An
 *       operator who is entitled to it reads it from configuration, where it already is, rather than from
 *       the log stream, where it would be aggregated, retained and replicated far more widely.</li>
 *   <li><b>The checkpointed transaction identifier.</b> Reported as {@code present} or {@code absent}, never
 *       by value. The record count is the restart position; the identifier only corroborates it, so naming
 *       it would disclose a business record identifier and buy nothing.</li>
 *   <li><b>Any field of any record.</b> The source emits no per-record dump on this path at all - both
 *       {@code DISPLAY DALYTRAN-RECORD} statements are commented out in the corpus, as recorded above - so
 *       there is no parity obligation to emit one, and none is emitted. That is why no masking question
 *       arises here for the card number at bytes 263-278.</li>
 * </ol>
 *
 * <p>What remains is the logical dataset name, the selected input source, the row and record counts, the
 * rendered {@code FILE STATUS}, and the three verbatim error literals. Every one of those is a property of
 * the run rather than of anybody's account, which is the line this class draws.
 *
 * <h2>Constraints on this translation</h2>
 * <ul>
 *   <li>{@code transactionSource} stays a plain string. Mapping it to
 *       {@code com.cardemo.model.enums.TransactionSource}, or adding a third constant to that enum, breaks
 *       50 of the 300 fixture rows.</li>
 *   <li>{@code origTs} and {@code procTs} stay {@code String}. Treating either as a temporal type destroys
 *       parity, because the corpus has mutually incompatible producers for a {@code PIC X(26)} stamp.</li>
 *   <li>There is no catalogue entry for the DALYTRAN dataset, so its key length and catalogued record length
 *       cannot be cited from {@code app/catlg/LISTCAT.txt}, and no DD {@code LRECL} exists for it either.
 *       The record geometry is therefore cited from the copybook, the FD and the measured fixture, as
 *       above, rather than invented.</li>
 *   <li>The fixture is {@code dailytran.txt}, not {@code dalytran.txt}; every test-resource path uses the
 *       fixture's actual name even though the mainframe DD and dataset spell it {@code DALYTRAN}.</li>
 *   <li>An {@code INFO} emission on this path must name neither the concrete input object key nor the
 *       checkpointed transaction identifier: that would publish a date-partitioned key and a business record
 *       identifier into a log stream enabled in every deployment. Both are excluded at source, for the
 *       reasons given in the log-hygiene section above, and
 *       {@code src/test/java/com/cardemo/unit/batch/BatchLogHygieneTest.java} holds that exclusion.</li>
 *   <li>The three error literals are mutually inconsistent in the source. They are reproduced verbatim and
 *       the inconsistency is documented above rather than harmonised.</li>
 *   <li>{@code FD-CUST-DATA} names customer data inside a transaction FD, and {@code MOVE 8 TO  APPL-RESULT.}
 *       carries a double space where the sibling readers do not. Both are cited as written; renaming or
 *       normalising either would break the citation.</li>
 *   <li>The batch timestamp producer emits hundredths plus four zeros rather than milliseconds, evidenced at
 *       {@code app/cbl/CBTRN02C.cbl:L149-L174}. This reader produces no timestamp, so the distinction binds
 *       the writer side rather than this class - which is why the citation is to the source and not to
 *       prose.</li>
 *   <li>The {@code repository} path resumes a restart by row ordinal rather than by key, because
 *       {@link DailyTransactionRepository} declares no keyset finder and none is added from here. See
 *       {@link #restoreRestartCursor(ExecutionContext)} for the exact consequence and the mitigating
 *       fact.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> Not thread safe, and not required to be: the {@code step} scope gives
 * each step execution its own instance and Spring Batch drives a reader from one thread per step.
 * Every mutable field is an instance field; there is no static mutable state anywhere in the class.
 *
 * @see com.cardemo.batch.processors.TransactionPostingProcessor
 * @see DailyTransactionRepository
 * @see FileStatusMapper
 */
@Component
@StepScope
public class DailyTransactionReader implements ItemStreamReader<DailyTransaction> {

    /**
     * The class logger, and the only static member of this class that is not a constant.
     * <p>
     * SLF4J is the sole logging channel: there is no {@code System.out}, no {@code System.err} and no
     * {@code printStackTrace()} anywhere in this file.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionReader.class);

    /**
     * Rows fetched per database round trip on the {@code repository} path when
     * {@code carddemo.batch.daily-transaction-reader.page-size} is absent.
     * <p>
     * 100 is the value the four sibling readers use and the value
     * {@code src/main/resources/application.yml:1219-1226} declares for each of them, so the fifth
     * reader does not introduce a second convention. It comfortably exceeds nothing in particular: the
     * 300-row Gate 1 fixture takes three round trips at this size, which is deliberate, because a
     * single-round-trip fixture would never exercise the slice-refill boundary.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * The object key read on the {@code fixed-width} path when
     * {@code carddemo.batch.daily-transaction-reader.object-key} is absent.
     * <p>
     * The prefix segment is the lower-cased DD name, matching the {@code gdg/...} prefix style of
     * {@code src/main/resources/application.yml:1091-1113}, and the object name is the fixture's
     * actual spelling. {@code localstack-init/init-aws.sh} documents the input bucket as
     * &quot;DALYTRAN staging, LRECL 350, seeded from app/data/ASCII/dailytran.txt&quot; and records
     * that the fixture is spelled {@code dailytran.txt} and never {@code dalytran.txt}.
     */
    public static final String DEFAULT_OBJECT_KEY = "dalytran/dailytran.txt";

    /** The property that selects the input path, quoted in diagnostics so an operator can find it. */
    private static final String PROPERTY_SOURCE = "carddemo.batch.daily-transaction-reader.source";

    /** The property that sets the database round-trip size on the {@code repository} path. */
    private static final String PROPERTY_PAGE_SIZE =
            "carddemo.batch.daily-transaction-reader.page-size";

    /** The property that names the object read on the {@code fixed-width} path. */
    private static final String PROPERTY_OBJECT_KEY =
            "carddemo.batch.daily-transaction-reader.object-key";

    /** The property that names the input bucket, declared once in {@code application.yml}. */
    private static final String PROPERTY_INPUT_BUCKET = "carddemo.aws.s3.batch-input-bucket";

    /**
     * The property holding the application signing key, from which the object-authenticity key is derived.
     *
     * <p>The same key the token layer uses, and deliberately so: it is mandatory in all four profiles,
     * environment-indirected with no committed default anywhere in this repository, and already refused at
     * startup when it is too short. A separate secret for this control would be one more thing an operator
     * could leave unset, and a control that is off when unconfigured is not a control. Domain separation is
     * achieved by <em>deriving</em> a single-purpose key from it - see
     * {@link InputObjectEnvelope#KEY_DERIVATION_LABEL} - so an object signature can never be replayed as a
     * token and a token can never be replayed as an object signature.
     */
    private static final String PROPERTY_SIGNING_KEY = "carddemo.security.jwt.signing-key";

    /**
     * The abend reason of an authenticity refusal, {@code ABEND-REASON PIC X(50)} of
     * {@code app/cpy/CSMSG02Y.cpy} being fifty characters wide.
     *
     * <p>Additive: the source has no counterpart, because a z/OS dataset was reachable only through the
     * catalogue and RACF and could not be replaced by an arbitrary principal. It is one closed literal so that
     * a refusal is greppable and so that no rejection reveals which of the checks it failed.
     */
    private static final String REASON_INPUT_NOT_AUTHENTIC = "DALYTRAN INPUT NOT AUTHENTIC";

    /**
     * Bytes per read while measuring the content digest.
     *
     * <p>Sixteen kibibytes: large enough that a 105,300-byte fixture is seven reads rather than three hundred,
     * small enough that the buffer is bounded and independent of the object's size. The buffer holds no content
     * after the pass, so digesting a large object costs a linear read and no retained memory.
     */
    private static final int DIGEST_BUFFER_BYTES = 16 * 1024;

    /** The environment variable behind {@link #PROPERTY_INPUT_BUCKET}, named in its failure message. */
    private static final String ENV_INPUT_BUCKET = "CARDDEMO_S3_BATCH_INPUT_BUCKET";

    /**
     * The DD name of the dataset this reader replaces, {@code app/cbl/CBTRN02C.cbl:L29} and
     * {@code app/jcl/POSTTRAN.jcl:L30}. Used in log events and exception context in place of the
     * record itself.
     */
    private static final String LOGICAL_FILE = "DALYTRAN";

    /**
     * The COBOL program named as the abend culprit, {@code app/cbl/CBTRN02C.cbl:L23}
     * {@code PROGRAM-ID. CBTRN02C}. Eight characters, which is the width
     * {@code ABEND-CULPRIT PIC X(08)} of {@code app/cpy/CSMSG02Y.cpy} allows.
     */
    private static final String ABEND_CULPRIT = "CBTRN02C";

    /** The {@code OPEN} operation name, used only as exception and log context. */
    private static final String OPERATION_OPEN = "OPEN";

    /** The {@code READ} operation name, used only as exception and log context. */
    private static final String OPERATION_READ = "READ";

    /** The {@code CLOSE} operation name, used only as exception and log context. */
    private static final String OPERATION_CLOSE = "CLOSE";

    /**
     * {@code DISPLAY 'ERROR OPENING DALYTRAN'} at {@code app/cbl/CBTRN02C.cbl:L247}.
     * <p>
     * <b>This literal omits the word {@code FILE}</b> that the read and close literals both carry.
     * That inconsistency is in the source and is reproduced verbatim; harmonising the three would be a
     * parity break. See parity structure 5 in the class documentation.
     */
    private static final String ERROR_OPENING_MESSAGE = "ERROR OPENING DALYTRAN";

    /** {@code DISPLAY 'ERROR READING DALYTRAN FILE'} at {@code app/cbl/CBTRN02C.cbl:L363}. */
    private static final String ERROR_READING_MESSAGE = "ERROR READING DALYTRAN FILE";

    /** {@code DISPLAY 'ERROR CLOSING DALYTRAN FILE'} at {@code app/cbl/CBTRN02C.cbl:L593}. */
    private static final String ERROR_CLOSING_MESSAGE = "ERROR CLOSING DALYTRAN FILE";

    /** {@code DISPLAY 'ABENDING PROGRAM'} at {@code app/cbl/CBTRN02C.cbl:L708}. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    // Record geometry, app/cpy/CVTRA06Y.cpy:L2 and :L5-L18. Compile-time constants, deliberately not
    // configuration: src/main/resources/application.yml:1284-1300 records why a byte contract must not
    // be settable. Offsets are ONE-BASED and INCLUSIVE, matching the copybook and the citations, and
    // are converted to Java's zero-based half-open form in exactly one place, in fixedWidthField.

    /**
     * The record length, 350 characters.
     * <p>
     * Established by three artefacts and by no DD statement: {@code RECLN = 350} at
     * {@code app/cpy/CVTRA06Y.cpy:L2} with the field widths at {@code :L5-L18} summing to it; the FD at
     * {@code app/cbl/CBTRN02C.cbl:L66-L69} as {@code X(16)} plus {@code X(334)}; and the measured
     * 105,300 bytes of {@code app/data/ASCII/dailytran.txt}, which is 300 times 350 plus one terminator
     * per row. {@code app/jcl/POSTTRAN.jcl:L30-L31} carries no {@code DCB} and no {@code LRECL}.
     */
    private static final int RECORD_LENGTH = 350;

    /** {@code DALYTRAN-ID PIC X(16)} start, byte 1. */
    private static final int TRANSACTION_ID_START = 1;

    /** {@code DALYTRAN-ID PIC X(16)} end, byte 16. */
    private static final int TRANSACTION_ID_END = 16;

    /** {@code DALYTRAN-TYPE-CD PIC X(02)} start, byte 17. */
    private static final int TYPE_CODE_START = 17;

    /** {@code DALYTRAN-TYPE-CD PIC X(02)} end, byte 18. */
    private static final int TYPE_CODE_END = 18;

    /** {@code DALYTRAN-CAT-CD PIC 9(04)} start, byte 19. */
    private static final int CATEGORY_CODE_START = 19;

    /** {@code DALYTRAN-CAT-CD PIC 9(04)} end, byte 22. */
    private static final int CATEGORY_CODE_END = 22;

    /** {@code DALYTRAN-SOURCE PIC X(10)} start, byte 23. */
    private static final int TRANSACTION_SOURCE_START = 23;

    /** {@code DALYTRAN-SOURCE PIC X(10)} end, byte 32. */
    private static final int TRANSACTION_SOURCE_END = 32;

    /** {@code DALYTRAN-DESC PIC X(100)} start, byte 33. */
    private static final int DESCRIPTION_START = 33;

    /** {@code DALYTRAN-DESC PIC X(100)} end, byte 132. */
    private static final int DESCRIPTION_END = 132;

    /**
     * {@code DALYTRAN-AMT PIC S9(09)V99} start, byte 133.
     * <p>
     * <b>The overpunch decoder is applied here and nowhere else.</b>
     */
    private static final int AMOUNT_START = 133;

    /**
     * {@code DALYTRAN-AMT PIC S9(09)V99} end, byte 143, which is also the overpunch position.
     * <p>
     * Eleven characters with no separate sign byte: nine integer digits, two decimal digits, and the
     * sign overpunched onto the last of them.
     */
    private static final int AMOUNT_END = 143;

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} start, byte 144. */
    private static final int MERCHANT_ID_START = 144;

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} end, byte 152. */
    private static final int MERCHANT_ID_END = 152;

    /**
     * {@code DALYTRAN-MERCHANT-NAME PIC X(50)} start, byte 153.
     * <p>
     * A pure text field, and the standing proof that overpunch decoding must be position aware: across
     * the 300 rows of {@code app/data/ASCII/dailytran.txt} this field contains every one of the
     * eighteen overpunch letter codes {@code A} through {@code R}.
     */
    private static final int MERCHANT_NAME_START = 153;

    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)} end, byte 202. */
    private static final int MERCHANT_NAME_END = 202;

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} start, byte 203. */
    private static final int MERCHANT_CITY_START = 203;

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} end, byte 252. */
    private static final int MERCHANT_CITY_END = 252;

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} start, byte 253. */
    private static final int MERCHANT_ZIP_START = 253;

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} end, byte 262. */
    private static final int MERCHANT_ZIP_END = 262;

    /** {@code DALYTRAN-CARD-NUM PIC X(16)} start, byte 263. Never logged and never in an exception. */
    private static final int CARD_NUMBER_START = 263;

    /** {@code DALYTRAN-CARD-NUM PIC X(16)} end, byte 278. */
    private static final int CARD_NUMBER_END = 278;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)} start, byte 279. Text, never parsed. */
    private static final int ORIG_TS_START = 279;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)} end, byte 304. */
    private static final int ORIG_TS_END = 304;

    /** {@code DALYTRAN-PROC-TS PIC X(26)} start, byte 305. Text, 26 spaces in every fixture row. */
    private static final int PROC_TS_START = 305;

    /** {@code DALYTRAN-PROC-TS PIC X(26)} end, byte 330. */
    private static final int PROC_TS_END = 330;

    /**
     * The scale of {@code DALYTRAN-AMT}, taken from the {@code V99} of {@code PIC S9(09)V99}.
     * <p>
     * The decoded value carries exactly this scale, and the invariant is asserted rather than assumed.
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Charset used for every decode, fixed explicitly and never the platform default.
     * <p>
     * ISO-8859-1 is byte transparent: each of the 256 code points {@code 0x00} to {@code 0xFF} maps to
     * exactly one byte and back, so a 350-byte record becomes exactly 350 characters and every declared
     * offset lands on the byte the copybook names. A variable-width Unicode encoding would decode any
     * byte above {@code 0x7F} as a replacement character or fold two bytes into one and silently shift
     * every offset after it, and the platform default is not reproducible across hosts. The same
     * charset is used by the fixed-width writers of this module, so a record round-trips unchanged.
     * <p>
     * <b>No EBCDIC is decoded.</b> {@code app/data/EBCDIC/**} - twelve {@code .PS} files including
     * {@code DALYTRAN.PS} and {@code DALYTRAN.PS.INIT} - is codepage reference material only and is
     * never parsed by the build; the ASCII fixtures are the authoritative input.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Buffer size for the character stream on the {@code fixed-width} path, in characters.
     * <p>
     * Sized to hold whole records with one character of slack each, which is the look-ahead
     * {@link #rejectRecordSeparator()} needs to inspect the character after a record without a further
     * physical read. It bounds heap use independently of the object size, which is what keeps the read
     * streaming rather than materialising.
     */
    private static final int STREAM_BUFFER_CHARS = 32 * (RECORD_LENGTH + 1);

    // Trailing-sign overpunch table. The sign of a zoned-decimal field is carried by its LAST character,
    // which encodes both the sign and the final digit. The same table appears on the encode side in
    // com.cardemo.batch.writers.RejectWriter and, for a different record layout, in
    // com.cardemo.model.dto.AccountUpdateRequest: one codec per record layout, colocated with the class
    // that owns that layout, is the repository convention (Rule 1 clause C3). This class owns
    // app/cpy/CVTRA06Y.cpy; com.cardemo.batch.readers.TransactionBackupReader owns app/cpy/CVTRA05Y.cpy.

    /** The overpunch code for positive zero, <code>&#123;</code>. */
    private static final char OVERPUNCH_POSITIVE_ZERO = '{';

    /** The overpunch code for negative zero, <code>&#125;</code>. */
    private static final char OVERPUNCH_NEGATIVE_ZERO = '}';

    /** The first positive overpunch letter, {@code A}, which encodes a final digit of one. */
    private static final char OVERPUNCH_POSITIVE_FIRST = 'A';

    /** The last positive overpunch letter, {@code I}, which encodes a final digit of nine. */
    private static final char OVERPUNCH_POSITIVE_LAST = 'I';

    /** The first negative overpunch letter, {@code J}, which encodes a final digit of one. */
    private static final char OVERPUNCH_NEGATIVE_FIRST = 'J';

    /** The last negative overpunch letter, {@code R}, which encodes a final digit of nine. */
    private static final char OVERPUNCH_NEGATIVE_LAST = 'R';

    /** The lowest plain decimal digit, accepted in the terminal position as an unsigned image. */
    private static final char DIGIT_ZERO = '0';

    /** The highest plain decimal digit. */
    private static final char DIGIT_NINE = '9';

    /** The digit the two zero overpunch codes stand for. */
    private static final char DIGIT_ONE = '1';

    // Record separators. Named here in order to be REFUSED, not consumed. app/cbl/CBTRN02C.cbl:L66-L69
    // declares FD DALYTRAN-FILE as a single fixed 350-character group, X(16) plus X(334), over the
    // ORGANIZATION IS SEQUENTIAL file of :L29-L32, so the record boundary IS the record length and the
    // dataset carries no delimiter byte at all. On the fixed-width path a separator is therefore a data
    // defect rather than a row boundary. The line-terminated 351-byte stride of
    // app/data/ASCII/dailytran.txt belongs to the seed migration and to the test fixture loader, which read
    // that file as text by name; it is deliberately not a mode of this reader. See rejectRecordSeparator.

    /** Line feed, refused after a complete record image on the {@code fixed-width} path. */
    private static final char LINE_FEED = '\n';

    /** Carriage return, refused after a complete record image on the {@code fixed-width} path. */
    private static final char CARRIAGE_RETURN = '\r';

    /** The value {@link java.io.Reader#read()} returns at end of stream. */
    private static final int END_OF_STREAM = -1;

    // WORKING-STORAGE counterparts, app/cbl/CBTRN02C.cbl:L131-L148.

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBTRN02C.cbl:L146}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBTRN02C.cbl:L361}). */
    private static final String END_OF_FILE_YES = "Y";

    /**
     * The {@code '0'} that occupies the second byte of {@link #STATUS_PHYSICAL_IO_ERROR}.
     * <p>
     * It is the character a COBOL {@code MOVE} into a numeric display item pads with on the left, which
     * is why it is the right stand-in for an unavailable subcode rather than a space.
     */
    private static final char NUMERIC_SUBCODE_NONE = '0';

    /** {@code '00'}: the status tested at {@code app/cbl/CBTRN02C.cbl:L239}, {@code :L347}, {@code :L585}. */
    private static final String STATUS_SUCCESS = requireExactCode(FileStatus.SUCCESS);

    /** {@code '10'}: end of file, driving {@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBTRN02C.cbl:L352}. */
    private static final String STATUS_END_OF_FILE = requireExactCode(FileStatus.END_OF_FILE);

    /** {@code '35'}: the file is not available, which is what a missing input object corresponds to. */
    private static final String STATUS_FILE_UNAVAILABLE = requireExactCode(FileStatus.FILE_UNAVAILABLE);

    /**
     * The member of the {@code '9x'} family this reader reports when the underlying store or stream
     * rejects an operation.
     * <p>
     * {@link FileStatus#IO_ERROR} is a family rather than a value: its first byte is fixed at
     * {@link FileStatus#IO_ERROR_FIRST_BYTE} and the second byte carries an implementation-defined
     * subcode. Neither a relational store nor an object store reports a VSAM subcode, so the subcode is
     * set to {@code '0'} to mean &quot;no further subcode available from this layer&quot;. The
     * underlying detail is never discarded: it travels as the cause of the thrown exception.
     * <p>
     * The specific z/OS VSAM subcode a given failure would have produced on the mainframe cannot be
     * established from this repository, because mainframe-runtime reproduction is out of scope. Should a
     * byte-exact subcode ever be required, the translation belongs at the {@link FileStatusMapper} layer,
     * where the status vocabulary already lives, and not in this reader.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + NUMERIC_SUBCODE_NONE;

    /** Execution-context key holding the number of records emitted so far. */
    private static final String CONTEXT_KEY_RECORDS_READ = "DailyTransactionReader.recordsRead";

    /**
     * Execution-context key holding the {@code DALYTRAN-ID} of the most recently emitted record.
     * <p>
     * The identifier is checkpointed and nothing else from the record is: no card number, no amount and
     * no merchant detail ever reaches the context (Rule 1 clause D1).
     */
    private static final String CONTEXT_KEY_LAST_TRANSACTION_ID =
            "DailyTransactionReader.lastTransactionId";

    /**
     * Execution-context key holding the {@code ingest_seq} of the most recently emitted staged row.
     * <p>
     * This is the keyset cursor a restarted run seeks past, and it is what makes the resume exact instead of
     * arithmetic on a page size - see {@link #restoreRestartCursor(ExecutionContext)}. It is an ordinal and
     * nothing more: no card number, no amount and no merchant detail ever reaches the context (Rule 1
     * clause D1).
     */
    private static final String CONTEXT_KEY_LAST_INGEST_SEQUENCE =
            "DailyTransactionReader.lastIngestSequence";

    /**
     * Where the 350-byte {@code DALYTRAN} records are read from.
     * <p>
     * Both constants are reachable through
     * {@code carddemo.batch.daily-transaction-reader.source}, so neither branch is dead code (Rule 1
     * clause B1). They exist because the legacy dataset and the Java target are two different things
     * that hold the same records: {@code DALYTRAN} is a keyless physical-sequential dataset
     * ({@code app/cbl/CBTRN02C.cbl:L29-L32}), and the Java target additionally stages it in the
     * {@code daily_transaction} relation so the posting job can join it to the account, card,
     * cross-reference and category-balance tables inside one transaction.
     * <p>
     * A nested enum is used deliberately: this package is capped at seven classes, and a nested type
     * adds no file.
     */
    private enum InputSource {

        /**
         * Read the staged rows from {@code daily_transaction} through
         * {@link DailyTransactionRepository#findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
         * long, org.springframework.data.domain.Pageable)}, in ascending ingestion-ordinal order,
         * seeking past the rows already emitted rather than skipping over them.
         * <p>
         * The default. The rows arrive already decoded, because
         * {@code src/main/resources/db/migration/V3__seed_data.sql} performed the position-aware
         * overpunch decode when it loaded the fixture.
         */
        REPOSITORY,

        /**
         * Read and decode the 350-byte records from the input object in
         * {@code carddemo.aws.s3.batch-input-bucket}.
         * <p>
         * This is the path that exercises the position-aware decoder of
         * {@code decodeSignedAmount(String, long)} and it is the closer analogue of
         * {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} at {@code app/cbl/CBTRN02C.cbl:L346}, since
         * it consumes the same fixed-width image the mainframe dataset held.
         */
        FIXED_WIDTH
    }

    // Collaborators and configuration, injected through the constructor and never reassigned. No field
    // is annotated @Autowired and there is no setter injection.

    /**
     * The staging-table access point. Read-only: the only method reached is the ordered slice finder,
     * and there is no {@code save}, {@code delete}, {@code @Modifying} query or {@code EntityManager}
     * reference anywhere in this file.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * The object-store access point, supplied as the {@code S3Operations} interface by
     * {@code com.cardemo.config.AwsConfig}. No client is constructed here and no credential is handled
     * here; the emulator endpoint override is declared in all four profiles - required with no default in
     * the base, {@code test} and {@code prod} profiles, and defaulted to the LocalStack edge only in
     * {@code application-local.yml} - so no live-cloud path is structurally reachable. Only
     * {@code objectExists} and {@code download} are called, both read-only.
     */
    private final S3Operations objectStorage;

    /**
     * The central {@code FILE STATUS} translator. Consumed rather than re-implemented: it already
     * renders the {@code 9910-DISPLAY-IO-STATUS} line and already applies the {@code APPL-RESULT}
     * arithmetic of both the two-way guard and the three-way sequential-read guard, and its own
     * documentation cites {@code app/cbl/CBTRN02C.cbl:L360-L361} for the latter. Duplicating any of
     * that here would violate Rule 1 clause C3.
     */
    private final FileStatusMapper fileStatusMapper;

    /** The selected input path, resolved and validated once at construction. */
    private final InputSource inputSource;

    /** Rows per database round trip on the {@code repository} path; validated at construction. */
    private final int pageSize;

    /**
     * The input bucket, used only on the {@code fixed-width} path.
     * <p>
     * Empty when unset, which is permitted on the {@code repository} path and refused at construction
     * on the {@code fixed-width} one.
     */
    private final String inputBucket;

    /** The object key read on the {@code fixed-width} path; validated at construction. */
    private final String objectKey;
    /**
     * The application signing key the object-authenticity key is derived from. Empty on the
     * {@code repository} path, where no object is read and nothing needs authenticating; never logged and
     * never placed in an exception message.
     */
    private final String signingKey;

    // Cursor state. Every field below is the Java counterpart of a WORKING-STORAGE item at
    // app/cbl/CBTRN02C.cbl:L131-L148 and is therefore an INSTANCE field: never static, never shared. The
    // step scope gives each step execution its own instance.

    /** {@code END-OF-FILE PIC X(01)} ({@code :L146}). Held as its literal {@code 'N'} or {@code 'Y'} value. */
    private String endOfFile = END_OF_FILE_NO;

    /** {@code APPL-RESULT PIC S9(9) COMP} ({@code :L142}), tested through {@code APPL-AOK} and {@code APPL-EOF}. */
    private int applResult;

    /** {@code IO-STATUS} ({@code :L131-L133}), the two-character status moved in before the renderer runs. */
    private String ioStatus = STATUS_SUCCESS;

    /** {@code DALYTRAN-RECORD}, the record area that {@code COPY CVTRA06Y} declares at {@code :L102}. */
    private DailyTransaction dailyTransactionRecord;

    /** Rows of the slice currently buffered on the {@code repository} path. */
    private List<DailyTransaction> pageBuffer = List.of();

    /** Cursor into {@link #pageBuffer}; the next row to hand out. */
    private int pageBufferIndex;

    /**
     * The highest ingestion ordinal already emitted on the {@code repository} path; the keyset cursor.
     * <p>
     * <b>Finding, severity Medium - remediated by this field.</b> This replaces a page index. A page index
     * makes the provider render {@code OFFSET}, so every refill re-walks and discards the rows already
     * consumed, and a restart positioned by {@code ordinal / pageSize} is exact only while the relation is
     * unchanged between runs. Holding the last ordinal instead makes each refill a seek to the point the read
     * left off, and makes a resumed run land on the row after the last one it actually emitted.
     * <p>
     * Zero on a cold start, which is the value that starts from the beginning: {@code ingest_seq} is
     * one-based, being the record's own position in the flat file, so no staged row can carry the ordinal
     * zero. Advanced as each row is handed out rather than when a slice is buffered, so that a checkpoint
     * taken part-way through a buffer names the last row genuinely emitted and never one merely fetched.
     */
    private long lastIngestSequence;

    /**
     * Whether a further slice may follow, as last reported by {@code Slice.hasNext()}.
     * <p>
     * Seeded {@code true} so the first refill is always attempted; once it is {@code false} the next
     * refill attempt reports end of file instead of issuing a query that is known to be empty.
     */
    private boolean moreSlicesAvailable = true;

    /**
     * The character stream over the input object on the {@code fixed-width} path, held open between
     * {@link #open(ExecutionContext)} and {@link #close()} exactly as the source holds the dataset open
     * between {@code OPEN INPUT DALYTRAN-FILE} at {@code :L238} and {@code CLOSE DALYTRAN-FILE} at
     * {@code :L584}. {@code null} on the {@code repository} path and after the close.
     */
    private BufferedReader recordStream;

    /**
     * Scratch buffer for one record on the {@code fixed-width} path, allocated once per open so the read
     * loop allocates no array per record.
     */
    private char[] recordBuffer;

    /** Rows emitted so far, the counter an end-of-run summary reports. */
    private long recordsRead;

    /**
     * {@code DALYTRAN-ID} of the most recently emitted record, checkpointed by
     * {@link #update(ExecutionContext)}. Never a card number and never an amount.
     */
    private String lastTransactionId;

    /** Whether the open completed successfully, mirroring an open VSAM ACB or an open dataset. */
    private boolean fileOpen;

    /**
     * Creates a reader bound to the staged daily-transaction input.
     *
     * @param dailyTransactionRepository the staging-table access point; must not be {@code null}
     * @param objectStorage the object-store access point supplied by
     *     {@code com.cardemo.config.AwsConfig}; must not be {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translator; must not be {@code null}
     * @param configuredSource the input selector from {@value #PROPERTY_SOURCE}, one of
     *     {@code repository} or {@code fixed-width}, case-insensitive and accepting either a hyphen or
     *     an underscore; defaults to {@code repository}
     * @param pageSize rows per database round trip from {@value #PROPERTY_PAGE_SIZE}, defaulting to
     *     {@value #DEFAULT_PAGE_SIZE}; must be at least one
     * @param inputBucket the input bucket from {@value #PROPERTY_INPUT_BUCKET}, defaulting to empty and
     *     required non-blank only when the {@code fixed-width} path is selected
     * @param objectKey the input object key from {@value #PROPERTY_OBJECT_KEY}, defaulting to
     *     {@value #DEFAULT_OBJECT_KEY}; must not be blank
     * @param signingKey the application signing key from {@value #PROPERTY_SIGNING_KEY}, which has no
     *     default anywhere in this repository; the object-authenticity key is derived from it and it is
     *     required non-blank only when the {@code fixed-width} path is selected
     * @throws NullPointerException if any collaborator is {@code null}
     * @throws IllegalArgumentException if the selector names neither path, if {@code pageSize} is less
     *     than one, if {@code objectKey} is blank, or if the {@code fixed-width} path is selected with a
     *     blank bucket or a blank signing key
     */
    public DailyTransactionReader(
            final DailyTransactionRepository dailyTransactionRepository,
            final S3Operations objectStorage,
            final FileStatusMapper fileStatusMapper,
            @Value("${" + PROPERTY_SOURCE + ":repository}") final String configuredSource,
            @Value("${" + PROPERTY_PAGE_SIZE + ":" + DEFAULT_PAGE_SIZE + "}") final int pageSize,
            @Value("${" + PROPERTY_INPUT_BUCKET + ":}") final String inputBucket,
            @Value("${" + PROPERTY_OBJECT_KEY + ":" + DEFAULT_OBJECT_KEY + "}") final String objectKey,
            @Value("${" + PROPERTY_SIGNING_KEY + ":}") final String signingKey) {
        // Only Objects.requireNonNull and private static validators are called here. Invoking an
        // overridable instance method from the constructor of a non-final class would publish a
        // partially built reference, which -Xlint:all -Werror reports as this-escape; the step scope
        // forbids a final class because it proxies by subclassing.
        this.dailyTransactionRepository = Objects.requireNonNull(
                dailyTransactionRepository, "dailyTransactionRepository must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.inputSource = requireInputSource(configuredSource);
        this.pageSize = requirePositivePageSize(pageSize);
        this.objectKey = requireObjectKey(objectKey);
        this.inputBucket = requireInputBucket(inputBucket, this.inputSource);
        this.signingKey = requireSigningKey(signingKey, this.inputSource);
    }

    // Mainline PROCEDURE DIVISION, app/cbl/CBTRN02C.cbl:L193-L234, realised as the ItemStream lifecycle.
    // Only the DALYTRAN part of it: the five other opens and closes, the validation cascade, the posting
    // routine and the reject write belong to the sibling classes named in the class documentation.

    /**
     * Opens the input, performing {@code 0000-DALYTRAN-OPEN} and reproducing
     * {@code app/cbl/CBTRN02C.cbl:L195}.
     * <p>
     * <b>Side effects.</b> Resets every cursor field, restores the restart cursor when the context
     * carries one, opens either a database cursor position or a character stream over the input object,
     * and writes one or three log events. No row is read and nothing is written anywhere.
     *
     * @param executionContext the step execution context; a restart cursor written by a previous run of
     *     the same step instance is honoured when present, and a {@code null} context is treated as a
     *     cold start so the reader remains usable outside a step
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly; a failure is
     *     reported as {@link FatalProcessingException}, which is also unchecked
     * @throws FatalProcessingException if the input cannot be reached, reproducing
     *     {@code DISPLAY 'ERROR OPENING DALYTRAN'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code app/cbl/CBTRN02C.cbl:L247-L250}
     */
    @Override
    public void open(final ExecutionContext executionContext) {
        // Cold-start every cursor field first, so a reused instance cannot inherit a previous run's
        // position. Ordering matters only in that this happens before the restart cursor is restored.
        endOfFile = END_OF_FILE_NO;
        applResult = FileStatusMapper.APPL_AOK;
        ioStatus = STATUS_SUCCESS;
        dailyTransactionRecord = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        lastIngestSequence = 0L;
        moreSlicesAvailable = true;
        recordStream = null;
        recordBuffer = null;
        recordsRead = 0L;
        lastTransactionId = null;
        fileOpen = false;

        // A null context is an explicit, handled case rather than a guarded assumption (Rule 1 clause
        // B2). So is a context that exists but carries no checkpoint, which is a cold start.
        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            restoreRestartCursor(executionContext);
        }

        // PERFORM 0000-DALYTRAN-OPEN.  (:L195)
        openDailyTransactionFile();

        // Additive, with no counterpart in the source: advance a resumed run to the record after the last
        // one emitted. A cold start returns from it immediately. See positionAfterRestart().
        positionAfterRestart();
    }

    /**
     * Returns the next staged daily transaction, or {@code null} once the input is exhausted,
     * reproducing the mainline loop body at {@code app/cbl/CBTRN02C.cbl:L202-L219}.
     * <p>
     * The method body is the loop <em>body</em>, not the loop: Spring Batch drives the iteration, so
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} becomes the framework calling this method until it answers
     * {@code null}. Both of the source's nested {@code IF END-OF-FILE = 'N'} guards
     * ({@code :L203} and {@code :L205}) are reproduced explicitly and in order; the second cannot fail
     * when the first passed and a record was returned, which is exactly why it is redundant and exactly
     * why it is preserved.
     * <p>
     * <b>No per-record log event is emitted, because the source emits none.</b> Both
     * {@code DISPLAY DALYTRAN-RECORD} statements are commented out - {@code :L207} in this loop body and
     * {@code :L349} inside {@code 1000-DALYTRAN-GET-NEXT} - so unlike
     * {@code app/cbl/CBACT03C.cbl} and {@code app/cbl/CBCUS01C.cbl}, which emit twice per row, and
     * {@code app/cbl/CBACT02C.cbl}, which emits once, this program emits nothing per row. Adding an
     * event would be a parity break, and it would also publish a 16-character card number, which Rule 1
     * clause D1 forbids. See parity structures 1 and 2 in the class documentation.
     * <p>
     * <b>Why there is no {@code @Transactional} annotation.</b> A chunk-oriented step already runs this
     * method inside its own transaction, and Spring silently ignores the {@code readOnly} attribute of a
     * method that merely <em>participates</em> in an existing transaction rather than starting one.
     * Annotating {@code readOnly = true} here would read as an enforced guarantee while enforcing
     * nothing, which Rule 1 clause A1 rules out. Read-only is guaranteed structurally instead: the only
     * repository call this class can reach is
     * {@link DailyTransactionRepository#findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
     * long, org.springframework.data.domain.Pageable)} and the only object-store calls are
     * {@code objectExists} and {@code download}.
     *
     * @return the next staged transaction in file order, or {@code null} at end of data, which is the
     *     Spring Batch end-of-input signal and the analogue of {@code MOVE 'Y' TO END-OF-FILE} at
     *     {@code app/cbl/CBTRN02C.cbl:L361}
     * @throws FatalProcessingException if the input reports a status that is neither {@code '00'} nor
     *     {@code '10'}, reproducing the abend path at {@code app/cbl/CBTRN02C.cbl:L363-L366}
     * @throws DataIntegrityException if a record is not exactly {@value #RECORD_LENGTH} characters or
     *     carries an unrecognised overpunch or non-numeric character where the copybook declares digits
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}, which has no
     *     legacy counterpart because the mainline performs the open unconditionally at {@code :L195}
     */
    @Override
    public DailyTransaction read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); app/cbl/CBTRN02C.cbl:L195 performs "
                            + "0000-DALYTRAN-OPEN before the mainline loop, so the file is always open "
                            + "by the time the loop body runs");
        }

        // PERFORM UNTIL END-OF-FILE = 'Y'  (:L202) - the framework owns the iteration, so the
        // terminating condition becomes an explicit early return that keeps answering null after the
        // input has been exhausted.
        if (END_OF_FILE_YES.equals(endOfFile)) {
            return null;
        }

        // IF END-OF-FILE = 'N'  (:L203) - the first of the two guards, redundant against the loop
        // condition immediately above and retained deliberately.
        if (!END_OF_FILE_NO.equals(endOfFile)) {
            return null;
        }

        // PERFORM 1000-DALYTRAN-GET-NEXT  (:L204)
        final DailyTransaction record = getNextDailyTransaction();

        // IF END-OF-FILE = 'N'  (:L205) - the second guard. The null test is the same condition
        // expressed through the returned value.
        if (!END_OF_FILE_NO.equals(endOfFile) || record == null) {
            return null;
        }

        // ADD 1 TO WS-TRANSACTION-COUNT  (:L206). This is the reader's own tally: it supplies the
        // ingestion ordinal on the fixed-width path and the restart cursor on both. The count the
        // end-of-run summary DISPLAYs at :L227 and the RETURN-CODE decision at :L229-L231 belong to
        // com.cardemo.batch.jobs.DailyTransactionPostingJob, not here.
        recordsRead++;

        // :L207 is "*              DISPLAY DALYTRAN-RECORD" - COMMENTED OUT IN THE SOURCE. It is
        // reproduced as this comment and must never become executable code: see parity structure 2.

        lastTransactionId = record.getTransactionId();
        return record;
    }

    /**
     * Checkpoints the restart cursor so an interrupted step can resume without re-emitting rows.
     * <p>
     * Exactly two values are stored and together they are the whole of the cursor: the number of records
     * emitted so far, which is the <b>position</b>, and the {@code DALYTRAN-ID} of the most recently
     * emitted record, which is the <b>proof</b> that a resumed run landed where it meant to. Nothing
     * else from the record is stored - no card number, no amount, no merchant detail, no entity and no
     * buffer (Rule 1 clause D1).
     * <p>
     * The record count is the position rather than the identifier because {@code DALYTRAN-ID} is
     * <b>not a key</b>: the input is keyless ({@code app/cbl/CBTRN02C.cbl:L29-L32}) and
     * {@link DailyTransactionRepository} states that the identifier may legitimately repeat across
     * staged rows, so seeking to it would be ambiguous. The ordinal is unambiguous on both paths.
     * <p>
     * <b>Side effects.</b> Mutates {@code executionContext} only. Performs no I/O and logs nothing.
     *
     * @param executionContext the step execution context to write into; a {@code null} context is
     *     ignored, which makes the reader usable outside a step for unit testing
     * @throws org.springframework.batch.item.ItemStreamException never thrown; this method cannot fail
     */
    @Override
    public void update(final ExecutionContext executionContext) {
        if (executionContext == null) {
            return;
        }
        executionContext.putLong(CONTEXT_KEY_RECORDS_READ, recordsRead);
        executionContext.putLong(CONTEXT_KEY_LAST_INGEST_SEQUENCE, lastIngestSequence);
        if (lastTransactionId != null) {
            executionContext.putString(CONTEXT_KEY_LAST_TRANSACTION_ID, lastTransactionId);
        }
    }

    /**
     * Closes the input, performing {@code 9000-DALYTRAN-CLOSE} and reproducing
     * {@code app/cbl/CBTRN02C.cbl:L221}.
     * <p>
     * The order is the source's: the close precedes the summary, so a close failure abends before the
     * summary is written and the summary is therefore evidence that the read completed. The row count is
     * reported alongside it as additive observability (Rule 1 clause A4); it replaces nothing, because
     * the legacy counts at {@code :L227-L228} are the job's to report, not the reader's.
     * <p>
     * <b>Side effects.</b> Releases the slice buffer or the character stream, resets the cursor and
     * writes at least one log event.
     *
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly
     * @throws FatalProcessingException if releasing the input fails, reproducing
     *     {@code DISPLAY 'ERROR CLOSING DALYTRAN FILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code app/cbl/CBTRN02C.cbl:L593-L596}
     */
    @Override
    public void close() {
        // PERFORM 9000-DALYTRAN-CLOSE.  (:L221)
        closeDailyTransactionFile();

        LOG.info("{} closed; source={} recordsRead={}",
                LOGICAL_FILE, inputSource, Long.valueOf(recordsRead));
    }

    /**
     * Returns the number of records emitted so far.
     * <p>
     * Exposed so the sibling-owned Micrometer &quot;records processed&quot; counter can observe this
     * step without this class registering an instrument of its own. <b>No meter, timer or gauge is
     * created here</b>: the four named counters are owned by
     * {@code com.cardemo.observability.MetricsConfig}, and adding a fifth instrument from a reader would
     * duplicate that ownership and risk a high-cardinality tag.
     *
     * @return the count of records returned by {@link #read()} since the last
     *     {@link #open(ExecutionContext)}, never negative
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    // 1000-DALYTRAN-GET-NEXT, app/cbl/CBTRN02C.cbl:L345-L369.

    /**
     * Reads the next record and applies the three-way sequential-read guard of
     * {@code 1000-DALYTRAN-GET-NEXT} ({@code app/cbl/CBTRN02C.cbl:L345-L369}).
     * <p>
     * The source shape is preserved exactly: the read sets a status ({@code :L346}); {@code '00'} yields
     * {@code MOVE 0 TO APPL-RESULT} ({@code :L348}); {@code '10'} yields {@code MOVE 16}
     * ({@code :L352}); anything else yields {@code MOVE 12} ({@code :L354}). The guard that follows then
     * either continues ({@code :L357-L358}), sets {@code END-OF-FILE} to {@code 'Y'}
     * ({@code :L360-L361}), or reports and abends ({@code :L363-L366}).
     * <p>
     * <b>End of file is loop termination, not an error.</b> {@code 88 APPL-EOF VALUE 16} is declared at
     * {@code :L144} and is a normal outcome; nothing is thrown for it.
     * <p>
     * The {@code APPL-RESULT} arithmetic is not restated here. {@link FileStatusMapper} already
     * implements this exact nested test and its own documentation cites this very paragraph, so
     * duplicating it would violate Rule 1 clause C3.
     *
     * @return the record just read when the status was {@code '00'}, or {@code null} at end of file
     * @throws FatalProcessingException when the status is neither {@code '00'} nor {@code '10'}, carrying
     *     the underlying failure as its cause when one was raised
     * @throws DataIntegrityException when a fixed-width record is malformed; propagated unwrapped so the
     *     row number and field position it carries are not buried
     */
    private DailyTransaction getNextDailyTransaction() {
        Throwable inputFailure = null;
        try {
            // READ DALYTRAN-FILE INTO DALYTRAN-RECORD.  (:L346)
            ioStatus = readNextRecord();
        } catch (IOException | DataAccessException failure) {
            // The COBOL READ reports through DALYTRAN-STATUS; a stream reports by throwing an
            // IOException and a relational store by throwing a DataAccessException. Either is translated
            // to the '9x' family and then RETAINED as the cause, never swallowed and never allowed to
            // escape untyped (Rule 1 clause B4). A DataIntegrityException raised by the decoder is
            // deliberately NOT caught here: it is already typed and already carries the row and the
            // field position, and burying it under a file status would lose both.
            inputFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT / ELSE IF '10' MOVE 16 / ELSE MOVE 12
        // (:L347-L356).
        applResult = fileStatusMapper.applResultForSequentialRead(ioStatus);

        // IF APPL-AOK CONTINUE  (:L357-L358).
        //
        // :L349 is "*        DISPLAY DALYTRAN-RECORD" - COMMENTED OUT IN THE SOURCE, inside this very
        // '00' branch. It is reproduced as this comment and must never become executable code. Together
        // with the equally commented-out :L207 it is why this program emits no per-record output at all:
        // see parity structures 1 and 2 in the class documentation.
        if (applResult == FileStatusMapper.APPL_AOK) {
            return dailyTransactionRecord;
        }

        // ELSE IF APPL-EOF MOVE 'Y' TO END-OF-FILE  (:L360-L361).
        if (applResult == FileStatusMapper.APPL_EOF) {
            endOfFile = END_OF_FILE_YES;
            dailyTransactionRecord = null;
            return null;
        }

        // ELSE DISPLAY 'ERROR READING DALYTRAN FILE' / MOVE DALYTRAN-STATUS TO IO-STATUS /
        // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L363-L366). IO-STATUS already
        // holds the status, so the MOVE at :L364 is the assignment made above.
        LOG.error(ERROR_READING_MESSAGE);
        LOG.error(displayIoStatus(ioStatus));
        abendProgram(ERROR_READING_MESSAGE, OPERATION_READ, inputFailure);

        // EXIT.  (:L369) - unreachable, because abendProgram always throws. Present so a reader of this
        // method sees the paragraph terminate exactly where the source does, and so the compiler proves
        // the method has no fall-through path that could silently return a stale record.
        return null;
    }

    /**
     * Performs the work behind the {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} verb at
     * {@code app/cbl/CBTRN02C.cbl:L346} and reports its outcome as a COBOL file status.
     * <p>
     * Dispatch is on the configured {@link InputSource} and both branches are reachable, so neither is
     * dead code (Rule 1 clause B1).
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area, or
     *     {@link #STATUS_END_OF_FILE} when the input is exhausted; {@link #STATUS_PHYSICAL_IO_ERROR}
     *     when a buffered element is {@code null}, which a {@code NOT NULL} relation cannot legitimately
     *     produce
     * @throws IOException if the character stream fails on the {@code fixed-width} path
     * @throws DataAccessException if the store rejects the query on the {@code repository} path
     * @throws DataIntegrityException if a fixed-width record is malformed
     */
    private String readNextRecord() throws IOException {
        return switch (inputSource) {
            case REPOSITORY -> readNextRecordFromRepository();
            case FIXED_WIDTH -> readNextRecordFromObject();
        };
    }

    /**
     * Reads the next staged row from the {@code daily_transaction} relation, refilling the slice buffer
     * when it is exhausted.
     * <p>
     * <b>Ordering is the contract and it is fixed in the finder's name.</b>
     * {@link DailyTransactionRepository#findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
     * long, org.springframework.data.domain.Pageable)} orders ascending by the ingestion ordinal, which is
     * the primary key, so the order is both <em>faithful</em> - it is the order the flat file had, since the
     * ordinal <em>is</em> the record's position in the file - and <em>total</em>, so slice boundaries are
     * stable and no row can be skipped or repeated across them. Ordering by {@code DALYTRAN-ID} would
     * forfeit both: that field is not unique and is only coincidentally the file order in the shipped
     * fixture. <b>No bare {@code findAll()} is called anywhere</b>, and no repository method is added
     * from here.
     * <p>
     * <b>Finding, severity Medium - remediated here: the refill seeks rather than skips.</b> This loop
     * requested {@code PageRequest.of(pageIndex, size)}, which the provider renders as {@code OFFSET}, so
     * every refill made the store walk and discard all the rows already consumed - reading <em>n</em> rows in
     * pages of <em>p</em> cost a quadratic number of row visits instead of a linear one, and the cost grew as
     * the read progressed. It now passes {@link #lastIngestSequence}, the highest ordinal already emitted, and
     * always asks for page zero of a size-bounded request: the store enters the primary-key index once, at the
     * point the read left off. The bound on the buffer is unchanged, so the heap profile is unchanged; what
     * changes is that the work per refill no longer depends on how far in the read has got.
     * <p>
     * <b>Why a slice and why the cursor advances.</b> A whole-relation {@code List} would materialise every
     * staged row into the heap at once, which is the opposite of what the source does; the repository
     * deliberately offers no such form. A {@code Slice} rather than a {@code Page} avoids a counting query per
     * chunk that nothing reads - the source keeps its own tally at {@code app/cbl/CBTRN02C.cbl:L206} and never
     * asks the file how many records it holds. The cursor is advanced until a slice reports that no further
     * rows follow, which is the direct analogue of {@code PERFORM UNTIL END-OF-FILE = 'Y'} at
     * {@code :L202-L219}.
     *
     * @return {@link #STATUS_SUCCESS}, {@link #STATUS_END_OF_FILE} or
     *     {@link #STATUS_PHYSICAL_IO_ERROR}
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecordFromRepository() {
        while (pageBufferIndex >= pageBuffer.size()) {
            if (!moreSlicesAvailable) {
                // The previous slice reported no successor, so end of data is known without issuing a
                // query that is certain to come back empty. FILE STATUS '10', not an error.
                dailyTransactionRecord = null;
                return STATUS_END_OF_FILE;
            }

            // Seek, not skip. The cursor is the highest ordinal already emitted, so the store enters the
            // primary-key index once at that point instead of walking and discarding every row before it.
            // A resumed run needs no separate within-slice adjustment at all: its cursor was restored from
            // the checkpoint, so the first refill already begins after the last row genuinely emitted.
            final Slice<DailyTransaction> slice = dailyTransactionRepository
                    .findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                            lastIngestSequence, PageRequest.ofSize(pageSize));
            pageBuffer = slice.getContent();
            moreSlicesAvailable = slice.hasNext();
            pageBufferIndex = 0;

            if (pageBuffer.isEmpty()) {
                // An empty first slice is an empty staging table: a successful run with a row count of
                // zero, stated explicitly rather than inferred from silence (Rule 1 clause B2).
                dailyTransactionRecord = null;
                return STATUS_END_OF_FILE;
            }
        }

        final DailyTransaction next = pageBuffer.get(pageBufferIndex);
        pageBufferIndex++;

        // Explicit null branch (Rule 1 clause B2): every column of this relation is NOT NULL and the
        // ordinal is its primary key, so a null element means the result set is not what the schema
        // promises. It is reported through the status vocabulary rather than allowed to become a
        // NullPointerException further down.
        if (next == null) {
            dailyTransactionRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        // Advance the keyset cursor as the row is handed out, not when its slice was fetched, so that a
        // checkpoint taken part-way through a buffer names the last row genuinely emitted. The ordinal is the
        // primary key and every column of this relation is NOT NULL, so it cannot be absent here.
        lastIngestSequence = next.getIngestSequence();

        dailyTransactionRecord = next;
        return STATUS_SUCCESS;
    }

    /**
     * Reads and decodes the next 350-character record from the input object.
     * <p>
     * The image is obtained by {@link #readFixedWidthImage()} and decoded by
     * {@link #decodeRecord(String, long)}; the two are separate so that a stream failure and a data
     * defect cannot be confused with one another. The ingestion ordinal handed to the decoder is
     * {@code recordsRead + 1}, which makes it the one-based position of the record within the file -
     * the same quantity {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L206}
     * maintains.
     *
     * @return {@link #STATUS_SUCCESS} when a record was decoded, or {@link #STATUS_END_OF_FILE} when the
     *     object is exhausted at a record boundary
     * @throws IOException if the character stream fails
     * @throws DataIntegrityException if the record is not exactly {@value #RECORD_LENGTH} characters or
     *     carries an unrecognised overpunch or a non-digit where the copybook declares digits
     */
    private String readNextRecordFromObject() throws IOException {
        final String image = readFixedWidthImage();
        if (image == null) {
            dailyTransactionRecord = null;
            return STATUS_END_OF_FILE;
        }
        dailyTransactionRecord = decodeRecord(image, recordsRead + 1L);
        return STATUS_SUCCESS;
    }

    // Fixed-width stream primitives. RECFM=FB semantics and nothing else: exactly RECORD_LENGTH characters
    // per record, undelimited, so the object length is a whole multiple of RECORD_LENGTH. A separator byte
    // is refused rather than consumed - see rejectRecordSeparator for why the earlier tolerance was wrong.

    /**
     * Reads exactly {@value #RECORD_LENGTH} characters and refuses any separator that follows them.
     * <p>
     * <b>The charset is explicit and applied once, at the stream.</b> The bytes were decoded through
     * {@link #RECORD_CHARSET} by the {@link InputStreamReader} created in
     * {@link #openDailyTransactionFile()}, so one byte is one character and every offset the copybook
     * declares lands where it should. The image itself is built with the {@code char[]} constructor of
     * {@link String}, which takes no charset at all, so the platform-default {@code new String(byte[])}
     * overload is never reached anywhere in this class.
     * <p>
     * <b>The stream is undelimited and a separator byte is a hard failure.</b>
     * {@code app/cbl/CBTRN02C.cbl:L66-L69} declares the whole {@code DALYTRAN} record as one fixed
     * {@value #RECORD_LENGTH}-character group, so the object is a whole number of
     * {@value #RECORD_LENGTH}-byte records and nothing else. See {@link #rejectRecordSeparator()} for why no
     * optional-terminator tolerance is permitted on this path.
     * <p>
     * <b>The exact-multiple rule is enforced by construction rather than by a separate length probe.</b> A
     * short final read is a geometry failure naming the observed length, and a full read followed by a
     * separator byte is a delimiter failure naming the byte; between them, an object whose length is not a
     * whole multiple of {@value #RECORD_LENGTH} cannot be consumed silently.
     * <p>
     * <b>Bounds are checked before anything is used.</b> Zero characters at a record boundary is a clean
     * end of data. Anything from one to {@value #RECORD_LENGTH} minus one is a truncated record, which is
     * a data defect and is reported as one rather than silently padded, silently skipped or allowed to
     * shift every subsequent offset. The message names the row and the observed length and <b>never the
     * record content</b> (Rule 1 clause D1).
     *
     * @return the {@value #RECORD_LENGTH}-character image, or {@code null} at a clean end of data
     * @throws IOException if the underlying stream fails
     * @throws DataIntegrityException if a partial record is present at the end of the stream, or if a
     *     record separator follows a complete record
     */
    private String readFixedWidthImage() throws IOException {
        int filled = 0;
        while (filled < RECORD_LENGTH) {
            final int read = recordStream.read(recordBuffer, filled, RECORD_LENGTH - filled);
            if (read == END_OF_STREAM) {
                break;
            }
            filled += read;
        }

        if (filled == 0) {
            // End of data exactly on a record boundary: FILE STATUS '10', reported by the caller.
            return null;
        }

        if (filled != RECORD_LENGTH) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d is %d characters but app/cpy/CVTRA06Y.cpy:L2 declares RECLN = %d and "
                            + "app/cbl/CBTRN02C.cbl:L66-L69 declares X(16) + X(334); the configured input "
                            + "object in bucket '%s' is truncated at that record and no field offset after "
                            + "byte %d can be trusted",
                    LOGICAL_FILE, Long.valueOf(recordsRead + 1L), Integer.valueOf(filled),
                    Integer.valueOf(RECORD_LENGTH), inputBucket, Integer.valueOf(filled)),
                    LOGICAL_FILE, inputBucket);
        }

        rejectRecordSeparator();
        return new String(recordBuffer, 0, RECORD_LENGTH);
    }

    /**
     * Refuses a record separator following a complete record image, leaving the stream positioned at the
     * first character of the next record.
     * <p>
     * <b>Tolerating a separator here would be a defect, not a convenience.</b> Consuming a lone
     * {@code \n}, a {@code \r\n} pair or a lone {@code \r} after every record - on the
     * grounds that {@code app/data/ASCII/dailytran.txt} carries one and that a terminator shape should
     * not be assumed - makes corrupt object geometry indistinguishable from valid
     * input: an object written by something other than this application, or truncated mid-transfer, would
     * be consumed as though every record after the first stray byte were correctly aligned, and the run
     * would report success over shifted fields.
     * <p>
     * {@code app/cbl/CBTRN02C.cbl:L29-L32} declares {@code DALYTRAN-FILE} as
     * {@code ORGANIZATION IS SEQUENTIAL} and {@code :L66-L69} gives it one fixed
     * {@value #RECORD_LENGTH}-character record group, {@code FD-TRAN-ID PIC X(16)} plus
     * {@code FD-CUST-DATA PIC X(334)}. The record boundary is therefore the record length, the mainframe
     * dataset carries no delimiter byte, and this application's fixed-width writers emit none - so on this
     * path a separator can only mean the object is not the dataset it claims to be.
     * {@code app/jcl/POSTTRAN.jcl:L30-L31} is deliberately <em>not</em> cited: that DD statement carries no
     * {@code DCB}, no {@code LRECL} and no {@code RECFM}, and this class does not attribute a geometry to it.
     * Line-oriented ingestion of the ASCII fixture belongs to
     * {@code src/main/resources/db/migration/V3__seed_data.sql} and to the test fixture loader, which read
     * the file as text by name and feed the {@code repository} path; it is deliberately not a mode of this
     * reader.
     *
     * @throws IOException if the underlying stream fails, or if it does not support the mark needed to
     *     push a non-separator character back
     * @throws DataIntegrityException if the next character is {@code LF} or {@code CR}
     */
    private void rejectRecordSeparator() throws IOException {
        recordStream.mark(1);
        final int next = recordStream.read();
        if (next == END_OF_STREAM) {
            return;
        }
        if (next == LINE_FEED || next == CARRIAGE_RETURN) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s object '%s' in bucket '%s' carries a 0x%02X record separator after row %d; "
                            + "app/cbl/CBTRN02C.cbl:L66-L69 declares one fixed %d-character record group "
                            + "with no delimiter, so a separator means the object was not written by this "
                            + "application or was corrupted in transfer",
                    LOGICAL_FILE, objectKey, inputBucket, Integer.valueOf(next),
                    Long.valueOf(recordsRead + 1L), Integer.valueOf(RECORD_LENGTH)),
                    LOGICAL_FILE, inputBucket);
        }
        // No separator: the character just read is the first of the next record and is pushed back.
        recordStream.reset();
    }

    /**
     * Discards the given number of whole records so a resumed run continues where the previous one
     * stopped, and verifies that it landed on the expected record.
     * <p>
     * <b>This is exact on this path, because an object is immutable once written.</b> Skipping
     * {@code alreadyEmitted} records positions the stream on the record after the last one emitted, and
     * the {@code DALYTRAN-ID} of the last skipped record is compared against the checkpointed
     * identifier. A mismatch means the object was replaced between the two runs, so the resume position
     * is wrong and rows would be silently skipped or silently repeated; that is reported loudly rather
     * than downgraded (Rule 1 clause B4). Only bytes 1-16 of the skipped record are looked at, so no
     * amount and no card number is touched.
     * <p>
     * <b>This has no legacy counterpart.</b> The source has no restart concept at all: a rerun of
     * {@code app/jcl/POSTTRAN.jcl} reprocesses the whole dataset. The capability is additive, and its
     * failure modes are therefore additive too.
     *
     * @param alreadyEmitted the number of records the previous run emitted; must be positive
     * @param expectedLastTransactionId the checkpointed {@code DALYTRAN-ID} of the last emitted record,
     *     or {@code null} when the context carried none, in which case no comparison is made
     * @throws IOException if the underlying stream fails
     * @throws FileAccessException if the object holds fewer records than the checkpoint claims were
     *     already emitted
     * @throws DataIntegrityException if a skipped record is truncated, or if the last skipped record does
     *     not carry the checkpointed identifier
     */
    private void skipAlreadyEmittedRecords(final long alreadyEmitted,
            final String expectedLastTransactionId) throws IOException {
        String lastSkippedImage = null;
        for (long skipped = 0L; skipped < alreadyEmitted; skipped++) {
            lastSkippedImage = readFixedWidthImage();
            if (lastSkippedImage == null) {
                throw new FileAccessException(String.format(Locale.ROOT,
                        "%s restart cannot resume: the configured input object in bucket '%s' holds only "
                                + "%d records but the execution context reports %d already emitted, so the "
                                + "object is not the one the previous run read",
                        LOGICAL_FILE, inputBucket, Long.valueOf(skipped),
                        Long.valueOf(alreadyEmitted)),
                        STATUS_FILE_UNAVAILABLE, LOGICAL_FILE, OPERATION_OPEN);
            }
        }

        if (expectedLastTransactionId == null || lastSkippedImage == null) {
            return;
        }

        final String observed = fixedWidthField(lastSkippedImage, TRANSACTION_ID_START,
                TRANSACTION_ID_END, "DALYTRAN-ID", alreadyEmitted);
        if (!observed.equals(expectedLastTransactionId)) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s restart cannot resume: record %d of the configured input object in bucket '%s' "
                            + "does not carry the DALYTRAN-ID the execution context checkpointed, so the "
                            + "object changed between runs and resuming would skip or repeat records",
                    LOGICAL_FILE, Long.valueOf(alreadyEmitted), inputBucket),
                    CONTEXT_KEY_LAST_TRANSACTION_ID, LOGICAL_FILE);
        }
    }

    // Position-aware decode of app/cpy/CVTRA06Y.cpy. Every extraction goes through fixedWidthField, so
    // every one of them is bounds checked against the actual image length before any substring is taken.

    /**
     * Decodes one {@value #RECORD_LENGTH}-character {@code DALYTRAN-RECORD} image into a staging row.
     * <p>
     * The fourteen constructor arguments are supplied in the COBOL field order of
     * {@code app/cpy/CVTRA06Y.cpy:L5-L17}, with the ingestion ordinal first because it is the identity
     * and the reader knows it before it parses anything. The 20-byte {@code FILLER} at {@code :L18}
     * carries no data and is deliberately not passed.
     * <p>
     * <b>Nothing is trimmed, padded, upper-cased, re-signed, rounded or reformatted.</b> Every
     * right-padded {@code PIC X(n)} field keeps its trailing spaces exactly, which is load-bearing in two
     * places the fixture proves: {@code DALYTRAN-PROC-TS} is 26 spaces on all 300 rows and must survive
     * as 26 spaces - not {@code null}, not empty, not trimmed, not an epoch - and
     * {@code DALYTRAN-SOURCE} is {@code POS TERM} or {@code OPERATOR} right-padded to ten characters and
     * must survive at that width. {@code DailyTransaction} re-checks every width against the picture
     * clause, so a defect in this method surfaces as a named property rather than as a constraint
     * violation at flush time.
     * <p>
     * <b>{@code DALYTRAN-SOURCE} is plain text, not a closed domain.</b>
     * {@code com.cardemo.model.enums.TransactionSource} is neither imported nor referenced by this class:
     * it holds exactly two constants, {@code 'System'} from {@code app/cbl/CBACT04C.cbl:L484} and
     * {@code 'POS TERM'} from {@code app/cbl/COBIL00C.cbl:L222}, and 50 of the 300 fixture rows carry
     * {@code OPERATOR}, which is neither. Validating the field against that enum, or adding a third
     * constant to it, would break those 50 rows.
     * <p>
     * This method is a pure function of its arguments: it reads and writes no field of this instance.
     *
     * @param image the {@value #RECORD_LENGTH}-character record image; must not be {@code null}
     * @param rowNumber the one-based position of this record within the file, used as the ingestion
     *     ordinal and as exception context
     * @return the decoded staging row, never {@code null}
     * @throws DataIntegrityException if the image is not {@value #RECORD_LENGTH} characters, if a field
     *     the copybook declares as digits holds a non-digit, or if the amount carries an unrecognised
     *     terminal overpunch character
     * @throws IllegalArgumentException if a decoded value is outside the range its picture clause admits,
     *     as {@code DailyTransaction} determines
     */
    private static DailyTransaction decodeRecord(final String image, final long rowNumber) {
        return new DailyTransaction(
                Long.valueOf(rowNumber),
                fixedWidthField(image, TRANSACTION_ID_START, TRANSACTION_ID_END, "DALYTRAN-ID", rowNumber),
                fixedWidthField(image, TYPE_CODE_START, TYPE_CODE_END, "DALYTRAN-TYPE-CD", rowNumber),
                unsignedInteger(
                        fixedWidthField(image, CATEGORY_CODE_START, CATEGORY_CODE_END,
                                "DALYTRAN-CAT-CD", rowNumber),
                        "DALYTRAN-CAT-CD PIC 9(04)", CATEGORY_CODE_START, rowNumber),
                fixedWidthField(image, TRANSACTION_SOURCE_START, TRANSACTION_SOURCE_END,
                        "DALYTRAN-SOURCE", rowNumber),
                fixedWidthField(image, DESCRIPTION_START, DESCRIPTION_END, "DALYTRAN-DESC", rowNumber),
                decodeSignedAmount(
                        fixedWidthField(image, AMOUNT_START, AMOUNT_END, "DALYTRAN-AMT", rowNumber),
                        rowNumber),
                unsignedLong(
                        fixedWidthField(image, MERCHANT_ID_START, MERCHANT_ID_END,
                                "DALYTRAN-MERCHANT-ID", rowNumber),
                        "DALYTRAN-MERCHANT-ID PIC 9(09)", MERCHANT_ID_START, rowNumber),
                fixedWidthField(image, MERCHANT_NAME_START, MERCHANT_NAME_END,
                        "DALYTRAN-MERCHANT-NAME", rowNumber),
                fixedWidthField(image, MERCHANT_CITY_START, MERCHANT_CITY_END,
                        "DALYTRAN-MERCHANT-CITY", rowNumber),
                fixedWidthField(image, MERCHANT_ZIP_START, MERCHANT_ZIP_END,
                        "DALYTRAN-MERCHANT-ZIP", rowNumber),
                fixedWidthField(image, CARD_NUMBER_START, CARD_NUMBER_END, "DALYTRAN-CARD-NUM", rowNumber),
                fixedWidthField(image, ORIG_TS_START, ORIG_TS_END, "DALYTRAN-ORIG-TS", rowNumber),
                fixedWidthField(image, PROC_TS_START, PROC_TS_END, "DALYTRAN-PROC-TS", rowNumber));
    }

    /**
     * Extracts one field by its one-based inclusive copybook offsets, after bounds-checking them against
     * the actual image length.
     * <p>
     * <b>This is the only place a substring of a record image is taken</b>, and it is the only place the
     * copybook's one-based inclusive offsets are converted to Java's zero-based half-open form. Both
     * properties are deliberate: a single conversion site cannot disagree with itself, and a single
     * bounds check cannot be forgotten at one of thirteen call sites. The check is against the observed
     * length rather than against {@value #RECORD_LENGTH}, so it holds even if a caller ever passes a
     * shorter image.
     * <p>
     * Trailing spaces are preserved exactly: there is no {@code trim}, no {@code strip} and no
     * normalisation to {@code null} or to the empty string anywhere in this class.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param image the record image; must not be {@code null}
     * @param startOneBased the first byte of the field, one-based and inclusive
     * @param endOneBased the last byte of the field, one-based and inclusive
     * @param cobolField the copybook field name, used only as exception context
     * @param rowNumber the one-based position of the record within the file, used only as exception
     *     context
     * @return the field text, exactly {@code endOneBased - startOneBased + 1} characters long
     * @throws DataIntegrityException if the image is too short to contain the declared field, which the
     *     message reports together with the row, the field and the offsets - never the content
     */
    private static String fixedWidthField(final String image, final int startOneBased,
            final int endOneBased, final String cobolField, final long rowNumber) {
        final int length = image.length();
        if (endOneBased > length) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d is %d characters, so field %s at bytes %d-%d declared by "
                            + "app/cpy/CVTRA06Y.cpy cannot be read; a %s record is %d characters",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(length), cobolField,
                    Integer.valueOf(startOneBased), Integer.valueOf(endOneBased), LOGICAL_FILE,
                    Integer.valueOf(RECORD_LENGTH)),
                    cobolField, LOGICAL_FILE);
        }
        return image.substring(startOneBased - 1, endOneBased);
    }

    /**
     * Decodes the trailing-sign zoned-decimal {@code DALYTRAN-AMT} field into a {@link BigDecimal}.
     * <p>
     * <b>This is the only signed field in {@code app/cpy/CVTRA06Y.cpy}</b>: {@code PIC S9(09)V99} at
     * bytes 133-143 ({@code :L10}), eleven characters with no separate sign byte, the sign overpunched
     * onto byte 143. This method is called from exactly one place, on exactly those eleven characters.
     * <p>
     * <b>The decode table.</b> <code>&#123;</code> is {@code +0}; {@code A} through {@code I} are
     * {@code +1} to {@code +9}; <code>&#125;</code> is {@code -0}; {@code J} through {@code R} are
     * {@code -1} to {@code -9}. A plain digit in the terminal position is accepted and read as positive,
     * which is what an unsigned {@code MOVE} leaves there.
     * <p>
     * <b>DECODING IS POSITION AWARE, DRIVEN FROM THE PICTURE CLAUSES, AND A GLOBAL TEXT REPLACEMENT WOULD
     * BE WRONG.</b> The proof is measured on the Gate 1 fixture itself. Across the 300 rows of
     * {@code app/data/ASCII/dailytran.txt}, {@code DALYTRAN-DESC} at bytes 33-132 - a pure
     * {@code PIC X(100)} text field - contains at least one overpunch-lookalike letter in <b>every one of
     * the 300 rows</b>, and the set of such letters occurring there is the complete
     * {@code ABCDEFGHIJKLMNOPQR}, all eighteen codes. {@code DALYTRAN-MERCHANT-NAME} at bytes 153-202
     * carries the same complete set across 285 of the 300 rows, and
     * {@code DALYTRAN-MERCHANT-CITY} at 203-252 carries seventeen of the eighteen. Counting the whole
     * record outside bytes 133-143, <b>300 of 300 rows would be corrupted</b> by a pass that replaced
     * these characters wherever it found them. Sibling layouts corroborate the same hazard:
     * {@code app/data/ASCII/acctdata.txt:L1} holds {@code 00000001940}<code>{</code> at bytes 13-24 for
     * {@code +194.00} while bytes 103-112 of that same file hold the literal {@code A000000000} in all 50
     * rows, because {@code ACCT-ADDR-ZIP} is {@code PIC X(10)} text whose leading {@code A} a global pass
     * would turn into a digit.
     * <p>
     * <b>No normalisation of any kind is applied.</b> There is no absolute value, no sign stripping and
     * no unconditional negation: the sign is applied if and only if the terminal character encodes one.
     * The fixture's 6 <code>&#125;</code> rows and 44 {@code J}-through-{@code R} rows are 50 genuinely
     * negative amounts, and they are what exercise the cycle-debit branch downstream -
     * {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a negative amount to {@code ACCT-CURR-CYC-DEBIT}, which
     * is exactly why the over-limit formula at {@code :L403-L405} subtracts that accumulator. Normalising
     * them would silently disable a whole branch of the parity comparison.
     * <p>
     * <b>The scale is exactly {@value #AMOUNT_SCALE} by construction, and the invariant is asserted.</b>
     * {@link BigDecimal#movePointLeft(int)} applied to a scale-zero integer yields precisely that scale,
     * and {@link BigDecimal#negate()} preserves it. The assertion compares
     * {@link BigDecimal#scale()}, an {@code int}; <b>no {@code equals} is ever invoked on a
     * {@code BigDecimal}</b> in this class, and there is no {@code float} or {@code double} anywhere in
     * this file.
     * <p>
     * <b>No rounding occurs, so no {@link java.math.RoundingMode} is referenced.</b> The field carries
     * nine integer digits and two decimal digits and the decode is an exact decimal shift; there is
     * nothing to round. The domain bound is structural for the same reason: eleven digits at scale two
     * cannot exceed the {@code 999999999.99} that {@code PIC S9(09)V99} admits, so a range comparison
     * here would be unreachable code, and {@code DailyTransaction} re-checks the bound in any case.
     * <p>
     * <b>Negative zero cannot be represented.</b> <code>&#125;</code> denotes {@code -0}, and
     * {@code BigDecimal} has no signed zero, so a field of {@code 00000000000}<code>&#125;</code> decodes
     * to {@code 0.00}. This cannot arise from the reference fixture - all six <code>&#125;</code> rows
     * there carry non-zero magnitudes, decoding to {@code -919.00}, {@code -243.00}, {@code -763.00},
     * {@code -907.00}, {@code -372.00} and {@code -435.00} - and it is inherent to decimal arithmetic and
     * to the {@code NUMERIC(11,2)} column rather than a defect here.
     * <p>
     * This method is a pure function of its arguments. The row number is an argument precisely so that it
     * can be named in a failure without the method reading any instance state.
     *
     * @param rawField the eleven characters at bytes 133-143; must not be {@code null}
     * @param rowNumber the one-based position of the record within the file, used only as exception
     *     context
     * @return the amount at scale exactly {@value #AMOUNT_SCALE}, never {@code null}
     * @throws DataIntegrityException if the field is not eleven characters, if any of its first ten
     *     characters is not a digit, or if its terminal character is neither a digit nor a member of the
     *     overpunch table; the message names the row and the byte position and never the record content
     * @throws IllegalStateException if the decoded value does not carry scale
     *     {@value #AMOUNT_SCALE}, which the arithmetic makes impossible and which is asserted rather than
     *     assumed
     */
    private static BigDecimal decodeSignedAmount(final String rawField, final long rowNumber) {
        final int width = AMOUNT_END - AMOUNT_START + 1;
        if (rawField.length() != width) {
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d field DALYTRAN-AMT is %d characters but app/cpy/CVTRA06Y.cpy:L10 "
                            + "declares PIC S9(09)V99 at bytes %d-%d, which is %d characters",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(rawField.length()),
                    Integer.valueOf(AMOUNT_START), Integer.valueOf(AMOUNT_END), Integer.valueOf(width)),
                    "DALYTRAN-AMT PIC S9(09)V99", LOGICAL_FILE);
        }

        // The terminal character carries BOTH the sign and the final digit. It is read from byte
        // AMOUNT_END and from nowhere else: no scan, no search and no replacement over the record.
        final char overpunch = rawField.charAt(width - 1);
        final boolean negative = overpunch == OVERPUNCH_NEGATIVE_ZERO
                || (overpunch >= OVERPUNCH_NEGATIVE_FIRST && overpunch <= OVERPUNCH_NEGATIVE_LAST);

        final char finalDigit;
        if (overpunch >= DIGIT_ZERO && overpunch <= DIGIT_NINE) {
            finalDigit = overpunch;
        } else if (overpunch == OVERPUNCH_POSITIVE_ZERO || overpunch == OVERPUNCH_NEGATIVE_ZERO) {
            finalDigit = DIGIT_ZERO;
        } else if (overpunch >= OVERPUNCH_POSITIVE_FIRST && overpunch <= OVERPUNCH_POSITIVE_LAST) {
            finalDigit = (char) (DIGIT_ONE + (overpunch - OVERPUNCH_POSITIVE_FIRST));
        } else if (overpunch >= OVERPUNCH_NEGATIVE_FIRST && overpunch <= OVERPUNCH_NEGATIVE_LAST) {
            finalDigit = (char) (DIGIT_ONE + (overpunch - OVERPUNCH_NEGATIVE_FIRST));
        } else {
            // Untrusted input, checked rather than trusted (Rule 1 clause A2). The offending character
            // is reported by POSITION, not by value, so nothing of the record reaches the message.
            throw new DataIntegrityException(String.format(Locale.ROOT,
                    "%s record %d carries an unrecognised trailing-sign overpunch character at byte %d; "
                            + "app/cpy/CVTRA06Y.cpy:L10 declares PIC S9(09)V99 there, whose terminal "
                            + "position admits a digit, an opening or closing brace, or one of the "
                            + "letters %c-%c or %c-%c",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(AMOUNT_END),
                    Character.valueOf(OVERPUNCH_POSITIVE_FIRST), Character.valueOf(OVERPUNCH_POSITIVE_LAST),
                    Character.valueOf(OVERPUNCH_NEGATIVE_FIRST), Character.valueOf(OVERPUNCH_NEGATIVE_LAST)),
                    "DALYTRAN-AMT PIC S9(09)V99", LOGICAL_FILE);
        }

        final StringBuilder digits = new StringBuilder(width);
        for (int index = 0; index < width - 1; index++) {
            final char current = rawField.charAt(index);
            if (current < DIGIT_ZERO || current > DIGIT_NINE) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s record %d carries a non-digit at byte %d, inside DALYTRAN-AMT at bytes %d-%d, "
                                + "which app/cpy/CVTRA06Y.cpy:L10 declares as PIC S9(09)V99",
                        LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(AMOUNT_START + index),
                        Integer.valueOf(AMOUNT_START), Integer.valueOf(AMOUNT_END)),
                        "DALYTRAN-AMT PIC S9(09)V99", LOGICAL_FILE);
            }
            digits.append(current);
        }
        digits.append(finalDigit);

        final BigDecimal magnitude = new BigDecimal(digits.toString()).movePointLeft(AMOUNT_SCALE);
        final BigDecimal decoded = negative ? magnitude.negate() : magnitude;

        // Asserted, not assumed: the column is NUMERIC(11,2) and the parity comparison is made on two
        // decimal places, so a scale other than two would be a defect worth failing on.
        if (decoded.scale() != AMOUNT_SCALE) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s record %d decoded DALYTRAN-AMT at scale %d; PIC S9(09)V99 and column "
                            + "dalytran_amt NUMERIC(11,2) both require scale %d",
                    LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(decoded.scale()),
                    Integer.valueOf(AMOUNT_SCALE)));
        }
        return decoded;
    }

    /**
     * Decodes an unsigned {@code PIC 9(n)} display field into an {@link Integer}.
     * <p>
     * <b>No overpunch decoding is applied here, because the picture clause is unsigned.</b> That is the
     * position-aware rule in its plainest form: {@code DALYTRAN-CAT-CD} is {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA06Y.cpy:L7}, so every one of its four characters must be a digit, and a letter
     * there is a defect rather than a sign. The fixture bears this out - all 300 rows carry
     * {@code 0001} - and treating a letter as a sign would invent a value the field cannot hold.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param rawField the field text; must not be {@code null}
     * @param pictureClause the field name and picture clause, used only as exception context
     * @param startOneBased the one-based byte offset of the field, used to report an offending position
     * @param rowNumber the one-based position of the record within the file, used only as exception
     *     context
     * @return the decoded value, never {@code null}
     * @throws DataIntegrityException if any character is not a digit; the message names the row and the
     *     byte position and never the record content
     */
    private static Integer unsignedInteger(final String rawField, final String pictureClause,
            final int startOneBased, final long rowNumber) {
        return Integer.valueOf(
                Math.toIntExact(unsignedDigits(rawField, pictureClause, startOneBased, rowNumber)));
    }

    /**
     * Decodes an unsigned {@code PIC 9(n)} display field into a {@link Long}.
     * <p>
     * Used for {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)} at {@code app/cpy/CVTRA06Y.cpy:L11}, whose
     * nine digits exceed no {@code long} bound. As with {@link #unsignedInteger(String, String, int,
     * long)}, no overpunch decoding is applied, because the picture clause is unsigned.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param rawField the field text; must not be {@code null}
     * @param pictureClause the field name and picture clause, used only as exception context
     * @param startOneBased the one-based byte offset of the field, used to report an offending position
     * @param rowNumber the one-based position of the record within the file, used only as exception
     *     context
     * @return the decoded value, never {@code null}
     * @throws DataIntegrityException if any character is not a digit
     */
    private static Long unsignedLong(final String rawField, final String pictureClause,
            final int startOneBased, final long rowNumber) {
        return Long.valueOf(unsignedDigits(rawField, pictureClause, startOneBased, rowNumber));
    }

    /**
     * Validates that every character of an unsigned display field is a digit and returns its value.
     * <p>
     * The accumulation is done digit by digit rather than through {@link Long#parseLong(String)} so that
     * an offending character can be reported by its <b>byte position within the record</b>, which is the
     * context an operator needs and which a parse failure does not supply. A leading-zero image such as
     * {@code 0001} is the normal case for a COBOL display field and needs no special handling.
     * <p>
     * This method is a pure function of its arguments.
     *
     * @param rawField the field text; must not be {@code null}
     * @param pictureClause the field name and picture clause, used only as exception context
     * @param startOneBased the one-based byte offset of the field within the record
     * @param rowNumber the one-based position of the record within the file
     * @return the non-negative decoded value
     * @throws DataIntegrityException if any character is not a digit
     */
    private static long unsignedDigits(final String rawField, final String pictureClause,
            final int startOneBased, final long rowNumber) {
        long value = 0L;
        for (int index = 0; index < rawField.length(); index++) {
            final char current = rawField.charAt(index);
            if (current < DIGIT_ZERO || current > DIGIT_NINE) {
                throw new DataIntegrityException(String.format(Locale.ROOT,
                        "%s record %d carries a non-digit at byte %d, inside %s; an unsigned COBOL "
                                + "display field admits digits only and carries no overpunch sign",
                        LOGICAL_FILE, Long.valueOf(rowNumber), Integer.valueOf(startOneBased + index),
                        pictureClause),
                        pictureClause, LOGICAL_FILE);
            }
            value = (value * 10L) + (current - DIGIT_ZERO);
        }
        return value;
    }

    // 0000-DALYTRAN-OPEN, app/cbl/CBTRN02C.cbl:L236-L252.

    /**
     * Opens the input, reproducing {@code 0000-DALYTRAN-OPEN}
     * ({@code app/cbl/CBTRN02C.cbl:L236-L252}).
     * <p>
     * The source shape is preserved: {@code MOVE 8 TO APPL-RESULT} ({@code :L237}) seeds the result with
     * a failure value that only a successful open clears; {@code OPEN INPUT DALYTRAN-FILE}
     * ({@code :L238}) is attempted; {@code '00'} yields {@code MOVE 0} and anything else
     * {@code MOVE 12} ({@code :L239-L243}); then the guard either continues ({@code :L244-L245}) or
     * reports and abends ({@code :L247-L250}).
     * <p>
     * <b>Note the literal.</b> {@code 'ERROR OPENING DALYTRAN'} at {@code :L247} omits the word
     * {@code FILE} that the read and close literals carry. Reproduced verbatim; see parity structure 5.
     * <p>
     * <b>What stands in for {@code OPEN INPUT}.</b> On the {@code repository} path, one bounded seek for a
     * single row past the reader's cursor, which is the cheapest statement that proves the relation is
     * reachable and which makes "nothing to read" an explicit logged outcome rather than something inferred
     * from an absence of rows. It is deliberately <em>not</em> a {@code count()}: an aggregate makes the store
     * visit every staged row to answer a question nothing reads, and the source does not ask it either -
     * {@code app/cbl/CBTRN02C.cbl} keeps its own tally at {@code :L206} and never interrogates the file for a
     * total, which is reported instead at close from the counter the reader maintains. On the
     * {@code fixed-width} path, an existence probe followed by opening a character stream over the object,
     * with the charset applied once at that stream.
     * <p>
     * <b>Side effects.</b> One round trip or one object-store request; sets the open flag; allocates the
     * per-record buffer on the {@code fixed-width} path; writes one log event on success and two before
     * abending on failure.
     *
     * @throws FatalProcessingException if the input cannot be reached, reproducing
     *     {@code DISPLAY 'ERROR OPENING DALYTRAN'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L247-L250}
     */
    private void openDailyTransactionFile() {
        // MOVE 8 TO APPL-RESULT.  (:L237). Eight is neither APPL-AOK nor APPL-EOF, so an open that never
        // completes cannot be mistaken for one that succeeded.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        Throwable openFailure = null;
        try {
            // OPEN INPUT DALYTRAN-FILE  (:L238)
            ioStatus = openInputSource();
        } catch (final CardDemoException alreadyTyped) {
            // Finding M-11, severity High. An authenticity refusal is NOT an input-output error and must not
            // be re-reported as one: it is already the typed, fully described abend this class would raise,
            // and re-wrapping it as status '9x' would replace "this object is not ours" with "the device
            // failed" - the one substitution that would make an attack look like a hardware fault. Placed
            // above the catch below because CardDemoException is a RuntimeException, so the order is what
            // makes the distinction reachable.
            throw alreadyTyped;
        } catch (IOException | RuntimeException failure) {
            // The COBOL OPEN reports through DALYTRAN-STATUS. A relational store reports by throwing a
            // DataAccessException and an object store by throwing an unchecked SDK exception or an
            // IOException, so both shapes are translated into the status vocabulary here. Nothing is
            // swallowed: the throwable is retained and travels as the cause of the abend (Rule 1 clause
            // B4). Nothing this class itself throws can reach this catch, because openInputSource
            // performs no decoding.
            openFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT  (:L239-L243).
        // Delegated rather than restated: this two-way test is what applResultForGuard implements.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        if (applResult == FileStatusMapper.APPL_AOK) {
            // IF APPL-AOK CONTINUE  (:L244-L245)
            fileOpen = true;
            // The configured object key is deliberately NOT named here. See the class documentation's
            // log-hygiene section: the DALYTRAN input key is date partitioned, so it carries a business
            // date, and the key is resolvable from configuration by anyone entitled to it without the log
            // repeating it. The logical dataset and the selected source are what an operator needs.
            LOG.info("{} opened; source={}", LOGICAL_FILE, inputSource);
        } else {
            // ELSE DISPLAY 'ERROR OPENING DALYTRAN' / MOVE DALYTRAN-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L247-L250)
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_OPENING_MESSAGE, OPERATION_OPEN, openFailure);
        }
        // EXIT.  (:L252)
    }

    /**
     * Performs the work behind the {@code OPEN INPUT DALYTRAN-FILE} verb at
     * {@code app/cbl/CBTRN02C.cbl:L238} and reports its outcome as a COBOL file status.
     * <p>
     * On the {@code fixed-width} path a missing object is reported as {@code '35'} rather than as a
     * {@code '9x'} error, because {@code '35'} is precisely &quot;the file is not available&quot; in the
     * status vocabulary and is the counterpart of the CICS {@code NOTOPEN} condition. Both statuses lead
     * to the same abend through the guard above; distinguishing them is what makes the rendered
     * {@code FILE STATUS} line diagnostic rather than merely alarming.
     *
     * @return {@link #STATUS_SUCCESS}, or {@link #STATUS_FILE_UNAVAILABLE} when the configured object
     *     does not exist
     * @throws IOException if the object's stream cannot be opened
     * @throws DataAccessException if the relation cannot be reached; translated by the caller
     */
    private String openInputSource() throws IOException {
        if (inputSource == InputSource.REPOSITORY) {
            // Finding, severity Medium, remediated here. This was a count(), which is a whole-relation
            // aggregate: the store visits every staged row to answer it, and nothing needs the answer. The
            // source does not ask either - app/cbl/CBTRN02C.cbl keeps its own tally at :L206 and never
            // interrogates the file for a total. What OPEN INPUT has to establish is reachability, and the
            // cheapest statement that establishes it is a bounded fetch of one row from the same index the
            // read path uses. The emitted total is reported at close, from the counter the reader already
            // maintains, so the figure an operator sees is now the number of records actually processed
            // rather than a count taken before any of them were.
            // The probe seeks from the reader's own cursor, which open() has already restored, so a resumed
            // run asks "is anything left" rather than re-entering the index at the head of a relation whose
            // first rows it has no further interest in. On a cold start the cursor is zero and this is a seek
            // to the very first row.
            final boolean anyRowRemains = !dailyTransactionRepository
                    .findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                            lastIngestSequence, PageRequest.ofSize(1))
                    .getContent()
                    .isEmpty();
            if (anyRowRemains) {
                LOG.info("{} staging relation is reachable, with at least one row still to read past "
                        + "ordinal {}", LOGICAL_FILE, Long.valueOf(lastIngestSequence));
            } else {
                // Nothing left to read is a successful, complete run, not a fault. Stated explicitly so an
                // operator is never left to infer it from silence (Rule 1 clause B2).
                LOG.info("{} staging relation is reachable with nothing left to read past ordinal {}; the "
                        + "read will complete without emitting a record", LOGICAL_FILE,
                        Long.valueOf(lastIngestSequence));
            }
            return STATUS_SUCCESS;
        }

        if (!objectStorage.objectExists(inputBucket, objectKey)) {
            return STATUS_FILE_UNAVAILABLE;
        }

        final S3Resource resource = objectStorage.download(inputBucket, objectKey);
        // Finding M-11, severity High: BEFORE any byte is decoded. See verifyInputObjectAuthenticity for
        // why both the manifest code and the content digest are checked, and why neither alone is enough.
        verifyInputObjectAuthenticity(resource);
        final InputStream bytes = resource.getInputStream();
        // The charset is applied HERE and only here, explicitly. Every subsequent operation is on
        // characters, so no platform-default byte-to-character conversion is reachable.
        recordStream = new BufferedReader(new InputStreamReader(bytes, RECORD_CHARSET),
                STREAM_BUFFER_CHARS);
        recordBuffer = new char[RECORD_LENGTH];
        return STATUS_SUCCESS;
    }

    /**
     * Positions a resumed run on the record after the last one the previous run emitted.
     * <p>
     * <b>Additive: the source has no restart concept at all.</b> A rerun of
     * {@code app/jcl/POSTTRAN.jcl} reprocesses the whole dataset, so nothing here reproduces a paragraph.
     * It exists because Spring Batch offers restartability and a checkpoint that were written but never
     * honoured would be state with no purpose (Rule 1 clause B1).
     * <p>
     * The two paths position differently, and both are handled. On the {@code repository} path the
     * position is already encoded in the slice cursor by
     * {@link #restoreRestartCursor(ExecutionContext)}, so there is nothing left to do here. On the
     * {@code fixed-width} path the stream must actually be advanced, which
     * {@link #skipAlreadyEmittedRecords(long, String)} does, verifying as it goes that it landed on the
     * checkpointed record.
     * <p>
     * <b>Side effects.</b> Advances the character stream on the {@code fixed-width} path and writes one
     * log event; does nothing at all on a cold start.
     *
     * @throws FatalProcessingException if the stream fails while skipping
     * @throws FileAccessException if the object holds fewer records than the checkpoint claims
     * @throws DataIntegrityException if the object changed between runs
     */
    private void positionAfterRestart() {
        if (recordsRead <= 0L || inputSource != InputSource.FIXED_WIDTH) {
            return;
        }
        try {
            skipAlreadyEmittedRecords(recordsRead, lastTransactionId);
        } catch (IOException failure) {
            LOG.error(ERROR_READING_MESSAGE);
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_READING_MESSAGE, OPERATION_OPEN, failure);
        }
        LOG.info("{} resumed: skipped {} records already emitted from the configured input object",
                LOGICAL_FILE, Long.valueOf(recordsRead));
    }

    // 9000-DALYTRAN-CLOSE, app/cbl/CBTRN02C.cbl:L582-L598.

    /**
     * Closes the input, reproducing {@code 9000-DALYTRAN-CLOSE}
     * ({@code app/cbl/CBTRN02C.cbl:L582-L598}).
     * <p>
     * <b>Arithmetic idiom, parity structure 3.</b> This paragraph writes
     * {@code MOVE 8 TO  APPL-RESULT.} at {@code :L583} - <b>with two spaces after {@code TO}</b> - where
     * {@code 0000-DALYTRAN-OPEN} writes {@code MOVE 8 TO APPL-RESULT.} at {@code :L237} with one. It also
     * uses {@code MOVE 8} where the four sibling readers use {@code ADD 8 TO ZERO GIVING}, for example
     * {@code app/cbl/CBACT01C.cbl:L152}. Both divergences are recorded here rather than normalised away,
     * and both compute the same value.
     * <p>
     * <b>What stands in for {@code CLOSE}.</b> Releasing the slice buffer on the {@code repository} path
     * and closing the character stream on the {@code fixed-width} one. No store round trip is needed and
     * none is made: a read-only pass holds nothing that requires committing. The teardown is nevertheless
     * guarded exactly as the source guards its close, so the failure branch stays reachable for any fault
     * raised while releasing the stream rather than being unreachable by construction.
     * <p>
     * <b>Idempotent.</b> Calling it twice is harmless, which matters because Spring Batch may close a
     * stream it failed to open. The stream reference is cleared before the guard runs, so a second call
     * has nothing left to release.
     * <p>
     * <b>Side effects.</b> Clears the buffer, the stream, the record area and the open flag. Writes two
     * log events only when the close fails.
     *
     * @throws FatalProcessingException if releasing the input raises a fault, reproducing
     *     {@code DISPLAY 'ERROR CLOSING DALYTRAN FILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L593-L596}
     */
    private void closeDailyTransactionFile() {
        // MOVE 8 TO  APPL-RESULT.  (:L583) - note the two spaces in the source; see parity structure 3.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        Throwable closeFailure = null;
        final BufferedReader closing = recordStream;
        recordStream = null;
        recordBuffer = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        dailyTransactionRecord = null;
        fileOpen = false;
        try {
            // CLOSE DALYTRAN-FILE  (:L584)
            if (closing != null) {
                closing.close();
            }
            ioStatus = STATUS_SUCCESS;
        } catch (IOException | RuntimeException failure) {
            closeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT  (:L585-L589).
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        // IF APPL-AOK CONTINUE  (:L590-L591)
        if (applResult != FileStatusMapper.APPL_AOK) {
            // ELSE DISPLAY 'ERROR CLOSING DALYTRAN FILE' / MOVE DALYTRAN-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L593-L596)
            LOG.error(ERROR_CLOSING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_CLOSING_MESSAGE, OPERATION_CLOSE, closeFailure);
        }
        // EXIT.  (:L598)
    }

    // 9999-ABEND-PROGRAM, app/cbl/CBTRN02C.cbl:L707-L711.

    /**
     * Abends the step, reproducing {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBTRN02C.cbl:L707-L711}).
     * <p>
     * The source emits {@code 'ABENDING PROGRAM'} ({@code :L708}), zeroes {@code TIMING}
     * ({@code :L709}), moves {@code 999} into {@code ABCODE} ({@code :L710}) and calls the Language
     * Environment abend service ({@code :L711}). <i>Evidence note:</i> other project documents cite this
     * paragraph as {@code :L707-L711}; the {@code CALL 'CEE3ABD'.} that terminates it is at
     * {@code :L711}, and the source governs.
     * <p>
     * The Java counterpart throws {@link FatalProcessingException} carrying the full
     * {@code app/cpy/CSMSG02Y.cpy} abend payload: abend code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, this program as the
     * culprit, the failing operation as the reason, and the legacy message as the message. Process return
     * code {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} is what the job's
     * status mapping derives from this exception; it is not set here, because a reader does not own the
     * process exit code. All three typed exceptions this class raises share the
     * {@link com.cardemo.exception.CardDemoException} base, so a caller can catch the family without
     * catching {@code RuntimeException}.
     * <p>
     * {@code TIMING} has no counterpart. It is a Language Environment abend parameter selecting whether a
     * dump is taken, and there is no dump facility to select; the exception carries the stack trace that
     * replaces it.
     * <p>
     * <b>This method always throws and never returns normally.</b>
     *
     * @param message the legacy message literal that preceded the abend, used as both the reason and part
     *     of the abend message so the failing operation is identifiable from either field
     * @param operation the attempted operation, one of {@value #OPERATION_OPEN},
     *     {@value #OPERATION_READ} or {@value #OPERATION_CLOSE}
     * @param cause the throwable that provoked the abend, or {@code null} when the status alone identified
     *     the fault; always attached when present, so the root cause is never lost (Rule 1 clause B4)
     * @throws FatalProcessingException always
     */
    private void abendProgram(final String message, final String operation, final Throwable cause) {
        // DISPLAY 'ABENDING PROGRAM'  (:L708)
        LOG.error(ABENDING_PROGRAM_MESSAGE);

        // MOVE 0 TO TIMING (:L709) / MOVE 999 TO ABCODE (:L710) / CALL 'CEE3ABD'. (:L711).
        // The message names the logical file, the operation and the rendered status - never the record.
        throw new FatalProcessingException(
                Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT,
                message,
                String.format(Locale.ROOT, "%s (%s, %s, %s)",
                        message, LOGICAL_FILE, operation, displayIoStatus(ioStatus)),
                cause);
    }

    // 9910-DISPLAY-IO-STATUS, app/cbl/CBTRN02C.cbl:L714-L727.

    /**
     * Renders a file status as the legacy diagnostic line, reproducing {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBTRN02C.cbl:L714-L727}).
     * <p>
     * The paragraph has two branches. When {@code IO-STATUS} is not numeric or its first byte is
     * {@code '9'} ({@code :L715-L716}), byte one is copied into position one and byte two is widened
     * through {@code TWO-BYTES-BINARY} into three digits ({@code :L717-L720}). Otherwise the field is set
     * to {@code '0000'} and the two status characters are overlaid at positions three and four
     * ({@code :L723-L724}). Both branches then emit {@code 'FILE STATUS IS: NNNN'} followed by the
     * four-character {@code IO-STATUS-04} field ({@code :L721}, {@code :L725}).
     * <p>
     * <b>{@code 'FILE STATUS IS: NNNN'} is a fixed 20-character literal, not a template.</b> The
     * {@code NNNN} is part of the constant text and the four rendered characters follow it, so status
     * {@code '23'} renders as {@code FILE STATUS IS: NNNN0023} and never as
     * {@code FILE STATUS IS: 0023}. Substituting the digits into the {@code NNNN} would be a parity
     * break, and the parity comparison is made on the emitted line.
     * <p>
     * <b>This method delegates and holds no logic of its own</b>, which is deliberate. The identical
     * paragraph recurs across the batch corpus - {@code app/cbl/CBTRN02C.cbl:L714},
     * {@code app/cbl/CBACT01C.cbl:L176} and their siblings - and
     * {@link FileStatusMapper#displayIoStatus(String)} is the single implementation of it, with
     * {@link FileStatus#DISPLAY_MESSAGE_PREFIX} the single definition of the literal. Re-deriving either
     * here would be the parallel mapping Rule 1 clause C3 forbids. The method is retained rather than
     * inlined so the paragraph stays individually traceable.
     * <p>
     * It is a pure function of its argument: it reads and writes no field of this instance.
     *
     * @param fileStatus the raw status, ordinarily two characters, and tolerated when {@code null},
     *     shorter or longer, exactly as a COBOL {@code MOVE} into a two-byte group tolerates a mismatched
     *     sending field
     * @return the complete legacy line, never {@code null}, always 24 characters: the 20-character prefix
     *     followed by exactly four rendered characters
     */
    private String displayIoStatus(final String fileStatus) {
        return fileStatusMapper.displayIoStatus(fileStatus);
    }

    // Restart support and construction-time validation. Every validator is private static, so the
    // constructor can call it without invoking an overridable method: that would publish a partially
    // constructed reference, which -Xlint:all -Werror reports as this-escape, and the class cannot be
    // final because the step scope proxies by subclassing.

    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes
     * instead of re-emitting records.
     * <p>
     * <b>The position is the record ordinal, because {@code DALYTRAN-ID} is not a key.</b> The input is
     * keyless ({@code app/cbl/CBTRN02C.cbl:L29-L32}) and {@link DailyTransactionRepository} records that
     * a staged identifier may legitimately repeat, so there is nothing to seek to by key. The ordinal is
     * unambiguous on both paths, and the checkpointed identifier is restored alongside it as the value
     * that proves a resumed run landed where it meant to.
     * <p>
     * <b>The {@code repository} path once resumed by offset, and no longer does.</b> It
     * positioned itself at {@code ordinal / pageSize} with a within-page skip, which is exact only while the
     * staging relation is unchanged between the two runs: a row inserted or deleted below the cursor shifts
     * every offset after it, so a resumed run could silently re-emit or silently skip rows. It now restores
     * {@link #lastIngestSequence} and seeks strictly past it through
     * {@link DailyTransactionRepository#findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
     * long, org.springframework.data.domain.Pageable)}, so a resumed run lands on the row after the last one
     * it genuinely emitted whatever else has changed - and no page arithmetic is performed at all, which
     * removes the overflow that arithmetic could suffer rather than merely reporting it. The
     * {@code fixed-width} path never had this exposure, because an object is immutable once written and
     * {@link #skipAlreadyEmittedRecords(long, String)} verifies the landing record.
     * <p>
     * <b>The checkpointed ordinal, and the fallback when it is absent.</b> The cursor is written to the
     * context by {@link #update(ExecutionContext)} as the last emitted {@code ingest_seq}, which is what makes
     * the resume exact. A context written before that entry existed carries only the emitted record count; in
     * that case the count is used as the ordinal, which is correct because {@code ingest_seq} <em>is</em> the
     * record's one-based position in the file, so the <em>k</em>-th row carries ordinal <em>k</em>. The
     * fallback is therefore exact for a contiguously staged relation and degrades to re-reading rather than to
     * skipping if it is ever not.
     * <p>
     * A non-positive checkpoint is ignored and the read starts from the beginning, which is the correct
     * reading of a checkpoint written before any record was emitted.
     *
     * @param executionContext the step execution context, already known to contain the record-count key
     */
    private void restoreRestartCursor(final ExecutionContext executionContext) {
        final long checkpointed = executionContext.getLong(CONTEXT_KEY_RECORDS_READ, 0L);
        if (checkpointed <= 0L) {
            return;
        }

        recordsRead = checkpointed;
        lastTransactionId = executionContext.containsKey(CONTEXT_KEY_LAST_TRANSACTION_ID)
                ? executionContext.getString(CONTEXT_KEY_LAST_TRANSACTION_ID)
                : null;
        lastIngestSequence = executionContext.getLong(CONTEXT_KEY_LAST_INGEST_SEQUENCE, checkpointed);

        // The checkpointed transaction identifier is reported as present or absent, never by value: the
        // ingest ordinal below IS the restart position, and the identifier only corroborates it, so naming
        // it would disclose a business record identifier for no diagnostic gain. The ordinal is a Java-side
        // surrogate rather than a business key, so it is named in full.
        LOG.info("Resuming {} read after {} records; source={} lastIngestSequence={} checkpointedKey={}",
                LOGICAL_FILE, Long.valueOf(recordsRead), inputSource, Long.valueOf(lastIngestSequence),
                lastTransactionId == null ? "absent" : "present");
    }

    /**
     * Resolves and validates the configured input selector.
     * <p>
     * The value is untrusted configuration, so it is checked rather than trusted (Rule 1 clause A2). It is
     * accepted case-insensitively and with either a hyphen or an underscore, because
     * {@code fixed-width} is the natural spelling in a YAML profile while {@code FIXED_WIDTH} is the
     * natural spelling of the constant. {@link Locale#ROOT} is used for the case fold so the result cannot
     * depend on the host locale - a Turkish default locale would otherwise fold {@code i} to a dotless
     * capital and make {@code repository} unrecognisable.
     *
     * @param configured the raw configured value
     * @return the selected path, never {@code null}
     * @throws IllegalArgumentException if the value is {@code null}, blank, or names neither path
     */
    private static InputSource requireInputSource(final String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be either 'repository' or 'fixed-width' but was blank; the default is "
                            + "'repository', so remove the key rather than emptying it",
                    PROPERTY_SOURCE));
        }
        final String normalised = configured.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return InputSource.valueOf(normalised);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be either 'repository' or 'fixed-width'; '%s' names neither. 'repository' "
                            + "reads the staged daily_transaction rows and 'fixed-width' decodes the "
                            + "350-byte images from the input object",
                    PROPERTY_SOURCE, configured), unknown);
        }
    }

    /**
     * Validates the injected page size.
     *
     * @param pageSize the configured value
     * @return {@code pageSize}, unchanged
     * @throws IllegalArgumentException if {@code pageSize} is less than one, because a slice of zero or
     *     fewer rows could never advance the read
     */
    private static int requirePositivePageSize(final int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be at least 1 but was %d; a non-positive slice cannot advance the "
                            + "sequential read of %s",
                    PROPERTY_PAGE_SIZE, Integer.valueOf(pageSize), LOGICAL_FILE));
        }
        return pageSize;
    }

    /**
     * Validates the configured input object key.
     * <p>
     * Surrounding whitespace is stripped, because a YAML value can pick it up and a key with a trailing
     * space names a different object. A blank value is refused outright: it would address the bucket
     * itself rather than an object, which fails in a way that looks like a missing file rather than like
     * a configuration defect.
     *
     * @param configured the raw configured value
     * @return the key with surrounding whitespace removed, never blank
     * @throws IllegalArgumentException if the value is {@code null} or blank
     */
    private static String requireObjectKey(final String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must name the object holding the 350-byte %s images and must not be blank; "
                            + "the default is '%s'",
                    PROPERTY_OBJECT_KEY, LOGICAL_FILE, DEFAULT_OBJECT_KEY));
        }
        return configured.strip();
    }

    /**
     * Validates the configured input bucket against the selected path.
     * <p>
     * The bucket is required <b>only</b> on the {@code fixed-width} path, and it is required <b>at
     * construction</b> rather than at the first read, so a misconfiguration fails before any work is done.
     * On the {@code repository} path an absent value is legitimate and yields an empty string: the default
     * path must carry no cloud prerequisite, because assuming one would be exactly the
     * environment-specific assumption Rule 1 clause C2 rules out.
     * <p>
     * The key itself is declared once, at {@code src/main/resources/application.yml:1064}, as
     * {@code ${CARDDEMO_S3_BATCH_INPUT_BUCKET}} with no literal default, so in any context that loads
     * that file an absent environment variable already fails the refresh. The empty default here can
     * therefore only take effect in a context that deliberately omits the key, such as a unit test.
     *
     * @param configured the raw configured value, permitted to be {@code null} or blank on the
     *     {@code repository} path
     * @param selected the resolved input path
     * @return the bucket name with surrounding whitespace removed, or the empty string when none is
     *     configured and none is needed
     * @throws IllegalArgumentException if the {@code fixed-width} path is selected with no bucket
     */
    private static String requireInputBucket(final String configured, final InputSource selected) {
        final String normalised = configured == null ? "" : configured.strip();
        if (selected == InputSource.FIXED_WIDTH && normalised.isEmpty()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be configured with a non-blank value when %s is 'fixed-width'; set %s. "
                            + "The bucket is provisioned idempotently by localstack-init/init-aws.sh",
                    PROPERTY_INPUT_BUCKET, PROPERTY_SOURCE, ENV_INPUT_BUCKET));
        }
        return normalised;
    }

    /**
     * Extracts the two-character code of a {@link FileStatus} that is expected to be an exact value
     * rather than a family, so the literals this class compares against derive from the single definition
     * of the status vocabulary instead of being restated as string constants (Rule 1 clause C3).
     *
     * @param status the status constant, expected to be an exact value
     * @return its two-character code
     * @throws IllegalStateException if {@code status} is a family and exposes no exact code, which would
     *     mean the enum contract had changed underneath this class
     */
    private static String requireExactCode(final FileStatus status) {
        return status.code().orElseThrow(() -> new IllegalStateException(String.format(Locale.ROOT,
                "FileStatus.%s must expose an exact two-character code; it reports itself as a family",
                status.name())));
    }

    /**
     * Validates the signing key the object-authenticity key is derived from.
     *
     * <p>Required only on the {@code fixed-width} path, and required <b>at construction</b> rather than at
     * the first read, so a topology that selected the object path without the key fails before any work is
     * done. On the {@code repository} path an absent value is legitimate and yields an empty string: nothing
     * is authenticated there because nothing external is read.
     *
     * <p>The message names the property and the environment variable and never any part of the value.
     *
     * @param configured the value bound from {@value #PROPERTY_SIGNING_KEY}, possibly {@code null}
     * @param selected the resolved input path
     * @return the key, or an empty string on the {@code repository} path
     * @throws IllegalArgumentException if the {@code fixed-width} path is selected with a blank key
     */
    private static String requireSigningKey(final String configured, final InputSource selected) {
        final String normalised = configured == null ? "" : configured;
        if (selected == InputSource.FIXED_WIDTH && normalised.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s must be configured when %s is 'fixed-width', because the authenticity key of the "
                            + "input object is derived from it and an unauthenticated object is never read; "
                            + "set JWT_SIGNING_KEY. There is no default and no unverified mode",
                    PROPERTY_SIGNING_KEY, PROPERTY_SOURCE));
        }
        return normalised;
    }

    // Finding M-11, severity High: the authenticity of the external input object.

    /**
     * Refuses the input object unless it carries a valid authenticity envelope, before a record is parsed.
     *
     * <p><b>Well formed is not the same as ours.</b> Correct length-and-charset parsing establishes that
     * an object is <em>well formed</em>, not that it is <em>ours</em>. The input bucket lives in an emulator
     * whose community edition was measured to enforce no authorisation at all - see the evidence recorded in
     * {@code docker-compose.yml} on the {@code localstack} service - so any principal able to reach the
     * emulator port could replace this object with 350-byte records that parse perfectly and post as real
     * transactions. Network containment narrows who can reach the port; it cannot tell one reachable
     * principal from another. That distinction is made here.
     *
     * <p><b>Both halves are necessary, and each closes what the other cannot.</b> The keyed code over the
     * manifest proves that a holder of the key vouched for <em>this</em> bucket, <em>this</em> key,
     * <em>this</em> length and <em>this</em> content digest - so metadata cannot be forged. The digest pass
     * then proves the bytes are the ones that were vouched for - so a valid manifest cannot be copied onto a
     * different body, which a signature check alone would accept whenever the substituted body kept the same
     * length.
     *
     * <p><b>Why the digest is computed before parsing rather than while parsing.</b> Verifying as the records
     * stream would authenticate the tail only after the head had already been posted and committed, which is
     * indistinguishable from having accepted it. The object is therefore read once through the digest and
     * once for the records. The digest pass allocates one fixed buffer and holds no content, so the cost is a
     * second linear read rather than a second copy in memory - the same bounded-read discipline the report
     * job applies to its own generation.
     *
     * <p><b>No rejection says which check failed.</b> The three metadata members, the version prefix, the
     * code itself, the declared length and the digest are all refused with one reason, because the
     * distinctions are exactly the feedback a forger would iterate against. The reason names the logical
     * dataset and the bucket; it never names the object key, the writer, the digest or any record content.
     *
     * @param resource the object about to be read, already proven to exist; never {@code null}
     * @throws FatalProcessingException if the object does not carry a valid envelope for its own bytes
     */
    private void verifyInputObjectAuthenticity(final S3Resource resource) {
        final Map<String, String> metadata = resource.metadata() == null
                ? Map.of()
                : resource.metadata();
        final String writer = metadataValue(metadata, InputObjectEnvelope.METADATA_WRITER);
        final String declaredDigest = metadataValue(metadata, InputObjectEnvelope.METADATA_CONTENT_SHA256);
        final String presentedCode = metadataValue(metadata, InputObjectEnvelope.METADATA_SIGNATURE);
        final long declaredLength = resource.contentLength();

        final boolean manifestAccepted = InputObjectEnvelope.isPermittedWriter(writer)
                && InputObjectEnvelope.isRenderedDigest(declaredDigest)
                && declaredLength >= 0
                && InputObjectEnvelope.verify(inputBucket, objectKey, declaredLength, writer,
                        declaredDigest, signingKey, presentedCode);
        if (!manifestAccepted) {
            throw refuseInputObject();
        }

        final DigestOutcome measured = digestOf(resource);
        if (measured.length() != declaredLength
                || !MessageDigest.isEqual(InputObjectEnvelope.fromHexadecimal(declaredDigest),
                        measured.digest())) {
            throw refuseInputObject();
        }

        // The writer identity is attribution rather than personal data, and it is inside the authenticated
        // manifest, so an operator reading this line knows WHO vouched for the object rather than merely that
        // something did. The object key is still withheld, for the reason the log-hygiene section gives: it is
        // date partitioned and therefore carries a business date.
        LOG.info("{} input object authenticity verified; writer={} bytes={}", LOGICAL_FILE, writer,
                Long.valueOf(measured.length()));
    }

    /**
     * Builds the one refusal this control raises, with the one reason vocabulary it uses.
     *
     * @return the refusal, never {@code null}
     */
    private FatalProcessingException refuseInputObject() {
        LOG.error("{} input object refused: no valid authenticity envelope for its own content in bucket "
                + "'{}'", LOGICAL_FILE, inputBucket);
        return new FatalProcessingException(
                Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT,
                REASON_INPUT_NOT_AUTHENTIC,
                String.format(Locale.ROOT,
                        "%s (%s, %s): the configured input object in bucket '%s' does not carry a valid "
                                + "authenticity envelope for its own content, so no record was parsed. The "
                                + "producer must set the three '%s'-prefixed metadata members described on "
                                + "%s.InputObjectEnvelope",
                        REASON_INPUT_NOT_AUTHENTIC, LOGICAL_FILE, OPERATION_OPEN, inputBucket,
                        InputObjectEnvelope.METADATA_PREFIX, DailyTransactionReader.class.getSimpleName()),
                null);
    }

    /**
     * Looks a metadata member up without depending on the case the store reports.
     *
     * <p>Object stores lower-case user-metadata names in transit, and a producer may have written any case,
     * so a case-sensitive lookup would make the control depend on a detail neither side controls.
     *
     * @param metadata the object's user metadata, never {@code null}
     * @param name the member name, already lowercase
     * @return the value, stripped, or an empty string when the member is absent or blank
     */
    private static String metadataValue(final Map<String, String> metadata, final String name) {
        for (final Map.Entry<String, String> member : metadata.entrySet()) {
            if (member.getKey() != null && member.getKey().equalsIgnoreCase(name)) {
                return member.getValue() == null ? "" : member.getValue().strip();
            }
        }
        return "";
    }

    /**
     * Reads the object once and reports its digest and its exact byte count.
     *
     * @param resource the object to measure; never {@code null}
     * @return the measured digest and length, never {@code null}
     * @throws FatalProcessingException if the object cannot be read while being measured
     */
    private DigestOutcome digestOf(final S3Resource resource) {
        final MessageDigest digest = InputObjectEnvelope.newDigest();
        final byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        long length = 0L;
        try (InputStream bytes = resource.getInputStream()) {
            int read = bytes.read(buffer);
            while (read >= 0) {
                digest.update(buffer, 0, read);
                length += read;
                read = bytes.read(buffer);
            }
        } catch (final IOException failure) {
            // Not an authenticity refusal: the object could not be read at all. Reported as the OPEN failure
            // it is, with the cause attached so nothing is swallowed.
            throw new FatalProcessingException(
                    Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                    ABEND_CULPRIT,
                    ERROR_OPENING_MESSAGE,
                    String.format(Locale.ROOT,
                            "%s (%s, %s): the configured input object in bucket '%s' could not be read while "
                                    + "its content digest was being measured",
                            ERROR_OPENING_MESSAGE, LOGICAL_FILE, OPERATION_OPEN, inputBucket),
                    failure);
        }
        return new DigestOutcome(digest.digest(), length);
    }

    /**
     * What one digest pass measured: the digest of the content and the number of bytes it covered.
     *
     * @param digest the raw digest bytes; never {@code null}
     * @param length the exact number of content bytes digested
     */
    private record DigestOutcome(byte[] digest, long length) {
    }

    /**
     * The authenticity envelope that makes the external input object provably vouched for.
     *
     * <p><b>Finding M-11, severity High.</b> This is the object-side counterpart of the queue-side envelope
     * in {@code com.cardemo.service.report.ReportSubmissionService.JobSubmissionEnvelope}, and the two are
     * deliberately <em>separate contracts rather than one shared implementation</em>: they authenticate
     * different things - a message body against an object manifest - and they derive different keys from the
     * same material precisely so that neither can be replayed as the other. What they share is a
     * standard-library keyed hash and a hexadecimal rendering, which cannot diverge in behaviour the way two
     * validation grammars can. Each cites the other so a reader finds both.
     *
     * <p><b>The producer contract, stated in full because the producer is outside this repository.</b> An
     * upstream feed writes the object with three user-metadata members:
     *
     * <ul>
     *   <li>{@value #METADATA_WRITER} - who produced it. Attribution, carried inside the authenticated
     *       manifest so it cannot be swapped, and bounded to {@value #WRITER_MAX_LENGTH} printable
     *       characters.</li>
     *   <li>{@value #METADATA_CONTENT_SHA256} - the SHA-256 of the object's bytes, lowercase hexadecimal.</li>
     *   <li>{@value #METADATA_SIGNATURE} - {@value #SIGNATURE_VERSION}, then {@code =}, then the lowercase
     *       hexadecimal keyed code of the canonical manifest.</li>
     * </ul>
     *
     * <p>The canonical manifest is these six lines joined by {@code \n}, in this order and with no trailing
     * newline: the derivation label, the bucket, the object key, the decimal content length, the writer, and
     * the lowercase hexadecimal digest. Bucket and key are inside it on purpose - a validly signed object
     * cannot then be moved to another key, or into another bucket, and still verify, which is what stops a
     * stale generation being replayed as today's input.
     *
     * <p>The purpose key is {@code HMAC-SHA-256(signing key, }{@value #KEY_DERIVATION_LABEL}{@code )} and the
     * code is {@code HMAC-SHA-256(purpose key, canonical manifest)}. With {@code openssl} that is two calls,
     * which is why this contract needs no tooling in this repository to be satisfiable.
     */
    public static final class InputObjectEnvelope {

        /** The common prefix of the three metadata members, named in the refusal so a producer can find them. */
        public static final String METADATA_PREFIX = "carddemo-";

        /** Metadata member naming the producer of the object. */
        public static final String METADATA_WRITER = METADATA_PREFIX + "writer";

        /** Metadata member carrying the lowercase hexadecimal SHA-256 of the object's bytes. */
        public static final String METADATA_CONTENT_SHA256 = METADATA_PREFIX + "content-sha256";

        /** Metadata member carrying the rendered keyed code of the canonical manifest. */
        public static final String METADATA_SIGNATURE = METADATA_PREFIX + "signature";

        /**
         * The version prefix of a rendered code, so a future algorithm change is a new prefix rather than an
         * ambiguous byte string and a consumer refuses a version it does not implement.
         */
        public static final String SIGNATURE_VERSION = "v1";

        /**
         * The domain-separation label. Any change to it invalidates every previously issued code, which is
         * why it carries the version {@link #SIGNATURE_VERSION} renders.
         */
        public static final String KEY_DERIVATION_LABEL = "carddemo/s3/dalytran-object/v1";

        /** Longest accepted writer identity. Bounded because it is untrusted input that reaches a log. */
        public static final int WRITER_MAX_LENGTH = 64;

        /** Characters of a rendered SHA-256: 32 bytes as lowercase hexadecimal. */
        private static final int DIGEST_HEX_LENGTH = 64;

        /** The keyed hash and the digest. Both are on every supported runtime, so no configuration selects them. */
        private static final String MAC_ALGORITHM = "HmacSHA256";

        /** The content digest algorithm. */
        private static final String DIGEST_ALGORITHM = "SHA-256";

        /** Not instantiable: this type is a contract, and its operations are pure functions. */
        private InputObjectEnvelope() {
            throw new AssertionError("InputObjectEnvelope is a contract holder and is never instantiated");
        }

        /**
         * Renders the code an authorised producer writes into {@value #METADATA_SIGNATURE}.
         *
         * <p>Published rather than private because the producer contract has to be executable to be real:
         * this is the definition the integration tests sign with, and the definition any future in-repository
         * feed would call instead of restating the canonical form.
         *
         * @param bucket the destination bucket; must not be {@code null}
         * @param key the destination object key; must not be {@code null}
         * @param contentLength the exact number of content bytes
         * @param writer the producer identity; must not be {@code null}
         * @param contentSha256Hex the lowercase hexadecimal SHA-256 of the content; must not be {@code null}
         * @param signingKey the application signing key the purpose key is derived from; never blank
         * @return the rendered code, never {@code null}
         * @throws IllegalArgumentException if the signing key is absent or blank
         */
        public static String sign(final String bucket, final String key, final long contentLength,
                final String writer, final String contentSha256Hex, final String signingKey) {

            return SIGNATURE_VERSION + '=' + hexadecimal(mac(purposeKey(signingKey),
                    canonicalManifest(bucket, key, contentLength, writer, contentSha256Hex)
                            .getBytes(StandardCharsets.UTF_8)));
        }

        /**
         * Decides whether a presented code was produced for this exact manifest by a holder of the key.
         *
         * <p>Every rejection returns {@code false} rather than throwing, so the caller decides what a failed
         * verification means; and no rejection reports which reason applied.
         *
         * @param bucket the bucket the object was read from; must not be {@code null}
         * @param key the object key that was read; must not be {@code null}
         * @param contentLength the length the store reports
         * @param writer the writer identity the metadata carried; must not be {@code null}
         * @param contentSha256Hex the digest the metadata carried; must not be {@code null}
         * @param signingKey the application signing key; never blank
         * @param presented the metadata value exactly as delivered, possibly empty
         * @return {@code true} only when the presented value is a well-formed code of a version this
         *     implementation produces and equals the code for this manifest
         * @throws IllegalArgumentException if the signing key is absent or blank
         */
        public static boolean verify(final String bucket, final String key, final long contentLength,
                final String writer, final String contentSha256Hex, final String signingKey,
                final String presented) {

            final String prefix = SIGNATURE_VERSION + '=';
            if (presented == null || !presented.startsWith(prefix)) {
                return false;
            }
            final byte[] offered = fromHexadecimal(presented.substring(prefix.length()));
            if (offered == null) {
                return false;
            }
            final byte[] expected = mac(purposeKey(signingKey),
                    canonicalManifest(bucket, key, contentLength, writer, contentSha256Hex)
                            .getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, offered);
        }

        /**
         * Reports whether a writer identity is acceptable at all: present, bounded and printable.
         *
         * <p>Bounded and character-checked because it is untrusted input that reaches a log line, and a
         * newline inside it would forge a second log record.
         *
         * @param writer the value the metadata carried, possibly empty
         * @return true when the value may be used
         */
        public static boolean isPermittedWriter(final String writer) {
            if (writer == null || writer.isEmpty() || writer.length() > WRITER_MAX_LENGTH) {
                return false;
            }
            for (int position = 0; position < writer.length(); position++) {
                final char character = writer.charAt(position);
                if (character < 0x20 || character > 0x7E) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Reports whether a value has the exact shape of a rendered SHA-256.
         *
         * @param digestHex the value the metadata carried, possibly empty
         * @return true when it is {@value #DIGEST_HEX_LENGTH} lowercase hexadecimal characters
         */
        public static boolean isRenderedDigest(final String digestHex) {
            if (digestHex == null || digestHex.length() != DIGEST_HEX_LENGTH) {
                return false;
            }
            return fromHexadecimal(digestHex) != null;
        }

        /**
         * Builds a digest instance for one pass over an object's content.
         *
         * @return a fresh digest, never {@code null}
         * @throws IllegalStateException if the platform lacks SHA-256, which no supported runtime does
         */
        public static MessageDigest newDigest() {
            try {
                return MessageDigest.getInstance(DIGEST_ALGORITHM);
            } catch (final NoSuchAlgorithmException absent) {
                throw new IllegalStateException(DIGEST_ALGORITHM + " is required by every supported runtime",
                        absent);
            }
        }

        /**
         * Renders bytes as lowercase hexadecimal.
         *
         * @param value the bytes; must not be {@code null}
         * @return the rendering, never {@code null}
         */
        public static String hexadecimal(final byte[] value) {
            final StringBuilder rendered = new StringBuilder(value.length * 2);
            for (final byte element : value) {
                rendered.append(Character.forDigit((element >> 4) & 0xF, 16));
                rendered.append(Character.forDigit(element & 0xF, 16));
            }
            return rendered.toString();
        }

        /**
         * Parses lowercase hexadecimal, reporting a malformed value as {@code null} rather than by throwing.
         *
         * <p>{@code null} rather than an exception because a malformed value is one of the ordinary rejection
         * paths of an untrusted metadata member, not a programming error.
         *
         * @param value the rendering to parse; may be empty
         * @return the bytes, or {@code null} when the value is not an even-length run of lowercase
         *     hexadecimal digits
         */
        public static byte[] fromHexadecimal(final String value) {
            if (value == null || value.isEmpty() || value.length() % 2 != 0) {
                return null;
            }
            final byte[] parsed = new byte[value.length() / 2];
            for (int index = 0; index < parsed.length; index++) {
                final int high = Character.digit(value.charAt(index * 2), 16);
                final int low = Character.digit(value.charAt(index * 2 + 1), 16);
                if (high < 0 || low < 0
                        || Character.isUpperCase(value.charAt(index * 2))
                        || Character.isUpperCase(value.charAt(index * 2 + 1))) {
                    return null;
                }
                parsed[index] = (byte) ((high << 4) | low);
            }
            return parsed;
        }

        /**
         * Joins the six authenticated members into the exact bytes the code is taken over.
         *
         * @param bucket the bucket
         * @param key the object key
         * @param contentLength the content length
         * @param writer the writer identity
         * @param contentSha256Hex the rendered content digest
         * @return the canonical manifest, never {@code null}
         */
        private static String canonicalManifest(final String bucket, final String key,
                final long contentLength, final String writer, final String contentSha256Hex) {

            return KEY_DERIVATION_LABEL + '\n'
                    + Objects.requireNonNull(bucket, "bucket must not be null") + '\n'
                    + Objects.requireNonNull(key, "key must not be null") + '\n'
                    + Long.toString(contentLength) + '\n'
                    + Objects.requireNonNull(writer, "writer must not be null") + '\n'
                    + Objects.requireNonNull(contentSha256Hex, "contentSha256Hex must not be null");
        }

        /**
         * Derives the single-purpose key from the application signing key.
         *
         * @param signingKey the application signing key; never blank
         * @return the derived key material, never {@code null}
         * @throws IllegalArgumentException if the signing key is absent or blank
         */
        private static byte[] purposeKey(final String signingKey) {
            if (signingKey == null || signingKey.isBlank()) {
                throw new IllegalArgumentException("the input-object envelope key is derived from "
                        + PROPERTY_SIGNING_KEY + ", which must be configured; it has no default anywhere in "
                        + "this repository and an unauthenticated input object is never read");
            }
            return mac(signingKey.getBytes(StandardCharsets.UTF_8),
                    KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Computes one keyed code.
         *
         * @param key the key material; must not be {@code null}
         * @param content the bytes to authenticate; must not be {@code null}
         * @return the raw code bytes, never {@code null}
         * @throws IllegalStateException if the platform lacks the algorithm, which no supported runtime does
         */
        private static byte[] mac(final byte[] key, final byte[] content) {
            try {
                final Mac keyedHash = Mac.getInstance(MAC_ALGORITHM);
                keyedHash.init(new SecretKeySpec(key, MAC_ALGORITHM));
                return keyedHash.doFinal(content);
            } catch (final NoSuchAlgorithmException | InvalidKeyException unavailable) {
                throw new IllegalStateException(MAC_ALGORITHM
                        + " is required by every supported runtime and the derived key is never empty",
                        unavailable);
            }
        }
    }
}
