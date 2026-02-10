import { getAuthHeader } from './auth';
import { compress, decompress } from './sdp-codec';

interface QueueItem {
  url: string;
  options: FetchOptions;
  resolve: (value: TransportResponse) => void;
  reject: (reason: Error) => void;
}

interface FetchOptions {
  method?: string;
  json?: unknown;
  data?: string;
}

export interface TransportResponse {
  status: number;
  contentType: string;
  body: string | null;
}

export class WebRTCTransport {
  private signalingUrl: string;
  private stunServer: string;
  private pc: RTCPeerConnection | null = null;
  private dc: RTCDataChannel | null = null;
  connected = false;
  private connecting = false;

  private _queue: QueueItem[] = [];
  private _busy = false;
  private _currentResolve: ((value: TransportResponse) => void) | null = null;
  private _currentReject: ((reason: Error) => void) | null = null;
  private _headerMsg: Record<string, unknown> | null = null;
  private _pingInterval: ReturnType<typeof setInterval> | null = null;
  private _dcPromise: Promise<RTCDataChannel> | null = null;

  constructor(signalingUrl: string, stunServer: string = 'stun.l.google.com:19302') {
    this.signalingUrl = signalingUrl;
    this.stunServer = stunServer;
  }

  private _iceServers(): RTCIceServer[] {
    return [{ urls: 'stun:' + this.stunServer }];
  }

