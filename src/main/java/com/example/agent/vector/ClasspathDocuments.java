package com.example.agent.vector;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 从 classpath（含 jar 内 BOOT-INF/classes）读取文本资源的小工具，
 * 用于「工作目录没有 → 回退到 jar 内置资源」的部署场景。
 */
public final class ClasspathDocuments {

    private static final ResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

    private ClasspathDocuments() {
    }

    /**
     * 读取匹配 locationPattern（如 {@code classpath*:skill/references/*.md}）的所有文本资源，
     * 返回按文件名排序的 (name, content) 列表；匹配不到时返回空列表。
     */
    public static List<Doc> read(String locationPattern) {
        List<Doc> docs = new ArrayList<>();
        try {
            Resource[] resources = RESOLVER.getResources(locationPattern);
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource r : resources) {
                if (!r.isReadable()) {
                    continue;
                }
                String name = r.getFilename() != null ? r.getFilename() : r.getDescription();
                docs.add(new Doc(name, r.getContentAsString(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 classpath 资源失败: " + locationPattern, e);
        }
        return docs;
    }

    /** 一个具名文本资源。 */
    public record Doc(String name, String content) {
    }
}
