package com.jackops.rotavital.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.jackops.rotavital.dto.BenchmarkResponse;
import com.jackops.rotavital.dto.BenchmarkRow;
import com.jackops.rotavital.dto.RequestStatisticsReport;
import com.jackops.rotavital.dto.RequestStatisticsResponse;
import com.jackops.rotavital.model.ProcessingMode;
import com.jackops.rotavital.model.RequestHistoryRecord;

/**
 * Relatório estatístico do histórico nacional de requisições (painel consultado o dia inteiro).
 *
 * Sequencial: uma única passada sobre os N registros — O(N) tempo, O(G) memória (G = grupos).
 * Paralelo: os N registros são divididos em T fatias contíguas; cada thread agrega a própria fatia
 * em um acumulador local (O(N/T)) e, no final, os T parciais são combinados (O(T·G)).
 * O trabalho total continua O(N): as threads dividem o tempo de parede, não a complexidade.
 */
@Service
public class RequestStatisticsService {

    public static final int MAX_THREADS = 64;

    private final RequestHistoryDataset dataset;

    public RequestStatisticsService(RequestHistoryDataset dataset) {
        this.dataset = dataset;
    }

    public RequestStatisticsResponse generateReport(ProcessingMode mode, int threads, int size) {
        RequestHistoryRecord[] records = dataset.get(size);
        int effectiveThreads = mode == ProcessingMode.SEQUENTIAL ? 1 : validateThreads(threads);

        long start = System.nanoTime();
        RequestStatisticsReport report = compute(records, mode, effectiveThreads);
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;

        return new RequestStatisticsResponse(mode, effectiveThreads, records.length, round3(elapsedMs), report);
    }

    public RequestStatisticsReport compute(RequestHistoryRecord[] records, ProcessingMode mode, int threads) {
        return switch (mode) {
            case SEQUENTIAL -> computeSequential(records);
            case PLATFORM_THREADS -> computeParallel(records, validateThreads(threads), false);
            case VIRTUAL_THREADS -> computeParallel(records, validateThreads(threads), true);
        };
    }

    public RequestStatisticsReport computeSequential(RequestHistoryRecord[] records) {
        return new RequestStatisticsAccumulator()
                .acceptRange(records, 0, records.length)
                .toReport();
    }

    public RequestStatisticsReport computeParallel(RequestHistoryRecord[] records, int threads, boolean virtual) {
        int n = records.length;
        int sliceSize = (n + threads - 1) / threads;

        try (ExecutorService executor = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(threads)) {

            // 1) cada thread processa a própria fatia [from, to) com um acumulador só dela
            List<Future<RequestStatisticsAccumulator>> partials = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                int from = Math.min(n, t * sliceSize);
                int to = Math.min(n, from + sliceSize);
                partials.add(executor.submit(() -> new RequestStatisticsAccumulator().acceptRange(records, from, to)));
            }

            // 2) agregação ao final: combina os parciais na thread da requisição
            RequestStatisticsAccumulator total = new RequestStatisticsAccumulator();
            for (Future<RequestStatisticsAccumulator> partial : partials) {
                total.merge(partial.get());
            }
            return total.toReport();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Processamento interrompido.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Falha ao processar uma das fatias.", e.getCause());
        }
    }

    /**
     * Mede cada combinação (tamanho × versão × threads) várias vezes, sempre sobre a mesma entrada,
     * e confere se o relatório paralelo é idêntico ao sequencial.
     */
    public BenchmarkResponse benchmark(List<Integer> sizes, List<Integer> threadCounts, int repetitions,
                                       int warmup, boolean includeVirtual) {
        if (repetitions < 1 || repetitions > 100 || warmup < 0 || warmup > 100) {
            throw new IllegalArgumentException("Repetições devem estar entre 1 e 100 e aquecimento entre 0 e 100.");
        }
        threadCounts.forEach(this::validateThreads);

        List<BenchmarkRow> rows = new ArrayList<>();
        for (int size : sizes) {
            RequestHistoryRecord[] records = dataset.get(size);

            RequestStatisticsReport expected = computeSequential(records);
            double[] sequential = measure(records, ProcessingMode.SEQUENTIAL, 1, repetitions, warmup);
            double sequentialAvg = average(sequential);
            rows.add(row(size, ProcessingMode.SEQUENTIAL, 1, sequential, sequentialAvg, true));

            List<ProcessingMode> modes = includeVirtual
                    ? List.of(ProcessingMode.PLATFORM_THREADS, ProcessingMode.VIRTUAL_THREADS)
                    : List.of(ProcessingMode.PLATFORM_THREADS);
            for (ProcessingMode mode : modes) {
                for (int threads : threadCounts) {
                    boolean same = expected.equals(compute(records, mode, threads));
                    double[] times = measure(records, mode, threads, repetitions, warmup);
                    rows.add(row(size, mode, threads, times, sequentialAvg, same));
                }
            }
        }
        return new BenchmarkResponse(Runtime.getRuntime().availableProcessors(),
                Runtime.version().toString(), warmup, rows);
    }

    private double[] measure(RequestHistoryRecord[] records, ProcessingMode mode, int threads,
                             int repetitions, int warmup) {
        for (int i = 0; i < warmup; i++) {
            compute(records, mode, threads);
        }
        double[] times = new double[repetitions];
        for (int i = 0; i < repetitions; i++) {
            long start = System.nanoTime();
            compute(records, mode, threads);
            times[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        return times;
    }

    private static BenchmarkRow row(int size, ProcessingMode mode, int threads, double[] times,
                                    double sequentialAvg, boolean same) {
        double avg = average(times);
        double min = Double.MAX_VALUE;
        double max = 0;
        for (double time : times) {
            min = Math.min(min, time);
            max = Math.max(max, time);
        }
        double speedup = sequentialAvg / avg;
        return new BenchmarkRow(size, mode, threads, times.length, round3(avg), round3(min), round3(max),
                round3(speedup), round3(speedup / threads), same);
    }

    private int validateThreads(int threads) {
        if (threads < 1 || threads > MAX_THREADS) {
            throw new IllegalArgumentException("Número de threads deve estar entre 1 e " + MAX_THREADS + ".");
        }
        return threads;
    }

    private static double average(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