  async connect(): Promise<void> {
    if (this.connected) return;
    if (this.connecting) {
      return new Promise((resolve, reject) => {
        const check = setInterval(() => {
          if (this.connected) { clearInterval(check); resolve(); }
          if (!this.connecting) { clearInterval(check); reject(new Error('Connection failed')); }
        }, 100);
      });
    }

    this.connecting = true;

    try {
      this.pc = new RTCPeerConnection({
        iceServers: this._iceServers(),
      });

      this.dc = this.pc.createDataChannel('typhon', { ordered: true });
      this._setupDataChannel(this.dc);

      const offer = await this.pc.createOffer();
      await this.pc.setLocalDescription(offer);

      await this._waitForICEGathering();

      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      const authHeader = getAuthHeader();
      if (authHeader) headers['Authorization'] = authHeader;

      const response = await fetch(this.signalingUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify({ sdp: this.pc.localDescription!.sdp, type: 'offer' }),
      });

      if (!response.ok) {
        throw new Error('Signaling failed: ' + response.status);
      }

      const answer = await response.json();
      await this.pc.setRemoteDescription(new RTCSessionDescription(answer));

      await this._waitForDataChannel();

      this.connected = true;
      this.connecting = false;
      this._startPingInterval();
    } catch (e) {
      this.connecting = false;
      this.disconnect();
      throw e;
    }
  }

  fetch(url: string, options: FetchOptions = {}): Promise<TransportResponse> {
    return new Promise((resolve, reject) => {
      this._queue.push({ url, options, resolve, reject });
      this._processQueue();
    });
  }

  /**
   * Server-offers-first: accept a compressed offer from the server.
   * Sets the remote description, creates an answer, gathers ICE candidates,
   * and returns the compressed answer SDP string for the user to copy.
   * The DataChannel is received via ondatachannel (server created it).
   */
  async acceptOffer(compressedOffer: string): Promise<string> {
    if (this.connected || this.connecting) {
      throw new Error('Already connected or connecting');
    }

    this.connecting = true;

    try {
      this.pc = new RTCPeerConnection({
        iceServers: this._iceServers(),
      });

      // Listen for DataChannel from server (server created it)
      this._dcPromise = new Promise<RTCDataChannel>((resolve) => {
        this.pc!.ondatachannel = (event) => {
          resolve(event.channel);
        };
      });

      // Set remote description (server's offer)
      const offerSdp = await decompress(compressedOffer);
      await this.pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: offerSdp }));

      // Create answer
      const answer = await this.pc.createAnswer();
      await this.pc.setLocalDescription(answer);

      await this._waitForICEGathering();

      const answerSdp = this.pc.localDescription!.sdp;
      return await compress(answerSdp);
    } catch (e) {
      this.connecting = false;
      this.disconnect();
      throw e;
    }
  }

  /**
   * After acceptOffer(), wait for the DataChannel to open.
   * Call this after the user has copied the answer back to the server.
   */
  async waitForConnection(): Promise<void> {
    if (!this.pc || !this._dcPromise) {
      throw new Error('No pending connection — call acceptOffer() first');
    }

    try {
      this.dc = await this._dcPromise;
      this._setupDataChannel(this.dc);

      await this._waitForDataChannel();

      this.connected = true;
      this.connecting = false;
      this._startPingInterval();
      this._dcPromise = null;
    } catch (e) {
      this.connecting = false;
      this.disconnect();
      throw e;
    }
  }

  disconnect(): void {
    this._stopPingInterval();

    if (this.dc) {
      try { this.dc.close(); } catch (_) { /* ignore */ }
      this.dc = null;
    }
    if (this.pc) {
      try { this.pc.close(); } catch (_) { /* ignore */ }
      this.pc = null;
    }

    this.connected = false;
    this.connecting = false;

    if (this._currentReject) {
      this._currentReject(new Error('Disconnected'));
      this._currentReject = null;
      this._currentResolve = null;
    }
    while (this._queue.length > 0) {
      const item = this._queue.shift()!;
      item.reject(new Error('Disconnected'));
    }
    this._busy = false;
    this._headerMsg = null;
  }

  private _setupDataChannel(dc: RTCDataChannel): void {
    dc.onmessage = (event) => this._onMessage(event.data);
    dc.onclose = () => {
      this.connected = false;
      this._stopPingInterval();
    };
    dc.onerror = () => {
      if (this._currentReject) {
        this._currentReject(new Error('DataChannel error'));
        this._currentReject = null;
        this._currentResolve = null;
        this._headerMsg = null;
        this._busy = false;
        this._processQueue();
      }
    };
  }

  private _onMessage(data: string): void {
    if (!this._currentResolve) return;

    if (this._headerMsg === null) {
      try {
        this._headerMsg = JSON.parse(data);
      } catch {
        this._currentReject!(new Error('Invalid header JSON: ' + data));
        this._currentReject = null;
        this._currentResolve = null;
        this._headerMsg = null;
        this._busy = false;
        this._processQueue();
        return;
      }

      if ((this._headerMsg as Record<string, unknown>).status === 202) {
        this._headerMsg = null;
        return;
      }

      if ((this._headerMsg as Record<string, unknown>).status === 204 ||
          (this._headerMsg as Record<string, unknown>)['content-length'] === 0) {
        const result: TransportResponse = {
          status: (this._headerMsg as Record<string, unknown>).status as number,
          contentType: ((this._headerMsg as Record<string, unknown>)['content-type'] as string) || '',
          body: null,
        };
        this._currentResolve(result);
        this._currentResolve = null;
        this._currentReject = null;
        this._headerMsg = null;
        this._busy = false;
        this._processQueue();
        return;
      }
    } else {
      const result: TransportResponse = {
        status: (this._headerMsg as Record<string, unknown>).status as number,
        contentType: ((this._headerMsg as Record<string, unknown>)['content-type'] as string) || '',
        body: data,
      };
      this._currentResolve(result);
      this._currentResolve = null;
      this._currentReject = null;
      this._headerMsg = null;
      this._busy = false;
      this._processQueue();
    }
  }

  private _processQueue(): void {
    if (this._busy || this._queue.length === 0) return;
    if (!this.connected || !this.dc || this.dc.readyState !== 'open') return;

    this._busy = true;
    const { url, options, resolve, reject } = this._queue.shift()!;

    this._currentResolve = resolve;
    this._currentReject = reject;
    this._headerMsg = null;

    const request = {
      url,
      method: (options.method || 'GET').toUpperCase(),
      json: options.json || null,
      data: options.data || null,
    };

    try {
      this.dc.send(JSON.stringify(request));
    } catch (e) {
      this._currentReject!(new Error('Send failed: ' + (e as Error).message));
      this._currentResolve = null;
      this._currentReject = null;
      this._busy = false;
      this._processQueue();
    }
  }

  private _waitForICEGathering(): Promise<void> {
    return new Promise((resolve) => {
      if (this.pc!.iceGatheringState === 'complete') {
        resolve();
        return;
      }
      const timeout = setTimeout(() => resolve(), 3000);
      this.pc!.addEventListener('icegatheringstatechange', () => {
        if (this.pc!.iceGatheringState === 'complete') {
          clearTimeout(timeout);
          resolve();
        }
      });
    });
  }

  private _waitForDataChannel(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.dc!.readyState === 'open') {
        resolve();
        return;
      }
      const timeout = setTimeout(() => reject(new Error('DataChannel open timeout')), 10000);
      this.dc!.addEventListener('open', () => {
        clearTimeout(timeout);
        resolve();
      });
      this.dc!.addEventListener('error', () => {
        clearTimeout(timeout);
        reject(new Error('DataChannel failed to open'));
      });
    });
  }

  private _startPingInterval(): void {
    this._stopPingInterval();
    this._pingInterval = setInterval(() => {
      if (this.connected && this.dc && this.dc.readyState === 'open' && !this._busy) {
        this.fetch('ping', { method: 'GET' }).catch(() => {});
      }
    }, 15000);
  }

  private _stopPingInterval(): void {
    if (this._pingInterval) {
      clearInterval(this._pingInterval);
      this._pingInterval = null;
    }
  }
}
