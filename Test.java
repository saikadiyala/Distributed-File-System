import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Test {
    private static void run() {
        System.out.println("Running: " + new java.util.Date());
    }

    public static void main(String[] args) {
        ScheduledExecutorService executorService;
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(Test::run, 0, 1, TimeUnit.SECONDS);
    }
}
