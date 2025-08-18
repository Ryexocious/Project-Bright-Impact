package controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class CaretakerDashboardController {

    @FXML
    private void handleMissedDoses() {
        switchScene("/fxml/missed_doses.fxml");
    }

    @FXML
    private void handleVitalsMonitoring() {
        switchScene("/fxml/vitals_monitor.fxml");
    }

    @FXML
    private void handleLogout() {
        switchScene("/fxml/login.fxml");
    }

    private void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) Stage.getWindows().filtered(window -> window.isShowing()).get(0);
            stage.setScene(new Scene(root));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
