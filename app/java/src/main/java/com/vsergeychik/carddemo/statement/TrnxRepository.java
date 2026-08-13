package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import org.springframework.stereotype.Repository;

import java.util.Objects;

/**
 * The {@code TRNXFILE} dataset's access contract - the sorted transaction extract the statement job
 * reads, {@code app/cpy/COSTM01.CPY} and {@code app/cbl/CBSTM03B.CBL:31-35}.
 *
 * <h2>What this owns, and why it is a repository of its own</h2>
 * <p>The migration plan names {@code Trnx} among the twelve dataset repositories and gates on that count
 * (AAP 0.3.5, 0.4.12, gate <strong>G10</strong>): one base dataset, one {@code @Repository}. This class
 * is the single place {@code TRNXFILE}'s identity and geometry are established - the configured dataset
 * name, the {@value TrnxRecord#RECORD_LENGTH}-byte record width, the {@code TRNX-KEY} span, and the
 * {@link DatasetRelation} every statement over that dataset is composed from - and the single place its
 * binding is validated against the copybook. Nothing else in the module resolves
 * {@code carddemo.datasets.TRNXFILE}, so no second answer to "how wide is a TRNX record" can exist.
 *
 * <p>The dataset name never appears in Java (gate <strong>G46</strong>): it is read from
 * {@code carddemo.datasets.}{@value #DD_NAME}{@code .dsname}, and a blank one is refused here rather
 * than composed into a statement that would fail somewhere less informative.
 *
 * <h2>What this deliberately does NOT own</h2>
 * <p>The six-operation dispatch is {@code CBSTM03B}'s, and it stays in
 * {@link StatementGenerationJobB}. {@code app/cbl/CBSTM03B.CBL:105-131} is a single
 * {@code EVALUATE LK-M03B-DD} that routes to one paragraph range per DD name, and
 * {@code :133-229} gives each range the same three-test shape whose <em>content</em> depends on that
 * {@code SELECT}'s {@code ACCESS MODE}. {@code TRNXFILE} is {@code ACCESS MODE IS SEQUENTIAL}
 * ({@code :31-35}), so its paragraph implements {@code OPEN}, sequential {@code READ} and {@code CLOSE}
 * and tests for nothing else - it has no keyed read at all. Relocating that dispatch here would split
 * one COBOL program's control flow across two classes for no gain; what belongs here is the dataset, and
 * that is what is here.
 *
 * <h2>Immutability</h2>
 * <p>Every field is assigned once in the constructor and nothing derived from a session is held: no
 * cursor position, no {@code FILE STATUS}, no resolved statement text. A singleton is therefore safe to
 * share across concurrent statement runs (practice <strong>B9</strong>, gate <strong>G53</strong>).
 *
 * @see StatementGenerationJobB
 * @see TrnxRecord
 */
@Repository
// Deliberately NOT final. Spring Boot's PersistenceExceptionTranslationAutoConfiguration registers a
// PersistenceExceptionTranslationPostProcessor that proxies every @Repository bean, and CGLIB cannot
// subclass a final class - the context would fail to start with "Could not generate CGLIB subclass".
// Every sibling repository in this module is non-final for the same reason.
public class TrnxRepository {

    /**
     * {@code 'TRNXFILE'} - the DD name {@code CBSTM03A} passes in {@code LK-M03B-DD} and the
     * {@code carddemo.datasets} key this dataset is bound under.
     *
     * <p>A configuration key rather than a dataset name: the name itself is a deployment input.
     */
    public static final String DD_NAME = "TRNXFILE";

    /** The copybook that declares this record, named in every diagnostic this class raises. */
    public static final String COPYBOOK = "app/cpy/COSTM01.CPY";

    /** The resolved dataset name, exactly as {@code carddemo.datasets} declares it. */
    private final String datasetName;

    /** The dataset contract every statement over {@code TRNXFILE} is composed from. */
    private final DatasetRelation relation;

    /** The {@code TRNX-KEY} span: offset 0, width {@value TrnxRecord#TRNX_KEY_LENGTH}. */
    private final KeySpan keySpan;

