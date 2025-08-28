package controller;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.Duration;
import utils.FirestoreService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<String> roleChoiceBox;
    @FXML private Label messageLabel;

    @FXML
    public void initialize() {
        roleChoiceBox.getItems().clear();
        roleChoiceBox.getItems().addAll("Elder", "Caretaker");
        roleChoiceBox.setValue("Elder"); // Default selection
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String role = roleChoiceBox.getValue();

        if (username.isEmpty() || password.isEmpty() || role == null) {
            messageLabel.setText("Please fill all fields and select a role.");
            return;
        }

        Firestore db = FirestoreService.getFirestore();
        CollectionReference usersRef = db.collection("users");

        ApiFuture<QuerySnapshot> future = usersRef
                .whereEqualTo("username", username)
                .whereEqualTo("password", password)
                .whereEqualTo("role", role.toLowerCase())
                .get();

        new Thread(() -> {
            try {
                List<QueryDocumentSnapshot> users = future.get().getDocuments();
                if (users.isEmpty()) {
                    Platform.runLater(() -> messageLabel.setText("Invalid credentials or role."));
                } else {
                    DocumentReference userDoc = users.get(0).getReference();
                    userDoc.update("loggedIn", true, "lastLogin", System.currentTimeMillis());

                    Platform.runLater(() -> {
                        messageLabel.setText("Login successful!");
                        if (role.equalsIgnoreCase("Elder")) {
                            // pass the username to the elder dashboard so it can load details
                            switchSceneWithFade("elder_dashboard.fxml", username);
                        } else {
                            // caretaker doesn't need the username passed
                            switchSceneWithFade("caretaker_dashboard.fxml",username);
                        }
                    });
                }
            } catch (InterruptedException | ExecutionException e) {
                Platform.runLater(() -> messageLabel.setText("Login failed: " + e.getMessage()));
                e.printStackTrace();
            }
        }).start();
    }

    @FXML
    private void handleRegistration() {
        switchSceneWithFade("registration.fxml");
    }

    // Overload used when we need to pass the logged-in username to the next controller
    private void switchSceneWithFade(String fxmlFile, String loggedInUsername) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + fxmlFile));
            Parent root = loader.load();

            // If the loaded controller is ElderDashboardController, call initializeElder
            Object controller = loader.getController();
            if (controller instanceof ElderDashboardController) {
                ((ElderDashboardController) controller).initializeElder(loggedInUsername);
            }else if(controller instanceof CaretakerDashboardController) {
            	((CaretakerDashboardController)controller).initializeCaretaker(loggedInUsername);
            }

            Stage stage = (Stage) usernameField.getScene().getWindow();
            Scene scene = new Scene(root);

            FadeTransition ft = new FadeTransition(Duration.millis(400), root);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();

            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            messageLabel.setText("Failed to load scene: " + fxmlFile);
        }
    }

    // Existing overload for calls that don't need to pass username
    private void switchSceneWithFade(String fxmlFile) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + fxmlFile));
            Parent root = loader.load();

            Stage stage = (Stage) usernameField.getScene().getWindow();
            Scene scene = new Scene(root);

            FadeTransition ft = new FadeTransition(Duration.millis(400), root);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();

            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            messageLabel.setText("Failed to load scene: " + fxmlFile);
        }
    }
}
