export type ApiResponse<T> = { success: boolean; data: T; message: string };
export type Session = { id: string; title: string; status: string; createdAt: string; updatedAt: string };
export type HistoryMessage = { id: string; sessionId: string; sequence: number; role: 'user' | 'assistant'; content: string; createdAt: string };
export type Citation = { number: number; title: string; content: string };
export type RestoredEvent = { taskId: string; sequence: number; eventType: string; data: Record<string, unknown>; createdAt: string };
export type ChatMessage = { id: string; role: 'user' | 'assistant'; content: string; citations: Citation[]; pending?: boolean };
export type TaskCreated = { taskId: string; sessionId: string; status: string };
export type TaskStatus = { taskId: string; status: string };
export type RunningTask = { taskId: string; sessionId: string; status: string };
export type DocumentView = {
  documentId: string; title: string; status: string; latestVersion: number; activeVersion: number;
  chunkCount: number; filename: string | null; mediaType: string | null; fileSize: number | null;
  sha256: string | null; parseStatus: string; parseTaskStatus: string; indexStatus: string;
  errorCode: string | null; lastError: string | null; updatedAt: string;
};
