package com.ryankew.infinitechatagent.job;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.segment.TextSegmentTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


/**
 * 自动加载文档。
 *
 * 注意：DashScope text-embedding-v4 单次请求最多接收 10 条文本，
 * 而一次性 embedAll(全部切片) 在切片多、网络偶发卡顿时会整批失败，
 * 且 EmbeddingStoreIngestor 是"全部算完再一次性写库"，失败时一条都进不去。
 * 这里改成按小批增量写入 + 单批重试：某批失败只影响该批，已成功的批次保留。
 */
@Component
@Order(1)
@Slf4j
public class RagDataLoader implements CommandLineRunner {

    @Value("${rag.docs-path}")
    private String docsPath;

    // 单批切片数。DashScope text-embedding-v4 单次最多 10 条，传更大也会被模型内部再拆成 10 条。
    @Value("${rag.embedding-batch-size:10}")
    private int embeddingBatchSize;

    // 单批最大尝试次数（含首次）与失败后的等待时间。
    @Value("${rag.embedding-max-attempts:3}")
    private int embeddingMaxAttempts;

    @Value("${rag.embedding-retry-delay-ms:2000}")
    private long embeddingRetryDelayMs;

    @Resource
    private DocumentSplitter ragDocumentSplitter;

    @Resource
    private TextSegmentTransformer ragTextSegmentTransformer;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Override
    public void run(String... args) {
        log.info("RAG - 开始加载本地基础文档，路径: {}", docsPath);
        try {
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(docsPath);
            if (documents.isEmpty()) {
                log.warn("RAG - 指定路径下未发现文档");
                return;
            }

            List<TextSegment> segments = new ArrayList<>();
            for (Document document : documents) {
                segments.addAll(ragDocumentSplitter.split(document));
            }
            List<TextSegment> transformed = segments.stream()
                    .map(ragTextSegmentTransformer::transform)
                    .toList();

            int total = transformed.size();
            int batchSize = Math.max(1, Math.min(embeddingBatchSize, 10));
            int totalBatches = (total + batchSize - 1) / batchSize;
            log.info("RAG - 文档切分完成：{} 个文档 / {} 个切片，开始分批向量化（每批 {} 条，共 {} 批）",
                    documents.size(), total, batchSize, totalBatches);

            int failedBatches = 0;
            for (int i = 0; i < total; i += batchSize) {
                int batchIndex = i / batchSize + 1;
                List<TextSegment> batch = transformed.subList(i, Math.min(i + batchSize, total));
                if (!embedAndStore(batch, batchIndex, totalBatches)) {
                    failedBatches++;
                }
                if (batchIndex % 10 == 0 || batchIndex == totalBatches) {
                    log.info("RAG - 向量化进度 {}/{} 批", batchIndex, totalBatches);
                }
            }

            if (failedBatches == 0) {
                log.info("RAG - 本地文档加载完成，共加载 {} 个文档 / {} 个切片", documents.size(), total);
            } else {
                log.warn("RAG - 本地文档加载完成，但有 {} 个批次失败，向量库可能不完整", failedBatches);
            }
        } catch (Exception e) {
            log.error("RAG - 加载本地文档失败", e);
        }
    }

    private boolean embedAndStore(List<TextSegment> batch, int batchIndex, int totalBatches) {
        for (int attempt = 1; attempt <= embeddingMaxAttempts; attempt++) {
            try {
                Response<List<Embedding>> response = embeddingModel.embedAll(batch);
                embeddingStore.addAll(response.content(), batch);
                return true;
            } catch (Exception e) {
                log.warn("RAG - 第 {}/{} 批向量化失败（第 {}/{} 次尝试）：{}",
                        batchIndex, totalBatches, attempt, embeddingMaxAttempts, e.getMessage());
                if (attempt < embeddingMaxAttempts) {
                    try {
                        Thread.sleep(embeddingRetryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        log.error("RAG - 第 {}/{} 批在 {} 次尝试后仍失败，跳过该批", batchIndex, totalBatches, embeddingMaxAttempts);
        return false;
    }
}
