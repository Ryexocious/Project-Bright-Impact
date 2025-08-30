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
import utils.SessionManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // find user by username/password/role
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
                    return;
                }

                // Use the first matched document as the authenticated user
                DocumentReference userRef = users.get(0).getReference();
                String userId = userRef.getId();
                String roleLower = role.toLowerCase();

                // Create session locally and persist small session info to Firestore.
                String sessionId = SessionManager.createSession(userId, roleLower);

                // Write lastLogin & lastSessionId fields to user doc
                Map<String,Object> sessionFields = new HashMap<>();
                sessionFields.put("lastLogin", System.currentTimeMillis());
                sessionFields.put("lastSessionId", sessionId);
                // Optionally keep loggedIn for backward compatibility (comment out if you remove loggedIn usage everywhere)
                // sessionFields.put("loggedIn", true);

                try {
                    userRef.update(sessionFields).get();
                } catch (Exception e) {
                    // If update fails (rare), we still continue with local session; but log
                    System.out.println("Warning: failed to write lastSessionId to Firestore: " + e.getMessage());
                }

                Platform.runLater(() -> {
                    messageLabel.setText("Login successful!");
                    Stage stage = (Stage) usernameField.getScene().getWindow();

                    try {
                        if (role.equalsIgnoreCase("Elder")) {
                            // IMPORTANT: load via FXMLLoader so we can get the controller and call initializeElder(...)
                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/elder_dashboard.fxml"));
                            Parent root = loader.load();

                            // Get controller and pass username so ElderDashboardController can initialize
                            Object controller = loader.getController();
                            if (controller instanceof ElderDashboardController) {
                                ElderDashboardController elderCtrl = (ElderDashboardController) controller;
                                elderCtrl.initializeElder(username); // pass the logged-in username
                            }

                            FadeTransition ft = new FadeTransition(Duration.millis(400), root);
                            ft.setFromValue(0);
                            ft.setToValue(1);
                            ft.play();

                            Scene scene = new Scene(root);
                            if (stage != null) {
                                stage.setScene(scene);
                                stage.centerOnScreen();
                            }
                        } else {
                            // Keep caretaker behavior unchanged
                            switchSceneWithFade("caretaker_dashboard.fxml");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        messageLabel.setText("Failed to load dashboard.");
                    }
                });

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

    private void switchSceneWithFade(String fxmlFile) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + fxmlFile));
            Parent root = loader.load();

            // Controllers that need to know who is logged in should use SessionManager.getCurrentUserId()
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
