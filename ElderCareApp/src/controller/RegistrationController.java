package controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.animation.FadeTransition;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;

public class RegistrationController {

    @FXML private TextField nameField;
    @FXML private TextField emailField;
    @FXML private TextField phoneField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<String> roleComboBox;
    @FXML private TextField pairingCodeField;
    @FXML private TextField usernameField;

    @FXML private Label pairingCodeLabel;
    @FXML private Label errorLabel;
    @FXML private Button registerButton;

    @FXML private HBox emailBox, pairingCodeBox;

    @FXML
    public void initialize() {
        roleComboBox.getItems().addAll("Elder", "Caretaker");

        emailBox.setVisible(false);
        emailBox.setManaged(false);
        pairingCodeBox.setVisible(false);
        pairingCodeBox.setManaged(false);

        roleComboBox.setOnAction(e -> {
            boolean isElder = "Elder".equals(roleComboBox.getValue());

            emailBox.setVisible(!isElder);
            emailBox.setManaged(!isElder);

            pairingCodeBox.setVisible(!isElder);
            pairingCodeBox.setManaged(!isElder);
        });
    }

    @FXML
    public void handleRegistration() {
        System.out.println("Register button clicked!");

        String name = nameField.getText();
        String username = usernameField.getText();
        String password = passwordField.getText();
        String role = roleComboBox.getValue();

        if (name.isEmpty() || username.isEmpty() || password.isEmpty() || role == null) {
            errorLabel.setText("Please fill all required fields.");
            return;
        }

        // Dummy data logic
        String dataToPass = role.equalsIgnoreCase("Elder") ? "PAIR1234" : name;

        goToPostRegistration(role, dataToPass);
    }

    private void goToPostRegistration(String role, String data) {
        String fxmlPath = "D:/projectBrightImpact/Project-Bright-Impact/ElderCareApp/src/fxml/post_registration.fxml";

        try {
            File fxmlFile = new File(fxmlPath);
            if (!fxmlFile.exists()) {
                errorLabel.setText("Post-registration FXML not found.");
                return;
            }

            FXMLLoader loader = new FXMLLoader(fxmlFile.toURI().toURL());
            Pane root = loader.load();

            PostRegistrationController controller = loader.getController();
            controller.initializeView(role, data);

            Stage stage = (Stage) registerButton.getScene().getWindow();
            Scene scene = new Scene(root);

            FadeTransition ft = new FadeTransition(Duration.millis(400), root);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();

            stage.setScene(scene);

        } catch (IOException e) {
            e.printStackTrace();
            errorLabel.setText("Failed to load post-registration screen.");
        }
    }
    // dashboard code add hobe
}
