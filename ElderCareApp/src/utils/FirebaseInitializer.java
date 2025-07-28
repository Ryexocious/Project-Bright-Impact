package utils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;

import java.io.FileInputStream;
import java.io.IOException;

public class FirebaseInitializer {

    private static Firestore db;

    public static void initialize() {
        if (db != null) return; // Already initialized

        try {
            FileInputStream serviceAccount =
                new FileInputStream("data/firebase/serviceAccountKey.json");

            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

            FirebaseApp.initializeApp(options);
            db = FirestoreClient.getFirestore();
            System.out.println("✅ Firebase initialized successfully.");

        } catch (IOException e) {
            System.err.println("❌ Failed to initialize Firebase.");
            e.printStackTrace();
        }
    }

    public static Firestore getDB() {
        if (db == null) initialize();
        return db;
    }
}
