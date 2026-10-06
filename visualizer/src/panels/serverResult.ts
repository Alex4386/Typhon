import type { ConfigResult } from '../protocol/messages';
import { useStore } from '../store/store';

/**
 * Shows what the server did, in its words; when it asks to confirm (a reset), shows its description
 * and re-sends with the token through {@code retry}.
 */
export function showServerResult(r: ConfigResult, retry: (token: string) => Promise<ConfigResult>, done?: (r: ConfigResult) => void, failed?: () => void) {
  const s = useStore.getState();
  if (r.needsConfirmation && r.token) {
    const token = r.token;
    s.set({
      configPrompt: {
        result: r,
        confirm: () => void retry(token).then((x) => showServerResult(x, retry, done, failed)),
        cancel: () => failed?.(),
      },
    });
    return;
  }
  if (!r.ok) {
    for (const e of r.errors ?? []) s.toast(`${e.path}: ${e.message}`, 'alert');
    failed?.();
    return;
  }
  const c = r.consequences?.[0];
  if (c) s.toast(c.message, r.applied === 'reinit' ? 'warn' : 'info');
  if (r.note) s.toast(r.note, 'warn');
  for (const w of r.warnings ?? []) s.toast(w, 'warn');
  done?.(r);
}
