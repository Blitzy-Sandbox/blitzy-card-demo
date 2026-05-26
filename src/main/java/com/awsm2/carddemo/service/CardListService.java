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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardListDto;
import com.awsm2.carddemo.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Paged card-list service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COCRDLIC.cbl} (CICS transaction id
 * {@code CCLI}, mapset {@code COCRDLI}, map {@code CCRDLIA}).
 *
 * <p>This service produces a paginated card list (admin all-cards view
 * or per-account view) mirroring the 3270 card-list screen rendered by
 * {@code COCRDLIC.cbl}. In the COBOL source the program issues
 * {@code EXEC CICS STARTBR} on the {@code CARDDAT} VSAM KSDS (or its
 * {@code CARDAIX} alternate-index when the operator supplied an account
 * filter) and then iterates with {@code EXEC CICS READNEXT} to
 * materialize the 7-row {@code CCRDLIA} BMS map (see
 * {@code app/bms/COCRDLI.bms}, fields {@code ACCTNO1..ACCTNO7},
 * {@code CRDNUM1..CRDNUM7}, {@code CRDSTS1..CRDSTS7}). Paging is driven
 * by the {@code DFHPF7} / {@code DFHPF8} AID keys (page backward /
 * forward); the COBOL source carries explicit working-storage state for
 * the first / last keys of the current page
 * ({@code WS-CA-FIRST-CARDKEY}, {@code WS-CA-LAST-CARDKEY}) so the next
 * pseudo-conversation can resume the browse at the correct cursor
 * position.</p>
 *
 * <p>In the Java target, the equivalent semantics are achieved through
 * Spring Data {@link Pageable} pagination (page size <b>fixed at 7</b>
 * to preserve the legacy contract) plus the derived queries on
 * {@link CardRepository}. Cursor state is not maintained server-side
 * (REST is stateless per AAP &sect;0.3.4); the client supplies the
 * 0-indexed page number on each request.</p>
 *
 * <h2>Admin vs. non-admin routing (COBOL CDEMO-USRTYPE branch)</h2>
 * <ul>
 *   <li><b>Admin, no filter</b> &mdash; the COBOL {@code COCRDLIC.cbl}
 *       header (lines 4&ndash;7) states: "List Credit Cards
 *       a) All cards if no context passed and admin user". In the Java
 *       target this maps to {@code isAdmin == true &amp;&amp;
 *       accountFilter == null} &rarr;
 *       {@link CardRepository#findAll(Pageable)} ordered by
 *       {@code cardNum} ASC.</li>
 *   <li><b>Admin, with filter</b> &mdash; an administrator may
 *       optionally scope the browse to a single account. Maps to
 *       {@code isAdmin == true &amp;&amp; accountFilter != null} &rarr;
 *       {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
 *       Pageable)}.</li>
 *   <li><b>Non-admin, with filter</b> &mdash; the COBOL header (lines
 *       4&ndash;7) states: "b) Only the ones associated with ACCT in
 *       COMMAREA if user is not admin". The non-admin caller MUST have
 *       supplied the account filter (extracted by the controller from
 *       JWT claims / the COMMAREA-equivalent context). Maps to
 *       {@code isAdmin == false &amp;&amp; accountFilter != null}
 *       &rarr;
 *       {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
 *       Pageable)}.</li>
 *   <li><b>Non-admin, no filter</b> &mdash; a non-admin caller without
 *       an account context cannot see any cards. The COBOL source
 *       handles this through the {@code WS-NO-RECORDS-FOUND}
 *       88-level flag ({@code COCRDLIC.cbl}:L121&ndash;L122 with the
 *       verbatim text "NO RECORDS FOUND FOR THIS SEARCH CONDITION.").
 *       Maps in Java to an empty {@link CardListDto} response with the
 *       verbatim message logged at INFO level for operational
 *       traceability.</li>
 * </ul>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COCRDLIC.cbl} (CICS
 *       TRANID {@code 'CCLI'}, mapset {@code COCRDLI}, map
 *       {@code CCRDLIA}, file {@code 'CARDDAT'}; AAP &sect;0.4.1
 *       online-programs mapping).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COCRDLI.bms} (7-row card
 *       table).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COCRDLI.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, 150 bytes; 91 bytes of business fields
 *       plus 59 bytes of trailing FILLER) &mdash; mapped to JPA
 *       entity {@link Card}.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}
 *       ({@code KEYS(16 0)}, {@code RECORDSIZE(150 150)}).</li>
 *   <li><b>VSAM AIX:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} on
 *       {@code CARD-ACCT-ID} &mdash; replaced by the Spring Data
 *       derived query
 *       {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
 *       Pageable)} backed by the PostgreSQL index
 *       {@code idx_cards_acct_id} declared in V002.</li>
 *   <li><b>Page size 7:</b> verbatim transcription of the COBOL
 *       {@code WS-MAX-SCREEN-LINES VALUE 7}
 *       ({@code COCRDLIC.cbl}:L177&ndash;L178) and the
 *       {@code WS-EDIT-SELECT OCCURS 7 TIMES} working-storage array
 *       ({@code COCRDLIC.cbl}:L72&ndash;L82). AAP &sect;0.7.1
 *       Minimal Change Clause: 7 is a literal carry-over from the
 *       legacy 3270 screen layout, not an arbitrary REST
 *       convention.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COBOL COCRDLIC.cbl &harr;
 *            CardListService.listCards(...)</caption>
 *   <tr><th>COBOL paragraph / construct</th>
 *       <th>Java equivalent</th></tr>
 *   <tr><td>{@code 0000-MAIN} (entry, PF-key dispatch, COMMAREA
 *           hydration)</td>
 *       <td>{@link #listCards(Long, boolean, int)} entry point;
 *           PF-key dispatch is replaced by REST verb + path
 *           parameters; COMMAREA hydration is replaced by the
 *           caller-supplied {@code accountFilter} and {@code isAdmin}
 *           parameters (extracted by the controller from JWT
 *           claims / Spring Security {@code Authentication}).</td></tr>
 *   <tr><td>{@code WS-MAX-SCREEN-LINES VALUE 7} (L177&ndash;L178)</td>
 *       <td>{@link #PAGE_SIZE} constant.</td></tr>
 *   <tr><td>{@code STARTBR}/{@code READNEXT} on {@code CARDAIX} when
 *           {@code CDEMO-ACCT-ID} present</td>
 *       <td>{@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
 *           Pageable)}</td></tr>
 *   <tr><td>{@code STARTBR}/{@code READNEXT} on {@code CARDDAT}
 *           (admin no-filter branch)</td>
 *       <td>{@link CardRepository#findAll(Pageable)} ordered ascending
 *           by {@code cardNum}</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO} / page number / total
 *           pages</td>
 *       <td>{@link CardListDto#page()},
 *           {@link CardListDto#totalPages()},
 *           {@link CardListDto#first()},
 *           {@link CardListDto#last()}</td></tr>
 *   <tr><td>{@code 88 WS-NO-RECORDS-FOUND VALUE 'NO RECORDS FOUND
 *           FOR THIS SEARCH CONDITION.'} (L121&ndash;L122)</td>
 *       <td>{@link #NO_RECORDS_FOUND_MSG} verbatim constant; logged
 *           at INFO level when the result page is empty.</td></tr>
 *   <tr><td>{@code SETUP-PROTECT-USRTYPE} (admin vs. non-admin)
 *           &mdash; restricts non-admin browses to cards owned by
 *           {@code CDEMO-ACCT-ID}</td>
 *       <td>{@code isAdmin} parameter + non-null {@code accountFilter}
 *           contract; controller layer is responsible for extracting
 *           the user type and account context from the authenticated
 *           principal and supplying them faithfully here.</td></tr>
 * </table>
 *
 * <h2>Page size invariant (AAP &sect;0.7.3 Minimal Change Clause)</h2>
 * <p>The COBOL source hard-codes 7 rows per page via the literal
 * {@code WS-MAX-SCREEN-LINES VALUE 7} and the 88-level declarations on
 * the {@code CCRDLIA} BMS map. The Java target preserves this
 * invariant: callers do NOT supply a {@code size} parameter on this
 * service method &mdash; {@link #PAGE_SIZE} is the fixed, intentional,
 * non-overridable contract. Any REST controller layer that wishes to
 * expose a tunable {@code size} query parameter MUST coerce it to 7
 * before invoking this service (or document its deviation).</p>
 *
 * <h2>Concurrency and transactional behavior</h2>
 * <p>This service is read-only and declares
 * {@link Transactional @Transactional(readOnly = true)} on its public
 * method. The underlying queries on {@link CardRepository} run inside
 * a {@code READ COMMITTED} JPA / RDS PostgreSQL transaction for the
 * duration of the query &mdash; matching the COBOL source which
 * performs {@code STARTBR}/{@code READNEXT} without {@code UPDATE}-mode
 * locks. The {@code readOnly = true} flag enables the JPA provider's
 * flush-mode optimization (no dirty-checking on the persistence
 * context) per AAP &sect;0.7.1 transactional-integrity rules.</p>
 *
 * <h2>PCI-DSS guard rails (PAN masking)</h2>
 * <p>Each {@link CardListDto.CardRow} returned by this service masks
 * the PAN to the {@code ************XXXX} format (12 asterisks + last
 * 4 digits) before being placed on the wire. This format matches the
 * PCI-DSS v4.0 Requirement 3.4.1 PAN-masking guidance and the masking
 * format documented in {@link CardListDto} (see the {@code maskPan}
 * helper there). This service NEVER emits the full 16-digit card
 * number in a list response, in alignment with AAP &sect;0.6.6
 * PCI-DSS discipline. The {@link Logger} attached to this class
 * NEVER logs raw card numbers; only the account ID (non-PCI-sensitive)
 * and page number are logged for traceability.</p>
 *
 * <h2>No AWS SDK isolation</h2>
 * <p>This service contains no AWS SDK client calls. All AWS service
 * integrations (Secrets Manager, KMS, CloudWatch, etc.) are isolated
 * in the {@code com.awsm2.carddemo.adapter} package per AAP
 * &sect;0.7.1. This service depends only on its
 * {@link CardRepository}, {@link CardListDto}, and {@link Card}
 * collaborators &mdash; no direct AWS calls.</p>
 *
 * @see CardRepository
 * @see CardListDto
 * @see Card
 */
@Service
public class CardListService {

    /**
     * SLF4J class-level logger. Routed through Logback +
     * {@code logstash-logback-encoder} to CloudWatch Logs per AAP
     * &sect;0.6.6 observability. Log statements emit traceability
     * events for the paginated card-list browse (e.g.,
     * {@code "listCards accountFilter={} isAdmin={} page={}"}) and
     * the verbatim {@link #NO_RECORDS_FOUND_MSG} when a query
     * returns no results.
     *
     * <p><b>PCI-DSS discipline:</b> this logger NEVER receives raw
     * 16-digit card numbers as arguments. Card-number masking is the
     * responsibility of the {@link CardListDto.CardRow} producer
     * (see {@link #toRow(Card)} below). The account ID
     * ({@link Card#getCardAcctId()}) is non-PCI-sensitive and may be
     * logged for traceability.</p>
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(CardListService.class);

    /**
     * Page-size constant &mdash; fixed at 7 rows per page to preserve
     * the legacy COBOL contract.
     *
     * <p>This value is a <b>verbatim transcription</b> of the COBOL
     * working-storage literal:</p>
     * <pre>
     *   05  WS-MAX-SCREEN-LINES                    PIC S9(4) COMP
     *                                              VALUE 7.
     * </pre>
     * <p>declared at {@code app/cbl/COCRDLIC.cbl}:L177&ndash;L178, and
     * the {@code WS-EDIT-SELECT OCCURS 7 TIMES} working-storage array
     * at {@code COCRDLIC.cbl}:L72&ndash;L82 (which corresponds to the
     * 7-row BMS table {@code CRDSEL1..CRDSEL7} /
     * {@code ACCTNO1..ACCTNO7} / {@code CRDNUM1..CRDNUM7} /
     * {@code CRDSTS1..CRDSTS7} in {@code app/bms/COCRDLI.bms}). Per AAP
     * &sect;0.7.1 Minimal Change Clause, the value 7 is a literal
     * carry-over from the legacy 3270 screen layout &mdash; it is not
     * an arbitrary REST page-size convention and MUST NOT be changed
     * without an explicit migration request.</p>
     *
     * <p>Exposed as {@code public static final} so that:</p>
     * <ul>
     *   <li>Controllers can reference it when constructing
     *       {@link Pageable} requests upstream;</li>
     *   <li>Unit tests can verify the value without reflection
     *       (avoiding accidental drift in this constant);</li>
     *   <li>Documentation tooling (Javadoc, OpenAPI) can surface the
     *       fixed page size on the API contract.</li>
     * </ul>
     */
    // COBOL: COCRDLIC:WS-MAX-SCREEN-LINES=7 (L177-L178)
    public static final int PAGE_SIZE = 7;

    /**
     * Verbatim transcription of the COBOL
     * {@code 88 WS-NO-RECORDS-FOUND} message text at
     * {@code app/cbl/COCRDLIC.cbl}:L121&ndash;L122:
     * <pre>
     *   88  WS-NO-RECORDS-FOUND                 VALUE
     *       'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'.
     * </pre>
     * <p>The COBOL source rendered this message into the
     * {@code CCRDLIA} BMS map's {@code ERRMSG} field when a paged
     * browse returned no records (e.g., admin browse on an empty
     * cluster, or per-account browse for an account with no cards).
     * In the Java target this message is preserved verbatim per AAP
     * &sect;0.7.3 refactor discipline ("Error codes and condition
     * handling surfaced to downstream consumers must be preserved
     * verbatim") and emitted at INFO log level when an empty page is
     * returned, so the operational audit trail in CloudWatch / OpenSearch
     * retains the same diagnostic vocabulary the mainframe operators
     * are accustomed to seeing on the 3270 screen.</p>
     *
     * <p>The message is NOT surfaced directly in the
     * {@link CardListDto} response body (the DTO is a strict
     * record-projection of the BMS map's data fields and does not
     * carry a free-form error-text component); clients infer the
     * "no records" condition from
     * {@link CardListDto#totalElements()} {@code == 0L} and
     * {@link CardListDto#rows()} being an empty list.</p>
     */
    // COBOL: COCRDLIC:88 WS-NO-RECORDS-FOUND (L121-L122) verbatim
    private static final String NO_RECORDS_FOUND_MSG =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * Spring Data JPA repository abstracting the {@code CARDDATA} VSAM
     * KSDS / PostgreSQL {@code cards} table. Injected via constructor
     * (no field injection per Spring Boot 3.x best practice and AAP
     * &sect;0.7.1 "Dependency injection for loose coupling").
     */
    private final CardRepository cardRepository;

    /**
     * Constructor injection of {@link CardRepository}.
     *
     * <p>Following AAP &sect;0.3.3 layered architecture and AAP
     * &sect;0.7.1 "Constructor injection only" discipline. Spring's
     * IoC container locates the {@link CardRepository} bean
     * (auto-instantiated by Spring Data via the
     * {@code @EnableJpaRepositories} declaration in
     * {@code JpaConfig}) and supplies it here at bean-construction
     * time.</p>
     *
     * <p>A defensive null check via
     * {@link Objects#requireNonNull(Object, String)} guards against a
     * configuration error where the IoC container fails to supply the
     * repository (e.g., misconfigured {@code @EnableJpaRepositories}
     * scan or an explicit {@code null}-bean test override).</p>
     *
     * @param cardRepository Spring Data repository abstracting the
     *                       {@code CARDDATA} VSAM KSDS / PostgreSQL
     *                       {@code cards} table; never {@code null}
     */
    public CardListService(CardRepository cardRepository) {
        // COBOL: COCRDLIC has no constructor (CICS programs are
        // singletons loaded by RDO). Java target uses constructor
        // injection per AAP §0.7.1 Dependency Injection rule.
        this.cardRepository =
                Objects.requireNonNull(cardRepository, "cardRepository");
    }

    /**
     * Lists cards on the page identified by {@code page} (0-indexed),
     * routing by {@code isAdmin} flag and optional {@code accountFilter}.
     *
     * <p>This is the Java equivalent of the COBOL paragraph
     * {@code 0000-MAIN} (plus the
     * {@code 9000-READ-FORWARD}/{@code STARTBR}/{@code READNEXT} cycle)
     * in {@code app/cbl/COCRDLIC.cbl}, which (after PF-key dispatch and
     * screen-edit validation) issues {@code EXEC CICS STARTBR} on
     * either {@code CARDDAT} or {@code CARDAIX} and then iterates a
     * 7-row page via {@code EXEC CICS READNEXT}.</p>
     *
     * <h4>Routing matrix (COBOL CDEMO-USRTYPE branch in COCRDLIC.cbl)</h4>
     * <table>
     *   <caption>Caller context &harr; repository call</caption>
     *   <tr><th>{@code isAdmin}</th><th>{@code accountFilter}</th>
     *       <th>Behavior</th></tr>
     *   <tr><td>{@code true}</td><td>{@code null}</td>
     *       <td>All cards, paged ASC by {@code cardNum}
     *           ({@link CardRepository#findAll(Pageable)}).
     *           COBOL: admin user, no COMMAREA account context.</td></tr>
     *   <tr><td>{@code true}</td><td>non-null</td>
     *       <td>Cards owned by the specified account, paged ASC by
     *           {@code cardNum}
     *           ({@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
     *           Pageable)}). COBOL: admin user with explicit account
     *           filter.</td></tr>
     *   <tr><td>{@code false}</td><td>non-null</td>
     *       <td>Cards owned by the specified account
     *           ({@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long,
     *           Pageable)}). COBOL: non-admin user, account context
     *           from CICS COMMAREA.</td></tr>
     *   <tr><td>{@code false}</td><td>{@code null}</td>
     *       <td>Empty result; the verbatim
     *           {@link #NO_RECORDS_FOUND_MSG} is logged at INFO
     *           level. COBOL: non-admin user without account context
     *           cannot see any cards.</td></tr>
     * </table>
     *
     * <h4>Page-index normalization</h4>
     * <p>A negative {@code page} value is coerced to {@code 0} (first
     * page) for defense in depth &mdash; the COBOL source initializes
     * {@code WS-CA-SCREEN-NUM} to {@code 1} on first entry and never
     * accepts negative pages from the operator (the BMS map's
     * {@code PAGENO} field is a 3-character non-editable display
     * field). This normalization mirrors that contract for the REST
     * API.</p>
     *
     * <h4>PAN masking</h4>
     * <p>Every {@link CardListDto.CardRow} returned by this method has
     * its {@code cardNumber} <b>masked</b> via {@link #maskPan(String)}
     * before being placed on the wire (12 leading asterisks +
     * trailing 4 digits, matching the PCI-DSS v4.0 Requirement 3.4.1
     * masking pattern documented in {@link CardListDto}). Per AAP
     * &sect;0.6.6, this service NEVER emits the full PAN in any
     * response or log statement.</p>
     *
     * @param accountFilter optional 11-digit account ID to scope the
     *                      browse to cards owned by a specific account.
     *                      Sourced in the COBOL world from
     *                      {@code CDEMO-ACCT-ID} in the CICS COMMAREA
     *                      defined in {@code COCOM01Y.cpy}; sourced
     *                      in the Java world from JWT claims / Spring
     *                      Security {@code Authentication} (extracted
     *                      by the controller layer). May be
     *                      {@code null} only when {@code isAdmin} is
     *                      {@code true}.
     * @param isAdmin       {@code true} if the caller is an admin
     *                      user. Sourced in the COBOL world from
     *                      {@code CDEMO-USRTYP-ADMIN} in
     *                      {@code COCOM01Y.cpy}; sourced in the Java
     *                      world from Spring Security role checks /
     *                      JWT claims (typically the {@code ROLE_ADMIN}
     *                      authority via {@code @PreAuthorize}).
     * @param page          0-indexed page number to retrieve.
     *                      Negative values are coerced to {@code 0}.
     * @return a non-{@code null} {@link CardListDto} containing up to
     *         {@link #PAGE_SIZE} masked card rows plus paging metadata
     *         ({@link CardListDto#totalElements()},
     *         {@link CardListDto#totalPages()},
     *         {@link CardListDto#first()},
     *         {@link CardListDto#last()}). When no records match, an
     *         empty DTO ({@link CardListDto#rows()} is an empty list,
     *         {@code totalElements == 0L},
     *         {@code totalPages == 0}, both {@code first} and
     *         {@code last} flags are {@code true}) is returned and the
     *         verbatim {@link #NO_RECORDS_FOUND_MSG} is logged.
     */
    @Transactional(readOnly = true)
    public CardListDto listCards(Long accountFilter, boolean isAdmin, int page) {

        // COBOL: COCRDLIC:0000-MAIN entry. Normalize the page index per
        // the COBOL WS-CA-SCREEN-NUM PIC 9(1) (always >= 0) contract.
        final int safePage = Math.max(0, page);

        // Traceability log per AAP §0.6.6. PII discipline: no card
        // numbers (only the non-PCI account ID and page index).
        LOG.debug("listCards accountFilter={} isAdmin={} page={}",
                accountFilter, isAdmin, safePage);

        // -------------------------------------------------------------
        // Branch 1: Non-admin without account filter (COBOL
        // CDEMO-USRTYPE-USER branch where CDEMO-ACCT-ID is unset).
        // A non-admin caller cannot list cards without an account
        // context; the COBOL source surfaces this as the
        // WS-NO-RECORDS-FOUND condition. Return early with an empty
        // DTO so we do not waste a database round-trip.
        // -------------------------------------------------------------
        if (!isAdmin && accountFilter == null) {
            // COBOL: COCRDLIC:88 WS-NO-RECORDS-FOUND (L121-L122) emitted
            // verbatim for parity with the legacy operator-facing message.
            LOG.info("{}", NO_RECORDS_FOUND_MSG);
            return emptyDto(safePage, null);
        }

        // -------------------------------------------------------------
        // Build Pageable. Page size is the fixed COBOL constant
        // PAGE_SIZE (7); sort ascending by card_num to match the VSAM
        // KSDS primary-key browse order driven by the CICS STARTBR /
        // READNEXT cursor in COCRDLIC.cbl. Note that for the per-
        // account derived query (findByCardAcctIdOrderByCardNumAsc),
        // the method-name "OrderBy" clause overrides any Sort embedded
        // in the Pageable; for findAll(Pageable), the Sort is
        // honored. Supplying the Sort here unconditionally keeps the
        // semantic explicit and consistent across both code paths.
        // -------------------------------------------------------------
        // COBOL: COCRDLIC:WS-MAX-SCREEN-LINES=7 — fixed page size from L177-L178
        final Pageable pageable = PageRequest.of(
                safePage,
                PAGE_SIZE,
                Sort.by("cardNum").ascending());

        // -------------------------------------------------------------
        // Branch 2: Admin with no filter — full-cluster browse.
        // Branch 3: Either admin-with-filter or non-admin-with-filter —
        // per-account browse via the AIX-replacement derived query.
        // -------------------------------------------------------------
        final Page<Card> result;
        if (isAdmin && accountFilter == null) {
            // COBOL: COCRDLIC:CDEMO-USRTYPE-ADMIN branch — full
            // CARDDAT browse ordered by primary key CARD-NUM ASC.
            // Equivalent to CICS STARTBR FILE('CARDDAT') KEYLENGTH(0)
            // RIDFLD(LOW-VALUES) + READNEXT loop in COCRDLIC.cbl.
            result = cardRepository.findAll(pageable);
        } else {
            // COBOL: COCRDLIC:STARTBR-CARDAIX-RID (per-account browse
            // via the CARDAIX alternate index on CARD-ACCT-ID) +
            // READNEXT loop. Replaced by the Spring Data derived query
            // backed by PostgreSQL index idx_cards_acct_id (V002).
            result = cardRepository
                    .findByCardAcctIdOrderByCardNumAsc(accountFilter, pageable);
        }

        // -------------------------------------------------------------
        // Empty-result handling. Even when the query path is admin-
        // findAll or filtered-findByCardAcctId, the result may be
        // empty (e.g., empty cluster, account with no cards, or
        // page index past the last page). Surface the same verbatim
        // NO_RECORDS_FOUND_MSG via INFO log for operator parity, and
        // return an empty DTO that preserves the supplied account
        // filter in the response (clients expect a stable echo of the
        // filter regardless of result count).
        // -------------------------------------------------------------
        if (result.isEmpty()) {
            // COBOL: COCRDLIC:88 WS-NO-RECORDS-FOUND (L121-L122) verbatim
            LOG.info("{}", NO_RECORDS_FOUND_MSG);
            return emptyDto(safePage, accountFilter);
        }

        // -------------------------------------------------------------
        // Build the response DTO. For each Card entity in the page,
        // construct a CardListDto.CardRow with the PAN masked at the
        // producer per PCI-DSS v4.0 Requirement 3.4.1 (AAP §0.6.6).
        // -------------------------------------------------------------
        // COBOL: COCRDLIC:POPULATE-HEADER-INFO + per-row screen build
        final List<CardListDto.CardRow> rows = result.getContent().stream()
                .map(this::toRow)
                .toList();

        return new CardListDto(
                rows,
                result.getNumber(),          // 0-indexed page number
                PAGE_SIZE,                   // fixed page size per AAP
                result.getTotalElements(),   // total rows across all pages
                result.getTotalPages(),      // total page count
                result.isFirst(),            // COBOL: WS-CA-FIRST-PAGE
                result.isLast(),             // COBOL: CA-LAST-PAGE-SHOWN
                accountFilter,               // echo back the filter
                null);                       // cardNumberFilter unused here
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an empty {@link CardListDto} for "no records" responses.
     *
     * <p>The DTO has an empty {@code rows} list,
     * {@code totalElements == 0L},
     * {@code totalPages == 0}, and both {@code first} and {@code last}
     * page indicators set to {@code true} (a zero-element collection
     * is logically both the first and the last page). The
     * {@code accountFilter} is echoed back so the client can correlate
     * the empty result with its request.</p>
     *
     * <p>This factory exists because the canonical {@link CardListDto}
     * record constructor takes 9 arguments and the same boilerplate
     * would otherwise be duplicated across the two empty-result code
     * paths (non-admin-without-filter and empty-page).</p>
     *
     * @param page          the supplied page number (already
     *                      normalized via {@link Math#max(int, int)})
     * @param accountFilter the supplied account filter, echoed back;
     *                      may be {@code null}
     * @return a non-{@code null} {@link CardListDto} with no rows
     */
    private CardListDto emptyDto(int page, Long accountFilter) {
        return new CardListDto(
                Collections.emptyList(),
                page,
                PAGE_SIZE,
                0L,                          // totalElements
                0,                           // totalPages
                true,                        // first
                true,                        // last
                accountFilter,
                null);                       // cardNumberFilter
    }

    /**
     * Maps a {@link Card} JPA entity to a {@link CardListDto.CardRow}.
     *
     * <p>The PAN is <b>always masked</b> via {@link #maskPan(String)}
     * before being placed on the wire &mdash; this service never emits
     * the full 16-digit card number in a list response, in alignment
     * with AAP &sect;0.6.6 PCI-DSS discipline. The CVV is
     * intentionally <b>not</b> included on the row by design (the
     * {@link CardListDto.CardRow} record has no CVV component, and
     * the underlying {@link Card} JPA entity no longer carries CVV
     * after the V017 migration that removed {@code card_cvv_cd} per
     * PCI-DSS v4.0 Requirement 3.2 and QA finding DB1); the
     * embossed name and expiration date are surfaced for richer
     * client rendering as documented in {@link CardListDto}.</p>
     *
     * <p><b>QA finding U2 (optimistic-lock token visibility on list
     * responses):</b> the JPA {@code @Version} value carried on the
     * {@link Card} entity is propagated to the
     * {@link CardListDto.CardRow#version()} component so that clients
     * iterating the list can submit an optimistic-lock-safe PUT for
     * any row <b>without</b> first round-tripping to the detail
     * endpoint to obtain the version. The version is monotonically
     * non-decreasing and is incremented by Hibernate on every
     * successful UPDATE per AAP &sect;0.4.1.</p>
     *
     * <p>Defensive masking is also applied inside
     * {@link CardListDto.CardRow#toString()} (as documented in
     * {@link CardListDto}) so that even if a future code path
     * inadvertently constructed a row with an unmasked PAN, the
     * default {@code toString()} would not leak it. This service
     * pre-masks at the producer as the primary line of defense.</p>
     *
     * @param card the {@link Card} entity to convert; never
     *             {@code null} (the {@link Page#getContent()} stream
     *             never contains {@code null} elements)
     * @return a non-{@code null} {@link CardListDto.CardRow} with the
     *         PAN masked and the JPA {@code @Version} carried on
     *         {@link CardListDto.CardRow#version()}
     */
    private CardListDto.CardRow toRow(Card card) {
        // COBOL: COCRDLIC:per-row screen build (BMS field set:
        //   ACCTNOn   ← CARD-ACCT-ID
        //   CRDNUMn   ← CARD-NUM (masked here per PCI-DSS)
        //   CRDSTSn   ← CARD-ACTIVE-STATUS
        // The embossed name and expiration date are not displayed on
        // the legacy 3270 list screen but are surfaced on the REST
        // response for richer client rendering per the CardListDto
        // contract (AAP §0.7.3 information enrichment, not behavior
        // change).
        //
        // QA finding U2: the JPA @Version token is carried on the
        // row so REST clients can submit an optimistic-lock-safe PUT
        // directly from a list-response entry without a round-trip
        // to the detail endpoint. The version field has no COBOL
        // provenance — it is the JPA replacement for the COBOL
        // before/after image comparison in COCRDUPC.cbl.
        return new CardListDto.CardRow(
                maskPan(card.getCardNum()),       // PCI-DSS PAN masking
                card.getCardAcctId(),
                card.getCardEmbossedName(),
                card.getCardExpirationDate(),
                card.getCardActiveStatus(),
                card.getVersion());
    }

    /**
     * Masks a card-number string for PCI-DSS-compliant transmission.
     *
     * <p>Applies the standard {@code "************XXXX"} format
     * (12 leading asterisks + the trailing 4 characters of the input)
     * matching:</p>
     * <ul>
     *   <li>The PCI-DSS v4.0 Requirement 3.4.1 PAN-masking guidance
     *       (a maximum of the first 6 and last 4 digits may be
     *       displayed; CardDemo's policy is to display only the last
     *       4 to be conservative, AAP &sect;0.6.6).</li>
     *   <li>The {@link CardListDto} producer expectation: "{@code
     *       cardNumber} is pre-masked at the producer (only the last
     *       4 digits are retained, the leading 12 digits replaced by
     *       {@code '*'} characters per industry-standard PAN
     *       masking)".</li>
     *   <li>The defensive masking applied by
     *       {@link CardListDto.CardRow#toString()} (same
     *       12-asterisk + last-4-digit format) so the producer's
     *       masking is idempotent under the DTO's toString().</li>
     * </ul>
     *
     * <p>Defensive handling:</p>
     * <ul>
     *   <li>{@code null} input &rarr; returns {@code "****"} (fully
     *       masked) rather than {@code null} or empty string &mdash;
     *       guarantees the response never carries a {@code null} PAN
     *       in a list row.</li>
     *   <li>Input shorter than 4 characters &rarr; returns
     *       {@code "****"} (fully masked) rather than leaking partial
     *       digit information.</li>
     *   <li>Input of any other length &rarr; returns the
     *       12-asterisk-prefixed trailing 4 characters of the input.
     *       The COBOL source always supplies a 16-character
     *       {@code CARD-NUM PIC X(16)} value, so this branch is the
     *       overwhelmingly common path.</li>
     * </ul>
     *
     * <p><b>PCI-DSS rationale:</b> the 12-asterisk + last-4-digit
     * format is the format documented in {@link CardListDto} (see the
     * {@code maskPan} package-private helper there). The Card entity's
     * own {@link Card#toString()} renders the masking as
     * {@code "****-****-****-XXXX"} (4-4-4-4 grouping with hyphens)
     * for log-line readability; this service's helper uses the
     * un-grouped {@code "************XXXX"} form to match the DTO
     * producer contract exactly (so the DTO's defensive
     * {@code toString()} masking is idempotent on the value this
     * service supplies).</p>
     *
     * @param pan the card-number string to mask; may be {@code null}
     *            or shorter than 4 characters (defensive)
     * @return a non-{@code null} masked representation; never
     *         leaks more than the last 4 characters of the input
     */
    // COBOL: COCRDLIC has no equivalent — PAN masking is a Java-side
    // PCI-DSS guard rail added per AAP §0.6.6. The COBOL 3270 screen
    // historically displayed the full 16-digit CARD-NUM (the 3270
    // network was assumed to be physically isolated); the REST world
    // requires defense in depth against log/audit leakage.
    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            // Defensive fallback — never leak any digit on a malformed
            // input. PCI-DSS v4.0 3.4.1 (mask all but the last 4) is
            // satisfied vacuously when no digits are revealed.
            return "****";
        }
        // PCI-DSS v4.0 Requirement 3.4.1: mask all but the last 4
        // characters. 12 asterisks + 4 trailing characters matches the
        // CardListDto producer/consumer contract for the masked PAN.
        return "************" + pan.substring(pan.length() - 4);
    }
}
