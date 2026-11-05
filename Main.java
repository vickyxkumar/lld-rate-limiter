import enums.UserTier;
import model.User;
import service.RateLimiterService;

public class Main {
    public static void main(String[] args) {
        User freeUser = new User("user1", UserTier.FREE); // 10 req in 60 sec
        User premiumUser = new User("user2", UserTier.PREMIUM); // 100 req in 60 sec
        User enterpriseUser = new User("user3", UserTier.ENTERPRISE); // 1000 req in 60 sec

        RateLimiterService rateLimiterService = new RateLimiterService();

        System.out.println("=== Free User Requests ===");
        for (int i = 1; i <= 15; i++) {
            try {
                boolean allowed = rateLimiterService.allowRequests(freeUser);
                System.out.println("Request " + i + " for Free User: " + (allowed ? "ALLOWED" : "BLOCKED"));
                Thread.sleep(100); // simulate delay between requests
            } catch (Exception e) {
                System.out.println("Error processing request " + i + " for Free User: " + e.getMessage());
            }
        }

        System.out.println("\n=== Premium User Requests ===");
        for (int i = 1; i <= 130; i++) {
            try {
                boolean allowed = rateLimiterService.allowRequests(premiumUser);
                System.out.println("Request " + i + " for Premium User: " + (allowed ? "ALLOWED" : "BLOCKED"));
                Thread.sleep(100);
            } catch (Exception e) {
                System.out.println("Error processing request " + i + " for Premium User: " + e.getMessage());
            }
        }

        System.out.println("\n=== Enterprise User Requests ===");
        int allowedCount = 0;
        for (int i = 1; i <= 1100; i++) {
            if (rateLimiterService.allowRequests(enterpriseUser)) {
                allowedCount++;
            }
        }
        System.out.println("Enterprise User: " + allowedCount + " of 1100 requests ALLOWED");
    }
}
