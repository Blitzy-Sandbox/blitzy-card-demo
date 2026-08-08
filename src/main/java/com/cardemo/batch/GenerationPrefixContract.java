/*
 * ******************************************************************
 * Program     : GenerationPrefixContract.java
 * Application : CardDemo
 * Type        : Batch object-key namespace contract and startup
 *               validator (Java 25 / Spring Boot 3.5.11)
 * Function    : Holds the one grammar every S3 object-key prefix in the
 *               batch layer must satisfy, and refuses at startup any
 *               configuration in which two of the nine configured roots
 *               could resolve to one another's objects.
 * Source      : app/jcl/DEFGDGB.jcl :L21-L45 (the GDG base definitions
 *               whose relative generation references become the key
 *               prefixes of AAP section 0.5.2.2);
 *               app/jcl/DALYREJS.jcl :L24-L28 (DALYREJS base);
 *               app/jcl/REPTFILE.jcl :L22-L28 (TRANREPT base);
 *               app/proc/TRANREPT.prc :L27-L31 (TRANSACT.BKUP base);
 *               app/proc/TRANREPT.prc :L49-L53 (TRANSACT.DALY base);
 *               app/jcl/INTCALC.jcl :L37-L41 (SYSTRAN base);
 *               app/jcl/COMBTRAN.jcl :L33-L37 (TRANSACT.COMBINED base);
 *               app/jcl/PRTCATBL.jcl :L35-L39 (TCATBALF.BKUP base);
 *               app/jcl/CREASTMT.JCL :L26-L32 (TRXFL work cluster,
 *               KEYS(32 0) RECORDSIZE(350 350));
 *               app/catlg/LISTCAT.txt :L3555 (TRANSACT cluster,
 *               whose object mirror carries the ninth root) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one grammar every batch object-key prefix must satisfy, and the startup guard that no two configured
 * roots can resolve to one another's objects.
 *
 * <h2>What it does</h2>
 *
 * <p><strong>Finding m-02, severity Medium, RESOLVED.</strong> Six classes each carried their own private
 * prefix validator and no two agreed. Measured before this class existed:
 *
 * <table border="1">
 *   <caption>The six divergent validators the review found</caption>
 *   <tr><th>Class</th><th>Rule</th><th>Missing</th></tr>
 *   <tr><td>{@code InterestCalculationJob.normalisePrefix}</td>
 *       <td>strips trailing separators; refuses an empty result</td>
 *       <td>no whitespace strip, no leading-separator check, no doubled-separator check, no traversal
 *           check, no character check</td></tr>
 *   <tr><td>{@code CombineTransactionsJob.requireGenerationPrefix}</td>
 *       <td>strips whitespace and trailing separators</td>
 *       <td>no leading-separator, doubled-separator, traversal or character check</td></tr>
 *   <tr><td>{@code RejectWriter.requireGdgPrefix}</td><td>as above</td><td>as above</td></tr>
 *   <tr><td>{@code TransactionWriter.requireObjectPrefix}</td><td>as above</td><td>as above</td></tr>
 *   <tr><td>{@code StatementGenerationJob.normalisePrefix}</td>
 *       <td>strips whitespace and <em>both</em> leading and trailing separators</td>
 *       <td>no doubled-separator, traversal or character check</td></tr>
 *   <tr><td>{@code TransactionBackupReader.requireGenerationPrefix}</td>
 *       <td>strips whitespace and <em>appends</em> one trailing separator</td>
 *       <td>no leading-separator, doubled-separator, traversal or character check</td></tr>
 * </table>
 *
 * <p>{@code TransactionReportJob} carried a seventh, and it was the strict one: printable ASCII, no
 * surrounding whitespace, no leading separator, no trailing separator, no doubled separator, no
 * {@code ..} segment - and it <em>refused</em> rather than normalised. The review named that one as the
 * benchmark, so it is the grammar published here and the other six now delegate to it.
 *
 * <p><b>An eighth site the review did not name, found by measuring rather than by reading the report.</b>
 * {@code CombinedTransactionReader.requireGenerationPrefix} stripped whitespace, refused only {@code ..} and
 * {@code //}, then <em>appended</em> a separator when one was missing - so a leading separator passed, a
 * value already ending in a separator was accepted in a second spelling, and a control character inside an
 * otherwise printable value passed too. It consumes two of the same bases as the classes the review named,
 * {@code TRANSACT.BKUP} and {@code SYSTRAN}, so leaving it behind would have left the duplication this
 * finding is about intact on exactly the roots the other consumers had just been tightened on, and would have
 * meant two spellings of one base could still be in force at once. It delegates as well, which is what makes
 * this the <em>only</em> prefix grammar in the tier rather than merely the majority one.
 *
 * <h2>Why it refuses rather than normalises</h2>
 *
 * <p>Five of the six silently rewrote the configured value. Silent normalisation means the value an operator
 * wrote and the value in force can differ, with nothing reporting the difference - the same class of defect as
 * a key that is declared in a profile and bound by nobody. Refusing makes the divergence a startup failure
 * naming the property. No shipped configuration changes behaviour: all nine values declared in
 * {@code src/main/resources/application.yml} already satisfy this grammar, so the only value this refuses is
 * one that was previously being silently rewritten.
 *
 * <p><b>The two consumers that genuinely need a trailing separator still get one.</b>
 * {@code TransactionBackupReader} and {@code CombinedTransactionReader} hand their prefixes to a bucket
 * listing - the second on both of its concatenated sources - and object storage has no
 * directories, so a prefix match is a plain string match: without a separator {@code gdg/transact-bkup} also
 * matches {@code gdg/transact-bkup-shadow} and a generation of that unrelated base could be selected as the
 * greatest key. {@link #listingPrefixOf(String)} derives that form from the validated one, so the grammar
 * stays single and the listing defence is kept.
 *
 * <h2>The startup collision guard</h2>
 *
 * <p>A grammar alone cannot catch two roots that are individually well formed and jointly ambiguous. This
 * class is therefore also a bean, and its constructor refuses the context when any two of the nine configured
 * roots collide. Three relations are refused, and the distinction between them is worth stating because only
 * the first is unfixable:
 *
 * <ol>
 *   <li><b>Equal.</b> Two bases would write into one namespace and each would see the other's generations as
 *       its own. Nothing downstream can recover from that.</li>
 *   <li><b>Path ancestor</b> - {@code gdg/a} and {@code gdg/a/b}. A listing of the ancestor returns the
 *       descendant's objects, and {@code CombinedTransactionReader} resolves a generation by taking the
 *       first path segment after the base, so it would read a segment that belongs to another base.</li>
 *   <li><b>String prefix that is not a path ancestor</b> - {@code gdg/a} and {@code gdg/ab}. This is a near
 *       miss rather than a collision: it is safe for as long as every listing uses
 *       {@link #listingPrefixOf(String)}, and becomes a silent wrong-base read the moment one does not. It is
 *       refused too, because the cost of refusing is zero - no configured value trips it - and the cost of
 *       tolerating it is a defect that appears only under a future change.</li>
 * </ol>
 *
 * <p><b>Why a bean that nothing injects is not dead code.</b> It has an effect, and the effect is the point:
 * an unusable prefix catalogue fails the context refresh instead of failing a batch run hours later, and the
 * effective values are logged once so an operator can audit them. That is the opposite of the inert
 * configuration keys this project has withdrawn elsewhere, which had no consumer <em>and</em> no effect. The
 * nine prefixes are also validated here even though each consuming class validates its own, because a class
 * that no run exercises never validates anything - and a misconfigured statement work prefix should not wait
 * for a statement run to be discovered.
 *
 * <h2>How to build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Dtest=GenerationPrefixContractTest test} covers the grammar and the collision
 * guard with no container and no database. The integration tiers exercise the same code by refreshing a real
 * context.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>The seven generation roots, each with the same value in {@code application.yml} as in its consuming
 * class's inline default: {@code carddemo.aws.s3.gdg-prefixes.transact-bkup} {@code gdg/transact-bkup},
 * {@code .transact-daly} {@code gdg/transact-daly}, {@code .transact-combined}
 * {@code gdg/transact-combined}, {@code .systran} {@code gdg/systran}, {@code .daly-rejs}
 * {@code gdg/dalyrejs}, {@code .tranrept} {@code gdg/tranrept} and {@code .tcatbalf-bkup}
 * {@code gdg/tcatbalf-bkup}. Two further object-key roots are not generation groups and are held to the same
 * grammar: {@code carddemo.aws.s3.work-prefixes.trxfl} {@code work/trxfl}, the {@code CREASTMT} work cluster,
 * and {@code carddemo.aws.s3.transaction-object-prefix} {@code transact}, the posting job's object mirror.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every failure is an {@link IllegalArgumentException} at context refresh naming the property and the rule
 * it broke, which Spring surfaces as a bean-creation failure. A prefix written with a leading or trailing
 * separator, with a doubled separator, with surrounding or interior whitespace, with a {@code .} or
 * {@code ..} segment, with a backslash or with a non-printable character is refused rather than repaired -
 * correct the profile. Two roots that collide are reported as a pair with the relation named; give one of them
 * a distinct base rather than relying on a listing to disambiguate them.
 */
