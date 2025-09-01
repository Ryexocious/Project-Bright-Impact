package controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.scene.control.Alert;
import javafx.scene.layout.Region;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import javafx.application.Platform;
import javafx.scene.Scene;
import utils.FirestoreService;
import utils.SessionManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
public class CaretakerDashboardController {

    @FXML private Label caretakerNameLabel;
    @FXML private StackPane contentPane;

    // The following may be null if the corresponding controls are in a child FXML
    // loaded into contentPane rather than in the main caretaker_dashboard.fxml
    @FXML private Label todayDosesLabel;
    @FXML private Label completedLabel;
    @FXML private Label pendingLabel;
    @FXML private ProgressIndicator adherenceIndicator;
    @FXML private Label adherencePercentLabel;
    @FXML private Label nextDoseLabel;

    @FXML private Button createScheduleBtn;
    @FXML private Button viewTodayBtn;
    @FXML private Button modifyScheduleBtn;
    @FXML private Button pastDoseBtn;
    @FXML private Button doseIntakeHistoryBtn;
    @FXML private Button vitalsMonitorBtn;
    @FXML private Label avatarInitialsLabel;
    @FXML private Button notificationBell;
    @FXML private Label notificationCount;


    // sample stats data
    private int todayDoses = 5;
    private int completed = 3;

    @FXML
    public void initialize() {
        clearActiveButtons();
        // Load the default view immediately (this may populate UI controls inside child FXML)
        handleViewToday();
        // Start loading caretaker name (will update UI when ready)
        loadCaretakerNameFromSession();

        notificationCount.setVisible(false);
        notificationCount.setText("");
        loadNotifications();


    }

