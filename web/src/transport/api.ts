import type { ApiClient, ApiResponse, RequestConfig } from './types';
import { getAuthHeader } from './auth';

let API_BASE = '/v1';

export function setApiBase(serverUrl: string): void {
  API_BASE = serverUrl.replace(/\/+$/, '') + '/v1';
}

export function getApiBase(): string {
  return API_BASE;
}

const httpApi: ApiClient = {
  async get<T = unknown>(url: string, config?: RequestConfig): Promise<ApiResponse<T>> {
    return httpRequest<T>({ url, method: 'GET', ...config });
  },
  async post<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>> {
    return httpRequest<T>({ url, method: 'POST', data, ...config });
  },
  async put<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>> {
    return httpRequest<T>({ url, method: 'PUT', data, ...config });
  },
  async patch<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>> {
    return httpRequest<T>({ url, method: 'PATCH', data, ...config });
  },
  async delete<T = unknown>(url: string, config?: RequestConfig): Promise<ApiResponse<T>> {
    return httpRequest<T>({ url, method: 'DELETE', ...config });
  },
};

async function httpRequest<T>(config: {
  url: string;
  method: string;
  data?: unknown;
  params?: Record<string, string>;
}): Promise<ApiResponse<T>> {
  const method = (config.method || 'GET').toUpperCase();

  let url = API_BASE + (config.url || '/');
  if (config.params) {
    const qs = new URLSearchParams(config.params).toString();
    if (qs) url += (url.includes('?') ? '&' : '?') + qs;
  }

  const headers: Record<string, string> = {};
  const authHeader = getAuthHeader();
  if (authHeader) headers['Authorization'] = authHeader;

  const fetchConfig: RequestInit = { method, headers };

  if (config.data !== undefined && config.data !== null) {
    if (typeof config.data === 'object') {
      headers['Content-Type'] = 'application/json';
      fetchConfig.body = JSON.stringify(config.data);
    } else {
      fetchConfig.body = String(config.data);
    }
  }

  const response = await fetch(url, fetchConfig);
  const contentType = response.headers.get('content-type') || '';
  let data: unknown;
  if (contentType.includes('application/json')) {
    data = await response.json();
  } else {
    data = await response.text();
  }

  return {
    status: response.status,
    data: data as T,
    headers: { 'content-type': contentType },
  };
}

export function getApi(): ApiClient {
  return httpApi;
}

export { httpApi };
