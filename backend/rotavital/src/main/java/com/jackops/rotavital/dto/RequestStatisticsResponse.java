package com.jackops.rotavital.dto;

import com.jackops.rotavital.model.ProcessingMode;

public record RequestStatisticsResponse(
        ProcessingMode mode,
        int threads,
        int inputSize,
        double processingTimeMs,
        RequestStatisticsReport report) {
}
