package com.cardemo.model.dto;

import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

/**
 * REST request/response payload for the CardDemo <strong>user-administration</strong>
 * screens: <strong>User List</strong> ({@code CU00}), <strong>User Add</strong>
 * ({@code CU01}), <strong>User Update</strong> ({@code CU02}) and
 * <strong>User Delete</strong> ({@code CU03}).
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * four 3270 user-administration maps the legacy system used. On the mainframe those
 * screens were BMS maps {@code COUSR0A} (list), {@code COUSR1A} (add),
 * {@code COUSR2A} (update) and {@code COUSR3A} (delete); their symbolic maps are
 * defined by the copybooks {@code app/cpy-bms/COUSR00.CPY} ({@code 01 COUSR0AI}),
 * {@code app/cpy-bms/COUSR01.CPY} ({@code 01 COUSR1AI}),
 * {@code app/cpy-bms/COUSR02.CPY} ({@code 01 COUSR2AI}) and
 * {@code app/cpy-bms/COUSR03.CPY} ({@code 01 COUSR3AI}). The online programs
 * {@code app/cbl/COUSR00C.cbl} (paginated browse, ten rows per page),
 * {@code app/cbl/COUSR01C.cbl} (add), {@code app/cbl/COUSR02C.cbl} (update) and
 * {@code app/cbl/COUSR03C.cbl} (delete) populated those symbolic structures from the
 * {@code USRSEC} VSAM file. This DTO consolidates all four screens into a single
 * canonical representation.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/UserAdminController}
 * to the CRUD routes under {@code /api/admin/users/*}. Jackson serializes/deserializes
 * the JSON body, Jakarta Bean Validation enforces the structural field constraints
 * declared below (the {@code @Valid} gate on the controller method), and the validated
 * payload is handed to {@code service/admin/UserListService} (browse, from
 * {@code COUSR00C}), {@code service/admin/UserAddService} (add, from {@code COUSR01C}),
 * {@code service/admin/UserUpdateService} (update, from {@code COUSR02C}) or
 * {@code service/admin/UserDeleteService} (delete, from {@code COUSR03C}). This DTO is
 * therefore a pure boundary type &mdash; it carries no business logic, no I/O and no
 * static mutable state.</p>
 *
 * <h2>Key insight &mdash; four screens, one user concept</h2>
 * <p>Add, update, delete and list all share this one shape. A field that an
 * individual operation does not carry is simply left {@code null}: for example the
 * delete screen ({@code COUSR03}) has no password field, so {@link #password} stays
 * {@code null} on a delete; the single-user operations leave the list members
 * ({@link #users}, {@link #pageNumber}, {@link #userIdFilter}) {@code null}; and a
 * list response leaves the single-user members {@code null}. This faithfully mirrors
 * the way the four COBOL programs each used a subset of the user fields.</p>
 *
 * <h2>Scope &mdash; chrome, control bytes and the error line are dropped (AAP &sect;0.4.2)</h2>
 * <p>The four symbolic maps each declared presentation chrome and BMS metadata that
 * have <strong>no</strong> REST equivalent and are deliberately excluded:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}
 *       ({@code X(8)}), {@code CURTIMEI} ({@code X(8)}) and {@code PGMNAMEI}
 *       ({@code X(8)}) &mdash; banner, titles, date/time and program name painted by
 *       the program, never typed by the user.</li>
 *   <li><strong>Error line (output-only):</strong> {@code ERRMSGI} ({@code X(78)})
 *       &mdash; the message the program wrote back on a failed operation; in REST this
 *       becomes the response/error body, not a modeled field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (flag/attribute) and the redefined output bytes
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V} in the {@code COUSRnAO}
 *       output redefinitions &mdash; 3270 datastream metadata with no REST analogue.</li>
 *   <li><strong>AID / PF keys:</strong> the {@code DFHAID} attention identifiers
 *       (ENTER, PF3, PF7/PF8 paging, &hellip;) do not map onto a request body; each AID
 *       maps to a distinct REST endpoint instead (AAP &sect;0.4.2).</li>
 * </ul>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code SEND}/{@code RECEIVE MAP} &rarr; REST DTO.</strong> The CICS
 *       map I/O that populated the {@code COUSRnAI} symbolic structures is replaced by
 *       Jackson binding a JSON body to this object.</li>
 *   <li><strong>Byte-faithful field lengths (AAP &sect;0.7.2).</strong> Each textual
 *       field carries a {@link Size @Size(max = n)} equal to its COBOL {@code PIC X(n)}
 *       width, preserving the external-interface width exactly. Note the user names are
 *       {@code PIC X(20)} &mdash; <strong>distinct</strong> from the customer names
 *       ({@code PIC X(25)}) modeled elsewhere; the two must not be conflated.</li>
 *   <li><strong>{@code USRTYPE}/{@code UTYPE} {@code PIC X(01)} &rarr; {@link UserType}
 *       enum.</strong> The 1-byte user-type field (condition names
 *       {@code 88 ... ADMIN VALUE 'A'} / {@code 88 ... USER VALUE 'U'}) maps to the
 *       shared {@link UserType} enum (constants {@code ADMIN('A')} / {@code USER('U')})
 *       rather than a raw {@code String}, giving type-safe role handling while
 *       preserving the byte-exact 'A'/'U' codes via {@link UserType#getCode()}. By
 *       default Jackson serializes it as the enum constant name
 *       ({@code "ADMIN"} / {@code "USER"}).</li>
 *   <li><strong>Plaintext {@code USRSEC} password &rarr; BCrypt in the service.</strong>
 *       The single permitted behavioral change (constraint C-003, AAP &sect;0.7.2) is
 *       hashing the stored credential with BCrypt; that hashing happens downstream in
 *       {@code UserAddService}/{@code UserUpdateService}, <strong>not</strong> in this
 *       DTO. To keep the secret from leaking back out, {@link #password} is annotated
 *       {@link JsonProperty @JsonProperty(access = WRITE_ONLY)} so Jackson accepts it on
 *       an inbound add/update request but <strong>never serializes it</strong> into any
 *       response body, and {@link #toString()} masks it. The password must never be
 *       logged.</li>
 *   <li><strong>No floating point (AAP &sect;0.7.3).</strong> The user-administration
 *       screens carry no monetary or decimal data, so there is no {@code float},
 *       {@code double} or {@link java.math.BigDecimal} anywhere in this DTO.</li>
 * </ul>
 *
 * <h2>Pagination &mdash; ten rows per page (COUSR00)</h2>
 * <p>The legacy list map painted <strong>ten</strong> user rows per page (see
 * {@link #ROWS_PER_PAGE}). That page size is preserved: the browse page is modeled as a
 * {@link List} of {@link UserListItem} rows ({@link #users}), and the next/previous-page
 * arithmetic is the responsibility of {@code service/admin/UserListService}.</p>
 *
 * <h2>Operation-scoped validation (AAP &sect;0.4.2)</h2>
 * <p>The field-level edits the COBOL programs enforce differ by operation, so this single
 * shared shape carries two Bean Validation group tokens, {@link OnAdd} and
 * {@link OnUpdate}, and the endpoints activate the matching one via
 * {@code @Validated(UserSecurityDto.OnAdd.class)} /
 * {@code @Validated(UserSecurityDto.OnUpdate.class)}.</p>
 * <ul>
 *   <li><strong>{@link #userId}</strong> is mandatory only when <em>adding</em> a user
 *       (COUSR01C); on update and delete it is supplied by the path variable and on a
 *       list response it is absent. Its {@link NotBlank @NotBlank} guard is therefore
 *       bound to {@link OnAdd} only.</li>
 *   <li><strong>{@link #firstName}, {@link #lastName} and {@link #password}</strong>
 *       ({@link NotBlank @NotBlank}) plus <strong>{@link #userType}</strong>
 *       ({@link NotNull @NotNull}) are bound to {@link OnAdd}/{@link OnUpdate}, faithfully
 *       reproducing the COUSR01C (add) and COUSR02C (update) edits that rejected an empty
 *       first name, last name, password or user type. The delete screen ({@code COUSR03})
 *       carries none of these, so delete validates under the default group.</li>
 * </ul>
 * <p>The always-on {@link Size @Size} width constraints apply to every operation.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see com.cardemo.model.enums.UserType
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.NotBlank
 * @see com.fasterxml.jackson.annotation.JsonProperty
 */
