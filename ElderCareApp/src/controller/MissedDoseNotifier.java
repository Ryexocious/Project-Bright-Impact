package controller;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import utils.EmailService;
import utils.FirestoreService;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Centralized helper to:
 *  - Find schedule items that should be marked missed (past snooze window)
 *  - Atomically mark them missed (if not already) and log missed_doses
 *  - Group newly-missed items and notify caretakers (email + notifications collection)
 *
 * Behavior:
 *  - Scans recent schedule days (today and previous 2 days) to limit load.
 *  - Uses transactional updates to avoid duplicate marking.
 *  - Uses a "missedNotified" boolean flag on schedule item docs to avoid duplicate notifications/emails.
 */
public class MissedDoseNotifier {

    private static final long SNOOZE_SECONDS = 30 * 60;
    private static final DateTimeFormatter keyFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Scan recent schedule documents for this elder and mark as missed any item whose baseTimestamp <= now - 30 min,
     * status not equal to "taken" or "missed". After marking, notify caretakers grouped by scheduled base datetime.
     *
     * This is intentionally conservative: it scans only the last 3 days (today and two previous days).
     *
     * @param elderId Firestore user id of the elder
     */
    public static void processMissedDosesForElder(String elderId) {
        if (elderId == null) return;
        Firestore db = FirestoreService.getFirestore();

        try {
            Instant now = Instant.now();
            Instant cutoff = now.minusSeconds(SNOOZE_SECONDS);

            List<ScheduleMissedItem> newlyMarked = new ArrayList<>();

            // scan today and previous 2 days
            for (int daysBack = 0; daysBack <= 2; daysBack++) {
                LocalDate d = LocalDate.now().minusDays(daysBack);
                String dayId = d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                DocumentReference dayRef = db.collection("users").document(elderId)
                        .collection("schedules").document(dayId);

                DocumentSnapshot daySnap = null;
                try {
                    daySnap = dayRef.get().get();
                } catch (Exception e) {
                    // ignore missing day doc
                    continue;
                }
                if (daySnap == null || !daySnap.exists()) continue;

                List<QueryDocumentSnapshot> items;
                try {
                    items = dayRef.collection("items").get().get().getDocuments();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    continue;
                }

                for (QueryDocumentSnapshot itemDoc : items) {
                    try {
                        String status = itemDoc.contains("status") ? itemDoc.getString("status") : null;
                        if ("taken".equals(status) || "missed".equals(status)) continue;

                        Object baseO = itemDoc.get("baseTimestamp");
                        Instant baseInst = null;
                        if (baseO instanceof com.google.cloud.Timestamp) {
                            com.google.cloud.Timestamp ts = (com.google.cloud.Timestamp) baseO;
                            baseInst = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
                        } else if (baseO instanceof Date) {
                            baseInst = ((Date) baseO).toInstant();
                        } else {
                            continue;
                        }

                        if (baseInst.isAfter(cutoff)) continue; // not yet beyond snooze

                        // attempt to mark missed in a transaction to avoid races
                        DocumentReference itemRef = itemDoc.getReference();
                        try {
                            db.runTransaction(transaction -> {
                                DocumentSnapshot fresh = transaction.get(itemRef).get();
                                String cur = fresh.contains("status") ? fresh.getString("status") : null;
                                if ("taken".equals(cur) || "missed".equals(cur)) return null;

                                Map<String, Object> up = new HashMap<>();
                                up.put("status", "missed");
                                up.put("missedLoggedAt", Timestamp.now());
                                transaction.update(itemRef, up);

                                return null;
                            }).get();

                            // After successful transaction, write missed_doses log
                            CollectionReference missedCol = db.collection("users").document(elderId).collection("missed_doses");
                            Map<String, Object> log = new HashMap<>();
                            log.put("medicineId", itemDoc.getString("medicineId"));
                            log.put("name", itemDoc.getString("name"));
                            log.put("type", itemDoc.getString("type"));
                            log.put("amount", itemDoc.getString("amount"));
                            if (baseInst != null) {
                                com.google.cloud.Timestamp missedDoseTs = com.google.cloud.Timestamp.ofTimeSecondsAndNanos(baseInst.getEpochSecond(), baseInst.getNano());
                                log.put("missedDoseTime", missedDoseTs);
                            } else {
                                log.put("missedDoseTime", Timestamp.now());
                            }
                            log.put("loggedAt", Timestamp.now());
                            missedCol.document().set(log).get();

                            // Add to newlyMarked for grouping/notification
                            ScheduleMissedItem smi = new ScheduleMissedItem();
                            smi.docRef = itemRef;
                            smi.medicineId = itemDoc.getString("medicineId");
                            smi.name = itemDoc.getString("name");
                            smi.type = itemDoc.getString("type");
                            smi.amount = itemDoc.getString("amount");
                            smi.baseInstant = baseInst;
                            smi.time = itemDoc.getString("time");
                            newlyMarked.add(smi);

                        } catch (ExecutionException ee) {
                            // Could be NotFound or concurrent modification; ignore if already gone or already changed
                            // Print for debugging
                            // ee.printStackTrace();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                    } catch (Exception inner) {
                        inner.printStackTrace();
                    }
                }
            }

            if (!newlyMarked.isEmpty()) {
                notifyCaretakersAboutMissedDoses(newlyMarked, elderId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Notify caretakers (email + notifications docs) for the provided missed items.
     * This method groups items by scheduled base datetime and sends one grouped email.
     * It also writes per-caretaker notification rows to the top-level notifications collection.
     *
     * Important: to avoid duplicate notifications/emails, the code will check the schedule item doc's
     * "missedNotified" flag and only include items that have not been notified. After successful send,
     * it sets "missedNotified" = true and "missedNotifiedAt" timestamp on each item doc.
     */
    public static void notifyCaretakersAboutMissedDoses(List<ScheduleMissedItem> missedItems, String elderId) {
        if (missedItems == null || missedItems.isEmpty() || elderId == null) return;
        Firestore db = FirestoreService.getFirestore();

        try {
            // Filter out items already notified
            List<ScheduleMissedItem> toNotify = new ArrayList<>();
            for (ScheduleMissedItem s : missedItems) {
                try {
                    DocumentSnapshot fresh = s.docRef.get().get();
                    Boolean already = fresh.contains("missedNotified") ? fresh.getBoolean("missedNotified") : Boolean.FALSE;
                    if (Boolean.TRUE.equals(already)) continue;
                    toNotify.add(s);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            if (toNotify.isEmpty()) return;

            // Gather caretakers' emails
            CollectionReference usersCol = db.collection("users");
            List<String> caretakerEmails = new ArrayList<>();
            List<DocumentSnapshot> caretakerDocs = new ArrayList<>();

            QuerySnapshot caretakersSnapshot = usersCol
                    .whereEqualTo("role", "caretaker")
                    .whereEqualTo("elderId", elderId)
                    .get()
                    .get();

            for (QueryDocumentSnapshot doc : caretakersSnapshot.getDocuments()) {
                String email = doc.getString("email");
                if (email != null && !email.isEmpty()) caretakerEmails.add(email);
                caretakerDocs.add(doc);
            }

            if (caretakerEmails.isEmpty()) {
                // fallback to elder.caretakers list
                DocumentSnapshot elderSnap = usersCol.document(elderId).get().get();
                Object caretakersField = elderSnap.get("caretakers");
                if (caretakersField instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> caretakerIds = (List<String>) caretakersField;
                    for (String cid : caretakerIds) {
                        try {
                            DocumentSnapshot caretDocSnap = usersCol.document(cid).get().get();
                            if (caretDocSnap != null && caretDocSnap.exists()) {
                                String e = caretDocSnap.getString("email");
                                if (e != null && !e.isEmpty()) caretakerEmails.add(e);
                                caretakerDocs.add(caretDocSnap);
                            }
                        } catch (Exception ex) { ex.printStackTrace(); }
                    }
                }
            }

            if (caretakerEmails.isEmpty()) {
                // nothing to notify
                return;
            }

            // Group items by base datetime (yyyy-MM-dd HH:mm)
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (ScheduleMissedItem si : toNotify) {
                String key;
                if (si.baseInstant != null) {
                    key = LocalDateTime.ofInstant(si.baseInstant, ZoneId.systemDefault()).format(keyFmt);
                } else {
                    key = (si.time != null ? si.time : "unknown time");
                    key = LocalDate.now().toString() + " " + key;
                }
                String desc = (si.name == null ? "(unknown)" : si.name) + " — " + (si.amount == null ? "" : si.amount);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(desc);
            }

            // Fetch elder username for email
            String elderUsername = "Elder";
            try {
                DocumentSnapshot elderSnap = db.collection("users").document(elderId).get().get();
                String uname = elderSnap != null ? elderSnap.getString("username") : null;
                if (uname != null && !uname.trim().isEmpty()) elderUsername = uname;
            } catch (Exception ex) {
                // ignore and use fallback
            }

            // Send grouped email (best-effort)
            try {
                EmailService.sendMissedDosesEmail(elderUsername, caretakerEmails, grouped);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            // Write notifications docs (one per caretaker) and mark each item as notified
            for (DocumentSnapshot caretakerDoc : caretakerDocs) {
                try {
                    String caretakerId = caretakerDoc.getId();
                    Map<String, Object> notif = new HashMap<>();
                    notif.put("elderId", elderId);
                    notif.put("caretakerId", caretakerId);
                    notif.put("type", "missedDose");
                    notif.put("message", "Elder " + elderUsername + " missed dose(s): " + grouped.toString());
                    notif.put("timestamp", System.currentTimeMillis());
                    notif.put("read", false);

                    db.collection("notifications").add(notif);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            // Mark schedule items as notified
            for (ScheduleMissedItem si : toNotify) {
                try {
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("missedNotified", true);
                    upd.put("missedNotifiedAt", Timestamp.now());
                    si.docRef.update(upd);
                } catch (Exception ex) {
                    // best-effort
                    ex.printStackTrace();
                }
            }

        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Notify for a single missed schedule item immediately (used e.g. when creating today's items and an item is
     * created with initialStatus == "missed"). This method will check missedNotified and only proceed if necessary.
     */
    public static void notifySingleMissedItem(String elderId, ScheduleMissedItem item) {
        if (elderId == null || item == null) return;
        notifyCaretakersAboutMissedDoses(Collections.singletonList(item), elderId);
    }

    public static class ScheduleMissedItem {
        public DocumentReference docRef;
        public String medicineId;
        public String name;
        public String type;
        public String amount;
        public Instant baseInstant;
        public String time;
    }
}
