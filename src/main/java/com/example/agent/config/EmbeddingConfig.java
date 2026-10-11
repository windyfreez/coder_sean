package com.example.agent.config;

import com.example.agent.vector.LocalOnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地 ONNX 向量模型（bge-small-zh-v1.5 量化版），在进程内计算，完全离线。
 *
 * <p>为什么不用 Ollama / 云端 Embedding：部署目标是"什么都没有"的 Linux 服务器，
 * 装 Ollama 又慢又重。这里把模型文件直接打进 jar，启动即可用，不需要任何外部服务。
 *
 * <p>模型文件来源：依赖 {@code langchain4j-embeddings-bge-small-zh-v15-q}，
 * 它的 jar 根目录里就放着 {@code bge-small-zh-v1.5-q.onnx} 与 tokenizer，
 * 本类按 classpath 资源读取，所以模型会随 fat jar 一起分发。
 *
 * <p>读取顺序：工作目录 {@code knowledge.embedding.dir} 覆盖 → jar 内置资源
 * （内置资源会先解压到缓存目录，再用真实文件加载，避免依赖嵌套 jar 的路径行为）。
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /** 打包进 jar 的模型文件名（由 langchain4j 模型载体包提供）。 */
    private static final String MODEL_FILE = "bge-small-zh-v1.5-q.onnx";
    private static final String TOKENIZER_FILE = "bge-small-zh-v1.5-q-tokenizer.json";

    /** BGE 系列用 CLS 池化；L2 归一化由 OnnxBertBiEncoder 内部完成。 */
    private static final PoolingMode POOLING_MODE = PoolingMode.CLS;

    private final Path modelDir;
    private final Path cacheDir;

    public EmbeddingConfig(
            @Value("${knowledge.embedding.dir:data/onnx}") String modelDir,
            @Value("${knowledge.embedding.cache-dir:}") String cacheDir) {
        this.modelDir = Path.of(modelDir).toAbsolutePath().normalize();
        this.cacheDir = (cacheDir == null || cacheDir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "personal-knowledge-agent", "onnx")
                : Path.of(cacheDir).toAbsolutePath().normalize();
    }

    /**
     * 本地 ONNX 向量模型。只有 {@code knowledge.embedding.provider=onnx}（默认）时才装配；
     * 设为 {@code openai} 时改由 Spring AI 的 OpenAI 兼容 Embedding 自动装配接管。
     */
    @Bean
    @ConditionalOnProperty(name = "knowledge.embedding.provider", havingValue = "onnx", matchIfMissing = true)
    public EmbeddingModel embeddingModel() throws IOException {
        Path model = materialize(MODEL_FILE);
        Path tokenizer = materialize(TOKENIZER_FILE);

        OnnxEmbeddingModel onnx;
        try {
            onnx = new OnnxEmbeddingModel(model, tokenizer, POOLING_MODE);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // 原生库加载失败（最常见的就是 glibc / libstdc++ 太老），给出可执行的提示
            throw new IllegalStateException(nativeLibraryHint(e), e);
        }

        // 启动自检：真正跑一次推理，确认模型可用（失败不阻塞启动，只告警）
        int dim = -1;
        try {
            dim = onnx.dimension();
        } catch (Exception e) {
            log.warn("向量模型自检失败，检索可能不可用: {}", e.toString());
        }
        log.info("本地向量模型就绪：{}（{} 维，CLS 池化，无需任何外部向量服务）",
                model.getFileName(), dim > 0 ? dim : "未知");
        return new LocalOnnxEmbeddingModel(onnx);
    }

    /** 把原生库加载失败翻译成"下一步该干什么"。 */
    private static String nativeLibraryHint(Throwable e) {
        String message = String.valueOf(e.getMessage());
        StringBuilder sb = new StringBuilder();
        sb.append("\n本地 ONNX 向量模型的原生库加载失败：").append(message).append('\n');
        if (message.contains("GLIBC") || message.contains("GLIBCXX") || message.contains("CXXABI")) {
            sb.append("原因：当前系统的 glibc / libstdc++ 版本过低。\n")
              .append("  · 本项目已把 onnxruntime 锁在 1.16.3、djl tokenizers 锁在 0.28.0 以适配 CentOS 7\n")
              .append("    （它们的最高符号需求是 glibc 2.16 / GLIBCXX_3.4.19，CentOS 7 是 2.17 / 3.4.19）。\n")
              .append("  · 报错里如果出现 GLIBC_2.2x 或 GLIBCXX_3.4.2x，说明 jar 里的原生库版本被换回了新版。\n");
        }
        sb.append("解决办法（二选一）：\n")
          .append("  1) 改用云端 Embedding（推荐，无需任何原生库）：\n")
          .append("       java -jar app.jar --knowledge.embedding.provider=openai \\\n")
          .append("         --knowledge.embedding.base-url=https://api.siliconflow.cn \\\n")
          .append("         --knowledge.embedding.api-key=sk-xxx \\\n")
          .append("         --knowledge.embedding.model=BAAI/bge-m3\n")
          .append("  2) 换一台 glibc ≥ 2.27 的机器（CentOS 7 已 EOL，建议迁到 Rocky / AlmaLinux 8+）。");
        return sb.toString();
    }

    /**
     * 定位模型文件：工作目录优先，否则用 jar 内置资源（解压到缓存目录后返回真实路径）。
     */
    private Path materialize(String fileName) throws IOException {
        Path override = modelDir.resolve(fileName);
        if (Files.isRegularFile(override)) {
            log.info("[使用] 工作目录向量模型文件: {}", override);
            return override;
        }

        Resource bundled = new ClassPathResource(fileName);
        if (!bundled.exists()) {
            throw new IllegalStateException("找不到向量模型文件 " + fileName
                    + "：依赖 langchain4j-embeddings-bge-small-zh-v15-q 缺失，"
                    + "或该文件未被放在 " + modelDir);
        }

        long expected = -1;
        try {
            expected = bundled.contentLength();
        } catch (IOException ignored) {
            // 嵌套 jar 下偶尔取不到长度，忽略即可
        }

        Path cached = cacheDir.resolve(fileName);
        boolean needCopy = !Files.isRegularFile(cached)
                || (expected > 0 && Files.size(cached) != expected);
        if (needCopy) {
            Files.createDirectories(cacheDir);
            Path tmp = Files.createTempFile(cacheDir, fileName, ".part");
            try (InputStream in = bundled.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
            log.info("[解压] jar 内置向量模型 {} -> {}", fileName, cached);
        }
        return cached;
    }
}
