package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import utils.FirestoreService;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class EditCurrentDosesController {

    @FXML private TableView<MedicineRecord> medicinesTable;
    @FXML private TableColumn<MedicineRecord, String> medNameCol;
    @FXML private TableColumn<MedicineRecord, String> medTypeCol;
    @FXML private TableColumn<MedicineRecord, String> medAmountCol;
    @FXML private TableColumn<MedicineRecord, String> medStartDateCol;
    @FXML private TableColumn<MedicineRecord, String> medEndDateCol;
    @FXML private TableColumn<MedicineRecord, String> medTimesCol;
    @FXML private TableColumn<MedicineRecord, Void> actionCol;

    @FXML private VBox editSection;
    @FXML private DatePicker editStartDate;
    @FXML private DatePicker editEndDate;
    @FXML private TextField editAmountField;
    // NON-EDITABLE unit label shown next to numeric amount (user cannot change)
    @FXML private Label editAmountUnitLabel;

    @FXML private ComboBox<String> timePickerCombo;
    @FXML private ListView<String> timesListView;
    @FXML private Button addTimeButton, removeTimeButton, clearTimesButton, updateMedicineBtn;
    @FXML private Label progressLabel;

    // Back button to return to dashboard
    @FXML private Button backButton;

    private final ObservableList<MedicineRecord> medicineList = FXCollections.observableArrayList();
    private final ObservableList<String> timesList = FXCollections.observableArrayList();
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private String resolvedElderId = null;
    private String currentCaretakerId = null;
    private MedicineRecord currentEditing = null;
    private List<String> originalTimes = new ArrayList<>();

    @FXML
    public void initialize() {
        setupColumns();
        setupActionButtons();
        detectLoggedInCaretaker();
        medicinesTable.setItems(medicineList);

        // populate hourly dropdown
        for (int h = 0; h < 24; h++) timePickerCombo.getItems().add(String.format("%02d:00", h));
        timePickerCombo.setEditable(true); // allow manual entry
        timePickerCombo.setPromptText("HH:mm");
        timesListView.setItems(timesList);

        addTimeButton.setOnAction(e -> addTime());
        removeTimeButton.setOnAction(e -> removeTime());
        clearTimesButton.setOnAction(e -> timesList.clear());

        updateMedicineBtn.setOnAction(e -> handleUpdateMedicine());
        editSection.setVisible(false);

        // back button returns to dashboard
        backButton.setOnAction(e -> handleBack());

        // When editing start date, filter available times (date logic remains)
        editStartDate.valueProperty().addListener((obs, oldV, newV) -> filterEditTimeOptionsForDate(newV));

        // double-click to edit a time in the list — manual editing allowed (no "can't set past" constraint)
        timesListView.setOnMouseClicked(evt -> {
            if (evt.getClickCount() == 2) {
                String selected = timesListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    TextInputDialog d = new TextInputDialog(selected);
                    d.setTitle("Edit time");
                    d.setHeaderText("Edit selected dose time (HH:mm)");
                    d.setContentText("Time:");
                    Optional<String> res = d.showAndWait();
                    res.ifPresent(newVal -> {
                        String val = newVal == null ? "" : newVal.trim();
                        try {
                            LocalTime parsed = LocalTime.parse(val);
                            int idx = timesList.indexOf(selected);
                            if (idx >= 0) {
                                timesList.set(idx, parsed.format(DateTimeFormatter.ofPattern("HH:mm")));
                                FXCollections.sort(timesList);
                            }
                        } catch (Exception ex) {
                            showAlert("Invalid time format. Use HH:mm (e.g., 08:00).");
                        }
                    });
                }
            }
        });

        // allow Delete key to remove selected time
        timesListView.setOnKeyPressed(evt -> {
            switch (evt.getCode()) {
                case DELETE:
                case BACK_SPACE:
                    String sel = timesListView.getSelectionModel().getSelectedItem();
                    if (sel != null) timesList.remove(sel);
                    break;
                default:
                    break;
            }
        });
    }

    private void setupColumns() {
        medNameCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getName()));
        medTypeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getType()));
        medAmountCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getAmount()));
        medStartDateCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getStartDateStr()));
        medEndDateCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getEndDateStr()));
        medTimesCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getTimesStr()));
    }

    private void setupActionButtons() {
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button endBtn = new Button("End");
            private final Button updateBtn = new Button("Update");

            {
                endBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                updateBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");

                endBtn.setOnAction(e -> {
                    MedicineRecord med = getTableView().getItems().get(getIndex());
                    handleMarkAsEnded(med);
                });

                updateBtn.setOnAction(e -> {
                    MedicineRecord med = getTableView().getItems().get(getIndex());
                    showEditSection(med);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else setGraphic(new HBox(8, endBtn, updateBtn));
            }
        });
    }

    private void detectLoggedInCaretaker() {
        new Thread(() -> {
            try {
                List<QueryDocumentSnapshot> docs = FirestoreService.getFirestore()
                        .collection("users")
                        .whereEqualTo("loggedIn", true)
                        .whereEqualTo("role", "caretaker")
                        .get().get().getDocuments();

                if (!docs.isEmpty()) {
                    // store caretaker id for audit fields
                    currentCaretakerId = docs.get(0).getId();

                    Object elderIdObj = docs.get(0).get("elderId");
                    if (elderIdObj != null) {
                        resolvedElderId = elderIdObj.toString();
                        loadMedicines();
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void loadMedicines() {
        if (resolvedElderId == null) return;
        new Thread(() -> {
            try {
                List<QueryDocumentSnapshot> meds = FirestoreService.getFirestore()
                        .collection("users")
                        .document(resolvedElderId)
                        .collection("medicines")
                        .get().get().getDocuments();

                medicineList.clear();
                ZoneId zid = ZoneId.systemDefault();
                Instant now = Instant.now();
                LocalDate today = LocalDate.now(zid);

                for (QueryDocumentSnapshot med : meds) {
                    String name = med.getString("name");
                    String type = med.getString("type");
                    String amount = med.getString("amount");

                    @SuppressWarnings("unchecked")
                    Map<String, Object> activePeriod = (Map<String, Object>) med.get("activePeriod");
                    LocalDate start = null;
                    LocalDate end = null;
                    try {
                        if (activePeriod != null) {
                            Object s = activePeriod.get("startDate");
                            Object e = activePeriod.get("endDate");
                            if (s instanceof String) start = LocalDate.parse((String) s);
                            if (e instanceof String) end = LocalDate.parse((String) e);
                        }
                    } catch (Exception ignored) {}

                    if (start == null || end == null) {
                        // fallback to any top-level string fields or skip
                        Object sTop = med.get("startDate");
                        Object eTop = med.get("endDate");
                        try {
                            if (start == null && sTop instanceof String) start = LocalDate.parse((String) sTop);
                            if (end == null && eTop instanceof String) end = LocalDate.parse((String) eTop);
                        } catch (Exception ignored) {}
                    }

                    if (start == null || end == null) {
                        // skip malformed record
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    List<String> times = (List<String>) med.get("times");
                    if (times == null) times = new ArrayList<>();

                    // skip if explicitly forceEnded
                    Boolean forceEnded = med.getBoolean("forceEnded");
                    if (Boolean.TRUE.equals(forceEnded)) continue;

                    // Build a cutoff instant (forceEndedAt -> lastDose.timestamp -> activePeriod.endDate + latest time -> top-level endDate)
                    Instant cutoffInstant = buildCutoffInstant(med, times);

                    if (cutoffInstant != null && now.isAfter(cutoffInstant)) {
                        // medicine ended in the past, skip
                        continue;
                    }

                    // keep ongoing only if today <= end (we already used cutoff above)
                    if (!today.isAfter(end)) {
                        medicineList.add(new MedicineRecord(name, type, amount, start, end, new ArrayList<>(times), med.getReference()));
                    }
                }

                Platform.runLater(() -> {
                    medicinesTable.refresh();
                    editSection.setVisible(false);
                    currentEditing = null;
                });

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }


    /**
     * Handle force ending logic:
     * - First check cutoff instant: prefer forceEndedAt (if exists), otherwise lastDose.timestamp.
     *   If cutoff exists and now is after cutoff => treat as already ended (notify + refresh).
     * - Otherwise decide:
     *   - If current time is before firstDose.timestamp -> confirm deletion (only confirmation, no reason) -> delete.
     *   - Else -> prompt for force-end reason and update doc with forceEnded flags.
     *
     * After any successful end (delete or update): refresh the full screen and hide edit panel.
     */
    private void handleMarkAsEnded(MedicineRecord med) {
        new Thread(() -> {
            try {
                DocumentReference ref = med.getDocRef();
                DocumentSnapshot snap = ref.get().get();

                Instant nowInst = Instant.now();

                // 1) Pre-check cutoff: forceEndedAt then lastDose.timestamp -> if now > cutoff -> already ended
                Instant cutoffInstant = extractInstantFromObject(snap.get("forceEndedAt"));
                if (cutoffInstant == null) cutoffInstant = extractInstantFromObject(snap.get("lastDose"));

                if (cutoffInstant != null && nowInst.isAfter(cutoffInstant)) {
                    Platform.runLater(() -> {
                        showInfo("Already ended", "This medicine appears to have been ended previously.");
                        loadMedicines();
                    });
                    return;
                }

                // 2) Get firstDose timestamp if present
                Instant firstDoseInstant = extractFirstDoseInstant(snap);

                if (firstDoseInstant != null && nowInst.isBefore(firstDoseInstant)) {
                    // Before first dose: only ask for confirmation and delete if confirmed (no reason required)
                    boolean confirmed = showConfirmationSync("Confirm deletion", "This medicine has not reached its first dose yet. Delete it permanently?");
                    if (!confirmed) return;

                    ref.delete().get();
                    Platform.runLater(() -> {
                        showInfo("Deleted", "Medicine deleted successfully.");
                        loadMedicines();
                    });
                    return;
                }

                // 3) Otherwise (first dose passed or missing): perform force end after asking reason
                Optional<String> reasonOpt = promptForRequiredReasonSync("Reason for force end", "Please enter a reason for force ending this medicine:");
                if (reasonOpt.isEmpty()) return; // aborted

                String forceReason = reasonOpt.get();

                // count completed (taken) doses before now
                int takenCount = 0;
                try {
                    List<QueryDocumentSnapshot> history = ref.collection("history")
                            .whereEqualTo("action", "taken")
                            .get().get().getDocuments();

                    for (QueryDocumentSnapshot h : history) {
                        Object createdAtObj = h.get("createdAt");
                        if (createdAtObj instanceof Timestamp) {
                            Timestamp t = (Timestamp) createdAtObj;
                            if (!t.toDate().toInstant().isAfter(nowInst)) takenCount++;
                        } else {
                            String dateStr = h.getString("date");
                            if (dateStr != null) {
                                try {
                                    LocalDate d = LocalDate.parse(dateStr);
                                    if (!d.isAfter(LocalDate.now())) takenCount++;
                                } catch (Exception ex) {
                                    // ignore malformed
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                Map<String, Object> updates = new HashMap<>();
                updates.put("activePeriod.endDate", fmt.format(LocalDate.now()));
                updates.put("forceEnded", true);
                updates.put("forceEndedAt", Timestamp.now());
                updates.put("completedDosesBeforeForceEnd", takenCount);
                updates.put("forceEndedReason", forceReason);
                if (currentCaretakerId != null) updates.put("forceEndedBy", currentCaretakerId);

                ref.update(updates).get();

                Platform.runLater(() -> {
                    showInfo("Force Ended", "Medicine marked as ended.");
                    loadMedicines();
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Failed to end medicine: " + e.getMessage()));
            }
        }).start();
    }

    private void showEditSection(MedicineRecord med) {
        currentEditing = med;
        editSection.setVisible(true);
        editStartDate.setValue(med.getStartDate());
        editEndDate.setValue(med.getEndDate());

        // Determine unit from type (Tablet/Capsule -> pill(s), Syrup -> ml, Injection -> units)
        String unitFromType = deriveUnitFromType(med.getType());

        // Split stored amount into numeric & unit parts and show numeric in editable field,
        // unit in non-editable label. But unit shown is chosen from type (unitFromType)
        String storedAmount = med.getAmount() == null ? "" : med.getAmount().trim();
        String numericPart = storedAmount;
        if (!storedAmount.isEmpty()) {
            // attempt to extract numeric substring (before first space)
            int idx = storedAmount.indexOf(' ');
            if (idx > 0) {
                String possibleNumeric = storedAmount.substring(0, idx).trim();
                // verify if possibleNumeric is parseable as number (int/decimal)
                try {
                    Double.parseDouble(possibleNumeric);
                    numericPart = possibleNumeric;
                } catch (Exception ex) {
                    // fallback: try to extract any digits from start
                    String digits = storedAmount.replaceAll("^([^0-9.-]*)([0-9.-]+).*", "$2");
                    if (!digits.equals(storedAmount)) {
                        try { Double.parseDouble(digits); numericPart = digits; } catch (Exception ignored) {}
                    } else {
                        numericPart = ""; // leave empty so user can type
                    }
                }
            } else {
                // no space - try parse whole string as number
                try {
                    Double.parseDouble(storedAmount);
                    numericPart = storedAmount;
                } catch (Exception ex) {
                    numericPart = "";
                }
            }
        } else {
            numericPart = "";
        }

        // if numericPart empty, default to "1"
        if (numericPart == null || numericPart.isEmpty()) numericPart = "1";

        editAmountField.setText(numericPart);
        editAmountUnitLabel.setText(unitFromType);

        timesList.setAll(med.getTimesList());
        originalTimes = new ArrayList<>(med.getTimesList());

        // Default: enable start date then check authoritative firstDose timestamp
        editStartDate.setDisable(false);

        new Thread(() -> {
            try {
                DocumentSnapshot snap = med.getDocRef().get().get();

                Instant firstDoseInstant = extractFirstDoseInstant(snap);
                Instant now = Instant.now();
                if (firstDoseInstant != null) {
                    boolean disable = !now.isBefore(firstDoseInstant); // disable if now >= firstDoseInstant
                    Platform.runLater(() -> {
                        editStartDate.setDisable(disable);
                        if (disable) editStartDate.setTooltip(new Tooltip("Start date locked: the first dose time has passed."));
                        else editStartDate.setTooltip(null);
                    });
                } else {
                    // fallback to checking times vs today (local heuristic)
                    LocalTime firstTime = med.getFirstDoseTime();
                    boolean disable = (LocalDate.now().isAfter(med.getStartDate())) ||
                            (LocalDate.now().isEqual(med.getStartDate()) && firstTime != null && LocalTime.now().isAfter(firstTime));
                    Platform.runLater(() -> editStartDate.setDisable(disable));
                }

                // filter time options for current editStartDate value (date logic remains)
                Platform.runLater(() -> filterEditTimeOptionsForDate(editStartDate.getValue()));
                Platform.runLater(this::updateProgressLabel);
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    filterEditTimeOptionsForDate(editStartDate.getValue());
                    updateProgressLabel();
                });
            }
        }).start();
    }

    /**
     * Determine unit label text from medicine type.
     */
    private String deriveUnitFromType(String type) {
        if (type == null) return "";
        String t = type.trim();
        if ("Tablet".equalsIgnoreCase(t) || "Capsule".equalsIgnoreCase(t)) {
            return "pill(s)";
        } else if ("Syrup".equalsIgnoreCase(t)) {
            return "ml";
        } else if ("Injection".equalsIgnoreCase(t) || "Injectable".equalsIgnoreCase(t)) {
            return "units";
        } else {
            return ""; // empty means no suffix shown
        }
    }

    private void addTime() {
        String input;
        if (timePickerCombo.isEditable()) {
            input = timePickerCombo.getEditor().getText();
        } else {
            input = timePickerCombo.getValue();
        }
        if (input == null) input = "";
        String time = input.trim();
        if (time.isEmpty()) {
            showAlert("Choose or type a time to add (HH:mm)");
            return;
        }

        // normalize/validate time using LocalTime.parse
        LocalTime chosen;
        try {
            chosen = LocalTime.parse(time);
        } catch (Exception ex) {
            showAlert("Invalid time format. Use HH:mm (e.g., 08:00).");
            return;
        }

        String formatted = chosen.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (!timesList.contains(formatted)) {
            timesList.add(formatted);
            FXCollections.sort(timesList);
        } else {
            showAlert("Time already present.");
        }
    }

    private void removeTime() {
        String selected = timesListView.getSelectionModel().getSelectedItem();
        if (selected != null) timesList.remove(selected);
    }

    /**
     * When updating:
     * - Re-check firstDose.timestamp from Firestore to avoid race condition.
     * - If firstDose has passed and user attempted to change startDate -> prevent it.
     * - Date selection logic remains; time-selection past-checks have been removed.
     * - Otherwise perform update and add forceUpdated flag and metadata (including reason if provided).
     * - Prompt caretaker for required reason when changes are flagged as forceUpdated.
     *
     * After success: refresh whole screen and hide edit panel.
     */
    private void handleUpdateMedicine() {
        if (currentEditing == null) return;

        LocalDate newStart = editStartDate.getValue();
        LocalDate newEnd = editEndDate.getValue();
        String newAmountNumeric = editAmountField.getText().trim();
        String unit = editAmountUnitLabel.getText().trim();

        // make a final normalized times list for safe capture by background thread
        final List<String> newTimesFinal = dedupeSortTimes(new ArrayList<>(timesList));

        if (newTimesFinal.isEmpty()) {
            showAlert("Please select at least one time. Reverting to previous times.");
            timesList.setAll(originalTimes);
            return;
        }

        // Compose final amount: numeric + " " + unit (unit label is NOT editable by user)
        String composedAmount;
        if (newAmountNumeric.isEmpty()) {
            // if numeric is empty, preserve previous full amount
            composedAmount = currentEditing.getAmount();
        } else {
            if (unit == null || unit.isEmpty()) composedAmount = newAmountNumeric;
            else composedAmount = newAmountNumeric + " " + unit;
        }

        // Re-check Firestore to avoid race conditions
        new Thread(() -> {
            try {
                DocumentSnapshot snap = currentEditing.getDocRef().get().get();
                Instant firstDoseInstant = extractFirstDoseInstant(snap);
                Instant now = Instant.now();

                if (firstDoseInstant != null && !now.isBefore(firstDoseInstant) && !Objects.equals(newStart, currentEditing.getStartDate())) {
                    // cannot change start date
                    Platform.runLater(() -> showAlert("Cannot modify start date: the first dose timestamp has already passed."));
                    return;
                }

                // additional check (defensive): if start date already passed locally and newStart changed -> prevent
                if (LocalDate.now().isAfter(currentEditing.getStartDate()) && !Objects.equals(newStart, currentEditing.getStartDate())) {
                    Platform.runLater(() -> showAlert("Cannot modify start date as it has already passed."));
                    return;
                }

                boolean changed = !Objects.equals(newStart, currentEditing.getStartDate()) ||
                        !Objects.equals(newEnd, currentEditing.getEndDate()) ||
                        !Objects.equals(composedAmount, currentEditing.getAmount()) ||
                        !Objects.equals(newTimesFinal, currentEditing.getTimesList());

                Map<String, Object> updates = new HashMap<>();
                updates.put("activePeriod.startDate", fmt.format(newStart));
                updates.put("activePeriod.endDate", fmt.format(newEnd));
                updates.put("amount", composedAmount);
                updates.put("times", newTimesFinal);

                if (changed) {
                    // prompt for required reason for manual/force update
                    Optional<String> reasonOpt = promptForRequiredReasonSync("Reason for update", "Please enter a reason for this manual update:");
                    if (reasonOpt.isEmpty()) {
                        // cancelled by user
                        return;
                    }
                    String reason = reasonOpt.get();

                    updates.put("forceUpdated", true);
                    updates.put("forceUpdatedAt", Timestamp.now());
                    updates.put("forceUpdatedReason", reason);
                    if (currentCaretakerId != null) updates.put("forceUpdatedBy", currentCaretakerId);
                }

                currentEditing.getDocRef().update(updates).get();

                Platform.runLater(() -> {
                    currentEditing.setStartDate(newStart);
                    currentEditing.setEndDate(newEnd);
                    currentEditing.setAmount(composedAmount);
                    currentEditing.setTimesList(newTimesFinal);
                    originalTimes = new ArrayList<>(newTimesFinal);
                    medicinesTable.refresh();
                    updateProgressLabel();

                    // Hide edit section and refresh the whole list
                    editSection.setVisible(false);
                    currentEditing = null;
                    loadMedicines();
                    showInfo("Updated", "Medicine updated successfully.");
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Failed to update medicine: " + e.getMessage()));
            }
        }).start();
    }

    private void updateProgressLabel() {
        if (currentEditing == null) return;
        LocalDate today = LocalDate.now();
        long totalDays = currentEditing.getStartDate().until(currentEditing.getEndDate()).getDays() + 1;
        long passedDays = currentEditing.getStartDate().until(today).getDays() + 1;
        if (passedDays < 0) passedDays = 0;
        if (passedDays > totalDays) passedDays = totalDays;
        progressLabel.setText("Progress: " + passedDays + " / " + totalDays + " days");
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Notice");
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showInfo(String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    /**
     * Utility to extract firstDose.timestamp Instant from a medicine DocumentSnapshot.
     * Accepts Map shaped firstDose {date, time, timestamp} or direct Timestamp.
     */
    private Instant extractFirstDoseInstant(DocumentSnapshot medSnap) {
        try {
            Object firstDoseObj = medSnap.get("firstDose");
            if (firstDoseObj == null) return null;
            if (firstDoseObj instanceof Map) {
                Object ts = ((Map<?, ?>) firstDoseObj).get("timestamp");
                if (ts instanceof com.google.cloud.Timestamp) {
                    return ((com.google.cloud.Timestamp) ts).toDate().toInstant();
                }
                if (ts instanceof java.util.Date) {
                    return ((java.util.Date) ts).toInstant();
                }
                // fallback to composing from date & time fields
                Object dateObj = ((Map<?, ?>) firstDoseObj).get("date");
                Object timeObj = ((Map<?, ?>) firstDoseObj).get("time");
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
            }
            if (firstDoseObj instanceof com.google.cloud.Timestamp) {
                return ((com.google.cloud.Timestamp) firstDoseObj).toDate().toInstant();
            }
            if (firstDoseObj instanceof java.util.Date) {
                return ((java.util.Date) firstDoseObj).toInstant();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    /**
     * Try to extract an Instant from:
     *  - null => null
     *  - Firestore Timestamp
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
            Instant inst = extractInstantFromObject(d.get("forceEndedAt"));
            if (inst != null) return inst;

            // fallback: lastDose.timestamp
            inst = extractInstantFromObject(d.get("lastDose"));
            if (inst != null) return inst;

            // fallback: activePeriod.endDate + latest time from times list
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

            // fallback: top-level "endDate" string
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
     * Prompt user for a required reason synchronously. Runs the TextInputDialog on the FX thread and
     * waits for the result. Returns Optional.empty() if the user cancelled.
     */
    private Optional<String> promptForRequiredReasonSync(String title, String header) {
        AtomicReference<Optional<String>> resultRef = new AtomicReference<>(Optional.empty());
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                TextInputDialog dialog = new TextInputDialog();
                dialog.setTitle(title);
                dialog.setHeaderText(header);
                dialog.setContentText("Reason:");
                Optional<String> res = dialog.showAndWait();
                if (res.isPresent() && res.get().trim().isEmpty()) {
                    Alert err = new Alert(Alert.AlertType.WARNING);
                    err.setTitle("Validation");
                    err.setContentText("Reason cannot be empty. Please provide a reason or cancel.");
                    err.showAndWait();
                    Optional<String> res2 = dialog.showAndWait();
                    if (res2.isPresent() && !res2.get().trim().isEmpty()) resultRef.set(Optional.of(res2.get().trim()));
                    else resultRef.set(Optional.empty());
                } else if (res.isPresent()) {
                    resultRef.set(Optional.of(res.get().trim()));
                } else {
                    resultRef.set(Optional.empty());
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return Optional.empty();
        }
        return resultRef.get();
    }

    /**
     * Show a confirmation dialog synchronously (OK/Cancel). Returns true if OK pressed.
     */
    private boolean showConfirmationSync(String title, String content) {
        AtomicBoolean out = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle(title);
                confirm.setContentText(content);
                Optional<ButtonType> b = confirm.showAndWait();
                out.set(b.isPresent() && b.get() == ButtonType.OK);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return out.get();
    }

    /**
     * Filter the edit timePicker options for a given start date.
     * If startDate == today, only times >= now are shown.
     */
    private void filterEditTimeOptionsForDate(LocalDate selDate) {
        List<String> hourly = new ArrayList<>();
        for (int h = 0; h < 24; h++) hourly.add(String.format("%02d:00", h));

        if (selDate == null) {
            timePickerCombo.getItems().setAll(hourly);
            return;
        }
        ZoneId zid = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zid);
        if (selDate.isEqual(today)) {
            LocalTime nowTime = LocalTime.now(zid).withSecond(0).withNano(0);
            List<String> allowed = new ArrayList<>();
            for (String t : hourly) {
                try {
                    LocalTime lt = LocalTime.parse(t);
                    if (!lt.isBefore(nowTime)) allowed.add(t);
                } catch (Exception ignored) {}
            }
            if (allowed.isEmpty()) timePickerCombo.getItems().setAll(hourly);
            else timePickerCombo.getItems().setAll(allowed);
            if (!timePickerCombo.getItems().isEmpty()) timePickerCombo.setValue(timePickerCombo.getItems().get(0));
        } else {
            timePickerCombo.getItems().setAll(hourly);
            timePickerCombo.setValue("08:00");
        }
    }

    /**
     * Back to dashboard
     */
    private void handleBack() {
        try {
            javafx.scene.Parent root = javafx.fxml.FXMLLoader.load(getClass().getResource("/fxml/caretaker_dashboard.fxml"));
            javafx.stage.Stage stage = (javafx.stage.Stage) backButton.getScene().getWindow();
            stage.setScene(new javafx.scene.Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            showAlert("Failed to load dashboard.");
        }
    }

    public static class MedicineRecord {
        private String name, type, amount;
        private List<String> timesList;
        private LocalDate startDate, endDate;
        private final DocumentReference docRef;

        public MedicineRecord(String name, String type, String amount, LocalDate startDate,
                              LocalDate endDate, List<String> timesList, DocumentReference docRef) {
            this.name = name;
            this.type = type;
            this.amount = amount;
            this.startDate = startDate;
            this.endDate = endDate;
            this.timesList = timesList;
            this.docRef = docRef;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getAmount() { return amount; }
        public LocalDate getStartDate() { return startDate; }
        public LocalDate getEndDate() { return endDate; }
        public List<String> getTimesList() { return timesList; }
        public DocumentReference getDocRef() { return docRef; }

        public String getTimesStr() { return String.join(", ", timesList); }
        public String getStartDateStr() { return startDate.toString(); }
        public String getEndDateStr() { return endDate.toString(); }

        public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
        public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
        public void setAmount(String amount) { this.amount = amount; }
        public void setTimesList(List<String> timesList) { this.timesList = timesList; }

        public LocalTime getFirstDoseTime() {
            if (timesList == null || timesList.isEmpty()) return null;
            return LocalTime.parse(timesList.get(0), DateTimeFormatter.ofPattern("HH:mm"));
        }
    }

    // -----------------------
    // Helper: dedupe and sort times (HH:mm)
    // -----------------------
    private List<String> dedupeSortTimes(Collection<String> times) {
        TreeSet<LocalTime> set = new TreeSet<>();
        for (String t : times) try { set.add(LocalTime.parse(t)); } catch (Exception ignored) {}
        List<String> out = new ArrayList<>();
        for (LocalTime lt : set) out.add(lt.format(DateTimeFormatter.ofPattern("HH:mm")));
        return out;
    }
}
