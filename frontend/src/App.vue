<template>
  <div class="app">
    <Sidebar />
    <main class="main">
      <MessageList />
      <ChatInput />
    </main>
  </div>
</template>

<script setup>
import Sidebar from './components/Sidebar.vue'
import MessageList from './components/MessageList.vue'
import ChatInput from './components/ChatInput.vue'
</script>

<style scoped>
.app {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
</style>
