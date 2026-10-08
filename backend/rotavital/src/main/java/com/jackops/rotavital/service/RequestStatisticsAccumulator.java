package com.jackops.rotavital.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import com.jackops.rotavital.dto.GroupStatistics;
import com.jackops.rotavital.dto.HospitalRanking;
import com.jackops.rotavital.dto.RequestStatisticsReport;
import com.jackops.rotavital.model.BloodType;
import com.jackops.rotavital.model.BrazilianState;
import com.jackops.rotavital.model.ComponentType;
import com.jackops.rotavital.model.RequestHistoryRecord;
import com.jackops.rotavital.model.RhFactor;

/**
 * Agregado parcial das estatísticas do histórico de requisições.
 *
 * Cada thread recebe o seu próprio acumulador e processa apenas a sua fatia, sem nenhum estado
 * compartilhado — por isso não há locks nem race conditions. Ao final, os parciais são combinados
 * com {@link #merge(RequestStatisticsAccumulator)}. Todos os contadores são inteiros (long), então
 * a soma é associativa e comutativa: a ordem de combinação não altera o resultado e a resposta
 * paralela é idêntica à sequencial.
 */
public class RequestStatisticsAccumulator {

    static final int BLOOD_GROUPS = BloodType.values().length * RhFactor.values().length;
    static final int COMPONENTS = ComponentType.values().length;
    static final int GROUPS = BrazilianState.values().length * BLOOD_GROUPS * COMPONENTS;

    /** Histograma do tempo de espera em faixas de 5 minutos; a última faixa acumula tudo acima de 10 h. */
    static final int WAIT_BUCKET_SECONDS = 5 * 60;
    static final int WAIT_BUCKETS = 121;

    static final long URGENT_SLA_SECONDS = 60 * 60;
    static final long ROUTINE_SLA_SECONDS = 4 * 60 * 60;

    private static final int TOP_HOSPITALS = 10;

    private final long[] requests = new long[GROUPS];
    private final long[] units = new long[GROUPS];
    private final long[] urgent = new long[GROUPS];
    private final long[] fulfilled = new long[GROUPS];
    private final long[] withinSla = new long[GROUPS];
    private final long[] waitSumSeconds = new long[GROUPS];
    private final long[] waitMinSeconds = new long[GROUPS];
    private final long[] waitMaxSeconds = new long[GROUPS];
    private final long[] waitHistogram = new long[GROUPS * WAIT_BUCKETS];
    private final long[] requestsByHour = new long[24];
    private final long[] unitsByHospital = new long[RequestHistoryDataset.HOSPITAL_COUNT];

    public RequestStatisticsAccumulator() {
        Arrays.fill(waitMinSeconds, Long.MAX_VALUE);
        Arrays.fill(waitMaxSeconds, Long.MIN_VALUE);
    }

    /** Processa a fatia [from, to) do histórico. */
    public RequestStatisticsAccumulator acceptRange(RequestHistoryRecord[] records, int from, int to) {
        for (int i = from; i < to; i++) {
            accept(records[i]);
        }
        return this;
    }

    public void accept(RequestHistoryRecord r) {
        int g = groupIndex(r.state(), r.bloodType(), r.rhFactor(), r.componentType());

        requests[g]++;
        units[g] += r.units();
        if (r.urgent()) {
            urgent[g]++;
        }
        requestsByHour[(int) ((r.requestedAt() % 86_400) / 3_600)]++;
        unitsByHospital[r.hospitalId()] += r.units();

        if (r.isFulfilled()) {
            long wait = r.fulfilledAt() - r.requestedAt();
            fulfilled[g]++;
            waitSumSeconds[g] += wait;
            if (wait < waitMinSeconds[g]) {
                waitMinSeconds[g] = wait;
            }
            if (wait > waitMaxSeconds[g]) {
                waitMaxSeconds[g] = wait;
            }
            if (wait <= (r.urgent() ? URGENT_SLA_SECONDS : ROUTINE_SLA_SECONDS)) {
                withinSla[g]++;
            }
            int bucket = (int) Math.min(wait / WAIT_BUCKET_SECONDS, WAIT_BUCKETS - 1);
            waitHistogram[g * WAIT_BUCKETS + bucket]++;
        }
    }

