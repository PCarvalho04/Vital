package com.jackops.rotavital.dto;

import java.util.List;

/** Relatório estatístico do histórico nacional de requisições. Tempos em minutos, taxas em %. */
public record RequestStatisticsReport(
        long totalRequests,
        long totalUnits,
        long urgentRequests,
        long fulfilledRequests,
        long unfulfilledRequests,
        double slaComplianceRate,
        double averageWaitMinutes,
        List<Long> requestsByHourOfDay,
        List<HospitalRanking> topHospitalsByUnits,
        List<GroupStatistics> groups) {
}
