import type { ApiResponse } from './types';

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

export async function request<T>(path: string, token: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: { Authorization: `Bearer ${token}`, ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...options.headers },
  });
  const body = await response.json().catch(() => null) as ApiResponse<T> | null;
  if (!response.ok || !body?.success) throw new ApiError(body?.message || `HTTP ${response.status}`, response.status);
  return body.data;
}

export function uploadFile(method: 'POST' | 'PUT', path: string, token: string, file: File, title: string, onProgress: (ratio: number) => void): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, path);
    xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(Math.round(event.loaded / event.total * 100)); };
    xhr.onerror = () => reject(new ApiError('上传连接中断', 0));
    xhr.onload = () => {
      let body: ApiResponse<unknown> | null = null;
      try { body = JSON.parse(xhr.responseText) as ApiResponse<unknown>; } catch { /* non JSON error */ }
      if (xhr.status >= 200 && xhr.status < 300 && body?.success) resolve(body.data);
      else reject(new ApiError(body?.message || `HTTP ${xhr.status}`, xhr.status));
    };
    const form = new FormData();
    form.append('file', file);
    if (title.trim()) form.append('title', title.trim());
    xhr.send(form);
  });
}
