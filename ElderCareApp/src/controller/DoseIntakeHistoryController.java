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
import utils.SessionManager;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/*
  DoseIntakeHistoryController

  Purpose and responsibilities:
  - Present a searchable, filterable history of dose events for the elder linked to the currently
    logged-in caretaker.
  - Support filtering by medicine name (live/debounced), medicine type, status and date range.
  - Read schedule day documents from Firestore (users/{elderId}/schedules/{yyyy-MM-dd}) and their
    "items" subcollection. Apply server-side timestamp range where possible and perform additional
    client-side filtering (name/type/status) to avoid requiring complex composite indexes.
  - Build a simple DTO (DoseRecord) per item for table binding and sorting.

  Threading model and safety:
  - All Firestore network I/O runs off the JavaFX Application Thread in new background threads.
  - Any UI mutation (table update, alerts, labels) is executed inside Platform.runLater(...) to
    ensure FX-thread safety.
  - The controller uses a small debounce (PauseTransition) for the name filter to reduce query churn.

  Important behavioural notes:
  - The controller relies on SessionManager.getCurrentUserId() to identify the logged-in caretaker.
  - If the caretaker document contains "elderId", the controller uses it to locate schedule documents.
  - Date ranges: if the user leaves both from/to empty, the controller defaults to the last 7 days.
  - When constructing Firestore queries, the controller attempts to query by a timestamp range but
    gracefully falls back to a full-day fetch if server-side index requirements prevent the range query.
*/

public class DoseIntakeHistoryController {

    /*
      FXML-injected controls:
      - These fields are bound by fx:id values in the corresponding FXML file. They must exist and
        be non-null after FXMLLoader has initialized the controller.
      - All interactions with these controls must happen on the JavaFX Application Thread.
    */
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

    /*
      Formatters and state:
      - dateFmt/timeFmt/dtFmt are reused for parsing/formatting displayed dates.
      - currentCaretakerId is the logged-in user's uid (from SessionManager).
      - resolvedElderId is the elder id read from the caretaker user document; it determines which user's
        schedules we query.
    */
    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
    private final DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String currentCaretakerId = null;
    private String resolvedElderId = null;

    /*
      Debounce support:
      - nameDebounce is a PauseTransition used to delay running searches while the user types in the
        name filter, reducing the number of background queries.
      - Configured in initialize() with 350ms delay.
    */
    private PauseTransition nameDebounce;

    /*
      initialize()
      - Set up UI defaults (combos, table column value factories).
      - Attach event handlers for search/clear/back buttons.
      - Wire the name field to a debounced search.
      - Kick off caretaker detection which will trigger the first search once the elder is resolved.
      - All heavy work (Firestore reads) happens on background threads started from here or helper methods.
    */
    @FXML
    public void initialize() {
        List<String> statuses = Arrays.asList("Any", "taken", "missed", "hasnt_arrived", "in_snooze_duration");
        statusFilterCombo.getItems().setAll(statuses);
        statusFilterCombo.setValue("Any");

        typeFilterCombo.getItems().setAll("Any", "Tablet", "Capsule", "Syrup", "Injection", "Other");
        typeFilterCombo.setValue("Any");

        searchBtn.setOnAction(e -> {
            if (nameDebounce != null) nameDebounce.stop();
            runSearch();
        });
        clearBtn.setOnAction(e -> {
            if (nameDebounce != null) nameDebounce.stop();
            clearFilters();
        });
        backBtn.setOnAction(e -> handleBack());

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

        tableView.setPlaceholder(new Label("No dose records to display."));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        nameDebounce = new PauseTransition(Duration.millis(350));
        nameFilterField.textProperty().addListener((obs, oldV, newV) -> {
            nameDebounce.stop();
            nameDebounce.setOnFinished(ev -> runSearch());
            nameDebounce.playFromStart();
        });

        detectLoggedInCaretaker();

        hintLabel.setText("Tip: leave date range empty to search last 7 days");
    }

