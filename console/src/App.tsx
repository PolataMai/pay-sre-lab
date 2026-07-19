import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { IncidentsList } from './pages/IncidentsList';
import { IncidentDetail } from './pages/IncidentDetail';
import { FaultInjection } from './pages/FaultInjection';
import { BenchmarkPage } from './pages/BenchmarkPage';

export function App() {
  return (
    <div className="app-shell">
      <nav className="app-sidebar">
        <h1>PaySRE Lab</h1>
        <NavLink to="/incidents" className={({ isActive }) => (isActive ? 'active' : '')}>
          Incidents
        </NavLink>
        <NavLink to="/faults" className={({ isActive }) => (isActive ? 'active' : '')}>
          Fault injection
        </NavLink>
        <NavLink to="/benchmark" className={({ isActive }) => (isActive ? 'active' : '')}>
          Benchmark
        </NavLink>
      </nav>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<Navigate to="/incidents" replace />} />
          <Route path="/incidents" element={<IncidentsList />} />
          <Route path="/incidents/:incidentId" element={<IncidentDetail />} />
          <Route path="/faults" element={<FaultInjection />} />
          <Route path="/benchmark" element={<BenchmarkPage />} />
          <Route path="*" element={<Navigate to="/incidents" replace />} />
        </Routes>
      </main>
    </div>
  );
}