public class UserSecurityDto {

    /**
     * The number of user rows the {@code COUSR00} browse painted per page (ten).
     *
     * <p>Preserves the legacy list page size exactly. The list-view {@link #users}
     * collection holds at most this many {@link UserListItem} rows for a single page;
     * the page-turning arithmetic lives in {@code service/admin/UserListService}.</p>
     */
    public static final int ROWS_PER_PAGE = 10;

    /**
     * Jakarta Bean Validation group marking constraints that apply only when
     * <strong>adding</strong> a user.
     *
     * <p>This pure marker interface (it declares no members) lets the otherwise shared
     * DTO carry a constraint &mdash; the {@link NotBlank @NotBlank} guard on
     * {@link UserSecurityDto#userId} &mdash; that must fire only for the add operation.
     * The add endpoint requests this group via
     * {@code @Validated(UserSecurityDto.OnAdd.class)}; update, delete and list validate
     * with the default group, under which the user id may legally be absent. This keeps
     * the four user-administration operations on one shape without an add-only rule
     * leaking into the others.</p>
     */
    public interface OnAdd {
        // Marker only: declares no members. Used solely as a Bean Validation group token.
    }

    /**
     * Jakarta Bean Validation group marking constraints that apply only when
     * <strong>updating</strong> a user.
     *
     * <p>This pure marker interface (it declares no members) lets the shared DTO carry
     * the field-level edits the update screen ({@code COUSR02}) enforces &mdash; a
     * non-blank {@link UserSecurityDto#firstName}, {@link UserSecurityDto#lastName} and
     * {@link UserSecurityDto#password}, and a non-null {@link UserSecurityDto#userType}.
     * The update endpoint requests this group via
     * {@code @Validated(UserSecurityDto.OnUpdate.class)}. The user id on update is taken
     * from the request path (not the body), so the add-only {@link OnAdd} guard on
     * {@link UserSecurityDto#userId} is deliberately <em>not</em> part of this group.</p>
     */
    public interface OnUpdate {
        // Marker only: declares no members. Used solely as a Bean Validation group token.
    }

