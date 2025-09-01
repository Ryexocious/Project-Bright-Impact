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
import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.EventListener;
import com.google.cloud.firestore.Query;
import javafx.application.Platform;
import javafx.scene.Scene;
import utils.FirestoreService;
import utils.SessionManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;

/**
 * Caretaker Dashboard controller — updated to fix notification bell / badge handling.
 *
 * Key changes:
 * - single shared, synchronized `currentUnreadDocs` list that's updated on initial load and from realtime listener
 * - bell action uses a snapshot copy of currentUnreadDocs at click time, and marking read updates UI immediately
 * - seenNotificationIds still prevents popup for historic notifications on initial load
 */
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

    // snapshot listener handle for notifications
    private ListenerRegistration notificationsListener = null;
    // track which notification document IDs we've seen so we don't popup on initial load
    private final Set<String> seenNotificationIds = new HashSet<>();

    // CURRENT unread docs (kept up-to-date). Access guarded by notifLock.
    private final Object notifLock = new Object();
    private List<DocumentSnapshot> currentUnreadDocs = new ArrayList<>();

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

        // defensive UI init
        if (notificationCount != null) {
            notificationCount.setVisible(false);
            notificationCount.setText("");
        }

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
                final String fallback = SessionManager.getCurrentUserId() == null ? "" :
                        (SessionManager.getCurrentUserId().length() > 8 ? SessionManager.getCurrentUserId().substring(0, 8) : SessionManager.getCurrentUserId());
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

        // Attach realtime notifications listener after caretaker name is loaded/initialized
        attachNotificationsListener();
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

    /**
     * Load notifications once and also attach a real-time listener so caretakers get new notifications immediately.
     */
    private void loadNotifications() {
        String caretakerId = SessionManager.getCurrentUserId();
        if (caretakerId == null) return;

        // load current unread notifications once (initial)
        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                var snap = db.collection("notifications")
                        .whereEqualTo("caretakerId", caretakerId)
                        .whereEqualTo("read", false)
                        .orderBy("timestamp")
                        .get()
                        .get();

                List<QueryDocumentSnapshot> docs = snap.getDocuments();
                List<String> messages = new ArrayList<>();
                synchronized (notifLock) {
                    currentUnreadDocs.clear();
                    for (QueryDocumentSnapshot doc : docs) {
                        messages.add(doc.getString("message"));
                        currentUnreadDocs.add(doc);
                        // Mark these as seen so we don't popup on initial load
                        seenNotificationIds.add(doc.getId());
                    }
                }

                // show the initial bell UI state and set bell action
                Platform.runLater(() -> showNotifications(messages, docs));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Attach a Firestore snapshot listener to notifications for the current caretaker so the UI shows new
     * unread notifications immediately (no re-login required).
     */
    private void attachNotificationsListener() {
        String caretakerId = SessionManager.getCurrentUserId();
        if (caretakerId == null) return;

        // detach old listener if present
        try {
            if (notificationsListener != null) {
                notificationsListener.remove();
                notificationsListener = null;
            }
        } catch (Exception ignored) {}

        Firestore db = FirestoreService.getFirestore();
        Query q = db.collection("notifications")
                .whereEqualTo("caretakerId", caretakerId)
                .whereEqualTo("read", false)
                .orderBy("timestamp", Query.Direction.ASCENDING);

        // Attach a realtime listener
        notificationsListener = q.addSnapshotListener((snap, err) -> {
            if (err != null) {
                System.err.println("Notifications listener error: " + err.getMessage());
                return;
            }
            if (snap == null) return;

            List<String> messages = new ArrayList<>();
            List<DocumentSnapshot> docs = new ArrayList<>();

            for (DocumentSnapshot d : snap.getDocuments()) {
                docs.add(d);
                String msg = d.getString("message");
                if (msg != null) messages.add(msg);
            }

            // Update shared current unread docs atomically
            synchronized (notifLock) {
                currentUnreadDocs.clear();
                currentUnreadDocs.addAll(docs);
            }

            // Update badge UI
            Platform.runLater(() -> {
                if (messages.isEmpty()) {
                    if (notificationCount != null) {
                        notificationCount.setVisible(false);
                        notificationCount.setText("");
                    }
                } else {
                    if (notificationCount != null) {
                        notificationCount.setVisible(true);
                        notificationCount.setText(String.valueOf(messages.size()));
                    }
                }
            });

            // Determine which docs are newly added (not seen before); show alert for them only
            List<String> newly = new ArrayList<>();
            List<DocumentSnapshot> newlyDocs = new ArrayList<>();
            for (DocumentSnapshot d : docs) {
                if (!seenNotificationIds.contains(d.getId())) {
                    String m = d.getString("message");
                    if (m != null) newly.add(m);
                    newlyDocs.add(d);
                    seenNotificationIds.add(d.getId());
                }
            }

            if (!newly.isEmpty()) {
                Platform.runLater(() -> {
                    // show grouped alert for new notifications
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, String.join("\n\n", newly));
                    alert.setHeaderText("New notifications");
                    alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
                    alert.showAndWait();

                    // After user dismisses popup for newly arrived notifications, mark those newlyDocs as read?
                    // NOTE: we do NOT auto-mark them here — original behavior was to just popup.
                    // If desired, you can mark them here; current behavior keeps them unread until bell is pressed.
                });
            }

            // Always (re)set the bell action using the current unread docs (so bell is always up-to-date)
            Platform.runLater(() -> installBellAction());
        });
    }

    /**
     * Show notifications UI based on a one-time list (used by initial load).
     * It also installs the bell click action to use current unread docs at click-time.
     */
    private void showNotifications(List<String> messages, List<? extends DocumentSnapshot> docs) {
        // Update badge
        Platform.runLater(() -> {
            if (messages == null || messages.isEmpty()) {
                if (notificationCount != null) {
                    notificationCount.setVisible(false);
                    notificationCount.setText("");
                }
            } else {
                if (notificationCount != null) {
                    notificationCount.setVisible(true);
                    notificationCount.setText(String.valueOf(messages.size()));
                }
            }
        });

        // Ensure bell has an up-to-date action
        Platform.runLater(() -> installBellAction());
    }

    /**
     * Install/refresh the notification bell click handler. The handler captures a *snapshot copy*
     * of the current unread docs at the moment of click and then marks those as read.
     */
    private void installBellAction() {
        if (notificationBell == null) return;

        notificationBell.setOnAction(evt -> {
            List<DocumentSnapshot> docsSnapshot;
            synchronized (notifLock) {
                docsSnapshot = new ArrayList<>(currentUnreadDocs);
            }

            if (docsSnapshot.isEmpty()) {
                // nothing to show
                return;
            }

            List<String> messages = new ArrayList<>();
            for (DocumentSnapshot d : docsSnapshot) {
                String m = d.getString("message");
                if (m != null) messages.add(m);
            }

            Alert alert = new Alert(Alert.AlertType.INFORMATION, String.join("\n\n", messages));
            alert.setHeaderText("Notifications");
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait();

            // After viewing, mark those snapshot docs as read (firestore updates) and immediately update UI.
            markAsReadAndClear(docsSnapshot);
        });
    }

    /**
     * Mark the provided docs as read (in Firestore), clear them from currentUnreadDocs, and update the badge immediately.
     * This waits for each update to complete (best-effort).
     */
    private void markAsReadAndClear(List<DocumentSnapshot> docsToMark) {
        if (docsToMark == null || docsToMark.isEmpty()) return;

        new Thread(() -> {
            Firestore db = FirestoreService.getFirestore();
            boolean anySuccess = false;

            for (DocumentSnapshot doc : docsToMark) {
                try {
                    doc.getReference().update("read", true).get();
                    anySuccess = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Remove marked ids from currentUnreadDocs and update badge immediately on FX thread.
            synchronized (notifLock) {
                // Remove docs whose id matches docsToMark
                Set<String> idsToRemove = new HashSet<>();
                for (DocumentSnapshot d : docsToMark) idsToRemove.add(d.getId());

                List<DocumentSnapshot> newList = new ArrayList<>();
                for (DocumentSnapshot d : currentUnreadDocs) {
                    if (!idsToRemove.contains(d.getId())) newList.add(d);
                }
                currentUnreadDocs = newList;
            }

            Platform.runLater(() -> {
                int remaining;
                synchronized (notifLock) {
                    remaining = currentUnreadDocs.size();
                }
                if (remaining <= 0) {
                    if (notificationCount != null) {
                        notificationCount.setVisible(false);
                        notificationCount.setText("");
                    }
                } else {
                    if (notificationCount != null) {
                        notificationCount.setVisible(true);
                        notificationCount.setText(String.valueOf(remaining));
                    }
                }
                // re-install bell action to ensure it uses latest docs next time
                installBellAction();
            });
        }).start();
    }
}
