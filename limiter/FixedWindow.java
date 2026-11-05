package limiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import enums.RateLimiterType;

public class FixedWindow extends RateLimiter {

    // Window id and count live in one object so they are always read and updated together.
    private static final class Window {
        final long id;
        final int count;

        Window(long id, int count){
            this.id = id;
            this.count = count;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindow(RateLimiterConfig config){
        this(config, System::currentTimeMillis);
    }

    public FixedWindow(RateLimiterConfig config, LongSupplier clock){
        super(RateLimiterType.FIXED_WINDOW, config, clock);
    }

    @Override
    public boolean allowRequest(String userId){
        AtomicBoolean isAllowed = new AtomicBoolean(false);
        long currentWindowId = clock.getAsLong() / config.windowMillis();

        windows.compute(userId, (id, window) -> {
            // First request, or a new window has started: reset the count.
            if (window == null || window.id != currentWindowId) {
                isAllowed.set(true);
                return new Window(currentWindowId, 1);
            }
            if (window.count < config.maxRequests) {
                isAllowed.set(true);
                return new Window(currentWindowId, window.count + 1);
            }
            return window;
        });

        return isAllowed.get();
    }
}
