/*
 * ******************************************************************
 * Program     : CardCrossReference.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Relational replacement for VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS - the card to customer to
 *               account cross reference that fronts every online lookup chain
 *               and is presented to CICS as file CCXREF.
 * Source      : app/cpy/CVACT03Y.cpy (36 populated B in a 50 B slot,
 *               key 16) @ 7756d89
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
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Card to customer to account cross reference: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}.
 *
 * <p>The three fields of {@code app/cpy/CVACT03Y.cpy} populate 36 bytes of the 50-byte record slot reported at
 * {@code app/catlg/LISTCAT.txt:L403}; the remaining 14 bytes are deliberate slack and are not modelled.
 *
 * <p>Each instance is one row of table {@code card_cross_reference} and one record of the legacy
 * VSAM KSDS cluster {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}, catalogued at
 * {@code app/catlg/LISTCAT.txt:L401}. The record layout is {@code app/cpy/CVACT03Y.cpy}, whose group
 * item is {@code 01 CARD-XREF-RECORD} with elementary items at {@code :L5} through {@code :L8} and
 * whose own header comment at {@code :L2} reads "Data-structure for card xref (RECLN 50)". Online the
 * cluster is exposed as CICS file {@code CCXREF}.
 *
 * <p>Functionally this is the entry point of every keyed lookup in the legacy corpus. A card number
 * arrives from a screen or from a daily transaction record, the cross reference resolves it to an
 * account identifier and a customer identifier, and only then are the account and the customer
 * records read - three separate keyed reads in a fixed order. The class itself is a pure data holder:
 * it performs no I/O, holds no service reference, emits no log record and starts no unit of work.
 *
 * <h2>Verified field contract</h2>
 *
 * <p>Exactly three columns are mapped and every one of them is {@code NOT NULL}. The widths are taken
 * from the copybook, not inferred:
 *
 * <pre>
 *  #  COBOL field      Copybook  PIC     Java field   Java type  Column         SQL type
 *  1  XREF-CARD-NUM    :L5       X(16)   cardNumber   String     xref_card_num  CHAR(16)     primary key
 *  2  XREF-CUST-ID     :L6       9(09)   customerId   Long       xref_cust_id   NUMERIC(9)   FK customer
 *  3  XREF-ACCT-ID     :L7       9(11)   accountId    Long       xref_acct_id   NUMERIC(11)  FK account
 *  -  FILLER           :L8       X(14)   not modelled -          -              -
 * </pre>
 *
 * <p>There is no fourth column of any kind, no version column for optimistic locking, and no
 * monetary or rate value - so no {@code java.math.BigDecimal} field appears here and none may be
 * invented. Of the eleven entities in this package a version column belongs on exactly four -
 * {@code Account}, {@code Card}, {@code Customer} and {@code Transaction} - and this is not one of
 * them, because the cross reference is written whole by the provisioning job and is never the target
 * of a read-modify-write screen conversation.
 *
 * <h2>Record geometry: 36 populated bytes in a 50 byte slot</h2>
 *
 * <p>The arithmetic is {@code 16 + 9 + 11 = 36} populated bytes inside a catalogued 50 byte slot,
 * which leaves <strong>14 bytes of deliberate slack that is not modelled</strong>. Four independent
 * observations agree, which is why the slack is treated as an allocation artefact rather than as data:
 *
 * <ul>
 *   <li>{@code app/catlg/LISTCAT.txt:L403} reports {@code KEYLEN 16} and {@code AVGLRECL 50}, and
 *       {@code :L404} reports {@code RKP 0} with {@code MAXLRECL 50} - a fixed 50 byte slot whose key
 *       is the leading 16 bytes.</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy:L8} declares the residue as {@code FILLER PIC X(14)}, and
 *       {@code 36 + 14 = 50}. A COBOL {@code FILLER} item is unnamed and therefore unreferenceable by
 *       any program in the corpus; it carries no business meaning that could be mapped.</li>
 *   <li>{@code app/data/ASCII/cardxref.txt} is 1,850 bytes over 50 lines - 37 bytes per line, that is
 *       records of exactly 36 bytes plus one line terminator. Every one of the 50 rows measures 36
 *       characters, decomposing as a 16-character card number, a 9-digit customer identifier and an
 *       11-digit account identifier. The 14 byte {@code FILLER} is not even present in the fixture.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L486} reports {@code AXRKP 25} for the alternate index, placing
 *       the alternate key at byte offset 25 of the base record. Since {@code 16 + 9 = 25}, the
 *       catalogue independently confirms both the field order and the two leading widths.</li>
 *   </ul>
 *
 * <p>The discrepancy between the 36 byte logical record and the 50 byte catalogued slot is therefore a
 * legacy allocation artefact: it is documented here and never modelled. Fixed width emission at the
 * object store boundary, where the trailing 14 bytes must reappear as blanks, is the responsibility of
 * the batch writers, not of this entity.
 *
 * <h2>Mapping decisions that must not be undone</h2>
 *
 * <p><strong>The account property must be named exactly {@code accountId}.</strong> The
 * repository layer, {@code com.cardemo.repository.CardCrossReferenceRepository}, declares a derived
 * finder {@code findByAccountId}. Spring Data resolves derived finders against JavaBean property
 * names, so the field is {@code accountId} with {@code getAccountId()} and {@code setAccountId(Long)}
 * and nothing else. Naming it after the COBOL item, or modelling it as an association, would make the
 * finder unresolvable and would fail application context startup rather than fail a test.
 *
 * <p><strong>No association is declared, in either direction.</strong> Both foreign keys are
 * held as plain scalar {@code Long} values. This entity is the most tempting place in the package to
 * introduce mappings, because in shape it is a join table, and doing so would be wrong for three
 * reasons. First, the legacy corpus performs explicit keyed reads in a fixed order - cross reference,
 * then account, then customer - and the account view service reproduces exactly that chain; an
 * association would replace an explicit read with an implicit one and the order would become a
 * side effect of the mapping. Second, a many-to-one mapping on the account would rename the JavaBean
 * property and break the derived finder described above. Third, with no many-to-one, one-to-many or
 * one-to-one mapping, no join column, and no cascade or fetch declaration anywhere in the class, there
 * is no lazy proxy and therefore no possibility of an N+1 select - the strongest available reading of
 * Rule 1 clause A5. Referential integrity to {@code account} and {@code customer} is enforced instead
 * by two of the ten foreign keys created in {@code V1__create_schema.sql}, and read performance by the
 * B-tree index created in {@code V2__create_indexes.sql}.
 *
 * <p><strong>The card number is never emitted.</strong> A primary account number is sensitive
 * data, so {@code toString()} reports only {@code customerId} and {@code accountId} and deliberately
 * omits {@code cardNumber}. No debug rendering, no full rendering and no masking helper is provided:
 * masking in {@code logback-spring.xml} is a backstop, and never emitting the value in the first place
 * is the primary defence. The class also does not implement {@code java.io.Serializable}, both because
 * insecure deserialization is a risky pattern this project refuses by policy and because a serialisable
 * class without an explicit serial version identifier is a fatal warning under {@code -Werror}.
 *
 * <p><strong>The two identifiers stay numeric although the fixture zero-pads them.</strong>
 * In {@code app/data/ASCII/cardxref.txt} the customer and account identifiers are written as
 * zero-padded 9-digit and 11-digit fields respectively. They are nonetheless mapped as {@code Long} over
 * {@code NUMERIC(9)} and {@code NUMERIC(11)}, because zero padding of a key is a fixed width emission
 * concern owned by the batch writers and because the composite key classes in
 * {@code com.cardemo.model.key} already fix account identifiers as {@code Long}. This is a deliberate
 * asymmetry with fields such as {@code CUST-SSN} and
 * {@code CUST-FICO-CREDIT-SCORE} elsewhere in this package, which are {@code PIC 9(n)} in COBOL yet map
 * to {@code CHAR(n)} text because for those the leading zeros are significant data rather than key
 * padding. The two treatments must not be unified in either direction.
 *
 * <p><strong>The card number needs an explicit JDBC type code, and this was proved rather
 * than assumed.</strong> A text valued attribute resolves by default to {@code VARCHAR}, but
 * PostgreSQL reports a {@code CHAR} column as {@code bpchar} with JDBC type {@code CHAR}. Because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, that mismatch is not a
 * cosmetic difference - it aborts application startup. The failure was reproduced against a live
 * PostgreSQL 16 schema built from exactly the DDL enumerated below, and it reads "wrong column type
 * encountered in column [xref_card_num] in table [card_cross_reference]; found [bpchar (Types#CHAR)],
 * but expecting [char(16) (Types#VARCHAR)]". Declaring the code on the field fixes it, and is the
 * correct fix rather than widening the column: {@code PIC X(16)} is a fixed width item whose value is
 * blank padded to 16 characters on the way back out to fixed width records, and a variable length
 * column would quietly stop preserving that. Verified afterwards on the same live schema: validation
 * passes and a persist, reload and account keyed query round trip succeeds. Do not remove the code.
 *
 * <p><strong>No constraint annotation is declared.</strong> The width and precision contract is
 * carried by the column mapping, which is authoritative because the schema is validated at startup.
 * Duplicating it as bean-validation constraint annotations would create two sources of truth that can
 * drift, so none is present. Structural validation of an inbound payload belongs to the request DTOs.
 *
 * <h2>Decimal arithmetic rule</h2>
 *
 * <p>This entity holds no monetary or rate value, but the project-wide rule is stated here because it
 * governs every sibling that does: money and rates are {@code java.math.BigDecimal} at the scale the
 * source PIC clause dictates, they are compared with {@code compareTo} and never with
 * {@code equals} - {@code equals} is scale sensitive, so a value of one dollar at scale 2 is not
 * {@code equals} to the same value at scale 4 - and no IEEE 754 binary approximate numeric type is
 * permitted in any financial field anywhere in the codebase, including this one.
 *
 * <h2>Alternate index provenance</h2>
 *
 * <p>{@code app/catlg/LISTCAT.txt:L480} defines alternate index
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} and {@code :L482} reports {@code KEYLEN 11}, which is the
 * eleven digit account identifier, that is {@code XREF-ACCT-ID}. The alternate index is not an entity
 * and has no counterpart in this class. It becomes two things elsewhere: a derived finder in
 * {@code com.cardemo.repository.CardCrossReferenceRepository}, and a non-unique B-tree index on
 * {@code xref_acct_id} created by {@code V2__create_indexes.sql}. Non-unique is not a judgement call -
 * legitimately maps to several cards. This is the second of the three alternate indexes in the
 * catalogue, which reports {@code AIX 3} at {@code :L3938}; the other two belong to {@code Card} and
 * {@code Transaction}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The entity itself has no configuration switch: the table name, the three column names and their
 * SQL types are declared here and are not overridable at runtime. Two settings outside the class
 * govern how it behaves. {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile,
 * so the schema is never generated from these annotations - they are checked against the migrated
 * schema and any divergence stops the application. And the physical naming strategy is left at the
 * Spring Boot default, which is why every {@code name} attribute is stated explicitly rather than
 * derived from the Java identifier.
 *
 * <h2>The schema this mapping requires</h2>
 *
 * <p>The mapping is reconciled against real DDL rather than asserted.
 * {@code src/main/resources/db/migration/V1__create_schema.sql} declares 11 tables, 10 named foreign
 * keys, 5 CHECK constraints and 4 {@code version} columns, and among them
 * {@code CREATE TABLE card_cross_reference} with 3 columns whose names are identical, as a set, to the 3
 * {@code @Column(name = ...)} declarations below. Because {@code spring.jpa.hibernate.ddl-auto} is
 * {@code validate} in every profile, any divergence in column name, SQL type, precision or nullability
 * fails application context startup outright rather than surfacing later as bad data. The contract is:
 *
 * <ul>
 *   <li>table {@code card_cross_reference} in {@code V1__create_schema.sql} with
 *       {@code xref_card_num CHAR(16) PRIMARY KEY},
 *       {@code xref_cust_id NUMERIC(9) NOT NULL} carrying a foreign key to {@code customer.cust_id},
 *       and {@code xref_acct_id NUMERIC(11) NOT NULL} carrying a foreign key to
 *       {@code account.acct_id};</li>
 *   <li>no version column on this table, and no fourth column - in particular nothing corresponding to
 *       the 14 byte {@code FILLER};</li>
 *   <li>a <strong>non-unique</strong> B-tree index on {@code xref_acct_id} in
 *       {@code V2__create_indexes.sql}.</li>
 *   </ul>
 *
 * <p>For the avoidance of doubt about the surrounding migration set: {@code V1__create_schema.sql}
 * creates exactly 11 tables with 10 foreign keys and 5 check constraints, of which this table is one
 * and contributes two of the ten foreign keys. The Spring Batch {@code BATCH_} metadata tables come
 * from the framework's own bundled schema script and must never be added to {@code V1}, and there must
 * never be a fourth migration file.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B clean compile} and run the unit suite with
 * {@code ./mvnw -B clean test}; the full gate is {@code ./mvnw -B clean verify}, which additionally
 * enforces a JaCoCo floor of 80 percent line coverage and runs the dependency vulnerability scan. The
 * compiler runs with {@code -Xlint:all} and {@code -Werror} and with {@code failOnWarning}, so any
 * warning this file produces is a build failure.
 * <p>To run: bring the backing services up with {@code docker compose up -d}, which starts PostgreSQL 16
 * among the rest, then start the application with
 * {@code ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=local}.
 * Flyway applies the migrations on
 * deliberately environment indirected with no committed default, and the application refuses to start
 * without it.
 *
 * <p>The unit tests for this class belong in {@code src/test/java/com/cardemo/unit/model} and are to assert
 * the behaviour that the annotations alone cannot: 36 populated bytes inside the 50 byte slot, a key length
 * of 16, that the 14 byte {@code FILLER} is unmapped, that no version column exists, that the account
 * property is reachable under the JavaBean name {@code accountId}, and that {@code toString()} does not
 * contain the card number. Per {@code CONTRIBUTING.md:L34} they must pass locally before a change to
 * this file is proposed, and per {@code CONTRIBUTING.md:L33} a change here should stay confined to the
 * field contract rather than reformatting surrounding code.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code IllegalArgumentException: cardNumber ... must not be null}</dt>
 *   <dd>A mandatory column was left null. All three columns are {@code NOT NULL}, so the constructor
 *       and the setters reject null eagerly, naming the Java property, the COBOL item and the column.
 *       This is deliberate: without it the same mistake would surface much later as an opaque
 *       constraint violation at flush time, detached from the code that caused it. A blank but
 *       non-null card number is accepted, because the legacy fixed width record can legitimately carry
 *       spaces and rejecting it here would diverge from the source.</dd>
 *   <dt>{@code PropertyReferenceException: No property 'accountId' found}</dt>
 *   <dd>The account property has been renamed or converted into an association. Restore the plain
 *       scalar {@code accountId}; see the mapping decisions above.</dd>
 *   <dt>{@code SchemaManagementException: missing table [card_cross_reference]}</dt>
 *   <dd>The Flyway migrations have not run against the target schema, or they ran against a different
 *       one. Confirm the datasource, then confirm that {@code V1__create_schema.sql} creates the table
 *       exactly as enumerated above.</dd>
 *   <dt>{@code SchemaManagementException: wrong column type ... found [bpchar]}</dt>
 *   <dd>The explicit JDBC type code on the card number has been removed. PostgreSQL reports a
 *       {@code CHAR} column as {@code bpchar} with JDBC type {@code CHAR}, while a text valued
 *       attribute resolves by default to {@code VARCHAR}, and with {@code ddl-auto} at
 *       {@code validate} that mismatch stops startup. Restore the declared code rather than widening
 *       the column to a variable length type: the blank padding of a fixed width key is the behaviour
 *       being reproduced. See the note on the card number field above.</dd>
 *   <dt>Two distinct unsaved instances compare equal</dt>
 *   <dd>Expected. Identity is the primary key alone, so instances whose key is still null are
 *       indistinguishable. Assign the card number before placing an instance in a hash based
 *       collection.</dd>
 *   </dl>
 *
 * <p><b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson.
 * {@link JsonIgnoreType} removes any property whose declared type is this class from an enclosing
 * object's JSON, and {@link JsonAutoDetect} with every visibility set to {@code NONE} switches off bean
 * introspection entirely, so no getter, no setter, no field and no creator is discoverable. An entity is
 * a bean with public accessors, so without the barrier the default behaviour of returning this type from
 * a controller, or holding a field of it on a response object, is to publish the primary account number
 * that is this table's primary key. With the barrier in place Jackson finds no properties and its default
 * {@code FAIL_ON_EMPTY_BEANS} setting turns that mistake into a loud failure at the first request rather
 * than a silent disclosure. Nothing legitimate is lost: outbound representations are built by
 * {@code com.cardemo.model.dto.CardDto} and {@code com.cardemo.model.dto.AccountDto}, and persistence is
 * unaffected because Hibernate reads and writes the annotated fields reflectively and never consults
 * Jackson visibility.</p>
 */
