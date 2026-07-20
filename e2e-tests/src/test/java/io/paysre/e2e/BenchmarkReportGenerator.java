package io.paysre.e2e;

import io.paysre.control.investigation.RootCauseCode;
import io.paysre.control.investigation.RootCausePolicy;
import io.paysre.control.investigation.RootCausePolicyCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generates the PaySRE benchmark baseline report from the fault
 * catalog and the root cause policy registry. The report is what the
 * lab currently scores for the Stub investigation model; once a real
 * model adapter is wired in (R3b / R8 multi-model matrix), the same
 * generator can be extended to run each scenario against each model
 * and append columns.
 */
final class BenchmarkReportGenerator {

    private final List<ScenarioRow> rows = new ArrayList<>();
    private final RootCausePolicyCatalog catalog;

    BenchmarkReportGenerator(RootCausePolicyCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    void recordScenario(ScenarioGroundTruth truth) {
        var policy = catalog.forRootCause(parseRootCause(truth));
        rows.add(new ScenarioRow(truth, policy));
    }

    String render(String generatorVersion) {
        var sb = new StringBuilder();
        sb.append("# PaySRE Benchmark — Stub Model Baseline\n\n");
        sb.append("Generator: ").append(generatorVersion).append('\n');
        sb.append("Scenarios: ").append(rows.size()).append('\n');
        sb.append("Model: StubInvestigationModel (deterministic; see ")
                .append("`docs/superpowers/specs/2026-07-17-minimax-investigation-model-design.md` ")
                .append("for the real-model counterpart).\n\n");

        sb.append("## Per-scenario ground truth\n\n");
        sb.append("| Scenario | Fault | Root cause | Policy | Allowed runbooks | ")
                .append("Advisory | Requires review | Evidence count | Remediation |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (var row : rows) {
            sb.append("| ").append(row.scenarioId())
                    .append(" | `").append(row.faultType()).append('`')
                    .append(" | `").append(row.rootCause()).append('`')
                    .append(" | ").append(row.policyClass())
                    .append(" | ").append(row.allowedRunbooks())
                    .append(" | ").append(row.advisory() ? "yes" : "no")
                    .append(" | ").append(row.requiresReview() ? "yes" : "no")
                    .append(" | ").append(row.evidenceCount())
                    .append(" | ").append(row.remediation())
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## Catalogue policies in use\n\n");
        sb.append("```\n").append(catalog.describe()).append("\n```\n\n");

        sb.append("## Notes\n\n");
        sb.append("- `Allowed runbooks` is `[]` for advisory scenarios; the\n")
                .append("  ActionGuard rejects any Runbook proposal with\n")
                .append("  `ADVISORY_NO_RUNBOOK` and the orchestrator transitions\n")
                .append("  the incident straight to `MITIGATED` so it can be\n")
                .append("  resolved via `POST /api/incidents/{id}/resolution`.\n")
                .append("- `Remediation` is `-` for advisory scenarios because no\n")
                .append("  remediation block is required (and `remediation` is\n")
                .append("  omitted from the YAML).\n")
                .append("- Multi-model matrix (Stub × MiniMax × real channel) is added\n")
                .append("  by R8 once R3b supplies `MINIMAX_API_KEY`.\n");
        return sb.toString();
    }

    void writeTo(Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, render("BenchmarkReportGenerator v1"));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write benchmark report", exception);
        }
    }

    private static RootCauseCode parseRootCause(ScenarioGroundTruth truth) {
        var raw = truth.expected().rootCause();
        for (var candidate : RootCauseCode.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "scenario " + truth.id() + " declares unknown root cause: " + raw);
    }

    private record ScenarioRow(
            ScenarioGroundTruth truth,
            RootCausePolicy policy) {

        String scenarioId() {
            return truth.id();
        }

        String faultType() {
            return truth.fault().type();
        }

        String rootCause() {
            return truth.expected().rootCause();
        }

        String policyClass() {
            return policy.getClass().getSimpleName();
        }

        String allowedRunbooks() {
            var runbooks = policy.allowedRunbooks();
            if (runbooks.isEmpty()) {
                return "[]";
            }
            return "`" + String.join("`, `", runbooks) + "`";
        }

        boolean advisory() {
            return truth.expected().advisory();
        }

        boolean requiresReview() {
            return policy.requiresHumanReview();
        }

        String evidenceCount() {
            return String.valueOf(truth.expected().minimumEvidenceCount());
        }

        String remediation() {
            if (truth.expected().advisory()) {
                return "—";
            }
            var r = truth.expected().remediation();
            return "`runbookExecutionStatus=" + r.runbookExecutionStatus()
                    + "`, `finalPaymentStatus=" + r.finalPaymentStatus() + "`";
        }
    }
}