    /**
     * Resolves the {@code TRNXFILE} binding and validates its geometry against {@code COSTM01}.
     *
     * @param datasetBindings the DD-name-keyed catalogue bound from {@code carddemo.datasets}; must not
     *                        be {@code null}
     * @throws NullPointerException  if {@code datasetBindings} is {@code null}
     * @throws IllegalStateException if {@value #DD_NAME} is unconfigured, declares no usable dataset
     *                               name, or declares a record width, key width or key offset other than
     *                               the copybook's
     */
    public TrnxRepository(final DatasetBindings datasetBindings) {
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java (gate G46)");

        final DatasetBinding binding = datasetBindings.binding(DD_NAME);
        requireCopybookRecordLength(binding);
        requireDeclaredKeyGeometry(binding);

        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()),
                TrnxRecord.RECORD_LENGTH);
        this.datasetName = this.relation.dsname();
        this.keySpan = new KeySpan(TrnxRecord.TRNX_KEY_OFFSET, TrnxRecord.TRNX_KEY_LENGTH);
    }

    /**
     * The resolved dataset name.
     *
     * @return the name {@code carddemo.datasets.TRNXFILE.dsname} declares; never {@code null} and never
     *         blank
     */
    public String datasetName() {
        return datasetName;
    }

    /**
     * The dataset contract the statement job composes its {@code TRNXFILE} statements from.
     *
     * @return the relation; never {@code null}
     */
    public DatasetRelation relation() {
        return relation;
    }

    /**
     * The copybook-declared record width, {@value TrnxRecord#RECORD_LENGTH}.
     *
     * @return the width in bytes
     */
    public int recordLength() {
        return TrnxRecord.RECORD_LENGTH;
    }

    /**
     * The {@code TRNX-KEY} span within the record.
     *
     * @return offset 0, width {@value TrnxRecord#TRNX_KEY_LENGTH}; never {@code null}
     */
    public KeySpan keySpan() {
        return keySpan;
    }

    /**
     * Requires the binding to agree with {@code COSTM01} about the record width. Gate
     * <strong>G19</strong> at startup.
     *
     * <p>This subroutine hands raw record bytes back in an {@code X(1000)} area, so a wrong width does
     * not fail at the read - it displaces every field the caller then decodes.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length differs
     */
    private static void requireCopybookRecordLength(final DatasetBinding binding) {
        if (binding.recordLength() != TrnxRecord.RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares a record "
                    + "length of " + binding.recordLength() + ", but " + COPYBOOK + " declares "
                    + TrnxRecord.RECORD_LENGTH + " and app/cbl/CBSTM03B.CBL:58-63 splits its FD record "
                    + "to exactly that width. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + TrnxRecord.RECORD_LENGTH + '.');
        }
    }

    /**
     * Requires the binding to declare the key width and offset the {@code RECORD KEY} declares.
     *
     * <p>{@code app/cbl/CBSTM03B.CBL:31-35} declares this {@code SELECT}
     * {@code ORGANIZATION IS INDEXED} with {@code RECORD KEY IS FD-TRNXFILE-KEY}, so a missing or
     * mismatched key width is a configuration defect even though this DD is only ever read
     * sequentially: a browse of an indexed file is a browse <em>in key order</em>.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if no key length is declared, a different one is, or a non-zero key
     *                               offset is
     */
    private static void requireDeclaredKeyGeometry(final DatasetBinding binding) {
        final Integer configured = binding.keyLength();
        if (configured == null) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares no "
                    + "key-length, but app/cbl/CBSTM03B.CBL:31-35 declares it ORGANIZATION IS INDEXED "
                    + "with a RECORD KEY, and " + COPYBOOK + " gives that key a width of "
                    + TrnxRecord.TRNX_KEY_LENGTH + ". Set carddemo.datasets." + DD_NAME
                    + ".key-length to " + TrnxRecord.TRNX_KEY_LENGTH + '.');
        }
        if (configured != TrnxRecord.TRNX_KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares key-length "
                    + configured + ", but the RECORD KEY of app/cbl/CBSTM03B.CBL is "
                    + TrnxRecord.TRNX_KEY_LENGTH + " bytes wide per " + COPYBOOK + ". A browse ordered "
                    + "by the wrong span would return the extract in an order the statement run does "
                    + "not expect. Correct carddemo.datasets." + DD_NAME + ".key-length.");
        }
        if (binding.keyOffsetOrZero() != TrnxRecord.TRNX_KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares key-offset "
                    + binding.keyOffsetOrZero() + ", but FD-TRNXFILE-KEY is the first field of the FD "
                    + "record at app/cbl/CBSTM03B.CBL:58-63, so the key begins at offset "
                    + TrnxRecord.TRNX_KEY_OFFSET + ". Remove carddemo.datasets." + DD_NAME
                    + ".key-offset.");
        }
    }

    /**
     * Requires a configured dataset name that can be composed into a statement.
     *
     * <p>Blank is refused as well as absent, because {@code application.yml} spells every name as an
     * environment reference and an unconfigured deployment yields an empty string rather than
     * {@code null}.
     *
     * @param candidate the configured dataset name
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if it is absent or blank
     */
    private static String requireUsableDatasetName(final String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + DD_NAME + "' declares no "
                    + "dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this module composes "
                    + "its statements from configuration alone and hard-codes no dataset name (gate "
                    + "G46).");
        }
        return candidate;
    }
}
