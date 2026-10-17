<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import { useWorkspaceStore } from '../stores/workspace';

const workspace = useWorkspaceStore();
const question = ref('');
const notice = ref('');
const messagesEl = ref<HTMLElement | null>(null);
const suggestions = ['年假申请需要提前多久？', '总结员工报销流程', '文档中的测试编号是什么？'];

watch(() => workspace.messages.map(item => item.content).join('|'), async () => {
  await nextTick();
  messagesEl.value?.scrollTo({ top: messagesEl.value.scrollHeight, behavior: 'smooth' });
});

async function submit() {
  const text = question.value.trim();
  if (!text || workspace.busy) return;
  question.value = '';
  notice.value = '';
  await workspace.send(text);
}

async function select(id: string) {
  try { await workspace.selectSession(id); }
  catch (cause) { notice.value = cause instanceof Error ? cause.message : '读取会话失败'; }
}

async function remove(id: string, title: string) {
  if (!confirm(`删除会话“${title}”？数据库仍保留审计记录。`)) return;
  try { await workspace.deleteSession(id); }
  catch (cause) { notice.value = cause instanceof Error ? cause.message : '删除失败'; }
}

async function cancel() {
  try { await workspace.cancel(); }
  catch (cause) { notice.value = cause instanceof Error ? cause.message : '取消失败'; }
}
</script>

<template>
  <main class="chat-layout">
    <section class="history-panel">
      <div class="panel-heading"><div><span class="eyebrow">CONVERSATIONS</span><h2>历史会话</h2></div><button class="icon-button" type="button" aria-label="刷新会话" @click="workspace.loadSessions().catch(() => notice = '刷新会话失败')">↻</button></div>
      <button class="primary full" type="button" :disabled="workspace.busy" @click="select('')">＋ 新建会话</button>
      <p v-if="!workspace.sessions.length" class="muted">还没有历史会话。</p>
      <div class="session-list">
        <div v-for="session in workspace.sessions" :key="session.id" class="session-row" :class="{ selected: workspace.sessionId === session.id }">
          <button type="button" :disabled="workspace.busy" :title="session.title" @click="select(session.id)"><span>◉</span>{{ session.title }}</button>
          <button type="button" class="remove" :disabled="workspace.busy" :aria-label="`删除会话 ${session.title}`" @click="remove(session.id, session.title)">×</button>
        </div>
      </div>
    </section>
    <section class="chat-panel">
      <div class="chat-heading"><div><span class="eyebrow">EVIDENCE FIRST</span><h2>{{ workspace.sessionId ? '继续会话' : '新的知识问答' }}</h2></div><span class="status-pill" :class="workspace.busy ? 'working' : ''">{{ workspace.busy ? (workspace.phase || '处理中') : workspace.taskStatus }}</span></div>
      <p v-if="notice || workspace.error" class="alert error" role="alert">{{ notice || workspace.error }}</p>
      <div ref="messagesEl" class="messages" aria-live="polite">
        <div v-if="!workspace.messages.length" class="welcome"><div class="welcome-icon">✦</div><h3>提问之前，先找到依据。</h3><p>针对企业制度、流程和已导入文档提问，回答会标出证据来源。</p><div class="suggestions"><button v-for="item in suggestions" :key="item" type="button" @click="question = item">{{ item }} ↗</button></div></div>
        <article v-for="message in workspace.messages" :key="message.id" class="message" :class="message.role">
          <div class="message-avatar">{{ message.role === 'user' ? '我' : '知' }}</div>
          <div class="message-body"><div class="message-label">{{ message.role === 'user' ? '你' : '知衡 Agent' }}<span v-if="message.pending" class="muted"> · 正在生成</span></div><div class="message-content">{{ message.content || (message.pending ? '正在检索与组织证据…' : '') }}</div>
            <div v-if="message.citations.length" class="citations"><h4>引用依据</h4><details v-for="citation in message.citations" :key="citation.number"><summary>[{{ citation.number }}] {{ citation.title }}</summary><p>{{ citation.content }}</p></details></div>
          </div>
        </article>
      </div>
      <form class="composer" @submit.prevent="submit"><textarea v-model="question" rows="2" maxlength="2000" aria-label="输入问题" placeholder="输入关于制度、流程或文档的问题…" @keydown.enter.exact.prevent="submit" /><div class="composer-actions"><span>Enter 发送 · Shift + Enter 换行</span><div><button v-if="workspace.busy" class="secondary" type="button" @click="cancel">停止生成</button><button class="primary" type="submit" :disabled="!question.trim() || workspace.busy">发送问题 ↗</button></div></div></form>
    </section>
  </main>
</template>
