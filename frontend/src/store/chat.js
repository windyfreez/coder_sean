import { defineStore } from 'pinia'
import { streamChat, fetchTitle } from '../api/chat'

const STORAGE_KEY = 'coder-sean-conversations'
const MODE_KEY = 'coder-sean-mode'

function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversations: [], // { id, title, createdAt, messages: [{ role, content, streaming }] }
    activeId: null,
    loading: false,
    // 对话模式：'quick' 快点回答我很急（流式对话模式） | 'think' 容我思考片刻（深度思考）
    mode: 'quick'
  }),

  getters: {
    activeConversation(state) {
      return state.conversations.find((c) => c.id === state.activeId) || null
    },
    isThink(state) {
      return state.mode === 'think'
    }
  },

  actions: {
    init() {
      try {
        const saved = localStorage.getItem(STORAGE_KEY)
        if (saved) {
          const list = JSON.parse(saved)
          this.conversations = list
          this.activeId = list.length ? list[0].id : null
        }
      } catch (e) {
        this.conversations = []
      }
      const m = localStorage.getItem(MODE_KEY)
      if (m === 'think' || m === 'quick') this.mode = m
    },

    persist() {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(this.conversations))
      } catch (e) {
        /* ignore */
      }
    },

    toggleMode() {
      this.mode = this.mode === 'think' ? 'quick' : 'think'
      try {
        localStorage.setItem(MODE_KEY, this.mode)
      } catch (e) {
        /* ignore */
      }
    },

    newChat() {
      const conv = { id: uid(), title: '新对话', createdAt: Date.now(), messages: [] }
      this.conversations.unshift(conv)
      this.activeId = conv.id
      this.persist()
    },

    selectChat(id) {
      this.activeId = id
    },

    deleteChat(id) {
      this.conversations = this.conversations.filter((c) => c.id !== id)
      if (this.activeId === id) {
        this.activeId = this.conversations.length ? this.conversations[0].id : null
      }
      this.persist()
    },

    async send(message) {
      const text = message.trim()
      if (!text || this.loading) return

      let conv = this.activeConversation
      if (!conv) {
        this.newChat()
        conv = this.activeConversation
      }

      conv.messages.push({ role: 'user', content: text })

      conv.messages.push({ role: 'assistant', content: '', streaming: true })
      this.persist()

      // 从响应式数组中重新取引用再修改：
      // 若直接持有 push 前的裸对象改 content，不会触发 Vue 响应式，页面不更新
      const assistantMsg = conv.messages[conv.messages.length - 1]

      this.loading = true
      try {
        // 带上会话 id（多轮记忆键）与当前模式（think/quick）
        await streamChat(text, conv.id, this.mode, {
          onChunk: (_chunk, fullText) => {
            // 只更新响应式内容以呈现打字机效果，结束时统一持久化，避免逐段写 localStorage 卡顿
            assistantMsg.content = fullText
          }
        })
      } catch (e) {
        if (!assistantMsg.content) {
          assistantMsg.content = `[请求失败] ${e.message}`
        }
      } finally {
        assistantMsg.streaming = false
        this.loading = false
        this.persist()
      }

      // 首轮对话结束后，让 AI 概括会话主题、作为标题（DeepSeek 风格）
      if (conv.messages.length === 2 && conv.title === '新对话') {
        const summaryInput = conv.messages
          .map((m) => (m.role === 'user' ? '用户：' : '助手：') + (m.content || ''))
          .join('\n')
          .slice(0, 4000)
        const title = await fetchTitle(summaryInput)
        if (title && title !== '新对话' && conv.title === '新对话') {
          conv.title = title
          this.persist()
        }
      }
    }
  }
})
