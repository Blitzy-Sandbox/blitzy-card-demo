/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.writer;

import com.carddemo.batch.processor.PostingResult;
import com.carddemo.batch.writer.PostedTransactionWriter;
import com.carddemo.batch.writer.PostingResultWriter;
import com.carddemo.batch.writer.RejectTransactionWriter;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.RejectReasonCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Routing and lifecycle unit tests for {@link PostingResultWriter}, the composite
 * writer that fans each {@link PostingResult} from the posting step to the correct
 * durable sink. This guarantees the CP4 fix end-to-end: posted records flow to the
 * transaction master and rejected records flow to the 430-byte S3 reject object,
 * so no reject is dropped.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingResultWriter — fan-out routing & stream lifecycle")
class PostingResultWriterTest {

    @Mock
    private PostedTransactionWriter postedTransactionWriter;
    @Mock
    private RejectTransactionWriter rejectTransactionWriter;

    private PostingResultWriter writer;

    @BeforeEach
    void setUp() {
        writer = new PostingResultWriter(postedTransactionWriter, rejectTransactionWriter);
    }

    @Test
    @DisplayName("routes Posted to the transaction writer and Rejected to the reject writer")
    @SuppressWarnings("unchecked")
    void routesMixedChunk() throws Exception {
        Transaction tx = org.mockito.Mockito.mock(Transaction.class);
        PostingResult posted = new PostingResult.Posted(tx);
        PostingResult rejected = new PostingResult.Rejected(
                "IMAGE-350-BYTES", RejectReasonCode.CARD_NOT_FOUND);

        Chunk<PostingResult> chunk = new Chunk<>();
        chunk.add(posted);
        chunk.add(rejected);

        writer.write(chunk);

        ArgumentCaptor<Chunk<Transaction>> postedCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(postedTransactionWriter).write(postedCaptor.capture());
        assertThat(postedCaptor.getValue().getItems()).containsExactly(tx);

        ArgumentCaptor<Chunk<RejectTransactionWriter.RejectedTransaction>> rejectCaptor =
                ArgumentCaptor.forClass(Chunk.class);
        verify(rejectTransactionWriter).write(rejectCaptor.capture());
        assertThat(rejectCaptor.getValue().getItems()).hasSize(1);
        RejectTransactionWriter.RejectedTransaction mapped = rejectCaptor.getValue().getItems().get(0);
        assertThat(mapped.originalRecordImage()).isEqualTo("IMAGE-350-BYTES");
        assertThat(mapped.reason()).isEqualTo(RejectReasonCode.CARD_NOT_FOUND);
    }

    @Test
    @DisplayName("all-posted chunk does not touch the reject writer")
    void allPostedChunk() throws Exception {
        Chunk<PostingResult> chunk = new Chunk<>();
        chunk.add(new PostingResult.Posted(org.mockito.Mockito.mock(Transaction.class)));

        writer.write(chunk);

        verify(postedTransactionWriter).write(org.mockito.ArgumentMatchers.any());
        verify(rejectTransactionWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("all-rejected chunk does not touch the posted writer")
    void allRejectedChunk() throws Exception {
        Chunk<PostingResult> chunk = new Chunk<>();
        chunk.add(new PostingResult.Rejected("IMG", RejectReasonCode.OVER_CREDIT_LIMIT));

        writer.write(chunk);

        verify(rejectTransactionWriter).write(org.mockito.ArgumentMatchers.any());
        verify(postedTransactionWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("empty chunk touches neither delegate")
    void emptyChunk() throws Exception {
        writer.write(new Chunk<>());

        verify(postedTransactionWriter, never()).write(org.mockito.ArgumentMatchers.any());
        verify(rejectTransactionWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("open/update/close forward to the step-scoped reject delegate")
    void lifecycleForwardsToRejectWriter() {
        ExecutionContext ctx = new ExecutionContext();

        writer.open(ctx);
        verify(rejectTransactionWriter).open(ctx);

        writer.update(ctx);
        verify(rejectTransactionWriter).update(ctx);

        writer.close();
        verify(rejectTransactionWriter).close();
    }
}
