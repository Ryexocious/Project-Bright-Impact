package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import utils.EmailService;
import utils.FirestoreService;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import model.TimerController;
import javafx.geometry.Pos;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

/**
 * Updated ElderDashboardController
 * - scheduleExecutor (single-threaded) serializes ensureTodayScheduleAndProcess runs
 * - markItemMissedAndLog is transactional & deterministic to avoid duplicate missed_doses logs
 * - delegates grouped notifications to MissedDoseNotifier (user-provided file)
 */
public class ElderDashboardController {

    private final Object lock = new Object();

    private String elderUsername;
    private String pairingCode;
    private String elderId; // elder document id

    @javafx.fxml.FXML private Button helpRequestButton;
    @javafx.fxml.FXML private Button viewMedicineButton;
    @javafx.fxml.FXML private Button viewVitalsButton;
    @javafx.fxml.FXML private Button logoutButton;
    @javafx.fxml.FXML private Label welcomeLabel;

    @javafx.fxml.FXML private Button pairingCodeButton;
    @javafx.fxml.FXML private Label copyToastLabel;

    @javafx.fxml.FXML private Label upcomingTitleLabel;
    @javafx.fxml.FXML private VBox timerContainer;
    @javafx.fxml.FXML private StackPane timerHost;
    @javafx.fxml.FXML private VBox medsListBox;
    @javafx.fxml.FXML private Button confirmIntakeButton;

    private AnimationTimer countdownAnim;

    // timer target and window
    private LocalDateTime nextDoseDateTime;
    private long initialWindowSeconds = 0L;

    private enum Mode { IDLE, PRE_DOSE, SNOOZE }
    private Mode currentMode = Mode.IDLE;

    // guarded by lock
    private List<ScheduleItem> currentDueItems = new ArrayList<>();

    // prevents immediate re-show for the same group that was just confirmed/hidden
    private volatile LocalDateTime lastShownConfirmGroupBase = null;

    private final DateTimeFormatter labelDateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final DateTimeFormatter dateIdFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimerController timerView;

    // Executor for background Firestore work — kept for network IO
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    // SINGLE-THREADED executor to serialize schedule processing (prevents concurrent mark/log runs)
    private final ExecutorService scheduleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean scheduleRunning = new AtomicBoolean(false);

    private ListenerRegistration medsListener = null;
    private ListenerRegistration todayItemsListener = null;
    private volatile String currentTodayId = null;

    // Rollover checker on FX thread (replaces scheduled executor previously used)
    private Timeline rolloverChecker = null;

    private final Object alarmLock = new Object();
    private volatile MediaPlayer alarmPlayer = null;
    private volatile boolean alarmPlaying = false;

    // Total snooze window in seconds (30 minutes)
    private static final long SNOOZE_TOTAL_SECONDS = 30 * 60;

    // Check if remainingSeconds falls into any of the alarm-trigger windows
    private boolean isInAlarmWindow(long remainingSeconds) {
        if (remainingSeconds < 0) return false;

        return (remainingSeconds >= 1680 && remainingSeconds <= 1799)
                || (remainingSeconds >= 1080 && remainingSeconds <= 1199)
                || (remainingSeconds >= 480 && remainingSeconds <= 599)
                || (remainingSeconds >= 0 && remainingSeconds <= 119);
    }

    private void startAlarmIfNeeded() {
        // must run on JavaFX thread for MediaPlayer interactions
        if (alarmPlaying) return;
        Platform.runLater(() -> {
            synchronized (alarmLock) {
                if (alarmPlaying) return;
                try {
                    File f = new File("data/alarm.mp3"); // relative path to project working dir
                    if (!f.exists()) {
                        System.err.println("Alarm file not found (expected relative data/alarm.mp3). Absolute checked: " + f.getAbsolutePath());
                        return;
                    }
                    String uri = f.toURI().toString();
                    Media media = new Media(uri);
                    MediaPlayer mp = new MediaPlayer(media);
                    mp.setCycleCount(MediaPlayer.INDEFINITE);
                    mp.setOnError(() -> System.err.println("Alarm media error: " + mp.getError()));
                    mp.setOnEndOfMedia(() -> {
                        try { mp.seek(javafx.util.Duration.ZERO); } catch (Exception ignored) {}
                    });
                    mp.play();
                    alarmPlayer = mp;
                    alarmPlaying = true;
                } catch (Exception ex) {
                    ex.printStackTrace();
                    alarmPlayer = null;
                    alarmPlaying = false;
                }
            }
        });
    }

    private void stopAlarmIfNeeded() {
        Platform.runLater(() -> {
            synchronized (alarmLock) {
                if (alarmPlayer != null) {
                    try {
                        alarmPlayer.stop();
                        alarmPlayer.dispose();
                    } catch (Exception ignored) {}
                    alarmPlayer = null;
                }
                alarmPlaying = false;
            }
        });
    }

    private String makeItemDocId(String medId, String time) {
        if (medId == null) medId = "mednull";
        if (time == null) time = "tnull";
        String cleanedTime = time.replace(":", "-");
        String raw = medId + "|" + cleanedTime;
        // allow only letters, digits, dash, underscore, pipe
        return raw.replaceAll("[^a-zA-Z0-9_\\-\\|]", "_");
    }

