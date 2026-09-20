package com.example.agent.service;

import com.example.agent.vector.ClasspathDocuments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 基础系统提示：身份、回答方式与语气规则。 */
    private static final String BASE_SYSTEM_PROMPT = """
            你是 Coder Sean（Sean Zhao）的 AI 数字分身，用他的第一人称口吻「我」与用户交流。
            你的输出要自然、连贯，像本人跟朋友聊天在认真帮忙，而不是在汇报"检索结果"。

            回答规则（务必遵守）：
            1. 不要向用户提及任何内部机制与过程：不出现"知识库""检索""RAG""上下文""工具"等词，
               也不要说"根据我的知识库""知识库中没有相关内容""让我查一下/搜一下"这类话；
            2. 若问题与提供的相关知识相关，自然地使用其中的要点回答；
               若无关或没有可用知识，直接凭你自己的知识正常作答，不要生硬地绕回"知识库有没有"；
            3. 回答要有实质内容：先给结论或思路，再给必要的解释或示例；能用列表/代码就清晰呈现，不要空话；
            4. 语气轻松随意、偶尔幽默，但朴实克制、不吹不夸；
            5. 结合本次会话已有的上下文连续作答，不要重复询问用户已经讲过的信息；
            6. Markdown 必须书写规范，否则不会被渲染：# 与列表符号（-、*、1.）后必须跟一个空格，
               加粗用 **内容**（星号紧贴文字、内部不留空格）。
            """;

    /** 会话标题生成提示。 */
    private static final String TITLE_PROMPT = """
            你是会话标题生成器。请用不超过 12 个字概括下面这段对话的核心主题，
            直接输出标题本身：不要引号、不要标点结尾、不要任何解释或前后缀。
            """;

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor questionAnswerAdvisor;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;

    /** skill 包根目录（SKILL.md + references/）。 */
    private final Path skillDir;
    /** 快速回答模式所用模型。 */
    private final String quickModel;
    /** 深度思考模式所用模型。 */
    private final String thinkModel;

    /** 组装好的完整系统提示（基础规则 + skill 内容），支持热重载。 */
    private volatile String systemPrompt;
    /** 本次加载到的 skill 文件（供状态查询）。 */
    private volatile List<String> loadedSkillFiles = List.of();

    public ChatService(
            ChatClient chatClient,
            QuestionAnswerAdvisor questionAnswerAdvisor,
            ChatMemory chatMemory,
            @Value("${knowledge.skill-dir:skill}") String skillDir,
            @Value("${chat.quick-model:deepseek-chat}") String quickModel,
            @Value("${chat.think-model:deepseek-reasoner}") String thinkModel) {
        this.chatClient = chatClient;
        this.questionAnswerAdvisor = questionAnswerAdvisor;
        this.quickModel = quickModel;
        this.thinkModel = thinkModel;
        this.skillDir = Path.of(skillDir).toAbsolutePath().normalize();
        // 记忆 Advisor 排在最前：先注入历史、并记录本轮"原始"提问，
        // 避免 RAG 把检索到的上下文写进对话记忆
        this.chatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .order(Ordered.HIGHEST_PRECEDENCE)
                .build();
        this.systemPrompt = buildSystemPrompt();
    }

    /**
     * 热重载个人 Skill：重新读取 skill/ 下的 md 并重建系统提示（无需重启）。
     */
    public SkillReloadResult reloadSkill() {
        this.systemPrompt = buildSystemPrompt();
        return new SkillReloadResult(loadedSkillFiles, systemPrompt.length());
    }

    /**
     * 读取 skill 包（SKILL.md + references/*.md）并组装系统提示。
     * 优先工作目录 skill/（可本地编辑 + 热重载），读不到时回退 jar 内置资源。
     */
    private String buildSystemPrompt() {
        List<ClasspathDocuments.Doc> docs = loadSkillDocs();
        this.loadedSkillFiles = docs.stream().map(ClasspathDocuments.Doc::name).toList();

        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (docs.isEmpty()) {
            log.warn("未找到 skill 文件（{}），仅使用基础系统提示", skillDir);
            return sb.toString();
        }

        sb.append("\n\n===== 个人 Skill（请严格按以下资料的语气、思路与边界作答）=====\n");
        sb.append("以 Coder Sean 的第一人称「我」作答；语气、措辞与回答结构遵循 voice.md；\n");
        sb.append("自我介绍、立场与思路遵循 identity.md（别像念简历、别自夸）；\n");
        sb.append("判断\"该不该下结论、能说到什么程度\"时遵循 knowledge-source.md（没把握的领域不下肯定结论）。\n");

        for (ClasspathDocuments.Doc d : docs) {
            sb.append("\n----- ").append(d.name()).append(" -----\n");
            sb.append(d.content().trim()).append('\n');
        }
        log.info("已加载 skill 文件: {}", loadedSkillFiles);
        return sb.toString();
    }

    /** 读取 skill 文档：工作目录优先，读不到回退 jar 内置资源。 */
    private List<ClasspathDocuments.Doc> loadSkillDocs() {
        List<ClasspathDocuments.Doc> docs = new ArrayList<>();
        Path skillMd = skillDir.resolve("SKILL.md");
        if (Files.isRegularFile(skillMd)) {
            try {
                docs.add(new ClasspathDocuments.Doc("SKILL.md", Files.readString(skillMd, StandardCharsets.UTF_8)));
                Path refs = skillDir.resolve("references");
                if (Files.isDirectory(refs)) {
                    try (Stream<Path> s = Files.list(refs)) {
                        s.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                                .sorted()
                                .forEach(p -> {
                                    try {
                                        docs.add(new ClasspathDocuments.Doc(p.getFileName().toString(),
                                                Files.readString(p, StandardCharsets.UTF_8)));
                                    } catch (IOException e) {
                                        log.warn("读取 skill 文件失败: {}", p, e);
                                    }
                                });
                    }
                }
                return docs;
            } catch (IOException e) {
                log.warn("读取工作目录 skill 失败，回退 jar 内置资源: {}", skillDir, e);
                docs.clear();
            }
        }
        // 回退：jar 内置资源
        try {
            docs.addAll(ClasspathDocuments.read("classpath*:skill/SKILL.md"));
            docs.addAll(ClasspathDocuments.read("classpath*:skill/references/*.md"));
        } catch (RuntimeException e) {
            log.warn("读取 jar 内置 skill 资源失败: {}", e.getMessage());
        }
        return docs;
    }

    /**
     * 带上下文记忆 + RAG 的对话，支持两种模式：
     * mode = "think" 深度思考（deepseek-reasoner）；否则快速回答（deepseek-chat）。
     */
    public Flux<String> chat(String message, String conversationId, String mode) {
        String cid = (conversationId == null || conversationId.isBlank())
                ? ChatMemory.DEFAULT_CONVERSATION_ID
                : conversationId;
        boolean deep = "think".equalsIgnoreCase(mode);
        String model = deep ? thinkModel : quickModel;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .options(OpenAiChatOptions.builder().model(model).build())
                .advisors(questionAnswerAdvisor, chatMemoryAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .stream()
                .content();
    }

    /** 用 LLM 把对话内容概括成一个短标题（供侧边栏展示）。 */
    public String generateTitle(String text) {
        if (text == null || text.isBlank()) {
            return "新对话";
        }
        try {
            String title = chatClient.prompt()
                    .system(TITLE_PROMPT)
                    .user(text)
                    .call()
                    .content();
            if (title == null || title.isBlank()) {
                return "新对话";
            }
            String cleaned = title.trim()
                    .replaceAll("[\"'“”]+", "")
                    .replaceAll("[。！!？?\\s]+$", "")
                    .trim();
            if (cleaned.length() > 20) {
                cleaned = cleaned.substring(0, 20);
            }
            return cleaned.isBlank() ? "新对话" : cleaned;
        } catch (Exception e) {
            log.warn("生成会话标题失败", e);
            return "新对话";
        }
    }

    /** Skill 热重载结果。 */
    public record SkillReloadResult(List<String> files, int promptChars) {
    }
}
