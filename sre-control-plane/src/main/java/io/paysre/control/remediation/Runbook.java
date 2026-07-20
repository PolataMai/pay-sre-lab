package io.paysre.control.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import io.paysre.control.incident.Incident;
import io.paysre.control.investigation.InvestigationConclusion;

public interface Runbook {

    String name();

    JsonNode execute(Incident incident, InvestigationConclusion conclusion);
}
