package utils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.cloud.firestore.Firestore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class FirestoreService {

    private static Firestore db;

    public static Firestore getFirestore() {
        if (db == null) {
            try {
                // Use relative path to avoid hardcoding machine-specific paths
                String relativePath = "data/firebase/eldercareapp-3e451-firebase-adminsdk-fbsvc-22d600c784.json";
                File file = new File(relativePath);
                System.out.println("Using Firebase credentials from: " + file.getAbsolutePath());

                FileInputStream serviceAccount = new FileInputStream(file);

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                db = FirestoreClient.getFirestore();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return db;
    }
}