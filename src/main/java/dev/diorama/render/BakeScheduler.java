package dev.diorama.render;

import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Runs CPU jobs and keeps failures distinct from empty, successfully loaded tiles. */
final class BakeScheduler {
    record Result(TileKey key, int generation, TileImage image, RuntimeException failure) {}
    private final Executor executor;
    private final List<CompletableFuture<Void>> jobs = new ArrayList<>();
    private final ConcurrentLinkedQueue<Result> completed = new ConcurrentLinkedQueue<>();

    BakeScheduler(Executor executor) { this.executor = executor; }

    void submit(TileKey key, int generation, Supplier<TileImage> work) {
        this.jobs.removeIf(CompletableFuture::isDone);
        this.jobs.add(CompletableFuture.runAsync(() -> {
            try {
                this.completed.add(new Result(key, generation, work.get().withGeneration(generation), null));
            } catch (RuntimeException failure) {
                this.completed.add(new Result(key, generation, null, failure));
            }
        }, this.executor));
    }

    Result poll() { return this.completed.poll(); }
    CompletableFuture<Void> idle() { return CompletableFuture.allOf(this.jobs.toArray(CompletableFuture[]::new)); }
}
