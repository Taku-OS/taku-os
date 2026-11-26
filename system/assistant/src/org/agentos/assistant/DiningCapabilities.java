package org.agentos.assistant;

import org.json.JSONObject;

final class DiningCapabilities {
    private DiningCapabilities() {}

    static JSONObject snapshot() throws Exception {
        return new JSONObject()
                .put("candidateShare", true)
                .put("pollJournal", true)
                .put("pollSendReceipt", true)
                .put("pollObservation", true)
                .put("booking", true)
                .put("reservationFeed", true)
                .put("calendarFollowUp", false)
                .put("rideFollowUp", false);
    }
}
