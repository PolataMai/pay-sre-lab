package io.paysre.control.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public sealed interface InvestigationDecision {

    record CallTool(String toolName, JsonNode arguments) implements InvestigationDecision {
        public CallTool {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
            arguments = arguments.deepCopy();
        }
    }

    record Conclude(InvestigationConclusion conclusion) implements InvestigationDecision {
        public Conclude {
            Objects.requireNonNull(conclusion, "conclusion");
        }
    }

    record Escalate(String reason) implements InvestigationDecision {
        public Escalate {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
