<template>
  <div class="wrap" ref="wrapRef">
    <div v-if="!conv || !conv.messages.length" class="welcome">
      <div class="welcome-logo">CS</div>
      <h1>你好，我是 Coder Sean</h1>
      <p>你的 AI 个人知识库对话助手，会基于你 wiki 里的知识来回答</p>
    </div>

    <div v-else class="messages">
      <div v-for="(m, i) in conv.messages" :key="i" class="msg" :class="m.role">
        <div class="avatar" :class="m.role">{{ m.role === 'user' ? '我' : 'CS' }}</div>
        <div class="body">
          <div class="markdown" v-html="renderMarkdown(m.content)"></div>
          <span v-if="m.streaming" class="cursor"></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { marked } from 'marked'
import { useChatStore } from '../store/chat'

// 全局 markdown 配置：GFM + 换行即 <br>
marked.setOptions({ gfm: true, breaks: true })

const chat = useChatStore()
const conv = computed(() => chat.activeConversation)
const wrapRef = ref(null)

function renderMarkdown(text) {
  if (!text) return ''
  try {
    return marked.parse(normalizeMarkdown(unwrapFenced(text)))
  } catch (e) {
    console.error('markdown 渲染失败:', e)
    // 兜底：渲染失败时至少展示原文，避免空白
    return text
  }
}

// 若模型把整段回答包在 ```markdown ``` 代码围栏里，剥掉外层围栏，
// 否则会以"代码块"原样显示 ##、** 等符号
function unwrapFenced(text) {
  const m = text.trim().match(/^```[a-zA-Z]*\r?\n([\s\S]*?)\r?\n```\s*$/)
  return m ? m[1] : text
}

// 修复模型常见的 markdown 格式偏差：
// marked 是严格 CommonMark —— 标记后缺空格（#标题 / -项目）、加粗内侧有空格（** 粗 **）都不会渲染
function normalizeMarkdown(text) {
  if (!text) return text
  let inFence = false
  return text
    .split('\n')
    .map((line) => {
      // 代码围栏内原样保留，避免误改源码
      if (/^\s*(```|~~~)/.test(line)) {
        inFence = !inFence
        return line
      }
      return inFence ? line : fixLine(line)
    })
    .join('\n')
}

function fixLine(line) {
  // 标题：#标题 → # 标题
  let m = line.match(/^([ \t]*)(#{1,6})([^#\s].*)$/)
  if (m) return `${m[1]}${m[2]} ${m[3]}`
  // 引用：>内容 → > 内容
  m = line.match(/^([ \t]*>)([^\s>].*)$/)
  if (m) return `${m[1]} ${m[2]}`
  // 有序列表：1.内容 → 1. 内容
  m = line.match(/^([ \t]*)(\d+)\.([^\s\d].*)$/)
  if (m) return `${m[1]}${m[2]}. ${m[3]}`
  // 无序列表：-内容 / +内容（排除 --- 分隔线）
  m = line.match(/^([ \t]*)([-+])([^\s\-+*].*)$/)
  if (m) return `${m[1]}${m[2]} ${m[3]}`
  // 无序列表：*内容（仅当整行没有第二个 *，避免误伤 *斜体*）
  m = line.match(/^([ \t]*)\*([^\s*].*)$/)
  if (m && !m[2].includes('*')) return `${m[1]}* ${m[2]}`
  // 加粗内侧多余空格：** 加粗 ** → **加粗**
  return line.replace(/\*\*([^\n]+?)\*\*/g, (whole, inner) => {
    const t = inner.trim()
    return t && t !== inner ? `**${t}**` : whole
  })
}

// 新消息或流式更新时自动滚到底部
watch(
  () => conv.value?.messages?.map((m) => m.content).join('|'),
  async () => {
    await nextTick()
    if (wrapRef.value) {
      wrapRef.value.scrollTop = wrapRef.value.scrollHeight
    }
  }
)
</script>

<style scoped>
.wrap {
  flex: 1;
  overflow-y: auto;
}
.welcome {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--text-secondary);
}
.welcome-logo {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--accent);
  color: var(--accent-fg);
  font-weight: 700;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.welcome h1 {
  font-size: 22px;
  color: var(--text);
}
.welcome p {
  font-size: 14px;
}
.messages {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 20px;
}
.msg {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}
.avatar {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.avatar.assistant {
  background: var(--accent);
  color: var(--accent-fg);
}
.avatar.user {
  background: var(--bg-avatar-user);
  color: var(--text-strong);
}
.body {
  min-width: 0;
  flex: 1;
  padding-top: 6px;
  line-height: 1.7;
  font-size: 15px;
  color: var(--text);
}
.msg.user .body {
  background: var(--bg-soft);
  border-radius: 12px;
  padding: 10px 14px;
  flex: 0 1 auto;
}
.cursor {
  display: inline-block;
  width: 8px;
  height: 16px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--accent);
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink {
  to {
    visibility: hidden;
  }
}

/* markdown 基础样式 */
.markdown :deep(p) {
  margin: 0 0 10px;
}
.markdown :deep(pre) {
  background: var(--bg-code);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  margin: 0 0 10px;
}
.markdown :deep(code) {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  background: var(--bg-soft);
  padding: 1px 5px;
  border-radius: 4px;
  color: var(--text);
}
.markdown :deep(pre code) {
  background: none;
  padding: 0;
}
.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3) {
  margin: 16px 0 8px;
}
.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 22px;
  margin: 0 0 10px;
}
.markdown :deep(blockquote) {
  border-left: 3px solid var(--border-strong);
  padding-left: 12px;
  color: var(--text-secondary);
  margin: 0 0 10px;
}
.markdown :deep(table) {
  border-collapse: collapse;
  margin: 0 0 10px;
}
.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
}
.markdown :deep(a) {
  color: var(--accent);
}
</style>
