// src/main/java/org/saturn/routing/OsrmTableClient.java

/*
 * OsrmTableClient.java 2026 WrA (wra.eng@gmail.com)
 *
 * OSRM Table client — computes duration/distance matrix via /table/v1.
 *
 * Primary use cases:
 *   1. ETA matrix: all active vehicles (sources) vs all geofence centroids (destinations)
 *   2. Nearest vehicle: find which vehicle has the shortest ETA to a given geofence
 *
 * Input sources      : List<Coordinate> — typically from /api/positions (last pos per device)
 * Input destinations : List<Coordinate> — typically from GeofenceUtils.centroid() per geofence
 * Output             : TableResult — durations[][] + distances[][] matrix
 *
 * Coordinate wrapper: GeofenceUtils.Coordinate (lat/lon).
 * OSRM requires lon,lat order — conversion handled internally.
 */

package org.saturn.routing;

import org.json.JSONArray;
import org.json.JSONObject;
import org.saturn.config.Config;
import org.saturn.config.Keys;
import org.saturn.utils.GeofenceUtils.Coordinate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OsrmTableClient {

    private static final Logger LOGGER = Logger.getLogger(OsrmTableClient.class.getName());

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT =  90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON =  180.0;

    private static final double NO_ROUTE = Double.MAX_VALUE;

    private final String   baseUrl;
    private final String   profile;
    private final Duration requestTimeout;
    private final int      maxRetries;
    private final double   fallbackSpeed; // m/s, 0 = disabled
    private final HttpClient httpClient;

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /**
     * Full result of a /table/v1 call.
     *
     * durations[i][j] : travel time in seconds from source i to destination j
     *                   Double.MAX_VALUE if no route found
     * distances[i][j] : travel distance in meters from source i to destination j
     *                   Double.MAX_VALUE if no route found
     */
    public static class TableResult {
        public final double[][]      durations;
        public final double[][]      distances;
        public final List<Coordinate> sources;
        public final List<Coordinate> destinations;

        public TableResult(double[][] durations, double[][] distances,
                           List<Coordinate> sources, List<Coordinate> destinations) {
            this.durations    = durations;
            this.distances    = distances;
            this.sources      = Collections.unmodifiableList(sources);
            this.destinations = Collections.unmodifiableList(destinations);
        }

        /** Number of source rows. */
        public int sourceCount() {
            return sources.size();
        }

        /** Number of destination columns. */
        public int destinationCount() {
            return destinations.size();
        }
    }

    /**
     * A single entry in the nearest-vehicle result list.
     * Sorted ascending by durationSeconds (nearest first).
     */
    public static class DestinationEta {
        public final int    sourceIndex;
        public final int    destinationIndex;
        public final double durationSeconds;
        public final double distanceMeters;

        public DestinationEta(int sourceIndex, int destinationIndex,
                               double durationSeconds, double distanceMeters) {
            this.sourceIndex      = sourceIndex;
            this.destinationIndex = destinationIndex;
            this.durationSeconds  = durationSeconds;
            this.distanceMeters   = distanceMeters;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public OsrmTableClient(Config config) {
        this.baseUrl        = config.getString(Keys.ROUTING_URL).replaceAll("/+$", "");
        this.profile        = config.getString(Keys.ROUTING_PROFILE);
        this.requestTimeout = Duration.ofSeconds(config.getLong(Keys.ROUTING_TIMEOUT));
        this.maxRetries     = config.getInteger(Keys.ROUTING_RETRY);

        // fallbackSpeed in config is km/h → convert to m/s internally
        double speedKmh = config.getDouble(Keys.ROUTING_TABLE_FALLBACK_SPEED);
        this.fallbackSpeed = speedKmh > 0 ? speedKmh / 3.6 : 0.0;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        LOGGER.info(String.format(
                "OSRM TableClient initialized: url=%s, profile=%s, fallbackSpeed=%.1f km/h",
                baseUrl, profile, speedKmh));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Compute a full duration + distance matrix between sources and destinations.
     *
     * @param sources      list of source Coordinates (e.g. vehicle last positions)
     * @param destinations list of destination Coordinates (e.g. geofence centroids)
     * @return TableResult, or null on failure
     */
    public TableResult computeMatrix(List<Coordinate> sources, List<Coordinate> destinations) {
        if (sources == null || sources.isEmpty()) {
            LOGGER.warning("TableClient: sources list is empty");
            return null;
        }
        if (destinations == null || destinations.isEmpty()) {
            LOGGER.warning("TableClient: destinations list is empty");
            return null;
        }

        List<Coordinate> validSrc  = filterValid(sources);
        List<Coordinate> validDst  = filterValid(destinations);

        if (validSrc.isEmpty() || validDst.isEmpty()) {
            LOGGER.warning("TableClient: no valid coordinates after filtering");
            return null;
        }

        // Combine all coords: sources first, then destinations
        List<Coordinate> allCoords = new ArrayList<>();
        allCoords.addAll(validSrc);
        allCoords.addAll(validDst);

        // Build index ranges
        int srcCount = validSrc.size();
        int dstCount = validDst.size();

        String srcIndices = buildIndexString(0, srcCount - 1);
        String dstIndices = buildIndexString(srcCount, srcCount + dstCount - 1);

        String coordString = buildCoordinatesString(allCoords);
        String url = buildTableUrl(coordString, srcIndices, dstIndices);

        LOGGER.fine("OSRM table URL: " + url);

        HttpResponse<String> response = executeWithRetry(url);
        if (response == null || response.statusCode() != 200) {
            LOGGER.warning("TableClient: HTTP request failed after retries");
            return null;
        }

        return parseTableResponse(response.body(), validSrc, validDst);
    }

    /**
     * Convenience: find the nearest source to each destination, sorted by ETA.
     *
     * Useful for: "which vehicle is closest to the office geofence?"
     *
     * @param sources      list of vehicle Coordinates
     * @param destinations list of geofence centroid Coordinates
     * @return list of DestinationEta sorted ascending by durationSeconds;
     *         empty list on failure
     */
    public List<DestinationEta> findNearest(List<Coordinate> sources,
                                             List<Coordinate> destinations) {
        TableResult result = computeMatrix(sources, destinations);
        if (result == null) {
            return Collections.emptyList();
        }

        List<DestinationEta> etaList = new ArrayList<>();

        for (int d = 0; d < result.destinationCount(); d++) {
            double bestDuration = NO_ROUTE;
            double bestDistance = NO_ROUTE;
            int    bestSrc      = -1;

            for (int s = 0; s < result.sourceCount(); s++) {
                double dur = result.durations[s][d];
                if (dur < bestDuration) {
                    bestDuration = dur;
                    bestDistance = result.distances[s][d];
                    bestSrc      = s;
                }
            }

            if (bestSrc >= 0 && bestDuration < NO_ROUTE) {
                etaList.add(new DestinationEta(bestSrc, d, bestDuration, bestDistance));
            }
        }

        etaList.sort(Comparator.comparingDouble(e -> e.durationSeconds));
        return etaList;
    }

    // -------------------------------------------------------------------------
    // URL builder
    // -------------------------------------------------------------------------

    private String buildTableUrl(String coordString, String srcIndices, String dstIndices) {
        StringBuilder url = new StringBuilder();
        url.append(baseUrl)
           .append("/table/v1/")
           .append(profile)
           .append("/")
           .append(coordString)
           .append("?annotations=duration,distance")
           .append("&sources=").append(srcIndices)
           .append("&destinations=").append(dstIndices);

        if (fallbackSpeed > 0) {
            url.append("&fallback_speed=").append(String.format("%.2f", fallbackSpeed));
            url.append("&fallback_coordinate=snapped");
        }

        return url.toString();
    }

    // -------------------------------------------------------------------------
    // Response parser
    // -------------------------------------------------------------------------

    private TableResult parseTableResponse(String json,
                                            List<Coordinate> sources,
                                            List<Coordinate> destinations) {
        try {
            JSONObject root = new JSONObject(json);
            String code = root.optString("code", "Unknown");

            if ("NoTable".equals(code)) {
                LOGGER.warning("TableClient: NoTable — no route found between coordinates");
                return null;
            }
            if (!"Ok".equals(code)) {
                LOGGER.warning("TableClient: code=" + code
                        + " msg=" + root.optString("message"));
                return null;
            }

            int srcCount = sources.size();
            int dstCount = destinations.size();

            // --- Durations matrix ---
            double[][] durations = new double[srcCount][dstCount];
            JSONArray  durRows   = root.optJSONArray("durations");

            if (durRows != null) {
                for (int i = 0; i < Math.min(srcCount, durRows.length()); i++) {
                    JSONArray row = durRows.getJSONArray(i);
                    for (int j = 0; j < Math.min(dstCount, row.length()); j++) {
                        durations[i][j] = row.isNull(j) ? NO_ROUTE : row.getDouble(j);
                    }
                }
            }

            // --- Distances matrix ---
            double[][] distances = new double[srcCount][dstCount];
            JSONArray  distRows  = root.optJSONArray("distances");

            if (distRows != null) {
                for (int i = 0; i < Math.min(srcCount, distRows.length()); i++) {
                    JSONArray row = distRows.getJSONArray(i);
                    for (int j = 0; j < Math.min(dstCount, row.length()); j++) {
                        distances[i][j] = row.isNull(j) ? NO_ROUTE : row.getDouble(j);
                    }
                }
            }

            // Count unreachable pairs
            int nullCount = 0;
            for (int i = 0; i < srcCount; i++) {
                for (int j = 0; j < dstCount; j++) {
                    if (durations[i][j] >= NO_ROUTE) nullCount++;
                }
            }
            if (nullCount > 0) {
                LOGGER.warning(String.format(
                        "TableClient: %d/%d pairs unreachable (null route)",
                        nullCount, srcCount * dstCount));
            }

            LOGGER.info(String.format(
                    "TableClient: matrix computed — %dx%d (%d unreachable)",
                    srcCount, dstCount, nullCount));

            return new TableResult(durations, distances, sources, destinations);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "TableClient: failed to parse /table/v1 response", e);
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
                        "TableClient attempt %d/%d status %d",
                        attempt, maxRetries, response.statusCode()));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format(
                        "TableClient attempt %d/%d IO: %s",
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

    /** Build semicolon-separated index range: "0;1;2;...;end" */
    private String buildIndexString(int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) sb.append(";");
            sb.append(i);
        }
        return sb.toString();
    }

    /** Build OSRM coordinate string: lon,lat;lon,lat;... */
    private String buildCoordinatesString(List<Coordinate> coords) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) sb.append(";");
            // OSRM expects lon,lat (GeoJSON order)
            sb.append(String.format("%.6f,%.6f", coords.get(i).lon, coords.get(i).lat));
        }
        return sb.toString();
    }

    private List<Coordinate> filterValid(List<Coordinate> coords) {
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate c : coords) {
            if (isValidCoordinate(c.lat, c.lon)) {
                result.add(c);
            }
        }
        return result;
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT
            && lon >= MIN_LON && lon <= MAX_LON;
    }
}
