package com.jackops.rotavital.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.jackops.rotavital.model.BloodType;
import com.jackops.rotavital.model.BrazilianState;
import com.jackops.rotavital.model.ComponentType;
import com.jackops.rotavital.model.RequestHistoryRecord;
import com.jackops.rotavital.model.RhFactor;

/**
 * Gera e mantém em memória o histórico sintético de requisições na escala nacional.
 * A geração é determinística (semente fixa): o mesmo tamanho sempre produz os mesmos registros,
 * o que permite comparar as versões sequencial e paralela sobre exatamente a mesma entrada.
 */
@Component
public class RequestHistoryDataset {

    public static final int MAX_SIZE = 10_000_000;
    public static final int HOSPITAL_COUNT = 5_000;
    private static final long SEED = 20260101L;

    private static final long PERIOD_START = LocalDateTime.of(2025, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long PERIOD_SECONDS = 365L * 24 * 60 * 60;

    // Distribuição aproximada dos tipos sanguíneos no Brasil: O+, A+, B+, AB+, O-, A-, B-, AB- (em décimos de %)
    private static final BloodType[] TYPES = {BloodType.O, BloodType.A, BloodType.B, BloodType.AB,
            BloodType.O, BloodType.A, BloodType.B, BloodType.AB};
    private static final RhFactor[] RHS = {RhFactor.POSITIVE, RhFactor.POSITIVE, RhFactor.POSITIVE, RhFactor.POSITIVE,
            RhFactor.NEGATIVE, RhFactor.NEGATIVE, RhFactor.NEGATIVE, RhFactor.NEGATIVE};
    private static final int[] TYPE_WEIGHTS = {360, 340, 80, 25, 90, 80, 20, 5};

    private static final ComponentType[] COMPONENTS = ComponentType.values();
    private static final int[] COMPONENT_WEIGHTS = {60, 25, 15};

    private static final BrazilianState[] STATES = BrazilianState.values();

    private final Map<Integer, RequestHistoryRecord[]> cache = new ConcurrentHashMap<>();

    public RequestHistoryRecord[] get(int size) {
        if (size < 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Tamanho deve estar entre 0 e " + MAX_SIZE + ".");
        }
        return cache.computeIfAbsent(size, RequestHistoryDataset::generate);
    }

    public void clear() {
        cache.clear();
    }

    static RequestHistoryRecord[] generate(int size) {
        SplittableRandom random = new SplittableRandom(SEED);
        int[] stateWeights = new int[STATES.length];
        for (int i = 0; i < STATES.length; i++) {
            stateWeights[i] = STATES[i].getPopulationWeight();
        }

        RequestHistoryRecord[] records = new RequestHistoryRecord[size];
        for (int i = 0; i < size; i++) {
            int typeIndex = pick(random, TYPE_WEIGHTS);
            boolean urgent = random.nextInt(100) < 20;
            long requestedAt = PERIOD_START + random.nextLong(PERIOD_SECONDS);

            long fulfilledAt = RequestHistoryRecord.NOT_FULFILLED;
            if (random.nextInt(100) >= 3) {
                // tempo de espera com distribuição exponencial: urgentes ~40 min, rotina ~180 min
                double meanMinutes = urgent ? 40.0 : 180.0;
                long waitSeconds = (long) (-Math.log(1.0 - random.nextDouble()) * meanMinutes * 60);
                fulfilledAt = requestedAt + waitSeconds;
            }

            records[i] = new RequestHistoryRecord(
                    random.nextInt(HOSPITAL_COUNT),
                    STATES[pick(random, stateWeights)],
                    TYPES[typeIndex],
                    RHS[typeIndex],
                    COMPONENTS[pick(random, COMPONENT_WEIGHTS)],
                    1 + random.nextInt(4),
                    urgent,
                    requestedAt,
                    fulfilledAt);
        }
        return records;
    }

    private static int pick(SplittableRandom random, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int value = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            value -= weights[i];
            if (value < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }
}
