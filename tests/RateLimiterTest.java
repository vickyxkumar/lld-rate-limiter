package tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import enums.RateLimiterType;
import enums.UserTier;
import factory.RateLimiterFactory;
import limiter.FixedWindow;
import limiter.RateLimiter;
import limiter.RateLimiterConfig;
import limiter.SlidingWindow;
import limiter.SlidingWindowCounter;
import limiter.TokenBucket;
import model.User;
import service.RateLimiterService;

/**
 * Dependency-free tests. Every limiter gets a fake clock, so time is moved forward by hand
 * instead of sleeping.
 *
 * Run: java -cp out tests.RateLimiterTest
 */
public class RateLimiterTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        run("FixedWindow allows up to the limit, then blocks", RateLimiterTest::fixedWindowBlocksOverLimit);
        run("FixedWindow resets in the next window", RateLimiterTest::fixedWindowResets);
        run("FixedWindow resets with real-sized timestamps", RateLimiterTest::fixedWindowResetsWithLargeTimestamps);
        run("SlidingWindow expires old requests on a rolling basis", RateLimiterTest::slidingWindowRolls);
        run("TokenBucket refills one token per interval", RateLimiterTest::tokenBucketRefills);
        run("TokenBucket never exceeds its capacity", RateLimiterTest::tokenBucketCapsAtCapacity);
        run("SlidingWindowCounter weights the previous window", RateLimiterTest::slidingWindowCounterWeightsPrevious);
        run("SlidingWindowCounter resets after an idle gap", RateLimiterTest::slidingWindowCounterResetsAfterGap);
        run("Users are limited independently", RateLimiterTest::usersAreIndependent);
        run("Limits hold under concurrent requests", RateLimiterTest::concurrentRequestsRespectLimit);
        run("Factory creates every RateLimiterType", RateLimiterTest::factoryCreatesEveryType);
        run("Config rejects non-positive values", RateLimiterTest::configRejectsInvalidValues);
        run("Service has a limiter for every tier", RateLimiterTest::serviceCoversEveryTier);

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  FAILED: " + f));
            System.exit(1);
        }
    }

    // ---------- Fixed Window ----------

    static void fixedWindowBlocksOverLimit() {
        FakeClock clock = new FakeClock(0);
        RateLimiter limiter = new FixedWindow(new RateLimiterConfig(3, 1), clock::now);

        assertAllowed(limiter, "u", 3);
        assertBlocked(limiter, "u");
    }

    static void fixedWindowResets() {
        FakeClock clock = new FakeClock(0);
        RateLimiter limiter = new FixedWindow(new RateLimiterConfig(3, 1), clock::now);

        assertAllowed(limiter, "u", 3);
        assertBlocked(limiter, "u");

        clock.set(1000); // next window
        assertAllowed(limiter, "u", 3);
        assertBlocked(limiter, "u");
    }

    // Regression: window ids were boxed Longs compared with != (reference equality).
    static void fixedWindowResetsWithLargeTimestamps() {
        FakeClock clock = new FakeClock(1_700_000_000_000L);
        RateLimiter limiter = new FixedWindow(new RateLimiterConfig(2, 60), clock::now);

        assertAllowed(limiter, "u", 2);
        assertBlocked(limiter, "u");

        clock.advance(60_000);
        assertAllowed(limiter, "u", 2);
        assertBlocked(limiter, "u");
    }

    // ---------- Sliding Window Log ----------

    static void slidingWindowRolls() {
        FakeClock clock = new FakeClock(0);
        RateLimiter limiter = new SlidingWindow(new RateLimiterConfig(3, 1), clock::now);

        assertTrue(limiter.allowRequest("u"), "t=0");
        clock.set(100);
        assertTrue(limiter.allowRequest("u"), "t=100");
        clock.set(200);
        assertTrue(limiter.allowRequest("u"), "t=200");
        clock.set(300);
        assertFalse(limiter.allowRequest("u"), "t=300 should be over the limit");

        clock.set(1000); // the t=0 request has expired
        assertTrue(limiter.allowRequest("u"), "t=1000");
        clock.set(1050); // t=100 is still inside the window
        assertFalse(limiter.allowRequest("u"), "t=1050 should be over the limit");
        clock.set(1100); // the t=100 request has expired
        assertTrue(limiter.allowRequest("u"), "t=1100");
    }

    // ---------- Token Bucket ----------

    static void tokenBucketRefills() {
        FakeClock clock = new FakeClock(0);
        // 2 tokens per 2 s => one token every 1000 ms
        RateLimiter limiter = new TokenBucket(new RateLimiterConfig(2, 2), clock::now);

        assertAllowed(limiter, "u", 2);
        assertBlocked(limiter, "u");

        clock.set(999);
        assertBlocked(limiter, "u");

        clock.set(1000);
        assertAllowed(limiter, "u", 1);
        assertBlocked(limiter, "u");
    }

    static void tokenBucketCapsAtCapacity() {
        FakeClock clock = new FakeClock(0);
        RateLimiter limiter = new TokenBucket(new RateLimiterConfig(3, 3), clock::now);

        assertAllowed(limiter, "u", 3);
        clock.advance(1_000_000); // a long idle period must not build up more than 3 tokens
        assertAllowed(limiter, "u", 3);
        assertBlocked(limiter, "u");
    }

    // ---------- Sliding Window Counter ----------

    static void slidingWindowCounterWeightsPrevious() {
        FakeClock clock = new FakeClock(0);
        RateLimiter limiter = new SlidingWindowCounter(new RateLimiterConfig(10, 1), clock::now);

        assertAllowed(limiter, "u", 10);
        assertBlocked(limiter, "u");

        // Halfway through the next window, the previous 10 count as 10 * 0.5 = 5,
        // so only 5 more requests fit.
        clock.set(1500);
        assertAllowed(limiter, "u", 5);
        assertBlocked(limiter, "u");
    }

    static void slidingWindowCounterResetsAfterGap() {
        FakeClock clock = new FakeClock(0);
        RateLimiter limiter = new SlidingWindowCounter(new RateLimiterConfig(4, 1), clock::now);

        assertAllowed(limiter, "u", 4);
        assertBlocked(limiter, "u");

        clock.set(3000); // two full windows later: nothing from window 0 overlaps
        assertAllowed(limiter, "u", 4);
        assertBlocked(limiter, "u");
    }

    // ---------- Shared behaviour ----------

    static void usersAreIndependent() {
        for (RateLimiterType type : RateLimiterType.values()) {
            FakeClock clock = new FakeClock(0);
            RateLimiter limiter = RateLimiterFactory.getRateLimiter(new RateLimiterConfig(2, 10), type, clock::now);

            assertAllowed(limiter, "alice", 2);
            assertBlocked(limiter, "alice");
            assertAllowed(limiter, "bob", 2);
        }
    }

    static void concurrentRequestsRespectLimit() throws Exception {
        int limit = 100;
        int threads = 16;
        int requestsPerThread = 50;

        for (RateLimiterType type : RateLimiterType.values()) {
            FakeClock clock = new FakeClock(0);
            RateLimiter limiter = RateLimiterFactory.getRateLimiter(new RateLimiterConfig(limit, 60), type, clock::now);

            AtomicInteger allowed = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (limiter.allowRequest("shared")) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), type + ": threads did not finish");
            assertTrue(allowed.get() == limit, type + ": expected " + limit + " allowed, got " + allowed.get());
        }
    }

    static void factoryCreatesEveryType() {
        for (RateLimiterType type : RateLimiterType.values()) {
            RateLimiter limiter = RateLimiterFactory.getRateLimiter(new RateLimiterConfig(1, 1), type);
            assertTrue(limiter != null && limiter.getType() == type, "factory returned the wrong limiter for " + type);
        }
    }

    static void configRejectsInvalidValues() {
        assertThrows(() -> new RateLimiterConfig(0, 60), "maxRequests = 0");
        assertThrows(() -> new RateLimiterConfig(10, 0), "timeWindowInSeconds = 0");
        assertThrows(() -> new RateLimiterConfig(-1, 60), "maxRequests < 0");
    }

    static void serviceCoversEveryTier() {
        RateLimiterService service = new RateLimiterService();
        for (UserTier tier : UserTier.values()) {
            assertTrue(service.allowRequests(new User("u-" + tier, tier)), tier + ": first request should be allowed");
        }
    }

    // ---------- Helpers ----------

    static final class FakeClock {
        private final AtomicLong now;

        FakeClock(long start) { now = new AtomicLong(start); }
        long now() { return now.get(); }
        void set(long millis) { now.set(millis); }
        void advance(long millis) { now.addAndGet(millis); }
    }

    interface TestBody { void run() throws Exception; }

    static void run(String name, TestBody body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable e) {
            failures.add(name + " -> " + e.getMessage());
            System.out.println("FAIL  " + name + " -> " + e.getMessage());
        }
    }

    static void assertAllowed(RateLimiter limiter, String userId, int times) {
        for (int i = 1; i <= times; i++) {
            assertTrue(limiter.allowRequest(userId), "request " + i + " for " + userId + " should be allowed");
        }
    }

    static void assertBlocked(RateLimiter limiter, String userId) {
        assertFalse(limiter.allowRequest(userId), "request for " + userId + " should be blocked");
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException: " + message);
    }
}
