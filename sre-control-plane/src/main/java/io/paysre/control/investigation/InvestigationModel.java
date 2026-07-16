package io.paysre.control.investigation;

@FunctionalInterface
public interface InvestigationModel {

    InvestigationDecision decide(InvestigationContext context);
}
