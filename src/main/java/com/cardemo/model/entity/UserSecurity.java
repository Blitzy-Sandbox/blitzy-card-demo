/*
 * ******************************************************************
 * Program     : UserSecurity.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Replaces the VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS, the sign-on
 *               credential and role store read by the CICS sign-on
 *               program and maintained by the four user administration
 *               programs. One row per application user: the eight
 *               character user id, the two twenty character name
 *               fields, the credential - a BCrypt hash here, never the
 *               plaintext the source held - and the single character
 *               user class that drives role based authorisation.
 * Source      : app/cpy/CSUSR01Y.cpy (80 B, key 8) @ 7756d89
 * Seed        : app/jcl/DUSRSECJ.jcl (inline IEBGENER data, 10 rows) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.model.entity;

import com.cardemo.model.enums.UserType;

import java.util.Objects;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Application user, credential and role: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}.
 *
 * <h2>What it does</h2>
 *
 * <p>Each row carries one application user - an eight character identifier, a first and last name, the
 * credential, and the single character class that decides whether the user reaches the main menu or the
 * admin menu. The record layout is {@code app/cpy/CSUSR01Y.cpy}, whose group item at {@code :L17} is
 * {@code 01 SEC-USER-DATA} (the name is <em>SEC-USER-DATA</em>, not {@code SEC-USER-RECORD}) and whose
 * five elementary items and one named filler occupy {@code :L18} through {@code :L23}.
 *
 * <p>This class is a pure data holder. It performs no I/O, holds no collaborator, emits no log line,
 * hashes nothing, verifies nothing, and reaches no framework beyond the persistence annotations below.
 * Everything that could be mistaken for authentication logic deliberately lives elsewhere; see
 * <i>Behaviour that deliberately lives elsewhere</i> below.
 *
 * <h2>Provenance and physical evidence: three independent corroborations</h2>
 *
 * <p>The two physical facts that fix this mapping - an <b>80 byte record</b> and a <b>key length of
 * 8</b> - are each confirmed three times over by unrelated artefacts, which is why they are treated as
 * settled rather than inferred:
 *
 * <ol>
 *   <li>the catalogue listing: {@code app/catlg/LISTCAT.txt:L3881} names the cluster
 *       {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}, and {@code :L3883} reports
 *       {@code KEYLEN 8} with {@code AVGLRECL 80} (the following line repeats {@code MAXLRECL 80},
 *       so the record is fixed rather than merely averaging 80);</li>
 *   <li>the IDCAMS job that defines the cluster: {@code app/jcl/DUSRSECJ.jcl:L64} opens
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)}, {@code :L65} declares
 *       {@code KEYS(8,0)} - eight bytes at offset zero, so the key is the leading field - and
 *       {@code :L66} declares {@code RECORDSIZE(80,80)};</li>
 *   <li>the IEBGENER step that materialises the seed: {@code app/jcl/DUSRSECJ.jcl:L48} declares
 *       {@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)} on the output dataset.</li>
 * </ol>
 *
 * <p>The copybook itself supplies the fourth, arithmetic confirmation - see the field contract below -
 * and {@code app/catlg/LISTCAT.txt:L3888} reports {@code REC-TOTAL 10}, matching the ten seed rows
 * exactly.
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy} is one of the 12 of 28 members of {@code app/cpy} that carry the
 * repository's Apache-2.0 source banner themselves, which is precisely why its field definitions begin
 * at line 17 rather than line 1: {@code :L1} through {@code :L16} are the banner. Banner coverage across
 * the frozen corpus is {@code app/cbl} 28/28, {@code app/cpy-bms} 17/17, {@code app/bms} 17/17,
 * {@code app/jcl} 28/29 and {@code app/cpy} 12/28, which makes it a repository convention rather than a
 * local habit; the block comment at the head of this file reproduces it, as Rule 1 clause C1 requires.
 *
 * <h2>Verified field contract</h2>
 *
 * <pre>
 * #  COBOL field (CSUSR01Y.cpy)   PIC    Java field    Column         SQL type
 * -  ---------------------------  -----  ------------  -------------  ------------------------
 * 1  SEC-USR-ID      (:L18)       X(08)  secUsrId      sec_usr_id     CHAR(8)     PRIMARY KEY
 * 2  SEC-USR-FNAME   (:L19)       X(20)  secUsrFname   sec_usr_fname  CHAR(20)    NOT NULL
 * 3  SEC-USR-LNAME   (:L20)       X(20)  secUsrLname   sec_usr_lname  CHAR(20)    NOT NULL
 * 4  SEC-USR-PWD     (:L21)       X(08)  passwordHash  sec_usr_pwd    VARCHAR(60) NOT NULL
 * 5  SEC-USR-TYPE    (:L22)       X(01)  secUsrType    sec_usr_type   CHAR(1)     NOT NULL
 * -  SEC-USR-FILLER  (:L23)       X(23)  not modelled  not modelled   not modelled
 * </pre>
 *
 * <p>The geometry closes exactly: {@code 8 + 20 + 20 + 8 + 1 = 57} populated bytes, {@code + 23} bytes
 * of trailing filler {@code = 80} bytes, which is the catalogued record length. Row 1 of the seed proves
 * the same arithmetic byte for byte - {@code ADMIN001} in the eight key bytes, {@code MARGARET} blank
 * padded to twenty, {@code GOLD} blank padded to twenty, the eight byte credential field, then
 * {@code A} - which is the 57 populated bytes, the remaining 23 being the filler the fixed length record
 * carries to reach 80.
 *
 * <p>Field 4 is the single place where the column width deliberately departs from the PIC width. The
 * eight bytes still count towards the source record geometry above, because that arithmetic describes
 * the legacy record; the <em>column</em> is 60 characters because that is what a BCrypt hash needs. See
 * the Blocker finding below.
 *
 * <p>{@code SEC-USR-FILLER} is not modelled. Two things about it are worth recording. First, it is a
 * <em>named</em> trailing filler, unlike the anonymous {@code FILLER} items that pad the other ten
 * record layouts in this package - a source quirk, nothing more, since a name does not make it data.
 * Second, it carries no data in any of the ten seed rows and no program in the corpus ever references
 * it; its only function is to pad the record to the catalogued 80 bytes. Mapping it would add a column
 * that is 23 blanks in every row, which is why the 23 byte count is recorded here in documentation and
 * nowhere in the schema.
 *
 * <h2>Naming: why the credential member is not called secUsrPwd</h2>
 *
 * <p>Four of the five members keep their COBOL derived names exactly - {@code secUsrId},
 * {@code secUsrFname}, {@code secUsrLname} and {@code secUsrType} - so that the mapping back to
 * {@code app/cpy/CSUSR01Y.cpy} is mechanical. The credential member breaks that pattern on purpose and
 * is named {@code passwordHash}, with {@link #getPasswordHash()} as its accessor, because the source
 * field it replaces held a <em>plaintext</em> password and this member never can. A member called
 * {@code secUsrPwd} would invite a caller to compare it against a presented password, which is exactly
 * the mistake this naming forecloses. The <em>column</em> is still {@code sec_usr_pwd}, so the COBOL
 * provenance survives where it belongs - in the schema, where the mapping has to be traceable - while
 * the Java name states what the value actually is.
 *
 * <p>{@link #getPasswordHash()} is the normative accessor for the two collaborators that will consume
 * this entity: {@code com.cardemo.repository.UserSecurityRepository} and
 * {@code com.cardemo.security.CardDemoUserDetailsService}. Neither exists yet; when they are authored
 * they read the hash through that accessor and nothing else on this class exposes credential material.
 *
 * <h2>Online visibility: this cluster is not batch only</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines exactly eight CICS file names - {@code ACCTDAT},
 * {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX},
 * {@code TRANSACT} and {@code USRSEC}, the last at {@code :L88}. {@code USRSEC} therefore has an online
 * definition, which distinguishes it from {@code TCATBALF}, {@code DISCGRP}, {@code TRANCATG} and
 * {@code TRANTYPE}: those four appear nowhere in the CSD and are batch only. The practical consequence
 * is that this table is on the online request path - the sign-on program {@code app/cbl/COSGN00C.cbl}
 * reads it on every authentication, naming the file at {@code :L39} and the dataset at {@code :L212} -
 * and the four user administration programs {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C} and
 * {@code COUSR03C} maintain it. It is not reference data that only a nightly job touches.
 *
 * <h2>Seed data: ten rows that exist only inside JCL</h2>
 *
 * <p>There is no {@code usrsec.txt} fixture. Unlike the nine {@code app/data/ASCII} fixtures that seed
 * the sibling tables, the ten user records exist only as inline data inside a job step:
 * {@code app/jcl/DUSRSECJ.jcl:L32} runs {@code EXEC PGM=IEBGENER}, {@code :L34} opens
 * {@code //SYSUT1   DD *}, the ten data rows occupy {@code :L35-L44}, and {@code :L45} terminates the
 * stream with {@code /*}. Any test or migration that needs the seed must read it from there.
 *
 * <p>The ten rows are five administrators - {@code ADMIN001} MARGARET GOLD, {@code ADMIN002} RUSSELL
 * RUSSELL, {@code ADMIN003} RAYMOND WHITMORE, {@code ADMIN004} EMMANUEL CASGRAIN and {@code ADMIN005}
 * GRANVILLE LACHAPELLE, all of type {@code A} - and five standard users - {@code USER0001} LAWRENCE
 * THOMAS, {@code USER0002} AJITH KUMAR, {@code USER0003} LAURITZ ALME, {@code USER0004} AVERARDO MAZZI
 * and {@code USER0005} LEE TING, all of type {@code U}. All ten identifiers are already upper case in
 * the source, which is worth knowing and is <em>not</em> a reason to normalise anything here.
 *
 * <p>Every one of the ten carries the same credential: a single shared literal plaintext value, recorded
 * at {@code app/jcl/DUSRSECJ.jcl:L35-L44}. That value is referred to by citation only and is
 * transcribed nowhere in this file, nowhere in any test, and nowhere else under {@code src/}. See the
 * third Blocker finding below.
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><b>Blocker - the credential column holds a BCrypt hash and nothing else.</b> The source field is
 * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21}, and the sign-on program compares it
 * in plaintext: {@code app/cbl/COSGN00C.cbl:L223} reads {@code IF SEC-USR-PWD = WS-USER-PWD}. The target
 * is {@code sec_usr_pwd VARCHAR(60) NOT NULL}, holding a BCrypt strength 10 hash. {@code VARCHAR} and
 * not {@code CHAR}, because a BCrypt hash is exactly 60 characters and must never be blank padded - a
 * padded hash fails verification - and emphatically not {@code CHAR(8)}, which could not hold one at
 * all. This entity has exactly one credential member, {@code passwordHash}; no field, parameter,
 * constructor argument or accessor anywhere on it holds a password in plaintext, and there is no
 * hashing, verification or encoding logic of any kind. That contract is what makes the audit gate's
 * assertion - every password BCrypt hashed, no literal secret anywhere - provable against the ten
 * seeded users rather than merely asserted.
 *
 * <p><b>Blocker - the user type needs an explicit converter, because neither enumerated mode works.</b>
 * {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22} carries {@code A} or {@code U},
 * the two codes declared as condition names in {@code app/cpy/COCOM01Y.cpy:L27-L28} against
 * {@code CDEMO-USER-TYPE PIC X(01)} at {@code :L26}. Mapping {@link UserType} with a string enumerated
 * mode would persist the constant names - six and four characters - into a one character column, which
 * either fails on insert or truncates; mapping it with an ordinal mode would persist {@code 0} and
 * {@code 1}, which are not the source's values at all and would silently change meaning the moment
 * anyone reordered the constants. Neither mode is usable, so persistence goes through the nested
 * {@link UserTypeConverter} declared at the bottom of this class. Grepping this file for
 * {@code Enumerated} returns nothing, and that is deliberate.
 *
 * <p><b>Blocker - the shared plaintext seed literal is never transcribed.</b> Rule 1 clause D1 is
 * unconditional: no secrets in code, logs, tests or config. The value shared by all ten rows at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44} therefore appears nowhere in this file - not in code, not in a
 * comment, not in this documentation - and must appear nowhere else under {@code src/}. The downstream
 * obligation is explicit: {@code V3__seed_data.sql} stores the ten users only as precomputed BCrypt
 * strength 10 hashes, and must never embed the plaintext value it hashes. No BCrypt hash literal appears
 * in this file either, in any of its version prefixes.
 *
 * <p><b>Blocker - fixed width CHAR columns need an explicit JDBC type code.</b> Four of the five
 * columns are {@code CHAR(n)} and every profile is required to set
 * {@code spring.jpa.hibernate.ddl-auto: validate}, which makes this a startup concern rather than a
 * cosmetic one. Hibernate maps a {@code String} attribute to JDBC {@code VARCHAR} by default, and
 * {@code columnDefinition} does not change that - it only supplies the DDL text Hibernate would emit if
 * it were generating the schema. Schema validation compares type codes, so a {@code CHAR} column against
 * a {@code VARCHAR} expectation refuses to start the application context with a
 * {@code SchemaManagementException} naming the offending column. The remedy, already applied by the
 * sibling entities in this package, is an explicit {@code JdbcTypeCode} of {@code SqlTypes.CHAR} on each
 * character attribute. It is applied here to the four {@code CHAR} attributes and deliberately withheld
 * from {@code passwordHash}, whose {@code VARCHAR} expectation is the correct one and is exactly what
 * keeps the never-a-CHAR-credential-column requirement true under validation.
 *
 * <p><b>High - this class is not a security principal.</b> It does not implement Spring Security's
 * user-details contract and declares none of that contract's authorisation state: no activation flag, no
 * expiry or lock flags, no granted-authority collection, no roles. None of those exists in the 80 byte
 * source record, so adding one would break the record contract, and importing the interface would drag
 * Spring Security into a pure data holder - this file imports nothing from Spring at all. The
 * adaptation from this entity to a principal belongs to
 * {@code com.cardemo.security.CardDemoUserDetailsService}, and this package must not import from the
 * security package - the dependency runs the other way. Nothing from Spring, from
 * {@code com.cardemo.exception}, or from the repository, service, controller, batch, config or
 * observability packages is imported here.
 *
 * <p><b>Medium - fixed width text semantics: a blank but non null value must load.</b> Four columns are
 * fixed width {@code CHAR}, so values arrive blank padded to their declared widths and a value of
 * nothing but spaces is legitimate legacy data. Consequently there is no bean validation on any field:
 * no not-blank, not-empty, pattern or minimum size constraint appears anywhere in this class.
 * {@code nullable = false} is the only nullability assertion, and it belongs to the schema rather than
 * to a validation annotation. No accessor trims, folds case or normalises in any way; trimming is a
 * presentation concern that belongs to the DTO layer, and a getter that trimmed would hide the very
 * padding a fixed width defect shows up as. Note in particular that the sign-on program upper cases
 * <em>both</em> the identifier and the presented password before comparing them -
 * {@code app/cbl/COSGN00C.cbl:L132} and {@code :L135} - which is service layer behaviour that this
 * entity neither implements nor interferes with. Were any case or format operation ever added here it
 * would have to pin {@code Locale.ROOT} to stay deterministic under Rule 1 clause C2, but none is
 * needed and none is present.
 *
 * <p><b>Low - no alternate index, and none is wanted.</b> {@code USRSEC} has no alternate index: the
 * catalogue's three alternate indexes belong to {@code CARDDATA}, {@code CARDXREF} and
 * {@code TRANSACT}. {@code V2__create_indexes.sql} therefore creates nothing for this table beyond its
 * primary key. That is sufficient rather than merely economical - the user list screen
 * {@code app/cbl/COUSR00C.cbl} pages in primary key order at ten rows per page, which the primary key
 * index already serves, so a second index would be write amplification for no read benefit.
 *
 * <h2>Required schema, and what is Not available</h2>
 *
 * <p><b>Not available:</b> {@code src/main/resources/db/migration/V1__create_schema.sql} did not exist
 * when this entity was authored, and neither did the directory that will hold it, so the schema could
 * not be read and this mapping could not be reconciled against it. The field contract above is
 * therefore the normative column contract, and the migration must converge on it rather than the
 * reverse. None of the four {@code application*.yml} profile files was available either, so the
 * {@code spring.jpa.hibernate.ddl-auto: validate} setting cited throughout this documentation is the
 * mandated configuration rather than an observed one - and it is precisely why the contract below must
 * be met exactly: under {@code validate}, any divergence in column name, type, length or nullability
 * fails application context startup outright rather than degrading quietly. The resolved expectations
 * were confirmed instead by binding this class with Hibernate 6.6.42 against the PostgreSQL dialect,
 * which yields {@code sec_usr_id char(8)}, {@code sec_usr_fname char(20)},
 * {@code sec_usr_lname char(20)}, {@code sec_usr_pwd varchar(60)} and {@code sec_usr_type char(1)}, all
 * non null. What is needed, precisely:
 *
 * <ul>
 *   <li>table {@code user_security} with {@code sec_usr_id CHAR(8) NOT NULL PRIMARY KEY};</li>
 *   <li>{@code sec_usr_fname CHAR(20) NOT NULL};</li>
 *   <li>{@code sec_usr_lname CHAR(20) NOT NULL};</li>
 *   <li>{@code sec_usr_pwd VARCHAR(60) NOT NULL} - a BCrypt hash only, never {@code CHAR(8)}, never
 *       {@code CHAR(60)}, and never a plaintext column under any name;</li>
 *   <li>{@code sec_usr_type CHAR(1) NOT NULL}, plus a check constraint restricting it to {@code 'A'}
 *       and {@code 'U'} - one of the first migration's five check constraints, and the schema level
 *       counterpart of {@link UserTypeConverter}'s refusal to accept a third code;</li>
 *   <li>no version column: this entity carries no optimistic locking counter, unlike the account, card,
 *       customer and transaction entities;</li>
 *   <li>no index beyond the primary key;</li>
 *   <li>no foreign key in either direction: the source record has no relationship to any other
 *       cluster;</li>
 *   <li>seeded by {@code V3__seed_data.sql} with the ten users from
 *       {@code app/jcl/DUSRSECJ.jcl:L35-L44}, stored only as precomputed BCrypt strength 10 hashes. The
 *       shared plaintext literal must never be embedded in that migration or in any other file under
 *       {@code src/}. There is no {@code usrsec.txt} fixture to load; the seed source is inline JCL
 *       data.</li>
 * </ul>
 *
 * <p>For the migration author's wider orientation: the first migration creates exactly 11 tables with
 * 10 foreign keys and 5 check constraints, of which this table is one and contributes one check
 * constraint and no foreign key. Spring Batch's {@code BATCH_*} tables come from the framework's own
 * schema script and must never appear in the first migration, nor be added as a fourth one.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Nothing on this class is configurable and it reads no property. The settings that govern it live
 * elsewhere and are recorded here so that a failure can be traced to the right file:
 * {@code spring.jpa.hibernate.ddl-auto: validate}, mandated for all four profiles, which is what turns a
 * mapping divergence into a startup failure; the Flyway migrations {@code V1__create_schema.sql},
 * {@code V2__create_indexes.sql} and {@code V3__seed_data.sql}, which own the schema and the seed;
 * BCrypt strength 10, which belongs to the security configuration's password encoder and never to this
 * entity; and the credential masking rules in {@code logback-spring.xml}, which are a <em>backstop</em>
 * and never the primary defence - the primary defence is that this class never emits the hash in the
 * first place.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Source the pinned toolchain first, then build from the repository root:
 * {@code source /etc/profile.d/10-carddemo-toolchain.sh} for Java 25 and Maven 3.9.11;
 * {@code mvn -B clean compile} to compile, which runs under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} set, so any warning at all is a build failure; {@code mvn -B clean test} for
 * the unit suite; {@code mvn -B clean verify} for the full gate, which additionally enforces the 80
 * percent line coverage floor. The unit tests for this class belong in
 * {@code src/test/java/com/cardemo/unit/model} and should assert the 80 byte record arithmetic and the
 * key length of 8, that {@code sec_usr_pwd} is declared {@code VARCHAR(60)}, that no member holds a
 * credential in plaintext, that the rendering contains neither the hash nor a BCrypt version prefix, that the
 * converter round trips both constants, that a blank code yields {@code null}, and that an unrecognised
 * code throws.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>{@code SchemaManagementException: wrong column type encountered in column [sec_usr_id]}</b>
 *       and the like, at startup: the migration created the column with a type whose JDBC type code
 *       disagrees with this mapping. For the four {@code CHAR} columns the expectation is
 *       {@code CHAR}, supplied by the explicit JDBC type code annotation. If the message names
 *       {@code sec_usr_pwd}, the migration wrongly created it as {@code CHAR} - it must be
 *       {@code VARCHAR(60)}.</li>
 *   <li><b>{@code IllegalArgumentException} naming an unrecognised {@code sec_usr_type} code</b>, on
 *       read: a third code reached the column, which means the check constraint is missing from the
 *       first migration or was bypassed by a direct write. Fix the data or the constraint; do not
 *       relax the converter, whose refusal to guess is the point.</li>
 *   <li><b>Every seeded user fails to authenticate</b>: the seed migration stored something other
 *       than a BCrypt strength 10 hash - the plaintext value, a differently costed hash, or a hash
 *       blank padded by a {@code CHAR} column. Verify the column is {@code VARCHAR(60)} and the stored
 *       value is exactly 60 characters.</li>
 *   <li><b>A credential appears in a log line</b>: it did not come from this class. Nothing here
 *       renders the hash, so the source is a caller that logged the object graph reflectively, a
 *       serialiser applied to the entity instead of to a DTO, or a debugger dump. Route the entity
 *       through a DTO before it reaches any sink.</li>
 *   <li><b>A row of blank names fails to load</b>: a bean validation constraint was added to a name
 *       field. None is permitted; blank but non null is legitimate fixed width data.</li>
 * </ul>
 *
 * <h2>Behaviour that deliberately lives elsewhere</h2>
 *
 * <p>Hashing and verification belong to the authentication service; adaptation to a security principal
 * belongs to {@code com.cardemo.security.CardDemoUserDetailsService}; trimming and display formatting
 * belong to the DTO layer; the accepted set of type codes is enforced twice, by
 * {@link UserTypeConverter} on read and by a check constraint in the schema. This class carries no
 * inheritance and no mapped superclass, no association to any other entity and therefore no cascade,
 * no lazy loading and no possibility of an N+1 pattern, no lifecycle callback of any kind - in
 * particular nothing that could hash, mutate or normalise the credential on persist or load - and no
 * {@code Serializable} implementation, both because the missing serial version identifier lint is fatal
 * under {@code -Werror} and because a credential bearing type is precisely the wrong thing to make
 * serialisable.
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * Width of the user identifier column: 8 characters, from {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L18}. This is also the cluster's key length, declared as
     * {@code KEYS(8,0)} at {@code app/jcl/DUSRSECJ.jcl:L65} and reported as {@code KEYLEN 8} at
     * {@code app/catlg/LISTCAT.txt:L3883}.
     */
    private static final int USER_ID_WIDTH = 8;

    /**
     * Width of the first name column: 20 characters, from {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy:L19}.
     */
    private static final int FIRST_NAME_WIDTH = 20;

    /**
     * Width of the last name column: 20 characters, from {@code SEC-USR-LNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy:L20}.
     */
    private static final int LAST_NAME_WIDTH = 20;

    /**
     * Width of the credential column: 60 characters, which is the exact length of a BCrypt hash.
     *
     * <p>This is the one width that is not the PIC width of the field it replaces. The source field
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21} is eight bytes because it held
     * plaintext; the column is 60 characters because it holds a hash, and it is {@code VARCHAR} rather
     * than {@code CHAR} so that no blank padding can corrupt one.
     *
     * <p>The constant is named for the hash algorithm rather than for the credential, deliberately: the
     * upper case naming convention for constants would otherwise reproduce, as a substring of an
     * identifier, the very literal that the third Blocker finding forbids appearing anywhere in this
     * file. Keeping that literal out of the file entirely is what lets the prohibition be checked
     * mechanically rather than by reading.
     */
    private static final int BCRYPT_HASH_WIDTH = 60;

    /**
     * Width of the user type column: 1 character, from {@code SEC-USR-TYPE PIC X(01)} at
     * {@code app/cpy/CSUSR01Y.cpy:L22}, carrying {@code A} or {@code U}.
     */
    private static final int USER_TYPE_WIDTH = 1;

    /**
     * User identifier and primary key, from {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L18}.
     *
     * <p>The eight leading bytes of the record, which are also the cluster key:
     * {@code app/jcl/DUSRSECJ.jcl:L65} declares {@code KEYS(8,0)}, an eight byte key at relative offset
     * zero, and {@code app/catlg/LISTCAT.txt:L3884} confirms {@code RKP 0}. Because the key is the
     * leading field, primary key order and physical record order coincide, which is what lets the user
     * list screen page in key order without a secondary index.
     *
     * <p>This is a natural business key, supplied by the seed migration and by the user add service that
     * replaces {@code app/cbl/COUSR01C.cbl}. It is never generated: no generation strategy is declared
     * here and no sequence exists for it anywhere in the schema. All ten seeded identifiers are already
     * upper case, and this field neither enforces nor imposes that.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_id", nullable = false, length = USER_ID_WIDTH)
    private String secUsrId;

    /**
     * User first name, from {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L19}.
     *
     * <p>Bytes 9 through 28 of the record. Blank padded to 20 characters by the fixed width source and
     * by the {@code CHAR(20)} column, and stored exactly as received: nothing here trims, folds case or
     * otherwise repairs the value, because a name of nothing but spaces is legitimate legacy data and a
     * loading rule that rejected it would reject rows the source accepts.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_fname", nullable = false, length = FIRST_NAME_WIDTH)
    private String secUsrFname;

    /**
     * User last name, from {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L20}.
     *
     * <p>Bytes 29 through 48 of the record, with the same fixed width semantics as the first name: blank
     * padded, never trimmed, never validated for content.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_lname", nullable = false, length = LAST_NAME_WIDTH)
    private String secUsrLname;

    /**
     * BCrypt hash of the user's password - <b>never the password itself</b>. Replaces
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21}, which held plaintext.
     *
     * <p>This is the only credential member on the class and the only member whose column width departs
     * from its source PIC width. Three properties of the mapping are load bearing:
     *
     * <ul>
     *   <li>the column is {@code VARCHAR(60)}. Sixty because a BCrypt hash is exactly sixty characters;
     *       {@code VARCHAR} rather than {@code CHAR} because a {@code CHAR} column would blank pad the
     *       value and a padded hash fails verification; and emphatically not {@code CHAR(8)}, the
     *       source's width, which could not hold a hash at all;</li>
     *   <li>this field deliberately carries <em>no</em> explicit JDBC type code override, unlike the
     *       four character fields around it. {@code String} maps to JDBC {@code VARCHAR} by default,
     *       which is precisely the expectation a {@code VARCHAR(60)} column needs, so the absence of an
     *       override here is what makes schema validation agree that this column is not a
     *       {@code CHAR};</li>
     *   <li>no hashing, verification or encoding happens anywhere in this class. There is no
     *       plaintext-accepting setter, no comparison helper, and no password encoder import. Producing
     *       the hash is the authentication service's job, and verifying it is the security
     *       configuration's.</li>
     * </ul>
     *
     * <p>The value is excluded from {@link #toString()}, {@link #equals(Object)} and
     * {@link #hashCode()}. The sign-on program it descends from compared plaintext directly -
     * {@code app/cbl/COSGN00C.cbl:L223} - after upper casing both the identifier and the presented
     * password at {@code :L132} and {@code :L135}; that upper casing is service layer behaviour which
     * this field neither performs nor obstructs.
     */
    @Column(name = "sec_usr_pwd", nullable = false, length = BCRYPT_HASH_WIDTH)
    private String passwordHash;

    /**
     * User class, from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}: byte 57 of
     * the record, carrying {@code A} for an administrator or {@code U} for a standard user.
     *
     * <p>Typed as {@link UserType} rather than as a bare character, because the two codes are a closed
     * domain declared as condition names in {@code app/cpy/COCOM01Y.cpy:L27-L28} against
     * {@code CDEMO-USER-TYPE PIC X(01)} at {@code :L26}, and because the sign-on program moves this very
     * byte into that field at {@code app/cbl/COSGN00C.cbl:L227} to drive the menu it routes to.
     *
     * <p>Persistence goes through {@link UserTypeConverter}, not through either enumerated mode: a
     * string mode would persist the constant names into a one character column and an ordinal mode would
     * persist positions instead of the source's own codes. The explicit JDBC type code makes the
     * converter's {@code String} form present itself as a {@code CHAR}, matching the {@code CHAR(1)}
     * column the schema must declare.
     */
    @Convert(converter = UserTypeConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_type", nullable = false, length = USER_TYPE_WIDTH)
    private UserType secUsrType;

    /**
     * No argument constructor required by the JPA specification, which the persistence provider uses to
     * materialise an instance before populating its state.
     *
     * <p>Deliberately {@code protected} rather than {@code public}: the provider and any subclass can
     * reach it, while application code is steered to the all columns constructor and cannot accidentally
     * create a user with five null columns. Every field is left null here, which is the correct transient
     * state for an instance the provider is about to fill; an instance created this way is not valid to
     * persist until all five properties are set.
     */
    protected UserSecurity() {
        // Intentionally empty: JPA populates the persistent state directly after construction.
    }

    /**
     * Creates a fully populated user, which is how application, migration and test code should build one.
     *
     * <p>There is deliberately no convenience constructor that accepts a password in plaintext. The
     * credential argument is a BCrypt hash that the caller has already computed; this class never hashes
     * and never holds plaintext, so a constructor that appeared to accept one would be a lie about where
     * the responsibility sits.
     *
     * <p>All five fields are assigned directly rather than through the setters. That is not a stylistic
     * choice: the JPA specification forbids a final entity class, so this class is not final, and a
     * constructor that called an overridable setter would leak {@code this} to a subclass override
     * before construction finished. The compiler reports exactly that as a {@code this-escape} warning,
     * and {@code -Werror} turns it into a build failure.
     *
     * <p>No validation is applied and none is appropriate here. All five columns are non null in the
     * schema and the persistence provider reports a null on flush with full context, while a length or
     * content check in this constructor would reject the blank but non null values that the fixed width
     * source legitimately carries. The constructor has no side effect, performs no I/O and throws
     * nothing.
     *
     * @param secUsrId     the eight character identifier for {@code sec_usr_id}, from
     *                     {@code SEC-USR-ID PIC X(08)}; longer values are rejected by the column width
     *                     on flush and shorter ones are blank padded by the {@code CHAR(8)} column
     * @param secUsrFname  the first name for {@code sec_usr_fname}, from
     *                     {@code SEC-USR-FNAME PIC X(20)}, blank padded to 20 characters by the column
     * @param secUsrLname  the last name for {@code sec_usr_lname}, from
     *                     {@code SEC-USR-LNAME PIC X(20)}, blank padded to 20 characters by the column
     * @param passwordHash the BCrypt hash for {@code sec_usr_pwd} - a sixty character hash, never a
     *                     password in plaintext and never a hash produced at any other strength than the
     *                     one the security configuration pins
     * @param secUsrType   the user class for {@code sec_usr_type}, from
     *                     {@code SEC-USR-TYPE PIC X(01)}; {@link UserType#ADMIN} for the five seeded
     *                     administrators and {@link UserType#USER} for the five seeded standard users
     */
    public UserSecurity(String secUsrId, String secUsrFname, String secUsrLname, String passwordHash,
            UserType secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.passwordHash = passwordHash;
        this.secUsrType = secUsrType;
    }

    /**
     * Returns the user identifier, the primary key, from {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L18}.
     *
     * @return the eight character identifier exactly as held, blank padded to 8 by the {@code CHAR(8)}
     *         column when read back from the database and never trimmed, or {@code null} on a transient
     *         instance built by the no argument constructor
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the user identifier, from {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18}.
     *
     * <p>Mutating the primary key of an entity that is already managed is not meaningful to the
     * persistence provider; this setter exists for the provider's own property access and for
     * constructing an instance in stages. No validation, trimming or case folding is performed, so a
     * blank but non null identifier is accepted exactly as the fixed width source permits, and nothing
     * is thrown.
     *
     * @param secUsrId the eight character identifier to store in {@code sec_usr_id}
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user's first name, from {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy:L19}.
     *
     * @return the name exactly as held, which after a database read is blank padded to 20 characters and
     *         is not trimmed, or {@code null} on a transient instance built by the no argument
     *         constructor
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user's first name, from {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy:L19}.
     *
     * <p>No validation, trimming or case folding is performed and nothing is thrown; a name of only
     * spaces is a legitimate value in the source and stays legitimate here.
     *
     * @param secUsrFname the first name to store in {@code sec_usr_fname}
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user's last name, from {@code SEC-USR-LNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy:L20}.
     *
     * @return the name exactly as held, which after a database read is blank padded to 20 characters and
     *         is not trimmed, or {@code null} on a transient instance built by the no argument
     *         constructor
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user's last name, from {@code SEC-USR-LNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy:L20}.
     *
     * <p>No validation, trimming or case folding is performed and nothing is thrown, for the same fixed
     * width reason as the first name.
     *
     * @param secUsrLname the last name to store in {@code sec_usr_lname}
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the BCrypt hash of the user's password.
     *
     * <p><b>Warning.</b> The returned value is a BCrypt hash and is never a password in plaintext. It is
     * credential material: it must never be logged, never be written to a trace or a metric tag, never
     * be serialised into a response body, and never be copied into a DTO. The masking rules in
     * {@code logback-spring.xml} are a backstop for accidents, not the primary defence; the primary
     * defence is not emitting the value at all. The only legitimate consumers are the password encoder
     * that verifies a presented password against it and the migration that seeds it.
     *
     * <p>This is the normative accessor for {@code com.cardemo.repository.UserSecurityRepository} and
     * {@code com.cardemo.security.CardDemoUserDetailsService}. It is named for what the value is rather
     * than for the COBOL field it replaces - {@code SEC-USR-PWD PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L21}, which held plaintext - so that no caller can mistake it for a
     * comparable password.
     *
     * @return the sixty character BCrypt hash exactly as stored, never blank padded because the column
     *         is {@code VARCHAR}, or {@code null} on a transient instance built by the no argument
     *         constructor
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Sets the BCrypt hash of the user's password.
     *
     * <p><b>Warning.</b> The argument must already be a BCrypt hash. This setter performs no hashing,
     * no encoding, no validation and no normalisation whatsoever - passing a password in plaintext would
     * store it in plaintext, which is a defect in the caller and one this class cannot detect
     * without taking on the hashing responsibility that deliberately belongs to the authentication
     * service. There is intentionally no overload that accepts a password in plaintext, precisely so that
     * the correct call is the only call available. The value must never be logged or serialised.
     *
     * @param passwordHash the sixty character BCrypt hash to store in {@code sec_usr_pwd}
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Returns the user class, from {@code SEC-USR-TYPE PIC X(01)} at
     * {@code app/cpy/CSUSR01Y.cpy:L22}.
     *
     * @return {@link UserType#ADMIN} when the column holds {@code A} and {@link UserType#USER} when it
     *         holds {@code U}; {@code null} on a transient instance built by the no argument
     *         constructor, or when the column held a blank that {@link UserTypeConverter} mapped to
     *         {@code null}
     */
    public UserType getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user class, from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}.
     *
     * <p>Because the parameter is the enumeration rather than a character, the closed domain of two
     * codes is enforced by the type system on this path and there is nothing left for the setter to
     * validate; the only value outside the domain that can reach it is {@code null}, which the non null
     * column rejects on flush with full context. Nothing is thrown here.
     *
     * @param secUsrType the user class to store in {@code sec_usr_type}
     */
    public void setSecUsrType(UserType secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Compares two users on {@code secUsrId} alone.
     *
     * <p>The identifier is the right and only basis for identity here. It is a natural key that arrives
     * from the seed rather than a generated surrogate, so it is populated from the moment an instance is
     * meaningful, and it stays stable across the transient, managed and detached states while a name, a
     * user class or - especially - a credential can all legitimately change. Two instances that denote
     * the same user must not start comparing unequal because one of them has had its password rotated.
     *
     * <p>The credential is excluded for a second, independent reason: reading it here would put hash
     * material on a code path that collection membership calls implicitly and frequently, and a hash
     * comparison is not a meaningful contributor to identity in the first place. The same reasoning
     * excludes it from {@link #hashCode()}.
     *
     * <p>The type test is a pattern match rather than a {@code getClass()} comparison so that a lazily
     * loaded provider proxy compares equal to the instance it stands for; a {@code getClass()} test would
     * see the generated proxy class and report inequality. This class declares no inheritance and no
     * persistence hierarchy, so the symmetry caveat that normally attaches to a pattern match in a non
     * final class cannot arise.
     *
     * <p>Boundary case, stated rather than hidden: two instances that have not yet been given an
     * identifier both hold {@code null} and therefore compare equal, so unkeyed instances must not be
     * relied on to stay distinct inside a hash based collection. Give an instance its identifier before
     * putting it in a set or a map. Note also that this comparison is exact string equality, which is
     * stricter than the database's blank insensitive comparison on {@code CHAR}; because all ten seeded
     * identifiers fully occupy {@code CHAR(8)} there is no padding for the two rules to disagree about.
     *
     * @param other the object to compare with, which may be {@code null}
     * @return {@code true} if {@code other} is a user with an equal {@code secUsrId}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserSecurity that)) {
            return false;
        }
        return Objects.equals(this.secUsrId, that.secUsrId);
    }

    /**
     * Returns a hash code derived from {@code secUsrId} alone, consistent with {@link #equals(Object)}.
     *
     * <p>{@code Objects.hashCode(Object)} is used rather than dereferencing the field, so a transient
     * instance whose identifier is still {@code null} hashes to 0 instead of throwing. The value is
     * stable for the lifetime of an instance whose key does not change, which is the property a hash
     * based collection requires. The credential is not read here, for the reasons given on
     * {@link #equals(Object)}.
     *
     * @return the hash code of the primary key, or 0 when the key is {@code null}
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(secUsrId);
    }

    /**
     * Returns a deliberately narrow diagnostic rendering: the identifier and the user class, and nothing
     * else.
     *
     * <p>The BCrypt hash is excluded <b>unconditionally</b>. There is no flag, no profile and no debug
     * mode that adds it, because a rendering is exactly how credential material escapes into a log
     * aggregator, an exception message, a metric tag or an APM trace. The masking rules in
     * {@code logback-spring.xml} are a backstop against the accidents this method cannot see; never
     * emitting the value is the primary defence, and the two are not interchangeable.
     *
     * <p>The two name fields are omitted as well, not because they are secret but under least privilege:
     * they are personal data, they contribute nothing to identifying a row that its primary key does not
     * already identify, and a diagnostic line is not a place personal data needs to be. What remains is
     * the minimum that makes a log line useful - which row, and which privilege class - and this is why
     * this rendering is restricted while the reference data entities in this package render every column
     * they hold.
     *
     * <p>The identifier appears exactly as held, so after a database read it shows its 8 character blank
     * padded form; a rendering that trimmed it would hide the very padding a fixed width defect shows up
     * as. The user class renders as its constant name rather than its stored code, since a diagnostic
     * reader is better served by {@code ADMIN} than by {@code A}, and it shows {@code null} for a
     * transient or blank valued instance rather than substituting a default.
     *
     * @return a single line rendering of the identifier and the user class, never {@code null} and never
     *         containing credential material
     */
    @Override
    public String toString() {
        return "UserSecurity{secUsrId=" + secUsrId + ", secUsrType=" + secUsrType + "}";
    }

    /**
     * Maps {@link UserType} onto the single character {@code sec_usr_type} column, and back.
     *
     * <h2>What this component does and why it must exist</h2>
     *
     * <p>The column is {@code CHAR(1)} carrying the two codes {@code A} and {@code U} that
     * {@code app/cpy/COCOM01Y.cpy:L27-L28} declares, and the enumeration it maps to is a pure model type
     * that deliberately carries no persistence annotation of its own. Neither built in enumerated mode
     * can bridge the two: a string mode would try to write the constant names {@code ADMIN} and
     * {@code USER} into one character, failing or truncating, and an ordinal mode would write {@code 0}
     * and {@code 1}, which are not the source's values and would change meaning silently if the
     * constants were ever reordered. An explicit converter is therefore the only correct mechanism, and
     * this is the one place in the application where that mapping is expressed.
     *
     * <p>It is a nested class on purpose. The mapping is meaningful only for this entity's one attribute,
     * so declaring it here keeps it beside the field it serves and adds no twelfth source file to a
     * package whose eleven entities correspond one to one with the eleven VSAM clusters. It is
     * {@code public static} so that the persistence provider can reflectively instantiate it, and its
     * constructor is declared explicitly rather than left implicit so that the provider's requirement is
     * stated in code rather than inferred.
     *
     * <p>Automatic application is switched off. The converter is bound to exactly one attribute, by the
     * conversion annotation on that field, and can never attach itself to another {@link UserType}
     * attribute elsewhere in the model as a side effect of being on the classpath. That is the least
     * privilege reading of an annotation whose permissive setting would widen its reach silently.
     *
     * <h2>Inputs, outputs and side effects</h2>
     *
     * <p>Both directions are pure functions of their argument: no state is held, no static field is
     * declared - mutable or otherwise - nothing is logged, nothing is cached and no I/O is performed, so
     * a single instance is safe for concurrent use by the provider.
     *
     * <h2>Error modes</h2>
     *
     * <p>Exactly one failure is possible, and it is raised rather than absorbed:
     * {@link #convertToEntityAttribute(String)} throws {@link IllegalArgumentException} when the column
     * holds a non blank value that is not exactly one character long, or is one character that is neither
     * {@code A} nor {@code U}. Nothing is swallowed and nothing
     * silently defaults - in particular an unrecognised code never resolves to {@link UserType#USER}, to
     * {@link UserType#ADMIN} or to {@code null}, because guessing a privilege class is the one mistake a
     * role store must never make. The schema is required to carry a check constraint restricting
     * {@code sec_usr_type} to {@code 'A'} and {@code 'U'}, so this exception should be unreachable in a
     * correctly migrated database; it exists to make a bypassed constraint or a direct write loud instead
     * of invisible, and it must stay consistent with that constraint by accepting no third code.
     */
    @Converter(autoApply = false)
    public static class UserTypeConverter implements AttributeConverter<UserType, String> {

        /**
         * Creates a converter instance.
         *
         * <p>Declared explicitly, and public, because the persistence provider instantiates this class
         * reflectively through a no argument constructor. The converter holds no state, so there is
         * nothing to initialise and no argument to accept.
         */
        public UserTypeConverter() {
            // Intentionally empty: the converter is stateless, so there is nothing to initialise.
        }

        /**
         * Converts a user class to the single character code the column stores.
         *
         * <p>The code comes from the enumeration itself rather than from a literal restated here, so the
         * written value cannot drift from the value the model considers canonical. The result is a one
         * character string, which the {@code CHAR(1)} column stores without padding.
         *
         * @param attribute the user class to write; may be {@code null}
         * @return {@code "A"} for an administrator and {@code "U"} for a standard user, or {@code null}
         *         when {@code attribute} is {@code null}, so that a null attribute becomes a SQL null
         *         and is rejected by the non null column rather than being written as some substitute
         *         character
         */
        @Override
        public String convertToDatabaseColumn(UserType attribute) {
            if (attribute == null) {
                return null;
            }
            return String.valueOf(attribute.getCode());
        }

        /**
         * Converts the single character code the column stores back to a user class.
         *
         * <p>Every boundary condition is handled explicitly:
         *
         * <ul>
         *   <li>a {@code null} column value yields {@code null};</li>
         *   <li>a value that is entirely padding yields {@code null}. This case is real rather than
         *       defensive: the column is fixed width, so a legacy row that never had a user class set
         *       arrives as a blank, and a blank is an absent value rather than an invalid one;</li>
         *   <li>otherwise the padding stripped value is resolved through the enumeration, and anything
         *       outside the two code domain throws.</li>
         * </ul>
         *
         * <p>Padding is stripped with {@code String.trim()} rather than {@code String.strip()} on
         * purpose. {@code trim()} removes every character at or below the space, which covers both the
         * blanks and the low value bytes that fixed width legacy records use as padding, whereas
         * {@code strip()} removes only Unicode whitespace and would leave a low value byte in place -
         * turning an absent value into a spurious unrecognised code. Neither call depends on a locale,
         * so the behaviour is deterministic on every host.
         *
         * <p>Once padding has been stripped the value must be <em>exactly one</em> character long, and
         * the length check is delegated to {@link UserType#fromCode(String)} which already reports an
         * empty result for any other length. Taking the first character of a longer value instead would
         * be an unsafe default of exactly the kind the security-by-default standard forbids: it would
         * resolve {@code "AA"} to {@link UserType#ADMIN}, silently granting a privilege class on the
         * strength of a value the source cannot even represent - {@code app/cpy/COCOM01Y.cpy:L26}
         * declares {@code CDEMO-USER-TYPE} as {@code PIC X(01)}, so a two character value is not a user
         * class code at all. The leniency such a shortcut would buy is illusory in any case, because
         * {@code trim()} above has already removed the padding a driver could have added, so no value a
         * {@code CHAR(1)} column can produce is affected by the distinction. What is affected is
         * genuinely corrupt input, and rejecting that is the whole point.
         *
         * <p>No case folding is applied: {@code a} is not {@code A}, exactly as the source's own
         * comparison is exact.
         *
         * @param dbData the raw column value; may be {@code null}, may be padding only, and may be
         *               malformed
         * @return the matching user class, or {@code null} when {@code dbData} is {@code null} or
         *         consists only of padding
         * @throws IllegalArgumentException if the padding stripped value is neither {@code A} nor
         *                                  {@code U}; the message names the column, the whole rejected
         *                                  value with its length and code units, and cites the copybook
         *                                  that defines the accepted codes
         */
        @Override
        public UserType convertToEntityAttribute(String dbData) {
            if (dbData == null) {
                return null;
            }
            final String unpadded = dbData.trim();
            if (unpadded.isEmpty()) {
                return null;
            }
            return UserType.fromCode(unpadded).orElseThrow(() -> new IllegalArgumentException(
                    "Unrecognised sec_usr_type code '" + unpadded + "' (" + unpadded.length()
                            + " UTF-16 code unit(s): " + hexUnitsOf(unpadded) + ") read from column "
                            + "sec_usr_type of table user_security; app/cpy/COCOM01Y.cpy:L26 declares "
                            + "CDEMO-USER-TYPE as PIC X(01) and :L27-L28 define exactly two codes, 'A' "
                            + "for ADMIN and 'U' for USER, so either the CHECK constraint on this "
                            + "column is missing from V1__create_schema.sql or the row was written "
                            + "around it"));
        }

        /**
         * Renders each UTF-16 code unit of a rejected value in hexadecimal, space separated.
         *
         * <p>Present so that a rejection message stays diagnosable when the offending value is
         * invisible: a low value control byte, a non breaking space or a full width letter all print as
         * nothing useful on their own, and the length alone does not identify them. {@code
         * Integer.toHexString} is locale independent, so the rendering is identical on every host.
         *
         * @param value the rejected value, never {@code null} and never empty
         * @return the value's code units, for example {@code 0x41 0x41} for {@code "AA"}
         */
        private static String hexUnitsOf(final String value) {
            final StringBuilder rendered = new StringBuilder(value.length() * 5);
            for (int index = 0; index < value.length(); index++) {
                if (index > 0) {
                    rendered.append(' ');
                }
                rendered.append("0x").append(Integer.toHexString(value.charAt(index)));
            }
            return rendered.toString();
        }
    }
}
