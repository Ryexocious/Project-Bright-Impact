package controller;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import utils.EmailService;
import utils.FirestoreService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import utils.SessionManager;

public class RegistrationController {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField emailField;
    @FXML private TextField nameField;
    @FXML private ComboBox<String> roleComboBox;
    @FXML private TextField pairingCodeField;

    @FXML private Label messageLabel;
    @FXML private HBox emailBox, pairingCodeBox;
    @FXML private Button registerButton;

    @FXML
    private void initialize() {
        roleComboBox.getItems().addAll("Elder", "Caretaker");
        roleComboBox.setValue("Elder");

        // Hide inputs unless needed
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
    private void handleRegistration() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String email = emailField.getText().trim();
        String name = nameField.getText().trim();
        String role = roleComboBox.getValue();
        String pairingCodeInput = pairingCodeField.getText().trim();

        // For elder: generate pairing code
        final String pairingCode = "Elder".equals(role) ? utils.PairingCodeGenerator.generateCode() : pairingCodeInput;

        // Basic required-field checks
        if (username.isEmpty() || password.isEmpty() || name.isEmpty() || role == null ||
                (!"Elder".equals(role) && (email.isEmpty() || pairingCode.isEmpty()))) {
            messageLabel.setText("Please fill all required fields.");
            return;
        }

        // Simple email format validation for caretakers
        if (!"Elder".equals(role) && !isValidEmail(email)) {
            messageLabel.setText("Please enter a valid email address.");
            return;
        }

        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                boolean usernameExists = !users.whereEqualTo("username", username).get().get().isEmpty();
                boolean emailExists = (!"Elder".equals(role)) && !users.whereEqualTo("email", email).get().get().isEmpty();

                if (usernameExists) {
                    Platform.runLater(() -> messageLabel.setText("Username already exists."));
                    return;
                }
                if (emailExists) {
                    Platform.runLater(() -> messageLabel.setText("Email already registered."));
                    return;
                }

                if ("Elder".equals(role)) {
                    // Create elder record
                    Map<String, Object> elderData = new HashMap<>();
                    elderData.put("username", username);
                    elderData.put("password", password);
                    elderData.put("name", name);
                    elderData.put("role", "elder");
                    elderData.put("pairingCode", pairingCode);
                    elderData.put("createdAt", System.currentTimeMillis());
                    elderData.put("caretakers", new java.util.ArrayList<String>()); // list of caretaker IDs
                    DocumentReference elderRef = users.document();
                    elderData.put("elderId", elderRef.getId());

                    elderRef.set(elderData).get();
                    SessionManager.createSession(elderRef.getId(), "elder");

                    Platform.runLater(() -> {
                        messageLabel.setText("Elder registered. Share pairing code: " + pairingCode);
                        goToPostRegistration(role, pairingCode, username, elderRef.getId());
                    });

                } else {
                    // Caretaker: validate pairing code
                    DocumentSnapshot elderDoc = users.whereEqualTo("pairingCode", pairingCode).whereEqualTo("role", "elder")
                            .get().get().getDocuments().stream().findFirst().orElse(null);

                    if (elderDoc == null) {
                        Platform.runLater(() -> messageLabel.setText("Invalid pairing code."));
                        return;
                    }

                    String elderId = elderDoc.getId();

                    Map<String, Object> caretakerData = new HashMap<>();
                    caretakerData.put("username", username);
                    caretakerData.put("password", password);
                    caretakerData.put("email", email);
                    caretakerData.put("name", name);
                    caretakerData.put("role", "caretaker");
                    caretakerData.put("elderId", elderId);
                    caretakerData.put("createdAt", System.currentTimeMillis());

                    DocumentReference caretakerRef = users.document();
                    caretakerData.put("caretakerId", caretakerRef.getId());

                    caretakerRef.set(caretakerData).get();

                    // Add caretaker ID under elder’s caretakers list
                    users.document(elderId).update("caretakers", com.google.cloud.firestore.FieldValue.arrayUnion(caretakerRef.getId()));

                    EmailService.sendConfirmationEmail(email, username);
                    SessionManager.createSession(caretakerRef.getId(), "caretaker");

                    Platform.runLater(() -> {
                        messageLabel.setText("Caretaker registered and linked to elder.");
                        goToPostRegistration(role, elderDoc.getString("name"), username, caretakerRef.getId()); // Show linked elder name
                    });
                }

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> messageLabel.setText("Registration failed."));
            }
        }).start();
    }

    private boolean isValidEmail(String email) {
        return EMAIL_PATTERN.matcher(email).matches();
    }

    private void goToPostRegistration(String role, String data, String username, String userID) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/post_registration.fxml"));
            Parent root = loader.load();

            PostRegistrationController controller = loader.getController();
            controller.initializeView(role, data, username, userID);

            Stage stage = (Stage) registerButton.getScene().getWindow();
            Scene scene = new Scene(root);

            FadeTransition ft = new FadeTransition(Duration.millis(400), root);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.play();

            stage.setScene(scene);

        } catch (IOException e) {
            e.printStackTrace();
            messageLabel.setText("Failed to load post-registration screen.");
        }
    }

    @FXML
    private void goToLogin() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            Platform.runLater(() -> messageLabel.setText("Failed to load login screen."));
        }
    }
}
