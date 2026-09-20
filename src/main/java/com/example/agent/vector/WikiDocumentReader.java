package com.example.agent.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 读取 wiki 目录下的 Markdown，构造成带文件名元数据的 Document 列表。
 * 优先读工作目录；目录不存在时回退到 jar 内置资源（同名以工作目录为准）。
 */
@Component
public class WikiDocumentReader {

    private static final Logger log = LoggerFactory.getLogger(WikiDocumentReader.class);

    /** jar 内置 Wiki 资源位置（由 pom 的 resources 配置决定）。 */
    private static final String CLASSPATH_PATTERN = "classpath*:data/wiki/**/*.md";

    private final Path wikiDir;

    public WikiDocumentReader(@Value("${knowledge.wiki-dir:data/wiki}") String wikiDir) {
        this.wikiDir = Path.of(wikiDir).toAbsolutePath().normalize();
    }

    public List<Document> readAll() {
        Map<String, Document> byName = new LinkedHashMap<>();

        // 1) 工作目录优先（本地开发 / 服务器已拷贝）
        if (Files.isDirectory(wikiDir)) {
            try (Stream<Path> paths = Files.walk(wikiDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                        .forEach(path -> {
                            try {
                                String fileName = path.getFileName().toString();
                                String content = Files.readString(path, StandardCharsets.UTF_8);
                                byName.put(fileName, toDocument(fileName, content, path.toString()));
                                log.info("[读取] {} ({} 字符)", fileName, content.length());
                            } catch (IOException e) {
                                log.warn("[跳过] 无法读取文件: {}", path, e);
                            }
                        });
            } catch (IOException e) {
                log.error("遍历 Wiki 目录失败: {}", wikiDir, e);
            }
        } else {
            log.info("Wiki 目录不存在，尝试读取 jar 内置资源: {}", wikiDir);
        }

        // 2) jar 内置资源兜底（同名以工作目录为准）
        try {
            for (ClasspathDocuments.Doc d : ClasspathDocuments.read(CLASSPATH_PATTERN)) {
                if (!byName.containsKey(d.name())) {
                    byName.put(d.name(), toDocument(d.name(), d.content(), "classpath:data/wiki/" + d.name()));
                    log.info("[读取] {} ({} 字符, 内置资源)", d.name(), d.content().length());
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取 jar 内置 Wiki 资源失败: {}", e.getMessage());
        }

        List<Document> documents = new ArrayList<>(byName.values());
        log.info("共读取 {} 个 Wiki 文档", documents.size());
        return documents;
    }

    private Document toDocument(String fileName, String content, String path) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", fileName);
        metadata.put("file_name", fileName);
        metadata.put("path", path);
        return new Document(content, metadata);
    }
}
