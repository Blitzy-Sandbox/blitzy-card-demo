/*
 * ******************************************************************
 * Program     : DataIntegrityException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Referential-integrity failure across the ten foreign keys of the target schema.
 * Source      : app/catlg/LISTCAT.txt @ 7756d89 - the authoritative physical
 *               specification: 10 base clusters, 3 alternate indexes with 3
 *               matching paths, and not one referential constraint among them.
 * Source      : app/cpy/CVACT03Y.cpy @ 7756d89 - the cross-reference record that
 *               makes the card to account and card to customer relationships
 *               explicit while enforcing neither of them.
 * Source      : app/cpy/CVTRA01Y.cpy @ 7756d89 - the composite category-balance
 *               key, whose three parts each point at a different parent record.
 * Source      : app/cpy/CVTRA05Y.cpy @ 7756d89 - the 350-byte transaction record,
 *               which carries the card number it relates to as ordinary data.
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
package com.cardemo.exception;

/**
 * Reports that a write violated a referential or check constraint declared by the target schema.
 *
 * <p><strong>What it does.</strong> It gives a name to one specific failure: a row could not be written
 * because the relational store refused it, either because a parent row the write depends on does not
 * exist or because a value fell outside a declared domain. It carries an opaque, caller-supplied
 * constraint identifier, the relation the write was aimed at, and - most importantly - the underlying
 * throwable that actually knows what went wrong.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line or line
 * range in the frozen corpus, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the four
 * {@code Source} lines in the file header record. Filename case is reproduced as it appears on disk,
 * which matters because the corpus is not uniform: in {@code app/cpy} only {@code COSTM01.CPY} uses an
 * uppercase extension and in {@code app/cbl} only {@code CBSTM03A.CBL} and {@code CBSTM03B.CBL} do, so
 * every copybook and program cited here is lowercase because every one of them is lowercase on disk.
 * Each line number quoted was read from the file rather than inherited from prose, and the two places
 * where that reading disagreed with the prose are called out explicitly below.
 *
 * <h2>Why this type is an addition, not a translation</h2>
 *
 * <p>The other members of this hierarchy translate something. This one does not, and describing it as a
 * translation would be wrong. <strong>VSAM has no referential integrity at all.</strong> There is no
 * foreign key, no check constraint, no cascade and no enforcement of any kind in the legacy substrate.
 * {@code app/catlg/LISTCAT.txt} is 3,956 lines of authoritative physical specification and it catalogues
 * exactly ten base clusters - {@code ACCTDATA}, {@code CARDDATA}, {@code CARDXREF}, {@code CUSTDATA},
 * {@code DISCGRP}, {@code TCATBALF}, {@code TRANCATG}, {@code TRANSACT}, {@code TRANTYPE} and
 * {@code USRSEC} - together with three alternate indexes, each with one matching path, over
 * {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}. Searched case-insensitively for the words
 * that would have to appear if the substrate enforced anything - foreign, referential, constraint,
 * cascade - that catalogue yields <strong>zero</strong> matches. The relationships are real; the
 * enforcement simply is not there.
 *
 * <p>The relationships were instead enforced <em>procedurally</em>, by the programs, one keyed lookup at
 * a time. The clearest instance is the validation cascade of the daily posting program. At
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, {@code 1500-VALIDATE-TRAN} performs a cross-reference lookup
 * and then, only if the reason code is still zero, an account lookup. The cross-reference lookup at
 * {@code :L380-L392} sets its reason code at {@code :L385} when the read hits {@code INVALID KEY}; the
 * account lookup at {@code :L393-L422} sets its own at {@code :L397}. <strong>Those two reads are the
 * entire foreign-key check of the legacy system.</strong> The record they consult,
 * {@code app/cpy/CVACT03Y.cpy}, is a 50-byte join record holding a 16-character card number, a 9-digit
 * customer identifier and an 11-digit account identifier side by side - three related keys in one
 * record, with nothing whatsoever guaranteeing that the two parents exist.
 *
 * <p>In the target the same relationships are declared once, in the schema, and PostgreSQL enforces them
 * on every write. This type is therefore the failure vocabulary of a capability the source did not have:
 * a mechanism <em>addition</em> justified by the substrate change, recorded as such in
 * {@code DECISION_LOG.md}. Recording it as a translation would misstate its provenance, which is a Low
 * severity documentation defect rather than a functional one, but it is still wrong.
 *
 * <h2>The deliberate deviation this type makes visible</h2>
 *
 * <p>There is one behavioural difference between source and target in this area, and it is a genuine
 * improvement rather than parity. It is labelled here, and in {@code DECISION_LOG.md}, precisely so that
 * nobody mistakes it for equivalence. <strong>Severity: Medium.</strong>
 *
 * <p>In the source, {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl:L424-L444} performs
 * three writes as <em>three independent commits</em>: the category-balance upsert
 * ({@code 2700-UPDATE-TCATBAL}, performed at {@code :L440}, body at {@code :L467-L501}), the account
 * update ({@code 2800-UPDATE-ACCOUNT-REC}, performed at {@code :L441}, body at {@code :L545-L560}) and
 * the transaction write ({@code 2900-WRITE-TRANSACTION-FILE}, performed at {@code :L442}, body from
 * {@code :L562}). When the account rewrite fails, its {@code REWRITE ... INVALID KEY} branch assigns a
 * reason code at {@code :L556} - and that is all it does. Because the assignment happens on the
 * already-validated path, no reject record is written, the reject count is not incremented, execution
 * continues into the transaction write, and the value is cleared on the next iteration by the reset at
 * {@code :L208-L209}. <strong>The run therefore leaves an orphaned category-balance row and an orphaned
 * transaction row behind, and reports nothing.</strong>
 *
 * <p>Two verified corrections to the prose that describes those locators, recorded here because the
 * frozen corpus is the authority and a wrong citation is an evidence defect. First,
 * {@code 2000-POST-TRANSACTION} ends at {@code :L444}; the range {@code L424-L465} quoted elsewhere runs
 * past its {@code EXIT} and into {@code 2500-WRITE-REJECT-REC}, which is a different paragraph occupying
 * {@code :L446-L465}. Second, the end-of-run return-code gate is precisely {@code :L229-L231}, namely
 * {@code IF WS-REJECT-COUNT &gt; 0}, {@code MOVE 4 TO RETURN-CODE}, {@code END-IF}.
 *
 * <p>In the target all three writes sit inside one transaction boundary declared with
 * {@code rollbackFor = Exception.class}, so the orphan can no longer be created: the failure unwinds
 * every write in the unit of work. This type is one of the failures that boundary now surfaces. The
 * difference from the source is real and intentional - the target detects and reports a condition the
 * source silently tolerated - and it is not claimed as parity.
 *
 * <h2>Why no constraint name is hard-coded here</h2>
 *
 * <p>The schema declares <strong>exactly ten foreign keys and exactly five check constraints</strong>
 * across its eleven tables, and the single authoritative declaration of all fifteen lives in
 * {@code V1__create_schema.sql} under {@code src/main/resources/db/migration}. That file is the only
 * place any of those names may be written down.
 *
 * <p>This class therefore hard-codes <strong>none</strong> of them. There is no enum of constraint
 * constants, no list, no array, no map, no per-key {@code static final String} and no lookup table, and
 * none may be added. Three reasons, in order of weight. It would duplicate a schema this class does not
 * own, which is exactly the duplication Rule 1 Clause C forbids. It would drift silently the moment the
 * migration is revised or a constraint renamed, and a stale catalogue is worse than none. And enumerating
 * from memory risks asserting an eleventh foreign key that does not exist - a High severity defect,
 * because it would make this class disagree with the database it describes. Instead the identifier
 * arrives from the caller, opaque and unvalidated, so this class stays correct whether the schema
 * declares ten constraints, renames one, or adds a sixteenth. No name from that migration is reproduced
 * here, and the class does not require the migration to be present in order to be correct.
 *
 * <p>The relationship graph the constraints span can be described in shape without naming anything: a
 * card relates to an account; the cross-reference record of {@code app/cpy/CVACT03Y.cpy} relates to both
 * an account and a customer; a transaction relates to the card whose number it carries in
 * {@code app/cpy/CVTRA05Y.cpy}; the category balance of {@code app/cpy/CVTRA01Y.cpy} relates to an
 * account through the first eleven digits of its 17-byte composite key, and the remaining two parts of
 * that key relate to the transaction type and transaction category lookups; and the disclosure group
 * relates to those same two lookups. <strong>That description is a sketch for orientation and is
 * expressly not the authoritative list.</strong> Read {@code V1__create_schema.sql} for the names.
 *
 * <p>One consequence is worth stating because it explains why the cause matters so much. The entities of
 * {@code com.cardemo.model.entity} declare no JPA association at all - no many-to-one, no one-to-many,
 * no join column and no cascade - and hold every relating key as a plain scalar column. Nothing in the
 * Java object graph knows these relationships exist. They are enforced only by PostgreSQL, at flush or
 * commit, and the actual constraint name is therefore known only to the driver-level throwable that the
 * store raises. That throwable is the cause.
 *
 * <h2>Position among the nine types, and the two it is confused with</h2>
 *
 * <p>This class is <strong>not</strong> a {@code FILE STATUS} translation, so do not look for a status
 * value that maps to it; none does. Of the nine types in this package, five translate a status or
 * response condition - {@code RecordNotFoundException} for {@code '23'},
 * {@code DuplicateRecordException} for {@code '22'}, {@code FileUnavailableException} for {@code '35'},
 * {@code FileAccessException} for the {@code '9x'} family and {@code FatalProcessingException} for
 * anything unexpected - while {@code '00'} and {@code '04'} mean continue and {@code '10'} means loop
 * termination and is not an error at all. {@code ValidationException},
 * {@code ConcurrentUpdateException} and this class are the three members that translate no status.
 *
 * <p>Conflating this type with its two nearest neighbours is the most likely misuse, so the boundary is
 * drawn sharply:
 *
 * <ul>
 *   <li>A <strong>duplicate primary key on a write</strong> is {@code DuplicateRecordException} - the
 *       reading of {@code FILE STATUS '22'} and of {@code DFHRESP(DUPREC)} and {@code DFHRESP(DUPKEY)}.
 *       The row conflicts with one that already exists.</li>
 *   <li>A <strong>missing row on a read</strong> is {@code RecordNotFoundException} - the error reading
 *       of {@code FILE STATUS '23'} and of {@code DFHRESP(NOTFND)}. The lookup itself found nothing.</li>
 *   <li>A <strong>violated referential or check constraint on a write</strong> is this class. The row
 *       being written is itself refused, because a parent it depends on is absent or a value is outside
 *       its declared domain. The distinguishing feature is that the failure is raised by the store as a
 *       consequence of the write, not returned by a lookup the caller performed.</li>
 * </ul>
 *
 * <h2>Reject codes are never thrown as this exception</h2>
 *
 * <p>This is a high-confusion point and deserves to be stated flatly. The batch reject codes are
 * business outcomes, not exceptions: they are modelled as an enum and they drive the job exit status.
 * <strong>Codes 100, 101 and 109 are never thrown as this exception, and neither are 102 and 103.</strong>
 *
 * <p>The confusion is understandable, because codes 100 and 101 are literally the legacy procedural
 * equivalent of the two most obvious foreign keys, and 109 sits on the orphan-write path described
 * above. But their mechanism is entirely different. Each is moved into
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}, declared at {@code app/cbl/CBTRN02C.cbl:L181} - at
 * {@code :L385}, {@code :L397}, {@code :L410}, {@code :L417} and {@code :L556} respectively - and is
 * then written into a 430-byte reject record, whose two parts are
 * {@code REJECT-TRAN-DATA PIC X(350)} and {@code VALIDATION-TRAILER PIC X(80)} at
 * {@code :L176-L178}, the trailer itself being a {@code PIC 9(04)} reason and a
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} description at {@code :L180-L182}. The reason is
 * reset every iteration at {@code :L208-L209}, and the run's exit contract at {@code :L229-L231} is
 * {@code IF WS-REJECT-COUNT &gt; 0}, {@code MOVE 4 TO RETURN-CODE}, {@code END-IF}. A rejected record is
 * a record that was processed and recorded, not a failure that unwound anything. Throwing here would
 * abort a run that the source completed with a return code of 4.
 *
 * <h2>Security contract - carry the identity, never the offending value</h2>
 *
 * <p>Exception messages are logged, so this class treats everything handed to it as content that will be
 * written to a log. Two rules follow, and the first is a <strong>Blocker</strong> if broken.
 *
 * <p><strong>Never re-serialise a store or driver message into this exception's own message.</strong> A
 * constraint-violation message from a relational driver very commonly embeds the offending key value,
 * and on a card-to-account or transaction-to-card relationship that offending value <em>is</em> a
 * 16-character card number - the width is fixed by {@code app/cpy/CVACT03Y.cpy} and by
 * {@code app/cpy/CVTRA05Y.cpy}, both of which declare it as {@code PIC X(16)}. Copying that text into
 * this exception's message lifts a cardholder identifier out of the one place the masking rules of
 * {@code logback-spring.xml} are able to act on it. Leave the driver text where it belongs, in the
 * cause, and describe the failure in your own words.
 *
 * <p><strong>Carry the constraint identity, not the data.</strong> The constraint name and the relation
 * name are schema metadata and are safe. A key value, a password or its hash, a social security number,
 * a telephone number, a government identifier, a date of birth, an electronic funds account identifier
 * and a card number are not, and none of them belongs in the message, in either field, or in any example
 * in this file. Identify a failing write by the constraint it violated, never by the content that
 * violated it.
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not repair anything.</strong> There is no cascade logic, no orphan cleanup, no
 *       reconciliation and no retry, here or in any helper. The exception reports; the schema enforces
 *       and the transaction boundary undoes. Adding recovery here would put a decision in a constructor
 *       and would hide a genuine integrity fault behind a partial fix.</li>
 *   <li><strong>It carries no SQL.</strong> No data-definition text, no query fragment and no table
 *       definition of any kind. The schema lives in the Flyway migrations. Rule 1 Clause D's concern
 *       with shell injection has a direct analogue in statement text carried around inside a domain
 *       object, and such text would add an injection surface for no benefit whatever.</li>
 *   <li><strong>It logs nothing.</strong> No logger, no console stream, no metric and no trace. A
 *       constructor that logged would double-log every failure that a caller also handles, and would
 *       put a side effect where callers expect none.</li>
 *   <li><strong>It is framework-independent.</strong> No Spring, persistence-provider or JDBC type is
 *       imported; in fact the class imports nothing at all. The store-specific exception reaches it only
 *       as a {@link Throwable} cause, which keeps this vocabulary usable from a batch step, a service or
 *       a test with equal ease and keeps the dependency arrow pointing away from the framework.</li>
 *   <li><strong>It bears no annotation.</strong> In particular no response-status annotation: choosing an
 *       HTTP status is contextual and belongs to the controller that catches this, not to the type.</li>
 *   <li><strong>It adds no serialization hook.</strong> No object-stream callback, no serialization proxy,
 *       no instance-replacement method and no alternative externalization contract, and no instance is
 *       ever reconstructed from untrusted input. Rule 1 Clause D names insecure deserialization as a
 *       pattern to flag; the only concession here is the pinned {@code serialVersionUID} below, which
 *       exists to satisfy a compiler lint rather than to enable a wire format.</li>
 *   <li><strong>It exposes no privilege.</strong> No connection, no data source handle, no schema
 *       metadata and no credential - only the constraint identity and the relation name.</li>
 * </ul>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>{@link Throwable#getCause()} returns null.</em> The throwing site used a constructor without
 *       a cause inside a {@code catch}. Because the real constraint name is known only to the store, the
 *       cause is essentially the whole diagnostic value of this type; switch to a cause-carrying
 *       constructor. This class never drops a cause it was handed.</li>
 *   <li><em>{@link #getConstraintName()} returns null.</em> No identifier was supplied. That is
 *       permitted and is not an error, but it costs the fastest route to the answer. Prefer a
 *       constructor that names the constraint.</li>
 *   <li><em>Every write into a table fails on a relationship.</em> Suspect seed or load order rather
 *       than data: a child row cannot be inserted before its parent exists. Load parents first.</li>
 *   <li><em>A failure appears immediately after a schema change.</em> Check migration state. A partially
 *       applied or out-of-order migration can leave a constraint present with a definition the code no
 *       longer expects. Confirm which versions actually applied before reading anything into the
 *       message.</li>
 *   <li><em>A single record fails where others succeed.</em> The parent row for that record is missing,
 *       or a value falls outside a declared domain. Identify the record by its key and check the parent;
 *       do not copy the driver text into a log to find out.</li>
 *   <li><em>This type is thrown where a lookup returned nothing.</em> That is the misuse described
 *       above. Use {@code RecordNotFoundException} for a read that found nothing and
 *       {@code DuplicateRecordException} for a key collision.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> A type in this hierarchy was added
 *       without declaring {@code serialVersionUID}. Every type here descends from {@link Throwable} and
 *       so is unavoidably {@link java.io.Serializable}, and the build compiles with
 *       {@code -Xlint:all -Werror} and {@code failOnWarning}, which makes the declaration mandatory.</li>
 * </ul>
 *
 * <h2>Determinism and testing</h2>
 *
 * <p>Instances are immutable: both fields are final, there are no setters and there is no static mutable
 * state. Construction reads no clock, no locale, no character set, no environment variable and no schema
 * metadata, and performs no case folding, trimming or formatting of any kind - values are stored exactly
 * as supplied. That is a deliberate choice rather than an omission: constraint names are routinely
 * upper- or lower-cased, and a default-locale case conversion is a real defect class, so this class
 * avoids the whole category by never converting at all. Behaviour is consequently deterministic and the
 * type is trivially unit testable. Tests live under {@code src/test/java/com/cardemo/unit} and not in
 * this package; the behaviour worth asserting is cause preservation, identifier retention and the
 * documented null contract, plus a repository-level integration test on a containerised PostgreSQL that
 * proves a genuine relationship violation surfaces as this type.
 *
 * @see CardDemoException
 */
