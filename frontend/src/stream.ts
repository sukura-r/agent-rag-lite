export type SseEvent = { id: number | null; event: string; data: Record<string, unknown> };

export function parseSseBlock(block: string): SseEvent | null {
  let event = 'message';
  let id: number | null = null;
  const data: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('id:')) {
      const value = Number(line.slice(3).trim());
      if (Number.isSafeInteger(value) && value > 0) id = value;
    } else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return null;
  let payload: unknown;
  try { payload = JSON.parse(data.join('\n')); } catch { payload = { message: data.join('\n') }; }
  return { id, event, data: typeof payload === 'object' && payload !== null ? payload as Record<string, unknown> : { value: payload } };
}

export class EventGapError extends Error {
  constructor(readonly expected: number, readonly received: number) { super(`事件序号缺口：期望 ${expected}，收到 ${received}`); }
}

export function acceptSequencedEvent(lastId: number, item: SseEvent): number | null {
  if (item.id === null) return lastId;
  if (item.id <= lastId) return null;
  if (item.id !== lastId + 1) throw new EventGapError(lastId + 1, item.id);
  return item.id;
}

export async function readSse(response: Response, onEvent: (event: SseEvent) => void, signal: AbortSignal): Promise<void> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null;
    throw new Error(body?.message || `订阅失败：HTTP ${response.status}`);
  }
  if (!response.body) throw new Error('浏览器不支持流式响应');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let lastActivity = Date.now();
  const watchdog = setInterval(() => { if (Date.now() - lastActivity > 45000) void reader.cancel('45 秒未收到事件或心跳'); }, 5000);
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) { buffer += decoder.decode(); break; }
      lastActivity = Date.now();
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, '\n');
      let boundary: number;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const item = parseSseBlock(block);
        if (item) onEvent(item);
      }
    }
    if (buffer.trim()) {
      const item = parseSseBlock(buffer);
      if (item) onEvent(item);
    }
  } finally { clearInterval(watchdog); reader.releaseLock(); }
}
