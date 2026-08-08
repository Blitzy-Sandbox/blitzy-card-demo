package com.vsergeychik.carddemo.user.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 80-byte {@code USRSEC} security-user record, the Java projection of
 * {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <p>Transcribed from revision {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}, the
 * version footer the copybook itself carries at line 25 and the revision this whole migration is
 * anchored to. This is a <strong>like-for-like</strong> translation: the byte layout is the contract
 * and nothing here has been "improved".
 *
 * <h2>The layout, which IS the contract</h2>
 * Six items, every one of them {@code PIC X}. The copybook declares no {@code COMP}, no
 * {@code COMP-3}, no {@code 9(n)}, no {@code S9}, no {@code V9}, no {@code OCCURS} and no
 * {@code REDEFINES} - verified by exhaustive grep - so this record has no numeric field, no scale,
 * no sign overpunch, no table indexing and no overlay. That is why nothing in this file touches
 * {@code BigDecimal}, and why the decimal seam is deliberately not imported: there is no scaled
 * field for it to act on, and importing it here would misdirect the next reader.
 *
 * <table border="1">
 *   <caption>{@code app/cpy/CSUSR01Y.cpy} lines 17-23</caption>
 *   <tr><th>COBOL item</th><th>PICTURE</th><th>Offset</th><th>Length</th><th>Java accessor</th></tr>
 *   <tr><td>{@code SEC-USR-ID}</td><td>{@code X(08)}</td><td>0</td><td>8</td>
 *       <td>{@link #secUsrId()}</td></tr>
 *   <tr><td>{@code SEC-USR-FNAME}</td><td>{@code X(20)}</td><td>8</td><td>20</td>
 *       <td>{@link #secUsrFname()}</td></tr>
 *   <tr><td>{@code SEC-USR-LNAME}</td><td>{@code X(20)}</td><td>28</td><td>20</td>
 *       <td>{@link #secUsrLname()}</td></tr>
 *   <tr><td>{@code SEC-USR-PWD}</td><td>{@code X(08)}</td><td>48</td><td>8</td>
 *       <td>{@link #secUsrPwd()}</td></tr>
 *   <tr><td>{@code SEC-USR-TYPE}</td><td>{@code X(01)}</td><td>56</td><td>1</td>
 *       <td>{@link #secUsrType()}</td></tr>
 *   <tr><td>{@code SEC-USR-FILLER}</td><td>{@code X(23)}</td><td>57</td><td>23</td>
 *       <td>{@link #secUsrFiller()}</td></tr>
 *   <tr><td><strong>total</strong></td><td></td><td></td><td><strong>80</strong></td><td></td></tr>
 * </table>
 *
 * <p>The 80-byte width is proven three independent ways rather than asserted once: the copybook
 * offsets sum to 80; {@code app/jcl/DUSRSECJ.jcl} declares {@code RECORDSIZE(80,80)} and
 * {@code DCB=(LRECL=80,RECFM=FB,...)}; and the {@code USRSEC} sequential dataset under
 * {@code app/data/EBCDIC} is exactly 800 bytes for its ten records. {@link #LAYOUT} then has the arithmetic checked by machine
 * at class-initialisation time - see <em>The total-width self-check</em> below - so the figure in
 * this comment can never silently drift away from the code.
 *
 * <h2>{@code SEC-USER-DATA} versus {@code SEC-USR-*}: an asymmetry preserved on purpose</h2>
 * The copybook's group item is spelled {@code SEC-USER-DATA} - {@code USER} in full - while every
 * one of its six subordinate items is spelled {@code SEC-USR-} - {@code USR} contracted. Both
 * spellings are reproduced exactly as the copybook has them, in {@link #GROUP_NAME} and in the
 * {@code FIELD_SEC_USR_*} constants. The inconsistency is <strong>not</strong> tidied up, because
 * the parity differ compares field by field <em>by name</em>: a nicer name here would silently stop
 * matching the oracle and make a real difference invisible. The same rule that keeps
 * {@code ACCT-EXPIRAION-DATE} misspelled in the account record keeps this asymmetry intact.
 *
 * <h2>{@code SEC-USR-FILLER} is a named field, not an anonymous gap</h2>
 * The copybook declares {@code 05 SEC-USR-FILLER PIC X(23).}, which is an ordinary <em>named</em>
 * data item whose name merely happens to contain the word {@code FILLER}. It is not COBOL's reserved
 * anonymous {@code 05 FILLER PIC X(23).}. The distinction is load-bearing, so this field is declared
 * through {@link FieldSpan#alphanumeric(String, int, int)} under its real name rather than through
 * {@link FieldSpan#filler(int, int)}:
 * <ul>
 *   <li>a genuinely anonymous span is not a referable COBOL name, so it is excluded from layout
 *       name lookup and from the codec's field-image map - which would hide these 23 bytes from the
 *       parity differ;</li>
 *   <li>declared as the named alphanumeric item it actually is, {@code SEC-USR-FILLER} resolves
 *       through {@code LAYOUT.span(...)}, appears in {@link #fieldImages()}, and is addressable by
 *       {@link #secUsrFiller()}.</li>
 * </ul>
 * The emitted bytes are identical either way - both categories pad with the charset's space byte -
 * so choosing the named form costs nothing and buys full visibility.
 *
 * <p>No program reads or assigns {@code SEC-USR-FILLER}: a grep across all 28 COBOL programs returns
 * zero references. It is nonetheless retained in full, because dead code is preserved rather than
 * cleaned up, and because dropping it would make the record 57 bytes instead of 80 and shift every
 * subsequent byte of the dataset. It defaults to 23 spaces, which is exactly what the legacy data
 * holds: {@code app/cbl/COUSR01C.cbl} lines 154-158 move only the five named fields and then write
 * all 80 bytes, and no {@code INITIALIZE SEC-USER-DATA} or {@code MOVE SPACES TO SEC-USER-DATA}
 * exists anywhere in the codebase. Decoding the {@code USRSEC} EBCDIC dataset confirms the outcome:
 * every one of its ten records carries exactly 23 trailing spaces.
 *
 * <h2>{@code PIC X} semantics: padded on write, and never trimmed on read</h2>
 * Every accessor returns the <strong>full, space-padded, declared-width</strong> value.
 * {@link #secUsrLname()} on the seeded {@code ADMIN001} record returns {@code "GOLD"} followed by
 * sixteen spaces, a string of length exactly 20 - never the four-character {@code "GOLD"}.
 *
 * <p>This is a hard parity requirement, not a stylistic choice. {@code app/cpy-bms/COUSR02.CPY}
 * declares its screen input fields at {@code FNAMEI PIC X(20)}, {@code LNAMEI PIC X(20)},
 * {@code PASSWDI PIC X(8)} and {@code USRTYPEI PIC X(1)} - identical widths to the record's own
 * fields - so the four change tests at {@code app/cbl/COUSR02C.cbl} lines 219-234, of the form
 * {@code IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME}, are <em>equal-length</em> byte comparisons. An
 * accessor that trimmed would make a padded screen field compare unequal to an unpadded record
 * field, flipping the {@code USR-MODIFIED} outcome and writing a spurious update. The defect would
 * be silent and would surface only as a parity diff far from its cause.
 *
 * <p>Trimming that the COBOL genuinely does perform belongs to the caller, not here. The
 * {@code STRING ... DELIMITED BY SPACE} that builds the user-added confirmation message, and the
 * {@code FUNCTION UPPER-CASE} normalisation applied to a sign-on user id and password, are the
 * concern of the controller and the sign-on service respectively. <strong>This type neither
 * normalises nor trims nor upper-cases.</strong> It is a passive fixed-width value holder.
 *
 * <h2>The password is plaintext, and stays that way</h2>
 * {@code SEC-USR-PWD PIC X(08)} is stored and compared in plaintext, exactly as
 * {@code app/cbl/COSGN00C.cbl} line 223 does it with {@code IF SEC-USR-PWD = WS-USER-PWD} against a
 * {@code WS-USER-PWD PIC X(08)} of matching width. It is therefore exposed here as a plain
 * fixed-width {@code String}.
 *
 * <p>The posture is deliberately left as the legacy design has it - neither strengthened nor
 * weakened. It is <strong>not</strong> strengthened: there is no hashing, no salting, no encoder and
 * no digest, because introducing one would change observable behaviour and pull in a security
 * framework that is out of scope. It is also <strong>not</strong> weakened: the password is excluded
 * from {@link #toString()} so it cannot leak into a log line, an exception message or a diagnostic
 * dump. That exclusion follows the source's own handling rather than departing from it - the
 * password is never projected onto the user list screen, and the delete screen has no password field
 * at all - while {@link #equals(Object)} and {@link #hashCode()} still cover all six fields, because
 * the password is part of the record's identity even though it is not part of its rendering.
 *
 * <h2>The charset is always the caller's, never assumed</h2>
 * Encoding and decoding take an explicit {@link Charset}. Nothing here consults a platform default
 * and nothing hard-codes a code page, because a pad byte is code-page dependent - a space is
 * {@code 0x40} under {@code IBM037} and {@code 0x20} under {@code US-ASCII}. The configuration class
 * that resolves the project's code pages is deliberately not imported: a model must not depend on
 * configuration, so the charset arrives as a parameter from whichever repository, job or test is
 * doing the reading.
 *
 * <h2>The total-width self-check</h2>
 * {@link #LAYOUT} is a {@link RecordLayout}, whose constructor refuses to build unless the declared
 * spans run contiguously from offset 0 with no gap and no overlap and sum to <em>exactly</em>
 * {@link #RECORD_LENGTH}. Because {@link #LAYOUT} is a static final field, that check runs when this
 * class is first initialised, so {@code 8 + 20 + 20 + 8 + 1 + 23 = 80} is machine-verified rather
 * than merely asserted in a comment. Mistyping an offset, or deleting {@code SEC-USR-FILLER}, makes
 * this class fail to initialise at once and names the offending descriptor, instead of quietly
 * emitting a 57-byte record that would shift every following byte in the dataset.
 *
 * <h2>Immutability and thread safety</h2>
 * A {@code record} with six {@code String} components: deeply immutable, freely shareable across
 * threads, and safe to cache. There is no mutable static state - the only static members are
 * {@code final} constants and immutable descriptors - so COBOL {@code WORKING-STORAGE} never becomes
 * shared Java state, and one request's data can never bleed into another's.
 *
 * <h2>Consumers</h2>
 * {@code CSUSR01Y} is copied by twelve programs, but only five touch a {@code SEC-USR-} field:
 * {@code COUSR00C} (52 references), {@code COUSR02C} (17), {@code COUSR01C} (9), {@code COUSR03C}
 * (8) and {@code COSGN00C} (2). The remaining seven copy the layout as an unused
 * {@code WORKING-STORAGE} declaration. This is nonetheless one shared type imported by every
 * consumer and never duplicated per program.
 *
 * @param secUsrId     {@code SEC-USR-ID PIC X(08)} - the 8-byte primary key, at offset 0
 * @param secUsrFname  {@code SEC-USR-FNAME PIC X(20)} - first name, space-padded to 20
 * @param secUsrLname  {@code SEC-USR-LNAME PIC X(20)} - last name, space-padded to 20
 * @param secUsrPwd    {@code SEC-USR-PWD PIC X(08)} - plaintext password, space-padded to 8
 * @param secUsrType   {@code SEC-USR-TYPE PIC X(01)} - user type, {@code 'A'} admin or {@code 'U'}
 *                     regular, as {@code COSGN00C} routes on
 * @param secUsrFiller {@code SEC-USR-FILLER PIC X(23)} - the named trailing span, normally 23 spaces
 * @see FixedWidthCodec
 * @see FixedWidthRecord
 */
public record SecUserRecord(String secUsrId,
                            String secUsrFname,
                            String secUsrLname,
                            String secUsrPwd,
                            String secUsrType,
                            String secUsrFiller) {

    // =============================================================================================
    // Record geometry. Declared here, on the type that owns the copybook, so that the repository
    // never hard-codes a width or a key length of its own.
    // =============================================================================================

    /**
     * The declared record width in bytes, from {@code app/cpy/CSUSR01Y.cpy} and corroborated by
     * {@code RECORDSIZE(80,80)} and {@code LRECL=80} in {@code app/jcl/DUSRSECJ.jcl}.
     */
    public static final int RECORD_LENGTH = 80;

    /** The absolute 0-based offset of the primary key, from {@code KEYS(8,0)}. */
    public static final int KEY_OFFSET = 0;

    /**
     * The primary key width in bytes, from {@code KEYS(8,0)}. This equals
     * {@link #SEC_USR_ID_LENGTH} because the key <em>is</em> {@code SEC-USR-ID}: every CICS call in
     * the four user-maintenance programs passes {@code KEYLENGTH (LENGTH OF SEC-USR-ID)}.
     */
    public static final int KEY_LENGTH = 8;

    // =============================================================================================
    // COBOL names, carried verbatim. The parity differ compares field by field BY NAME, so these
    // strings are part of the migration contract and must never be prettified.
    // =============================================================================================

    /**
     * The copybook's group item name, spelled {@code SEC-USER-DATA} with {@code USER} in full - in
     * contrast to the {@code SEC-USR-} prefix of its subordinate items. Preserved, not corrected.
     */
    public static final String GROUP_NAME = "SEC-USER-DATA";

    /** COBOL name of {@link #secUsrId()}. */
    public static final String FIELD_SEC_USR_ID = "SEC-USR-ID";

    /** COBOL name of {@link #secUsrFname()}. */
    public static final String FIELD_SEC_USR_FNAME = "SEC-USR-FNAME";

    /** COBOL name of {@link #secUsrLname()}. */
    public static final String FIELD_SEC_USR_LNAME = "SEC-USR-LNAME";

    /** COBOL name of {@link #secUsrPwd()}. */
    public static final String FIELD_SEC_USR_PWD = "SEC-USR-PWD";

    /** COBOL name of {@link #secUsrType()}. */
    public static final String FIELD_SEC_USR_TYPE = "SEC-USR-TYPE";

    /**
     * COBOL name of {@link #secUsrFiller()}. A real, referable item name that happens to contain the
     * word {@code FILLER}; it is not COBOL's anonymous {@code FILLER}.
     */
    public static final String FIELD_SEC_USR_FILLER = "SEC-USR-FILLER";

    // =============================================================================================
    // Per-field offsets and lengths, transcribed one line at a time from the copybook so each is
    // reviewable against it by eye.
    // =============================================================================================

    /** Absolute 0-based offset of {@code SEC-USR-ID}. */
    public static final int SEC_USR_ID_OFFSET = 0;

    /** Declared width of {@code SEC-USR-ID PIC X(08)}. */
    public static final int SEC_USR_ID_LENGTH = 8;

    /** Absolute 0-based offset of {@code SEC-USR-FNAME}. */
    public static final int SEC_USR_FNAME_OFFSET = 8;

    /** Declared width of {@code SEC-USR-FNAME PIC X(20)}. */
    public static final int SEC_USR_FNAME_LENGTH = 20;

    /** Absolute 0-based offset of {@code SEC-USR-LNAME}. */
    public static final int SEC_USR_LNAME_OFFSET = 28;

    /** Declared width of {@code SEC-USR-LNAME PIC X(20)}. */
    public static final int SEC_USR_LNAME_LENGTH = 20;

    /** Absolute 0-based offset of {@code SEC-USR-PWD}. */
    public static final int SEC_USR_PWD_OFFSET = 48;

    /** Declared width of {@code SEC-USR-PWD PIC X(08)}. */
    public static final int SEC_USR_PWD_LENGTH = 8;

    /** Absolute 0-based offset of {@code SEC-USR-TYPE}. */
    public static final int SEC_USR_TYPE_OFFSET = 56;

    /** Declared width of {@code SEC-USR-TYPE PIC X(01)}. */
    public static final int SEC_USR_TYPE_LENGTH = 1;

    /** Absolute 0-based offset of {@code SEC-USR-FILLER}. */
    public static final int SEC_USR_FILLER_OFFSET = 57;

    /** Declared width of {@code SEC-USR-FILLER PIC X(23)}. */
    public static final int SEC_USR_FILLER_LENGTH = 23;

    // =============================================================================================
    // Field descriptors and the self-checking layout.
    // =============================================================================================

    /** Descriptor for {@code SEC-USR-ID PIC X(08)} at offset 0. */
    public static final FieldSpan SPAN_SEC_USR_ID =
            FieldSpan.alphanumeric(FIELD_SEC_USR_ID, SEC_USR_ID_OFFSET, SEC_USR_ID_LENGTH);

    /** Descriptor for {@code SEC-USR-FNAME PIC X(20)} at offset 8. */
    public static final FieldSpan SPAN_SEC_USR_FNAME =
            FieldSpan.alphanumeric(FIELD_SEC_USR_FNAME, SEC_USR_FNAME_OFFSET, SEC_USR_FNAME_LENGTH);

    /** Descriptor for {@code SEC-USR-LNAME PIC X(20)} at offset 28. */
    public static final FieldSpan SPAN_SEC_USR_LNAME =
            FieldSpan.alphanumeric(FIELD_SEC_USR_LNAME, SEC_USR_LNAME_OFFSET, SEC_USR_LNAME_LENGTH);

    /** Descriptor for {@code SEC-USR-PWD PIC X(08)} at offset 48. */
    public static final FieldSpan SPAN_SEC_USR_PWD =
            FieldSpan.alphanumeric(FIELD_SEC_USR_PWD, SEC_USR_PWD_OFFSET, SEC_USR_PWD_LENGTH);

    /** Descriptor for {@code SEC-USR-TYPE PIC X(01)} at offset 56. */
    public static final FieldSpan SPAN_SEC_USR_TYPE =
            FieldSpan.alphanumeric(FIELD_SEC_USR_TYPE, SEC_USR_TYPE_OFFSET, SEC_USR_TYPE_LENGTH);

    /**
     * Descriptor for {@code SEC-USR-FILLER PIC X(23)} at offset 57.
     *
     * <p>Declared as a <em>named alphanumeric</em> span rather than an anonymous reserved one,
     * because the copybook gives it a real name. That keeps it resolvable by name, visible to the
     * parity differ and addressable through {@link #secUsrFiller()}, while emitting byte-identical
     * space padding.
     */
    public static final FieldSpan SPAN_SEC_USR_FILLER =
            FieldSpan.alphanumeric(FIELD_SEC_USR_FILLER, SEC_USR_FILLER_OFFSET,
                    SEC_USR_FILLER_LENGTH);

    /**
     * The six descriptors in copybook declaration order, {@code SEC-USR-FILLER} included.
     *
     * <p>Immutable, and the single ordering used by {@link #LAYOUT}, {@link #fieldImages()} and the
     * constructor's width validation, so those three can never disagree about the record's shape.
     */
    public static final List<FieldSpan> SPANS = List.of(
            SPAN_SEC_USR_ID,
            SPAN_SEC_USR_FNAME,
            SPAN_SEC_USR_LNAME,
            SPAN_SEC_USR_PWD,
            SPAN_SEC_USR_TYPE,
            SPAN_SEC_USR_FILLER);

    /**
     * The validated record layout, and the machine-checked proof of this record's geometry.
     *
     * <p>Building it runs the layout self-check: the spans must be contiguous from offset 0, must
     * not overlap, must carry no duplicate referable name, and must sum to exactly
     * {@link #RECORD_LENGTH}. Because this field is static, that check runs at class initialisation,
     * so a mistyped offset or a dropped {@code SEC-USR-FILLER} surfaces immediately as an
     * initialisation failure naming the offending descriptor.
     */
    public static final RecordLayout LAYOUT = new RecordLayout(RECORD_LENGTH, SPANS);

    /** Rendered in place of the password by {@link #toString()}, so it can never leak. */
    private static final String PASSWORD_PLACEHOLDER = "<omitted>";

    // =============================================================================================
    // Construction.
    // =============================================================================================

    /**
     * Validates that every component is present and is <strong>exactly</strong> its declared width,
     * so the class invariant "an accessor always returns the full fixed-width value" holds for every
     * instance however it was built.
     *
     * <p>A wrong width is rejected rather than silently padded or truncated. That mirrors the
     * convention the fixed-width layer already sets, where wrapping stored bytes rejects a length
     * mismatch and widening a short row is a separate, explicitly named act. The reasoning is the
     * same in both places: a silent adjustment here would turn a transcription error into
     * plausible-looking output, which is the most expensive kind of parity defect to trace. Callers
     * holding values of some other width should use
     * {@link #of(String, String, String, String, String, Charset)}, which applies the COBOL
     * alphanumeric {@code MOVE} rule deliberately.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if any component's length differs from its declared width,
     *                                  with the field name, the declared width and the supplied
     *                                  length all named
     */
    public SecUserRecord {
        secUsrId = requireDeclaredWidth(secUsrId, SPAN_SEC_USR_ID);
        secUsrFname = requireDeclaredWidth(secUsrFname, SPAN_SEC_USR_FNAME);
        secUsrLname = requireDeclaredWidth(secUsrLname, SPAN_SEC_USR_LNAME);
        secUsrPwd = requireDeclaredWidth(secUsrPwd, SPAN_SEC_USR_PWD);
        secUsrType = requireDeclaredWidth(secUsrType, SPAN_SEC_USR_TYPE);
        secUsrFiller = requireDeclaredWidth(secUsrFiller, SPAN_SEC_USR_FILLER);
    }

    /**
     * Builds a record from values of any width by applying the COBOL alphanumeric {@code MOVE} rule
     * to each, and sets {@code SEC-USR-FILLER} to its 23 spaces.
     *
     * <p>This is the entry point for the {@code COUSR01C} add path, where lines 154-158 move exactly
     * these five fields from the screen and then write all 80 bytes without ever touching the filler.
     * Each value is put through the codec's alphanumeric move, so a short value is padded on the
     * right with spaces and an over-long one is truncated <strong>on the right</strong> - the
     * direction COBOL uses for a {@code PIC X} receiver, chosen explicitly rather than left to a
     * plain Java assignment that would neither pad nor truncate.
     *
     * @param secUsrId    the user id; padded or right-truncated to 8
     * @param secUsrFname the first name; padded or right-truncated to 20
     * @param secUsrLname the last name; padded or right-truncated to 20
     * @param secUsrPwd   the plaintext password; padded or right-truncated to 8
     * @param secUsrType  the user type; padded or right-truncated to 1
     * @param charset     the code page, supplied explicitly and never defaulted
     * @return a record whose every field is exactly its declared width
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  characters a fixed-width record relies on
     */
    public static SecUserRecord of(String secUsrId,
                                   String secUsrFname,
                                   String secUsrLname,
                                   String secUsrPwd,
                                   String secUsrType,
                                   Charset charset) {
        return of(secUsrId, secUsrFname, secUsrLname, secUsrPwd, secUsrType, defaultFiller(),
                new FixedWidthCodec(charset));
    }

    /**
     * Builds a record from values of any width, including an explicit {@code SEC-USR-FILLER}, using
     * an already-constructed codec.
     *
     * <p>The filler is a parameter here so that an update path can carry the bytes it read forward
     * untouched: {@code COUSR02C} rewrites only the fields the screen changed, and the record area's
     * remaining bytes keep whatever they already held. Taking the codec rather than a
     * {@link Charset} lets a repository reuse the one it already owns instead of building a fresh one
     * per record.
     *
     * @param secUsrId     the user id; padded or right-truncated to 8
     * @param secUsrFname  the first name; padded or right-truncated to 20
     * @param secUsrLname  the last name; padded or right-truncated to 20
     * @param secUsrPwd    the plaintext password; padded or right-truncated to 8
     * @param secUsrType   the user type; padded or right-truncated to 1
     * @param secUsrFiller the trailing named span; padded or right-truncated to 23
     * @param codec        the charset-bound codec that applies the {@code PIC X} move rule
     * @return a record whose every field is exactly its declared width
     * @throws NullPointerException if any argument is {@code null}
     */
    public static SecUserRecord of(String secUsrId,
                                   String secUsrFname,
                                   String secUsrLname,
                                   String secUsrPwd,
                                   String secUsrType,
                                   String secUsrFiller,
                                   FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC X move rule; it carries "
                + "the code page, which is never assumed");
        return new SecUserRecord(
                movePicX(codec, secUsrId, SPAN_SEC_USR_ID),
                movePicX(codec, secUsrFname, SPAN_SEC_USR_FNAME),
                movePicX(codec, secUsrLname, SPAN_SEC_USR_LNAME),
                movePicX(codec, secUsrPwd, SPAN_SEC_USR_PWD),
                movePicX(codec, secUsrType, SPAN_SEC_USR_TYPE),
                movePicX(codec, secUsrFiller, SPAN_SEC_USR_FILLER));
    }

    /**
     * An all-spaces record of the declared widths, the equivalent of a freshly initialised
     * {@code SEC-USER-DATA} area.
     *
     * <p>Useful as a starting point for a caller that sets fields selectively, and as the neutral
     * value a not-found read yields. Needs no charset, because it is defined in characters and only
     * acquires a code page when it is encoded.
     *
     * @return a record whose six fields are 8, 20, 20, 8, 1 and 23 spaces respectively
     */
    public static SecUserRecord blank() {
        return new SecUserRecord(
                spaces(SEC_USR_ID_LENGTH),
                spaces(SEC_USR_FNAME_LENGTH),
                spaces(SEC_USR_LNAME_LENGTH),
                spaces(SEC_USR_PWD_LENGTH),
                spaces(SEC_USR_TYPE_LENGTH),
                defaultFiller());
    }

    // =============================================================================================
    // Serialisation. Both directions go through the codec at absolute offsets, so every byte
    // position stays diffable against the copybook and no offset arithmetic is repeated here.
    // =============================================================================================

    /**
     * Serialises this record to exactly {@link #RECORD_LENGTH} bytes.
     *
     * <p>The record area is allocated over {@link #LAYOUT} and initialised before any field is
     * written, so {@code SEC-USR-FILLER} already holds the charset's space bytes and the returned
     * array is 80 bytes wide whatever the caller did or did not set.
     *
     * @param record  the record to serialise
     * @param charset the code page, supplied explicitly and never defaulted
     * @return exactly {@link #RECORD_LENGTH} bytes in {@code charset}
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  characters a fixed-width record relies on
     */
    public static byte[] encode(SecUserRecord record, Charset charset) {
        return encode(record, new FixedWidthCodec(charset));
    }

    /**
     * Serialises this record to exactly {@link #RECORD_LENGTH} bytes using an already-constructed
     * codec, for a repository that owns one.
     *
     * @param record the record to serialise
     * @param codec  the charset-bound codec
     * @return exactly {@link #RECORD_LENGTH} bytes in the codec's code page
     * @throws NullPointerException if {@code record} or {@code codec} is {@code null}
     */
    public static byte[] encode(SecUserRecord record, FixedWidthCodec codec) {
        Objects.requireNonNull(record, "A record is required to serialise; call blank() for an "
                + "all-spaces " + GROUP_NAME + " area");
        Objects.requireNonNull(codec, "A codec is required to serialise a record; it carries the "
                + "code page, which is never assumed");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, SPAN_SEC_USR_ID, record.secUsrId());
        codec.writePicX(area, SPAN_SEC_USR_FNAME, record.secUsrFname());
        codec.writePicX(area, SPAN_SEC_USR_LNAME, record.secUsrLname());
        codec.writePicX(area, SPAN_SEC_USR_PWD, record.secUsrPwd());
        codec.writePicX(area, SPAN_SEC_USR_TYPE, record.secUsrType());
        codec.writePicX(area, SPAN_SEC_USR_FILLER, record.secUsrFiller());
        return area.toByteArray();
    }

    /**
     * Deserialises exactly {@link #RECORD_LENGTH} bytes into a record, reading every field
     * <strong>untrimmed</strong>.
     *
     * <p>A byte count other than 80 is rejected outright and never quietly accommodated, because a
     * short row would shift every field after the gap and produce a record that looks plausible. The
     * seeded {@code USRSEC} data is the standing example: the ten in-stream cards of
     * {@code app/jcl/DUSRSECJ.jcl} carry only 57 characters each, omitting the 23-byte trailing
     * span, and the dataset's own {@code LRECL=80,RECFM=FB} right-pads them on the way in. Widening
     * such a row is the caller's explicit step, taken with the codec's declared-width padding
     * helper; this method deals only in complete 80-byte images.
     *
     * @param bytes   exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page, supplied explicitly and never defaulted
     * @return the decoded record, every field at its full declared width
     * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}, or
     *                                  {@code charset} is not a single-byte code page for the
     *                                  characters a fixed-width record relies on
     */
    public static SecUserRecord decode(byte[] bytes, Charset charset) {
        return decode(bytes, new FixedWidthCodec(charset));
    }

    /**
     * Deserialises exactly {@link #RECORD_LENGTH} bytes using an already-constructed codec, for a
     * repository that owns one.
     *
     * @param bytes exactly {@link #RECORD_LENGTH} bytes
     * @param codec the charset-bound codec
     * @return the decoded record, every field at its full declared width and untrimmed
     * @throws NullPointerException     if {@code bytes} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}
     */
    public static SecUserRecord decode(byte[] bytes, FixedWidthCodec codec) {
        Objects.requireNonNull(bytes, "Record bytes are required to decode a " + GROUP_NAME
                + " record; call blank() for an all-spaces record instead");
        Objects.requireNonNull(codec, "A codec is required to decode a record; it carries the code "
                + "page, which is never assumed");
        if (bytes.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("Supplied " + bytes.length + " byte(s) for "
                    + GROUP_NAME + ", which app/cpy/CSUSR01Y.cpy declares as exactly " + RECORD_LENGTH
                    + " byte(s). A fixed-width record is never partially decoded: a row that is "
                    + "short because the trailing " + FIELD_SEC_USR_FILLER + " X("
                    + SEC_USR_FILLER_LENGTH + ") is absent from the source data - as in the "
                    + "57-character seed cards of app/jcl/DUSRSECJ.jcl - must be right-padded to "
                    + RECORD_LENGTH + " before it reaches here");
        }
        FixedWidthRecord area = codec.wrap(bytes, LAYOUT);
        return new SecUserRecord(
                codec.readPicX(area, SPAN_SEC_USR_ID),
                codec.readPicX(area, SPAN_SEC_USR_FNAME),
                codec.readPicX(area, SPAN_SEC_USR_LNAME),
                codec.readPicX(area, SPAN_SEC_USR_PWD),
                codec.readPicX(area, SPAN_SEC_USR_TYPE),
                codec.readPicX(area, SPAN_SEC_USR_FILLER));
    }

    // =============================================================================================
    // Field access by COBOL name. The parity differ compares field by field BY NAME, so it needs to
    // reach a value through the copybook's own spelling without knowing this type's accessors.
    // =============================================================================================

    /**
     * Every field keyed by its COBOL name, in copybook declaration order,
     * {@code SEC-USR-FILLER} included.
     *
     * <p>Insertion-ordered and unmodifiable. The keys are the copybook's own spellings, which is what
     * lets a field-by-field differ line this record up against the oracle without sharing any code
     * with it. The values are the raw fixed-width images, untrimmed, so a comparison sees exactly the
     * bytes the record holds.
     *
     * <p>The password is present here. This map is the parity and diagnostics contract, where
     * omitting a field would hide a real difference; it is {@link #toString()} that withholds the
     * password, because that is the rendering a log line can pick up by accident.
     *
     * @return an unmodifiable, insertion-ordered map of all six COBOL field names to their images
     */
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put(FIELD_SEC_USR_ID, secUsrId);
        images.put(FIELD_SEC_USR_FNAME, secUsrFname);
        images.put(FIELD_SEC_USR_LNAME, secUsrLname);
        images.put(FIELD_SEC_USR_PWD, secUsrPwd);
        images.put(FIELD_SEC_USR_TYPE, secUsrType);
        images.put(FIELD_SEC_USR_FILLER, secUsrFiller);
        // Collections.unmodifiableMap over a LinkedHashMap, deliberately NOT Map.copyOf: the copy
        // factory returns an unordered map, which would silently scramble copybook declaration order
        // and leave a field-by-field differ reporting its differences in an arbitrary sequence.
        return Collections.unmodifiableMap(images);
    }

    /**
     * One field's raw fixed-width image, looked up by its COBOL name.
     *
     * <p>An unknown name is rejected rather than answered with {@code null}, so a misspelled field
     * name in a parity case fails loudly at the point of the mistake instead of comparing a
     * {@code null} against an expectation and reporting a puzzling difference.
     *
     * @param fieldName the COBOL item name, verbatim and case-sensitive, for example
     *                  {@code "SEC-USR-FNAME"}
     * @return the field's untrimmed image, exactly its declared width
     * @throws NullPointerException     if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not one of this record's six fields
     */
    public String image(String fieldName) {
        Objects.requireNonNull(fieldName, "A field name is required to read a " + GROUP_NAME
                + " field by name");
        Map<String, String> images = fieldImages();
        String found = images.get(fieldName);
        if (found == null) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a field of " + GROUP_NAME
                    + "; app/cpy/CSUSR01Y.cpy declares " + images.keySet()
                    + " and names are case-sensitive");
        }
        return found;
    }

    /**
     * The VSAM primary key of this record, which is {@code SEC-USR-ID} in full.
     *
     * <p>Exposed as a named operation rather than left to callers slicing
     * {@link #KEY_OFFSET}/{@link #KEY_LENGTH} out of an encoded row, so the repository states the key
     * once and cannot drift from {@code KEYS(8,0)}. Because the key occupies the record's leading 8
     * bytes, this is by construction equal to the first {@link #KEY_LENGTH} bytes of
     * {@link #encode(SecUserRecord, Charset)}.
     *
     * @return the 8-character key image, space-padded and untrimmed
     */
    public String key() {
        return secUsrId;
    }

    // =============================================================================================
    // Rendering. Deliberately hand-written so the password cannot be rendered.
    // =============================================================================================

    /**
     * A diagnostic rendering that <strong>never</strong> includes the password.
     *
     * <p>The record's automatically generated rendering would print all six components, including
     * {@code SEC-USR-PWD} in plaintext, and would then leak it into any log line, exception message
     * or debugger dump that touched a record. This override replaces the password with a fixed
     * placeholder while leaving the fields the legacy screens themselves display. Padding is shown as
     * it is held, since it is part of each field's value.
     *
     * <p>The password remains fully available through {@link #secUsrPwd()} for the plaintext
     * comparison the sign-on program performs, and through {@link #fieldImages()} for parity
     * comparison. Only this rendering withholds it.
     *
     * @return a single-line description of the record with the password masked
     */
    @Override
    public String toString() {
        return GROUP_NAME + "["
                + FIELD_SEC_USR_ID + "='" + secUsrId + "', "
                + FIELD_SEC_USR_FNAME + "='" + secUsrFname + "', "
                + FIELD_SEC_USR_LNAME + "='" + secUsrLname + "', "
                + FIELD_SEC_USR_PWD + "=" + PASSWORD_PLACEHOLDER + ", "
                + FIELD_SEC_USR_TYPE + "='" + secUsrType + "', "
                + FIELD_SEC_USR_FILLER + ".length=" + secUsrFiller.length()
                + "]";
    }

    // =============================================================================================
    // Private helpers. Each guard is a separate, individually reachable branch.
    // =============================================================================================

    /**
     * Checks one component against its descriptor's declared width, naming the field, the width it
     * must have and the width it was given.
     */
    private static String requireDeclaredWidth(String value, FieldSpan field) {
        Objects.requireNonNull(value, "Field '" + field.name() + "' of " + GROUP_NAME + " is "
                + "required; a COBOL PIC X field is never absent, so move SPACES to blank it");
        if (value.length() != field.length()) {
            throw new IllegalArgumentException("Field '" + field.name() + "' of " + GROUP_NAME
                    + " is declared PIC X(" + field.length() + ") but was given " + value.length()
                    + " character(s). This type holds fields at exactly their declared width and "
                    + "never silently pads or truncates, so that an accessor always returns the "
                    + "full space-padded value the parity differ compares. Use of(...) to apply the "
                    + "COBOL alphanumeric MOVE rule deliberately");
        }
        return value;
    }

    /**
     * Applies the COBOL alphanumeric {@code MOVE} rule for one field, rejecting a {@code null}
     * sending value with a message that names the receiving field.
     */
    private static String movePicX(FixedWidthCodec codec, String value, FieldSpan field) {
        Objects.requireNonNull(value, "A sending value is required for field '" + field.name()
                + "' of " + GROUP_NAME + "; move an empty string or SPACES to blank it");
        return codec.movePicX(value, field.length());
    }

    /** The 23 spaces a {@code SEC-USR-FILLER} that was never assigned holds. */
    private static String defaultFiller() {
        return spaces(SEC_USR_FILLER_LENGTH);
    }

    /** A run of {@code count} spaces, the pad character of every {@code PIC X} field here. */
    private static String spaces(int count) {
        return " ".repeat(count);
    }
}
