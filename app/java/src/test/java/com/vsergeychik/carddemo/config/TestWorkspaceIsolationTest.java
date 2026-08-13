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
 * Enforces the security contract of {@code carddemo.test.work-dir}, the writable root every dataset a test
 * run produces is rooted at.
 */
@DisplayName("carddemo.test.work-dir - run-unique, owner-only, never link-followed, proven through "
        + "the production job-submission writer")
class TestWorkspaceIsolationTest {
    private static final String WORK_DIR_PROPERTY = "carddemo.test.work-dir";

    private static final String TEST_PROFILE = "test";

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
        PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
        PosixFilePermissions.fromString("rw-------");

    private static final String OWNED_SUBDIRECTORY = "workspace-isolation-check";

    private static final Pattern UNIQUE_ROOT_SHAPE =
        Pattern.compile(".*carddemo-test[/\\\\][^/\\\\]+[/\\\\][^/\\\\]+$");

    private String configuredWorkDir;

    private Path ownedDirectory;

    @BeforeEach
    void resolveConfiguredWorkDir() {
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
            Path outside = Path.of(configuredWorkDir).resolve("outside-approved-root-JOBS");
            Files.deleteIfExists(outside);

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

    private Path createOwnedDirectory() throws IOException {
        Path root = Path.of(configuredWorkDir);
        createOwnerOnlyDirectories(root);
        Path owned = root.resolve(OWNED_SUBDIRECTORY);
        if (Files.isDirectory(owned, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursivelyWithin(owned);
        }
        return createOwnerOnlyDirectory(owned);
    }

    private static void createOwnerOnlyDirectories(Path directory) throws IOException {
        Path parent = directory.getParent();
        if (parent != null && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            createOwnerOnlyDirectories(parent);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            createOwnerOnlyDirectory(directory);
        }
    }

    private static Path createOwnerOnlyDirectory(Path directory) throws IOException {
        if (posixSupported()) {
            return Files.createDirectory(directory,
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        }
        return Files.createDirectory(directory);
    }

    private static void restrictToOwner(Path file) throws IOException {
        if (posixSupported()) {
            Files.setPosixFilePermissions(file, OWNER_ONLY_FILE);
        }
    }

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
                    continue;
                }
            }
        }
    }

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

    static boolean posixSupported() {
        Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        return temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix")
            && Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null;
    }

    private ReportRequestController.InternalReaderJobSubmissionPort portWriting(Path destination) {
        WebConfig.JobSubmissionProperties properties = new WebConfig.JobSubmissionProperties("JOBS",
            "INREADER", "IBM037", ReportRequestController.JCL_RECORD_LENGTH, "FIXED", "UNBLOCKED",
            "MOD", ownedDirectory.toString(), destination.toString());
        return new ReportRequestController.InternalReaderJobSubmissionPort(properties,
            Charset.forName("IBM037"));
    }

}
