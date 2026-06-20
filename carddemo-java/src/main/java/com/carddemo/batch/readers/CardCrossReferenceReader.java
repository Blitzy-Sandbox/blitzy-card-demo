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
 * {@code CBACT03C} (source commit {@code 27d6c6f}; REFERENCE ONLY, the COBOL is not copied).
 * Streams {@link CardCrossReference} rows in ascending {@code XREF-CARD-NUM} order through a
 * sorted, paged {@link RepositoryItemReader} scan of {@link CardCrossReferenceRepository},
 * mirroring the sequential VSAM KSDS primary-key read order of the source program's
 * {@code 0000-XREFFILE-OPEN} / {@code 1000-XREFFILE-GET-NEXT} / {@code 9000-XREFFILE-CLOSE}
 * lifecycle. Each cross-reference record is emitted exactly once; the source program's
 * duplicate per-record {@code DISPLAY} (a debug artifact) is intentionally not reproduced, and
 * this reader produces no console or log output.
 */
@Component
@StepScope
public class CardCrossReferenceReader extends RepositoryItemReader<CardCrossReference> {

    /** Paged data source for the scan; the scan settings are applied in {@link #afterPropertiesSet()}. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Binds the card cross-reference repository that backs the sequential scan. The reader's
     * repository, method, sort, page size, and name are applied in {@link #afterPropertiesSet()}
     * during the {@code InitializingBean} lifecycle rather than in this constructor.
     *
     * @param cardCrossReferenceRepository the card cross-reference repository used as the paged
     *                                     data source for the scan
     */
    public CardCrossReferenceReader(CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Configures the sorted, paged repository scan and then delegates to the superclass for its own
     * initialization and validation. Items are read ascending by the {@code xrefCardNum} key (the
     * VSAM KSDS primary-key order) via the inherited {@code findAll(Pageable)} operation with a page
     * size of 100; exhaustion yields {@code null} (the COBOL {@code FILE STATUS '10'} end-of-file)
     * and a data-access failure propagates as a Spring Data {@code DataAccessException} that fails
     * the step. Invoked by the Spring {@code InitializingBean} lifecycle for the step-scoped
     * instance before the reader is opened.
     *
     * @throws Exception if superclass initialization fails (for example, a missing required property)
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
