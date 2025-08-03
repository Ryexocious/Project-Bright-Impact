package controller;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

public class PostRegistrationController {

	@FXML private VBox elderSection;
	@FXML private VBox caretakerSection;
	@FXML private TextField pairingCodeField;
	@FXML private Label pairedElderNameLabel;

	public void initializeView(String role, String pairingCodeOrElderName) {
	    if (role.equalsIgnoreCase("elder")) {
	        elderSection.setVisible(true);
	        elderSection.setManaged(true);
	        caretakerSection.setVisible(false);
	        caretakerSection.setManaged(false);
	        pairingCodeField.setText(pairingCodeOrElderName);
	    } else if (role.equalsIgnoreCase("caretaker")) {
	        elderSection.setVisible(false);
	        elderSection.setManaged(false);
	        caretakerSection.setVisible(true);
	        caretakerSection.setManaged(true);
	        pairedElderNameLabel.setText(pairingCodeOrElderName);
	    }
	}

}