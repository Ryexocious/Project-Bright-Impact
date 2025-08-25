package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Callback;
import javafx.util.Duration;
import utils.FirestoreService;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Dose intake history viewer for caretakers.
 *
 * - Real-time (debounced) search on medicine name input.
 * - Also supports Search button, Clear button and date-range filters.
 * - No "Source" column (removed per request).
 */
public class DoseIntakeHistoryController {

    @FXML private TextField nameFilterField;
    @FXML private ComboBox<String> typeFilterCombo;
    @FXML private ComboBox<String> statusFilterCombo;
    @FXML private DatePicker fromDatePicker;
    @FXML private DatePicker toDatePicker;
    @FXML private Button searchBtn;
    @FXML private Button clearBtn;
    @FXML private Button backBtn;

    @FXML private TableView<DoseRecord> tableView;
    @FXML private TableColumn<DoseRecord, String> colDate;
    @FXML private TableColumn<DoseRecord, String> colTime;
    @FXML private TableColumn<DoseRecord, String> colName;
    @FXML private TableColumn<DoseRecord, String> colType;
    @FXML private TableColumn<DoseRecord, String> colAmount;
    @FXML private TableColumn<DoseRecord, HBox> colStatus;
    @FXML private TableColumn<DoseRecord, String> colLoggedAt;

    @FXML private Label noRecordsLabel;
    @FXML private Label hintLabel;

    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
    private final DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String currentCaretakerId = null;
    private String resolvedElderId = null;

    // debounce for live typing
    private PauseTransition nameDebounce;

    @FXML
    public void initialize() {
        // Setup status combo options
        List<String> statuses = Arrays.asList("Any", "taken", "missed", "hasnt_arrived", "in_snooze_duration");
        statusFilterCombo.getItems().setAll(statuses);
        statusFilterCombo.setValue("Any");

        // type combo: allow common ones
        typeFilterCombo.getItems().setAll("Any", "Tablet", "Capsule", "Syrup", "Injection", "Other");
        typeFilterCombo.setValue("Any");

        // Wire buttons
        searchBtn.setOnAction(e -> {
            // cancel debounce and run immediate search
            if (nameDebounce != null) nameDebounce.stop();
            runSearch();
        });
        clearBtn.setOnAction(e -> {
            if (nameDebounce != null) nameDebounce.stop();
            clearFilters();
        });
        backBtn.setOnAction(e -> handleBack());

        // configure table columns
        colDate.setCellValueFactory(new PropertyValueFactory<>("dateStr"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("timeStr"));
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colType.setCellValueFactory(new PropertyValueFactory<>("type"));
        colAmount.setCellValueFactory(new PropertyValueFactory<>("amount"));

        colStatus.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<DoseRecord, HBox>, ObservableValue<HBox>>() {
            @Override
            public ObservableValue<HBox> call(TableColumn.CellDataFeatures<DoseRecord, HBox> param) {
                DoseRecord r = param.getValue();
                HBox box = createStatusPill(r.status);
                return new SimpleObjectProperty<>(box);
            }
        });

        colLoggedAt.setCellValueFactory(new PropertyValueFactory<>("loggedAtStr"));

        // nicer table defaults
        tableView.setPlaceholder(new Label("No dose records to display."));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // debounce for name filter: 350ms
        nameDebounce = new PauseTransition(Duration.millis(350));
        nameFilterField.textProperty().addListener((obs, oldV, newV) -> {
            // restart debounce
            nameDebounce.stop();
            nameDebounce.setOnFinished(ev -> runSearch());
            nameDebounce.playFromStart();
        });

        // detect logged-in caretaker and load
        detectLoggedInCaretaker();

        // default hint
        hintLabel.setText("Tip: leave date range empty to search last 7 days");
    }

