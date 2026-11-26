package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;

/** Freezes the address from the canonical reply and verifies the actual OpenTable branch. */
final class DiningPollReceiptTarget {
    private DiningPollReceiptTarget() {}

    static String candidateAddress(
            String thread, String restaurant, JSONObject source, JSONObject reply)
            throws Exception {
        JSONObject provenance = reply.getJSONObject("provenance");
        if (!"primary".equals(source.optString("kind"))
                || !"taku.main-reply/1".equals(reply.optString("schema"))
                || !"primary-assistant-reply".equals(provenance.optString("kind"))
                || !thread.equals(provenance.optString("threadId"))
                || !source.getString("turnId").equals(provenance.optString("turnId"))
                || !source.getString("replyId").equals(reply.optString("id"))
                || source.getLong("revision") != reply.optLong("revision")
                || !source.getString("contractHash").equals(provenance.optString("contractHash"))
                || !"completed".equals(reply.getJSONObject("lifecycle").optString("phase")))
            throw invalid();
        JSONArray blocks =
                reply.getJSONObject("card").getJSONObject("request").getJSONArray("blocks");
        String option = null;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (!"collection".equals(block.optString("kind"))) continue;
            JSONArray rows = block.optJSONArray("items");
            if (rows == null) continue;
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j);
                if (!DiningRestaurantIdentity.sameName(restaurant, row.optString("label")))
                    continue;
                if (option != null) throw invalid();
                option = row.getString("id");
            }
        }
        if (option == null) throw invalid();
        String hint = null;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i), config = block.optJSONObject("config");
            if (!"collection".equals(block.optString("kind"))
                    || config == null
                    || !option.equals(config.optString("group"))) continue;
            JSONArray rows = block.getJSONArray("items");
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j);
                if (!"address".equals(row.optString("id"))) continue;
                JSONObject value = row.getJSONObject("value");
                if (hint != null || !"text".equals(value.optString("type"))) throw invalid();
                hint = value.getString("value");
            }
        }
        if (hint == null) throw invalid();
        requireStreetAddress(hint);
        return hint;
    }

    static void verifyAddress(String expected, String observed) throws IOException {
        requireStreetAddress(expected);
        requireStreetAddress(observed);
        if (!addressKey(expected).equals(addressKey(observed))) throw invalid();
    }

    private static void requireStreetAddress(String value) throws IOException {
        // Require a street number and locality. City-only hints cannot select a branch.
        if (value == null
                || value.length() > 300
                || !value.strip()
                        .matches("(?s)^[0-9]+[A-Za-z]?(?:-[0-9]+)?\\s+[^,]+,\\s*[^,]+.*$")) {
            throw new IOException("RESTAURANT_STREET_ADDRESS_REQUIRED");
        }
    }

    private static String addressKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceFirst("\\b([0-9]{5})-[0-9]{4}(?=\\s*$)", "$1")
                .replaceFirst(",\\s*(?:united states|usa)\\s*$", "")
                .replaceAll("[.,]", " ")
                .replaceAll("\\bstreet\\b", "st")
                .replaceAll("\\bavenue\\b", "ave")
                .replaceAll("\\broad\\b", "rd")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static IOException invalid() {
        return new IOException("RECEIPT_TARGET_CHANGED");
    }
}
