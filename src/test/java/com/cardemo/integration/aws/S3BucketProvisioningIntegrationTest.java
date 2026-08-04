/*
 ******************************************************************
 * Program     : S3BucketProvisioningIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 AWS integration test (Failsafe tier)
 * Function    : Verifies the three logical S3 buckets replacing the seven legacy GDG bases,
 *               output-bucket versioning, and that no live AWS endpoint is reachable.
 * Source      : app/jcl/DEFGDGB.jcl:25-57 @ 7756d89 (six GDG bases, all LIMIT(5) SCRATCH)
 * Source      : app/jcl/DALYREJS.jcl:24-28 @ 7756d89 (the seventh GDG base)
 * Source      : app/jcl/REPTFILE.jcl:25-28 @ 7756d89 (TRANREPT LIMIT(10), no SCRATCH -
 *               retention conflict)
 * Source      : app/catlg/LISTCAT.txt:3937-3951 @ 7756d89 (totals block: GDG 7, CLUSTER 10,
 *               AIX 3, PATH 3; the GDG count itself is line 3942)
 * Source      : app/jcl/POSTTRAN.jcl:30-38 @ 7756d89 (DALYTRAN.PS input, DALYREJS(+1) LRECL=430)
 * Source      : app/jcl/CREASTMT.JCL:69,89,94 @ 7756d89 (STMTFILE 80, HTMLFILE 80 then 100)
 * Source      : app/proc/TRANREPT.prc:21,76,78 @ 7756d89 (EXEC PROC=REPROC, LRECL=133,
 *               TRANREPT(+1))
 * Source      : app/jcl/DEFCUST.jcl:35-38 @ 7756d89 (AWS.CUSTDATA.CLUSTER orphan, not modelled)
 * Note        : No COBOL analogue - GDG generations become S3 keys over a versioned bucket.
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Proves that the object-store substrate the CardDemo migration stands on is actually there and actually
 * shaped the way the plan says: three logical buckets rather than seven, object versioning on the one bucket
 * that inherits generation semantics, and no reachable address anywhere in the tier except the emulator
 * container's own.
 *
 * <h2>1. What it does</h2>
 *
 * <p>The legacy system kept seven generation data groups. This class is the written and executable proof that
 * they became <strong>three</strong> buckets and not seven, and that the collapse is a namespacing decision
 * rather than a loss of separation.
 *
 * <p><strong>The count is six plus one, and the seventh is the one most easily lost.</strong>
 * {@code app/jcl/DEFGDGB.jcl:25-57} defines six bases, every one of them
 * {@code LIMIT(5)} with {@code SCRATCH}:
 *
 * <ol>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP} - {@code app/jcl/DEFGDGB.jcl:25-27}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.DALY} - {@code app/jcl/DEFGDGB.jcl:31-33}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANREPT} - {@code app/jcl/DEFGDGB.jcl:37-39}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TCATBALF.BKUP} - {@code app/jcl/DEFGDGB.jcl:43-45}</li>
 *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN} - {@code app/jcl/DEFGDGB.jcl:49-51}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} - {@code app/jcl/DEFGDGB.jcl:55-57}</li>
 * </ol>
 *
 * <p>The seventh lives in a member of its own, which is exactly why a reader who only opens the
 * obviously-named definition job undercounts: {@code app/jcl/DALYREJS.jcl:24-28} declares
 * {@code AWS.M2.CARDDEMO.DALYREJS} with the same {@code LIMIT(5)} and {@code SCRATCH}. Six plus one is
 * <strong>seven</strong>, and the catalogue agrees independently -
 * {@code app/catlg/LISTCAT.txt:3942} reports {@code GDG 7} inside the totals block at
 * {@code :3937-3951}, alongside {@code CLUSTER 10}, {@code AIX 3}, {@code PATH 3}, {@code DATA 13},
 * {@code INDEX 13}, {@code NONVSAM 160} and {@code TOTAL 209}, and the listing carries exactly seven
 * {@code GDG BASE} entries, at L684 DALYREJS, L1098 SYSTRAN, L1202 TCATBALF.BKUP, L1527 TRANREPT, L1631
 * TRANSACT.BKUP, L2919 TRANSACT.COMBINED and L3021 TRANSACT.DALY. <strong>An earlier revision of the plan
 * said six. Seven is right, and the consequence of six would not have been cosmetic: a missing base is a
 * missing output prefix, so one batch stream would have written into another's namespace.</strong>
 *
 * <p><strong>Why seven bases need only one bucket.</strong> A bucket is not the unit of separation here - a
 * key prefix is. {@code src/main/resources/application.yml} declares seven
 * {@code carddemo.aws.s3.gdg-prefixes.*} entries, one per base, and this class asserts that a generation
 * written under any one of them is discoverable under that prefix and under no other. That is what makes the
 * seven-to-three collapse provable by inspection instead of asserted in prose, and it is why creating seven
 * buckets would have added nothing except six more things to provision.
 *
 * <p><strong>The three roles are not interchangeable</strong>, and each is anchored to a record length the
 * job control declares:
 *
 * <dl>
 *   <dt>input</dt>
 *   <dd>The daily transaction staging dataset. {@code app/jcl/POSTTRAN.jcl:30-31} names
 *       {@code AWS.M2.CARDDEMO.DALYTRAN.PS} and the image is 350 bytes; the ASCII fixture that seeds it is
 *       {@code app/data/ASCII/dailytran.txt} - spelled in full, unlike the mainframe dataset name, which is
 *       a difference that silently breaks a resource path that guesses. <strong>Not versioned</strong>: an
 *       input dataset had no generations.</dd>
 *   <dt>output</dt>
 *   <dd>The generation-bearing bucket, and <strong>the only versioned one</strong>. It carries the rejects at
 *       430 bytes - {@code app/jcl/POSTTRAN.jcl:34-38} declares
 *       {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} for {@code AWS.M2.CARDDEMO.DALYREJS(+1)}, note
 *       {@code RECFM=F}, fixed unblocked - and the report at 133 bytes, from
 *       {@code app/proc/TRANREPT.prc:76} and {@code :78}. Versioning is what a relative generation
 *       reference becomes, so this is the bucket that inherits it.</dd>
 *   <dt>statements</dt>
 *   <dd>The two statement streams, at 80 and 100 bytes per line: {@code app/jcl/CREASTMT.JCL:89} declares
 *       {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} for the text output and {@code :94} declares
 *       {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} for the markup output, under deterministic account and
 *       month prefixes. <strong>Not versioned</strong>: those outputs are pre-deleted and rewritten by
 *       {@code app/jcl/CREASTMT.JCL} STEP030 and STEP040, so retaining prior copies would keep history the
 *       legacy system never had.</dd>
 * </dl>
 *
 * <p>Record geometry itself is deliberately <em>not</em> asserted here, and neither is generation-key
 * ordering: both belong to the planned {@code S3GenerationKeyIntegrationTest}. That is phrased as an
 * obligation rather than as a fact on purpose, so the sentence stays true whether or not the file has been
 * authored yet. Byte-exact 430-byte reject geometry is in any case already covered in this package by
 * {@code S3AndSqsEmulatorIntegrationTest}, which exists and passes. This class stops at bucket existence,
 * bucket roles, the versioning mechanism, the seven-base accounting and the proof that no live address is
 * reachable. One concern per class, as Rule 1 Clause A asks.
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp clean verify}.
 *
 * <p><strong>This class is collected by Failsafe, never by Surefire</strong>, and the distinction is
 * load bearing rather than tidy. The root build binds {@code maven-failsafe-plugin} to
 * {@code **}{@code /integration/}{@code **}{@code /*Test.java} and the matching {@code e2e} tree at
 * {@code integration-test} and {@code verify}, deliberately, even though these classes keep the {@code Test}
 * suffix; {@code maven-surefire-plugin} includes {@code **}{@code /*Test.java} but <em>excludes</em>
 * {@code **}{@code /integration/}{@code **} and {@code **}{@code /e2e/}{@code **}. A class moved up to
 * {@code com/cardemo/integration/}, to {@code com/cardemo/}, to {@code com/} or to the root of
 * {@code src/test/java} matches <strong>neither</strong> include set, so it is collected by neither plugin
 * and <strong>silently never runs</strong> - a green build, success reported by both plugins, no error and no
 * output. That is the worst failure mode available in this build, so the path
 * {@code src/test/java/com/cardemo/integration/aws/S3BucketProvisioningIntegrationTest.java} must not be
 * renamed or relocated and no sub-package may be introduced beneath it. After a build, confirm the Failsafe
 * report names this class; Surefire naming it instead, or neither naming it, is a
 * <strong>Blocker</strong>.
 *
 * <p><strong>A reachable Docker socket is a prerequisite, not a convenience.</strong>
 * {@code AbstractAwsIntegrationTest} starts a PostgreSQL 16 container and a LocalStack container eagerly and
 * rethrows a startup failure rather than skipping, because a green build must never be obtainable by having
 * no daemon. There is no in-memory substitute: an in-memory object store has no bucket-versioning semantics
 * to assert, which is the entire subject of this class. Where no daemon or socket is available the correct
 * report is that the gate is <em>blocked</em>, never an untested pass. The environment this was written and
 * run against supplies Docker Engine 29.7.0 with the socket present, and host {@code java} 25.0.3,
 * {@code javac} 25.0.3 and Maven 3.9.11 are all on the path, so Maven runs directly. An earlier note in the
 * plan claiming Docker was unavailable, and a later one claiming the host toolchain was absent so Maven had
 * to run inside a container, are both <strong>stale and withdrawn</strong>; severity <strong>Low</strong>,
 * because each cost a wrong instruction rather than a wrong artefact.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>The {@code test} profile is active, inherited from the harness. Every address, port and credential comes
 * from a running container at a precedence above every profile file, so <strong>no host, port, endpoint,
 * connection string, access key, secret key or bucket name literal appears in this source</strong>. The
 * endpoint is referred to only as the LocalStack endpoint injected by Testcontainers, and it is compared
 * against the value the harness exposes rather than against any written host.
 *
 * <ul>
 *   <li><strong>The three bucket names are environment-indirected and read by property key</strong>, never
 *       written: {@code carddemo.aws.s3.batch-input-bucket},
 *       {@code carddemo.aws.s3.batch-output-bucket} and {@code carddemo.aws.s3.statements-bucket}. These are
 *       the keys {@code com.cardemo.config.AwsConfig} binds and that
 *       {@code src/main/resources/application.yml} declares, each resolving an environment variable with no
 *       literal default so that a missing name aborts startup instead of writing a run into whatever bucket
 *       happens to exist.</li>
 *   <li><strong>Images are pinned, never floating</strong>, in the harness: PostgreSQL 16 by content digest
 *       and LocalStack by exact release. A mutable tag would let this source select different software
 *       tomorrow with nothing in the build changing.</li>
 *   <li><strong>Time is pinned.</strong> The harness publishes a fixed clock as the context's primary
 *       {@code java.time.Clock}, and generation prefixes derive from that fixed instant, so nothing here
 *       reads a wall clock and no assertion depends on the day it ran.</li>
 *   <li><strong>Provisioning is self-contained.</strong> {@code localstack-init/init-aws.sh} provisions the
 *       developer compose topology and is <strong>not a precondition for this tier</strong>; the harness
 *       provisions its own resources into its own container, and this class provisions its own buckets on top
 *       of that. That script is owned elsewhere and is neither created nor modified from here.</li>
 * </ul>
 *
 * <p><strong>Retention is documented, not enforced, and it is the one legacy inconsistency actually
 * resolved.</strong> {@code app/jcl/DEFGDGB.jcl:37-39} declares {@code LIMIT(5)} with {@code SCRATCH} for
 * {@code AWS.M2.CARDDEMO.TRANREPT}, while {@code app/jcl/REPTFILE.jcl:25-28} declares
 * {@code LIMIT(10)} with <em>no</em> {@code SCRATCH} for the very same base. Both were read verbatim. A
 * single object-store lifecycle value has to be chosen, so it is resolved <strong>to 10</strong> - the
 * larger, so nothing the legacy system would have kept is discarded - and the resolution is carried in
 * configuration as {@code carddemo.aws.s3.gdg-retention-generations}, which this class asserts. Severity
 * <strong>Medium</strong>. Remediation: none outstanding; the value is chosen, recorded and now tested.
 * <strong>No S3 lifecycle rule is asserted to be configured</strong>, because retention became documentation
 * rather than enforcement; what is asserted is the versioning mechanism that replaces relative generation
 * references.
 *
 * <p><strong>Every other legacy inconsistency is logged and never fixed</strong>, because repairing one would
 * change the behaviour the parity comparison is measured against. Four were re-read at this checkout and
 * confirmed: the genuinely corrupted {@code STMTFILE} DD continuation at {@code app/jcl/CREASTMT.JCL:90},
 * which carries fragments of two other lines; the 80-versus-100 record-length disagreement for
 * {@code HTMLFILE} between the pre-delete step at {@code app/jcl/CREASTMT.JCL:69} and the execution step at
 * {@code :94}; the job-name typo {@code //OEPNFIL} at {@code app/jcl/OPENFIL.jcl:1}, where
 * {@code app/jcl/CLOSEFIL.jcl:1} is spelled correctly - severity <strong>Low</strong>; and
 * {@code app/proc/TRANREPT.prc:21}, whose {@code EXEC PROC=REPROC} names an internal procedure while the
 * member itself resolves as {@code TRANREPT}. <strong>No corrected value is asserted for any of them.</strong>
 * Separately, {@code app/jcl/DEFCUST.jcl:35-38} defines {@code AWS.CUSTDATA.CLUSTER} with
 * {@code KEYS(10 0)} and {@code RECORDSIZE(500 500)}, which no program opens; it is recorded as an orphan and
 * deliberately not modelled.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code Could not find a valid Docker environment}</dt>
 *   <dd>No reachable container runtime or socket. Start one; do not skip, and do not report a pass.</dd>
 *   <dt>A dependency under {@code org.testcontainers} fails to resolve</dt>
 *   <dd>The <strong>Testcontainers 2.0.3 coordinate trap</strong>, severity <strong>Blocker</strong>. Only
 *       the prefixed module coordinates exist at 2.0.3 - {@code testcontainers},
 *       {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}. The bare {@code postgresql}, {@code localstack} and
 *       {@code junit-jupiter} identifiers under that group <strong>do not exist at 2.0.3</strong> and fail
 *       resolution outright. Compounding it, Spring Boot 3.5.11 already imports the Testcontainers bill of
 *       materials at a 1.x version, so importing a second one yields ordering-dependent resolution that may
 *       silently select 1.x. The remedy is two-part and <em>both</em> parts are required: override the
 *       managed version through the {@code testcontainers.version} property rather than importing a second
 *       bill of materials, and use only prefixed coordinates. Overriding without renaming resolves artefacts
 *       that do not exist; renaming without overriding resolves the wrong version. The root build already
 *       does both, verified at this checkout, so this is a standing hazard rather than an open defect - and
 *       the build file is owned elsewhere, so the remediation for a regression is to restore those two
 *       settings there, never to add a dependency from this tier.</dd>
 *   <dt>The build fails on a warning that looks harmless</dt>
 *   <dd>{@code maven-compiler-plugin} 3.14.1 runs at release 25 with {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning}, so one unused import, raw type, unchecked cast or deprecation fails the build.
 *       That is deliberate and must not be relaxed. Coverage is gated by JaCoCo 0.8.12 at 80% of lines with
 *       no exclusions; a prior record citing 0.8.14 is <strong>Medium</strong> drift and the pinned 0.8.12
 *       governs.</dd>
 *   <dt>A bucket will not delete, reporting that it is not empty</dt>
 *   <dd>Measured, not theorised: deleting a versioned bucket that still holds versions is refused with
 *       {@code BucketNotEmpty} and HTTP 409. Emptying the <em>visible</em> objects is not enough, because a
 *       versioned bucket retains a version per overwrite and a delete marker per logical delete, and each has
 *       to be deleted <em>by version identifier</em> - deleting by key alone adds another delete marker
 *       instead of removing anything. {@code deleteBucketAndAllVersions} handles it, and
 *       {@link VersionedBucketDeletion} asserts that it does.</dd>
 *   <dt>Context startup aborts with {@code Could not resolve placeholder} naming the token signing key</dt>
 *   <dd>Severity <strong>High</strong>, and the defect is not in this file.
 *       {@code src/main/resources/application.yml} maps the signing key to an environment variable with no
 *       default, so that no deployment can boot with a key an attacker already knows;
 *       {@code src/main/resources/application-test.yml}, which is owned elsewhere, supplies no test value, so
 *       the refresh aborts before any test runs. Remediation, for the owner of that file: supply a
 *       recognisably non-production test value in the {@code test} profile. Until it does, the harness
 *       registers one itself. <strong>It is reported here and never patched from here</strong>, and no real
 *       key is written anywhere.</dd>
 *   <dt>A listing fails with {@code Could not parse XML response}</dt>
 *   <dd>Measured while writing {@link UntrustedObjectKeys}, and worth knowing before it is mistaken for a
 *       defect in this code. An object key containing a raw C0 control character stores successfully, but the
 *       emulator then emits that byte unescaped into the XML of a subsequent listing and the parser rejects
 *       the document - which would also break the version-listing cleanup and leak the bucket. Severity
 *       <strong>Medium</strong>, and it is an emulator limitation rather than a fault in the code under test.
 *       Remediation, applied here: verify a control-character key with a head request, which carries no XML
 *       body, and remove it by key before any listing runs.</dd>
 *   <dt>A test passes alone and fails in a suite</dt>
 *   <dd>Almost always a shared resource name. Every bucket here is derived from
 *       {@code scopedResourceName}, which is unique per concrete class and identical on every run, and is
 *       created through the harness helpers so that the per-test cleanup removes it.</dd>
 * </dl>
 *
 * <h2>Least privilege, and the risky patterns that are absent</h2>
 *
 * <p>Rule 1 Clause D requires that no secret appear in code, logs, <em>tests</em> or config, and that risky
 * patterns be flagged. <strong>There is no live account, no live credential and no live endpoint on any code
 * path from this class.</strong> No access key, secret key, session token, authorisation header, bearer
 * string or credential-digest prefix appears in it, and neither does the eight-character plaintext credential
 * that the ten seeded users share at {@code app/jcl/DUSRSECJ.jcl:35-44} - that value is deliberately not
 * spelled anywhere in this file, not even to say it is absent, because a scanner cannot tell a citation from
 * a leak. Nothing reads an environment variable or a system property, and nothing sets one.
 *
 * <p>The structural guarantee is worth stating precisely, because the obvious version of it is wrong. It is
 * <em>not</em> that the base and production profiles carry no endpoint override: read at this checkout, all
 * four profiles declare all three service endpoints, and an <strong>absent</strong> override would be
 * strictly worse, because the SDK would then fall through to regional endpoint discovery and address a real
 * account. The guarantee is that the base, {@code test} and {@code prod} profiles indirect the endpoint
 * through an environment variable with <em>no</em> default while only {@code local} carries a loopback
 * default, and that {@code com.cardemo.config.AwsConfig} owns a guard which parses every configured endpoint
 * and aborts the context refresh unless its scheme is http or https, it carries no user information, and its
 * host is on an allow-list of loopback and compose-local names. Because that guard is a bean factory
 * post-processor it runs before any bean exists, so a misconfiguration fails startup instead of escaping.
 * <strong>The plan's claim that base and {@code prod} carry no override is inaccurate: severity
 * Medium.</strong> Remediation: correct the plan text; no code change, and this class asserts the runtime
 * truth instead - that the injected client's resolved endpoint is the container's own.
 *
 * <p>Three named risky patterns are <strong>absent by inspection</strong>. There is no
 * <strong>eval or exec</strong> - no {@code Runtime.exec}, no {@code ProcessBuilder}, no script engine and no
 * reflective arbitrary invocation - which simultaneously discharges the migration invariant that no external
 * sort process is ever spawned. There is no <strong>insecure deserialization</strong> - no
 * {@code ObjectInputStream}, no {@code readObject}, no Jackson polymorphic default typing and no unguarded
 * YAML load. And there is no <strong>shell injection</strong> - no concatenated shell command, and by the
 * same reasoning no concatenated SQL or JPQL, since this class issues no statement at all. It also holds no
 * {@code static} field of any kind, uses no {@code float} or {@code double}, and swallows nothing: the one
 * place an expected service signal is converted to a boolean says so in its own documentation and rethrows
 * everything else.
 *
 * <h2>Information that is Not available</h2>
 *
 * <p>Rule 1 Clause F requires that missing information be stated plainly rather than filled with an
 * invention. Three things are missing here, and each is disclosed with what would close it.
 *
 * <ol>
 *   <li><strong>The boundary-parity expected-output baseline is Not available.</strong> A search across
 *       expected, baseline, golden, system-output and per-dataset name patterns returned only dataset
 *       <em>definition</em> job control and zero captured data, and a byte-size sweep for 430-byte and
 *       133-byte artefacts returned nothing. <em>What is needed:</em> a captured 430-byte {@code DALYREJS}
 *       reject dataset together with the resulting {@code TRANSACT}, {@code ACCTDATA} and {@code TCATBALF}
 *       images from a real {@code POSTTRAN} execution at a known input state. Until those exist <strong>this
 *       class creates no baseline file and fabricates no expected bytes</strong>, and a baseline produced by
 *       running the Java implementation and then asserting against it is circular and forbidden - it would
 *       prove only that the code agrees with itself.</li>
 *   <li><strong>A file-unavailable exercise is Not available.</strong> A census across {@code app/cbl} found
 *       the file status {@code '35'} literal zero times and the corresponding not-open response code zero
 *       times, so the legacy corpus never takes that path and no test for it is invented here.
 *       <em>What is needed:</em> a legacy program that actually handles that status, or a stated requirement
 *       that the Java tier introduce the path as new behaviour.</li>
 *   <li><strong>A source locator for the CICS transaction-identifier field is Not available</strong>,
 *       severity <strong>Medium</strong>. The field occurs nowhere in the repository; the complete
 *       exchange-interface-block census in {@code app/cbl} is the communication-area length field and the
 *       attention-identifier field only. It is supplied by the transaction monitor rather than by this
 *       corpus, so <strong>no {@code app/...} line reference for it may be fabricated</strong>, and the
 *       correlation identifier that replaces it as the thread of request identity is documented as new
 *       capability instead. <em>What is needed:</em> nothing from this repository - the citation belongs to
 *       the vendor's own copybook, which is not part of this corpus.</li>
 * </ol>
 */
