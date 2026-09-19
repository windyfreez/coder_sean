package com.example.agent.controller;

import com.example.agent.service.ChatService;
import com.example.agent.service.CompileKnowledgeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理类接口：知识库编译、Skill 热重载等运维操作。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CompileKnowledgeService compileKnowledgeService;
    private final ChatService chatService;

    public AdminController(CompileKnowledgeService compileKnowledgeService, ChatService chatService) {
        this.compileKnowledgeService = compileKnowledgeService;
        this.chatService = chatService;
    }

    /** 编译个人 Wiki（data/raw-notes → data/wiki）。 */
    @PostMapping("/compile")
    public CompileKnowledgeService.CompileResult compile() {
        return compileKnowledgeService.compileWiki();
    }

    /** 热重载个人 Skill（重新读取 skill/ 下的 md 并重建系统提示，无需重启）。 */
    @PostMapping("/skill/reload")
    public ChatService.SkillReloadResult reloadSkill() {
        return chatService.reloadSkill();
    }
}
