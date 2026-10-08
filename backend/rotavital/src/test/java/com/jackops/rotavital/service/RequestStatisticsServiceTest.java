package com.jackops.rotavital.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jackops.rotavital.dto.BenchmarkResponse;
import com.jackops.rotavital.dto.GroupStatistics;
import com.jackops.rotavital.dto.RequestStatisticsReport;
import com.jackops.rotavital.model.BloodType;
import com.jackops.rotavital.model.BrazilianState;
import com.jackops.rotavital.model.ComponentType;
import com.jackops.rotavital.model.ProcessingMode;
import com.jackops.rotavital.model.RequestHistoryRecord;
import com.jackops.rotavital.model.RhFactor;

class RequestStatisticsServiceTest {

    private final RequestHistoryDataset dataset = new RequestHistoryDataset();
    private final RequestStatisticsService service = new RequestStatisticsService(dataset);

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, 16})
    void platformThreadsShouldReturnExactlyTheSequentialReport(int threads) {
        RequestHistoryRecord[] records = dataset.get(200_000);
        RequestStatisticsReport expected = service.computeSequential(records);

        assertEquals(expected, service.compute(records, ProcessingMode.PLATFORM_THREADS, threads));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void virtualThreadsShouldReturnExactlyTheSequentialReport(int threads) {
        RequestHistoryRecord[] records = dataset.get(200_000);
        RequestStatisticsReport expected = service.computeSequential(records);

        assertEquals(expected, service.compute(records, ProcessingMode.VIRTUAL_THREADS, threads));
    }

    @Test
    void shouldHandleFewerRecordsThanThreads() {
        RequestHistoryRecord[] records = dataset.get(5);
        RequestStatisticsReport expected = service.computeSequential(records);

        assertEquals(expected, service.compute(records, ProcessingMode.PLATFORM_THREADS, 8));
        assertEquals(5, expected.totalRequests());
    }

    @Test
    void shouldHandleEmptyHistory() {
        RequestStatisticsReport report = service.compute(dataset.get(0), ProcessingMode.PLATFORM_THREADS, 4);

        assertEquals(0, report.totalRequests());
        assertTrue(report.groups().isEmpty());
        assertTrue(report.topHospitalsByUnits().isEmpty());
    }

    @Test
    void shouldAggregateKnownRecordsCorrectly() {
        long t0 = 1_735_689_600L; // 2025-01-01T00:00:00Z
        RequestHistoryRecord[] records = {
                // urgente atendida em 30 min (dentro do SLA de 1 h)
                record(1, true, 2, t0, t0 + 30 * 60),
                // urgente atendida em 90 min (fora do SLA)
                record(1, true, 1, t0 + 3_600, t0 + 3_600 + 90 * 60),
                // rotina não atendida
                record(2, false, 4, t0 + 7_200, RequestHistoryRecord.NOT_FULFILLED),
        };

        RequestStatisticsReport report = service.compute(records, ProcessingMode.PLATFORM_THREADS, 2);

        assertEquals(service.computeSequential(records), report);
        assertEquals(3, report.totalRequests());
        assertEquals(7, report.totalUnits());
        assertEquals(2, report.urgentRequests());
        assertEquals(2, report.fulfilledRequests());
        assertEquals(1, report.unfulfilledRequests());
        assertEquals(33.33, report.slaComplianceRate());
        assertEquals(60.0, report.averageWaitMinutes());
        assertEquals(1L, report.requestsByHourOfDay().get(0));
        assertEquals(1L, report.requestsByHourOfDay().get(1));
        assertEquals(1L, report.requestsByHourOfDay().get(2));
        assertEquals(2, report.topHospitalsByUnits().getFirst().hospitalId());

        GroupStatistics group = report.groups().getFirst();
        assertEquals(1, report.groups().size());
        assertEquals("SP", group.state());
        assertEquals("O-", group.bloodGroup());
        assertEquals(30, group.minWaitMinutes());
        assertEquals(90, group.maxWaitMinutes());
        assertEquals(35, group.p50WaitMinutes()); // faixa [30, 35)
        assertEquals(95, group.p90WaitMinutes()); // faixa [90, 95)
    }

    @Test
    void datasetShouldBeDeterministic() {
        assertEquals(List.of(RequestHistoryDataset.generate(1_000)), List.of(RequestHistoryDataset.generate(1_000)));
    }

    @Test
    void benchmarkShouldReportIdenticalResults() {
        BenchmarkResponse response = service.benchmark(List.of(50_000), List.of(2, 4), 1, 0, true);

        assertEquals(5, response.rows().size());
        assertTrue(response.rows().stream().allMatch(row -> row.sameResultAsSequential()));
    }

    @Test
    void shouldRejectInvalidThreadCount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.generateReport(ProcessingMode.PLATFORM_THREADS, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateReport(ProcessingMode.PLATFORM_THREADS, 65, 10));
    }

    private static RequestHistoryRecord record(int hospitalId, boolean urgent, int units, long requestedAt,
                                               long fulfilledAt) {
        return new RequestHistoryRecord(hospitalId, BrazilianState.SP, BloodType.O, RhFactor.NEGATIVE,
                ComponentType.RED_BLOOD_CELLS, units, urgent, requestedAt, fulfilledAt);
    }
}
