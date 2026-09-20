package com.example.agent.service;

import com.example.agent.vector.ClasspathDocuments;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 个人知识库编译器：把 raw-notes 目录下的零散 Markdown 笔记，
 * 借助 LLM 生成元数据后，编译成带 front-matter 的 Wiki 文件写入 wiki 目录。
 * 支持增量编译（按文件修改时间判断），原始笔记优先读工作目录、读不到时回退 jar 内置资源。
 */
@Service
@Slf4j
public class CompileKnowledgeService {

    /** jar 内置原始笔记位置（由 pom 的 resources 配置决定）。 */
    private static final String RAW_NOTES_CLASSPATH_PATTERN = "classpath*:data/raw-notes/*.md";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final Path rawNotesDir;
    private final Path wikiDir;

    public CompileKnowledgeService(
            ChatClient chatClient,
            ObjectMapper objectMapper,
            @Value("${knowledge.raw-notes-dir:data/raw-notes}") String rawNotesDir,
            @Value("${knowledge.wiki-dir:data/wiki}") String wikiDir) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.rawNotesDir = Path.of(rawNotesDir).toAbsolutePath().normalize();
        this.wikiDir = Path.of(wikiDir).toAbsolutePath().normalize();
    }

    /**
     * 编译整个知识库（增量）：只处理新增或修改过的 .md 文件。
     */
    public CompileResult compileWiki() {
        log.info("开始编译知识库: rawNotesDir={}, wikiDir={}", rawNotesDir, wikiDir);

        List<Source> sources = loadSources();
        if (sources.isEmpty()) {
            log.warn("未找到原始笔记: {}", rawNotesDir);
            return new CompileResult(0, 0, 0, List.of(), List.of("未找到原始笔记: " + rawNotesDir));
        }
        List<String> allFileNames = sources.stream().map(Source::name).toList();

        try {
            Files.createDirectories(wikiDir);
        } catch (IOException e) {
            log.error("创建 Wiki 输出目录失败: {}", wikiDir, e);
            return new CompileResult(allFileNames.size(), 0, 0, List.of(), List.of("创建输出目录失败: " + e.getMessage()));
        }

        int compiled = 0;
        int skipped = 0;
        List<String> compiledFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Source src : sources) {
            Path wikiFile = wikiDir.resolve(src.name());
            try {
                if (isUnchanged(src, wikiFile)) {
                    skipped++;
                    log.info("[跳过] {} (未变更)", src.name());
                    continue;
                }
                compileOne(src, wikiFile, allFileNames);
                compiled++;
                compiledFiles.add(src.name());
            } catch (Exception e) {
                log.error("[失败] 编译笔记 {} 时出错", src.name(), e);
                errors.add(src.name() + ": " + e.getMessage());
            }
        }

        log.info("编译完成: 扫描 {} 个, 编译 {} 个, 跳过 {} 个, 失败 {} 个",
                allFileNames.size(), compiled, skipped, errors.size());
        return new CompileResult(allFileNames.size(), compiled, skipped, compiledFiles, errors);
    }

    /** 原始笔记来源：工作目录优先，读不到回退 jar 内置资源。 */
    private List<Source> loadSources() {
        List<Source> sources = new ArrayList<>();
        if (Files.isDirectory(rawNotesDir)) {
            try (Stream<Path> stream = Files.list(rawNotesDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                sources.add(new Source(p.getFileName().toString(),
                                        Files.readString(p, StandardCharsets.UTF_8),
                                        Files.getLastModifiedTime(p).toMillis()));
                            } catch (IOException e) {
                                log.warn("跳过无法读取的笔记: {}", p, e);
                            }
                        });
            } catch (IOException e) {
                log.error("扫描原始笔记目录失败: {}", rawNotesDir, e);
            }
        }
        if (!sources.isEmpty()) {
            return sources;
        }
        try {
            for (ClasspathDocuments.Doc d : ClasspathDocuments.read(RAW_NOTES_CLASSPATH_PATTERN)) {
                sources.add(new Source(d.name(), d.content(), null));
            }
            if (!sources.isEmpty()) {
                log.info("工作目录无原始笔记，已回退 jar 内置资源，共 {} 个", sources.size());
            }
        } catch (RuntimeException e) {
            log.warn("读取 jar 内置原始笔记失败: {}", e.getMessage());
        }
        return sources;
    }

    private boolean isUnchanged(Source src, Path wikiFile) {
        if (!Files.isRegularFile(wikiFile)) {
            return false; // 新增文件
        }
        if (src.lastModified() == null) {
            return true; // 内置资源无修改时间：已编译过即跳过
        }
        try {
            return src.lastModified() <= Files.getLastModifiedTime(wikiFile).toMillis();
        } catch (IOException e) {
            log.warn("无法读取修改时间, 按需重新编译: {}", wikiFile, e);
            return false;
        }
    }

    private void compileOne(Source src, Path wikiFile, List<String> allFileNames) throws IOException {
        log.info("[编译] {}", src.name());
        NoteMetadata metadata = generateMetadata(src.name(), src.content(), allFileNames);
        Files.writeString(wikiFile, buildWikiFile(src.name(), metadata, src.content()), StandardCharsets.UTF_8);
        log.info("[完成] {} -> {}", src.name(), wikiFile);
    }

    private NoteMetadata generateMetadata(String fileName, String content, List<String> allFileNames) {
        try {
            String prompt = buildMetadataPrompt(fileName, content, allFileNames);
            String response = chatClient.prompt(prompt).call().content();
            NoteMetadata metadata = objectMapper.readValue(extractJson(response), NoteMetadata.class);
            return sanitize(metadata, fileName);
        } catch (Exception e) {
            log.warn("LLM 生成元数据失败, 使用降级摘要: {} ({})", fileName, e.getMessage());
            return fallbackMetadata(content);
        }
    }

    private String buildMetadataPrompt(String fileName, String content, List<String> allFileNames) {
        String fileList = allFileNames.stream()
                .filter(name -> !name.equals(fileName))
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n"));
        return String.join("\n",
                "你是一个个人知识库整理助手。请阅读下面的笔记内容并生成元数据。",
                "",
                "可供关联的笔记文件名列表（从中选择内容最相关的）：",
                fileList,
                "",
                "当前笔记文件名：" + fileName,
                "笔记内容：",
                "---",
                content,
                "---",
                "",
                "请只输出一个 JSON 对象，不要输出任何解释、Markdown 代码块标记或其他文字，格式如下：",
                "{\"summary\":\"30字以内的核心摘要\",\"keywords\":[\"关键词1\",\"关键词2\",\"关键词3\"],\"related\":[\"相关笔记文件名1\",\"相关笔记文件名2\"]}",
                "",
                "要求：",
                "1. summary：不超过30个汉字，一句话概括笔记核心内容。",
                "2. keywords：恰好3个关键词，用于索引检索。",
                "3. related：从上面的文件名列表中选择0~2个内容最相关的文件名（不含当前文件自身）；若没有合适的则输出空数组 []。"
        );
    }

    private String extractJson(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private NoteMetadata sanitize(NoteMetadata metadata, String fileName) {
        String summary = metadata.summary() == null ? "" : metadata.summary().trim();
        if (summary.length() > 30) {
            summary = summary.substring(0, 30);
        }
        List<String> keywords = metadata.keywords() == null
                ? List.of()
                : metadata.keywords().stream().filter(k -> k != null && !k.isBlank()).limit(3).toList();
        List<String> related = metadata.related() == null
                ? List.of()
                : metadata.related().stream()
                        .filter(r -> r != null && !r.isBlank() && !r.equals(fileName))
                        .distinct()
                        .limit(2)
                        .toList();
        return new NoteMetadata(summary, keywords, related);
    }

    private NoteMetadata fallbackMetadata(String content) {
        String summary = "";
        if (content != null) {
            for (String line : content.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    summary = t;
                    break;
                }
            }
        }
        if (summary.length() > 30) {
            summary = summary.substring(0, 30);
        }
        return new NoteMetadata(summary, List.of(), List.of());
    }

    private String buildWikiFile(String fileName, NoteMetadata metadata, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(fileName).append('\n');
        sb.append("summary: ").append(metadata.summary()).append('\n');
        sb.append("keywords: ").append(formatList(metadata.keywords())).append('\n');
        sb.append("related: ").append(formatRelated(metadata.related())).append('\n');
        sb.append("---\n");
        sb.append(content);
        if (!content.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private String formatList(List<String> items) {
        return "[" + String.join(", ", items) + "]";
    }

    private String formatRelated(List<String> items) {
        return items.stream().map(n -> "[" + n + "]").collect(Collectors.joining(", ", "[", "]"));
    }

    /** 一个原始笔记来源（lastModified 为 null 表示来自 jar 内置资源）。 */
    private record Source(String name, String content, Long lastModified) {
    }

    /** LLM 返回的笔记元数据。 */
    public record NoteMetadata(String summary, List<String> keywords, List<String> related) {
    }

    /** 编译结果统计。 */
    public record CompileResult(int scanned, int compiled, int skipped,
                                List<String> compiledFiles, List<String> errors) {
    }
}
