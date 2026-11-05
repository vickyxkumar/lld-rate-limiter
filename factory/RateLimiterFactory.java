package factory;

import java.util.function.LongSupplier;

import enums.RateLimiterType;
import limiter.FixedWindow;
import limiter.RateLimiter;
import limiter.RateLimiterConfig;
import limiter.SlidingWindow;
import limiter.SlidingWindowCounter;
import limiter.TokenBucket;

public class RateLimiterFactory {

    public static RateLimiter getRateLimiter(RateLimiterConfig config, RateLimiterType type) {
        return getRateLimiter(config, type, System::currentTimeMillis);
    }

    public static RateLimiter getRateLimiter(RateLimiterConfig config, RateLimiterType type, LongSupplier clock) {
        switch (type) {
            case FIXED_WINDOW:
                return new FixedWindow(config, clock);
            case SLIDING_WINDOW:
                return new SlidingWindow(config, clock);
            case TOKEN_BUCKET:
                return new TokenBucket(config, clock);
            case SLIDING_WINDOW_COUNTER:
                return new SlidingWindowCounter(config, clock);
            default:
                throw new IllegalArgumentException("Unsupported rate limiter type: " + type);
        }
    }
}