    // ---------------------------------------------------------------------
    // Single-user fields -- add (COUSR01), update (COUSR02), delete (COUSR03)
    // ---------------------------------------------------------------------

    /**
     * The user id of the user being added, updated or deleted.
     *
     * <p>Migrated from {@code USERID PIC X(8)} on the add screen ({@code COUSR01}) and
     * {@code USRIDIN PIC X(8)} on the update/delete screens
     * ({@code COUSR02}/{@code COUSR03}) &mdash; a fixed-length, eight-character
     * alphanumeric {@code USRSEC} key. {@link Size @Size(max = 8)} preserves the
     * original field width byte-for-byte (AAP &sect;0.7.2) for every operation, while
     * {@link NotBlank @NotBlank} reproduces the COBOL edit that rejected an empty user
     * id on <em>add</em> &mdash; it is bound to the {@link OnAdd} group so update and
     * delete (which take the id from the path) and list responses (which omit it) are
     * not rejected.</p>
     */
    // USERID (COUSR01) / USRIDIN (COUSR02/03) PIC X(8) -> fixed-length 8-char USRSEC key -> String(8)
    // @NotBlank is scoped to the OnAdd group: mandatory on add, optional otherwise.
    @NotBlank(groups = OnAdd.class, message = "User ID must be supplied")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * The user's first name.
     *
     * <p>Migrated from {@code FNAME PIC X(20)} &mdash; a fixed-length, twenty-character
     * field. {@link Size @Size(max = 20)} preserves the original width. This is the
     * <strong>user</strong> name width {@code X(20)}, deliberately distinct from the
     * customer name width {@code X(25)} used elsewhere. {@link NotBlank @NotBlank}
     * (bound to {@link OnAdd}/{@link OnUpdate}) reproduces the COUSR01C/COUSR02C edit
     * that rejected an empty first name on add and update.</p>
     */
    // FNAME PIC X(20) -> 20-char first name -> String(20) (user name X(20), NOT customer X(25))
    // @NotBlank({OnAdd,OnUpdate}) reproduces the COUSR01C (add) + COUSR02C (update) empty-field edit.
    @NotBlank(groups = {OnAdd.class, OnUpdate.class}, message = "First name must be supplied")
    @Size(max = 20, message = "First name must not exceed 20 characters")
    private String firstName;

