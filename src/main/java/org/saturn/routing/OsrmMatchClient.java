// src/main/java/org/saturn/routing/OsrmMatchClient.java

/*
 * OsrmMatchClient.java 2026 WrA (wra.eng@gmail.com)
 *
 * OSRM Map Matching client uses /match/v1 (Hidden Markov Model).
 * More accurate than /route/v1 for GPS traces with noise/drift:
 *   - HMM automatically drops outliers (null tracepoints)
 *   - confidence score available per matching segment
 *   - gaps=split separates disconnected segments
 *
 * Used as primary; falls back to OsrmClient (/route/v1) if:
 *   - NoMatch (trace is too sparse/noisy)
 *   - confidence < threshold
 *   - all matchings are dropped
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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OsrmMatchClient {

    private static final Logger LOGGER = Logger.getLogger(OsrmMatchClient.class.getName());

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;

    private final String baseUrl;
    private final String profile;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final double minConfidence;

    // Fallback client if the match fails
    private final OsrmClient fallbackClient;

    public OsrmMatchClient(Config config, OsrmClient fallbackClient) {
        this.baseUrl        = config.getString(Keys.ROUTING_URL).replaceAll("/+$", "");
        this.profile        = config.getString(Keys.ROUTING_PROFILE);
        this.requestTimeout = Duration.ofSeconds(config.getLong(Keys.ROUTING_TIMEOUT));
        this.maxRetries     = config.getInteger(Keys.ROUTING_RETRY);
        this.minConfidence  = config.getDouble(Keys.ROUTING_MATCH_MIN_CONFIDENCE);
        this.fallbackClient = fallbackClient;

        LOGGER.info(String.format(
                "OSRM MatchClient initialized: url=%s, profile=%s, minConfidence=%.2f",
                baseUrl, profile, minConfidence));
    }

    /**
     * Map-match a GPS trace using OSRM /match/v1.
     * Automatically fallback to OsrmClient (/route/v1) if the match fails or
     * the confidence is below the threshold.
     *
     * @param positions input GPS positions (must have a valid fixTime)
     * @return snapped positions, or the /route/v1 fallback result, or the original positions if all else fails
     */
    public List<Position> matchRoute(List<Position> positions) {
        if (positions == null || positions.size() < 2) {
            return positions;
        }

        // Coordinate validation
        List<Position> valid = filterValid(positions);
        if (valid.size() < 2) {
            LOGGER.warning("MatchClient: not enough valid positions, using fallback");
            return fallback(positions);
        }

        // Build URL match/v1
        String coords = buildCoordinatesString(valid);
        String timestamps = buildTimestampsString(valid);
        String url = String.format("%s/match/v1/%s/%s", baseUrl, profile, coords)
                + "?overview=full&geometries=geojson&tidy=true&gaps=split"
                + (timestamps != null ? "&timestamps=" + timestamps : "");

        LOGGER.fine("OSRM match URL: " + url);

        // Retry Execute
        HttpResponse<String> response = executeWithRetry(url);
        if (response == null || response.statusCode() != 200) {
            LOGGER.warning("MatchClient: HTTP failed, using fallback");
            return fallback(positions);
        }

        // Parse response
        return parseMatchResponse(response.body(), valid, positions);
    }

    /**
     * Parse the /match/v1 response.
     * Combine all matching segments that exceed the confidence threshold.
     * Segments with low confidence are skipped and replaced with fallback positions.
     */    
    private List<Position> parseMatchResponse(
            String json, List<Position> validPositions, List<Position> originalPositions) {
        try {
            JSONObject root = new JSONObject(json);
            String code = root.optString("code", "Unknown");

            if ("NoMatch".equals(code)) {
                LOGGER.info("MatchClient: NoMatch — fallback to route/v1");
                return fallback(originalPositions);
            }

            if (!"Ok".equals(code)) {
                LOGGER.warning("MatchClient: code=" + code
                        + " msg=" + root.optString("message") + " — fallback");
                return fallback(originalPositions);
            }

            JSONArray matchings   = root.optJSONArray("matchings");
            JSONArray tracepoints = root.optJSONArray("tracepoints");

            if (matchings == null || matchings.length() == 0) {
                LOGGER.warning("MatchClient: no matchings in response — fallback");
                return fallback(originalPositions);
            }

            // Log dropped tracepoints
            int nullCount = 0;
            List<Integer> droppedIdx = new ArrayList<>();
            if (tracepoints != null) {
                for (int i = 0; i < tracepoints.length(); i++) {
                    if (tracepoints.isNull(i)) {
                        nullCount++;
                        droppedIdx.add(i);
                    }
                }
            }
            if (nullCount > 0) {
                int tracepointCount = tracepoints != null ? tracepoints.length() : 0;
                LOGGER.info(String.format(
                        "MatchClient: %d/%d tracepoints dropped by HMM: %s",
                        nullCount, tracepointCount, droppedIdx));
            }

            // Collect the positions of all matching segments
            List<Position> result = new ArrayList<>();
            int acceptedSegments = 0;

            for (int m = 0; m < matchings.length(); m++) {
                JSONObject matching = matchings.getJSONObject(m);
                double confidence = matching.optDouble("confidence", 0.0);

                if (confidence < minConfidence) {
                    LOGGER.info(String.format(
                            "MatchClient: segment [%d] confidence=%.4f < %.2f — skip segment",
                            m, confidence, minConfidence));
                    continue;
                }

                JSONObject geometry = matching.optJSONObject("geometry");
                if (geometry == null) continue;

                JSONArray coords = geometry.getJSONArray("coordinates");
                LOGGER.fine(String.format(
                        "MatchClient: segment [%d] confidence=%.4f pts=%d dist=%.2fkm",
                        m, confidence, coords.length(),
                        matching.optDouble("distance", 0) / 1000.0));

                // Map geometry coordinates to Position objects
                List<Position> segmentPositions = coordsToPositions(
                        coords, validPositions, result.size());
                result.addAll(segmentPositions);
                acceptedSegments++;
            }

            if (result.isEmpty()) {
                LOGGER.warning("MatchClient: all segments below confidence threshold — fallback");
                return fallback(originalPositions);
            }

            LOGGER.info(String.format(
                    "MatchClient: match complete — %d segments accepted, %d positions, %d dropped",
                    acceptedSegments, result.size(), nullCount));
            return result;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MatchClient: parse error — fallback", e);
            return fallback(originalPositions);
        }
    }

    /**
     * Map GeoJSON coordinates to Saturn Position objects.
     * Reuse metadata from the original positions using an index offset.
     */
    private List<Position> coordsToPositions(
            JSONArray coords, List<Position> originals, int offset) {
        List<Position> result = new ArrayList<>();
        for (int i = 0; i < coords.length(); i++) {
            JSONArray coord = coords.getJSONArray(i);
            double lon = coord.getDouble(0);
            double lat = coord.getDouble(1);

            if (!isValidCoordinate(lat, lon)) continue;

            // Select the nearest original based on the index
            int origIdx = Math.min(offset + i, originals.size() - 1);
            Position orig = originals.get(origIdx);

            Position snapped = new Position();
            snapped.setDeviceId(orig.getDeviceId());
            snapped.setProtocol(orig.getProtocol());
            snapped.setServerTime(orig.getServerTime());
            snapped.setDeviceTime(orig.getDeviceTime());
            snapped.setFixTime(orig.getFixTime());
            snapped.setLatitude(lat);
            snapped.setLongitude(lon);
            snapped.setValid(orig.getValid());
            snapped.setAltitude(orig.getAltitude());
            snapped.setSpeed(orig.getSpeed());
            snapped.setCourse(orig.getCourse());
            if (orig.getAttributes() != null) {
                snapped.getAttributes().putAll(orig.getAttributes());
            }
            result.add(snapped);
        }
        return result;
    }

    private List<Position> fallback(List<Position> positions) {
        if (fallbackClient != null) {
            LOGGER.info("MatchClient: executing fallback to OsrmClient (route/v1)");
            return fallbackClient.calculateRoute(positions);
        }
        LOGGER.warning("MatchClient: no fallback client — returning original positions");
        return positions;
    }

    private List<Position> filterValid(List<Position> positions) {
        List<Position> result = new ArrayList<>();
        for (Position p : positions) {
            if (isValidCoordinate(p.getLatitude(), p.getLongitude())) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Build OSRM coordinate string: lon1,lat1;lon2,lat2;...
     * OSRM /match/v1 uses the GeoJSON format (lon,lat), the same as /route/v1
     */    
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

     /**
     * Construct a string of timestamps from fixTime.
     * OSRM requires timestamps to be monotonically increasing (ASC).
     * Return null if fixTime is unavailable or not monotonically increasing.
     */
    private String buildTimestampsString(List<Position> positions) {
        if (positions.isEmpty() || positions.get(0).getFixTime() == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        long prevTs = Long.MIN_VALUE;

        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).getFixTime() == null) return null;

            long ts = positions.get(i).getFixTime().getTime() / 1000; // ms → seconds
            if (ts <= prevTs) {
                // Timestamps are not monotonic — skip timestamps, leave OSRM without time
                LOGGER.warning(String.format(
                        "MatchClient: non-monotonic timestamp at idx=%d (%d <= %d) — skip timestamps",
                        i, ts, prevTs));
                return null;
            }
            if (i > 0) sb.append(";");
            sb.append(ts);
            prevTs = ts;
        }
        return sb.toString();
    }

    private HttpResponse<String> executeWithRetry(String url) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response;
                LOGGER.log(Level.WARNING, String.format(
                        "MatchClient attempt %d/%d status %d",
                        attempt, maxRetries, response.statusCode()));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format(
                        "MatchClient attempt %d/%d IO: %s", attempt, maxRetries, e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (attempt < maxRetries) {
                try { Thread.sleep(1000L * attempt); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
        }
        return null;
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
    }
}
