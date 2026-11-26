package org.agentos.assistant;

import static org.agentos.assistant.DiningPollIslandTest.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public final class DiningPollReceiptDateTest {
    private static final LocalDate DATE = LocalDate.of(2026, 12, 1);
    private static final LocalTime TIME = LocalTime.of(19, 15);
    private static final ZonedDateTime TARGET =
            ZonedDateTime.of(DATE, TIME, ZoneId.of("America/New_York"));

    private static List<String> details(String label) {
        return List.of(
                "Harbor Cafe",
                "Reservation confirmed",
                "23 King St, New York, NY 10001",
                "Party of 3",
                label,
                "Manage booking");
    }

    public static void main(String[] args) throws Exception {
        List<String> yearless = details("Dec 1 at 7:15 PM");
        rejects("RECEIPT_FULL_DATE_REQUIRED", () -> DiningPollReceipt.parse(yearless));
        check(
                DiningPollReceipt.matchesDate(DiningPollReceipt.parse(yearless, DATE), TARGET),
                "A matching native calendar proof supplies the missing year");
        check(
                DiningPollReceipt.timeForDate("Dec 1 at 7:15 PM", DATE).equals(TIME),
                "The Modify booking summary may also omit the year");
        rejects(
                "RESERVATION_DATE_CONTEXT_CHANGED",
                () -> DiningPollReceipt.parse(yearless, DATE.plusDays(1)));
        rejects(
                "RESERVATION_DATE_CONTEXT_CHANGED",
                () -> DiningPollReceipt.parse(yearless, DATE.plusMonths(1)));
        check(
                !DiningPollReceipt.matchesDate(
                        DiningPollReceipt.parse(details("Dec 1 at 7:45 PM"), DATE), TARGET),
                "Missing year does not waive the receipt's own time");

        List<String> weekday = details("Tue at 7:15 PM");
        rejects("RECEIPT_FULL_DATE_REQUIRED", () -> DiningPollReceipt.parse(weekday));
        check(
                DiningPollReceipt.matchesDate(DiningPollReceipt.parse(weekday, DATE), TARGET),
                "A matching weekday and native calendar proof remain supported");
        rejects(
                "RESERVATION_DATE_CONTEXT_CHANGED",
                () -> DiningPollReceipt.parse(weekday, DATE.plusDays(1)));

        List<String> explicit = details("Dec 1, 2026 at 7:15 PM");
        check(
                DiningPollReceipt.matchesDate(DiningPollReceipt.parse(explicit), TARGET),
                "A full date from the confirmed receipt needs no secondary proof");
        rejects(
                "RESERVATION_DATE_CONTEXT_CHANGED",
                () -> DiningPollReceipt.parse(explicit, DATE.plusYears(1)));
        check(
                !DiningPollReceipt.matchesDate(
                        new DiningPollReceipt.Receipt(
                                "Harbor Cafe", "Dec 1 at 7:15 PM", 3, "", null, 12, 1, TIME),
                        TARGET),
                "Yearless model cannot bypass the parser's requirement");
        System.out.println("Dining receipt dates: " + checks + " checks passed");
    }
}
