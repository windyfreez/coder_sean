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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 递归读取 wiki 目录下所有 Markdown 文件，构造成带文件名元数据的 Document 列表。
 */
@Component
public class WikiDocumentReader {

    private static final Logger log = LoggerFactory.getLogger(WikiDocumentReader.class);

    private final Path wikiDir;

    public WikiDocumentReader(@Value("${knowledge.wiki-dir:data/wiki}") String wikiDir) {
        this.wikiDir = Path.of(wikiDir).toAbsolutePath().normalize();
    }

    public List<Document> readAll() {
        List<Document> documents = new ArrayList<>();
        if (!Files.isDirectory(wikiDir)) {
            log.warn("Wiki 目录不存在: {}", wikiDir);
            return documents;
        }
        try (Stream<Path> paths = Files.walk(wikiDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            String fileName = path.getFileName().toString();
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("source", fileName);
                            metadata.put("file_name", fileName);
                            metadata.put("path", path.toString());
                            documents.add(new Document(content, metadata));
                            log.info("[读取] {} ({} 字符)", fileName, content.length());
                        } catch (IOException e) {
                            log.warn("[跳过] 无法读取文件: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.error("遍历 Wiki 目录失败: {}", wikiDir, e);
        }
        log.info("共读取 {} 个 Wiki 文档", documents.size());
        return documents;
    }
}
