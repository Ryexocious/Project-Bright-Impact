package controller;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import utils.FirestoreService;
import utils.SessionManager;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EditCurrentDosesController — updated to use SessionManager and defensive Firestore handling.
 */
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
    @FXML private Label editAmountUnitLabel;

    @FXML private ComboBox<String> timePickerCombo;
    @FXML private ListView<String> timesListView;
    @FXML private Button addTimeButton, removeTimeButton, clearTimesButton, updateMedicineBtn;
    @FXML private Label progressLabel;
    @FXML private Button backButton;
    @FXML private ScrollPane editScroll;

    private final ObservableList<MedicineRecord> medicineList = FXCollections.observableArrayList();
    private final ObservableList<String> timesList = FXCollections.observableArrayList();
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Resolved elder id (the caretaker is linked to an elder)
    private String resolvedElderId = null;
    // current caretaker id (from SessionManager or fallback)
    private String currentCaretakerId = null;

    private MedicineRecord currentEditing = null;
    private List<String> originalTimes = new ArrayList<>();

    private final boolean DEBUG_DUMP_TODAY_ITEMS_ON_UPDATE = false;

    // dedicated executor
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        setupColumns();
        setupActionButtons();
        detectLoggedInCaretaker();
        medicinesTable.setItems(medicineList);

        for (int h = 0; h < 24; h++) timePickerCombo.getItems().add(String.format("%02d:00", h));
        timePickerCombo.setEditable(true);
        timePickerCombo.setPromptText("HH:mm");
        timesListView.setItems(timesList);

        addTimeButton.setOnAction(e -> addTime());
        removeTimeButton.setOnAction(e -> removeTime());
        clearTimesButton.setOnAction(e -> timesList.clear());

        updateMedicineBtn.setOnAction(e -> handleUpdateMedicine());
        editSection.setVisible(false);
        editSection.setManaged(false);
        editScroll.setVisible(false);
        editScroll.setManaged(false);

        backButton.setOnAction(e -> handleBack());

        editStartDate.valueProperty().addListener((obs, oldV, newV) -> filterEditTimeOptionsForDate(newV));

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

    /**
     * Prefer SessionManager for current user; fallback to legacy query if session missing.
     */
    private void detectLoggedInCaretaker() {
        bgExecutor.submit(() -> {
            try {
                // Primary: session manager
                if (SessionManager.isLoggedIn() && "Caretaker".equalsIgnoreCase(SessionManager.getCurrentUserRole())) {
                    currentCaretakerId = SessionManager.getCurrentUserId();
                    // fetch caretaker doc to find linked elder
                    try {
                        DocumentSnapshot caret = FirestoreService.getFirestore().collection("users").document(currentCaretakerId).get().get();
                        if (caret.exists() && caret.contains("elderId")) {
                            resolvedElderId = caret.getString("elderId");
                            loadMedicines();
                        } else {
                            // no linked elder
                            Platform.runLater(() -> {
                                medicineList.clear();
                                editSection.setVisible(false);
                            });
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Platform.runLater(() -> {
                            medicineList.clear();
                            editSection.setVisible(false);
                        });
                    }
                    return;
                }

                // Fallback: legacy query (keeps backward compatibility). Still picks first matching doc.
                List<QueryDocumentSnapshot> docs = FirestoreService.getFirestore()
                        .collection("users")
                        .whereEqualTo("loggedIn", true)
                        .whereEqualTo("role", "caretaker")
                        .get().get().getDocuments();

                if (!docs.isEmpty()) {
                    currentCaretakerId = docs.get(0).getId();
                    Object elderIdObj = docs.get(0).get("elderId");
                    if (elderIdObj != null) {
                        resolvedElderId = elderIdObj.toString();
                        loadMedicines();
                    } else {
                        Platform.runLater(() -> {
                            medicineList.clear();
                            editSection.setVisible(false);
                        });
                    }
                } else {
                    // no caretaker found
                    Platform.runLater(() -> {
                        medicineList.clear();
                        editSection.setVisible(false);
                    });
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    medicineList.clear();
                    editSection.setVisible(false);
                });
            }
        });
    }

    private void loadMedicines() {
        if (resolvedElderId == null) return;
        bgExecutor.submit(() -> {
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
                    try {
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
                            Object sTop = med.get("startDate");
                            Object eTop = med.get("endDate");
                            try {
                                if (start == null && sTop instanceof String) start = LocalDate.parse((String) sTop);
                                if (end == null && eTop instanceof String) end = LocalDate.parse((String) eTop);
                            } catch (Exception ignored) {}
                        }

                        if (start == null || end == null) {
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        List<String> times = (List<String>) med.get("times");
                        if (times == null) times = new ArrayList<>();

                        Boolean forceEnded = med.getBoolean("forceEnded");
                        if (Boolean.TRUE.equals(forceEnded)) continue;

                        Instant cutoffInstant = buildCutoffInstant(med, times);

                        if (cutoffInstant != null && now.isAfter(cutoffInstant)) {
                            continue;
                        }

                        if (!today.isAfter(end)) {
                            medicineList.add(new MedicineRecord(name, type, amount, start, end, new ArrayList<>(times), med.getReference()));
                        }
                    } catch (Exception inner) {
                        inner.printStackTrace();
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
        });
    }

    private void handleMarkAsEnded(MedicineRecord med) {
        bgExecutor.submit(() -> {
            try {
                DocumentReference ref = med.getDocRef();
                DocumentSnapshot snap;
                try {
                    snap = ref.get().get();
                } catch (Exception e) {
                    // treat missing doc as already removed
                    if (isNotFound(e)) {
                        Platform.runLater(() -> {
                            showInfo("Already removed", "This medicine was already removed.");
                            loadMedicines();
                        });
                        return;
                    }
                    throw e;
                }

                Instant nowInst = Instant.now();

                Instant cutoffInstant = extractInstantFromObject(snap.get("forceEndedAt"));
                if (cutoffInstant == null) cutoffInstant = extractInstantFromObject(snap.get("lastDose"));

                if (cutoffInstant != null && nowInst.isAfter(cutoffInstant)) {
                    Platform.runLater(() -> {
                        showInfo("Already ended", "This medicine appears to have been ended previously.");
                        loadMedicines();
                    });
                    return;
                }

                Instant firstDoseInstant = extractFirstDoseInstant(snap);

                if (firstDoseInstant != null && nowInst.isBefore(firstDoseInstant)) {
                    boolean confirmed = showConfirmationSync("Confirm deletion", "This medicine has not reached its first dose yet. Delete it permanently?");
                    if (!confirmed) return;

                    try {
                        ref.delete().get();
                    } catch (Exception e) {
                        if (isNotFound(e)) {
                            // already gone
                        } else throw e;
                    }

                    if (resolvedElderId != null) {
                        purgeMedicineFromAllSchedules(resolvedElderId, ref.getId());
                        writeScheduleSyncPing(resolvedElderId);
                    }

                    Platform.runLater(() -> {
                        showInfo("Deleted", "Medicine deleted successfully.");
                        loadMedicines();
                    });
                    return;
                }

                Optional<String> reasonOpt = promptForRequiredReasonSync("Reason for force end", "Please enter a reason for force ending this medicine:");
                if (reasonOpt.isEmpty()) return;

                String forceReason = reasonOpt.get();

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
                                } catch (Exception ex) {}
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
                updates.put("lastModifiedAt", Timestamp.now());

                try {
                    ref.update(updates).get();
                } catch (Exception e) {
                    if (isNotFound(e)) {
                        Platform.runLater(() -> {
                            showInfo("Already removed", "Medicine document no longer exists.");
                            loadMedicines();
                        });
                        return;
                    } else throw e;
                }

                if (resolvedElderId != null) {
                    purgeMedicineFromAllSchedules(resolvedElderId, ref.getId());
                    writeScheduleSyncPing(resolvedElderId);
                }

                Platform.runLater(() -> {
                    showInfo("Force Ended", "Medicine marked as ended.");
                    loadMedicines();
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Failed to end medicine: " + e.getMessage()));
            }
        });
    }

    private void showEditSection(MedicineRecord med) {
        currentEditing = med;
        editSection.setVisible(true);
        editSection.setManaged(true);
        editScroll.setVisible(true);
        editScroll.setManaged(true);
        editStartDate.setValue(med.getStartDate());
        editEndDate.setValue(med.getEndDate());

        String unitFromType = deriveUnitFromType(med.getType());

        String storedAmount = med.getAmount() == null ? "" : med.getAmount().trim();
        String numericPart = storedAmount;
        if (!storedAmount.isEmpty()) {
            int idx = storedAmount.indexOf(' ');
            if (idx > 0) {
                String possibleNumeric = storedAmount.substring(0, idx).trim();
                try {
                    Double.parseDouble(possibleNumeric);
                    numericPart = possibleNumeric;
                } catch (Exception ex) {
                    String digits = storedAmount.replaceAll("^([^0-9.-]*)([0-9.-]+).*", "$2");
                    if (!digits.equals(storedAmount)) {
                        try { Double.parseDouble(digits); numericPart = digits; } catch (Exception ignored) {}
                    } else numericPart = "";
                }
            } else {
                try {
                    Double.parseDouble(storedAmount);
                    numericPart = storedAmount;
                } catch (Exception ex) { numericPart = ""; }
            }
        } else numericPart = "";

        if (numericPart == null || numericPart.isEmpty()) numericPart = "1";

        editAmountField.setText(numericPart);
        editAmountUnitLabel.setText(unitFromType);

        timesList.setAll(med.getTimesList());
        originalTimes = new ArrayList<>(med.getTimesList());

        editStartDate.setDisable(false);

        bgExecutor.submit(() -> {
            try {
                DocumentSnapshot snap;
                try {
                    snap = med.getDocRef().get().get();
                } catch (Exception e) {
                    if (isNotFound(e)) {
                        Platform.runLater(() -> {
                            showAlert("Medicine no longer exists.");
                            loadMedicines();
                        });
                        return;
                    } else throw e;
                }

                Instant firstDoseInstant = extractFirstDoseInstant(snap);
                Instant now = Instant.now();
                if (firstDoseInstant != null) {
                    boolean disable = !now.isBefore(firstDoseInstant);
                    Platform.runLater(() -> {
                        editStartDate.setDisable(disable);
                        if (disable) editStartDate.setTooltip(new Tooltip("Start date locked: the first dose time has passed."));
                        else editStartDate.setTooltip(null);
                    });
                } else {
                    LocalTime firstTime = med.getFirstDoseTime();
                    boolean disable = (LocalDate.now().isAfter(med.getStartDate())) ||
                            (LocalDate.now().isEqual(med.getStartDate()) && firstTime != null && LocalTime.now().isAfter(firstTime));
                    Platform.runLater(() -> editStartDate.setDisable(disable));
                }

                Platform.runLater(() -> {
                    filterEditTimeOptionsForDate(editStartDate.getValue());
                    updateProgressLabel();
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    filterEditTimeOptionsForDate(editStartDate.getValue());
                    updateProgressLabel();
                });
            }
        });
    }

    private String deriveUnitFromType(String type) {
        if (type == null) return "";
        String t = type.trim();
        if ("Tablet".equalsIgnoreCase(t) || "Capsule".equalsIgnoreCase(t)) return "pill(s)";
        else if ("Syrup".equalsIgnoreCase(t)) return "ml";
        else if ("Injection".equalsIgnoreCase(t) || "Injectable".equalsIgnoreCase(t)) return "units";
        else return "";
    }

    private void addTime() {
        String input;
        if (timePickerCombo.isEditable()) input = timePickerCombo.getEditor().getText();
        else input = timePickerCombo.getValue();
        if (input == null) input = "";
        String time = input.trim();
        if (time.isEmpty()) {
            showAlert("Choose or type a time to add (HH:mm)");
            return;
        }

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
        } else showAlert("Time already present.");
    }

    private void removeTime() {
        String selected = timesListView.getSelectionModel().getSelectedItem();
        if (selected != null) timesList.remove(selected);
    }

    private void handleUpdateMedicine() {
        if (currentEditing == null) return;

        LocalDate newStart = editStartDate.getValue();
        LocalDate newEnd = editEndDate.getValue();
        String newAmountNumeric = editAmountField.getText().trim();
        String unit = editAmountUnitLabel.getText().trim();

        final List<String> newTimesFinal = dedupeSortTimes(new ArrayList<>(timesList));

        if (newTimesFinal.isEmpty()) {
            showAlert("Please select at least one time. Reverting to previous times.");
            timesList.setAll(originalTimes);
            return;
        }

        String composedAmount;
        if (newAmountNumeric.isEmpty()) composedAmount = currentEditing.getAmount();
        else {
            if (unit == null || unit.isEmpty()) composedAmount = newAmountNumeric;
            else composedAmount = newAmountNumeric + " " + unit;
        }

        bgExecutor.submit(() -> {
            try {
                DocumentSnapshot snap;
                try {
                    snap = currentEditing.getDocRef().get().get();
                } catch (Exception e) {
                    if (isNotFound(e)) {
                        Platform.runLater(() -> {
                            showAlert("Medicine no longer exists.");
                            loadMedicines();
                        });
                        return;
                    } else throw e;
                }

                Instant firstDoseInstant = extractFirstDoseInstant(snap);
                Instant now = Instant.now();

                if (firstDoseInstant != null && !now.isBefore(firstDoseInstant) && !Objects.equals(newStart, currentEditing.getStartDate())) {
                    Platform.runLater(() -> showAlert("Cannot modify start date: the first dose timestamp has already passed."));
                    return;
                }

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
                    Optional<String> reasonOpt = promptForRequiredReasonSync("Reason for update", "Please enter a reason for this manual update:");
                    if (reasonOpt.isEmpty()) return;
                    String reason = reasonOpt.get();
                    updates.put("forceUpdated", true);
                    updates.put("forceUpdatedAt", Timestamp.now());
                    updates.put("forceUpdatedReason", reason);
                    // use session caretaker id if available
                    if (currentCaretakerId == null && SessionManager.isLoggedIn()) currentCaretakerId = SessionManager.getCurrentUserId();
                    if (currentCaretakerId != null) updates.put("forceUpdatedBy", currentCaretakerId);
                }

                updates.put("lastModifiedAt", Timestamp.now());

                try {
                    currentEditing.getDocRef().update(updates).get();
                } catch (Exception e) {
                    if (isNotFound(e)) {
                        Platform.runLater(() -> {
                            showAlert("Medicine no longer exists.");
                            loadMedicines();
                        });
                        return;
                    } else throw e;
                }

                if (changed && resolvedElderId != null) {
                    try {
                        String medId = currentEditing.getDocRef().getId();
                        purgeMedicineFromTodaySchedule(resolvedElderId, medId);

                        boolean purgeVisible = waitUntilTodayItemsPurged(resolvedElderId, medId, 5000, 200);
                        if (!purgeVisible) {
                            System.err.println("Warning: purgeDidNotBecomeVisibleForMed " + medId + " within timeout; continuing to create (may cause duplicate).");
                        }

                        if (DEBUG_DUMP_TODAY_ITEMS_ON_UPDATE) dumpTodayItemsForMedToLog(resolvedElderId, medId);

                        createTodayScheduleItemsForMedicine(
                                resolvedElderId,
                                currentEditing.getDocRef(),
                                medId,
                                currentEditing.getName(),
                                currentEditing.getType(),
                                composedAmount,
                                newTimesFinal,
                                newStart,
                                newEnd
                        );

                        writeScheduleSyncPing(resolvedElderId);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                } else {
                    if (resolvedElderId != null) writeScheduleSyncPing(resolvedElderId);
                }

                Platform.runLater(() -> {
                    currentEditing.setStartDate(newStart);
                    currentEditing.setEndDate(newEnd);
                    currentEditing.setAmount(composedAmount);
                    currentEditing.setTimesList(newTimesFinal);
                    originalTimes = new ArrayList<>(newTimesFinal);
                    medicinesTable.refresh();
                    updateProgressLabel();

                    editSection.setVisible(false);
                    currentEditing = null;
                    loadMedicines();
                    showInfo("Updated", "Medicine updated successfully.");
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Failed to update medicine: " + e.getMessage()));
            }
        });
    }

    private boolean isNotFound(Exception e) {
        if (e == null) return false;
        if (e instanceof NotFoundException) return true;
        Throwable c = e.getCause();
        if (c instanceof NotFoundException) return true;
        // Some Firestore exceptions may wrap different types; check message as last resort
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("not found");
    }

    private boolean waitUntilTodayItemsPurged(String elderId, String medId, long timeoutMs, long pollMs) {
        long start = System.currentTimeMillis();
        Firestore db = FirestoreService.getFirestore();
        String todayId = LocalDate.now().format(fmt);
        DocumentReference dayRef = db.collection("users").document(elderId).collection("schedules").document(todayId);
        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                DocumentSnapshot daySnap = dayRef.get().get();
                if (!daySnap.exists()) return true;
                List<QueryDocumentSnapshot> items = dayRef.collection("items")
                        .whereEqualTo("medicineId", medId)
                        .get().get().getDocuments();
                if (items.isEmpty()) return true;
                Thread.sleep(pollMs);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return false;
    }

    private void dumpTodayItemsForMedToLog(String elderId, String medId) {
        bgExecutor.submit(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                String todayId = LocalDate.now().format(fmt);
                DocumentReference dayRef = db.collection("users").document(elderId).collection("schedules").document(todayId);
                DocumentSnapshot ds = dayRef.get().get();
                if (!ds.exists()) {
                    System.err.println("dumpTodayItemsForMedToLog: day doc missing for " + todayId);
                    return;
                }
                List<QueryDocumentSnapshot> items = dayRef.collection("items").whereEqualTo("medicineId", medId).get().get().getDocuments();
                System.err.println("===== Today's items for med " + medId + " (count=" + items.size() + ") =====");
                for (QueryDocumentSnapshot it : items) {
                    Object baseO = it.get("baseTimestamp");
                    String baseStr = "<null>";
                    if (baseO instanceof com.google.cloud.Timestamp) {
                        com.google.cloud.Timestamp ts = (com.google.cloud.Timestamp) baseO;
                        Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
                        baseStr = LocalDateTime.ofInstant(inst, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    }
                    System.err.println("docId=" + it.getId() + " time=" + it.getString("time") + " base=" + baseStr + " status=" + it.getString("status") + " createdAt=" + it.get("createdAt"));
                }
                System.err.println("====================================================");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
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
            if (obj instanceof com.google.cloud.Timestamp) {
                return ((com.google.cloud.Timestamp) obj).toDate().toInstant();
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

    private Instant buildCutoffInstant(DocumentSnapshot d, List<String> times) {
        try {
            Instant inst = extractInstantFromObject(d.get("forceEndedAt"));
            if (inst != null) return inst;

            inst = extractInstantFromObject(d.get("lastDose"));
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
                } else resultRef.set(Optional.empty());
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(); } catch (InterruptedException e) { e.printStackTrace(); return Optional.empty(); }
        return resultRef.get();
    }

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
        try { latch.await(); } catch (InterruptedException e) { e.printStackTrace(); return false; }
        return out.get();
    }

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

    private List<String> dedupeSortTimes(Collection<String> times) {
        TreeSet<LocalTime> set = new TreeSet<>();
        for (String t : times) try { set.add(LocalTime.parse(t)); } catch (Exception ignored) {}
        List<String> out = new ArrayList<>();
        for (LocalTime lt : set) out.add(lt.format(DateTimeFormatter.ofPattern("HH:mm")));
        return out;
    }

    private void purgeMedicineFromAllSchedules(String elderId, String medId) {
        try {
            Firestore db = FirestoreService.getFirestore();
            CollectionReference schedulesCol = db.collection("users").document(elderId).collection("schedules");
            List<QueryDocumentSnapshot> days = schedulesCol.get().get().getDocuments();
            for (QueryDocumentSnapshot dayDoc : days) {
                try {
                    CollectionReference itemsCol = dayDoc.getReference().collection("items");
                    List<QueryDocumentSnapshot> items = itemsCol.whereEqualTo("medicineId", medId).get().get().getDocuments();

                    if (!items.isEmpty()) {
                        WriteBatch batch = db.batch();
                        for (QueryDocumentSnapshot it : items) {
                            batch.delete(it.getReference());
                        }
                        try {
                            batch.commit().get();
                        } catch (Exception e) {
                            if (!isNotFound(e)) throw e;
                        }
                    }
                } catch (Exception inner) {
                    inner.printStackTrace();
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void purgeMedicineFromTodaySchedule(String elderId, String medId) {
        try {
            Firestore db = FirestoreService.getFirestore();
            String todayId = LocalDate.now().format(fmt);
            DocumentReference dayRef = db.collection("users").document(elderId).collection("schedules").document(todayId);
            DocumentSnapshot daySnap = dayRef.get().get();
            if (!daySnap.exists()) return;

            CollectionReference itemsCol = dayRef.collection("items");
            List<QueryDocumentSnapshot> items = itemsCol.whereEqualTo("medicineId", medId).get().get().getDocuments();
            if (items.isEmpty()) return;

            WriteBatch batch = db.batch();
            for (QueryDocumentSnapshot it : items) {
                batch.delete(it.getReference());
            }
            try {
                batch.commit().get();
            } catch (Exception e) {
                if (!isNotFound(e)) throw e;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void writeScheduleSyncPing(String elderId) {
        try {
            Firestore db = FirestoreService.getFirestore();
            DocumentReference ping = db.collection("users")
                    .document(elderId)
                    .collection("sync")
                    .document("schedulePing");
            Map<String, Object> data = new HashMap<>();
            data.put("ts", Timestamp.now());
            ping.set(data, SetOptions.merge());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void createTodayScheduleItemsForMedicine(String elderId,
                                                    DocumentReference medRef,
                                                    String medId,
                                                    String name,
                                                    String type,
                                                    String amount,
                                                    List<String> times,
                                                    LocalDate startDate,
                                                    LocalDate endDate) {
        if (medId == null || elderId == null) return;
        try {
            LocalDate today = LocalDate.now();
            if (startDate != null && today.isBefore(startDate)) return;
            if (endDate != null && today.isAfter(endDate)) return;

            Firestore db = FirestoreService.getFirestore();
            String dateId = today.format(fmt);
            DocumentReference dayRef = db.collection("users").document(elderId)
                    .collection("medicines").document(medId) /* not used here */; // keep original semantics below
            // create/merge day doc in schedules collection
            dayRef = db.collection("users").document(elderId).collection("schedules").document(dateId);

            Map<String, Object> meta = new HashMap<>();
            meta.put("createdAt", Timestamp.now());
            meta.put("date", dateId);
            dayRef.set(meta, SetOptions.merge()).get();

            CollectionReference itemsCol = dayRef.collection("items");

            List<QueryDocumentSnapshot> existing = itemsCol.whereEqualTo("medicineId", medId).get().get().getDocuments();
            if (!existing.isEmpty()) {
                WriteBatch delBatch = db.batch();
                for (QueryDocumentSnapshot it : existing) delBatch.delete(it.getReference());
                try {
                    delBatch.commit().get();
                } catch (Exception e) {
                    if (!isNotFound(e)) throw e;
                }
            }

            ZoneId zid = ZoneId.systemDefault();
            List<String> dedupedTimes = dedupeSortTimes(times);
            for (String t : dedupedTimes) {
                try {
                    LocalTime lt = LocalTime.parse(t);
                    LocalDateTime base = LocalDateTime.of(today, lt);
                    Instant baseInstant = base.atZone(zid).toInstant();
                    com.google.cloud.Timestamp baseTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(baseInstant.getEpochSecond(), baseInstant.getNano());

                    LocalDateTime snoozeEnd = base.plusMinutes(30);
                    String initialStatus;
                    LocalDateTime nowLd = LocalDateTime.now();
                    if (nowLd.isBefore(base)) initialStatus = "hasnt_arrived";
                    else if (!nowLd.isBefore(base) && nowLd.isBefore(snoozeEnd)) initialStatus = "in_snooze_duration";
                    else initialStatus = "missed";

                    List<QueryDocumentSnapshot> check = itemsCol
                            .whereEqualTo("medicineId", medId)
                            .whereEqualTo("time", t)
                            .get().get().getDocuments();
                    if (!check.isEmpty()) {
                        for (QueryDocumentSnapshot found : check) {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put("medicineRef", medRef);
                            upd.put("name", name);
                            upd.put("type", type);
                            upd.put("amount", amount);
                            upd.put("baseTimestamp", baseTs);
                            upd.put("status", initialStatus);
                            upd.put("time", t);
                            upd.put("updatedAt", Timestamp.now());
                            try {
                                found.getReference().set(upd, SetOptions.merge()).get();
                            } catch (Exception e) {
                                if (isNotFound(e)) {
                                    // vanished concurrently, continue to recreate below
                                } else throw e;
                            }
                        }
                        continue;
                    }

                    Map<String, Object> item = new HashMap<>();
                    item.put("medicineId", medId);
                    item.put("medicineRef", medRef);
                    item.put("name", name);
                    item.put("type", type);
                    item.put("amount", amount);
                    item.put("time", t);
                    item.put("baseTimestamp", baseTs);
                    item.put("status", initialStatus);
                    item.put("createdAt", Timestamp.now());

                    try {
                        itemsCol.document().set(item).get();
                    } catch (Exception e) {
                        if (isNotFound(e)) {
                            // day doc or path disappeared concurrently; skip this item
                            continue;
                        } else throw e;
                    }

                    if ("missed".equals(initialStatus)) {
                        logMissedDoseForMedicine(elderId, medId, name, type, amount, baseTs);
                    }

                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void logMissedDoseForMedicine(String elderId, String medId, String name, String type, String amount, com.google.cloud.Timestamp baseTs) {
        try {
            Firestore db = FirestoreService.getFirestore();
            CollectionReference missedCol = db.collection("users").document(elderId).collection("missed_doses");
            Map<String, Object> log = new HashMap<>();
            log.put("medicineId", medId);
            log.put("name", name);
            log.put("type", type);
            log.put("amount", amount);
            if (baseTs != null) log.put("missedDoseTime", baseTs);
            else log.put("missedDoseTime", Timestamp.now());
            log.put("loggedAt", Timestamp.now());
            missedCol.document().set(log).get();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // Call to clean up executor when scene is closed
    public void dispose() {
        try {
            if (bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.shutdownNow();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
