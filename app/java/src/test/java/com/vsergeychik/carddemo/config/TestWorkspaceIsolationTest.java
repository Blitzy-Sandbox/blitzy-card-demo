package com.vsergeychik.carddemo.config;

import java.nio.charset.Charset;
import com.vsergeychik.carddemo.transaction.ReportRequestController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Enforces the security contract of {@code carddemo.test.work-dir}, the writable root every dataset a
 * test run produces is rooted at.
 *
 * <h2>Why a test owns this</h2>
 * <p>{@code src/test/resources/application-test.yml} can only say <em>where</em> the root is. It
 * cannot create it safely, and a comment asserting that it will be created safely is worth nothing.
 * The three weaknesses that a shared temporary root carries are all properties of how the directory
 * is <em>made and used</em>, so they are asserted here, at build time, against the configured value:
 * <ul>
 *   <li><strong>CWE-59, link following.</strong> A predictable path under a world-writable sticky
 *       {@code /tmp} can be pre-created by any other user on the host as a symlink pointing
 *       elsewhere. Every write the suite then performs is redirected while the suite still passes.
 *       Checked here by refusing to accept a root that is a symlink under
 *       {@link LinkOption#NOFOLLOW_LINKS}, and by writing beneath it with
 *       {@link StandardOpenOption#CREATE_NEW} together with {@link LinkOption#NOFOLLOW_LINKS} so an
 *       existing link is never followed.</li>
 *   <li><strong>CWE-377, insecure temporary file.</strong> What materialises beneath this root is
 *       the job-submission destination - {@code carddemo.job-submission.destination} resolves to
 *       {@code ${carddemo.test.work-dir}/inreader/JOBS} - whose records are the eighty-byte JCL
 *       skeletons {@code CORPT00C} writes, naming datasets and the submitting user. So the directory
 *       must not be world-readable. Checked here by requiring owner-only {@code rwx------} on the
 *       root, and by driving the real port and reading back the permissions it created its file
 *       with.
 *       <p>This bullet used to claim the root holds materialised {@code USRSEC} rows with their
 *       legacy plaintext {@code SEC-USR-PWD} span. That was wrong and worth stating plainly, because
 *       it justified the check with a file that is never there: under the {@code test} profile every
 *       dataset including {@code USRSEC} is a relation in the in-memory H2 database, not a file in
 *       this tree. The correct justification is the one above, which is a file this module really
 *       does write.</li>
 *   <li><strong>CWE-362, race.</strong> Two runs sharing one root open the same files and overwrite
 *       each other's output, producing a parity diff whose cause is another process. Checked here by
 *       requiring the configured root to carry both a clone component and a per-invocation component,
 *       so no two runs can resolve to the same path.</li>
 * </ul>
 *
 * <h2>Asserted through the production writer, not through the JDK</h2>
 * <p>The link-following and permission properties are asserted by driving
 * {@link ReportRequestController.InternalReaderJobSubmissionPort} - the module's one filesystem writer,
 * and the component {@code carddemo.job-submission.approved-root} points at this very property for.
 * That distinction is the whole value of these tests. They previously wrote a file themselves with
 * {@link StandardOpenOption#CREATE_NEW} and {@link LinkOption#NOFOLLOW_LINKS} and then asserted the
 * write behaved as those flags promise, which tests the JDK rather than this module: had production
 * code omitted either flag, every assertion would still have passed. Now the flags under test are the
 * ones the port passes, so a regression in the port fails here.
 *
 * <p>The port defends the descent in four independent places, which is worth knowing before changing
 * any of them: the per-element {@link Files#isSymbolicLink(Path)} test, the per-element
 * "exists but is not a directory" test, {@code requireParentStillWithinRoot}'s requirement that the
 * parent resolve to itself and inside the root, and the {@link LinkOption#NOFOLLOW_LINKS} on the
 * channel open. Disabling any one of them alone leaves every test here passing, because another still
 * refuses; the tests below were each confirmed to fail once the layer that actually answers them was
 * removed, so none of them is passing by accident.
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It does not seed fixtures or run a job, and it asserts nothing about the datasets a job produces.
 * It creates the root, proves the properties above hold for it and for the port writing beneath it,
 * and removes only what it created - never ascending to the shared parent, because deleting
 * {@code /tmp/carddemo-test} would destroy a concurrent clone's run. The cleanup is scoped to a
 * directory this test made itself, inside the run-owned root.
 *
 * <p>Every POSIX-permission assertion is skipped on a filesystem with no POSIX view rather than
 * asserted loosely, so the suite states honestly which platform it verified.
 */
@DisplayName("carddemo.test.work-dir - run-unique, owner-only, never link-followed, proven through "
        + "the production job-submission writer")
class TestWorkspaceIsolationTest {

    /** The configuration key under test. */
    private static final String WORK_DIR_PROPERTY = "carddemo.test.work-dir";

    /** The profile that declares it. */
    private static final String TEST_PROFILE = "test";

    /** Owner-only directory permissions: {@code rwx------}. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
        PosixFilePermissions.fromString("rwx------");

    /**
     * The permissions a file written beneath the root may carry at most: owner read and write. A
     * group- or world-readable dataset would defeat the point of an owner-only root.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
        PosixFilePermissions.fromString("rw-------");

    /**
     * The subdirectory this test creates and is therefore entitled to delete. Named after the test so
     * that a leftover directory is attributable, and never the run root itself.
     */
    private static final String OWNED_SUBDIRECTORY = "workspace-isolation-check";

    /**
     * The two components the configured root must carry to be unique on a shared host: the clone
     * index and the per-invocation run id. Matched structurally rather than by value, because the
     * values differ per clone and per run - which is the property being asserted.
     */
    private static final Pattern UNIQUE_ROOT_SHAPE =
        Pattern.compile(".*carddemo-test[/\\\\][^/\\\\]+[/\\\\][^/\\\\]+$");

    /** The resolved value of {@link #WORK_DIR_PROPERTY} for this run. */
    private String configuredWorkDir;

    /** The directory this test created, and the only one it may remove. */
    private Path ownedDirectory;

    @BeforeEach
    void resolveConfiguredWorkDir() {
        // An ApplicationContextRunner slice over the shipped test profile, the pattern the other
        // config suites use: the real application-test.yml is the property source, no bean of this
        // module is registered, and nothing beyond the one property is resolved.
        AtomicReference<String> resolved = new AtomicReference<>();
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=" + TEST_PROFILE)
            .run(context -> resolved.set(context.getEnvironment().getProperty(WORK_DIR_PROPERTY)));
        configuredWorkDir = resolved.get();
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() throws IOException {
        if (ownedDirectory == null) {
            return;
        }
        deleteRecursivelyWithin(ownedDirectory);
        ownedDirectory = null;
    }

    @Nested
    @DisplayName("Uniqueness")
    class Uniqueness {

        @Test
        @DisplayName("the configured root is resolved and carries no unresolved placeholder")
        void theRootResolves() {
            Assertions.assertThat(configuredWorkDir)
                .as("%s must resolve to a real path. An unresolved ${...} would become a literal "
                    + "directory name, and every dataset below it would land in a directory named "
                    + "after the placeholder rather than in a unique one.", WORK_DIR_PROPERTY)
                .isNotNull()
                .isNotBlank()
                .doesNotContain("${");
        }

        @Test
        @DisplayName("the root carries both a clone component and a per-invocation component")
        void theRootIsCloneAndRunUnique() {
            Assertions.assertThat(configuredWorkDir)
                .as("the root must end in <clone>/<run-id> beneath carddemo-test, which is what "
                    + "stops two clones or two invocations resolving to the same writable directory "
                    + "(CWE-362). Resolved value: %s", configuredWorkDir)
                .matches(UNIQUE_ROOT_SHAPE);
        }

        @Test
        @DisplayName("the root is not the shared parent, and not a bare temporary directory")
        void theRootIsNotShared() {
            Path root = Path.of(configuredWorkDir);
            Assertions.assertThat(root.getNameCount())
                .as("a root at the top of the temporary directory would be shared with every other "
                    + "process on the host")
                .isGreaterThan(2);
            Assertions.assertThat(root.getFileName().toString())
                .as("the last path element identifies the invocation")
                .isNotBlank();
        }

        @Test
        @DisplayName("the root lies outside the repository, so no run can write into a reference tree")
        void theRootLiesOutsideTheRepository() {
            Path root = Path.of(configuredWorkDir).toAbsolutePath().normalize();
            Path module = Path.of("").toAbsolutePath().normalize();
            Assertions.assertThat(root.startsWith(module))
                .as("practice B3 is structural: app/cbl, app/cpy, app/cpy-bms, app/bms, app/jcl, "
                    + "app/proc, app/csd, app/ctl, app/catlg and app/data are the read-only parity "
                    + "oracle, and a writable root inside the module could reach them. Root %s, "
                    + "module %s", root, module)
                .isFalse();
        }
    }

    @Nested
    @DisplayName("Secure creation")
    @EnabledIf("com.vsergeychik.carddemo.config.TestWorkspaceIsolationTest#posixSupported")
    class SecureCreation {

        @Test
        @DisplayName("the run-owned directory is created owner-only and is a real directory")
        void createdOwnerOnly() throws IOException {
            ownedDirectory = createOwnedDirectory();

            Assertions.assertThat(Files.isDirectory(ownedDirectory, LinkOption.NOFOLLOW_LINKS))
                .as("checked with NOFOLLOW_LINKS: a symlink pre-created by another user would "
                    + "otherwise satisfy isDirectory and redirect every write (CWE-59)")
                .isTrue();
            Assertions.assertThat(Files.isSymbolicLink(ownedDirectory))
                .as("the run-owned directory must never be a symlink")
                .isFalse();
            Assertions.assertThat(Files.getPosixFilePermissions(ownedDirectory,
                    LinkOption.NOFOLLOW_LINKS))
                .as("owner-only rwx------: the job-submission destination materialised beneath this "
                    + "root holds JCL skeletons naming datasets and the submitting user, so a group- "
                    + "or world-readable directory is a disclosure (CWE-377)")
                .isEqualTo(OWNER_ONLY_DIRECTORY);
        }

        @Test
        @DisplayName("the production port writes beneath the root and creates its file owner-only")
        void theProductionPortWritesOwnerOnlyBeneathTheRoot() throws IOException {
            ownedDirectory = createOwnedDirectory();
            Path destination = ownedDirectory.resolve("inreader").resolve("JOBS");
            ReportRequestController.InternalReaderJobSubmissionPort port = portWriting(destination);

            ReportRequestController.WriteQueueOutcome outcome =
                port.writeQueueTd(ReportRequestController.JOB_LINE_01);

            Assertions.assertThat(outcome)
                .as("a well-formed eighty-character record is written, so the root is usable as "
                    + "configured")
                .isEqualTo(ReportRequestController.WriteQueueOutcome.NORMAL);
            Assertions.assertThat(Files.readAllBytes(destination))
                .as("RECORDSIZE(80) RECORDFORMAT(FIXED): one record is eighty bytes on the medium")
                .hasSize(ReportRequestController.JCL_RECORD_LENGTH);
            Assertions.assertThat(Files.getPosixFilePermissions(destination,
                    LinkOption.NOFOLLOW_LINKS))
                .as("the permissions are the port's own, applied at creation: this is the assertion "
                    + "that would have failed had production code created the file with the process "
                    + "umask instead")
                .isEqualTo(OWNER_ONLY_FILE);
            Assertions.assertThat(Files.getPosixFilePermissions(destination.getParent(),
                    LinkOption.NOFOLLOW_LINKS))
                .as("and the directory the port created on the way down is owner-only too")
                .isEqualTo(OWNER_ONLY_DIRECTORY);
            Assertions.assertThat(destination.toRealPath()
                    .startsWith(Path.of(configuredWorkDir).toRealPath()))
                .as("the record landed inside the configured root, not merely at a path spelled as "
                    + "though it were")
                .isTrue();
        }

        @Test
        @DisplayName("a symlink planted at the destination is refused by the port, not followed")
        void aPlantedSymlinkIsRefusedByThePort() throws IOException {
            ownedDirectory = createOwnedDirectory();
            Path queueDirectory = createOwnerOnlyDirectory(ownedDirectory.resolve("inreader"));
            Path elsewhere = Files.createFile(ownedDirectory.resolve("attacker-target.txt"));
            Path destination = queueDirectory.resolve("JOBS");
            Files.createSymbolicLink(destination, elsewhere);

            // CWE-59 reproduced against production code. The link is inside the approved root and
            // spelled exactly as the configuration says, so a containment check alone accepts it; only
            // a per-element no-follow inspection catches it.
            ReportRequestController.WriteQueueOutcome outcome =
                portWriting(destination).writeQueueTd(ReportRequestController.JOB_LINE_01);

            Assertions.assertThat(outcome)
                .as("EXEC CICS WRITEQ TD has ERROROPTION(IGNORE), so the port reports rather than "
                    + "throws: an unusable destination is RESP NOTOPEN")
                .isEqualTo(ReportRequestController.WriteQueueOutcome.notOpen());
            Assertions.assertThat(Files.readAllBytes(elsewhere))
                .as("the link target must be untouched - a followed link would leave eighty bytes "
                    + "here and still report success")
                .isEmpty();
        }

        @Test
        @DisplayName("a symlink planted on the way down to the destination is refused as well")
        void aPlantedIntermediateSymlinkIsRefusedByThePort() throws IOException {
            ownedDirectory = createOwnedDirectory();
            Path outsideTree = createOwnerOnlyDirectory(ownedDirectory.resolve("attacker-tree"));
            // "inreader" itself is the link, so the destination's own name is never a link and the
            // refusal has to come from inspecting each element of the descent.
            Files.createSymbolicLink(ownedDirectory.resolve("inreader"), outsideTree);
            Path destination = ownedDirectory.resolve("inreader").resolve("JOBS");

            ReportRequestController.WriteQueueOutcome outcome =
                portWriting(destination).writeQueueTd(ReportRequestController.JOB_LINE_01);

            Assertions.assertThat(outcome)
                .as("an intermediate link redirects the whole subtree while the destination string "
                    + "still lies inside the approved root")
                .isEqualTo(ReportRequestController.WriteQueueOutcome.notOpen());
            Assertions.assertThat(Files.list(outsideTree).toList())
                .as("nothing was created through the link")
                .isEmpty();
        }

        @Test
        @DisplayName("a destination outside the approved root is refused before any write")
        void aDestinationOutsideTheApprovedRootIsRefused() throws IOException {
            ownedDirectory = createOwnedDirectory();
            // Outside the approved root - which is the run-owned directory this test writes through -
            // but still inside the run-unique root, so the path cannot collide with another run's.
            // Naming it in the clone-level parent instead would put an absence assertion on a directory
            // shared between runs, which is precisely the CWE-362 hazard this class exists to guard: a
            // path another run had created would fail this test for a reason having nothing to do with
            // the code under test.
            Path outside = Path.of(configuredWorkDir).resolve("outside-approved-root-JOBS");
            Files.deleteIfExists(outside);

            // Containment is the property that keeps a mis-set destination from writing anywhere on the
            // host. The refusal is reported rather than thrown: EXEC CICS WRITEQ TD has
            // ERROROPTION(IGNORE), so the port resolves the destination once, keeps why it could not be
            // used, and answers every write with RESP NOTOPEN - which is what an extrapartition queue
            // whose dataset cannot be opened reports. An exception here would be the wrong contract, and
            // asserting one is how this test first got it wrong.
            Assertions.assertThat(portWriting(outside)
                    .writeQueueTd(ReportRequestController.JOB_LINE_01))
                .as("a destination outside %s must never be written to", configuredWorkDir)
                .isEqualTo(ReportRequestController.WriteQueueOutcome.notOpen());
            Assertions.assertThat(Files.exists(outside, LinkOption.NOFOLLOW_LINKS))
                .as("and nothing was created there")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("Cleanup")
    class Cleanup {

        @Test
        @DisplayName("cleanup removes the run-owned directory and never ascends to the shared parent")
        void cleanupIsScopedToTheOwnedDirectory() throws IOException {
            Path owned = createOwnedDirectory();
            Files.createFile(owned.resolve("dalyrejs.txt"));
            Files.createDirectory(owned.resolve("inreader"));
            Files.createFile(owned.resolve("inreader").resolve("JOBS"));

            deleteRecursivelyWithin(owned);

            Assertions.assertThat(Files.exists(owned, LinkOption.NOFOLLOW_LINKS)).isFalse();
            Assertions.assertThat(Files.isDirectory(Path.of(configuredWorkDir)))
                .as("the run root itself survives: cleanup is confined to what this test created")
                .isTrue();

            // And the shared parent above the run root is untouched, which is the part that would
            // destroy a concurrent clone's run if cleanup ever ascended.
            Path sharedParent = Path.of(configuredWorkDir).getParent();
            Assertions.assertThat(Files.isDirectory(sharedParent))
                .as("the clone-level parent %s is shared and must never be removed", sharedParent)
                .isTrue();
        }

        @Test
        @DisplayName("deleting a path outside the owned directory is refused")
        void deletingOutsideTheOwnedDirectoryIsRefused() throws IOException {
            ownedDirectory = createOwnedDirectory();
            Path outside = Path.of(configuredWorkDir);

            Assertions.assertThatThrownBy(() -> requireWithin(ownedDirectory, outside))
                .as("a recursive delete must be provably confined; a helper that accepted an "
                    + "ancestor would be one typo away from removing a shared directory")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
        }
    }

    /**
     * Creates the run-owned subdirectory, and the run root above it, with owner-only permissions.
     *
     * <p>Directories are created one level at a time with an explicit permission set rather than
     * through {@code createDirectories} plus a later {@code setPosixFilePermissions}, so there is no
     * window in which the directory exists with the process umask's permissions.
     *
     * @return the created directory, always beneath the configured run root
     * @throws IOException if creation fails
     */
    private Path createOwnedDirectory() throws IOException {
        Path root = Path.of(configuredWorkDir);
        createOwnerOnlyDirectories(root);
        Path owned = root.resolve(OWNED_SUBDIRECTORY);
        if (Files.isDirectory(owned, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursivelyWithin(owned);
        }
        return createOwnerOnlyDirectory(owned);
    }

    /** Creates every missing element of a path, each owner-only. */
    private static void createOwnerOnlyDirectories(Path directory) throws IOException {
        Path parent = directory.getParent();
        if (parent != null && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            createOwnerOnlyDirectories(parent);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            createOwnerOnlyDirectory(directory);
        }
    }

    /**
     * Creates one directory with owner-only permissions where the platform supports them.
     *
     * @param directory the directory to create
     * @return {@code directory}
     * @throws IOException if creation fails
     */
    private static Path createOwnerOnlyDirectory(Path directory) throws IOException {
        if (posixSupported()) {
            return Files.createDirectory(directory,
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        }
        return Files.createDirectory(directory);
    }

    /** Narrows a file to owner read and write where the platform supports POSIX permissions. */
    private static void restrictToOwner(Path file) throws IOException {
        if (posixSupported()) {
            Files.setPosixFilePermissions(file, OWNER_ONLY_FILE);
        }
    }

    /**
     * Deletes a directory tree, refusing anything that is not strictly beneath itself.
     *
     * <p>The guard is the point: a recursive delete in a shared temporary directory is exactly the
     * operation that must never be handed an ancestor.
     */
    private static void deleteRecursivelyWithin(Path owned) throws IOException {
        if (!Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(owned)) {
            List<Path> deepestFirst = tree.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                requireWithin(owned, path);
                try {
                    Files.deleteIfExists(path);
                } catch (NoSuchFileException alreadyGone) {
                    // Another element of the same tree removed it; nothing further to do.
                    continue;
                }
            }
        }
    }

    /**
     * Requires that {@code candidate} is {@code owned} itself or lies beneath it.
     *
     * @throws IllegalArgumentException if the candidate lies outside the owned directory
     */
    private static void requireWithin(Path owned, Path candidate) {
        Path normalisedOwned = owned.toAbsolutePath().normalize();
        Path normalisedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalisedCandidate.startsWith(normalisedOwned)) {
            throw new IllegalArgumentException("Refusing to delete " + normalisedCandidate
                + " because it lies outside the run-owned directory " + normalisedOwned
                + ". Cleanup is confined to what this run created: the parent directories are "
                + "shared with every parallel clone, and removing one would destroy another run.");
        }
    }

    /**
     * Whether the filesystem holding the configured root exposes POSIX permissions.
     *
     * <p>Referenced by {@code @EnabledIf} as well as by the helpers, so the permission assertions are
     * skipped honestly on a filesystem that cannot carry them rather than asserted loosely.
     *
     * @return {@code true} when a POSIX attribute view is available
     */
    static boolean posixSupported() {
        Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        return temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix")
            && Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null;
    }

    /**
     * The real production writer, configured exactly as {@code application-test.yml} configures it:
     * {@code approved-root} is the run-owned root this class guards and {@code destination} is the file
     * beneath it.
     *
     * <p>{@code IBM037} is passed explicitly rather than taken from the binding because the code page is
     * irrelevant to every property asserted here and naming it removes a platform-default dependency
     * (practice B8).
     *
     * @param destination the file the port should append to
     * @return a port bound to that destination
     */
    private ReportRequestController.InternalReaderJobSubmissionPort portWriting(Path destination) {
        WebConfig.JobSubmissionProperties properties = new WebConfig.JobSubmissionProperties("JOBS",
            "INREADER", "IBM037", ReportRequestController.JCL_RECORD_LENGTH, "FIXED", "UNBLOCKED",
            "MOD", ownedDirectory.toString(), destination.toString());
        return new ReportRequestController.InternalReaderJobSubmissionPort(properties,
            Charset.forName("IBM037"));
    }

}
