package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import utils.FirestoreService;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * MedicineHistoryController
 *
 * - Determines completion by preferring forceEndedAt as cutoff; if null then uses lastDose.timestamp.
 * - If firstDose/lastDose timestamps are missing in Firestore they are computed and written back.
 * - Populates completed vs ongoing lists using the computed/stored cutoff timestamp.
 *
 * Filtering behavior:
 *   A medicine is included by a date filter only when BOTH:
 *     - its start date is inside the filter window (start between from..to)
 *     - its cutoff date (forceEndedAt OR lastDose/end fallback) is inside the filter window
 */
public class MedicineHistoryController {

    @FXML private TableView<MedicineRecord> completedMedicinesTable;
    @FXML private TableColumn<MedicineRecord, String> completedMedNameCol;
    @FXML private TableColumn<MedicineRecord, String> completedMedTypeCol;
    @FXML private TableColumn<MedicineRecord, String> completedMedAmountCol;
    @FXML private TableColumn<MedicineRecord, Integer> completedMedDosesCol;
    @FXML private TableColumn<MedicineRecord, String> completedMedDateRangeCol;

    @FXML private TableView<MedicineRecord> ongoingMedicinesTable;
    @FXML private TableColumn<MedicineRecord, String> ongoingMedNameCol;
    @FXML private TableColumn<MedicineRecord, String> ongoingMedTypeCol;
    @FXML private TableColumn<MedicineRecord, String> ongoingMedAmountCol;
    @FXML private TableColumn<MedicineRecord, Integer> ongoingMedDosesCol;
    @FXML private TableColumn<MedicineRecord, String> ongoingMedDateRangeCol;

    @FXML private TextField completedSearchField;
    @FXML private DatePicker completedFromDate;
    @FXML private DatePicker completedToDate;

    @FXML private TextField ongoingSearchField;
    @FXML private DatePicker ongoingFromDate;
    @FXML private DatePicker ongoingToDate;

    // Back button
    @FXML private Button backButton;

    private String resolvedElderId = null;
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ObservableList<MedicineRecord> completedList = FXCollections.observableArrayList();
    private ObservableList<MedicineRecord> ongoingList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        setupColumns();
        setupFilters();
        detectLoggedInCaretaker();

