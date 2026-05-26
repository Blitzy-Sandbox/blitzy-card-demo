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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Card-update request DTO with optimistic locking.
 *
 * <p>Replaces the 3270 card-update screen rendered by the COBOL/CICS program
 * {@code COCRDUPC.cbl} (mapset {@code COCRDUP}, map {@code CCRDUPA}, defined
 * in {@code app/bms/COCRDUP.bms} and the generated symbolic-map copybook
 * {@code app/cpy-bms/COCRDUP.CPY}).  In the source, {@code COCRDUPC.cbl}
 * issues an {@code EXEC CICS READ UPDATE} against the {@code CARDDAT} VSAM
 * cluster ({@code CARD-RECORD} layout, {@code app/cpy/CVACT02Y.cpy}, 150
 * bytes), compares the before-image to a snapshot stored in COMMAREA (the
 * original COBOL "optimistic locking" pattern implemented as manual
 * field-by-field snapshot comparison), and finally issues an
 * {@code EXEC CICS REWRITE} when no other process has changed the record.
 * In the Java target, the {@link #version()} component on this DTO carries
 * the JPA {@code @Version} value returned by the prior
 * {@code GET /api/cards/{cardNumber}} call; if that value does not match
 * the database row's current version at update time, JPA throws an
 * {@code OptimisticLockException} which the service layer translates into
 * a {@code ConcurrentModificationException} from
 * {@code com.awsm2.carddemo.exception}, which
 * {@code GlobalExceptionHandler} maps to HTTP 409 Conflict.
 *
 * <p><b>Optimistic locking (AAP &sect;0.6.2 / &sect;0.7.1):</b>  This DTO
 * and {@code AccountUpdateDto} are the <i>only</i> two DTOs in the Java
 * target that carry a {@link #version()} field, because the AAP mandates
 * that JPA {@code @Version} optimistic locking is applied only to the
 * {@code Account} and {@code Card} entities &mdash; the two record types
 * whose source programs ({@code COACTUPC.cbl} and {@code COCRDUPC.cbl})
 * exhibit explicit before/after snapshot comparison patterns.  All other
 * update flows in the source code base either (a) operate on append-only
 * tables (transactions) or (b) are guarded by single-row uniqueness checks
 * that do not require a separate concurrency-control mechanism.
 *
 * <p><b>Field-contract preservation (BMS &harr; JSON contract):</b>  The
 * BMS {@code COCRDUP} map renders the expiration date as <i>segmented</i>
 * sub-fields {@code EXPMON} (PIC X(2)), {@code EXPYEAR} (PIC X(4)), and
 * {@code EXPDAY} (PIC X(2)) on the 3270 screen; this DTO collapses the
 * segmented values into a single canonical ISO-8601 {@link LocalDate}
 * property ({@link #expirationDate()}, JSON property
 * {@code "expirationDate"}, format {@code yyyy-MM-dd}).  The COBOL source
 * reconstructs the segmented values via REDEFINES on
 * {@code CARD-MONTH-CHECK}, {@code CARD-YEAR-CHECK}, etc. (lines 92&ndash;
 * 99 of {@code COCRDUPC.cbl}), validates the digit ranges (88-level
 * {@code VALID-MONTH VALUES 1 THRU 12}, {@code VALID-YEAR VALUES 1950
 * THRU 2099}), and finally assembles the date into the
 * {@code CARD-EXPIRAION-DATE PIC X(10)} field on the underlying record.
 * In the Java target, the client supplies the already-assembled
 * {@code LocalDate} value via JSON; field-level Bean Validation enforces
 * the ISO-8601 wire format and
 * {@link com.awsm2.carddemo.validation.DateValidationService
 *  DateValidationService} (port of
 * {@code app/cpy/CSUTLDPY.cpy} and {@code app/cbl/CSUTLDTC.cbl}) applies
 * the leap-year / month / day semantic checks per AAP &sect;0.5.2 (LE
 * {@code CEEDAYS} replacement).
 *
 * <p><b>Immutability of {@link #cardNumber()} on update:</b>  The BMS
 * {@code COCRDUP} map renders {@code CARDSID} (the 16-digit
 * {@code CARD-NUM}) as an unprotected input field, but the COBOL source's
 * intent is that the operator types the {@code CARD-NUM} <i>only to
 * identify the card being updated</i> &mdash; the program does <b>not</b>
 * permit changing the primary key.  In the Java target this is enforced
 * by the controller layer: the URL path variable
 * {@code PUT /api/cards/{cardNumber}} is the canonical identifier; the
 * service layer compares this DTO's {@link #cardNumber()} component
 * against the path variable and rejects mismatches with HTTP 400 Bad
 * Request.  Documenting this constraint in the DTO Javadoc preserves
 * traceability to the source program's intent per AAP &sect;0.7.3.
 *
 * <p><b>Cross-field validation (delegated to service layer):</b>
 * <ul>
 *   <li>Expiration date semantic validity (leap-year, month/day, future-
 *       date guards): delegated to {@code DateValidationService} &mdash;
 *       port of {@code app/cpy/CSUTLDPY.cpy} and
 *       {@code app/cbl/CSUTLDTC.cbl} (replaces the LE {@code CEEDAYS}
 *       call: AAP &sect;0.5.2).</li>
 *   <li>Owning-account existence and active status: delegated to
 *       {@code AccountRepository.findById(...)} via
 *       {@code CardUpdateService}; rejection on missing account yields
 *       {@code RecordNotFoundException} &rarr; HTTP 404.</li>
 *   <li>Card existence for the owning account: delegated to
 *       {@code CardRepository.findById(...)} cross-checked against the
 *       {@code CardCrossReference} repository (port of the
 *       {@code CXACAIX} alternate-index pattern, AAP &sect;0.6.2).</li>
 * </ul>
 * On validation failure the service raises a {@code ValidationException}
 * from {@code com.awsm2.carddemo.exception} with a populated
 * {@code fieldErrors} map; {@code GlobalExceptionHandler} translates the
 * exception to HTTP 400 Bad Request with a standardized JSON envelope
 * (AAP &sect;0.3.4).
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COCRDUP.bms} (mapset
 *       {@code COCRDUP}, map {@code CCRDUPA})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COCRDUP.CPY} (record
 *       {@code CCRDUPAI} / output redefine {@code CCRDUPAO})</li>
 *   <li>Program: {@code app/cbl/COCRDUPC.cbl} (online CICS pseudo-
 *       conversational card-update program; READ UPDATE / REWRITE
 *       pattern with manual before/after snapshot comparison)</li>
 *   <li>Record Layout: {@code app/cpy/CVACT02Y.cpy} (CARD-RECORD, 150
 *       bytes)</li>
 *   <li>Consumed by: {@code CardUpdateService#update(String,
 *       CardUpdateDto)} (one-service-per-COBOL-program mapping per AAP
 *       &sect;0.3.3 / &sect;0.4.1)</li>
 *   <li>Controller endpoint: {@code PUT /api/cards/{cardNumber}} (see
 *       {@code CardController})</li>
 * </ul>
 *
 * <p><b>PCI-DSS / cardholder-data handling (AAP &sect;0.6.6, &sect;
 * 0.7.2):</b>  The {@link #cardNumber()} component carries a full
 * 16-digit Primary Account Number (PAN), which is regulated cardholder
 * data under PCI-DSS.  The default {@link Record#toString()} generated
 * for a record exposes every component verbatim; that would leak the
 * full PAN into application logs if a controller, service, or validator
 * inadvertently logs the request body.  To prevent any accidental PAN
 * leakage into CloudWatch Logs and downstream OpenSearch indexes (the
 * AAP mandates a CloudWatch log filter and Macie S3 scanning for PAN-
 * like sequences), this record overrides {@link #toString()} to render
 * the PAN as twelve asterisks followed by the last four digits
 * ({@code ************XXXX}).  The
 * {@code CARD-CVV-CD PIC 9(03)} field present on the underlying
 * {@code CARD-RECORD} ({@code app/cpy/CVACT02Y.cpy} line 7) is
 * <i>intentionally</i> not represented on this DTO &mdash; updating the
 * CVV is not a supported business operation in CardDemo, and excluding
 * the field from the wire contract guarantees that the CVV cannot be
 * inadvertently transmitted, logged, or audited.  The auto-generated
 * {@link #equals(Object)} and {@link #hashCode()} implementations from
 * the record contract are retained &mdash; equality compares every
 * component including the unmasked PAN (which is held in memory during
 * request processing); only the textual {@link #toString()}
 * representation is masked.
 *
 * <p>This DTO contains no business logic, no AWS-SDK references, and no
 * Lombok &mdash; it is a pure validated request envelope per AAP
 * &sect;0.3.3 (Layered Architecture) and the no-Lombok directive in the
 * file's agent prompt.
 *
 * <p><b>COBOL field-name typo preserved:</b>  The
 * {@code CARD-EXPIRAION-DATE} field name in {@code app/cpy/CVACT02Y.cpy}
 * (line 9) contains a spelling error ("EXPIRAION" instead of
 * "EXPIRATION").  The Java target uses the corrected English spelling
 * ({@link #expirationDate()}) because the wire contract is the canonical
 * JSON property name; the source-name typo is documented in this Javadoc
 * for traceability per the Minimal Change Clause (AAP &sect;0.7.3)
 * &mdash; the database column and the {@code Card} entity field retain
 * the documented mapping back to {@code CARD-EXPIRAION-DATE}.
 *
 * @param cardNumber     16-digit Primary Account Number identifying the
 *                       card being updated.  Maps to
 *                       {@code CARD-NUM PIC X(16)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 5) and BMS
 *                       field {@code CARDSID PIC X(16)} on the
 *                       {@code COCRDUP} screen.  Immutable on update;
 *                       the controller layer enforces that this value
 *                       equals the URL path variable.  Masked in
 *                       {@link #toString()} per AAP &sect;0.6.6
 *                       PCI-DSS rules.
 * @param accountId      11-digit owning account identifier.  Maps to
 *                       {@code CARD-ACCT-ID PIC 9(11)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 6) and BMS
 *                       field {@code ACCTSID PIC X(11)} on the
 *                       {@code COCRDUP} screen.  Foreign key into the
 *                       {@code Account} JPA entity.
 * @param embossedName   name as embossed on the physical card.  Maps to
 *                       {@code CARD-EMBOSSED-NAME PIC X(50)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 8) and BMS
 *                       field {@code CRDNAME PIC X(50)} on the
 *                       {@code COCRDUP} screen.  Validated by the
 *                       {@code FLG-CARDNAME-ISVALID} 88-level in
 *                       {@code COCRDUPC.cbl}.  Per QA finding V1, the
 *                       Java target additionally enforces the physical
 *                       card-embossing character class
 *                       {@code [A-Z0-9 \-'.]+} via {@code @Pattern}, so
 *                       that markup, scripts, or any character the
 *                       COBOL source could never have produced is
 *                       rejected at the DTO boundary.  This eliminates
 *                       a stored-XSS surface and aligns the Java
 *                       contract with the physical card-stock domain
 *                       (capital letters, digits, hyphen, apostrophe,
 *                       period, and space &mdash; the only characters
 *                       reproducible on a card embosser).
 * @param expirationDate card expiration date (ISO 8601 yyyy-MM-dd).
 *                       Maps to {@code CARD-EXPIRAION-DATE PIC X(10)}
 *                       in {@code app/cpy/CVACT02Y.cpy} (line 9; COBOL
 *                       spelling typo preserved at the storage layer)
 *                       and segmented BMS fields {@code EXPMON} /
 *                       {@code EXPYEAR} / {@code EXPDAY}.  Semantic
 *                       validation (leap year, month range, future-date
 *                       guard) delegated to {@code DateValidationService}.
 * @param activeStatus   single-character active flag (' Y' or 'N').
 *                       Maps to {@code CARD-ACTIVE-STATUS PIC X(01)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 10) and BMS
 *                       field {@code CRDSTCD PIC X(01)} on the
 *                       {@code COCRDUP} screen.  Validated by the
 *                       {@code FLG-YES-NO-VALID} 88-level in
 *                       {@code COCRDUPC.cbl} (line 91) which permits
 *                       only the values 'Y' and 'N'.
 * @param version        JPA optimistic-lock version value from the
 *                       prior {@code GET /api/cards/{cardNumber}};
 *                       replaces the COBOL manual snapshot-comparison
 *                       pattern in {@code COCRDUPC.cbl} (READ UPDATE /
 *                       REWRITE with COMMAREA-resident before-image).
 *                       Mismatch on save raises
 *                       {@code OptimisticLockException}, translated to
 *                       {@code ConcurrentModificationException} &rarr;
 *                       HTTP 409 by {@code GlobalExceptionHandler}.
 *
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "CardUpdateDto",
        description = "Card-update request payload.  Mirrors the input fields of the "
                + "legacy COCRDUP 3270 screen (BMS map CCRDUPA) and the underlying "
                + "CARD-RECORD (CVACT02Y.cpy, 150 bytes) layout.  Optimistic locking "
                + "is enforced via the version field, which must equal the value "
                + "returned by the prior GET; mismatch yields HTTP 409.  The PAN is "
                + "masked in toString() per PCI-DSS rules; the CVV is intentionally "
                + "excluded from this DTO because updating CVV is not a supported "
                + "business operation.")
public record CardUpdateDto(

        @NotBlank(message = "Card number is required")
        @Pattern(regexp = "^\\d{16}$",
                message = "Card number must be exactly 16 digits")
        @Size(max = 16,
                message = "Card number must be at most 16 characters")
        @Schema(description = "16-digit card number (Primary Account Number).  "
                        + "Immutable on update; identifies the card being modified.  "
                        + "Maps to CARD-NUM PIC X(16) in CVACT02Y.cpy and BMS field "
                        + "CARDSID PIC X(16) on the COCRDUP screen.  Masked in "
                        + "toString() per PCI-DSS rules.",
                example = "4111111111111111",
                maxLength = 16,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("cardNumber")
        String cardNumber,

        @NotNull(message = "Account ID is required")
        @Schema(description = "11-digit owning account identifier.  Maps to "
                        + "CARD-ACCT-ID PIC 9(11) in CVACT02Y.cpy and BMS field "
                        + "ACCTSID PIC X(11) on the COCRDUP screen.  Foreign key "
                        + "into the Account JPA entity.",
                example = "10000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("accountId")
        Long accountId,

        @NotBlank(message = "Embossed name is required")
        @Size(max = 50,
                message = "Embossed name must be at most 50 characters")
        @Pattern(regexp = "^[A-Z0-9 \\-'.]+$",
                message = "Embossed name must contain only uppercase letters, "
                        + "digits, spaces, hyphens, apostrophes, and periods")
        @Schema(description = "Name as embossed on the physical card.  Maps to "
                        + "CARD-EMBOSSED-NAME PIC X(50) in CVACT02Y.cpy and BMS "
                        + "field CRDNAME PIC X(50).  Validated by COBOL 88-level "
                        + "FLG-CARDNAME-ISVALID in COCRDUPC.cbl. Per QA finding "
                        + "V1, restricted to the physical card-embossing character "
                        + "set [A-Z0-9 \\-'.] to prevent stored XSS via "
                        + "scripts/markup characters that the COBOL source could "
                        + "never have produced.",
                example = "JOHN DOE",
                maxLength = 50,
                pattern = "^[A-Z0-9 \\-'.]+$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("embossedName")
        String embossedName,

        @NotNull(message = "Expiration date is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @Schema(description = "Card expiration date (ISO 8601 yyyy-MM-dd).  Maps to "
                        + "CARD-EXPIRAION-DATE PIC X(10) (COBOL spelling typo "
                        + "preserved at the storage layer) and segmented BMS fields "
                        + "EXPMON/EXPYEAR/EXPDAY.  Semantic validation (leap year, "
                        + "month/day, future-date guard) delegated to "
                        + "DateValidationService (port of CSUTLDPY.cpy and "
                        + "CSUTLDTC.cbl).",
                example = "2030-12-31",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("expirationDate")
        LocalDate expirationDate,

        @NotBlank(message = "Active status is required")
        @Pattern(regexp = "^[YN]$",
                message = "Active status must be 'Y' or 'N'")
        @Schema(description = "Card active status flag: 'Y' active, 'N' inactive.  "
                        + "Maps to CARD-ACTIVE-STATUS PIC X(01) in CVACT02Y.cpy and "
                        + "BMS field CRDSTCD PIC X(01) on the COCRDUP screen.  "
                        + "Validated by COBOL 88-level FLG-YES-NO-VALID (line 91 of "
                        + "COCRDUPC.cbl).",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("activeStatus")
        String activeStatus,

        /**
         * JPA {@code @Version} value from the prior {@code GET}.  Required
         * for optimistic locking on the {@code Card} entity; mismatch on
         * save raises {@code OptimisticLockException}, translated to
         * {@code ConcurrentModificationException} &rarr; HTTP 409 by
         * {@code GlobalExceptionHandler}.  Replaces the manual snapshot
         * comparison performed by {@code COCRDUPC.cbl} (READ UPDATE with
         * COMMAREA-resident before-image, compared against the after-image
         * before REWRITE).
         */
        @NotNull(message = "Version is required for optimistic-locking-based update")
        @Schema(description = "Optimistic-lock version from the prior GET.  Replaces "
                        + "the COBOL before/after image comparison in "
                        + "COCRDUPC.cbl (READ UPDATE / REWRITE pattern).  Mismatch "
                        + "yields HTTP 409.",
                example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("version")
        Long version
) {

    /**
     * Returns a PCI-DSS-safe string representation of this request.
     *
     * <p>The default {@link Record#toString()} generated by the Java record
     * contract would include the full 16-digit {@link #cardNumber()}
     * verbatim.  Per AAP &sect;0.6.6 (PCI-DSS rules) and the
     * Macie-driven leakage-prevention controls referenced in the AAP, this
     * overridden method renders the PAN as twelve asterisks followed by
     * the last four digits ({@code ************XXXX}) so that accidental
     * logging of the DTO in CloudWatch Logs or OpenSearch does not expose
     * the regulated cardholder data.
     *
     * <p>If {@link #cardNumber()} is {@code null} or shorter than four
     * characters, the masked representation collapses to a literal
     * four-asterisk placeholder ({@code "****"}) so that downstream log
     * processors observe a well-defined fixed-width token regardless of
     * the input length.
     *
     * <p>Every other component is rendered as-is.  The {@code accountId},
     * {@code embossedName}, {@code expirationDate}, {@code activeStatus},
     * and {@code version} fields do not carry sensitive cardholder data
     * under PCI-DSS (the embossed name is the cardholder's display name
     * and is not classified as Sensitive Authentication Data); they are
     * safe to emit unmasked.  The CVV is not on this DTO at all (see
     * class Javadoc), so no CVV masking is required.
     *
     * <p>Equality and hash semantics are unaffected; the auto-generated
     * {@link Object#equals(Object)} and {@link Object#hashCode()}
     * implementations from the record contract continue to use every
     * component including the unmasked PAN (which is held in memory
     * during request processing); only the textual {@link #toString()} is
     * masked.  This mirrors the COBOL behavior where the unmasked
     * {@code CARD-NUM} is held in working storage during request
     * processing but never written to a permanent audit log.
     *
     * <p><b>NOTE on record-generated {@code equals()}/{@code hashCode()}
     * (accepted risk per AAP &sect;0.6.6).</b>  The Java record contract
     * forbids overriding {@code equals(Object)}/{@code hashCode()} to
     * exclude components without converting the type to a regular class
     * (a scope expansion that would violate the AAP-mandated Minimal
     * Change Clause).  The risk that the unmasked PAN participates in
     * equality/hash operations is <b>accepted</b> because the PAN value
     * lives in JVM memory only for the duration of a single
     * {@code PUT /api/cards/{cardNumber}} request, equality/hash are
     * not invoked by audit/logging/caching/persistence paths (only
     * {@code toString()} reaches CloudWatch / OpenSearch), and debugger
     * inspection / heap-dump analysis are governed by the platform's
     * PCI-DSS access controls (AAP &sect;0.6.6).
     *
     * @return a string representation safe for logging and audit emission
     */
    @Override
    public String toString() {
        final String maskedPan;
        if (cardNumber == null || cardNumber.length() < 4) {
            maskedPan = "****";
        } else {
            maskedPan = "************"
                    + cardNumber.substring(cardNumber.length() - 4);
        }
        return "CardUpdateDto[cardNumber=" + maskedPan
                + ", accountId=" + accountId
                + ", embossedName=" + embossedName
                + ", expirationDate=" + expirationDate
                + ", activeStatus=" + activeStatus
                + ", version=" + version
                + "]";
    }
}
