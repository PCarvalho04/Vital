package com.jackops.rotavital.model;

/**
 * Uma requisição de sangue registrada no histórico nacional.
 * Instantes em epoch-segundos; fulfilledAt = -1 quando a requisição não foi atendida.
 */
public record RequestHistoryRecord(
        int hospitalId,
        BrazilianState state,
        BloodType bloodType,
        RhFactor rhFactor,
        ComponentType componentType,
        int units,
        boolean urgent,
        long requestedAt,
        long fulfilledAt) {

    public static final long NOT_FULFILLED = -1L;

    public boolean isFulfilled() {
        return fulfilledAt != NOT_FULFILLED;
    }
}
