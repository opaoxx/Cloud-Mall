package com.cloudmall.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.domain.po.ProductIndexRebuildJobPO;
import com.cloudmall.product.mapper.ProductIndexRebuildMapper;
import com.cloudmall.product.service.ProductIndexWriter;
import com.cloudmall.product.service.impl.index.ProductIndexDocumentLoader;
import com.cloudmall.product.service.impl.index.ProductIndexRebuildJob;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ES Bulk 失败不得跳游标，索引丢失后必须重新建立完整代次。 */
class ProductIndexRebuildJobTest {
  @Test
  void successfulBulkAdvancesOnlyTheBoundedBatchCursor() throws Exception {
    ProductIndexRebuildMapper mapper = mock(ProductIndexRebuildMapper.class);
    ProductIndexDocumentLoader loader = mock(ProductIndexDocumentLoader.class);
    ProductIndexWriter writer = mock(ProductIndexWriter.class);
    ProductIndexRebuildJobPO job = job("PENDING", 0L);
    when(mapper.findJob("cloudmall-product-index")).thenReturn(job);
    when(mapper.claim(eq("cloudmall-product-index"), anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              job.leaseOwner = invocation.getArgument(1);
              return 1;
            });
    when(loader.loadBatch(0L, 2)).thenReturn(List.of(document(1L), document(2L)));
    when(mapper.finishBatch(
            eq("cloudmall-product-index"),
            anyString(),
            eq(0L),
            eq(2L),
            eq(2),
            eq("RUNNING"),
            any(LocalDateTime.class)))
        .thenReturn(1);
    ProductIndexRebuildJob rebuild = rebuild(mapper, loader, writer);

    rebuild.runNextBatch();

    verify(writer).upsertBatch(anyList());
    verify(mapper)
        .finishBatch(
            eq("cloudmall-product-index"),
            anyString(),
            eq(0L),
            eq(2L),
            eq(2),
            eq("RUNNING"),
            any(LocalDateTime.class));
    verify(mapper, never())
        .markFailed(anyString(), anyString(), anyString(), any(LocalDateTime.class));
  }

  @Test
  void partialBulkFailureLeavesTheOriginalCursorForRetry() throws Exception {
    ProductIndexRebuildMapper mapper = mock(ProductIndexRebuildMapper.class);
    ProductIndexDocumentLoader loader = mock(ProductIndexDocumentLoader.class);
    ProductIndexWriter writer = mock(ProductIndexWriter.class);
    ProductIndexRebuildJobPO job = job("PENDING", 0L);
    when(mapper.findJob("cloudmall-product-index")).thenReturn(job);
    when(mapper.claim(eq("cloudmall-product-index"), anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              job.leaseOwner = invocation.getArgument(1);
              return 1;
            });
    when(loader.loadBatch(0L, 2)).thenReturn(List.of(document(1L), document(2L)));
    doThrow(new IllegalStateException("ES partial failure")).when(writer).upsertBatch(anyList());

    rebuild(mapper, loader, writer).runNextBatch();

    verify(mapper)
        .markFailed(
            eq("cloudmall-product-index"), anyString(), anyString(), any(LocalDateTime.class));
    verify(mapper, never())
        .finishBatch(
            anyString(),
            anyString(),
            anyLong(),
            anyLong(),
            anyInt(),
            anyString(),
            any(LocalDateTime.class));
  }

  @Test
  void lostIndexRestartsFromZeroBeforeReadingLaterProducts() throws Exception {
    ProductIndexRebuildMapper mapper = mock(ProductIndexRebuildMapper.class);
    ProductIndexDocumentLoader loader = mock(ProductIndexDocumentLoader.class);
    ProductIndexWriter writer = mock(ProductIndexWriter.class);
    ProductIndexRebuildJobPO job = job("RUNNING", 100L);
    when(mapper.findJob("cloudmall-product-index")).thenReturn(job);
    when(mapper.claim(eq("cloudmall-product-index"), anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              job.leaseOwner = invocation.getArgument(1);
              return 1;
            });
    when(writer.indexExists()).thenReturn(false);
    when(mapper.restartClaimedAfterIndexLoss(
            eq("cloudmall-product-index"), anyString(), any(LocalDateTime.class)))
        .thenReturn(1);

    rebuild(mapper, loader, writer).runNextBatch();

    verify(mapper)
        .restartClaimedAfterIndexLoss(
            eq("cloudmall-product-index"), anyString(), any(LocalDateTime.class));
    verify(loader, never()).loadBatch(anyLong(), anyInt());
  }

  @Test
  void completedJobReopensWhenItsIndexDisappears() throws Exception {
    ProductIndexRebuildMapper mapper = mock(ProductIndexRebuildMapper.class);
    ProductIndexDocumentLoader loader = mock(ProductIndexDocumentLoader.class);
    ProductIndexWriter writer = mock(ProductIndexWriter.class);
    when(mapper.findJob("cloudmall-product-index")).thenReturn(job("COMPLETED", 200L));
    when(writer.indexExists()).thenReturn(false);
    when(loader.countSources()).thenReturn(88476L);
    when(mapper.restartCompleted(eq("cloudmall-product-index"), any(LocalDateTime.class)))
        .thenReturn(1);

    rebuild(mapper, loader, writer).auditCompletedIndex();

    verify(mapper).restartCompleted(eq("cloudmall-product-index"), any(LocalDateTime.class));
    verify(writer, never()).countDocuments();
  }

  private static ProductIndexRebuildJob rebuild(
      ProductIndexRebuildMapper mapper,
      ProductIndexDocumentLoader loader,
      ProductIndexWriter writer) {
    return new ProductIndexRebuildJob(
        mapper,
        loader,
        writer,
        new MockEnvironment()
            .withProperty("cloudmall.product.elasticsearch.index", "cloudmall_product")
            .withProperty("cloudmall.product.index.rebuild-batch-size", "2")
            .withProperty("cloudmall.product.index.rebuild-enabled", "true")
            .withProperty("cloudmall.product.index.rebuild-max-events-per-minute", "120"));
  }

  private static ProductIndexRebuildJobPO job(String status, long cursor) {
    ProductIndexRebuildJobPO job = new ProductIndexRebuildJobPO();
    job.jobId = "cloudmall-product-index";
    job.targetIndex = "cloudmall_product";
    job.cursorProductId = cursor;
    job.batchSize = 2;
    job.maxEventsPerMinute = 120;
    job.status = status;
    return job;
  }

  private static ProductIndexDocument document(long productId) {
    return new ProductIndexDocument(
        productId, 1L, "测试商品", null, null, new BigDecimal("12.30"), 1, 0L);
  }
}
