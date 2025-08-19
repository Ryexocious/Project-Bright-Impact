package utils;

import java.util.Properties;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.List;

public class EmailService {

    private static final String SENDER_EMAIL = "fahimabrar3166.official@gmail.com";
    private static final String SENDER_PASSWORD = "zcrkcqcplfodqcnz";  // App password (2FA-enabled)

    // Modified: accepts a list of caretaker emails
    public static void sendHelpRequestEmail(String elderName, List<String> caretakerEmails) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true"); // ✅ use STARTTLS for port 587
        props.put("mail.smtp.ssl.enable", "false");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SENDER_EMAIL, SENDER_PASSWORD);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(SENDER_EMAIL));

            // Convert caretaker list → comma separated string
            String recipients = String.join(",", caretakerEmails);
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients));

            message.setSubject("Emergency Help Request");
            message.setText("Elder " + elderName + " has requested emergency help!");

            Transport.send(message);
            System.out.println("✅ Help request email sent successfully to caretakers: " + recipients);

        } catch (MessagingException e) {
            e.printStackTrace();
            System.err.println("❌ Failed to send help request email.");
        }
    }

    public static void sendConfirmationEmail(String receiverEmail, String username) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true"); // ✅ fix same here
        props.put("mail.smtp.ssl.enable", "false");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SENDER_EMAIL, SENDER_PASSWORD);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(SENDER_EMAIL));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(receiverEmail));
            message.setSubject("ElderCareApp Registration Confirmation");
            message.setText("Hello " + username + ",\n\nYour registration in ElderCareApp was successful.\n\nThank you!");

            Transport.send(message);
            System.out.println("✅ Confirmation email sent to " + receiverEmail);

        } catch (MessagingException e) {
            e.printStackTrace();
            System.err.println("❌ Failed to send confirmation email.");
        }
    }
}
