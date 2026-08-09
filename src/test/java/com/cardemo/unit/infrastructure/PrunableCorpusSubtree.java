/*
 * ******************************************************************
 * Program     : PrunableCorpusSubtree.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Single-sources the one frozen-corpus subtree a Docker
 *               build context legitimately prunes, and the DIRECTORY-
 *               KEYED rule that decides when a citation into it may be
 *               passed over. Every suite that resolves app/... paths
 *               against disk reads this type, so the rule cannot hold
 *               in one suite and be missing from the next.
 * Source      : .dockerignore  (the `app/data/EBCDIC` exclusion and the
 *                 note recording why the data is pruned rather than
 *                 admitted)
 *               Dockerfile  (the build stage that COPYs app/ and then
 *                 runs the unit tier inside the pruned context)
 *               app/data/EBCDIC/**  (twelve fixed-width .PS datasets
 *                 kept as byte-level codepage reference, never parsed)
 *               frozen at commit 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The one subtree of the frozen corpus whose citations are resolved only when the subtree is present.
 *
 * <h2>What is pruned, and why the data is not simply admitted</h2>
 *
 * <p>{@code app/data/EBCDIC} holds twelve fixed-width {@code .PS} datasets kept as byte-level codepage
 * reference. The Agent Action Plan puts them out of scope: nothing transcodes them and no build step parses
 * them. They are consequently the one part of the corpus a Docker build context legitimately prunes, and
 * {@code .dockerignore} prunes it, because 204 KB of material no stage reads has no business crossing to the
 * daemon.
 *
 * <p>Dropping that exclusion would also turn every assertion below green, and it is still the wrong lever: it
 * fixes a build-context artefact by widening what the daemon receives, and leaves out-of-scope material in the
 * context permanently. Narrowing the exclusion to the {@code .PS} files while keeping the directory is worse
 * again - two of those files are cited by name, so a context carrying the directory but not its contents fails
 * the citation gate on a genuinely broken-looking citation. The lever is this rule, and it is narrowed rather
 * than weakened.
 *
 * <h2>Why the rule is keyed on the DIRECTORY</h2>
 *
 * <p>The {@code Dockerfile} build stage copies {@code app/} and then runs the whole unit tier inside that
 * stage, so any suite that resolves an {@code app/...} path against disk runs once with the subtree present -
 * in a clone, in continuous integration, in a developer's build - and once with it absent, inside the pruned
 * context. Keyed on the subtree being ABSENT, the rule is inert everywhere the subtree exists: every citation
 * under it is then resolved exactly like any other, so a mistyped dataset name is still caught. Only a context
 * that carries none of those files passes them over. Keyed on an individual FILE being absent it would instead
 * hide precisely the defect it must catch.
 *
 * <h2>Why this lives in one type rather than in each suite</h2>
 *
 * <p>Two suites resolve {@code app/...} paths against disk, and a review found the rule in only one of them.
 * {@link SourceCitationResolutionTest} carried it and passed inside the pruned context;
 * {@link InventoryCountGateTest} carried nothing and failed there, which failed the whole image build on a
 * defect that existed only in the build context. Copying the rule into the second suite would have restored
 * the build and left the same trap open for a third suite. Holding it here means a suite either reads this
 * type or does not resolve corpus paths at all, and the two suites that do read the same constant and the same
 * predicate.
 *
 * <p>This type declares no test method, so the runner does not collect it -
 * {@code TestTierContractTest.SupportTypeShape} holds that shape for every support type in the tree. The
 * assertions about the rule's narrowness live with the suites that apply it:
 * {@code SourceCitationResolutionTest.theEbcdicExemptionIsNarrowAndSelfLimiting()} and
 * {@code InventoryCountGateTest.PrunableSubtreeExemption}, each of which is deliberately meaningful in BOTH
 * contexts rather than skipped in one.
 */
final class PrunableCorpusSubtree {

    /**
     * The pruned subtree, as a repository-relative path with forward slashes.
     *
     * <p>Deliberately one concrete subtree and not a prefix such as {@code app/data} or {@code app/}. Widening
     * it would silence citation failures across material the migration actually derives from - the ASCII
     * fixtures under {@code app/data/ASCII} are the authoritative seed and test input and are never pruned.
     */
    static final String PATH = "app/data/EBCDIC";

    /** Not instantiable: a constant and two predicates, with no state of any kind. */
    private PrunableCorpusSubtree() {
        throw new AssertionError("PrunableCorpusSubtree is a support type and is never instantiated");
    }

    /**
     * Reports whether the subtree is present under the given repository root.
     *
     * <p>Answers {@code true} in a clone, in continuous integration and in a developer's build, and
     * {@code false} only inside a build context that pruned it. A suite that needs to assert strictness in one
     * case and the rule's own effect in the other branches on this.
     *
     * <p>Inputs: the repository root. Output: whether the directory exists. Side effects: none.
     *
     * @param root the repository root, never {@code null}
     * @return {@code true} when {@value #PATH} exists as a directory under {@code root}
     */
    static boolean isPresentUnder(final Path root) {
        Objects.requireNonNull(root, "root");
        return Files.isDirectory(root.resolve(PATH));
    }

    /**
     * Reports whether a citation may be passed over without resolving it against disk.
     *
     * <p>True only when both halves hold: the whole subtree is absent, AND the citation points into it. Either
     * half alone is not enough, which is the whole of the rule - a citation under the subtree is resolved
     * strictly wherever the subtree exists, and a citation outside it is resolved strictly everywhere.
     *
     * <p>Inputs: the repository root and one repository-relative citation. Output: whether it is exempt. Side
     * effects: none.
     *
     * @param root the repository root, never {@code null}
     * @param citation a repository-relative citation such as {@code app/cpy/CVACT01Y.cpy}, never {@code null}
     * @return {@code true} when the citation is inside the pruned-away subtree
     */
    static boolean isExemptCitation(final Path root, final String citation) {
        Objects.requireNonNull(citation, "citation");
        return !isPresentUnder(root) && citation.startsWith(PATH);
    }
}