    private void loadCaretakerNameFromSession() {
        String uid = SessionManager.getCurrentUserId();
        if (uid == null || uid.trim().isEmpty()) {
            initializeCaretaker("");
            return;
        }

        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                DocumentSnapshot doc = db.collection("users").document(uid).get().get();

                String name = null;
                if (doc != null && doc.exists()) {
                    for (String field : Arrays.asList("displayName", "name", "fullName", "username")) {
                        Object o = doc.get(field);
                        if (o instanceof String && !((String)o).trim().isEmpty()) {
                            name = (String) o;
                            break;
                        }
                    }
                }

                final String finalName;
                if (name == null || name.trim().isEmpty()) {
                    finalName = uid.length() > 8 ? uid.substring(0, 8) : uid;
                } else {
                    finalName = name;
                }

                Platform.runLater(() -> initializeCaretaker(finalName));
            } catch (Exception e) {
                final String fallback = uid.length() > 8 ? uid.substring(0, 8) : uid;
                Platform.runLater(() -> initializeCaretaker(fallback));
            }
        }).start();
    }

    /**
     * Update profile name & avatar initials.
     * Will only attempt to refresh stats if the stat controls are present (non-null).
     */
    public void initializeCaretaker(String username) {
        if (caretakerNameLabel != null) caretakerNameLabel.setText(username == null ? "" : username);
        if (avatarInitialsLabel != null) {
            if (username != null && !username.trim().isEmpty()) {
                String[] nameParts = username.trim().split("\\s+");
                String initials = "";
                if (nameParts.length > 0 && nameParts[0].length() > 0) {
                    initials += nameParts[0].charAt(0);
                    if (nameParts.length > 1 && nameParts[nameParts.length - 1].length() > 0) {
                        initials += nameParts[nameParts.length - 1].charAt(0);
                    }
                } else if (username.length() >= 1) {
                    initials = username.substring(0, 1);
                }
                avatarInitialsLabel.setText(initials.toUpperCase());
            } else {
                avatarInitialsLabel.setText("");
            }
        }

        // Only refresh stats when the stat controls exist (defensive to avoid NPE)
        if (todayDosesLabel != null || completedLabel != null || pendingLabel != null
                || adherenceIndicator != null || adherencePercentLabel != null || nextDoseLabel != null) {
            refreshStats();
        }
    }

    /* ---------- Stats (defensive: null-checks) ---------- */
    private void refreshStats() {
        // compute derived values
        int pending = Math.max(0, todayDoses - completed);
        double adherence = todayDoses == 0 ? 0.0 : ((double) completed) / todayDoses;
        LocalDateTime next = LocalDateTime.now().plusHours(2);
        String nextFormatted = next.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm"));

        // update only controls that are non-null
        if (todayDosesLabel != null) todayDosesLabel.setText(String.valueOf(todayDoses));
        if (completedLabel != null) completedLabel.setText(String.valueOf(completed));
        if (pendingLabel != null) pendingLabel.setText(String.valueOf(pending));
        if (adherenceIndicator != null) adherenceIndicator.setProgress(adherence);
        if (adherencePercentLabel != null) adherencePercentLabel.setText(String.format("%.0f%%", adherence * 100));
        if (nextDoseLabel != null) nextDoseLabel.setText(nextFormatted);
    }

    /* ---------- UI helpers ---------- */
    private void clearActiveButtons() {
        try {
            createScheduleBtn.getStyleClass().remove("active");
            viewTodayBtn.getStyleClass().remove("active");
            modifyScheduleBtn.getStyleClass().remove("active");
            pastDoseBtn.getStyleClass().remove("active");
            doseIntakeHistoryBtn.getStyleClass().remove("active");
            vitalsMonitorBtn.getStyleClass().remove("active");
        } catch (Exception ignored) {}
    }

    private void setActiveButton(Button btn) {
        clearActiveButtons();
        if (btn != null && !btn.getStyleClass().contains("active")) {
            btn.getStyleClass().add("active");
        }
    }

    /* ---------- Content loader (no animation) ---------- */
    private void loadIntoContent(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent node = loader.load();
            contentPane.getChildren().clear();
            contentPane.getChildren().add(node);

            // After loading content, try refreshing stats again (if controls are now present)
            // This is defensive: either the controls are part of this controller's FXML or a child controller.
            // If they are part of this controller's FXML and were injected lazily, this helps update them.
            // If stats controls belong to the child controller, consider exposing an API on that child controller
            // and calling it here (loader.getController()) — optional improvement.
            refreshStats();
        } catch (IOException e) {
            showError("Could not load screen: " + fxmlPath + "\n" + e.getMessage());
        }
    }

    /* ---------- Button Handlers ---------- */

    @FXML
    private void handleCreateSchedule() {
        setActiveButton(createScheduleBtn);
        loadIntoContent("/fxml/medicine_schedule.fxml");
    }

    @FXML
    private void handleViewToday() {
        setActiveButton(viewTodayBtn);
        loadIntoContent("/fxml/todays_schedule.fxml");
    }

    @FXML
    private void handleModifySchedule() {
        setActiveButton(modifyScheduleBtn);
        loadIntoContent("/fxml/edit_medicine_doses.fxml");
    }

    @FXML
    private void handlePastDoses() {
        setActiveButton(pastDoseBtn);
        loadIntoContent("/fxml/medicine_history.fxml");
    }

    @FXML
    private void handleDoseIntakeHistory() {
        setActiveButton(doseIntakeHistoryBtn);
        loadIntoContent("/fxml/dose_intake_history.fxml");
    }

    @FXML
    private void handleSignOut() {
        new Thread(() -> {
            try {
                String uid = SessionManager.getCurrentUserId();

                if (uid != null) {
                    try {
                        Firestore db = FirestoreService.getFirestore();
                        DocumentReference userRef = db.collection("users").document(uid);

                        Map<String, Object> updates = new HashMap<>();
                        updates.put("lastSessionId", FieldValue.delete());

                        try {
                            userRef.update(updates).get();
                        } catch (Exception e) {
                            System.out.println("Warning: failed to clear remote session on logout: " + e.getMessage());
                        }
                    } catch (Exception e) {
                        System.out.println("Warning: logout remote cleanup failed: " + e.getMessage());
                    }
                }
            } finally {
                SessionManager.clear();
                Platform.runLater(() -> {
                    try {
                        Parent root = FXMLLoader.load(getClass().getResource("/fxml/login.fxml"));
                        Stage stage = (Stage) Stage.getWindows().filtered(window -> window.isShowing()).get(0);
                        stage.setScene(new Scene(root));
                        stage.centerOnScreen();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                });
            }
        }).start();
    }

    /* ---------- Alerts ---------- */
    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText("Error");
        a.setContentText(msg);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.showAndWait();
    }
    private void loadNotifications() {
        String caretakerId = SessionManager.getCurrentUserId();
        if (caretakerId == null) return;

        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                var snap = db.collection("notifications")
                        .whereEqualTo("caretakerId", caretakerId)
                        .whereEqualTo("read", false)
                        .get()
                        .get();

                List<QueryDocumentSnapshot> docs = snap.getDocuments(); // Use QueryDocumentSnapshot
                List<String> messages = new ArrayList<>();
                for (QueryDocumentSnapshot doc : docs) {
                    messages.add(doc.getString("message"));
                }

                Platform.runLater(() -> showNotifications(messages, docs));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void showNotifications(List<String> messages, List<? extends DocumentSnapshot> docs) {
        Platform.runLater(() -> {
            if (messages.isEmpty()) {
                notificationCount.setVisible(false); // hide badge
                notificationCount.setText("");
            } else {
                notificationCount.setVisible(true);  // show badge
                notificationCount.setText(String.valueOf(messages.size()));
            }
        });

        // Set the bell action
        notificationBell.setOnAction(evt -> {
            if (messages.isEmpty()) return; // nothing to show

            Alert alert = new Alert(Alert.AlertType.INFORMATION, String.join("\n\n", messages));
            alert.setHeaderText("Notifications");
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait();

            // After viewing, mark as read
            markAsRead(docs);

            // Hide the badge after marking read
            Platform.runLater(() -> {
                notificationCount.setVisible(false);
                notificationCount.setText("");
            });
        });
    }


    private void markAsRead(List<? extends DocumentSnapshot> docs) {
        for (DocumentSnapshot doc : docs) {
            try {
                doc.getReference().update("read", true).get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