@DisplayName("S3 bucket provisioning - seven GDG bases onto three buckets, versioned output, no live AWS")
class S3BucketProvisioningIntegrationTest extends AbstractAwsIntegrationTest {

    /**
     * Property key of the batch input bucket, held so that a failure message can name the key an operator
     * would have to fix rather than only the value that was missing.
     *
     * <p>It repeats the text inside the neighbouring {@code @Value} because an annotation argument has to be
     * a compile-time constant and this class is permitted no {@code static} field to share one. The
     * repetition is not left to trust: {@link LogicalBuckets#everyPropertyKeyAgreesWithTheHarness()} asserts
     * that resolving this key yields exactly the injected value, which converts a possible drift into a
     * checked invariant.
     */
    private final String inputBucketKey = "carddemo.aws.s3.batch-input-bucket";

    /** Property key of the batch output bucket - the versioned one. See {@link #inputBucketKey}. */
    private final String outputBucketKey = "carddemo.aws.s3.batch-output-bucket";

    /** Property key of the statements bucket. See {@link #inputBucketKey}. */
    private final String statementsBucketKey = "carddemo.aws.s3.statements-bucket";

    /** Property key prefix under which the seven generation bases are configured, one entry per base. */
    private final String generationPrefixKeyRoot = "carddemo.aws.s3.gdg-prefixes.";

