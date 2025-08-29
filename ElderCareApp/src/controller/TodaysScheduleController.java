package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import utils.FirestoreService;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * TodaysScheduleController - streamlined and robust against multiple Firestore shapes.
 *
 * Inclusion logic for selectedDate:
 *  - If a per-day "dates" array exists: include only when selectedDate is present there.
 *  - Else compute "start" (from activePeriod.startDate or fallback) and "cutoff" (prefer forceEndedAt, then lastDose.timestamp,
 *    else activePeriod.endDate + latest configured time, else top-level endDate). Include when:
 *      start <= selectedDate <= cutoffDate
 *
 * Timezone handling: uses system default zone. If your Firestore timestamps are always Asia/Dhaka,
 * consider replacing ZoneId.systemDefault() with ZoneId.of("Asia/Dhaka").
 */
public class TodaysScheduleController {

    @FXML private VBox scheduleContainer;
    @FXML private Label elderFoundLabel;
    @FXML private Button backButton; // added back button (wired to FXML)

    // new top date label (created dynamically so we keep FXML minimal)
    private Label scheduleDateLabel;

    // Linked elder info
    private String resolvedElderId = null;
    private String resolvedElderUsername = null;

    // Caretaker info
    private String currentCaretakerId = null;
    private String currentCaretakerUsername = null;
    private String currentCaretakerElderId = null;

    private LocalDate selectedDate = LocalDate.now();

    private final DateTimeFormatter displayDateFmt = DateTimeFormatter.ofPattern("d MMMM, yyyy");
    private final DateTimeFormatter isoDateFmt = DateTimeFormatter.ISO_LOCAL_DATE;
    private final DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

    @FXML
    public void initialize() {
        elderFoundLabel.setText("Detecting linked elder...");
        detectLoggedInCaretaker();

        // wire back button to dashboard
        /*if (backButton != null) {
            backButton.setOnAction(e -> handleBack());
        }*/
    }

    /**
     * Adds a big CTA button to allow viewing a custom date's schedule.
     */
    private void addCustomDayButton() {
        Button customDayButton = new Button("View a Custom Day Schedule");
        customDayButton.setStyle(
                "-fx-background-color: #1a73e8; -fx-text-fill: white; " +
                        "-fx-font-size: 14; -fx-padding: 8 20; -fx-background-radius: 6;"
        );
        customDayButton.setOnAction(e -> openDatePicker());

        HBox wrapper = new HBox(customDayButton);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(12, 0, 12, 0));

