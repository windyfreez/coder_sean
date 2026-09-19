import { ref } from 'vue'

const THEME_KEY = 'coder-sean-theme'
const isDark = ref(false)

// 应用启动时初始化：优先取本地存储，其次跟随系统偏好
export function initTheme() {
  const saved = localStorage.getItem(THEME_KEY)
  const prefersDark =
    window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
  isDark.value = saved ? saved === 'dark' : prefersDark
  applyTheme()
}

function applyTheme() {
  document.documentElement.dataset.theme = isDark.value ? 'dark' : 'light'
}

export function toggleTheme() {
  isDark.value = !isDark.value
  localStorage.setItem(THEME_KEY, isDark.value ? 'dark' : 'light')
  applyTheme()
}

export function useTheme() {
  return { isDark, toggleTheme }
}
