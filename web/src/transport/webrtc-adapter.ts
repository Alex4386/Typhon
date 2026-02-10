import type { ApiClient, ApiResponse, RequestConfig } from './types';
import type { WebRTCTransport } from './webrtc-transport';

export class WebRTCAdapter implements ApiClient {
  private transport: WebRTCTransport;

  constructor(transport: WebRTCTransport) {
    this.transport = transport;
  }

  private async request<T>(config: {
    url: string;
    method: string;
    data?: unknown;
    params?: Record<string, string>;
  }): Promise<ApiResponse<T>> {
    const method = (config.method || 'GET').toUpperCase();

    let url = config.url || '/';
    if (config.params) {
      const qs = new URLSearchParams(config.params).toString();
      if (qs) url += (url.includes('?') ? '&' : '?') + qs;
    }

    const options: { method: string; json?: unknown; data?: string } = { method };
    if (config.data !== undefined && config.data !== null) {
      if (typeof config.data === 'object') {
        options.json = config.data;
      } else {
        options.data = String(config.data);
      }
    }

    const result = await this.transport.fetch(url, options);

    let data: unknown = result.body;
    if (result.contentType && result.contentType.includes('application/json') && data) {
      try {
        data = JSON.parse(data as string);
      } catch {
        // leave as string
      }
    }

    return {
      status: result.status,
      data: data as T,
      headers: { 'content-type': result.contentType },
    };
  }

  get<T = unknown>(url: string, config?: RequestConfig): Promise<ApiResponse<T>> {
    return this.request<T>({ url, method: 'GET', ...config });
  }

  post<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>> {
    return this.request<T>({ url, method: 'POST', data, ...config });
  }

  put<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>> {
    return this.request<T>({ url, method: 'PUT', data, ...config });
  }

  delete<T = unknown>(url: string, config?: RequestConfig): Promise<ApiResponse<T>> {
    return this.request<T>({ url, method: 'DELETE', ...config });
  }
}
