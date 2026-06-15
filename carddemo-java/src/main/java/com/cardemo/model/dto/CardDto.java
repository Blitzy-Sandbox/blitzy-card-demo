package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * REST request/response payload for the CardDemo <strong>card</strong> screens:
 * <strong>Card List</strong> ({@code CCLI}), <strong>Card Detail / View</strong>
 * ({@code CCDL}) and <strong>Card Update</strong> ({@code CCUP}).
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * three 3270 card maps the legacy system used. On the mainframe those screens were
 * BMS maps {@code COCRDLI} (list), {@code COCRDSL} (detail/view) and {@code COCRDUP}
 * (update); their symbolic maps are defined by the copybooks
 * {@code app/cpy-bms/COCRDLI.CPY} ({@code 01 CCRDLIAI}),
 * {@code app/cpy-bms/COCRDSL.CPY} ({@code 01 CCRDSLAI}) and
 * {@code app/cpy-bms/COCRDUP.CPY} ({@code 01 CCRDUPAI}). The online programs
 * {@code app/cbl/COCRDLIC.cbl} (paginated browse, seven rows per page),
 * {@code app/cbl/COCRDSLC.cbl} (single keyed read) and
 * {@code app/cbl/COCRDUPC.cbl} (keyed update with optimistic concurrency) populated
 * those symbolic structures from the {@code CARDDAT} VSAM file. This DTO consolidates
 * all three screens into a single canonical representation.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/CardController} to
 * the {@code GET}/{@code PUT} routes under {@code /api/cards/*}. Jackson
 * serializes/deserializes the JSON body, Jakarta Bean Validation enforces the
 * structural field constraints declared below (the {@code @Valid} gate on the
 * controller method), and the validated payload is handed to
 * {@code service/card/CardListService} (browse, from {@code COCRDLIC}),
 * {@code service/card/CardDetailService} (view, from {@code COCRDSLC}) or
 * {@code service/card/CardUpdateService} (update, from {@code COCRDUPC}). This DTO is
 * therefore a pure boundary type &mdash; it carries no business logic, no I/O and no
 * static state.</p>
 *
 * <h2>Key insight &mdash; three screens, one card concept</h2>
 * <p>The three card screens describe the <em>same</em> card record at two shapes:</p>
 * <ul>
 *   <li>The <strong>detail/update</strong> shape ({@code COCRDSL}/{@code COCRDUP}) is
 *       the single-record view: one account id, one card number, the embossed name,
 *       the active-status code, and the expiry components. The update map additionally
 *       splits out the expiry <em>day</em> ({@code EXPDAY}); the view map exposes only
 *       month and year. The single-card properties on this class model that shape.</li>
 *   <li>The <strong>list</strong> shape ({@code COCRDLI}) is the paginated browse:
 *       <strong>exactly seven rows per page</strong> (see {@link #ROWS_PER_PAGE}), each
 *       row carrying a selection flag, an account number, a card number and a status.
 *       The nested {@link CardListItem} type models one such row, and {@link #cards}
 *       holds the (at most seven) rows of the current page identified by
 *       {@link #pageNumber}. On the list screen the {@link #accountId} and
 *       {@link #cardNumber} single-card properties double as the browse
 *       <em>filter</em> inputs (the {@code ACCTSID}/{@code CARDSID} key fields the
 *       operator typed to position the browse).</li>
 * </ul>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Card number kept as a fixed-width {@link String} (AAP &sect;0.4.2).</strong>
 *       {@code CARDSID}/{@code CRDNUM} are {@code PIC X(16)} &mdash; a sixteen-digit
 *       card number. It is modeled as a {@link String}, <strong>never</strong> a
 *       numeric type, so the fixed sixteen-digit width and any leading zeros are
 *       preserved exactly. {@link Pattern @Pattern} enforces a digit-only shape and
 *       {@link Size @Size(max = 16)} preserves the width.</li>
 *   <li><strong>Byte-faithful field lengths (AAP &sect;0.7.1 / &sect;0.7.2).</strong>
 *       Every textual field carries a {@link Size @Size(max = n)} equal to its COBOL
 *       {@code PIC} length, preserving the external-interface width exactly.</li>
 *   <li><strong>Expiry kept as raw components plus an optional derived date.</strong>
 *       {@code EXPMON}/{@code EXPYEAR}/{@code EXPDAY} are preserved as the raw
 *       {@link String} components the screen carried (byte-faithful). A derived
 *       {@link LocalDate} {@link #expirationDate} is additionally exposed for
 *       convenience; it is composed from the components by the service layer
 *       ({@code service/shared/DateValidationService}, the translation of
 *       {@code CSUTLDTC}/{@code CEEDAYS}), which also enforces date <em>validity</em>
 *       &mdash; this DTO does not.</li>
 *   <li><strong>Pagination preserved.</strong> {@code COCRDLIC} browses the card file
 *       seven rows at a time; that page size is encoded as {@link #ROWS_PER_PAGE} and
 *       the page index as {@link #pageNumber} ({@code PAGENO PIC X(3)}). The page
 *       arithmetic itself lives in {@code CardListService}.</li>
 *   <li><strong>No floating point (AAP &sect;0.7.3).</strong> The card screens carry no
 *       monetary fields, so there is no {@code float}, {@code double} (nor
 *       {@code BigDecimal}) anywhere in this DTO.</li>
 * </ul>
 *
 * <h2>Deliberately excluded &mdash; 3270 chrome, control bytes and AID keys (AAP &sect;0.4.2)</h2>
 * <p>The following symbolic-map members are <strong>not</strong> modeled because they
 * are presentation/control artifacts with no REST equivalent:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}/
 *       {@code CURTIMEI} ({@code X(8)}) and {@code PGMNAMEI} ({@code X(8)}).</li>
 *   <li><strong>Message lines (output-only):</strong> {@code INFOMSGI} and
 *       {@code ERRMSGI} &mdash; in REST these become the response/error body, not
 *       request fields.</li>
 *   <li><strong>Function-key labels:</strong> {@code FKEYSI} (detail {@code X(75)},
 *       update {@code X(21)}) and {@code FKEYSCI} (update {@code X(18)}) &mdash; PF
 *       keys map onto distinct REST endpoints / controller routing, never a body
 *       field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (flag/attribute) input bytes and the redefined
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V} output bytes &mdash; 3270
 *       datastream metadata with no REST analogue. This includes the list screen's
 *       per-row protect byte {@code CRDSTP{n}} ({@code X(1)}, rows 2&ndash;7), which is
 *       a field attribute, not card data.</li>
 * </ul>
 *
 * <p>Consistent with the folder rule, this DTO is a separate API representation and
 * deliberately does <strong>not</strong> import any {@code com.cardemo.model.entity}
 * type; mapping between this DTO and the {@code Card} entity is the service layer's
 * responsibility.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.Pattern
 * @see java.time.LocalDate
 */
public class CardDto {

    /**
     * The number of card rows the legacy browse painted on a single
     * {@code COCRDLI} page (seven), preserved as an explicit contract constant.
     *
     * <p>The online program {@code COCRDLIC} read the {@code CARDDAT} file seven
     * records at a time, mapping them to the symbolic rows {@code CRDSEL1}&hellip;
     * {@code CRDSEL7}. {@link #cards} therefore holds at most this many items per page.
     * The actual page arithmetic (positioning, next/previous) is performed by
     * {@code service/card/CardListService}; this constant simply documents the
     * page-size contract so it is not silently lost in translation.</p>
     */
    public static final int ROWS_PER_PAGE = 7;

    // ---------------------------------------------------------------------
    // Single-card fields -- detail/view (COCRDSL) and update (COCRDUP)
    // On the list screen (COCRDLI) accountId and cardNumber double as the
    // ACCTSID / CARDSID browse filter inputs.
    // ---------------------------------------------------------------------

    /**
     * The account identifier that owns the card.
     *
     * <p>Migrated from {@code ACCTSID PIC X(11)}: an eleven-character account number.
     * Kept as a fixed-width {@link String} so leading zeros and the exact width are
     * preserved; {@link Pattern @Pattern} enforces a digit-only shape and
     * {@link Size @Size} preserves the eleven-character width. On the list screen this
     * same property carries the {@code ACCTSID} account filter the operator typed.</p>
     */
    // ACCTSID PIC X(11) -> 11-digit fixed-width account number -> String(11)
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @Pattern(regexp = "\\d{1,11}", message = "Account ID must be 1 to 11 digits")
    private String accountId;

    /**
     * The sixteen-digit card number (primary account number).
     *
     * <p>Migrated from {@code CARDSID PIC X(16)}: a sixteen-character card number.
     * Kept as a fixed-width {@link String} &mdash; <strong>never</strong> a numeric
     * type &mdash; so the sixteen-digit identity and any leading zeros are preserved
     * exactly. {@link Pattern @Pattern} enforces a digit-only shape (zero to sixteen
     * digits, allowing an unset/blank value) and {@link Size @Size} preserves the
     * width. On the list screen this same property carries the {@code CARDSID} card
     * filter the operator typed.</p>
     */
    // CARDSID PIC X(16) -> 16-digit fixed-width card number (PAN) -> String(16), NEVER numeric
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    @Pattern(regexp = "\\d{0,16}", message = "Card number must be 0 to 16 digits")
    private String cardNumber;

    /**
     * The cardholder name embossed on the card.
     *
     * <p>Migrated from {@code CRDNAME PIC X(50)}: a fifty-character name field,
     * modeled as a {@link String} with the width preserved by {@link Size @Size}.
     * Serialized as {@code embossedName} to match the {@code CARD-EMBOSSED-NAME}
     * COBOL field, the {@code card.embossed_name} V1 column, and the documented API
     * contract.</p>
     */
    // CRDNAME PIC X(50) / CARD-EMBOSSED-NAME -> 50-char embossed cardholder name -> String(50)
    @Size(max = 50, message = "Embossed name must not exceed 50 characters")
    private String embossedName;

    /**
     * The card active-status code.
     *
     * <p>Migrated from {@code CRDSTCD PIC X(1)}: a single-character active flag
     * (typically {@code 'Y'} active or {@code 'N'} inactive), modeled as a
     * {@link String} of length one. Serialized as {@code activeStatus} to match the
     * {@code CARD-ACTIVE-STATUS} COBOL field, the {@code card.active_status} V1
     * column, and the documented API contract.</p>
     */
    // CRDSTCD PIC X(1) / CARD-ACTIVE-STATUS -> single-character active flag (Y/N) -> String(1)
    @Size(max = 1, message = "Active status must be a single character")
    private String activeStatus;

    /**
     * The card expiry month component.
     *
     * <p>Migrated from {@code EXPMON PIC X(2)}: the two-character month portion of the
     * expiry date. Kept as a raw {@link String} component to stay byte-faithful to the
     * screen contract; calendar validity is enforced downstream (see
     * {@link #expirationDate}).</p>
     */
    // EXPMON PIC X(2) -> 2-char expiry month component -> String(2)
    @Size(max = 2, message = "Expiry month must not exceed 2 characters")
    @Pattern(regexp = "\\d{0,2}", message = "Expiry month must be 0 to 2 digits")
    private String expiryMonth;

    /**
     * The card expiry year component.
     *
     * <p>Migrated from {@code EXPYEAR PIC X(4)}: the four-character year portion of the
     * expiry date. Kept as a raw {@link String} component to stay byte-faithful to the
     * screen contract.</p>
     */
    // EXPYEAR PIC X(4) -> 4-char expiry year component -> String(4)
    @Size(max = 4, message = "Expiry year must not exceed 4 characters")
    @Pattern(regexp = "\\d{0,4}", message = "Expiry year must be 0 to 4 digits")
    private String expiryYear;

    /**
     * The card expiry day component.
     *
     * <p>Migrated from {@code EXPDAY PIC X(2)} &mdash; present on the
     * <strong>update</strong> map ({@code COCRDUP}) only; the view map
     * ({@code COCRDSL}) exposes month and year alone. Kept as a raw {@link String}
     * component to stay byte-faithful to the screen contract.</p>
     */
    // EXPDAY PIC X(2) (COCRDUP only) -> 2-char expiry day component -> String(2)
    @Size(max = 2, message = "Expiry day must not exceed 2 characters")
    @Pattern(regexp = "\\d{0,2}", message = "Expiry day must be 0 to 2 digits")
    private String expiryDay;

    /**
     * The card expiration date, derived from the raw expiry components.
     *
     * <p>This is a convenience projection of {@link #expiryYear}, {@link #expiryMonth}
     * and {@link #expiryDay} into a single {@link LocalDate}. The COBOL screens carried
     * only the raw month/year(/day) string components; this composed value is assembled
     * (and its calendar validity checked) by the service layer via
     * {@code service/shared/DateValidationService} (the translation of
     * {@code CSUTLDTC}/{@code CEEDAYS}). It is annotated
     * {@link JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} so the JSON wire form is an
     * ISO date string. The raw components are retained as the byte-faithful source of
     * truth; this field is purely derived and never the canonical representation.</p>
     */
    // Derived convenience date composed from EXPYEAR/EXPMON/EXPDAY by DateValidationService
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    /**
     * Optimistic-locking version token, mirrored from the {@code Card} entity's JPA
     * {@code @Version} column.
     *
     * <p>This field has <strong>no COBOL {@code PIC} origin</strong>: it is the Java&nbsp;25
     * realization of the {@code COCRDUPC} <em>read-before-update</em> concurrency check, which
     * compared the card image read at display time against the record on disk at update time and
     * rejected the write if it had changed. The card detail/read populates this token; the client
     * echoes it back on the subsequent update; {@code CardUpdateService} compares it against the
     * current entity version and rejects a <em>stale</em> form &mdash; one loaded before another
     * user's completed update &mdash; with a
     * {@link com.cardemo.exception.ConcurrentModificationException} (HTTP&nbsp;409). It is a
     * server-issued, client-echoed token, so it carries no input-validation constraint; when
     * {@code null} the server-side JPA {@code @Version} remains the safety net (AAP&nbsp;&sect;0.7.5).</p>
     */
    private Long version;

    // ---------------------------------------------------------------------
    // List-view fields -- paginated browse (COCRDLI), seven rows per page
    // ---------------------------------------------------------------------

    /**
     * The current browse page number.
     *
     * <p>Migrated from {@code PAGENO PIC X(3)}: the up-to-three-digit page index the
     * {@code COCRDLI} screen displayed. Kept as a fixed-width {@link String} &mdash;
     * consistent with the byte-faithful treatment of every other screen field on this
     * DTO &mdash; so the exact {@code X(3)} width is preserved; {@link Pattern @Pattern}
     * enforces a digit-only shape and {@link Size @Size} preserves the width. The
     * seven-rows-per-page semantics ({@link #ROWS_PER_PAGE}) and the next/previous page
     * arithmetic are the responsibility of {@code service/card/CardListService}.</p>
     */
    // PAGENO PIC X(3) -> up-to-3-digit page index -> String(3) (byte-faithful)
    @Size(max = 3, message = "Page number must not exceed 3 characters")
    @Pattern(regexp = "\\d{0,3}", message = "Page number must be 0 to 3 digits")
    private String pageNumber;

    /**
     * The card rows of the current browse page (at most {@link #ROWS_PER_PAGE} = 7).
     *
     * <p>Migrated from the seven repeating row groups of {@code COCRDLI}
     * ({@code CRDSEL{1-7}}/{@code ACCTNO{1-7}}/{@code CRDNUM{1-7}}/{@code CRDSTS{1-7}}).
     * Each element is a {@link CardListItem}. The list is annotated {@link Valid @Valid}
     * so Bean Validation cascades into every row, enforcing each row's byte-faithful
     * field widths.</p>
     */
    // COBOL substitution: COCRDLI's 7 repeating symbolic rows (CRDSEL/ACCTNO/CRDNUM/CRDSTS 1..7)
    // collapse into one List<CardListItem> of at most ROWS_PER_PAGE (7) elements; @Valid cascades.
    @Valid
    private List<CardListItem> cards;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson) to
     * instantiate this DTO reflectively before populating its properties via setters.
     */
    public CardDto() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    // ---------------------------------------------------------------------
    // Accessors -- single-card fields
    // ---------------------------------------------------------------------

    /**
     * Returns the owning account identifier ({@code ACCTSID}).
     *
     * @return the eleven-character account identifier, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the owning account identifier ({@code ACCTSID}).
     *
     * @param accountId the eleven-character account identifier to set
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the sixteen-digit card number ({@code CARDSID}).
     *
     * @return the sixteen-character card number, or {@code null} if unset
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the sixteen-digit card number ({@code CARDSID}).
     *
     * @param cardNumber the sixteen-character card number to set
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * Returns the embossed cardholder name ({@code CRDNAME} / {@code CARD-EMBOSSED-NAME}).
     *
     * @return the up-to-fifty-character embossed name, or {@code null} if unset
     */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Sets the embossed cardholder name ({@code CRDNAME} / {@code CARD-EMBOSSED-NAME}).
     *
     * @param embossedName the up-to-fifty-character embossed name to set
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /**
     * Returns the card active-status code ({@code CRDSTCD} / {@code CARD-ACTIVE-STATUS}).
     *
     * @return the single-character status flag (e.g. {@code "Y"}/{@code "N"}), or
     *         {@code null} if unset
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the card active-status code ({@code CRDSTCD} / {@code CARD-ACTIVE-STATUS}).
     *
     * @param activeStatus the single-character status flag to set
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the raw expiry month component ({@code EXPMON}).
     *
     * @return the two-character month component, or {@code null} if unset
     */
    public String getExpiryMonth() {
        return expiryMonth;
    }

    /**
     * Sets the raw expiry month component ({@code EXPMON}).
     *
     * @param expiryMonth the two-character month component to set
     */
    public void setExpiryMonth(String expiryMonth) {
        this.expiryMonth = expiryMonth;
    }

    /**
     * Returns the raw expiry year component ({@code EXPYEAR}).
     *
     * @return the four-character year component, or {@code null} if unset
     */
    public String getExpiryYear() {
        return expiryYear;
    }

    /**
     * Sets the raw expiry year component ({@code EXPYEAR}).
     *
     * @param expiryYear the four-character year component to set
     */
    public void setExpiryYear(String expiryYear) {
        this.expiryYear = expiryYear;
    }

    /**
     * Returns the raw expiry day component ({@code EXPDAY}; update screen only).
     *
     * @return the two-character day component, or {@code null} if unset
     */
    public String getExpiryDay() {
        return expiryDay;
    }

    /**
     * Sets the raw expiry day component ({@code EXPDAY}; update screen only).
     *
     * @param expiryDay the two-character day component to set
     */
    public void setExpiryDay(String expiryDay) {
        this.expiryDay = expiryDay;
    }

    /**
     * Returns the derived expiration date composed from the raw expiry components.
     *
     * @return the composed {@link LocalDate}, or {@code null} if not derived
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the derived expiration date composed from the raw expiry components.
     *
     * @param expirationDate the composed {@link LocalDate} to set
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the optimistic-locking version token (mirrored from the {@code Card} entity
     * {@code @Version}); {@code null} when unset.
     *
     * @return the version token, or {@code null} if unset
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version token. Populated from the entity on read and echoed
     * by the client on update for stale-form detection (AAP&nbsp;&sect;0.7.5).
     *
     * @param version the version token to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // ---------------------------------------------------------------------
    // Accessors -- list-view fields
    // ---------------------------------------------------------------------

    /**
     * Returns the current browse page number ({@code PAGENO}).
     *
     * @return the up-to-three-character page index, or {@code null} if unset
     */
    public String getPageNumber() {
        return pageNumber;
    }

    /**
     * Sets the current browse page number ({@code PAGENO}).
     *
     * @param pageNumber the up-to-three-character page index to set
     */
    public void setPageNumber(String pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * Returns the card rows of the current browse page.
     *
     * @return the list of {@link CardListItem} rows (at most {@link #ROWS_PER_PAGE}),
     *         or {@code null} if unset
     */
    public List<CardListItem> getCards() {
        return cards;
    }

    /**
     * Sets the card rows of the current browse page.
     *
     * @param cards the list of {@link CardListItem} rows to set (at most
     *              {@link #ROWS_PER_PAGE})
     */
    public void setCards(List<CardListItem> cards) {
        this.cards = cards;
    }

    // =====================================================================
    // Nested row type for the paginated card-list browse (COCRDLI)
    // =====================================================================

    /**
     * A single row of the {@code COCRDLI} card-list browse.
     *
     * <p>The legacy list map carried seven identical row groups
     * ({@code CRDSEL{1-7}}, {@code ACCTNO{1-7}}, {@code CRDNUM{1-7}},
     * {@code CRDSTS{1-7}}). Rather than reproduce seven flat field sets, the migrated
     * contract models <strong>one</strong> row type and exposes the page as a
     * {@link java.util.List} of these rows ({@link CardDto#cards}), capped at
     * {@link CardDto#ROWS_PER_PAGE} (seven) elements per page.</p>
     *
     * <p>Only the four data members per row are modeled. The legacy per-row protect
     * byte {@code CRDSTP{n}} ({@code PIC X(1)}, present on rows 2&ndash;7) is a 3270
     * field attribute, not card data, and is therefore omitted (AAP &sect;0.4.2). The
     * row fields carry {@link Size @Size} width constraints only (no digit
     * {@link Pattern @Pattern}); these are server-produced browse rows that may legally
     * be space-padded or blank, so digit-shape validation is deliberately left to the
     * input/filter fields on the enclosing {@link CardDto}.</p>
     */
    public static class CardListItem {

        /**
         * The row selection indicator.
         *
         * <p>Migrated from {@code CRDSEL{n} PIC X(1)}: the single character the
         * operator typed beside a row to select it (for example {@code "S"}) or a
         * space when unselected. Modeled as a {@link String} of length one.</p>
         */
        // CRDSEL{n} PIC X(1) -> single-character row select ("S"/space) -> String(1)
        @Size(max = 1, message = "Selection flag must be a single character")
        private String selectionFlag;

        /**
         * The account identifier shown on the row.
         *
         * <p>Migrated from {@code ACCTNO{n} PIC X(11)}: an eleven-character account
         * number, kept as a fixed-width {@link String} so the width is preserved.</p>
         */
        // ACCTNO{n} PIC X(11) -> 11-char account number -> String(11)
        @Size(max = 11, message = "Account ID must not exceed 11 characters")
        private String accountId;

        /**
         * The card number shown on the row.
         *
         * <p>Migrated from {@code CRDNUM{n} PIC X(16)}: a sixteen-character card number,
         * kept as a fixed-width {@link String} &mdash; never a numeric type &mdash; so
         * the sixteen-digit identity and any leading zeros are preserved exactly.</p>
         */
        // CRDNUM{n} PIC X(16) -> 16-char card number (PAN) -> String(16), NEVER numeric
        @Size(max = 16, message = "Card number must not exceed 16 characters")
        private String cardNumber;

        /**
         * The card active-status code shown on the row.
         *
         * <p>Migrated from {@code CRDSTS{n} PIC X(1)}: a single-character active flag
         * (e.g. {@code 'Y'}/{@code 'N'}), modeled as a {@link String} of length one.
         * Serialized as {@code activeStatus} to match the documented API contract.</p>
         */
        // CRDSTS{n} PIC X(1) -> single-character active flag (Y/N) -> String(1)
        @Size(max = 1, message = "Active status must be a single character")
        private String activeStatus;

        /**
         * Default no-argument constructor required by the JSON binder (Jackson).
         */
        public CardListItem() {
            // Intentionally empty: Jackson instantiates then sets fields via setters.
        }

        /**
         * Returns the row selection indicator ({@code CRDSEL}).
         *
         * @return the single-character selection flag, or {@code null} if unset
         */
        public String getSelectionFlag() {
            return selectionFlag;
        }

        /**
         * Sets the row selection indicator ({@code CRDSEL}).
         *
         * @param selectionFlag the single-character selection flag to set
         */
        public void setSelectionFlag(String selectionFlag) {
            this.selectionFlag = selectionFlag;
        }

        /**
         * Returns the row account identifier ({@code ACCTNO}).
         *
         * @return the eleven-character account identifier, or {@code null} if unset
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Sets the row account identifier ({@code ACCTNO}).
         *
         * @param accountId the eleven-character account identifier to set
         */
        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        /**
         * Returns the row card number ({@code CRDNUM}).
         *
         * @return the sixteen-character card number, or {@code null} if unset
         */
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Sets the row card number ({@code CRDNUM}).
         *
         * @param cardNumber the sixteen-character card number to set
         */
        public void setCardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
        }

        /**
         * Returns the row card active-status code ({@code CRDSTS} / {@code CARD-ACTIVE-STATUS}).
         *
         * @return the single-character status flag, or {@code null} if unset
         */
        public String getActiveStatus() {
            return activeStatus;
        }

        /**
         * Sets the row card active-status code ({@code CRDSTS} / {@code CARD-ACTIVE-STATUS}).
         *
         * @param activeStatus the single-character status flag to set
         */
        public void setActiveStatus(String activeStatus) {
            this.activeStatus = activeStatus;
        }

        /**
         * Identity-based equality keyed on {@link #accountId} and {@link #cardNumber}
         * &mdash; the account/card pair that uniquely identifies a browse row.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code CardListItem} with an equal
         *         account id and card number
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CardListItem that = (CardListItem) o;
            return Objects.equals(accountId, that.accountId)
                    && Objects.equals(cardNumber, that.cardNumber);
        }

        /**
         * Hash code derived from {@link #accountId} and {@link #cardNumber}, consistent
         * with {@link #equals(Object)}.
         *
         * @return the combined hash code of the account id and card number
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountId, cardNumber);
        }

        /**
         * Diagnostic representation of this browse row.
         *
         * <p>For safety the sixteen-digit card number (a PCI-sensitive primary account
         * number) is <strong>masked</strong> &mdash; shown only as present
         * ({@code "****"}) or absent ({@code "null"}) &mdash; consistent with the
         * security-conscious {@code toString} convention used across the model layer.</p>
         *
         * @return a human-readable, non-sensitive description of this row
         */
        @Override
        public String toString() {
            return "CardListItem{"
                    + "selectionFlag='" + selectionFlag + '\''
                    + ", accountId='" + accountId + '\''
                    + ", cardNumber=" + (cardNumber == null ? "null" : "****")
                    + ", activeStatus='" + activeStatus + '\''
                    + '}';
        }
    }

    // ---------------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------------

    /**
     * Identity-based equality keyed on the card number ({@link #cardNumber}).
     *
     * <p>Two {@code CardDto} instances are equal when they are of the exact same class
     * and share the same {@link #cardNumber}. The card number alone defines the identity
     * of the record this DTO represents; the mutable attributes (name, status, expiry)
     * and the transient list-view members ({@link #pageNumber}, {@link #cards}) are
     * deliberately excluded so equality stays stable across edits and is independent of
     * which screen shape populated the instance. This mirrors the identity-based contract
     * of the {@code Card} entity. Note that two not-yet-identified instances (both with a
     * {@code null} {@code cardNumber}) compare equal.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code CardDto} with an equal card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CardDto that = (CardDto) o;
        return Objects.equals(cardNumber, that.cardNumber);
    }

    /**
     * Hash code derived solely from {@link #cardNumber}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNumber);
    }

    /**
     * Diagnostic representation of this DTO.
     *
     * <p>For safety the sixteen-digit card number (a PCI-sensitive primary account
     * number) is <strong>masked</strong> &mdash; shown only as present ({@code "****"})
     * or absent ({@code "null"}) &mdash; consistent with the security-conscious
     * {@code toString} convention used across the model layer. The list-view rows are
     * summarized by their count rather than enumerated, so embedded row card numbers are
     * never emitted into logs either.</p>
     *
     * @return a human-readable, non-sensitive description of this card DTO
     */
    @Override
    public String toString() {
        return "CardDto{"
                + "accountId='" + accountId + '\''
                + ", cardNumber=" + (cardNumber == null ? "null" : "****")
                + ", embossedName='" + embossedName + '\''
                + ", activeStatus='" + activeStatus + '\''
                + ", expiryMonth='" + expiryMonth + '\''
                + ", expiryYear='" + expiryYear + '\''
                + ", expiryDay='" + expiryDay + '\''
                + ", expirationDate=" + expirationDate
                + ", version=" + version
                + ", pageNumber='" + pageNumber + '\''
                + ", cards=" + (cards == null ? "null" : "[" + cards.size() + " row(s)]")
                + '}';
    }
}
