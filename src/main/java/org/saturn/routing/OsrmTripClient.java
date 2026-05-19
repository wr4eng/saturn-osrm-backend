// src/main/java/org/saturn/routing/OsrmTripClient.java

/*
 * OsrmTripClient.java 2026 WrA (wra.eng@gmail.com)
 *
 * OSRM Trip client — solves the Traveling Salesman Problem via /trip/v1.
 * Uses a greedy heuristic (farthest-insertion) for >=10 waypoints,
 * brute force for <10 waypoints.
 *
 * Primary use case: multi-stop route planning from device last positions.
 *
 * Input  : List<Position> — typically last known position per device/stop
 * Output : TripResult     — ordered waypoints + per-leg geometry + totals
 *
 * Fallback: if OSRM returns NoTrips or HTTP fails, returns null so the
 * caller can decide whether to fall back to OsrmClient (/route/v1) or
 * return an error to the API.
 */

package org.saturn.routing;

import org.json.JSONArray;
import org.json.JSONObject;
import org.saturn.config.Config;
import org.saturn.config.Keys;
import org.saturn.model.Position;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OsrmTripClient {

    private static final Logger LOGGER = Logger.getLogger(OsrmTripClient.class.getName());

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT =  90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON =  180.0;

    private final String   baseUrl;
    private final String   profile;
    private final Duration requestTimeout;
    private final int      maxRetries;
    private final boolean  roundtrip;
    private final String   source;
    private final String   destination;
    private final HttpClient httpClient;

    // Optional fallback for when OSRM returns NoTrips
    private final OsrmClient fallbackClient;

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /**
     * One leg of the optimized trip (between two consecutive waypoints).
     */
    public static class TripLeg {
        public final double distanceMeters;
        public final double durationSeconds;
        /** GeoJSON LineString coordinates [lon, lat] pairs — ready for map rendering */
        public final List<double[]> geometry;

        public TripLeg(double distanceMeters, double durationSeconds, List<double[]> geometry) {
            this.distanceMeters  = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.geometry        = Collections.unmodifiableList(geometry);
        }
    }

    /**
     * A waypoint in the optimized visit order.
     * tripsIndex    : which trip object this waypoint belongs to (usually 0)
     * waypointIndex : position index within that trip
     * original      : the original input Position this waypoint maps to
     */
    public static class WaypointOrder {
        public final int      tripsIndex;
        public final int      waypointIndex;
        public final Position original;

        public WaypointOrder(int tripsIndex, int waypointIndex, Position original) {
            this.tripsIndex    = tripsIndex;
            this.waypointIndex = waypointIndex;
            this.original      = original;
        }
    }

    /**
     * Full result of a /trip/v1 call.
     */
    public static class TripResult {
        public final List<WaypointOrder> waypoints;
        public final List<TripLeg>       legs;
        public final double              totalDistanceMeters;
        public final double              totalDurationSeconds;

        public TripResult(List<WaypointOrder> waypoints, List<TripLeg> legs,
                          double totalDistanceMeters, double totalDurationSeconds) {
            this.waypoints            = Collections.unmodifiableList(waypoints);
            this.legs                 = Collections.unmodifiableList(legs);
            this.totalDistanceMeters  = totalDistanceMeters;
            this.totalDurationSeconds = totalDurationSeconds;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public OsrmTripClient(Config config, OsrmClient fallbackClient) {
        this.baseUrl        = config.getString(Keys.ROUTING_URL).replaceAll("/+$", "");
        this.profile        = config.getString(Keys.ROUTING_PROFILE);
        this.requestTimeout = Duration.ofSeconds(config.getLong(Keys.ROUTING_TIMEOUT));
        this.maxRetries     = config.getInteger(Keys.ROUTING_RETRY);
        this.roundtrip      = config.getBoolean(Keys.ROUTING_TRIP_ROUNDTRIP);
        this.source         = config.getString(Keys.ROUTING_TRIP_SOURCE);
        this.destination    = config.getString(Keys.ROUTING_TRIP_DESTINATION);
        this.fallbackClient = fallbackClient;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        LOGGER.info(String.format(
                "OSRM TripClient initialized: url=%s, profile=%s, roundtrip=%s, source=%s, destination=%s",
                baseUrl, profile, roundtrip, source, destination));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Plan an optimized multi-stop trip from a list of last positions.
     *
     * @param positions last known position per device/stop (order does not matter)
     * @return TripResult with optimized visit order and route geometry,
     *         or null if OSRM cannot compute a trip and no fallback is configured
     */
    public TripResult planTrip(List<Position> positions) {
        if (positions == null || positions.size() < 2) {
            LOGGER.warning("TripClient: need at least 2 positions");
            return null;
        }

        List<Position> valid = filterValid(positions);
        if (valid.size() < 2) {
            LOGGER.warning("TripClient: not enough valid coordinates");
            return null;
        }

        String coords = buildCoordinatesString(valid);
        String url    = buildTripUrl(coords);

        LOGGER.fine("OSRM trip URL: " + url);

        HttpResponse<String> response = executeWithRetry(url);
        if (response == null || response.statusCode() != 200) {
            LOGGER.warning("TripClient: HTTP request failed after retries");
            return null;
        }

        return parseTripResponse(response.body(), valid);
    }

    // -------------------------------------------------------------------------
    // URL builder
    // -------------------------------------------------------------------------

    private String buildTripUrl(String coords) {
        StringBuilder url = new StringBuilder();
        url.append(baseUrl)
           .append("/trip/v1/")
           .append(profile)
           .append("/")
           .append(coords)
           .append("?overview=full")
           .append("&geometries=geojson")
           .append("&steps=false")
           .append("&roundtrip=").append(roundtrip)
           .append("&source=").append(source)
           .append("&destination=").append(destination);
        return url.toString();
    }

    // -------------------------------------------------------------------------
    // Response parser
    // -------------------------------------------------------------------------

    private TripResult parseTripResponse(String json, List<Position> inputPositions) {
        try {
            JSONObject root = new JSONObject(json);
            String code = root.optString("code", "Unknown");

            if ("NoTrips".equals(code)) {
                LOGGER.warning("TripClient: NoTrips — input coordinates may not be connected");
                return null;
            }
            if (!"Ok".equals(code)) {
                LOGGER.warning("TripClient: code=" + code
                        + " msg=" + root.optString("message"));
                return null;
            }

            // --- Waypoints (optimized visit order) ---
            JSONArray waypointsJson = root.optJSONArray("waypoints");
            List<WaypointOrder> waypoints = new ArrayList<>();

            if (waypointsJson != null) {
                for (int i = 0; i < waypointsJson.length(); i++) {
                    JSONObject wp = waypointsJson.getJSONObject(i);
                    int tripsIndex    = wp.optInt("trips_index", 0);
                    int waypointIndex = wp.optInt("waypoint_index", i);
                    Position original = i < inputPositions.size()
                            ? inputPositions.get(i) : null;
                    waypoints.add(new WaypointOrder(tripsIndex, waypointIndex, original));
                }
            }

            // --- Trips (legs + geometry) ---
            JSONArray tripsJson = root.optJSONArray("trips");
            List<TripLeg> legs  = new ArrayList<>();
            double totalDistance = 0.0;
            double totalDuration = 0.0;

            if (tripsJson != null && tripsJson.length() > 0) {
                // Typically one trip object; iterate all for completeness
                for (int t = 0; t < tripsJson.length(); t++) {
                    JSONObject trip = tripsJson.getJSONObject(t);
                    totalDistance += trip.optDouble("distance", 0.0);
                    totalDuration += trip.optDouble("duration", 0.0);

                    JSONArray legsJson = trip.optJSONArray("legs");
                    if (legsJson == null) continue;

                    for (int l = 0; l < legsJson.length(); l++) {
                        JSONObject leg       = legsJson.getJSONObject(l);
                        double legDistance   = leg.optDouble("distance", 0.0);
                        double legDuration   = leg.optDouble("duration", 0.0);

                        // Geometry is on the trip level (overview=full), not per-leg
                        // We'll attach the full geometry to the first leg only
                        List<double[]> geomPoints = new ArrayList<>();
                        if (l == 0) {
                            JSONObject geometry = trip.optJSONObject("geometry");
                            if (geometry != null) {
                                JSONArray coords = geometry.optJSONArray("coordinates");
                                if (coords != null) {
                                    for (int c = 0; c < coords.length(); c++) {
                                        JSONArray pt = coords.getJSONArray(c);
                                        // GeoJSON: [lon, lat]
                                        geomPoints.add(new double[]{
                                                pt.getDouble(0),
                                                pt.getDouble(1)
                                        });
                                    }
                                }
                            }
                        }

                        legs.add(new TripLeg(legDistance, legDuration, geomPoints));
                    }
                }
            }

            LOGGER.info(String.format(
                    "TripClient: trip planned — %d waypoints, %d legs, %.2f km, %.0f s",
                    waypoints.size(), legs.size(),
                    totalDistance / 1000.0, totalDuration));

            return new TripResult(waypoints, legs, totalDistance, totalDuration);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "TripClient: failed to parse /trip/v1 response", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private HttpResponse<String> executeWithRetry(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response;
                }
                LOGGER.log(Level.WARNING, String.format(
                        "TripClient attempt %d/%d status %d",
                        attempt, maxRetries, response.statusCode()));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format(
                        "TripClient attempt %d/%d IO: %s",
                        attempt, maxRetries, e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Position> filterValid(List<Position> positions) {
        List<Position> result = new ArrayList<>();
        for (Position p : positions) {
            if (isValidCoordinate(p.getLatitude(), p.getLongitude())) {
                result.add(p);
            }
        }
        return result;
    }

    /** Saturn Position → OSRM coordinate string: lon,lat;lon,lat;... */
    private String buildCoordinatesString(List<Position> positions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(String.format("%.6f,%.6f",
                    positions.get(i).getLongitude(),
                    positions.get(i).getLatitude()));
        }
        return sb.toString();
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT
            && lon >= MIN_LON && lon <= MAX_LON;
    }
}
