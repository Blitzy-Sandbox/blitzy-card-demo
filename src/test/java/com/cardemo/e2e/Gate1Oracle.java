/*
 ******************************************************************
 * Program     : Gate1Oracle.java
 * Application : CardDemo
 * Type        : Java 25 end-to-end test support type (declares no test method)
 * Function    : Loads the Gate 1 boundary-parity oracle from the classpath and exposes it as
 *               typed, field-separated records so both Gate 1 suites compare against ONE
 *               reading of it. The oracle is the output of the LEGACY program: the frozen
 *               app/cbl/CBTRN02C.cbl was compiled unmodified and executed against the frozen
 *               ASCII fixtures, and its reject, transaction, account, category-balance and
 *               SYSOUT images were captured. Nothing here derives from the Java migration.
 * Source      : src/test/resources/parity/gate1/** - the captured images, their provenance
 *               record and the derivation harness that regenerates them
 * Source      : app/cpy/CVTRA05Y.cpy @ 7756d89 - the 350-byte transaction offset map
 * Source      : app/cpy/CVACT01Y.cpy @ 7756d89 - the 300-byte account offset map
 * Source      : app/cpy/CVTRA01Y.cpy @ 7756d89 - the 50-byte category-balance offset map
 * Source      : app/cbl/CBTRN02C.cbl @ 7756d89 - :L176-:L182 the 430-byte reject geometry,
 *               :L692-:L705 the run-generated processing timestamp this oracle excludes
 * Note        : Deliberately NOT named *Test: the runner's include patterns must not collect
 *               it, and it declares no test method, which TestTierContractTest asserts.
 ******************************************************************
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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 ******************************************************************
 */

