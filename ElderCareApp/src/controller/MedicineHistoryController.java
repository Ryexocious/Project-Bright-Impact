package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import utils.FirestoreService;
import utils.SessionManager;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * MedicineHistoryController
 *
 * - Prefers forceEndedAt as cutoff; otherwise uses lastDose timestamp (or computes it).
 * - Writes missing firstDose/lastDose timestamps back to Firestore as a best-effort.
 * - Uses SessionManager to locate the current caretaker and their linked elder (falls back to legacy query).
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


    private String resolvedElderId = null;
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ObservableList<MedicineRecord> completedList = FXCollections.observableArrayList();
    private ObservableList<MedicineRecord> ongoingList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        setupColumns();
        setupFilters();
        detectLoggedInCaretaker();

    }

    private void setupColumns() {
        completedMedNameCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("name"));
        completedMedTypeCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("type"));
        completedMedAmountCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("amount"));
        completedMedDosesCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("dosesTaken"));
        completedMedDateRangeCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("dateRange"));

        ongoingMedNameCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("name"));
        ongoingMedTypeCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("type"));
        ongoingMedAmountCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("amount"));
        ongoingMedDosesCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("dosesTaken"));
        ongoingMedDateRangeCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("dateRange"));
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

    private void filterCompletedByDate(FilteredList<MedicineRecord> list) {
        LocalDate from = completedFromDate.getValue();
        LocalDate to = completedToDate.getValue();
        list.setPredicate(med -> {
            if (from == null && to == null) return true;

            LocalDate start = med.getStartDate();
            LocalDate cutoff = med.getCutoffDate();
            if (cutoff == null) cutoff = med.getEndDate();

            if (from != null && start.isBefore(from)) return false;
            if (to != null && start.isAfter(to)) return false;
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

            if (from != null && start.isBefore(from)) return false;
            if (to != null && start.isAfter(to)) return false;
            if (from != null && cutoff.isBefore(from)) return false;
            if (to != null && cutoff.isAfter(to)) return false;

            return true;
        });
    }

    /**
     * Prefer SessionManager to find logged-in caretaker -> linked elderId.
     * Fallback: legacy query whereEqualTo("loggedIn", true).
     */
    private void detectLoggedInCaretaker() {
        new Thread(() -> {
            try {
                // Primary: session manager
                if (SessionManager.isLoggedIn() && SessionManager.getCurrentUserRole() != null
                        && SessionManager.getCurrentUserRole().toLowerCase().contains("caretaker")) {
                    String caretId = SessionManager.getCurrentUserId();
                    try {
                        DocumentSnapshot caret = FirestoreService.getFirestore().collection("users").document(caretId).get().get();
                        if (caret.exists() && caret.contains("elderId")) {
                            resolvedElderId = caret.getString("elderId");
                            loadMedicineHistory();
                            return;
                        } else {
                            // no linked elder - clear lists
                            Platform.runLater(() -> {
                                completedList.clear();
                                ongoingList.clear();
                                completedMedicinesTable.refresh();
                                ongoingMedicinesTable.refresh();
                            });
                            return;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        // fall through to legacy fallback
                    }
                }

                // Fallback legacy query
                Firestore db = FirestoreService.getFirestore();
                List<QueryDocumentSnapshot> docs = db.collection("users")
                        .whereEqualTo("loggedIn", true)
                        .whereEqualTo("role", "caretaker")
                        .get().get().getDocuments();

                if (!docs.isEmpty()) {
                    Object elderIdObj = docs.get(0).get("elderId");
                    if (elderIdObj != null) {
                        resolvedElderId = elderIdObj.toString();
                        loadMedicineHistory();
                        return;
                    }
                }
                // nothing found
                Platform.runLater(() -> {
                    completedList.clear();
                    ongoingList.clear();
                    completedMedicinesTable.refresh();
                    ongoingMedicinesTable.refresh();
                });
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
                    try {
                        String medId = med.getId();
                        String name = med.getString("name");
                        String type = med.getString("type");
                        String amount = med.getString("amount");
                        DocumentReference medRef = med.getReference();

                        @SuppressWarnings("unchecked")
                        Map<String, Object> activePeriod = (Map<String, Object>) med.get("activePeriod");
                        LocalDate start = parseDate(activePeriod == null ? null : activePeriod.get("startDate"));
                        LocalDate end = parseDate(activePeriod == null ? null : activePeriod.get("endDate"));

                        if (start == null || end == null) {
                            // skip if period missing
                            continue;
                        }

                        String dateRange = start + " to " + end;

                        @SuppressWarnings("unchecked")
                        List<String> times = (List<String>) med.get("times");

                        // determine cutoffInstant: prefer forceEndedAt -> lastDose -> computed last dose (end + latest time)
                        Instant cutoffInstant = null;

                        // try forceEndedAt
                        Object forceEndedAtObj = med.get("forceEndedAt");
                        cutoffInstant = extractInstantFromObject(forceEndedAtObj);

                        // if null, try lastDose
                        Instant lastDoseInstant = null;
                        Object lastDoseObj = med.get("lastDose");
                        if (cutoffInstant == null) {
                            lastDoseInstant = extractInstantFromObject(lastDoseObj);
                            // if lastDose missing, compute from end + latest time and persist (best-effort)
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

                                try {
                                    LocalTime lt = LocalTime.parse(latestTime);
                                    LocalDateTime ldt = LocalDateTime.of(end, lt);
                                    lastDoseInstant = ldt.atZone(zid).toInstant();
                                    Timestamp lastDoseTs = Timestamp.ofTimeSecondsAndNanos(lastDoseInstant.getEpochSecond(), lastDoseInstant.getNano());

                                    Map<String, Object> lastDoseMap = Map.of(
                                            "date", end.toString(),
                                            "time", latestTime,
                                            "timestamp", lastDoseTs
                                    );
                                    try {
                                        medRef.update("lastDose", lastDoseMap).get();
                                    } catch (Exception updateEx) {
                                        // ignore persist failure (best-effort)
                                        updateEx.printStackTrace();
                                    }
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                    lastDoseInstant = null;
                                }
                            }
                            cutoffInstant = lastDoseInstant;
                        }

                        // ensure firstDose exists (best-effort write)
                        try {
                            Object firstDoseObj = med.get("firstDose");
                            boolean needFirstDoseSave = false;
                            Map<String, Object> firstDoseMap = null;
                            if (!(firstDoseObj instanceof Map) || ((Map<?, ?>) firstDoseObj).get("timestamp") == null) {
                                // pick earliest time or default to 00:00
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
                                    // ignore best-effort write failure
                                    ex.printStackTrace();
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        // decide completed vs ongoing
                        boolean isCompleted;
                        if (cutoffInstant != null) {
                            isCompleted = !nowInstant.isBefore(cutoffInstant); // now >= cutoffInstant
                        } else {
                            // fallback: end date reached
                            isCompleted = !today.isBefore(end);
                        }

                        // count dosesTaken within active period from history action == "taken"
                        int dosesTaken = 0;
                        try {
                            List<QueryDocumentSnapshot> history = medRef.collection("history")
                                    .whereEqualTo("action", "taken")
                                    .get().get().getDocuments();

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
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        // compute cutoffDate to attach to record (if cutoffInstant present use that day's date)
                        LocalDate cutoffDate = null;
                        if (cutoffInstant != null) cutoffDate = cutoffInstant.atZone(zid).toLocalDate();
                        else cutoffDate = end;

                        Map<String, Object> firstDoseMapRead = med.get("firstDose") instanceof Map ? (Map<String, Object>) med.get("firstDose") : null;
                        Map<String, Object> lastDoseMapRead = med.get("lastDose") instanceof Map ? (Map<String, Object>) med.get("lastDose") : null;
                        Integer maxSnooze = med.contains("maxSnoozeMinutes") && med.get("maxSnoozeMinutes") instanceof Number ?
                                ((Number) med.get("maxSnoozeMinutes")).intValue() : null;

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

                        if (isCompleted) completedList.add(record);
                        else ongoingList.add(record);

                    } catch (Exception inner) {
                        inner.printStackTrace();
                    }
                }

                Platform.runLater(() -> {
                    completedMedicinesTable.setItems(FXCollections.observableArrayList(completedList));
                    ongoingMedicinesTable.setItems(FXCollections.observableArrayList(ongoingList));
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
            try { return LocalDate.parse((String) obj); } catch (Exception ex) { return null; }
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
            if (obj instanceof Timestamp) return ((Timestamp) obj).toDate().toInstant();
            if (obj instanceof java.util.Date) return ((java.util.Date) obj).toInstant();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

  

    public void refreshAfterEnd() {
        loadMedicineHistory();
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

        private final Integer maxSnoozeMinutes;
        private final Map<String, Object> firstDose;
        private final Map<String, Object> lastDose;

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
