package utils;

import java.util.Properties;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EmailService {

    private static final String SENDER_EMAIL = "fahimabrar3166.official@gmail.com";
    private static final String SENDER_PASSWORD = "zcrkcqcplfodqcnz";  // App password (2FA-enabled)

    public static void sendHelpRequestEmail(String elderName, List<String> caretakerEmails) {
        if (caretakerEmails == null || caretakerEmails.isEmpty()) {
            System.err.println("No caretakers to email for help request.");
            return;
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true"); // use STARTTLS (port 587)
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

    /**
     * Send a single grouped missed-doses email.
     *
     * @param elderName human name of elder
     * @param caretakerEmails list of recipient emails
     * @param groupedMisses map: key = human datetime string (e.g. "2025-08-25 14:00"), value = list of descriptions
     */
    public static void sendMissedDosesEmail(String elderName, List<String> caretakerEmails, Map<String, List<String>> groupedMisses) {
        if (caretakerEmails == null || caretakerEmails.isEmpty() || groupedMisses == null || groupedMisses.isEmpty()) {
            System.err.println("No caretakers or missed doses to notify.");
            return;
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
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

            String recipients = String.join(",", caretakerEmails);
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients));

            String subject = "Missed Doses Alert — " + elderName;
            message.setSubject(subject);

            // Build the body grouped by time (sorted)
            StringBuilder sb = new StringBuilder();
            sb.append("Hello,\n\n");
            sb.append("This is an automated notification from ElderCareApp.\n\n");
            sb.append("Elder ").append(elderName).append(" has missed the following scheduled doses:\n\n");

            groupedMisses.keySet().stream().sorted().forEach(timeKey -> {
                sb.append("At ").append(timeKey).append(":\n");
                List<String> items = groupedMisses.get(timeKey);
                if (items != null) {
                    items.forEach(it -> sb.append("  - ").append(it).append("\n"));
                }
                sb.append("\n");
            });

            sb.append("Please check and, if required, attend to the elder promptly.\n\n");
            sb.append("Regards,\nElderCareApp Automated Alerts\n");

            message.setText(sb.toString());
            Transport.send(message);
            System.out.println("✅ Missed-doses email sent to: " + recipients);
        } catch (MessagingException e) {
            e.printStackTrace();
            System.err.println("❌ Failed to send missed-doses email.");
        }
    }

    public static void sendConfirmationEmail(String receiverEmail, String username) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
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
