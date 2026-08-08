package com.vsergeychik.carddemo.config;

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
 *   <li><strong>CWE-377, insecure temporary file.</strong> The USRSEC rows the suite materialises
 *       carry the legacy plaintext {@code SEC-USR-PWD} span, so the directory holding them must not
 *       be world-readable. Checked here by requiring owner-only {@code rwx------} on the root.</li>
 *   <li><strong>CWE-362, race.</strong> Two runs sharing one root open the same files and overwrite
 *       each other's output, producing a parity diff whose cause is another process. Checked here by
 *       requiring the configured root to carry both a clone component and a per-invocation component,
 *       so no two runs can resolve to the same path.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It does not create datasets, seed fixtures or run a job. It creates the root, proves the four
 * properties above hold for it, and removes only what it created - never ascending to the shared
 * parent, because deleting {@code /tmp/carddemo-test} would destroy a concurrent clone's run. The
 * cleanup is scoped to a directory this test made itself, inside the run-owned root.
 *
 * <p>Every POSIX-permission assertion is skipped on a filesystem with no POSIX view rather than
 * asserted loosely, so the suite states honestly which platform it verified.
 */
@DisplayName("carddemo.test.work-dir - run-unique, owner-only, never link-followed")
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
                .as("owner-only rwx------: the USRSEC seed materialised beneath this root carries "
                    + "the legacy plaintext SEC-USR-PWD span, so a group- or world-readable "
                    + "directory is a disclosure (CWE-377)")
                .isEqualTo(OWNER_ONLY_DIRECTORY);
        }

        @Test
        @DisplayName("a dataset written beneath it uses CREATE_NEW and NOFOLLOW_LINKS")
        void writesDoNotFollowLinks() throws IOException {
            ownedDirectory = createOwnedDirectory();
            Path dataset = ownedDirectory.resolve("usrsec.txt");

            Files.write(dataset, List.of("row"), StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            restrictToOwner(dataset);

            Assertions.assertThat(Files.readAllLines(dataset, StandardCharsets.US_ASCII))
                .containsExactly("row");
            Assertions.assertThat(Files.getPosixFilePermissions(dataset, LinkOption.NOFOLLOW_LINKS))
                .as("a dataset the suite writes is owner-only for the same reason the directory is")
                .isEqualTo(OWNER_ONLY_FILE);

            // The second CREATE_NEW must fail. That is the property that makes a pre-created path -
            // a real file or a planted symlink - impossible to write through silently.
            Assertions.assertThatThrownBy(() -> Files.write(dataset, List.of("second"),
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
                .as("CREATE_NEW refuses an existing path, so a planted file or link cannot be "
                    + "written through")
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a planted symlink beneath the root is refused rather than followed")
        void plantedSymlinkIsRefused() throws IOException {
            ownedDirectory = createOwnedDirectory();
            Path elsewhere = Files.createFile(ownedDirectory.resolve("attacker-target.txt"));
            Path planted = ownedDirectory.resolve("planted.txt");
            Files.createSymbolicLink(planted, elsewhere);

            Assertions.assertThatThrownBy(() -> Files.write(planted, List.of("redirected"),
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
                .as("this is CWE-59 reproduced deliberately: without NOFOLLOW_LINKS the write lands "
                    + "in the link's target and the suite reports success over data it did not "
                    + "write where it thought")
                .isInstanceOf(IOException.class);

            Assertions.assertThat(Files.readAllLines(elsewhere, StandardCharsets.US_ASCII))
                .as("the link target must be untouched")
                .isEmpty();
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
}
