package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * REST request/response payload for the CardDemo <strong>transaction</strong> screens:
 * <strong>Transaction List</strong> ({@code CT00}), <strong>Transaction Detail / View</strong>
 * ({@code CT01}) and <strong>Transaction Add</strong> ({@code CT02}).
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * three 3270 transaction maps the legacy system used. On the mainframe those screens
 * were BMS maps {@code COTRN00} (list), {@code COTRN01} (detail/view) and
 * {@code COTRN02} (add); their symbolic maps are defined by the copybooks
 * {@code app/cpy-bms/COTRN00.CPY} ({@code 01 COTRN0AI}),
 * {@code app/cpy-bms/COTRN01.CPY} ({@code 01 COTRN1AI}) and
 * {@code app/cpy-bms/COTRN02.CPY} ({@code 01 COTRN2AI}). The online programs
 * {@code app/cbl/COTRN00C.cbl} (paginated browse, ten rows per page),
 * {@code app/cbl/COTRN01C.cbl} (single keyed read) and
 * {@code app/cbl/COTRN02C.cbl} (add with auto-generated id, cross-reference resolution
 * and Y/N confirmation) populated those symbolic structures from the {@code TRANSACT}
 * VSAM file. This DTO consolidates all three screens into a single canonical
 * representation.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/TransactionController}
 * to the {@code GET}/{@code POST} routes under {@code /api/transactions/*}. Jackson
 * serializes/deserializes the JSON body, Jakarta Bean Validation enforces the structural
 * field constraints declared below (the {@code @Valid} gate on the controller method),
 * and the validated payload is handed to
 * {@code service/transaction/TransactionListService} (browse, from {@code COTRN00C}),
 * {@code service/transaction/TransactionDetailService} (view, from {@code COTRN01C}) or
 * {@code service/transaction/TransactionAddService} (add, from {@code COTRN02C}). This
 * DTO is therefore a pure boundary type &mdash; it carries no business logic, no I/O and
 * no static mutable state.</p>
 *
 * <h2>Key insight &mdash; three screens, one transaction concept</h2>
 * <p>The three transaction screens describe the <em>same</em> transaction record at two
 * shapes:</p>
 * <ul>
 *   <li>The <strong>detail/add</strong> shape ({@code COTRN01}/{@code COTRN02}) is the
 *       single-record view: the transaction id, the owning card number, the
 *       type/category codes, the source, a full sixty-character description, the
 *       monetary {@link #amount}, the origination and processing dates, and the full
 *       merchant block (id, name, city, zip). The add map ({@code COTRN02}) additionally
 *       carries the {@link #accountId} key the operator keys in and a {@link #confirm}
 *       Y/N flag; it has no transaction id of its own because {@code COTRN02C}
 *       auto-generates the id by browsing to the end of the file and incrementing. The
 *       single-transaction properties on this class model that shape.</li>
 *   <li>The <strong>list</strong> shape ({@code COTRN00}) is the paginated browse:
 *       <strong>exactly ten rows per page</strong> (see {@link #ROWS_PER_PAGE}), each row
 *       carrying a selection flag, a transaction id, a date, a (truncated, twenty-six
 *       character) description and the monetary amount. The nested
 *       {@link TransactionListItem} type models one such row, and {@link #transactions}
 *       holds the (at most ten) rows of the current page identified by
 *       {@link #pageNumber}. The {@link #transactionIdFilter} property carries the
 *       starting transaction id the operator typed to position the browse
 *       ({@code TRNIDIN}); on the detail screen that very same field is the lookup key
 *       used to fetch the record.</li>
 * </ul>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Monetary amount &rarr; {@link BigDecimal} of scale 2 (AAP &sect;0.7.3).</strong>
 *       The amount appears on screen as {@code TRNAMT}/{@code TAMT00n PIC X(12)} (a
 *       twelve-character display field) but is backed by {@code TRAN-AMT PIC S9(09)V99}
 *       in {@code app/cpy/CVTRA05Y.cpy} &mdash; a <em>signed</em> packed-decimal money
 *       value of nine integer plus two fractional digits. It is modeled as a
 *       {@link BigDecimal} with {@link Digits @Digits(integer = 9, fraction = 2)} and is
 *       <strong>never</strong> {@code float} or {@code double}: penny-level parity is
 *       non-negotiable because this amount is summed into statement totals
 *       ({@code CBSTM03A}) and feeds interest and report processing. Any comparison of
 *       this field MUST use {@link BigDecimal#compareTo(BigDecimal)} rather than the
 *       scale-sensitive {@link BigDecimal#equals(Object)}. The identical rule is applied
 *       to {@link TransactionListItem#amount the list-row amount}.</li>
 *   <li><strong>Transaction id and card number kept as fixed-width {@link String}
 *       (AAP &sect;0.4.2).</strong> {@code TRNID}/{@code TRNID0n} and
 *       {@code CARDNUM}/{@code CARDNIN} are {@code PIC X(16)}. They are modeled as
 *       {@link String}, <strong>never</strong> a numeric type, so the fixed sixteen-character
 *       width and any leading zeros are preserved exactly.</li>
 *   <li><strong>Byte-faithful field lengths (AAP &sect;0.7.1 / &sect;0.7.2).</strong>
 *       Every textual field carries a {@link Size @Size(max = n)} equal to its COBOL
 *       {@code PIC} length, preserving the external-interface width exactly. Note the
 *       list-row description is {@code PIC X(26)} whereas the detail description is
 *       {@code PIC X(60)}; both widths are preserved on their respective members.</li>
 *   <li><strong>{@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} dates &rarr; {@link LocalDate}.</strong>
 *       The detail screen carries the date portion only ({@code TORIGDT}/{@code TPROCDT
 *       PIC X(10)}, {@code yyyy-mm-dd}); the underlying record holds a full
 *       twenty-six-character timestamp on the {@code Transaction} entity. The screen
 *       contract is faithfully modeled here as a {@link LocalDate} annotated
 *       {@link JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} so the JSON wire form
 *       preserves the {@code PIC X(10)} contract exactly. Date <em>validity</em> is
 *       enforced by the service layer ({@code service/shared/DateValidationService}, the
 *       translation of {@code CSUTLDTC}/{@code CEEDAYS}), not by this DTO.</li>
 *   <li><strong>Pagination preserved.</strong> {@code COTRN00C} browses the transaction
 *       file ten rows at a time; that page size is encoded as {@link #ROWS_PER_PAGE} and
 *       the page index as {@link #pageNumber} ({@code PAGENUM PIC X(8)}). The page
 *       arithmetic itself lives in {@code TransactionListService}.</li>
 * </ul>
 *
 * <h2>Deliberately excluded &mdash; 3270 chrome, control bytes and AID keys (AAP &sect;0.4.2)</h2>
 * <p>The following symbolic-map members are <strong>not</strong> modeled because they are
 * presentation/control artifacts with no REST equivalent:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}/
 *       {@code CURTIMEI} ({@code X(8)}) and {@code PGMNAMEI} ({@code X(8)}).</li>
 *   <li><strong>Message line (output-only):</strong> {@code ERRMSGI} ({@code X(78)})
 *       &mdash; in REST this becomes the response/error body, not a request field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (flag/attribute) input bytes and the redefined
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V} output bytes &mdash; 3270
 *       datastream metadata with no REST analogue.</li>
 *   <li><strong>Function/AID keys:</strong> PF-key handling maps onto distinct REST
 *       endpoints / controller routing, never a body field.</li>
 * </ul>
 *
 * <p>Consistent with the folder rule, this DTO is a separate API representation and
 * deliberately does <strong>not</strong> import any {@code com.cardemo.model.entity}
 * type; mapping between this DTO and the {@code Transaction} entity is the service
 * layer's responsibility.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.Digits
 * @see jakarta.validation.constraints.Pattern
 * @see java.math.BigDecimal
 * @see java.time.LocalDate
 */
public class TransactionDto {

    /**
     * The number of transaction rows the legacy browse painted on a single
     * {@code COTRN00} page (ten), preserved as an explicit contract constant.
     *
     * <p>The online program {@code COTRN00C} read the {@code TRANSACT} file ten records
     * at a time, mapping them to the symbolic rows {@code TRNID01}&hellip;{@code TRNID10}
     * (and the parallel {@code SEL000n}/{@code TDATE0n}/{@code TDESC0n}/{@code TAMT00n}
     * groups). {@link #transactions} therefore holds at most this many items per page.
     * The actual page arithmetic (positioning, next/previous) is performed by
     * {@code service/transaction/TransactionListService}; this constant simply documents
     * the page-size contract so it is not silently lost in translation.</p>
     */
    public static final int ROWS_PER_PAGE = 10;

    // ---------------------------------------------------------------------
    // Single-transaction fields -- detail/view (COTRN01) and add (COTRN02).
    // On the list screen (COTRN00) transactionIdFilter carries the TRNIDIN
    // starting-id browse filter; on the detail screen the same TRNIDIN field
    // is the lookup key used to fetch the record.
    // ---------------------------------------------------------------------

    /**
     * The unique transaction identifier.
     *
     * <p>Migrated from {@code TRNID PIC X(16)} (detail map {@code COTRN01}): the
     * sixteen-character transaction id. Kept as a fixed-width {@link String} so the
     * width and any leading zeros are preserved exactly. On the add screen
     * ({@code COTRN02}) there is no inbound transaction id &mdash; {@code COTRN02C}
     * auto-generates it by browsing to the end of the {@code TRANSACT} file and
     * incrementing &mdash; so this property is populated on the response after the add
     * completes.</p>
     */
    // TRNID PIC X(16) -> 16-char fixed-width transaction id -> String(16)
    @Size(max = 16, message = "Transaction ID must not exceed 16 characters")
    private String transactionId;

    /**
     * The starting transaction id filter / detail lookup key.
     *
     * <p>Migrated from {@code TRNIDIN PIC X(16)}, which appears on both the list map
     * ({@code COTRN00}, where it is the starting id the operator typed to position the
     * paginated browse) and the detail map ({@code COTRN01}, where it is the key the
     * operator typed to look a record up). A single property serves both roles. Kept as
     * a fixed-width {@link String} so the sixteen-character width is preserved.</p>
     */
    // TRNIDIN PIC X(16) -> 16-char browse-start / lookup key -> String(16)
    @Size(max = 16, message = "Transaction ID filter must not exceed 16 characters")
    private String transactionIdFilter;

    /**
     * The account identifier keyed on the add screen.
     *
     * <p>Migrated from {@code ACTIDIN PIC X(11)} (add map {@code COTRN02}): the
     * eleven-character account number the operator keys in so {@code COTRN02C} can
     * resolve the owning card via the cross-reference. Kept as a fixed-width
     * {@link String} so leading zeros and the exact width are preserved;
     * {@link Pattern @Pattern} enforces a digit-only shape (zero to eleven digits,
     * allowing an unset/blank value because the same DTO also serves the list and detail
     * screens which do not carry this field) and {@link Size @Size} preserves the
     * width.</p>
     */
    // ACTIDIN PIC X(11) -> 11-char fixed-width account number -> String(11)
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @Pattern(regexp = "\\d{0,11}", message = "Account ID must be 0 to 11 digits")
    private String accountId;

    /**
     * The sixteen-digit card number (primary account number) the transaction posts to.
     *
     * <p>Migrated from {@code CARDNUM PIC X(16)} (detail map {@code COTRN01}) and
     * {@code CARDNIN PIC X(16)} (add map {@code COTRN02}). Kept as a fixed-width
     * {@link String} &mdash; <strong>never</strong> a numeric type &mdash; so the
     * sixteen-digit identity and any leading zeros are preserved exactly.
     * {@link Pattern @Pattern} enforces a digit-only shape (zero to sixteen digits,
     * allowing an unset/blank value) and {@link Size @Size} preserves the width.</p>
     */
    // CARDNUM / CARDNIN PIC X(16) -> 16-char card number (PAN) -> String(16), NEVER numeric
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    @Pattern(regexp = "\\d{0,16}", message = "Card number must be 0 to 16 digits")
    private String cardNumber;

    /**
     * The transaction type code.
     *
     * <p>Migrated from {@code TTYPCD PIC X(2)}: a two-character transaction-type code
     * (the {@code TRANTYPE} reference-data key). Modeled as a fixed-width
     * {@link String}.</p>
     */
    // TTYPCD PIC X(2) -> 2-char transaction type code -> String(2)
    @Size(max = 2, message = "Type code must not exceed 2 characters")
    private String typeCode;

    /**
     * The transaction category code.
     *
     * <p>Migrated from {@code TCATCD PIC X(4)}: a four-character transaction-category
     * code (the {@code TRANCATG} reference-data key). Modeled as a fixed-width
     * {@link String}.</p>
     */
    // TCATCD PIC X(4) -> 4-char transaction category code -> String(4)
    @Size(max = 4, message = "Category code must not exceed 4 characters")
    private String categoryCode;

    /**
     * The transaction source.
     *
     * <p>Migrated from {@code TRNSRC PIC X(10)}: a ten-character free-text source label
     * describing where the transaction originated. Modeled as a fixed-width
     * {@link String}.</p>
     */
    // TRNSRC PIC X(10) -> 10-char transaction source -> String(10)
    @Size(max = 10, message = "Source must not exceed 10 characters")
    private String source;

    /**
     * The transaction description.
     *
     * <p>Migrated from {@code TDESC PIC X(60)} on the detail/add maps: a
     * sixty-character free-text description. Modeled as a {@link String} of at most
     * sixty characters. (The list-row description {@code TDESC0n} is the shorter
     * {@code PIC X(26)} form &mdash; see {@link TransactionListItem#description}.)</p>
     */
    // TDESC PIC X(60) -> 60-char description -> String(60)
    @Size(max = 60, message = "Description must not exceed 60 characters")
    private String description;

    /**
     * The signed monetary transaction amount.
     *
     * <p>Migrated from {@code TRNAMT PIC X(12)} on screen, backed by
     * {@code TRAN-AMT PIC S9(09)V99} in {@code app/cpy/CVTRA05Y.cpy}: a signed
     * packed-decimal money value of nine integer plus two fractional digits. Modeled as
     * a {@link BigDecimal} of <strong>scale 2</strong>; <strong>never</strong>
     * {@code float}/{@code double} (AAP &sect;0.7.3). {@link Digits @Digits(integer = 9,
     * fraction = 2)} locks the precision to the {@code PIC} clause. Callers MUST compare
     * with {@link BigDecimal#compareTo(BigDecimal)}, not the scale-sensitive
     * {@link BigDecimal#equals(Object)}.</p>
     */
    // COBOL substitution: TRNAMT PIC X(12) / TRAN-AMT PIC S9(09)V99 -> BigDecimal scale 2, signed (NEVER float/double)
    @Digits(integer = 9, fraction = 2, message = "Amount must have at most 9 integer and 2 fraction digits")
    private BigDecimal amount;

    /**
     * The transaction origination date.
     *
     * <p>Migrated from {@code TORIGDT PIC X(10)} (the {@code yyyy-mm-dd} date portion of
     * the record's {@code TRAN-ORIG-TS} timestamp). Modeled as a {@link LocalDate} and
     * annotated {@link JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} so the JSON wire
     * form preserves the exact {@code PIC X(10)} contract. The full twenty-six-character
     * origination timestamp lives on the {@code Transaction} entity, not on this screen
     * DTO.</p>
     */
    // TORIGDT PIC X(10) (yyyy-mm-dd; date portion of TRAN-ORIG-TS) -> LocalDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate originationDate;

    /**
     * The transaction processing date.
     *
     * <p>Migrated from {@code TPROCDT PIC X(10)} (the {@code yyyy-mm-dd} date portion of
     * the record's {@code TRAN-PROC-TS} timestamp). Modeled as a {@link LocalDate} and
     * annotated {@link JsonFormat @JsonFormat(pattern = "yyyy-MM-dd")} so the JSON wire
     * form preserves the exact {@code PIC X(10)} contract.</p>
     */
    // TPROCDT PIC X(10) (yyyy-mm-dd; date portion of TRAN-PROC-TS) -> LocalDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate processingDate;

    /**
     * The merchant identifier.
     *
     * <p>Migrated from {@code MID PIC X(9)} (backed by {@code TRAN-MERCHANT-ID
     * PIC 9(09)}): a nine-character merchant id. Kept as a fixed-width {@link String} so
     * the width and any leading zeros are preserved; {@link Pattern @Pattern} enforces a
     * digit-only shape (zero to nine digits, allowing an unset/blank value) and
     * {@link Size @Size} preserves the width.</p>
     */
    // MID PIC X(9) / TRAN-MERCHANT-ID PIC 9(09) -> 9-char merchant id -> String(9)
    @Size(max = 9, message = "Merchant ID must not exceed 9 characters")
    @Pattern(regexp = "\\d{0,9}", message = "Merchant ID must be 0 to 9 digits")
    private String merchantId;

    /**
     * The merchant name.
     *
     * <p>Migrated from {@code MNAME PIC X(30)}: a thirty-character merchant name.
     * Modeled as a fixed-width {@link String}.</p>
     */
    // MNAME PIC X(30) -> 30-char merchant name -> String(30)
    @Size(max = 30, message = "Merchant name must not exceed 30 characters")
    private String merchantName;

    /**
     * The merchant city.
     *
     * <p>Migrated from {@code MCITY PIC X(25)}: a twenty-five-character merchant city.
     * Modeled as a fixed-width {@link String}.</p>
     */
    // MCITY PIC X(25) -> 25-char merchant city -> String(25)
    @Size(max = 25, message = "Merchant city must not exceed 25 characters")
    private String merchantCity;

    /**
     * The merchant ZIP code.
     *
     * <p>Migrated from {@code MZIP PIC X(10)}: a ten-character merchant ZIP. Modeled as
     * a fixed-width {@link String}.</p>
     */
    // MZIP PIC X(10) -> 10-char merchant zip -> String(10)
    @Size(max = 10, message = "Merchant ZIP must not exceed 10 characters")
    private String merchantZip;

    /**
     * The add-confirmation flag.
     *
     * <p>Migrated from {@code CONFIRM PIC X(1)} (add map {@code COTRN02}): the single
     * character ({@code 'Y'}/{@code 'N'}) the operator types to confirm or cancel
     * creation of a new transaction. Modeled as a {@link String} of length one.</p>
     */
    // CONFIRM PIC X(1) -> single-character add confirmation (Y/N) -> String(1)
    @Size(max = 1, message = "Confirm flag must be a single character")
    private String confirm;

    // ---------------------------------------------------------------------
    // List-view fields -- paginated browse (COTRN00).
    // ---------------------------------------------------------------------

    /**
     * The current browse page number.
     *
     * <p>Migrated from {@code PAGENUM PIC X(8)}: the page indicator the list screen
     * displayed. Kept as a fixed-width {@link String} (rather than a numeric type) so the
     * eight-character display contract is preserved byte-for-byte and any leading spaces
     * or zero-padding survive the round trip; the page arithmetic itself is performed by
     * {@code service/transaction/TransactionListService}. At most {@link #ROWS_PER_PAGE}
     * (ten) rows are carried in {@link #transactions} for each page.</p>
     */
    // PAGENUM PIC X(8) -> 8-char display page indicator -> String(8) (kept as String to preserve width)
    @Size(max = 8, message = "Page number must not exceed 8 characters")
    private String pageNumber;

    /**
     * The transaction rows of the current browse page.
     *
     * <p>Migrated from the ten repeating row groups of {@code COTRN00}
     * ({@code SEL000n}/{@code TRNID0n}/{@code TDATE0n}/{@code TDESC0n}/{@code TAMT00n},
     * {@code n = 1..10}). Rather than reproduce ten flat field sets, the migrated
     * contract models <strong>one</strong> row type ({@link TransactionListItem}) and
     * exposes the page as a {@link List} of those rows, capped at {@link #ROWS_PER_PAGE}
     * (ten) elements per page. The {@link Valid @Valid} annotation cascades Jakarta Bean
     * Validation into each row so the per-row field constraints are enforced.</p>
     */
    // COBOL substitution: COTRN00 10 repeating rows -> List<TransactionListItem>, <= ROWS_PER_PAGE (10) per page
    @Valid
    private List<TransactionListItem> transactions;

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    /**
     * Default no-argument constructor required by the JSON binder (Jackson).
     */
    public TransactionDto() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    // ---------------------------------------------------------------------
    // Accessors -- single-transaction fields (detail/add)
    // ---------------------------------------------------------------------

    /**
     * Returns the transaction identifier ({@code TRNID}).
     *
     * @return the sixteen-character transaction id, or {@code null} if unset
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Sets the transaction identifier ({@code TRNID}).
     *
     * @param transactionId the sixteen-character transaction id to set
     */
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    /**
     * Returns the starting transaction id filter / detail lookup key ({@code TRNIDIN}).
     *
     * @return the sixteen-character filter/lookup id, or {@code null} if unset
     */
    public String getTransactionIdFilter() {
        return transactionIdFilter;
    }

    /**
     * Sets the starting transaction id filter / detail lookup key ({@code TRNIDIN}).
     *
     * @param transactionIdFilter the sixteen-character filter/lookup id to set
     */
    public void setTransactionIdFilter(String transactionIdFilter) {
        this.transactionIdFilter = transactionIdFilter;
    }

    /**
     * Returns the account identifier ({@code ACTIDIN}).
     *
     * @return the eleven-character account identifier, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier ({@code ACTIDIN}).
     *
     * @param accountId the eleven-character account identifier to set
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the card number ({@code CARDNUM}/{@code CARDNIN}).
     *
     * @return the sixteen-character card number, or {@code null} if unset
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the card number ({@code CARDNUM}/{@code CARDNIN}).
     *
     * @param cardNumber the sixteen-character card number to set
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * Returns the transaction type code ({@code TTYPCD}).
     *
     * @return the two-character type code, or {@code null} if unset
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code ({@code TTYPCD}).
     *
     * @param typeCode the two-character type code to set
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code ({@code TCATCD}).
     *
     * @return the four-character category code, or {@code null} if unset
     */
    public String getCategoryCode() {
        return categoryCode;
    }

    /**
     * Sets the transaction category code ({@code TCATCD}).
     *
     * @param categoryCode the four-character category code to set
     */
    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns the transaction source ({@code TRNSRC}).
     *
     * @return the ten-character source label, or {@code null} if unset
     */
    public String getSource() {
        return source;
    }

    /**
     * Sets the transaction source ({@code TRNSRC}).
     *
     * @param source the ten-character source label to set
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Returns the transaction description ({@code TDESC}).
     *
     * @return the up-to-sixty-character description, or {@code null} if unset
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the transaction description ({@code TDESC}).
     *
     * @param description the up-to-sixty-character description to set
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the signed monetary transaction amount ({@code TRNAMT} /
     * {@code TRAN-AMT}).
     *
     * @return the amount as a {@link BigDecimal} of scale 2, or {@code null} if unset
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Sets the signed monetary transaction amount ({@code TRNAMT} / {@code TRAN-AMT}).
     *
     * <p>The value is stored as supplied; this DTO neither rounds nor rescales it.
     * Callers needing penny-level parity should provide a {@link BigDecimal} of scale 2
     * and compare with {@link BigDecimal#compareTo(BigDecimal)} (AAP &sect;0.7.3).</p>
     *
     * @param amount the signed amount to set
     */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns the transaction origination date ({@code TORIGDT}).
     *
     * @return the origination date, or {@code null} if unset
     */
    public LocalDate getOriginationDate() {
        return originationDate;
    }

    /**
     * Sets the transaction origination date ({@code TORIGDT}).
     *
     * @param originationDate the origination date to set
     */
    public void setOriginationDate(LocalDate originationDate) {
        this.originationDate = originationDate;
    }

    /**
     * Returns the transaction processing date ({@code TPROCDT}).
     *
     * @return the processing date, or {@code null} if unset
     */
    public LocalDate getProcessingDate() {
        return processingDate;
    }

    /**
     * Sets the transaction processing date ({@code TPROCDT}).
     *
     * @param processingDate the processing date to set
     */
    public void setProcessingDate(LocalDate processingDate) {
        this.processingDate = processingDate;
    }

    /**
     * Returns the merchant identifier ({@code MID}).
     *
     * @return the nine-character merchant id, or {@code null} if unset
     */
    public String getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier ({@code MID}).
     *
     * @param merchantId the nine-character merchant id to set
     */
    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name ({@code MNAME}).
     *
     * @return the up-to-thirty-character merchant name, or {@code null} if unset
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name ({@code MNAME}).
     *
     * @param merchantName the up-to-thirty-character merchant name to set
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city ({@code MCITY}).
     *
     * @return the up-to-twenty-five-character merchant city, or {@code null} if unset
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city ({@code MCITY}).
     *
     * @param merchantCity the up-to-twenty-five-character merchant city to set
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP code ({@code MZIP}).
     *
     * @return the up-to-ten-character merchant ZIP, or {@code null} if unset
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP code ({@code MZIP}).
     *
     * @param merchantZip the up-to-ten-character merchant ZIP to set
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the add-confirmation flag ({@code CONFIRM}).
     *
     * @return the single-character confirmation flag, or {@code null} if unset
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the add-confirmation flag ({@code CONFIRM}).
     *
     * @param confirm the single-character confirmation flag to set
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    // ---------------------------------------------------------------------
    // Accessors -- list-view fields (COTRN00)
    // ---------------------------------------------------------------------

    /**
     * Returns the current browse page number ({@code PAGENUM}).
     *
     * @return the up-to-eight-character page indicator, or {@code null} if unset
     */
    public String getPageNumber() {
        return pageNumber;
    }

    /**
     * Sets the current browse page number ({@code PAGENUM}).
     *
     * @param pageNumber the up-to-eight-character page indicator to set
     */
    public void setPageNumber(String pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * Returns the transaction rows of the current browse page.
     *
     * @return the list of {@link TransactionListItem} rows (at most
     *         {@link #ROWS_PER_PAGE}), or {@code null} if unset
     */
    public List<TransactionListItem> getTransactions() {
        return transactions;
    }

    /**
     * Sets the transaction rows of the current browse page.
     *
     * @param transactions the list of {@link TransactionListItem} rows to set (at most
     *                     {@link #ROWS_PER_PAGE})
     */
    public void setTransactions(List<TransactionListItem> transactions) {
        this.transactions = transactions;
    }

    // =====================================================================
    // Nested row type for the paginated transaction-list browse (COTRN00)
    // =====================================================================

    /**
     * A single row of the {@code COTRN00} transaction-list browse.
     *
     * <p>The legacy list map carried ten identical row groups
     * ({@code SEL000{1-10}}, {@code TRNID0{1-10}}, {@code TDATE0{1-10}},
     * {@code TDESC0{1-10}}, {@code TAMT00{1-10}}). Rather than reproduce ten flat field
     * sets, the migrated contract models <strong>one</strong> row type and exposes the
     * page as a {@link java.util.List} of these rows ({@link TransactionDto#transactions}),
     * capped at {@link TransactionDto#ROWS_PER_PAGE} (ten) elements per page.</p>
     *
     * <p>Only the five data members per row are modeled. The legacy per-row length
     * ({@code ...L}), flag/attribute ({@code ...F}/{@code ...A}) and output control
     * bytes ({@code ...C}/{@code ...P}/{@code ...H}/{@code ...V}) are 3270 datastream
     * metadata, not transaction data, and are therefore omitted (AAP &sect;0.4.2).</p>
     *
     * <p><strong>Decimal fidelity.</strong> Like the enclosing DTO's
     * {@link TransactionDto#amount}, the row {@link #amount} is a {@link BigDecimal} of
     * scale 2 backed by {@code TRAN-AMT PIC S9(09)V99}; it is <strong>never</strong>
     * {@code float}/{@code double} and must be compared with
     * {@link BigDecimal#compareTo(BigDecimal)} (AAP &sect;0.7.3). Note the row
     * description is the shorter {@code PIC X(26)} form, distinct from the detail
     * screen's {@code PIC X(60)} description.</p>
     */
    public static class TransactionListItem {

        /**
         * The row selection indicator.
         *
         * <p>Migrated from {@code SEL000n PIC X(1)}: the single character the operator
         * typed beside a row to select it (for example {@code "S"}) or a space when
         * unselected. Modeled as a {@link String} of length one.</p>
         */
        // SEL000n PIC X(1) -> single-character row select ("S"/space) -> String(1)
        @Size(max = 1, message = "Selection flag must be a single character")
        private String selectionFlag;

        /**
         * The transaction identifier shown on the row.
         *
         * <p>Migrated from {@code TRNID0n PIC X(16)}: a sixteen-character transaction id,
         * kept as a fixed-width {@link String} so the width and any leading zeros are
         * preserved.</p>
         */
        // TRNID0n PIC X(16) -> 16-char transaction id -> String(16)
        @Size(max = 16, message = "Transaction ID must not exceed 16 characters")
        private String transactionId;

        /**
         * The transaction date shown on the row.
         *
         * <p>Migrated from {@code TDATE0n PIC X(8)}: an eight-character date as painted on
         * the browse row, kept as a fixed-width {@link String} so the exact display form
         * is preserved (the list screen rendered the date in the program's own
         * eight-character layout).</p>
         */
        // TDATE0n PIC X(8) -> 8-char display date -> String(8)
        @Size(max = 8, message = "Date must not exceed 8 characters")
        private String date;

        /**
         * The (truncated) transaction description shown on the row.
         *
         * <p>Migrated from {@code TDESC0n PIC X(26)}: the twenty-six-character description
         * the browse row could fit &mdash; <strong>shorter</strong> than the detail
         * screen's {@code PIC X(60)} description. Modeled as a {@link String} of at most
         * twenty-six characters so the list-row width is preserved exactly.</p>
         */
        // TDESC0n PIC X(26) -> 26-char (truncated) row description -> String(26) (NOT 60)
        @Size(max = 26, message = "Description must not exceed 26 characters")
        private String description;

        /**
         * The signed monetary amount shown on the row.
         *
         * <p>Migrated from {@code TAMT00n PIC X(12)} on screen, backed by
         * {@code TRAN-AMT PIC S9(09)V99}: a signed packed-decimal money value. Modeled as
         * a {@link BigDecimal} of <strong>scale 2</strong>; <strong>never</strong>
         * {@code float}/{@code double} (AAP &sect;0.7.3). {@link Digits @Digits(integer =
         * 9, fraction = 2)} locks the precision; compare with
         * {@link BigDecimal#compareTo(BigDecimal)}.</p>
         */
        // COBOL substitution: TAMT00n PIC X(12) / TRAN-AMT PIC S9(09)V99 -> BigDecimal scale 2, signed (NEVER float/double)
        @Digits(integer = 9, fraction = 2, message = "Amount must have at most 9 integer and 2 fraction digits")
        private BigDecimal amount;

        /**
         * Default no-argument constructor required by the JSON binder (Jackson).
         */
        public TransactionListItem() {
            // Intentionally empty: Jackson instantiates then sets fields via setters.
        }

        /**
         * Returns the row selection indicator ({@code SEL000n}).
         *
         * @return the single-character selection flag, or {@code null} if unset
         */
        public String getSelectionFlag() {
            return selectionFlag;
        }

        /**
         * Sets the row selection indicator ({@code SEL000n}).
         *
         * @param selectionFlag the single-character selection flag to set
         */
        public void setSelectionFlag(String selectionFlag) {
            this.selectionFlag = selectionFlag;
        }

        /**
         * Returns the row transaction identifier ({@code TRNID0n}).
         *
         * @return the sixteen-character transaction id, or {@code null} if unset
         */
        public String getTransactionId() {
            return transactionId;
        }

        /**
         * Sets the row transaction identifier ({@code TRNID0n}).
         *
         * @param transactionId the sixteen-character transaction id to set
         */
        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        /**
         * Returns the row transaction date ({@code TDATE0n}).
         *
         * @return the eight-character display date, or {@code null} if unset
         */
        public String getDate() {
            return date;
        }

        /**
         * Sets the row transaction date ({@code TDATE0n}).
         *
         * @param date the eight-character display date to set
         */
        public void setDate(String date) {
            this.date = date;
        }

        /**
         * Returns the row (truncated) description ({@code TDESC0n}).
         *
         * @return the up-to-twenty-six-character description, or {@code null} if unset
         */
        public String getDescription() {
            return description;
        }

        /**
         * Sets the row (truncated) description ({@code TDESC0n}).
         *
         * @param description the up-to-twenty-six-character description to set
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * Returns the row signed monetary amount ({@code TAMT00n} / {@code TRAN-AMT}).
         *
         * @return the amount as a {@link BigDecimal} of scale 2, or {@code null} if unset
         */
        public BigDecimal getAmount() {
            return amount;
        }

        /**
         * Sets the row signed monetary amount ({@code TAMT00n} / {@code TRAN-AMT}).
         *
         * <p>The value is stored as supplied; this row type neither rounds nor rescales
         * it. Compare with {@link BigDecimal#compareTo(BigDecimal)} (AAP &sect;0.7.3).</p>
         *
         * @param amount the signed amount to set
         */
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        /**
         * Identity-based equality keyed on {@link #transactionId} &mdash; the
         * transaction id that uniquely identifies a browse row.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code TransactionListItem} with an equal
         *         transaction id
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TransactionListItem that = (TransactionListItem) o;
            return Objects.equals(transactionId, that.transactionId);
        }

        /**
         * Hash code derived from {@link #transactionId}, consistent with
         * {@link #equals(Object)}.
         *
         * @return the hash code of the transaction id
         */
        @Override
        public int hashCode() {
            return Objects.hash(transactionId);
        }

        /**
         * Diagnostic representation of this browse row.
         *
         * @return a human-readable description of this row
         */
        @Override
        public String toString() {
            return "TransactionListItem{"
                    + "selectionFlag='" + selectionFlag + '\''
                    + ", transactionId='" + transactionId + '\''
                    + ", date='" + date + '\''
                    + ", description='" + description + '\''
                    + ", amount=" + amount
                    + '}';
        }
    }

    // ---------------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------------

    /**
     * Identity-based equality keyed on the transaction id ({@link #transactionId}).
     *
     * <p>Two {@code TransactionDto} instances are equal when they are of the exact same
     * class and share the same {@link #transactionId}. The transaction id alone defines
     * the identity of the record this DTO represents; the mutable attributes (amount,
     * description, merchant block, dates) and the transient screen members
     * ({@link #transactionIdFilter}, {@link #accountId}, {@link #confirm},
     * {@link #pageNumber}, {@link #transactions}) are deliberately excluded so equality
     * stays stable across edits and is independent of which screen shape populated the
     * instance. Note that two not-yet-identified instances (both with a {@code null}
     * {@code transactionId} &mdash; as on the add screen before the id is generated)
     * compare equal.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionDto} with an equal
     *         transaction id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionDto that = (TransactionDto) o;
        return Objects.equals(transactionId, that.transactionId);
    }

    /**
     * Hash code derived solely from {@link #transactionId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the transaction id
     */
    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }

    /**
     * Diagnostic representation of this DTO.
     *
     * <p>For safety the sixteen-digit card number (a PCI-sensitive primary account
     * number) is <strong>masked</strong> &mdash; shown only as present ({@code "****"})
     * or absent ({@code "null"}) &mdash; consistent with the security-conscious
     * {@code toString} convention used across the model layer. The list-view rows are
     * summarized by their count rather than enumerated.</p>
     *
     * @return a human-readable, non-sensitive description of this transaction DTO
     */
    @Override
    public String toString() {
        return "TransactionDto{"
                + "transactionId='" + transactionId + '\''
                + ", transactionIdFilter='" + transactionIdFilter + '\''
                + ", accountId='" + accountId + '\''
                + ", cardNumber=" + (cardNumber == null ? "null" : "****")
                + ", typeCode='" + typeCode + '\''
                + ", categoryCode='" + categoryCode + '\''
                + ", source='" + source + '\''
                + ", description='" + description + '\''
                + ", amount=" + amount
                + ", originationDate=" + originationDate
                + ", processingDate=" + processingDate
                + ", merchantId='" + merchantId + '\''
                + ", merchantName='" + merchantName + '\''
                + ", merchantCity='" + merchantCity + '\''
                + ", merchantZip='" + merchantZip + '\''
                + ", confirm='" + confirm + '\''
                + ", pageNumber='" + pageNumber + '\''
                + ", transactions=" + (transactions == null ? "null" : "[" + transactions.size() + " row(s)]")
                + '}';
    }
}