        // back button handler
        if (backButton != null) backButton.setOnAction(e -> handleBack());
    }

    private void setupColumns() {
        completedMedNameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        completedMedTypeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        completedMedAmountCol.setCellValueFactory(new PropertyValueFactory<>("amount"));
        completedMedDosesCol.setCellValueFactory(new PropertyValueFactory<>("dosesTaken"));
        completedMedDateRangeCol.setCellValueFactory(new PropertyValueFactory<>("dateRange"));

        ongoingMedNameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        ongoingMedTypeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        ongoingMedAmountCol.setCellValueFactory(new PropertyValueFactory<>("amount"));
        ongoingMedDosesCol.setCellValueFactory(new PropertyValueFactory<>("dosesTaken"));
        ongoingMedDateRangeCol.setCellValueFactory(new PropertyValueFactory<>("dateRange"));
    }

    private void setupFilters() {
        FilteredList<MedicineRecord> filteredCompleted = new FilteredList<>(completedList, p -> true);
        completedSearchField.textProperty().addListener((obs, oldV, newV) -> {
            filteredCompleted.setPredicate(med -> {
                if (newV == null || newV.isEmpty()) return true;
                return med.getName().toLowerCase().contains(newV.toLowerCase());
            });
        });
        completedFromDate.valueProperty().addListener((obs, oldV, newV) -> filterCompletedByDate(filteredCompleted));
        completedToDate.valueProperty().addListener((obs, oldV, newV) -> filterCompletedByDate(filteredCompleted));
        completedMedicinesTable.setItems(filteredCompleted);

        FilteredList<MedicineRecord> filteredOngoing = new FilteredList<>(ongoingList, p -> true);
        ongoingSearchField.textProperty().addListener((obs, oldV, newV) -> {
            filteredOngoing.setPredicate(med -> {
                if (newV == null || newV.isEmpty()) return true;
                return med.getName().toLowerCase().contains(newV.toLowerCase());
            });
        });
        ongoingFromDate.valueProperty().addListener((obs, oldV, newV) -> filterOngoingByDate(filteredOngoing));
        ongoingToDate.valueProperty().addListener((obs, oldV, newV) -> filterOngoingByDate(filteredOngoing));
        ongoingMedicinesTable.setItems(filteredOngoing);
    }

    /**
     * Filter logic changed to require BOTH:
     *  - startDate is inside filter window
     *  - cutoffDate is inside filter window
     *
     * If a from or to is null, that side is considered unbounded.
     */
    private void filterCompletedByDate(FilteredList<MedicineRecord> list) {
        LocalDate from = completedFromDate.getValue();
        LocalDate to = completedToDate.getValue();
        list.setPredicate(med -> {
            if (from == null && to == null) return true;

            LocalDate start = med.getStartDate();
            LocalDate cutoff = med.getCutoffDate(); // cutoff (forceEndedAt or lastDose/end fallback)

            // if cutoff is null (extremely unlikely), use endDate fallback
            if (cutoff == null) cutoff = med.getEndDate();

            // start must be within [from,to]
            if (from != null && start.isBefore(from)) return false;
            if (to != null && start.isAfter(to)) return false;

            // cutoff must be within [from,to]
            if (from != null && cutoff.isBefore(from)) return false;
            if (to != null && cutoff.isAfter(to)) return false;

            return true;
        });
    }

    private void filterOngoingByDate(FilteredList<MedicineRecord> list) {
        LocalDate from = ongoingFromDate.getValue();
        LocalDate to = ongoingToDate.getValue();
        list.setPredicate(med -> {
            if (from == null && to == null) return true;

            LocalDate start = med.getStartDate();
            LocalDate cutoff = med.getCutoffDate();

            if (cutoff == null) cutoff = med.getEndDate();

            // start must be within [from,to]
            if (from != null && start.isBefore(from)) return false;
            if (to != null && start.isAfter(to)) return false;

            // cutoff must be within [from,to]
            if (from != null && cutoff.isBefore(from)) return false;
            if (to != null && cutoff.isAfter(to)) return false;

            return true;
        });
    }

    private void detectLoggedInCaretaker() {
        new Thread(() -> {
            Firestore db = FirestoreService.getFirestore();
            try {
                List<QueryDocumentSnapshot> docs = db.collection("users")
                        .whereEqualTo("loggedIn", true)
                        .whereEqualTo("role", "caretaker")
                        .get().get().getDocuments();

                if (!docs.isEmpty()) {
                    Object elderIdObj = docs.get(0).get("elderId");
                    if (elderIdObj != null) {
                        resolvedElderId = elderIdObj.toString();
                        loadMedicineHistory();
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void loadMedicineHistory() {
        if (resolvedElderId == null) return;

        new Thread(() -> {
            Firestore db = FirestoreService.getFirestore();
            try {
                List<QueryDocumentSnapshot> meds = db.collection("users")
                        .document(resolvedElderId)
                        .collection("medicines")
                        .get().get().getDocuments();

                completedList.clear();
                ongoingList.clear();

                Instant nowInstant = Instant.now();
                ZoneId zid = ZoneId.systemDefault();
                LocalDate today = LocalDate.now(zid);

                for (QueryDocumentSnapshot med : meds) {
                    String medId = med.getId();
                    String name = med.getString("name");
                    String type = med.getString("type");
                    String amount = med.getString("amount");

                    DocumentReference medRef = med.getReference();

                    // activePeriod handling (may be Map with startDate/endDate as String or Timestamp)
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> activePeriod = (java.util.Map<String, Object>) med.get("activePeriod");
                    LocalDate start = parseDate(activePeriod == null ? null : activePeriod.get("startDate"));
                    LocalDate end = parseDate(activePeriod == null ? null : activePeriod.get("endDate"));

                    if (start == null || end == null) {
                        // Skip medicine entries without valid activePeriod
                        continue;
                    }

                    String dateRange = start + " to " + end;

                    @SuppressWarnings("unchecked")
                    List<String> times = (List<String>) med.get("times");

                    // --------------- New logic: prefer forceEndedAt, otherwise check lastDose ---------------
                    Instant cutoffInstant = null;

                    // Try forceEndedAt first
                    Object forceEndedAtObj = med.get("forceEndedAt");
                    cutoffInstant = extractInstantFromObject(forceEndedAtObj);

                    // If forceEndedAt is null, then check lastDose
                    Instant lastDoseInstant = null;
                    String lastDoseTimeStr = null;
                    Object lastDoseObj = med.get("lastDose");
                    if (cutoffInstant == null) {
                        if (lastDoseObj instanceof java.util.Map) {
                            Object tsObj = ((java.util.Map<?, ?>) lastDoseObj).get("timestamp");
                            if (tsObj instanceof Timestamp) {
                                lastDoseInstant = ((Timestamp) tsObj).toDate().toInstant();
                            } else if (tsObj instanceof java.util.Date) {
                                lastDoseInstant = ((java.util.Date) tsObj).toInstant();
                            }
                            Object timeObj = ((java.util.Map<?, ?>) lastDoseObj).get("time");
                            lastDoseTimeStr = timeObj == null ? null : timeObj.toString();
                        }

                        // If lastDose timestamp missing, compute it from end + latest time and persist (best effort)
                        if (lastDoseInstant == null) {
                            String latestTime = "23:59";
                            if (times != null && !times.isEmpty()) {
                                try {
                                    latestTime = times.stream()
                                            .map(String::trim)
                                            .filter(t -> {
                                                try { LocalTime.parse(t); return true; } catch (Exception ex) { return false; }
                                            })
                                            .max(Comparator.comparing(LocalTime::parse))
                                            .orElse("23:59");
                                } catch (Exception ex) {
                                    latestTime = "23:59";
                                }
                            }
                            lastDoseTimeStr = latestTime;
                            try {
                                LocalTime lastLt = LocalTime.parse(latestTime);
                                LocalDateTime lastLdt = LocalDateTime.of(end, lastLt);
                                lastDoseInstant = lastLdt.atZone(zid).toInstant();
                                Timestamp lastDoseTs = Timestamp.ofTimeSecondsAndNanos(lastDoseInstant.getEpochSecond(), lastDoseInstant.getNano());
                                Map<String, Object> lastDoseMap = Map.of(
                                        "date", end.toString(),
                                        "time", latestTime,
                                        "timestamp", lastDoseTs
                                );
                                try {
                                    medRef.update("lastDose", lastDoseMap).get();
                                } catch (Exception updateEx) {
                                    updateEx.printStackTrace();
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                lastDoseInstant = null;
                            }
                        }

                        // Now use lastDoseInstant as cutoff if present
                        cutoffInstant = lastDoseInstant;
                    }

                    // Ensure firstDose exists (compute & persist if missing) - best-effort
                    try {
                        Object firstDoseObj = med.get("firstDose");
                        boolean needFirstDoseSave = false;
                        Map<String, Object> firstDoseMap = null;
                        if (!(firstDoseObj instanceof java.util.Map) || ((java.util.Map<?, ?>) firstDoseObj).get("timestamp") == null) {
                            // compute earliest time or default to 00:00
                            String firstTime = "00:00";
                            if (times != null && !times.isEmpty()) {
                                try {
                                    firstTime = times.stream()
                                            .map(String::trim)
                                            .filter(t -> {
                                                try { LocalTime.parse(t); return true; } catch (Exception ex) { return false; }
                                            })
                                            .min(Comparator.comparing(LocalTime::parse))
                                            .orElse("00:00");
                                } catch (Exception ex) {
                                    firstTime = "00:00";
                                }
                            }
                            try {
                                LocalTime ft = LocalTime.parse(firstTime);
                                LocalDateTime fdt = LocalDateTime.of(start, ft);
                                Instant firstInstant = fdt.atZone(zid).toInstant();
                                Timestamp firstTs = Timestamp.ofTimeSecondsAndNanos(firstInstant.getEpochSecond(), firstInstant.getNano());
                                firstDoseMap = Map.of(
                                        "date", start.toString(),
                                        "time", firstTime,
                                        "timestamp", firstTs
                                );
                                needFirstDoseSave = true;
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                        if (needFirstDoseSave && firstDoseMap != null) {
                            try {
                                medRef.update("firstDose", firstDoseMap).get();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    // Determine completion using cutoffInstant (if present)
                    boolean isCompleted = false;
                    if (cutoffInstant != null) {
                        isCompleted = !nowInstant.isBefore(cutoffInstant); // now >= cutoffInstant
                    } else {
                        // Fallback: if today is after or equal to end date -> completed
                        if (!today.isBefore(end)) isCompleted = true;
                    }

                    // Count dosesTaken from history (action == "taken") inside active period
                    List<QueryDocumentSnapshot> history = medRef.collection("history")
                            .whereEqualTo("action", "taken")
                            .get().get().getDocuments();

                    int dosesTaken = 0;
                    for (QueryDocumentSnapshot h : history) {
                        String dateStr = h.getString("date");
                        if (dateStr == null) continue;
                        LocalDate d;
                        try {
                            d = LocalDate.parse(dateStr);
                        } catch (Exception ex) {
                            continue;
                        }
                        if ((d.isEqual(start) || d.isAfter(start)) && (d.isEqual(end) || d.isBefore(end))) dosesTaken++;
                    }

                    // read optional params and dose maps
                    Integer maxSnooze = med.getLong("maxSnoozeMinutes") == null ? null : med.getLong("maxSnoozeMinutes").intValue();
                    Map<String, Object> firstDoseMapRead = med.get("firstDose") instanceof Map ? (Map<String, Object>) med.get("firstDose") : null;
                    Map<String, Object> lastDoseMapRead = med.get("lastDose") instanceof Map ? (Map<String, Object>) med.get("lastDose") : null;

                    // compute cutoffDate to store in record (use cutoffInstant if present else end)
                    LocalDate cutoffDate = null;
                    if (cutoffInstant != null) {
                        cutoffDate = cutoffInstant.atZone(zid).toLocalDate();
                    } else {
                        cutoffDate = end;
                    }

                    // build record (now includes cutoffDate)
                    MedicineRecord record = new MedicineRecord(
                            medId,
                            name,
                            type,
                            amount,
                            dosesTaken,
                            dateRange,
                            start,
                            end,
                            maxSnooze,
                            firstDoseMapRead,
                            lastDoseMapRead,
                            cutoffDate
                    );

                    if (isCompleted) {
                        completedList.add(record);
                    } else {
                        ongoingList.add(record);
                    }
                }

                Platform.runLater(() -> {
                    completedMedicinesTable.refresh();
                    ongoingMedicinesTable.refresh();
                });

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private LocalDate parseDate(Object obj) {
        if (obj instanceof Timestamp) {
            return ((Timestamp) obj).toDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        } else if (obj instanceof String) {
            try {
                return LocalDate.parse((String) obj);
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    public void refreshAfterEnd() {
        loadMedicineHistory();
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
     * Back to dashboard
     */
    private void handleBack() {
        try {
            Parent root = FXMLLoader.load(getClass().getResource("/fxml/caretaker_dashboard.fxml"));
            javafx.stage.Stage stage = (javafx.stage.Stage) backButton.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            // minimal feedback if back fails
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.ERROR, "Failed to load dashboard.");
                a.showAndWait();
            });
        }
    }

    public static class MedicineRecord {
        private final String medicineId;
        private final String name;
        private final String type;
        private final String amount;
        private final int dosesTaken;
        private final String dateRange;
        private final LocalDate startDate;
        private final LocalDate endDate;

        // additional fields (not shown in table by default) for future use
        private final Integer maxSnoozeMinutes;
        private final Map<String, Object> firstDose;
        private final Map<String, Object> lastDose;

        // NEW: cutoffDate (the inclusive date when medicine was ended/force-ended)
        private final LocalDate cutoffDate;

        public MedicineRecord(String medicineId,
                              String name, String type, String amount,
                              int dosesTaken, String dateRange,
                              LocalDate startDate, LocalDate endDate,
                              Integer maxSnoozeMinutes,
                              Map<String, Object> firstDose,
                              Map<String, Object> lastDose,
                              LocalDate cutoffDate) {
            this.medicineId = medicineId;
            this.name = name;
            this.type = type;
            this.amount = amount;
            this.dosesTaken = dosesTaken;
            this.dateRange = dateRange;
            this.startDate = startDate;
            this.endDate = endDate;
            this.maxSnoozeMinutes = maxSnoozeMinutes;
            this.firstDose = firstDose;
            this.lastDose = lastDose;
            this.cutoffDate = cutoffDate;
        }

        public String getMedicineId() { return medicineId; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getAmount() { return amount; }
        public int getDosesTaken() { return dosesTaken; }
        public String getDateRange() { return dateRange; }
        public LocalDate getStartDate() { return startDate; }
        public LocalDate getEndDate() { return endDate; }

        public Integer getMaxSnoozeMinutes() { return maxSnoozeMinutes; }
        public Map<String, Object> getFirstDose() { return firstDose; }
        public Map<String, Object> getLastDose() { return lastDose; }

        public LocalDate getCutoffDate() { return cutoffDate; }
    }
}
