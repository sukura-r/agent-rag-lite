import { describe, expect, it } from 'vitest';
import { acceptSequencedEvent, EventGapError, parseSseBlock, readSse, type SseEvent } from './stream';

describe('persistent SSE event cursor', () => {
  it('parses a multiline data block and its persisted sequence', () => {
    expect(parseSseBlock('id: 12\nevent: answer\ndata: {"answer":"已找到",\ndata: "citations":[]}')).toEqual({
      id: 12, event: 'answer', data: { answer: '已找到', citations: [] },
    });
  });

  it('ignores replayed events and reconnects from the last contiguous event on a gap', () => {
    const item = (id: number) => ({ id, event: 'token', data: { delta: '字' } });
    expect(acceptSequencedEvent(3, item(3))).toBeNull();
    expect(acceptSequencedEvent(3, item(4))).toBe(4);
    expect(() => acceptSequencedEvent(3, item(5))).toThrow(EventGapError);
  });

  it('reads a CRLF event split between network chunks', async () => {
    const encoder = new TextEncoder();
    const chunks = ['id: 1\r', '\nevent: token\r\ndata: {"delta":"好"}\r', '\n\r\n'];
    const response = new Response(new ReadableStream({
      start(controller) { chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk))); controller.close(); },
    }));
    const items: SseEvent[] = [];
    await readSse(response, item => items.push(item), new AbortController().signal);
    expect(items).toEqual([{ id: 1, event: 'token', data: { delta: '好' } }]);
  });
});
