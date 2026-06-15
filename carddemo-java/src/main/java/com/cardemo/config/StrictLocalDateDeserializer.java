package com.cardemo.config;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;

/**
 * Jackson {@link LocalDate} deserializer that parses with <strong>strict</strong> date
 * resolution, rejecting calendar-impossible dates rather than silently normalizing them.
 *
 * <h2>Provenance &mdash; net-new technology-substitution infrastructure (no single COBOL source)</h2>
 * <p>This class has <strong>no single COBOL source equivalent</strong>: it is foundational
 * JSON-binding infrastructure that enforces the date-validity contract of the migrated REST
 * surface. On the mainframe, every date field flowed through the LE callable service
 * {@code CEEDAYS} (wrapped by {@code app/cbl/CSUTLDTC.cbl}), which <em>rejected</em> impossible
 * dates such as February&nbsp;30 or February&nbsp;29 in a non-leap year. The migration maps that
 * validation to {@code java.time.LocalDate} plus custom validators (AAP &sect;0.1.2, transformation
 * rule {@code LE CEEDAYS date validation -> java.time.LocalDate + custom validators}). This
 * deserializer restores that "reject, never normalize" guarantee at the JSON binding boundary so
 * the {@code service/shared/DateValidationService} ({@code CSUTLDTC}/{@code CEEDAYS} translation)
 * and the rest of the layered validation cascade observe the <em>exact bytes the client sent</em>.
 * Traceability to the frozen legacy baseline is by original COBOL repository commit SHA
 * {@code 27d6c6f}; the COBOL source is never copied into this repository (AAP &sect;0.7.2).</p>
 *
 * <h2>Why this class is necessary (the defect it fixes)</h2>
 * <p>The DTO date fields ({@code AccountDto}, {@code CardDto}, {@code TransactionDto}) are annotated
 * {@link com.fasterxml.jackson.annotation.JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} to pin the
 * {@code PIC X(10)} wire contract. For an annotated field, Jackson rebuilds its formatter from that
 * pattern via {@code DateTimeFormatter.ofPattern("yyyy-MM-dd")}, which defaults to
 * {@link ResolverStyle#SMART}. SMART resolution silently <em>clamps</em> an out-of-range day to the
 * last valid day of the month &mdash; so a request carrying {@code "2024-02-30"} was being accepted
 * and persisted as {@code 2024-02-29}, and {@code "2023-02-29"} (non-leap) as {@code 2023-02-28}.
 * Because the corruption happened during binding, the downstream {@code DateValidationService}
 * only ever saw an already-valid {@link LocalDate} and could not reject the original bad input,
 * violating 100% behavioral parity (AAP &sect;0.7.2) with the COBOL {@code CEEDAYS} edit.</p>
 *
 * <h2>How it fixes the defect</h2>
 * <p>The deserializer is seeded with {@link DateTimeFormatter#ISO_LOCAL_DATE}, which uses
 * {@link ResolverStyle#STRICT} and rejects any calendar-impossible date (bad day-of-month, bad
 * month, non-leap February&nbsp;29). Crucially, {@link #withDateFormat(DateTimeFormatter)} is
 * overridden to <strong>ignore</strong> the SMART formatter that Jackson derives from a field's
 * {@code @JsonFormat(pattern = ...)} and keep the strict ISO formatter instead. The wire format is
 * unchanged: {@code "yyyy-MM-dd"} is exactly {@code ISO_LOCAL_DATE}, so every legitimate date still
 * parses and serializes identically; only impossible dates now fail. A failed parse surfaces as a
 * Jackson {@code InvalidFormatException}, which Spring wraps in
 * {@code HttpMessageNotReadableException} &mdash; mapped by
 * {@code WebConfig.GlobalExceptionHandler} to HTTP&nbsp;400 ({@code MALFORMED_REQUEST}).</p>
 *
 * <h2>Scope</h2>
 * <p>Registered once, globally, by {@code WebConfig.jacksonCustomizer()} via
 * {@code Jackson2ObjectMapperBuilder.deserializers(...)}, so it governs <em>every</em>
 * {@link LocalDate} field across all DTOs uniformly (Minimal Change Clause, AAP &sect;0.7.1: one
 * dedicated, isolated module rather than per-field edits). It deliberately does not alter
 * serialization, {@code BigDecimal} handling, or any other binding behavior.</p>
 *
 * @see WebConfig#jacksonCustomizer()
 * @see com.cardemo.service.shared.DateValidationService
 */
public final class StrictLocalDateDeserializer extends LocalDateDeserializer {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a strict {@link LocalDate} deserializer seeded with the
     * {@link DateTimeFormatter#ISO_LOCAL_DATE} formatter (which resolves with
     * {@link ResolverStyle#STRICT} and therefore rejects calendar-impossible dates).
     */
    public StrictLocalDateDeserializer() {
        super(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Forces strict ISO-8601 resolution regardless of any per-field
     * {@link com.fasterxml.jackson.annotation.JsonFormat @JsonFormat(pattern = ...)} override.
     *
     * <p>When a DTO field declares {@code @JsonFormat(pattern = "yyyy-MM-dd")}, Jackson's
     * {@code createContextual(...)} rebuilds a {@link ResolverStyle#SMART} formatter from that
     * pattern and hands it to {@code withDateFormat(...)}. SMART resolution would silently clamp
     * impossible dates (e.g. {@code 2024-02-30 -> 2024-02-29}), defeating the COBOL
     * {@code CEEDAYS} "reject, never normalize" contract. By ignoring the supplied (SMART)
     * formatter and returning {@code this}, the strict {@code ISO_LOCAL_DATE} formatter is
     * preserved. The wire pattern {@code "yyyy-MM-dd"} is byte-identical to {@code ISO_LOCAL_DATE},
     * so valid dates are unaffected; only impossible dates are now rejected.</p>
     *
     * @param formatter the (SMART) formatter Jackson derived from a {@code @JsonFormat} pattern;
     *                  intentionally ignored in favor of strict ISO resolution
     * @return {@code this} &mdash; the strict {@code ISO_LOCAL_DATE} deserializer, unchanged
     */
    @Override
    protected LocalDateDeserializer withDateFormat(DateTimeFormatter formatter) {
        // Intentionally ignore the @JsonFormat-derived SMART formatter; keep strict ISO_LOCAL_DATE
        // so calendar-impossible dates are rejected (COBOL CEEDAYS parity, AAP §0.7.2/§0.1.2).
        return this;
    }
}