    /** Property key carrying the resolved retention limit. See the retention conflict in the class notes. */
    private final String retentionKey = "carddemo.aws.s3.gdg-retention-generations";

    /**
     * Property key of the posted-transaction mirror prefix, which is <strong>not</strong> a generation base
     * and must therefore be excluded from the seven.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:26-27} writes posted transactions to
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, an indexed cluster, not to any {@code GDG BASE} entry, so
     * its byte-exact object mirror gets a prefix of its own rather than borrowing a generation namespace it
     * does not belong to.
     */
    private final String transactionPrefixKey = "carddemo.aws.s3.transaction-object-prefix";

    /** Batch input bucket name, read by property key so that no name literal appears in this source. */
    @Value("${carddemo.aws.s3.batch-input-bucket}")
    private String configuredInputBucket;

    /** Batch output bucket name - the only versioned role. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String configuredOutputBucket;

    /** Statements bucket name. */
    @Value("${carddemo.aws.s3.statements-bucket}")
    private String configuredStatementsBucket;

    /** The context's own environment, used to resolve property keys and to read the active profile. */
    @Autowired
    private Environment environment;

    /**
     * Sole constructor, used by the test framework.
     *
     * <p>Declared and empty rather than left implicit, for two reasons. It follows the precedent the harness
     * and every nested group in this file already set, so the whole package reads the same way. And it keeps
     * the class clean under the strictest documentation lint, which reports an undocumented default
     * constructor as a warning - a warning that would become a build failure the moment the doclint gate is
     * widened, since the build compiles with warnings escalated to errors.
     *
     * <p>It stays empty deliberately: every collaborator is injected by the framework after construction, and
     * a constructor that touched an injected field would read it before it was populated.
     */
    S3BucketProvisioningIntegrationTest() {
        // Intentionally empty; all state is injected by the framework or created per test.
    }

    /**
     * One legacy generation data group base, paired with the configuration key that carries its object-store
     * prefix and with the job-control locator that proves it exists.
     *
     * @param legacyBaseName the catalogued dataset name of the generation base
     * @param configKey the configuration key, relative to {@link #generationPrefixKeyRoot}, holding its
     *     object-store key prefix
     * @param locator the repository locator, at anchor 7756d89, that establishes the base
     */
    private record GenerationBase(String legacyBaseName, String configKey, String locator) {
    }

