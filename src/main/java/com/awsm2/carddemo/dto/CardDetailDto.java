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
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Card-detail view response DTO.
 *
 * <p>Replaces the 3270 card-detail display screen rendered by the COBOL/CICS
 * program {@code COCRDSLC.cbl} (CICS transaction id {@code CCDL}, mapset
 * {@code COCRDSL}, map {@code CCRDSLA}, defined in {@code app/bms/COCRDSL.bms}
 * and the generated symbolic-map copybook {@code app/cpy-bms/COCRDSL.CPY}).
 * In the source, {@code COCRDSLC.cbl} issues an {@code EXEC CICS READ}
 * (read-only, not {@code READ UPDATE}) against the {@code CARDDAT} VSAM KSDS
 * cluster keyed by the 16-digit card number ({@code CARD-NUM PIC X(16)}),
 * populates the {@code CCRDSLAO} output redefine of the BMS symbolic map, and
 * issues an {@code EXEC CICS SEND MAP} to display the record as read-only.
 * In the Java target, {@code CardController.getCard(String cardNumber)}
 * returns this DTO as the JSON response body of
 * {@code GET /api/cards/{cardNumber}}.
 *
 * <p><b>Read-only view (no optimistic-lock version):</b>  Unlike
 * {@link CardUpdateDto} which carries a {@code version} field for JPA
 * {@code @Version}-based optimistic locking, this DTO is the read-only
 * response of {@code GET /api/cards/{cardNumber}} and therefore omits the
 * version component &mdash; mutations to the card are performed through the
 * separate {@code CardUpdateDto} contract handled by {@code COCRDUPC.cbl}'s
 * Java equivalent ({@code CardUpdateService}).  This mirrors the COBOL
 * source's strict separation between the read-only {@code COCRDSLC.cbl}
 * (view) program and the read-update-rewrite {@code COCRDUPC.cbl} (update)
 * program.
 *
 * <p><b>Field-contract preservation (BMS &harr; JSON contract):</b>  The
 * BMS {@code COCRDSL} map renders the expiration date as <i>segmented</i>
 * sub-fields {@code EXPMON} (PIC X(2)) and {@code EXPYEAR} (PIC X(4)) on
 * positions {@code (15,25)} and {@code (15,30)} of the 3270 screen, joined
 * by a literal {@code "/"} character at position {@code (15,28)}; this DTO
 * collapses the segmented values into a single canonical ISO-8601
 * {@link LocalDate} property ({@link #expirationDate()}, JSON property
 * {@code "expirationDate"}, format {@code yyyy-MM-dd}).  The COBOL source
 * splits the 10-character {@code CARD-EXPIRAION-DATE PIC X(10)} field into
 * {@code CARD-EXPIRY-YEAR}, {@code CARD-EXPIRY-MONTH}, and
 * {@code CARD-EXPIRY-DAY} via REDEFINES (see {@code COCRDSLC.cbl} lines
 * 84&ndash;92).  In the Java target, the wire format is the already-
 * assembled ISO date string, simplifying client consumption while preserving
 * the underlying storage representation in the {@code Card} entity field.
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COCRDSL.bms} (mapset
 *       {@code COCRDSL}, map {@code CCRDSLA})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COCRDSL.CPY} (record
 *       {@code CCRDSLAI} / output redefine {@code CCRDSLAO})</li>
 *   <li>Program: {@code app/cbl/COCRDSLC.cbl} (online CICS pseudo-
 *       conversational card-detail view program; read-only
 *       {@code EXEC CICS READ} against {@code CARDDAT})</li>
 *   <li>Record Layout: {@code app/cpy/CVACT02Y.cpy} (CARD-RECORD, 150
 *       bytes; 91 bytes of business data + 59 bytes of trailing FILLER
 *       which carries no business meaning and is therefore not
 *       represented in this DTO)</li>
 *   <li>Produced by: {@code CardDetailService#findByCardNumber(String)}
 *       (one-service-per-COBOL-program mapping per AAP
 *       &sect;0.3.3 / &sect;0.4.1)</li>
 *   <li>Controller endpoint: {@code GET /api/cards/{cardNumber}} (see
 *       {@code CardController})</li>
 * </ul>
 *
 * <p><b>PCI-DSS / cardholder-data handling (AAP &sect;0.6.6, &sect;
 * 0.7.2):</b>  The {@link #cardNumber()} component carries a full
 * 16-digit Primary Account Number (PAN), which is regulated cardholder
 * data under PCI-DSS.  The default {@link Record#toString()} generated
 * for a record exposes every component verbatim; that would leak the
 * full PAN into application logs if a controller, service, or audit
 * filter inadvertently logs the response body.  To prevent any
 * accidental PAN leakage into CloudWatch Logs and downstream OpenSearch
 * indexes (the AAP mandates a CloudWatch log filter and Macie S3
 * scanning for PAN-like sequences), this record overrides
 * {@link #toString()} to render the PAN as twelve asterisks followed by
 * the last four digits ({@code ************XXXX}).  The
 * {@code CARD-CVV-CD PIC 9(03)} field present on the underlying
 * {@code CARD-RECORD} ({@code app/cpy/CVACT02Y.cpy} line 7) is
 * <i>intentionally</i> not represented on this DTO &mdash; under PCI-DSS
 * the Card Verification Value is Sensitive Authentication Data (SAD)
 * and must never be displayed, stored outside the issuing system, or
 * transmitted to consumers; excluding the field from the wire contract
 * guarantees that the CVV cannot be inadvertently transmitted, logged,
 * or audited.  The auto-generated {@link #equals(Object)} and
 * {@link #hashCode()} implementations from the record contract are
 * retained &mdash; equality compares every component including the
 * unmasked PAN (which is held in memory during request processing);
 * only the textual {@link #toString()} representation is masked.  Logs
 * must use {@code toString()} on the DTO, never {@code .cardNumber()}
 * directly.
 *
 * <p>This DTO contains no business logic, no AWS-SDK references, and no
 * Lombok &mdash; it is a pure response envelope per AAP &sect;0.3.3
 * (Layered Architecture) and the no-Lombok directive in the file's
 * agent prompt.
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
 *                       card whose details are being returned.  Maps to
 *                       {@code CARD-NUM PIC X(16)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 5) and BMS
 *                       field {@code CARDSID PIC X(16)} on the
 *                       {@code COCRDSL} screen at position {@code (8,45)}.
 *                       Masked in {@link #toString()} per AAP
 *                       &sect;0.6.6 PCI-DSS rules.
 * @param accountId      11-digit owning account identifier.  Maps to
 *                       {@code CARD-ACCT-ID PIC 9(11)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 6) and BMS
 *                       field {@code ACCTSID PIC X(11)} on the
 *                       {@code COCRDSL} screen at position {@code (7,45)}.
 *                       Foreign key into the {@code Account} JPA entity.
 * @param embossedName   name as embossed on the physical card.  Maps to
 *                       {@code CARD-EMBOSSED-NAME PIC X(50)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 8) and BMS
 *                       field {@code CRDNAME PIC X(50)} on the
 *                       {@code COCRDSL} screen at position {@code (11,25)}.
 * @param expirationDate card expiration date (ISO 8601 yyyy-MM-dd).
 *                       Maps to {@code CARD-EXPIRAION-DATE PIC X(10)}
 *                       in {@code app/cpy/CVACT02Y.cpy} (line 9; COBOL
 *                       spelling typo preserved at the storage layer)
 *                       and segmented BMS fields {@code EXPMON} (PIC
 *                       X(2), position {@code (15,25)}) and
 *                       {@code EXPYEAR} (PIC X(4), position
 *                       {@code (15,30)}).  Serialized as ISO-8601
 *                       {@code yyyy-MM-dd} via Jackson
 *                       {@link JsonFormat} for the REST response body.
 * @param activeStatus   single-character active flag ('Y' or 'N').  Maps
 *                       to {@code CARD-ACTIVE-STATUS PIC X(01)} in
 *                       {@code app/cpy/CVACT02Y.cpy} (line 10) and BMS
 *                       field {@code CRDSTCD PIC X(01)} on the
 *                       {@code COCRDSL} screen at position {@code (13,25)}.
 *
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 * @see CardUpdateDto
 * @see CardListDto
 */
@Schema(name = "CardDetailDto",
        description = "Card-detail view response payload.  Mirrors the read-only "
                + "output fields of the legacy COCRDSL 3270 screen (BMS map "
                + "CCRDSLA) and the underlying CARD-RECORD (CVACT02Y.cpy, 150 "
                + "bytes) layout, excluding the 59-byte trailing FILLER which "
                + "carries no business meaning.  The PAN is masked in toString() "
                + "per PCI-DSS rules; the CVV is intentionally excluded from "
                + "this DTO because under PCI-DSS the Card Verification Value "
                + "is Sensitive Authentication Data and must never be "
                + "transmitted to consumers.")
public record CardDetailDto(

        /**
         * 16-digit Primary Account Number.  Maps to
         * {@code CARD-NUM PIC X(16)} in {@code app/cpy/CVACT02Y.cpy}
         * (line 5).  Masked in {@link #toString()} per PCI-DSS rules.
         */
        @Pattern(regexp = "^\\d{16}$",
                message = "Card number must be exactly 16 digits")
        @Schema(description = "16-digit card number (Primary Account Number).  "
                        + "Maps to CARD-NUM PIC X(16) in CVACT02Y.cpy and BMS "
                        + "field CARDSID PIC X(16) on the COCRDSL screen.  "
                        + "Masked in toString() per PCI-DSS rules.",
                example = "4111111111111111",
                maxLength = 16)
        @JsonProperty("cardNumber")
        String cardNumber,

        /**
         * 11-digit owning account identifier.  Maps to
         * {@code CARD-ACCT-ID PIC 9(11)} in {@code app/cpy/CVACT02Y.cpy}
         * (line 6).
         */
        @Schema(description = "11-digit owning account identifier.  Maps to "
                        + "CARD-ACCT-ID PIC 9(11) in CVACT02Y.cpy and BMS "
                        + "field ACCTSID PIC X(11) on the COCRDSL screen.  "
                        + "Foreign key into the Account JPA entity.",
                example = "10000000001",
                maxLength = 11)
        @JsonProperty("accountId")
        Long accountId,

        /**
         * Name as embossed on the physical card.  Maps to
         * {@code CARD-EMBOSSED-NAME PIC X(50)} in
         * {@code app/cpy/CVACT02Y.cpy} (line 8).
         */
        @Schema(description = "Name as embossed on the physical card.  Maps "
                        + "to CARD-EMBOSSED-NAME PIC X(50) in CVACT02Y.cpy "
                        + "and BMS field CRDNAME PIC X(50) on the COCRDSL "
                        + "screen.",
                example = "JOHN DOE",
                maxLength = 50)
        @JsonProperty("embossedName")
        String embossedName,

        /**
         * Card expiration date (ISO 8601 yyyy-MM-dd).  Maps to
         * {@code CARD-EXPIRAION-DATE PIC X(10)} in
         * {@code app/cpy/CVACT02Y.cpy} (line 9; COBOL spelling typo
         * "EXPIRAION" preserved at the storage layer).  Stored as a
         * 10-character ISO date string in source; mapped to
         * {@link LocalDate} here.  Serialized as ISO-8601
         * {@code yyyy-MM-dd} via Jackson {@link JsonFormat} on the JSON
         * response.  Replaces LE {@code CEEDAYS}-based COBOL date
         * validation per AAP &sect;0.5.2.
         */
        @Schema(description = "Card expiration date (ISO 8601 yyyy-MM-dd).  "
                        + "Maps to CARD-EXPIRAION-DATE PIC X(10) in "
                        + "CVACT02Y.cpy (COBOL spelling typo preserved at "
                        + "the storage layer) and segmented BMS fields "
                        + "EXPMON/EXPYEAR on the COCRDSL screen.",
                example = "2030-12-31",
                format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("expirationDate")
        LocalDate expirationDate,

        /**
         * Card active status: 'Y' active, 'N' inactive.  Maps to
         * {@code CARD-ACTIVE-STATUS PIC X(01)} in
         * {@code app/cpy/CVACT02Y.cpy} (line 10).
         */
        @Pattern(regexp = "^[YN]$",
                message = "Active status must be 'Y' or 'N'")
        @Schema(description = "Card active status flag: 'Y' active, 'N' "
                        + "inactive.  Maps to CARD-ACTIVE-STATUS PIC X(01) "
                        + "in CVACT02Y.cpy and BMS field CRDSTCD PIC X(01) "
                        + "on the COCRDSL screen.",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N"})
        @JsonProperty("activeStatus")
        String activeStatus
) {

    /**
     * Returns a PCI-DSS-safe string representation of this response.
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
     * {@code embossedName}, {@code expirationDate}, and
     * {@code activeStatus} fields do not carry sensitive cardholder data
     * under PCI-DSS (the embossed name is the cardholder's display name
     * and is not classified as Sensitive Authentication Data); they are
     * safe to emit unmasked.  The CVV is not on this DTO at all (see
     * class Javadoc), so no CVV masking is required.
     *
     * <p>Equality and hash semantics are unaffected; the auto-generated
     * {@link Object#equals(Object)} and {@link Object#hashCode()}
     * implementations from the record contract continue to use every
     * component including the unmasked PAN (which is held in memory
     * during request processing); only the textual {@link #toString()}
     * is masked.  This mirrors the COBOL behavior where the unmasked
     * {@code CARD-NUM} is held in working storage during request
     * processing but never written to a permanent audit log.
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
        return "CardDetailDto[cardNumber=" + maskedPan
                + ", accountId=" + accountId
                + ", embossedName=" + embossedName
                + ", expirationDate=" + expirationDate
                + ", activeStatus=" + activeStatus
                + "]";
    }
}
