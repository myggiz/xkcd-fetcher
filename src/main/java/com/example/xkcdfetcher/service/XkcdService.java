package com.example.xkcdfetcher.service;

import com.example.xkcdfetcher.client.XkcdHttpClient;
import com.example.xkcdfetcher.model.XkcdComic;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@CacheConfig(cacheNames = "comics")
public class XkcdService {
    private static final int MAX_CONCURRENT_REQUESTS = 5;  // Balanced concurrency
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(200);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(10);
    private static final int REQUESTS_PER_WINDOW = 30;  // 3 requests per second on average
    private static final Duration REQUEST_DELAY = Duration.ofMillis(50);  // Delay between requests

    private final XkcdHttpClient client;
    private final Map<Integer, XkcdComic> comicCache = new ConcurrentHashMap<>();
    private final io.github.bucket4j.Bucket bucket;
    private final Scheduler rateLimitScheduler = Schedulers.newBoundedElastic(MAX_CONCURRENT_REQUESTS * 2, 1000, "rate-limited");
    private final Semaphore concurrencySemaphore;
    private final AtomicLong lastErrorTime = new AtomicLong(0);
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger(5); // Fixed batch size

    public XkcdService(XkcdHttpClient client) {
        this.client = client;
        this.bucket = createRateLimiter();
        this.concurrencySemaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);