    /**
     * The user's last name.
     *
     * <p>Migrated from {@code LNAME PIC X(20)} &mdash; a fixed-length, twenty-character
     * field. {@link Size @Size(max = 20)} preserves the original width. This is the
     * <strong>user</strong> name width {@code X(20)}, deliberately distinct from the
     * customer name width {@code X(25)} used elsewhere. {@link NotBlank @NotBlank}
     * (bound to {@link OnAdd}/{@link OnUpdate}) reproduces the COUSR01C/COUSR02C edit
     * that rejected an empty last name on add and update.</p>
     */
    // LNAME PIC X(20) -> 20-char last name -> String(20) (user name X(20), NOT customer X(25))
    // @NotBlank({OnAdd,OnUpdate}) reproduces the COUSR01C (add) + COUSR02C (update) empty-field edit.
    @NotBlank(groups = {OnAdd.class, OnUpdate.class}, message = "Last name must be supplied")
    @Size(max = 20, message = "Last name must not exceed 20 characters")
    private String lastName;

    /**
     * The user's password (write-only).
     *
     * <p>Migrated from {@code PASSWD PIC X(8)} on the add screen ({@code COUSR01}) and
     * the update screen ({@code COUSR02}) &mdash; a fixed-length, eight-character
     * field. {@link Size @Size(max = 8)} preserves the original width, and
     * {@link NotBlank @NotBlank} (bound to {@link OnAdd}/{@link OnUpdate}) reproduces the
     * COUSR01C/COUSR02C edit that rejected an empty password on add and update. The
     * delete screen ({@code COUSR03}) carries no password, so on a delete this field
     * simply stays {@code null} (delete validates under the default group).</p>
     *
     * <p><strong>Security (single permitted behavioral change, AAP &sect;0.7.2).</strong>
     * Annotated {@link JsonProperty @JsonProperty(access = WRITE_ONLY)} so it can be
     * supplied on an inbound add/update request but is <strong>never</strong> serialized
     * into any response. The plaintext value is consumed only by
     * {@code UserAddService}/{@code UserUpdateService}, which hash it with BCrypt before
     * persisting; the hashing happens in the service, <strong>not</strong> in this DTO.
     * It must never be logged; {@link #toString()} masks it.</p>
     */
    // COBOL substitution: PASSWD PIC X(8) plaintext USRSEC password -> String(8), but the
    //  stored credential is BCrypt-hashed in UserAddService/UserUpdateService (the single
    //  permitted behavioral change, C-003 / §0.7.2), NOT here. WRITE_ONLY: accepted on
    //  deserialization, NEVER serialized into a response; masked in toString(); never logged.
    // @NotBlank({OnAdd,OnUpdate}) reproduces the COUSR01C (add) + COUSR02C (update) edit that
    //  rejected an empty password; delete (COUSR03, no password) uses the default group, unaffected.
    @NotBlank(groups = {OnAdd.class, OnUpdate.class}, message = "Password must be supplied")
    @Size(max = 8, message = "Password must not exceed 8 characters")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * The user's role (type).
     *
     * <p>Migrated from {@code USRTYPE PIC X(01)} on the add/update screens
     * ({@code COUSR01}/{@code COUSR02}) with condition names {@code 88 ... ADMIN VALUE
     * 'A'} and {@code 88 ... USER VALUE 'U'}. The legacy programs branched on these
     * 88-levels to distinguish administrators from regular users; the same value is
     * conveyed here type-safely. {@link NotNull @NotNull} (bound to
     * {@link OnAdd}/{@link OnUpdate}) reproduces the COUSR01C/COUSR02C edit that rejected
     * an empty user type on add and update.</p>
     */
    // COBOL substitution: USRTYPE PIC X(01) (88 'A'=ADMIN / 'U'=USER)
    //  -> type-safe com.cardemo.model.enums.UserType enum (NOT a raw String / local enum).
    // @NotNull({OnAdd,OnUpdate}) reproduces the COUSR01C (add) + COUSR02C (update) empty-field edit;
    //  @NotNull (not @NotBlank) because the field is a typed enum, not a String.
    @NotNull(groups = {OnAdd.class, OnUpdate.class}, message = "User type must be supplied")
    private UserType userType;

    // ---------------------------------------------------------------------
    // List-view fields -- paginated browse (COUSR00), ten rows per page
    // ---------------------------------------------------------------------

