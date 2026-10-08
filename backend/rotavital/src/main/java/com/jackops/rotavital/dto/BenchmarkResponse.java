package com.jackops.rotavital.dto;

import java.util.List;

public record BenchmarkResponse(
        int availableProcessors,
        String javaVersion,
        int warmupRuns,
        List<BenchmarkRow> rows) {
}