public class DataIntegrityException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements {@link java.io.Serializable},
     * so every type in this hierarchy is unavoidably serializable and must pin this value explicitly:
     * the build runs {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning}, which turns the
     * {@code serial} lint into a compilation failure. A fixed literal is used rather than a computed
     * default so that the identity does not shift when the class is edited.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The identifier of the violated constraint, exactly as supplied by the caller, or null when the
     * caller did not name one.
     *
     * <p>The value is opaque to this class. It is neither parsed, validated, matched against a known set
     * nor normalised in any way, because the single authoritative declaration of the constraints is
     * {@code V1__create_schema.sql} and this class does not own it.
     */
    private final String constraintName;

    /**
     * The relation the refused write was aimed at - conventionally a table name - exactly as supplied by
     * the caller, or null when the caller did not name one.
     *
     * <p>Like the constraint identifier this value is opaque: it is recorded for diagnosis and is never
     * interpreted, and no schema is consulted to confirm that the relation exists.
     */
    private final String relation;

    /**
     * Creates an integrity failure that reports a refused write with no underlying throwable and no
     * constraint identity.
     *
     * <p>This is the least informative form and is rarely the right one. Because referential and check
     * constraints are enforced by the store rather than by the Java object graph, a failure of this kind
     * almost always arrives as a caught throwable, and in that situation
     * {@link #DataIntegrityException(String, String, String, Throwable)} is the form to use. Reach for
     * this constructor only when CardDemo's own logic established that a relationship cannot hold and
     * there is genuinely nothing to attribute the finding to.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded and
     * no state outside this instance is read or written. Both accessors will return null.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. It is
     *                permitted to be null or blank and is passed through to {@link CardDemoException}
     *                unchanged, so {@link Throwable#getMessage()} returns exactly what was supplied. It
     *                must describe the failure in CardDemo's own words and must not be a copied store or
     *                driver message, and it must contain no key value, credential or personally
     *                identifiable value, because exception messages are logged
     */
    public DataIntegrityException(String message) {
        super(message);
        this.constraintName = null;
        this.relation = null;
    }

    /**
     * Creates an integrity failure that preserves the throwable the store raised, without naming a
     * constraint.
     *
     * <p>Use this form at a wrapping site where the constraint identity is not separately available. It
     * satisfies Rule 1 Clause B's requirement to wrap with context and preserve the root cause: the
     * message supplies the CardDemo-level context and the cause retains the original failure, unmodified
     * and fully navigable through {@link Throwable#getCause()}. The cause is where the real constraint
     * name and the offending values live, which is why it must never be discarded - and equally why its
     * text must never be copied into {@code message}.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * re-thrown nor logged; it is delegated to {@link CardDemoException} exactly as supplied.
     * {@link #getConstraintName()} and {@link #getRelation()} will both return null.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. It is
     *                permitted to be null or blank and is passed through unchanged. It must be written
     *                for this application and must not reproduce the cause's own message, which
     *                routinely embeds the offending key value
     * @param cause   the underlying throwable, typically the store-specific constraint-violation
     *                exception, retrievable through {@link Throwable#getCause()}. It is permitted to be
     *                null, which records that there is no underlying failure to attribute. When non-null
     *                it is always retained; this class never drops a cause it was handed
     */
    public DataIntegrityException(String message, Throwable cause) {
        super(message, cause);
        this.constraintName = null;
        this.relation = null;
    }

    /**
     * Creates an integrity failure that names the violated constraint and the relation, with no
     * underlying throwable.
     *
     * <p>Use this form when CardDemo's own logic determined that a relationship cannot hold - a
     * pre-flight check, for instance - and the constraint it corresponds to is known by name. When a
     * throwable was caught, use {@link #DataIntegrityException(String, String, String, Throwable)}
     * instead; dropping it is the swallowing that Rule 1 Clause B forbids.
     *
     * <p>Side effects: none beyond throwable construction. Neither argument is validated against the
     * schema, because the schema is declared in {@code V1__create_schema.sql} and is deliberately not
     * duplicated here.
     *
     * @param message        the detail message, retrievable through {@link Throwable#getMessage()}.
     *                       Permitted to be null or blank and passed through unchanged. It must carry no
     *                       key value, credential or personally identifiable value
     * @param constraintName the identifier of the violated constraint. Permitted to be null or blank and
     *                       stored exactly as supplied, with no trimming, case folding or defaulting, so
     *                       that {@link #getConstraintName()} returns precisely this value
     * @param relation       the relation the refused write was aimed at, conventionally a table name.
     *                       Permitted to be null or blank and stored exactly as supplied, so that
     *                       {@link #getRelation()} returns precisely this value
     */
    public DataIntegrityException(String message, String constraintName, String relation) {
        super(message);
        this.constraintName = constraintName;
        this.relation = relation;
    }

    /**
     * Creates a fully populated integrity failure: message, constraint identity, relation and preserved
     * cause.
     *
     * <p><strong>This is the canonical form</strong> and the one to prefer at every site that catches a
     * constraint violation from the store. It is the only constructor that carries the complete picture:
     * the message says what the application was attempting, the constraint identity and relation say
     * which rule was broken and where, and the cause retains the store's own account of the failure -
     * including the real constraint name, which is knowable only to the store because the entities
     * declare no associations and the constraints exist only in the schema.
     *
     * <p>Side effects: none beyond throwable construction. The cause is delegated to
     * {@link CardDemoException} untouched and is never inspected or unwrapped; neither name is validated
     * against the schema.
     *
     * @param message        the detail message, retrievable through {@link Throwable#getMessage()}.
     *                       Permitted to be null or blank and passed through unchanged. It must be
     *                       written for this application: copying the cause's message here is a Blocker,
     *                       because a driver's constraint-violation text commonly embeds the offending
     *                       key value, which on a card-related relationship is a card number
     * @param constraintName the identifier of the violated constraint. Permitted to be null or blank and
     *                       stored exactly as supplied. Carry the constraint identity here, never the
     *                       value that violated it
     * @param relation       the relation the refused write was aimed at, conventionally a table name.
     *                       Permitted to be null or blank and stored exactly as supplied
     * @param cause          the underlying throwable, typically the store-specific constraint-violation
     *                       exception, retrievable through {@link Throwable#getCause()}. Permitted to be
     *                       null; when non-null it is always retained
     */
    public DataIntegrityException(String message, String constraintName, String relation, Throwable cause) {
        super(message, cause);
        this.constraintName = constraintName;
        this.relation = relation;
    }

    /**
     * Returns the identifier of the violated constraint, exactly as it was supplied at construction.
     *
     * <p>The value is opaque: it was not parsed, validated, matched against any known set or normalised,
     * so it is returned byte for byte as given. Callers must not assume it corresponds to a constraint
     * that currently exists, and must not compare it against a hard-coded name - the single
     * authoritative declaration of the ten foreign keys and five check constraints is
     * {@code V1__create_schema.sql}, and duplicating any of those names in code would create a second
     * source of truth that drifts.
     *
     * <p>Side effects: none. This method reads no schema, opens no connection and performs no case or
     * format conversion, so it is deterministic and independent of locale.
     *
     * @return the constraint identifier as supplied, which may be null when no identifier was given and
     *         may be blank when a blank one was given; never altered from the supplied value
     */
    public String getConstraintName() {
        return constraintName;
    }

    /**
     * Returns the relation the refused write was aimed at, exactly as it was supplied at construction.
     *
     * <p>Conventionally this is a table name, but the value is opaque and no schema was consulted to
     * confirm that the relation exists. It is returned byte for byte as given.
     *
     * <p>Side effects: none. This method reads no schema, opens no connection and performs no case or
     * format conversion, so it is deterministic and independent of locale.
     *
     * @return the relation name as supplied, which may be null when no relation was given and may be
     *         blank when a blank one was given; never altered from the supplied value
     */
    public String getRelation() {
        return relation;
    }
}
