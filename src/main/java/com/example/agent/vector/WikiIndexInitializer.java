package com.example.agent.vector;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时把 wiki 文档加载、切块并向量化到 VectorStore。
 * 注意：SimpleVectorStore 是内存存储，启动时恒为空，故每次启动都重新索引。
 */
@Component
public class WikiIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(WikiIndexInitializer.class);

    private final VectorStore vectorStore;
    private final WikiDocumentReader wikiDocumentReader;
    private final TextSplitter textSplitter;

    public WikiIndexInitializer(VectorStore vectorStore, WikiDocumentReader wikiDocumentReader) {
        this.vectorStore = vectorStore;
        this.wikiDocumentReader = wikiDocumentReader;
        // 按 ~800 token 切块，让短问题能命中具体段落，而不是被整篇大文档的向量稀释
        this.textSplitter = new TokenTextSplitter();
    }

    @PostConstruct
    public void init() {
        List<Document> documents = wikiDocumentReader.readAll();
        if (documents.isEmpty()) {
            log.warn("Wiki 目录为空，跳过向量化索引");
            return;
        }
        List<Document> chunks = textSplitter.split(documents);
        log.info("共 {} 个 Wiki 文档，切块后 {} 个 chunk，开始向量化...", documents.size(), chunks.size());
        try {
            vectorStore.add(chunks);
            log.info("向量化索引完成，共 {} 个 chunk", chunks.size());
        } catch (Exception e) {
            log.error("向量化索引失败（Embedding 端点不可用？注意 DeepSeek 不提供 embedding 接口）", e);
        }
    }
}
