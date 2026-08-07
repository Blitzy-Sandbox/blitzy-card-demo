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
 * Source      : app/jcl/DUSRSECJ.jcl (inline IEBGENER data, 10 rows) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.cardemo.model.enums.UserType;

import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Application user, credential and role: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}.
 *
 * <p>The five fields of {@code app/cpy/CSUSR01Y.cpy} populate 57 bytes of the 80-byte record, the remaining 23
 * being trailing filler, and the key is the eight-byte user identifier. The cluster is defined in job control
 * rather than catalogued — {@code app/jcl/DUSRSECJ.jcl:L65-L66} declares {@code KEYS(8,0) RECORDSIZE(80,80)} —
 * and it has an entry in {@code app/csd/CARDDEMO.CSD}, so it is online data that four screen programs
 * maintain, not batch-only reference data.
 *
 * <p>The credential column is the one place where the column width departs from the picture width: the source
 * field is eight bytes of plaintext, the column holds a 60-character BCrypt hash, and the member is named for
 * what it stores rather than for the COBOL field. The shared plaintext literal that the ten seed rows carry is
 * referred to by locator only ({@code app/jcl/DUSRSECJ.jcl:L35-L44}) and is transcribed nowhere under
 * {@code src/}.
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
 *   </ol>
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
 * the same arithmetic byte for byte. Illustrated with a <strong>synthetic</strong> stand-in rather than
 * the seeded identity - eight key bytes such as {@code ADMNUSR1}, a given name blank padded to twenty
 * such as {@code FNAME001}, a family name blank padded to twenty such as {@code LNAME001}, the eight
 * byte credential field, then {@code A} - that is the 57 populated bytes, the remaining 23 being the
 * filler the fixed length record carries to reach 80. The real row 1 values are not transcribed here;
 * they are reachable by citation at {@code app/jcl/DUSRSECJ.jcl:L35}.
 *
 * <p>Field 4 is the single place where the column width deliberately departs from the PIC width. The
 * eight bytes still count towards the source record geometry above, because that arithmetic describes
 * the legacy record; the <em>column</em> is 60 characters because that is what a BCrypt hash needs, for
 * the reason set out under the credential column below.
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
 * <p>The ten rows split <strong>five administrators of type {@code A} then five standard users of type
 * {@code U}</strong>, in that order, each an eight character identifier followed by a given name and a
 * family name. The identifiers and names themselves are <strong>not reproduced here</strong>: they are
 * seeded identities, and the aggregate plus the layout is the evidence this file needs. Where an example
 * identifier is required, this documentation uses the synthetic keys {@code ADMNUSR1} through
 * {@code ADMNUSR5} and {@code STDUSR01} through {@code STDUSR05}, which match the eight character key
 * width without standing for any real row. The actual values are reachable by citation at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44}. One property of the real identifiers does matter and is recorded
 * without disclosing them: all ten are already upper case in the source, which is worth knowing and is
 * <em>not</em> a reason to normalise anything here.
 *
 * <p>Every one of the ten carries the same credential: a single shared literal plaintext value, recorded
 * at {@code app/jcl/DUSRSECJ.jcl:L35-L44}. That value is referred to by citation only and is
 * transcribed nowhere in this file, nowhere in any test, and nowhere else under {@code src/}.
 *
 * <h2>Mapping decisions that must not be undone</h2>
 *
 * <p><b>The credential column holds a BCrypt hash and nothing else.</b> The source field is
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
 * <p><b>The user type needs an explicit converter, because neither enumerated mode works.</b>
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
 * <p><b>The shared plaintext seed literal is never transcribed.</b> Rule 1 clause D is
 * unconditional: no secrets in code, logs, tests or config. The value shared by all ten rows at
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44} therefore appears nowhere in this file - not in code, not in a
 * comment, not in this documentation - and must appear nowhere else under {@code src/}. The downstream
 * obligation is explicit: {@code V3__seed_data.sql} stores the ten users only as precomputed BCrypt
 * strength 10 hashes, and must never embed the plaintext value it hashes. No BCrypt hash literal appears
 * in this file either, in any of its version prefixes.
 *
 * <p><b>Fixed width CHAR columns need an explicit JDBC type code.</b> Four of the five
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
 * <p><b>This class is not a security principal.</b> It does not implement Spring Security's
 * user-details contract and declares none of that contract's authorisation state: no activation flag, no
 * expiry or lock flags, no granted-authority collection, no roles. None of those exists in the 80 byte
 * <p><b>This class is not a security principal.</b> It does not implement Spring Security's
 * Spring Security into a pure data holder - this file imports nothing from Spring at all. The
 * adaptation from this entity to a principal belongs to
 * {@code com.cardemo.security.CardDemoUserDetailsService}, and this package must not import from the
 * security package - the dependency runs the other way. Nothing from Spring, from
 * {@code com.cardemo.exception}, or from the repository, service, controller, batch, config or
 * observability packages is imported here.
 *
 * <p><b>Fixed width text semantics: a blank but non null value must load.</b> Four columns are
 * fixed width {@code CHAR}, so values arrive blank padded to their declared widths and a value of
 * nothing but spaces is legitimate legacy data. Consequently there is no bean validation on any field:
 * no not-blank, not-empty, pattern or minimum size constraint annotation appears anywhere in this class,
 * and the four non credential members are stored exactly as received, unfolded and untrimmed. What is
 * asserted imperatively for them is only what the record layout itself fixes - that the value is present,
 * and that a character value is no wider than its picture clause - which admits every blank padded value
 * the source can produce. {@code nullable = false} on the column remains in place as the schema level
 * backstop that also covers the provider's own reflective writes.
 *
 * <p>The credential is the one deliberate exception, and it is an exception of kind rather than of
 * degree: it is the only member whose column is not a fixed width transcription of a source field, so
 * there is no blank padded, short or plaintext value it could legitimately hold. Its invariant is
 * enforced imperatively at every boundary that can set it - the all columns constructor, the setter, and
 * a {@code PrePersist} and {@code PreUpdate} callback that also covers field access and provider
 * materialisation - rather than by an annotation, because the constraint is a structural shape rather
 * than a presence or size rule and because it must hold at the database boundary without a validation
 * provider being present. See {@link #setPasswordHash(String)} for the rule and its evidence.
 *
 * <p>No accessor trims, folds case or normalises in any way; trimming is a
 * presentation concern that belongs to the DTO layer, and a getter that trimmed would hide the very
 * padding a fixed width defect shows up as. Note in particular that the sign-on program upper cases
 * <em>both</em> the identifier and the presented password before comparing them -
 * {@code app/cbl/COSGN00C.cbl:L132} and {@code :L135} - which is service layer behaviour that this
 * entity neither implements nor interferes with. Were any case or format operation ever added here it
 * would have to pin {@code Locale.ROOT} to stay deterministic under Rule 1 clause C2, but none is
 * needed and none is present.
 * <p><b>No alternate index, and none is wanted.</b> {@code USRSEC} has no alternate index: the
 * catalogue's three alternate indexes belong to {@code CARDDATA}, {@code CARDXREF} and
 * {@code TRANSACT}, and {@code V2__create_indexes.sql} accordingly creates nothing for this table beyond
 * its primary key. That is sufficient rather than merely economical - the user list screen
 * {@code app/cbl/COUSR00C.cbl} pages in primary key order at ten rows per page, which the primary key
 * index already serves, so a second index would be write amplification for no read benefit.
 *
 * <h2>The schema this mapping requires</h2>
 *
 * <p>The mapping is reconciled against real DDL rather than asserted: {@code V1__create_schema.sql}
 * declares {@code CREATE TABLE user_security} with 5 columns whose names are identical, as a set, to the
 * 5 {@code @Column(name = ...)} declarations above. Under
 * {@code spring.jpa.hibernate.ddl-auto: validate}, mandated in every profile, any divergence in column
 * name, type, length or nullability fails application context startup outright rather than degrading
 * quietly - which is why the contract below has to be met exactly. Binding this class with Hibernate
 * against the PostgreSQL dialect resolves to {@code sec_usr_id char(8)},
 * {@code sec_usr_fname char(20)}, {@code sec_usr_lname char(20)}, {@code sec_usr_pwd varchar(60)} and
 * {@code sec_usr_type char(1)}, all non null. The contract is:
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
 *   </ul>
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
 * <p>Build from the repository root with the pinned wrapper. The one prerequisite is stated as a capability
 * rather than as a host path: JDK 25 on {@code PATH} with {@code JAVA_HOME} set, however the host provides
 * it, with Maven 3.9.11 supplied by the wrapper itself. Then:
 * {@code ./mvnw -B clean compile} to compile, which runs under {@code -Xlint:all -Werror} with
 * {@code failOnWarning} set, so any warning at all is a build failure; {@code ./mvnw -B clean test} for
 * the unit suite; {@code ./mvnw -B clean verify} for the full gate, which additionally enforces the 80
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
 *   </ul>
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
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class UserSecurity {

    /**
     * Width of the user identifier column: 8 characters, from {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L18}. This is also the cluster's key length, declared as {@code KEYS(8,0)} at
     * {@code app/jcl/DUSRSECJ.jcl:L65} and reported as {@code KEYLEN 8} at {@code app/catlg/LISTCAT.txt:L3883}.
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
     */
    private static final int BCRYPT_HASH_WIDTH = 60;

    /**
     * The exact shape a stored credential must have: a BCrypt digest at strength 10, sixty characters
     * long. This is the invariant the credential member enforces, and it is the reason no arbitrary
     * string can reach {@code sec_usr_pwd}.
     *
     * <p>The three parts are each pinned by evidence rather than by convention:
     *
     * <ul>
     *   <li><b>the version tag is one of {@code 2a}, {@code 2b} or {@code 2y}, and nothing else.</b>
     *       That is exactly the set the verifier accepts, which is what makes the set correct rather
     *       than merely conventional. {@code BCryptPasswordEncoder.BCryptVersion} of
     *       {@code spring-security-crypto} <b>6.5.11</b> - the version {@code pom.xml} resolves through its
     *       forward override, not the 6.5.8 the parent manages - declares exactly three constants, for those
     *       three
     *       tags, and {@code BCrypt.gensalt(String, int, SecureRandom)} rejects any other third
     *       character with {@code IllegalArgumentException("Invalid prefix")}. The historical
     *       {@code 2x} tag is therefore deliberately excluded: the encoder can neither produce nor
     *       verify one, so storing it would store a credential that could never authenticate
     *       anybody;</li>
     *   <li><b>the cost factor is exactly {@code 10}.</b> The migration rule for this field pins
     *       BCrypt strength 10, so a digest at any other cost is not the artefact this column holds,
     *       even though the algorithm would happily verify it. Pinning the cost here is what makes the
     *       security audit's every-password-hashed-at-the-pinned-strength assertion provable against
     *       the ten seeded users rather than merely asserted;</li>
     *   <li><b>the remaining 53 characters are BCrypt's radix-64 alphabet</b> - {@code .}, {@code /},
     *       the two cases of the Latin alphabet and the ten digits - which is the 22 character salt
     *       followed by the 31 character digest. Seven prefix characters plus 53 gives the 60 the
     *       column is declared to hold.</li>
     * </ul>
     *
     * <p>The pattern is expressed with a character class for the version tag rather than by spelling
     * the three concrete prefixes out, so that no BCrypt prefix literal appears anywhere in this file
     * in any of its versions - the prohibition recorded above. It is
     * compiled once into a constant rather than per call, because it is evaluated on every credential
     * assignment and on every insert and update of this table.
     */
    private static final Pattern BCRYPT_STRENGTH_10_SHAPE =
            Pattern.compile("^\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}$");

    /**
     * Width of the user type column: 1 character, from {@code SEC-USR-TYPE PIC X(01)} at
     * {@code app/cpy/CSUSR01Y.cpy:L22}, carrying {@code A} or {@code U}.
     */
    private static final int USER_TYPE_WIDTH = 1;

    /**
     * User identifier and primary key, from {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_id", nullable = false, length = USER_ID_WIDTH)
    private String secUsrId;

    /**
     * User first name, from {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L19}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_fname", nullable = false, length = FIRST_NAME_WIDTH)
    private String secUsrFname;

    /**
     * User last name, from {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L20}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_lname", nullable = false, length = LAST_NAME_WIDTH)
    private String secUsrLname;

    /**
     * BCrypt hash of the user's password - <b>never the password itself</b>. Replaces
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21}, which held plaintext.
     */
    @Column(name = "sec_usr_pwd", nullable = false, length = BCRYPT_HASH_WIDTH)
    private String passwordHash;

    /**
     * User class, from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}: byte 57 of the
     * record, carrying {@code A} for an administrator or {@code U} for a standard user.
     */
    @Convert(converter = UserTypeConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sec_usr_type", nullable = false, length = USER_TYPE_WIDTH)
    private UserType secUsrType;

    /**
     * No argument constructor required by the JPA specification, which the persistence provider uses to
     * materialise an instance before populating its state.
     */
    protected UserSecurity() {
        // Intentionally empty: JPA populates the persistent state directly after construction.
    }

    /**
     * Creates a fully populated user, which is how application, migration and test code should build one.
     *
     * @param secUsrId the eight character identifier for {@code sec_usr_id}, from {@code SEC-USR-ID PIC X(08)}.
     * @param secUsrFname the first name for {@code sec_usr_fname}, from {@code SEC-USR-FNAME PIC X(20)}, blank
     * padded to 20 characters by the column
     * @param secUsrLname the last name for {@code sec_usr_lname}, from {@code SEC-USR-LNAME PIC X(20)}, blank
     * padded to 20 characters by the column
     * @param passwordHash the BCrypt hash for {@code sec_usr_pwd} - a sixty character hash, never a password in
     * plaintext and never a hash produced at any other strength than the one the security configuration pins
     * @param secUsrType the user class for {@code sec_usr_type}, from {@code SEC-USR-TYPE PIC X(01)}.
     */
    public UserSecurity(String secUsrId, String secUsrFname, String secUsrLname, String passwordHash,
            UserType secUsrType) {
        this.secUsrId = requireWidth(secUsrId, "secUsrId", "SEC-USR-ID PIC X(08)", USER_ID_WIDTH);
        this.secUsrFname = requireWidth(secUsrFname, "secUsrFname", "SEC-USR-FNAME PIC X(20)",
                FIRST_NAME_WIDTH);
        this.secUsrLname = requireWidth(secUsrLname, "secUsrLname", "SEC-USR-LNAME PIC X(20)",
                LAST_NAME_WIDTH);
        this.passwordHash = requireBcryptStrength10Digest(passwordHash);
        this.secUsrType = requireUserType(secUsrType);
    }

    /**
     * Returns the user identifier, the primary key, from {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy:L18}.
     *
     * @return the eight character identifier exactly as held, blank padded to 8 by the {@code CHAR(8)} column
     * when read back from the database and never trimmed, or {@code null} on a transient instance built by the
     * no argument constructor
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the user identifier, from {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18}.
     *
     * @param secUsrId the eight character identifier to store in {@code sec_usr_id}
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = requireWidth(secUsrId, "secUsrId", "SEC-USR-ID PIC X(08)", USER_ID_WIDTH);
    }

    /**
     * Returns the user's first name, from {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L19}.
     *
     * @return the name exactly as held, which after a database read is blank padded to 20 characters and is not
     * trimmed, or {@code null} on a transient instance built by the no argument constructor
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user's first name, from {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L19}.
     *
     * @param secUsrFname the first name to store in {@code sec_usr_fname}; must not be {@code null} and
     *                    must be at most {@value #FIRST_NAME_WIDTH} characters
     * @throws IllegalArgumentException if {@code secUsrFname} is {@code null} or longer than
     *                                  {@value #FIRST_NAME_WIDTH} characters
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = requireWidth(secUsrFname, "secUsrFname", "SEC-USR-FNAME PIC X(20)",
                FIRST_NAME_WIDTH);
    }

    /**
     * Returns the user's last name, from {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L20}.
     *
     * @return the name exactly as held, which after a database read is blank padded to 20 characters and is not
     * trimmed, or {@code null} on a transient instance built by the no argument constructor
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user's last name, from {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:L20}.
     *
     * @param secUsrLname the last name to store in {@code sec_usr_lname}; must not be {@code null} and
     *                    must be at most {@value #LAST_NAME_WIDTH} characters
     * @throws IllegalArgumentException if {@code secUsrLname} is {@code null} or longer than
     *                                  {@value #LAST_NAME_WIDTH} characters
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = requireWidth(secUsrLname, "secUsrLname", "SEC-USR-LNAME PIC X(20)",
                LAST_NAME_WIDTH);
    }

    /**
     * Returns the BCrypt hash of the user's password.
     *
     * @return the sixty character BCrypt hash exactly as stored, never blank padded because the column is
     * {@code VARCHAR}, or {@code null} on a transient instance built by the no argument constructor
     * <p>{@link JsonIgnore} is applied here in addition to the class level barrier documented on the type.
     * The barrier alone already hides this property, so this annotation is defence in depth: it keeps the
     * credential invisible even if a caller reintroduces bean visibility through a Jackson mix-in or a
     * custom introspector, and it states the prohibition at the accessor a reader is looking at rather
     * than only at the top of the file.
     *
     */
    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Sets the BCrypt hash of the user's password.
     *
     * @param passwordHash the sixty character BCrypt hash to store in {@code sec_usr_pwd}
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = requireBcryptStrength10Digest(passwordHash);
    }

    /**
     * Returns the user class, from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}.
     *
     * @return {@link UserType#ADMIN} when the column holds {@code A} and {@link UserType#USER} when it holds
     * {@code U}.
     */
    public UserType getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user class, from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:L22}.
     *
     * @param secUsrType the user class to store in {@code sec_usr_type}
     */
    public void setSecUsrType(UserType secUsrType) {
        this.secUsrType = requireUserType(secUsrType);
    }

    /**
     * Re-asserts the credential invariant immediately before this row is inserted or updated, so that
     * the invariant holds at the database boundary and not merely at the two setters that lead to it.
     *
     * <h4>Why a callback is needed when the constructor and setter already guard</h4>
     *
     * <p>Those two guards cover every path application code can take, but they are not the only paths a
     * persistent field has. The persistence provider populates a materialised instance through field
     * access, and reflection reaches a private field directly; neither route runs a setter. This
     * callback closes both, because the provider invokes it on the instance it is about to write
     * whatever route put the state there. The result is that a malformed credential cannot be persisted
     * by any means: not by construction, not by mutation, not by field injection and not by a provider
     * managed instance that was never fully populated.
     *
     * <p>It also gives the never-populated case a diagnostic worth having. An instance built through the
     * no argument constructor and flushed without its credential being set would otherwise fail as a
     * not-null violation naming a column, from inside the driver, after the statement was built. Here it
     * fails before the statement exists, naming the invariant and the entity.
     *
     * <h4>Why this is the enforcement point rather than a sixth CHECK constraint</h4>
     *
     * <p>A database {@code CHECK} would be the obvious place for a shape rule, and it is deliberately
     * not used. The migration's constraint budget is fixed at exactly five {@code CHECK} constraints by
     * the schema requirement this project works to - eleven tables, not null on every column, five check
     * constraints, ten foreign keys - and {@code V1__create_schema.sql} records that ceiling explicitly,
     * naming all five and listing every candidate constraint that was considered and excluded to hold
     * the budget. Adding a sixth would contradict the schema contract, so the equivalent persistence
     * invariant lives here instead, where it is additionally portable across dialects and testable
     * without a database.
     *
     * <h4>Inputs, outputs and error modes</h4>
     *
     * <p>Takes nothing, returns nothing and mutates nothing: it reads one field and either returns or
     * throws. It performs no I/O, so it cannot slow a flush by more than a regular expression match over
     * sixty characters. Throwing from a lifecycle callback marks the surrounding transaction for
     * rollback, which is the correct outcome for a violated invariant - the alternative, writing the row
     * and reporting later, is exactly what this class exists to prevent.
     *
     * <p>Declared {@code private} because the specification permits a lifecycle callback at any access
     * level and nothing outside this class has any business invoking it; the provider reaches it
     * reflectively. A single method carries both annotations because insert and update need the identical
     * assertion, and the specification allows one method to serve more than one lifecycle event.
     *
     * @throws IllegalArgumentException if the credential is {@code null} or is not a BCrypt strength 10
     *                                  digest of exactly {@value #BCRYPT_HASH_WIDTH} characters. The
     *                                  message names the defect and never reproduces the offending value
     */
    @PrePersist
    @PreUpdate
    private void assertCredentialInvariantBeforeWrite() {
        requireBcryptStrength10Digest(this.passwordHash);
    }

    /**
     * Returns the argument if it is a BCrypt digest at the pinned strength of 10, and throws otherwise.
     *
     * <h4>What it checks, and in what order</h4>
     *
     * <p>Three tests, ordered so that the message can name the most specific defect it can prove. First
     * {@code null}, which is the staged-construction mistake. Then the length, because a wrong length is
     * the single most common defect - a source width value, a truncated digest, a blank run - and
     * reporting the observed length is far more useful than reporting that a pattern did not match.
     * Then the shape, which covers the version tag, the cost factor and the radix-64 alphabet together.
     *
     * <h4>Why the message never contains the value</h4>
     *
     * <p>The argument is credential material, so no branch interpolates it. Rule 1 clause D is
     * unconditional about secrets in logs, and an exception message is a log line in every deployment
     * this project has: it reaches the structured logger, the trace and any error response the exception
     * handler renders. The masking rules in {@code logback-spring.xml} are a backstop for accidents, not
     * a licence to emit the value deliberately. What the message does carry is the observed length and
     * the structural requirement that was not met, which is everything a caller needs to fix the defect
     * and nothing an attacker can use. The observed length is not credential material: it is a property
     * of the column, already public in this documentation.
     *
     * <p>This is the deliberate divergence from the sibling composite key classes, whose validators
     * report the received value back in the message. That is correct for an account identifier or a
     * category code and wrong for a credential, and the difference is the reason this helper is written
     * out rather than delegated to a shared one.
     *
     * <h4>Why static</h4>
     *
     * <p>It observes no instance state and it is called from a constructor. A non static helper called
     * from a constructor of a non final class is precisely the {@code this-escape} pattern that
     * {@code -Xlint:all} reports and {@code -Werror} fails the build on, so {@code static} is load
     * bearing rather than incidental.
     *
     * @param candidate the value offered for {@code sec_usr_pwd}, which may be {@code null}
     * @return the same value, unchanged, when it satisfies the invariant - never a repaired,
     *         trimmed, padded or re-encoded value, because repairing a credential silently is worse
     *         than rejecting it loudly
     * @throws IllegalArgumentException if the value is {@code null}, is not exactly
     *                                  {@value #BCRYPT_HASH_WIDTH} characters, or does not carry a
     *                                  {@code 2a}, {@code 2b} or {@code 2y} version tag, a cost factor
     *                                  of 10 and a radix-64 salt and digest
     */
    private static String requireBcryptStrength10Digest(final String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "sec_usr_pwd must hold a BCrypt strength 10 digest, but was null. This class never "
                            + "hashes and never holds a password in plaintext: the caller supplies a "
                            + "digest already computed by the authentication service's encoder");
        }
        if (candidate.length() != BCRYPT_HASH_WIDTH) {
            throw new IllegalArgumentException(
                    "sec_usr_pwd must hold a BCrypt strength 10 digest of exactly " + BCRYPT_HASH_WIDTH
                            + " characters, but the value offered is " + candidate.length()
                            + " characters. The value itself is credential material and is deliberately "
                            + "not reproduced in this message");
        }
        if (!BCRYPT_STRENGTH_10_SHAPE.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "sec_usr_pwd must hold a BCrypt strength 10 digest: the value offered is "
                            + BCRYPT_HASH_WIDTH + " characters but is not one. Its version tag must be "
                            + "2a, 2b or 2y - the only three the verifier accepts - its cost factor must "
                            + "be 10, and its salt and digest must use BCrypt's radix-64 alphabet. The "
                            + "value itself is credential material and is deliberately not reproduced "
                            + "in this message");
        }
        return candidate;
    }

    /**
     * Returns {@code value} when it is present and fits the width of the COBOL field it comes from, and
     * otherwise reports the offending property and the length received.
     *
     * <p>Rejects {@code null}, because COBOL has no null - a {@code PIC X(n)} field always holds its
     * declared width - and because every column of this table is {@code NOT NULL}. Rejects any value
     * longer than the picture clause declares, because the 80 byte record cannot carry one and a
     * {@code CHAR(n)} column would otherwise report a failure naming only a column. Everything the
     * picture clause admits is accepted, a value of only spaces included, and nothing is trimmed,
     * padded or case folded: the sign on comparison upper cases the identifier itself, and doing it here
     * would move a service decision into a data holder.
     *
     * <p><strong>The message reports the received length and never the value.</strong> This entity is
     * the credential record, and while the identifier and the two names are not themselves secret they
     * sit alongside {@code sec_usr_pwd}; a guard here holds the same line as
     * {@link #requireBcryptStrength10Digest(String)} rather than a weaker one, so that no message from
     * this class can ever be the thing that puts user material into a log.
     *
     * <p>Static for the same reason as {@link #requireBcryptStrength10Digest(String)}: it is called from
     * a constructor of a class the JPA specification forbids making {@code final}, and a non static
     * helper on that path is the {@code this-escape} pattern that {@code -Xlint:all -Werror} fails the
     * build on.
     *
     * @param value      the value offered by the caller, which may be {@code null}
     * @param property   the Java property name, used in the message
     * @param cobolField the originating COBOL item and its picture clause, used in the message
     * @param width      the declared width of that field in characters
     * @return the same value, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or longer than {@code width}
     */
    private static String requireWidth(final String value, final String property,
            final String cobolField, final int width) {
        if (value == null) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must not be null: it maps to a NOT NULL CHAR(" + width
                    + ") column of table user_security");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(property + " (" + cobolField + ") must be at most " + width
                    + " characters but the value offered is " + value.length()
                    + " characters. The value itself is deliberately not reproduced in this message");
        }
        return value;
    }

    /**
     * Returns {@code value} when it is present, and otherwise reports that the user class is missing.
     *
     * <p>The enumeration already closes the domain to the two codes {@code SEC-USR-TYPE PIC X(01)}
     * admits, so {@code null} is the only value outside it that can reach a caller of this method.
     * Refusing it here rather than on flush means the failure names the property at the call site that
     * caused it instead of surfacing a constraint violation from the driver much later. Static for the
     * same reason as {@link #requireWidth(String, String, String, int)}.
     *
     * @param value the value offered for {@code sec_usr_type}, which may be {@code null}
     * @return the same value, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static UserType requireUserType(final UserType value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "secUsrType (SEC-USR-TYPE PIC X(01)) must not be null: it maps to a NOT NULL CHAR(1) "
                            + "column of table user_security, and the two codes the source admits are "
                            + "A for an administrator and U for a standard user");
        }
        return value;
    }

    /**
     * Compares two users on {@code secUsrId} alone.
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
     * @return the hash code of the primary key, or 0 when the key is {@code null}
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(secUsrId);
    }

    /**
     * Returns a deliberately narrow diagnostic rendering: the identifier and the user class, and nothing else.
     *
     * @return a single line rendering of the identifier and the user class, never {@code null} and never
     * containing credential material
     */
    @Override
    public String toString() {
        return "UserSecurity{secUsrId=" + secUsrId + ", secUsrType=" + secUsrType + "}";
    }

    /**
     * Maps {@link UserType} onto the single character {@code sec_usr_type} column, and back.
     */
    @Converter(autoApply = false)
    public static class UserTypeConverter implements AttributeConverter<UserType, String> {

        /**
         * Creates a converter instance.
         */
        public UserTypeConverter() {
            // Intentionally empty: the converter is stateless, so there is nothing to initialise.
        }

        /**
         * Converts a user class to the single character code the column stores.
         *
         * @param attribute the user class to write; may be {@code null}
         * @return {@code "A"} for an administrator and {@code "U"} for a standard user, or {@code null} when
         * {@code attribute} is {@code null}, so that a null attribute becomes a SQL null and is rejected by the
         * non null column rather than being written as some substitute character
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
         * @param dbData the raw column value; may be {@code null}, may be padding only, and may be malformed
         * @return the matching user class, or {@code null} when {@code dbData} is {@code null} or consists only
         * of padding
         * @throws IllegalArgumentException if the padding stripped value is neither {@code A} nor {@code U}.
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
                    "Unrecognised sec_usr_type code of " + unpadded.length()
                            + " UTF-16 code unit(s) at code point(s) " + hexUnitsOf(unpadded)
                            + " read from column sec_usr_type of table user_security (the rejected "
                            + "value is withheld from this message so that a control character in "
                            + "corrupt data cannot forge a log entry); app/cpy/COCOM01Y.cpy:L26 "
                            + "declares CDEMO-USER-TYPE as PIC X(01) and :L27-L28 define exactly two "
                            + "codes, 'A' for ADMIN and 'U' for USER, so either the CHECK constraint "
                            + "on this column is missing from V1__create_schema.sql or the row was "
                            + "written around it"));
        }

        /**
         * Renders each UTF-16 code unit of a rejected value in hexadecimal, space separated.
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
