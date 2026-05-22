/*
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
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code customers} table (Flyway
 * migration {@code V003__create_customer.sql}). This entity is the
 * Java target for the COBOL {@code CUSTOMER-RECORD} layout defined in
 * {@code app/cpy/CVCUS01Y.cpy} (RECLN = 500 bytes), and replaces the
 * mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>Represents a credit-card customer with their full demographic,
 * address, identity, contact, and credit-rating profile. Read by:
 * <ul>
 *   <li>{@code AccountViewService} (COBOL {@code COACTVWC}) &mdash;
 *       joins customers onto accounts via the {@code card_xref} table
 *       to render the account-view screen; the 18 customer attributes
 *       (name, address, phone, SSN, govt-id, DOB, FICO, EFT routing,
 *       primary-cardholder indicator) are surfaced directly into
 *       {@code AccountViewDto}.</li>
 *   <li>{@code AccountUpdateService} (COBOL {@code COACTUPC}) &mdash;
 *       updates customer demographic columns inside the same
 *       {@code @Transactional} boundary that updates the account row;
 *       the {@code cust_id} is fetched via the card-xref join.</li>
 *   <li>{@code CustomerFileReaderService} (COBOL {@code CBCUS01C})
 *       &mdash; batch sequential scanner that emits every row in the
 *       customers table for audit / reporting purposes.</li>
 *   <li>{@code StatementGenerationService} (COBOL {@code CBSTM03A} /
 *       {@code CBSTM03B}) &mdash; prints the customer name and mailing
 *       address as the statement header.</li>
 * </ul>
 *
 * <h2>PII / PCI-DSS NOTICE (AAP &sect;0.6.6)</h2>
 * <p>This entity contains personally identifiable information (PII).
 * The most sensitive fields are:
 * <ul>
 *   <li>{@link #custSsn} &mdash; Social Security Number
 *       ({@code PIC 9(09)}); the single most sensitive PII column in
 *       the entire CardDemo schema.</li>
 *   <li>{@link #custGovtIssuedId} &mdash; government-issued identifier
 *       (driver's license, passport, state-ID number).</li>
 *   <li>{@link #custDobYyyyMmDd} &mdash; date of birth.</li>
 *   <li>{@link #custFicoCreditScore} &mdash; FICO credit score.</li>
 *   <li>Full name, address, and phone numbers.</li>
 * </ul>
 *
 * <p>PCI-DSS / PII protection is layered (AAP &sect;0.6.6):
 * <ol>
 *   <li><b>Encryption at rest</b> &mdash; RDS storage encrypted via a
 *       customer-managed KMS CMK (configured in
 *       {@code infrastructure/terraform/rds.tf}). No column-level
 *       pgcrypto encryption is applied at the schema layer per the
 *       AAP &sect;0.6.6 architectural decision; encryption is a
 *       storage-layer concern.</li>
 *   <li><b>Encryption in transit</b> &mdash; JDBC connections require
 *       TLS 1.2+ via the {@code rds.force_ssl=1} RDS parameter group
 *       setting; the {@code sslmode=require} JDBC URL parameter is
 *       set in {@code application-prod.yml}.</li>
 *   <li><b>Continuous monitoring</b> &mdash; Amazon Macie scans S3
 *       export / Glue ETL outputs for accidental SSN exposure; any
 *       leak triggers a Macie finding and a CloudWatch alarm.</li>
 *   <li><b>Log masking</b> &mdash; the {@link #toString()} method
 *       MASKS the SSN to its last 4 digits ({@code ***-**-1234}),
 *       MASKS the government-issued ID to a prefix marker
 *       ({@code GOVT-****}), and OMITS the phone numbers and full
 *       date of birth so that a stray {@code log.info(customer)} or
 *       implicit string concatenation cannot leak credential or PII
 *       material into CloudWatch Logs or OpenSearch.</li>
 * </ol>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook (canonical):</b> {@code app/cpy/CVCUS01Y.cpy}
 *       &mdash; 500-byte fixed-width record layout with 18 business
 *       fields plus a 168-byte trailing FILLER. The FILLER has no
 *       relational equivalent and is omitted from this entity. The
 *       {@code CUST-DOB-YYYY-MM-DD} field name (with hyphens) is the
 *       canonical form adopted by V003 and this Java entity per AAP
 *       &sect;0.4.1.</li>
 *   <li><b>COBOL copybook (variant):</b> {@code app/cpy/CUSTREC.cpy}
 *       &mdash; identical 500-byte layout; differs only in that the
 *       DOB field is named {@code CUST-DOB-YYYYMMDD} (no hyphens).
 *       Both copybooks refer to the same 10-byte position in the
 *       record. This entity is canonical for both copybooks per AAP
 *       &sect;0.4.1.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} &mdash;
 *       KEYS(9 0), RECORDSIZE(500 500), INDEXED, CYLINDERS(1 5)
 *       (per {@code app/jcl/CUSTFILE.jcl}:L46-L59 and
 *       {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>COBOL consumers:</b>
 *       {@code app/cbl/COACTVWC.cbl} (account view),
 *       {@code app/cbl/COACTUPC.cbl} (account update),
 *       {@code app/cbl/CBCUS01C.cbl} (batch sequential reader),
 *       {@code app/cbl/CBSTM03A.CBL} /
 *       {@code app/cbl/CBSTM03B.CBL} (statement generation).</li>
 *   <li><b>JCL DD allocation:</b> {@code app/jcl/CUSTFILE.jcl}
 *       (IDCAMS DEFINE CLUSTER + IDCAMS REPRO loading
 *       {@code CUSTDATA.PS} into the KSDS). Bulk customer master data
 *       is loaded to RDS by an AWS Glue Spark job per AAP &sect;0.6.2;
 *       Flyway does NOT seed customer rows.</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V003__create_customer.sql}.</li>
 *   <li><b>Golden fixture:</b> {@code app/data/ASCII/custdata.txt}.</li>
 * </ul>
 *
 * <h2>Field-to-column mapping</h2>
 * <table>
 *   <caption>COBOL CVCUS01Y.cpy &harr; PostgreSQL customers</caption>
 *   <tr><th>COBOL field</th><th>PIC clause</th><th>Java field</th>
 *       <th>JPA column</th><th>Notes</th></tr>
 *   <tr><td>CUST-ID</td><td>9(09)</td><td>{@link #custId}</td>
 *       <td>{@code cust_id NUMERIC(9) PK}</td>
 *       <td>VSAM KSDS primary key (RKP=0, KEYLEN=9)</td></tr>
 *   <tr><td>CUST-FIRST-NAME</td><td>X(25)</td><td>{@link #custFirstName}</td>
 *       <td>{@code cust_first_name VARCHAR(25) NN}</td>
 *       <td>Required for statement-header rendering</td></tr>
 *   <tr><td>CUST-MIDDLE-NAME</td><td>X(25)</td><td>{@link #custMiddleName}</td>
 *       <td>{@code cust_middle_name VARCHAR(25)}</td>
 *       <td>Nullable; all-spaces COBOL value maps to NULL</td></tr>
 *   <tr><td>CUST-LAST-NAME</td><td>X(25)</td><td>{@link #custLastName}</td>
 *       <td>{@code cust_last_name VARCHAR(25) NN}</td>
 *       <td>Required for statement-header rendering</td></tr>
 *   <tr><td>CUST-ADDR-LINE-1</td><td>X(50)</td><td>{@link #custAddrLine1}</td>
 *       <td>{@code cust_addr_line_1 VARCHAR(50) NN}</td>
 *       <td>Required for statement mailing</td></tr>
 *   <tr><td>CUST-ADDR-LINE-2</td><td>X(50)</td><td>{@link #custAddrLine2}</td>
 *       <td>{@code cust_addr_line_2 VARCHAR(50)}</td>
 *       <td>Nullable; apartment / unit / PO Box line</td></tr>
 *   <tr><td>CUST-ADDR-LINE-3</td><td>X(50)</td><td>{@link #custAddrLine3}</td>
 *       <td>{@code cust_addr_line_3 VARCHAR(50)}</td>
 *       <td>Nullable; additional locality details</td></tr>
 *   <tr><td>CUST-ADDR-STATE-CD</td><td>X(02)</td><td>{@link #custAddrStateCd}</td>
 *       <td>{@code cust_addr_state_cd CHAR(2) NN}</td>
 *       <td>{@code columnDefinition = "CHAR(2)"}; validated by
 *           {@code ValidationLookupService}</td></tr>
 *   <tr><td>CUST-ADDR-COUNTRY-CD</td><td>X(03)</td><td>{@link #custAddrCountryCd}</td>
 *       <td>{@code cust_addr_country_cd CHAR(3) NN}</td>
 *       <td>{@code columnDefinition = "CHAR(3)"}; e.g., {@code "USA"}</td></tr>
 *   <tr><td>CUST-ADDR-ZIP</td><td>X(10)</td><td>{@link #custAddrZip}</td>
 *       <td>{@code cust_addr_zip VARCHAR(10) NN}</td>
 *       <td>US ZIP / ZIP+4 or international postal code</td></tr>
 *   <tr><td>CUST-PHONE-NUM-1</td><td>X(15)</td><td>{@link #custPhoneNum1}</td>
 *       <td>{@code cust_phone_num_1 VARCHAR(15)}</td>
 *       <td>Nullable; primary phone</td></tr>
 *   <tr><td>CUST-PHONE-NUM-2</td><td>X(15)</td><td>{@link #custPhoneNum2}</td>
 *       <td>{@code cust_phone_num_2 VARCHAR(15)}</td>
 *       <td>Nullable; alternate phone</td></tr>
 *   <tr><td>CUST-SSN</td><td>9(09)</td><td>{@link #custSsn}</td>
 *       <td>{@code cust_ssn NUMERIC(9) NN}</td>
 *       <td><b>PII &mdash; masked in {@code toString()}</b></td></tr>
 *   <tr><td>CUST-GOVT-ISSUED-ID</td><td>X(20)</td><td>{@link #custGovtIssuedId}</td>
 *       <td>{@code cust_govt_issued_id VARCHAR(20)}</td>
 *       <td>Nullable; <b>PII &mdash; masked in {@code toString()}</b></td></tr>
 *   <tr><td>CUST-DOB-YYYY-MM-DD</td><td>X(10)</td><td>{@link #custDobYyyyMmDd}</td>
 *       <td>{@code cust_dob_yyyy_mm_dd DATE NN}</td>
 *       <td>COBOL {@code X(10)} string mapped to native
 *           {@link LocalDate}; omitted from {@code toString()}</td></tr>
 *   <tr><td>CUST-EFT-ACCOUNT-ID</td><td>X(10)</td><td>{@link #custEftAccountId}</td>
 *       <td>{@code cust_eft_account_id VARCHAR(10)}</td>
 *       <td>Nullable; EFT routing for bill payments</td></tr>
 *   <tr><td>CUST-PRI-CARD-HOLDER-IND</td><td>X(01)</td><td>{@link #custPriCardHolderInd}</td>
 *       <td>{@code cust_pri_card_holder_ind CHAR(1) NN}</td>
 *       <td>{@code columnDefinition = "CHAR(1)"}; {@code 'Y'}/{@code 'N'} flag</td></tr>
 *   <tr><td>CUST-FICO-CREDIT-SCORE</td><td>9(03)</td><td>{@link #custFicoCreditScore}</td>
 *       <td>{@code cust_fico_credit_score NUMERIC(3) NN}</td>
 *       <td>V003 CHECK constraint enforces range 300..850</td></tr>
 *   <tr><td>FILLER</td><td>X(168)</td><td>&mdash;</td>
 *       <td>&mdash;</td>
 *       <td>OMITTED (relational layouts have no positional padding)</td></tr>
 * </table>
 *
 * <h2>JPA contract</h2>
 * <ul>
 *   <li>{@code @Entity} + {@code @Table(name = "customers")} &mdash;
 *       table name matches V003 DDL exactly so that Hibernate
 *       {@code ddl-auto: validate} accepts this entity at startup.</li>
 *   <li>{@code @Id} on {@link #custId} &mdash; the entity's primary
 *       key; no {@code @GeneratedValue} because the identifier
 *       originates upstream (the 9-digit customer-ID from the source
 *       VSAM KSDS) and must be preserved verbatim during the
 *       parallel-run period for byte-accurate parity validation.</li>
 *   <li>Implements {@link Serializable} with an explicit
 *       {@code serialVersionUID} so instances can cross persistence-
 *       context boundaries, Spring Session caches, and JPA L2 caches
 *       deterministically.</li>
 *   <li><b>No {@code @Version}</b> &mdash; per AAP &sect;0.4.1 the
 *       Customer entity intentionally does NOT use optimistic locking
 *       (unlike {@code Account} and {@code Card}). Customer
 *       demographic updates are not multi-step CICS
 *       {@code READ UPDATE} flows in the source COBOL; the only
 *       customer-update path is {@code COACTUPC} which already wraps
 *       the {@code Account} + {@code Customer} pair inside a single
 *       {@code @Transactional(rollbackFor = Exception.class)}
 *       boundary on the {@code Account} entity's {@code @Version}
 *       check. Adding {@code @Version} to {@code Customer} would
 *       introduce spurious optimistic-lock conflicts.</li>
 *   <li><b>No {@code @OneToMany}, no {@code @ManyToOne}</b> &mdash;
 *       per the Minimal Change Clause (AAP &sect;0.7.3) the
 *       relationship from {@code Account} or {@code CardCrossReference}
 *       to {@code Customer} is modeled as a scalar foreign key
 *       (e.g., {@code account.cust_id} {@code long}) rather than as a
 *       JPA association. This mirrors the COBOL flat-file relational
 *       model (no embedded references), enables straightforward Glue
 *       Spark bulk loads, and avoids cascading fetch-graph behavior
 *       that would diverge from the COBOL access patterns.</li>
 *   <li><b>Customer deletion does NOT cascade to Account</b> per
 *       regulatory requirements (AAP &sect;0.3.1) &mdash; orphaned
 *       customer rows must remain referenceable for historical
 *       transaction-trail and statement-archive purposes.</li>
 *   <li><b>No JSON-binding annotations</b> &mdash; this entity is a
 *       pure persistence model. REST DTOs (e.g.,
 *       {@code AccountViewDto}, {@code AccountUpdateDto}) under
 *       {@code com.awsm2.carddemo.dto} carry the API contracts.</li>
 *   <li><b>No business logic, no AWS SDK, no Lombok</b> &mdash;
 *       enforced by AAP &sect;0.7.1 (one-to-one COBOL-to-Java service
 *       mapping; AWS adapters isolate SDK calls; explicit code without
 *       generated boilerplate).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.repository.CustomerRepository for Spring
 *      Data JPA access patterns
 */
