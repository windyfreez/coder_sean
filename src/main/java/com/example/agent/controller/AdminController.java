package com.example.agent.controller;

import com.example.agent.service.ChatService;
import com.example.agent.service.CompileKnowledgeService;
import com.example.agent.vector.VectorIndexService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 管理类接口：知识库编译、Skill 热重载、向量库与运行状态等运维操作。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CompileKnowledgeService compileKnowledgeService;
    private final ChatService chatService;
    private final VectorIndexService vectorIndexService;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    /** onnx = 本地模型；openai = 云端 OpenAI 兼容 Embedding。 */
    private final String embeddingProvider;

    public AdminController(CompileKnowledgeService compileKnowledgeService,
                           ChatService chatService,
                           VectorIndexService vectorIndexService,
                           EmbeddingModel embeddingModel,
                           VectorStore vectorStore,
                           @Value("${knowledge.embedding.provider:onnx}") String embeddingProvider) {
        this.compileKnowledgeService = compileKnowledgeService;
        this.chatService = chatService;
        this.vectorIndexService = vectorIndexService;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 编译个人 Wiki（data/raw-notes → data/wiki）
     */
    @PostMapping("/compile")
    public CompileKnowledgeService.CompileResult compile() {
        return compileKnowledgeService.compileWiki();
    }

    /**
     * 热重载个人 Skill（重新读取 skill/ 下的 md 并重建系统提示，无需重启）
     */
    @PostMapping("/skill/reload")
    public ChatService.SkillReloadResult reloadSkill() {
        return chatService.reloadSkill();
    }

    /**
     * 重新切块 + 向量化，并写入 knowledge.vector-store-file（再打包即可内置进 jar）
     */
    @PostMapping("/reindex")
    public VectorIndexService.ReindexResult reindex() throws IOException {
        return vectorIndexService.reindex();
    }

    /**
     * 运行状态自检：skill 加载情况、向量库来源与条数、向量维度
     */
    @GetMapping("/status")
    public Status status() {
        int dimensions = -1;
        try {
            dimensions = embeddingModel.dimensions();
        } catch (Exception e) {
            // 模型不可用时也要能拿到其余状态
        }
        return new Status(
                chatService.loadedSkillFiles(),
                chatService.systemPromptChars(),
                vectorIndexService.indexSource(),
                vectorIndexService.indexedChunks(),
                embeddingProvider,
                dimensions);
    }

    /** 状态快照。 */
    public record Status(List<String> skillFiles,
                         int systemPromptChars,
                         String vectorIndexSource,
                         int vectorChunks,
                         String embeddingProvider,
                         int embeddingDimensions) {
    }

    /**
     * 检索自检：只看向量库命中了哪些片段，不消耗大模型额度（排查"知识到底检索到没有"）
     */
    @GetMapping("/search")
    public List<Hit> search(@RequestParam("q") String query,
                            @RequestParam(value = "topK", defaultValue = "3") int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.0)
                        .build())
                .stream()
                .map(d -> new Hit(String.valueOf(d.getMetadata().get("source")), d.getScore(), preview(d)))
                .toList();
    }

    private static String preview(Document document) {
        String flat = document.getText().replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    /** 单条检索命中。 */
    public record Hit(String source, Double score, String preview) {
    }
}
