package controller;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

import utils.SessionManager; // adjust the package if SessionManager is in another package


public class PostRegistrationController {

    @FXML private VBox elderSection;
    @FXML private VBox caretakerSection;
    @FXML private TextField pairingCodeField;
    @FXML private Label pairedElderNameLabel;
    @FXML private Button copyButton;
    @FXML private Button dashboardButton;

    @FXML private Label copyMessageLabel; // temporary message label

    /**
     * Initialize the view based on the user's role.
     * @param role "elder" or "caretaker"
     * @param pairingCodeOrElderName Pairing code for Elder, Elder name for Caretaker
     */
    public void initializeView(String role, String pairingCodeOrElderName,String username,String userID) {
        if (role.equalsIgnoreCase("elder")) {
            elderSection.setVisible(true);
            elderSection.setManaged(true);
            caretakerSection.setVisible(false);
            caretakerSection.setManaged(false);
            pairingCodeField.setText(pairingCodeOrElderName);

            // Create temporary label for "copied" message
            copyMessageLabel = new Label();
            copyMessageLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
            copyMessageLabel.setVisible(false);
            elderSection.getChildren().add(copyMessageLabel);

            // Copy pairing code to clipboard
            copyButton.setOnAction(e -> {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(pairingCodeField.getText());
                clipboard.setContent(content);

                // Show "Copied!" message
                copyMessageLabel.setText("Code copied to clipboard!");
                copyMessageLabel.setVisible(true);

                // Fade out after 2 seconds
                FadeTransition ft = new FadeTransition(Duration.seconds(2), copyMessageLabel);
                ft.setFromValue(1);
                ft.setToValue(0);
                ft.setOnFinished(ev -> copyMessageLabel.setVisible(false));
                ft.play();
            });

        } else if (role.equalsIgnoreCase("caretaker")) {
            elderSection.setVisible(false);
            elderSection.setManaged(false);
            caretakerSection.setVisible(true);
            caretakerSection.setManaged(true);
            pairedElderNameLabel.setText(pairingCodeOrElderName);
        }

        // Dashboard button placeholder
        dashboardButton.setOnAction(e -> {
            try {
                Stage stage = (Stage) dashboardButton.getScene().getWindow();

                if (role.equalsIgnoreCase("elder")) {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/elder_dashboard.fxml"));
                    Parent root = loader.load();

                    // Pass username to elder dashboard
                    ElderDashboardController elderCtrl = loader.getController();
                    elderCtrl.initializeElder(username);

                    stage.setScene(new Scene(root));
                    stage.centerOnScreen();
                } else if (role.equalsIgnoreCase("caretaker")) {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/caretaker_dashboard.fxml"));
                    Parent root = loader.load();

                    stage.setScene(new Scene(root));
                    stage.centerOnScreen();
                } else {
                    System.out.println("Unknown role, cannot navigate to dashboard.");
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        });


    }
    
}
