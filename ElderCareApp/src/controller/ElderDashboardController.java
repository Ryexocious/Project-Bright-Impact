package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import utils.EmailService;
import utils.FirestoreService;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

import model.TimerController;

/**
 * Elder dashboard — updated to support intake-window, snooze and auto-SOS behavior.
 *
 * Behavior summary:
 *  - Pre-dose: blue progress from now -> next dose time.
 *  - At dose time: switch to intake window (red) for 10 minutes.
 *    - If Confirm pressed within the window => mark taken & reset to next dose.
 *    - If not pressed -> allow up to 2 snoozes (default) — after all snoozes are used, auto SOS.
 */
public class ElderDashboardController {

    private String elderUsername;
    private String pairingCode;
    private String elderId; // store elder document id

    @FXML private Button helpRequestButton;
    @FXML private Button viewMedicineButton;
    @FXML private Button viewVitalsButton;
    @FXML private Button logoutButton;
    @FXML private Label welcomeLabel;

    // Timer + medicines
    @FXML private VBox timerContainer;
    @FXML private StackPane timerHost;
    @FXML private VBox medsListBox;
    @FXML private Button confirmIntakeButton;    // shown only during intake window
    @FXML private Label nextDoseLabel;
    @FXML private Label intakeStatusLabel;       // optional small status (snooze count etc.)

    private Timeline countdownTimeline;

    // Next scheduled dose (for today) datetime
    private LocalDateTime nextDoseDateTime;
    private long initialWindowSeconds = 0L; // seconds captured at scheduling time (now->nextDose)

    // Intake-window state
    private boolean inIntakeWindow = false;
    private long intakeRemainingSeconds = 0L; // seconds left in current intake/snooze window
    private final long INTAKE_WINDOW_SECONDS = 10 * 60L; // 10 minutes
    private int snoozesUsed = 0;
    private final int maxSnoozesAllowed = 2;  // default 2 snoozes

    private List<MedicineSchedule> currentDueMedicines = new ArrayList<>();

    private DateTimeFormatter labelDateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Canvas timer view
    private TimerController timerView;

    /* ==========================
       Initialisation & User actions
       ========================== */

