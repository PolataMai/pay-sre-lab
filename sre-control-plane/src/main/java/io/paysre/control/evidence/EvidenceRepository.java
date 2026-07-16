package io.paysre.control.evidence;

import java.util.List;
import java.util.Optional;

public interface EvidenceRepository {

    Evidence save(Evidence evidence);

    Optional<Evidence> findById(String evidenceId);

    List<Evidence> findByIncidentId(String incidentId);
}
