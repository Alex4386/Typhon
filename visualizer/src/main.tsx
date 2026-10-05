import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { getTile, useStore } from './store/store';
import './styles.css';

// Debug hooks for scripts/screenshot*.mjs: always in dev, and in production builds with ?debug.
if (import.meta.env.DEV || new URLSearchParams(window.location.search).has("debug")) {
  (window as unknown as { __typhon: typeof useStore }).__typhon = useStore;
  (window as unknown as { __typhonTile: typeof getTile }).__typhonTile = getTile;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
