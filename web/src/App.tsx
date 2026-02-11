import { HashRouter, Routes, Route } from 'react-router-dom';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ConnectionProvider } from '@/hooks/useConnectionStatus';
import AppLayout from '@/components/layout/AppLayout';
import Dashboard from './pages/Dashboard';
import Volcanoes from './pages/Volcanoes';
import VolcanoDetail from './pages/VolcanoDetail';
import VentDetail from './pages/VentDetail';
import Settings from './pages/Settings';
import Connect from './pages/Connect';

export default function App() {
  return (
    <TooltipProvider>
      <HashRouter>
        <Routes>
          <Route path="/connect" element={<Connect />} />
          <Route
            element={
              <ConnectionProvider>
                <AppLayout />
              </ConnectionProvider>
            }
          >
            <Route path="/" element={<Dashboard />} />
            <Route path="/volcanoes" element={<Volcanoes />} />
            <Route path="/volcanoes/:name" element={<VolcanoDetail />} />
            <Route path="/volcanoes/:name/vents/:ventName" element={<VentDetail />} />
            <Route path="/settings" element={<Settings />} />
          </Route>
        </Routes>
      </HashRouter>
    </TooltipProvider>
  );
}
