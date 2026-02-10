import type { ApiClient, ApiResponse, RequestConfig } from './types';
import { getAuthHeader } from './auth';
import { WebRTCTransport } from './webrtc-transport';
import { WebRTCAdapter } from './webrtc-adapter';

let API_BASE = '/api';

export function setHttpApiBase(base: string): void {
  API_BASE = base;
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

let _webrtcTransport: WebRTCTransport | null = null;
let _webrtcAdapter: WebRTCAdapter | null = null;
let _webrtcConnecting = false;
let _webrtcFailed = false;

export async function connectWebRTC(signalingUrl?: string): Promise<WebRTCAdapter | null> {
  if (_webrtcAdapter && _webrtcTransport && _webrtcTransport.connected) {
    return _webrtcAdapter;
  }
  if (_webrtcFailed || _webrtcConnecting) return null;

  _webrtcConnecting = true;
  try {
    const url = signalingUrl || (window.location.origin + API_BASE + '/webrtc/offer');
    _webrtcTransport = new WebRTCTransport(url);
    await _webrtcTransport.connect();
    _webrtcAdapter = new WebRTCAdapter(_webrtcTransport);
    _webrtcConnecting = false;
    return _webrtcAdapter;
  } catch (e) {
    console.warn('[Typhon] WebRTC connection failed:', (e as Error).message);
    _webrtcFailed = true;
    _webrtcConnecting = false;
    return null;
  }
}

export function resetWebRTCState(): void {
  if (_webrtcTransport) {
    _webrtcTransport.disconnect();
    _webrtcTransport = null;
  }
  _webrtcAdapter = null;
  _webrtcConnecting = false;
  _webrtcFailed = false;
}

/**
 * Server-offers-first: process a compressed offer from the server.
 * Returns the transport and compressed answer string for the user to copy.
 */
export async function processServerOffer(compressedOffer: string, stunServer?: string): Promise<{ transport: WebRTCTransport; answer: string }> {
  resetWebRTCState();
  const transport = new WebRTCTransport('', stunServer);
  const answer = await transport.acceptOffer(compressedOffer);
  return { transport, answer };
}

/**
 * After processServerOffer(), wait for the DataChannel to open.
 * Registers the transport as the active WebRTC connection.
 */
export async function finalizeServerOffer(transport: WebRTCTransport): Promise<void> {
  await transport.waitForConnection();
  _webrtcTransport = transport;
  _webrtcAdapter = new WebRTCAdapter(transport);
}

export function isWebRTCActive(): boolean {
  return !!(_webrtcAdapter && _webrtcTransport && _webrtcTransport.connected);
}

export function getApi(): ApiClient {
  if (_webrtcAdapter && _webrtcTransport && _webrtcTransport.connected) {
    return _webrtcAdapter;
  }
  return httpApi;
}

export { httpApi };
