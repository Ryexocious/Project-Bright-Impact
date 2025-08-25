package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import utils.FirestoreService;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * MedicineController - updated:
 *  - prefer forceEndedAt for cutoff; else lastDose.timestamp; else activePeriod.endDate + latest time; else endDate.
 *  - UI prevents creating a schedule whose first dose datetime is before "now".
 *  - If startDate == today, time picker options earlier than now are filtered out and cannot be added.
 */
public class MedicineController {

    // Header info
    @FXML private Label elderFoundLabel;
    @FXML private Label statusLabel;
    @FXML private Button closeButton;

    // CENTER ScrollPane (bound at runtime so resizing works)
    @FXML private ScrollPane centerScroll;

    // Schedule view
    @FXML private ListView<HBox> scheduleListView;

    // Form controls
    @FXML private TextField medicineNameField;
    @FXML private ComboBox<String> medicineTypeCombo;

    // type-specific controls
    @FXML private Spinner<Integer> pillsPerDoseSpinner;
    @FXML private Spinner<Integer> syrupMlPerDoseSpinner;
    @FXML private Spinner<Integer> unitsPerDoseSpinner;

    // date range pickers
    @FXML private DatePicker startDatePicker;
    @FXML private DatePicker endDatePicker;

    // time pickers / list (1-hour intervals)
    @FXML private ComboBox<String> timePickerCombo;
    @FXML private Button addTimeButton;
    @FXML private Button removeTimeButton;
    @FXML private Button clearTimesButton;
    @FXML private ListView<String> timesListView;

    // action buttons
    @FXML private Button saveButton;
    @FXML private Button backButton;

    // Firestore / state
    private String resolvedElderId = null;
    private String resolvedElderUsername = null;

    private String currentCaretakerId = null;
    private String currentCaretakerUsername = null;
    private String currentCaretakerElderId = null;

    private volatile boolean canEditResolvedElder = false;

    // Caretaker view: hide action buttons. Keep the logic for elder use later.
    private final boolean showDoseActionButtons = false;

    private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

    // Keep a listener reference so we can avoid multiple bindings
    private ChangeListener<javafx.geometry.Bounds> viewportListener = null;

