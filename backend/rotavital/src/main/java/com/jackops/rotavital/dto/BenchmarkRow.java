package com.jackops.rotavital.dto;

import com.jackops.rotavital.model.ProcessingMode;

/**
 * Uma linha da tabela de medições. Speedup = tempo médio sequencial / tempo médio desta versão.
 * sameResultAsSequential indica se o relatório é idêntico ao sequencial (false denunciaria race condition).
 */
public record BenchmarkRow(
        int inputSize,
        ProcessingMode mode,
        int threads,
        int repetitions,
        double averageMs,
        double minMs,
        double maxMs,
        double speedup,
        double efficiency,
        boolean sameResultAsSequential) {
}
