import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './style.css'
import { initTheme } from './theme'

// 挂载前初始化主题，避免首次渲染闪色
initTheme()

createApp(App).use(createPinia()).mount('#app')
