package controller;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Firestore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import utils.EmailService;
import utils.FirestoreService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ElderDashboardController {

    private String elderUsername;
    private String pairingCode;
    private String elderId; // store elder document id
    private Timeline clockTimeline; // For real-time clock updates
    private Timeline medicineCheckTimeline; // For checking medicine alerts
    private List<Map<String, Object>> upcomingMedicines = new ArrayList<>();

    // Existing FXML elements
    @FXML private Button helpRequestButton;
    @FXML private Button logoutButton;
    @FXML private Label welcomeLabel;

    // New FXML elements for enhanced dashboard
    @FXML private Label currentDateLabel;
    @FXML private Label currentTimeLabel;

    // Medicine Alert Banner
    @FXML private VBox medicineAlertBanner;
    @FXML private Label alertMedicineText;
    @FXML private Button takeMedicineButton;
    @FXML private Button snoozeButton;

    // Upcoming Medicines Section - NOW VBox for time groups
    @FXML private VBox upcomingMedicinesContainer;
    @FXML private VBox noUpcomingMedicinesCard;

    // Summary Section
    @FXML private Label medicinesTakenCount;
    @FXML private Label medicinesRemainingCount;
    @FXML private Label nextDoseTime;
    @FXML private Label nextDoseName;

    // Navigation buttons
    @FXML private Button viewAllMedicineButton;
    @FXML private Button addMedicineButton;
    @FXML private Button medicineHistoryButton;
    @FXML private Button profileButton;
    @FXML private Button settingsButton;

    /**
     * Initializes the dashboard after login by fetching elder details from Firestore
     * and setting up real-time updates for medicine schedule and clock.
     *
     * @param loggedInUsername The username of the logged-in elder
     */
    public void initializeElder(String loggedInUsername) {
        helpRequestButton.setDisable(true); // Disable until data loaded
        welcomeLabel.setText("Loading...");

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
                    this.elderId = elderDoc.getId(); // store elder document id

                    System.out.println("✅ Elder details loaded: " + elderUsername +
                            ", PairingCode: " + pairingCode + ", elderId: " + elderId);

                    Platform.runLater(() -> {
                        helpRequestButton.setDisable(false); // Enable help button
                        welcomeLabel.setText("Welcome, " + elderUsername); // Clean welcome text

                        // Initialize dashboard components
                        startClockUpdate();
                        loadMedicineSchedule();
                        startMedicineAlertChecker();
                        updateTodaySummary();
                    });

                } else {
                    System.err.println("❌ Elder not found in database: " + loggedInUsername);
                    Platform.runLater(() -> welcomeLabel.setText("Elder not found"));
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> welcomeLabel.setText("Failed to load elder details"));
            }
        }).start();
    }

    /**
     * Starts the real-time clock update that runs every second.
     * Updates current date and time display continuously.
     */
    private void startClockUpdate() {
        clockTimeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> updateDateTime())
        );
        clockTimeline.setCycleCount(Animation.INDEFINITE);
        clockTimeline.play();

        // Initial update
        updateDateTime();
    }

    /**
     * Updates the current date and time display.
     * Shows real-time clock with seconds for precise medicine timing.
     */
    private void updateDateTime() {
        LocalDateTime now = LocalDateTime.now();

        // Format date: Monday, January 15, 2024
        String formattedDate = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
        currentDateLabel.setText(formattedDate);

        // Format time with seconds: 2:30:45 PM
        String formattedTime = now.format(DateTimeFormatter.ofPattern("h:mm:ss a"));
        currentTimeLabel.setText(formattedTime);
    }

    /**
     * Loads upcoming medicine schedule from Firebase and displays time-based groups.
     * Groups medicines by their scheduled times for simpler display.
     */
    private void loadMedicineSchedule() {
        if (elderId == null) return;

        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();

                // Query medicine schedules for this elder from Firebase
                QuerySnapshot medicineSnapshot = db.collection("medicine_schedules")
                        .whereEqualTo("elderId", elderId)
                        .whereEqualTo("active", true)
                        .orderBy("nextScheduledTime")
                        .limit(10) // Get more medicines to group them
                        .get()
                        .get();

                upcomingMedicines.clear();

                // Process medicines from Firebase
                for (QueryDocumentSnapshot doc : medicineSnapshot.getDocuments()) {
                    Map<String, Object> medicineData = doc.getData();
                    upcomingMedicines.add(medicineData);
                }

                Platform.runLater(() -> {
                    displayMedicineTimeGroups();
                });

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    showNoMedicinesMessage();
                });
            }
        }).start();
    }

    /**
     * Groups medicines by scheduled time and displays them as time-based groups.
     * This is more senior-friendly than individual cards.
     */
    private void displayMedicineTimeGroups() {
        upcomingMedicinesContainer.getChildren().clear();

        if (upcomingMedicines.isEmpty()) {
            showNoMedicinesMessage();
            return;
        }

        // Hide no medicines message
        noUpcomingMedicinesCard.setVisible(false);
        noUpcomingMedicinesCard.setManaged(false);

        // Group medicines by scheduled time
        Map<String, List<Map<String, Object>>> medicineGroups = groupMedicinesByTime();

        // Create time group displays
        for (Map.Entry<String, List<Map<String, Object>>> group : medicineGroups.entrySet()) {
            VBox timeGroup = createMedicineTimeGroup(group.getKey(), group.getValue());
            upcomingMedicinesContainer.getChildren().add(timeGroup);
        }

        // Start countdown updates for time groups
        startTimeGroupCountdownUpdates();
    }

    /**
     * Groups medicines by their scheduled time (hour:minute).
     * Returns a map where key is scheduled time and value is list of medicines.
     */
    private Map<String, List<Map<String, Object>>> groupMedicinesByTime() {
        final Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();

        for (final Map<String, Object> medicine : upcomingMedicines) {
            final String scheduledTime = medicine.get("nextScheduledTime") != null ?
                    medicine.get("nextScheduledTime").toString() : "Not scheduled";

            groups.computeIfAbsent(scheduledTime, k -> new ArrayList<>()).add(medicine);
        }

        return groups;
    }

    /**
     * Creates a time-based medicine group display.
     *
     * @param scheduledTime The scheduled time for this group
     * @param medicines List of medicines scheduled at this time
     * @return VBox containing the time group display
     */
    private VBox createMedicineTimeGroup(String scheduledTime, List<Map<String, Object>> medicines) {
        VBox timeGroup = new VBox(5);
        timeGroup.setAlignment(Pos.CENTER_LEFT);
        timeGroup.getStyleClass().add("medicine-time-group");

        // Create header with time and medicine info
        HBox groupHeader = new HBox(8);
        groupHeader.setAlignment(Pos.CENTER_LEFT);
        groupHeader.getStyleClass().add("time-group-header");

        // Time display section
        VBox timeDisplay = new VBox(1);
        timeDisplay.setAlignment(Pos.CENTER);
        timeDisplay.getStyleClass().add("time-display");

        // Calculate countdown to scheduled time
        String countdownText = calculateCountdownText(scheduledTime);
        Label countdownLabel = new Label(countdownText);
        countdownLabel.getStyleClass().add("group-countdown-text");

        Label scheduledLabel = new Label("at " + scheduledTime);
        scheduledLabel.getStyleClass().add("group-scheduled-time");

        timeDisplay.getChildren().addAll(countdownLabel, scheduledLabel);

        // Medicines in this group section
        VBox medicinesDisplay = new VBox(1);
        medicinesDisplay.setAlignment(Pos.CENTER_LEFT);
        medicinesDisplay.getStyleClass().add("medicines-in-group");

        // Medicine count
        final int medicineCount = medicines.size();
        String countText = medicineCount == 1 ? "Take 1 medicine:" : "Take " + medicineCount + " medicines:";
        Label countLabel = new Label(countText);
        countLabel.getStyleClass().add("medicine-count-text");

        // Medicine names with dosages
        final StringBuilder medicineNamesBuilder = new StringBuilder();
        for (int i = 0; i < medicines.size(); i++) {
            final Map<String, Object> med = medicines.get(i);
            String name = med.get("medicineName") != null ? med.get("medicineName").toString() : "Unknown";
            String dosage = med.get("dosage") != null ? med.get("dosage").toString() : "";

            medicineNamesBuilder.append(name);
            if (!dosage.isEmpty()) {
                medicineNamesBuilder.append(" ").append(dosage);
            }

            if (i < medicines.size() - 1) {
                medicineNamesBuilder.append(", ");
            }
        }

        Label namesLabel = new Label(medicineNamesBuilder.toString());
        namesLabel.getStyleClass().add("medicine-names-text");
        namesLabel.setWrapText(true);

        medicinesDisplay.getChildren().addAll(countLabel, namesLabel);

        groupHeader.getChildren().addAll(timeDisplay, medicinesDisplay);
        timeGroup.getChildren().add(groupHeader);

        // Store scheduled time for countdown updates
        timeGroup.setUserData(scheduledTime);

        return timeGroup;
    }

    /**
     * Calculates countdown text for a scheduled time.
     *
     * @param scheduledTime The scheduled time string (e.g., "4:45 PM")
     * @return Countdown text (e.g., "In 2 hours" or "In 30 minutes")
     */
    private String calculateCountdownText(String scheduledTime) {
        try {
            // Parse scheduled time (assuming today's date)
            LocalTime scheduled = LocalTime.parse(scheduledTime, DateTimeFormatter.ofPattern("h:mm a"));
            LocalTime now = LocalTime.now();

            long minutesDiff = ChronoUnit.MINUTES.between(now, scheduled);

            if (minutesDiff < 0) {
                // If time has passed today, assume it's tomorrow
                minutesDiff += 24 * 60; // Add 24 hours
            }

            if (minutesDiff < 60) {
                return "In " + minutesDiff + " minutes";
            } else {
                long hours = minutesDiff / 60;
                long minutes = minutesDiff % 60;
                if (minutes == 0) {
                    return "In " + hours + (hours == 1 ? " hour" : " hours");
                } else {
                    return "In " + hours + "h " + minutes + "m";
                }
            }
        } catch (Exception e) {
            return "Time pending";
        }
    }

    /**
     * Shows message when no upcoming medicines are scheduled.
     */
    private void showNoMedicinesMessage() {
        upcomingMedicinesContainer.getChildren().clear();
        noUpcomingMedicinesCard.setVisible(true);
        noUpcomingMedicinesCard.setManaged(true);
    }

    /**
     * Starts timeline for updating countdown timers on medicine time groups.
     * Updates every minute to keep countdowns accurate.
     */
    private void startTimeGroupCountdownUpdates() {
        Timeline countdownTimeline = new Timeline(
                new KeyFrame(Duration.minutes(1), e -> updateTimeGroupCountdowns())
        );
        countdownTimeline.setCycleCount(Animation.INDEFINITE);
        countdownTimeline.play();

        // Initial update
        updateTimeGroupCountdowns();
    }

    /**
     * Updates countdown displays on all medicine time groups.
     */
    private void updateTimeGroupCountdowns() {
        for (var node : upcomingMedicinesContainer.getChildren()) {
            if (node instanceof VBox) {
                VBox timeGroup = (VBox) node;
                String scheduledTime = (String) timeGroup.getUserData();
                updateTimeGroupCountdown(timeGroup, scheduledTime);
            }
        }
    }

    /**
     * Updates individual time group countdown based on scheduled time.
     *
     * @param timeGroup The time group VBox
     * @param scheduledTime The scheduled time for this group
     */
    private void updateTimeGroupCountdown(VBox timeGroup, String scheduledTime) {
        // Find the countdown label in the time group
        for (var child : timeGroup.getChildren()) {
            if (child instanceof HBox) {
                HBox groupHeader = (HBox) child;
                for (var headerChild : groupHeader.getChildren()) {
                    if (headerChild instanceof VBox && headerChild.getStyleClass().contains("time-display")) {
                        VBox timeDisplay = (VBox) headerChild;
                        // Update the first label (countdown text)
                        if (!timeDisplay.getChildren().isEmpty() &&
                                timeDisplay.getChildren().get(0) instanceof Label) {
                            Label countdownLabel = (Label) timeDisplay.getChildren().get(0);
                            String newCountdownText = calculateCountdownText(scheduledTime);
                            countdownLabel.setText(newCountdownText);

                            // Update styling based on urgency
                            updateCountdownStyling(countdownLabel, newCountdownText);
                        }
                        break;
                    }
                }
                break;
            }
        }
    }

    /**
     * Updates countdown styling based on urgency.
     */
    private void updateCountdownStyling(Label countdownLabel, String countdownText) {
        // Remove existing urgency classes
        countdownLabel.getStyleClass().removeAll("countdown-urgent", "countdown-soon", "countdown-normal");

        if (countdownText.contains("minute") && !countdownText.contains("hour")) {
            // Less than 1 hour - urgent
            countdownLabel.getStyleClass().add("countdown-urgent");
        } else if (countdownText.contains("1h") || countdownText.contains("1 hour")) {
            // 1-2 hours - soon
            countdownLabel.getStyleClass().add("countdown-soon");
        } else {
            // More than 2 hours - normal
            countdownLabel.getStyleClass().add("countdown-normal");
        }
    }

    /**
     * Starts medicine alert checker that runs every minute.
     * Shows alert banner when medicine is due.
     */
    private void startMedicineAlertChecker() {
        medicineCheckTimeline = new Timeline(
                new KeyFrame(Duration.minutes(1), e -> checkForMedicineAlerts())
        );
        medicineCheckTimeline.setCycleCount(Animation.INDEFINITE);
        medicineCheckTimeline.play();
    }

    /**
     * Checks if any medicine is due now and shows alert banner.
     */
    private void checkForMedicineAlerts() {
        final LocalTime now = LocalTime.now();

        for (final Map<String, Object> medicine : upcomingMedicines) {
            try {
                final String scheduledTimeStr = medicine.get("nextScheduledTime") != null ?
                        medicine.get("nextScheduledTime").toString() : "";

                if (!scheduledTimeStr.isEmpty()) {
                    final LocalTime scheduledTime = LocalTime.parse(scheduledTimeStr,
                            DateTimeFormatter.ofPattern("h:mm a"));

                    // Check if medicine is due (within 2 minutes of scheduled time)
                    long minutesDiff = Math.abs(ChronoUnit.MINUTES.between(now, scheduledTime));
                    if (minutesDiff <= 2) {
                        Platform.runLater(() -> showMedicineAlert(medicine));
                        return; // Show alert for first due medicine only
                    }
                }
            } catch (Exception e) {
                // Continue checking other medicines if parsing fails
                continue;
            }
        }

        // No medicines due - hide alert banner
        Platform.runLater(() -> {
            medicineAlertBanner.setVisible(false);
            medicineAlertBanner.setManaged(false);
        });
    }

    /**
     * Shows medicine alert banner when medicine is due.
     *
     * @param medicine The medicine that is due
     */
    private void showMedicineAlert(Map<String, Object> medicine) {
        String medicineName = medicine.get("medicineName") != null ?
                medicine.get("medicineName").toString() : "Unknown Medicine";
        String dosage = medicine.get("dosage") != null ?
                medicine.get("dosage").toString() : "";

        String alertText = "It's time to take your " + medicineName;
        if (!dosage.isEmpty()) {
            alertText += " " + dosage;
        }

        alertMedicineText.setText(alertText);
        medicineAlertBanner.setVisible(true);
        medicineAlertBanner.setManaged(true);
    }

    /**
     * Updates today's medicine summary with taken/remaining counts.
     */
    private void updateTodaySummary() {
        if (elderId == null) return;

        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();

                // Get today's medicine records
                // TODO: Query actual medicine taking records from Firebase for today
                // For now, calculate from upcoming medicines

                final int totalTodayMedicines = upcomingMedicines.size() + 3; // 3 already taken (sample)
                final int takenCount = 3; // Sample taken count
                final int remainingCount = upcomingMedicines.size();

                // Find next medicine time and name
                final String nextTime;
                final String nextMedicine;

                if (!upcomingMedicines.isEmpty()) {
                    Map<String, Object> nextMed = upcomingMedicines.get(0);
                    nextTime = nextMed.get("nextScheduledTime") != null ?
                            nextMed.get("nextScheduledTime").toString() : "Not scheduled";
                    nextMedicine = nextMed.get("medicineName") != null ?
                            nextMed.get("medicineName").toString() : "Unknown";
                } else {
                    nextTime = "No medicines";
                    nextMedicine = "";
                }

                Platform.runLater(() -> {
                    medicinesTakenCount.setText(String.valueOf(takenCount));
                    medicinesRemainingCount.setText(String.valueOf(remainingCount));
                    nextDoseTime.setText(nextTime);
                    nextDoseName.setText(nextMedicine);
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    medicinesTakenCount.setText("0");
                    medicinesRemainingCount.setText("0");
                    nextDoseTime.setText("No medicines");
                    nextDoseName.setText("");
                });
            }
        }).start();
    }

    /**
     * Handles when elder marks medicine as taken from alert banner.
     */
    @FXML
    private void handleTakeMedicine() {
        // TODO: Mark medicine as taken in Firebase
        // Hide alert banner
        medicineAlertBanner.setVisible(false);
        medicineAlertBanner.setManaged(false);

        // Refresh medicine display and summary
        loadMedicineSchedule();
        updateTodaySummary();

        System.out.println("✅ Medicine marked as taken");
    }

    /**
     * Handles snooze request - remind again in 5 minutes.
     */
    @FXML
    private void handleSnoozeMedicine() {
        // TODO: Set reminder for 5 minutes later in Firebase
        // Hide alert banner for now
        medicineAlertBanner.setVisible(false);
        medicineAlertBanner.setManaged(false);

        System.out.println("⏰ Medicine snoozed for 5 minutes");
    }

    /**
     * Handles emergency help requests by finding caretakers and sending emails.
     */
    @FXML
    private void handleHelpRequest() {
        if (elderId == null) {
            System.err.println("⚠️ Elder details not initialized (elderId). Cannot send help request.");
            return;
        }

        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                // 1) Primary: query caretakers by elderId
                QuerySnapshot caretakersSnapshot = users
                        .whereEqualTo("role", "caretaker")
                        .whereEqualTo("elderId", elderId)
                        .get()
                        .get();

                List<String> caretakerEmails = new ArrayList<>();
                for (QueryDocumentSnapshot doc : caretakersSnapshot.getDocuments()) {
                    String email = doc.getString("email");
                    if (email != null && !email.isEmpty()) {
                        caretakerEmails.add(email);
                    }
                }

                // 2) Fallback: try elder.caretakers field
                if (caretakerEmails.isEmpty()) {
                    System.out.println("Primary query returned no caretakers. Trying fallback...");
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
                                        String email = caretDocSnap.getString("email");
                                        if (email != null && !email.isEmpty()) {
                                            caretakerEmails.add(email);
                                        }
                                    }
                                } catch (InterruptedException | ExecutionException ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                    } catch (InterruptedException | ExecutionException ex) {
                        ex.printStackTrace();
                    }
                }

                // 3) Send emails
                if (!caretakerEmails.isEmpty()) {
                    System.out.println("Found caretakers: " + caretakerEmails);
                    EmailService.sendHelpRequestEmail(elderUsername, caretakerEmails);
                } else {
                    System.out.println("⚠️ No caretakers found for elder: " + elderId);
                }

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Navigate to complete medicine schedule view.
     */
    @FXML
    private void handleViewMedicine() {
        switchScene("/fxml/medicine_schedule.fxml");
    }
    // Below this dont have fxml file.they can be done diffrently.
    /**
     * Navigate to add new medicine page.
     */
    @FXML
    private void handleAddMedicine() {
        switchScene("/fxml/add_medicine.fxml");
    }

    /**
     * Navigate to medicine history/logs page.
     */
    @FXML
    private void handleMedicineHistory() {
        switchScene("/fxml/medicine_history.fxml");
    }

    /**
     * Navigate to user profile page.
     */
    @FXML
    private void handleProfile() {
        switchScene("/fxml/elder_profile.fxml");
    }

    /**
     * Navigate to settings page.
     */
    @FXML
    private void handleSettings() {
        switchScene("/fxml/elder_settings.fxml");
    }

    /**
     * Logs out elder and returns to login screen.
     * Stops all running timelines before logout.
     */
    @FXML
    private void handleLogout() {
        // Stop all running timelines
        if (clockTimeline != null) clockTimeline.stop();
        if (medicineCheckTimeline != null) medicineCheckTimeline.stop();

        switchScene("/fxml/login.fxml");
    }

    /**
     * Helper method to switch scenes safely with error handling.
     *
     * @param fxmlPath Path to the FXML file to load
     */
    private void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) helpRequestButton.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}