@Entity
@Table(name = "customers")
public class Customer implements Serializable {

    /**
     * Serial-version UID for this JPA entity. Kept stable across
     * minor field additions so that Hibernate L2 cache entries and
     * Spring Session data survive non-breaking entity evolution.
     * Bump this value if (and only if) the wire/cache layout changes
     * incompatibly (e.g., a field removed, renamed, or its type
     * changed in a way that breaks Java {@link Serializable}
     * round-trip).
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Field declarations
    //
    // Order matches the COBOL copybook (CVCUS01Y.cpy) line order so that the
    // Java source remains visually parallel to the source-of-truth COBOL
    // record layout. This eases code review for COBOL SMEs during the
    // parallel-run validation window (AAP §0.7.3).
    // -------------------------------------------------------------------------

    // COBOL: CVCUS01Y.cpy:L5 CUST-ID PIC 9(09) - 9-digit unsigned customer identifier (PK)
    // Maps to V003 column: cust_id NUMERIC(9) NOT NULL (pk_customers)
    /**
     * 9-digit unsigned numeric customer identifier. Primary key.
     * Range 0..999,999,999. Stored as a VSAM KSDS key (RKP=0,
     * KEYLEN=9 per {@code app/catlg/LISTCAT.txt}) in the COBOL
     * source and as {@code NUMERIC(9) PRIMARY KEY} in the
     * PostgreSQL target. The Java type is {@link Long} (preferred
     * over {@link Integer} because it aligns with V003's
     * {@code NUMERIC(9)} precision and provides consistent FK
     * semantics with {@code card_xref.xref_cust_id}). {@code @Id}
     * is on the field; no {@code @GeneratedValue} because the
     * identifier originates upstream and must be preserved verbatim
     * during the parallel-run period.
     */
    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    // COBOL: CVCUS01Y.cpy:L6 CUST-FIRST-NAME PIC X(25) - customer first name
    // Maps to V003 column: cust_first_name VARCHAR(25) NOT NULL
    /**
     * Customer first name. 25-character fixed-width in COBOL,
     * stored TRIMMED of trailing COBOL padding spaces in
     * PostgreSQL (idiomatic relational storage). Required for
     * statement-header rendering (CBSTM03A / CBSTM03B) and the
     * account-view screen (COACTVWC).
     */
    @Column(name = "cust_first_name", nullable = false, length = 25)
    private String custFirstName;

