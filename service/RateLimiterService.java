package service;

import java.util.EnumMap;
import java.util.Map;

import enums.RateLimiterType;
import enums.UserTier;
import factory.RateLimiterFactory;
import limiter.RateLimiter;
import limiter.RateLimiterConfig;
import model.User;

public class RateLimiterService {

    private final Map<UserTier, RateLimiter> tierToRateLimiter = new EnumMap<>(UserTier.class);

    public RateLimiterService() {
        tierToRateLimiter.put(UserTier.FREE, RateLimiterFactory.getRateLimiter(
                new RateLimiterConfig(10, 60), RateLimiterType.TOKEN_BUCKET));
        tierToRateLimiter.put(UserTier.PREMIUM, RateLimiterFactory.getRateLimiter(
                new RateLimiterConfig(100, 60), RateLimiterType.FIXED_WINDOW));
        tierToRateLimiter.put(UserTier.ENTERPRISE, RateLimiterFactory.getRateLimiter(
                new RateLimiterConfig(1000, 60), RateLimiterType.SLIDING_WINDOW_COUNTER));
    }

    public boolean allowRequests(User user){
        RateLimiter algorithm = tierToRateLimiter.get(user.getUserTier());
        if(algorithm == null) {
            throw new IllegalArgumentException("No rate limiter configured for user tier: " + user.getUserTier());
        }
        return algorithm.allowRequest(user.getUserId());
    }
}