@Component
public final class GenerationPrefixContract {

    /** Diagnostics for the startup validation. */
    private static final Logger LOG = LoggerFactory.getLogger(GenerationPrefixContract.class);

    /** The single object-key segment separator; object storage has no others. */
    private static final String KEY_SEPARATOR = "/";

    /** Lowest permitted code point: the space is excluded deliberately, so this starts at {@code '!'}. */
    private static final char LOWEST_PERMITTED = 0x21;

    /** Highest permitted code point, the last printable ASCII character. */
    private static final char HIGHEST_PERMITTED = 0x7E;

    /**
     * The nine configured roots in declaration order, keyed by property name.
     *
     * <p>Held so the startup log can name each value with the property it came from, which is what makes the
     * effective configuration auditable rather than merely valid.
     */
    private final Map<String, String> roots;

    /**
     * Validates every configured object-key root and refuses any pair that could resolve to one another.
     *
     * <p>Each parameter carries the same inline default as its consuming class, so this bean can never be the
     * only thing that fails: if a default here disagreed with the one a job binds, the two would validate
     * different values and the disagreement would be invisible. They are written out rather than shared as
     * constants because the consuming classes are in four different packages and a shared constant would make
     * this class their dependency, which it deliberately is not - nothing injects it and nothing calls it
     * except through the two static methods below.
     *
     * @param transactBkup {@code carddemo.aws.s3.gdg-prefixes.transact-bkup}
     * @param transactDaly {@code carddemo.aws.s3.gdg-prefixes.transact-daly}
     * @param transactCombined {@code carddemo.aws.s3.gdg-prefixes.transact-combined}
     * @param systran {@code carddemo.aws.s3.gdg-prefixes.systran}
     * @param dalyRejs {@code carddemo.aws.s3.gdg-prefixes.daly-rejs}
     * @param tranrept {@code carddemo.aws.s3.gdg-prefixes.tranrept}
     * @param tcatbalfBkup {@code carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup}
     * @param trxflWork {@code carddemo.aws.s3.work-prefixes.trxfl}
     * @param transactionObject {@code carddemo.aws.s3.transaction-object-prefix}
     * @throws IllegalArgumentException if any root breaks the grammar or any two roots collide
     */
    public GenerationPrefixContract(
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:gdg/transact-bkup}")
                    final String transactBkup,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-daly:gdg/transact-daly}")
                    final String transactDaly,
            @Value("${carddemo.aws.s3.gdg-prefixes.transact-combined:gdg/transact-combined}")
                    final String transactCombined,
            @Value("${carddemo.aws.s3.gdg-prefixes.systran:gdg/systran}") final String systran,
            @Value("${carddemo.aws.s3.gdg-prefixes.daly-rejs:gdg/dalyrejs}") final String dalyRejs,
            @Value("${carddemo.aws.s3.gdg-prefixes.tranrept:gdg/tranrept}") final String tranrept,
            @Value("${carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup:gdg/tcatbalf-bkup}")
                    final String tcatbalfBkup,
            @Value("${carddemo.aws.s3.work-prefixes.trxfl:work/trxfl}") final String trxflWork,
            @Value("${carddemo.aws.s3.transaction-object-prefix:transact}")
                    final String transactionObject) {

        // Every callee below is a private static pure function of its arguments, so this constructor invokes
        // no overridable method and cannot publish a partly built instance to a subclass override.
        // -Xlint:this-escape is silent as a result, and the class is final besides.
        final Map<String, String> configured = new LinkedHashMap<>();
        configured.put("carddemo.aws.s3.gdg-prefixes.transact-bkup", transactBkup);
        configured.put("carddemo.aws.s3.gdg-prefixes.transact-daly", transactDaly);
        configured.put("carddemo.aws.s3.gdg-prefixes.transact-combined", transactCombined);
        configured.put("carddemo.aws.s3.gdg-prefixes.systran", systran);
        configured.put("carddemo.aws.s3.gdg-prefixes.daly-rejs", dalyRejs);
        configured.put("carddemo.aws.s3.gdg-prefixes.tranrept", tranrept);
        configured.put("carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup", tcatbalfBkup);
        configured.put("carddemo.aws.s3.work-prefixes.trxfl", trxflWork);
        configured.put("carddemo.aws.s3.transaction-object-prefix", transactionObject);

        this.roots = validateCatalogue(configured);
        LOG.info("Validated {} object-key roots, all pairwise non-colliding: {}",
                Integer.valueOf(this.roots.size()), this.roots);
    }

    /**
     * The validated catalogue, so a deployment's effective roots are readable rather than inferred.
     *
     * @return the property-to-prefix mapping in declaration order, never {@code null}
     */
    public Map<String, String> configuredRoots() {
        return this.roots;
    }

    /**
     * Validates one configured object-key prefix against the single grammar and returns it unchanged.
     *
     * <p>Refuses rather than normalises, for the reason given on the class. The rules, in the order they are
     * applied so a diagnostic always names the first thing wrong:
     *
     * <ol>
     *   <li>present and not blank;</li>
     *   <li>equal to its own {@link String#strip()}, because a YAML value can pick up surrounding whitespace
     *       and a prefix with a trailing space addresses a different key space;</li>
     *   <li>printable ASCII from {@code '!'} through {@code '~'} - the space is excluded as well as the
     *       control range, because no dataset name in the frozen corpus contains one and an interior space
     *       makes a key that many tools require percent-encoded;</li>
     *   <li>relative: no leading separator, because a key never begins with one;</li>
     *   <li>no trailing separator, because every consumer composes {@code prefix + "/" + segment} and a
     *       trailing one would produce a segment with an empty name;</li>
     *   <li>no doubled separator, for the same reason applied to the interior;</li>
     *   <li>no backslash, which is not a separator in object storage and would become a literal character in
     *       a segment name;</li>
     *   <li>no {@code .} or {@code ..} segment, because neither has a meaning in a flat key space and both
     *       are the shape a traversal attempt takes.</li>
     * </ol>
     *
     * @param value the configured value, permitted to be {@code null} so the diagnostic can name it
     * @param property the property the value came from, named in every failure
     * @return {@code value} unchanged, once it is known to satisfy every rule
     * @throws IllegalArgumentException if any rule is broken, naming the property and the rule
     */
    public static String requireRelativePrefix(final String value, final String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property
                    + " must name an object-key prefix and must not be blank; a blank prefix would address"
                    + " the bucket root and could select an object of an unrelated base");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(property
                    + " must not carry surrounding whitespace; a prefix with a leading or trailing space"
                    + " addresses a different key space than the one it appears to name");
        }
        requirePermittedCharacters(value, property);
        if (value.startsWith(KEY_SEPARATOR)) {
            throw new IllegalArgumentException(property
                    + " must be a relative prefix, so it must not begin with '" + KEY_SEPARATOR + "'");
        }
        if (value.endsWith(KEY_SEPARATOR)) {
            throw new IllegalArgumentException(property
                    + " must not end with '" + KEY_SEPARATOR + "'; every consumer appends one separator"
                    + " before the generation segment, so a trailing separator would name an empty segment");
        }
        if (value.contains(KEY_SEPARATOR + KEY_SEPARATOR)) {
            throw new IllegalArgumentException(property
                    + " must not contain a doubled '" + KEY_SEPARATOR + "'; it would name a segment with an"
                    + " empty name and break the lexicographic ordering a (0) generation read depends on");
        }
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(property
                    + " must not contain '\\'; object storage has one separator and a backslash would become"
                    + " a literal character inside a segment name");
        }
        requireNoRelativeSegment(value, property);
        return value;
    }

    /**
     * Derives the listing form of a validated prefix: exactly one trailing separator.
     *
     * <p>Object storage has no directories, so a prefix filter is a plain string match. Listing under
     * {@code gdg/transact-bkup} also returns the objects of {@code gdg/transact-bkup-shadow}, and a consumer
     * that resolves a generation by taking the greatest key would select one belonging to an unrelated base.
     * Appending the separator makes the filter a path filter.
     *
     * @param validatedPrefix a prefix that has already passed {@link #requireRelativePrefix(String, String)}
     * @return the prefix with exactly one trailing separator, never {@code null}
     * @throws IllegalArgumentException if the argument is {@code null} or already ends with a separator, which
     *     can only mean it was not validated first
     */
    public static String listingPrefixOf(final String validatedPrefix) {
        if (validatedPrefix == null || validatedPrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "a listing prefix cannot be derived from an absent or blank prefix");
        }
        if (validatedPrefix.endsWith(KEY_SEPARATOR)) {
            throw new IllegalArgumentException("a listing prefix is derived from the validated relative form,"
                    + " which never ends with '" + KEY_SEPARATOR + "'; '" + validatedPrefix
                    + "' was not validated by requireRelativePrefix");
        }
        return validatedPrefix + KEY_SEPARATOR;
    }

    /**
     * Refuses any character outside printable ASCII, naming the position and the code point only.
     *
     * @param value the configured prefix
     * @param property the property the value came from
     * @throws IllegalArgumentException if any character is outside the permitted range
     */
    private static void requirePermittedCharacters(final String value, final String property) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < LOWEST_PERMITTED || character > HIGHEST_PERMITTED) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "%s must contain only printable ASCII from '!' through '~', but position %d holds"
                                + " code point %d",
                        property, Integer.valueOf(index + 1), Integer.valueOf(character)));
            }
        }
    }

    /**
     * Refuses a {@code .} or {@code ..} segment anywhere in the prefix.
     *
     * @param value the configured prefix, already known to have no leading, trailing or doubled separator
     * @param property the property the value came from
     * @throws IllegalArgumentException if any segment is a relative reference
     */
    private static void requireNoRelativeSegment(final String value, final String property) {
        for (final String segment : value.split(KEY_SEPARATOR, -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(property
                        + " must not contain a '" + segment + "' segment; a flat key space gives it no"
                        + " meaning and it is the shape a traversal attempt takes");
            }
        }
    }

    /**
     * Validates every root and every pair, returning an unmodifiable view in declaration order.
     *
     * @param configured the property-to-value mapping to validate
     * @return the same mapping, unmodifiable
     * @throws IllegalArgumentException if any root breaks the grammar or any two roots collide
     */
    private static Map<String, String> validateCatalogue(final Map<String, String> configured) {
        final Map<String, String> validated = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : configured.entrySet()) {
            validated.put(entry.getKey(), requireRelativePrefix(entry.getValue(), entry.getKey()));
        }
        requirePairwiseDistinct(validated);
        return Map.copyOf(validated);
    }

    /**
     * Refuses any pair of roots that could resolve to one another's objects.
     *
     * @param validated the roots, each already satisfying the grammar
     * @throws IllegalArgumentException naming the two properties and the relation between them
     */
    private static void requirePairwiseDistinct(final Map<String, String> validated) {
        final List<Map.Entry<String, String>> entries = List.copyOf(validated.entrySet());
        for (int left = 0; left < entries.size(); left++) {
            for (int right = left + 1; right < entries.size(); right++) {
                final Map.Entry<String, String> first = entries.get(left);
                final Map.Entry<String, String> second = entries.get(right);
                final String relation = collisionBetween(first.getValue(), second.getValue());
                if (relation != null) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "%s ('%s') and %s ('%s') are not distinct object-key roots: %s. Two bases that"
                                    + " share a namespace each see the other's objects as their own, and a"
                                    + " (0) generation read selects the greatest key under the base without"
                                    + " being able to tell them apart",
                            first.getKey(), first.getValue(), second.getKey(), second.getValue(), relation));
                }
            }
        }
    }

    /**
     * Names the collision between two roots, or returns {@code null} when they are safely distinct.
     *
     * @param first one root
     * @param second the other root
     * @return the relation, or {@code null} when there is none
     */
    private static String collisionBetween(final String first, final String second) {
        if (first.equals(second)) {
            return "they are equal";
        }
        if (second.startsWith(first + KEY_SEPARATOR)) {
            return "the first is a path ancestor of the second";
        }
        if (first.startsWith(second + KEY_SEPARATOR)) {
            return "the second is a path ancestor of the first";
        }
        if (second.startsWith(first)) {
            return "the first is a string prefix of the second, which is safe only while every listing"
                    + " appends a separator and becomes a wrong-base read the moment one does not";
        }
        if (first.startsWith(second)) {
            return "the second is a string prefix of the first, which is safe only while every listing"
                    + " appends a separator and becomes a wrong-base read the moment one does not";
        }
        return null;
    }
}
