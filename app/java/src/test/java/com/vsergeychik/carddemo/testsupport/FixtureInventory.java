package com.vsergeychik.carddemo.testsupport;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * The {@code test} profile's fixture inventory, bound from {@code carddemo.test.fixtures} and held to
 * what it declares.
 *
 * <h2>Why this class exists</h2>
 * <p>{@code src/test/resources/carddemo-test-fixtures.yml} declares ten entries: for each one a
 * classpath resource or an inline seed, the number of records, the measured bytes per record, the
 * copybook width to right-pad short records up to, and the DD names the entry serves. That declaration
 * was <em>documentation</em> - imported into the profile through
 * {@code spring.config.import: optional:classpath:/carddemo-test-fixtures.yml} and read by nothing. Its
 * geometry could drift from the fixtures it describes, its resources could stop resolving, and its
 * normalisations could go unapplied, and every one of those would have been silent.
 *
 * <p>So the declaration is now bound <strong>strictly</strong> - {@code ignoreUnknownFields = false}, so
 * a misspelled key fails the bind rather than being discarded - and {@link #validate(DatasetBindings)}
 * re-derives every number it states from the fixture bytes and from the shipped dataset catalogue. A
 * declaration is a claim, and a claim nothing checks is not evidence.
 *
 * <h2>Why it is a map, and why it lives here</h2>
 * <p>A {@code LinkedHashMap} subclass for the same reason {@code DataSourceConfig.DatasetBindings} and
 * {@code BatchConfig.JobContracts} are: it is the shape Spring's relaxed binder populates for a
 * keyed catalogue, and iteration order follows binding order deterministically. It sits in
 * {@code com.vsergeychik.carddemo.testsupport}, which is deliberately outside the shipped component
 * scan, because the inventory and the ten plaintext {@code USRSEC} rows it carries must never reach the
 * packaged artifact - the whole reason the declaring document is under {@code src/test/resources}
 * rather than beside {@code application-test.yml}.
 *
 * @see FixtureSeeder for the seeding this inventory drives
 */
@ConfigurationProperties(prefix = FixtureInventory.PREFIX, ignoreUnknownFields = false)
public class FixtureInventory extends LinkedHashMap<String, FixtureInventory.FixtureDeclaration> {

    /** The property prefix the declaring document uses. */
    public static final String PREFIX = "carddemo.test.fixtures";

    /** Serialisation identity, inherited from {@link LinkedHashMap}. */
    private static final long serialVersionUID = 1L;

    /**
     * The ten entries the declaring document is required to hold: the nine fixed-width fixtures derived
     * from {@code app/data/ASCII}, and {@code usrsec}, which has no file anywhere in the repository and
     * is seeded from the in-stream data of {@code app/jcl/DUSRSECJ.jcl}.
     *
     * <p>An exact set rather than a minimum, for the same reason the job catalogue and the dataset
     * catalogue are exact: a missing entry leaves a dataset silently empty, and an extra one describes
     * data no dataset reads.
     */
    static final Set<String> REQUIRED_FIXTURES = Set.of("acctdata", "carddata", "cardxref", "custdata",
            "dailytran", "discgrp", "tcatbal", "trancatg", "trantype", "usrsec");

    /** The byte a short fixture record is right-padded with - {@code PIC X} padding, 0x20. */
    static final char PAD = ' ';

    /**
     * One declared fixture.
     *
     * @param resource       the classpath location of the fixture file, or {@code null} when the entry
     *                       is seeded inline
     * @param records        the number of records the entry holds
     * @param bytesPerRecord the entry's measured on-disk width, which is <em>not</em> the copybook width
     * @param padTo          the copybook width to right-pad up to, present only where the entry is
     *                       short of it, and {@code null} otherwise
     * @param datasets       the DD names this one entry serves; several DD names address one dataset
     * @param seed           inline records, present only where no fixture file exists
     */
    public record FixtureDeclaration(String resource, int records, int bytesPerRecord, Integer padTo,
                                     List<String> datasets, List<String> seed) {

        /**
         * The width a record has once the declared normalisation has been applied.
         *
         * @return {@link #padTo()} when one is declared, and {@link #bytesPerRecord()} otherwise
         */
        public int normalisedWidth() {
            return padTo == null ? bytesPerRecord : padTo;
        }

        /**
         * Whether this entry is seeded from inline records rather than from a file.
         *
         * @return {@code true} when the entry declares seed rows
         */
        public boolean isInline() {
            return seed != null && !seed.isEmpty();
        }
    }

    /**
     * Holds the bound inventory to everything it declares, against the shipped dataset catalogue.
     *
     * <p>One method so a test can drive every arm by direct call. It checks, in order: the key set is
     * exactly {@link #REQUIRED_FIXTURES}; each entry declares either a resource or an inline seed and
     * never both; the record count and the width are positive and any declared pad is strictly wider
     * than the measured width; every DD name the entry serves exists in the catalogue; and every one of
     * those DD names declares the record length this entry normalises to - which is what makes the
     * declared pad the copybook width rather than a number of its own. It deliberately does <em>not</em>
     * read the fixture files: {@link #recordsOf(FixtureDeclaration, ResourceLoader, Charset)} does that,
     * and re-measures what it reads, so a caller that never seeds still gets the structural check.
     *
     * @param catalogue the shipped {@code carddemo.datasets} catalogue, already validated
     * @throws IllegalStateException if the inventory is incoherent or disagrees with the catalogue
     */
    public void validate(DatasetBindings catalogue) {
        Objects.requireNonNull(catalogue, "The dataset catalogue is required to validate the fixture "
                + "inventory, because a declared pad is only meaningful against the record length the "
                + "catalogue declares for the DD names the fixture serves");
        Set<String> missing = new LinkedHashSet<>(REQUIRED_FIXTURES);
        missing.removeAll(keySet());
        Set<String> unexpected = new LinkedHashSet<>(keySet());
        unexpected.removeAll(REQUIRED_FIXTURES);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalStateException(PREFIX + " must declare exactly the "
                    + REQUIRED_FIXTURES.size() + " entries the test profile seeds - the nine fixtures "
                    + "derived from app/data/ASCII plus usrsec, which has no file in the repository and "
                    + "comes from app/jcl/DUSRSECJ.jcl. Missing: " + sorted(missing) + ". Unexpected: "
                    + sorted(unexpected) + ".");
        }
        forEach((name, declaration) -> validateDeclaration(name, declaration, catalogue));
    }

    /**
     * The records one declared entry holds, normalised to the width it declares.
     *
     * <p>The file is read as bytes in the configured code page and split on the line terminator, and
     * <strong>every line is re-measured</strong>: a fixture whose width or record count has drifted from
     * the declaration fails here rather than seeding a dataset the declaration misdescribes. Each record
     * is then right-padded once, with spaces, to {@link FixtureDeclaration#normalisedWidth()} - the
     * single owner of the pad, at seed time, before anything decodes it.
     *
     * @param declaration the entry to read
     * @param loader      resolves the declared {@code classpath:} resource
     * @param charset     the code page the fixture is stored in - {@code US-ASCII} under this profile,
     *                    and never the platform default
     * @return the normalised records, in file order
     * @throws IllegalStateException if the resource does not resolve, or the file's geometry differs
     *                               from what the entry declares
     */
    public List<String> recordsOf(FixtureDeclaration declaration, ResourceLoader loader,
                                  Charset charset) {
        Objects.requireNonNull(declaration, "A declaration is required");
        Objects.requireNonNull(charset, "The code page is required: a fixture is bytes, and decoding it "
                + "in the platform default would be the one thing this module never does");
        List<String> raw = declaration.isInline()
                ? List.copyOf(declaration.seed())
                : readLines(declaration, Objects.requireNonNull(loader, "A resource loader is required"),
                        charset);

        if (raw.size() != declaration.records()) {
            throw new IllegalStateException("The fixture " + describe(declaration) + " holds "
                    + raw.size() + " record(s) but declares " + declaration.records()
                    + ". The declaration is the thing a context-load test asserts against, so a "
                    + "disagreement is a defect in one of the two and never something to reconcile "
                    + "silently.");
        }
        List<String> normalised = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            String record = raw.get(index);
            if (record.length() != declaration.bytesPerRecord()) {
                throw new IllegalStateException("Record " + (index + 1) + " of " + describe(declaration)
                        + " measures " + record.length() + " byte(s) but the entry declares "
                        + declaration.bytesPerRecord() + " for every record. A fixed-width fixture has "
                        + "exactly one width; a ragged one would shift every field offset after the "
                        + "first short record.");
            }
            normalised.add(pad(record, declaration.normalisedWidth()));
        }
        return List.copyOf(normalised);
    }

    /**
     * The distinct dataset names one entry seeds, resolved through the catalogue.
     *
     * <p>Distinct, because several DD names address one dataset: {@code ACCTDAT} is the CICS file and
     * {@code ACCTFILE} the batch DD over the same account master, and an alternate-index path is a
     * finder over its base cluster rather than a second table (gate G45). Seeding once per dataset is
     * what keeps a five-DD entry from inserting its fifty records five times.
     *
     * @param declaration the entry to resolve
     * @param catalogue   the shipped catalogue
     * @return the distinct dataset names, in declaration order
     */
    public List<String> datasetNamesOf(FixtureDeclaration declaration, DatasetBindings catalogue) {
        Objects.requireNonNull(declaration, "A declaration is required");
        Objects.requireNonNull(catalogue, "The dataset catalogue is required");
        Set<String> names = new LinkedHashSet<>();
        for (String ddName : declaration.datasets()) {
            names.add(catalogue.binding(ddName).dsname());
        }
        return List.copyOf(names);
    }

    /**
     * Validates one entry against the catalogue.
     *
     * @param name        the entry's key, quoted in every diagnostic
     * @param declaration the entry
     * @param catalogue   the shipped catalogue
     * @throws IllegalStateException if the entry is incoherent or disagrees with the catalogue
     */
    private void validateDeclaration(String name, FixtureDeclaration declaration,
                                     DatasetBindings catalogue) {
        if (declaration == null) {
            throw new IllegalStateException(invalid(name) + " it declares no properties at all.");
        }
        boolean hasResource = declaration.resource() != null && !declaration.resource().isBlank();
        if (hasResource == declaration.isInline()) {
            throw new IllegalStateException(invalid(name) + " an entry is either backed by a fixture "
                    + "file or seeded inline, and this one declares "
                    + (hasResource ? "both" : "neither") + ". usrsec is the only inline entry, because "
                    + "app/data/ASCII holds no usrsec file; every other entry names a resource.");
        }
        if (declaration.records() <= 0) {
            throw new IllegalStateException(invalid(name) + " it declares " + declaration.records()
                    + " records. An entry that seeds nothing would leave its datasets silently empty.");
        }
        if (declaration.bytesPerRecord() <= 0) {
            throw new IllegalStateException(invalid(name) + " it declares "
                    + declaration.bytesPerRecord() + " bytes per record.");
        }
        if (declaration.padTo() != null && declaration.padTo() <= declaration.bytesPerRecord()) {
            throw new IllegalStateException(invalid(name) + " it declares pad-to "
                    + declaration.padTo() + " against a measured width of "
                    + declaration.bytesPerRecord() + ". pad-to is present only where the fixture is "
                    + "SHORT of its copybook width, so declaring one that is not wider either states a "
                    + "deviation that does not exist or would truncate the record.");
        }
        if (declaration.datasets() == null || declaration.datasets().isEmpty()) {
            throw new IllegalStateException(invalid(name) + " it names no DD name, so nothing it holds "
                    + "would ever be read.");
        }
        for (String ddName : declaration.datasets()) {
            DatasetBinding binding = catalogue.binding(ddName);
            if (binding.recordLength() != declaration.normalisedWidth()) {
                throw new IllegalStateException(invalid(name) + " it normalises to "
                        + declaration.normalisedWidth() + " bytes, but the catalogue declares DD name "
                        + ddName + " at " + binding.recordLength() + ". The copybook width is the "
                        + "contract: a fixture is padded UP to it and the configured length is never "
                        + "adjusted down to a fixture (gates G16, G21).");
            }
        }
    }

    /**
     * Reads a fixture file's lines.
     *
     * @param declaration the entry naming the resource
     * @param loader      resolves the {@code classpath:} location
     * @param charset     the code page to decode in
     * @return the lines, in file order, with no terminator
     * @throws IllegalStateException if the resource does not resolve
     */
    private static List<String> readLines(FixtureDeclaration declaration, ResourceLoader loader,
                                          Charset charset) {
        Resource resource = loader.getResource(declaration.resource());
        if (!resource.exists()) {
            throw new IllegalStateException("The fixture resource " + declaration.resource()
                    + " does not resolve on the test classpath. A location that does not resolve seeds "
                    + "an empty dataset, and an empty dataset produces a parity diff whose cause is "
                    + "expensive to trace back to a missing file.");
        }
        try (var input = resource.getInputStream()) {
            String text = new String(input.readAllBytes(), charset);
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            lines.replaceAll(line -> line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            // A terminated file yields one empty trailing element, and that element is the terminator
            // rather than a record. Dropping every trailing empty is safe because a fixed-width record
            // of a positive declared width can never be empty - a genuinely empty line inside the file
            // survives and is reported by the width check as the ragged record it is.
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The fixture resource " + declaration.resource()
                    + " could not be read", unreadable);
        }
    }

    /**
     * Right-pads one record with spaces to the given width.
     *
     * @param record the record as read
     * @param width  the normalised width
     * @return the record at exactly {@code width} characters
     */
    private static String pad(String record, int width) {
        if (record.length() >= width) {
            return record;
        }
        return record + String.valueOf(PAD).repeat(width - record.length());
    }

    /**
     * Names one entry in a diagnostic.
     *
     * @param declaration the entry
     * @return the resource location, or the inline description when there is none
     */
    private static String describe(FixtureDeclaration declaration) {
        return declaration.isInline() ? "the inline usrsec seed" : declaration.resource();
    }

    /**
     * The leading clause of an invalid-entry diagnostic.
     *
     * @param name the entry's key
     * @return the clause, naming the full property path
     */
    private static String invalid(String name) {
        return PREFIX + "." + name + " is invalid:";
    }

    /**
     * A stable rendering of a name set for a diagnostic.
     *
     * @param names the names
     * @return the names in ascending order
     */
    private static List<String> sorted(Set<String> names) {
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(String::compareTo);
        return ordered;
    }

    /**
     * The entries this inventory holds, as an unmodifiable view for a caller that only reads.
     *
     * @return the declarations by name, in binding order
     */
    public Map<String, FixtureDeclaration> declarations() {
        return Map.copyOf(this);
    }
}