        scheduleContainer.getChildren().add(wrapper);
    }

    private void openDatePicker() {
        DatePicker datePicker = new DatePicker(selectedDate);
        datePicker.setShowWeekNumbers(false);
        datePicker.setDayCellFactory(dp -> new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item.isBefore(LocalDate.now().minusYears(2)) || item.isAfter(LocalDate.now().plusYears(2)));
            }
        });

        Dialog<LocalDate> dialog = new Dialog<>();
        dialog.setTitle("Select Schedule Date");
        dialog.getDialogPane().setContent(datePicker);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> btn == ButtonType.OK ? datePicker.getValue() : null);

        Optional<LocalDate> result = dialog.showAndWait();
        result.ifPresent(date -> {
            selectedDate = date;
            loadScheduleForSelectedDate();
        });
    }

    // -----------------------
    // Detect logged-in caretaker and linked elder
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

                    if (currentCaretakerElderId != null) {
                        loadMyElder(currentCaretakerElderId);
                    } else {
                        Platform.runLater(() -> elderFoundLabel.setText("This caretaker is not linked to an elder."));
                    }
                } else {
                    Platform.runLater(() -> elderFoundLabel.setText("No logged-in caretaker found."));
                }
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> elderFoundLabel.setText("Failed to detect caretaker."));
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
                    Platform.runLater(() -> {
                        elderFoundLabel.setText("Linked elder: " + resolvedElderUsername);
                        loadScheduleForSelectedDate();
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

    /**
     * Load schedule for selectedDate.
     */
    private void loadScheduleForSelectedDate() {
        scheduleContainer.getChildren().clear();
        if (resolvedElderId == null) return;

        // Top date label
        scheduleDateLabel = new Label("Viewing schedule for " + selectedDate.format(displayDateFmt));
        scheduleDateLabel.setStyle("-fx-font-size:14; -fx-font-weight:600; -fx-text-fill:#222;");
        VBox.setMargin(scheduleDateLabel, new Insets(0, 0, 10, 0));
        scheduleContainer.getChildren().add(scheduleDateLabel);

        Firestore db = FirestoreService.getFirestore();
        CollectionReference medicCol = db.collection("users")
                .document(resolvedElderId)
                .collection("medicines");

        new Thread(() -> {
            try {
                QuerySnapshot snap = medicCol.get().get();
                List<QueryDocumentSnapshot> docs = snap.getDocuments();
                List<HBox> cards = new ArrayList<>();

                ZoneId zid = ZoneId.systemDefault();

                for (QueryDocumentSnapshot d : docs) {
                    String name = safeString(d.getString("name"));
                    String type = safeString(d.getString("type"));
                    String amount = safeString(d.getString("amount"));

                    @SuppressWarnings("unchecked")
                    List<String> times = (List<String>) d.get("times");
                    @SuppressWarnings("unchecked")
                    List<String> dates = (List<String>) d.get("dates"); // optional per-day scheduling
                    Object activePeriodObj = d.get("activePeriod");

                    if (times == null || times.isEmpty()) continue;

                    boolean include = false;

                    // 1) If per-day dates array exists, it takes precedence
                    if (dates != null && !dates.isEmpty()) {
                        include = dates.contains(selectedDate.format(isoDateFmt));
                    } else {
                        // 2) Otherwise rely on activePeriod/start and cutoff-end
                        LocalDate start = parseDateFromActivePeriod(activePeriodObj, "startDate");
                        LocalDate fallbackStart = parseDate(d.get("startDate")); // fallback top-level
                        if (start == null) start = fallbackStart;

                        // If still null, try firstDose.date
                        if (start == null) {
                            Object firstDoseObj = d.get("firstDose");
                            if (firstDoseObj instanceof Map) {
                                Object dateObj = ((Map<?, ?>) firstDoseObj).get("date");
                                start = dateObj instanceof String ? parseLocalDateSafe((String) dateObj) : null;
                            }
                        }

                        if (start == null) {
                            // Defensive: if there's no start we treat as started (legacy records)
                            start = LocalDate.MIN;
                        }

                        // compute cutoff instant/date
                        Instant cutoffInstant = buildCutoffInstant(d, times);
                        LocalDate cutoffDate;
                        if (cutoffInstant != null) {
                            cutoffDate = cutoffInstant.atZone(zid).toLocalDate();
                        } else {
                            LocalDate activeEnd = parseDateFromActivePeriod(activePeriodObj, "endDate");
                            if (activeEnd == null) activeEnd = parseDate(d.get("endDate"));
                            cutoffDate = (activeEnd != null) ? activeEnd : LocalDate.MAX;
                        }

                        // include only when start <= selectedDate <= cutoffDate
                        include = !(selectedDate.isBefore(start) || selectedDate.isAfter(cutoffDate));
                    }

                    if (!include) continue;

                    // For included medicines, create dose cards for each time
                    for (String t : times) {
                        if (t == null) continue;
                        String timeStr = t.trim();
                        if (timeStr.isEmpty()) continue;
                        HBox card = makeDoseCard(name, type, amount, timeStr);
                        cards.add(card);
                    }
                }

                // sort by time
                cards.sort(Comparator.comparing(h -> {
                    Label timeLabel = (Label) h.getUserData();
                    try {
                        return LocalTime.parse(timeLabel.getText(), timeFmt);
                    } catch (Exception ex) {
                        return LocalTime.MIDNIGHT;
                    }
                }));

                Platform.runLater(() -> {
                    if (cards.isEmpty()) {
                        // show "no schedule" message
                        Label emptyMsg = new Label("No medicines scheduled for this day.");
                        emptyMsg.setStyle("-fx-font-size:14; -fx-text-fill:#777;");
                        scheduleContainer.getChildren().add(emptyMsg);
                    } else {
                        scheduleContainer.getChildren().addAll(cards);
                    }
                    addCustomDayButton();
                });
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> elderFoundLabel.setText("Failed to load schedule."));
            }
        }).start();
    }

    private HBox makeDoseCard(String name, String type, String amount, String time) {
        Label medInfoLabel = new Label(name + " (" + type + ") - " + amount);
        medInfoLabel.setStyle("-fx-font-weight:600; -fx-font-size:14;");

        Label timeLabel = new Label(time);
        timeLabel.setStyle("-fx-font-size:12; -fx-text-fill:#666;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox card = new HBox(10, medInfoLabel, spacer, timeLabel);
        card.setStyle("-fx-padding:8; -fx-background-color:#fff; -fx-border-radius:8; -fx-background-radius:8;" +
                "-fx-effect: dropshadow(two-pass-box, rgba(0,0,0,0.06), 4, 0, 0, 2);");

        card.setMaxWidth(Double.MAX_VALUE);
        card.setUserData(timeLabel);
        return card;
    }

    private String safeString(String s) {
        return s != null ? s : "";
    }

    private LocalDate parseDateFromActivePeriod(Object activePeriodObj, String key) {
        if (activePeriodObj == null) return null;
        try {
            if (activePeriodObj instanceof Map) {
                Object v = ((Map<?, ?>) activePeriodObj).get(key);
                if (v == null) return null;
                if (v instanceof String) {
                    return parseLocalDateSafe((String) v);
                } else if (v instanceof Timestamp) {
                    return ((Timestamp) v).toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                } else if (v instanceof java.util.Date) {
                    return ((java.util.Date) v).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                } else if (v instanceof Map) {
                    Object maybeDate = ((Map<?, ?>) v).get("date");
                    if (maybeDate instanceof String) return parseLocalDateSafe((String) maybeDate);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private LocalDate parseLocalDateSafe(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception ex) {
            try {
                return LocalDate.parse(s, isoDateFmt);
            } catch (Exception ex2) {
                return null;
            }
        }
    }

    private LocalDate parseDate(Object obj) {
        if (obj instanceof Timestamp) {
            return ((Timestamp) obj).toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        } else if (obj instanceof String) {
            return parseLocalDateSafe((String) obj);
        } else if (obj instanceof java.util.Date) {
            return ((java.util.Date) obj).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return null;
    }

    private Instant buildCutoffInstant(DocumentSnapshot d, List<String> times) {
        try {
            Object forceEndedAtObj = d.get("forceEndedAt");
            Instant inst = extractInstantFromObject(forceEndedAtObj);
            if (inst != null) return inst;

            Object lastDoseObj = d.get("lastDose");
            Instant lastInst = extractInstantFromObject(lastDoseObj);
            if (lastInst != null) return lastInst;

            Object activePeriodObj = d.get("activePeriod");
            LocalDate activeEnd = parseDateFromActivePeriod(activePeriodObj, "endDate");
            if (activeEnd != null) {
                String latestTime = "23:59";
                if (times != null && !times.isEmpty()) {
                    try {
                        latestTime = times.stream()
                                .map(String::trim)
                                .filter(t -> {
                                    try { LocalTime.parse(t); return true; } catch (Exception e) { return false; }
                                })
                                .max(Comparator.comparing(LocalTime::parse))
                                .orElse("23:59");
                    } catch (Exception ex) { latestTime = "23:59"; }
                }
                try {
                    LocalTime lt = LocalTime.parse(latestTime);
                    LocalDateTime ldt = LocalDateTime.of(activeEnd, lt);
                    return ldt.atZone(ZoneId.systemDefault()).toInstant();
                } catch (Exception ex) {
                    try {
                        LocalDateTime ldt = LocalDateTime.of(activeEnd, LocalTime.MAX);
                        return ldt.atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored) {}
                }
            }

            Object topEnd = d.get("endDate");
            LocalDate topEndDate = parseDate(topEnd);
            if (topEndDate != null) {
                try {
                    LocalDateTime ldt = LocalDateTime.of(topEndDate, LocalTime.MAX);
                    return ldt.atZone(ZoneId.systemDefault()).toInstant();
                } catch (Exception ignored) {}
            }
            return null;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
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

    /*private void handleBack() {
        try {
            Parent root = javafx.fxml.FXMLLoader.load(getClass().getResource("/fxml/caretaker_dashboard.fxml"));
            javafx.stage.Stage stage = (javafx.stage.Stage) scheduleContainer.getScene().getWindow();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (IOException e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.ERROR, "Failed to load dashboard.");
                a.showAndWait();
            });
        }
    }*/
}
