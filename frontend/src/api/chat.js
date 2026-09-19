// 调用后端 /chat 接口（SSE 流式），逐段回调 onChunk，结束时 resolve 完整文本
// conversationId：会话 id（后端多轮记忆键）；mode：'think' 深度思考 / 'quick' 快速回答
export async function streamChat(message, conversationId, mode, { onChunk, signal } = {}) {
  const response = await fetch('/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, conversationId, mode }),
    signal
  })

  if (!response.ok || !response.body) {
    throw new Error(`请求失败：HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let fullText = ''

  // 解析 SSE 事件（后端输出格式：data:<内容>\n\n）
  const drain = () => {
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const dataLines = []
      for (const line of raw.split('\n')) {
        if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^ /, ''))
        }
      }
      if (dataLines.length) {
        const text = dataLines.join('\n')
        fullText += text
        if (onChunk) onChunk(text, fullText)
      }
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    drain()
  }
  buffer += decoder.decode()
  drain()

  return fullText
}

// 让 AI 概括对话内容，生成一个短标题
export async function fetchTitle(text) {
  try {
    const r = await fetch('/chat/title', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text })
    })
    if (!r.ok) return '新对话'
    const data = await r.json()
    return (data && data.title) || '新对话'
  } catch (e) {
    return '新对话'
  }
}