    /**
     * The current browse page number.
     *
     * <p>Migrated from {@code PAGENUM PIC X(8)}: the page indicator the {@code COUSR00}
     * screen displayed. Kept as a fixed-width {@link String} &mdash; consistent with the
     * byte-faithful treatment of every other screen field on this DTO &mdash; so the
     * exact {@code X(8)} width is preserved; {@link Size @Size(max = 8)} preserves the
     * width. The ten-rows-per-page semantics ({@link #ROWS_PER_PAGE}) and the
     * next/previous-page arithmetic are the responsibility of
     * {@code service/admin/UserListService}.</p>
     */
    // PAGENUM PIC X(8) -> page indicator -> String(8) (byte-faithful; pagination logic lives in the service)
    @Size(max = 8, message = "Page number must not exceed 8 characters")
    private String pageNumber;

    /**
     * The starting user-id filter for the browse.
     *
     * <p>Migrated from {@code USRIDIN PIC X(8)} on the list screen ({@code COUSR00}):
     * the eight-character user id the operator typed to position (start) the browse.
     * {@link Size @Size(max = 8)} preserves the original field width. This is distinct
     * from the single-user {@link #userId} (the user being added/updated/deleted); on a
     * list request only this filter is populated.</p>
     */
    // USRIDIN PIC X(8) (COUSR00) -> starting user-id filter for the browse -> String(8)
    @Size(max = 8, message = "User ID filter must not exceed 8 characters")
    private String userIdFilter;

