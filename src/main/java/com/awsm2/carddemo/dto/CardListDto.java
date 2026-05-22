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
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Paged card-list response DTO.
 *
 * <p>Replaces the 3270 card-list screen rendered by the COBOL/CICS program
 * {@code COCRDLIC.cbl} (CICS transaction id {@code CCLI}).  In the source,
 * {@code COCRDLIC} uses {@code EXEC CICS STARTBR}/{@code READNEXT} on the
 * {@code CARDDATA} VSAM KSDS (alternate-index browse via
 * {@code CARDAIX}/{@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} when filtering
 * by account ID) to browse 7 card records at a time (the BMS map
 * {@code CCRDLIA} in {@code app/bms/COCRDLI.bms} defines a 7-row table
 * {@code CRDSEL1..CRDSEL7}, {@code ACCTNO1..ACCTNO7},
 * {@code CRDNUM1..CRDNUM7}, {@code CRDSTS1..CRDSTS7}), with paging driven
 * by the {@code PF7} (page backward) and {@code PF8} (page forward) keys
 * &mdash; the 24th-line footer instructs the operator with
 * {@code "F3=Exit F7=Backward F8=Forward"}.  The COBOL working storage
 * declares {@code WS-EDIT-SELECT-FLAGS PIC X(7)} and
 * {@code WS-EDIT-SELECT OCCURS 7 TIMES} (see {@code COCRDLIC.cbl} lines
 * 72&ndash;82), confirming the page-of-7 contract.
 *
 * <p>In the Java target, this is replaced by Spring Data
 * {@link org.springframework.data.domain.Pageable} on
 * {@code CardRepository.findAll(Pageable)} with {@code size=7} to preserve
 * the original page size.  The optional {@link #accountFilter()} narrows
 * the result set to cards owned by a specific account ID (mirroring the
 * COBOL non-admin branch, which restricts the browse to cards associated
 * with the {@code CDEMO-ACCT-ID} carried in the CICS COMMAREA defined in
 * {@code COCOM01Y.cpy}), and {@link #cardNumberFilter()} positions the
 * browse at a specific card number prefix.
 *
 * <p>Controller endpoint:
 * {@code GET /api/cards?account={id}&page={n}&size=7} &mdash; see
 * {@code CardController} (AAP &sect;0.3.4 REST endpoint inventory).
 *
 * <p><b>BMS field origin &mdash; per-row mapping (CCRDLIA map, 7 occurrences
 * &mdash; values shown for row {@code n = 1..7}):</b>
 * <pre>{@code
 *   BMS field    Length   CVACT02Y field            CardRow component
 *   ----------   ------   -----------------------   --------------------
 *   ACCTNOnI     X(11)    CARD-ACCT-ID    9(11)     accountId
 *   CRDNUMnI     X(16)    CARD-NUM        X(16)     cardNumber  (masked)
 *   CRDSTSnI     X(01)    CARD-ACTIVE-STATUS X(01)  activeStatus
 *   (none)                CARD-EMBOSSED-NAME X(50)  embossedName  *
 *   (none)                CARD-EXPIRAION-DATE X(10) expirationDate **
 *   CRDSELnI     X(01)    (selection field;         (not surfaced; the
 *                          PF-key based row selection  REST API has no
 *                          on the 3270 screen)         per-row selector
 *                                                      &mdash; clients
 *                                                      navigate by the
 *                                                      cardNumber path
 *                                                      parameter on the
 *                                                      detail endpoint)
 * }</pre>
 *
 * <p>(*) The legacy 3270 screen does not render the embossed name in the
 * list view (the BMS map allocates only 11+16+1 = 28 character positions
 * per row out of the available 80 columns).  The REST response surfaces
 * the embossed name from the underlying {@code CARD-RECORD} so callers
 * (mobile clients, account dashboards) can render a richer view without a
 * round-trip to the detail endpoint.  This is information enrichment via
 * additional record fields, not a change to any preserved business
 * calculation, and is consistent with the AAP Minimal Change Clause (AAP
 * &sect;0.7.3) since no existing behavior is altered.
 *
 * <p>(**) Native {@code java.time.LocalDate} replaces the LE-managed
 * {@code CARD-EXPIRAION-DATE PIC X(10)} string per AAP &sect;0.5.2 (no
 * {@code CEEDAYS} dependency).  Serialized as an ISO-8601 date string
 * ({@code yyyy-MM-dd}) using {@link JsonFormat} for stable client
 * contracts.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COCRDLI.bms} (mapset {@code COCRDLI},
 *       map {@code CCRDLIA}, 7-row card table)</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COCRDLI.CPY}
 *       ({@code CCRDLIAI} input record)</li>
 *   <li>Program: {@code app/cbl/COCRDLIC.cbl} (CICS transaction
 *       {@code CCLI}, program name {@code COCRDLIC})</li>
 *   <li>Record Layout: {@code app/cpy/CVACT02Y.cpy}
 *       ({@code CARD-RECORD}, RECLN=150)</li>
 *   <li>VSAM Cluster: {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}
 *       (RECLN=150, KEYLEN=16; primary key {@code CARD-NUM}); browse by
 *       account ID uses the alternate index
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} on
 *       {@code CARD-ACCT-ID}, replaced in the Java target by a derived
 *       repository query {@code findByAccountId(Long, Pageable)} per AAP
 *       &sect;0.6.2.</li>
 *   <li>COMMAREA fields driving the per-account scope (non-admin users):
 *       {@code CDEMO-ACCT-ID PIC 9(11)} from {@code COCOM01Y.cpy} &mdash;
 *       replaced by the {@link #accountFilter()} query parameter on the
 *       REST endpoint, with role-based gating handled by Spring Security
 *       on the controller.</li>
 * </ul>
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.7.1, &sect;0.6.6):</b>
 * <ul>
 *   <li>{@link CardRow#cardNumber()} is <b>pre-masked at the producer</b>
 *       (only the last 4 digits are retained, the leading 12 digits
 *       replaced by {@code '*'} characters per industry-standard PAN
 *       masking).  This DTO holds the masked string verbatim and never
 *       performs un-masking.  Callers must NEVER attempt to reverse the
 *       masking.</li>
 *   <li>{@link #cardNumberFilter()}, when echoed back to the client, is
 *       also masked using the same 12-asterisk + last-4-digit format.</li>
 *   <li>{@code CARD-CVV-CD PIC 9(03)} from {@code CVACT02Y.cpy} is
 *       <b>intentionally excluded</b> from {@link CardRow}.  CVV/CVC values
 *       are sensitive authentication data (SAD) under PCI-DSS and must
 *       NEVER appear in any response body, log line, audit event, or
 *       transient cache &mdash; AAP &sect;0.6.6 forbids any plaintext
 *       card-data exposure beyond pre-masked PAN.</li>
 *   <li>{@code FILLER PIC X(59)} from the 150-byte {@code CARD-RECORD}
 *       layout is not mapped &mdash; it is unused padding in the
 *       on-disk layout.</li>
 *   <li>The default {@link Record#toString()} generated for the outer
 *       {@code CardListDto} record is safe to log because it delegates to
 *       {@link CardRow#toString()} for each row, which is explicitly
 *       overridden below to emit only PCI-safe fields (the already-masked
 *       card number plus non-sensitive identifiers).</li>
 * </ul>
 *
 * <p>This record is immutable and response-only; it carries no Jakarta
 * Bean Validation constraints (the request side &mdash; query parameters
 * {@code account}, {@code cardNumber}, {@code page}, {@code size} &mdash;
 * is validated at the controller method-parameter level, not on this DTO).
 *
 * @param rows             the list of card rows on the current page; up to
 *                         7 entries to match the 7-row BMS table in
 *                         {@code COCRDLI.bms}
 * @param page             the current page number (0-indexed, as produced
 *                         by Spring Data {@code Page#getNumber()}; the
 *                         COBOL source displayed a 3-character page number
 *                         via the BMS field {@code PAGENO PIC X(3)} which
 *                         was 1-indexed, and the Java target normalizes
 *                         to 0-indexed per Spring Data convention)
 * @param size             the page size; fixed at 7 to match the legacy
 *                         BMS table capacity of {@code COCRDLI.bms}
 * @param totalElements    the total number of matching cards across all
 *                         pages, reflecting {@link #accountFilter()} and
 *                         {@link #cardNumberFilter()} when provided
 * @param totalPages       the total number of pages available given
 *                         {@code totalElements} and {@code size}
 * @param first            {@code true} if this is the first page
 *                         (corresponds to the COBOL guard for "top of the
 *                         page" on the {@code PF7} handler)
 * @param last             {@code true} if this is the last page
 *                         (corresponds to the COBOL guard for "bottom of
 *                         the page" on the {@code PF8} handler)
 * @param accountFilter    the optional account-ID filter echoed from the
 *                         request; matches the legacy BMS field
 *                         {@code ACCTSID PIC X(11)} (and the COBOL
 *                         working-storage flag
 *                         {@code WS-EDIT-ACCT-FLAG} validating
 *                         {@code FLG-ACCTFILTER-ISVALID}); may be
 *                         {@code null} when listing all cards (admin
 *                         view) or required when listing cards owned by
 *                         a specific account (non-admin view per
 *                         {@code CDEMO-USRTYPE-USER} branch in
 *                         {@code COCRDLIC.cbl})
 * @param cardNumberFilter the optional card-number filter echoed from
 *                         the request; matches the legacy BMS field
 *                         {@code CARDSID PIC X(16)} (and the COBOL
 *                         working-storage flag
 *                         {@code WS-EDIT-CARD-FLAG} validating
 *                         {@code FLG-CARDFILTER-ISVALID}); when present,
 *                         is masked using the same 12-asterisk +
 *                         last-4-digit format used for
 *                         {@link CardRow#cardNumber()}
 *
 * @see CardListDto.CardRow
 */
@Schema(name = "CardListDto",
        description = "Paged card-list response. Replaces the 3270 "
                + "card-list screen rendered by COBOL program COCRDLIC / "
                + "BMS mapset COCRDLI. Page size is fixed at 7 to match "
                + "the 7-row BMS table CCRDLIA.")
public record CardListDto(

        @Schema(description = "Card rows on this page. Up to 7 entries to "
                + "match the legacy BMS 7-row table (COCRDLI.bms map "
                + "CCRDLIA, rows ACCTNO1..ACCTNO7 / CRDNUM1..CRDNUM7 / "
                + "CRDSTS1..CRDSTS7).")
        @JsonProperty("rows")
        List<CardRow> rows,

        @Schema(description = "Current page number (0-indexed), as produced "
                + "by Spring Data Page#getNumber(). The legacy COBOL "
                + "program (COCRDLIC.cbl) displayed a 3-character page "
                + "number via BMS field PAGENO PIC X(3); the Java target "
                + "normalizes to 0-indexed paging per Spring Data "
                + "convention.",
                example = "0")
        @JsonProperty("page")
        int page,

        @Schema(description = "Page size. Fixed at 7 to match the 7-row "
                + "BMS table in COCRDLI.bms. Any request with a different "
                + "size is coerced to 7 by the controller for fidelity "
                + "with the legacy screen.",
                example = "7")
        @JsonProperty("size")
        int size,

        @Schema(description = "Total number of matching cards across all "
                + "pages, as produced by Spring Data "
                + "Page#getTotalElements(). Reflects the accountFilter and "
                + "cardNumberFilter when provided.",
                example = "143")
        @JsonProperty("totalElements")
        long totalElements,

        @Schema(description = "Total number of pages available given "
                + "totalElements and size, as produced by Spring Data "
                + "Page#getTotalPages().",
                example = "21")
        @JsonProperty("totalPages")
        int totalPages,

        @Schema(description = "First-page indicator. True when this is "
                + "page 0. Corresponds to the COBOL guard in COCRDLIC.cbl "
                + "PROCESS-PF7-KEY that emits \"You are already at the top "
                + "of the page...\".",
                example = "true")
        @JsonProperty("first")
        boolean first,

        @Schema(description = "Last-page indicator. True when this is the "
                + "final page. Corresponds to the COBOL guard in "
                + "COCRDLIC.cbl PROCESS-PF8-KEY that emits \"You are "
                + "already at the bottom of the page...\".",
                example = "false")
        @JsonProperty("last")
        boolean last,

        @Schema(description = "Account-ID filter echoed from the request. "
                + "Mirrors the legacy BMS field ACCTSID PIC X(11) which "
                + "the COBOL program (COCRDLIC.cbl) edits via "
                + "FLG-ACCTFILTER-ISVALID before issuing STARTBR on the "
                + "CARDAIX alternate index. May be null when listing all "
                + "cards (admin view, CDEMO-USRTYPE-ADMIN branch) or "
                + "required when the caller is restricted to cards owned "
                + "by a specific account (non-admin view, "
                + "CDEMO-USRTYPE-USER branch carrying CDEMO-ACCT-ID in "
                + "the CICS COMMAREA defined in COCOM01Y.cpy).",
                example = "10000000001",
                nullable = true)
        @JsonProperty("accountFilter")
        Long accountFilter,

        @Schema(description = "Card-number filter echoed from the request "
                + "(masked when present). Mirrors the legacy BMS field "
                + "CARDSID PIC X(16) which the COBOL program "
                + "(COCRDLIC.cbl) edits via FLG-CARDFILTER-ISVALID before "
                + "positioning the browse. When echoed back to the client, "
                + "this value is masked using the same 12-asterisk + "
                + "last-4-digit PCI-DSS-compliant format as cardNumber on "
                + "each row.",
                example = "************1234",
                nullable = true,
                maxLength = 16)
        @JsonProperty("cardNumberFilter")
        String cardNumberFilter
) {

    /**
     * Returns a PCI-DSS-safe string representation of this {@code CardListDto}.
     *
     * <p>The default {@link Record#toString()} generated by the compiler
     * would emit {@link #cardNumberFilter()} verbatim &mdash; if a caller
     * accidentally constructed the DTO with an unmasked PAN-shaped value
     * in the filter (for example, an integration test that passes a raw
     * 16-digit card number through the query string for negative-path
     * validation), the default {@code toString()} would leak that PAN
     * into CloudWatch Logs / OpenSearch via any framework or interceptor
     * that logs response payloads.
     *
     * <p>This override applies the same 12-asterisk + last-4-digit PAN
     * masking format used by {@link CardRow#toString()} to the
     * {@code cardNumberFilter} component before rendering, so the outer
     * DTO is safe to log under PCI-DSS Requirement 3.4 regardless of
     * what value the producer placed in the filter.  The {@code rows}
     * list delegates to {@link CardRow#toString()} for each entry,
     * which is also defensively masked.
     *
     * @return a string representation safe for logging and audit emission
     */
    @Override
    public String toString() {
        return "CardListDto[rows=" + rows
                + ", page=" + page
                + ", size=" + size
                + ", totalElements=" + totalElements
                + ", totalPages=" + totalPages
                + ", first=" + first
                + ", last=" + last
                + ", accountFilter=" + accountFilter
                + ", cardNumberFilter=" + maskPan(cardNumberFilter)
                + "]";
    }

    /**
     * Defensively masks a card-number-shaped string for safe logging.
     *
     * <p>The masking algorithm preserves only the last 4 characters and
     * prefixes them with 12 asterisks, producing the PCI-DSS-compliant
     * {@code "************nnnn"} form.  {@code null} inputs render as
     * literal {@code null}; inputs shorter than 4 characters are rendered
     * as {@code "****"} (fully masked) to avoid leaking partial digit
     * information.
     *
     * <p>The masking is <b>idempotent</b>: a string that is already in
     * the {@code "************nnnn"} form passes through unchanged (the
     * last 4 characters are preserved and the leading characters are
     * re-prefixed with 12 asterisks).
     *
     * @param value the card-number-shaped value to mask (may be {@code null})
     * @return the masked representation, or {@code null} if the input is
     *         {@code null}; otherwise the masked string
     */
    static String maskPan(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() < 4) {
            return "****";
        }
        return "************" + value.substring(value.length() - 4);
    }

    /**
     * One row of the paged card list.
     *
     * <p>Mirrors a single occurrence of the 7-row BMS table {@code CCRDLIA}
     * defined in {@code app/bms/COCRDLI.bms} and a single
     * {@code CARD-RECORD} defined in {@code app/cpy/CVACT02Y.cpy}
     * (RECLN=150).  The BMS row displays three visible fields
     * ({@code ACCTNOnI}, {@code CRDNUMnI}, {@code CRDSTSnI}); this Java
     * record surfaces those plus two additional fields from the
     * underlying record ({@code embossedName}, {@code expirationDate})
     * so REST callers can render the list without round-tripping for
     * detail.
     *
     * <p><b>Per-component COBOL provenance (CVACT02Y.cpy):</b>
     * <ul>
     *   <li>{@link #cardNumber()} &harr; {@code CARD-NUM PIC X(16)}
     *       (held here in masked form: 12 leading {@code '*'} characters
     *       plus the trailing 4 digits of the original PAN)</li>
     *   <li>{@link #accountId()} &harr;
     *       {@code CARD-ACCT-ID PIC 9(11)} (11-digit unsigned account
     *       identifier, foreign key to the {@code ACCTDATA} cluster /
     *       {@code Account} JPA entity)</li>
     *   <li>{@link #embossedName()} &harr;
     *       {@code CARD-EMBOSSED-NAME PIC X(50)}</li>
     *   <li>{@link #expirationDate()} &harr;
     *       {@code CARD-EXPIRAION-DATE PIC X(10)} (note the
     *       original-source spelling &mdash; "EXPIRAION", the missing T
     *       is in the COBOL copybook itself; the Java record uses the
     *       correctly-spelled name "expirationDate" for clarity on the
     *       wire)</li>
     *   <li>{@link #activeStatus()} &harr;
     *       {@code CARD-ACTIVE-STATUS PIC X(01)} ({@code "Y"} = active,
     *       {@code "N"} = inactive)</li>
     * </ul>
     *
     * <p><b>Intentionally excluded from this row (per PCI-DSS / record
     * layout):</b>
     * <ul>
     *   <li>{@code CARD-CVV-CD PIC 9(03)}: CVV is sensitive
     *       authentication data (SAD) and must never appear in any
     *       response, log, audit event, or cache (AAP &sect;0.6.6).</li>
     *   <li>{@code FILLER PIC X(59)}: 59-byte padding with no business
     *       meaning, retained only in the on-disk layout.</li>
     * </ul>
     *
     * <p><b>PCI-DSS:</b> {@link #cardNumber()} is delivered pre-masked.
     * The {@link #toString()} method below is explicitly overridden so
     * log statements that print the row never accidentally emit the full
     * PAN.  Even though the card number is pre-masked, the explicit
     * {@code toString()} provides defense in depth and a single audited
     * place to update if the masking format ever changes.
     *
     * @param cardNumber     the card number associated with the row,
     *                       <b>pre-masked at the producer</b> (only the
     *                       last 4 digits retained); sourced from
     *                       {@code CARD-NUM} after masking
     * @param accountId      the owning account ID; sourced from
     *                       {@code CARD-ACCT-ID PIC 9(11)}
     * @param embossedName   the embossed name on the physical card
     *                       (up to 50 characters); sourced from
     *                       {@code CARD-EMBOSSED-NAME PIC X(50)}
     * @param expirationDate the card's expiration date; sourced from
     *                       {@code CARD-EXPIRAION-DATE PIC X(10)} (note
     *                       the original-source spelling) and converted
     *                       to native {@link LocalDate}
     * @param activeStatus   the active-status indicator;
     *                       {@code "Y"} = active, {@code "N"} = inactive;
     *                       sourced from
     *                       {@code CARD-ACTIVE-STATUS PIC X(01)}
     */
    @Schema(name = "CardListDto.CardRow",
            description = "Single card row in the paged list. Mirrors one "
                    + "occurrence of the 7-row BMS table CCRDLIA and one "
                    + "CARD-RECORD in CVACT02Y.cpy. Card numbers are "
                    + "pre-masked at the producer (PCI-DSS).")
    public record CardRow(

            @Schema(description = "Masked card number. Maps to COBOL "
                    + "CARD-NUM PIC X(16) in CVACT02Y.cpy, masked at the "
                    + "producer so only the last 4 digits are retained. "
                    + "The leading 12 characters are '*' per PCI-DSS PAN "
                    + "masking guidance. Never carries the full PAN.",
                    example = "************1234",
                    maxLength = 16)
            @JsonProperty("cardNumber")
            String cardNumber,

            @Schema(description = "Owning account ID. Maps to COBOL "
                    + "CARD-ACCT-ID PIC 9(11) in CVACT02Y.cpy. Foreign "
                    + "key to the ACCTDATA cluster / Account JPA entity.",
                    example = "10000000001",
                    minimum = "0",
                    maximum = "99999999999")
            @JsonProperty("accountId")
            Long accountId,

            @Schema(description = "Embossed name on the physical card. "
                    + "Maps to COBOL CARD-EMBOSSED-NAME PIC X(50) in "
                    + "CVACT02Y.cpy. Up to 50 characters. Not surfaced "
                    + "on the legacy 3270 list view (the BMS row only "
                    + "renders account number, card number, and active "
                    + "status due to width constraints) &mdash; included "
                    + "in the REST response for richer client renderings.",
                    example = "JOHN DOE",
                    maxLength = 50)
            @JsonProperty("embossedName")
            String embossedName,

            @JsonFormat(shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd")
            @Schema(description = "Card expiration date. Replaces COBOL "
                    + "CARD-EXPIRAION-DATE PIC X(10) (note the "
                    + "original-source spelling \"EXPIRAION\" \u2014 the "
                    + "missing T is in the COBOL copybook itself) with "
                    + "native java.time.LocalDate. Serialized on the wire "
                    + "as an ISO-8601 date string (yyyy-MM-dd). Replaces "
                    + "the LE CEEDAYS dependency per AAP \u00a70.5.2.",
                    example = "2030-12-31",
                    format = "date")
            @JsonProperty("expirationDate")
            LocalDate expirationDate,

            @Schema(description = "Active-status indicator. Maps to "
                    + "COBOL CARD-ACTIVE-STATUS PIC X(01) in CVACT02Y.cpy. "
                    + "\"Y\" denotes an active card; \"N\" denotes an "
                    + "inactive card.",
                    example = "Y",
                    allowableValues = {"Y", "N"},
                    maxLength = 1)
            @JsonProperty("activeStatus")
            String activeStatus
    ) {

        /**
         * Returns a PCI-DSS-safe string representation of this row.
         *
         * <p>The default {@link Record#toString()} generated by the
         * compiler would emit every component verbatim including the
         * {@link #cardNumber()}.  Although the producer ({@code
         * CardListService}) is expected to pre-mask the card number
         * before constructing the row, <b>defensive masking</b> is
         * applied here so that any direct construction path (test
         * fixtures, mappers, integration test scenarios) that passes
         * a raw 16-digit PAN cannot leak that PAN into CloudWatch Logs,
         * OpenSearch indices, or any framework/interceptor that logs the
         * row via the default {@code Object#toString()} path.  The
         * {@link CardListDto#maskPan(String) maskPan} helper applies the
         * standard 12-asterisk + last-4-digit format and is idempotent
         * &mdash; values that are already masked pass through unchanged.
         *
         * <p>This override also provides:
         * <ul>
         *   <li>A single, audited location to update if the PAN masking
         *       format or the list of safe-to-log fields ever changes
         *       &mdash; the change is made here and is picked up
         *       wherever the row is logged.</li>
         *   <li>An explicit, inline-commented documentation of the
         *       deliberate omission of any plaintext sensitive
         *       authentication data (no CVV, no full PAN, no PIN, no
         *       PIN block, no track data) at the DTO boundary so
         *       reviewers can verify PCI-DSS compliance at a glance.</li>
         * </ul>
         *
         * <p><b>NOTE on record-generated {@code equals()}/{@code hashCode()}
         * (accepted risk per AAP &sect;0.6.6).</b>  Java records auto-generate
         * {@link Object#equals(Object)} and {@link Object#hashCode()} over
         * every component &mdash; including {@link #cardNumber()} &mdash;
         * and the record contract forbids overriding these methods to
         * exclude components without converting the type to a regular
         * class (a scope expansion that would violate the AAP-mandated
         * Minimal Change Clause).  The risk that the (typically already
         * masked) PAN participates in equality/hash operations is
         * <b>accepted</b> because:
         * <ul>
         *   <li>The producer ({@code CardListService}) pre-masks the
         *       PAN at row construction time, so the canonical
         *       in-memory value is the masked form
         *       ({@code ************XXXX}).</li>
         *   <li>{@link Object#equals(Object)} and {@link Object#hashCode()}
         *       on rows are not invoked by any audit, logging, caching,
         *       or persistence path &mdash; only {@code toString()}
         *       (defensively masked above) reaches CloudWatch /
         *       OpenSearch.</li>
         *   <li>Debugger inspection and heap-dump analysis are governed
         *       by the platform's PCI-DSS access controls (AAP
         *       &sect;0.6.6) and are out of scope for DTO-level
         *       mitigation.</li>
         * </ul>
         *
         * @return a string of the form
         *         {@code CardRow[cardNumber=..., accountId=...,
         *         embossedName=..., expirationDate=..., activeStatus=...]}
         *         with the card number defensively masked
         */
        @Override
        public String toString() {
            // Defensive masking — the producer (CardListService) is
            // expected to pre-mask the PAN per PCI-DSS Requirement 3.4,
            // but maskPan() is applied here as defense in depth so a
            // direct construction path with a raw PAN cannot leak it
            // through toString(). The maskPan() helper is idempotent.
            return "CardRow[cardNumber=" + maskPan(cardNumber)
                    + ", accountId=" + accountId
                    + ", embossedName=" + embossedName
                    + ", expirationDate=" + expirationDate
                    + ", activeStatus=" + activeStatus
                    + "]";
        }
    }
}