@Entity
@Table(name = "card_cross_reference")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class CardCrossReference {

    /** Declared width of {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Inclusive lower bound of both numeric fields, {@code XREF-CUST-ID PIC 9(09)} at
     * {@code app/cpy/CVACT03Y.cpy:L6} and {@code XREF-ACCT-ID PIC 9(11)} at {@code :L7}. Neither picture
     * clause carries an {@code S}, so both are unsigned and zero is their floor; zero is accepted rather
     * than treated as a sentinel.
     */
    private static final long MIN_IDENTIFIER = 0L;

    /** Inclusive upper bound of {@code XREF-CUST-ID PIC 9(09)}: nine unsigned display digits. */
    private static final long MAX_CUSTOMER_ID = 999_999_999L;

    /** Inclusive upper bound of {@code XREF-ACCT-ID PIC 9(11)}: eleven unsigned display digits. */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /**
     * The card number, and the primary key.
     */
    @Id
    @Column(name = "xref_card_num", nullable = false, length = CARD_NUMBER_WIDTH, columnDefinition = "CHAR(16)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String cardNumber;

    /**
     * The customer identifier this card belongs to.
     */
    @Column(name = "xref_cust_id", nullable = false, columnDefinition = "NUMERIC(9)")
    private Long customerId;

    /**
     * The account identifier this card is attached to, and the alternate key of the legacy cluster.
     */
    @Column(name = "xref_acct_id", nullable = false, columnDefinition = "NUMERIC(11)")
    private Long accountId;

    /**
     * Creates an empty instance for the persistence provider.
     */
    protected CardCrossReference() {
        // Intentionally empty. Field values are supplied reflectively by the persistence provider.
    }

    /**
     * Creates a fully populated cross reference.
     *
     * @param cardNumber the 16 character card number, {@code XREF-CARD-NUM PIC X(16)}.
     * @param customerId the 9 digit customer identifier, {@code XREF-CUST-ID PIC 9(09)}.
     * @param accountId the 11 digit account identifier, {@code XREF-ACCT-ID PIC 9(11)}.
     * @throws IllegalArgumentException if any argument is null, naming the offending property, its COBOL item
     * and its column
     */
    public CardCrossReference(String cardNumber, Long customerId, Long accountId) {
        this.cardNumber = requireWidth(
                requireSupplied(cardNumber, "cardNumber", "XREF-CARD-NUM", "xref_card_num"),
                "cardNumber", "XREF-CARD-NUM PIC X(16)", CARD_NUMBER_WIDTH);
        this.customerId = requireRange(
                requireSupplied(customerId, "customerId", "XREF-CUST-ID", "xref_cust_id"),
                "customerId", "XREF-CUST-ID PIC 9(09)", MAX_CUSTOMER_ID);
        this.accountId = requireRange(
                requireSupplied(accountId, "accountId", "XREF-ACCT-ID", "xref_acct_id"),
                "accountId", "XREF-ACCT-ID PIC 9(11)", MAX_ACCOUNT_ID);
    }

    /**
     * Returns the card number, which is the primary key.
     *
     * @return the 16 character card number, never null on a persistent instance and possibly null on a
     * transient one created through the provider's no-argument constructor
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Replaces the card number.
     *
     * @param cardNumber the 16 character card number.
     * @throws IllegalArgumentException if {@code cardNumber} is null or longer than 16 characters
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = requireWidth(
                requireSupplied(cardNumber, "cardNumber", "XREF-CARD-NUM", "xref_card_num"),
                "cardNumber", "XREF-CARD-NUM PIC X(16)", CARD_NUMBER_WIDTH);
    }

    /**
     * Returns the customer identifier this card belongs to.
     *
     * @return the 9 digit customer identifier, never null on a persistent instance
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Replaces the customer identifier this card belongs to.
     *
     * @param customerId the 9 digit customer identifier.
     * @throws IllegalArgumentException if {@code customerId} is null, negative, or greater than
     *                                  999999999
     */
    public void setCustomerId(Long customerId) {
        this.customerId = requireRange(
                requireSupplied(customerId, "customerId", "XREF-CUST-ID", "xref_cust_id"),
                "customerId", "XREF-CUST-ID PIC 9(09)", MAX_CUSTOMER_ID);
    }

    /**
     * Returns the account identifier this card is attached to.
     *
     * @return the 11 digit account identifier, never null on a persistent instance
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Replaces the account identifier this card is attached to.
     *
     * @param accountId the 11 digit account identifier.
     * @throws IllegalArgumentException if {@code accountId} is null, negative, or greater than
     *                                  99999999999
     */
    public void setAccountId(Long accountId) {
        this.accountId = requireRange(
                requireSupplied(accountId, "accountId", "XREF-ACCT-ID", "xref_acct_id"),
                "accountId", "XREF-ACCT-ID PIC 9(11)", MAX_ACCOUNT_ID);
    }

    /**
     * Compares two cross references by primary key alone.
     *
     * @param other the object to compare with, possibly null
     * @return {@code true} if {@code other} is a cross reference with an equal card number
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardCrossReference that)) {
            return false;
        }
        return Objects.equals(this.cardNumber, that.cardNumber);
    }

    /**
     * Returns a hash code derived from the primary key alone.
     *
     * @return the hash code of the card number, or zero when it has not been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(cardNumber);
    }

    /**
     * Returns a diagnostic rendering that deliberately excludes the card number.
     *
     * <p>A primary account number is sensitive data and this class is reachable from log statements,
     * exception messages and debugger output, so the card number is omitted here. Masking rules in
     * {@code logback-spring.xml} are a backstop; not emitting the value at all is the primary defence,
     * and it is the reason no alternative full or masked rendering exists on this class. The primary key
     * is therefore not recoverable from this string - use {@link #getCardNumber()} deliberately when the
     * value is genuinely required.
     *
     * <p><b>Only one identifier is rendered, not both.</b> The customer identifier was removed for a
     * reason that is specific to this entity and easy to miss: the whole purpose of this table is to
     * associate a card, an account and a customer, so a rendering that emits the account and the customer
     * together reproduces the association itself. Anything that reads such a line - a log aggregator, a
     * ticket attachment, a support transcript - acquires a customer-to-account linkage that it was never
     * granted, without ever touching the database. The account identifier alone locates the row for
     * anyone already authorised to read it, and discloses no linkage on its own.
     *
     * <p>The rendering is built by concatenation only, so it is byte for byte identical on every
     * machine: no locale sensitive or platform dependent formatting is involved.
     *
     * @return a stable rendering containing the account identifier only, never the card number and never
     *         the customer identifier
     */
    @Override
    public String toString() {
        return "CardCrossReference{accountId=" + accountId + "}";
    }

    /**
     * Returns {@code value} when it is present, and otherwise reports precisely which column is at fault.
     *
     * @param <T> the type of the value being checked
     * @param value the value supplied by the caller, possibly null
     * @param property the Java property name, used in the message
     * @param cobolField the originating COBOL elementary item, used in the message
     * @param column the database column name, used in the message
     * @return {@code value}, unchanged, when it is not null
     * @throws IllegalArgumentException if {@code value} is null
     */
    private static <T> T requireSupplied(T value, String property, String cobolField, String column) {
        if (value == null) {
            throw new IllegalArgumentException(
                    property + " (COBOL " + cobolField + ", column " + column
                            + ") must not be null: the column is NOT NULL in card_cross_reference");
        }
        return value;
    }

    /**
     * Returns {@code value} when it fits the width of the COBOL field it comes from, and otherwise reports
     * the offending property and the length received.
     *
     * <p>Applied on top of {@link #requireSupplied(Object, String, String, String)} rather than in place of
     * it, so that a null and an over-wide value are reported as the different failures they are. Everything
     * the picture clause admits is accepted, a value of only spaces included, and nothing is trimmed,
     * padded or case folded: the cross-reference key must re-emit byte for byte.
     *
     * <p><strong>The message reports the received length and never the value.</strong>
     * {@code XREF-CARD-NUM} is a card number, which {@link #toString()} already masks, and a validation
     * message is exactly the kind of string that reaches a log.
     *
     * <p>Static for the same reason as {@link #requireSupplied(Object, String, String, String)}: a
     * constructor of a class the JPA specification forbids making {@code final} must not call an
     * overridable method, and {@code -Xlint:all -Werror} rejects it as {@code this-escape}.
     *
     * <p>The width is a count of Unicode code points rather than of {@code char} values, and the unit is
     * load-bearing. A {@code PIC X(n)} clause declares n character positions and the {@code CHAR(n)} column
     * it maps to pads to n characters, while a Java {@code String} measures itself in UTF-16 code units; the
     * two disagree for any supplementary-plane character. Counting code units let a value that had been
     * accepted, stored and padded fail when it was read back and offered here again. A code point count never
     * exceeds a code unit count, so this is the same bound the write path applies rather than a looser one.
     * Held as {@code DL-MS-05} in {@code DECISION_LOG.md}.
     *
     * @param value      the value already known to be non-null
     * @param property   the Java property name, used in the message
     * @param cobolField the originating COBOL item and its picture clause, used in the message
     * @param width      the declared width of that field in characters
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is longer than {@code width}
     */
    private static String requireWidth(String value, String property, String cobolField, int width) {
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions > width) {
            throw new IllegalArgumentException(property + " (COBOL " + cobolField + ") must be at most "
                    + width + " characters but was " + characterPositions);
        }
        return value;
    }

    /**
     * Returns {@code value} when it fits the unsigned digit count of the COBOL field it comes from, and
     * otherwise reports the offending property and the value received.
     *
     * <p>Neither picture clause carries an {@code S}, so a negative value is outside the domain the record
     * can represent and is refused; zero is inside it and is accepted, being a legitimate identifier rather
     * than a sentinel. <strong>No referential check happens here:</strong> whether a customer or account
     * row actually exists for the identifier is a foreign-key and service-layer question, and this table is
     * the cross-reference that answers it rather than a consumer of the answer.
     *
     * <p>The value is safe to name in the message: both identifiers are already present in
     * {@link #toString()}, unlike the card number.
     *
     * <p>Static for the same reason as {@link #requireSupplied(Object, String, String, String)}.
     *
     * @param value      the value already known to be non-null
     * @param property   the Java property name, used in the message
     * @param cobolField the originating COBOL item and its picture clause, used in the message
     * @param maximum    the largest value that digit count can hold
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is negative or greater than {@code maximum}
     */
    private static Long requireRange(Long value, String property, String cobolField, long maximum) {
        if (value.longValue() < MIN_IDENTIFIER || value.longValue() > maximum) {
            throw new IllegalArgumentException(property + " (COBOL " + cobolField + ") must be between "
                    + MIN_IDENTIFIER + " and " + maximum + " inclusive but was " + value);
        }
        return value;
    }
}
