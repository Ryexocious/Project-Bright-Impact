package utils;
import java.util.UUID;

public class PairingCodeGenerator {
    public static String generateCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
