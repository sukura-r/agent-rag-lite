<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { request, uploadFile } from '../api';
import { useWorkspaceStore } from '../stores/workspace';
import type { DocumentView } from '../types';

const workspace = useWorkspaceStore();
const tokenDraft = ref(workspace.adminToken);
const documents = ref<DocumentView[]>([]);
const page = ref(0);
const total = ref(0);
const pageSize = 8;
const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const versionInput = ref<HTMLInputElement | null>(null);
const versionTarget = ref<DocumentView | null>(null);
const versionUploading = ref(false);
const versionProgress = ref(0);
const title = ref('');
const uploading = ref(false);
const progress = ref(0);
const error = ref('');
const notice = ref('');
const loading = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

function statusLabel(document: DocumentView): string {
  const parse = document.parseStatus;
  const parseTask = document.parseTaskStatus;
  const index = document.indexStatus;
  if (document.status === 'DELETING') return '删除中';
  if ((index === 'READY' || document.status === 'READY') && document.chunkCount === 0) return '无可检索内容，请上传新版本';
  let latest: string;
  if (parse === 'OCR_REQUIRED') latest = '需要 OCR';
  else if (parse === 'UNSUPPORTED') latest = '格式不支持';
  else if (parse === 'FAILED' || parseTask === 'DEAD' || index === 'DEAD') latest = '处理失败';
  else if (parse === 'RETRY_WAIT' || parseTask === 'RETRY_WAIT' || index === 'FAILED') latest = '等待重试';
  else if (parseTask === 'PARSING') latest = '正在解析';
  else if (parse === 'PARSED' && index !== 'READY') latest = '正在建立索引';
  else if (parse === 'STORED' || parseTask === 'PENDING') latest = '等待解析';
  else if (index === 'READY' || document.status === 'READY') latest = '可检索';
  else latest = '处理中';
  return document.activeVersion > 0 && document.latestVersion > document.activeVersion
    ? `旧版可检索 · 新版${latest}` : latest;
}

async function load(silent = false) {
  if (loading.value) return;
  if (!workspace.adminToken) { documents.value = []; total.value = 0; return; }
  loading.value = true;
  try {
    total.value = await request<number>('/api/knowledge/documents/count', workspace.adminToken);
    page.value = Math.min(page.value, Math.max(0, Math.ceil(total.value / pageSize) - 1));
    documents.value = await request<DocumentView[]>(`/api/knowledge/documents?page=${page.value}&size=${pageSize}`, workspace.adminToken);
    if (!silent) error.value = '';
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '文档列表加载失败'; }
  finally { loading.value = false; }
}

function saveToken() { workspace.setAdminToken(tokenDraft.value); page.value = 0; void load(); }

function changePage(next: number) { page.value = next; void load(); }

async function upload() {
  if (!file.value || !workspace.adminToken || uploading.value || versionUploading.value) return;
  uploading.value = true;
  progress.value = 0;
  error.value = '';
  notice.value = '';
  try {
    await uploadFile('POST', '/api/knowledge/documents/upload', workspace.adminToken, file.value, title.value, ratio => progress.value = ratio);
    notice.value = '文件已接收，解析和索引将在后台继续。';
    file.value = null;
    title.value = '';
    if (fileInput.value) fileInput.value.value = '';
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '上传失败'; }
  finally { uploading.value = false; }
}

function chooseVersion(item: DocumentView) {
  if (!workspace.adminToken || uploading.value || versionUploading.value) return;
  versionTarget.value = item;
  versionInput.value?.click();
}

async function uploadVersion(event: Event) {
  const input = event.target as HTMLInputElement;
  const selected = input.files?.[0];
  const target = versionTarget.value;
  input.value = '';
  if (!selected || !target || !workspace.adminToken || versionUploading.value) return;
  versionUploading.value = true;
  versionProgress.value = 0;
  error.value = '';
  notice.value = '';
  try {
    await uploadFile('PUT', `/api/knowledge/documents/${encodeURIComponent(target.documentId)}/upload`, workspace.adminToken, selected, '', ratio => versionProgress.value = ratio);
    notice.value = `“${target.title}”的新版本已接收，旧版会在新索引就绪前保持可检索。`;
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '新版本上传失败'; }
  finally { versionUploading.value = false; versionTarget.value = null; }
}