    // COBOL: CVCUS01Y.cpy:L7 CUST-MIDDLE-NAME PIC X(25) - customer middle name (nullable)
    // Maps to V003 column: cust_middle_name VARCHAR(25)
    /**
     * Customer middle name. 25-character fixed-width in COBOL.
     * Nullable &mdash; some customers in the source fixture have
     * all-spaces middle names (no middle name); the application
     * layer treats an all-spaces COBOL field as the absence of a
     * value, which maps to PostgreSQL {@code NULL}.
     */
    @Column(name = "cust_middle_name", length = 25)
    private String custMiddleName;

    // COBOL: CVCUS01Y.cpy:L8 CUST-LAST-NAME PIC X(25) - customer last name
    // Maps to V003 column: cust_last_name VARCHAR(25) NOT NULL
    /**
     * Customer last name. 25-character fixed-width in COBOL,
     * stored TRIMMED of trailing COBOL padding spaces in
     * PostgreSQL. Required for statement-header rendering and the
     * account-view screen.
     */
    @Column(name = "cust_last_name", nullable = false, length = 25)
    private String custLastName;

    // COBOL: CVCUS01Y.cpy:L9 CUST-ADDR-LINE-1 PIC X(50) - mailing address line 1
    // Maps to V003 column: cust_addr_line_1 VARCHAR(50) NOT NULL
    /**
     * First line of the customer's mailing address (typically
     * street number + street name). 50-character fixed-width in
     * COBOL. Required for statement mailing; populated in every
     * golden-fixture row.
     */
    @Column(name = "cust_addr_line_1", nullable = false, length = 50)
    private String custAddrLine1;

