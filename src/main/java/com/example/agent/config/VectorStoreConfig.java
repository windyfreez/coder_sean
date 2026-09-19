package com.example.agent.config;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储与 RAG Advisor 配置（仅声明 Bean，启动加载见 WikiIndexInitializer）。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 内存级向量存储（每次启动重建）。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /** 覆盖默认的僵硬 QA 模板（默认要求"只能基于上下文、答不上就告知用户"）。 */
    private static final String QA_TEMPLATE = """
            {query}

            以下是可能与问题相关的知识片段（相关就用，不相关请忽略）：
            ---------------------
            {question_answer_context}
            ---------------------

            请直接、自然、连贯地回答用户问题：
            1. 上下文与问题相关时，把要点自然地融入回答（可转述，但不要出现"根据知识库""上下文显示"等说法）；
            2. 上下文不相关或为空时，直接基于你自己的知识正常回答即可，绝不声明"找不到""没有相关内容"；
            3. 全程不要提及"知识库""检索""工具""上下文""AI"等内部机制或过程；
            4. 输出简洁、工程化、具体可操作，保持自然的对话口吻。
            """;

    /**
     * RAG 检索增强 Advisor：相似度阈值 0.3，取 Top 3 相关片段。
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(3)
                .similarityThreshold(0.3)
                .build();
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .promptTemplate(new PromptTemplate(QA_TEMPLATE))
                .build();
    }
}
