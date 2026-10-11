package com.example.agent.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量库的装配与重建。
 *
 * <p>启动时按以下顺序取向量库（SimpleVectorStore 在内存中，每次启动都要重新填充）：
 * <ol>
 *   <li>工作目录里的 {@code knowledge.vector-store-file}（存在即用，改完知识可以热替换）；</li>
 *   <li>jar 内置的 {@code classpath:/vectorstore/vectorstore.json}（打包时生成，部署即用，无需向量化）；</li>
 *   <li>都没有时，才读 wiki 文档现场切块 + 向量化（离线本地模型，也能算得出来）。</li>
 * </ol>
 */
@Component
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    /** 打包进 jar 的向量库副本。 */
    private static final String BUNDLED_INDEX = "classpath:/vectorstore/vectorstore.json";

    private final SimpleVectorStore vectorStore;
    private final WikiDocumentReader wikiDocumentReader;
    private final ResourceLoader resourceLoader;
    private final EmbeddingModel embeddingModel;
    private final String vectorStoreFile;
    private final TextSplitter textSplitter = new TokenTextSplitter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前生效的向量条数。 */
    private volatile int indexedChunks;
    /** 当前向量库的来源描述（便于排查"到底用了哪份数据"）。 */
    private volatile String indexSource = "未加载";
    /**
     * 当前库中所有向量 id。SimpleVectorStore 不会按内容去重，
     * 重建前必须按 id 清空，否则会不断叠加（同一片段出现多次、挤占 TopK）。
     * 从文件加载时，JSON 的顶级 key 就是这些 id。
     */
    private final Set<String> presentIds = ConcurrentHashMap.newKeySet();

    public VectorIndexService(
            SimpleVectorStore vectorStore,
            WikiDocumentReader wikiDocumentReader,
            ResourceLoader resourceLoader,
            EmbeddingModel embeddingModel,
            @Value("${knowledge.vector-store-file:data/vectorstore/vectorstore.json}") String vectorStoreFile) {
        this.vectorStore = vectorStore;
        this.wikiDocumentReader = wikiDocumentReader;
        this.resourceLoader = resourceLoader;
        this.embeddingModel = embeddingModel;
        this.vectorStoreFile = vectorStoreFile;
    }

    @PostConstruct
    public void init() {
        try {
            Path local = Path.of(vectorStoreFile).toAbsolutePath().normalize();
            if (Files.isRegularFile(local)) {
                load(new FileSystemResource(local), local.toString());
                return;
            }

            Resource bundled = resourceLoader.getResource(BUNDLED_INDEX);
            if (bundled.exists()) {
                load(bundled, BUNDLED_INDEX);
                return;
            }

            log.info("未找到任何向量库文件（{} / {}），改为启动时现算", local, BUNDLED_INDEX);
            rebuild();
            saveCache("启动时现算完成");
        } catch (Exception e) {
            log.error("向量库初始化失败，本次启动将退化为无知识检索（对话仍可用）", e);
        }
    }

    private void load(Resource resource, String source) throws IOException {
        // 顺手记下 id 与向量维度：id 用于幂等重建，维度用于判断这份向量库是否还配得上当前模型
        Set<String> ids = new LinkedHashSet<>();
        int storedDimensions = 0;
        try (InputStream in = resource.getInputStream()) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectMapper.readTree(in).fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                ids.add(field.getKey());
                if (storedDimensions == 0) {
                    JsonNode embedding = field.getValue().get("embedding");
                    if (embedding != null && embedding.isArray()) {
                        storedDimensions = embedding.size();
                    }
                }
            }
        }

        // 换过 Embedding 模型（比如 onnx 换 openai）后维度会变，硬加载只会算出无意义的相似度，
        // 所以这里直接丢弃旧数据改为重新向量化
        int currentDimensions = currentDimensions();
        if (storedDimensions > 0 && currentDimensions > 0 && storedDimensions != currentDimensions) {
            log.warn("向量库维度不匹配：{} 是 {} 维、当前 Embedding 模型是 {} 维，忽略该文件并重新向量化",
                    source, storedDimensions, currentDimensions);
            rebuild();
            // 云端 Embedding 是逐条请求的，重算一次成本不低；落盘后下次启动直接加载
            saveCache("重新向量化完成");
            return;
        }

        vectorStore.load(resource);
        presentIds.clear();
        presentIds.addAll(ids);
        this.indexedChunks = ids.size();
        this.indexSource = source;
        log.info("已加载向量库（{}）：{} 条向量（{} 维），跳过启动时向量化",
                source, ids.size(), storedDimensions > 0 ? storedDimensions : currentDimensions);
    }

    /** 当前 Embedding 模型的向量维度；取不到时返回 -1（表示跳过校验）。 */
    private int currentDimensions() {
        try {
            return embeddingModel.dimensions();
        } catch (Exception e) {
            log.warn("获取当前 Embedding 维度失败，跳过向量库维度校验: {}", e.toString());
            return -1;
        }
    }

    /**
     * 重新读取 wiki、切块并向量化（不落盘）。返回 chunk 数。
     */
    public synchronized int rebuild() {
        List<Document> documents = wikiDocumentReader.readAll();
        if (documents.isEmpty()) {
            log.warn("Wiki 文档为空，跳过向量化");
            return 0;
        }
        List<Document> chunks = textSplitter.split(documents);

        // 先清空旧向量，保证"重建"是幂等的（否则每次重建都会翻倍）
        if (!presentIds.isEmpty()) {
            vectorStore.delete(new ArrayList<>(presentIds));
            log.info("已清除旧向量 {} 条", presentIds.size());
            presentIds.clear();
        }

        log.info("共 {} 个 Wiki 文档，切块后 {} 个 chunk，开始向量化...", documents.size(), chunks.size());
        vectorStore.add(chunks);
        chunks.forEach(chunk -> presentIds.add(chunk.getId()));
        this.indexedChunks = chunks.size();
        this.indexSource = "现场向量化";
        log.info("向量化完成，共 {} 个 chunk", chunks.size());
        return chunks.size();
    }

    /**
     * 重建向量库并落盘到 {@code knowledge.vector-store-file}，
     * 便于把新数据再打包进 jar（更新知识后重新出包用）。
     */
    public synchronized ReindexResult reindex() throws IOException {
        int chunks = rebuild();
        Path target = saveCache("手动重新索引完成");
        return new ReindexResult(chunks, target.toString());
    }

    /** 把当前向量库写到 {@code knowledge.vector-store-file}；写不进去只告警，不影响运行。 */
    private Path saveCache(String reason) {
        Path target = Path.of(vectorStoreFile).toAbsolutePath().normalize();
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            vectorStore.save(target.toFile());
            this.indexSource = target.toString();
            log.info("{}，向量库已缓存到 {}（下次启动直接加载，无需重新向量化）", reason, target);
            return target;
        } catch (Exception e) {
            log.warn("向量库缓存落盘失败（不影响本次运行）: {}", e.toString());
            return target;
        }
    }

    public int indexedChunks() {
        return indexedChunks;
    }

    public String indexSource() {
        return indexSource;
    }

    /** 重新索引结果。 */
    public record ReindexResult(int chunks, String savedTo) {
    }
}
