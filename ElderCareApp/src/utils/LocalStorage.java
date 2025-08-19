package utils;

import java.io.*;
import java.time.LocalDateTime;

public class LocalStorage {

    private static final String MEDICINE_TAKEN_FILE = "data/medicine_taken.txt";

    public static void saveMedicineTaken(LocalDateTime time) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(MEDICINE_TAKEN_FILE, true))) {
            writer.write(time.toString());
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
