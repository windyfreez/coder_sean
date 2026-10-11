package com.example.agent.vector;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 LangChain4j 的本地 ONNX 向量模型适配成 Spring AI 的 {@link EmbeddingModel}。
 *
 * <p>Spring AI 自带的 ONNX 实现（spring-ai-transformers）会走 DJL 的
 * {@code NDManager.newBaseManager()}，其默认引擎是 PyTorch，首次使用时要去 djl.ai
 * 下载几百 MB 原生库，离线服务器上会直接失败；所以这里只用 langchain4j 的纯 Java 池化实现，
 * 通过本适配器接回 Spring AI 的接口，对外完全透明。
 */
public class LocalOnnxEmbeddingModel implements EmbeddingModel {

    private final dev.langchain4j.model.embedding.EmbeddingModel delegate;

    /** 首次推理后缓存向量维度，避免重复探测。 */
    private volatile int dimension;

    public LocalOnnxEmbeddingModel(dev.langchain4j.model.embedding.EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<TextSegment> segments = request.getInstructions().stream()
                .map(TextSegment::from)
                .toList();
        List<dev.langchain4j.data.embedding.Embedding> vectors = delegate.embedAll(segments).content();

        List<Embedding> results = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i).vector();
            this.dimension = vector.length;
            results.add(new Embedding(vector, i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        int known = this.dimension;
        return known > 0 ? known : EmbeddingModel.super.dimensions();
    }
}