    /*
      detectLoggedInCaretaker()
      - Uses SessionManager.getCurrentUserId() to find the logged-in user id.
      - Loads the user document from Firestore and validates the "caretaker" role.
      - If the user document contains "elderId", it stores it in resolvedElderId and triggers runSearch().
      - All Firestore network calls are performed on a background thread (new Thread).
      - Errors and UI messages are posted to the FX thread with Platform.runLater.
    */
    private void detectLoggedInCaretaker() {
        new Thread(() -> {
            try {
                String currentUid = SessionManager.getCurrentUserId();
                if (currentUid == null) {
                    Platform.runLater(() -> showError("No logged-in caretaker found. Please login."));
                    return;
                }

                Firestore db = FirestoreService.getFirestore();
                DocumentSnapshot caretSnap = db.collection("users").document(currentUid).get().get();
                if (!caretSnap.exists()) {
                    Platform.runLater(() -> showError("Logged-in caretaker not found in database."));
                    return;
                }

                String role = caretSnap.getString("role");
                if (role == null || !"caretaker".equalsIgnoreCase(role)) {
                    Platform.runLater(() -> showError("Current user is not a caretaker."));
                    return;
                }

                currentCaretakerId = currentUid;
                Object elderIdObj = caretSnap.get("elderId");
                if (elderIdObj != null) resolvedElderId = elderIdObj.toString();

                Platform.runLater(() -> runSearch());
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> showError("Failed to detect logged-in caretaker."));
            }
        }).start();
    }

    /*
      clearFilters()
      - Reset all filter UI controls to their defaults and run a fresh search.
      - Uses runSearch() to update the table.
    */
    private void clearFilters() {
        nameFilterField.clear();
        typeFilterCombo.setValue("Any");
        statusFilterCombo.setValue("Any");
        fromDatePicker.setValue(null);
        toDatePicker.setValue(null);
        runSearch();
    }

    /*
      runSearch()
      - Main search logic executed on demand (via search button, clear, debounce finish, or after caretaker detection).
      - Steps:
          1) Prepare and normalize filters (name/type/status).
          2) Compute a date range (default last 7 days when both empty).
          3) Clamp large ranges (max 120 days window).
          4) Convert start/end into Firestore Timestamps (startTs/endTs) to attempt server-side timestamp filtering.
          5) Iterate days from start..end and read schedules/{yyyy-MM-dd} documents.
          6) For each day document, attempt to query the items subcollection using a baseTimestamp range.
             If the range query fails due to index restrictions, fall back to fetching all items for that day.
          7) Build DoseRecord objects via DoseRecord.fromSnapshot(...) and perform client-side filtering by name/type/status.
          8) Sort results by baseTimestamp descending and update the TableView on the FX thread.
      - Important points:
          - Query fallback prevents the UI from failing when composite indexes are not present; it trades bandwidth
            for robustness.
          - Client-side filtering ensures flexible comparisons (case-insensitive name contains, exact type/status match).
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

        if (from.plusDays(120).isBefore(to)) {
            to = from.plusDays(120);
        }

        LocalDate start = from;
        LocalDate end = to;

        ZoneId zid = ZoneId.systemDefault();
        com.google.cloud.Timestamp startTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(start.atStartOfDay(zid).toEpochSecond(), 0);
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

                    // Try to perform an efficient timestamp-range query. If it fails (missing index or server rule),
                    // fall back to fetching all items for that date, then filter client-side.
                    Query q = itemsCol;
                    try {
                        q = q.whereGreaterThanOrEqualTo("baseTimestamp", startTs)
                                .whereLessThanOrEqualTo("baseTimestamp", endTs);
                    } catch (Exception ex) {
                        // fallback
                    }

                    List<QueryDocumentSnapshot> items;
                    try {
                        items = q.get().get().getDocuments();
                    } catch (Exception ex) {
                        items = itemsCol.get().get().getDocuments();
                    }

                    for (QueryDocumentSnapshot it : items) {
                        try {
                            DoseRecord r = DoseRecord.fromSnapshot(it, dateId);

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

    /*
      createStatusPill(status)
      - Build a small HBox containing a stylized Label that visually represents the status.
      - Styles are applied via CSS classes (moved out of Java). The CSS file should define:
          .pill { common pill visuals }
          .pill-taken / .pill-missed / .pill-notyet / .pill-snooze / .pill-unknown
          .pill-container (alignment)
      - This removes inline styles and allows theming via the stylesheet.
    */
    private HBox createStatusPill(String status) {
        Label lbl = new Label();
        lbl.getStyleClass().add("pill");

        String s = (status == null) ? "" : status;
        switch (s) {
            case "taken":
                lbl.setText("Taken");
                lbl.getStyleClass().add("pill-taken");
                break;
            case "missed":
                lbl.setText("Missed");
                lbl.getStyleClass().add("pill-missed");
                break;
            case "hasnt_arrived":
                lbl.setText("Not yet");
                lbl.getStyleClass().add("pill-notyet");
                break;
            case "in_snooze_duration":
                lbl.setText("Snoozing");
                lbl.getStyleClass().add("pill-snooze");
                break;
            default:
                lbl.setText(s.isEmpty() ? "Unknown" : s);
                lbl.getStyleClass().add("pill-unknown");
        }

        HBox box = new HBox(lbl);
        box.getStyleClass().add("pill-container");
        return box;
    }

    /*
      showError(msg)
      - Utility to display an error Alert dialog with the provided message.
      - Blocks until the user dismisses the dialog (showAndWait()) — keep messages concise to avoid blocking UX.
    */
    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.showAndWait();
    }

    /*
      handleBack()
      - Navigate back to the caretaker dashboard by loading the FXML and replacing the current scene.
      - Any IO exceptions are printed and an error dialog is shown to the user.
    */
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

    /*
      DoseRecord DTO
      - Represents a single dose/ scheduled item row to display in the TableView.
      - Fields:
          id, medicineId, name, type, amount, timeStr, date, baseTimestamp (LocalDateTime), status,
          loggedAt (Instant), loggedAtStr (human-friendly).
      - Derived property getters are provided for the table's PropertyValueFactory usage (getDateStr, getTimeStr etc.)
      - fromSnapshot(DocumentSnapshot, dateId):
          * Responsible for converting Firestore document fields into a DoseRecord instance.
          * Handles multiple possible field shapes for timestamps:
              - baseTimestamp expected to be a com.google.cloud.Timestamp
              - takenAt / missedLoggedAt / createdAt are considered (priority given to takenAt, then missedLoggedAt then createdAt)
          * Converts com.google.cloud.Timestamp into java.time.Instant and localizes to ZoneId.systemDefault() for baseTimestamp storage.
          * Produces loggedAtStr using the controller's dtFmt and ZoneId.systemDefault() for display.
      - This DTO is intentionally simple and mutable to keep creation straightforward when iterating Firestore results.
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

        // Getters used by PropertyValueFactory bindings in the TableView
        public String getDateStr() { return date == null ? "" : date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")); }
        public String getTimeStr() { return timeStr == null ? "" : timeStr; }
        public String getName() { return name == null ? "" : name; }
        public String getType() { return type == null ? "" : type; }
        public String getAmount() { return amount == null ? "" : amount; }
        public String getLoggedAtStr() { return loggedAtStr == null ? "" : loggedAtStr; }

        /*
          fromSnapshot(d, dateId)
          - Convert a Firestore DocumentSnapshot into a DoseRecord.
          - Robust to missing fields and differences in timestamp types.
          - Parsing strategy:
              baseTimestamp -> LocalDateTime (if present and is com.google.cloud.Timestamp)
              loggedAt -> takenAt (preferred) || missedLoggedAt || createdAt (fallback)
              loggedAtStr -> formatted with pattern "yyyy-MM-dd HH:mm:ss" in the system zone if loggedAt present
        */
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
