package com.jackops.rotavital.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jackops.rotavital.dto.BenchmarkResponse;
import com.jackops.rotavital.dto.RequestStatisticsResponse;
import com.jackops.rotavital.model.ProcessingMode;
import com.jackops.rotavital.service.RequestHistoryDataset;
import com.jackops.rotavital.service.RequestStatisticsService;

@RestController
@RequestMapping("/api/reports/request-statistics")
public class RequestStatisticsController {

    private final RequestStatisticsService requestStatisticsService;
    private final RequestHistoryDataset dataset;

    public RequestStatisticsController(RequestStatisticsService requestStatisticsService,
                                       RequestHistoryDataset dataset) {
        this.requestStatisticsService = requestStatisticsService;
        this.dataset = dataset;
    }

    /**
     * Gera o relatório estatístico do histórico.
     * Ex.: GET /api/reports/request-statistics?mode=PLATFORM_THREADS&threads=4&size=1000000
     */
    @GetMapping
    public ResponseEntity<RequestStatisticsResponse> report(
            @RequestParam(defaultValue = "SEQUENTIAL") ProcessingMode mode,
            @RequestParam(defaultValue = "1") int threads,
            @RequestParam(defaultValue = "100000") int size) {
        return ResponseEntity.ok(requestStatisticsService.generateReport(mode, threads, size));
    }

    /** Pré-gera (e mantém em memória) o histórico sintético do tamanho pedido. */
    @PostMapping("/dataset")
    public ResponseEntity<Map<String, Object>> generateDataset(@RequestParam int size) {
        long start = System.nanoTime();
        int generated = dataset.get(size).length;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return ResponseEntity.ok(Map.of("size", generated, "generationTimeMs", elapsedMs));
    }

    /**
     * Executa a bateria de medições dentro do servidor.
     * Ex.: GET /api/reports/request-statistics/benchmark?sizes=100000,1000000&threads=2,4,8&repetitions=10
     */
    @GetMapping("/benchmark")
    public ResponseEntity<BenchmarkResponse> benchmark(
            @RequestParam(defaultValue = "100000,1000000") List<Integer> sizes,
            @RequestParam(defaultValue = "2,4,8") List<Integer> threads,
            @RequestParam(defaultValue = "10") int repetitions,
            @RequestParam(defaultValue = "3") int warmup,
            @RequestParam(defaultValue = "true") boolean includeVirtual) {
        return ResponseEntity.ok(requestStatisticsService.benchmark(sizes, threads, repetitions, warmup, includeVirtual));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
