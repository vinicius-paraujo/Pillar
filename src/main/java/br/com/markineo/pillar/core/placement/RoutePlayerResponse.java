package br.com.markineo.pillar.core.placement;

// Carries the outcome name, not the object: RouteOutcome is a constant with identity,
// and a reflective codec would rebuild it into a new instance that == no longer matches.
public record RoutePlayerResponse(String outcome) {

    public static RoutePlayerResponse of(RouteOutcome outcome) {
        return new RoutePlayerResponse(outcome.name());
    }
}