    /**
     * Call this AFTER you have loaded the FXML and want the controller to begin (pass username).
     * Your app should call initializeElder(loggedInUsername) after login.
     */
    public void initializeElder(String loggedInUsername) {
        // UI pre-state
        if (helpRequestButton != null) helpRequestButton.setDisable(true);
        if (welcomeLabel != null) welcomeLabel.setText("Loading...");

        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                QuerySnapshot elderSnapshot = users
                        .whereEqualTo("username", loggedInUsername)
                        .whereEqualTo("role", "elder")
                        .get()
                        .get();

                if (!elderSnapshot.isEmpty()) {
                    QueryDocumentSnapshot elderDoc = elderSnapshot.getDocuments().get(0);
                    this.elderUsername = elderDoc.getString("username");
                    this.pairingCode = elderDoc.getString("pairingCode");
                    this.elderId = elderDoc.getId();

                    Platform.runLater(() -> {
                        if (helpRequestButton != null) helpRequestButton.setDisable(false);
                        if (welcomeLabel != null) welcomeLabel.setText("Welcome, " + elderUsername + " 👴");
                    });

                    loadMedicinesAndStartTimer();
                } else {
                    Platform.runLater(() -> {
                        if (welcomeLabel != null) welcomeLabel.setText("Elder not found");
                    });
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    if (welcomeLabel != null) welcomeLabel.setText("Failed to load elder details");
                });
            }
        }).start();

        // hide confirm button until intake window triggered
        if (confirmIntakeButton != null) confirmIntakeButton.setVisible(false);
        if (intakeStatusLabel != null) intakeStatusLabel.setText("");
    }

    @FXML
    private void handleHelpRequest() {
        if (elderId == null) return;
        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                QuerySnapshot caretakersSnapshot = users
                        .whereEqualTo("role", "caretaker")
                        .whereEqualTo("elderId", elderId)
                        .get()
                        .get();

                List<String> caretakerEmails = new ArrayList<>();
                for (QueryDocumentSnapshot doc : caretakersSnapshot.getDocuments()) {
                    String email = doc.getString("email");
                    if (email != null && !email.isEmpty()) caretakerEmails.add(email);
                }

                if (caretakerEmails.isEmpty()) {
                    try {
                        DocumentSnapshot elderSnapshot = users.document(elderId).get().get();
                        Object caretakersField = elderSnapshot.get("caretakers");
                        if (caretakersField instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> caretakerIds = (List<String>) caretakersField;
                            for (String cid : caretakerIds) {
                                try {
                                    DocumentSnapshot caretDocSnap = users.document(cid).get().get();
                                    if (caretDocSnap != null && caretDocSnap.exists()) {
                                        String e = caretDocSnap.getString("email");
                                        if (e != null && !e.isEmpty()) caretakerEmails.add(e);
                                    }
                                } catch (InterruptedException | ExecutionException ex) { ex.printStackTrace(); }
                            }
                        }
                    } catch (InterruptedException | ExecutionException ex) { ex.printStackTrace(); }
                }

                if (!caretakerEmails.isEmpty()) {
                    EmailService.sendHelpRequestEmail(elderUsername, caretakerEmails);
                } else {
                    System.out.println("No caretakers found for elder: " + elderId);
                }

            } catch (InterruptedException | ExecutionException e) { e.printStackTrace(); }
        }).start();
    }

    @FXML
    private void handleViewMedicine() { switchScene("/fxml/medicine_schedule.fxml"); }

    @FXML
    private void handleViewVitals() { switchScene("/fxml/vitals.fxml"); }

    @FXML
    private void handleLogout() { switchScene("/fxml/login.fxml"); }

    private void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) (helpRequestButton != null ? helpRequestButton.getScene().getWindow() : null);
            if (stage != null) {
                stage.setScene(new Scene(root));
                stage.centerOnScreen();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    /* ==========================
       Loading medicines & scheduling next dose (TODAY)
       ========================== */

    private void loadMedicinesAndStartTimer() {
        if (elderId == null) return;
        Firestore db = FirestoreService.getFirestore();
        CollectionReference medsCol = db.collection("users").document(elderId).collection("medicines");

        new Thread(() -> {
            try {
                QuerySnapshot medsSnap = medsCol.get().get();
                List<MedicineSchedule> meds = new ArrayList<>();
                for (DocumentSnapshot d : medsSnap.getDocuments()) {
                    MedicineSchedule m = MedicineSchedule.fromFirestore(d);
                    if (m.isActiveOn(LocalDate.now())) meds.add(m);
                }
                Platform.runLater(() -> {
                    displayFullMedicinesList(meds);
                    scheduleNextImmediateDoseToday(meds);
                });
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void displayFullMedicinesList(List<MedicineSchedule> meds) {
        if (medsListBox == null) return;
        medsListBox.getChildren().clear();
        if (meds.isEmpty()) {
            medsListBox.getChildren().add(new Label("No medicines found for today."));
            return;
        }
        for (MedicineSchedule m : meds) {
            Label l = new Label(m.name + " — " + (m.amount == null ? "" : m.amount));
            l.setStyle("-fx-font-size: 14px; -fx-padding: 6;");
            medsListBox.getChildren().add(l);
        }
    }

    private void scheduleNextImmediateDoseToday(List<MedicineSchedule> meds) {
        inIntakeWindow = false;
        snoozesUsed = 0;
        if (confirmIntakeButton != null) confirmIntakeButton.setVisible(false);
        if (intakeStatusLabel != null) intakeStatusLabel.setText("");

        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        TreeSet<LocalTime> candidateTimes = new TreeSet<>();

        for (MedicineSchedule m : meds) {
            if (m.times != null) {
                for (String ts : m.times) {
                    try {
                        LocalTime lt = LocalTime.parse(ts);
                        if (!lt.isBefore(nowTime)) candidateTimes.add(lt);
                    } catch (Exception ignored) {}
                }
            }
        }

        if (candidateTimes.isEmpty()) {
            nextDoseDateTime = null;
            initialWindowSeconds = 0L;
            currentDueMedicines = new ArrayList<>();
            if (nextDoseLabel != null) nextDoseLabel.setText("No upcoming doses today.");
            ensureTimerViewPresent();
            if (timerView != null) timerView.update(0.0, 0L, false);
            if (countdownTimeline != null) countdownTimeline.stop();
            return;
        }

        LocalTime earliest = candidateTimes.first();
        nextDoseDateTime = LocalDateTime.of(today, earliest);

        Instant nowInstant = Instant.now();
        Instant targetInstant = nextDoseDateTime.atZone(ZoneId.systemDefault()).toInstant();
        long secs = Duration.between(nowInstant, targetInstant).getSeconds();
        if (secs < 0) secs = 0;
        initialWindowSeconds = Math.max(1L, secs);

        // upcoming meds at next dose time
        String earliestStr = earliest.toString();
        List<MedicineSchedule> upcoming = new ArrayList<>();
        for (MedicineSchedule m : meds) {
            if (m.times != null && m.times.contains(earliestStr)) upcoming.add(m);
        }
        currentDueMedicines = upcoming;

        updateUpcomingUI();
        ensureTimerViewPresent();
        startCountdownTimeline();
    }

    private void updateUpcomingUI() {
        if (nextDoseLabel != null) {
            if (nextDoseDateTime != null) {
                nextDoseLabel.setText("Next dose at: " + nextDoseDateTime.format(labelDateTimeFmt));
            } else {
                nextDoseLabel.setText("No upcoming doses today.");
            }
        }

        if (medsListBox == null) return;
        medsListBox.getChildren().clear();
        if (currentDueMedicines == null || currentDueMedicines.isEmpty()) {
            medsListBox.getChildren().add(new Label("No medicines in upcoming group."));
            return;
        }
        for (MedicineSchedule m : currentDueMedicines) {
            Label l = new Label("• " + m.name + " — " + (m.amount == null ? "" : m.amount));
            l.setStyle("-fx-font-size: 16px; -fx-padding: 6;");
            medsListBox.getChildren().add(l);
        }
    }

    private void ensureTimerViewPresent() {
        if (timerHost == null) return;
        if (timerView == null) {
            timerView = new TimerController();
            timerView.setCanvasSize(340, 340);
            timerHost.getChildren().clear();
            timerHost.getChildren().add(timerView);
        }
    }

    private void startCountdownTimeline() {
        if (countdownTimeline != null) countdownTimeline.stop();

        countdownTimeline = new Timeline(
                new KeyFrame(javafx.util.Duration.seconds(0), ev -> updateTimerUI()),
                new KeyFrame(javafx.util.Duration.seconds(1))
        );
        countdownTimeline.setCycleCount(Timeline.INDEFINITE);
        countdownTimeline.play();
    }

    private void updateTimerUI() {
        if (inIntakeWindow) {
            intakeRemainingSeconds = Math.max(0L, intakeRemainingSeconds - 1L);
            double progress = 1.0 - ((double) intakeRemainingSeconds / (double) INTAKE_WINDOW_SECONDS);
            if (timerView != null) timerView.update(progress, intakeRemainingSeconds, true);

            if (intakeStatusLabel != null) {
                intakeStatusLabel.setText("Intake window — snoozes used: " + snoozesUsed + " / " + maxSnoozesAllowed);
            }

            if (intakeRemainingSeconds <= 0) {
                if (snoozesUsed < maxSnoozesAllowed) {
                    snoozesUsed++;
                    intakeRemainingSeconds = INTAKE_WINDOW_SECONDS;
                    if (confirmIntakeButton != null) confirmIntakeButton.setVisible(true);
                    return;
                } else {
                    performAutoSOS("Snoozes exhausted for this group.");
                    exitIntakeAndReset();
                    return;
                }
            }
            return;
        }

        if (nextDoseDateTime == null) {
            if (timerView != null) timerView.update(0.0, 0L, false);
            if (nextDoseLabel != null) nextDoseLabel.setText("No upcoming doses today.");
            if (countdownTimeline != null) countdownTimeline.stop();
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long remainingSeconds = Duration.between(now, nextDoseDateTime).getSeconds();
        if (remainingSeconds <= 0) {
            enterIntakeWindow();
            return;
        }

        long total = initialWindowSeconds > 0 ? initialWindowSeconds : remainingSeconds;
        double progress = total <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, (double) (total - remainingSeconds) / (double) total));
        if (timerView != null) timerView.update(progress, remainingSeconds, false);
    }

    private void enterIntakeWindow() {
        inIntakeWindow = true;
        intakeRemainingSeconds = INTAKE_WINDOW_SECONDS;
        snoozesUsed = 0;
        if (confirmIntakeButton != null) confirmIntakeButton.setVisible(true);
        if (intakeStatusLabel != null) {
            intakeStatusLabel.setText("Intake window started. Snoozes allowed: " + maxSnoozesAllowed);
        }
    }

    private void exitIntakeAndReset() {
        inIntakeWindow = false;
        intakeRemainingSeconds = 0;
        snoozesUsed = 0;
        if (confirmIntakeButton != null) confirmIntakeButton.setVisible(false);
        if (intakeStatusLabel != null) intakeStatusLabel.setText("");
        loadMedicinesAndStartTimer();
    }

    @FXML
    private void handleConfirmIntake() {
        if (!inIntakeWindow) return;
        if (currentDueMedicines == null || currentDueMedicines.isEmpty()) return;

        Firestore db = FirestoreService.getFirestore();
        for (MedicineSchedule m : currentDueMedicines) {
            if (m.docRef != null) {
                m.docRef.update("lastTaken", Timestamp.now());
            }
        }

        exitIntakeAndReset();
    }

    private void performAutoSOS(String reason) {
        Platform.runLater(() -> {
            handleHelpRequest();
            Alert a = new Alert(Alert.AlertType.WARNING, "Auto SOS triggered: " + reason, ButtonType.OK);
            a.setHeaderText("Emergency: Unconfirmed medicines");
            a.show();
        });
    }

    /* ==========================
       Helper: MedicineSchedule (minimal, schema-aware)
       ========================== */

    private static class MedicineSchedule {
        String id;
        String name;
        String amount;
        List<String> times; // "HH:mm"
        Map<String, Object> activePeriod;
        DocumentReference docRef;

        static MedicineSchedule fromFirestore(DocumentSnapshot d) {
            MedicineSchedule m = new MedicineSchedule();
            m.id = d.getId();
            m.name = d.getString("name");
            m.amount = d.getString("amount");
            Object t = d.get("times");
            if (t instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) t;
                m.times = new ArrayList<>(list);
            } else {
                m.times = new ArrayList<>();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ap = (Map<String, Object>) d.get("activePeriod");
            m.activePeriod = ap;

            m.docRef = d.getReference();
            return m;
        }

        boolean isActiveOn(LocalDate date) {
            if (activePeriod == null) return true;
            try {
                Object s = activePeriod.get("startDate");
                Object e = activePeriod.get("endDate");
                LocalDate start = (s instanceof String) ? LocalDate.parse((String) s) : null;
                LocalDate end = (e instanceof String) ? LocalDate.parse((String) e) : null;
                if (start != null && date.isBefore(start)) return false;
                if (end != null && date.isAfter(end)) return false;
                return true;
            } catch (Exception ex) {
                return true;
            }
        }
    }
}