    // COBOL: CVCUS01Y.cpy:L10 CUST-ADDR-LINE-2 PIC X(50) - mailing address line 2 (nullable)
    // Maps to V003 column: cust_addr_line_2 VARCHAR(50)
    /**
     * Second line of the customer's mailing address (typically
     * apartment / suite / unit number or PO Box). Nullable &mdash;
     * not present for every customer (single-family residence
     * without an apartment number leaves this field empty).
     */
    @Column(name = "cust_addr_line_2", length = 50)
    private String custAddrLine2;

    // COBOL: CVCUS01Y.cpy:L11 CUST-ADDR-LINE-3 PIC X(50) - mailing address line 3 (nullable)
    // Maps to V003 column: cust_addr_line_3 VARCHAR(50)
    /**
     * Third line of the customer's mailing address (typically city
     * + state + ZIP combined, or international locality details).
     * Nullable &mdash; present primarily for foreign addresses or
     * addresses requiring additional locality lines.
     */
    @Column(name = "cust_addr_line_3", length = 50)
    private String custAddrLine3;

    // COBOL: CVCUS01Y.cpy:L12 CUST-ADDR-STATE-CD PIC X(02) - US state / territory abbreviation
    // Maps to V003 column: cust_addr_state_cd CHAR(2) NOT NULL
    // Validated by ValidationLookupService (CSLKPCDY.cpy) at the application layer.
    /**
     * US state or territory abbreviation (e.g., {@code "TX"},
     * {@code "CA"}, {@code "NY"}, {@code "PR"}, {@code "VI"}).
     * 2-character fixed-width in COBOL; stored as {@code CHAR(2)}
     * to preserve the exact 2-character semantics. Validation
     * against the authoritative US state / territory list lives in
     * the application layer ({@code ValidationLookupService}
     * porting {@code CSLKPCDY.cpy} per AAP &sect;0.7.1) &mdash; a
     * SQL CHECK constraint here would require maintaining the
     * state list in two places and is therefore omitted per the
     * Minimal Change Clause (AAP &sect;0.7.3). The
     * {@code columnDefinition = "CHAR(2)"} matches V003's
     * {@code char(2)} column type exactly so Hibernate
     * {@code ddl-auto: validate} accepts this entity.
     */
    @Column(name = "cust_addr_state_cd", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String custAddrStateCd;

    // COBOL: CVCUS01Y.cpy:L13 CUST-ADDR-COUNTRY-CD PIC X(03) - 3-letter country code
    // Maps to V003 column: cust_addr_country_cd CHAR(3) NOT NULL
    /**
     * 3-letter country code (e.g., {@code "USA"}, {@code "CAN"},
     * {@code "MEX"}). 3-character fixed-width in COBOL; stored as
     * {@code CHAR(3)}. Per the COBOL fixture all rows are
     * {@code "USA"}; the field exists to support future
     * international customers without a schema change. The
     * {@code columnDefinition = "CHAR(3)"} matches V003's
     * {@code char(3)} column type exactly.
     */
    @Column(name = "cust_addr_country_cd", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String custAddrCountryCd;

    // COBOL: CVCUS01Y.cpy:L14 CUST-ADDR-ZIP PIC X(10) - ZIP / ZIP+4 / postal code
    // Maps to V003 column: cust_addr_zip VARCHAR(10) NOT NULL
    /**
     * Customer ZIP / ZIP+4 (US) or postal code (international).
     * Stored as {@code VARCHAR(10)} to accommodate 5-digit, 5+4
     * hyphenated US ZIP ({@code "78487-7965"}), and shorter
     * international postal codes without trailing-padding storage.
     * Combined with {@link #custAddrStateCd}, validated by the
     * {@code ValidationLookupService} NANPA / ZIP-prefix lookup
     * table (porting {@code CSLKPCDY.cpy}) at the application
     * layer.
     */
    @Column(name = "cust_addr_zip", nullable = false, length = 10)
    private String custAddrZip;

    // COBOL: CVCUS01Y.cpy:L15 CUST-PHONE-NUM-1 PIC X(15) - primary phone number (nullable)
    // Maps to V003 column: cust_phone_num_1 VARCHAR(15)
    /**
     * Primary phone number. 15-character fixed-width in COBOL.
     * Stored as {@code VARCHAR(15)} to preserve any formatting
     * characters (parentheses, hyphens, spaces) from the source
     * fixture without trailing padding. Nullable &mdash; some
     * customers in the fixture have no primary phone on file.
     * <p><b>OMITTED from {@link #toString()} per PCI-DSS / PII
     * protection.</b>
     */
    @Column(name = "cust_phone_num_1", length = 15)
    private String custPhoneNum1;

    // COBOL: CVCUS01Y.cpy:L16 CUST-PHONE-NUM-2 PIC X(15) - alternate phone number (nullable)
    // Maps to V003 column: cust_phone_num_2 VARCHAR(15)
    /**
     * Secondary / alternate phone number. 15-character fixed-width
     * in COBOL. Nullable &mdash; many customers have only one
     * phone on file.
     * <p><b>OMITTED from {@link #toString()} per PCI-DSS / PII
     * protection.</b>
     */
    @Column(name = "cust_phone_num_2", length = 15)
    private String custPhoneNum2;

    // COBOL: CVCUS01Y.cpy:L17 CUST-SSN PIC 9(09) - PII Social Security Number
    // Maps to V003 column: cust_ssn NUMERIC(9) NOT NULL
    // PII - encrypted at rest (RDS KMS), masked in toString (last 4 digits only),
    // scanned by Macie in S3 exports, masked in application logs by logback-spring.xml.
    /**
     * <b>PII &mdash; 9-digit US Social Security Number.</b> The
     * single most sensitive PII column in the entire CardDemo
     * schema. {@code PIC 9(09)} in COBOL, mapped to
     * {@code NUMERIC(9) NOT NULL} in PostgreSQL; the Java type is
     * {@link Long} to match V003's numeric type.
     *
     * <p><b>PCI-DSS handling matrix (AAP &sect;0.6.6):</b>
     * <ol>
     *   <li>Encryption at rest via RDS customer KMS CMK
     *       (delegated to the storage layer; no column-level
     *       pgcrypto encryption).</li>
     *   <li>Encryption in transit via JDBC TLS 1.2+
     *       ({@code rds.force_ssl=1}).</li>
     *   <li>Macie continuously scans S3 buckets for accidental
     *       SSN exposure; any leak triggers a Macie finding +
     *       CloudWatch alarm.</li>
     *   <li>{@link #toString()} masks the SSN to its last 4
     *       digits ({@code "***-**-1234"}) so a stray
     *       {@code log.info(customer)} cannot leak the raw
     *       value.</li>
     *   <li>Logback patterns in
     *       {@code src/main/resources/logback-spring.xml} also
     *       redact any 9-digit numeric pattern matching an SSN
     *       in raw log lines.</li>
     * </ol>
     */
    @Column(name = "cust_ssn", nullable = false)
    private Long custSsn;

    // COBOL: CVCUS01Y.cpy:L18 CUST-GOVT-ISSUED-ID PIC X(20) - PII government-issued identifier
    // Maps to V003 column: cust_govt_issued_id VARCHAR(20)
    // Sensitive PII - subject to the same masking / Macie rules as cust_ssn (AAP §0.6.6).
    /**
     * <b>PII &mdash; government-issued identifier</b>
     * (driver's-license number, passport number, state-ID number,
     * etc.). 20-character fixed-width in COBOL; stored as
     * {@code VARCHAR(20)}. Nullable &mdash; not every customer in
     * the fixture has a govt-issued ID on file.
     *
     * <p>Subject to the same masking / Macie-scanning rules as
     * {@link #custSsn} at the application layer (per AAP
     * &sect;0.6.6). {@link #toString()} masks this value to a
     * prefix marker ({@code "GOVT-****"}) so the raw identifier
     * cannot leak into application logs.
     */
    @Column(name = "cust_govt_issued_id", length = 20)
    private String custGovtIssuedId;

    // COBOL: CVCUS01Y.cpy:L19 CUST-DOB-YYYY-MM-DD PIC X(10) - date of birth
    // (CUSTREC.cpy variant: CUST-DOB-YYYYMMDD - same 10-byte position)
    // Maps to V003 column: cust_dob_yyyy_mm_dd DATE NOT NULL
    // COBOL X(10) 'YYYY-MM-DD' string -> native java.time.LocalDate (AAP §0.6.2;
    // replaces LE CEEDAYS-based date handling per AAP §0.6.1).
    /**
     * Customer date of birth. The COBOL source stores this as a
     * 10-character string in ISO-8601 {@code YYYY-MM-DD} format
     * inside the fixed-width record. The Java target stores it as
     * a native {@link LocalDate}, mapped to a PostgreSQL
     * {@code DATE} column (V003). This enables native date
     * arithmetic in JPA (e.g., age calculation, FICO-vintage
     * analysis) without manual substring parsing, and replaces
     * LE-based {@code CEEDAYS} date handling per AAP &sect;0.6.1.
     *
     * <p>The Java field name {@code custDobYyyyMmDd} preserves the
     * COBOL field name {@code CUST-DOB-YYYY-MM-DD} for traceability
     * even though the Java type is format-agnostic
     * ({@link LocalDate}). The {@code CUSTREC.cpy} variant names
     * the field {@code CUST-DOB-YYYYMMDD} (no hyphens); both refer
     * to the same 10-byte position in the record. V003 adopts the
     * cleaner {@code CVCUS01Y} form
     * {@code cust_dob_yyyy_mm_dd} per AAP &sect;0.6.2 and the
     * agent_prompt.
     *
     * <p>Required for KYC / regulatory identity verification.
     *
     * <p><b>OMITTED from {@link #toString()} per PCI-DSS / PII
     * protection</b> &mdash; DOB combined with name + ZIP is
     * considered PII under HIPAA and many state privacy laws.
     */
    @Column(name = "cust_dob_yyyy_mm_dd", nullable = false)
    private LocalDate custDobYyyyMmDd;

    // COBOL: CVCUS01Y.cpy:L20 CUST-EFT-ACCOUNT-ID PIC X(10) - EFT routing ID (nullable)
    // Maps to V003 column: cust_eft_account_id VARCHAR(10)
    /**
     * Electronic funds transfer (EFT) account routing identifier
     * used when posting bill payments
     * ({@code BillPaymentService} / COBOL {@code COBIL00C}).
     * 10-character fixed-width in COBOL; stored as
     * {@code VARCHAR(10)}. Nullable &mdash; not every customer has
     * set up EFT (some customers pay by other means).
     */
    @Column(name = "cust_eft_account_id", length = 10)
    private String custEftAccountId;

    // COBOL: CVCUS01Y.cpy:L21 CUST-PRI-CARD-HOLDER-IND PIC X(01) - Y/N flag
    // Maps to V003 column: cust_pri_card_holder_ind CHAR(1) NOT NULL
    /**
     * Primary cardholder indicator. Exactly one character:
     * <ul>
     *   <li>{@code "Y"} &mdash; this customer is the primary
     *       cardholder on their associated account(s).</li>
     *   <li>{@code "N"} &mdash; secondary / authorized user.</li>
     * </ul>
     * 1-character fixed-width in COBOL; stored as {@code CHAR(1)}.
     * Per the Minimal Change Clause (AAP &sect;0.7.3), a SQL CHECK
     * constraint restricting the value to {@code ('Y','N')} is
     * intentionally omitted at the schema layer &mdash; the COBOL
     * source did not validate this value at the data layer, so the
     * Java target also does not. Application-layer validation
     * lives in the {@code AccountUpdateService} flow. The
     * {@code columnDefinition = "CHAR(1)"} matches V003's
     * {@code char(1)} column type exactly so Hibernate
     * {@code ddl-auto: validate} accepts this entity.
     */
    @Column(name = "cust_pri_card_holder_ind", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String custPriCardHolderInd;

    // COBOL: CVCUS01Y.cpy:L22 CUST-FICO-CREDIT-SCORE PIC 9(03) - 3-digit FICO score
    // Maps to V003 column: cust_fico_credit_score NUMERIC(3) NOT NULL
    // V003 CHECK constraint enforces range 300..850 (canonical FICO range).
    /**
     * 3-digit FICO credit score. {@code PIC 9(03)} in COBOL,
     * mapped to {@code NUMERIC(3) NOT NULL} in PostgreSQL. The
     * Java type is {@link Integer} (sufficient for the 0..999
     * range that {@code NUMERIC(3)} can hold and idiomatic for
     * small integral scores). V003 enforces a
     * {@code CHECK (cust_fico_credit_score BETWEEN 300 AND 850)}
     * constraint that restricts the value to the canonical
     * real-world FICO range &mdash; although the underlying COBOL
     * {@code PIC 9(03)} accepts the broader 000-999 range, the
     * Java target enforces the operational invariant
     * deterministically at the schema layer to protect downstream
     * credit-limit calculations
     * ({@code InterestCalculationService}) and disclosure-group
     * lookups ({@code DisclosureGroupRepository}).
     *
     * <p>Used by {@code AccountUpdateService} and
     * {@code InterestCalculationService}. Although the FICO score
     * itself is not regulated PII in isolation, it is sensitive
     * financial data and is OMITTED from production logs at the
     * Logback level (logback-spring.xml redacts numeric scores in
     * 300..850 range adjacent to customer identifiers).
     */
    @Column(name = "cust_fico_credit_score", nullable = false, precision = 3)
    private Integer custFicoCreditScore;

    // -------------------------------------------------------------------------
    // Constructors
    //
    // 1. No-arg constructor (required by JPA / Hibernate proxy creation).
    // 2. All-args constructor accepting the 18 source fields in COBOL
    //    declaration order. Useful in tests and seed fixtures where the
    //    caller has all fields in hand. Per AAP §0.7.3 refactor discipline,
    //    the parameter order matches CVCUS01Y.cpy line order exactly so
    //    the Java source remains visually parallel to the COBOL copybook.
    // -------------------------------------------------------------------------

    /**
     * Default no-arg constructor required by the JPA specification
     * for entity proxy creation by Hibernate. Leaves all fields at
     * their Java defaults (all {@code null}) so that Hibernate can
     * populate them from a result set. Application code SHOULD
     * prefer the all-args constructor when building new entities
     * programmatically.
     */
    public Customer() {
        // intentionally empty - JPA requires a public/protected no-arg constructor
    }

    /**
     * All-args constructor for programmatic entity creation. Accepts
     * the 18 source fields in COBOL declaration order
     * ({@code CVCUS01Y.cpy} L5..L22) per AAP &sect;0.7.3 refactor
     * discipline, which keeps the Java source visually parallel to
     * the COBOL copybook.
     *
     * @param custId               9-digit unsigned customer identifier (PK)
     * @param custFirstName        first name (max 25 chars; required)
     * @param custMiddleName       middle name (max 25 chars; nullable)
     * @param custLastName         last name (max 25 chars; required)
     * @param custAddrLine1        address line 1 (max 50 chars; required)
     * @param custAddrLine2        address line 2 (max 50 chars; nullable)
     * @param custAddrLine3        address line 3 (max 50 chars; nullable)
     * @param custAddrStateCd      US state / territory code (exactly 2 chars; required)
     * @param custAddrCountryCd    3-letter country code (exactly 3 chars; required)
     * @param custAddrZip          ZIP / postal code (max 10 chars; required)
     * @param custPhoneNum1        primary phone (max 15 chars; nullable; PII)
     * @param custPhoneNum2        secondary phone (max 15 chars; nullable; PII)
     * @param custSsn              <b>PII</b> &mdash; 9-digit Social Security Number; required
     * @param custGovtIssuedId     <b>PII</b> &mdash; govt-issued ID (max 20 chars; nullable)
     * @param custDobYyyyMmDd      date of birth (required; PII)
     * @param custEftAccountId     EFT routing ID (max 10 chars; nullable)
     * @param custPriCardHolderInd primary-cardholder flag ({@code "Y"}/{@code "N"}; required)
     * @param custFicoCreditScore  FICO credit score (300..850; required)
     */
    public Customer(Long custId,
                    String custFirstName,
                    String custMiddleName,
                    String custLastName,
                    String custAddrLine1,
                    String custAddrLine2,
                    String custAddrLine3,
                    String custAddrStateCd,
                    String custAddrCountryCd,
                    String custAddrZip,
                    String custPhoneNum1,
                    String custPhoneNum2,
                    Long custSsn,
                    String custGovtIssuedId,
                    LocalDate custDobYyyyMmDd,
                    String custEftAccountId,
                    String custPriCardHolderInd,
                    Integer custFicoCreditScore) {
        this.custId = custId;
        this.custFirstName = custFirstName;
        this.custMiddleName = custMiddleName;
        this.custLastName = custLastName;
        this.custAddrLine1 = custAddrLine1;
        this.custAddrLine2 = custAddrLine2;
        this.custAddrLine3 = custAddrLine3;
        this.custAddrStateCd = custAddrStateCd;
        this.custAddrCountryCd = custAddrCountryCd;
        this.custAddrZip = custAddrZip;
        this.custPhoneNum1 = custPhoneNum1;
        this.custPhoneNum2 = custPhoneNum2;
        this.custSsn = custSsn;
        this.custGovtIssuedId = custGovtIssuedId;
        this.custDobYyyyMmDd = custDobYyyyMmDd;
        this.custEftAccountId = custEftAccountId;
        this.custPriCardHolderInd = custPriCardHolderInd;
        this.custFicoCreditScore = custFicoCreditScore;
    }

    // -------------------------------------------------------------------------
    // Accessors (getters and setters)
    //
    // Plain JavaBean accessors, one per field. Required by Hibernate's
    // property access mode and consumed by Spring Data JPA derived queries,
    // Jackson serialization at the DTO boundary, and the JPA validation
    // framework. Order matches the COBOL copybook for review parity.
    // -------------------------------------------------------------------------

    /**
     * @return the 9-digit customer identifier (primary key)
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * @param custId the 9-digit customer identifier to set
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * @return the customer's first name
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * @param custFirstName the customer's first name (max 25 chars)
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * @return the customer's middle name (may be {@code null})
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * @param custMiddleName the customer's middle name (max 25 chars; nullable)
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * @return the customer's last name
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * @param custLastName the customer's last name (max 25 chars)
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /**
     * @return the first line of the customer's mailing address
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * @param custAddrLine1 the first line of the mailing address (max 50 chars)
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /**
     * @return the second line of the customer's mailing address (may be {@code null})
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * @param custAddrLine2 the second line of the mailing address (max 50 chars; nullable)
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /**
     * @return the third line of the customer's mailing address (may be {@code null})
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * @param custAddrLine3 the third line of the mailing address (max 50 chars; nullable)
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /**
     * @return the US state / territory abbreviation (exactly 2 chars)
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * @param custAddrStateCd the US state / territory abbreviation
     *                        (exactly 2 chars; validated by
     *                        {@code ValidationLookupService})
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /**
     * @return the 3-letter country code (e.g., {@code "USA"})
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * @param custAddrCountryCd the 3-letter country code to set
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /**
     * @return the ZIP / ZIP+4 / postal code
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * @param custAddrZip the ZIP / postal code (max 10 chars)
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /**
     * @return the primary phone number (may be {@code null}; PII)
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * @param custPhoneNum1 the primary phone number (max 15 chars; nullable)
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /**
     * @return the alternate phone number (may be {@code null}; PII)
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * @param custPhoneNum2 the alternate phone number (max 15 chars; nullable)
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * Returns the 9-digit US Social Security Number.
     *
     * <p><b>WARNING &mdash; PII (AAP &sect;0.6.6):</b> the return
     * value is sensitive PII. Callers MUST NOT log it, MUST NOT
     * include it in HTTP responses without explicit masking, and
     * MUST NOT persist it to any non-encrypted storage. The
     * intended consumers are:
     * <ul>
     *   <li>{@code AccountViewService} &mdash; surfaces a masked
     *       SSN ({@code "***-**-1234"}) into {@code AccountViewDto}.</li>
     *   <li>{@code AccountUpdateService} &mdash; updates the SSN
     *       inside the same {@code @Transactional} boundary as the
     *       account update.</li>
     *   <li>JPA / Hibernate &mdash; transparent persistence read/write.</li>
     * </ul>
     *
     * @return the raw 9-digit SSN value
     */
    public Long getCustSsn() {
        return custSsn;
    }

    /**
     * Stores a 9-digit US Social Security Number.
     *
     * <p><b>WARNING &mdash; PII (AAP &sect;0.6.6):</b> callers must
     * ensure the value is validated as a 9-digit number and that
     * the surrounding context (JDBC TLS, RDS KMS encryption-at-rest,
     * application-layer authorization) protects the value at every
     * stage of its lifecycle.
     *
     * @param custSsn the 9-digit Social Security Number to set
     */
    public void setCustSsn(Long custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identifier (driver's license,
     * passport, state ID, etc.).
     *
     * <p><b>WARNING &mdash; PII (AAP &sect;0.6.6):</b> subject to
     * the same handling rules as {@link #getCustSsn()}.
     *
     * @return the government-issued identifier (may be {@code null})
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * @param custGovtIssuedId the government-issued identifier
     *                         (max 20 chars; nullable; PII)
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /**
     * @return the customer's date of birth
     */
    public LocalDate getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * @param custDobYyyyMmDd the customer's date of birth
     *                        (parsed from COBOL {@code "YYYY-MM-DD"}
     *                        string via {@code DateValidationService}
     *                        on ingest)
     */
    public void setCustDobYyyyMmDd(LocalDate custDobYyyyMmDd) {
        this.custDobYyyyMmDd = custDobYyyyMmDd;
    }

    /**
     * @return the EFT account routing identifier (may be {@code null})
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * @param custEftAccountId the EFT account routing identifier
     *                         (max 10 chars; nullable)
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /**
     * @return the primary-cardholder indicator
     *         ({@code "Y"} for primary, {@code "N"} for secondary)
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * @param custPriCardHolderInd the primary-cardholder indicator
     *                             (must be {@code "Y"} or {@code "N"})
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /**
     * @return the FICO credit score (300..850 per V003 CHECK constraint)
     */
    public Integer getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * Sets the FICO credit score.
     *
     * <p>The V003 migration enforces a
     * {@code CHECK (cust_fico_credit_score BETWEEN 300 AND 850)}
     * constraint that will reject any value outside the canonical
     * real-world FICO range at the database layer. Application-
     * layer validation should pre-screen values before persisting
     * to surface validation errors via REST 400-series responses
     * rather than 500-series database-constraint failures.
     *
     * @param custFicoCreditScore the FICO credit score to set
     *                            (must be in range 300..850)
     */
    public void setCustFicoCreditScore(Integer custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract: based on the
    // primary-key field only (custId). This contract is STABLE across the
    // entity lifecycle states (transient, managed, detached, removed).
    //
    // toString MASKS PII per AAP §0.6.6: SSN is masked to last 4 digits,
    // government-issued ID is masked to a prefix marker, and phone numbers
    // + full date of birth are OMITTED entirely. The masking implementation
    // ensures that a stray log.info(customer) cannot leak PII into
    // CloudWatch Logs or OpenSearch even if Logback patterns fail to redact
    // upstream.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the primary key ({@link #custId})
     * only, matching the standard JPA entity contract and AAP
     * &sect;0.6.6 requirements (equals MUST NOT be based on PII
     * fields like {@link #custSsn}). Two {@code Customer}
     * instances are equal iff they have the same {@code custId}
     * value (both {@code null} is treated as equal &mdash; common
     * during transient-state comparisons within a test fixture but
     * should not occur in persisted state).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this object is the same as the
     *         {@code o} argument; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer)) {
            return false;
        }
        Customer customer = (Customer) o;
        return Objects.equals(custId, customer.custId);
    }

    /**
     * Hash code derived from {@link #custId} only, consistent with
     * the {@link #equals(Object)} contract above. Safe for use as a
     * hash-set or hash-map key once {@code custId} has been
     * assigned (which is required before
     * {@code EntityManager.persist} by virtue of the
     * {@code nullable = false} primary-key constraint).
     *
     * @return the hash-code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }

    /**
     * String representation suitable for log statements and debug
     * output.
     *
     * <p><b>PCI-DSS / PII compliance (AAP &sect;0.6.6, &sect;0.7.1):</b>
     * the following fields are MASKED or OMITTED to prevent a stray
     * {@code log.info(customer)} or implicit string concatenation
     * from leaking PII into CloudWatch Logs or OpenSearch:
     * <ul>
     *   <li>{@link #custSsn} &mdash; <b>MASKED</b> to the last 4
     *       digits as {@code "***-**-1234"} via
     *       {@link #maskSsn(Long)}.</li>
     *   <li>{@link #custGovtIssuedId} &mdash; <b>MASKED</b> to a
     *       prefix marker {@code "GOVT-****"} via
     *       {@link #maskGovtIssuedId(String)}.</li>
     *   <li>{@link #custPhoneNum1} &mdash; <b>OMITTED</b> entirely
     *       (direct contact number is PII).</li>
     *   <li>{@link #custPhoneNum2} &mdash; <b>OMITTED</b> entirely.</li>
     *   <li>{@link #custDobYyyyMmDd} &mdash; <b>OMITTED</b>
     *       entirely (DOB + name + ZIP triangulates identity).</li>
     *   <li>{@link #custAddrLine1} / {@link #custAddrLine2} /
     *       {@link #custAddrLine3} &mdash; <b>OMITTED</b> entirely
     *       (full street address is PII when combined with name).</li>
     * </ul>
     *
     * <p>Included fields (safe for logging): {@link #custId},
     * {@link #custFirstName}, {@link #custLastName},
     * {@link #custAddrStateCd}, {@link #custAddrCountryCd},
     * {@link #custAddrZip} (city-level locality only),
     * {@link #custFicoCreditScore},
     * {@link #custPriCardHolderInd}, {@link #custEftAccountId}.
     *
     * @return a non-sensitive string representation of this entity
     */
    @Override
    public String toString() {
        return "Customer{"
                + "custId=" + custId
                + ", custFirstName='" + custFirstName + '\''
                + ", custLastName='" + custLastName + '\''
                + ", custAddrStateCd='" + custAddrStateCd + '\''
                + ", custAddrCountryCd='" + custAddrCountryCd + '\''
                + ", custAddrZip='" + custAddrZip + '\''
                + ", custSsn='" + maskSsn(custSsn) + '\''
                + ", custGovtIssuedId='" + maskGovtIssuedId(custGovtIssuedId) + '\''
                + ", custEftAccountId='" + custEftAccountId + '\''
                + ", custPriCardHolderInd='" + custPriCardHolderInd + '\''
                + ", custFicoCreditScore=" + custFicoCreditScore
                // NOTE: custPhoneNum1, custPhoneNum2, custDobYyyyMmDd, and
                // custAddrLine1/2/3 are intentionally OMITTED per PCI-DSS /
                // PII protection (AAP §0.6.6 and §0.7.1) - "No plaintext
                // card/account/PII data in logs - enforced via CloudWatch
                // log filters + Macie S3 scanning."
                + '}';
    }

    // -------------------------------------------------------------------------
    // Internal PII masking helpers
    //
    // Defense-in-depth masking applied AT THE ENTITY LAYER in addition to
    // the upstream Logback redaction patterns in logback-spring.xml. If the
    // Logback patterns ever fail (e.g., misconfiguration, log appender
    // bypassing the encoder), this entity-layer masking still ensures the
    // raw PII values cannot leak via toString.
    // -------------------------------------------------------------------------

    /**
     * Masks a 9-digit Social Security Number to its last 4 digits
     * in the canonical {@code "***-**-1234"} format. Returns
     * {@code "***-**-****"} if the input is {@code null} (defensive
     * default so {@link #toString()} never emits a literal
     * {@code "null"} in a context that suggests SSN absence).
     *
     * <p>This is a one-way redaction &mdash; the original SSN
     * cannot be recovered from the masked output. Used only by
     * {@link #toString()} for logging purposes; persistence layer
     * always uses the raw {@link #custSsn} value.
     *
     * @param ssn the raw 9-digit SSN value (may be {@code null})
     * @return the masked SSN string in {@code "***-**-NNNN"} format
     */
    private static String maskSsn(Long ssn) {
        if (ssn == null) {
            return "***-**-****";
        }
        // Format to exactly 9 digits with leading-zero padding to
        // preserve SSNs that start with one or more zeros (e.g.,
        // 001234567 which would otherwise lose its leading zero
        // when converted via Long.toString()).
        String s = String.format("%09d", ssn);
        return "***-**-" + s.substring(5);
    }

    /**
     * Masks a government-issued identifier to a fixed prefix marker
     * ({@code "GOVT-****"}) regardless of the underlying value's
     * format. Returns {@code null} verbatim if the input is
     * {@code null} so {@link #toString()} can distinguish absent
     * data from masked data at the visual-inspection level.
     *
     * <p>Unlike SSN masking which retains the last 4 digits, the
     * government-issued ID has no canonical structure (it may be a
     * driver's-license string, passport number, state-ID number,
     * etc.) so no portion is retained &mdash; the entire value is
     * replaced by the marker. This is the most conservative
     * possible masking, prioritizing PII protection over log
     * readability.
     *
     * @param govtId the raw government-issued ID (may be {@code null})
     * @return {@code null} if the input is {@code null}; otherwise
     *         the fixed marker {@code "GOVT-****"}
     */
    private static String maskGovtIssuedId(String govtId) {
        if (govtId == null) {
            return null;
        }
        return "GOVT-****";
    }
}
