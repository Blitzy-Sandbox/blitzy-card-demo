package com.cardemo.repository;

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link DisclosureGroup} entity, replacing all
 * COBOL VSAM keyed access patterns for the {@code DISCGRP.VSAM.KSDS} dataset.
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS)
 *     KEYS(16 0)
 *     RECORDSIZE(50 50)
 *     SHAREOPTIONS(2 3)
 *     INDEXED)
 * </pre>
 * <ul>
 *   <li><strong>Primary Key:</strong> 16-byte composite key at offset 0
 *       ({@code DIS-ACCT-GROUP-ID[10]} + {@code DIS-TRAN-TYPE-CD[2]}
 *       + {@code DIS-TRAN-CAT-CD[4]})</li>
 *   <li><strong>Record Size:</strong> 50 bytes fixed</li>
 *   <li><strong>Alternate Indexes:</strong> None</li>
 * </ul>
 *
 * <h3>COBOL Access Patterns Mapped</h3>
 * <p>The COBOL batch program {@code CBACT04C.cbl} (Interest Calculation) accesses
 * the DISCGRP dataset with the following critical pattern:</p>
 * <ol>
 *   <li>First attempts a keyed READ with the account's specific group ID +
 *       transaction type code + category code.</li>
 *   <li>If FILE STATUS = '23' (record not found), retries with
 *       {@code DIS-ACCT-GROUP-ID = 'DEFAULT   '} (10 chars, space-padded).</li>
 *   <li>On success, reads {@code DIS-INT-RATE} for the interest formula:
 *       {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200}.</li>
 * </ol>
 * <p>This DEFAULT group fallback is the single most important business logic pattern
 * for this repository. The fallback orchestration itself resides in the service layer
 * ({@code InterestCalculationProcessor}); this repository provides the lookup method
 * that enables it.</p>
 *
 * <h3>Inherited CRUD Operations</h3>
 * <p>By extending {@link JpaRepository}, this interface inherits standard CRUD
 * operations that replace the COBOL VSAM I/O verbs:</p>
 * <ul>
 *   <li>{@code findById(DisclosureGroupId)} — replaces {@code READ DISCGRP-FILE}
 *       with keyed access</li>
 *   <li>{@code findAll()} — replaces sequential browse of the KSDS cluster</li>
 *   <li>{@code save(DisclosureGroup)} — replaces {@code WRITE/REWRITE} operations</li>
 *   <li>{@code saveAll(Iterable)} — replaces IDCAMS REPRO bulk load</li>
 *   <li>{@code deleteById(DisclosureGroupId)} — replaces {@code DELETE} operation</li>
 *   <li>{@code deleteAll()} — replaces IDCAMS DELETE CLUSTER purge</li>
 *   <li>{@code count()} — provides record count for verification</li>
 *   <li>{@code existsById(DisclosureGroupId)} — replaces FILE STATUS '00'/'23'
 *       existence check</li>
 * </ul>
 *
 * <h3>Exception Translation</h3>
 * <p>The {@link Repository @Repository} annotation enables Spring's persistence
 * exception translation, mapping JPA-specific exceptions to Spring's
 * {@code DataAccessException} hierarchy. This replaces the COBOL FILE STATUS
 * code checking pattern (e.g., status '23' → empty Optional, status '35' →
 * DataAccessResourceFailureException).</p>
 *
 * <p>COBOL source references: {@code app/jcl/DISCGRP.jcl},
 * {@code app/cpy/CVTRA02Y.cpy}, {@code app/cbl/CBACT04C.cbl}
 * from commit {@code 27d6c6f}.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {

    /**
     * Finds a disclosure group record by its individual composite key components.
     *
     * <p>This method provides an explicit lookup by the three components of the
     * {@link DisclosureGroupId} composite key, navigating the {@code @EmbeddedId}
     * path in JPQL ({@code d.id.groupId}, {@code d.id.typeCode}, {@code d.id.catCode}).
     * It returns {@link Optional#empty()} when no matching record exists, which maps
     * to the COBOL FILE STATUS '23' (record not found) condition.</p>
     *
     * <h4>DEFAULT Fallback Pattern</h4>
     * <p>This method is the foundation for the critical DEFAULT group fallback pattern
     * used during batch interest calculation ({@code CBACT04C.cbl}, paragraphs
     * {@code 1200-GET-INTEREST-RATE} and {@code 1200-A-GET-DEFAULT-INT-RATE}):</p>
     * <ol>
     *   <li>The {@code InterestCalculationProcessor} first calls this method with the
     *       account's specific group ID (e.g., {@code "0000000001"}).</li>
     *   <li>If the returned {@link Optional} is empty (no specific rate found), the
     *       processor retries with {@code groupId = "DEFAULT"} (the universal fallback
     *       group) while preserving the same type code and category code.</li>
     *   <li>The resolved {@link DisclosureGroup#getDisIntRate()} value is then used in
     *       the interest formula: {@code (balance × rate) / 1200} with
     *       {@code BigDecimal} precision and {@code RoundingMode.HALF_EVEN}.</li>
     * </ol>
     *
     * <p><strong>Note:</strong> {@code findById(new DisclosureGroupId(groupId, typeCode, catCode))}
     * would also work since this is an exact composite key lookup. This explicit method
     * is provided for readability and to make the DEFAULT fallback pattern more visible
     * and self-documenting in the codebase.</p>
     *
     * @param groupId  the disclosure account group identifier (up to 10 characters);
     *                 typically an account group code or {@code "DEFAULT"} for the
     *                 fallback lookup. Maps COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)}.
     * @param typeCode the transaction type code (up to 2 characters), e.g., {@code "01"}
     *                 for purchases, {@code "02"} for cash advances.
     *                 Maps COBOL {@code DIS-TRAN-TYPE-CD PIC X(02)}.
     * @param catCode  the transaction category code as {@link Short},
     *                 matching the entity's {@code @EmbeddedId} field type.
     *                 Maps COBOL {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * @return an {@link Optional} containing the matching {@link DisclosureGroup} if
     *         found, or {@link Optional#empty()} if no record exists for the given
     *         key combination (equivalent to COBOL FILE STATUS '23')
     */
    @Query("SELECT d FROM DisclosureGroup d WHERE d.id.groupId = :groupId "
            + "AND d.id.typeCode = :typeCode AND d.id.catCode = :catCode")
    Optional<DisclosureGroup> findByGroupIdAndTypeCodeAndCatCode(
            @Param("groupId") String groupId,
            @Param("typeCode") String typeCode,
            @Param("catCode") Short catCode);
}
