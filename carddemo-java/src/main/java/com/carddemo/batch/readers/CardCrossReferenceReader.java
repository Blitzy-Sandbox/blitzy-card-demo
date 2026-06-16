package com.carddemo.batch.readers;

import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.repository.CardCrossReferenceRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the card cross-reference file, replacing COBOL batch reader
 * {@code CBACT03C} (source commit {@code 27d6c6f}). Streams {@link CardCrossReference} rows in
 * ascending {@code XREF-CARD-NUM} order via a sorted, paged repository scan, mirroring the
 * sequential VSAM KSDS primary-key read of {@code XREFFILE-FILE} (record {@code CARD-XREF-RECORD},
 * copybook {@code CVACT03Y}).
 *
 * <p>The COBOL {@code OPEN} / {@code READ NEXT} / {@code CLOSE} cycle maps to the
 * {@link RepositoryItemReader} lifecycle: each row is emitted exactly once and exhaustion yields
 * {@code null} so Spring Batch stops the step. The source program's duplicate per-record
 * {@code DISPLAY} (a debug artifact, not a double read) is intentionally not reproduced; this
 * reader produces no console or log output.
 *
 * <p>Wired into a Spring Batch step by {@code com.carddemo.batch.jobs}; it defines no job or step
 * itself. The inherited reader is configured in {@link #afterPropertiesSet()} rather than the
 * constructor so that no overridable method is invoked during construction.
 */
@Component
@StepScope
public class CardCrossReferenceReader extends RepositoryItemReader<CardCrossReference> {

    /** The cross-reference repository scanned page-by-page in ascending key order. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Captures the cross-reference repository for use in {@link #afterPropertiesSet()}; the
     * inherited reader is configured there (not here) so the constructor invokes no overridable
     * method.
     *
     * @param cardCrossReferenceRepository the repository scanned page-by-page in key order
     */
    public CardCrossReferenceReader(CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Configures the inherited paged repository reader to scan {@link CardCrossReference} rows via
     * {@code CardCrossReferenceRepository.findAll(Pageable)} in ascending {@code xrefCardNum} (the
     * {@code @Id} property mapped to column {@code xref_card_num}), preserving the COBOL sequential
     * primary-key read order with deterministic paging, then delegates to the superclass to
     * validate the configuration.
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(cardCrossReferenceRepository);
        setMethodName("findAll");
        setSort(Map.of("xrefCardNum", Sort.Direction.ASC));
        setPageSize(100);
        setName("cardCrossReferenceReader");
        super.afterPropertiesSet();
    }
}
