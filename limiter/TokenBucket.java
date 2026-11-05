package limiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import enums.RateLimiterType;

public class TokenBucket extends RateLimiter {

    // this is to keep track of the number of tokens for each user
    private final Map<String, Integer> tokenCount = new ConcurrentHashMap<>();

    // this is to keep track of the last refill time for each user
    private final Map<String, Long> lastRefillTime = new ConcurrentHashMap<>();

    // time (in ms) needed to add one token; computed in ms to avoid integer division to 0 (e.g. 60s / 100 req).
    // Clamped to 1 so that a very high rate (e.g. 5000 req / 1 s) cannot cause division by zero.
    private final long refillRate;

    public TokenBucket(RateLimiterConfig config){
        this(config, System::currentTimeMillis);
    }

    public TokenBucket(RateLimiterConfig config, LongSupplier clock){
        super(RateLimiterType.TOKEN_BUCKET, config, clock);
        this.refillRate = Math.max(1L, config.windowMillis() / config.maxRequests);
    }

    @Override
    public boolean allowRequest(String userId){
        AtomicBoolean isAllowed = new AtomicBoolean(false);
        long currentTime = clock.getAsLong();

        tokenCount.compute(userId, (id, availableToken) -> {
            int currentTokenCount = refillTokens(userId, availableToken, currentTime);
            if (currentTokenCount > 0) {
                isAllowed.set(true);
                return currentTokenCount - 1;
            }
            return currentTokenCount;
        });

        return isAllowed.get();
    }

    // Called inside tokenCount.compute(), so it must NOT read/write tokenCount itself
    // (that causes "IllegalStateException: Recursive update"). compute() stores the returned value.
    private int refillTokens(String userId, Integer availableToken, long currentTime){
        long lastRefill = lastRefillTime.getOrDefault(userId, currentTime);
        long timeSinceLastRefill = currentTime - lastRefill;
        long tokensToAdd = timeSinceLastRefill / refillRate;
        int currentTokenCount = (availableToken == null) ? config.maxRequests : availableToken;
        int newTokenCount = (int) Math.min(currentTokenCount + tokensToAdd, config.maxRequests);

        if (availableToken == null || newTokenCount == config.maxRequests) {
            // first request or bucket full: start counting refill time from now
            lastRefillTime.put(userId, currentTime);
        } else if (tokensToAdd > 0) {
            // advance only by the time actually converted into tokens, keeping the leftover
            lastRefillTime.put(userId, lastRefill + tokensToAdd * refillRate);
        }
        return newTokenCount;
    }
}
