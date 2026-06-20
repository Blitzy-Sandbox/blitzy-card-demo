package com.carddemo.batch.readers;

import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the card master, replacing COBOL batch reader {@code CBACT02C}
 * (source commit {@code 27d6c6f}; REFERENCE ONLY, COBOL is not copied). Streams {@link Card}
 * rows in ascending {@code CARD-NUM} order through a sorted, paged {@link RepositoryItemReader}
 * scan over {@link CardRepository}, reproducing the sequential primary-key read of the VSAM
 * {@code CARDDAT} KSDS ({@code ORGANIZATION INDEXED}, {@code ACCESS MODE SEQUENTIAL},
 * {@code RECORD KEY FD-CARD-NUM}).
 *
 * <p>The COBOL {@code OPEN} / {@code READ NEXT} / {@code CLOSE} cycle maps to the
 * framework-managed reader lifecycle: a successful read (FILE STATUS {@code '00'}) yields the
 * next {@link Card}, exhaustion (FILE STATUS {@code '10'}) yields {@code null} to stop the step,
 * and a hard data-access failure surfaces as a Spring {@code DataAccessException} that fails the
 * step (the abend equivalent). The primary key {@code cardNum} is a fixed-width 16-character
 * field, so the ascending sort is the lexicographic order matching the VSAM key sequence.
 *
 * <p>The {@code findAll}-based scan and its sort, page size, and stream name are applied in
 * {@link #afterPropertiesSet()}; the constructor only stores the injected repository. This bean
 * is wired into a {@code Step} by {@code com.carddemo.batch.jobs} and declares no job or step.
 */
@Component
@StepScope
public class CardReader extends RepositoryItemReader<Card> {

    /** Card master repository (VSAM {@code CARDDAT} KSDS), the paged data source for the scan. */
    private final CardRepository cardRepository;

    /**
     * Creates the step-scoped reader, retaining the injected card master repository. The
     * {@link RepositoryItemReader} configuration is performed in {@link #afterPropertiesSet()}.
     *
     * @param cardRepository the card master repository supplying paged {@code findAll} access
     */
    public CardReader(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Configures the paged, ascending-{@code cardNum} repository scan ({@code findAll} with a
     * 100-row page size and stream name {@code cardReader}), then delegates to the superclass for
     * its own initialization and required-property validation. Invoked by the Spring
     * {@code InitializingBean} lifecycle for the step-scoped instance before the first read.
     *
     * @throws Exception if superclass initialization fails (for example, a required property is unset)
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(cardRepository);
        setMethodName("findAll");
        setSort(Map.of("cardNum", Sort.Direction.ASC));
        setPageSize(100);
        setName("cardReader");
        super.afterPropertiesSet();
    }
}
