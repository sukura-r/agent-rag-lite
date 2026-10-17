import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { request } from '../api';
import { acceptSequencedEvent, readSse, type SseEvent } from '../stream';
import type { ChatMessage, Citation, HistoryMessage, RestoredEvent, RunningTask, Session, TaskCreated, TaskStatus } from '../types';

const ACTIVE_KEY = 'zhiheng-active-task';
type SavedTask = { taskId: string; sessionId: string };
const terminalStatuses = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

function savedTask(): SavedTask | null {
  try { return JSON.parse(localStorage.getItem(ACTIVE_KEY) || 'null') as SavedTask | null; }
  catch { localStorage.removeItem(ACTIVE_KEY); return null; }
}

function asText(value: unknown): string { return typeof value === 'string' ? value : ''; }

export const useWorkspaceStore = defineStore('workspace', () => {
  const userToken = ref(sessionStorage.getItem('zhiheng-user-token') || 'local-user-token');
  const adminToken = ref(sessionStorage.getItem('zhiheng-admin-token') || '');
  const sessionId = ref(localStorage.getItem('zhiheng-session-id') || '');
  const sessions = ref<Session[]>([]);
  const messages = ref<ChatMessage[]>([]);
  const taskId = ref('');
  const taskStatus = ref('IDLE');
  const phase = ref('');
  const error = ref('');
  const connecting = ref(false);
  const busy = computed(() => connecting.value || taskStatus.value === 'RUNNING' || taskStatus.value === 'CANCEL_REQUESTED');
  let streamAbort: AbortController | null = null;
  let lastEventId = 0;
  let currentTask: SavedTask | null = null;
  let generation = 0;

  function setUserToken(value: string) {
    const next = value.trim();
    if (next !== userToken.value) {
      stopSubscription();
      clearActive();
      setSessionId('');
      sessions.value = [];
      messages.value = [];
      taskId.value = '';
      taskStatus.value = 'IDLE';
      phase.value = '';
      error.value = '';
      connecting.value = false;
    }
    userToken.value = next;
    sessionStorage.setItem('zhiheng-user-token', userToken.value);
  }
  function setAdminToken(value: string) {
    adminToken.value = value.trim();
    sessionStorage.setItem('zhiheng-admin-token', adminToken.value);
  }
  function setSessionId(value: string) {
    sessionId.value = value;
    if (value) localStorage.setItem('zhiheng-session-id', value);
    else localStorage.removeItem('zhiheng-session-id');
  }
  function clearActive() {
    currentTask = null;
    localStorage.removeItem(ACTIVE_KEY);
  }
  function stopSubscription() {
    generation++;
    streamAbort?.abort();
    streamAbort = null;
  }
  function dispose() { stopSubscription(); }

  async function loadSessions() {
    sessions.value = await request<Session[]>('/api/conversations', userToken.value);
  }

  async function selectSession(id: string) {
    if (busy.value) throw new Error('请先等待或取消当前任务');
    stopSubscription();
    setSessionId(id);
    messages.value = [];
    error.value = '';
    if (!id) return;
    const [history, events] = await Promise.all([
      request<HistoryMessage[]>(`/api/conversations/${encodeURIComponent(id)}/messages`, userToken.value),
      request<RestoredEvent[]>(`/api/conversations/${encodeURIComponent(id)}/events`, userToken.value),
    ]);
    const answers = events.filter(item => item.eventType === 'answer');
    let answerIndex = 0;
    messages.value = history.map(item => {
      const answer = item.role === 'assistant' ? answers[answerIndex++] : undefined;
      return { id: item.id, role: item.role, content: item.content, citations: (answer?.data.citations || []) as Citation[] };
    });
  }

  async function deleteSession(id: string) {
    if (busy.value) throw new Error('当前任务运行中，不能删除会话');
    await request<string>(`/api/conversations/${encodeURIComponent(id)}`, userToken.value, { method: 'DELETE' });
    if (sessionId.value === id) await selectSession('');
    await loadSessions();
  }

  function applyEvent(item: SseEvent) {
    if (!currentTask) return;
    const next = acceptSequencedEvent(lastEventId, item);
    if (next === null) return;
    lastEventId = next;
    const data = item.data;
    const pending = messages.value.findLast(message => message.role === 'assistant' && message.pending);
    if (item.event === 'status') phase.value = asText(data.phase);
    if (item.event === 'token' && pending) pending.content += asText(data.delta);
    if (item.event === 'answer' && pending) {
      pending.content = asText(data.answer) || pending.content;
      pending.citations = Array.isArray(data.citations) ? data.citations as Citation[] : [];
    }
    if (item.event === 'error') {
      error.value = asText(data.message) || '任务执行失败';
      if (pending) { pending.content ||= error.value; pending.pending = false; }
      taskStatus.value = 'FAILED';
      clearActive();
    }
    if (item.event === 'cancelled') {
      taskStatus.value = 'CANCELLED';
      if (pending) { pending.content ||= '本次回答已取消。'; pending.pending = false; }
      clearActive();
    }
    if (item.event === 'done') {
      taskStatus.value = 'COMPLETED';
      if (pending) pending.pending = false;
      clearActive();
      void loadSessions().catch(() => undefined);
    }
  }

  async function subscribe(): Promise<void> {
    const task = currentTask;
    if (!task) return;
    const mine = ++generation;
    let attempts = 0;
    while (currentTask?.taskId === task.taskId && generation === mine) {
      const controller = new AbortController();
      streamAbort = controller;
      try {
        const response = await fetch(`/api/agent/tasks/${encodeURIComponent(task.taskId)}/events`, {
          headers: { Authorization: `Bearer ${userToken.value}`, 'Last-Event-ID': String(lastEventId) },
          signal: controller.signal,
        });
        await readSse(response, applyEvent, controller.signal);
        attempts = 0;
        if (!currentTask) break;
        phase.value = 'RECONNECTING';
      } catch (cause) {
        controller.abort();
        if (generation !== mine) break;
        error.value = cause instanceof Error ? cause.message : '订阅中断';
        phase.value = 'RECONNECTING';
        attempts++;
        if (attempts >= 5) {
          try {
            const status = await request<TaskStatus>(`/api/agent/tasks/${encodeURIComponent(task.taskId)}`, userToken.value);
            if (terminalStatuses.has(status.status)) {
              taskStatus.value = status.status;
              clearActive();
              await selectSession(task.sessionId);
              break;
            }
          } catch { /* retry after network recovers */ }
        }
      }
      if (currentTask?.taskId !== task.taskId || generation !== mine) break;
      await new Promise(resolve => setTimeout(resolve, Math.min(1000 * 2 ** Math.min(attempts, 3), 8000)));
    }
    streamAbort = null;
  }

  async function send(message: string) {
    const value = message.trim();
    if (!value || busy.value) return;
    error.value = '';
    phase.value = 'CONNECTING';
    connecting.value = true;
    messages.value.push({ id: crypto.randomUUID(), role: 'user', content: value, citations: [] });
    const pending: ChatMessage = { id: crypto.randomUUID(), role: 'assistant', content: '', citations: [], pending: true };
    messages.value.push(pending);
    try {
      const created = await request<TaskCreated>('/api/agent/tasks', userToken.value, {
        method: 'POST', body: JSON.stringify({ sessionId: sessionId.value || null, message: value }),
      });
      taskId.value = created.taskId;
      taskStatus.value = created.status;
      setSessionId(created.sessionId);
      currentTask = { taskId: created.taskId, sessionId: created.sessionId };
      localStorage.setItem(ACTIVE_KEY, JSON.stringify(currentTask));
      lastEventId = 0;
      connecting.value = false;
      await subscribe();
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '发送失败';
      pending.content ||= `请求失败：${error.value}`;
      pending.pending = false;
      taskStatus.value = 'FAILED';
      connecting.value = false;
    }
  }

  async function cancel() {
    if (!taskId.value || !busy.value) return;
    const status = await request<TaskStatus>(`/api/agent/tasks/${encodeURIComponent(taskId.value)}/cancel`, userToken.value, { method: 'POST' });
    taskStatus.value = status.status;
    phase.value = 'CANCELLING';
    if (terminalStatuses.has(status.status)) {
      const pending = messages.value.findLast(message => message.role === 'assistant' && message.pending);
      if (pending) { pending.content ||= '本次回答已取消。'; pending.pending = false; }
      clearActive();
      stopSubscription();
    }
  }

  async function resume() {
    await loadSessions();
    const saved = savedTask();
    if (sessionId.value) {
      try { await selectSession(sessionId.value); }
      catch { setSessionId(''); messages.value = []; }
    }
    let active = saved;
    if (!active) {
      const running = await request<RunningTask[]>('/api/agent/tasks/running', userToken.value);
      if (running.length) active = { taskId: running[0].taskId, sessionId: running[0].sessionId };
    }
    if (!active) return;
    const status = await request<TaskStatus>(`/api/agent/tasks/${encodeURIComponent(active.taskId)}`, userToken.value);
    if (terminalStatuses.has(status.status)) { clearActive(); return; }
    if (sessionId.value !== active.sessionId) await selectSession(active.sessionId);
    taskId.value = active.taskId;
    taskStatus.value = status.status;
    currentTask = active;
    localStorage.setItem(ACTIVE_KEY, JSON.stringify(active));
    lastEventId = 0;
    messages.value.push({ id: `task-${active.taskId}`, role: 'assistant', content: '', citations: [], pending: true });
    void subscribe();
  }

  return { userToken, adminToken, sessionId, sessions, messages, taskId, taskStatus, phase, error, busy,
    setUserToken, setAdminToken, loadSessions, selectSession, deleteSession, send, cancel, resume, dispose };
});