    /** Combina outro parcial neste (etapa de agregação ao final do processamento paralelo). */
    public RequestStatisticsAccumulator merge(RequestStatisticsAccumulator other) {
        for (int g = 0; g < GROUPS; g++) {
            requests[g] += other.requests[g];
            units[g] += other.units[g];
            urgent[g] += other.urgent[g];
            fulfilled[g] += other.fulfilled[g];
            withinSla[g] += other.withinSla[g];
            waitSumSeconds[g] += other.waitSumSeconds[g];
            waitMinSeconds[g] = Math.min(waitMinSeconds[g], other.waitMinSeconds[g]);
            waitMaxSeconds[g] = Math.max(waitMaxSeconds[g], other.waitMaxSeconds[g]);
        }
        for (int i = 0; i < waitHistogram.length; i++) {
            waitHistogram[i] += other.waitHistogram[i];
        }
        for (int h = 0; h < requestsByHour.length; h++) {
            requestsByHour[h] += other.requestsByHour[h];
        }
        for (int h = 0; h < unitsByHospital.length; h++) {
            unitsByHospital[h] += other.unitsByHospital[h];
        }
        return this;
    }

    public RequestStatisticsReport toReport() {
        BrazilianState[] states = BrazilianState.values();
        BloodType[] types = BloodType.values();
        RhFactor[] rhs = RhFactor.values();
        ComponentType[] components = ComponentType.values();

        List<GroupStatistics> groups = new ArrayList<>();
        long totalRequests = 0;
        long totalUnits = 0;
        long totalUrgent = 0;
        long totalFulfilled = 0;
        long totalWithinSla = 0;
        long totalWaitSeconds = 0;

        for (BrazilianState state : states) {
            for (BloodType type : types) {
                for (RhFactor rh : rhs) {
                    for (ComponentType component : components) {
                        int g = groupIndex(state, type, rh, component);
                        if (requests[g] == 0) {
                            continue;
                        }
                        totalRequests += requests[g];
                        totalUnits += units[g];
                        totalUrgent += urgent[g];
                        totalFulfilled += fulfilled[g];
                        totalWithinSla += withinSla[g];
                        totalWaitSeconds += waitSumSeconds[g];

                        boolean hasWait = fulfilled[g] > 0;
                        groups.add(new GroupStatistics(
                                state.name(),
                                bloodGroupLabel(type, rh),
                                component.name(),
                                requests[g],
                                units[g],
                                urgent[g],
                                fulfilled[g],
                                requests[g] - fulfilled[g],
                                rate(withinSla[g], requests[g]),
                                hasWait ? round2(waitSumSeconds[g] / 60.0 / fulfilled[g]) : 0.0,
                                hasWait ? waitMinSeconds[g] / 60 : 0,
                                hasWait ? waitMaxSeconds[g] / 60 : 0,
                                percentileMinutes(g, 0.50),
                                percentileMinutes(g, 0.90)));
                    }
                }
            }
        }

        List<HospitalRanking> topHospitals = IntStream.range(0, unitsByHospital.length)
                .boxed()
                .sorted(Comparator.<Integer>comparingLong(h -> unitsByHospital[h]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(TOP_HOSPITALS)
                .filter(h -> unitsByHospital[h] > 0)
                .map(h -> new HospitalRanking(h, unitsByHospital[h]))
                .toList();

        return new RequestStatisticsReport(
                totalRequests,
                totalUnits,
                totalUrgent,
                totalFulfilled,
                totalRequests - totalFulfilled,
                rate(totalWithinSla, totalRequests),
                totalFulfilled > 0 ? round2(totalWaitSeconds / 60.0 / totalFulfilled) : 0.0,
                Arrays.stream(requestsByHour).boxed().toList(),
                topHospitals,
                groups);
    }

    /** Limite superior (em minutos) da faixa do histograma onde está o percentil pedido. */
    private int percentileMinutes(int g, double percentile) {
        if (fulfilled[g] == 0) {
            return 0;
        }
        long target = (long) Math.ceil(fulfilled[g] * percentile);
        long cumulative = 0;
        for (int b = 0; b < WAIT_BUCKETS; b++) {
            cumulative += waitHistogram[g * WAIT_BUCKETS + b];
            if (cumulative >= target) {
                return (b + 1) * WAIT_BUCKET_SECONDS / 60;
            }
        }
        return WAIT_BUCKETS * WAIT_BUCKET_SECONDS / 60;
    }

    static int groupIndex(BrazilianState state, BloodType type, RhFactor rh, ComponentType component) {
        int bloodGroup = type.ordinal() * RhFactor.values().length + rh.ordinal();
        return (state.ordinal() * BLOOD_GROUPS + bloodGroup) * COMPONENTS + component.ordinal();
    }

    private static String bloodGroupLabel(BloodType type, RhFactor rh) {
        return type.name() + (rh == RhFactor.POSITIVE ? "+" : "-");
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : round2(100.0 * part / total);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
