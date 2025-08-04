package controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.animation.FadeTransition;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<String> roleChoiceBox;
    @FXML private Label messageLabel;

    @FXML
    public void initialize() {
        roleChoiceBox.getItems().addAll("Elder", "Caretaker");
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText();
        String password = passwordField.getText();
        String role = roleChoiceBox.getValue();

        if (username.isEmpty() || password.isEmpty() || role == null) {
            messageLabel.setText("Please fill all fields and select a role.");
            return;
        }

        // Simulate login
        messageLabel.setText("Login successful!");

        // TODO: Navigate to dashboard or main app screen here
        // e.g., loadDashboard();
    }

    @FXML
    private void handleRegistration() {
        try {
            URL resourceUrl = getClass().getResource("/fxml/Registration.fxml");
            if (resourceUrl == null) {
                messageLabel.setText("Could not find registration page.");
                return;
            }

            FXMLLoader loader = new FXMLLoader(resourceUrl);
            Pane root = loader.load();

            Stage stage = (Stage) usernameField.getScene().getWindow();
            Scene scene = new Scene(root);

            FadeTransition ft = new FadeTransition(Duration.millis(400), root);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();

            stage.setScene(scene);

        } catch (IOException e) {
            e.printStackTrace();
            messageLabel.setText("Failed to load registration page.");
        }
    }
}
