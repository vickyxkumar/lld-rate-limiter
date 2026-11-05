# Rate Limiter (Low-Level Design)

A Java implementation of a per-user rate limiter. Each user tier (Free, Premium, Enterprise) can use a different
rate-limiting algorithm and limit. The project is a low-level design exercise. It shows four classic algorithms
behind one abstraction, a factory that creates them, and a service that sends each request to the limiter for its
tier.

## Features

- **Four algorithms:** Fixed Window, Sliding Window Log, Sliding Window Counter, and Token Bucket.
- **Per-user state:** every algorithm tracks its counters, timestamps or tokens separately for each `userId`.
- **Tier-based policy:** `RateLimiterService` maps each `UserTier` to its own limiter and config.
- **Thread-safe updates:** per-user state lives in a `ConcurrentHashMap`, and each check-and-update runs atomically
  inside `compute()`.
- **Testable time:** every limiter takes an injectable clock (`LongSupplier`), so tests move time forward by hand
  instead of sleeping.
- **Easy to extend:** to add an algorithm, subclass `RateLimiter` and register it in the factory.

## Project Structure

```
.
├── Main.java                       # Demo: sends bursts of requests for a Free, Premium and Enterprise user
├── enums/
│   ├── RateLimiterType.java        # FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET, SLIDING_WINDOW_COUNTER
│   └── UserTier.java               # FREE, PREMIUM, ENTERPRISE
├── model/
│   └── User.java                   # userId + userTier
├── limiter/
│   ├── RateLimiter.java            # Abstract base: allowRequest(userId) + injectable clock
│   ├── RateLimiterConfig.java      # maxRequests + timeWindowInSeconds (validated, must be > 0)
│   ├── FixedWindow.java
│   ├── SlidingWindow.java
│   ├── SlidingWindowCounter.java
│   └── TokenBucket.java
├── factory/
│   └── RateLimiterFactory.java     # Creates a RateLimiter from a type + config (+ optional clock)
├── service/
│   └── RateLimiterService.java     # Maps a user's tier to its limiter
└── tests/
    └── RateLimiterTest.java        # Dependency-free test suite using a fake clock
```

## Design

```
            ┌──────────────────────┐
  User ───▶ │  RateLimiterService  │  tier → RateLimiter
            └──────────┬───────────┘
                       │ allowRequest(userId)
                       ▼
            ┌───────────────────────┐         ┌────────────────────┐
            │ RateLimiter (abstract)│◀────────│ RateLimiterFactory │
            └──────────┬────────────┘ creates └────────────────────┘
      ┌────────────────┼─────────────────┬──────────────────────┐
      ▼                ▼                 ▼                      ▼
 FixedWindow     SlidingWindow     TokenBucket       SlidingWindowCounter
```

Design patterns used:

| Pattern  | Where | Why |
|----------|-------|-----|
| Strategy | `RateLimiter` and its subclasses | Each algorithm is interchangeable behind `allowRequest(userId)`. |
| Factory  | `RateLimiterFactory.getRateLimiter(config, type)` | Callers ask for an algorithm by type and never construct it themselves. |

### Configuration

`RateLimiterConfig(maxRequests, timeWindowInSeconds)` means "allow at most `maxRequests` per `timeWindowInSeconds`".
Both values must be positive, or the constructor throws `IllegalArgumentException`.

The service configures these tiers:

| Tier       | Algorithm              | Limit                 |
|------------|------------------------|-----------------------|
| FREE       | Token Bucket           | 10 requests / 60 s    |
| PREMIUM    | Fixed Window           | 100 requests / 60 s   |
| ENTERPRISE | Sliding Window Counter | 1000 requests / 60 s  |

## Algorithms

### Fixed Window — `limiter/FixedWindow.java`

Time is split into fixed windows of `timeWindowInSeconds`, aligned to the epoch. Each user has a window id and a
counter, and the counter resets when a new window starts. A request is allowed while the counter is below
`maxRequests`.

- **Pros:** O(1) memory per user and very cheap.
- **Cons:** traffic can burst at window edges. A user can send up to `2 × maxRequests` in a short span that straddles
  two windows.

### Sliding Window Log — `limiter/SlidingWindow.java`

Stores the timestamp of each allowed request in a queue per user. On every call it drops the timestamps older than
`now − window`. If fewer than `maxRequests` remain, it records the new request and allows it.