    /**
     * The user rows of the current browse page (at most {@link #ROWS_PER_PAGE} = 10).
     *
     * <p>Migrated from the ten repeating row groups of {@code COUSR00}
     * ({@code SEL000{1-10}}/{@code USRID0{1-10}}/{@code FNAME0{1-10}}/
     * {@code LNAME0{1-10}}/{@code UTYPE0{1-10}}). Rather than reproduce ten flat field
     * sets, the migrated contract models <strong>one</strong> row type and exposes the
     * page as a {@link List} of those rows. The list is annotated {@link Valid @Valid}
     * so Bean Validation cascades into every row, enforcing each row's byte-faithful
     * field widths.</p>
     */
    // COBOL substitution: COUSR00's 10 repeating symbolic rows (SEL/USRID/FNAME/LNAME/UTYPE 1..10)
    // collapse into one List<UserListItem> of at most ROWS_PER_PAGE (10) elements; @Valid cascades.
    @Valid
    private List<UserListItem> users;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson) to
     * instantiate this DTO reflectively before populating its properties via setters.
     */
    public UserSecurityDto() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    // ---------------------------------------------------------------------
    // Accessors -- single-user fields
    // ---------------------------------------------------------------------

    /**
     * Returns the single-user user id ({@code USERID}/{@code USRIDIN}).
     *
     * @return the up-to-eight-character user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the single-user user id ({@code USERID}/{@code USRIDIN}).
     *
     * @param userId the user id to set (up to 8 characters)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user's first name ({@code FNAME}).
     *
     * @return the up-to-twenty-character first name, or {@code null} if unset
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the user's first name ({@code FNAME}).
     *
     * @param firstName the first name to set (up to 20 characters)
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the user's last name ({@code LNAME}).
     *
     * @return the up-to-twenty-character last name, or {@code null} if unset
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the user's last name ({@code LNAME}).
     *
     * @param lastName the last name to set (up to 20 characters)
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the user's write-only password ({@code PASSWD}).
     *
     * <p>The raw plaintext value is returned for the sole use of
     * {@code UserAddService}/{@code UserUpdateService}, which hash it with BCrypt;
     * callers must never log it.</p>
     *
     * @return the password, or {@code null} if unset (always {@code null} on delete)
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the user's password ({@code PASSWD}).
     *
     * @param password the plaintext password to set (up to 8 characters); hashed
     *                 downstream by the service, never by this DTO
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the user's role ({@code USRTYPE}).
     *
     * @return the {@link UserType}, or {@code null} if unset
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the user's role ({@code USRTYPE}).
     *
     * @param userType the {@link UserType} to set
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    // ---------------------------------------------------------------------
    // Accessors -- list-view fields
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
     * @param pageNumber the page indicator to set (up to 8 characters)
     */
    public void setPageNumber(String pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * Returns the starting user-id filter for the browse ({@code USRIDIN} on
     * {@code COUSR00}).
     *
     * @return the up-to-eight-character user-id filter, or {@code null} if unset
     */
    public String getUserIdFilter() {
        return userIdFilter;
    }

    /**
     * Sets the starting user-id filter for the browse ({@code USRIDIN} on
     * {@code COUSR00}).
     *
     * @param userIdFilter the user-id filter to set (up to 8 characters)
     */
    public void setUserIdFilter(String userIdFilter) {
        this.userIdFilter = userIdFilter;
    }

    /**
     * Returns the user rows of the current browse page.
     *
     * @return the list of {@link UserListItem} rows (at most {@link #ROWS_PER_PAGE}),
     *         or {@code null} if unset
     */
    public List<UserListItem> getUsers() {
        return users;
    }

    /**
     * Sets the user rows of the current browse page.
     *
     * @param users the list of {@link UserListItem} rows to set (at most
     *              {@link #ROWS_PER_PAGE})
     */
    public void setUsers(List<UserListItem> users) {
        this.users = users;
    }

    // ---------------------------------------------------------------------
    // Object contract
    // ---------------------------------------------------------------------

    /**
     * Identity-based equality keyed on the user id ({@link #userId}).
     *
     * <p>Two {@code UserSecurityDto} instances are equal when they are of the exact
     * same class and share the same {@link #userId}. The user id alone defines the
     * identity of the {@code USRSEC} record this DTO represents; the mutable attributes
     * (names, password, type) and the transient list-view members
     * ({@link #pageNumber}, {@link #userIdFilter}, {@link #users}) are deliberately
     * excluded so equality stays stable across edits and is independent of which screen
     * shape populated the instance. Note that two not-yet-identified instances (both
     * with a {@code null} {@code userId}) compare equal.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code UserSecurityDto} with an equal user
     *         id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserSecurityDto that = (UserSecurityDto) o;
        return Objects.equals(userId, that.userId);
    }

    /**
     * Hash code derived solely from {@link #userId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the user id
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    /**
     * Diagnostic representation of this DTO.
     *
     * <p>For safety the {@link #password} is <strong>masked</strong> &mdash; shown only
     * as present ({@code "****"}) or absent ({@code "null"}), never its length or
     * content &mdash; consistent with the security-conscious {@code toString} convention
     * used across the model layer. The list-view rows are summarized by their count
     * rather than enumerated, so embedded row data is never emitted into logs either.</p>
     *
     * @return a human-readable, password-safe description of this user DTO
     */
    @Override
    public String toString() {
        return "UserSecurityDto{"
                + "userId='" + userId + '\''
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", password=" + (password == null ? "null" : "****")
                + ", userType=" + userType
                + ", pageNumber='" + pageNumber + '\''
                + ", userIdFilter='" + userIdFilter + '\''
                + ", users=" + (users == null ? "null" : "[" + users.size() + " row(s)]")
                + '}';
    }

    // =====================================================================
    // Nested row type for the paginated user-list browse (COUSR00)
    // =====================================================================

    /**
     * A single row of the {@code COUSR00} user-list browse.
     *
     * <p>The legacy list map carried ten identical row groups
     * ({@code SEL000{1-10}}, {@code USRID0{1-10}}, {@code FNAME0{1-10}},
     * {@code LNAME0{1-10}}, {@code UTYPE0{1-10}}). Rather than reproduce ten flat field
     * sets, the migrated contract models <strong>one</strong> row type and exposes the
     * page as a {@link java.util.List} of these rows ({@link UserSecurityDto#users}),
     * capped at {@link UserSecurityDto#ROWS_PER_PAGE} (ten) elements per page.</p>
     *
     * <p>Only the five data members per row are modeled. The legacy per-row length
     * ({@code ...L}), flag/attribute ({@code ...F}/{@code ...A}) and output control
     * bytes ({@code ...C}/{@code ...P}/{@code ...H}/{@code ...V}) are 3270 datastream
     * metadata, not user data, and are therefore omitted (AAP &sect;0.4.2). No password
     * is carried on a browse row &mdash; the list never exposes credentials.</p>
     */
    public static class UserListItem {

        /**
         * The row selection indicator.
         *
         * <p>Migrated from {@code SEL000n PIC X(1)}: the single character the operator
         * typed beside a row to select it (for example {@code "U"} to update or
         * {@code "D"} to delete) or a space when unselected. Modeled as a {@link String}
         * of length one.</p>
         */
        // SEL000n PIC X(1) -> single-character row select ("U"/"D"/space) -> String(1)
        @Size(max = 1, message = "Selection flag must be a single character")
        private String selectionFlag;

        /**
         * The user id shown on the row.
         *
         * <p>Migrated from {@code USRID0n PIC X(8)}: an eight-character {@code USRSEC}
         * user id, kept as a fixed-width {@link String} so the width is preserved.</p>
         */
        // USRID0n PIC X(8) -> 8-char USRSEC user id -> String(8)
        @Size(max = 8, message = "User ID must not exceed 8 characters")
        private String userId;

        /**
         * The first name shown on the row.
         *
         * <p>Migrated from {@code FNAME0n PIC X(20)}: a twenty-character first name, kept
         * as a fixed-width {@link String} so the width is preserved. This is the
         * <strong>user</strong> name width {@code X(20)}, distinct from the customer
         * name width {@code X(25)} used elsewhere.</p>
         */
        // FNAME0n PIC X(20) -> 20-char first name -> String(20) (user name X(20), NOT customer X(25))
        @Size(max = 20, message = "First name must not exceed 20 characters")
        private String firstName;

        /**
         * The last name shown on the row.
         *
         * <p>Migrated from {@code LNAME0n PIC X(20)}: a twenty-character last name, kept
         * as a fixed-width {@link String} so the width is preserved. This is the
         * <strong>user</strong> name width {@code X(20)}, distinct from the customer
         * name width {@code X(25)} used elsewhere.</p>
         */
        // LNAME0n PIC X(20) -> 20-char last name -> String(20) (user name X(20), NOT customer X(25))
        @Size(max = 20, message = "Last name must not exceed 20 characters")
        private String lastName;

        /**
         * The user's role (type) shown on the row.
         *
         * <p>Migrated from {@code UTYPE0n PIC X(01)} with condition names
         * {@code 88 ... ADMIN VALUE 'A'} and {@code 88 ... USER VALUE 'U'}; mapped to the
         * shared {@link UserType} enum, preserving the byte-exact 'A'/'U' codes.</p>
         */
        // COBOL substitution: UTYPE0n PIC X(01) (88 'A'=ADMIN / 'U'=USER)
        //  -> type-safe com.cardemo.model.enums.UserType enum (NOT a raw String / local enum).
        private UserType userType;

        /**
         * Default no-argument constructor required by the JSON binder (Jackson).
         */
        public UserListItem() {
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
         * Returns the row user id ({@code USRID0n}).
         *
         * @return the eight-character user id, or {@code null} if unset
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Sets the row user id ({@code USRID0n}).
         *
         * @param userId the eight-character user id to set
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * Returns the row first name ({@code FNAME0n}).
         *
         * @return the twenty-character first name, or {@code null} if unset
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * Sets the row first name ({@code FNAME0n}).
         *
         * @param firstName the twenty-character first name to set
         */
        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        /**
         * Returns the row last name ({@code LNAME0n}).
         *
         * @return the twenty-character last name, or {@code null} if unset
         */
        public String getLastName() {
            return lastName;
        }

        /**
         * Sets the row last name ({@code LNAME0n}).
         *
         * @param lastName the twenty-character last name to set
         */
        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        /**
         * Returns the row user type ({@code UTYPE0n}).
         *
         * @return the {@link UserType}, or {@code null} if unset
         */
        public UserType getUserType() {
            return userType;
        }

        /**
         * Sets the row user type ({@code UTYPE0n}).
         *
         * @param userType the {@link UserType} to set
         */
        public void setUserType(UserType userType) {
            this.userType = userType;
        }

        /**
         * Identity-based equality keyed on the row {@link #userId} &mdash; the
         * {@code USRSEC} key that uniquely identifies a browse row.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code UserListItem} with an equal user
         *         id
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            UserListItem that = (UserListItem) o;
            return Objects.equals(userId, that.userId);
        }

        /**
         * Hash code derived from the row {@link #userId}, consistent with
         * {@link #equals(Object)}.
         *
         * @return the hash code of the row user id
         */
        @Override
        public int hashCode() {
            return Objects.hash(userId);
        }

        /**
         * Diagnostic representation of this browse row. The row carries no credential,
         * so there is nothing sensitive to mask.
         *
         * @return a human-readable description of this row
         */
        @Override
        public String toString() {
            return "UserListItem{"
                    + "selectionFlag='" + selectionFlag + '\''
                    + ", userId='" + userId + '\''
                    + ", firstName='" + firstName + '\''
                    + ", lastName='" + lastName + '\''
                    + ", userType=" + userType
                    + '}';
        }
    }
}
