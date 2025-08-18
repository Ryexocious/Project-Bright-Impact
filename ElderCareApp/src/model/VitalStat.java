package model;

import java.time.LocalDate;

public class VitalStat {
    private LocalDate date;
    private String medicineStatus;  // Taken/Missed
    private String missedMedicine;  // Yes/No
    private String helpRequests;    // Yes/No

    public VitalStat(LocalDate date, String medicineStatus, String missedMedicine, String helpRequests) {
        this.date = date;
        this.medicineStatus = medicineStatus;
        this.missedMedicine = missedMedicine;
        this.helpRequests = helpRequests;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getMedicineStatus() {
        return medicineStatus;
    }

    public String getMissedMedicine() {
        return missedMedicine;
    }

    public String getHelpRequests() {
        return helpRequests;
    }
}