- **Pros:** exact. Over any rolling window, it never allows more than `maxRequests`.
- **Cons:** O(maxRequests) memory per user.

### Sliding Window Counter — `limiter/SlidingWindowCounter.java`

A cheap approximation of the sliding log. It keeps only two counters per user: the count for the current fixed
window and the count for the previous one. The previous count is weighted by how much of it still overlaps the
rolling window:

```
estimate = previousCount × (1 − elapsedInCurrentWindow / windowSize) + currentCount
```

The request is allowed if `estimate < maxRequests`. For example, with a limit of 10/s and 10 requests in the previous
second, a request halfway through the current second sees `10 × 0.5 = 5`, so 5 more requests fit.

- **Pros:** O(1) memory per user, and it smooths out most of the fixed window's edge bursts.
- **Cons:** it is approximate, because it assumes the previous window's requests were spread evenly.

### Token Bucket — `limiter/TokenBucket.java`

Each user starts with a full bucket of `maxRequests` tokens. A request uses one token. Tokens refill at a rate of one
every `timeWindowInSeconds × 1000 / maxRequests` ms, up to the bucket's capacity. Refills are computed lazily on
each request. Only the time that actually became tokens is subtracted, so partial refill progress is kept between
requests.

- **Pros:** allows short bursts up to the capacity and smooths traffic to a steady rate after that.
- **Cons:** you have to tune two parameters, capacity and refill rate.

## Getting Started

### Prerequisites

- JDK 8 or newer (tested on OpenJDK 21)

### Build and run

The project has no build tool, so compile it with `javac` from the project root:

```bash
javac -d out $(find . -name "*.java")
java -cp out Main
```

### Run the tests

The tests need no libraries. They use a fake clock, so they finish instantly:

```bash
java -cp out tests.RateLimiterTest
```

They cover each algorithm's limit, reset and refill behaviour, the per-user isolation, the thread safety under
concurrent requests, the factory, config validation, and the service tiers. The process exits with a non-zero code
if any test fails.

### Expected demo output

`Main` sends 15 requests for a Free user and 130 for a Premium user, 100 ms apart. Then it sends 1100 requests at
once for an Enterprise user:

```
=== Free User Requests ===
Request 1 for Free User: ALLOWED
...
Request 10 for Free User: ALLOWED
Request 11 for Free User: BLOCKED      # bucket empty; the next token arrives after 6 s
...
=== Premium User Requests ===
Request 1 for Premium User: ALLOWED
...
Request 100 for Premium User: ALLOWED
Request 101 for Premium User: BLOCKED  # window limit reached
...
=== Enterprise User Requests ===
Enterprise User: 1000 of 1100 requests ALLOWED
```

If the Premium run crosses a minute boundary, the window resets and more requests after 100 are allowed. This is
expected for a fixed window.

## Usage

```java
RateLimiterService service = new RateLimiterService();
User user = new User("user1", UserTier.FREE);

if (service.allowRequests(user)) {
    // handle the request
} else {
    // reject, e.g. with HTTP 429 Too Many Requests
}
```

To use an algorithm on its own:

```java
RateLimiter limiter = RateLimiterFactory.getRateLimiter(
        new RateLimiterConfig(5, 10),       // 5 requests per 10 seconds
        RateLimiterType.SLIDING_WINDOW);

limiter.allowRequest("user42");
```

To control time in a test, pass a clock:

```java
AtomicLong now = new AtomicLong(0);
RateLimiter limiter = RateLimiterFactory.getRateLimiter(
        new RateLimiterConfig(3, 1), RateLimiterType.FIXED_WINDOW, now::get);

now.set(1000);   // jump to the next window
```

## Extending

To add a new algorithm:

1. Add a value to `RateLimiterType`.
2. Create a class in `limiter/` that extends `RateLimiter` and implements `allowRequest(String userId)`. Read the time
   from `clock.getAsLong()`, not from `System.currentTimeMillis()`.
3. Add a `case` for it in `RateLimiterFactory.getRateLimiter`.
4. Map a tier to it in `RateLimiterService`.
5. Add tests to `tests/RateLimiterTest.java`.

## Known Limitations

- **State is in memory and never evicted.** The per-user maps grow with every new user, and a user's limits are not
  shared across instances of the service. A production setup would keep this state in a shared store with expiry,
  such as Redis with TTLs or a Lua script for atomic updates.