    // Deduplicate times preserving order
    private List<String> dedupeTimesPreserveOrder(List<String> times) {
        if (times == null) return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : times) {
            if (t != null) seen.add(t);
        }
        return new ArrayList<>(seen);
    }


    public void initializeElder(String loggedInUsername) {
        if (helpRequestButton != null) helpRequestButton.setDisable(true);
        if (welcomeLabel != null) welcomeLabel.setText("Loading...");

        if (pairingCodeButton != null) {
            pairingCodeButton.setText("Loading...");
            pairingCodeButton.setDisable(true);
        }
        if (copyToastLabel != null) copyToastLabel.setVisible(false);
        if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Upcoming Medicines");

        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        bgExecutor.submit(() -> {
            try {
                QuerySnapshot snap = users
                        .whereEqualTo("username", loggedInUsername)
                        .whereEqualTo("role", "elder")
                        .get()
                        .get();

                if (!snap.isEmpty()) {
                    QueryDocumentSnapshot elderDoc = snap.getDocuments().get(0);
                    this.elderUsername = elderDoc.getString("username");
                    this.pairingCode = elderDoc.getString("pairingCode");
                    this.elderId = elderDoc.getId();

                    Platform.runLater(() -> {
                        if (helpRequestButton != null) helpRequestButton.setDisable(false);
                        if (welcomeLabel != null) welcomeLabel.setText("Welcome, " + (elderUsername == null ? "" : elderUsername));
                        if (pairingCodeButton != null) {
                            pairingCodeButton.setDisable(false);
                            pairingCodeButton.setText(pairingCode == null ? "—" : pairingCode);
                        }
                    });

                    setupRealtimeListenersAndRollover();
                    // schedule processing — ensureTodayScheduleAndProcess will submit into scheduleExecutor
                    ensureTodayScheduleAndProcess();

                } else {
                    Platform.runLater(() -> {
                        if (welcomeLabel != null) welcomeLabel.setText("Elder not found");
                        if (pairingCodeButton != null) {
                            pairingCodeButton.setText("No pairing");
                            pairingCodeButton.setDisable(true);
                        }
                    });
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    if (welcomeLabel != null) welcomeLabel.setText("Failed to load elder details");
                    if (pairingCodeButton != null) {
                        pairingCodeButton.setText("Error");
                        pairingCodeButton.setDisable(true);
                    }
                });
            }
        });

        if (confirmIntakeButton != null) {
            confirmIntakeButton.setVisible(false);
            confirmIntakeButton.setDisable(false);
        }
        lastShownConfirmGroupBase = null;
    }


    private void setConfirmButtonVisible(boolean visible, LocalDateTime groupBase) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setConfirmButtonVisible(visible, groupBase));
            return;
        }
        if (confirmIntakeButton == null) return;

        if (visible) {
            if (groupBase != null && groupBase.equals(lastShownConfirmGroupBase)) {
                confirmIntakeButton.setDisable(false);
                confirmIntakeButton.setVisible(true);
                return;
            }
            confirmIntakeButton.setVisible(true);
            confirmIntakeButton.setDisable(false);
            lastShownConfirmGroupBase = groupBase;
        } else {
            confirmIntakeButton.setVisible(false);
            confirmIntakeButton.setDisable(true);
            lastShownConfirmGroupBase = groupBase;
        }
    }
    private void setupRealtimeListenersAndRollover() {
        if (elderId == null) return;
        Firestore db = FirestoreService.getFirestore();

        tryAttachMedsListener(db);
        attachTodayItemsListenerForCurrentDay(db);

        if (rolloverChecker != null) {
            rolloverChecker.stop();
            rolloverChecker = null;
        }

        rolloverChecker = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
            if (elderId == null) return;
            String newTodayId = LocalDate.now().format(dateIdFmt);
            if (!Objects.equals(newTodayId, currentTodayId)) {
                currentTodayId = newTodayId;
                Firestore fdb = FirestoreService.getFirestore();
                detachTodayItemsListener();
                attachTodayItemsListenerForCurrentDay(fdb);
                // schedule processing safely
                ensureTodayScheduleAndProcess();
            }
        }));
        rolloverChecker.setCycleCount(Timeline.INDEFINITE);
        rolloverChecker.play();
    }

    private void tryAttachMedsListener(Firestore db) {
        if (medsListener != null) {
            try { medsListener.remove(); } catch (Exception ignored) {}
            medsListener = null;
        }

        CollectionReference medsCol = db.collection("users").document(elderId).collection("medicines");
        medsListener = medsCol.addSnapshotListener((snap, error) -> {
            if (error != null) {
                System.err.println("Medicines listener error: " + error);
                return;
            }
            // schedule a serialized rescan (ensureTodayScheduleAndProcess will queue it)
            ensureTodayScheduleAndProcess();
        });
    }

    private void attachTodayItemsListenerForCurrentDay(Firestore db) {
        if (elderId == null) return;
        String todayId = LocalDate.now().format(dateIdFmt);
        currentTodayId = todayId;
        DocumentReference todayRef = db.collection("users").document(elderId)
                .collection("schedules").document(todayId);

        detachTodayItemsListener();

        todayItemsListener = todayRef.collection("items").addSnapshotListener((snap, error) -> {
            if (error != null) {
                System.err.println("Today items listener error: " + error);
                return;
            }
            if (snap == null) return;

            List<ScheduleItem> raw = new ArrayList<>();
            for (DocumentSnapshot d : snap.getDocuments()) {
                try {
                    ScheduleItem si = ScheduleItem.fromSnapshot(d);
                    raw.add(si);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            List<ScheduleItem> itemsToUse = computeDedupedAndEffectiveItems(raw);

            Platform.runLater(() -> {
                try {
                    scheduleNextImmediateDoseFromItems(itemsToUse);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
        });
    }

    private List<ScheduleItem> computeDedupedAndEffectiveItems(List<ScheduleItem> raw) {
        Map<String, ScheduleItem> bestByMed = new HashMap<>();
        List<ScheduleItem> finals = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (ScheduleItem s : raw) {
            if (s.medicineId == null) {
                finals.add(s);
                continue;
            }
            if ("taken".equals(s.status) || "missed".equals(s.status)) {
                finals.add(s);
                continue;
            }
            ScheduleItem prev = bestByMed.get(s.medicineId);
            if (prev == null) bestByMed.put(s.medicineId, s);
            else {
                // choose earliest baseTimestamp (closest upcoming), not the later one
                if (s.baseTimestamp != null && prev.baseTimestamp != null) {
                    if (s.baseTimestamp.isBefore(prev.baseTimestamp)) bestByMed.put(s.medicineId, s);
                } else if (s.baseTimestamp != null && prev.baseTimestamp == null) {
                    bestByMed.put(s.medicineId, s);
                }
                // if both null, keep prev (no change)
            }
        }

        List<ScheduleItem> out = new ArrayList<>(finals);
        for (ScheduleItem s : bestByMed.values()) {
            String eff = computeEffectiveStatus(s, now);
            s.effectiveStatus = eff;
            out.add(s);
        }
        return out;
    }

    private String computeEffectiveStatus(ScheduleItem s, LocalDateTime now) {
        if (s == null) return "hasnt_arrived";
        if ("taken".equals(s.status) || "missed".equals(s.status)) return s.status;
        if (s.baseTimestamp == null) return "hasnt_arrived";
        LocalDateTime base = s.baseTimestamp;
        LocalDateTime snoozeEnd = base.plusMinutes(30);
        if (now.isBefore(base)) return "hasnt_arrived";
        if (!now.isBefore(base) && now.isBefore(snoozeEnd)) return "in_snooze_duration";
        return "missed";
    }

    private void detachTodayItemsListener() {
        if (todayItemsListener != null) {
            try { todayItemsListener.remove(); } catch (Exception ignored) {}
            todayItemsListener = null;
        }
    }

    private void teardownRealtimeListeners() {
        if (medsListener != null) {
            try { medsListener.remove(); } catch (Exception ignored) {}
            medsListener = null;
        }
        detachTodayItemsListener();
    }


    /**
     * Public-facing entrypoint that queues a single serialized schedule processing run.
     * This method returns immediately; actual work runs on scheduleExecutor and is guarded
     * so multiple callers won't cause concurrent runs.
     */
    private void ensureTodayScheduleAndProcess() {
        if (elderId == null) return;

        // If another schedule run is in progress, skip — the running one will re-scan at the end.
        if (!scheduleRunning.compareAndSet(false, true)) {
            return;
        }

        scheduleExecutor.submit(() -> {
            try {
                Firestore db = FirestoreService.getFirestore();
                String todayId = LocalDate.now().format(dateIdFmt);
                DocumentReference todayRef = db.collection("users").document(elderId)
                        .collection("schedules").document(todayId);

                try {
                    DocumentSnapshot todaySnap = todayRef.get().get();
                    if (todaySnap.exists()) {
                        syncScheduleWithMedicines(todayRef);
                        updateStatusesForToday(todayRef);
                        loadTodayItemsAndStartTimer(todayRef);
                    } else {
                        // handle leftover from yesterday
                        String yesterdayId = LocalDate.now().minusDays(1).format(dateIdFmt);
                        DocumentReference yRef = db.collection("users").document(elderId)
                                .collection("schedules").document(yesterdayId);
                        DocumentSnapshot ySnap = yRef.get().get();
                        if (ySnap.exists()) {
                            QuerySnapshot pending = yRef.collection("items")
                                    .whereEqualTo("status", "hasnt_arrived")
                                    .get().get();

                            List<ScheduleItem> newlyMarked = new ArrayList<>();
                            for (DocumentSnapshot p : pending.getDocuments()) {
                                ScheduleItem si = ScheduleItem.fromSnapshot(p);
                                boolean marked = markItemMissedAndLog(p.getReference(), si, db);
                                if (marked) newlyMarked.add(si);
                            }

                            if (!newlyMarked.isEmpty()) {
                                // delegate notification (MissedDoseNotifier should also avoid duplicates)
                                final List<ScheduleItem> nm = new ArrayList<>(newlyMarked);
                                bgExecutor.submit(() -> notifyCaretakersAboutMissedDoses(nm));
                            }
                        }
                        createTodayScheduleFromMedicines(todayRef);
                        updateStatusesForToday(todayRef);
                        loadTodayItemsAndStartTimer(todayRef);
                    }
                } catch (InterruptedException | ExecutionException ex) {
                    ex.printStackTrace();
                }
            } finally {
                scheduleRunning.set(false);
            }
        });
    }

    private void createTodayScheduleFromMedicines(DocumentReference todayRef) {
        Firestore db = FirestoreService.getFirestore();
        CollectionReference medsCol = db.collection("users").document(elderId).collection("medicines");

        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("createdAt", Timestamp.now());
            meta.put("date", todayRef.getId());
            todayRef.set(meta).get();

            QuerySnapshot medsSnap = medsCol.get().get();
            LocalDate today = LocalDate.now();
            LocalDateTime now = LocalDateTime.now();

            List<ScheduleItem> newlyMarked = new ArrayList<>();

            for (DocumentSnapshot medDoc : medsSnap.getDocuments()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> activePeriod = (Map<String, Object>) medDoc.get("activePeriod");
                if (!isMedicineActiveOn(activePeriod, today)) continue;

                Object timesObj = medDoc.get("times");
                List<String> times = new ArrayList<>();
                if (timesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> tmp = (List<String>) timesObj;
                    times.addAll(tmp);
                }

                // Deduplicate times (preserve order)
                List<String> dedupedTimes = dedupeTimesPreserveOrder(times);

                String medId = medDoc.getId();
                String name = medDoc.getString("name");
                String type = medDoc.getString("type");
                String amount = medDoc.getString("amount");

                for (String t : dedupedTimes) {
                    try {
                        LocalTime lt = LocalTime.parse(t);
                        LocalDateTime base = LocalDateTime.of(today, lt);
                        Instant baseInstant = base.atZone(ZoneId.systemDefault()).toInstant();
                        com.google.cloud.Timestamp baseTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(baseInstant.getEpochSecond(), baseInstant.getNano());

                        LocalDateTime snoozeEnd = base.plusMinutes(30);
                        String initialStatus;
                        if (now.isBefore(base)) {
                            initialStatus = "hasnt_arrived";
                        } else if (!now.isBefore(base) && now.isBefore(snoozeEnd)) {
                            initialStatus = "in_snooze_duration";
                        } else {
                            initialStatus = "missed";
                        }

                        Map<String, Object> item = new HashMap<>();
                        item.put("medicineId", medId);
                        item.put("medicineRef", medDoc.getReference());
                        item.put("name", name);
                        item.put("type", type);
                        item.put("amount", amount);
                        item.put("time", t);
                        item.put("baseTimestamp", baseTs);
                        item.put("status", initialStatus);
                        item.put("createdAt", Timestamp.now());

                        // Create deterministically to avoid duplicates:
                        String docId = makeItemDocId(medId, t);
                        DocumentReference itemRef = todayRef.collection("items").document(docId);

                        // Transactional create-if-not-exists
                        try {
                            db.runTransaction(transaction -> {
                                try {
                                    DocumentSnapshot snap = transaction.get(itemRef).get();
                                    if (!snap.exists()) {
                                        transaction.set(itemRef, item);
                                    }
                                } catch (Exception innerEx) {
                                    throw new RuntimeException(innerEx);
                                }
                                return null;
                            }).get();
                        } catch (Exception ex) {
                            // Log and continue (don't fail whole loop)
                            ex.printStackTrace();
                        }

                        if ("missed".equals(initialStatus)) {
                            // mark/log via transactional helper to avoid races
                            ScheduleItem s = new ScheduleItem();
                            s.id = itemRef.getId();
                            s.medicineId = medId;
                            s.medicineRef = medDoc.getReference();
                            s.name = name;
                            s.type = type;
                            s.amount = amount;
                            s.time = t;
                            s.baseTimestamp = base;
                            s.status = "missed";
                            s.docRef = itemRef;

                            boolean marked = markItemMissedAndLog(itemRef, s, db);
                            if (marked) newlyMarked.add(s);
                        }
                    } catch (Exception ex) {
                        // skip parse errors
                    }
                }
            }

            if (!newlyMarked.isEmpty()) {
                final List<ScheduleItem> nm = new ArrayList<>(newlyMarked);
                bgExecutor.submit(() -> notifyCaretakersAboutMissedDoses(nm));
            }
        } catch (InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
        }
    }

    private void syncScheduleWithMedicines(DocumentReference todayRef) {
        Firestore db = FirestoreService.getFirestore();

        try {
            QuerySnapshot itemsSnap = todayRef.collection("items").get().get();
            Set<String> existingKeys = new HashSet<>();
            for (DocumentSnapshot d : itemsSnap.getDocuments()) {
                String mid = d.getString("medicineId");
                String time = d.getString("time");
                if (mid != null && time != null) existingKeys.add(mid + "|" + time);
            }

            QuerySnapshot medsSnap = db.collection("users").document(elderId).collection("medicines").get().get();
            LocalDate today = LocalDate.now();
            LocalDateTime now = LocalDateTime.now();

            List<ScheduleItem> newlyMarked = new ArrayList<>();

            for (DocumentSnapshot medDoc : medsSnap.getDocuments()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> activePeriod = (Map<String, Object>) medDoc.get("activePeriod");
                if (!isMedicineActiveOn(activePeriod, today)) continue;

                Object timesObj = medDoc.get("times");
                List<String> times = new ArrayList<>();
                if (timesObj instanceof List) { @SuppressWarnings("unchecked") List<String> tmp = (List<String>) timesObj; times.addAll(tmp); }

                // Deduplicate times (preserve order)
                List<String> dedupedTimes = dedupeTimesPreserveOrder(times);

                String medId = medDoc.getId();
                String name = medDoc.getString("name");
                String type = medDoc.getString("type");
                String amount = medDoc.getString("amount");

                for (String t : dedupedTimes) {
                    String key = medId + "|" + t;
                    if (existingKeys.contains(key)) continue;

                    try {
                        LocalTime lt = LocalTime.parse(t);
                        LocalDateTime base = LocalDateTime.of(today, lt);
                        Instant baseInstant = base.atZone(ZoneId.systemDefault()).toInstant();
                        com.google.cloud.Timestamp baseTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(baseInstant.getEpochSecond(), baseInstant.getNano());

                        LocalDateTime snoozeEnd = base.plusMinutes(30);
                        String initialStatus;
                        if (now.isBefore(base)) {
                            initialStatus = "hasnt_arrived";
                        } else if (!now.isBefore(base) && now.isBefore(snoozeEnd)) {
                            initialStatus = "in_snooze_duration";
                        } else {
                            initialStatus = "missed";
                        }

                        Map<String, Object> item = new HashMap<>();
                        item.put("medicineId", medId);
                        item.put("medicineRef", medDoc.getReference());
                        item.put("name", name);
                        item.put("type", type);
                        item.put("amount", amount);
                        item.put("time", t);
                        item.put("baseTimestamp", baseTs);
                        item.put("status", initialStatus);
                        item.put("createdAt", Timestamp.now());

                        // Deterministic id & transactional create
                        String docId = makeItemDocId(medId, t);
                        DocumentReference itemRef = todayRef.collection("items").document(docId);

                        try {
                            db.runTransaction(transaction -> {
                                try {
                                    DocumentSnapshot snap = transaction.get(itemRef).get();
                                    if (!snap.exists()) {
                                        transaction.set(itemRef, item);
                                    }
                                } catch (Exception innerEx) {
                                    throw new RuntimeException(innerEx);
                                }
                                return null;
                            }).get();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        if ("missed".equals(initialStatus)) {
                            ScheduleItem s = new ScheduleItem();
                            s.id = itemRef.getId();
                            s.medicineId = medId;
                            s.medicineRef = medDoc.getReference();
                            s.name = name;
                            s.type = type;
                            s.amount = amount;
                            s.time = t;
                            s.baseTimestamp = base;
                            s.status = "missed";
                            s.docRef = itemRef;

                            boolean marked = markItemMissedAndLog(itemRef, s, db);
                            if (marked) newlyMarked.add(s);
                        }

                        existingKeys.add(key);
                    } catch (Exception ex) {
                        // skip parse errors
                    }
                }
            }

            if (!newlyMarked.isEmpty()) {
                final List<ScheduleItem> nm = new ArrayList<>(newlyMarked);
                bgExecutor.submit(() -> notifyCaretakersAboutMissedDoses(nm));
            }
        } catch (InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
        }
    }

    private boolean isMedicineActiveOn(Map<String, Object> activePeriod, LocalDate date) {
        if (activePeriod == null) return true;
        try {
            Object s = activePeriod.get("startDate");
            Object e = activePeriod.get("endDate");
            LocalDate start = (s instanceof String) ? LocalDate.parse((String) s) : null;
            LocalDate end = (e instanceof String) ? LocalDate.parse((String) e) : null;
            if (start != null && date.isBefore(start)) return false;
            if (end != null && date.isAfter(end)) return false;
            return true;
        } catch (Exception ex) {
            return true;
        }
    }

    private void updateStatusesForToday(DocumentReference todayRef) {
        Firestore db = FirestoreService.getFirestore();
        try {
            QuerySnapshot itemsSnap = todayRef.collection("items").get().get();
            LocalDateTime now = LocalDateTime.now();
            List<ApiUpdate> batchUpdates = new ArrayList<>();

            // collect missed items to notify
            List<ScheduleItem> toNotifyMissed = new ArrayList<>();

            for (DocumentSnapshot d : itemsSnap.getDocuments()) {
                ScheduleItem s = ScheduleItem.fromSnapshot(d);
                if (s.baseTimestamp == null) continue;

                String current = s.status;
                if ("taken".equals(current) || "missed".equals(current)) continue;

                LocalDateTime base = s.baseTimestamp;
                LocalDateTime snoozeEnd = base.plusMinutes(30);

                if (now.isBefore(base)) {
                    if (!"hasnt_arrived".equals(current)) {
                        batchUpdates.add(new ApiUpdate(d.getReference(), Map.of("status", "hasnt_arrived")));
                    }
                } else if (!now.isBefore(base) && now.isBefore(snoozeEnd)) {
                    if (!"in_snooze_duration".equals(current)) {
                        batchUpdates.add(new ApiUpdate(d.getReference(), Map.of("status", "in_snooze_duration")));
                    }
                } else {
                    // mark missed and collect only if newly marked
                    boolean marked = markItemMissedAndLog(d.getReference(), s, db);
                    if (marked) {
                        toNotifyMissed.add(s);
                    }
                }
            }

            for (ApiUpdate up : batchUpdates) {
                try { up.ref.update(up.data).get(); } catch (Exception ex) { ex.printStackTrace(); }
            }

            // send grouped notification once for all missed items
            if (!toNotifyMissed.isEmpty()) {
                final List<ScheduleItem> tnm = new ArrayList<>(toNotifyMissed);
                bgExecutor.submit(() -> notifyCaretakersAboutMissedDoses(tnm));
            }
        } catch (InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
        }
    }

    private static class ApiUpdate {
        final DocumentReference ref;
        final Map<String, Object> data;
        ApiUpdate(DocumentReference r, Map<String, Object> d) { ref = r; data = d; }
    }

    /**
     * Transactionally mark an item missed (if not already missed/taken) and create a deterministic missed_doses log.
     * Returns true if this call performed the marking & logging (i.e. it was newly marked).
     */
    private boolean markItemMissedAndLog(DocumentReference itemRef, ScheduleItem s, Firestore db) {
        if (itemRef == null) return false;
        try {
            // deterministic missed_doses doc id based on schedule item id to avoid duplicates
            String missedDocId = itemRef.getId();

            Boolean result = db.runTransaction((Transaction.Function<Boolean>) transaction -> {
                DocumentSnapshot snap = transaction.get(itemRef).get();
                String existing = (snap != null && snap.contains("status")) ? snap.getString("status") : null;
                if ("missed".equals(existing) || "taken".equals(existing)) {
                    return false;
                }

                // update the item to missed inside the transaction
                Map<String, Object> upd = new HashMap<>();
                upd.put("status", "missed");
                upd.put("missedLoggedAt", Timestamp.now());
                transaction.update(itemRef, upd);

                // prepare log map
                CollectionReference missedCol = db.collection("users").document(elderId).collection("missed_doses");
                Map<String, Object> log = new HashMap<>();
                log.put("medicineId", s != null ? s.medicineId : (snap.contains("medicineId") ? snap.getString("medicineId") : null));
                log.put("name", s != null ? s.name : (snap.contains("name") ? snap.getString("name") : null));
                log.put("type", s != null ? s.type : (snap.contains("type") ? snap.getString("type") : null));
                log.put("amount", s != null ? s.amount : (snap.contains("amount") ? snap.getString("amount") : null));
                // missedDoseTime
                Object baseO = snap.contains("baseTimestamp") ? snap.get("baseTimestamp") : null;
                if (baseO instanceof com.google.cloud.Timestamp) {
                    com.google.cloud.Timestamp ts = (com.google.cloud.Timestamp) baseO;
                    log.put("missedDoseTime", ts);
                } else {
                    log.put("missedDoseTime", Timestamp.now());
                }
                log.put("loggedAt", Timestamp.now());
                log.put("itemRefPath", itemRef.getPath());

                // set deterministic doc id
                transaction.set(missedCol.document(missedDocId), log);

                return true;
            }).get();

            return Boolean.TRUE.equals(result);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException ee) {
            // concurrent modification or other error; treat as not newly marked
            // ee.printStackTrace();
            return false;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * Notify caretakers with ONE grouped email describing the provided missed items.
     * Delegates to MissedDoseNotifier to centralize idempotent notifications.
     */
    private void notifyCaretakersAboutMissedDoses(List<ScheduleItem> missedItems) {
        if (missedItems == null || missedItems.isEmpty()) return;
        // Convert ScheduleItem -> MissedDoseNotifier.ScheduleMissedItem
        List<MissedDoseNotifier.ScheduleMissedItem> list = new ArrayList<>();
        for (ScheduleItem si : missedItems) {
            MissedDoseNotifier.ScheduleMissedItem m = new MissedDoseNotifier.ScheduleMissedItem();
            m.docRef = si.docRef;
            m.medicineId = si.medicineId;
            m.name = si.name;
            m.type = si.type;
            m.amount = si.amount;
            if (si.baseTimestamp != null) m.baseInstant = si.baseTimestamp.atZone(ZoneId.systemDefault()).toInstant();
            m.time = si.time;
            list.add(m);
        }

        // Delegate to central notifier on bgExecutor
        bgExecutor.submit(() -> MissedDoseNotifier.notifyCaretakersAboutMissedDoses(list, elderId));
    }


    private void loadTodayItemsAndStartTimer(DocumentReference todayRef) {
        // This method runs on scheduleExecutor context if called from ensureTodayScheduleAndProcess,
        // otherwise it posts a small background task to fetch and update UI.
        bgExecutor.submit(() -> {
            try {
                QuerySnapshot itemsSnap = todayRef.collection("items").get().get();
                List<ScheduleItem> items = new ArrayList<>();
                for (DocumentSnapshot d : itemsSnap.getDocuments()) {
                    items.add(ScheduleItem.fromSnapshot(d));
                }

                List<ScheduleItem> processed = computeDedupedAndEffectiveItems(items);

                Platform.runLater(() -> {
                    scheduleNextImmediateDoseFromItems(processed);
                });
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
            }
        });
    }

    private void scheduleNextImmediateDoseFromItems(List<ScheduleItem> items) {
        LocalDateTime now = LocalDateTime.now();

        TreeSet<LocalDateTime> candidateBases = new TreeSet<>();
        Map<LocalDateTime, List<ScheduleItem>> byBase = new HashMap<>();

        for (ScheduleItem it : items) {
            if (it.baseTimestamp == null) continue;
            if ("taken".equals(it.status) || "missed".equals(it.status)) continue;

            String eff = (it.effectiveStatus != null) ? it.effectiveStatus : computeEffectiveStatus(it, now);
            if ("missed".equals(eff) || "taken".equals(eff)) continue;

            LocalDateTime base = it.baseTimestamp;
            if (now.isBefore(base) || (!now.isBefore(base) && now.isBefore(base.plusMinutes(30)))) {
                candidateBases.add(base);
                byBase.computeIfAbsent(base, k -> new ArrayList<>()).add(it);
            }
        }

        if (candidateBases.isEmpty()) {
            synchronized (lock) {
                currentMode = Mode.IDLE;
                currentDueItems = new ArrayList<>();
                nextDoseDateTime = null;
                initialWindowSeconds = 0;
            }
            stopTimelineAndShowIdle();
            Platform.runLater(this::updateMedsUI);
            setConfirmButtonVisible(false, null);
            return;
        }

        LocalDateTime earliest = candidateBases.first();
        List<ScheduleItem> group = byBase.getOrDefault(earliest, Collections.emptyList());
        LocalDateTime base = earliest;
        LocalDateTime snoozeEnd = base.plusMinutes(30);

        Mode newMode;
        LocalDateTime target;
        long initialSecs;
        if (!now.isBefore(base) && now.isBefore(snoozeEnd)) {
            newMode = Mode.SNOOZE;
            target = snoozeEnd;
            initialSecs = Math.max(1L, java.time.Duration.between(base, snoozeEnd).getSeconds());
        } else {
            newMode = Mode.PRE_DOSE;
            target = base;
            Instant nowInst = Instant.now();
            Instant targetInst = base.atZone(ZoneId.systemDefault()).toInstant();
            long secs = java.time.Duration.between(nowInst, targetInst).getSeconds();
            if (secs < 0) secs = 0;
            initialSecs = Math.max(1L, secs);
        }

        List<ScheduleItem> filteredGroup = new ArrayList<>();
        for (ScheduleItem si : group) {
            if (!"taken".equals(si.status) && !"missed".equals(si.status)) filteredGroup.add(si);
        }

        synchronized (lock) {
            currentMode = newMode;
            currentDueItems = filteredGroup;
            nextDoseDateTime = target;
            initialWindowSeconds = initialSecs;
        }

        ensureTimerViewPresent();
        startCountdownAnim();

        if (newMode == Mode.SNOOZE) {
            setConfirmButtonVisible(true, base);
        } else {
            setConfirmButtonVisible(false, null);
        }

        Platform.runLater(this::updateMedsUI);
    }

    private void ensureTimerViewPresent() {
        if (timerHost == null) return;
        if (timerView == null) {
            timerView = new TimerController();
            timerView.setCanvasSize(340, 340);
            timerHost.getChildren().clear();
            timerHost.getChildren().add(timerView);
        }
    }

    private void stopTimelineAndShowIdle() {
        if (countdownAnim != null) {
            countdownAnim.stop();
            countdownAnim = null;
        }
        ensureTimerViewPresent();
        if (timerView != null) timerView.update(0.0, 0L, false);
        setConfirmButtonVisible(false, null);
        // Leave alarm off when idle
        stopAlarmIfNeeded();
    }

    private void startCountdownAnim() {
        // stop prior if any
        if (countdownAnim != null) {
            countdownAnim.stop();
            countdownAnim = null;
        }

        countdownAnim = new AnimationTimer() {
            @Override
            public void handle(long nowNano) {
                updateTimerUI();
            }
        };
        countdownAnim.start();
    }

    private void updateTimerUI() {
        Mode mode;
        List<ScheduleItem> dueItems;
        LocalDateTime target;
        long initialSecs;
        synchronized (lock) {
            mode = currentMode;
            dueItems = new ArrayList<>(currentDueItems);
            target = nextDoseDateTime;
            initialSecs = initialWindowSeconds;
        }

        switch (mode) {
            case IDLE:
                stopTimelineAndShowIdle();
                return;

            case SNOOZE:
                if (dueItems.isEmpty()) {
                    ensureTodayScheduleAndProcess();
                    return;
                }
                LocalDateTime base = dueItems.get(0).baseTimestamp;
                if (base == null) {
                    ensureTodayScheduleAndProcess();
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                long total = java.time.Duration.between(base, base.plusMinutes(30)).getSeconds();
                long elapsed = java.time.Duration.between(base, now).getSeconds();
                if (elapsed < 0) elapsed = 0;
                if (elapsed > total) elapsed = total;

                double progress = total <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, (double) elapsed / (double) total));
                long rem = Math.max(0L, total - elapsed);

                if (timerView != null) timerView.update(progress, rem, true);

                setConfirmButtonVisible(true, base);

                if (Platform.isFxApplicationThread()) {
                    if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Take these medicines now");
                } else {
                    Platform.runLater(() -> { if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Take these medicines now"); });
                }

                long remainingSeconds = java.time.Duration.between(now, target).getSeconds();
                if (remainingSeconds < 0) remainingSeconds = 0;

                // ALARM logic: play when in one of the configured windows and alarm not playing
                if (isInAlarmWindow(remainingSeconds)) {
                    startAlarmIfNeeded();
                } else {
                    stopAlarmIfNeeded();
                }

                if (remainingSeconds <= 0) {
                    // snooze expired: hide button for this group and mark missed
                    setConfirmButtonVisible(false, base);

                    // Ensure alarm stops when snooze expires
                    stopAlarmIfNeeded();

                    List<ScheduleItem> toMark = new ArrayList<>(dueItems);
                    // Use scheduleExecutor to mark items serially and avoid races
                    scheduleExecutor.submit(() -> {
                        Firestore db = FirestoreService.getFirestore();
                        List<ScheduleItem> newlyMarked = new ArrayList<>();
                        for (ScheduleItem s : toMark) {
                            try {
                                DocumentSnapshot snap = s.docRef.get().get();
                                String st = snap.contains("status") ? snap.getString("status") : null;
                                if (st != null && ("taken".equals(st) || "missed".equals(st))) continue;
                                boolean marked = markItemMissedAndLog(s.docRef, s, db);
                                if (marked) newlyMarked.add(s);
                            } catch (InterruptedException | ExecutionException ignore) {}
                        }

                        if (!newlyMarked.isEmpty()) {
                            notifyCaretakersAboutMissedDoses(newlyMarked);
                        }

                        Platform.runLater(() -> {
                            ensureTodayScheduleAndProcess();
                        });
                    });
                }
                return;

            case PRE_DOSE:
                // Ensure alarm is off in pre-dose
                stopAlarmIfNeeded();

                if (target == null) {
                    ensureTodayScheduleAndProcess();
                    return;
                }
                LocalDateTime now2 = LocalDateTime.now();
                long remaining = java.time.Duration.between(now2, target).getSeconds();
                if (remaining <= 0) {
                    ensureTodayScheduleAndProcess();
                    return;
                }
                long total2 = initialSecs > 0 ? initialSecs : remaining;
                double prog2 = total2 <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, (double) (total2 - remaining) / (double) total2));
                if (timerView != null) timerView.update(prog2, remaining, false);

                if (Platform.isFxApplicationThread()) {
                    if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Upcoming Medicines");
                } else {
                    Platform.runLater(() -> { if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Upcoming Medicines"); });
                }
                setConfirmButtonVisible(false, null);
                return;
        }
    }

    @javafx.fxml.FXML
    private void handleConfirmIntake() {
        LocalDateTime confirmedGroupBase;
        synchronized (lock) {
            if (currentMode != Mode.SNOOZE) return;
            confirmedGroupBase = currentDueItems.isEmpty() ? null : currentDueItems.get(0).baseTimestamp;
            currentMode = Mode.IDLE;
        }

        setConfirmButtonVisible(false, confirmedGroupBase);
        stopTimelineAndShowIdle();

        // When user confirms, stop the alarm immediately
        stopAlarmIfNeeded();

        List<ScheduleItem> toMark;
        synchronized (lock) { toMark = new ArrayList<>(currentDueItems); }

        bgExecutor.submit(() -> {
            Firestore db = FirestoreService.getFirestore();
            String todayId = LocalDate.now().format(dateIdFmt);
            DocumentReference todayRef = db.collection("users").document(elderId)
                    .collection("schedules").document(todayId);

            Set<String> updatedItemIds = new HashSet<>();

            // 1) Mark items that were in the confirmed group (the items we captured)
            for (ScheduleItem it : toMark) {
                try {
                    it.docRef.update("status", "taken", "takenAt", Timestamp.now()).get();
                    updatedItemIds.add(it.docRef.getId());
                    if (it.medicineRef != null) {
                        try { it.medicineRef.update("lastTaken", Timestamp.now()).get(); } catch (Exception ex) { /* ignore */ }
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

            // 2) Mark any other items that share the same baseTimestamp as taken as well.
            //    This prevents duplicates with the same scheduled time from remaining.
            try {
                if (confirmedGroupBase != null) {
                    Instant baseInst = confirmedGroupBase.atZone(ZoneId.systemDefault()).toInstant();
                    com.google.cloud.Timestamp groupTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(baseInst.getEpochSecond(), baseInst.getNano());

                    QuerySnapshot groupSnap = todayRef.collection("items")
                            .whereEqualTo("baseTimestamp", groupTs)
                            .get()
                            .get();

                    for (DocumentSnapshot d : groupSnap.getDocuments()) {
                        String docId = d.getId();
                        if (updatedItemIds.contains(docId)) continue;
                        String st = d.contains("status") ? d.getString("status") : null;
                        if (st != null && ("taken".equals(st) || "missed".equals(st))) continue;
                        try {
                            d.getReference().update("status", "taken", "takenAt", Timestamp.now()).get();
                            updatedItemIds.add(docId);
                            DocumentReference medRef = d.get("medicineRef", DocumentReference.class);
                            if (medRef != null) {
                                try { medRef.update("lastTaken", Timestamp.now()).get(); } catch (Exception ex) { /* ignore */ }
                            }
                        } catch (InterruptedException | ExecutionException ex) {
                            ex.printStackTrace();
                        }
                    }
                } else {
                    // fallback to previous behavior (older code) in case group base is null
                    QuerySnapshot snoozing = todayRef.collection("items")
                            .whereEqualTo("status", "in_snooze_duration")
                            .get().get();
                    for (DocumentSnapshot d : snoozing.getDocuments()) {
                        String docId = d.getId();
                        if (updatedItemIds.contains(docId)) continue;
                        try {
                            d.getReference().update("status", "taken", "takenAt", Timestamp.now()).get();
                            updatedItemIds.add(docId);
                            DocumentReference medRef = d.get("medicineRef", DocumentReference.class);
                            if (medRef != null) {
                                try { medRef.update("lastTaken", Timestamp.now()).get(); } catch (Exception ex) { /* ignore */ }
                            }
                        } catch (InterruptedException | ExecutionException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            } catch (InterruptedException | ExecutionException ex) {
                ex.printStackTrace();
            }

            Platform.runLater(() -> {
                synchronized (lock) { currentMode = Mode.IDLE; }
                ensureTodayScheduleAndProcess();
            });
        });
    }

    @javafx.fxml.FXML
    private void handleHelpRequest() {
        if (elderId == null) return;
        Firestore db = FirestoreService.getFirestore();
        CollectionReference users = db.collection("users");

        bgExecutor.submit(() -> {
            try {
                QuerySnapshot caretakersSnapshot = users
                        .whereEqualTo("role", "caretaker")
                        .whereEqualTo("elderId", elderId)
                        .get()
                        .get();

                List<String> caretakerEmails = new ArrayList<>();
                for (QueryDocumentSnapshot doc : caretakersSnapshot.getDocuments()) {
                    String email = doc.getString("email");
                    if (email != null && !email.isEmpty()) caretakerEmails.add(email);
                }

                if (caretakerEmails.isEmpty()) {
                    try {
                        DocumentSnapshot elderSnapshot = users.document(elderId).get().get();
                        Object caretakersField = elderSnapshot.get("caretakers");
                        if (caretakersField instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> caretakerIds = (List<String>) caretakersField;
                            for (String cid : caretakerIds) {
                                try {
                                    DocumentSnapshot caretDocSnap = users.document(cid).get().get();
                                    if (caretDocSnap != null && caretDocSnap.exists()) {
                                        String e = caretDocSnap.getString("email");
                                        if (e != null && !e.isEmpty()) caretakerEmails.add(e);
                                    }
                                } catch (InterruptedException | ExecutionException ex) { ex.printStackTrace(); }
                            }
                        }
                    } catch (InterruptedException | ExecutionException ex) { ex.printStackTrace(); }
                }

                if (!caretakerEmails.isEmpty()) {
                    EmailService.sendHelpRequestEmail(elderUsername, caretakerEmails);
                } else {
                    System.out.println("No caretakers found for elder: " + elderId);
                }
                for (QueryDocumentSnapshot doc : caretakersSnapshot.getDocuments()) {
                    String caretakerId = doc.getId();

                    Map<String, Object> notif = new HashMap<>();
                    notif.put("elderId", elderId);
                    notif.put("caretakerId", caretakerId);
                    notif.put("type", "helpRequest");
                    notif.put("message", "Elder " + elderUsername + " pressed the HELP button!");
                    notif.put("timestamp", System.currentTimeMillis());
                    notif.put("read", false);

                    db.collection("notifications").add(notif);
                }

            } catch (InterruptedException | ExecutionException e) { e.printStackTrace(); }
        });
    }

    @javafx.fxml.FXML
    private void handleViewMedicine() { switchScene("/fxml/medicine_schedule.fxml"); }

    @javafx.fxml.FXML
    private void handleViewVitals() { switchScene("/fxml/vitals.fxml"); }

    @javafx.fxml.FXML
    private void handleLogout() {
        dispose();
        switchScene("/fxml/login.fxml");
    }

    private void switchScene(String fxmlPath) {
        try {
            Parent root = FXMLLoader.load(getClass().getResource(fxmlPath));
            Stage stage = (Stage) (helpRequestButton != null ? helpRequestButton.getScene().getWindow() : null);
            if (stage != null) {
                stage.setScene(new Scene(root));
                stage.centerOnScreen();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    @javafx.fxml.FXML
    private void handleShowPairingCode() {
        if (pairingCode == null || pairingCode.isEmpty()) {
            if (copyToastLabel != null) {
                copyToastLabel.setText("Pairing unavailable");
                copyToastLabel.setVisible(true);

                copyToastLabel.setVisible(false);
            }
            return;
        }

        Clipboard cb = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(pairingCode);
        cb.setContent(content);

        if (copyToastLabel != null) {
            copyToastLabel.setText("Pairing code copied");
            copyToastLabel.setVisible(true);
            copyToastLabel.setVisible(false);
        }
    }


    private void updateMedsUI() {
        if (medsListBox == null) return;
        medsListBox.getChildren().clear();

        Mode mode;
        List<ScheduleItem> items;
        synchronized (lock) {
            mode = currentMode;
            items = new ArrayList<>(currentDueItems);
        }

        if (mode != Mode.SNOOZE) setConfirmButtonVisible(false, null);

        if (mode == Mode.SNOOZE) {
            if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Take these medicines now");
        } else {
            if (upcomingTitleLabel != null) upcomingTitleLabel.setText("Upcoming Medicines");
        }
        if (items == null || items.isEmpty()) {
            medsListBox.setAlignment(Pos.CENTER);
            Label noMoreLbl = new Label("No more medicines scheduled for today");
            noMoreLbl.setWrapText(true);
            noMoreLbl.setStyle("-fx-font-size:14; -fx-text-fill:#666; -fx-padding:12;");
            medsListBox.getChildren().add(noMoreLbl);
            return;
        }

        List<ScheduleItem> show = new ArrayList<>();
        for (ScheduleItem s : items) {
            if (s.status != null && ("taken".equals(s.status) || "missed".equals(s.status))) continue;
            show.add(s);
        }

        if (show.isEmpty()) {
            medsListBox.setAlignment(Pos.CENTER);
            Label noMoreLbl = new Label("No more medicines scheduled for today");
            noMoreLbl.setWrapText(true);
            noMoreLbl.setStyle("-fx-font-size:14; -fx-text-fill:#666; -fx-padding:12;");
            medsListBox.getChildren().add(noMoreLbl);
            return;
        }

        medsListBox.setAlignment(Pos.TOP_LEFT);

        for (ScheduleItem s : show) {
            HBox card = new HBox(8);
            card.setStyle("-fx-padding:8; -fx-background-color: white; -fx-background-radius:8; -fx-border-radius:8; -fx-border-color:#e6e9ef;");
            card.setPrefHeight(56);

            VBox meta = new VBox(2);
            Label nameLbl = new Label(s.name == null ? "(unknown)" : s.name);
            nameLbl.setStyle("-fx-font-weight:bold; -fx-font-size:13;");
            Label amountLbl = new Label(s.amount == null ? "" : s.amount);
            amountLbl.setStyle("-fx-font-size:12; -fx-text-fill:#666;");

            meta.getChildren().addAll(nameLbl, amountLbl);
            HBox.setHgrow(meta, javafx.scene.layout.Priority.ALWAYS);

            Region spacer = new Region();
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

            String timeText = s.time != null ? s.time : (s.baseTimestamp != null ? s.baseTimestamp.toLocalTime().toString() : "");
            Label timeLbl = new Label(timeText);
            timeLbl.setStyle("-fx-font-size:13; -fx-text-fill:#333;");

            card.getChildren().addAll(meta, spacer, timeLbl);
            medsListBox.getChildren().add(card);
        }
    }

    private static class ScheduleItem {
        String id;
        String medicineId;
        DocumentReference medicineRef;
        String name;
        String type;
        String amount;
        String time; // "HH:mm"
        LocalDateTime baseTimestamp;
        String status;
        String effectiveStatus;
        DocumentReference docRef;

        static ScheduleItem fromSnapshot(DocumentSnapshot d) {
            ScheduleItem s = new ScheduleItem();
            s.id = d.getId();
            s.medicineId = d.getString("medicineId");
            s.medicineRef = d.get("medicineRef", DocumentReference.class);
            s.name = d.getString("name");
            s.type = d.getString("type");
            s.amount = d.getString("amount");
            s.time = d.getString("time");
            s.status = d.getString("status");
            s.docRef = d.getReference();

            Object baseO = d.get("baseTimestamp");
            if (baseO instanceof com.google.cloud.Timestamp) {
                com.google.cloud.Timestamp ts = (com.google.cloud.Timestamp) baseO;
                Instant inst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
                s.baseTimestamp = LocalDateTime.ofInstant(inst, ZoneId.systemDefault());
            } else {
                s.baseTimestamp = null;
            }
            return s;
        }
    }

    public void dispose() {
        try {
            teardownRealtimeListeners();
            if (bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.shutdownNow();
            if (scheduleExecutor != null && !scheduleExecutor.isShutdown()) scheduleExecutor.shutdownNow();
            if (countdownAnim != null) countdownAnim.stop();
            if (rolloverChecker != null) rolloverChecker.stop();
            stopAlarmIfNeeded();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
