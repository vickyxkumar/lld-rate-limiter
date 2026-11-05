package limiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import enums.RateLimiterType;

/**
 * Approximates a sliding window using only two counters per user: the count in the current
 * fixed window and the count in the previous one. The previous window's count is weighted by
 * how much of it still overlaps the rolling window:
 *
 *   estimate = previousCount * (1 - elapsedInCurrentWindow / windowSize) + currentCount
 */
public class SlidingWindowCounter extends RateLimiter {

    private static final class Counters {
        final long windowId;
        final int previousCount;
        final int currentCount;

        Counters(long windowId, int previousCount, int currentCount){
            this.windowId = windowId;
            this.previousCount = previousCount;
            this.currentCount = currentCount;
        }
    }

    private final Map<String, Counters> counters = new ConcurrentHashMap<>();

    public SlidingWindowCounter(RateLimiterConfig config){
        this(config, System::currentTimeMillis);
    }

    public SlidingWindowCounter(RateLimiterConfig config, LongSupplier clock){
        super(RateLimiterType.SLIDING_WINDOW_COUNTER, config, clock);
    }

    @Override
    public boolean allowRequest(String userId){
        AtomicBoolean isAllowed = new AtomicBoolean(false);
        long now = clock.getAsLong();
        long windowMillis = config.windowMillis();
        long currentWindowId = now / windowMillis;
        double elapsedFraction = (double) (now % windowMillis) / windowMillis;

        counters.compute(userId, (id, c) -> {
            Counters state = rollForward(c, currentWindowId);
            double estimate = state.previousCount * (1 - elapsedFraction) + state.currentCount;
            if (estimate < config.maxRequests) {
                isAllowed.set(true);
                return new Counters(currentWindowId, state.previousCount, state.currentCount + 1);
            }
            return state;
        });

        return isAllowed.get();
    }

    // Moves the counters to the current window, shifting or clearing old counts as needed.
    private static Counters rollForward(Counters c, long currentWindowId){
        if (c == null) {
            return new Counters(currentWindowId, 0, 0);
        }
        if (c.windowId == currentWindowId) {
            return c;
        }
        if (c.windowId == currentWindowId - 1) {
            return new Counters(currentWindowId, c.currentCount, 0);
        }
        // More than one full window has passed: nothing overlaps any more.
        return new Counters(currentWindowId, 0, 0);
    }
}
