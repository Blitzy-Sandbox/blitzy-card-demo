package com.carddemo.batch.readers;

import com.carddemo.model.entity.Card;
import com.carddemo.repository.CardRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the card master, replacing COBOL batch reader CBACT02C
 * (source commit 27d6c6f). Streams {@link Card} rows in ascending CARD-NUM order via a
 * sorted, paged repository scan, reproducing the VSAM KSDS sequential key read.
 *
 * <p>Wired into a Spring Batch step by {@code com.carddemo.batch.jobs}; it defines no job
 * or step itself. The framework-managed {@link RepositoryItemReader} lifecycle replaces the
 * COBOL OPEN / READ NEXT / CLOSE skeleton: exhaustion yields the native {@code null} return
 * (the {@code '10'} end-of-file status), while a hard data-access failure surfaces as a
 * Spring Data {@code DataAccessException} that fails the step (the abend equivalent).</p>
 */
@Component
@StepScope
public class CardReader extends RepositoryItemReader<Card> {

    /** Repository whose paged {@code findAll} supplies the card records in key order. */
    private final CardRepository cardRepository;

    /**
     * Captures the repository for use during initialization. The inherited reader is
     * configured in {@link #afterPropertiesSet()} rather than here so that no overridable
     * method is invoked during construction.
     *
     * @param cardRepository the repository whose paged {@code findAll} supplies the records
     */
    public CardReader(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Configures the inherited {@link RepositoryItemReader} once the bean is fully
     * constructed: a paged, ascending {@code cardNum} scan over {@code findAll} that mirrors
     * the VSAM KSDS primary-key read sequence of CBACT02C. Runs on each step-scoped
     * instantiation before the framework validates the reader.
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