    /**
     * The seven generation bases, in catalogue order, each with the locator that proves it.
     *
     * <p>Ordered deliberately. Rule 1 Clause A asks for determinism, and an assertion that depended on the
     * iteration order of a hash-based set would be reproducible only by luck; the order chosen is the one the
     * catalogue listing itself uses, so a reader can walk the two side by side.
     *
     * @return an immutable list of exactly seven bases, never {@code null}
     */
    private List<GenerationBase> generationBases() {
        return List.of(
                // app/catlg/LISTCAT.txt:684 - the seventh base, and the one defined in its own member.
                new GenerationBase("AWS.M2.CARDDEMO.DALYREJS", "daly-rejs", "app/jcl/DALYREJS.jcl:24-28"),
                // app/catlg/LISTCAT.txt:1098
                new GenerationBase("AWS.M2.CARDDEMO.SYSTRAN", "systran", "app/jcl/DEFGDGB.jcl:49-51"),
                // app/catlg/LISTCAT.txt:1202
                new GenerationBase("AWS.M2.CARDDEMO.TCATBALF.BKUP", "tcatbalf-bkup",
                        "app/jcl/DEFGDGB.jcl:43-45"),
                // app/catlg/LISTCAT.txt:1527 - the base carrying the retention conflict.
                new GenerationBase("AWS.M2.CARDDEMO.TRANREPT", "tranrept",
                        "app/jcl/DEFGDGB.jcl:37-39 and app/jcl/REPTFILE.jcl:25-28"),
                // app/catlg/LISTCAT.txt:1631
                new GenerationBase("AWS.M2.CARDDEMO.TRANSACT.BKUP", "transact-bkup",
                        "app/jcl/DEFGDGB.jcl:25-27"),
                // app/catlg/LISTCAT.txt:2919
                new GenerationBase("AWS.M2.CARDDEMO.TRANSACT.COMBINED", "transact-combined",
                        "app/jcl/DEFGDGB.jcl:55-57"),
                // app/catlg/LISTCAT.txt:3021
                new GenerationBase("AWS.M2.CARDDEMO.TRANSACT.DALY", "transact-daly",
                        "app/jcl/DEFGDGB.jcl:31-33"));
    }

    /**
     * The endpoint of the emulator container this tier runs against.
     *
     * <p>Read from the container object rather than written down. That is the whole mechanism by which this
     * source contains no address: the port is assigned when the container starts and cannot be known in
     * advance, so a literal would be wrong as well as forbidden.
     *
     * @return the container's service endpoint, never {@code null}
     */
    private URI containerEndpoint() {
        return LOCALSTACK.getEndpoint();
    }

    /**
     * Answers whether a bucket exists.
     *
     * <p><strong>Error modes.</strong> A no-such-bucket response is the service's way of answering "no", so it
     * is translated into {@code false} rather than propagated; that is an expected control path and not a
     * swallowed failure, and the distinction matters because <em>every other</em> failure - a refused
     * endpoint, a credential problem, a transport error - propagates untouched with its cause intact. A head
     * request is used rather than a listing because it answers exactly the question asked and transfers no
     * object metadata, which is the efficiency Rule 1 Clause A asks for.
     *
     * @param bucketName the bucket to probe; must be non-{@code null} and non-blank
     * @return {@code true} when the bucket exists and is reachable, {@code false} when the service reports it
     *     absent
     */
    private boolean bucketExists(final String bucketName) {
        try {
            s3Client().headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            return true;
        } catch (final NoSuchBucketException absent) {
            // The documented negative answer, not an error: a head request on a missing bucket is how the
            // service says "no". Nothing is hidden - the exception carries no information this method has
            // not already conveyed, and any other failure is not caught here at all.
            return false;
        }
    }