    @FXML
    public void initialize() {
        // close top button
        closeButton.setOnAction(e -> handleClose());

        // medicine types
        medicineTypeCombo.getItems().addAll("Tablet", "Capsule", "Syrup", "Injection", "Other");
        medicineTypeCombo.setValue("Tablet");

        // spinners
        pillsPerDoseSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));
        syrupMlPerDoseSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 5));
        unitsPerDoseSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 1));

        // date pickers defaults
        startDatePicker.setValue(LocalDate.now());
        endDatePicker.setValue(LocalDate.now().plusDays(7));

        // time options: 1-hour steps
        List<String> hourly = generateTimeOptions(60);
        timePickerCombo.getItems().setAll(hourly);
        timePickerCombo.setValue("08:00");

        // If startDate changes, adjust the available times for today
        startDatePicker.valueProperty().addListener((obs, oldV, newV) -> filterTimeOptionsForDate(newV));

        // wire events
        addTimeButton.setOnAction(e -> handleAddTime());
        removeTimeButton.setOnAction(e -> handleRemoveSelectedTime());
        clearTimesButton.setOnAction(e -> timesListView.getItems().clear());

        medicineTypeCombo.setOnAction(e -> updateTypeControls());
        updateTypeControls();

        // wire save/back
        saveButton.setOnAction(e -> handleSaveMedicine());
        backButton.setOnAction(e -> handleBack());

        // compact times list
        timesListView.setFixedCellSize(28);

        // When the scene is ready, bind scheduleListView to the ScrollPane viewport width
        Platform.runLater(() -> {
            if (centerScroll != null) {
                // Remove previous listener if any (safety)
                if (viewportListener != null) centerScroll.viewportBoundsProperty().removeListener(viewportListener);

                viewportListener = (obs, oldB, newB) -> {
                    double w = newB.getWidth();
                    // leave a small margin
                    scheduleListView.setPrefWidth(Math.max(160, w - 24));
                    // rebind label widths in each row to the new list width
                    scheduleListView.getItems().forEach(this::bindRowWidths);
                };
                centerScroll.viewportBoundsProperty().addListener(viewportListener);

                // initialize sizes now as well
                javafx.geometry.Bounds b = centerScroll.getViewportBounds();
                if (b != null) {
                    scheduleListView.setPrefWidth(Math.max(160, b.getWidth() - 24));
                }
            } else {
                // fallback
                scheduleListView.prefWidthProperty().bind(scheduleListView.widthProperty());
            }
        });

        // detect logged-in caretaker and load linked elder
        detectLoggedInCaretaker();

        // initial filtering for today's date
        filterTimeOptionsForDate(startDatePicker.getValue());
    }

    // -----------------------
    // Window controls
    // -----------------------
    @FXML
    private void handleClose() {
        try {
            Stage st = (Stage) closeButton.getScene().getWindow();
            st.close();
        } catch (Exception ex) {
            // ignore
        }
    }

    // -----------------------
    // Caretaker & elder
    // -----------------------
    private void detectLoggedInCaretaker() {
        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        new Thread(() -> {
            try {
                QuerySnapshot snap = users.whereEqualTo("loggedIn", true)
                        .whereEqualTo("role", "caretaker")
                        .get().get();

                if (!snap.isEmpty()) {
                    QueryDocumentSnapshot doc = snap.getDocuments().get(0);
                    currentCaretakerId = doc.getId();
                    currentCaretakerUsername = doc.getString("username");
                    Object elderIdObj = doc.get("elderId");
                    if (elderIdObj != null) currentCaretakerElderId = elderIdObj.toString();

                    Platform.runLater(() -> statusLabel.setText("Logged in: " + (currentCaretakerUsername == null ? currentCaretakerId : currentCaretakerUsername)));

                    if (currentCaretakerElderId != null) {
                        loadMyElder(currentCaretakerElderId);
                    } else {
                        Platform.runLater(() -> elderFoundLabel.setText("This caretaker is not linked to an elder."));
                    }
                } else {
                    Platform.runLater(() -> statusLabel.setText("No logged-in caretaker found."));
                }
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("Failed to detect caretaker."));
            }
        }).start();
    }

    private void loadMyElder(String elderId) {
        Firestore db = FirestoreService.getFirestore();
        DocumentReference elderRef = db.collection("users").document(elderId);

        new Thread(() -> {
            try {
                DocumentSnapshot snap = elderRef.get().get();
                if (snap.exists()) {
                    resolvedElderId = snap.getId();
                    resolvedElderUsername = snap.getString("username");
                    canEditResolvedElder = true;
                    Platform.runLater(() -> {
                        elderFoundLabel.setText("Linked elder: " + resolvedElderUsername);
                        loadTodaySchedule();
                    });
                } else {
                    Platform.runLater(() -> elderFoundLabel.setText("Linked elder not found."));
                }
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> elderFoundLabel.setText("Failed to load elder."));
            }
        }).start();
    }

    // -----------------------
    // Type UI
    // -----------------------
    private void updateTypeControls() {
        String type = medicineTypeCombo.getValue();
        pillsPerDoseSpinner.setDisable(true);
        syrupMlPerDoseSpinner.setDisable(true);
        unitsPerDoseSpinner.setDisable(true);

        if ("Tablet".equalsIgnoreCase(type) || "Capsule".equalsIgnoreCase(type)) {
            pillsPerDoseSpinner.setDisable(false);
        } else if ("Syrup".equalsIgnoreCase(type)) {
            syrupMlPerDoseSpinner.setDisable(false);
        } else if ("Injection".equalsIgnoreCase(type)) {
            unitsPerDoseSpinner.setDisable(false);
        }
    }

    // -----------------------
    // Time helpers
    // -----------------------
    private List<String> generateTimeOptions(int stepMinutes) {
        List<String> out = new ArrayList<>();
        LocalTime cur = LocalTime.of(0, 0);
        int steps = (24 * 60) / stepMinutes;
        for (int i = 0; i < steps; i++) {
            out.add(cur.format(timeFmt));
            cur = cur.plusMinutes(stepMinutes);
        }
        return out;
    }

    /**
     * Filter timePickerCombo items when startDate is chosen.
     * If startDate == today: remove times strictly before now (rounded to minute).
     */
    private void filterTimeOptionsForDate(LocalDate selDate) {
        List<String> hourly = generateTimeOptions(60);
        if (selDate == null) {
            timePickerCombo.getItems().setAll(hourly);
            return;
        }
        LocalDate today = LocalDate.now();
        ZoneId zid = ZoneId.systemDefault();
        if (selDate.isEqual(today)) {
            LocalTime nowTime = LocalTime.now(zid).withSecond(0).withNano(0);
            List<String> allowed = new ArrayList<>();
            for (String t : hourly) {
                try {
                    LocalTime lt = LocalTime.parse(t);
                    if (!lt.isBefore(nowTime)) allowed.add(t);
                } catch (Exception ignored) {}
            }
            if (allowed.isEmpty()) {
                timePickerCombo.getItems().setAll(hourly);
            } else {
                timePickerCombo.getItems().setAll(allowed);
            }
            if (!timePickerCombo.getItems().isEmpty()) timePickerCombo.setValue(timePickerCombo.getItems().get(0));
        } else {
            timePickerCombo.getItems().setAll(hourly);
            timePickerCombo.setValue("08:00");
        }
    }

    @FXML
    private void handleAddTime() {
        String t = timePickerCombo.getValue();
        if (t == null || t.isEmpty()) {
            statusLabel.setText("Choose a time to add.");
            return;
        }

        // If the chosen startDate is today, ensure t >= now
        LocalDate startDate = startDatePicker.getValue();
        ZoneId zid = ZoneId.systemDefault();
        if (startDate != null && startDate.isEqual(LocalDate.now(zid))) {
            LocalTime nowTime = LocalTime.now(zid).withSecond(0).withNano(0);
            try {
                LocalTime chosen = LocalTime.parse(t);
                if (chosen.isBefore(nowTime)) {
                    statusLabel.setText("Cannot add time earlier than current time for today's start date. Choose a later time or pick the next day.");
                    return;
                }
            } catch (Exception ex) {
                statusLabel.setText("Invalid time format.");
                return;
            }
        }

        if (!timesListView.getItems().contains(t)) {
            timesListView.getItems().add(t);
            timesListView.getItems().sort(Comparator.comparing(LocalTime::parse));
            statusLabel.setText("Added time: " + t);
        } else {
            statusLabel.setText("Time already added.");
        }
    }

    @FXML
    private void handleRemoveSelectedTime() {
        String sel = timesListView.getSelectionModel().getSelectedItem();
        if (sel != null) {
            timesListView.getItems().remove(sel);
            statusLabel.setText("Removed time: " + sel);
        }
    }

    // -----------------------
    // Save medicine
    // -----------------------
    @FXML
    private void handleSaveMedicine() {
        if (resolvedElderId == null) {
            statusLabel.setText("No elder linked. Cannot save.");
            return;
        }
        if (!canEditResolvedElder) {
            statusLabel.setText("Not authorized to edit this elder's medicines.");
            return;
        }
        String name = medicineNameField.getText();
        if (name == null || name.trim().isEmpty()) {
            statusLabel.setText("Provide medicine name.");
            return;
        }

        // date range validation
        LocalDate startDate = startDatePicker.getValue();
        LocalDate endDate = endDatePicker.getValue();
        if (startDate == null || endDate == null) {
            statusLabel.setText("Select both start and end dates.");
            return;
        }
        LocalDate today = LocalDate.now();
        if (startDate.isBefore(today)) {
            statusLabel.setText("Start date cannot be in the past.");
            return;
        }
        if (endDate.isBefore(startDate)) {
            statusLabel.setText("End date must be the same or after start date.");
            return;
        }

        // times: require at least one time
        List<String> times = new ArrayList<>(timesListView.getItems());
        if (times.isEmpty()) {
            statusLabel.setText("Add at least one time for the day.");
            return;
        }

        // Ensure earliest selected time + startDate is not before now
        List<String> dedupedTimes = dedupeSortTimes(times);
        if (dedupedTimes.isEmpty()) {
            statusLabel.setText("No valid times to save.");
            return;
        }
        String firstTime = dedupedTimes.get(0);
        ZoneId zid = ZoneId.systemDefault();
        try {
            LocalDateTime firstDateTime = LocalDateTime.of(startDate, LocalTime.parse(firstTime));
            Instant firstInstant = firstDateTime.atZone(zid).toInstant();
            Instant nowInst = Instant.now();
            if (firstInstant.isBefore(nowInst)) {
                statusLabel.setText("The first dose datetime would be in the past. Pick a later time or choose a later start date.");
                return;
            }
        } catch (Exception ex) {
            statusLabel.setText("Invalid first dose time.");
            return;
        }

        String type = medicineTypeCombo.getValue();
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name.trim());
        payload.put("type", type);
        payload.put("createdAt", Timestamp.now());
        payload.put("createdByCaretaker", currentCaretakerId == null ? "" : currentCaretakerId);

        // type-specific amount info
        if ("Tablet".equalsIgnoreCase(type) || "Capsule".equalsIgnoreCase(type)) {
            payload.put("pillsPerDose", pillsPerDoseSpinner.getValue());
            payload.put("amount", pillsPerDoseSpinner.getValue() + " pill(s)");
        } else if ("Syrup".equalsIgnoreCase(type)) {
            payload.put("mlPerDose", syrupMlPerDoseSpinner.getValue());
            payload.put("amount", syrupMlPerDoseSpinner.getValue() + " ml");
        } else if ("Injection".equalsIgnoreCase(type)) {
            payload.put("unitsPerDose", unitsPerDoseSpinner.getValue());
            payload.put("amount", unitsPerDoseSpinner.getValue() + " units");
        } else {
            payload.put("amount", "");
        }

        // attach active date range
        payload.put("activePeriod", Map.of(
                "startDate", startDate.toString(),
                "endDate", endDate.toString()
        ));

        // always fixed-style times (daily list)
        payload.put("timeRange", Map.of("type", "fixed"));

        // store deduped & sorted times
        payload.put("times", dedupedTimes);

        // -----------------------
        // NEW: compute and store first and last dose date/time
        // -----------------------
        try {
            String lastTime = dedupedTimes.get(dedupedTimes.size() - 1);

            LocalTime ft = LocalTime.parse(firstTime);
            LocalTime lt = LocalTime.parse(lastTime);

            LocalDateTime firstDateTime = LocalDateTime.of(startDate, ft);
            Instant firstInstant = firstDateTime.atZone(zid).toInstant();
            Timestamp firstTimestamp = Timestamp.ofTimeSecondsAndNanos(firstInstant.getEpochSecond(), firstInstant.getNano());

            LocalDateTime lastDateTime = LocalDateTime.of(endDate, lt);
            Instant lastInstant = lastDateTime.atZone(zid).toInstant();
            Timestamp lastTimestamp = Timestamp.ofTimeSecondsAndNanos(lastInstant.getEpochSecond(), lastInstant.getNano());

            Map<String, Object> firstDoseMap = Map.of(
                    "date", startDate.toString(),
                    "time", firstTime,
                    "timestamp", firstTimestamp
            );
            Map<String, Object> lastDoseMap = Map.of(
                    "date", endDate.toString(),
                    "time", lastTime,
                    "timestamp", lastTimestamp
            );

            payload.put("firstDose", firstDoseMap);
            payload.put("lastDose", lastDoseMap);
        } catch (Exception ex) {
            ex.printStackTrace();
            // fallback: store string-only maps if something goes wrong with timestamp creation
            payload.put("firstDose", Map.of("date", startDate.toString(), "time", dedupedTimes.get(0)));
            payload.put("lastDose", Map.of("date", endDate.toString(), "time", dedupedTimes.get(dedupedTimes.size()-1)));
        }

        // persist to Firestore
        Firestore db = FirestoreService.getFirestore();
        CollectionReference medicCol = db.collection("users").document(resolvedElderId).collection("medicines");
        Platform.runLater(() -> statusLabel.setText("Saving..."));
        new Thread(() -> {
            try {
                DocumentReference doc = medicCol.document();
                payload.put("medicineId", doc.getId());
                doc.set(payload).get();

                // on success: clear the form (fully) and focus name field for next entry
                Platform.runLater(() -> {
                    statusLabel.setText("Saved " + name);
                    clearForm();
                    medicineNameField.requestFocus();
                    loadTodaySchedule();
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("Failed to save: " + ex.getMessage()));
            }
        }).start();
    }

    // -----------------------
    // Load today's schedule
    // -----------------------
    private void loadTodaySchedule() {
        scheduleListView.getItems().clear();
        if (resolvedElderId == null) {
            statusLabel.setText("No elder linked.");
            return;
        }
        Firestore db = FirestoreService.getFirestore();
        CollectionReference medicCol = db.collection("users").document(resolvedElderId).collection("medicines");

        new Thread(() -> {
            try {
                QuerySnapshot snap = medicCol.get().get();
                List<QueryDocumentSnapshot> docs = snap.getDocuments();
                List<HBox> cards = new ArrayList<>();
                LocalDate today = LocalDate.now();
                ZoneId zid = ZoneId.systemDefault();

                for (QueryDocumentSnapshot d : docs) {
                    String medId = d.getId();
                    String name = safeString(d.getString("name"));
                    String type = safeString(d.getString("type"));
                    String amount = safeString(d.getString("amount"));
                    List<String> times = (List<String>) d.get("times");
                    if (times == null || times.isEmpty()) continue;

                    // Determine cutoff instant:
                    Instant cutoffInstant = buildCutoffInstant(d, times);
                    // If there is a cutoff instant, decide whether to show any doses for today
                    boolean showAnyForToday = true;
                    if (cutoffInstant != null) {
                        LocalDate cutoffDate = LocalDate.ofInstant(cutoffInstant, zid);
                        if (today.isAfter(cutoffDate)) {
                            // today's date is after the cutoff date -> skip all doses for this medicine
                            showAnyForToday = false;
                        }
                    }
                    if (!showAnyForToday) continue;

                    // If cutoff exists and cutoff date == today, only include times <= cutoffInstant
                    boolean cutoffIsToday = false;
                    if (cutoffInstant != null) {
                        LocalDate cutoffDate = LocalDate.ofInstant(cutoffInstant, zid);
                        cutoffIsToday = today.isEqual(cutoffDate);
                    }

                    for (String t : times) {
                        // If cutoff is today, compare individual time's instant to cutoffInstant
                        if (cutoffIsToday && cutoffInstant != null) {
                            try {
                                LocalTime lt = LocalTime.parse(t);
                                LocalDateTime dt = LocalDateTime.of(today, lt);
                                Instant inst = dt.atZone(zid).toInstant();
                                if (inst.isAfter(cutoffInstant)) {
                                    // this dose time is after cutoff -> skip it
                                    continue;
                                }
                            } catch (Exception ex) {
                                // if parsing fails, fall back to including the time
                            }
                        }
                        HBox card = makeDoseCard(medId, name, type, amount, t);
                        cards.add(card);
                    }
                }
                // sort by time label (userData)
                cards.sort(Comparator.comparing(h -> {
                    Label timeLabel = (Label) h.getUserData();
                    return timeLabel.getText();
                }));
                Platform.runLater(() -> {
                    scheduleListView.getItems().setAll(cards);

                    // recompute list height from rows and cap it
                    double totalHeight = 0;
                    for (HBox h : cards) {
                        h.applyCss();
                        h.layout();
                        double ph = h.prefHeight(-1);
                        if (ph <= 0) ph = 64;
                        totalHeight += ph;
                    }
                    double newHeight = Math.min(Math.max(120, totalHeight + 8), 1000);
                    scheduleListView.setPrefHeight(newHeight);

                    // bind label widths now that list width is known
                    scheduleListView.getItems().forEach(this::bindRowWidths);

                    statusLabel.setText("Loaded " + cards.size() + " doses.");
                });
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("Failed to load schedule."));
            }
        }).start();
    }

    /**
     * Build cutoff Instant for a medicine document:
     * 1) forceEndedAt (if present)
     * 2) lastDose.timestamp (if present)
     * 3) activePeriod.endDate + latest configured time (if present)
     * 4) top-level "endDate" (string) + latest time
     * Returns null if no usable cutoff found.
     */
    private Instant buildCutoffInstant(DocumentSnapshot d, List<String> times) {
        try {
            // prefer forceEndedAt
            Object forceEndedAtObj = d.get("forceEndedAt");
            Instant inst = extractInstantFromObject(forceEndedAtObj);
            if (inst != null) return inst;

            // fallback: lastDose.timestamp
            Object lastDoseObj = d.get("lastDose");
            inst = extractInstantFromObject(lastDoseObj);
            if (inst != null) return inst;

            // fallback: activePeriod.endDate + latest time from times list
            Object activePeriodObj = d.get("activePeriod");
            if (activePeriodObj instanceof Map) {
                Object endDateObj = ((Map<?, ?>) activePeriodObj).get("endDate");
                if (endDateObj instanceof String) {
                    String endDateStr = (String) endDateObj;
                    // find latest time
                    List<String> deduped = dedupeSortTimes(times);
                    if (!deduped.isEmpty()) {
                        String latestTime = deduped.get(deduped.size() - 1);
                        try {
                            LocalDate ed = LocalDate.parse(endDateStr);
                            LocalTime lt = LocalTime.parse(latestTime);
                            LocalDateTime ldt = LocalDateTime.of(ed, lt);
                            return ldt.atZone(ZoneId.systemDefault()).toInstant();
                        } catch (Exception ignored) {}
                    } else {
                        // no times but we have endDate string -> treat as end-of-day
                        try {
                            LocalDate ed = LocalDate.parse(endDateStr);
                            LocalDateTime ldt = LocalDateTime.of(ed, LocalTime.MAX);
                            return ldt.atZone(ZoneId.systemDefault()).toInstant();
                        } catch (Exception ignored) {}
                    }
                }
            }

            // fallback: top-level "endDate" string (some docs might have it)
            Object topEnd = d.get("endDate");
            if (topEnd instanceof String) {
                String endDateStr = (String) topEnd;
                List<String> deduped = dedupeSortTimes(times);
                if (!deduped.isEmpty()) {
                    String latestTime = deduped.get(deduped.size() - 1);
                    try {
                        LocalDate ed = LocalDate.parse(endDateStr);
                        LocalTime lt = LocalTime.parse(latestTime);
                        LocalDateTime ldt = LocalDateTime.of(ed, lt);
                        return ldt.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored) {}
                } else {
                    try {
                        LocalDate ed = LocalDate.parse(endDateStr);
                        LocalDateTime ldt = LocalDateTime.of(ed, LocalTime.MAX);
                        return ldt.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored) {}
                }
            }

            // nothing usable
            return null;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Try to extract an Instant from:
     *  - null => null
     *  - Firestore Timestamp (com.google.cloud.Timestamp)
     *  - java.util.Date
     *  - Map containing a "timestamp" entry (which may also be Timestamp or Date)
     *  - Map with "date"/"time" but no timestamp -> tries to parse into Instant
     */
    private Instant extractInstantFromObject(Object obj) {
        try {
            if (obj == null) return null;
            if (obj instanceof Map) {
                Object ts = ((Map<?, ?>) obj).get("timestamp");
                if (ts != null) return extractInstantFromObject(ts);
                // if timestamp missing, try to compose from "date" and "time" if available
                Object dateObj = ((Map<?, ?>) obj).get("date");
                Object timeObj = ((Map<?, ?>) obj).get("time");
                if (dateObj instanceof String) {
                    String dateStr = (String) dateObj;
                    String timeStr = (timeObj instanceof String) ? (String) timeObj : "00:00";
                    try {
                        LocalDate d = LocalDate.parse(dateStr);
                        LocalTime tm = LocalTime.parse(timeStr);
                        LocalDateTime ldt = LocalDateTime.of(d, tm);
                        return ldt.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored) {}
                }
                return null;
            }
            if (obj instanceof Timestamp) {
                // com.google.cloud.Timestamp
                return ((Timestamp) obj).toDate().toInstant();
            }
            if (obj instanceof java.util.Date) {
                return ((java.util.Date) obj).toInstant();
            }
            return null;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Bind the label widths inside a row so they wrap instead of getting clipped.
     * Uses about 60% of the list width for the meta column (adjust multiplier if desired).
     */
    private void bindRowWidths(HBox row) {
        if (row == null) return;
        for (javafx.scene.Node child : row.getChildren()) {
            if (child instanceof VBox) {
                VBox meta = (VBox) child;
                meta.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(meta, Priority.ALWAYS);
                meta.getChildren().forEach(n -> {
                    if (n instanceof Label) {
                        Label lbl = (Label) n;
                        lbl.setWrapText(true);
                        // use scheduleListView width (not scroll viewport) to ensure proper reflow
                        lbl.maxWidthProperty().bind(scheduleListView.widthProperty().multiply(0.60));
                    }
                });
            } else if (child instanceof Label) {
                Label timeLabel = (Label) child;
                timeLabel.setWrapText(false);
            }
        }
    }

    private HBox makeDoseCard(String medId, String name, String type, String amount, String time) {
        HBox root = new HBox(12);
        root.setStyle("-fx-padding:10; -fx-background-radius:8; -fx-border-radius:8; -fx-border-color:#e6e9ef; -fx-background-color:white;");

        VBox meta = new VBox(4);
        meta.setFillWidth(true);

        Label title = new Label(name);
        title.setStyle("-fx-font-weight:bold; -fx-font-size:14;");
        title.setWrapText(true);

        Label typeLabel = new Label(type);
        typeLabel.setStyle("-fx-text-fill:#444; -fx-font-size:12;");
        typeLabel.setWrapText(true);

        Label sub = new Label(amount);
        sub.setStyle("-fx-text-fill:#666; -fx-font-size:12;");
        sub.setWrapText(true);

        meta.getChildren().addAll(title, typeLabel, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label timeLabel = new Label(time);
        timeLabel.setStyle("-fx-font-size:13; -fx-text-fill:#333;");
        timeLabel.setMinWidth(48);
        timeLabel.setPrefWidth(64);
        timeLabel.setMaxWidth(96);

        root.setUserData(timeLabel);

        if (showDoseActionButtons) {
            Button taken = new Button("Taken");
            taken.setStyle("-fx-background-color:#2e7d32; -fx-text-fill:white;");
            taken.setOnAction(e -> markDoseEvent(medId, time, "taken"));
            Button skip = new Button("Skip");
            skip.setStyle("-fx-background-color:#c62828; -fx-text-fill:white;");
            skip.setOnAction(e -> markDoseEvent(medId, time, "skipped"));
            HBox actions = new HBox(8, taken, skip);
            root.getChildren().addAll(meta, spacer, timeLabel, actions);
        } else {
            root.getChildren().addAll(meta, spacer, timeLabel);
        }

        return root;
    }

    private void markDoseEvent(String medId, String timeFor, String action) {
        if (resolvedElderId == null) {
            statusLabel.setText("No elder linked.");
            return;
        }
        Firestore db = FirestoreService.getFirestore();
        DocumentReference medRef = db.collection("users").document(resolvedElderId).collection("medicines").document(medId);
        CollectionReference history = medRef.collection("history");
        Map<String, Object> ev = new HashMap<>();
        ev.put("action", action);
        ev.put("timeFor", timeFor);
        ev.put("caretakerId", currentCaretakerId == null ? "" : currentCaretakerId);
        ev.put("createdAt", Timestamp.now());
        ev.put("date", LocalDate.now().toString());
        statusLabel.setText("Recording " + action + "...");
        new Thread(() -> {
            try {
                history.document().set(ev).get();
                Platform.runLater(() -> {
                    statusLabel.setText("Recorded " + action + " for " + timeFor);
                    loadTodaySchedule();
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("Failed to record: " + ex.getMessage()));
            }
        }).start();
    }

    // -----------------------
    // Utilities
    // -----------------------
    private boolean isValidHHmm(String s) {
        try { LocalTime.parse(s); return true; } catch (Exception ex) { return false; }
    }

    private List<String> dedupeSortTimes(Collection<String> times) {
        TreeSet<LocalTime> set = new TreeSet<>();
        for (String t : times) try { set.add(LocalTime.parse(t)); } catch (Exception ignored) {}
        List<String> out = new ArrayList<>();
        for (LocalTime lt : set) out.add(lt.format(timeFmt));
        return out;
    }

    private void clearForm() {
        medicineNameField.clear();
        pillsPerDoseSpinner.getValueFactory().setValue(1);
        syrupMlPerDoseSpinner.getValueFactory().setValue(5);
        unitsPerDoseSpinner.getValueFactory().setValue(1);
        timesListView.getItems().clear();
        medicineTypeCombo.setValue("Tablet");
        updateTypeControls();
        startDatePicker.setValue(LocalDate.now());
        endDatePicker.setValue(LocalDate.now().plusDays(7));
        filterTimeOptionsForDate(startDatePicker.getValue());
    }

    private String safeString(String s) { return s == null ? "" : s; }

    // -----------------------
    // Navigation
    // -----------------------
    @FXML
    private void handleBack() {
        try {
            Parent root = FXMLLoader.load(getClass().getResource("/fxml/caretaker_dashboard.fxml"));
            Stage stage = (Stage) backButton.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            Platform.runLater(() -> statusLabel.setText("Failed to load dashboard."));
        }
    }
}