async function retry(document: DocumentView) {
  try {
    await request(`/api/knowledge/documents/${encodeURIComponent(document.documentId)}/versions/${document.latestVersion}/parse/retry`, workspace.adminToken, { method: 'POST' });
    notice.value = `已重新提交“${document.title}”的解析任务`;
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重试失败'; }
}

async function remove(item: DocumentView) {
  if (!confirm(`删除文档“${item.title}”及其索引和原件？`)) return;
  error.value = '';
  try {
    await request(`/api/knowledge/documents/${encodeURIComponent(item.documentId)}`, workspace.adminToken, { method: 'DELETE' });
    notice.value = `“${item.title}”已提交删除，索引和原件将在后台清理。`;
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败'; }
}

async function download(item: DocumentView) {
  try {
    const response = await fetch(`/api/knowledge/documents/${encodeURIComponent(item.documentId)}/versions/${item.latestVersion}/source`, { headers: { Authorization: `Bearer ${workspace.adminToken}` } });
    if (!response.ok) throw new Error(`下载失败：HTTP ${response.status}`);
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = item.filename || item.title;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '下载失败'; }
}

onMounted(() => { void load(); timer = setInterval(() => void load(true), 2500); });
onUnmounted(() => { if (timer) clearInterval(timer); });
</script>

<template>
  <main class="document-layout">
    <section class="page-intro"><span class="eyebrow">DOCUMENT OPERATIONS</span><h2>让每份文档进入可检索状态</h2><p>上传后先保存原件，再异步解析、分块与建立索引。状态、失败原因和重试入口都在这里。</p></section>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <div class="document-grid"><section class="card upload-card"><div class="panel-heading"><div><span class="eyebrow">INGESTION</span><h3>上传文档</h3></div><span class="muted">DOCX · PDF · TXT · MD</span></div>
      <label class="field">管理员令牌<div class="token-row"><input v-model="tokenDraft" type="password" autocomplete="off" placeholder="输入管理员令牌" /><button type="button" @click="saveToken">应用</button></div></label>
      <label class="dropzone" @dragover.prevent @drop.prevent="event => { file = event.dataTransfer?.files?.[0] || null }"><span class="upload-symbol">↑</span><strong>{{ file?.name || '选择或拖放文件' }}</strong><small>支持文本型 PDF；最大 20 MiB</small><input ref="fileInput" type="file" accept=".docx,.pdf,.txt,.md" @change="event => { file = (event.target as HTMLInputElement).files?.[0] || null }" /></label>
      <label class="field">文档标题（可选）<input v-model="title" placeholder="默认使用文件名" /></label>
      <div v-if="uploading" class="progress"><div :style="{ width: `${progress}%` }" /></div>
      <button class="primary full" type="button" :disabled="!file || !workspace.adminToken || uploading || versionUploading" @click="upload">{{ uploading ? `上传中 ${progress}%` : '上传并开始解析' }}</button>
    </section>
    <section class="card documents-card"><div class="panel-heading"><div><span class="eyebrow">LIBRARY</span><h3>文档库 <small>{{ total }}</small></h3></div><button class="secondary" type="button" :disabled="loading" @click="load()">刷新状态 ↻</button></div>
      <p v-if="!workspace.adminToken" class="empty-state">填写管理员令牌后显示文档和解析状态。</p>
      <p v-else-if="!documents.length && !loading" class="empty-state">暂无文档，上传一份制度或流程文件开始使用。</p>
      <input ref="versionInput" type="file" accept=".docx,.pdf,.txt,.md" hidden @change="uploadVersion" />
      <div class="document-list"><div v-for="item in documents" :key="item.documentId" class="document-row"><div class="doc-icon">▤</div><div class="doc-main"><strong>{{ item.title }}</strong><small>{{ item.filename || '文本入库' }} · 最新 v{{ item.latestVersion }} · 生效 v{{ item.activeVersion }} · {{ item.chunkCount }} 个分块</small><p v-if="item.lastError" class="doc-error">{{ item.errorCode }} {{ item.lastError }}</p></div><div class="doc-actions"><span class="doc-status" :class="{ bad: ['FAILED','DEAD','UNSUPPORTED','OCR_REQUIRED'].includes(item.parseStatus) || item.indexStatus === 'DEAD' || (item.status === 'READY' && item.chunkCount === 0) }">{{ statusLabel(item) }}</span><button v-if="item.status !== 'DELETING'" type="button" :disabled="uploading || versionUploading" @click="chooseVersion(item)">{{ versionUploading && versionTarget?.documentId === item.documentId ? `上传中 ${versionProgress}%` : '上传新版本' }}</button><button v-if="['DEAD','RETRY_WAIT'].includes(item.parseTaskStatus)" type="button" @click="retry(item)">重试</button><button v-if="item.filename && item.status !== 'DELETING'" type="button" @click="download(item)">原件</button><button v-if="item.status !== 'DELETING'" type="button" class="danger" :disabled="uploading || versionUploading" @click="remove(item)">删除</button></div></div></div>
      <div v-if="total > pageSize" class="document-pagination"><span>第 {{ page + 1 }} / {{ Math.ceil(total / pageSize) }} 页</span><div><button type="button" :disabled="page === 0 || loading" @click="changePage(page - 1)">上一页</button><button type="button" :disabled="(page + 1) * pageSize >= total || loading" @click="changePage(page + 1)">下一页</button></div></div>
    </section></div>
  </main>
</template>
