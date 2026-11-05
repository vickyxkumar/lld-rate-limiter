package limiter;

public class RateLimiterConfig {
    final int maxRequests;
    final int timeWindowInSeconds;

    public RateLimiterConfig(int maxRequests, int timeWindowInSeconds){
        if (maxRequests <= 0 || timeWindowInSeconds <= 0) {
            throw new IllegalArgumentException("maxRequests and timeWindowInSeconds must be positive");
        }
        this.maxRequests = maxRequests;
        this.timeWindowInSeconds = timeWindowInSeconds;
    }

    long windowMillis(){
        return timeWindowInSeconds * 1000L;
    }
}
