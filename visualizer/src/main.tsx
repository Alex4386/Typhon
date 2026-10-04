import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { useStore } from './store/store';
import './styles.css';

if (import.meta.env.DEV) (window as unknown as { __typhon: typeof useStore }).__typhon = useStore;

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
