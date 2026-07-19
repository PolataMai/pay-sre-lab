export interface Incident {
  incidentId: string;
  status: 'DETECTED' | 'INVESTIGATING' | 'MITIGATION_PROPOSED' | 'NEEDS_HUMAN' | 'MITIGATED' | 'RESOLVED';
  aggregateKey: string;
  affectedService: string;
  signalName: string;
  severity: 'INFO' | 'WARNING' | 'ERROR' | 'CRITICAL';
  detectedAt: string;
  updatedAt: string;
  alertCount: number;
}

export interface EvidenceSummary {
  evidenceId: string;
  evidenceType: string;
  sourceTool: string;
  sourceToolVersion: number;
  collectedAt: string;
  contentPreview?: string;
  contentTruncated?: boolean;
}

export interface EvidenceDetail extends EvidenceSummary {
  sha256: string;
  content: unknown;
}

export interface ToolAudit {
  invocationId: string;
  actor: string;
  action: string;
  target: string | null;
  allowed: boolean;
  reasonCode: string | null;
  occurredAt: string;
  evidenceIds: string[];
}

export interface Conclusion {
  incidentId: string;
  rootCause: string;
  confidence: number;
  evidenceIds: string[];
  affectedPaymentCount: number;
  affectedAmount: { amount: string; currency: string };
  recommendedRunbook: string;
  requiresHumanReview: boolean;
}

export interface RunbookExecution {
  executionId: string;
  incidentId: string;
  runbook: string;
  status: 'PENDING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'REJECTED';
  requestedBy: string;
  approvedBy: string | null;
  result: { synced?: number } | null;
}

export interface FaultRule {
  channel: string;
  type: 'NONE' | 'TIMEOUT_BUT_SUCCESS' | 'TIMEOUT_BUT_FAILED' | 'DECLINE_ALL' | 'CALLBACK_LOST' | 'CALLBACK_DUPLICATED';
  probability: number;
  activeFrom: string;
  activeUntil: string;
  randomSeed: number;
}