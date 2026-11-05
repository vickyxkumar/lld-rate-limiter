package limiter;

import java.util.function.LongSupplier;

import enums.RateLimiterType;

public abstract class RateLimiter {
    protected final RateLimiterType type;
    protected final RateLimiterConfig config;

    // Source of the current time in ms. Injectable so tests can control time instead of sleeping.
    protected final LongSupplier clock;

    public RateLimiter(RateLimiterType type, RateLimiterConfig config, LongSupplier clock){
        this.type = type;
        this.config = config;
        this.clock = clock;
    }

    public RateLimiterType getType(){
        return type;
    }

    public abstract boolean allowRequest(String userId);
}
