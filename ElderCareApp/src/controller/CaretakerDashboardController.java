package controller;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.scene.control.Alert;
import javafx.scene.layout.Region;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CaretakerDashboardController {

    @FXML private Label caretakerNameLabel;
    @FXML private StackPane contentPane;

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

    // sample stats data
    private int todayDoses = 5;
    private int completed = 3;

    public void initializeCaretaker(String username) {
        caretakerNameLabel.setText(username);
        if (username != null && !username.trim().isEmpty()) {
            String[] nameParts = username.split(" ");
            String initials = "";
            if (nameParts.length > 0) {
                initials += nameParts[0].charAt(0);
                if (nameParts.length > 1) {
                    initials += nameParts[nameParts.length - 1].charAt(0);
                }
            }
            avatarInitialsLabel.setText(initials.toUpperCase());
        }
    }

    @FXML
    public void initialize() {
        clearActiveButtons();
        handleViewToday();
    }

    /* ---------- Stats ---------- */
    private void refreshStats() {
        todayDosesLabel.setText(String.valueOf(todayDoses));
        completedLabel.setText(String.valueOf(completed));
        int pending = Math.max(0, todayDoses - completed);
        pendingLabel.setText(String.valueOf(pending));
        double adherence = todayDoses == 0 ? 0.0 : ((double) completed) / todayDoses;
        if (adherenceIndicator != null) adherenceIndicator.setProgress(adherence);
        if (adherencePercentLabel != null) adherencePercentLabel.setText(String.format("%.0f%%", adherence * 100));
        LocalDateTime next = LocalDateTime.now().plusHours(2);
        if (nextDoseLabel != null) nextDoseLabel.setText(next.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm")));
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

    /* ---------- Content loader ---------- */
    private void loadIntoContentWithFade(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent node = loader.load();

            if (!contentPane.getChildren().isEmpty()) {
                FadeTransition ftOut = new FadeTransition(Duration.millis(160), contentPane.getChildren().get(0));
                ftOut.setFromValue(1.0);
                ftOut.setToValue(0.0);
                ftOut.setOnFinished(evt -> {
                    contentPane.getChildren().clear();
                    contentPane.getChildren().add(node);
                    FadeTransition ftIn = new FadeTransition(Duration.millis(220), node);
                    ftIn.setFromValue(0.0);
                    ftIn.setToValue(1.0);
                    ftIn.play();
                });
                ftOut.play();
            } else {
                contentPane.getChildren().add(node);
                FadeTransition ftIn = new FadeTransition(Duration.millis(220), node);
                ftIn.setFromValue(0.0);
                ftIn.setToValue(1.0);
                ftIn.play();
            }
        } catch (IOException e) {
            showError("Could not load screen: " + fxmlPath + "\n" + e.getMessage());
        }
    }

    /* ---------- Button Handlers ---------- */

    @FXML
    private void handleCreateSchedule() {
        setActiveButton(createScheduleBtn);
        loadIntoContentWithFade("/fxml/medicine_schedule.fxml");
    }

    @FXML
    private void handleViewToday() {
        setActiveButton(viewTodayBtn);
        loadIntoContentWithFade("/fxml/todays_schedule.fxml");
    }

    @FXML
    private void handleModifySchedule() {
        setActiveButton(modifyScheduleBtn);
        loadIntoContentWithFade("/fxml/edit_medicine_doses.fxml");
    }

    @FXML
    private void handlePastDoses() {
        setActiveButton(pastDoseBtn);
        loadIntoContentWithFade("/fxml/medicine_history.fxml");
    }

    // New feature handlers from teammate's dashboard
    @FXML
    private void handleDoseIntakeHistory() {
        setActiveButton(doseIntakeHistoryBtn);
        loadIntoContentWithFade("/fxml/dose_intake_history.fxml");
    }


    @FXML
    private void handleSignOut() {
        try {
            Parent loginRoot = FXMLLoader.load(getClass().getResource("/fxml/login.fxml"));
            Stage stage = (Stage) contentPane.getScene().getWindow();
            stage.getScene().setRoot(loginRoot);
        } catch (IOException e) {
            showError("Could not load login screen: " + e.getMessage());
        }
    }

    /* ---------- Alerts ---------- */
    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText("Error");
        a.setContentText(msg);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.showAndWait();
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.showAndWait();
    }
}