        // Warm up the cache with the latest comic
        getLatestComic()
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                comic -> log.info("Successfully cached latest comic #{}", comic.num()),
                error -> log.error("Failed to cache latest comic", error)
            );
    }



    private void adjustBatchSize(boolean success) {
        if (success) {
            // Keep batch size fixed when successful
            currentBatchSize.set(5);
        } else {
            // Reset to minimum batch size on errors
            currentBatchSize.set(3);
        }
        log.debug("Adjusted batch size to {}", currentBatchSize.get());
    }

    private Bucket createRateLimiter() {
        // More conservative rate limiting
        Refill refill = Refill.intervally(REQUESTS_PER_WINDOW, RATE_LIMIT_WINDOW);
        Bandwidth limit = Bandwidth.classic(REQUESTS_PER_WINDOW, refill)
            .withInitialTokens(REQUESTS_PER_WINDOW);
        return Bucket.builder()
            .addLimit(limit)
            .build();
    }

    private <T> Mono<T> withRateLimit(Mono<T> publisher, String operation) {
        return Mono.defer(() -> {
            if (!concurrencySemaphore.tryAcquire()) {
                log.debug("Concurrency limit reached for {}", operation);
                return Mono.error(new RuntimeException("Too many concurrent requests"));
            }

            return Mono.fromRunnable(() -> {
                if (!bucket.tryConsume(1)) {
                    throw new RuntimeException("Rate limit exceeded for " + operation);
                }
            })
            .then(publisher)
            .doOnError(e -> {
                log.warn("Error in {}: {}", operation, e.getMessage());
                lastErrorTime.set(System.currentTimeMillis());
                consecutiveErrors.incrementAndGet();
                adjustBatchSize(false);
            })
            .doOnSuccess(r -> adjustBatchSize(true))
            .doFinally(signalType -> {
                concurrencySemaphore.release();
                log.trace("Released semaphore for {}", operation);
            })
            .subscribeOn(rateLimitScheduler);
        }).retryWhen(Retry.backoff(3, Duration.ofMillis(100))
            .maxBackoff(Duration.ofSeconds(1))
            .doBeforeRetry(retry -> log.debug("Retrying after failure: {}", retry.failure().getMessage())));
    }

    @Cacheable(key = "'latest'")
    public Mono<XkcdComic> getLatestComic() {
        return withRateLimit(
            client.getLatestComic()
                .retryWhen(reactor.util.retry.Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_RETRY_DELAY)
                    .maxBackoff(Duration.ofSeconds(1))
                    .doBeforeRetry(retry -> log.warn("Retrying latest comic: {}", retry.failure().getMessage()))
                )
                .doOnNext(comic -> {
                    log.info("Fetched latest comic #{}", comic.num());
                    consecutiveErrors.set(0);
                    adjustBatchSize(true);
                })
                .cache(Duration.ofMinutes(5)),
            "getLatestComic"
        );
    }

    @Cacheable(key = "#id", unless = "#result == null")
    private Mono<XkcdComic> withRateLimit(int comicId, Mono<XkcdComic> comicMono) {
        return Mono.defer(() -> {
            try {
                if (!bucket.tryConsume(1)) {
                    log.debug("Rate limit reached, waiting for next window for comic #{}", comicId);
                    return Mono.error(new RuntimeException("Rate limit exceeded for getComicById " + comicId));
                }

                return Mono.usingWhen(
                    Mono.fromCallable(() -> {
                        boolean acquired = concurrencySemaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
                        if (!acquired) {
                            throw new RuntimeException("Failed to acquire semaphore for comic #" + comicId);
                        }
                        return true;
                    })
                    .subscribeOn(rateLimitScheduler)
                    .doOnSuccess(permit -> log.trace("Acquired semaphore for comic #{}", comicId)),

                    permit -> {
                        log.trace("Processing comic #{}", comicId);
                        return comicMono
                            .timeout(Duration.ofSeconds(10))
                            .retryWhen(reactor.util.retry.Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_RETRY_DELAY)
                                .filter(throwable -> !(throwable instanceof RuntimeException 
                                    && throwable.getMessage().contains("Rate limit exceeded")))
                                .doBeforeRetry(retry -> log.debug("Retry attempt {} for comic #{}", 
                                    retry.totalRetries() + 1, comicId)));
                    },

                    permit -> Mono.fromRunnable(() -> {
                        concurrencySemaphore.release();
                        log.trace("Released semaphore for comic #{}", comicId);
                    }),

                    (permit, error) -> Mono.fromRunnable(() -> {
                        concurrencySemaphore.release();
                        log.debug("Released semaphore for comic #{} after error: {}", comicId, error.getMessage());
                    }),

                    permit -> Mono.fromRunnable(() -> {
                        concurrencySemaphore.release();
                        log.trace("Released semaphore for comic #{} after completion", comicId);
                    })
                );
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    public Mono<XkcdComic> getComicById(int id) {
        return Mono.defer(() -> {
            // Check in-memory cache first
            XkcdComic cached = comicCache.get(id);
            if (cached != null) {
                return Mono.just(cached);
            }

            // Fetch from remote with rate limiting and retry
            return withRateLimit(id, client.getComicById(id)
                .retryWhen(reactor.util.retry.Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_RETRY_DELAY)
                    .maxBackoff(Duration.ofSeconds(1))
                    .filter(throwable -> !(throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException.NotFound))
                    .doBeforeRetry(retry -> log.warn("Retrying comic {}: {}", id, retry.failure().getMessage()))
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                        new RuntimeException(String.format("Failed to fetch comic %d after %d retries", id, MAX_RETRY_ATTEMPTS), retrySignal.failure()))
                )
                .doOnNext(comic -> {
                    // Update in-memory cache
                    comicCache.put(id, comic);
                    consecutiveErrors.set(0);
                    adjustBatchSize(true);
                })
            );
        }).cache(Duration.ofHours(1));
    }

    public Flux<XkcdComic> getAllComics() {
        return getLatestComic()
            .flatMapMany(latest -> {
                int latestId = latest.num();
                log.info("Starting to fetch {} comics with concurrency: {}", latestId, MAX_CONCURRENT_REQUESTS);
                
                // Create a flux of comic IDs with prefetch for better performance
                return Flux.range(1, latestId)
                    // Process in parallel with controlled concurrency (fixed at 5)
                    .parallel(5)
                    .runOn(Schedulers.boundedElastic())
                    .flatMap(id -> getComicById(id)
                        .onErrorResume(e -> {
                            log.debug("Skipping comic {}: {}", id, e.getMessage());
                            return Mono.empty();
                        })
                        .timeout(Duration.ofSeconds(3))
                    )
                    .sequential()
                    // Add controlled delay between requests to respect rate limits
                    .delayElements(REQUEST_DELAY)
                    .onBackpressureBuffer(100)
                    .timeout(Duration.ofMinutes(2));
            })
            .retryWhen(Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_RETRY_DELAY)
                .maxBackoff(Duration.ofSeconds(2))
                .jitter(0.3));
    }
    
    @Scheduled(fixedRate = 3600000) // Every hour
    @CacheEvict(allEntries = true)
    public void evictAllCaches() {
        log.info("Evicting all caches");
        comicCache.clear();
    }
}
