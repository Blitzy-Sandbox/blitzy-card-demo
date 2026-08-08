package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The {@code TRANCATG} transaction-category record: 60 bytes with a 6-byte composite key, the single
 * Java type for copybook {@code app/cpy/CVTRA04Y.cpy}, whose header comment reads
 * <em>"Data-structure for transaction category type (RECLN = 60)"</em>.
 *
 * <p>This is a lookup table keyed by transaction type and category, mapping that pair to a
 * human-readable description. It has exactly <strong>one</strong> COBOL consumer,
 * {@code app/cbl/CBTRN03C.cbl} - the transaction detail report - which reads it as
 * {@code COPY CVTRA04Y.} at {@code app/cbl/CBTRN03C.cbl:108}.
 *
 * <h2>The verified layout</h2>
 * Offsets are absolute and 0-based, as every span in this module is. The 1-based column is the
 * copybook's own byte numbering, kept alongside so a reviewer can check either convention without
 * doing arithmetic.
 * <table border="1">
 *   <caption>{@code 01 TRAN-CAT-RECORD} - 60 bytes</caption>
 *   <tr><th>COBOL item</th><th>Level</th><th>PICTURE</th><th>1-based</th><th>0-based offset</th>
 *       <th>Length</th><th>Java type</th></tr>
 *   <tr><td>{@code TRAN-CAT-KEY}</td><td>05 group</td><td>-</td><td>1-6</td><td>0</td><td>6</td>
 *       <td>composite key, a sub-span over the two items below</td></tr>
 *   <tr><td>{@code TRAN-TYPE-CD}</td><td>10</td><td>{@code X(02)}</td><td>1-2</td><td>0</td>
 *       <td>2</td><td>{@link String}</td></tr>
 *   <tr><td>{@code TRAN-CAT-CD}</td><td>10</td><td>{@code 9(04)}</td><td>3-6</td><td>2</td>
 *       <td>4</td><td>{@code int}</td></tr>
 *   <tr><td>{@code TRAN-CAT-TYPE-DESC}</td><td>05</td><td>{@code X(50)}</td><td>7-56</td><td>6</td>
 *       <td>50</td><td>{@link String}, <strong>untrimmed</strong></td></tr>
 *   <tr><td>{@code FILLER}</td><td>05</td><td>{@code X(04)}</td><td>57-60</td><td>56</td><td>4</td>
 *       <td>reserved span, retained verbatim</td></tr>
 * </table>
 * The storage spans sum to {@code 6 + 50 + 4 = 60}, which {@link #LAYOUT} proves on class
 * initialisation rather than asserting in prose.
 *
 * <h2>Four independent confirmations of that layout</h2>
 * The expected values in this class are <strong>statically derived</strong>: COBOL cannot be executed
 * in this environment, so nothing here was captured from a live run. To keep a transcription error
 * from becoming an invisible parity defect, the layout was corroborated four ways, from four
 * different kinds of artefact.
 * <ol>
 *   <li><strong>The consumer's own {@code FD}.</strong> {@code app/cbl/CBTRN03C.cbl:78-82} splits the
 *       same record as {@code FD-TRAN-CAT-KEY} - itself {@code FD-TRAN-TYPE-CD PIC X(02)} plus
 *       {@code FD-TRAN-CAT-CD PIC 9(04)} - followed by {@code FD-TRAN-CAT-DATA PIC X(54)}. That is
 *       {@code 6 + 54 = 60}, agreeing on the total and on the key while lumping the description and
 *       the {@code FILLER} into one span. {@code app/cbl/CBTRN03C.cbl:48} then declares
 *       {@code RECORD KEY IS FD-TRAN-CAT-KEY}, which is what fixes the key width at
 *       <strong>6</strong>.</li>
 *   <li><strong>The job that binds the dataset.</strong> {@code app/jcl/TRANREPT.jcl:71-72} declares
 *       the {@code TRANCATG} DD with {@code DISP=SHR} under {@code STEP10R EXEC PGM=CBTRN03C} at
 *       {@code app/jcl/TRANREPT.jcl:59}, listed among that step's input files - a keyed lookup
 *       input, read and never written. The dataset name that DD carries is deliberately <em>not</em>
 *       reproduced here. Every dataset name in this module lives in the {@code TRANCATG} entry of
 *       {@code carddemo.datasets} in {@code src/main/resources/application.yml}, behind an
 *       environment placeholder, and in no Java source at all (gate G46) - so this class cites the
 *       JCL line and the binding key, which is enough to find the name and keeps the single
 *       authority for it in one place.</li>
 *   <li><strong>The real fixture.</strong> {@code app/data/ASCII/trancatg.txt} measures 18 records of
 *       exactly 60 bytes each. Decoding the first three at the offsets above yields
 *       {@code ("01", 1, "Regular Sales Draft")}, {@code ("01", 2, "Regular Cash Advance")} and
 *       {@code ("01", 3, "Convenience Check Debit")}, each description right-space-padded to its full
 *       50 bytes. Any offset error would have shifted these values visibly.</li>
 *   <li><strong>The module's own configuration.</strong> {@code application.yml} binds
 *       {@code TRANCATG} with {@code record-length: 60} and {@code copybook: CVTRA04Y}, derived
 *       independently from the JCL.</li>
 * </ol>
 *
 * <h2>Three name collisions, none of which may be tidied away</h2>
 * Three of this copybook's names are reused elsewhere in the same domain with different meanings and,
 * in one case, a different byte width. Every name here is reproduced <strong>verbatim</strong>. None
 * is renamed, disambiguated or unified with its namesake, because the parity differ compares fields
 * <em>by name</em>: renaming one would make a real difference invisible. The collisions are recorded
 * here precisely so that nobody, reading a single file, mistakes one for the other.
 *
 * <h3>Collision 1 - {@code TRAN-CAT-KEY} is 6 bytes here and 17 bytes in {@code CVTRA01Y}</h3>
 * {@code app/cpy/CVTRA01Y.cpy} declares a group with the <em>identical</em> COBOL name
 * {@code TRAN-CAT-KEY}, but built from entirely different members:
 * {@code TRANCAT-ACCT-ID PIC 9(11)} plus {@code TRANCAT-TYPE-CD PIC X(02)} plus
 * {@code TRANCAT-CD PIC 9(04)}, totalling <strong>17</strong> bytes. Same name, 6 versus 17 bytes,
 * different member names, different record, different dataset - {@code TRANCATG} here against
 * {@code TCATBALF} there. The two key types are therefore <strong>distinct Java types that must
 * never be interchanged or unified</strong>, which is why {@code TranCategoryRepository} (6-byte key)
 * and {@code TranCatBalRepository} (17-byte key) each declare their own key value type. The hazard is
 * already documented independently in this module's {@code application.yml}, whose {@code TCATBALF}
 * binding carries {@code key-length: 17} annotated as "CVTRA01Y's TRAN-CAT-KEY". Guarding against a
 * silent 6-for-17 substitution is the whole purpose of {@link #TRAN_CAT_KEY_LENGTH} and of the
 * self-check in {@link #verifyDeclaredGeometry()}.
 *
 * <h3>Collision 2 - {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are also {@code CVTRA05Y}'s
 * field names</h3>
 * {@code app/cpy/CVTRA05Y.cpy} - the 350-byte transaction record modelled by {@code TranRecord} -
 * declares {@code TRAN-TYPE-CD PIC X(02)} at 0-based offset 16 and {@code TRAN-CAT-CD PIC 9(04)} at
 * 0-based offset 18. The clash is <strong>not hypothetical</strong>: {@code CBTRN03C} copies both
 * copybooks into one program ({@code COPY CVTRA05Y.} at {@code :93} and {@code COPY CVTRA04Y.} at
 * {@code :108}), so the unqualified names are genuinely ambiguous there and COBOL forces explicit
 * qualification at five sites:
 * <ul>
 *   <li>{@code :189} {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE} - feeding the
 *       {@code TRANTYPE} lookup, not this one</li>
 *   <li>{@code :191} {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD} into
 *       {@code FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY} at {@code :192}</li>
 *   <li>{@code :193} {@code MOVE TRAN-CAT-CD OF TRAN-RECORD} into
 *       {@code FD-TRAN-CAT-CD OF FD-TRAN-CAT-KEY} at {@code :194}</li>
 *   <li>{@code :365} {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD}</li>
 *   <li>{@code :367} {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD}</li>
 * </ul>
 * Note that the receivers at {@code :192} and {@code :194} are qualified too - the ambiguity runs in
 * both directions. In Java the two records are separate types, so no qualification is needed and the
 * compiler cannot confuse them; but they must <strong>stay</strong> separate types and must never be
 * merged, aliased or made to share a field-name constant.
 *
 * <h3>Collision 3 - {@code TRAN-TYPE-CD} here versus {@code TRAN-TYPE} in {@code CVTRA03Y}</h3>
 * {@code app/cpy/CVTRA03Y.cpy} - the transaction-type lookup modelled by {@code TranTypeRecord} -
 * names its 2-byte key {@code TRAN-TYPE}, <strong>without</strong> the {@code -CD} suffix, and its
 * description {@code TRAN-TYPE-DESC} rather than {@code TRAN-CAT-TYPE-DESC}. Both records are 60
 * bytes wide, both are lookups, both are read by {@code CBTRN03C} in the same paragraph sequence, and
 * both are keyed from the same 2-character value taken from {@code TranRecord}. A one-character
 * difference is all that separates them, so the two lookups are easy to confuse and must not be.
 *
 * <h2>The description is decoded untrimmed, and that is load-bearing</h2>
 * {@code TRAN-CAT-TYPE-DESC} is returned as all <strong>50</strong> bytes, trailing spaces included.
 * Two reasons, and the second is the subtle one:
 * <ul>
 *   <li>Trimming would break round-trip fidelity. The padding is part of the field's value, and a
 *       trimmed value could not be re-encoded to the declared 60-byte width.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl:368} performs
 *       {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC}, and that receiver is
 *       {@code PIC X(29)} at {@code app/cpy/CVTRA07Y.cpy:26}. COBOL truncates an alphanumeric
 *       {@code MOVE} on the <strong>right</strong>, so the report line carries the first 29
 *       characters of the 50-byte field. That truncation belongs at the point of use, applied
 *       deliberately through {@link FixedWidthCodec#movePicX(String, int)}, and never here: a model
 *       that pre-truncated would silently discard 21 bytes that the parity differ compares.</li>
 * </ul>
 * In the shipped fixture the widest description is exactly 29 characters
 * ({@code "Online purchase authorization"} and {@code "Sales draft credit adjustment"}), so no
 * fixture row is in fact truncated by that move - it fills the report field exactly. The untrimmed
 * contract still matters, because the field is 50 bytes wide and any value of 30 to 50 characters is
 * representable in it.
 *
 * <h2>{@code FILLER} content is retained, never assumed</h2>
 * The trailing {@code FILLER X(04)} is a first-class span: declared, positioned and always emitted.
 * Dropping it as "unused" would make the record 56 bytes and shift every subsequent byte of the
 * dataset, which is why {@link RecordLayout} refuses to be constructed unless the spans sum to
 * exactly 60.
 *
 * <p>Its <em>content</em> is a second, easily-missed point. A record built from nothing space-fills
 * its {@code FILLER}, which is the general convention. But every one of the 18 rows in
 * {@code app/data/ASCII/trancatg.txt} holds four ASCII <strong>zeros</strong> there, not spaces -
 * unlike the {@code dailytran} fixture, whose {@code FILLER} really is spaces. This class therefore
 * keeps the record's original 60 bytes as its authoritative state, so a record decoded from the
 * dataset reproduces its {@code FILLER} <strong>verbatim</strong> rather than re-spacing it. That
 * costs nothing in correctness and is exactly right for this dataset:
 * {@code CBTRN03C} only ever opens {@code TRANCATG} for input ({@code :450}), reads it by key
 * ({@code :505}) and closes it ({@code :589}) - there is no write, rewrite, delete or browse anywhere
 * - so {@code TRANCATG} is read-only in this system and any image this class re-emits came from a
 * record it read.
 *
 * <h2>What this class deliberately does not contain</h2>
 * <ul>
 *   <li><strong>No I/O, no {@code FileStatus} handling, no abend logic.</strong> The not-found path
 *       at {@code app/cbl/CBTRN03C.cbl:505-511} - {@code INVALID KEY}, then the {@code DISPLAY} at
 *       {@code :507}, then {@code MOVE 23 TO IO-STATUS}, then the abend - belongs to
 *       {@code TranCategoryRepository} and {@code TransactionReportJob}. This type is a pure record
 *       model. It does supply the 6-byte key image that {@code :507} renders, through
 *       {@link #tranCatKeyImage()}.</li>
 *   <li><strong>No decimal support.</strong> {@code CVTRA04Y} declares no signed decimal field, so
 *       nothing here imports the module's fixed-point decimal helper and no arbitrary-precision
 *       decimal type appears anywhere in this file. An unused import would break the audit trail that
 *       ties each import to a field that needs it. Nor is any binary approximate numeric primitive
 *       used, for the same reason those are barred module-wide: they cannot represent a decimal
 *       fraction exactly.</li>
 *   <li><strong>No {@code OCCURS} table, no {@code REDEFINES} overlay, no {@code 88}-level
 *       predicate.</strong> Verified by inspection: {@code CVTRA04Y} contains zero {@code OCCURS},
 *       zero {@code REDEFINES}, zero {@code COMP} or {@code COMP-3}, zero {@code SIGN}, zero
 *       {@code USAGE}, zero {@code 88}-levels and not one {@code VALUE} clause. The 1-based to 0-based
 *       conversion still governs the offsets above, but there is no array to index, so none is
 *       invented. In particular the 6-byte group key is modelled as a <em>sub-span</em> over its two
 *       elementary items - a named offset and length - and <strong>not</strong> as a third overlapping
 *       field or a fabricated {@code REDEFINES}, because the copybook declares neither.</li>
 *   <li><strong>No object-relational mapping.</strong> No entity, table, identity or version
 *       annotation, and no framework annotation of any kind: {@code TRANCATG} is a VSAM KSDS reached
 *       through JDBC with no schema, no migration and no generated table behind it.</li>
 * </ul>
 *
 * <h2>Immutability and thread safety</h2>
 * Instances are immutable and therefore safe to share and to cache across threads. The backing byte
 * array is never handed out: {@link #toByteArray()}, {@link #tranCatKeyBytes()} and
 * {@link #fillerBytes()} all return copies, and {@link #toRecordArea()} returns an independent,
 * separately-owned mutable record area. There is no mutable static state anywhere in this class.
 *
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 */
public final class TranCategoryRecord {

    /**
     * The declared record width, {@code RECLN = 60} from the copybook header comment and
     * {@code record-length: 60} from the {@code TRANCATG} dataset binding.
     */
    public static final int RECORD_LENGTH = 60;

    /**
     * The COBOL name of the composite key group, carried verbatim.
     *
     * <p>Held as a constant rather than left implicit precisely <em>because</em> the name collides:
     * {@code app/cpy/CVTRA01Y.cpy} declares a 17-byte group by this same name. The group itself has
     * no {@link FieldSpan} of its own, since it is a sub-span over the two elementary items rather
     * than storage in its own right.
     */
    public static final String TRAN_CAT_KEY_NAME = "TRAN-CAT-KEY";

    /**
     * The 0-based offset of the {@code TRAN-CAT-KEY} group: 0, the start of the record, matching
     * {@code RECORD KEY IS FD-TRAN-CAT-KEY} at {@code app/cbl/CBTRN03C.cbl:48}.
     */
    public static final int TRAN_CAT_KEY_OFFSET = 0;

    /**
     * The width of the {@code TRAN-CAT-KEY} group: <strong>6</strong> bytes,
     * {@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD 9(04)}.
     *
     * <p>Not 17. {@code app/cpy/CVTRA01Y.cpy} declares a group of the identical name that <em>is</em>
     * 17 bytes wide, and confusing the two would misread every byte of this record from offset 6
     * onwards. {@link #verifyDeclaredGeometry()} exists to make that mistake impossible to ship.
     */
    public static final int TRAN_CAT_KEY_LENGTH = 6;

    /** The 0-based offset of {@code 10 TRAN-TYPE-CD PIC X(02)}: 0 (1-based bytes 1-2). */
    public static final int TRAN_TYPE_CD_OFFSET = 0;

    /** The declared width of {@code 10 TRAN-TYPE-CD PIC X(02)}: 2 characters. */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /** The 0-based offset of {@code 10 TRAN-CAT-CD PIC 9(04)}: 2 (1-based bytes 3-6). */
    public static final int TRAN_CAT_CD_OFFSET = 2;

    /** The declared width of {@code 10 TRAN-CAT-CD PIC 9(04)}: 4 digits. */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /** The 0-based offset of {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)}: 6 (1-based bytes 7-56). */
    public static final int TRAN_CAT_TYPE_DESC_OFFSET = 6;

    /** The declared width of {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)}: 50 characters. */
    public static final int TRAN_CAT_TYPE_DESC_LENGTH = 50;

    /** The 0-based offset of the trailing {@code 05 FILLER PIC X(04)}: 56 (1-based bytes 57-60). */
    public static final int FILLER_OFFSET = 56;

    /** The declared width of the trailing {@code 05 FILLER PIC X(04)}: 4 bytes. */
    public static final int FILLER_LENGTH = 4;

    /**
     * {@code 10 TRAN-TYPE-CD PIC X(02)} - the transaction type code, the first half of the composite
     * key.
     *
     * <p>Alphanumeric, so it is a {@link String} and not a number, even though every value in the
     * fixture looks numeric ({@code "01"} through {@code "07"}). The leading zero is significant: a
     * two-character code is what the key image is built from, and decoding {@code "01"} to the
     * integer {@code 1} would collapse the field and produce a key that no longer matches.
     */
    public static final FieldSpan TRAN_TYPE_CD =
            FieldSpan.alphanumeric("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);

    /**
     * {@code 10 TRAN-CAT-CD PIC 9(04)} - the transaction category code, the second half of the
     * composite key.
     *
     * <p>A scale-free unsigned {@code PIC 9}, so it maps to {@code int}. Stored zoned
     * {@code DISPLAY}, one digit per byte, right justified and zero-filled on the left, which is how
     * the fixture's {@code 0001} denotes 1.
     */
    public static final FieldSpan TRAN_CAT_CD =
            FieldSpan.unsignedNumeric("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);

    /**
     * {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)} - the category description, read untrimmed.
     *
     * <p>The one field of this record that reaches the printed report, and the reason untrimmed
     * decoding matters: {@code app/cbl/CBTRN03C.cbl:368} moves it into a {@code PIC X(29)} receiver,
     * which keeps the leading 29 characters.
     */
    public static final FieldSpan TRAN_CAT_TYPE_DESC = FieldSpan.alphanumeric(
            "TRAN-CAT-TYPE-DESC", TRAN_CAT_TYPE_DESC_OFFSET, TRAN_CAT_TYPE_DESC_LENGTH);

    /**
     * {@code 05 FILLER PIC X(04)} - the trailing reserved span, declared with no {@code VALUE}
     * clause.
     *
     * <p>Present here as a real span, not an implied gap. Omitting it would leave the layout 4 bytes
     * short of 60 and {@link RecordLayout} would refuse to be constructed, which is exactly the
     * intent: a dropped {@code FILLER} fails immediately instead of silently shifting the dataset.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete layout in copybook declaration order: four storage spans, no overlay, no gap.
     *
     * <p>Constructing it runs {@link RecordLayout}'s self-check, so the spans are proven contiguous
     * from offset 0 and proven to sum to exactly {@link #RECORD_LENGTH} before this class can be
     * used at all. The 6-byte {@code TRAN-CAT-KEY} group is deliberately <em>not</em> a fifth span:
     * it is a sub-span over the first two, and declaring it as storage would count the same six bytes
     * twice and make the layout 66 bytes wide.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(
            RECORD_LENGTH, TRAN_TYPE_CD, TRAN_CAT_CD, TRAN_CAT_TYPE_DESC, FILLER);

    static {
        // Runs once, at class initialisation, on integer constants only - no charset, locale or
        // timezone is consulted here, so the check is deterministic on every platform. A violation
        // surfaces as an ExceptionInInitializerError the first time anything touches this type,
        // which is the earliest and loudest point available.
        verifyDeclaredGeometry();
    }

    /**
     * The sum of the declared storage span widths, computed from {@link #LAYOUT} rather than restated
     * as a literal, so it cannot drift away from the spans it is meant to describe.
     *
     * @return 60 for a correctly declared layout
     */
    public static int sumOfDeclaredSpanWidths() {
        int total = 0;
        for (FieldSpan span : LAYOUT.storageSpans()) {
            total += span.length();
        }
        return total;
    }

    /**
     * Proves this class's declared geometry, and in particular that the composite key is
     * <strong>6</strong> bytes rather than {@code CVTRA01Y}'s 17.
     *
     * <p>Four things are checked:
     * <ol>
     *   <li>the two key items sum to {@link #TRAN_CAT_KEY_LENGTH};</li>
     *   <li>{@link #TRAN_CAT_KEY_LENGTH} is 6 - the width {@code app/cbl/CBTRN03C.cbl:48} fixes by
     *       declaring {@code RECORD KEY IS FD-TRAN-CAT-KEY} over the {@code :79-81} group;</li>
     *   <li>{@link #TRAN_CAT_TYPE_DESC_OFFSET} equals the key width, so the description begins
     *       exactly where the key ends and no byte is unaccounted for between them;</li>
     *   <li>the storage spans sum to {@link #RECORD_LENGTH}, which is gate G19's 60-byte
     *       requirement and fails immediately if the trailing {@code FILLER} is ever dropped.</li>
     * </ol>
     * Called from the static initialiser and safe to call again from a test: it reads only immutable
     * constants and has no side effect.
     *
     * @throws IllegalStateException if any declared offset or width is inconsistent, naming the
     *                               {@code CVTRA01Y} 17-byte trap where that is the likely cause
     */
    public static void verifyDeclaredGeometry() {
        verifyGeometry(TRAN_TYPE_CD_LENGTH, TRAN_CAT_CD_LENGTH, TRAN_CAT_KEY_LENGTH,
                TRAN_CAT_TYPE_DESC_OFFSET, sumOfDeclaredSpanWidths());
    }

    /**
     * The self-check itself, with every quantity passed in rather than read from a constant.
     *
     * <p>Package-private purely as a testability seam, and it exists for a concrete reason. Called
     * through {@link #verifyDeclaredGeometry()} the four failure branches below are
     * <em>unreachable</em>, because the constants they guard are correct - which would leave half of
     * this method's branches permanently uncoverable and put the module's mandated 90% branch
     * threshold out of reach through no fault of the tests. Taking the values as parameters lets the
     * paired unit test deliberately pass a 17-byte key width, a description offset of 17 or a 56-byte
     * total and assert on each diagnostic, so every branch is driven and each failure message is
     * verified to actually name the trap it is meant to name.
     *
     * <p>It adds no behaviour of its own: {@link #verifyDeclaredGeometry()} is the only production
     * caller and it always supplies this class's real constants.
     *
     * @param typeCdLength   the declared width of {@code TRAN-TYPE-CD}
     * @param catCdLength    the declared width of {@code TRAN-CAT-CD}
     * @param keyLength      the declared width of the {@code TRAN-CAT-KEY} group
     * @param descOffset     the declared 0-based offset of {@code TRAN-CAT-TYPE-DESC}
     * @param declaredTotal  the summed width of the declared storage spans
     * @throws IllegalStateException if the supplied geometry is inconsistent
     */
    static void verifyGeometry(int typeCdLength,
                               int catCdLength,
                               int keyLength,
                               int descOffset,
                               int declaredTotal) {
        int keyItemsWidth = typeCdLength + catCdLength;
        if (keyItemsWidth != keyLength) {
            throw new IllegalStateException("CVTRA04Y's TRAN-CAT-KEY is declared as "
                    + keyLength + " byte(s) but its two elementary items, TRAN-TYPE-CD "
                    + "X(02) and TRAN-CAT-CD 9(04), occupy " + keyItemsWidth
                    + ". The key is a sub-span over exactly those two items and nothing else");
        }
        if (keyLength != 6) {
            throw new IllegalStateException("CVTRA04Y's TRAN-CAT-KEY must be 6 bytes - "
                    + "TRAN-TYPE-CD X(02) plus TRAN-CAT-CD 9(04) - but is declared as "
                    + keyLength + ". BEWARE THE NAMESAKE: app/cpy/CVTRA01Y.cpy declares a "
                    + "group with the IDENTICAL COBOL name TRAN-CAT-KEY that is 17 bytes wide "
                    + "(TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)) and "
                    + "belongs to the TCATBALF dataset, not TRANCATG. If this width has become 17, "
                    + "the two records have been conflated");
        }
        if (descOffset != keyLength) {
            throw new IllegalStateException("TRAN-CAT-TYPE-DESC must begin at offset "
                    + keyLength + ", immediately after the 6-byte TRAN-CAT-KEY, but is "
                    + "declared at offset " + descOffset + ". An offset of 17 here "
                    + "means CVTRA01Y's 17-byte TRAN-CAT-KEY has been substituted for CVTRA04Y's "
                    + "6-byte one");
        }
        if (declaredTotal != RECORD_LENGTH) {
            throw new IllegalStateException("CVTRA04Y declares RECLN = " + RECORD_LENGTH
                    + " but the layout's storage spans sum to " + declaredTotal
                    + ". The record is TRAN-CAT-KEY 6 + TRAN-CAT-TYPE-DESC 50 + FILLER 4; a total of "
                    + "56 means the trailing FILLER X(04) has been dropped");
        }
    }

    /**
     * The record's own 60 bytes, and the authoritative state of this instance. Every accessor is
     * derived from these bytes, so the decoded fields and the serialised image can never disagree.
     * Holding the original bytes is also what preserves a decoded record's {@code FILLER} verbatim.
     */
    private final byte[] image;

    /** The code page these bytes are in, supplied explicitly by the caller and never assumed. */
    private final Charset charset;

    /** {@code TRAN-TYPE-CD}, exactly {@link #TRAN_TYPE_CD_LENGTH} characters. */
    private final String tranTypeCd;

    /** {@code TRAN-CAT-CD}, decoded from its {@link #TRAN_CAT_CD_LENGTH}-digit zoned image. */
    private final int tranCatCd;

    /** {@code TRAN-CAT-TYPE-DESC}, exactly {@link #TRAN_CAT_TYPE_DESC_LENGTH} characters, untrimmed. */
    private final String tranCatTypeDesc;

    /** The {@code FILLER} span's characters, exactly {@link #FILLER_LENGTH} of them, verbatim. */
    private final String filler;

    /** The 6-byte {@code TRAN-CAT-KEY} sub-span, verbatim, as {@code CBTRN03C:507} displays it. */
    private final String tranCatKeyImage;

    /**
     * All state is decoded once, here, from a record area that has already been bounds-checked against
     * {@link #LAYOUT}. The array is taken by reference because every caller is a private factory in
     * this class that has just obtained a fresh copy from
     * {@link FixedWidthRecord#toByteArray()}; it is never exposed afterwards.
     */
    private TranCategoryRecord(byte[] image,
                               Charset charset,
                               String tranTypeCd,
                               int tranCatCd,
                               String tranCatTypeDesc,
                               String filler,
                               String tranCatKeyImage) {
        this.image = image;
        this.charset = charset;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranCatTypeDesc = tranCatTypeDesc;
        this.filler = filler;
        this.tranCatKeyImage = tranCatKeyImage;
    }

    /**
     * Decodes a stored {@code TRANCATG} record - the Java equivalent of
     * {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} at {@code app/cbl/CBTRN03C.cbl:505}.
     *
     * <p>The row must be exactly {@link #RECORD_LENGTH} bytes. Every row of
     * {@code app/data/ASCII/trancatg.txt} is, so a different width means either a genuinely malformed
     * dataset or a caller that has read the wrong number of bytes; tolerating it would let every
     * field offset drift. A row that is short because its source omits a trailing span must be widened
     * deliberately, with {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)}, before it arrives
     * here.
     *
     * <p>The {@code FILLER} bytes are retained as read, so re-serialising the result through
     * {@link #toByteArray()} reproduces the original row byte for byte - including the four ASCII
     * zeros this dataset carries there.
     *
     * @param record  the stored row, exactly {@link #RECORD_LENGTH} bytes; defensively copied
     * @param charset the code page of the stored bytes, stated explicitly
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@link #RECORD_LENGTH} bytes,
     *                                  if {@code charset} is not a single-byte code page for the
     *                                  digits and the space, or if {@code TRAN-CAT-CD} does not hold
     *                                  four digits
     */
    public static TranCategoryRecord decode(byte[] record, Charset charset) {
        Objects.requireNonNull(record, "TRANCATG record bytes are required; use "
                + "of(String, int, String, Charset) to build a record from field values instead");
        Objects.requireNonNull(charset, "A charset must be supplied explicitly: fixed-width "
                + "mainframe data is bytes in a specific code page, never a platform default");
        if (record.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("Supplied " + record.length + " byte(s) for a "
                    + "TRANCATG record, which app/cpy/CVTRA04Y.cpy declares as exactly "
                    + RECORD_LENGTH + " (TRAN-CAT-KEY 6 + TRAN-CAT-TYPE-DESC 50 + FILLER 4). Every "
                    + "row of app/data/ASCII/trancatg.txt is 60 bytes, so widen or reject the row "
                    + "deliberately - with FixedWidthCodec.padToDeclaredWidth - before decoding it");
        }
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return fromArea(codec, codec.wrap(record, LAYOUT));
    }

    /**
     * Builds a {@code TRANCATG} record from field values, applying COBOL {@code MOVE} semantics to
     * each.
     *
     * <p>The record area starts initialised from {@link #LAYOUT}, so the {@code FILLER} and every
     * unwritten byte are already correct before the first field is placed. Each field is then written
     * through the codec so the direction of any width adjustment is the COBOL one rather than an
     * accident:
     * <ul>
     *   <li>{@code TRAN-TYPE-CD} and {@code TRAN-CAT-TYPE-DESC} are alphanumeric, so a short value is
     *       padded on the right with spaces and an over-long one is truncated on the
     *       <strong>right</strong>;</li>
     *   <li>{@code TRAN-CAT-CD} is numeric, so a short value is zero-filled on the
     *       <strong>left</strong> - 1 becomes {@code 0001} - and a value above 9999 keeps its
     *       low-order four digits.</li>
     * </ul>
     * Neither over-wide case throws. That is deliberate parity: {@code ON SIZE ERROR} appears nowhere
     * in the 28 programs, so COBOL would discard the excess silently and so does this. A
     * <em>negative</em> category code is a different matter and is rejected, because {@code PIC 9(04)}
     * is unsigned and has no representation for one.
     *
     * <p>Because this record is built rather than read, its {@code FILLER} is the space-filled default
     * of an initialised area, not the four ASCII zeros the shipped dataset happens to carry. That
     * distinction is intentional: {@code TRANCATG} is never written by this system, so a built record
     * is a test or in-memory construct rather than something destined for the dataset.
     *
     * @param tranTypeCd      {@code TRAN-TYPE-CD}, the 2-character type code; {@code "01"} and not 1
     * @param tranCatCd       {@code TRAN-CAT-CD}, the category code; must not be negative
     * @param tranCatTypeDesc {@code TRAN-CAT-TYPE-DESC}, up to 50 characters
     * @param charset         the code page to encode into, stated explicitly
     * @return the assembled record, exactly {@link #RECORD_LENGTH} bytes wide
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative, or if {@code charset} is not
     *                                  a single-byte code page for the digits and the space
     */
    public static TranCategoryRecord of(String tranTypeCd,
                                        int tranCatCd,
                                        String tranCatTypeDesc,
                                        Charset charset) {
        Objects.requireNonNull(tranTypeCd, "TRAN-TYPE-CD is required; it is PIC X(02), so pass the "
                + "2-character code such as \"01\", or SPACES as \"  \" - never null");
        Objects.requireNonNull(tranCatTypeDesc, "TRAN-CAT-TYPE-DESC is required; it is PIC X(50), so "
                + "pass an empty string for a blank description rather than null");
        Objects.requireNonNull(charset, "A charset must be supplied explicitly to encode a "
                + "fixed-width record; it is never derived from the platform");
        requireUnsignedCategoryCode(tranCatCd);

        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, TRAN_TYPE_CD, tranTypeCd);
        codec.writePic9(area, TRAN_CAT_CD, tranCatCd);
        codec.writePicX(area, TRAN_CAT_TYPE_DESC, tranCatTypeDesc);
        return fromArea(codec, area);
    }

    /**
     * Decodes every span of an already-validated record area exactly once.
     *
     * <p>All byte handling is delegated to the codec and the record area - this class performs none of
     * its own - and the description is read with {@link FixedWidthCodec#readPicX} rather than
     * {@code readPicXTrimmed}, which is what keeps all 50 bytes intact.
     */
    private static TranCategoryRecord fromArea(FixedWidthCodec codec, FixedWidthRecord area) {
        return new TranCategoryRecord(
                area.toByteArray(),
                codec.charset(),
                codec.readPicX(area, TRAN_TYPE_CD),
                codec.readPic9AsInt(area, TRAN_CAT_CD),
                codec.readPicX(area, TRAN_CAT_TYPE_DESC),
                codec.readPicX(area, FILLER),
                area.readString(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH));
    }

    /**
     * Builds the 6-byte {@code TRAN-CAT-KEY} image for a keyed read, without needing a record.
     *
     * <p>This is the Java form of the two moves at {@code app/cbl/CBTRN03C.cbl:191-194}, which
     * assemble the lookup key from the transaction record before
     * {@code PERFORM 1500-C-LOOKUP-TRANCATG}:
     * <pre>
     *   MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY
     *   MOVE TRAN-CAT-CD  OF TRAN-RECORD TO FD-TRAN-CAT-CD  OF FD-TRAN-CAT-KEY
     * </pre>
     * The parameters are a {@link String} then an {@code int}, in that order, matching both the order
     * of those moves and the Java types of {@code TranRecord}'s two source fields exactly, so a
     * caller can pass them straight through. Each half is placed with its own COBOL {@code MOVE}
     * rule - the type code padded or truncated on the right, the category code zero-filled or
     * truncated on the left - so the result is always exactly {@link #TRAN_CAT_KEY_LENGTH}
     * characters.
     *
     * <p>Keeping this on the record type is what stops {@code TranCategoryRepository} from
     * re-deriving the key's offsets for itself, which is how a 6-byte key quietly becomes
     * {@code CVTRA01Y}'s 17-byte one.
     *
     * @param tranTypeCd the 2-character transaction type code
     * @param tranCatCd  the category code; must not be negative
     * @param charset    the code page the key will be encoded in, stated explicitly so the 6
     *                   characters are provably 6 bytes
     * @return the key image, exactly {@link #TRAN_CAT_KEY_LENGTH} characters
     * @throws NullPointerException     if {@code tranTypeCd} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative, or if {@code charset} is not
     *                                  a single-byte code page for the digits and the space
     */
    public static String tranCatKeyImage(String tranTypeCd, int tranCatCd, Charset charset) {
        Objects.requireNonNull(tranTypeCd, "TRAN-TYPE-CD is required to build a TRAN-CAT-KEY; it is "
                + "PIC X(02) and forms the key's first two bytes");
        Objects.requireNonNull(charset, "A charset must be supplied explicitly: a 6-character key "
                + "image is only a 6-byte key under a single-byte code page");
        requireUnsignedCategoryCode(tranCatCd);
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.movePicX(tranTypeCd, TRAN_TYPE_CD_LENGTH)
                + codec.movePic9(tranCatCd, TRAN_CAT_CD_LENGTH);
    }

    /**
     * Builds the 6-byte {@code TRAN-CAT-KEY} as bytes, for a repository issuing a keyed read against
     * the {@code TRANCATG} KSDS.
     *
     * @param tranTypeCd the 2-character transaction type code
     * @param tranCatCd  the category code; must not be negative
     * @param charset    the code page to encode in, stated explicitly
     * @return the key image encoded, exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
     * @throws NullPointerException     if {@code tranTypeCd} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative, or {@code charset} is not a
     *                                  single-byte code page for the digits and the space
     */
    public static byte[] tranCatKeyBytes(String tranTypeCd, int tranCatCd, Charset charset) {
        return FixedWidthRecord.encodeText(tranCatKeyImage(tranTypeCd, tranCatCd, charset), charset,
                "a TRAN-CAT-KEY image");
    }

    /**
     * Rejects a negative {@code TRAN-CAT-CD}. {@code PIC 9(04)} is an unsigned picture with no sign
     * position, so a negative value has no representation in it and storing its magnitude instead
     * would invent data the COBOL could never hold.
     */
    private static void requireUnsignedCategoryCode(int tranCatCd) {
        if (tranCatCd < 0) {
            throw new IllegalArgumentException("TRAN-CAT-CD is " + tranCatCd + ", but CVTRA04Y "
                    + "declares it PIC 9(04) - an unsigned picture with no sign position, so a "
                    + "negative category code cannot be represented");
        }
    }

    /**
     * {@code TRAN-TYPE-CD} - the 2-character transaction type code, the key's first half.
     *
     * <p>A {@link String} and not a number, so {@code "01"} stays {@code "01"} and never collapses to
     * {@code 1}. Returned as the full {@link #TRAN_TYPE_CD_LENGTH} characters, untrimmed.
     *
     * @return exactly {@link #TRAN_TYPE_CD_LENGTH} characters
     */
    public String tranTypeCd() {
        return tranTypeCd;
    }

    /**
     * {@code TRAN-CAT-CD} - the transaction category code, the key's second half, decoded from its
     * 4-digit zoned {@code DISPLAY} image. The fixture's {@code 0001} reads back as {@code 1}.
     *
     * @return the category code, never negative
     */
    public int tranCatCd() {
        return tranCatCd;
    }

    /**
     * {@code TRAN-CAT-TYPE-DESC} - the category description, <strong>untrimmed</strong>: all
     * {@link #TRAN_CAT_TYPE_DESC_LENGTH} characters, trailing spaces included.
     *
     * <p>The padding is part of the field's value and the parity differ compares it, so it is never
     * removed here. A caller that needs the report form applies the receiver's own width at the point
     * of use, exactly as {@code app/cbl/CBTRN03C.cbl:368} does into a {@code PIC X(29)} field:
     * <pre>
     *   String reportCatDesc = codec.movePicX(record.tranCatTypeDesc(), 29);
     * </pre>
     * which keeps the leading 29 characters, because COBOL truncates an alphanumeric {@code MOVE} on
     * the right.
     *
     * @return exactly {@link #TRAN_CAT_TYPE_DESC_LENGTH} characters, never trimmed
     */
    public String tranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * The trailing {@code FILLER} span's characters, verbatim.
     *
     * <p>For a record decoded from {@code app/data/ASCII/trancatg.txt} this is {@code "0000"} - four
     * ASCII zeros, which is what that dataset actually stores - and for a record built by
     * {@link #of(String, int, String, Charset)} it is four spaces, the initialised default. The
     * content is reported as found and never normalised, because a re-emitted image has to match the
     * row it came from.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return filler;
    }

    /**
     * The trailing {@code FILLER} span's bytes, as a copy.
     *
     * @return a fresh array of exactly {@link #FILLER_LENGTH} bytes
     */
    public byte[] fillerBytes() {
        return Arrays.copyOfRange(image, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);
    }

    /**
     * The 6-byte {@code TRAN-CAT-KEY} image of this record, as the raw six characters.
     *
     * <p>This is what {@code app/cbl/CBTRN03C.cbl:507} renders on the not-found path,
     * {@code DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY}, so the six characters appear
     * verbatim in the {@code SYSOUT} fingerprint the parity harness compares. It is taken straight
     * from the record's own bytes rather than recomposed from the decoded fields, which guarantees it
     * is byte-identical to what was read - leading zeros and all.
     *
     * @return exactly {@link #TRAN_CAT_KEY_LENGTH} characters, for example {@code "010001"}
     */
    public String tranCatKeyImage() {
        return tranCatKeyImage;
    }

    /**
     * The 6-byte {@code TRAN-CAT-KEY} of this record, as a copy of its bytes.
     *
     * @return a fresh array of exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
     */
    public byte[] tranCatKeyBytes() {
        return Arrays.copyOfRange(image, TRAN_CAT_KEY_OFFSET,
                TRAN_CAT_KEY_OFFSET + TRAN_CAT_KEY_LENGTH);
    }

    /**
     * The complete serialised record: all {@link #RECORD_LENGTH} bytes, as a copy.
     *
     * <p>For a record obtained from {@link #decode(byte[], Charset)} this reproduces the input row
     * <strong>byte for byte</strong>, {@code FILLER} included, because the original bytes are this
     * instance's state rather than something re-rendered from decoded fields.
     *
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     */
    public byte[] toByteArray() {
        return image.clone();
    }

    /**
     * The complete record decoded as text: all {@link #RECORD_LENGTH} characters, untrimmed.
     *
     * @return the whole record as characters, exactly {@link #RECORD_LENGTH} of them
     */
    public String toImage() {
        return FixedWidthRecord.decodeText(image, charset, "a TRAN-CAT-RECORD image");
    }

    /**
     * Reads any declared span of this record as text, untrimmed - the per-field raw access the parity
     * differ needs in order to compare field by field rather than as whole strings.
     *
     * <p>Pass one of this class's own {@link FieldSpan} constants. The bounds are checked against the
     * record area, so a descriptor belonging to a different, wider record fails here rather than
     * reading past the end.
     *
     * @param field the span to read, normally one of {@link #TRAN_TYPE_CD}, {@link #TRAN_CAT_CD},
     *              {@link #TRAN_CAT_TYPE_DESC} or {@link #FILLER}
     * @return the span's characters, exactly {@code field.length()} of them
     * @throws NullPointerException      if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside this 60-byte record
     */
    public String fieldImage(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span of a "
                + "TRANCATG record");
        return toRecordArea().readSpan(field);
    }

    /**
     * Reads any declared span of this record as bytes, as a copy.
     *
     * @param field the span to read
     * @return a fresh array of exactly {@code field.length()} bytes
     * @throws NullPointerException      if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside this 60-byte record
     */
    public byte[] fieldBytes(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span of a "
                + "TRANCATG record");
        return toRecordArea().readSpanBytes(field);
    }

    /**
     * A fresh, independent record area over a copy of this record's bytes - the mutable working
     * storage form, for a caller that needs to address the record by absolute offset.
     *
     * <p>The area is a copy, so mutating it cannot affect this instance and this type stays immutable.
     *
     * @return a new {@link FixedWidthRecord} of {@link #RECORD_LENGTH} bytes in this record's charset
     */
    public FixedWidthRecord toRecordArea() {
        return FixedWidthRecord.copyOf(image, RECORD_LENGTH, charset);
    }

    /**
     * The code page this record's bytes are in.
     *
     * @return the charset supplied when the record was decoded or built
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Value equality over the record's bytes and its code page.
     *
     * <p>The bytes are compared rather than the decoded fields, so two records agreeing on every
     * decoded value but differing in their {@code FILLER} are correctly unequal - which is what a
     * byte-for-byte parity comparison requires. The charset participates because the same bytes
     * decode to different characters under a different code page.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code TranCategoryRecord} with identical bytes and
     *         charset
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranCategoryRecord that)) {
            return false;
        }
        return charset.equals(that.charset) && Arrays.equals(image, that.image);
    }

    /**
     * A hash consistent with {@link #equals(Object)}, over the record's bytes and its code page.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * charset.hashCode() + Arrays.hashCode(image);
    }

    /**
     * A diagnostic rendering naming each COBOL item and quoting the two character fields so their
     * padding stays visible - the {@code FILLER} in particular, which is zeros in this dataset and
     * spaces in a built record.
     *
     * @return for example
     *         {@code TranCategoryRecord[TRAN-CAT-KEY='010001', TRAN-TYPE-CD='01', TRAN-CAT-CD=1,
     *         TRAN-CAT-TYPE-DESC='Regular Sales Draft', FILLER='0000', charset=US-ASCII]}
     */
    @Override
    public String toString() {
        return "TranCategoryRecord[" + TRAN_CAT_KEY_NAME + "='" + tranCatKeyImage
                + "', TRAN-TYPE-CD='" + tranTypeCd
                + "', TRAN-CAT-CD=" + tranCatCd
                + ", TRAN-CAT-TYPE-DESC='" + tranCatTypeDesc
                + "', FILLER='" + filler
                + "', charset=" + charset.name() + ']';
    }
}
