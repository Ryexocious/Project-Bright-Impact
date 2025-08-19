package utils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;

public class ReminderService {

    private static ReminderService instance;
    private Timer timer;

    private ReminderService() {
        timer = new Timer();
    }

    public static ReminderService getInstance() {
        if (instance == null) {
            instance = new ReminderService();
        }
        return instance;
    }

    public void scheduleMedicineReminder(LocalDateTime reminderTime, Runnable callback) {
        long delay = Duration.between(LocalDateTime.now(), reminderTime).toMillis();

        if (delay < 0) {
            // If time already passed, call immediately
            callback.run();
            return;
        }

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                callback.run();
            }
        }, delay);
    }

    public void cancelMedicineReminder() {
        timer.cancel();
        timer = new Timer();
    }
}
