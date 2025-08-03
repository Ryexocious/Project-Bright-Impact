package controller;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import model.User;
import utils.PairingCodeGenerator;
import javafx.scene.layout.HBox;


import java.util.*;

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

    @FXML private HBox emailBox,  pairingCodeBox;

    @FXML
    public void initialize() {
        roleComboBox.getItems().addAll("Elder", "Caretaker");

        // Default hidden
        emailBox.setVisible(false);
        emailBox.setManaged(false);



        pairingCodeBox.setVisible(false);
        pairingCodeBox.setManaged(false);

        roleComboBox.setOnAction(e -> {
            String role = roleComboBox.getValue();

            boolean isElder = "Elder".equals(role);

            emailBox.setVisible(!isElder);
            emailBox.setManaged(!isElder);


            pairingCodeBox.setVisible(!isElder);
            pairingCodeBox.setManaged(!isElder);
        });
    }

    private void handleRegistration() {
    	//Registration Handle korar por post_reg fxml e switch koris
    }
}
