<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { RouterLink, RouterView } from 'vue-router';
import { useWorkspaceStore } from './stores/workspace';

const workspace = useWorkspaceStore();
const bootError = ref('');
const health = ref<'checking' | 'up' | 'down'>('checking');
let healthTimer: ReturnType<typeof setInterval> | null = null;

async function checkHealth() {
  try {
    const response = await fetch('/api/system/health');
    const body = await response.json() as { success?: boolean; data?: { application?: string } };
    health.value = response.ok && body.success && body.data?.application === 'UP' ? 'up' : 'down';
  } catch { health.value = 'down'; }
}

onMounted(async () => {
  void checkHealth();
  healthTimer = setInterval(() => void checkHealth(), 10000);
  try { await workspace.resume(); }
  catch (cause) { bootError.value = cause instanceof Error ? cause.message : '工作台初始化失败'; }
});
onUnmounted(() => { workspace.dispose(); if (healthTimer) clearInterval(healthTimer); });

</script>

<template>
  <div class="shell" :class="{ 'shell-chat': $route.path === '/' }">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">知</span><span><strong>知衡 Agent</strong><small>企业知识工作台</small></span></div>
      <nav class="nav" aria-label="主导航">
        <RouterLink to="/" class="nav-link">✦ <span>知识问答</span></RouterLink>
        <RouterLink to="/documents" class="nav-link">▣ <span>文档管理</span></RouterLink>
      </nav>
    </aside>
    <div class="workspace">
      <header class="topbar"><div><span class="eyebrow">ZHiheng / Knowledge Operations</span><h1>让企业知识有据可循</h1></div><span class="top-indicator" :aria-label="health === 'up' ? 'Java 服务可用' : health === 'down' ? 'Java 服务不可用' : '正在检查 Java 服务'">{{ health === 'up' ? '● Java 服务可用' : health === 'down' ? '○ Java 服务不可用' : '◌ 检查服务中' }}</span></header>
      <p v-if="bootError" class="alert error" role="alert">初始化失败：{{ bootError }}</p>
      <RouterView />
    </div>
  </div>
</template>
