# PaySRE Benchmark — Stub Model Baseline

Generator: BenchmarkReportGenerator v1
Scenarios: 4
Model: StubInvestigationModel (deterministic; see `docs/superpowers/specs/2026-07-17-minimax-investigation-model-design.md` for the real-model counterpart).

## Per-scenario ground truth

| Scenario | Fault | Root cause | Policy | Allowed runbooks | Advisory | Requires review | Evidence count | Remediation |
|---|---|---|---|---|---|---|---|---|
| channel-timeout-but-success-v1 | `TIMEOUT_BUT_SUCCESS` | `CHANNEL_TIMEOUT_RESPONSE_LOST` | ChannelTimeoutResponseLostPolicy | `query-and-sync-unknown-payments` | no | yes | 6 | `runbookExecutionStatus=SUCCEEDED`, `finalPaymentStatus=SUCCESS` |
| channel-timeout-but-failed-v1 | `TIMEOUT_BUT_FAILED` | `CHANNEL_TIMEOUT_RESPONSE_LOST` | ChannelTimeoutResponseLostPolicy | `query-and-sync-unknown-payments` | no | yes | 6 | `runbookExecutionStatus=SUCCEEDED`, `finalPaymentStatus=FAILED` |
| channel-decline-spike-v1 | `DECLINE_ALL` | `CHANNEL_DECLINE_SPIKE` | ChannelDeclineSpikePolicy | [] | yes | yes | 4 | — |
| channel-code-mapping-error-v1 | `NONE` | `CHANNEL_CODE_MAPPING_ERROR` | ChannelCodeMappingErrorPolicy | [] | yes | yes | 4 | — |

## Catalogue policies in use

```
- rootCause=CHANNEL_TIMEOUT_RESPONSE_LOST, allowedRunbooks=[query-and-sync-unknown-payments], requiresHumanReview=true
- rootCause=CHANNEL_DECLINE_SPIKE, allowedRunbooks=[], requiresHumanReview=true
- rootCause=CHANNEL_CODE_MAPPING_ERROR, allowedRunbooks=[], requiresHumanReview=true
```

## Notes

- `Allowed runbooks` is `[]` for advisory scenarios; the
  ActionGuard rejects any Runbook proposal with
  `ADVISORY_NO_RUNBOOK` and the orchestrator transitions
  the incident straight to `MITIGATED` so it can be
  resolved via `POST /api/incidents/{id}/resolution`.
- `Remediation` is `-` for advisory scenarios because no
  remediation block is required (and `remediation` is
  omitted from the YAML).
- Multi-model matrix (Stub × MiniMax × real channel) is added
  by R8 once R3b supplies `MINIMAX_API_KEY`.
