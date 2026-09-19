<template>
  <aside class="sidebar">
    <div class="header">
      <div class="brand">
        <div class="logo">CS</div>
        <span class="name">Coder Sean</span>
      </div>
      <button class="new-chat" @click="chat.newChat()">
        <span class="plus">＋</span> 新建对话
      </button>
    </div>

    <div class="list">
      <div
        v-for="c in chat.conversations"
        :key="c.id"
        class="item"
        :class="{ active: c.id === chat.activeId }"
        @click="chat.selectChat(c.id)"
      >
        <span class="item-title">{{ c.title }}</span>
        <button class="del" title="删除对话" @click.stop="chat.deleteChat(c.id)">🗑</button>
      </div>
      <div v-if="!chat.conversations.length" class="empty">
        暂无对话，点击「新建对话」开始提问
      </div>
    </div>

    <div class="footer">
      <button class="theme-toggle" title="切换深浅色主题" @click="theme.toggleTheme()">
        <span class="theme-icon">{{ theme.isDark ? '' : '' }}</span>
        {{ theme.isDark ? '浅色模式' : '深色模式' }}
      </button>
      <div class="footer-text">AI 个人知识库对话助手</div>
    </div>
  </aside>
</template>

<script setup>
import { onMounted } from 'vue'
import { useChatStore } from '../store/chat'
import { useTheme } from '../theme'

const chat = useChatStore()
const theme = useTheme()
onMounted(() => chat.init())
</script>

<style scoped>
.sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  transition: background 0.2s ease, border-color 0.2s ease;
}
.header {
  padding: 14px 12px;
  border-bottom: 1px solid var(--border);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.logo {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--accent);
  color: var(--accent-fg);
  font-weight: 700;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.name {
  font-weight: 700;
  font-size: 16px;
  color: var(--text);
}
.new-chat {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px;
  border-radius: 10px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  font-size: 14px;
  color: var(--text);
  transition: background 0.15s;
}
.new-chat:hover {
  background: var(--bg-hover);
}
.plus {
  font-size: 16px;
  line-height: 1;
}
.list {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
}
.item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 9px;
  cursor: pointer;
  font-size: 14px;
  color: var(--text);
  transition: background 0.15s;
}
.item:hover {
  background: var(--bg-hover);
}
.item.active {
  background: var(--bg-active);
}
.item-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.del {
  opacity: 0;
  font-size: 13px;
  padding: 2px;
  border-radius: 6px;
}
.item:hover .del {
  opacity: 1;
}
.del:hover {
  background: var(--bg-active);
}
.empty {
  padding: 20px 12px;
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
}
.footer {
  padding: 12px;
  border-top: 1px solid var(--border);
}
.theme-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px;
  border-radius: 9px;
  font-size: 13px;
  color: var(--text-secondary);
  transition: background 0.15s, color 0.15s;
}
.theme-toggle:hover {
  background: var(--bg-hover);
  color: var(--text);
}
.theme-icon {
  font-size: 15px;
}
.footer-text {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-faint);
  text-align: center;
}
</style>
