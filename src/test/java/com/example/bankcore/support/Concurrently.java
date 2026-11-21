package com.example.bankcore.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** N 個のタスクをスタートラインを揃えて同時に実行し、全結果を返すテスト用ヘルパー。 */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<T> run(int tasks, IntFunction<T> body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                final int n = i;
                Callable<T> c = () -> {
                    ready.countDown();
                    start.await();
                    return body.apply(n);
                };
                futures.add(pool.submit(c));
            }
            ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS)); // 例外はそのまま伝播させる（デッドロック検出など）
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
