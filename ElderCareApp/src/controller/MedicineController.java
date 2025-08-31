package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import utils.FirestoreService;
import utils.SessionManager;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/*
  MedicineController

  Responsibilities (high-level):
  - Drive the "Add Medicine / Today's Schedule" UI.
  - Read medicines from Firestore for the resolved elder and build per-dose UI cards.
  - Allow adding a medicine (form validation -> persist to Firestore).
  - Allow recording dose events ("taken"/"skipped") into a medicine's history subcollection.
  - Manage UI wiring (spinners, date pickers, time combo, list sizing/binding) and keep ownership of
    the displayed state (resolvedElderId, caretaker info, permissions).
  - All Firestore reads and writes are performed off the JavaFX Application Thread (background threads).
    Any UI mutation is performed on the JavaFX Application Thread via Platform.runLater(...).

  Threading model:
  - Long-running operations (Firestore network I/O) are executed in new background Threads.
  - UI updates are wrapped with Platform.runLater to ensure modifications happen on the FX thread.
  - Methods intentionally do not block the FX thread while waiting for network operations.
*/

public class MedicineController {

    /*
      FXML-injected UI controls

      Notes:
      - These are bound via FXML. Their fx:id values must match the FXML file.
      - Access to these objects must happen on the JavaFX Application Thread.
      - Many methods assume these exist (non-null) after FXMLLoader has initialized the controller.
    */
    @FXML private Label elderFoundLabel;
    @FXML private Label statusLabel;
    @FXML private ScrollPane centerScroll;
    @FXML private ListView<HBox> scheduleListView;
    @FXML private TextField medicineNameField;
    @FXML private ComboBox<String> medicineTypeCombo;
    @FXML private Spinner<Integer> pillsPerDoseSpinner;
    @FXML private Spinner<Integer> syrupMlPerDoseSpinner;
    @FXML private Spinner<Integer> unitsPerDoseSpinner;
    @FXML private DatePicker startDatePicker;
    @FXML private DatePicker endDatePicker;
    @FXML private ComboBox<String> timePickerCombo;
    @FXML private Button addTimeButton;
    @FXML private Button removeTimeButton;
    @FXML private Button clearTimesButton;
    @FXML private ListView<String> timesListView;
    @FXML private Button saveButton;
    @FXML private Button backButton;

    /*
      Controller state (non-UI)

      resolvedElderId / resolvedElderUsername:
        - Identify the elder whose medicines are being edited/viewed.
        - Populated by loadMyElder(...) which is called when the logged-in caretaker has a linked elder.

      currentCaretakerId / currentCaretakerUsername / currentCaretakerElderId:
        - Identify the current logged-in caretaker (read from SessionManager and Firestore).
        - currentCaretakerElderId is the elderId saved on the caretaker's user document.

      canEditResolvedElder:
        - True when the controller has confirmed the logged-in caretaker can edit the elder's medicines.
        - Used as an authorization gate before saving.

      showDoseActionButtons:
        - Toggle that determines whether each dose card displays "Taken" / "Skip" action buttons.
        - Kept as a boolean field so the UI creation logic can remain unchanged.

      timeFmt:
        - Shared DateTimeFormatter for HH:mm formatting and parsing.

      viewportListener:
        - A listener attached to centerScroll.viewportBoundsProperty to re-compute list widths responsively.
        - Stored so it can be removed and replaced safely on re-initialization.
    */
    private String resolvedElderId = null;
    private String resolvedElderUsername = null;
    private String currentCaretakerId = null;
    private String currentCaretakerUsername = null;
    private String currentCaretakerElderId = null;
    private volatile boolean canEditResolvedElder = false;
    private boolean showDoseActionButtons = false;
    private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
    private ChangeListener<javafx.geometry.Bounds> viewportListener = null;

