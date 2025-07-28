package tests;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;

import java.io.FileInputStream;
import java.io.IOException;

public class TestFirestore {
    public static void main(String[] args) {
        try {
            // 🔑 Load your service account key
            FileInputStream serviceAccount = new FileInputStream("data/firebase/eldercareapp-d9ea0-firebase-adminsdk-fbsvc-cf1472bd91.json");

            // ⚙️ Build Firebase options
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

            // 🚀 Initialize Firebase
            FirebaseApp.initializeApp(options);

            // 🔥 Now get Firestore
            Firestore db = FirestoreClient.getFirestore();

            System.out.println("✅ Firestore initialized successfully!");

        } catch (IOException e) {
            System.err.println("❌ Error loading service account file:");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ General error:");
            e.printStackTrace();
        }
    }
}