package com.cardemo.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * The Gate 1 boundary-parity oracle, loaded once and read field by field.
 *
 * <p><strong>Purpose.</strong> Gate 1 requires that the Java posting pipeline be compared against what the
 * legacy program produces, rather than against itself or against a hand-derived figure. This type is the
 * single reading of that comparison target: it loads the captured legacy images from
 * {@code src/test/resources/parity/gate1/}, splits each fixed-width record into the field spans its
 * copybook declares, and hands back typed records. Both {@code GateVerificationTest} and
 * {@code BatchPipelineE2ETest} read it, so the two cannot disagree about what the oracle says.
 *
 * <p><strong>Why the oracle is trustworthy, stated rather than assumed.</strong> The images were produced by
 * compiling {@code app/cbl/CBTRN02C.cbl} <em>unmodified</em> and running it against the frozen ASCII
 * fixtures; {@code PROVENANCE.properties} records the compiler, its flags, the input digests and the output
 * digests, and {@code harness/derive-gate1-oracle.sh} re-derives every byte of that legacy output by
 * recompiling and re-executing the program. None of it was generated from Java and none of it was
 * hand-simulated. The harness stops at the raw datasets on purpose: rendering them into these images and
 * re-taking the digests is a manual step, so a regeneration is diffed against the committed oracle before it
 * can replace it. {@link #declared(String)} exposes those provenance entries so a
 * suite can assert the claim instead of trusting the prose.
 *
 * <p><strong>What it deliberately does not carry.</strong> Two spans of the 350-byte transaction record are
 * excluded from the captured image and therefore from every comparison built on it: the processing timestamp
 * at offsets 305-330, which {@code app/cbl/CBTRN02C.cbl:L692-L705} generates per run from
 * {@code FUNCTION CURRENT-DATE}, and the trailing {@code FILLER PIC X(20)} at 331-350, which the program
 * never assigns. Both exclusions and their reasons are recorded in the provenance file and are asserted
 * there rather than being silent.
 *
 * <p><strong>Error modes.</strong> {@link #load()} throws {@link IllegalStateException} if any artefact is
 * absent from the classpath, if a record width disagrees with the width the provenance declares, or if a
 * record count disagrees with the declared count. Each message names the artefact and both numbers, because
 * a truncated or re-rendered oracle must fail loudly at load rather than produce a field mismatch fifty
 * assertions later.
 */
final class Gate1Oracle {

    /** Classpath directory holding every artefact, mirroring {@code src/test/resources/parity/gate1/}. */
    static final String RESOURCE_ROOT = "/parity/gate1/";

    /** The provenance record, loaded so its claims can be asserted rather than trusted. */
    private final Properties provenance;

    /** Reject records in emission order, exactly as {@code 2500-WRITE-REJECT-REC} wrote them. */
    private final List<RejectImage> rejects;

    /** Posted transactions in key order, as the unloaded TRANFILE presents them. */
    private final List<TransactionImage> postedTransactions;

    /** Post-run account images keyed by account identifier. */
    private final Map<Long, AccountImage> accounts;

    /** Post-run category-balance images keyed by the {@code account|type|category} rendering. */
    private final Map<String, CategoryBalanceImage> categoryBalances;

    /** The captured SYSOUT lines, including the trailing {@code RETURN-CODE=} line. */
    private final List<String> sysout;

    /**
     * Constructs the loaded oracle.
     *
     * @param provenance the provenance properties; must not be {@code null}
     * @param rejects the reject images in emission order; must not be {@code null}
     * @param postedTransactions the posted-transaction images in key order; must not be {@code null}
     * @param accounts the post-run account images by identifier; must not be {@code null}
     * @param categoryBalances the post-run category-balance images by rendered key; must not be {@code null}
     * @param sysout the captured SYSOUT lines; must not be {@code null}
     */
    private Gate1Oracle(final Properties provenance, final List<RejectImage> rejects,
            final List<TransactionImage> postedTransactions, final Map<Long, AccountImage> accounts,
            final Map<String, CategoryBalanceImage> categoryBalances, final List<String> sysout) {
        this.provenance = Objects.requireNonNull(provenance, "provenance must not be null");
        this.rejects = List.copyOf(Objects.requireNonNull(rejects, "rejects must not be null"));
        this.postedTransactions = List.copyOf(
                Objects.requireNonNull(postedTransactions, "postedTransactions must not be null"));
        this.accounts = Map.copyOf(Objects.requireNonNull(accounts, "accounts must not be null"));
        this.categoryBalances = Map.copyOf(
                Objects.requireNonNull(categoryBalances, "categoryBalances must not be null"));
        this.sysout = List.copyOf(Objects.requireNonNull(sysout, "sysout must not be null"));
    }

    // ====================================================================================================
    // The field-separated images. Every span is taken from the copybook the record's layout comes from, so
    // a span is a citation rather than a guess.
    // ====================================================================================================

    /**
     * One 430-byte reject record, split at the boundaries {@code app/cbl/CBTRN02C.cbl:L176-L182} declares.
     *
     * @param transactionImage the 350-byte input record copied verbatim by {@code :L447}
     * @param reasonCode the four-digit reason, {@code WS-VALIDATION-FAIL-REASON}
     * @param reasonDescription the 76-character description, trailing spaces retained
     */
    record RejectImage(String transactionImage, int reasonCode, String reasonDescription) {
    }

    /**
     * One posted transaction, split at the {@code app/cpy/CVTRA05Y.cpy} offsets.
     *
     * <p>The processing timestamp is absent by design; see the type documentation.
     *
     * @param transactionId {@code TRAN-ID}, offsets 1-16
     * @param typeCode {@code TRAN-TYPE-CD}, 17-18
     * @param categoryCode {@code TRAN-CAT-CD}, 19-22
     * @param source {@code TRAN-SOURCE}, 23-32
     * @param description {@code TRAN-DESC}, 33-132
     * @param amount {@code TRAN-AMT}, 133-143, zoned decimal with a trailing overpunched sign
     * @param merchantId {@code TRAN-MERCHANT-ID}, 144-152
     * @param merchantName {@code TRAN-MERCHANT-NAME}, 153-202
     * @param merchantCity {@code TRAN-MERCHANT-CITY}, 203-252
     * @param merchantZip {@code TRAN-MERCHANT-ZIP}, 253-262
     * @param cardNumber {@code TRAN-CARD-NUM}, 263-278
     * @param originatingTimestamp {@code TRAN-ORIG-TS}, 279-304
     */
    record TransactionImage(String transactionId, String typeCode, String categoryCode, String source,
            String description, String amount, String merchantId, String merchantName, String merchantCity,
            String merchantZip, String cardNumber, String originatingTimestamp) {
    }

    /**
     * One post-run account image, split at the {@code app/cpy/CVACT01Y.cpy} offsets.
     *
     * @param accountId {@code ACCT-ID}, offsets 1-11
     * @param activeStatus {@code ACCT-ACTIVE-STATUS}, 12
     * @param currentBalance {@code ACCT-CURR-BAL}, 13-24, mutated by {@code 2800-UPDATE-ACCOUNT-REC}
     * @param creditLimit {@code ACCT-CREDIT-LIMIT}, 25-36
     * @param cashCreditLimit {@code ACCT-CASH-CREDIT-LIMIT}, 37-48
     * @param openDate {@code ACCT-OPEN-DATE}, 49-58
     * @param expiryDate {@code ACCT-EXPIRAION-DATE} - the misspelling is the field contract - 59-68
     * @param reissueDate {@code ACCT-REISSUE-DATE}, 69-78
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}, 79-90, mutated on a non-negative amount
     * @param cycleDebit {@code ACCT-CURR-CYC-DEBIT}, 91-102, mutated on a negative amount
     * @param addressZip {@code ACCT-ADDR-ZIP}, 103-112
     * @param groupId {@code ACCT-GROUP-ID}, 113-122
     */
    record AccountImage(long accountId, String activeStatus, String currentBalance, String creditLimit,
            String cashCreditLimit, String openDate, String expiryDate, String reissueDate,
            String cycleCredit, String cycleDebit, String addressZip, String groupId) {
    }

    /**
     * One post-run category-balance image, split at the {@code app/cpy/CVTRA01Y.cpy} offsets.
     *
     * @param accountId {@code TRANCAT-ACCT-ID}, offsets 1-11
     * @param typeCode {@code TRANCAT-TYPE-CD}, 12-13
     * @param categoryCode {@code TRANCAT-CD}, 14-17
     * @param balance {@code TRAN-CAT-BAL}, 18-28, an eleven-character {@code S9(09)V99}
     */
    record CategoryBalanceImage(long accountId, String typeCode, int categoryCode, String balance) {
    }

    // ====================================================================================================
    // Loading. Every width and count is checked against the provenance record at load time.
    // ====================================================================================================

    /**
     * Loads and validates the whole oracle.
     *
     * @return the loaded oracle, never {@code null}
     * @throws IllegalStateException if an artefact is missing, or if any width, count or digest disagrees
     *     with what the provenance record declares
     */
    static Gate1Oracle load() {
        final Properties provenance = loadProvenance();
        final List<String> rejectLines = loadLines("DALYREJS.expected",
                declaredInt(provenance, "artefact.rejects.records"),
                declaredInt(provenance, "artefact.rejects.lineWidth"), "rejects");
        final List<String> transactionLines = loadLines("TRANSACT.expected",
                declaredInt(provenance, "artefact.transactions.records"),
                declaredInt(provenance, "artefact.transactions.lineWidth"), "transactions");
        final List<String> accountLines = loadLines("ACCTDATA.expected",
                declaredInt(provenance, "artefact.accounts.records"),
                declaredInt(provenance, "artefact.accounts.lineWidth"), "accounts");
        final List<String> balanceLines = loadLines("TCATBALF.expected",
                declaredInt(provenance, "artefact.categoryBalances.records"),
                declaredInt(provenance, "artefact.categoryBalances.lineWidth"), "categoryBalances");

        final List<RejectImage> rejects = new ArrayList<>(rejectLines.size());
        for (final String line : rejectLines) {
            rejects.add(new RejectImage(line.substring(0, 350),
                    Integer.parseInt(line.substring(350, 354)), line.substring(354, 430)));
        }
        final List<TransactionImage> transactions = new ArrayList<>(transactionLines.size());
        for (final String line : transactionLines) {
            transactions.add(new TransactionImage(line.substring(0, 16), line.substring(16, 18),
                    line.substring(18, 22), line.substring(22, 32), line.substring(32, 132),
                    line.substring(132, 143), line.substring(143, 152), line.substring(152, 202),
                    line.substring(202, 252), line.substring(252, 262), line.substring(262, 278),
                    line.substring(278, 304)));
        }
        final Map<Long, AccountImage> accounts = new LinkedHashMap<>();
        for (final String line : accountLines) {
            final long id = Long.parseLong(line.substring(0, 11));
            accounts.put(Long.valueOf(id), new AccountImage(id, line.substring(11, 12),
                    line.substring(12, 24), line.substring(24, 36), line.substring(36, 48),
                    line.substring(48, 58), line.substring(58, 68), line.substring(68, 78),
                    line.substring(78, 90), line.substring(90, 102), line.substring(102, 112),
                    line.substring(112, 122)));
        }
        final Map<String, CategoryBalanceImage> balances = new LinkedHashMap<>();
        for (final String line : balanceLines) {
            final long id = Long.parseLong(line.substring(0, 11));
            final String type = line.substring(11, 13);
            final int category = Integer.parseInt(line.substring(13, 17));
            balances.put(renderKey(id, type, category),
                    new CategoryBalanceImage(id, type, category, line.substring(17, 28)));
        }
        final List<String> sysout = readResource("CBTRN02C.sysout.expected").lines().toList();
        return new Gate1Oracle(provenance, rejects, transactions, accounts, balances, sysout);
    }

    /**
     * Renders a category-balance key the way both suites compare it.
     *
     * @param accountId the account identifier
     * @param typeCode the two-character type code, whose surrounding spaces are stripped
     * @param categoryCode the four-digit category code
     * @return the {@code account|type|category} rendering, never {@code null}
     */
    static String renderKey(final long accountId, final String typeCode, final int categoryCode) {
        return accountId + "|" + typeCode.strip() + "|" + categoryCode;
    }

    /**
     * Reads the provenance record.
     *
     * @return the loaded properties, never {@code null}
     * @throws IllegalStateException if the file is absent from the classpath
     */
    private static Properties loadProvenance() {
        final Properties loaded = new Properties();
        try (InputStream stream = Gate1Oracle.class.getResourceAsStream(
                RESOURCE_ROOT + "PROVENANCE.properties")) {
            if (stream == null) {
                throw new IllegalStateException("The Gate 1 provenance record is absent from the classpath "
                        + "at " + RESOURCE_ROOT + "PROVENANCE.properties. The oracle cannot be trusted "
                        + "without it, because the record is what states that the images came from the "
                        + "legacy program rather than from this implementation.");
            }
            loaded.load(stream);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Reading the Gate 1 provenance record failed.", failure);
        }
        return loaded;
    }

    /**
     * Reads one artefact and checks its geometry against the declared values.
     *
     * @param name the artefact file name
     * @param expectedRecords the record count the provenance declares
     * @param expectedWidth the line width the provenance declares
     * @param label the provenance key prefix, used in failure messages
     * @return the lines, never {@code null}
     * @throws IllegalStateException if the count or any width disagrees with the declaration
     */
    private static List<String> loadLines(final String name, final int expectedRecords,
            final int expectedWidth, final String label) {
        final List<String> lines = readResource(name).lines().toList();
        if (lines.size() != expectedRecords) {
            throw new IllegalStateException(name + " holds " + lines.size() + " records but "
                    + "PROVENANCE.properties declares artefact." + label + ".records=" + expectedRecords
                    + ". One of the two was edited without the other. Re-derive the legacy output with "
                    + "harness/derive-gate1-oracle.sh, diff it against the committed image, and only then "
                    + "render and re-take the digest - never adjust either of the two by hand to agree.");
        }
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).length() != expectedWidth) {
                throw new IllegalStateException(name + " record " + (index + 1) + " is "
                        + lines.get(index).length() + " characters but PROVENANCE.properties declares "
                        + "artefact." + label + ".lineWidth=" + expectedWidth + ". A fixed-width oracle "
                        + "whose width has moved cannot be compared field by field.");
            }
        }
        return lines;
    }

    /**
     * Reads one classpath artefact as ISO-8859-1 so a byte maps to exactly one character.
     *
     * @param name the artefact file name
     * @return the whole content, never {@code null}
     * @throws IllegalStateException if the artefact is absent from the classpath
     */
    private static String readResource(final String name) {
        try (InputStream stream = Gate1Oracle.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("The Gate 1 oracle artefact " + name + " is absent from the "
                        + "classpath at " + RESOURCE_ROOT + name + ". Gate 1 compares against captured "
                        + "legacy output, so a missing artefact is a missing comparison, not a pass.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Reading the Gate 1 oracle artefact " + name + " failed.",
                    failure);
        }
    }

    /**
     * Reads one declared integer from the provenance record.
     *
     * @param provenance the loaded record
     * @param key the property name
     * @return the parsed value
     * @throws IllegalStateException if the key is absent or is not an integer
     */
    private static int declaredInt(final Properties provenance, final String key) {
        final String raw = provenance.getProperty(key);
        if (raw == null) {
            throw new IllegalStateException("PROVENANCE.properties declares no " + key
                    + ", so the artefact it describes cannot be geometry-checked at load time.");
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (final NumberFormatException malformed) {
            throw new IllegalStateException("PROVENANCE.properties entry " + key + " is '" + raw.strip()
                    + "', which is not an integer.", malformed);
        }
    }

    // ====================================================================================================
    // Accessors. Every collection is already immutable; the maps and lists are copies taken at construction.
    // ====================================================================================================

    /**
     * The reject records the legacy program wrote, in emission order.
     *
     * @return the immutable list, never {@code null}
     */
    List<RejectImage> rejects() {
        return this.rejects;
    }

    /**
     * The transactions the legacy program posted, in key order.
     *
     * @return the immutable list, never {@code null}
     */
    List<TransactionImage> postedTransactions() {
        return this.postedTransactions;
    }

    /**
     * The post-run account images, keyed by account identifier.
     *
     * @return the immutable map, never {@code null}
     */
    Map<Long, AccountImage> accounts() {
        return this.accounts;
    }

    /**
     * The post-run category-balance images, keyed by the {@code account|type|category} rendering.
     *
     * @return the immutable map, never {@code null}
     */
    Map<String, CategoryBalanceImage> categoryBalances() {
        return this.categoryBalances;
    }

    /**
     * The captured SYSOUT lines, including the trailing {@code RETURN-CODE=} line.
     *
     * @return the immutable list, never {@code null}
     */
    List<String> sysout() {
        return this.sysout;
    }

    /**
     * One provenance entry, so a suite can assert the oracle's own claims about itself.
     *
     * @param key the property name
     * @return the trimmed value, never {@code null}
     * @throws IllegalStateException if the key is absent
     */
    String declared(final String key) {
        final String raw = this.provenance.getProperty(key);
        if (raw == null) {
            throw new IllegalStateException("PROVENANCE.properties declares no " + key + ".");
        }
        return raw.strip();
    }

    /**
     * One provenance entry parsed as an integer.
     *
     * @param key the property name
     * @return the parsed value
     * @throws IllegalStateException if the key is absent or is not an integer
     */
    int declaredNumber(final String key) {
        return declaredInt(this.provenance, key);
    }

    /**
     * The SHA-256 digest of one artefact, computed from the bytes on the classpath.
     *
     * <p>Recomputing rather than trusting is the point: it is what makes a silently edited oracle fail. An
     * oracle a later change could quietly adjust to match the implementation would be no oracle at all.
     *
     * @param name the artefact file name
     * @return the lowercase hexadecimal digest, never {@code null}
     */
    static String digestOf(final String name) {
        try {
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    sha256.digest(readResource(name).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable in this runtime.", unavailable);
        }
    }

    /**
     * The SHA-256 digest of one repository file, used to prove the oracle's inputs have not moved.
     *
     * @param bytes the file content
     * @return the lowercase hexadecimal digest, never {@code null}
     */
    static String digestOfBytes(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(bytes, "bytes must not be null")));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable in this runtime.", unavailable);
        }
    }
}
