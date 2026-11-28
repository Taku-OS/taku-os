package org.agentos.notebook;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookPlaces {
    private static final int MAX_MATCHES = 5;
    private static final int SAME_PLACE_METERS = 75;

    record Place(String id, String name, String category, String locality, double latitude, double longitude,
                 int radiusMeters) {
        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("category", category)
                    .put("locality", locality).put("latitude", latitude).put("longitude", longitude)
                    .put("radiusMeters", radiusMeters);
        }

        static Place read(JSONObject value) throws JSONException {
            return new Place(value.getString("id"), value.getString("name"), value.getString("category"),
                    value.getString("locality"), value.getDouble("latitude"), value.getDouble("longitude"),
                    value.getInt("radiusMeters"));
        }
    }

    record Resolution(String status, Place place) {
        static Resolution unavailable(String status) { return new Resolution(status, null); }
    }

    private NotebookPlaces() {}

    static String key(NotebookArrivalIntent.Hint hint) {
        return normalize(hint.placeName()) + "\n" + normalize(hint.locality()) + "\n" + hint.category();
    }

    static boolean eligible(NotebookArrivalIntent.Hint hint) {
        return "place".equals(hint.trigger()) && !"country".equals(hint.category());
    }

    static Resolution resolve(Context context, NotebookArrivalIntent.Hint hint) throws InterruptedException {
        if (!Geocoder.isPresent()) return Resolution.unavailable("geocoder_unavailable");
        var connectivity = context.getSystemService(ConnectivityManager.class);
        var network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (network == null || !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return Resolution.unavailable("offline");
        }

        String query = hint.placeName();
        if (!hint.locality().isEmpty()) query += ", " + hint.locality();
        try {
            var geocoder = new Geocoder(context);
            var addresses = lookup(geocoder, query);
            var named = select(hint, addresses);
            if (!"not_found".equals(named.status()) || hint.locality().isEmpty()) return named;

            // Some geocoders return only a street address for a landmark. A second
            // search for the name must corroborate that exact address in the supported area.
            var precise = distinct(addresses, address -> inLocality(hint, address) && street(address) != null);
            if (precise.size() > 1) return Resolution.unavailable("ambiguous");
            if (precise.isEmpty()) return named;
            Address candidate = precise.get(0);
            String street = street(candidate);
            for (Address other : lookup(geocoder, hint.placeName())) {
                if (validFix(other) && inLocality(hint, other) && street.equals(street(other))
                        && samePosition(candidate, other)) return resolved(hint, candidate);
            }
            return named;
        } catch (ExecutionException | TimeoutException | RuntimeException unavailable) {
            return Resolution.unavailable("geocoder_unavailable");
        }
    }

    private static List<Address> lookup(Geocoder geocoder, String query)
            throws InterruptedException, ExecutionException, TimeoutException {
        var answer = new CompletableFuture<List<Address>>();
        geocoder.getFromLocationName(query, MAX_MATCHES, new Geocoder.GeocodeListener() {
            @Override public void onGeocode(List<Address> addresses) { answer.complete(addresses); }
            @Override public void onError(String error) { answer.completeExceptionally(new IllegalStateException()); }
        });
        return answer.get(12, TimeUnit.SECONDS);
    }

    static Resolution select(NotebookArrivalIntent.Hint hint, List<Address> addresses) {
        var matched = distinct(addresses, address -> named(hint, address) && inLocality(hint, address));
        return switch (matched.size()) {
            case 0 -> Resolution.unavailable("not_found");
            case 1 -> resolved(hint, matched.get(0));
            default -> Resolution.unavailable("ambiguous");
        };
    }

    private static List<Address> distinct(List<Address> addresses, Predicate<Address> accepted) {
        List<Address> matches = new ArrayList<>();
        for (Address address : addresses) {
            if (!validFix(address) || !accepted.test(address)) continue;
            if (matches.stream().noneMatch(other -> samePosition(other, address))) matches.add(address);
        }
        return matches;
    }

    private static boolean samePosition(Address first, Address second) {
        float[] distance = new float[1];
        Location.distanceBetween(first.getLatitude(), first.getLongitude(),
                second.getLatitude(), second.getLongitude(), distance);
        return distance[0] <= SAME_PLACE_METERS;
    }

    private static Resolution resolved(NotebookArrivalIntent.Hint hint, Address match) {
        double lat = match.getLatitude();
        double lon = match.getLongitude();
        String street = street(match);
        String identity = (street == null ? normalize(match.getFeatureName()) : street) + ":"
                + Math.round(lat * 10_000) + ":" + Math.round(lon * 10_000);
        String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        int radius = switch (hint.category()) {
            case "beach" -> 450;
            case "attraction" -> 300;
            default -> 200;
        };
        return new Resolution("resolved", new Place(id, hint.placeName(), hint.category(), hint.locality(),
                lat, lon, radius));
    }

    private static boolean validFix(Address address) {
        if (!address.hasLatitude() || !address.hasLongitude()) return false;
        double lat = address.getLatitude(), lon = address.getLongitude();
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    private static boolean named(NotebookArrivalIntent.Hint hint, Address address) {
        String name = normalize(hint.placeName());
        if (name.equals(normalize(address.getPremises()))) return true;
        return name.equals(normalize(address.getFeatureName()))
                && !name.equals(normalize(address.getSubLocality()))
                && !name.equals(normalize(address.getLocality()))
                && !name.equals(normalize(address.getSubAdminArea()))
                && !name.equals(normalize(address.getAdminArea()))
                && !name.equals(normalize(address.getCountryName()));
    }

    private static boolean inLocality(NotebookArrivalIntent.Hint hint, Address address) {
        String locality = normalize(hint.locality());
        if (locality.isEmpty()) return true;
        for (String area : new String[]{address.getSubLocality(), address.getLocality(), address.getSubAdminArea(),
                address.getAdminArea(), address.getCountryName()}) {
            if (locality.equals(normalize(area))) return true;
        }
        return false;
    }

    private static String street(Address address) {
        String number = normalize(address.getSubThoroughfare());
        String road = normalize(address.getThoroughfare());
        String country = normalize(address.getCountryCode());
        if (number.isEmpty() || road.isEmpty() || country.isEmpty()) return null;
        return number + "\n" + road + "\n" + country;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", " ").strip();
    }
}
