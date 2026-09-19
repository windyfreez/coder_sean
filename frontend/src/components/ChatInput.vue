<template>
  <div class="input-bar">
    <div class="box">
      <textarea
        ref="taRef"
        v-model="draft"
        rows="1"
        :placeholder="chat.loading ? '正在生成…' : '给 Coder Sean 发送消息'"
        @input="autoResize"
        @keydown.enter.exact.prevent="send"
      ></textarea>

      <!-- 模式切换：发送按钮左侧 -->
      <button
        class="mode-toggle"
        :class="{ deep: chat.isThink }"
        :title="chat.isThink ? '深度思考（点击切换到快速回答）' : '流式对话模式（点击切换到深度思考）'"
        @click="chat.toggleMode()"
      >
        {{ chat.isThink ? '🧠 容我思考片刻（深度思考）' : '⚡ 快点回答我很急（流式对话模式）' }}
      </button>

      <button class="send" :disabled="!canSend" title="发送" @click="send">
        <span class="arrow">↑</span>
      </button>
    </div>
    <div class="hint">
      Enter 发送 · 当前模式：{{ chat.isThink ? '容我思考片刻（深度思考）' : '快点回答我很急（流式对话模式）' }}
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useChatStore } from '../store/chat'

const chat = useChatStore()
const draft = ref('')
const taRef = ref(null)
const canSend = computed(() => !!draft.value.trim() && !chat.loading)

function autoResize() {
  const el = taRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function send() {
  if (!canSend.value) return
  const text = draft.value
  draft.value = ''
  if (taRef.value) taRef.value.style.height = 'auto'
  chat.send(text)
}
</script>

<style scoped>
.input-bar {
  padding: 10px 20px 18px;
  max-width: 800px;
  width: 100%;
  margin: 0 auto;
}
.box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border-strong);
  border-radius: 16px;
  background: var(--bg-input);
  box-shadow: var(--shadow);
  transition: border-color 0.2s ease, background 0.2s ease;
}
.box:focus-within {
  border-color: var(--accent);
}
textarea {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  resize: none;
  font-size: 15px;
  font-family: inherit;
  line-height: 1.5;
  max-height: 200px;
  color: var(--text);
  background: transparent;
}
textarea::placeholder {
  color: var(--text-faint);
}
.mode-toggle {
  flex-shrink: 0;
  align-self: center;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.2;
  padding: 7px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--bg-soft);
  color: var(--text-secondary);
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.mode-toggle:hover {
  background: var(--bg-hover);
  color: var(--text);
}
.mode-toggle.deep {
  border-color: var(--accent);
  color: var(--accent);
  background: transparent;
}
.send {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  align-self: center;
  border-radius: 50%;
  background: var(--accent);
  color: var(--accent-fg);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s;
}
.send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.arrow {
  font-size: 18px;
  line-height: 1;
}
.hint {
  text-align: center;
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 8px;
}
</style>
