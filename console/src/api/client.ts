import type {
  Conclusion,
  EvidenceDetail,
  EvidenceSummary,
  FaultRule,
  Incident,
  RunbookExecution,
  ToolAudit,
} from '../types/api';

const DEFAULT_BASE = '/api';

function baseUrl(): string {
  return (import.meta.env.VITE_CONTROL_PLANE_URL as string | undefined) ?? DEFAULT_BASE;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${baseUrl()}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${method} ${path} → ${response.status}: ${text}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const api = {
  listIncidents(): Promise<Incident[]> {
    return request<Incident[]>('GET', '/incidents');
  },
  getIncident(incidentId: string): Promise<Incident> {
    return request<Incident>('GET', `/incidents/${incidentId}`);
  },
  listEvidence(incidentId: string): Promise<EvidenceSummary[]> {
    return request<EvidenceSummary[]>('GET', `/incidents/${incidentId}/evidence`);
  },
  getEvidence(incidentId: string, evidenceId: string): Promise<EvidenceDetail> {
    return request<EvidenceDetail>(
      'GET',
      `/incidents/${incidentId}/evidence/${evidenceId}`,
    );
  },
  listToolAudits(incidentId: string): Promise<ToolAudit[]> {
    return request<ToolAudit[]>('GET', `/incidents/${incidentId}/tool-audits`);
  },
  getConclusion(incidentId: string): Promise<Conclusion> {
    return request<Conclusion>('GET', `/incidents/${incidentId}/conclusion`);
  },
  proposeRunbook(
    incidentId: string,
    runbook: string,
    requestedBy: string,
  ): Promise<RunbookExecution> {
    return request<RunbookExecution>(
      'POST',
      `/incidents/${incidentId}/runbook-executions`,
      { runbook, requestedBy },
    );
  },
  approveRunbook(
    incidentId: string,
    executionId: string,
    approver: string,
  ): Promise<RunbookExecution> {
    return request<RunbookExecution>(
      'POST',
      `/incidents/${incidentId}/runbook-executions/${executionId}/approval`,
      { approver },
    );
  },
  resolveIncident(incidentId: string, resolvedBy: string): Promise<Incident> {
    return request<Incident>('POST', `/incidents/${incidentId}/resolution`, {
      resolvedBy,
    });
  },
  listFaultRules(): Promise<FaultRule[]> {
    return request<FaultRule[]>('GET', '/channel/faults');
  },
  installFaultRule(rule: FaultRule): Promise<FaultRule> {
    return request<FaultRule>('PUT', `/channel/faults/${rule.channel}`, rule);
  },
};

export type { Conclusion, EvidenceDetail, EvidenceSummary, FaultRule, Incident, RunbookExecution, ToolAudit };