package io.paysre.control.investigation;

import java.util.Optional;

public interface InvestigationConclusionRepository {

    InvestigationConclusion save(InvestigationConclusion conclusion);

    Optional<InvestigationConclusion> findByIncidentId(String incidentId);
}
