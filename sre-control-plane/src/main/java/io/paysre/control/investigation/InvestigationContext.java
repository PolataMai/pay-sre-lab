package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import io.paysre.control.incident.Incident;
import io.paysre.control.tools.ToolResult;
import java.util.List;

public record InvestigationContext(
        Incident incident,
        InvestigationSeed seed,
        List<Evidence> evidence,
        List<ToolResult> toolResults,
        String lastValidationError) {

    public InvestigationContext {
        evidence = List.copyOf(evidence);
        toolResults = List.copyOf(toolResults);
    }
}
