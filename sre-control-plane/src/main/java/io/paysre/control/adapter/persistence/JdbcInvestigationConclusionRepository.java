package io.paysre.control.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.control.investigation.InvestigationConclusion;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import io.paysre.control.investigation.RootCauseCode;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvestigationConclusionRepository
        implements InvestigationConclusionRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcInvestigationConclusionRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public InvestigationConclusion save(InvestigationConclusion conclusion) {
        jdbc.update("""
                        insert into investigation_conclusion (
                            incident_id, root_cause, confidence, evidence_ids,
                            affected_payment_count, affected_amount, currency,
                            recommended_runbook, requires_human_review, created_at)
                        values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                        """,
                conclusion.incidentId(),
                conclusion.rootCause().name(),
                conclusion.confidence(),
                serialize(conclusion.evidenceIds()),
                conclusion.affectedPaymentCount(),
                conclusion.affectedAmount().amount(),
                conclusion.affectedAmount().currency().getCurrencyCode(),
                conclusion.recommendedRunbook(),
                conclusion.requiresHumanReview(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return conclusion;
    }

    @Override
    public Optional<InvestigationConclusion> findByIncidentId(String incidentId) {
        return jdbc.query("""
                        select incident_id, root_cause, confidence, evidence_ids,
                               affected_payment_count, affected_amount, currency,
                               recommended_runbook, requires_human_review
                          from investigation_conclusion
                         where incident_id = ?
                        """,
                        (resultSet, rowNumber) -> {
                            var currency = Currency.getInstance(resultSet.getString("currency").trim());
                            var amount = resultSet.getBigDecimal("affected_amount")
                                    .setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
                            return new InvestigationConclusion(
                                    resultSet.getString("incident_id"),
                                    RootCauseCode.valueOf(resultSet.getString("root_cause")),
                                    resultSet.getBigDecimal("confidence"),
                                    deserialize(resultSet.getString("evidence_ids")),
                                    resultSet.getLong("affected_payment_count"),
                                    new Money(amount, currency),
                                    resultSet.getString("recommended_runbook"),
                                    resultSet.getBoolean("requires_human_review"));
                        },
                        incidentId)
                .stream()
                .findFirst();
    }

    private String serialize(List<String> evidenceIds) {
        try {
            return objectMapper.writeValueAsString(evidenceIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence IDs", exception);
        }
    }

    private List<String> deserialize(String value) {
        try {
            var tree = objectMapper.readTree(value);
            if (tree.isTextual()) {
                return List.copyOf(objectMapper.readValue(tree.asText(), STRING_LIST));
            }
            return List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid conclusion evidence IDs", exception);
        }
    }
}
