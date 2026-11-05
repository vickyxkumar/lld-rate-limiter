package limiter;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import enums.RateLimiterType;

public class SlidingWindow extends RateLimiter {

    private final Map<String, Queue<Long>> requestTimestamps = new ConcurrentHashMap<>();

    public SlidingWindow(RateLimiterConfig config){
        this(config, System::currentTimeMillis);
    }

    public SlidingWindow(RateLimiterConfig config, LongSupplier clock){
        super(RateLimiterType.SLIDING_WINDOW, config, clock);
    }

    @Override
    public boolean allowRequest(String userId){
        AtomicBoolean isAllowed = new AtomicBoolean(false);
        long currentTime = clock.getAsLong();

        requestTimestamps.compute(userId, (id, log) -> {
            if (log == null) {
                log = new ArrayDeque<>();
            }

            while (!log.isEmpty() && log.peek() <= currentTime - config.windowMillis()) {
                log.poll();
            }
            if (log.size() < config.maxRequests) {
                log.offer(currentTime);
                isAllowed.set(true);
            }
            return log;
        });

        return isAllowed.get();
    }
}
