import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type {
  Conclusion,
  EvidenceDetail,
  EvidenceSummary,
  Incident,
  RunbookExecution,
  ToolAudit,
} from '../types/api';

export function IncidentDetail() {
  const { incidentId } = useParams<{ incidentId: string }>();
  const [incident, setIncident] = useState<Incident | null>(null);
  const [evidenceIndex, setEvidenceIndex] = useState<EvidenceSummary[] | null>(null);
  const [evidenceById, setEvidenceById] = useState<Record<string, EvidenceDetail>>({});
  const [audits, setAudits] = useState<ToolAudit[] | null>(null);
  const [conclusion, setConclusion] = useState<Conclusion | null>(null);
  const [executions, setExecutions] = useState<RunbookExecution[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [proposer, setProposer] = useState('sre-primary');
  const [approver, setApprover] = useState('sre-secondary');
  const [resolver, setResolver] = useState('sre-primary');
  const [runbookName, setRunbookName] = useState('query-and-sync-unknown-payments');

  useEffect(() => {
    if (!incidentId) {
      return;
    }
    Promise.all([
      api.getIncident(incidentId),
      api.listEvidence(incidentId),
      api.listToolAudits(incidentId),
      api.getConclusion(incidentId).catch((err) => {
        if (err.message.includes('404')) {
          return null;
        }
        throw err;
      }),
    ])
      .then(async ([incidentData, evidence, auditsData, conclusionData]) => {
        setIncident(incidentData);
        setEvidenceIndex(evidence);
        setAudits(auditsData);
        setConclusion(conclusionData);
        const details: Record<string, EvidenceDetail> = {};
        for (const summary of evidence) {
          try {
            details[summary.evidenceId] = await api.getEvidence(
              incidentId,
              summary.evidenceId,
            );
          } catch (err) {
            console.warn('evidence load failed', err);
          }
        }
        setEvidenceById(details);
      })
      .catch((err) => setError(err.message));
  }, [incidentId]);

  async function propose() {
    if (!incidentId) {
      return;
    }
    try {
      const execution = await api.proposeRunbook(incidentId, runbookName, proposer);
      setExecutions((prev) => [...prev, execution]);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function approve(executionId: string) {
    if (!incidentId) {
      return;
    }
    try {
      const execution = await api.approveRunbook(incidentId, executionId, approver);
      setExecutions((prev) =>
        prev.map((existing) => (existing.executionId === executionId ? execution : existing)),
      );
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function resolve() {
    if (!incidentId) {
      return;
    }
    try {
      const updated = await api.resolveIncident(incidentId, resolver);
      setIncident(updated);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (error) {
    return (
      <div className="card">
        <h2>Failed to load incident</h2>
        <p style={{ color: '#b91c1c' }}>{error}</p>
        <Link to="/incidents">Back to incidents</Link>
      </div>
    );
  }

  if (!incident || !evidenceIndex || !audits) {
    return <div className="card">Loading incident…</div>;
  }

  const recommendedRunbook = conclusion?.recommendedRunbook ?? '';
  const isAdvisory = recommendedRunbook === '' || recommendedRunbook === null;

  return (
    <div>
      <div className="card">
        <h2>
          {incident.incidentId}{' '}
          <span className={`pill status-${incident.status}`}>{incident.status}</span>
          {isAdvisory && conclusion && (
            <span className="pill advisory" style={{ marginLeft: '0.4rem' }}>
              ADVISORY
            </span>
          )}
        </h2>
        <p>
          Service: <strong>{incident.affectedService}</strong> · Signal:{' '}
          <strong>{incident.signalName}</strong> · Severity:{' '}
          <strong>{incident.severity}</strong>
        </p>
        <p>
          Detected {formatTimestamp(incident.detectedAt)} · Last updated{' '}
          {formatTimestamp(incident.updatedAt)} · {incident.alertCount} alert(s)
        </p>
        <p>
          <Link to="/incidents">Back to incidents</Link>
        </p>
      </div>

      {conclusion && (
        <div className="card">
          <h3>Investigation conclusion</h3>
          <p>
            Root cause: <code>{conclusion.rootCause}</code> · Confidence:{' '}
            {conclusion.confidence.toFixed(2)} · Requires human review:{' '}
            {conclusion.requiresHumanReview ? 'yes' : 'no'}
          </p>
          <p>
            Affected payments: {conclusion.affectedPaymentCount} · Total amount:{' '}
            {conclusion.affectedAmount.amount} {conclusion.affectedAmount.currency}
          </p>
          <p>
            Recommended runbook:{' '}
            {recommendedRunbook ? <code>{recommendedRunbook}</code> : <em>none (advisory)</em>}
          </p>
        </div>
      )}

      <div className="card">
        <h3>Evidence ({evidenceIndex.length})</h3>
        {evidenceIndex.map((summary) => {
          const detail = evidenceById[summary.evidenceId];
          return (
            <div key={summary.evidenceId} className="evidence">
              <strong>{summary.evidenceType}</strong>{' '}
              <span className="pill" style={{ background: '#e0e7ff', color: '#3730a3' }}>
                {summary.sourceTool} v{summary.sourceToolVersion}
              </span>
              <div className="sha">sha256: {detail?.sha256 ?? '…'}</div>
              {detail?.content != null && (
                <pre
                  style={{
                    fontSize: '0.78rem',
                    background: '#0f172a',
                    color: '#e0f2fe',
                    padding: '0.5rem',
                    borderRadius: 4,
                    overflowX: 'auto',
                  }}
                >
                  {JSON.stringify(detail.content, null, 2)}
                </pre>
              )}
            </div>
          );
        })}
      </div>

      <div className="card">
        <h3>Tool audit trail ({audits.length})</h3>
        {audits.map((audit) => (
          <div key={audit.invocationId} className="tool-audit">
            [{formatTimestamp(audit.occurredAt)}] {audit.actor} → {audit.action}{' '}
            {audit.target ?? ''} · {audit.allowed ? 'allowed' : 'denied'}{' '}
            {audit.reasonCode ? `(${audit.reasonCode})` : ''} · {audit.evidenceIds.length} evidence
          </div>
        ))}
      </div>

      <div className="card">
        <h3>Remediation</h3>
        {isAdvisory ? (
          <>
            <p>
              This root cause is advisory-only. No runbook can safely fix
              it; an on-call must reconcile the config / replay the
              affected payments manually.
            </p>
            <p>
              <button onClick={resolve} disabled={incident.status === 'RESOLVED'}>
                Resolve as advisory
              </button>
            </p>
          </>
        ) : (
          <>
            <label>Runbook</label>
            <input value={runbookName} onChange={(e) => setRunbookName(e.target.value)} />
            <label>Proposer</label>
            <input value={proposer} onChange={(e) => setProposer(e.target.value)} />
            <button onClick={propose}>Propose runbook</button>
            <h4>Pending / completed executions</h4>
            {executions.length === 0 ? (
              <p>No runbook execution yet.</p>
            ) : (
              executions.map((execution) => (
                <div key={execution.executionId} className="evidence">
                  <strong>{execution.runbook}</strong>{' '}
                  <span className={`pill status-${execution.status}`}>
                    {execution.status}
                  </span>
                  <div>
                    Requested by {execution.requestedBy}
                    {execution.approvedBy ? ` · Approved by ${execution.approvedBy}` : ''}
                  </div>
                  {execution.status === 'PENDING_APPROVAL' && (
                    <>
                      <label>Approver</label>
                      <input
                        value={approver}
                        onChange={(e) => setApprover(e.target.value)}
                      />
                      <button onClick={() => approve(execution.executionId)}>
                        Approve (four-eyes)
                      </button>
                    </>
                  )}
                </div>
              ))
            )}
          </>
        )}
        <label>Resolver</label>
        <input value={resolver} onChange={(e) => setResolver(e.target.value)} />
        <button
          className="secondary"
          onClick={resolve}
          disabled={incident.status === 'RESOLVED'}
        >
          Close incident
        </button>
      </div>
    </div>
  );
}

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString();
}