    /**
     * Reads a bucket's object-versioning status.
     *
     * <p>A bucket on which versioning has never been configured reports no status at all rather than an
     * explicit "off", which is why the result is an {@link Optional} instead of a
     * {@link BucketVersioningStatus} with a third constant invented for the purpose. That was verified
     * against the running emulator, not assumed.
     *
     * <p><strong>Side effects.</strong> None; this reads.
     *
     * @param bucketName the bucket to inspect; must be non-{@code null} and non-blank
     * @return the configured status, or empty when versioning has never been configured on the bucket
     */
    private Optional<BucketVersioningStatus> versioningStatusOf(final String bucketName) {
        return Optional.ofNullable(s3Client()
                .getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucketName).build())
                .status());
    }

    /**
     * Writes a small text object and returns the version identifier the service assigned it.
     *
     * <p>The payload is encoded with an explicit charset rather than the platform default, because a default
     * makes the stored bytes depend on the host and Rule 1 Clause A asks for determinism.
     *
     * <p><strong>Side effects.</strong> Creates or overwrites an object in the emulator container. On a
     * versioned bucket an overwrite adds a version rather than replacing content.
     *
     * @param bucketName the target bucket; must exist
     * @param objectKey the object key, used exactly as supplied and never normalised
     * @param payload the object content
     * @return the assigned version identifier, or {@code null} on a bucket without versioning
     */
    private String putText(final String bucketName, final String objectKey, final String payload) {
        return s3Client().putObject(
                        PutObjectRequest.builder().bucket(bucketName).key(objectKey).build(),
                        RequestBody.fromString(payload, StandardCharsets.UTF_8))
                .versionId();
    }

    /**
     * Reads back the current content of an object as text.
     *
     * @param bucketName the bucket holding it
     * @param objectKey the object key
     * @return the object's current content, decoded with the same explicit charset it was written with
     */
    private String currentText(final String bucketName, final String objectKey) {
        return s3Client()
                .getObjectAsBytes(GetObjectRequest.builder().bucket(bucketName).key(objectKey).build())
                .asString(StandardCharsets.UTF_8);
    }

    /**
     * Lists every object version in a bucket, following pagination to the end.
     *
     * <p>The pagination is explicit rather than trusted. A truncated first page quietly ignored is exactly how
     * this kind of assertion appears to work and then fails once a bucket holds more entries than one page
     * returns.
     *
     * @param bucketName the bucket to list
     * @return every version entry in the bucket, in the order the service returned the pages
     */
    private List<ObjectVersion> allVersions(final String bucketName) {
        final List<ObjectVersion> versions = new ArrayList<>();
        String keyMarker = null;
        String versionIdMarker = null;
        boolean morePages = true;
        while (morePages) {
            final ListObjectVersionsRequest.Builder request =
                    ListObjectVersionsRequest.builder().bucket(bucketName);
            if (keyMarker != null) {
                request.keyMarker(keyMarker);
            }
            if (versionIdMarker != null) {
                request.versionIdMarker(versionIdMarker);
            }
            final ListObjectVersionsResponse page = s3Client().listObjectVersions(request.build());
            versions.addAll(page.versions());
            morePages = Boolean.TRUE.equals(page.isTruncated());
            keyMarker = page.nextKeyMarker();
            versionIdMarker = page.nextVersionIdMarker();
        }
        return List.copyOf(versions);
    }

    /**
     * The three logical buckets that replaced the seven generation bases: that they are configured at all,
     * that they are configured coherently, and that they are actually present in the emulator.
     *
     * <p>These three are provisioned by the harness before the context refreshes and are shared by every
     * class in this package, so they are <strong>inspected and never mutated here</strong> - not created, not
     * written to and above all not registered for cleanup. Registering one would hand the application's own
     * bucket to the per-test teardown, which would delete it and break every later class running against the
     * cached context. The create-and-clean lifecycle is exercised instead on buckets this class owns
     * outright; see {@link GenerationVersioning}.
     */
    @Nested
    @DisplayName("the three logical buckets replacing the seven GDG bases")
    class LogicalBuckets {

        /** Sole constructor, used by the test framework. */
        LogicalBuckets() {
            // Intentionally empty; this group holds no state of its own.
        }

        /**
         * A blank or duplicated bucket name is a real misconfiguration, so it is refused explicitly.
         *
         * <p>Rule 1 Clause B asks for null and empty cases to be handled rather than assumed away. Both
         * failure shapes here are silent in production: a blank name makes every write target a name the
         * service will reject far from the cause, and two roles sharing one name interleaves 430-byte rejects
         * with 100-byte statement markup in a single namespace. Each message names the property key an
         * operator would have to fix, not merely the value that was wrong.
         */
        @Test
        @DisplayName("all three names are present, non-blank and distinct from one another")
        void allThreeNamesArePresentNonBlankAndDistinct() {
            assertThat(configuredInputBucket)
                    .as("The batch input bucket name resolved from '" + inputBucketKey + "' is blank. That "
                            + "key resolves an environment variable with no literal default, precisely so "
                            + "that a missing name aborts startup instead of writing a run into whatever "
                            + "bucket happens to exist. Supply the variable rather than adding a default.")
                    .isNotBlank();
            assertThat(configuredOutputBucket)
                    .as("The batch output bucket name resolved from '" + outputBucketKey + "' is blank. This "
                            + "is the versioned role, so a blank value loses the mechanism that replaces "
                            + "relative generation references entirely.")
                    .isNotBlank();
            assertThat(configuredStatementsBucket)
                    .as("The statements bucket name resolved from '" + statementsBucketKey + "' is blank.")
                    .isNotBlank();

            assertThat(List.of(configuredInputBucket, configuredOutputBucket, configuredStatementsBucket))
                    .as("The three bucket roles must be three distinct names. They carry different record "
                            + "geometries and different retention semantics - 350-byte staging input, "
                            + "430-byte rejects and 133-byte reports under generation prefixes, and 80- and "
                            + "100-byte statement lines - so collapsing two roles onto one name mixes "
                            + "unrelated outputs in a single namespace. Keys checked: '" + inputBucketKey
                            + "', '" + outputBucketKey + "', '" + statementsBucketKey + "'.")
                    .doesNotHaveDuplicates();
        }

        /**
         * The property key spelled in this class is the key the application actually binds.
         *
         * <p>This is the assertion that makes the deliberate repetition between each {@code @Value} argument
         * and its neighbouring key field safe. An annotation argument must be a compile-time constant and
         * this class is permitted no {@code static} field to share one, so the text necessarily appears
         * twice; resolving the key field and comparing it with the injected value turns that from a drift
         * risk into a checked invariant. It also catches the genuine hazard of an invented spelling: the plan
         * names the environment variables behind two of these keys as {@code CARDDEMO_S3_INPUT_BUCKET} and
         * {@code CARDDEMO_S3_OUTPUT_BUCKET}, whereas the repository uses
         * {@code CARDDEMO_S3_BATCH_INPUT_BUCKET} and {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET}. Severity
         * <strong>Medium</strong>; the keys asserted here are the ones
         * {@code com.cardemo.config.AwsConfig} binds, which is what governs. Remediation: correct the plan
         * text; no code change.
         */
        @Test
        @DisplayName("every property key resolves to the value the harness exposes")
        void everyPropertyKeyAgreesWithTheHarness() {
            assertThat(environment.getProperty(inputBucketKey))
                    .as("Property '" + inputBucketKey + "' must resolve to the same value the harness "
                            + "exposes. A mismatch means this class and the application are reading two "
                            + "different keys, so every assertion below would be about the wrong bucket.")
                    .isEqualTo(configuredInputBucket)
                    .isEqualTo(batchInputBucket());
            assertThat(environment.getProperty(outputBucketKey))
                    .as("Property '" + outputBucketKey + "' must resolve to the same value the harness "
                            + "exposes.")
                    .isEqualTo(configuredOutputBucket)
                    .isEqualTo(batchOutputBucket());
            assertThat(environment.getProperty(statementsBucketKey))
                    .as("Property '" + statementsBucketKey + "' must resolve to the same value the harness "
                            + "exposes.")
                    .isEqualTo(configuredStatementsBucket)
                    .isEqualTo(statementsBucket());
        }

        /**
         * All three buckets exist, so the seven bases have somewhere to land.
         *
         * <p>Provisioning is idempotent by design, following the legacy precedent directly: every one of the
         * six definitions in {@code app/jcl/DEFGDGB.jcl} is followed by
         * {@code IF LASTCC=12 THEN SET MAXCC=0} at {@code :29} and its five counterparts, which accepts an
         * already-existing base as success rather than failing the step. The same rule is applied here, which
         * is why repeated runs converge instead of tripping over their own previous state.
         */
        @Test
        @DisplayName("all three buckets exist, one per output family and no more")
        void allThreeBucketsExist() {
            assertThat(bucketExists(configuredInputBucket))
                    .as("The batch input bucket named by '" + inputBucketKey + "' does not exist. It stands "
                            + "in for the daily transaction staging dataset AWS.M2.CARDDEMO.DALYTRAN.PS of "
                            + "app/jcl/POSTTRAN.jcl:30-31, seeded from app/data/ASCII/dailytran.txt - note "
                            + "the fixture spells the word in full, unlike the dataset name.")
                    .isTrue();
            assertThat(bucketExists(configuredOutputBucket))
                    .as("The batch output bucket named by '" + outputBucketKey + "' does not exist. It "
                            + "carries the 430-byte rejects of app/jcl/POSTTRAN.jcl:34-38 and the 133-byte "
                            + "report of app/proc/TRANREPT.prc:76 under generation prefixes.")
                    .isTrue();
            assertThat(bucketExists(configuredStatementsBucket))
                    .as("The statements bucket named by '" + statementsBucketKey + "' does not exist. It "
                            + "carries the 80-byte text stream of app/jcl/CREASTMT.JCL:89 and the 100-byte "
                            + "markup stream of :94.")
                    .isTrue();
        }

        /**
         * Only the output bucket is versioned, and that asymmetry is the point.
         *
         * <p>Object versioning is what a relative generation reference becomes, so it belongs on the one
         * bucket the seven generation bases map onto and nowhere else. The two negative assertions are the
         * ones that would otherwise go unmade: versioning the input bucket would retain every re-seed of the
         * staging dataset, and versioning the statements bucket would retain copies the legacy system
         * explicitly discarded, since {@code app/jcl/CREASTMT.JCL} STEP030 pre-deletes both statement
         * outputs before STEP040 rewrites them. A bucket on which versioning has never been configured
         * reports no status at all rather than an explicit "off", which was verified against the running
         * emulator rather than assumed.
         */
        @Test
        @DisplayName("the output bucket alone is versioned; input and statements are not")
        void theOutputBucketAloneIsVersioned() {
            assertThat(versioningStatusOf(configuredOutputBucket))
                    .as("The batch output bucket named by '" + outputBucketKey + "' must have object "
                            + "versioning enabled. Versioning is the mechanism that replaces a relative "
                            + "generation reference: a next-generation write becomes a new object under a "
                            + "greater key and the content it replaced is retained beneath. Without it the "
                            + "seven bases of app/jcl/DEFGDGB.jcl:25-57 and app/jcl/DALYREJS.jcl:24-28 lose "
                            + "their history.")
                    .contains(BucketVersioningStatus.ENABLED);

            assertThat(versioningStatusOf(configuredInputBucket))
                    .as("The batch input bucket named by '" + inputBucketKey + "' must NOT be versioned. It "
                            + "stands in for a sequential staging dataset, which had no generations, so "
                            + "versioning it would retain every re-seed of app/data/ASCII/dailytran.txt "
                            + "indefinitely.")
                    .isEmpty();
            assertThat(versioningStatusOf(configuredStatementsBucket))
                    .as("The statements bucket named by '" + statementsBucketKey + "' must NOT be versioned. "
                            + "app/jcl/CREASTMT.JCL STEP030 pre-deletes both statement outputs and STEP040 "
                            + "rewrites them under a deterministic account-and-month prefix, so retaining "
                            + "prior copies would keep history the legacy system never had.")
                    .isEmpty();
        }
    }

    /**
     * The versioning mechanism itself, proven on a bucket this class creates and cleans rather than on the
     * shared one.
     *
     * <p>Object versioning is the substitute for a relative generation reference, so it has to be
     * demonstrated rather than assumed: writing the same key twice must leave two retrievable versions with
     * the second current, which is precisely the behaviour a {@code (+1)} write followed by a {@code (0)} read
     * relied on. Every bucket here comes from {@code scopedResourceName}, which is unique per concrete class
     * and identical on every run, and is created through the harness helpers so that the per-test teardown
     * removes it - self-provisioned and deterministically self-cleaned, so a repeated run converges exactly as
     * {@code IF LASTCC=12 THEN SET MAXCC=0} made a repeated {@code app/jcl/DEFGDGB.jcl} run converge.
     */
    @Nested
    @DisplayName("object versioning as the substitute for a relative generation reference")
    class GenerationVersioning {

        /** Sole constructor, used by the test framework. */
        GenerationVersioning() {
            // Intentionally empty; this group holds no state of its own.
        }

        /**
         * Two writes of one key leave two distinct versions, and the second is the current one.
         *
         * <p>This is the whole substitution in one assertion. A {@code (0)} read resolved the newest
         * generation and the older ones stayed addressable underneath; here the newest write is what a plain
         * read returns, while the version it replaced is still listed. Both halves matter: without the first,
         * a current-generation read would return stale content, and without the second, an overwrite would
         * destroy a generation the retention limit promised to keep.
         */
        @Test
        @DisplayName("writing one key twice yields two versions, the second current")
        void writingOneKeyTwiceYieldsTwoVersionsWithTheSecondCurrent() {
            final String bucket = createVersionedBucket(scopedResourceName("generations"));
            final String objectKey = generationPrefix(
                    environment.getProperty(generationPrefixKeyRoot + "tranrept"), 1) + "report";

            final String firstVersion = putText(bucket, objectKey, "FIRST-GENERATION");
            final String secondVersion = putText(bucket, objectKey, "SECOND-GENERATION");

            assertThat(firstVersion)
                    .as("The first write to a versioned bucket must be assigned a version identifier. A null "
                            + "identifier means versioning was not actually in force, so nothing below would "
                            + "be testing the generation substitute at all.")
                    .isNotBlank();
            assertThat(secondVersion)
                    .as("The second write must be assigned its own version identifier, distinct from the "
                            + "first. Equal identifiers would mean the write replaced content in place, which "
                            + "is what a generation data group did not do.")
                    .isNotBlank()
                    .isNotEqualTo(firstVersion);

            assertThat(allVersions(bucket))
                    .as("Both writes must survive as separate versions under the one key, because a "
                            + "generation the retention limit promised to keep must remain addressable after "
                            + "the next generation is written.")
                    .hasSize(2)
                    .allSatisfy(version -> assertThat(version.key()).isEqualTo(objectKey))
                    .extracting(ObjectVersion::versionId)
                    .containsExactlyInAnyOrder(firstVersion, secondVersion);

            assertThat(allVersions(bucket))
                    .filteredOn(version -> Boolean.TRUE.equals(version.isLatest()))
                    .as("Exactly one version must be flagged current, and it must be the second write - that "
                            + "flag is what a current-generation (0) reference resolves to.")
                    .hasSize(1)
                    .extracting(ObjectVersion::versionId)
                    .containsExactly(secondVersion);

            assertThat(currentText(bucket, objectKey))
                    .as("A plain read of the key must return the newest generation's content, which is the "
                            + "behaviour a (0) reference had.")
                    .isEqualTo("SECOND-GENERATION");
        }

        /**
         * Each of the seven generation prefixes is its own namespace inside the one bucket.
         *
         * <p>This is the executable half of the seven-to-three collapse. The seven bases do not need seven
         * buckets because separation comes from the key prefix, but that is only true if the prefixes really
         * do not overlap - so a generation is written under every one of the seven and each is then resolved
         * back through its own prefix alone. If two prefixes shared a namespace, one base's current
         * generation would resolve to another base's object, which is the failure the extra buckets would
         * otherwise have been protecting against.
         *
         * <p>The prefixes are read from configuration rather than restated, and they are walked in catalogue
         * order rather than in the iteration order of a hash-based collection, so the outcome does not depend
         * on anything unspecified.
         */
        @Test
        @DisplayName("all seven generation prefixes coexist in one bucket without colliding")
        void allSevenGenerationPrefixesCoexistInOneBucketWithoutColliding() {
            final String bucket = createVersionedBucket(scopedResourceName("namespaces"));

            for (final GenerationBase base : generationBases()) {
                final String configuredPrefix =
                        environment.getProperty(generationPrefixKeyRoot + base.configKey());
                assertThat(configuredPrefix)
                        .as("Generation base " + base.legacyBaseName() + ", established at "
                                + base.locator() + " @ 7756d89, has no object-store prefix configured under '"
                                + generationPrefixKeyRoot + base.configKey() + "'. A base without a prefix "
                                + "has no namespace of its own, so its output would land in another base's.")
                        .isNotBlank();

                final String objectKey = generationPrefix(configuredPrefix, 1) + "generation";
                putText(bucket, objectKey, base.legacyBaseName());

                assertThat(currentGenerationKey(bucket, configuredPrefix))
                        .as("The current generation of " + base.legacyBaseName() + " must resolve, under its "
                                + "own prefix '" + configuredPrefix + "' and no other, to the object just "
                                + "written for it. Anything else means two of the seven prefixes share a "
                                + "namespace, which is the collision the seven-onto-three collapse must not "
                                + "introduce.")
                        .contains(objectKey);
            }

            assertThat(allVersions(bucket))
                    .as("All seven generation bases of app/jcl/DEFGDGB.jcl:25-57 and "
                            + "app/jcl/DALYREJS.jcl:24-28 must coexist in ONE bucket, which is what makes the "
                            + "collapse from seven generation data groups to three buckets sound. "
                            + "app/catlg/LISTCAT.txt:3942 reports GDG 7.")
                    .hasSize(generationBases().size())
                    .extracting(ObjectVersion::key)
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * The boundary condition a versioned bucket creates: it cannot be removed while any version or delete
     * marker remains.
     *
     * <p>Rule 1 Clause B asks for boundary conditions to be handled explicitly rather than hoped away, and
     * this is the one that bites. Enabling versioning to stand in for generation retention means every
     * overwrite leaves a version behind and every logical delete leaves a delete marker, and the service
     * refuses to remove the bucket while either is present. Emptying the visible objects is not enough, and
     * deleting by key alone makes it worse by adding another marker. The two assertions here are the refusal
     * itself and the fact that the harness's own teardown copes with it - which is what allows this class to
     * pass when run twice in succession rather than leaking a bucket on the first run.
     */
    @Nested
    @DisplayName("removing a versioned bucket that still holds versions")
    class VersionedBucketDeletion {

        /** Sole constructor, used by the test framework. */
        VersionedBucketDeletion() {
            // Intentionally empty; this group holds no state of its own.
        }

        /**
         * A plain bucket delete is refused while versions remain, and the version-aware delete succeeds.
         *
         * <p>The refusal is asserted rather than merely described, because it is the reason the teardown has
         * to be version-aware at all; asserting only the success would leave the reader unable to tell whether
         * the extra work was necessary. The failure is checked by <em>type, service error code and HTTP
         * status</em>, and by the fact that it arrives as the service's own exception with its causal chain
         * intact rather than as an opaque wrapper - Rule 1 Clause B forbids swallowing a failure or losing its
         * root cause, and an assertion on the type alone would not detect either.
         *
         * <p>A delete marker is created deliberately as well as versions, because the two are listed
         * separately and a teardown that removed only versions would leave the marker and still fail.
         */
        @Test
        @DisplayName("a plain delete is refused, and the version-aware delete removes everything")
        void aPlainDeleteIsRefusedAndTheVersionAwareDeleteRemovesEverything() {
            final String bucket = createVersionedBucket(scopedResourceName("cleanup"));
            final String objectKey = generationPrefix(
                    environment.getProperty(generationPrefixKeyRoot + "daly-rejs"), 1) + "rejects";

            putText(bucket, objectKey, "FIRST-GENERATION");
            putText(bucket, objectKey, "SECOND-GENERATION");
            // A logical delete on a versioned bucket removes nothing; it adds a delete marker, which is
            // listed separately from the versions and blocks bucket removal just as effectively.
            s3Client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());

            final S3Exception refusal = catchThrowableOfType(S3Exception.class,
                    () -> s3Client().deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build()));

            assertThat(refusal)
                    .as("Removing a versioned bucket that still holds versions and a delete marker must be "
                            + "refused. If it were permitted, a teardown that only deleted the visible "
                            + "objects would appear to work, and this boundary would be discovered instead by "
                            + "a later run failing on a bucket that was never cleaned up.")
                    .isNotNull();
            assertThat(refusal.awsErrorDetails().errorCode())
                    .as("The refusal must be the service's specific not-empty error, so that a teardown can "
                            + "tell it apart from a permission or addressing failure and respond correctly.")
                    .isEqualTo("BucketNotEmpty");
            assertThat(refusal.statusCode())
                    .as("The refusal must carry the conflict status, which is the machine-readable half of "
                            + "the same fact.")
                    .isEqualTo(409);
            assertThat(refusal.getMessage())
                    .as("The failure must explain itself. Rule 1 Clause B forbids swallowing a failure or "
                            + "discarding its context, so an empty message is a defect even when the type is "
                            + "right.")
                    .isNotBlank();
            assertThat(refusal.getCause())
                    .as("The failure must arrive as the service's own exception with its causal chain intact, "
                            + "not re-wrapped around some other error. A service-reported refusal IS the root "
                            + "cause, so the chain terminates here - and asserting that explicitly is what "
                            + "distinguishes a preserved root cause from a discarded one.")
                    .isNull();

            deleteBucketAndAllVersions(bucket);

            assertThat(bucketExists(bucket))
                    .as("The version-aware delete must remove every version, every delete marker and then "
                            + "the bucket itself, so that this class passes when run twice in succession "
                            + "rather than leaking a bucket on the first run.")
                    .isFalse();

            // Deliberately repeated. The bucket is still on the harness's per-test ledger, so the teardown
            // will call this again after the method returns; proving the second call converges here is what
            // makes that harmless rather than a failure hidden in a teardown log.
            deleteBucketAndAllVersions(bucket);

            assertThat(bucketExists(bucket))
                    .as("Removing an already-absent bucket must converge rather than fail - the object-store "
                            + "equivalent of IF LASTCC=12 THEN SET MAXCC=0 at app/jcl/DEFGDGB.jcl:29 - "
                            + "because the harness teardown will attempt this same removal again.")
                    .isFalse();
        }
    }

    /**
     * The accounting: exactly seven generation bases, each with its own prefix, and the retention conflict
     * resolved to a single value.
     *
     * <p>Counting is the point of this group. An earlier revision of the plan recorded six bases, and six is
     * wrong in a way that no compiler and no ordinary test would notice: the seventh is defined in a member of
     * its own, so a reader who opens only {@code app/jcl/DEFGDGB.jcl} undercounts, and the consequence is a
     * missing output prefix rather than an error. The count is therefore asserted against the catalogue's own
     * total, and the resolved retention value is asserted alongside it, because both are corrections carried
     * by this migration rather than properties inherited from it.
     */
    @Nested
    @DisplayName("seven generation bases, and the resolved TRANREPT retention conflict")
    class GenerationBaseAccounting {

        /** Sole constructor, used by the test framework. */
        GenerationBaseAccounting() {
            // Intentionally empty; this group holds no state of its own.
        }

        /**
         * There are seven generation bases, not six, and each has a distinct configured prefix.
         *
         * <p>Six plus one is seven: {@code app/jcl/DEFGDGB.jcl:25-57} carries six and
         * {@code app/jcl/DALYREJS.jcl:24-28} carries the seventh in a member of its own. The catalogue is the
         * independent witness, reporting {@code GDG 7} at {@code app/catlg/LISTCAT.txt:3942} within the totals
         * block at {@code :3937-3951}, and listing exactly seven {@code GDG BASE} entries. Distinctness is
         * asserted as well as the count, because seven bases sharing six prefixes would satisfy a count alone
         * while still collapsing two namespaces into one.
         */
        @Test
        @DisplayName("exactly seven bases are catalogued, each with its own configured prefix")
        void exactlySevenBasesAreCataloguedEachWithItsOwnPrefix() {
            final List<GenerationBase> bases = generationBases();

            assertThat(bases)
                    .as("There must be exactly SEVEN generation data group bases. app/jcl/DEFGDGB.jcl:25-57 "
                            + "defines six, all LIMIT(5) SCRATCH, and app/jcl/DALYREJS.jcl:24-28 defines the "
                            + "seventh in a member of its own - which is why a reader who opens only the "
                            + "obviously named definition job undercounts. The catalogue is the independent "
                            + "witness: app/catlg/LISTCAT.txt:3942 reports 'GDG 7' inside the totals block at "
                            + ":3937-3951, and the listing carries seven GDG BASE entries at L684, L1098, "
                            + "L1202, L1527, L1631, L2919 and L3021. A missing seventh is a missing S3 output "
                            + "prefix, so one batch stream would write into another's namespace.")
                    .hasSize(7);

            assertThat(bases)
                    .as("Each of the seven bases must name a distinct catalogued dataset, matching the seven "
                            + "GDG BASE entries of app/catlg/LISTCAT.txt one for one.")
                    .extracting(GenerationBase::legacyBaseName)
                    .doesNotHaveDuplicates();

            final List<String> configuredPrefixes = new ArrayList<>();
            for (final GenerationBase base : bases) {
                final String prefix = environment.getProperty(generationPrefixKeyRoot + base.configKey());
                assertThat(prefix)
                        .as("Base " + base.legacyBaseName() + " (" + base.locator() + " @ 7756d89) must have "
                                + "a prefix configured under '" + generationPrefixKeyRoot + base.configKey()
                                + "'.")
                        .isNotBlank();
                configuredPrefixes.add(prefix);
            }

            assertThat(configuredPrefixes)
                    .as("The seven bases must map onto seven DISTINCT key prefixes. Separation inside a single "
                            + "bucket comes from the prefix, so two bases sharing one prefix would merge two "
                            + "generation histories - which a count of seven on its own would not detect.")
                    .hasSize(7)
                    .doesNotHaveDuplicates();

            assertThat(configuredPrefixes)
                    .as("None of the seven generation prefixes may be the posted-transaction mirror prefix "
                            + "configured at '" + transactionPrefixKey + "'. app/jcl/POSTTRAN.jcl:26-27 writes "
                            + "posted transactions to AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS, an INDEXED cluster, "
                            + "not to any GDG BASE entry, so that mirror is deliberately NOT one of the seven "
                            + "and borrows no generation namespace.")
                    .doesNotContain(environment.getProperty(transactionPrefixKey));
        }

        /**
         * The seven bases collapse onto exactly three buckets, and every generation lands in the output one.
         *
         * <p>Stated as a bucket count rather than as prose so that it cannot quietly drift: seven bases, three
         * buckets, and the generation-bearing role is a single one of the three. The input and statements roles
         * carry no generation semantics at all, which is the same fact their absence of versioning expresses
         * from the other direction.
         */
        @Test
        @DisplayName("seven bases collapse onto three buckets, generations on the output role alone")
        void sevenBasesCollapseOntoThreeBuckets() {
            final List<String> logicalBuckets =
                    List.of(configuredInputBucket, configuredOutputBucket, configuredStatementsBucket);

            assertThat(logicalBuckets)
                    .as("The seven generation bases must collapse onto exactly THREE logical buckets, not "
                            + "seven. A bucket is not the unit of separation here - a key prefix is - so "
                            + "provisioning one bucket per base would add six things to provision and no "
                            + "isolation that the prefixes do not already provide.")
                    .hasSize(3)
                    .doesNotHaveDuplicates()
                    .allSatisfy(bucket -> assertThat(bucketExists(bucket)).isTrue());

            assertThat(logicalBuckets)
                    .as("Generation semantics must belong to exactly one of the three roles, so exactly one "
                            + "bucket may be versioned. app/jcl/CREASTMT.JCL STEP030 pre-deletes the statement "
                            + "outputs and the staging input had no generations at all, so versioning either "
                            + "would retain history the legacy system did not keep.")
                    .filteredOn(bucket -> versioningStatusOf(bucket)
                            .filter(BucketVersioningStatus.ENABLED::equals)
                            .isPresent())
                    .containsExactly(configuredOutputBucket);
        }

        /**
         * The retention conflict is resolved to ten, and nothing pretends it is enforced.
         *
         * <p>Two members of the frozen corpus disagree about the same generation base, and both were read
         * verbatim at this checkout: {@code app/jcl/DEFGDGB.jcl:37-39} declares
         * {@code LIMIT(5)} with {@code SCRATCH} for {@code AWS.M2.CARDDEMO.TRANREPT}, while
         * {@code app/jcl/REPTFILE.jcl:25-28} declares {@code LIMIT(10)} with no {@code SCRATCH} for that same
         * base. This is <strong>the only legacy inconsistency the migration actually resolves</strong> rather
         * than logs, and only because a single object-store lifecycle value has to be chosen; ten is taken as
         * the larger, so nothing the legacy system would have kept is discarded. Severity
         * <strong>Medium</strong>.
         *
         * <p>What is <em>not</em> asserted is equally deliberate: no S3 lifecycle rule is asserted to be
         * configured. Retention became documented intent rather than enforcement, so asserting an enforced
         * rule would assert something the migration explicitly chose not to build. The versioning mechanism is
         * what is proven, in {@link GenerationVersioning}; the number is proven here.
         */
        @Test
        @DisplayName("the TRANREPT retention conflict is resolved to 10, and documented not enforced")
        void theRetentionConflictIsResolvedToTen() {
            assertThat(environment.getProperty(retentionKey, Integer.class))
                    .as("The retention limit at '" + retentionKey + "' must be 10. app/jcl/DEFGDGB.jcl:37-39 "
                            + "declares LIMIT(5) with SCRATCH for AWS.M2.CARDDEMO.TRANREPT while "
                            + "app/jcl/REPTFILE.jcl:25-28 declares LIMIT(10) with no SCRATCH for the SAME "
                            + "base. A single lifecycle value has to be chosen, so the larger is taken and "
                            + "nothing the legacy system would have kept is discarded. Severity Medium. This "
                            + "is the ONLY legacy inconsistency resolved rather than logged: the corrupted "
                            + "STMTFILE continuation at app/jcl/CREASTMT.JCL:90, the 80-versus-100 HTMLFILE "
                            + "mismatch between :69 and :94, the //OEPNFIL job-name typo at "
                            + "app/jcl/OPENFIL.jcl:1 and the EXEC PROC=REPROC naming at "
                            + "app/proc/TRANREPT.prc:21 are all logged and never fixed.")
                    .isEqualTo(10);
        }
    }

    /**
     * Least privilege: the injected client addresses the emulator container and nothing else, and the profile
     * that permits that is the only one active.
     *
     * <p>Rule 1 Clause D asks for least privilege over tokens, credentials and configuration, and for no
     * secret in code, logs, tests or config. The assertions here are the runtime half of that. They compare
     * the client's resolved endpoint against the value the container object reports - never against a written
     * host, because a written host would be both forbidden and wrong, the port being assigned at startup.
     */
    @Nested
    @DisplayName("least privilege - no live AWS endpoint or credential is reachable")
    class LeastPrivilegeBinding {

        /** Sole constructor, used by the test framework. */
        LeastPrivilegeBinding() {
            // Intentionally empty; this group holds no state of its own.
        }

        /**
         * The client the application configured is pointed at the container, not at a real region.
         *
         * <p>The resolved client configuration is inspected rather than the property that fed it, because the
         * property proves only what was requested while the configuration proves what the client will
         * actually do. An <em>absent</em> override is the dangerous case and is asserted against explicitly:
         * with no override the SDK falls through to regional endpoint discovery and addresses a real account,
         * which is why the guarantee cannot be phrased as "no endpoint is configured". A live endpoint on any
         * code path in this tier would be a <strong>Blocker</strong>.
         */
        @Test
        @DisplayName("the injected client's resolved endpoint is the container's own")
        void theInjectedClientResolvedEndpointIsTheContainers() {
            final URI expected = containerEndpoint();

            assertThat(s3Client().serviceClientConfiguration().endpointOverride())
                    .as("The object-store client the application configured must address the emulator "
                            + "container this tier started, and nothing else. The comparison is against the "
                            + "value the container object reports, never a written host: the port is assigned "
                            + "when the container starts, so a literal would be forbidden and wrong at once. "
                            + "An ABSENT override is the dangerous case, not the safe one - with no override "
                            + "the SDK falls through to regional endpoint discovery and addresses a real "
                            + "account. A live endpoint reaching a code path here is a Blocker.")
                    .contains(expected);

            assertThat(environment.getProperty("spring.cloud.aws.s3.endpoint"))
                    .as("The resolved endpoint property must agree with the client's configuration. A "
                            + "disagreement would mean some other source outranked the container's registered "
                            + "address, which is exactly how a real endpoint could slip in unnoticed. "
                            + "com.cardemo.config.AwsConfig owns the guard that refuses any host outside its "
                            + "loopback and compose-local allow-list, and because that guard is a bean factory "
                            + "post-processor it runs before any bean exists, so a misconfiguration fails "
                            + "startup rather than escaping.")
                    .isEqualTo(expected.toString());
        }

        /**
         * The {@code test} profile is the only one active.
         *
         * <p>Read from the context's environment rather than from an environment variable or a system
         * property, because the question is which profile the running context resolved, not what the shell
         * intended. It matters here specifically: the emulator binding, the container-supplied credentials and
         * the synthetic signing key are all properties of this profile, so a second profile layered on top
         * could change the endpoint the previous assertion just approved.
         */
        @Test
        @DisplayName("the test profile alone is active")
        void theTestProfileAloneIsActive() {
            assertThat(environment.getActiveProfiles())
                    .as("Exactly one profile, 'test', must be active. It is read from the context's own "
                            + "environment rather than from an environment variable or a system property, "
                            + "because what matters is the profile the running context resolved. A second "
                            + "profile layered on top could redirect the endpoint that this tier has just "
                            + "asserted points at the container.")
                    .containsExactly("test");
        }
    }

    /**
     * Untrusted input at the object-store boundary: a hostile object key must produce a deterministic, safe
     * outcome and must never be quietly rewritten.
     *
     * <p>Rule 1 Clause A asks that inputs be treated as untrusted. An object key is the most exposed input
     * this tier has, because a generation key is assembled from configuration and job parameters, so the two
     * failure modes worth ruling out are a key escaping its bucket and a key being silently sanitised. The
     * second is the more insidious: a store that quietly rewrote a key would make a later read by the original
     * key return nothing, with no error anywhere.
     */
    @Nested
    @DisplayName("untrusted object keys are stored verbatim and never escape the bucket")
    class UntrustedObjectKeys {

        /** Sole constructor, used by the test framework. */
        UntrustedObjectKeys() {
            // Intentionally empty; this group holds no state of its own.
        }

        /**
         * A key carrying traversal sequences and a very long segment is stored exactly as supplied.
         *
         * <p>Traversal is meaningless to an object store - a key is an opaque byte string, not a path - and
         * that is precisely why it has to be demonstrated rather than assumed, since the assumption that
         * {@code ../} is interpreted is what would lead someone to add a sanitiser. Both the raw and the
         * percent-encoded forms are included, along with a segment long enough to exercise the length
         * handling, and the assertion is that the stored key is byte-identical to the supplied one and is the
         * only object in the bucket. Byte-identity is what rules out silent sanitisation; sole occupancy is
         * what rules out an escape.
         */
        @Test
        @DisplayName("traversal sequences and a long segment are stored verbatim, and nothing escapes")
        void traversalAndLongSegmentsAreStoredVerbatim() {
            final String bucket = createBucket(scopedResourceName("hostile-keys"));
            final String hostileKey = "provisioning-probe/../../%2e%2e/"
                    + "a".repeat(400)
                    + "/etc/passwd";

            putText(bucket, hostileKey, "HOSTILE-KEY-PROBE");

            assertThat(allVersions(bucket))
                    .as("A hostile key must be stored EXACTLY as supplied and must be the only object in the "
                            + "bucket. Byte-identity is what rules out silent sanitisation, which is the more "
                            + "insidious of the two failure modes: a quietly rewritten key makes a later read "
                            + "by the original key return nothing, with no error raised anywhere. Sole "
                            + "occupancy is what rules out the key escaping its bucket. Traversal is "
                            + "meaningless to an object store because a key is an opaque byte string rather "
                            + "than a path - which is exactly why it is proven here instead of assumed, since "
                            + "assuming otherwise is what leads to adding a sanitiser that breaks round "
                            + "trips.")
                    .extracting(ObjectVersion::key)
                    .containsExactly(hostileKey);

            assertThat(currentText(bucket, hostileKey))
                    .as("The object must also be retrievable by the original key, which is the round trip a "
                            + "sanitiser would break.")
                    .isEqualTo("HOSTILE-KEY-PROBE");
        }

        /**
         * A key carrying raw control characters is stored verbatim too, and is verified without a listing.
         *
         * <p>The verification deliberately uses a head request rather than a listing, and the reason was
         * measured rather than guessed: such a key stores successfully, but the emulator then emits the raw
         * control byte unescaped into the XML of any subsequent listing and the parser rejects the document.
         * That would also break the version-listing teardown and leak the bucket, so the object is removed by
         * key - which needs no listing either - before this method returns. Severity <strong>Medium</strong>,
         * and it is a limitation of the emulator rather than a fault in the code under test. Remediation is
         * what is done here: head to verify, delete by key, never list while such a key is present.
         *
         * <p>The bucket is left empty, so the harness's version-aware teardown removes it cleanly and the
         * class still passes when run twice in succession.
         */
        @Test
        @DisplayName("a control-character key is stored verbatim and removed without a listing")
        void aControlCharacterKeyIsStoredVerbatimAndRemovedWithoutListing() {
            final String bucket = createBucket(scopedResourceName("control-keys"));
            final String controlCharacterKey = "provisioning-probe/ctl\u0001\u001Fsegment";

            putText(bucket, controlCharacterKey, "CONTROL-CHARACTER-PROBE");

            assertThat(s3Client()
                    .headObject(HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(controlCharacterKey)
                            .build())
                    .contentLength())
                    .as("A key carrying raw control characters must be stored under exactly the key supplied, "
                            + "which a head request confirms without transferring the object. A listing is "
                            + "deliberately NOT used: measured against the running emulator, the raw control "
                            + "byte is emitted unescaped into the XML of a subsequent listing and the parser "
                            + "rejects the document - which would break the version-aware teardown too and "
                            + "leak this bucket. Severity Medium, and it is an emulator limitation rather "
                            + "than a fault in the code under test.")
                    .isEqualTo((long) "CONTROL-CHARACTER-PROBE".length());

            // Removed by key, which needs no listing, so that the harness teardown can list this bucket's
            // versions and remove it cleanly. Leaving the object behind would leak the bucket instead.
            s3Client().deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(controlCharacterKey).build());

            assertThat(allVersions(bucket))
                    .as("With the control-character key removed, the bucket must list cleanly again, which is "
                            + "what lets the version-aware teardown remove it and lets this class pass when "
                            + "run twice in succession.")
                    .isEmpty();
        }

        /**
         * Writing into a bucket that does not exist fails loudly, with the service's own error intact.
         *
         * <p>The null-and-empty boundary of this class's subject: everything above assumes a bucket is there,
         * so the behaviour when one is not has to be pinned down rather than left to be discovered in
         * production. The failure is asserted by <em>type, service error code, HTTP status and message</em>,
         * and by the fact that it is the service's own exception with its causal chain intact rather than an
         * opaque wrapper. Rule 1 Clause B forbids swallowing a failure or discarding its root cause, and an
         * assertion on the type alone would detect neither.
         */
        @Test
        @DisplayName("writing to an absent bucket fails loudly with the service error intact")
        void writingToAnAbsentBucketFailsLoudly() {
            // Derived from the same deterministic naming helper as every other name here, then never
            // created - so it is guaranteed absent, and guaranteed not to collide with another class's.
            final String absentBucket = scopedResourceName("absent");

            final NoSuchBucketException failure = catchThrowableOfType(NoSuchBucketException.class,
                    () -> putText(absentBucket, "provisioning-probe/key", "SHOULD-NOT-BE-WRITTEN"));

            assertThat(failure)
                    .as("Writing into a bucket that does not exist must fail rather than create one. The "
                            + "queue side of this tier configures a fail-on-missing strategy for exactly this "
                            + "reason: silently conjuring a mistyped resource produces one with the wrong "
                            + "attributes and hides the mistake.")
                    .isNotNull();
            assertThat(failure.awsErrorDetails().errorCode())
                    .as("The failure must carry the service's specific no-such-bucket code, so a caller can "
                            + "distinguish an absent bucket from a permission or addressing problem.")
                    .isEqualTo("NoSuchBucket");
            assertThat(failure.statusCode())
                    .as("The failure must carry the not-found status, the machine-readable half of the same "
                            + "fact.")
                    .isEqualTo(404);
            assertThat(failure.getMessage())
                    .as("The failure must explain itself; an empty message discards context Rule 1 Clause B "
                            + "requires be preserved.")
                    .isNotBlank();
            assertThat(failure.getCause())
                    .as("The failure must arrive as the service's own exception rather than re-wrapped around "
                            + "another error, so the reported error IS the root cause and the chain "
                            + "terminates here. Asserting that explicitly is what distinguishes a preserved "
                            + "root cause from a discarded one.")
                    .isNull();

            assertThat(bucketExists(absentBucket))
                    .as("The failed write must not have created the bucket as a side effect, which would "
                            + "leave a resource behind that no teardown knows about.")
                    .isFalse();
        }
    }
}
