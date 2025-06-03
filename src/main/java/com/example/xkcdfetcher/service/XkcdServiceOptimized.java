package com.example.xkcdfetcher.service;

import com.example.xkcdfetcher.client.XkcdHttpClient;
import com.example.xkcdfetcher.model.XkcdComic;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = "comics")
public class XkcdServiceOptimized {
    private static final int INITIAL_BATCH_SIZE = 50;
    private static final int MAX_BATCH_SIZE = 200;
    private static final int MIN_BATCH_SIZE = 10;
    private static final int MAX_CONCURRENT_BATCHES = 30;
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(200);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(5);
    private static final int RATE_LIMIT_REQUESTS = 30;
    private static final Duration RATE_LIMIT_DURATION = Duration.ofSeconds(1);
    
    private final XkcdHttpClient client;
    private final Map<Integer, XkcdComic> comicCache = new ConcurrentHashMap<>();
    private final Bucket rateLimiter = createRateLimiter();
    private final AtomicInteger currentBatchSize = new AtomicInteger(INITIAL_BATCH_SIZE);
    private final AtomicLong lastErrorTime = new AtomicLong(0);
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    
    @PostConstruct
    public void init() {
        // Warm up the cache with the latest comic
        getLatestComic()
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                comic -> log.info("Successfully cached latest comic #{}", comic.num()),
                error -> log.error("Failed to cache latest comic", error)
            );
    }
    
    @Cacheable(key = "#id", unless = "#result == null")
    public Mono<XkcdComic> getComicById(int id) {
        return Mono.defer(() -> {
            // Check in-memory cache first
            XkcdComic cached = comicCache.get(id);
            if (cached != null) {
                return Mono.just(cached);
            }
            
            // Rate limit the requests
            if (!rateLimiter.tryConsume(1)) {
                return Mono.error(new RuntimeException("Rate limit exceeded"));
            }
            
            // Fetch from remote with retry
            return client.getComicById(id)
                .retryWhen(reactor.util.retry.Retry.backoff(MAX_RETRIES, INITIAL_RETRY_DELAY)
                    .maxBackoff(MAX_RETRY_DELAY)
                    .filter(throwable -> !(throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException.NotFound))
                    .doBeforeRetry(retry -> log.warn("Retrying comic {}: {}", id, retry.failure().getMessage()))
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> 
                        new RuntimeException(String.format("Failed to fetch comic %d after %d retries", id, MAX_RETRIES), retrySignal.failure()))
                )
                .doOnNext(comic -> {
                    // Update in-memory cache
                    comicCache.put(id, comic);
                    consecutiveErrors.set(0);
                })
                .doOnError(e -> {
                    log.error("Error fetching comic {}: {}", id, e.getMessage());
                    lastErrorTime.set(System.currentTimeMillis());
                    consecutiveErrors.incrementAndGet();
                    adjustBatchSize(false);
                });
        }).cache(Duration.ofHours(1));
    }
    
    @Cacheable(key = "'latest'")
    public Mono<XkcdComic> getLatestComic() {
        // Rate limit the requests
        if (!rateLimiter.tryConsume(1)) {
            return Mono.error(new RuntimeException("Rate limit exceeded"));
        }
        
        return client.getLatestComic()
            .retryWhen(reactor.util.retry.Retry.backoff(MAX_RETRIES, INITIAL_RETRY_DELAY)
                .maxBackoff(MAX_RETRY_DELAY)
                .doBeforeRetry(retry -> log.warn("Retrying latest comic: {}", retry.failure().getMessage()))
            )
            .doOnNext(comic -> {
                log.info("Fetched latest comic #{}", comic.num());
                consecutiveErrors.set(0);
            })
            .doOnError(e -> {
                log.error("Error fetching latest comic: {}", e.getMessage());
                lastErrorTime.set(System.currentTimeMillis());
                consecutiveErrors.incrementAndGet();
                adjustBatchSize(false);
            })
            .cache(Duration.ofMinutes(5));
    }
    
    public Flux<XkcdComic> getAllComics() {
        return getLatestComic()
            .flatMapMany(latest -> {
                int latestId = latest.num();
                int batchSize = getAdaptiveBatchSize();
                log.info("Fetching all comics up to #{} with batch size {}", latestId, batchSize);
                
                return Flux.range(1, latestId)
                    .filter(id -> id != 404) // Skip 404 as it's a special case in XKCD API
                    .buffer(batchSize)
                    .flatMap(this::fetchBatch, MAX_CONCURRENT_BATCHES);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error("Error in getAllComics: {}", e.getMessage()));
    }
    
    private Flux<XkcdComic> fetchBatch(List<Integer> comicIds) {
        return Flux.fromIterable(comicIds)
            .parallel(MAX_CONCURRENT_BATCHES)
            .runOn(Schedulers.parallel())
            .flatMap(this::getComicById)
            .sequential()
            .doOnError(e -> log.warn("Error in batch processing: {}", e.getMessage()));
    }
    
    private int getAdaptiveBatchSize() {
        long timeSinceLastError = System.currentTimeMillis() - lastErrorTime.get();
        int currentSize = currentBatchSize.get();
        
        // Gradually increase batch size if no recent errors
        if (timeSinceLastError > 30000) { // 30 seconds since last error
            return Math.min(MAX_BATCH_SIZE, currentSize + 5);
        }
        
        // Reduce batch size if we're seeing errors
        if (consecutiveErrors.get() > 0) {
            return Math.max(MIN_BATCH_SIZE, (int)(currentSize * 0.7));
        }
        
        return currentSize;
    }
    
    private void adjustBatchSize(boolean success) {
        if (success) {
            // Gradually increase batch size on success
            currentBatchSize.updateAndGet(current -> 
                Math.min(MAX_BATCH_SIZE, current + 1)
            );
        } else {
            // Decrease batch size on error
            currentBatchSize.updateAndGet(current -> 
                Math.max(MIN_BATCH_SIZE, (int)(current * 0.8))
            );
        }
        log.info("Adjusted batch size to {}", currentBatchSize.get());
    }
    
    private Bucket createRateLimiter() {
        // Create a simple in-memory rate limiter
        Bandwidth limit = Bandwidth.simple(RATE_LIMIT_REQUESTS, RATE_LIMIT_DURATION);
        return Bucket.builder()
            .addLimit(limit)
            .build();
    }
    
    @Scheduled(fixedRate = 3600000) // Every hour
    @CacheEvict(allEntries = true)
    public void evictAllCaches() {
        log.info("Evicting all caches");
        comicCache.clear();
    }
}
