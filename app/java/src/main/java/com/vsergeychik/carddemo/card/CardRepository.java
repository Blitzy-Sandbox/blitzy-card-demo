package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * The one data-access component for the CardDemo card master: the {@code CARDDAT} base KSDS and the
 * {@code CARDAIX} alternate-index path over it.
 *
 * <h2>One repository, one logical dataset, two keys (gate G45)</h2>
 * <p><strong>{@code CARDAIX} is an access path, not a table.</strong> This is the headline constraint
 * on this file, so it is stated before anything else. The card data exists once, as a single base
 * cluster; {@code CARDAIX} is an alternate-index path <em>over that same base</em>, keyed on the
 * account id instead of the card number. It therefore becomes an additional finder method here -
 * {@link #readByAccountIdViaAltIndex(long)} - and never a second repository, never a second relation
 * and never a join. Three independent pieces of evidence in the reference tree establish this:
 * <ul>
 *   <li>{@code app/csd/CARDDEMO.CSD:14} binds {@code CARDAIX} to a name that differs from the card
 *       base cluster's name at {@code :26} only by trading the base cluster's suffix for an
 *       alternate-index path suffix. Two names, one physical cluster;</li>
 *   <li>the sibling cross-reference alternate index is self-documenting about what the pattern means:
 *       {@code app/csd/CARDDEMO.CSD:64} reads
 *       {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)} - an index <em>to</em> a base
 *       file, reached <em>via</em> a different key;</li>
 *   <li>{@code app/jcl/INTCALC.jcl:29-32} proves it mechanically by opening one dataset twice inside a
 *       single job step, {@code XREFFILE} on the base and {@code XREFFIL1} on the path, and
 *       {@code app/cbl/CBACT04C.cbl:34-38} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} together
 *       with {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} on one {@code SELECT}. One file, two
 *       keys.</li>
 * </ul>
 * Both bindings are consequently validated in the constructor to declare the same
 * {@value #RECORD_LENGTH}-byte record and, where configuration states it, to agree that
 * {@code CARDAIX} indexes the card base.
 *
 * <h2>The record is {@code app/cpy/CVACT02Y.cpy}, all {@value #RECORD_LENGTH} bytes of it</h2>
 * <p>Every read decodes, and every write emits, exactly this layout. {@link CardRecord} owns it; the
 * offsets are restated here because this class is where the bytes cross the wire, and a reviewer
 * checking a statement against the copybook should not have to leave the file:
 * <table border="1">
 *   <caption>{@code CVACT02Y} byte layout, 0-based and half-open</caption>
 *   <tr><th>Field</th><th>PICTURE</th><th>Offset</th><th>Length</th><th>Role here</th></tr>
 *   <tr><td>{@code CARD-NUM}</td><td>{@code X(16)}</td><td>0</td><td>16</td>
 *       <td>{@code CARDDAT} primary key</td></tr>
 *   <tr><td>{@code CARD-ACCT-ID}</td><td>{@code 9(11)}</td><td>16</td><td>11</td>
 *       <td>{@code CARDAIX} alternate key</td></tr>
 *   <tr><td>{@code CARD-CVV-CD}</td><td>{@code 9(03)}</td><td>27</td><td>3</td><td>payload</td></tr>
 *   <tr><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code X(50)}</td><td>30</td><td>50</td>
 *       <td>payload</td></tr>
 *   <tr><td>{@code CARD-EXPIRAION-DATE}</td><td>{@code X(10)}</td><td>80</td><td>10</td>
 *       <td>payload</td></tr>
 *   <tr><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code X(01)}</td><td>90</td><td>1</td>
 *       <td>payload</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(59)}</td><td>91</td><td>59</td>
 *       <td>emitted as 59 spaces on every write</td></tr>
 * </table>
 * <p>{@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150}, matching the copybook's own {@code (RECLN 150)}
 * header comment at {@code app/cpy/CVACT02Y.cpy:2} and every one of the fifty rows of
 * {@code app/data/ASCII/carddata.txt}, each measured at exactly {@value #RECORD_LENGTH} bytes.
 *
 * <p>Two consequences bind every statement below:
 * <ul>
 *   <li><strong>The trailing {@code FILLER X(59)} is written, always.</strong>
 *       {@link #rewrite(CardRecord)} sends the whole {@value #RECORD_LENGTH}-byte image produced by
 *       {@link CardRecord#encode(FixedWidthCodec)}, which space-fills bytes 91 through 149. Dropping
 *       the reserved span would produce a 91-byte record and shift every later byte offset in the
 *       dataset. The legacy code does the same thing for the same reason:
 *       {@code app/cbl/COCRDUPC.cbl:1461} issues {@code INITIALIZE CARD-UPDATE-RECORD} - which
 *       space-fills an alphanumeric {@code FILLER} - before rewriting at
 *       {@code LENGTH(LENGTH OF CARD-UPDATE-RECORD)}, and that record, declared at
 *       {@code app/cbl/COCRDUPC.cbl:314-321}, carries its own {@code FILLER PIC X(59)} at
 *       {@code :321} and so is a full {@value #RECORD_LENGTH} bytes wide. The rewrite here is
 *       full width for exactly that reason, and is never a partial-length one;</li>
 *   <li><strong>a row that is not {@value #RECORD_LENGTH} bytes wide is not a card record.</strong>
 *       It is reported as a length error rather than padded into shape. Padding is right for the
 *       cross-reference fixture, whose rows genuinely omit a trailing reserved span; it would be
 *       wrong here, where the fixture measures exactly {@value #RECORD_LENGTH} and a short row can
 *       only mean the backend is not serving the layout this class was built against.</li>
 * </ul>
 *
 * <h2>{@code CARD-EXPIRAION-DATE} is misspelled in the copybook, and stays misspelled</h2>
 * <p>{@code app/cpy/CVACT02Y.cpy:9} declares {@code CARD-EXPIRAION-DATE}, missing the {@code T} of
 * {@code EXPIRATION}. {@link CardRecord#cardExpiraionDate()} reproduces the misspelling and this
 * class propagates it without comment in code and with comment in prose. It is not a typo to be
 * repaired: no correctly-spelled equivalent field exists anywhere in the repository, so there is no
 * "correct" form to prefer, and the parity differ compares fields <em>by name</em> - renaming one
 * would make a genuine difference invisible to the only check that can catch it.
 *
 * <h2>Numbers here are integers, and nothing else</h2>
 * <p>{@code CVACT02Y} declares no packed-decimal item and no signed or scaled picture, so no scaled
 * decimal type, no rounding policy and no monetary helper appears in this file - there is nothing
 * for one to do. {@code CARD-ACCT-ID} is a scale-free {@code 9(11)} and travels as a {@code long};
 * {@code CARD-CVV-CD} is a scale-free {@code 9(03)} and travels as an {@code int}. No approximate
 * numeric type is used for any value derived from a picture clause, here or anywhere in the module.
 *
 * <h2>Every operation, and the COBOL it came from</h2>
 * <p>The method surface is derived from what the three card programs actually do, and stops there.
 * Nothing is offered because a data-access component "usually" offers it:
 * <table border="1">
 *   <caption>Operations and their legacy provenance</caption>
 *   <tr><th>Method</th><th>CICS command</th><th>Source</th></tr>
 *   <tr><td>{@link #readByCardNumber(String)}</td><td>{@code READ}</td>
 *       <td>{@code COCRDSLC:736-773} {@code 9100-GETCARD-BYACCTCARD}, and the identical
 *           {@code COCRDUPC:1376-1412}</td></tr>
 *   <tr><td>{@link #readByAccountIdViaAltIndex(long)}</td><td>{@code READ} via the path</td>
 *       <td>{@code COCRDSLC:779-809} {@code 9150-GETCARD-BYACCT}</td></tr>
 *   <tr><td>{@link #readForUpdateByCardNumber(String)}</td><td>{@code READ ... UPDATE}</td>
 *       <td>{@code COCRDUPC:1427-1436}, inside {@code 9200-WRITE-PROCESSING}</td></tr>
 *   <tr><td>{@link #rewrite(CardRecord)}</td><td>{@code REWRITE}</td>
 *       <td>{@code COCRDUPC:1477-1483}</td></tr>
 *   <tr><td>{@link #startBrowse(String, BrowseDirection)}</td><td>{@code STARTBR ... GTEQ}</td>
 *       <td>{@code COCRDLIC:1129-1136} forward and {@code :1273-1280} backward</td></tr>
 *   <tr><td>{@link CardBrowse#readNext()}</td><td>{@code READNEXT}</td>
 *       <td>{@code COCRDLIC:1146-1154} and {@code :1197-1205}</td></tr>
 *   <tr><td>{@link CardBrowse#readPrev()}</td><td>{@code READPREV}</td>
 *       <td>{@code COCRDLIC:1294-1302} and {@code :1322-1330}</td></tr>
 *   <tr><td>{@link CardBrowse#endBrowse()}</td><td>{@code ENDBR}</td>
 *       <td>{@code COCRDLIC:1258} and {@code :1375-1377}</td></tr>
 * </table>
 * <p>There is deliberately <strong>no add and no delete</strong>. The CICS definitions grant both -
 * all eight files are defined with an identical {@code ADD(YES) DELETE(YES)} capability set - but no
 * card program exercises either against this file, and a capability nothing uses is not a
 * requirement. Offering them would widen the surface past the migration's contract and add branches
 * that no test could justify.
 *
 * <h2>The alternate-index read is coded in the legacy source but never reached</h2>
 * <p>Worth knowing before you look for its callers. {@code 9150-GETCARD-BYACCT} is complete and
 * correct COBOL, and <strong>no paragraph performs it</strong>: {@code COCRDSLC}'s
 * {@code 9000-READ-DATA} ({@code :726-730}) performs {@code 9100} alone, the only other occurrences
 * of {@code 9150} in the file are its own label and its {@code -EXIT} label, and the
 * {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID} that would position it is commented out at
 * {@code :739}. The path's file-name literal is likewise declared and unused in
 * {@code COCRDLIC}, {@code COCRDUPC} and {@code COACTVWC}.
 *
 * <p>It is migrated anyway, exactly as written. Preserving code the legacy system does not reach is
 * the standing rule for this migration - the same rule that keeps a documented no-op stub a no-op and
 * keeps an untriggered batch job runnable - because deciding that unreached code is unwanted code is a
 * redesign decision, and this is not a redesign. It is recorded here so that no reader mistakes the
 * absence of callers for an oversight, and so that no reader wires one up.
 *
 * <h2>Responses: the caller's guard chain is reproduced, not reinterpreted</h2>
 * <p>Every operation returns a discriminated result built from {@link FileStatus} constants, carrying
 * the record where there is one, the mapped {@link Outcome}, and <strong>both</strong> raw response
 * values. Both, because {@code COCRDUPC}'s {@code WS-FILE-ERROR-MESSAGE} composes a byte-exact
 * 80-character diagnostic out of the operation name, the file name, the response and the reason code:
 * collapsing the pair into a boolean would make that message unbuildable. The composition itself
 * belongs to the controllers, and the raw values exist so they can build it.
 *
 * <p>The three card programs agree on the shape of the guard chain. {@code COCRDSLC:752-772} and
 * {@code COCRDUPC:1392-1412} are the same three-armed {@code EVALUATE WS-RESP-CD} -
 * {@code WHEN DFHRESP(NORMAL)}, {@code WHEN DFHRESP(NOTFND)}, {@code WHEN OTHER} - and the mapping
 * below preserves it arm for arm:
 * <table border="1">
 *   <caption>Backend condition to CICS response, and the arm it lands on</caption>
 *   <tr><th>Condition</th><th>Response</th><th>Status</th><th>Arm</th></tr>
 *   <tr><td>the keyed record was returned</td><td>{@code NORMAL}</td><td>{@code '00'}</td>
 *       <td>{@code WHEN DFHRESP(NORMAL)}</td></tr>
 *   <tr><td>no record carries that key</td><td>{@code NOTFND}</td><td>{@code '23'}</td>
 *       <td>{@code WHEN DFHRESP(NOTFND)}</td></tr>
 *   <tr><td>the first of several records shares an alternate key</td><td>{@code DUPKEY}</td>
 *       <td>{@code '22'}</td><td>record returned, more exist</td></tr>
 *   <tr><td>the browse ran past the last record</td><td>{@code ENDFILE}</td><td>{@code '10'}</td>
 *       <td>{@code WHEN DFHRESP(ENDFILE)}</td></tr>
 *   <tr><td>a row that is not {@value #RECORD_LENGTH} bytes wide</td><td>{@code LENGERR}</td>
 *       <td>none</td><td>{@code WHEN OTHER}</td></tr>
 *   <tr><td>the dataset could not be reached at all</td><td>{@code NOTOPEN}</td><td>none</td>
 *       <td>{@code WHEN OTHER}</td></tr>
 *   <tr><td>the request failed for any other reason</td><td>{@code INVREQ}</td><td>none</td>
 *       <td>{@code WHEN OTHER}</td></tr>
 * </table>
 * <p>All four two-character batch statuses are therefore reachable and testable from this class, which
 * is what gate G47 asks for. The three responses that map to no batch status map to none here either:
 * inventing one for them would fabricate behaviour the COBOL never had, and
 * {@link FileStatus#batchStatusOfCicsResp(int)} reports the absence truthfully instead.
 *
 * <p>A record that is not there is <strong>not an exception</strong>. Both programs treat
 * {@code NOTFND} as an ordinary arm that sets a screen message, so it is returned as a result and
 * never thrown. Nor does anything here abend: the three card programs handle failure with
 * {@code EXEC CICS HANDLE ABEND} ({@code COCRDSLC:250-252}, {@code COCRDUPC:370-372}) rather than by
 * calling the Language Environment abend service, and not one of the nine abend call sites in the
 * estate is in this package. The module's abend type is consequently absent from this file by design,
 * and the only exceptions raised are for arguments a caller could not legally have passed.
 *
 * <h2>Statelessness, and why the browse cursor is returned rather than held (rule R6)</h2>
 * <p>This is a singleton bean with <strong>no mutable state whatsoever</strong>. Every field is
 * {@code final}; every collaborator arrives through the constructor; the only {@code static} members
 * are immutable constants. A browse has to remember where it is, so that memory lives in the
 * {@link CardBrowse} handle the caller is <em>given</em> - one per browse, never a field here.
 *
 * <p>That is what lets the online programs stay pseudo-conversational without server-side session
 * state. A browse is positioned by key on each step rather than by holding a backend cursor open
 * across a conversation, so two users paging two card lists cannot interfere with each other, and a
 * cursor cannot outlive the request that made it. {@code COCRDLIC} works the same way: it carries the
 * first and last key of the displayed page in its own communication area and re-positions from them,
 * which is precisely why a keyed browse reproduces it faithfully.
 *
 * <p>Both {@code STARTBR} sites specify {@code GTEQ} ({@code COCRDLIC:1133} and {@code :1277}), so
 * positioning is "the first key at or after the one supplied" in both directions, and
 * {@link #startBrowse(String, BrowseDirection)} preserves that. Both sites also capture the
 * {@code STARTBR} response and then <strong>never test it</strong> - {@code :1140} and {@code :1284}
 * move straight on to initialising the row counter - so no start-of-browse status is surfaced here
 * either. The first read is where the legacy guard chain actually begins, and it is where this class
 * reports its first outcome. {@code ENDBR} is captured even less: neither site specifies a response
 * option at all, which is why {@link CardBrowse#endBrowse()} yields nothing.
 *
 * <h2>Paging is the controller's business, not this class's (gate G39)</h2>
 * <p>The card list shows seven rows a page. That number appears <strong>nowhere in this file</strong>,
 * and must not. It is behaviour, fixed by {@code COCRDLIC}'s {@code WS-MAX-SCREEN-LINES} and by the
 * screen that has room for seven rows and no more - not a tunable. A repository that knew a page size
 * would be a repository that had to be configured with one, and making it configurable is exactly what
 * the gate forbids. This class hands back one record per read and counts nothing; the controller
 * counts, decides when a page is full, and remembers the keys it needs to page again.
 *
 * <h2>The JDBC driver is a deployment-time input (residual risk R-E)</h2>
 * <p>No driver coordinate is pinned anywhere in the module, so <strong>production connectivity cannot
 * be exercised in this build environment</strong>. That is stated rather than worked around. The
 * {@code DataSource} is fully configuration-bound; the site's mainframe data-access driver, its URL
 * and its credentials are supplied at deployment time; and this class invents none of them. It is
 * validated instead against the fixture-backed harness and against unit tests that drive every branch
 * with no backend in the path at all.
 *
 * <p>What this class does assume about the backend is therefore kept to the minimum a statement cannot
 * do without, and every part of it is traceable to a reference file rather than chosen here:
 * <ul>
 *   <li><strong>the relation is named by the configured dataset name</strong>, read from
 *       {@code carddemo.datasets.CARDDAT} and {@code carddemo.datasets.CARDAIX} and quoted as a
 *       delimited identifier. No dataset name appears in this file, which is gate G46;</li>
 *   <li><strong>the relation's first column carries the whole fixed-width record image</strong>. Reads
 *       address it by position; where a statement must name it - an {@code ORDER BY}, a {@code WHERE},
 *       the {@code SET} of a rewrite - the name is <em>discovered</em> from result-set metadata at that
 *       position, because the name is a site-specific deployment detail while the position is the
 *       contract. <strong>No copybook field name is used as a SQL column name.</strong> {@code CARD-NUM}
 *       names a span of {@code app/cpy/CVACT02Y.cpy}, and asking a backend for a column so named would
 *       assert a relational schema nothing in this repository describes - while this same class reads
 *       the whole record image out of column one, so both cannot be true of one backend;</li>
 *   <li><strong>a key is addressed by offset and length</strong>, taken from
 *       {@link CardRecord#cardDatPrimaryKeySpan()} and {@link CardRecord#cardAixAlternateKeySpan()}
 *       rather than spelled out here, so the key this class binds and the field the copybook declares
 *       cannot drift apart. The predicate is an escaped {@code LIKE} over the record image - core SQL
 *       every backend implements, unlike the substring functions whose spelling differs between
 *       dialects.</li>
 * </ul>
 * All of that lives in {@link DatasetRelation}, the module's one data-access contract, so it is decided
 * once for every repository rather than once per repository. What this class will send is visible before
 * it starts through {@link #describeBaseStatement()} and {@link #describeAlternateIndexStatement()},
 * and the composed statements are exposed to this class's own tests through
 * {@link #resolvedStatements()}.
 *
 * <h2>Keys are moved, never assigned</h2>
 * <p>A key is built with {@link FixedWidthCodec}, never by Java assignment or by
 * {@code String.format}. This matters more than it looks: a COBOL {@code MOVE} truncates an
 * alphanumeric receiver on the <em>right</em> and a numeric receiver on the <em>left</em>, and it is
 * the receiver's picture clause that decides which. {@code CARD-NUM} is {@code PIC X(16)}, so its key
 * is space-padded and truncated on the right; {@code CARD-ACCT-ID} is {@code PIC 9(11)}, so its key is
 * zero-filled and truncated on the left. Getting that backwards produces a key that looks plausible
 * and reads the wrong record, and {@code MOVE} is the single most frequent statement in the estate, so
 * the rule is applied through the codec at every site rather than restated at any of them.
 *
 * <h2>User-specified rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user rule governs this file</strong>. Its absence is not licence
 * to lower the bar: the migration plan elevates twelve enterprise practices to binding constraints in
 * their place, and the ones bearing on this file are honoured as follows. B1: no dependency is added,
 * so there is no accessor generator and no mapping framework here - the constructors, accessors and
 * result types are written out by hand. B2: only APIs the pinned framework generation manages are
 * used, and none of them deprecated; a newer generation's data-access API is not reached for. B3: the
 * reference trees are read-only and are cited purely as provenance. B4: no scope creep and no silent
 * correction - the two disagreements this file touches, the unreached alternate-index paragraph and
 * the record-format declaration that differs between the online and batch definitions, are documented
 * above rather than tidied away. B5: that unreached paragraph is migrated exactly as written, because
 * deciding unreached code is unwanted code is a redesign decision. B6: no credential is read, held,
 * logged or transformed here, and no authentication machinery is introduced. B7: every branch is
 * reachable from a plain unit test with a stubbed template, with no HTTP and no job launcher in the
 * path, which is what makes the per-package branch-coverage gate achievable deterministically. B8:
 * explicit imports with no wildcard, the code page passed in rather than defaulted, and every dataset
 * name externalised. B9: constructor injection only, no field or setter injection, no static mutable
 * state, and browse position held in a returned handle. B10: the tests are authored with this file and
 * ship in the same change, not bolted on afterwards. B11: the record image is addressed by absolute
 * offset through a hand-written codec, so every byte position is diffable against the copybook. B12:
 * the unreachable production backend is documented above and the driver is left as a deployment-time
 * input rather than being invented.
 *
 * <h2>What this class deliberately does not do</h2>
 * <ul>
 *   <li>it defines nothing. There is no data-definition statement of any kind, no schema migration, no
 *       persistence mapping, no version column and no index creation (gate G44). The optimistic
 *       concurrency the legacy code performs is a field-by-field re-read and compare, which lives in
 *       the update service, and a version column would be a schema change;</li>
 *   <li>it does not touch the cross-reference file. The three card programs use this file and its path
 *       and nothing else; the cross-reference has its own repository;</li>
 *   <li>it does not trim. A fixed-width field's padding is data, so a decoded record carries it, and a
 *       key is padded to its declared width rather than compared loosely;</li>
 *   <li>it does not interpret. A card's status stays a one-character string, an expiry stays an
 *       unvalidated ten-character string, and neither is parsed into a richer type - parsing would
 *       reject values the legacy system accepts.</li>
 * </ul>
 *
 * @see CardRecord
 * @see FileStatus
 */
@Repository
public class CardRepository {

    /**
     * Diagnostics for the one arm of each guard chain the COBOL cannot describe for itself - the
     * {@code WHEN OTHER} case, where the driver failed the request. The outcome that travels back to
     * the caller is deliberately coarse, because the COBOL's own arm is coarse, so the reason is
     * logged here rather than discarded: a production failure has to stay diagnosable.
     *
     * <p>{@code static final} and immutable, so it introduces no shared mutable state.
     */
    private static final Log LOG = LogFactory.getLog(CardRepository.class);

    // =================================================================================================
    // Dataset identity. Keys only - never a dataset name (gate G46).
    // =================================================================================================

    /**
     * The configuration key, and CICS {@code FILE} name, of the card base cluster:
     * {@code app/csd/CARDDEMO.CSD:25}. Its dataset name is declared at
     * {@code carddemo.datasets.CARDDAT.dsname} and is read from configuration, never written here.
     */
    public static final String BASE_DD_NAME = "CARDDAT";

    /**
     * The configuration key, and CICS {@code FILE} name, of the alternate-index path over the card
     * base: {@code app/csd/CARDDEMO.CSD:13}. Its dataset name is declared at
     * {@code carddemo.datasets.CARDAIX.dsname}.
     */
    public static final String ALTERNATE_INDEX_DD_NAME = "CARDAIX";

    /**
     * The <strong>batch</strong> DD name of the same base cluster: {@code CARDFILE}.
     *
     * <p>{@code app/cbl/CBACT02C.cbl:29} is {@code SELECT CARDFILE-FILE ASSIGN TO CARDFILE}, and
     * {@code app/jcl/READCARD.jcl:25-26} binds {@code //CARDFILE DD} over
     * {@code CARDDATA.VSAM.KSDS} - the very dataset {@link #BASE_DD_NAME} addresses online. One
     * dataset, two DD names: the CSD's for the transactions and the JCL's for the batch reader.
     *
     * <p>It exists because a batch job must read through <em>its own</em> DD. Resolving
     * {@link #BASE_DD_NAME} on a job's behalf would mean the job validated one binding and performed
     * its I/O against another, so a deployment that re-pointed {@code CARDFILE} - which is exactly
     * what a {@code //CARDFILE DD} statement is for - would read a dataset nobody asked for while
     * every startup check reported success. {@link #addressing(DatasetBinding, String)} is how a job
     * hands its resolved binding in.
     *
     * <p>A key, not a dataset name (gate G46).
     */
    public static final String BATCH_DD_NAME = "CARDFILE";

    /**
     * The declared width of a CICS file-name literal in the legacy programs: {@code PIC X(8)}. The
     * two literals below are held at this width because the COBOL holds them at this width, and one
     * of them reaches a screen through {@code ERROR-FILE PIC X(9)}.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    /**
     * The base cluster's CICS file-name literal, {@value #CICS_FILE_NAME_LENGTH} characters
     * <strong>including the trailing space</strong>: three programs declare it identically, at
     * {@code app/cbl/COCRDLIC.cbl:213-214}, {@code app/cbl/COCRDSLC.cbl:187-188} and
     * {@code app/cbl/COCRDUPC.cbl:251-252}.
     *
     * <p>The trailing space is not decoration. {@code COCRDUPC}'s {@code WS-FILE-ERROR-MESSAGE}
     * ({@code app/cbl/COCRDUPC.cbl:133-152}) composes a byte-exact 80-character diagnostic by moving
     * this literal into {@code ERROR-FILE PIC X(9)}, so its width is part of the message's width.
     * That composition belongs to the controllers; the literal is surfaced here at its declared width
     * so they need not restate it. It is a file <em>name</em>, not a dataset name.
     */
    public static final String BASE_CICS_FILE_NAME = "CARDDAT ";

    /**
     * The alternate-index path's CICS file-name literal, {@value #CICS_FILE_NAME_LENGTH} characters
     * including the trailing space, declared identically at {@code app/cbl/COCRDLIC.cbl:215-217},
     * {@code app/cbl/COCRDSLC.cbl:189-190}, {@code app/cbl/COCRDUPC.cbl:253-254} and
     * {@code app/cbl/COACTVWC.cbl:190-191}.
     */
    public static final String ALTERNATE_INDEX_CICS_FILE_NAME = "CARDAIX ";

    /**
     * The bean name of the module's active dataset code page.
     *
     * <p>Taken from {@link CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME}, the class that publishes the
     * bean, rather than restated as a literal here. A second spelling of a bean name is a rename waiting
     * to break silently: the qualifier would still compile, still resolve at startup against the old
     * name, and fail only when the publisher moved on. Kept as a constant of this class so a caller or a
     * test that referred to it still can.
     */
    public static final String DATASET_CHARSET_BEAN_NAME =
            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME;

    // =================================================================================================
    // Record geometry and row shape.
    // =================================================================================================

    /**
     * The fixed width of a card record in bytes, {@value #RECORD_LENGTH}, taken from
     * {@link CardRecord#RECORD_LENGTH} so that one copybook-derived constant governs the model, the
     * statements and the length checks alike.
     *
     * <p>The CICS definitions declare {@code RECORDFORMAT(V)} while the batch JCL declares
     * {@code RECFM=F} or {@code RECFM=FB} for the very same dataset. That disagreement is already
     * settled for the whole module and is not reopened here: record length is copybook-fixed, so this
     * class models no variable-length record and treats every row as exactly this wide.
     */
    public static final int RECORD_LENGTH = CardRecord.RECORD_LENGTH;

    /**
     * How a stored record image is named in a decoding diagnostic - never its content.
     *
     * <p>{@link FixedWidthCodec#decodeImage(byte[], String)} refuses a byte the dataset code page does
     * not define rather than substituting a replacement character, and it needs a name for what it was
     * decoding. That name is a source citation, because the stored bytes of a card record are a
     * customer's account number and embossed name and must not reach a log or an exception message.
     */
    private static final String RECORD_IMAGE_SUBJECT =
            "the stored CARD-RECORD image displayed by app/cbl/CBACT02C.cbl:78";

    /**
     * The ordinal position of the fixed-width record image in a fetched row: {@value}.
     *
     * <p>Reads address the image <strong>by position</strong>, so no column name is needed to get a
     * record out of the backend. That is the whole point: it keeps every read free of any assumption
     * about how the deployment-time driver spells things.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;


    /**
     * The reason code reported when the backend supplies none: {@value}.
     *
     * <p>CICS pairs every response with a condition-specific {@code RESP2} reason code, and the legacy
     * programs move both onto the screen - {@code app/cbl/COCRDSLC.cbl:769-770} and
     * {@code app/cbl/COCRDUPC.cbl:1409-1410} move {@code WS-RESP-CD} and {@code WS-REAS-CD} into
     * {@code ERROR-RESP} and {@code ERROR-RESP2}. Where a genuine reason code is available it is
     * reported; where the backend offers none, this value stands for "no reason code", which is what
     * CICS itself reports for a normal response.
     */
    public static final int NO_REASON_CODE = 0;

    /**
     * The {@code ERROR-OPNAME} value the legacy code reports for a failed read:
     * {@code app/cbl/COCRDSLC.cbl:767}, {@code app/cbl/COCRDUPC.cbl:1407} and
     * {@code app/cbl/COCRDLIC.cbl:1226}, {@code :1250}, {@code :1312} and {@code :1365} all move the
     * literal {@code 'READ'}. Note that the browse sites report it too: the legacy code names the
     * <em>logical</em> operation, not the CICS verb, and that is reproduced rather than improved on.
     */
    public static final String READ_OPERATION_NAME = "READ";

    /**
     * The operation name for a rewrite. The legacy code never reports one - {@code COCRDUPC} sets a
     * condition flag on a failed rewrite ({@code app/cbl/COCRDUPC.cbl:1491}) instead of composing a
     * file-error message - so this exists for logging and diagnostics only and reaches no screen.
     */
    public static final String REWRITE_OPERATION_NAME = "REWRITE";

    /**
     * Row limit for a keyed read on a unique key: {@value}. The base key is the record's own
     * {@code CARD-NUM}, so at most one record can match, and the statement says so.
     */
    private static final int SINGLE_ROW = 1;

    /**
     * Row limit for a keyed read: {@value}.
     *
     * <p>One row more than a single record, on both keyed paths, and the extra row means something
     * different on each:
     * <ul>
     *   <li><strong>through the alternate index</strong> - an alternate key need not be unique, since an
     *       account may carry more than one card, and CICS reports that by returning the first record
     *       together with a duplicate-key response. One row means a unique match, two mean the first of
     *       several, and the read succeeds either way.</li>
     *   <li><strong>on the base cluster</strong> - {@code CARD-NUM} is the cluster's unique primary key,
     *       so a second matching row is not a condition VSAM can present. It is an integrity defect in
     *       the backing relation, and detecting it is the point: a limit of one would make it invisible
     *       and the read would quietly return whichever row the backend ordered first.</li>
     * </ul>
     * Nothing beyond the second row is fetched on either path, because nothing beyond it changes the
     * answer.
     */
    private static final int DUPLICATE_DETECTION_ROW_LIMIT = 2;

    /**
     * How many rows the pre-rewrite probe looks at: two.
     *
     * <p>The question is "none, one, or more than one" - the three outcomes a rewrite distinguishes - and
     * the third is settled by the second row. Counting further would transfer rows to refine a number
     * nothing reads.
     */
    private static final int FAN_OUT_PROBE_LIMIT = 2;


    /**
     * The {@code CARDDAT} primary key span, {@code CARD-NUM PIC X(16)} at offset 0, taken from the
     * model so that the key this class binds and the field the copybook declares cannot drift apart.
     * Immutable, so it introduces no shared mutable state.
     */
    private static final FieldSpan BASE_KEY_FIELD = CardRecord.cardDatPrimaryKeySpan();

    /**
     * The {@code CARDAIX} alternate key span, {@code CARD-ACCT-ID PIC 9(11)} at offset 16, taken from
     * the model for the same reason. Note the offset: the sibling cross-reference record carries an
     * eleven-byte account id too, but at a different offset, and confusing the two compiles cleanly
     * and reads the wrong records. Naming both spans from the model is what prevents that.
     */
    private static final FieldSpan ALTERNATE_KEY_FIELD = CardRecord.cardAixAlternateKeySpan();

    /**
     * Where the primary key sits inside the record image, as an access path rather than a column.
     *
     * <p>Offset and length, taken from {@link #BASE_KEY_FIELD} - the field's <em>name</em> is
     * deliberately not carried across. {@code CARD-NUM} names a span of {@code app/cpy/CVACT02Y.cpy},
     * not a column of any relation: this module reaches a dataset as a relation whose first column
     * carries the whole 150-byte record image, and a statement that asked a backend for a column called
     * {@code CARD-NUM} would be asserting a schema nothing in the repository describes. The predicate is
     * therefore expressed over the record image itself, at this offset for this length.
     */
    private static final KeySpan BASE_KEY_SPAN =
            new KeySpan(BASE_KEY_FIELD.offset(), BASE_KEY_FIELD.length());

    /**
     * Where the alternate key sits inside the record image: offset 16 for 11 bytes.
     *
     * <p>The offset is what distinguishes this from the cross-reference record's account id, which is
     * also eleven bytes and is also an alternate key but lives at offset 25. Two access paths over two
     * datasets, and transposing them compiles cleanly while reading the wrong records - which is why
     * both are derived from their model's declared span rather than written out here.
     */
    private static final KeySpan ALTERNATE_KEY_SPAN =
            new KeySpan(ALTERNATE_KEY_FIELD.offset(), ALTERNATE_KEY_FIELD.length());

    // =================================================================================================
    // Injected collaborators and composed statements. Every field is final: this bean holds no mutable
    // state, and a browse's position lives in the handle the caller is given (practice B9).
    // =================================================================================================

    /** The module's single template, constructor-injected. The only route to the backend. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The hand-written fixed-width codec, carrying the dataset code page. It owns the pad-and-truncate
     * rules for both picture classes, so no key is ever built by plain assignment.
     */
    private final FixedWidthCodec codec;

    /**
     * The one representation every read, write and comparison operand of this dataset uses.
     *
     * <p>Injected rather than decided here, and its arrival deleted the worst symptom in this file: this
     * repository used to read the record image as bytes and, if the driver returned none, read the same
     * column again as characters and encode it - a fallback across two representations of one column - and
     * then bind text on the way back out. A fallback like that cannot be wrong loudly. It returns a
     * plausible 150 bytes either way, so a driver presenting the column as the other type produced records
     * that decoded into fields at the right offsets holding the wrong values. There is now one answer, it
     * comes from configuration, and there is no second path to fall back to.
     */
    private final RecordImageForm recordImageForm;

    /**
     * The base cluster as this module reaches it: the validated dataset name, its delimited rendering,
     * the record-image column it discovers, and every statement composed over it.
     *
     * <p>Shared with every other repository in the module, so how a dataset name becomes a SQL
     * identifier and how a key at a byte offset becomes a predicate is decided in one place.
     */
    private final DatasetRelation baseRelation;

    /**
     * The alternate-index path as a relation of its own.
     *
     * <p>A second <strong>access path</strong> over the same records, not a second table and not a
     * second copy of the data (gate G45): {@code app/csd/CARDDEMO.CSD} defines {@code CARDAIX} as
     * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH} over the {@code CARDDAT} base cluster, and the
     * constructor refuses a binding whose name does not sit under the base. It is a distinct relation
     * here only because the deployment addresses it by a distinct name.
     */
    private final DatasetRelation alternateIndexRelation;

    // =================================================================================================
    // Resolved shape. One field, and it stands for something the backend tells us rather than something
    // this class decides: which column of each relation carries the record image, and therefore what
    // each statement's text is.
    // =================================================================================================

    /**
     * The composed statements, resolved on first use.
     *
     * <p>Lazily rather than at construction because the record-image column's name is discovered from
     * the backend, and a repository must be constructible in a context that has not reached its backend
     * - which is also what lets every geometry check in the constructor fail fast at context refresh
     * rather than mid-transaction. {@code null} means "not yet resolved"; the value is deeply immutable,
     * so publishing it hands out nothing alterable.
     *
     * <p><strong>What is memoised is the statement TEXT, never the proof that the dataset is there.</strong>
     * {@link #openBrowse(String, BrowseDirection)} composes afresh through {@link #composeStatements()} on
     * every call, because it stands for the {@code STARTBR} that {@code app/cbl/CBACT02C.cbl:120} performs
     * as {@code OPEN INPUT} and tests at {@code :121}. Reaching the open through this memo made its outcome
     * depend on whether an earlier operation had populated the field: a dataset dropped since the last
     * successful pass was reported as opened, and the failure appeared one line later under the read
     * paragraph's message, {@code 'ERROR READING CARDFILE'} instead of {@code 'ERROR OPENING CARDFILE'}
     * ({@code :129}).
     *
     * <p>{@code volatile}, because a repository is a singleton reached from several threads and
     * {@link Statements} is a deeply immutable record: a volatile write publishes it safely and a volatile
     * read never sees a partially initialised one.
     */
    private volatile Statements statements;


    /**
     * Wiring constructor, used by the container.
     *
     * <p>It exists so the canonical constructor below can take the codec itself. The code page arrives
     * as an explicit argument, selected by bean name, so that the choice of code page is visible in the
     * wiring rather than buried in a default - a fixed-width mainframe record is bytes in a specific
     * code page, and the platform default is never consulted anywhere in this module.
     *
     * @param jdbcTemplate    the module's single template
     * @param datasetBindings the {@code carddemo.datasets} catalogue, from which this repository
     *                        resolves its two dataset names by key
     * @param datasetCharset  the active dataset code page
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either binding is absent, declares no usable dataset name, or
     *                               contradicts the copybook - see the canonical constructor
     */
    @Autowired
    public CardRepository(JdbcTemplate jdbcTemplate,
                          DatasetBindings datasetBindings,
                          @Qualifier(DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
                          RecordImageForm recordImageForm) {
        this(jdbcTemplate,
                datasetBindings,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: a fixed-width mainframe record is bytes in a "
                                + "specific code page, so the code page is stated explicitly and never "
                                + "taken from the platform")),
                recordImageForm);
    }

    /**
     * Canonical constructor. Resolves both dataset names from configuration, verifies each binding
     * against the copybook, and composes every statement this repository will ever send - once, here,
     * so that no statement is assembled per call and none can vary between calls.
     *
     * <p>The verification is deliberately strict, and it fails at construction rather than at first
     * use. A binding that disagrees with {@code app/cpy/CVACT02Y.cpy} would decode every field at the
     * wrong offset, and a mis-decoded record is far worse than an application that declines to start:
     * it is silently wrong data. Three things are checked:
     * <ul>
     *   <li>both bindings must declare a {@value #RECORD_LENGTH}-byte record, because that is what the
     *       copybook declares and what this class decodes by absolute offset;</li>
     *   <li>both must declare a usable dataset name, since the statements are composed from
     *       configuration alone;</li>
     *   <li>where the alternate-index binding states which base it indexes, or which field forms its
     *       alternate key, those statements must agree with this repository - the binding must index
     *       <em>this</em> base, on the account id. Where configuration is silent, nothing is asserted
     *       and nothing is assumed: an absent statement is not a contradiction.</li>
     * </ul>
     *
     * @param jdbcTemplate    the module's single template
     * @param datasetBindings the {@code carddemo.datasets} catalogue
     * @param codec           the fixed-width codec, carrying the dataset code page
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *                        {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if either binding is absent, declares a record width other than
     *                               {@value #RECORD_LENGTH}, declares no usable dataset name, or
     *                               contradicts this repository about what the alternate index indexes
     */
    public CardRepository(JdbcTemplate jdbcTemplate,
                          DatasetBindings datasetBindings,
                          FixedWidthCodec codec,
                          RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the card "
                + "file is reached through the module's single template");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it carries the "
                + "dataset code page and owns the MOVE semantics every key is built with");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "repository");
        RecordImageForm.requireSingleByteCodePage(this.codec.charset());

        DatasetBinding base = requireCardRecordBinding(datasetBindings, BASE_DD_NAME);
        DatasetBinding alternateIndex = requireCardRecordBinding(datasetBindings,
                ALTERNATE_INDEX_DD_NAME);
        requireAlternateIndexOverBase(alternateIndex);

        this.baseRelation = DatasetRelation.of(
                requireUsableDatasetName(base.dsname(), BASE_DD_NAME), RECORD_LENGTH);
        this.alternateIndexRelation = DatasetRelation.of(
                requireUsableDatasetName(alternateIndex.dsname(), ALTERNATE_INDEX_DD_NAME),
                RECORD_LENGTH);
    }

    /**
     * Re-binding constructor: the same repository addressing a different base cluster.
     *
     * <p>Private, and reached only through {@link #addressing(DatasetBinding, String)}. Everything that
     * decides <em>behaviour</em> - the template, the code page and {@code MOVE} semantics carried by the
     * codec, and how a record image crosses JDBC - is shared with the instance it was derived from, so a
     * re-bound repository is the same repository reading somewhere else. Only the base relation differs,
     * which is the whole point: a dataset name is the one thing a DD name selects.
     *
     * <p>{@link #statements} is deliberately <em>not</em> copied. Its text embeds the base relation's
     * identifier, so carrying it over would send the source instance's statements against this
     * instance's dataset - the exact defect this constructor exists to remove. It resolves again on
     * first use, against this relation.
     *
     * @param source         the instance whose collaborators are shared
     * @param baseRelation   the base cluster this instance addresses
     */
    private CardRepository(CardRepository source, DatasetRelation baseRelation) {
        this.jdbcTemplate = source.jdbcTemplate;
        this.codec = source.codec;
        this.recordImageForm = source.recordImageForm;
        this.baseRelation = baseRelation;
        this.alternateIndexRelation = source.alternateIndexRelation;
    }

    /**
     * Returns this repository addressing the base cluster a caller's own DD binding names.
     *
     * <p><strong>Why a batch job needs this.</strong> {@code CBACT02C} reads the card master through
     * the DD {@code app/jcl/READCARD.jcl:25-26} binds, and a job resolves that DD through its own view
     * of the catalogue - job-scoped entry first, global second - because a DD name is not unique across
     * this estate. This repository resolved {@link #BASE_DD_NAME} at construction, from the global
     * catalogue only. Those are two different resolutions, and a job that checked one and read through
     * the other would report a healthy start and then read whatever the other happened to name.
     *
     * <p>Returns {@code this} when the binding names the dataset already addressed, so the common
     * case - the shipped configuration, where {@code CARDFILE} and {@code CARDDAT} are two names for
     * one dataset - allocates nothing and shares the resolved statements.
     *
     * <p>No new access path and no schema change: the returned instance issues the same statements
     * against a relation built the same way (gate G44).
     *
     * @param binding the caller's resolved binding, normally from
     *                {@code BatchConfig.datasetBinding(jobKey, ddName)}
     * @param ddName  the DD name it was resolved for; used only to say which key is at fault
     * @return this repository, or one addressing the binding's dataset; never {@code null}
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if the binding declares a record width other than
     *                               {@value #RECORD_LENGTH}, or no usable dataset name
     */
    public CardRepository addressing(DatasetBinding binding, String ddName) {
        Objects.requireNonNull(ddName, "A DD name is required: it is what a diagnostic names when the "
                + "binding is at fault");
        Objects.requireNonNull(binding, "A resolved dataset binding is required for DD name '" + ddName
                + "': a batch job reads through the DD its JCL declares, not through the CICS file name "
                + "this repository resolved at construction");
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but the card file is decoded by "
                    + "absolute offset against a " + RECORD_LENGTH + "-byte record (app/cpy/CVACT02Y.cpy). "
                    + "Correct carddemo.datasets." + ddName + ".record-length to " + RECORD_LENGTH + ".");
        }
        String dsname = requireUsableDatasetName(binding.dsname(), ddName);
        if (dsname.equals(baseRelation.dsname())) {
            return this;
        }
        return new CardRepository(this, DatasetRelation.of(dsname, RECORD_LENGTH));
    }

    // =================================================================================================
    // What this repository resolved from configuration, and what it will send. Exposed so a deployment
    // can verify both without starting the application, and so the tests can assert them without a
    // backend (residual risk R-E).
    // =================================================================================================

    /**
     * The card base cluster's dataset name as configuration declared it.
     *
     * @return the value of {@code carddemo.datasets.CARDDAT.dsname}; never blank
     */
    public String baseDatasetName() {
        return baseRelation.dsname();
    }

    /**
     * The alternate-index path's dataset name as configuration declared it. It names a path over the
     * same base cluster, not a second dataset.
     *
     * @return the value of {@code carddemo.datasets.CARDAIX.dsname}; never blank
     */
    public String alternateIndexDatasetName() {
        return alternateIndexRelation.dsname();
    }

    /**
     * The statement that describes the base cluster without transferring a row.
     *
     * <p>Composed from the dataset name alone, so it is available before the backend has been reached
     * and a deployment can see exactly what this repository will ask for. It is what discovers the
     * record-image column's name.
     *
     * @return the describe statement over the base cluster
     */
    public String describeBaseStatement() {
        return baseRelation.describeStatement();
    }

    /**
     * The statement that describes the alternate-index path without transferring a row.
     *
     * @return the describe statement over the alternate-index path
     */
    public String describeAlternateIndexStatement() {
        return alternateIndexRelation.describeStatement();
    }

    /**
     * The resolved statements, or {@code null} while they have not been resolved.
     *
     * <p>Package-visible so this class's own tests can assert the composed text and the caching
     * behaviour without a backend. Safe to hand out because {@link Statements} is immutable.
     *
     * @return the resolved statements, or {@code null}
     */
    Statements resolvedStatements() {
        return statements;
    }


    // =================================================================================================
    // 9100-GETCARD-BYACCTCARD - app/cbl/COCRDSLC.cbl:736-773 and app/cbl/COCRDUPC.cbl:1376-1412.
    //
    // The two paragraphs are the same statement and the same three-armed EVALUATE; they differ only in
    // which screen flags each arm sets, which is the controllers' business and not this method's.
    // =================================================================================================

    /**
     * Reads one card by its card number, from the base cluster.
     *
     * <p>The COBOL is:
     * <pre>
     * MOVE CC-CARD-NUM       TO WS-CARD-RID-CARDNUM
     * EXEC CICS READ
     *      FILE      (LIT-CARDFILENAME)
     *      RIDFLD    (WS-CARD-RID-CARDNUM)
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-CARDNUM)
     *      INTO      (CARD-RECORD)
     *      LENGTH    (LENGTH OF CARD-RECORD)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     * {@code WS-CARD-RID-CARDNUM} is {@code PIC X(16)} ({@code COCRDSLC:98},
     * {@code COCRDUPC:129}), so the argument is subjected to an alphanumeric {@code MOVE} into a
     * sixteen-character receiver: a shorter value is padded on the right with spaces, a longer one is
     * truncated on the right. That is the receiver's rule, not a convenience - see this class's
     * documentation on moving rather than assigning.
     *
     * <p>Note that a blank or non-numeric card number is <em>not</em> rejected here. The legacy screen
     * edit rejects it first - {@code COCRDSLC:691-719} refuses a card filter that is blank, zero or
     * not numeric before any read happens - and a value that reached the file anyway would simply match
     * no record. Duplicating that edit here would put the same rule in two places and risk the two
     * disagreeing.
     *
     * @param cardNumber the card number to read, moved into the sixteen-character key. Any length is
     *                   accepted, because a {@code MOVE} accepts any length
     * @return the read outcome: the record on the normal arm, no record on the not-found arm, and the
     *         raw response pair on either of the failure arms. Never {@code null}
     * @throws NullPointerException if {@code cardNumber} is {@code null}. A COBOL alphanumeric field is
     *                              never absent - to search for spaces, pass spaces
     */
    public CardReadResult readByCardNumber(String cardNumber) {
        return readOnBaseCluster(cardNumber, false);
    }

    /**
     * Reads one card by its card number and holds it locked for a rewrite.
     *
     * <p>This is {@code app/cbl/COCRDUPC.cbl:1427-1436} - the same read as
     * {@link #readByCardNumber(String)} with the {@code UPDATE} option added. It is a separate method
     * because {@code COCRDUPC} issues both and branches differently on each, and because the lock is
     * the point: the record must not change between being read and being rewritten.
     *
     * <p>The guard chain that follows it is not the three-armed {@code EVALUATE} of the plain read. It
     * is a two-way test on whether the lock was taken at all ({@code :1441-1449}):
     * <pre>
     * IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
     *    CONTINUE
     * ELSE
     *    SET INPUT-ERROR                    TO TRUE
     *    IF  WS-RETURN-MSG-OFF
     *        SET COULD-NOT-LOCK-FOR-UPDATE  TO TRUE
     *    END-IF
     *    GO TO 9200-WRITE-PROCESSING-EXIT
     * END-IF
     * </pre>
     * Every non-normal response - a record that has since been deleted included - lands on the same
     * arm and abandons the update. So a caller here needs only {@link CardReadResult#isNormal()} to
     * reproduce it, and the distinct response values remain available for the diagnostic.
     *
     * @param cardNumber the card number to read and lock, moved into the sixteen-character key
     * @return the read outcome. A normal result carries the locked record; anything else is the
     *         could-not-lock arm. Never {@code null}
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    public CardReadResult readForUpdateByCardNumber(String cardNumber) {
        DatasetUnitOfWork.requireActive("A read-for-update of " + BASE_CICS_FILE_NAME.trim(),
                baseRelation.dsname());
        return readOnBaseCluster(cardNumber, true);
    }

    /**
     * Reads one card by account id, <strong>through the alternate-index path</strong>.
     *
     * <p>This is {@code app/cbl/COCRDSLC.cbl:779-809}, whose own comment at {@code :781} reads
     * {@code Read the Card file. Access via alternate index ACCTID}. It is the same statement shape as
     * the base read with two substitutions: the file is the path rather than the base cluster, and the
     * key is {@code WS-CARD-RID-ACCT-ID PIC 9(11)} rather than the card number. The path is where this
     * read goes; sending it to the base cluster would be gate G45's failure mode exactly.
     *
     * <p>An alternate key need not be unique, so this read has one arm the base read does not have: a
     * duplicate-key result, which carries the first matching record and tells the caller that more
     * exist. That is what CICS reports for a read through a path with a non-unique alternate key, and
     * it is not an error - the record is there and is returned.
     *
     * <p><strong>The legacy paragraph is never performed.</strong> See this class's documentation: it
     * is complete COBOL that no path reaches, and it is migrated as written rather than dropped.
     *
     * @param accountId the account id, moved into the eleven-digit key. This is the numeric view,
     *                  {@code CC-ACCT-ID-N PIC 9(11)}, whose alphanumeric redefinition
     *                  {@code CC-ACCT-ID PIC X(11)} is the same eleven bytes
     * @return the read outcome. Never {@code null}
     * @throws IllegalArgumentException if {@code accountId} is negative. {@code PIC 9(11)} is unsigned
     *                                  and has no sign position, so it cannot hold one
     */
    public CardReadResult readByAccountIdViaAltIndex(long accountId) {
        return readOnAlternateIndex(codec.movePic9(accountId, ALTERNATE_KEY_SPAN.length()));
    }

    /**
     * Reads one card by account id through the alternate-index path, taking the account id as digits.
     *
     * <p>The overload exists because the callers hold the account id in two COBOL views of one
     * eleven-byte span - {@code CC-ACCT-ID PIC X(11)} and {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC
     * 9(11)} ({@code app/cpy/CVCRD01Y.cpy}) - and both must reach the key identically. They do: this
     * overload applies the same numeric {@code MOVE} as {@link #readByAccountIdViaAltIndex(long)}, so
     * {@code "42"}, {@code "00000000042"} and {@code 42L} all produce the same eleven bytes.
     *
     * <p>Being a numeric {@code MOVE}, it zero-fills a short value on the <em>left</em> and truncates a
     * long one on the left, keeping the low-order digits. Keeping the leading digits instead is the
     * classic defect in this translation, which is why the codec owns the rule.
     *
     * @param accountIdDigits the account id as digits, of any length
     * @return the read outcome. Never {@code null}
     * @throws NullPointerException     if {@code accountIdDigits} is {@code null}
     * @throws IllegalArgumentException if {@code accountIdDigits} is not all digits. A
     *                                  {@code PIC 9(11)} receiver cannot hold a non-numeric value, and
     *                                  the legacy screen edit rejects one before any read
     */
    public CardReadResult readByAccountIdViaAltIndex(String accountIdDigits) {
        Objects.requireNonNull(accountIdDigits, "An account id is required to read the card file "
                + "through the alternate-index path; to search for zeroes, pass zeroes");
        return readOnAlternateIndex(codec.movePic9(accountIdDigits, ALTERNATE_KEY_SPAN.length()));
    }

    // =================================================================================================
    // 9200-WRITE-PROCESSING, the rewrite itself - app/cbl/COCRDUPC.cbl:1477-1483.
    // =================================================================================================

    /**
     * Rewrites a card record in place, at full record width.
     *
     * <p>The COBOL is:
     * <pre>
     * EXEC CICS
     *      REWRITE FILE(LIT-CARDFILENAME)
     *              FROM(CARD-UPDATE-RECORD)
     *              LENGTH(LENGTH OF CARD-UPDATE-RECORD)
     *              RESP      (WS-RESP-CD)
     *              RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     * followed by a two-way test ({@code :1488-1492}) that sets a
     * {@code LOCKED-BUT-UPDATE-FAILED} flag on anything other than a normal response.
     * {@code CARD-UPDATE-RECORD} ({@code :314-321}) is a full {@value #RECORD_LENGTH} bytes, its own
     * {@code FILLER PIC X(59)} included, so this is a full-width rewrite and not a partial-length one.
     *
     * <p>Three things follow, and all three are load-bearing:
     * <ul>
     *   <li>the image sent is the whole {@value #RECORD_LENGTH} bytes, with the reserved span
     *       space-filled. {@link CardRecord#encode(FixedWidthCodec)} guarantees that, and the guarantee
     *       is structural rather than checked: the record's layout cannot be constructed unless its
     *       seven spans are contiguous from offset zero and sum to exactly {@value #RECORD_LENGTH}, so
     *       a mis-sized layout fails at class initialisation rather than in a dataset;</li>
     *   <li>the key is the record's own card number, moved into the sixteen-character key exactly as a
     *       read would move it. A rewrite cannot change the key of the record it replaces;</li>
     *   <li>a rewrite that replaced no record is a failure, not a silent success. In CICS a
     *       {@code REWRITE} without a held record is an invalid request, and that is what is reported,
     *       with the number of records the key actually selected carried as a labelled
     *       {@link DatasetObservation} so the diagnostic says what happened.</li>
     * </ul>
     *
     * <p><strong>Fan-out is precluded, not reported afterwards.</strong> The predicate is an escaped
     * {@code LIKE} confined to the key span, and {@code CARD-NUM} is the base cluster's unique primary
     * key, so it selects one row. If a deployment's relation does not enforce that uniqueness, the same
     * {@code UPDATE} replaces every matching row with this one record - and reading that off the
     * affected-row count is reading it after the rows are already gone, which is a report of damage that
     * the enclosing unit of work then commits on the way out. So the key is required to select exactly
     * one row first, in the same transaction and, when one is open, under the same {@code FOR UPDATE}
     * lock the write will use; the write is not issued otherwise. Should the count still come back wrong
     * afterwards, the unit of work is refused rather than reported - see
     * {@link DatasetUnitOfWork#commitRefusal(String, String)}, which explains why that one case cannot be
     * a status. This is the same seam the account master's rewrite uses, deliberately: one rule for
     * one-record rewrites across the module.
     *
     * <p>This method does <strong>not</strong> check whether anyone else changed the record first. That
     * check is real and must not be lost - it is {@code 9300-CHECK-CHANGE-IN-REC}, a field-by-field
     * re-read and compare that {@code COCRDUPC} performs between the locking read and the rewrite
     * ({@code :1453-1457}) - but it is business logic and belongs to the update service. Doing it here
     * would put the decision in the wrong layer, and doing it with a version column would change the
     * schema, which is forbidden.
     *
     * <p><strong>A unit of work is required, and its absence is refused rather than reported.</strong>
     * {@code COCRDUPC} rewrites the record its own {@code READ ... UPDATE} holds, inside one CICS task, so
     * the boundary is not an addition - {@code CardUpdateService} already opens it with
     * {@link DatasetUnitOfWork#execute(String, java.util.function.Supplier)}. What the requirement rules
     * out is reaching this method without one: the pool hands out connections with auto-commit disabled,
     * so the {@code UPDATE} would execute, report the row it replaced, and be rolled back on return, and
     * this method would answer {@link CardWriteResult#normal()} for a record no later read could find. See
     * {@link DatasetUnitOfWork#requireActiveToPersist(String, String)}.
     *
     * @param record the record to write. Its card number identifies the record replaced
     * @return the write outcome: normal when exactly one record was replaced, otherwise the failure arm
     *         carrying the raw response pair. Never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted;
     *                               or as a
     *                               {@link com.vsergeychik.carddemo.common.DatasetIntegrityException}
     *                               if the write replaced more rows than the key selected when it was
     *                               checked, in which case the unit of work is refused rather than a
     *                               response returned
     */
    public CardWriteResult rewrite(CardRecord record) {
        Objects.requireNonNull(record, "A card record is required to rewrite one; there is no "
                + "partial-record rewrite, because CARD-UPDATE-RECORD is a full " + RECORD_LENGTH
                + "-byte record at app/cbl/COCRDUPC.cbl:314-321");
        // Refused before anything is attempted when no unit of work is open. The pool hands out
        // connections with auto-commit disabled, so the UPDATE would execute, report the row it replaced,
        // and then be rolled back on return - and this method would report the normal response for a
        // record no later read could find. See requireActiveToPersist.
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of " + BASE_CICS_FILE_NAME.trim()
                + ", which EXEC CICS REWRITE issues against the record the preceding READ ... UPDATE "
                + "still holds (app/cbl/COCRDUPC.cbl:1477-1492)", baseRelation.dsname());

        // Exactly RECORD_LENGTH bytes, with the reserved span space-filled. The width is not re-checked
        // here, and deliberately so: it is guaranteed where it belongs, by the model. CardRecord's
        // layout refuses to be constructed unless its seven spans are contiguous from offset zero and
        // sum to exactly RECORD_LENGTH - so a mis-sized layout fails at class initialisation, long
        // before any dataset is reached - and every alphanumeric component is held at its declared
        // width by the record's own constructor. Re-testing an invariant that cannot fail would add a
        // branch no test could reach, and an unreachable branch is worse than no branch: it looks like
        // a case someone forgot to cover.
        // Bound through the configured record-image representation - the same one this repository reads
        // with, and the same one every other dataset access in the module uses. The old comment here
        // argued that binding text was safe because the code page is single-byte; that argument was
        // sound and beside the point, because it was an argument this class made locally about a column
        // whose type only the deployment's driver knows. The representation is now stated once, in
        // configuration, so read and write cannot disagree and neither can two repositories.
        byte[] recordImage = record.encode(codec.charset());
        String keyPattern = BASE_KEY_SPAN.pattern(baseKeyOf(record.cardNum()));
        PreparedStatementSetter binder = parameters -> {
            recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
            recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
        };

        // Establish how many rows the key selects BEFORE any of them is replaced, under the same row lock
        // the UPDATE will use so the two see the same rows. Reading the affected-row count afterwards
        // would discover a fan-out only after the rows were overwritten.
        int selected;
        Statements sql;
        try {
            sql = resolveStatements();
            selected = fetch(sql.selectForUpdateByCardNumber(), keyPattern, FAN_OUT_PROBE_LIMIT)
                    .rowCount();
        } catch (DataAccessException rejected) {
            return CardWriteResult.failed(
                    responseOf(logRefusal(REWRITE_OPERATION_NAME, BASE_CICS_FILE_NAME,
                            "while establishing how many records its key selects", rejected)));
        }
        if (selected != SINGLE_ROW) {
            // Nothing has been written, and nothing will be. No row means a REWRITE with no held record,
            // which CICS reports as an invalid request; more than one means the relation is not the KSDS
            // the copybook describes, and issuing the write would replace all of them with this record.
            LOG.error("The key of a " + BASE_CICS_FILE_NAME.trim() + " record selects " + selected
                    + " row(s) where exactly " + SINGLE_ROW + " was expected; reporting the "
                    + "invalid-request response, which is what CICS reports for a REWRITE with no held "
                    + "record, without issuing the write. No row has been changed.");
            return CardWriteResult.failed(FileStatus.INVREQ,
                    DatasetObservation.matchingRows(selected));
        }

        int replaced;
        try {
            replaced = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            return CardWriteResult.failed(
                    responseOf(logRefusal(REWRITE_OPERATION_NAME, BASE_CICS_FILE_NAME,
                            "at full record width", rejected)));
        }
        if (replaced == SINGLE_ROW) {
            return CardWriteResult.normal();
        }
        if (replaced == 0) {
            // The row the probe found is gone. Nothing was written, so this is the same invalid request a
            // REWRITE with no held record is - which is what it would have been had the row never existed.
            LOG.error("A rewrite of " + BASE_CICS_FILE_NAME.trim() + " replaced no record although its "
                    + "key selected one when it was checked; reporting the invalid-request response");
            return CardWriteResult.failed(FileStatus.INVREQ, DatasetObservation.replacedRows(replaced));
        }
        // The probe said one row and the UPDATE replaced several, so the relation changed underneath it.
        // The damage is done and a response would let it commit, so the unit of work is refused instead.
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of a record of " + BASE_CICS_FILE_NAME.trim(),
                replaced + " rows were replaced where the key selected exactly one when it was checked "
                        + "under a row lock");
    }

    // =================================================================================================
    // STARTBR - app/cbl/COCRDLIC.cbl:1129-1136 forward and :1273-1280 backward.
    // =================================================================================================

    /**
     * Positions a browse at or after a card number and returns the handle that walks it.
     *
     * <p>The COBOL is, in both directions:
     * <pre>
     * EXEC CICS STARTBR
     *      DATASET(LIT-CARD-FILE)
     *      RIDFLD(WS-CARD-RID-CARDNUM)
     *      KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
     *      GTEQ
     *      RESP(WS-RESP-CD)
     *      RESP2(WS-REAS-CD)
     * END-EXEC
     * </pre>
     * {@code GTEQ} is specified at {@code :1133} and again at {@code :1277}, so positioning is "at or
     * after the supplied key" in both directions, and a key that matches no record positions at the
     * next higher one instead of failing.
     *
     * <p><strong>No status is returned, because the legacy code discards its own.</strong> Both sites
     * capture the response into {@code WS-RESP-CD} and then never test it: {@code :1140} moves zeroes
     * into the row counter and {@code :1284} computes its starting value, and neither looks at what
     * {@code STARTBR} said. Surfacing a status here would invite a caller to branch on something the
     * COBOL ignores, which is a behaviour change. The first read is where the guard chain begins, and
     * it is where the first outcome is reported.
     *
     * <p>Positioning performs no backend call. Each step of the browse re-positions by key instead of
     * holding a cursor open, which is what keeps the online layer free of server-side conversation
     * state - see this class's documentation on statelessness. A caller that needs the two to look
     * alike can still use the handle in a try-with-resources block; it is
     * {@link AutoCloseable}.
     *
     * @param cardNumber the key to position at, moved into the sixteen-character key. To begin at the
     *                   first record of the file, pass a value that sorts at or below every key - the
     *                   legacy code positions from the page keys it carries in its own communication
     *                   area
     * @param direction  which way the browse will be walked. It fixes which read the handle accepts,
     *                   exactly as the legacy code uses a separate {@code STARTBR} per direction
     * @return a fresh handle, positioned and not yet read from. Never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardBrowse startBrowse(String cardNumber, BrowseDirection direction) {
        Objects.requireNonNull(direction, "A browse direction is required: the legacy code issues a "
                + "separate STARTBR for each direction (app/cbl/COCRDLIC.cbl:1129 and :1273), so a "
                + "browse is never direction-less");
        return new CardBrowse(this, baseKeyOf(cardNumber), direction);
    }

    /**
     * Opens the base cluster for a sequential pass and reports whether it could be opened: the batch
     * entry point, for {@code OPEN INPUT CARDFILE-FILE} at {@code app/cbl/CBACT02C.cbl:120}.
     *
     * <p><strong>Why this exists alongside {@link #startBrowse(String, BrowseDirection)}.</strong> The
     * two callers of this cluster treat their open differently, and the difference is in the source.
     * {@code COCRDLIC} issues {@code EXEC CICS STARTBR}, captures the response and never tests it, so
     * {@code startBrowse} reports nothing and makes no call - surfacing a status there would invite a
     * caller to branch on something the COBOL ignores. {@code CBACT02C} declares
     * {@code FILE STATUS IS CARDFILE-STATUS} at {@code :33} and tests it at {@code :121-:127}, reaching
     * {@code DISPLAY 'ERROR OPENING CARDFILE'} at {@code :129} and an abend at {@code :132}. That arm is
     * unreachable unless the open can fail, so this entry point gives it something to fail on.
     *
     * <p>The probe describes the relation rather than reading it: it establishes that the dataset exists
     * and presents a record-image column without a single row crossing the wire, which is what an
     * {@code OPEN INPUT} establishes too. It cannot pass where a read would fail, because a read composes
     * its statement from the same describe.
     *
     * <p><strong>The probe runs on every call, never on the strength of an earlier one.</strong> It used to
     * go through the memoising statement accessor, so once any operation had resolved the statements the
     * describe was skipped and this method reported success over a dataset that had since been dropped -
     * with the failure appearing on the first read, wearing {@code 'ERROR READING CARDFILE'}
     * ({@code :110}) instead of {@code 'ERROR OPENING CARDFILE'} ({@code :129}). Whether an open succeeds
     * cannot depend on what ran before it, so the statement <em>text</em> is memoised and the proof that
     * the dataset is there is not.
     *
     * <p>The response is reported, never thrown, because {@code OPEN} has a {@code FILE STATUS} clause
     * and no exception, and the caller's own guard chain decides what a bad status means. What the backend
     * said is logged rather than discarded with it, so the abend that follows is diagnosable.
     *
     * @param cardNumber the key to position at or after, moved to the declared key width
     * @param direction  which way the pass is walked
     * @return a handle whose {@link CardBrowse#openResp()} reports whether the dataset was opened; never
     *         {@code null}
     * @throws NullPointerException if {@code direction} is {@code null}
     */
    public CardBrowse openBrowse(String cardNumber, BrowseDirection direction) {
        Objects.requireNonNull(direction, "A browse direction is required: the legacy code issues a "
                + "separate STARTBR for each direction (app/cbl/COCRDLIC.cbl:1129 and :1273), so a "
                + "browse is never direction-less");
        String key = baseKeyOf(cardNumber);
        try {
            // composeStatements() rather than resolveStatements(), and the difference is what makes the
            // open able to fail. The memoising accessor skips the describe once any earlier operation has
            // resolved the statements, so a dataset dropped since the last successful pass was reported as
            // opened and the failure surfaced on the first read under 'ERROR READING CARDFILE'. An open
            // proves the dataset is there every time it is asked to.
            composeStatements();
        } catch (DataAccessException translated) {
            LOG.error("Could not open a sequential pass over the " + BASE_DD_NAME + " base cluster - "
                    + BackendDiagnostic.of(translated).describe() + "; reporting CICS response "
                    + FileStatus.NOTOPEN + " to the caller, which is the arm that displays "
                    + "ERROR OPENING CARDFILE and abends");
            return new CardBrowse(this, key, direction, FileStatus.NOTOPEN);
        } catch (IllegalStateException unusable) {
            // The relation resolved but presents nothing at the record-image position, so there is no
            // record to read and no statement that could be composed over it. An open-time fact about the
            // dataset, reported from the open. The message is this module's own text and carries no value
            // the driver supplied, so it is safe to log verbatim.
            LOG.error("Could not open a sequential pass over the " + BASE_DD_NAME + " base cluster: "
                    + unusable.getMessage() + "; reporting CICS response " + FileStatus.NOTOPEN
                    + " to the caller");
            return new CardBrowse(this, key, direction, FileStatus.NOTOPEN);
        }
        return new CardBrowse(this, key, direction, FileStatus.NORMAL);
    }

    // =================================================================================================
    // The read seam. Every keyed read and every browse step funnels through here, so the guard chain is
    // written once and every arm of it is reachable from a plain unit test with a stubbed template.
    // =================================================================================================

    /**
     * Executes a keyed read against the base cluster and classifies the result.
     *
     * @param cardNumber the card number, moved into the sixteen-character key
     * @param locking    {@code true} to send the locking read rather than the plain one; also makes the
     *                   diagnostic say which of the two failed
     * @return the read outcome; never {@code null}
     */
    private CardReadResult readOnBaseCluster(String cardNumber, boolean locking) {
        String pattern = BASE_KEY_SPAN.pattern(baseKeyOf(cardNumber));
        try {
            Statements sql = resolveStatements();
            String statement = locking ? sql.selectForUpdateByCardNumber() : sql.selectByCardNumber();
            // One row more than a unique primary key can produce, so a violation of that uniqueness in
            // the backing relation is detected here rather than silently resolved by taking whichever row
            // the backend ordered first. See classifyRead's fan-out arm.
            FetchedRows rows = fetch(statement, pattern, DUPLICATE_DETECTION_ROW_LIMIT);
            return provenAbsence(classifyRead(rows, false), sql.probeUnreadableBaseRows(),
                    BASE_CICS_FILE_NAME);
        } catch (DataAccessException rejected) {
            return failedRead(READ_OPERATION_NAME, BASE_CICS_FILE_NAME,
                    locking ? "with the UPDATE option" : "without the UPDATE option", rejected);
        }
    }

    /**
     * Executes a keyed read through the alternate-index path and classifies the result, distinguishing
     * a unique match from the first of several.
     *
     * @param alternateKey the eleven-digit account key, already moved to its declared width
     * @return the read outcome; never {@code null}
     */
    private CardReadResult readOnAlternateIndex(String alternateKey) {
        String pattern = ALTERNATE_KEY_SPAN.pattern(alternateKey);
        try {
            Statements sql = resolveStatements();
            FetchedRows rows = fetch(sql.selectByAccountId(), pattern, DUPLICATE_DETECTION_ROW_LIMIT);
            return provenAbsence(classifyRead(rows, true), sql.probeUnreadableAlternateRows(),
                    ALTERNATE_INDEX_CICS_FILE_NAME);
        } catch (DataAccessException rejected) {
            return failedRead(READ_OPERATION_NAME, ALTERNATE_INDEX_CICS_FILE_NAME,
                    "through the alternate-index path", rejected);
        }
    }

    /**
     * Turns a keyed read's {@code NOTFND} into the invalid-request response when the relation holds a row
     * that <strong>cannot be read</strong>, and leaves every other outcome exactly as it was.
     *
     * <h2>Why an absence has to be proved</h2>
     * <p>{@code CARD-NUM} lives <em>inside</em> the record image - it is its leading sixteen bytes - so a
     * row whose record-image column holds nothing has no knowable key. The keyed predicate cannot match
     * it, which leaves the read with no matching row and, on the face of it, {@code NOTFND}. But
     * {@code NOTFND} is a positive claim: {@code app/cbl/COCRDSLC.cbl:755-761} takes it as "there is no
     * such card" and sets a screen message saying so, and {@code COCRDUPC} takes it as licence to carry
     * on. Making that claim while an unreadable row is sitting in the relation reports a record that is
     * present as absent - which is precisely what the sibling arm in {@link #classifyRead(FetchedRows,
     * boolean)} refuses to do for a row it can see.
     *
     * <p>So a not-found answer is confirmed with one further row-limited read before it is returned. It
     * costs one round trip, on the not-found path only, and it is the only place this class's answer
     * changes: a read that found its record is untouched, because a corrupt row elsewhere in the dataset is
     * none of that read's business - a VSAM {@code READ} of a key that resolves does not fail because
     * another record is damaged.
     *
     * <p>The response is {@link FileStatus#INVREQ}, the same {@code WHEN OTHER} response the visible form
     * of this condition already reports, so the caller's guard chain is unchanged and the batch caller
     * lands on {@code MOVE 12 TO APPL-RESULT} ({@code app/cbl/CBACT02C.cbl:101}) exactly as it would for
     * any other unreadable record.
     *
     * @param classified          the outcome the read classified to
     * @param unreadableRowsProbe the probe over the access path that was read - the base cluster's for a
     *                            keyed read, the alternate-index path's for a read through the path, so
     *                            the relation that answered "nothing matched" is the one asked to prove
     *                            it. In a deployment both names address one dataset (gate G45), which
     *                            makes the two probes the same statement there
     * @param fileName            the CICS file name for the diagnostic
     * @return {@code classified} unless it was {@code NOTFND} and the relation holds an unreadable row, in
     *         which case the invalid-request outcome; never {@code null}
     * @throws DataAccessException if the backend refuses the probe, which the caller reports as it reports
     *                             a refusal of the read itself
     */
    private CardReadResult provenAbsence(CardReadResult classified, String unreadableRowsProbe,
                                         String fileName) {
        if (!classified.isNotFound()) {
            return classified;
        }
        FetchedRows unreadable = fetchUnparameterised(unreadableRowsProbe, SINGLE_ROW);
        if (unreadable.rowCount() == 0) {
            // A genuine WHEN DFHRESP(NOTFND): no row matched the key and no row of the relation is
            // unreadable, so the absence is established rather than assumed.
            return classified;
        }
        LOG.error("A keyed read of " + fileName.trim() + " matched no row, but the relation holds a row "
                + "with no record image at position " + RECORD_IMAGE_COLUMN_INDEX + " - and CARD-NUM is "
                + "part of that image, so that row's key cannot be known; reporting the invalid-request "
                + "response rather than reporting as absent a record that may well be the one asked for");
        return CardReadResult.failed(FileStatus.INVREQ);
    }

    /**
     * Sends a statement that takes no parameter and brings back at most {@code rowLimit} rows' worth of
     * answer.
     *
     * <p>The same extractor and the same row-limit discipline as {@link #fetch(String, String, int)}: one
     * shape for reading rows, so the unreadable-row probe cannot drift from the reads it qualifies.
     *
     * @param statement the statement to send
     * @param rowLimit  the most rows worth fetching
     * @return what came back; never {@code null}
     * @throws DataAccessException if the backend rejected the request
     */
    private FetchedRows fetchUnparameterised(String statement, int rowLimit) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            // Both limits, as everywhere else: one bounds what the backend hands over, the other what it
            // carries across the round trip. One row settles the question this probe asks.
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            return prepared;
        };
        ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, rowLimit);
        FetchedRows rows = jdbcTemplate.query(creator, extractor);
        // A template that answered with nothing has told us nothing. Reported as no rows, which leaves the
        // not-found answer standing rather than converting it on the strength of an absent answer.
        return rows == null ? FetchedRows.empty() : rows;
    }

    /**
     * Executes one browse step and classifies the result. The only difference from a keyed read is what
     * an empty result means: on a browse it is the end of the file, not a missing record.
     *
     * @param statement the anchor statement or the advancing statement for the browse's direction
     * @param parameter the value the statement compares against: the escaped key pattern for the
     *                  anchor, the previous whole record image for an advancing step
     * @param operation the operation name for the diagnostic
     * @return the read outcome; never {@code null}
     */
    private BrowseStep browseStep(String statement, String parameter, String operation) {
        return browseStep(keyedStatement(statement, parameter, SINGLE_ROW), operation);
    }

    /**
     * Executes one browse step that advances past a stored record image.
     *
     * <p>The image is bound as an <em>image</em> and not as a comparison operand composed here, because
     * that is what it is: the exact bytes the backend handed over on the previous step. Binding it any
     * other way would send a re-encoding of a stored value and compare it with the stored values it was
     * derived from - which is the whole of finding BD-08 in a different disguise.
     *
     * @param statement the advancing statement for the browse's direction
     * @param position  the previous row's record image, exactly as the backend presented it
     * @param operation the operation name for the diagnostic
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private BrowseStep browseStepAfter(String statement, byte[] position, String operation) {
        return browseStep(imageStatement(statement, position, SINGLE_ROW), operation);
    }

    /**
     * The shared body of both browse steps.
     *
     * @param creator   the prepared, limited and bound statement
     * @param operation the operation name for the diagnostic
     * @return the outcome and, on a record, the exact image it came from; never {@code null}
     */
    private BrowseStep browseStep(PreparedStatementCreator creator, String operation) {
        try {
            ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, SINGLE_ROW);
            FetchedRows fetched = jdbcTemplate.query(creator, extractor);
            FetchedRows rows = fetched == null ? FetchedRows.empty() : fetched;
            if (rows.rowCount() == 0) {
                // WHEN DFHRESP(ENDFILE). COCRDLIC:1233-1245 and :1215-1221 rely on this being
                // distinguishable: it is what terminates paging and what sets the "no more records to
                // show" message and the no-next-page flag.
                return new BrowseStep(CardReadResult.endOfFile(), null);
            }
            // The exact image travels back with the outcome so the browse can advance past the bytes it
            // was actually given, rather than past a re-encoding of the record decoded from them.
            return new BrowseStep(classifyRead(rows, false), rows.firstImage());
        } catch (DataAccessException rejected) {
            return new BrowseStep(
                    failedRead(operation, BASE_CICS_FILE_NAME, "during a browse", rejected), null);
        }
    }

    /**
     * Builds a statement whose single parameter is a stored record image, capped at a row limit.
     *
     * @param statement the statement text
     * @param image     the stored image to bind
     * @param rowLimit  the row limit to apply
     * @return a creator that prepares and binds the statement
     */
    private PreparedStatementCreator imageStatement(String statement, byte[] image, int rowLimit) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        };
    }

    /**
     * Sends one statement with one key and brings back at most {@code rowLimit} rows' worth of answer.
     *
     * @param statement the statement to send
     * @param key       the single bind value
     * @param rowLimit  the most rows worth fetching; beyond it nothing changes the answer
     * @return what came back; never {@code null}
     * @throws DataAccessException if the backend rejected the request
     */
    private FetchedRows fetch(String statement, String key, int rowLimit) {
        PreparedStatementCreator creator = keyedStatement(statement, key, rowLimit);
        ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, rowLimit);
        FetchedRows rows = jdbcTemplate.query(creator, extractor);
        // A template that answered with nothing has told us nothing, and "nothing" is not a record. It
        // is reported as an empty result, which each caller then reads in its own terms - a missing
        // record on a keyed read, the end of the file on a browse step.
        return rows == null ? FetchedRows.empty() : rows;
    }

    /**
     * Builds the statement for a keyed read: one bind value, and a row limit that says out loud how
     * many rows can possibly matter.
     *
     * <p>Package-private so the tests can exercise it directly against a stubbed connection, which is
     * the only way to prove the row limit is actually applied without a backend.
     *
     * <p>The operand is bound through the configured record-image representation, because every operand
     * this repository sends - a keyed {@code LIKE} pattern, a browse anchor key, the image a browse
     * advances past - is compared against the record-image column. Binding it any other way would run the
     * comparison across two representations of one column.
     *
     * @param statement the statement text
     * @param key       the single bind value
     * @param rowLimit  the row limit to apply
     * @return a creator that prepares and binds the statement
     */
    PreparedStatementCreator keyedStatement(String statement, String key, int rowLimit) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            // Both limits, deliberately: one bounds what the backend will hand over, the other bounds
            // what it will carry across in a round trip. A keyed read on a unique key cannot want more
            // than one record, and a browse step cannot want more than one either.
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            recordImageForm.bindOperand(prepared, 1, key, codec.charset());
            return prepared;
        };
    }

    /**
     * Reads at most {@code rowLimit} rows, keeping the first record image and counting what arrived.
     *
     * <p>Package-private for direct testing against a stubbed result set.
     *
     * @param resultSet the rows to read
     * @param rowLimit  the most rows to read
     * @return the first image, if any, and how many rows arrived
     * @throws SQLException if reading a row failed
     */
    FetchedRows extractRows(ResultSet resultSet, int rowLimit) throws SQLException {
        byte[] firstImage = null;
        int rowCount = 0;
        while (rowCount < rowLimit && resultSet.next()) {
            if (rowCount == 0) {
                firstImage = readRecordImage(resultSet);
            }
            rowCount++;
        }
        return new FetchedRows(firstImage, rowCount);
    }

    /**
     * Reads the fixed-width record image out of the current row, by position.
     *
     * <p>Both shapes a backend might present it in are accepted, because which one arrives is a
     * property of the deployment-time driver and not of this migration (residual risk R-E): a binary
     * value is taken as the bytes it already is, and a character value is encoded with the dataset code
     * page rather than with any platform default.
     *
     * <p>Package-private for direct testing against a stubbed result set.
     *
     * @param resultSet positioned on the row to read
     * @return the record image, or {@code null} if the row carries none
     * @throws SQLException if reading the value failed
     */
    byte[] readRecordImage(ResultSet resultSet) throws SQLException {
        return recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    /**
     * Turns what came back into the arm of the guard chain it belongs on.
     *
     * <p>This is the {@code EVALUATE WS-RESP-CD} of {@code app/cbl/COCRDSLC.cbl:752-772}, expressed
     * once. The order matters and is the COBOL's order: a missing record first, then the reasons a row
     * that is present might still not be a readable card record, then the normal arm.
     *
     * <p>{@code alternateIndex} decides what a <em>second</em> matching row means, and the two meanings
     * are opposite. Through the path it is expected - an account may carry several cards - and yields the
     * first record with the duplicate-key response. On the base cluster it is impossible, because
     * {@code CARD-NUM} is the unique primary key, and yields the invalid-request response with no record
     * at all. Only the keyed reads can present more than one row; both browse steps fetch a single row,
     * so a sequential pass never reaches either arm.
     *
     * @param rows           what the read brought back
     * @param alternateIndex {@code true} when the read went through the path, where a second matching
     *                       row means the alternate key is not unique rather than that the file is
     *                       corrupt
     * @return the classified outcome; never {@code null}
     */
    private CardReadResult classifyRead(FetchedRows rows, boolean alternateIndex) {
        if (rows.rowCount() == 0) {
            // WHEN DFHRESP(NOTFND). An ordinary arm, never an exception: COCRDSLC:755-761 sets a screen
            // message and carries on.
            return CardReadResult.notFound();
        }
        if (!alternateIndex && rows.rowCount() > SINGLE_ROW) {
            // Fan-out on the BASE CLUSTER's key. CARDDAT is a KSDS whose CARD-NUM is its unique primary
            // key (app/csd/CARDDEMO.CSD), so more than one match cannot arise in the legacy system and is
            // an integrity defect in the backing relation.
            //
            // It is REPORTED, not resolved. Returning the first of several with the normal response would
            // hand the caller one card record chosen by whatever order the backend happened to produce,
            // with nothing to say a choice was made - and COCRDSLC and COCRDUPC then display or REWRITE
            // it. The duplicate-key response is not the answer either: CICS reports DUPKEY for a read
            // through a PATH on a non-unique alternate key, which is a documented and expected condition,
            // whereas a non-unique PRIMARY key is not a condition VSAM can present at all. So this lands
            // on WHEN OTHER with the invalid-request response, carrying the row count as the observation.
            LOG.error("A keyed read of " + BASE_CICS_FILE_NAME.trim() + " matched " + rows.rowCount()
                    + " rows, but CARD-NUM is the base cluster's unique primary key; reporting the "
                    + "invalid-request response rather than returning an arbitrary one of them as though "
                    + "it were the record. The backing relation needs a unique constraint on its key span");
            return CardReadResult.failed(FileStatus.INVREQ,
                    DatasetObservation.matchingRows(rows.rowCount()));
        }
        byte[] recordImage = rows.firstImage();
        if (recordImage == null) {
            // There is a record and it cannot be read. That is not a missing record - reporting it as
            // one would tell the caller something false - so it lands on WHEN OTHER.
            LOG.error("A row of " + BASE_CICS_FILE_NAME.trim() + " carries no record image at "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting the invalid-request "
                    + "response rather than reporting a record that is present as absent");
            return CardReadResult.failed(FileStatus.INVREQ);
        }
        if (recordImage.length != RECORD_LENGTH) {
            // The receiver is CARD-RECORD, declared 150 bytes by app/cpy/CVACT02Y.cpy. A row of any
            // other width is not one, and the length is carried as the reason code so the diagnostic
            // says how wide it actually was. It is NOT padded into shape: every row of
            // app/data/ASCII/carddata.txt measures exactly 150, so a short row here can only mean the
            // backend is not serving this layout, and hiding that would hide a real defect.
            LOG.error("A row of " + BASE_CICS_FILE_NAME.trim() + " is " + recordImage.length
                    + " byte(s) wide, but CARD-RECORD is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CVACT02Y.cpy; reporting a length error rather than decoding "
                    + "fields from offsets that would not be theirs");
            return CardReadResult.failed(FileStatus.LENGERR,
                    DatasetObservation.recordWidth(recordImage.length));
        }
        CardRecord record = CardRecord.decode(recordImage, codec);
        // The stored bytes, decoded once, character for character, and carried alongside the decoded
        // fields. READ ... INTO CARD-RECORD moves the whole record area, so DISPLAY CARD-RECORD
        // (app/cbl/CBACT02C.cbl:78) writes bytes this record model cannot reproduce: CARD-RECORD ends
        // with FILLER X(59), which carries no field and which a re-encode would therefore emit as
        // fifty-nine spaces whatever the row actually held. The decode is strict - an unmappable byte
        // is refused rather than replaced - so this String is the row's bytes or nothing.
        String storedImage = codec.decodeImage(recordImage, RECORD_IMAGE_SUBJECT);
        if (alternateIndex && rows.rowCount() > 1) {
            // The record is returned AND the caller is told more share this alternate key, which is
            // what CICS reports for a read through a path on a non-unique key.
            return CardReadResult.duplicateKey(record, storedImage);
        }
        // WHEN DFHRESP(NORMAL).
        return CardReadResult.normal(record, storedImage);
    }

    /**
     * Moves a card number into the base cluster's key at its declared width.
     *
     * @param cardNumber the sending value, of any length
     * @return the key, exactly {@code CARD-NUM}'s sixteen characters
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    private String baseKeyOf(String cardNumber) {
        Objects.requireNonNull(cardNumber, "A card number is required to key the card file; a COBOL "
                + "alphanumeric field is never absent, so to search for spaces pass spaces");
        return codec.movePicX(cardNumber, BASE_KEY_SPAN.length());
    }

    /**
     * Reads the base cluster's key out of a stored record image.
     *
     * <p>The leading {@link FieldSpan} of the layout, decoded in the dataset code page - so a browse's
     * remembered key comes out of the same bytes as its remembered position and the two cannot disagree.
     * Used for diagnostics only; nothing branches on it.
     *
     * <p>The width is not re-checked here, for the reason the rewrite gives for not re-checking it
     * either: it is guaranteed where it belongs. The only caller is the browse, which reaches this line
     * only on a record that {@code classifyRead} has already established is exactly
     * {@value #RECORD_LENGTH} bytes - a row of any other width never becomes a returned record at all. A
     * second test of that invariant would add a branch no test could reach, and an unreachable branch is
     * worse than no branch: it reads as a case someone forgot to cover.
     *
     * @param recordImage the stored image of a returned record, and so exactly
     *                    {@value #RECORD_LENGTH} bytes
     * @return the key, exactly {@code CARD-NUM}'s sixteen characters
     * @throws NullPointerException if {@code recordImage} is {@code null}
     */
    private String keyOfImage(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to read a key out of it");
        return new String(recordImage, 0, BASE_KEY_SPAN.length(), codec.charset());
    }

    // =================================================================================================
    // Failure translation. What the backend said is read from the backend; what the caller branches on
    // is a CICS response value, and the two are never confused for one another.
    // =================================================================================================

    /**
     * Maps a backend refusal to the CICS response value the legacy guard chain would have seen.
     *
     * <p>The classification is by {@code SQLSTATE} <strong>class</strong> - the two-character prefix the
     * SQL standard defines and every conforming driver reports - and not by the exception class a
     * framework wrapped the failure in. A framework's hierarchy is that framework's opinion about those
     * codes: it is not part of any driver's contract, it changes between versions, and a mapping built on
     * it silently mis-reports the moment a driver is swapped. {@code SQLSTATE} does not move.
     *
     * <p>The mapping itself is deliberately narrow, and every target is an existing
     * {@link FileStatus} constant rather than a number chosen here:
     * <ul>
     *   <li>class {@code 08}, a connection exception - the backend could not be reached at all, which is
     *       what CICS reports as {@link FileStatus#NOTOPEN}: the file is not available to the task;</li>
     *   <li>class {@code 42}, a syntax error or access-rule violation - the relation does not exist or
     *       this identity may not reach it, which is again a file that is not open to the task;</li>
     *   <li>class {@code 40}, a transaction rollback - a deadlock or serialisation failure. The record
     *       could not be held, which is what {@code COCRDUPC} treats as a failure to lock, so
     *       {@link FileStatus#INVREQ} is reported and the caller's existing not-normal arm runs;</li>
     *   <li>everything else, including a refusal with no {@code SQLSTATE} at all -
     *       {@link FileStatus#INVREQ}, the response CICS gives for a request the file cannot satisfy.</li>
     * </ul>
     *
     * @param diagnostic what the backend reported
     * @return a CICS response value from {@link FileStatus}
     */
    private static int responseOf(BackendDiagnostic diagnostic) {
        return diagnostic.connectionFailure() || diagnostic.syntaxOrAccessViolation()
                ? FileStatus.NOTOPEN
                : FileStatus.INVREQ;
    }

    /**
     * Logs a backend refusal with the driver's own diagnosis, and returns it.
     *
     * <p>What reaches the log is the operation, the CICS file name, the qualifier describing what was
     * attempted, and the {@code SQLSTATE}, vendor code and exception type the driver reported. What does
     * <strong>not</strong> reach it is the key or the record image: a card record carries a primary
     * account number and a card verification value, and a log file is read by more people, kept for
     * longer and guarded less than the dataset it describes.
     *
     * <p>The vendor code is logged as a vendor code. It is emphatically not reported to the caller as a
     * CICS {@code RESP2}: a driver's error number and a CICS reason code are quantities from two
     * different systems, and putting one where the other belongs yields a screen that looks
     * authoritative - {@code COCRDUPC} renders {@code ERROR-RESP2} verbatim at
     * {@code app/cbl/COCRDUPC.cbl:1410} - and means nothing. Where no genuine reason code exists,
     * {@link #NO_REASON_CODE} is reported, which is what CICS itself reports when there is none.
     *
     * @param operation the operation name
     * @param fileName  the CICS file name, blank-padded as the legacy field holds it
     * @param qualifier what was being attempted
     * @param refusal   the exception raised
     * @return the diagnostic read out of {@code refusal}
     */
    private static BackendDiagnostic logRefusal(String operation, String fileName, String qualifier,
                                               Throwable refusal) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("File error: " + operation + " on " + fileName.trim() + " " + qualifier
                + " was rejected - " + diagnostic.describe() + "; reporting response "
                + responseOf(diagnostic) + " and reason code " + NO_REASON_CODE + " to the caller");
        return diagnostic;
    }

    /**
     * Requires a response that classifies as the {@code WHEN OTHER} arm.
     *
     * <p>Shared by the read and write failure factories so the rule exists once: an arm the guard chain
     * names explicitly - not found, duplicate key, end of file, normal - must be reached through its own
     * factory, or a caller switching on the outcome and a caller switching on the response would land in
     * different places.
     *
     * @param resp the response to check
     * @throws IllegalArgumentException if it classifies as anything but {@link Outcome#OTHER}
     */
    private static void requireOtherArm(int resp) {
        Outcome classified = FileStatus.outcomeOfCicsResp(resp);
        if (classified != Outcome.OTHER) {
            throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                    + classified + ", which is an arm the guard chain names explicitly, so it "
                    + "cannot be reported as WHEN OTHER. Use the factory for that arm.");
        }
    }

    /**
     * Requires a value that could be a CICS reason code.
     *
     * <p>A reason code is what CICS puts in {@code RESP2}, and {@code COCRDUPC} renders it verbatim onto
     * a screen ({@code app/cbl/COCRDUPC.cbl:1410}). A negative value cannot be one, and a negative value
     * arriving here means something that is not a reason code - a vendor error number, a signed count -
     * was put where the reason code belongs. That is the substitution this guard exists to catch; the
     * quantities that used to be smuggled through here now travel as a
     * {@link DatasetObservation} instead.
     *
     * @param resp2 the value to check
     * @throws IllegalArgumentException if it is negative
     */
    private static void requireReasonCode(int resp2) {
        if (resp2 < 0) {
            throw new IllegalArgumentException("A CICS reason code is " + resp2
                    + "; reason codes are non-negative, and a negative one means something that is not "
                    + "a reason code was reported as one. Report " + NO_REASON_CODE
                    + " and carry the measurement as a " + DatasetObservation.class.getSimpleName()
                    + " instead.");
        }
    }

    /**
     * Reports a refused read as a failed outcome carrying the CICS response the caller branches on.
     *
     * @param operation the operation name
     * @param fileName  the CICS file name
     * @param qualifier what was being attempted
     * @param refusal   the exception raised
     * @return the failed outcome
     */
    private static CardReadResult failedRead(String operation, String fileName, String qualifier,
                                             Throwable refusal) {
        return CardReadResult.failed(
                responseOf(logRefusal(operation, fileName, qualifier, refusal)));
    }

    // =================================================================================================
    // Configuration resolution. Strict, and at construction time: a binding that disagrees with the
    // copybook would decode every field at the wrong offset, and silently wrong data is worse than an
    // application that declines to start.
    // =================================================================================================

    /**
     * Resolves one binding and verifies it describes the card record.
     *
     * @param datasetBindings the catalogue
     * @param ddName          the key to resolve
     * @return the binding
     * @throws IllegalStateException if it is absent, or declares a record width other than
     *                               {@value #RECORD_LENGTH}
     */
    private static DatasetBinding requireCardRecordBinding(DatasetBindings datasetBindings,
                                                           String ddName) {
        DatasetBinding binding = datasetBindings.binding(ddName);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but the card file is decoded by "
                    + "absolute offset against a " + RECORD_LENGTH + "-byte record: "
                    + "app/cpy/CVACT02Y.cpy declares CARD-RECORD as 16 + 11 + 3 + 50 + 10 + 1 + 59 = "
                    + RECORD_LENGTH + " and says so in its own (RECLN " + RECORD_LENGTH + ") header. "
                    + "Reading a differently sized record would place every field at an offset that is "
                    + "not its own, so the disagreement is rejected here rather than discovered in the "
                    + "data. Correct carddemo.datasets." + ddName + ".record-length to "
                    + RECORD_LENGTH + ".");
        }
        return binding;
    }

    /**
     * Verifies that the alternate-index binding, where it says anything at all about what it indexes,
     * says it indexes this repository's base on the account id.
     *
     * <p>Silence is accepted: a binding that states no base and no alternate key contradicts nothing,
     * and asserting against an absent value would invent a requirement. A binding that states
     * <em>different</em> ones is a genuine contradiction and is rejected, because it would mean this
     * repository is reading a path over somebody else's data.
     *
     * @param alternateIndex the alternate-index binding
     * @throws IllegalStateException if it declares a different base or a different alternate key
     */
    private static void requireAlternateIndexOverBase(DatasetBinding alternateIndex) {
        String declaredBase = alternateIndex.base();
        if (declaredBase != null && !BASE_DD_NAME.equals(declaredBase)) {
            throw new IllegalStateException("The dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares that it indexes '" + declaredBase + "', but it is the alternate-index "
                    + "path over '" + BASE_DD_NAME + "' - one cluster reached by two keys, per "
                    + "app/csd/CARDDEMO.CSD:13-14 and :25-26. This repository owns that base and its "
                    + "path and nothing else, so a path over a different base belongs to a different "
                    + "repository. Correct carddemo.datasets." + ALTERNATE_INDEX_DD_NAME + ".base to '"
                    + BASE_DD_NAME + "'.");
        }
        String declaredKey = alternateIndex.alternateKey();
        if (declaredKey != null && !ALTERNATE_KEY_FIELD.name().equals(declaredKey)) {
            throw new IllegalStateException("The dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares its alternate key as '" + declaredKey + "', but the path is keyed on "
                    + ALTERNATE_KEY_FIELD.name() + " - the field app/cbl/COCRDSLC.cbl:785 supplies as "
                    + "RIDFLD(WS-CARD-RID-ACCT-ID). Reading it under a different key would return the "
                    + "wrong records. Correct carddemo.datasets." + ALTERNATE_INDEX_DD_NAME
                    + ".alternate-key to '" + ALTERNATE_KEY_FIELD.name() + "'.");
        }
    }

    /**
     * Verifies a configured dataset name can be composed into a statement.
     *
     * @param candidate the configured value
     * @param ddName    the key it was configured under, for the diagnostic
     * @return the name, unchanged
     * @throws IllegalStateException    if it is absent or blank
     * @throws IllegalArgumentException if it is not a well-formed z/OS dataset name
     */
    private static String requireUsableDatasetName(String candidate, String ddName) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset "
                    + "name.");
        }
        // The grammar lives in DatasetRelation, so what a dataset name may contain is decided once for
        // the whole module rather than restated - and diverging - in each repository.
        return DatasetRelation.requireDatasetName(candidate);
    }

    // =================================================================================================
    // Statement resolution. One place decides the text of every statement this repository sends, and one
    // round trip - a describe that transfers no row - learns the two column names it needs to compose
    // them.
    // =================================================================================================

    /**
     * Resolves the statements on first use and returns them.
     *
     * <p>Two describes, one per relation, because the base cluster and the alternate-index path are
     * addressed by two names and this class does not assume the deployment presents them with the same
     * column name. Everything else is composed from {@link DatasetRelation}, which is what keeps the
     * text of a browse, a keyed read, a locking read and a rewrite identical in form across the module's
     * repositories.
     *
     * @return the composed statements
     * @throws DataAccessException   if either relation cannot be described
     * @throws IllegalStateException if either relation presents no usable record-image column
     */
    private Statements resolveStatements() {
        Statements resolved = this.statements;
        if (resolved == null) {
            resolved = composeStatements();
        }
        return resolved;
    }

    /**
     * Describes both relations and composes the statements <strong>unconditionally</strong>, republishing
     * the memo {@link #resolveStatements()} reads.
     *
     * <p>This is what {@link #openBrowse(String, BrowseDirection)} calls, and the difference between the two
     * methods is the whole of an {@code OPEN}. A sequential pass is established before anything is read, and
     * {@code app/cbl/CBACT02C.cbl:121-132} tests that establishment on its own account, with its own message
     * and its own abend. Reaching it through the memoising accessor meant the describe was skipped once any
     * earlier operation had resolved the statements, so the open could not fail after the first success and
     * a dropped dataset was reported by the read that followed instead.
     *
     * <p>The cost is one metadata round trip per open, paid where the COBOL pays it - once per pass - while
     * every read within the pass reuses the text this composed.
     *
     * @return the freshly composed statements; never {@code null}
     * @throws DataAccessException   if either relation cannot be described
     * @throws IllegalStateException if either relation presents no usable record-image column
     */
    private Statements composeStatements() {
        ResultSetExtractor<String> columnNameExtractor = CardRepository::extractRecordImageColumn;
        String baseColumn = baseRelation.rememberRecordImageColumn(
                jdbcTemplate.query(baseRelation.describeStatement(), columnNameExtractor));
        String alternateColumn = alternateIndexRelation.rememberRecordImageColumn(
                jdbcTemplate.query(alternateIndexRelation.describeStatement(),
                        columnNameExtractor));
        Statements composed = new Statements(
                baseRelation.selectByKey(baseColumn),
                baseRelation.selectByKeyForUpdate(baseColumn),
                alternateIndexRelation.selectByKey(alternateColumn),
                baseRelation.selectFromKeyAscending(baseColumn),
                baseRelation.selectAfterAscending(baseColumn),
                baseRelation.selectBeforeDescending(baseColumn),
                baseRelation.rewriteByKey(baseColumn),
                baseRelation.selectUnreadableRows(baseColumn),
                alternateIndexRelation.selectUnreadableRows(alternateColumn));
        // Published after it is fully built, through a volatile write, so a concurrent reader sees either
        // the previous complete value or this one and never a partially initialised record.
        this.statements = composed;
        return composed;
    }

    /**
     * Reads the record-image column's name out of a described result set.
     *
     * @param resultSet the described, empty result set
     * @return the column name at the record-image position, or {@code null} if there is none
     * @throws SQLException if the driver fails while describing
     */
    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    // =================================================================================================
    // Nested types.
    // =================================================================================================

    /**
     * Every statement this repository sends, composed once against the discovered record-image columns.
     *
     * <p>Each is expressed over the record image, never over a copybook field name: the key predicates
     * are escaped {@code LIKE} patterns produced by {@link #BASE_KEY_SPAN} and
     * {@link #ALTERNATE_KEY_SPAN}, which confine the match to the key's own bytes at its own offset.
     *
     * @param selectByCardNumber          {@code READ} on the base cluster, keyed on the card number
     * @param selectForUpdateByCardNumber {@code READ ... UPDATE}: the same read, holding the record
     *                                    locked for the unit of work
     * @param selectByAccountId           {@code READ} through the alternate-index path, keyed on the
     *                                    account id at its own offset, ordered so that "the first
     *                                    record with this alternate key" is deterministic
     * @param browseAnchor                {@code STARTBR ... GTEQ} and the first read: at or after the
     *                                    supplied key, ascending in both directions, because the anchor
     *                                    is the lowest qualifying key either way
     * @param browseForward               {@code READNEXT}: strictly beyond the last record returned,
     *                                    ascending. The parameter is the previous whole record image,
     *                                    not its key - a bare sixteen-byte key would compare as
     *                                    <em>less than</em> the very record it came from, so a browse
     *                                    positioned by key alone would return that record for ever
     * @param browseBackward              {@code READPREV}: strictly before the last record returned,
     *                                    descending, on the previous whole image for the same reason
     * @param rewrite                     {@code REWRITE}: the whole record image, keyed on the record's
     *                                    own card number
     * @param probeUnreadableBaseRows     the rows of the base cluster whose record image is absent. Not a
     *                                    COBOL operation: it is what lets a keyed read <em>prove</em> an
     *                                    absence before reporting {@code NOTFND}, since {@code CARD-NUM}
     *                                    lives inside the record image and a row with no image therefore
     *                                    has no knowable key. See
     *                                    {@link #provenAbsence(CardReadResult, String, String)}
     * @param probeUnreadableAlternateRows the same probe over the alternate-index path, so a read through
     *                                    the path proves its absence against the path it read.
     *                                    {@code CARD-ACCT-ID} lives inside the image too, so an unreadable
     *                                    row has no knowable alternate key either
     */
    record Statements(String selectByCardNumber,
                      String selectForUpdateByCardNumber,
                      String selectByAccountId,
                      String browseAnchor,
                      String browseForward,
                      String browseBackward,
                      String rewrite,
                      String probeUnreadableBaseRows,
                      String probeUnreadableAlternateRows) {
    }

    /**
     * What one read brought back, before it is classified: the first record image if there was one, and
     * how many rows arrived up to the limit that was asked for.
     *
     * <p>Package-private, and an implementation detail rather than part of the contract - it exists so
     * that reading rows and deciding what they mean stay two separable steps, each testable on its own.
     *
     * @param firstImage the first row's record image, or {@code null} when no row arrived or the row
     *                   carried none
     * @param rowCount   how many rows arrived, never more than the limit the read asked for
     */
    record FetchedRows(byte[] firstImage, int rowCount) {

        /**
         * The empty answer: no rows at all.
         *
         * @return an answer carrying no image and no rows
         */
        static FetchedRows empty() {
            return new FetchedRows(null, 0);
        }
    }

    /**
     * One browse step's outcome together with the exact bytes it came from.
     *
     * <p>Package-private and an implementation detail. It exists because a browse has to advance past the
     * record it just returned, and the only value that identifies that record unambiguously is the image
     * the backend handed over - not the record decoded from it, and not a re-encoding of that record. A
     * re-encoding is byte-identical only when every span of the stored row already holds what the model
     * would write there, and a row whose reserved span holds anything but spaces is precisely the row for
     * which it is not: the browse would then ask for the first image after a value that no row has, and
     * hand back the same record again.
     *
     * @param result     the outcome the caller branches on
     * @param exactImage the bytes the row's record-image column held, or {@code null} when no record was
     *                   returned
     */
    record BrowseStep(CardReadResult result, byte[] exactImage) {
    }

    /**
     * Which way a browse is walked.
     *
     * <p>Two constants because the legacy code issues two separate {@code STARTBR}s, one per direction:
     * forward at {@code app/cbl/COCRDLIC.cbl:1129} feeding {@code READNEXT}, backward at {@code :1273}
     * feeding {@code READPREV}. A browse is therefore never direction-less, and a handle accepts only
     * the read that matches the direction it was positioned for.
     */
    public enum BrowseDirection {

        /**
         * Ascending key order, walked with {@link CardBrowse#readNext()}. The first read returns the
         * record at or after the key positioned on, and each read after it returns the next higher key.
         */
        FORWARD,

        /**
         * Descending key order, walked with {@link CardBrowse#readPrev()}. The first read returns the
         * record at or after the key positioned on - positioning is at-or-after in both directions,
         * because both {@code STARTBR} sites specify it - and each read after it returns the next lower
         * key.
         *
         * <p>{@code COCRDLIC} confirms the inclusive first read: {@code :1284-1286} primes its row
         * counter one past the last screen row and its first {@code READPREV} at {@code :1294} only
         * decrements that counter, discarding the record, because that record is the one already at the
         * top of the page being paged away from. The reads that fill the page all come after it.
         */
        BACKWARD
    }

    /**
     * The outcome of one read: the arm of the guard chain it landed on, the record where there is one,
     * and both raw CICS response values.
     *
     * <p>Both raw values are carried because the legacy diagnostic needs both:
     * {@code app/cbl/COCRDSLC.cbl:767-771} and {@code app/cbl/COCRDUPC.cbl:1407-1411} move the
     * operation name, the file name, {@code WS-RESP-CD} and {@code WS-REAS-CD} into a
     * {@code WS-FILE-ERROR-MESSAGE} whose composed width is byte-exact. Collapsing the pair into a
     * boolean would make that message unbuildable, so it is not collapsed.
     *
     * <p>{@link #outcome()} and {@link #resp()} cannot disagree: the constructor rejects a pair that
     * does, so a caller may switch on either and reach the same arm. {@link #batchStatus()} is empty
     * for the three responses that genuinely have no two-character equivalent, rather than reporting a
     * fabricated one for them.
     *
     * <h2>{@code RESP2} carries a CICS reason code and nothing else</h2>
     *
     * <p>It used to carry, on the length-error arm, the width the row turned out to be - on the argument
     * that the width is the most useful reason code there is for a length error. It is indeed the most
     * useful <em>number</em>, and it is not a reason code, and {@code COCRDUPC} renders
     * {@code ERROR-RESP2} verbatim onto an operator's screen ({@code app/cbl/COCRDUPC.cbl:1410}) with no
     * way for the reader to know which of the two they are looking at. The very same paragraph of this
     * class already refused to report a driver's vendor error number as a reason code, for exactly that
     * reason; the width was the same substitution wearing a more convincing argument.
     *
     * <p>So {@code resp2} is now the adapter's reason code or {@link CardRepository#NO_REASON_CODE},
     * which is what CICS itself reports when a condition has no further reason, and a measured quantity
     * travels beside it in {@link #observation()} under the name of what was measured.
     *
     * @param resp    the raw CICS response, one of the {@link FileStatus} response constants
     * @param resp2   the CICS reason code the deployment's adapter reported, or
     *                {@link CardRepository#NO_REASON_CODE} where none is available. Never a width, a row
     *                count or a vendor error number
     * @param outcome the classification of {@code resp}, agreeing with it by construction
     * @param record  the record on the two arms that return one - the normal arm and the duplicate-key
     *                arm - and empty on every other arm. Never {@code null}
     * @param observation what the operation measured, where a measurement is what explains the outcome -
     *                a row's actual width, for instance. Empty on every arm that measured nothing
     */
    public record CardReadResult(int resp, int resp2, Outcome outcome, Optional<CardRecord> record,
            Optional<String> storedImage, Optional<DatasetObservation> observation) {

        /**
         * Rejects any result whose parts contradict each other.
         *
         * @throws NullPointerException     if {@code outcome} or {@code record} is {@code null}
         * @throws IllegalArgumentException if {@code outcome} does not classify {@code resp}, or if a
         *                                  record is present on an arm that returns none, or absent on
         *                                  an arm that returns one
         */
        public CardReadResult {
            Objects.requireNonNull(outcome, "A read result carries the arm it landed on; it is never "
                    + "absent");
            Objects.requireNonNull(record, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(storedImage, "A read result carries an empty stored image rather than "
                    + "a null one, so no null escapes the type");
            Objects.requireNonNull(observation, "A read result carries an empty observation rather than "
                    + "a null one, so no null escapes the type");
            requireReasonCode(resp2);
            Outcome classified = FileStatus.outcomeOfCicsResp(resp);
            if (outcome != classified) {
                throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                        + classified + ", but the result was built as " + outcome + ". The response and "
                        + "its classification must agree, or a caller switching on one would reach a "
                        + "different arm than a caller switching on the other.");
            }
            boolean recordExpected = outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
            if (record.isPresent() != recordExpected) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "Outcome " + outcome + " returns no record, but one was given. Only the "
                                + "normal arm and the duplicate-key arm reach CARD-RECORD."
                        : "Outcome " + outcome + " returns a record, and this result carries none.");
            }
            // The stored image travels with the record and never without it. That is what makes
            // DISPLAY CARD-RECORD reproducible: a caller reaching the record-bearing arm can always
            // reach the row's own bytes, and never has to fall back on re-encoding the decoded fields -
            // which would emit FILLER X(59) as spaces whatever the row held.
            if (storedImage.isPresent() != record.isPresent()) {
                throw new IllegalArgumentException(storedImage.isPresent()
                        ? "Outcome " + outcome + " returns no record, so it carries no stored image "
                                + "either; an image with no record to belong to has no meaning."
                        : "Outcome " + outcome + " returns a record, so it must carry the stored image "
                                + "that record was decoded from. Build it with "
                                + "CardReadResult.normal(record, storedImage) or "
                                + "CardReadResult.duplicateKey(record, storedImage).");
            }
            storedImage.ifPresent(image -> {
                if (image.length() != RECORD_LENGTH) {
                    throw new IllegalArgumentException("A stored CARD-RECORD image is " + RECORD_LENGTH
                            + " characters as app/cpy/CVACT02Y.cpy declares, but this one is "
                            + image.length() + ". DISPLAY CARD-RECORD writes the whole record area, so "
                            + "an image of any other width would emit a line the program cannot "
                            + "produce.");
                }
            });
        }

        /**
         * The normal arm: {@code WHEN DFHRESP(NORMAL)}, the record read.
         *
         * <p>Both the decoded record and the bytes it was decoded from are carried, and the second is
         * not redundant. {@code CARD-RECORD} ends with {@code FILLER X(59)}
         * ({@code app/cpy/CVACT02Y.cpy}), which holds no field and which the record model therefore
         * cannot reproduce; {@code DISPLAY CARD-RECORD} ({@code app/cbl/CBACT02C.cbl:78}) writes the
         * whole area including it. Re-encoding the decoded fields would emit fifty-nine spaces there
         * whatever the row actually held, so the row's own image is retained instead.
         *
         * @param record      the record read
         * @param storedImage the row's own bytes, decoded in the dataset code page - exactly
         *                    {@value CardRepository#RECORD_LENGTH} characters
         * @return the result
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *                                  {@value CardRepository#RECORD_LENGTH} characters
         */
        public static CardReadResult normal(CardRecord record, String storedImage) {
            Objects.requireNonNull(record, "The normal arm carries the record that was read");
            Objects.requireNonNull(storedImage, "The normal arm carries the stored image the record was "
                    + "decoded from, so DISPLAY CARD-RECORD can write the row's own bytes");
            return new CardReadResult(FileStatus.NORMAL, NO_REASON_CODE, Outcome.OK,
                    Optional.of(record), Optional.of(storedImage), Optional.empty());
        }

        /**
         * The duplicate-key arm, reachable only through the alternate-index path: the first record
         * sharing the alternate key, with more behind it. Not an error - the record is returned.
         *
         * @param record      the first record sharing the alternate key
         * @param storedImage that record's own bytes, decoded in the dataset code page - exactly
         *                    {@value CardRepository#RECORD_LENGTH} characters
         * @return the result
         * @throws NullPointerException     if either argument is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *                                  {@value CardRepository#RECORD_LENGTH} characters
         */
        public static CardReadResult duplicateKey(CardRecord record, String storedImage) {
            Objects.requireNonNull(record, "The duplicate-key arm carries the first record sharing the "
                    + "alternate key; the record is returned, which is what makes it not an error");
            Objects.requireNonNull(storedImage, "The duplicate-key arm returns a record, so it carries "
                    + "the stored image that record was decoded from");
            return new CardReadResult(FileStatus.DUPKEY, NO_REASON_CODE, Outcome.DUPLICATE,
                    Optional.of(record), Optional.of(storedImage), Optional.empty());
        }

        /**
         * The not-found arm: {@code WHEN DFHRESP(NOTFND)}. An ordinary outcome that sets a screen
         * message ({@code app/cbl/COCRDSLC.cbl:755-761}), never an exception.
         *
         * @return the result
         */
        public static CardReadResult notFound() {
            return new CardReadResult(FileStatus.NOTFND, NO_REASON_CODE, Outcome.NOT_FOUND,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code WHEN DFHRESP(ENDFILE)}, reachable from a browse step. It is what
         * terminates {@code COCRDLIC}'s paging loop and what sets its no-next-page flag
         * ({@code app/cbl/COCRDLIC.cbl:1215-1221} and {@code :1233-1245}).
         *
         * @return the result
         */
        public static CardReadResult endOfFile() {
            return new CardReadResult(FileStatus.ENDFILE, NO_REASON_CODE, Outcome.END_OF_FILE,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm.
         *
         * @param resp  the response to report; it must be one that classifies as
         *              {@link Outcome#OTHER}, because the arms that have their own factory must be
         *              reached through it
         * @param resp2 the reason code, or {@link CardRepository#NO_REASON_CODE}
         * @return the result
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *                                  {@link Outcome#OTHER}
         */
        public static CardReadResult failed(int resp) {
            return failed(resp, Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the operation measured.
         *
         * <p>For a failure a number explains: a row that is not the declared width, a key that selected
         * the wrong number of rows. The number goes here, labelled, and <strong>not</strong> into
         * {@code resp2}, which reports {@link CardRepository#NO_REASON_CODE} because CICS has no reason
         * code for it.
         *
         * @param resp        the raw CICS response, which must classify as {@link Outcome#OTHER}
         * @param observation what was measured
         * @return the result
         * @throws NullPointerException     if {@code observation} is {@code null}
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *                                  {@link Outcome#OTHER}
         */
        public static CardReadResult failed(int resp, DatasetObservation observation) {
            Objects.requireNonNull(observation, "This factory carries an observation; use failed(int) "
                    + "where there is nothing measured to report");
            return failed(resp, Optional.of(observation));
        }

        /**
         * The {@code WHEN OTHER} arm with a reason code the deployment's adapter actually reported.
         *
         * <p>Named for what it is, so that reaching for it to carry something that is not a reason code
         * reads as wrong at the call site. Every failure this module raises itself uses
         * {@link #failed(int)} or {@link #failed(int, DatasetObservation)} instead.
         *
         * @param resp  the raw CICS response, which must classify as {@link Outcome#OTHER}
         * @param resp2 the reason code the adapter reported
         * @return the result
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *                                  {@link Outcome#OTHER}, or if {@code resp2} is negative
         */
        public static CardReadResult reportedFailure(int resp, int resp2) {
            requireOtherArm(resp);
            return new CardReadResult(resp, resp2, Outcome.OTHER, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        /**
         * The shared body of the two {@code WHEN OTHER} factories that report no adapter reason code.
         *
         * @param resp        the raw CICS response
         * @param observation what was measured, possibly nothing
         * @return the result
         */
        private static CardReadResult failed(int resp, Optional<DatasetObservation> observation) {
            requireOtherArm(resp);
            return new CardReadResult(resp, NO_REASON_CODE, Outcome.OTHER, Optional.empty(),
                    Optional.empty(), observation);
        }

        /**
         * The two-character batch file status this response corresponds to, where it has one.
         *
         * <p>Empty for the responses that genuinely have no batch equivalent - a length error, a file
         * that could not be opened, an invalid request - because reporting a fabricated status for them
         * would invent behaviour the legacy system never had.
         *
         * @return the corresponding status, or empty where there is none
         */
        public Optional<String> batchStatus() {
            return FileStatus.batchStatusOfCicsResp(resp);
        }

        /**
         * Whether this is the normal arm.
         *
         * @return {@code true} for a normal response
         */
        public boolean isNormal() {
            return resp == FileStatus.NORMAL;
        }

        /**
         * Whether a record came back, on either of the two arms that return one.
         *
         * <p>This is the test {@code COCRDLIC}'s browse uses: its loop treats
         * {@code WHEN DFHRESP(NORMAL)} and the duplicate arm identically, because both mean a record
         * was returned ({@code app/cbl/COCRDLIC.cbl:1157-1158}, {@code :1208-1209},
         * {@code :1305-1306} and {@code :1333-1334}).
         *
         * @return {@code true} when {@link #record()} is present
         */
        public boolean isRecordReturned() {
            return record.isPresent();
        }

        /**
         * Whether this is the not-found arm.
         *
         * @return {@code true} for a not-found response
         */
        public boolean isNotFound() {
            return resp == FileStatus.NOTFND;
        }

        /**
         * Whether this is the end-of-file arm.
         *
         * @return {@code true} for an end-of-file response
         */
        public boolean isEndOfFile() {
            return resp == FileStatus.ENDFILE;
        }

        /**
         * Whether the alternate key matched more than one record.
         *
         * @return {@code true} for a duplicate-key response
         */
        public boolean isDuplicateKey() {
            return resp == FileStatus.DUPKEY;
        }

        /**
         * Whether this is the {@code WHEN OTHER} arm.
         *
         * @return {@code true} when the request failed
         */
        public boolean isFailure() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The record, for a caller already on an arm that returns one.
         *
         * @return the record
         * @throws IllegalStateException if this arm returns no record. A caller that has not yet
         *                               established which arm it is on must use {@link #record()} and
         *                               branch, exactly as the {@code EVALUATE} does
         */
        public CardRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("Outcome " + outcome
                    + " returns no record. Branch on the outcome first, as the EVALUATE at "
                    + "app/cbl/COCRDSLC.cbl:752-772 does, and read the record only on an arm that "
                    + "returns one."));
        }

        /**
         * The row's own bytes as characters, for a caller already on an arm that returns a record.
         *
         * <p>This is what {@code DISPLAY CARD-RECORD} ({@code app/cbl/CBACT02C.cbl:78}) writes: the
         * whole {@value CardRepository#RECORD_LENGTH}-byte record area, exactly as the row held it.
         * Re-encoding {@link #requireRecord()} instead would agree on every declared field and disagree
         * on {@code FILLER X(59)}, which holds no field and which a re-encode necessarily emits as
         * spaces - so the two are not interchangeable and this is the one a raw display must use.
         *
         * @return exactly {@value CardRepository#RECORD_LENGTH} characters
         * @throws IllegalStateException if this arm returns no record, and so no image either
         */
        public String requireStoredImage() {
            return storedImage.orElseThrow(() -> new IllegalStateException("Outcome " + outcome
                    + " returns no record, so it carries no stored image. DISPLAY CARD-RECORD is "
                    + "reached only on the arm that returns one - app/cbl/CBACT02C.cbl:77 tests "
                    + "END-OF-FILE before it displays - so branch on the outcome first."));
        }
    }

    /**
     * The outcome of a rewrite: the arm it landed on and both raw CICS response values.
     *
     * <p>There is no record component, because a rewrite returns none. The guard chain that follows it
     * in the legacy code is a two-way test ({@code app/cbl/COCRDUPC.cbl:1488-1492}) that sets a
     * {@code LOCKED-BUT-UPDATE-FAILED} flag on anything other than a normal response, so
     * {@link #isNormal()} reproduces it exactly, and the raw values remain available for a diagnostic.
     *
     * <p>{@code resp2} carries a CICS reason code and nothing else, for the reason set out on
     * {@link CardReadResult}: it used to carry the number of records the {@code UPDATE} actually
     * replaced, which is a JDBC affected-row count and not a quantity CICS has ever reported. A measured
     * quantity now travels beside it in {@link #observation()}.
     *
     * @param resp    the raw CICS response
     * @param resp2   the CICS reason code the deployment's adapter reported, or
     *                {@link CardRepository#NO_REASON_CODE} where none is available. Never a row count
     * @param outcome the classification of {@code resp}, agreeing with it by construction
     * @param observation what the write measured, where a measurement explains the outcome - the number
     *                of rows a key selected, for instance. Empty where nothing was measured
     */
    public record CardWriteResult(int resp, int resp2, Outcome outcome,
            Optional<DatasetObservation> observation) {

        /**
         * Rejects a result whose parts contradict each other.
         *
         * @throws NullPointerException     if {@code outcome} is {@code null}
         * @throws IllegalArgumentException if {@code outcome} does not classify {@code resp}
         */
        public CardWriteResult {
            Objects.requireNonNull(outcome, "A write result carries the arm it landed on; it is never "
                    + "absent");
            Objects.requireNonNull(observation, "A write result carries an empty observation rather "
                    + "than a null one, so no null escapes the type");
            requireReasonCode(resp2);
            Outcome classified = FileStatus.outcomeOfCicsResp(resp);
            if (outcome != classified) {
                throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                        + classified + ", but the result was built as " + outcome
                        + ". The response and its classification must agree.");
            }
        }

        /**
         * The normal arm: exactly one record was replaced.
         *
         * @return the result
         */
        public static CardWriteResult normal() {
            return new CardWriteResult(FileStatus.NORMAL, NO_REASON_CODE, Outcome.OK,
                    Optional.empty());
        }

        /**
         * The failure arm, on which the legacy code sets its update-failed flag.
         *
         * @param resp the response to report; it must classify as {@link Outcome#OTHER}, which every
         *             way a rewrite can fail does
         * @return the result, reporting {@link CardRepository#NO_REASON_CODE}
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *                                  {@link Outcome#OTHER}
         */
        public static CardWriteResult failed(int resp) {
            return failed(resp, Optional.empty());
        }

        /**
         * The failure arm, carrying what the write measured.
         *
         * @param resp        the response to report; it must classify as {@link Outcome#OTHER}
         * @param observation what was measured - a row count, never a reason code
         * @return the result, reporting {@link CardRepository#NO_REASON_CODE} as its reason
         * @throws NullPointerException     if {@code observation} is {@code null}
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *                                  {@link Outcome#OTHER}
         */
        public static CardWriteResult failed(int resp, DatasetObservation observation) {
            Objects.requireNonNull(observation, "This factory carries an observation; use failed(int) "
                    + "where there is nothing measured to report");
            return failed(resp, Optional.of(observation));
        }

        /**
         * The failure arm with a reason code the deployment's adapter actually reported.
         *
         * @param resp  the response to report; it must classify as {@link Outcome#OTHER}
         * @param resp2 the reason code the adapter reported
         * @return the result
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *                                  {@link Outcome#OTHER}, or if {@code resp2} is negative
         */
        public static CardWriteResult reportedFailure(int resp, int resp2) {
            requireRewriteFailureArm(resp);
            return new CardWriteResult(resp, resp2, Outcome.OTHER, Optional.empty());
        }

        /**
         * The shared body of the two failure factories that report no adapter reason code.
         *
         * @param resp        the response to report
         * @param observation what was measured, possibly nothing
         * @return the result
         */
        private static CardWriteResult failed(int resp, Optional<DatasetObservation> observation) {
            requireRewriteFailureArm(resp);
            return new CardWriteResult(resp, NO_REASON_CODE, Outcome.OTHER, observation);
        }

        /**
         * Requires a response a rewrite can actually report.
         *
         * @param resp the response to check
         * @throws IllegalArgumentException if it classifies as anything but {@link Outcome#OTHER}
         */
        private static void requireRewriteFailureArm(int resp) {
            Outcome classified = FileStatus.outcomeOfCicsResp(resp);
            if (classified != Outcome.OTHER) {
                throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                        + classified + ", which a rewrite cannot report: a rewrite either replaces its "
                        + "record or fails. Only a response classifying as WHEN OTHER may be reported "
                        + "as a failure.");
            }
        }

        /**
         * The two-character batch file status this response corresponds to, where it has one. Empty for
         * every failure a rewrite can report, none of which has a batch equivalent.
         *
         * @return the corresponding status, or empty where there is none
         */
        public Optional<String> batchStatus() {
            return FileStatus.batchStatusOfCicsResp(resp);
        }

        /**
         * Whether the rewrite succeeded.
         *
         * @return {@code true} for a normal response
         */
        public boolean isNormal() {
            return resp == FileStatus.NORMAL;
        }

        /**
         * Whether the rewrite failed, which is the arm that sets the legacy update-failed flag.
         *
         * @return {@code true} when the rewrite failed
         */
        public boolean isFailure() {
            return outcome == Outcome.OTHER;
        }
    }

    /**
     * One browse in progress: where it is positioned, which way it is walked, and whether it has been
     * ended.
     *
     * <p><strong>This is where a browse's state lives, and the only place it lives.</strong> The
     * repository that hands the handle out is a stateless singleton, so a handle is created per browse
     * and belongs to the caller that asked for it. Two callers paging two card lists hold two handles
     * and cannot interfere; a handle cannot outlive the request that made it; and nothing about a browse
     * is ever visible to anyone who did not start it. That is what lets the online layer stay
     * pseudo-conversational with no server-side session state, which is the migration's rule R6.
     *
     * <p>Each step re-positions by key rather than holding a backend cursor open, so a handle survives
     * being carried across a conversation without holding a resource. This is the same technique
     * {@code COCRDLIC} uses: it keeps the first and last key of the displayed page in its own
     * communication area and re-positions from them.
     *
     * <p>A handle counts nothing and knows no page size (gate G39). It yields one record per read; how
     * many make a page is the controller's decision, taken from {@code WS-MAX-SCREEN-LINES}.
     */
    public static final class CardBrowse implements AutoCloseable {

        /** The repository that composed the statements and owns the template. */
        private final CardRepository repository;

        /** The key positioned on, at its declared width. Positioning is at-or-after it. */
        private final String anchorKey;

        /** Which read this handle accepts. */
        private final BrowseDirection direction;

        /** The key of the last record returned, or {@code null} until one has been. */
        private String positionKey;

        /**
         * The exact bytes of the record last returned, or {@code null} before the first read.
         *
         * <p>Three things about this, and all three are load-bearing.
         *
         * <p><strong>The whole image and not the key.</strong> An advancing step asks for the first record
         * whose image sorts strictly after the position, and a bare sixteen-byte key would compare as
         * <em>less than</em> the very record it came from - {@code '4444333322221111'} sorts before
         * {@code '4444333322221111…'} - so a browse positioned by key alone would hand back the same
         * record for ever. The full image excludes it strictly, and because the card number is unique and
         * every image is exactly {@value CardRepository#RECORD_LENGTH} bytes the comparison always
         * resolves inside the leading sixteen, which is what makes it equivalent to the key ordering a
         * CICS browse follows ({@code app/cbl/COCRDLIC.cbl:1129-1154}, {@code :1273-1302}).
         *
         * <p><strong>The bytes the backend gave, not a re-encoding of the record decoded from them.</strong>
         * Those two are the same value only when every span of the stored row already holds what the model
         * would write there. {@code CVACT02Y}'s trailing {@code FILLER PIC X(59)} is written as spaces, so
         * a stored row whose reserved span holds anything else re-encodes to an image that is <em>lower</em>
         * than the row it came from - and the browse then asks for the first record after a value no row
         * has, and returns the same record again. Retaining the exact bytes removes the possibility rather
         * than relying on every stored row being tidy.
         *
         * <p><strong>Bytes and not text.</strong> A stored image decoded and re-encoded through a
         * {@code String} is width-preserving under a single-byte code page and not, in general,
         * byte-preserving: IBM037 maps {@code X'25'} to a character that encodes back as {@code X'15'}.
         * The position is therefore held and bound as the bytes it is.
         *
         * <p>{@link #positionKey} is kept alongside it for diagnostics, because a key is what a reader of
         * a log or a test failure recognises. It is read out of these same bytes rather than out of the
         * decoded record, so the two cannot disagree.
         */
        private byte[] positionImage;

        /** Whether a record has been returned, and so whether the next read advances or anchors. */
        private boolean positioned;

        /** Whether the browse has been ended. */
        private boolean ended;

        /**
         * What the open reported, as a CICS response.
         *
         * <p>{@link FileStatus#NORMAL} for a handle from
         * {@link CardRepository#startBrowse(String, BrowseDirection)}, which issues no backend call at
         * all - that is the online contract, where both {@code STARTBR} sites discard their own response.
         * For a handle from {@link CardRepository#openBrowse(String, BrowseDirection)} it is what the
         * open probe found, because the batch program that uses that entry point does test its
         * {@code OPEN}.
         */
        private final int openResp;

        /**
         * Constructed only by {@link CardRepository#startBrowse(String, BrowseDirection)}, so that a
         * handle always carries a key already moved to its declared width.
         *
         * @param repository the repository to read through
         * @param anchorKey  the key to position at or after, at its declared width
         * @param direction  which read this handle accepts
         */
        private CardBrowse(CardRepository repository, String anchorKey, BrowseDirection direction) {
            this(repository, anchorKey, direction, FileStatus.NORMAL);
        }

        /**
         * Constructed by {@link CardRepository#openBrowse(String, BrowseDirection)}, carrying what its
         * open probe found.
         *
         * @param repository the repository to read through
         * @param anchorKey  the key to position at or after, at its declared width
         * @param direction  which read this handle accepts
         * @param openResp   the CICS response the open reported
         */
        private CardBrowse(CardRepository repository, String anchorKey, BrowseDirection direction,
                           int openResp) {
            this.repository = repository;
            this.anchorKey = anchorKey;
            this.direction = direction;
            this.openResp = openResp;
        }

        /**
         * The CICS response the open reported, for a caller whose COBOL tests its {@code OPEN}.
         *
         * <p>Translate it with {@link FileStatus#batchStatusOfCicsResp(int)} to get the two-character
         * {@code FILE STATUS} a batch program's guard chain reads - which is exactly how
         * {@link #readNext()}'s result is translated, so the open and the read reach their arms the same
         * way.
         *
         * @return {@link FileStatus#NORMAL} when the dataset was opened, or {@link FileStatus#NOTOPEN}
         *         when it could not be
         */
        public int openResp() {
            return openResp;
        }

        /**
         * Whether the open succeeded, and so whether reading this handle can return anything.
         *
         * @return {@code true} when the open reported {@link FileStatus#NORMAL}
         */
        public boolean isOpen() {
            return openResp == FileStatus.NORMAL;
        }

        /**
         * Which way this browse is walked.
         *
         * @return the direction it was positioned for; never {@code null}
         */
        public BrowseDirection direction() {
            return direction;
        }

        /**
         * The key this browse was positioned at or after.
         *
         * @return the anchor key, exactly {@code CARD-NUM}'s declared width
         */
        public String anchorKey() {
            return anchorKey;
        }

        /**
         * The key of the last record returned.
         *
         * @return that key, or empty until a record has been returned
         */
        public Optional<String> positionKey() {
            return Optional.ofNullable(positionKey);
        }

        /**
         * Whether this browse has been ended.
         *
         * @return {@code true} once {@link #endBrowse()} or {@link #close()} has been called
         */
        public boolean isEnded() {
            return ended;
        }

        /**
         * Reads the next record in ascending key order.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:1146-1154} and {@code :1197-1205}. The first read returns the
         * record at or after the key positioned on; each read after it returns the next higher key.
         * Running past the last record reports the end of the file, distinctly, because that is what
         * terminates the legacy paging loop - and repeating the read reports it again rather than
         * wrapping round.
         *
         * @return the read outcome; never {@code null}
         */
        public CardReadResult readNext() {
            return read(BrowseDirection.FORWARD, Statements::browseForward);
        }

        /**
         * Reads the previous record in descending key order.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:1294-1302} and {@code :1322-1330}. The first read returns the
         * record at or after the key positioned on - {@code STARTBR} specifies at-or-after positioning
         * in this direction too, at {@code :1277} - and each read after it returns the next lower key.
         * The legacy code discards that first record deliberately, because it is the one already at the
         * top of the page being paged away from; discarding it is the caller's decision to make, and
         * {@code :1284-1286} and {@code :1294-1307} are where it makes it.
         *
         * @return the read outcome; never {@code null}
         */
        public CardReadResult readPrev() {
            return read(BrowseDirection.BACKWARD, Statements::browseBackward);
        }

        /**
         * Reads one step, anchoring on the first read and advancing on every read after it.
         *
         * @param required   the direction this read belongs to
         * @param advanceStatement selects, from the resolved statements, the one that advances in that
         *                         direction
         * @return the read outcome; never {@code null}
         */
        private CardReadResult read(BrowseDirection required,
                                    Function<Statements, String> advanceStatement) {
            if (ended) {
                // No browse is in progress, so there is nothing to read from. CICS reports an invalid
                // request for a browse operation without a browse, and so does this.
                LOG.error("A read was requested on a browse of " + BASE_CICS_FILE_NAME.trim()
                        + " that has already been ended; reporting the invalid-request response");
                return CardReadResult.failed(FileStatus.INVREQ);
            }
            if (direction != required) {
                // The legacy code positions a separate browse for each direction and never reads one
                // the other way. Reversing direction silently would invent behaviour, so the request is
                // refused as invalid instead.
                LOG.error("A " + required + " read was requested on a browse of "
                        + BASE_CICS_FILE_NAME.trim() + " positioned for " + direction
                        + "; reporting the invalid-request response rather than reversing a browse the "
                        + "legacy code never reverses");
                return CardReadResult.failed(FileStatus.INVREQ);
            }
            Statements sql;
            try {
                sql = repository.resolveStatements();
            } catch (DataAccessException rejected) {
                return CardRepository.failedRead(CardRepository.READ_OPERATION_NAME,
                        BASE_CICS_FILE_NAME, "while positioning a browse", rejected);
            }

            // The anchor compares against the key - "at or after this key" is exactly what a
            // sixteen-byte key expresses - while every step after it compares against the whole image,
            // for the reason set out on positionImage.
            BrowseStep step = positioned
                    ? repository.browseStepAfter(advanceStatement.apply(sql), positionImage,
                            CardRepository.READ_OPERATION_NAME)
                    : repository.browseStep(sql.browseAnchor(), anchorKey,
                            CardRepository.READ_OPERATION_NAME);
            CardReadResult result = step.result();
            if (result.isRecordReturned()) {
                // Advance only on a record, and advance to the bytes the backend actually presented. An
                // end of file leaves the position alone, so repeating the read reports the end of the file
                // again; a failure likewise, so a caller that retries retries the same step rather than
                // skipping one.
                positionImage = step.exactImage().clone();
                positionKey = repository.keyOfImage(positionImage);
                positioned = true;
            }
            return result;
        }

        /**
         * Ends the browse.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:1258} and {@code :1375-1377}. Neither site specifies a response
         * option, so neither the legacy code nor this method reports an outcome - there is nothing to
         * report that the COBOL would have looked at.
         *
         * <p>No backend call is made, because none is owed: a browse holds no cursor open, only a key.
         * What ending it does is close the handle, so that a read after it is refused rather than
         * silently resuming a browse the caller believes it has finished with. Ending an already-ended
         * browse does nothing, which makes {@link #close()} safe to call after it.
         */
        public void endBrowse() {
            ended = true;
        }

        /**
         * Ends the browse, so that a handle can be used in a try-with-resources block.
         *
         * <p>Declared without a checked exception, because ending a browse cannot fail.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }
}