    private void detectLoggedInCaretaker() {
        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                List<QueryDocumentSnapshot> docs = db.collection("users")
                        .whereEqualTo("loggedIn", true)
                        .whereEqualTo("role", "caretaker")
                        .get().get().getDocuments();

                if (!docs.isEmpty()) {
                    QueryDocumentSnapshot caret = docs.get(0);
                    currentCaretakerId = caret.getId();
                    Object elderIdObj = caret.get("elderId");
                    if (elderIdObj != null) {
                        resolvedElderId = elderIdObj.toString();
                    }
                }
                Platform.runLater(() -> {
                    // run initial search (last 7 days) when ready
                    runSearch();
                });
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> showError("Failed to detect logged-in caretaker."));
            }
        }).start();
    }

    private void clearFilters() {
        nameFilterField.clear();
        typeFilterCombo.setValue("Any");
        statusFilterCombo.setValue("Any");
        fromDatePicker.setValue(null);
        toDatePicker.setValue(null);
        runSearch();
    }

    /**
     * Performs the query across the selected date range (or last 7 days).
     * Note: called from FX thread, but the actual Firestore work is on a background Thread.
     */
    private void runSearch() {
        noRecordsLabel.setVisible(false);
        tableView.getItems().clear();

        if (resolvedElderId == null) {
            showError("No linked elder detected for this caretaker.");
            return;
        }

        String nameFilter = (nameFilterField.getText() == null) ? "" : nameFilterField.getText().trim().toLowerCase();
        String typeFilter = (typeFilterCombo.getValue() == null || "Any".equals(typeFilterCombo.getValue())) ? "" : typeFilterCombo.getValue().trim();
        String statusFilter = (statusFilterCombo.getValue() == null || "Any".equals(statusFilterCombo.getValue())) ? "" : statusFilterCombo.getValue().trim();

        // determine date range: if both null -> last 7 days
        LocalDate from = fromDatePicker.getValue();
        LocalDate to = toDatePicker.getValue();
        if (from == null && to == null) {
            to = LocalDate.now();
            from = to.minusDays(6);
        } else if (from == null && to != null) {
            from = to.minusDays(6);
        } else if (from != null && to == null) {
            to = from.plusDays(6);
        }

        // prevent excessively large spans (defensive)
        if (from.plusDays(120).isBefore(to)) {
            to = from.plusDays(120);
        }

        LocalDate start = from;
        LocalDate end = to;

        // create timestamps for filters (used server-side if possible)
        ZoneId zid = ZoneId.systemDefault();
        com.google.cloud.Timestamp startTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(start.atStartOfDay(zid).toEpochSecond(), 0);
        // endTs = end day's last second (approx) — using end.plusDays(1).atStartOfDay - 1 sec
        long endEpoch = end.plusDays(1).atStartOfDay(zid).toEpochSecond() - 1;
        com.google.cloud.Timestamp endTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(endEpoch, 0);

        final String nf = nameFilter;
        final String tf = typeFilter;
        final String sf = statusFilter;

        new Thread(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                List<DoseRecord> collected = new ArrayList<>();

                LocalDate d = start;
                DateTimeFormatter idFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

                while (!d.isAfter(end)) {
                    String dateId = d.format(idFmt);
                    DocumentReference dayRef = db.collection("users").document(resolvedElderId)
                            .collection("schedules").document(dateId);

                    DocumentSnapshot daySnap = dayRef.get().get();
                    if (!daySnap.exists()) {
                        d = d.plusDays(1);
                        continue;
                    }

                    CollectionReference itemsCol = dayRef.collection("items");
                    Query q = itemsCol;

                    // server-side filters where useful
                    if (!sf.isEmpty()) q = q.whereEqualTo("status", sf);
                    if (!tf.isEmpty()) q = q.whereEqualTo("type", tf);

                    try {
                        q = q.whereGreaterThanOrEqualTo("baseTimestamp", startTs);
                        q = q.whereLessThanOrEqualTo("baseTimestamp", endTs);
                    } catch (Exception ex) {
                        // ignore if baseTimestamp missing/typed differently
                    }

                    List<QueryDocumentSnapshot> items = q.get().get().getDocuments();
                    for (QueryDocumentSnapshot it : items) {
                        try {
                            DoseRecord r = DoseRecord.fromSnapshot(it, dateId);

                            // client-side filtering: partial name match + fallback checks
                            boolean ok = true;
                            if (!nf.isEmpty()) {
                                String n = (r.name == null) ? "" : r.name.toLowerCase();
                                if (!n.contains(nf)) ok = false;
                            }
                            if (!tf.isEmpty()) {
                                String t = (r.type == null) ? "" : r.type;
                                if (!t.equalsIgnoreCase(tf)) ok = false;
                            }
                            if (!sf.isEmpty()) {
                                String s = (r.status == null) ? "" : r.status;
                                if (!s.equals(sf)) ok = false;
                            }
                            if (ok) collected.add(r);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }

                    d = d.plusDays(1);
                }

                // sort by baseTimestamp descending (most recent first)
                collected.sort(Comparator.comparing((DoseRecord rr) -> rr.baseTimestamp == null ? Instant.EPOCH : rr.baseTimestamp.atZone(zid).toInstant()).reversed());

                final List<DoseRecord> finalList = collected;
                Platform.runLater(() -> {
                    tableView.getItems().setAll(finalList);
                    noRecordsLabel.setVisible(finalList.isEmpty());
                    if (finalList.isEmpty()) {
                        tableView.setPlaceholder(new Label("No records found for these filters."));
                    }
                });

            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> showError("Failed to load records: " + ex.getMessage()));
            }
        }).start();
    }

    private HBox createStatusPill(String status) {
        Label lbl = new Label();
        lbl.setStyle("-fx-padding:4 10 4 10; -fx-background-radius:12; -fx-font-size:12; -fx-font-weight:bold;");
        if (status == null) status = "";
        switch (status) {
            case "taken":
                lbl.setText("Taken");
                lbl.setStyle(lbl.getStyle() + "-fx-background-color:#e6f6ea; -fx-text-fill:#12711b;");
                break;
            case "missed":
                lbl.setText("Missed");
                lbl.setStyle(lbl.getStyle() + "-fx-background-color:#fdecea; -fx-text-fill:#9c1c0d;");
                break;
            case "hasnt_arrived":
                lbl.setText("Not yet");
                lbl.setStyle(lbl.getStyle() + "-fx-background-color:#fff7df; -fx-text-fill:#8a6d00;");
                break;
            case "in_snooze_duration":
                lbl.setText("Snoozing");
                lbl.setStyle(lbl.getStyle() + "-fx-background-color:#e6f2ff; -fx-text-fill:#1759a6;");
                break;
            default:
                lbl.setText(status.isEmpty() ? "Unknown" : status);
                lbl.setStyle(lbl.getStyle() + "-fx-background-color:#f0f0f0; -fx-text-fill:#333;");
        }
        HBox box = new HBox(lbl);
        box.setStyle("-fx-alignment:center-left;");
        return box;
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.showAndWait();
    }

    private void handleBack() {
        try {
            Parent root = FXMLLoader.load(getClass().getResource("/fxml/caretaker_dashboard.fxml"));
            Stage stage = (Stage) backBtn.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Failed to go back to dashboard.");
        }
    }

    /**
     * Simple DTO representing a dose record (table row)
     */
    public static class DoseRecord {
        public String id;
        public String medicineId;
        public String name;
        public String type;
        public String amount;
        public String timeStr;
        public LocalDate date;
        public LocalDateTime baseTimestamp;
        public String status;
        public Instant loggedAt; // takenAt / missedLoggedAt if available
        public String loggedAtStr;

        // derived properties for table binding
        public String getDateStr() { return date == null ? "" : date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")); }
        public String getTimeStr() { return timeStr == null ? "" : timeStr; }
        public String getName() { return name == null ? "" : name; }
        public String getType() { return type == null ? "" : type; }
        public String getAmount() { return amount == null ? "" : amount; }
        public String getLoggedAtStr() { return loggedAtStr == null ? "" : loggedAtStr; }

        static DoseRecord fromSnapshot(DocumentSnapshot d, String dateId) {
            DoseRecord r = new DoseRecord();
            r.id = d.getId();
            r.medicineId = d.getString("medicineId");
            r.name = d.getString("name");
            r.type = d.getString("type");
            r.amount = d.getString("amount");
            r.timeStr = d.getString("time");
            r.status = d.getString("status");
            r.date = (dateId == null || dateId.isEmpty()) ? null : LocalDate.parse(dateId, DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            Object baseO = d.get("baseTimestamp");
            if (baseO instanceof com.google.cloud.Timestamp) {
                com.google.cloud.Timestamp ts = (com.google.cloud.Timestamp) baseO;
                Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
                r.baseTimestamp = LocalDateTime.ofInstant(inst, ZoneId.systemDefault());
            } else r.baseTimestamp = null;

            // extract loggedAt: check takenAt, missedLoggedAt, createdAt fallbacks
            try {
                Object takenAt = d.get("takenAt");
                if (takenAt instanceof com.google.cloud.Timestamp) {
                    com.google.cloud.Timestamp ts = (com.google.cloud.Timestamp) takenAt;
                    r.loggedAt = ts.toDate().toInstant();
                } else {
                    Object missedAt = d.get("missedLoggedAt");
                    if (missedAt instanceof com.google.cloud.Timestamp) {
                        r.loggedAt = ((com.google.cloud.Timestamp) missedAt).toDate().toInstant();
                    } else {
                        Object createdAt = d.get("createdAt");
                        if (createdAt instanceof com.google.cloud.Timestamp) r.loggedAt = ((com.google.cloud.Timestamp) createdAt).toDate().toInstant();
                        else r.loggedAt = null;
                    }
                }
                if (r.loggedAt != null) r.loggedAtStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(r.loggedAt);
            } catch (Exception ex) {
                r.loggedAt = null;
                r.loggedAtStr = "";
            }

            return r;
        }
    }
}
