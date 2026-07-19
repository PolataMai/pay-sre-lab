import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Incident } from '../types/api';

export function IncidentsList() {
  const [incidents, setIncidents] = useState<Incident[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listIncidents()
      .then((data) => setIncidents(data))
      .catch((err) => setError(err.message));
  }, []);

  if (error) {
    return (
      <div className="card">
        <h2>Failed to load incidents</h2>
        <p style={{ color: '#b91c1c' }}>{error}</p>
        <p>The console talks to <code>VITE_CONTROL_PLANE_URL</code> (default
          <code> /api</code>). Make sure sre-control-plane is up.</p>
      </div>
    );
  }

  if (incidents === null) {
    return <div className="card">Loading incidents…</div>;
  }

  if (incidents.length === 0) {
    return (
      <div className="card">
        <h2>No incidents</h2>
        <p>The control plane has no open incidents. Trigger one by
          installing a fault rule and replaying a fault scenario.</p>
      </div>
    );
  }

  return (
    <div className="card">
      <h2>Incidents ({incidents.length})</h2>
      <table>
        <thead>
          <tr>
            <th>Incident</th>
            <th>Service</th>
            <th>Signal</th>
            <th>Severity</th>
            <th>Status</th>
            <th>Detected</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((incident) => (
            <tr key={incident.incidentId}>
              <td>
                <Link to={`/incidents/${incident.incidentId}`}>{incident.incidentId}</Link>
              </td>
              <td>{incident.affectedService}</td>
              <td>{incident.signalName}</td>
              <td>{incident.severity}</td>
              <td>
                <span className={`pill status-${incident.status}`}>{incident.status}</span>
              </td>
              <td>{formatTimestamp(incident.detectedAt)}</td>
              <td>{formatTimestamp(incident.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString();
}