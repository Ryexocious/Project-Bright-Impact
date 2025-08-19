package controller;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Firestore;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import utils.EmailService;
import utils.FirestoreService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class ElderDashboardController {

    private String elderUsername;
    private String pairingCode;
    private String elderId; // store elder document id

    @FXML private Button helpRequestButton;
    @FXML private Button viewMedicineButton;
    @FXML private Button viewVitalsButton;
    @FXML private Button logoutButton;
    @FXML private Label welcomeLabel;

    /**
     * Call this after login to fetch actual elder details from Firestore.
     */
    public void initializeElder(String loggedInUsername) {
        helpRequestButton.setDisable(true); // Disable until data loaded
        welcomeLabel.setText("Loading...");

        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                QuerySnapshot elderSnapshot = users
                        .whereEqualTo("username", loggedInUsername)
                        .whereEqualTo("role", "elder")
                        .get()
                        .get();

                if (!elderSnapshot.isEmpty()) {
                    QueryDocumentSnapshot elderDoc = elderSnapshot.getDocuments().get(0);
                    this.elderUsername = elderDoc.getString("username");
                    this.pairingCode = elderDoc.getString("pairingCode");
                    this.elderId = elderDoc.getId(); // store elder document id

                    System.out.println("✅ Elder details loaded: " + elderUsername +
                            ", PairingCode: " + pairingCode + ", elderId: " + elderId);

                    Platform.runLater(() -> {
                        helpRequestButton.setDisable(false); // Enable help button
                        welcomeLabel.setText("Welcome, " + elderUsername + " 👴"); // Dynamic welcome
                    });

                } else {
                    System.err.println("❌ Elder not found in database: " + loggedInUsername);
                    Platform.runLater(() -> welcomeLabel.setText("Elder not found"));
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> welcomeLabel.setText("Failed to load elder details"));
            }
        }).start();
    }

    @FXML
    private void handleHelpRequest() {
        if (elderId == null) {
            System.err.println("⚠️ Elder details not initialized (elderId). Cannot send help request.");
            return;
        }

        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                // 1) Primary: query caretakers by elderId (caretaker documents have elderId field)
                QuerySnapshot caretakersSnapshot = users
                        .whereEqualTo("role", "caretaker")
                        .whereEqualTo("elderId", elderId)
                        .get()
                        .get();

                List<String> caretakerEmails = new ArrayList<>();
                for (QueryDocumentSnapshot doc : caretakersSnapshot.getDocuments()) {
                    String email = doc.getString("email");
                    if (email != null && !email.isEmpty()) {
                        caretakerEmails.add(email);
                    }
                }

                // 2) Fallback: if none found, try reading elder.caretakers field (list of caretaker IDs)
                if (caretakerEmails.isEmpty()) {
                    System.out.println("Primary query returned no caretakers by elderId. Trying fallback (elder.caretakers array)...");
                    try {
                        DocumentSnapshot elderSnapshot = users.document(elderId).get().get();
                        Object caretakersField = elderSnapshot.get("caretakers");
                        if (caretakersField instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> caretakerIds = (List<String>) caretakersField;
                            if (!caretakerIds.isEmpty()) {
                                for (String cid : caretakerIds) {
                                    try {
                                        DocumentSnapshot caretDocSnap = users.document(cid).get().get(); // DocumentSnapshot
                                        if (caretDocSnap != null && caretDocSnap.exists()) {
                                            String e = caretDocSnap.getString("email");
                                            if (e != null && !e.isEmpty()) {
                                                caretakerEmails.add(e);
                                            }
                                        }
                                    } catch (InterruptedException | ExecutionException ex) {
                                        // log and continue with next id
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException | ExecutionException ex) {
                        ex.printStackTrace();
                    }
                }

                // 3) Send emails if any addresses found
                if (!caretakerEmails.isEmpty()) {
                    System.out.println("Found caretakers: " + caretakerEmails);
                    EmailService.sendHelpRequestEmail(elderUsername, caretakerEmails);
                } else {
                    System.out.println("⚠️ No caretakers found for this elder (elderId: " + elderId + ").");
                }

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }

    @FXML
    private void handleViewMedicine() {
        switchScene("/fxml/medicine_schedule.fxml");
    }

    @FXML
    private void handleViewVitals() {
        switchScene("/fxml/vitals.fxml");
    }

    @FXML
    private void handleLogout() {
        switchScene("/fxml/login.fxml");
    }

    /**
     * Helper method to switch scenes safely.
     */
    private void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) helpRequestButton.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
