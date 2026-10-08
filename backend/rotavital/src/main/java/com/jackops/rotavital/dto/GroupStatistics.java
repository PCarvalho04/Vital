package com.jackops.rotavital.dto;

/** Estatísticas de um grupo UF × tipo sanguíneo × hemocomponente. Tempos em minutos. */
public record GroupStatistics(
        String state,
        String bloodGroup,
        String componentType,
        long requests,
        long units,
        long urgentRequests,
        long fulfilledRequests,
        long unfulfilledRequests,
        double slaComplianceRate,
        double averageWaitMinutes,
        long minWaitMinutes,
        long maxWaitMinutes,
        int p50WaitMinutes,
        int p90WaitMinutes) {
}