    /*
      initialize()

      Purpose:
        - This method is invoked by the JavaFX runtime after the FXML loader has injected UI controls.
        - Wire up UI event handlers, initialize form defaults, set date/time defaults, and start
          discovery of the logged-in caretaker (which will trigger elder loading and schedule loading).
        - Also sets up layout responsiveness: binds the scheduleListView width to the scroll viewport width.

      Important details:
        - Calls to Firestore or long-running work are not performed here directly; detectLoggedInCaretaker()
          will create background threads. initialize() ensures the UI is ready immediately.
        - Platform.runLater(...) is used when adding listeners that require the scene graph to be realized.
    */
    @FXML
    public void initialize() {
        medicineTypeCombo.getItems().addAll("Tablet", "Capsule", "Syrup", "Injection", "Other");
        medicineTypeCombo.setValue("Tablet");

        pillsPerDoseSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));
        syrupMlPerDoseSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 5));
        unitsPerDoseSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 1));

        startDatePicker.setValue(LocalDate.now());
        endDatePicker.setValue(LocalDate.now().plusDays(7));

        List<String> hourly = generateTimeOptions(60);
        timePickerCombo.getItems().setAll(hourly);
        timePickerCombo.setValue("08:00");

        startDatePicker.valueProperty().addListener((obs, oldV, newV) -> {
            filterTimeOptionsForDate(newV);
        });

        addTimeButton.setOnAction(this::onAddTimeAction);
        removeTimeButton.setOnAction(this::onRemoveTimeAction);
        clearTimesButton.setOnAction(this::onClearTimesAction);

        medicineTypeCombo.setOnAction(this::onMedicineTypeChanged);
        updateTypeControls();

        saveButton.setOnAction(this::onSaveAction);
        backButton.setOnAction(this::onBackAction);

        timesListView.setFixedCellSize(40);

        Platform.runLater(() -> {
            if (centerScroll != null) {
                if (viewportListener != null) centerScroll.viewportBoundsProperty().removeListener(viewportListener);

                viewportListener = (obs, oldB, newB) -> {
                    double w = newB.getWidth();
                    scheduleListView.setPrefWidth(Math.max(160, w - 24));
                    scheduleListView.getItems().forEach(this::bindRowWidths);
                };
                centerScroll.viewportBoundsProperty().addListener(viewportListener);

                javafx.geometry.Bounds b = centerScroll.getViewportBounds();
                if (b != null) {
                    scheduleListView.setPrefWidth(Math.max(160, b.getWidth() - 24));
                }
            } else {
                scheduleListView.prefWidthProperty().bind(scheduleListView.widthProperty());
            }
        });

        detectLoggedInCaretaker();

        filterTimeOptionsForDate(startDatePicker.getValue());
    }

    /*
      Small adapter wrapper methods:
      - These maintain a consistent shape (EventHandler<ActionEvent>) while delegating to parameterless handlers.
      - Keeps existing method signatures used elsewhere unchanged.
    */
    private void onAddTimeAction(ActionEvent ev) { handleAddTime(); }
    private void onRemoveTimeAction(ActionEvent ev) { handleRemoveSelectedTime(); }
    private void onClearTimesAction(ActionEvent ev) { timesListView.getItems().clear(); }
    private void onMedicineTypeChanged(ActionEvent ev) { updateTypeControls(); }
    private void onSaveAction(ActionEvent ev) { handleSaveMedicine(); }
    private void onBackAction(ActionEvent ev) { handleBack(); }

    /*
      handleClose()

      Purpose:
        - Close the window that contains this UI. Historically the UI had a close button; the field
          was removed. This method gracefully finds an available reference Node and closes its window.
      Threading / safety:
        - Executed on the FX thread when called from UI handlers.
        - Uses defensive checks to avoid NullPointerExceptions if the scene/window is not available.
    */
    @FXML
    private void handleClose() {
        try {
            javafx.scene.Node ref = (saveButton != null) ? saveButton : (backButton != null ? backButton : medicineNameField);
            if (ref != null && ref.getScene() != null) {
                Stage st = (Stage) ref.getScene().getWindow();
                st.close();
            }
        } catch (Exception ex) {
            // intentionally swallow exceptions here because closing is a best-effort operation
        }
    }

    /*
      detectLoggedInCaretaker()

      Purpose:
        - Identify the currently logged-in user via SessionManager.getCurrentUserId().
        - Verify that the user's Firestore document exists and has role "caretaker".
        - Populate caretaker-related fields and, if an elderId is present on the caretaker doc,
          call loadMyElder(elderId) to populate the elder and schedule.

      Implementation notes:
        - Runs Firestore queries on a background thread (new Thread).
        - All UI state changes are routed through Platform.runLater to ensure FX-thread safety.
        - Uses conservative checks (null checks and doc.exists()) to provide useful status messages.
    */
    private void detectLoggedInCaretaker() {
        new Thread(() -> {
            try {
                String currentUid = SessionManager.getCurrentUserId();
                if (currentUid == null) {
                    Platform.runLater(() -> {
                        statusLabel.setText("No logged-in caretaker found. Please login.");
                        elderFoundLabel.setText("No logged-in caretaker.");
                    });
                    return;
                }

                Firestore db = FirestoreService.getFirestore();
                DocumentSnapshot doc = db.collection("users").document(currentUid).get().get();
                if (!doc.exists()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Logged-in caretaker not found in database.");
                        elderFoundLabel.setText("Caretaker not found.");
                    });
                    return;
                }

                String role = doc.getString("role");
                if (role == null || !"caretaker".equalsIgnoreCase(role)) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Current user is not a caretaker.");
                        elderFoundLabel.setText("Not a caretaker account.");
                    });
                    return;
                }

                currentCaretakerId = currentUid;
                currentCaretakerUsername = doc.getString("username");
                Object elderIdObj = doc.get("elderId");
                if (elderIdObj != null) currentCaretakerElderId = elderIdObj.toString();

                Platform.runLater(() -> {
                    statusLabel.setText("Logged in: " + (currentCaretakerUsername == null ? currentCaretakerId : currentCaretakerUsername));
                });

                if (currentCaretakerElderId != null) {
                    loadMyElder(currentCaretakerElderId);
                } else {
                    Platform.runLater(() -> elderFoundLabel.setText("This caretaker is not linked to an elder."));
                }
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("Failed to detect caretaker."));
            }
        }).start();
    }

    /*
      loadMyElder(elderId)

      Purpose:
        - Read the elder user document from Firestore using the given elderId.
        - Populate resolvedElderId and resolvedElderUsername and set canEditResolvedElder=true on success.
        - Trigger loadTodaySchedule() once the elder has been resolved.

      Threading / error handling:
        - Network I/O is performed on a background thread.
        - UI updates use Platform.runLater.
        - Exceptions are caught and translated into status messages displayed to the user.
    */
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

    /*
      updateTypeControls()

      Purpose:
        - Enable/disable the spinners that are relevant to the chosen medicine type.
        - Tablet / Capsule -> pillsPerDoseSpinner visible/enabled
        - Syrup -> syrupMlPerDoseSpinner enabled
        - Injection -> unitsPerDoseSpinner enabled
        - Others -> no amount spinner enabled

      UI note:
        - We disable the irrelevant spinners (setDisable(true)) so the user cannot enter irrelevant amounts.
    */
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

    /*
      generateTimeOptions(stepMinutes)

      Purpose:
        - Build a list of times in HH:mm format starting at 00:00 and stepping by stepMinutes until 24:00.
        - Used to populate the timePickerCombo. For this UI we use hourly steps (60 minutes).

      Implementation detail:
        - Uses the shared timeFmt (HH:mm) so parsing/formatting is consistent across the controller.
    */
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

    /*
      filterTimeOptionsForDate(selDate)

      Purpose:
        - If the selected start date equals today's date, remove times strictly before the current time.
          This prevents adding a time earlier than "now" for a medicine that starts today.
        - For non-today dates, reset to the full hourly list and default to 08:00.

      Behavior:
        - If filtering yields an empty allowed list (rare if the day is late), fall back to the full list.
        - Always set a sensible selected value on the combo after modifying its items.
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

    /*
      handleAddTime()

      Purpose:
        - Validate and add the currently selected time from the timePickerCombo into timesListView.
        - Prevent duplicates and ensure that when the start date is today, times earlier than the current time are rejected.

      UI effects:
        - Adds the time to timesListView and sorts the list.
        - Updates statusLabel with success/failure messages.
    */
    @FXML
    private void handleAddTime() {
        String t = timePickerCombo.getValue();
        if (t == null || t.isEmpty()) {
            statusLabel.setText("Choose a time to add.");
            return;
        }

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

    /*
      handleRemoveSelectedTime()

      Purpose:
        - Remove the currently selected entry from the timesListView and update statusLabel.
    */
    @FXML
    private void handleRemoveSelectedTime() {
        String sel = timesListView.getSelectionModel().getSelectedItem();
        if (sel != null) {
            timesListView.getItems().remove(sel);
            statusLabel.setText("Removed time: " + sel);
        }
    }

    /*
      handleSaveMedicine()

      Purpose:
        - Validate form inputs (elder linked, authorization, name, date range, times).
        - Build a payload Map representing the medicine document to write to Firestore.
        - Compute and attach firstDose and lastDose timestamps (comprehensive handling including fallbacks).
        - Persist the medicine doc in the elder's "medicines" subcollection.
        - On success: update the UI (statusLabel), clear the form, and reload today's schedule.

      Validation rules enforced:
        - resolvedElderId must be known.
        - canEditResolvedElder must be true.
        - medicine name non-empty.
        - startDate and endDate present and startDate >= today; endDate >= startDate.
        - At least one time is configured.
        - The computed first dose datetime cannot be in the past.

      Firestore behavior:
        - Writes occur in a new background thread to avoid blocking the FX thread.
        - Uses FirestoreService.getFirestore() to obtain the Firestore instance.
        - On error, exception message is shown in statusLabel.
    */
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

        List<String> times = new ArrayList<>(timesListView.getItems());
        if (times.isEmpty()) {
            statusLabel.setText("Add at least one time for the day.");
            return;
        }

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

        payload.put("activePeriod", Map.of(
                "startDate", startDate.toString(),
                "endDate", endDate.toString()
        ));

        payload.put("timeRange", Map.of("type", "fixed"));
        payload.put("times", dedupedTimes);

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
            payload.put("firstDose", Map.of("date", startDate.toString(), "time", dedupedTimes.get(0)));
            payload.put("lastDose", Map.of("date", endDate.toString(), "time", dedupedTimes.get(dedupedTimes.size()-1)));
        }

        Firestore db = FirestoreService.getFirestore();
        CollectionReference medicCol = db.collection("users").document(resolvedElderId).collection("medicines");
        Platform.runLater(() -> statusLabel.setText("Saving..."));
        new Thread(() -> {
            try {
                DocumentReference doc = medicCol.document();
                payload.put("medicineId", doc.getId());
                doc.set(payload).get();

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

    /*
      loadTodaySchedule()

      Purpose:
        - Read all medicine documents from resolved elder's "medicines" subcollection and build a list of dose cards
          to display for the current date.
        - Apply a cutoff logic to exclude medicines whose active period ended before today (and optionally
          exclude times later than a cutoff instant if the cutoff falls on the current date).
        - Convert each relevant medicine/time into an HBox "card" built by makeDoseCard(...).
        - Sort cards by their time label and set them into scheduleListView on the FX thread.
        - After inserting, recompute the list preferred height and bind label widths in rows for responsive wrapping.

      Important algorithmic details:
        - For each medicine document, times are extracted safely by checking "instanceof List<?>"
          and only taking String elements.
        - The cutoff instant for a medicine is computed by buildCutoffInstant(document, times).
          If the cutoff instant is before today's date, the medicine is skipped entirely.
        - If cutoffInstant lies on today, times after that instant are excluded.
        - Sorting uses userData set to the timeLabel for stable numeric/time ordering.
    */
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

                    List<String> times = new ArrayList<>();
                    Object timesObj = d.get("times");
                    if (timesObj instanceof List<?>) {
                        for (Object o : (List<?>) timesObj) {
                            if (o instanceof String) times.add((String) o);
                        }
                    }
                    if (times.isEmpty()) continue;

                    Instant cutoffInstant = buildCutoffInstant(d, times);
                    boolean showAnyForToday = true;
                    if (cutoffInstant != null) {
                        LocalDate cutoffDate = LocalDate.ofInstant(cutoffInstant, zid);
                        if (today.isAfter(cutoffDate)) {
                            showAnyForToday = false;
                        }
                    }
                    if (!showAnyForToday) continue;

                    boolean cutoffIsToday = false;
                    if (cutoffInstant != null) {
                        LocalDate cutoffDate = LocalDate.ofInstant(cutoffInstant, zid);
                        cutoffIsToday = today.isEqual(cutoffDate);
                    }

                    for (String t : times) {
                        if (cutoffIsToday && cutoffInstant != null) {
                            try {
                                LocalTime lt = LocalTime.parse(t);
                                LocalDateTime dt = LocalDateTime.of(today, lt);
                                Instant inst = dt.atZone(zid).toInstant();
                                if (inst.isAfter(cutoffInstant)) {
                                    continue;
                                }
                            } catch (Exception ex) {
                                // parsing failed -> include time by default
                            }
                        }
                        HBox card = makeDoseCard(medId, name, type, amount, t);
                        cards.add(card);
                    }
                }

                cards.sort(Comparator.comparing(h -> {
                    Label timeLabel = (Label) h.getUserData();
                    return timeLabel.getText();
                }));

                Platform.runLater(() -> {
                    scheduleListView.getItems().setAll(cards);

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

                    scheduleListView.getItems().forEach(this::bindRowWidths);

                    statusLabel.setText("Loaded " + cards.size() + " doses.");
                });
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> statusLabel.setText("Failed to load schedule."));
            }
        }).start();
    }

    /*
      buildCutoffInstant(document, times)

      Purpose:
        - Compute an Instant after which a medicine should no longer be considered active.
        - The search order is intentionally conservative and designed to follow document schema possibilities:

          1) If document has a top-level "forceEndedAt" or a nested object that contains a timestamp,
             extract it and return immediately (highest precedence).
          2) If document has "lastDose" object with a timestamp, use that.
          3) If document has an "activePeriod" map with "endDate" string:
             - If times array non-empty: pair the endDate with the latest configured time (deduped/sorted)
               to compute the final Instant for that day.
             - If times empty: use endDate with LocalTime.MAX to treat the whole end date as active until 23:59:59.
          4) If document has a top-level "endDate" string, perform the same logic as (3).
          5) If none of the above produce a usable Instant, return null.

      Implementation details:
        - Uses extractInstantFromObject(...) to flexibly handle Timestamp, Date, Map or nested timestamp.
        - Defensive programming: catches exceptions and returns null if parsing fails.
    */
    private Instant buildCutoffInstant(DocumentSnapshot d, List<String> times) {
        try {
            Object forceEndedAtObj = d.get("forceEndedAt");
            Instant inst = extractInstantFromObject(forceEndedAtObj);
            if (inst != null) return inst;

            Object lastDoseObj = d.get("lastDose");
            inst = extractInstantFromObject(lastDoseObj);
            if (inst != null) return inst;

            Object activePeriodObj = d.get("activePeriod");
            if (activePeriodObj instanceof Map) {
                Object endDateObj = ((Map<?, ?>) activePeriodObj).get("endDate");
                if (endDateObj instanceof String) {
                    String endDateStr = (String) endDateObj;
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
            }

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

            return null;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /*
      extractInstantFromObject(obj)

      Purpose:
        - Flexibly extract a java.time.Instant from a variety of shapes that might be found in Firestore documents:
          a) a com.google.cloud.Timestamp instance
          b) a java.util.Date instance
          c) a Map containing a "timestamp" entry which itself can be a Timestamp/Date or nested Map
          d) a Map containing "date" (string) and optional "time" (string) -> parse to LocalDate/LocalTime
        - Return null if the object's shape cannot be converted to an Instant.

      Notes:
        - This helper centralizes parsing logic to keep buildCutoffInstant readable.
        - Any parsing exceptions are swallowed and result in null to indicate absence of a usable instant.
    */
    private Instant extractInstantFromObject(Object obj) {
        try {
            if (obj == null) return null;
            if (obj instanceof Map) {
                Object ts = ((Map<?, ?>) obj).get("timestamp");
                if (ts != null) return extractInstantFromObject(ts);
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

    /*
      bindRowWidths(row)

      Purpose:
        - For a created HBox "card", bind widths of textual Labels inside the meta VBox so that they wrap
          appropriately when the scheduleListView width changes.
        - Approximates a layout policy where the meta column uses ~60% of the list width and the time column
          remains compact to the right.

      Implementation details:
        - Iterates children of the HBox: when a child is a VBox it sets HGrow=ALWAYS and binds inner label maxWidth
          to scheduleListView.width * 0.60. For Label children (e.g., the time label) wrap is disabled.
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
                        lbl.maxWidthProperty().bind(scheduleListView.widthProperty().multiply(0.60));
                    }
                });
            } else if (child instanceof Label) {
                Label timeLabel = (Label) child;
                timeLabel.setWrapText(false);
            }
        }
    }

    /*
      makeDoseCard(medId, name, type, amount, time)

      Purpose:
        - Construct a visual HBox "card" representing one scheduled dose for a medication at a specific time.
        - The card contains:
          - meta VBox with title, type and sub (amount)
          - spacer region to push the time to the right
          - time Label (userData is set to this Label to allow sorting)
          - optionally, action buttons ("Taken" / "Skip") if showDoseActionButtons is true

      UI & logic notes:
        - All style-related inline code was moved to CSS classes; here we only set styleClass on nodes.
        - Action buttons call markDoseEvent(...) which writes a history event to Firestore in a background thread.
        - The method does not modify any external state; it simply returns a ready-to-insert Node.
    */
    private HBox makeDoseCard(String medId, String name, String type, String amount, String time) {
        HBox root = new HBox(12);

        root.getStyleClass().add("dose-card");

        VBox meta = new VBox(4);
        meta.setFillWidth(true);
        meta.getStyleClass().add("dose-meta");

        Label title = new Label(name);
        title.getStyleClass().add("dose-title");
        title.setWrapText(true);

        Label typeLabel = new Label(type);
        typeLabel.getStyleClass().add("dose-type");
        typeLabel.setWrapText(true);

        Label sub = new Label(amount);
        sub.getStyleClass().add("dose-sub");
        sub.setWrapText(true);

        meta.getChildren().addAll(title, typeLabel, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label timeLabel = new Label(time);
        timeLabel.getStyleClass().add("dose-time");

        root.setUserData(timeLabel);

        if (showDoseActionButtons) {
            Button taken = new Button("Taken");
            taken.getStyleClass().add("btn-taken");
            taken.setOnAction(evt -> markDoseEvent(medId, time, "taken"));
            Button skip = new Button("Skip");
            skip.getStyleClass().add("btn-skip");
            skip.setOnAction(evt -> markDoseEvent(medId, time, "skipped"));
            HBox actions = new HBox(8, taken, skip);
            root.getChildren().addAll(meta, spacer, timeLabel, actions);
        } else {
            root.getChildren().addAll(meta, spacer, timeLabel);
        }

        return root;
    }

    /*
      markDoseEvent(medId, timeFor, action)

      Purpose:
        - Append a history event (map with action, timeFor, caretakerId, createdAt, date) into:
          users/{elderId}/medicines/{medId}/history

      Behavior:
        - Immediately updates statusLabel to show "Recording ...".
        - Performs Firestore write in a new Thread (non-blocking).
        - On success, updates statusLabel and reloads today's schedule on the FX thread.
        - On failure, prints stack trace and shows an error message in statusLabel.

      Security / data note:
        - createdAt uses Firestore Timestamp.now() to ensure server-side consistent time encoding.
    */
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

    /*
      dedupeSortTimes(times)

      Purpose:
        - Deduplicate and sort a collection of time strings (HH:mm) and return a List<String> in ascending time order.
        - Uses a TreeSet<LocalTime> to normalize and sort; ignores entries that fail parsing.
    */
    private List<String> dedupeSortTimes(Collection<String> times) {
        TreeSet<LocalTime> set = new TreeSet<>();
        for (String t : times) try { set.add(LocalTime.parse(t)); } catch (Exception ignored) {}
        List<String> out = new ArrayList<>();
        for (LocalTime lt : set) out.add(lt.format(timeFmt));
        return out;
    }

    /*
      clearForm()

      Purpose:
        - Reset the "Add Medicine" form UI to sensible defaults:
          - Clear the medicine name field
          - Reset spinners to defaults
          - Clear times list
          - Reset medicine type to "Tablet"
          - Reset date range to today..today+7
          - Re-filter time options for today's date
    */
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

    /*
      safeString(s)

      Purpose:
        - Small utility to convert potentially null string values to a non-null empty string.
        - Used when reading optional Firestore string fields.
    */
    private String safeString(String s) { return s == null ? "" : s; }

    /*
      handleBack()

      Purpose:
        - Navigate back to the caretaker dashboard by loading "/fxml/caretaker_dashboard.fxml"
          and replacing the current scene on the existing Stage.
      Implementation notes:
        - Loads the new scene synchronously on the FX thread; exceptions are caught and surfaced in statusLabel.
    */
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
