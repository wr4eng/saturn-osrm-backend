// src/main/java/org/saturn/routing/OsrmClient.java

// OsrmClient.java v2 2026 WrA (wra.eng@gmail.com)
// add routing filter

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OSRM routing client for Saturn GPS tracking server.
 * Handles coordinate conversion, validation, retries, caching, and graceful fallback.
 */
public class OsrmClient {

    // LOGGER is declared once as a static field at the class level
    private static final Logger LOGGER = Logger.getLogger(OsrmClient.class.getName());

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;

    private final String baseUrl;
    private final String profile;
    private final boolean snapToRoad;
    private final int maxRetries;
    private final Duration requestTimeout;
    private final boolean enableCache;
    private final long cacheTtl;
    private final HttpClient httpClient;
    private final Map<String, CacheEntry> routeCache;

    // GPS drift filter thresholds (0 = disabled)
    private final int filterMaxSpeedKmh;    // drop if implied speed > threshold
    private final int filterMaxAccuracyM;   // drop if GPS accuracy > threshold
    private final int filterMinDistanceM;   // skip if distance to prev < threshold

    public OsrmClient(Config config) {
        this.baseUrl        = config.getString(Keys.ROUTING_URL).replaceAll("/+$", "");
        this.profile        = config.getString(Keys.ROUTING_PROFILE);
        this.snapToRoad     = config.getBoolean(Keys.ROUTING_SNAP);
        this.maxRetries     = config.getInteger(Keys.ROUTING_RETRY);
        this.requestTimeout = Duration.ofSeconds(config.getLong(Keys.ROUTING_TIMEOUT));
        this.enableCache    = config.getBoolean(Keys.ROUTING_CACHE);
        this.cacheTtl       = config.getLong(Keys.ROUTING_CACHE_TTL);

        this.filterMaxSpeedKmh   = config.getInteger(Keys.ROUTING_FILTER_MAX_SPEED);
        this.filterMaxAccuracyM  = config.getInteger(Keys.ROUTING_FILTER_MAX_ACCURACY);
        this.filterMinDistanceM  = config.getInteger(Keys.ROUTING_FILTER_MIN_DISTANCE);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.routeCache = enableCache ? new ConcurrentHashMap<>() : null;

        LOGGER.info(String.format(
                "OSRM Client initialized: url=%s, profile=%s, cache=%s, retries=%d, timeout=%ds",
                baseUrl, profile, enableCache, maxRetries, requestTimeout.getSeconds()));
    }

    /**
     * Calculate snapped route using OSRM backend.
     * Falls back to original positions on any failure.
     */
    public List<Position> calculateRoute(List<Position> positions) {
        if (positions == null || positions.size() < 2) {
            return positions;
        }

        // 1. Filter: invalid coordinates + GPS drift
        List<Position> validPositions = applyDriftFilter(positions);

        if (validPositions.size() < 2) {
            LOGGER.warning("Not enough valid positions for routing, returning original list");
            return positions;
        }

        // 2. Cache Check
        if (routeCache != null) {
            String cacheKey = generateCacheKey(validPositions);
            List<Position> cached = getCachedRoute(cacheKey);
            if (cached != null) {
                LOGGER.fine("Route cache hit for " + validPositions.size() + " positions");
                return cached;
            }
        }

        // 3. Build OSRM request URL (Saturn lat,lon -> OSRM lon,lat)
        String coordinates = buildCoordinatesString(validPositions);
        String url = String.format("%s/route/v1/%s/%s", baseUrl, profile, coordinates);
        url += "?overview=full&geometries=geojson&steps=false&alternatives=false";
        if (!snapToRoad) {
            url += "&continue_straight=false";
        }

        LOGGER.fine("OSRM request URL: " + url);

        // 4. Execute with retry
        HttpResponse<String> response = executeWithRetry(url);
        if (response == null || response.statusCode() != 200) {
            LOGGER.warning("OSRM request failed after retries, falling back to original positions");
            return positions;
        }

        // 5. Parse response
        List<Position> snappedPositions = parseOsrmResponse(response.body(), validPositions);
        if (snappedPositions.isEmpty()) {
            return positions;
        }

        // 6. Cache result
        if (routeCache != null) {
            String cacheKey = generateCacheKey(validPositions);
            routeCache.put(cacheKey, new CacheEntry(snappedPositions, System.currentTimeMillis()));
        }

        LOGGER.fine("OSRM routing completed: " + snappedPositions.size() + " points");
        return snappedPositions;
    }

    /**
     * Execute HTTP request with configurable retries and exponential backoff.
     */
    private HttpResponse<String> executeWithRetry(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response;
                }
                LOGGER.log(Level.WARNING, String.format(
                        "OSRM attempt %d/%d failed with status %d",
                        attempt, maxRetries, response.statusCode()));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format(
                        "OSRM attempt %d/%d IO error: %s",
                        attempt, maxRetries, e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("OSRM request interrupted");
                return null;
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000L * attempt); // Backoff: 1s, 2s, 3s...
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Parse OSRM JSON response and map to Saturn Position objects.
     */
    private List<Position> parseOsrmResponse(String json, List<Position> originalPositions) {
        try {
            JSONObject root = new JSONObject(json);
            String code = root.optString("code", "Unknown");
            if (!"Ok".equals(code)) {
                LOGGER.warning("OSRM response code: " + code
                        + " | Message: " + root.optString("message"));
                return Collections.emptyList();
            }

            JSONArray routes = root.optJSONArray("routes");
            if (routes == null || routes.length() == 0) {
                LOGGER.warning("No routes found in OSRM response");
                return Collections.emptyList();
            }

            JSONObject geometry = routes.getJSONObject(0).optJSONObject("geometry");
            if (geometry == null) {
                LOGGER.warning("No geometry in OSRM route");
                return Collections.emptyList();
            }

            JSONArray coords = geometry.getJSONArray("coordinates");
            List<Position> result = new ArrayList<>();

            for (int i = 0; i < coords.length(); i++) {
                JSONArray coord = coords.getJSONArray(i);
                // OSRM/GeoJSON: [longitude, latitude]
                double lon = coord.getDouble(0);
                double lat = coord.getDouble(1);

                if (!isValidCoordinate(lat, lon)) {
                    LOGGER.warning(String.format(
                            "OSRM returned invalid coordinate: lon=%.6f, lat=%.6f", lon, lat));
                    continue;
                }

                Position original = getClosestOriginal(originalPositions, i);
                if (original != null) {
                    result.add(createSnappedPosition(original, lat, lon));
                }
            }

            return result;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse OSRM response", e);
            return Collections.emptyList();
        }
    }

    private Position getClosestOriginal(List<Position> positions, int index) {
        return index < positions.size()
                ? positions.get(index)
                : positions.get(positions.size() - 1);
    }

    private Position createSnappedPosition(Position original, double lat, double lon) {
        Position snapped = new Position();
        snapped.setDeviceId(original.getDeviceId());
        snapped.setProtocol(original.getProtocol());
        snapped.setServerTime(original.getServerTime());
        snapped.setDeviceTime(original.getDeviceTime());
        snapped.setFixTime(original.getFixTime());
        snapped.setLatitude(lat);
        snapped.setLongitude(lon);
        snapped.setValid(original.getValid());
        snapped.setAltitude(original.getAltitude());
        snapped.setSpeed(original.getSpeed());
        snapped.setCourse(original.getCourse());

        if (original.getAttributes() != null) {
            snapped.getAttributes().putAll(original.getAttributes());
        }

        return snapped;
    }

    /**
      * GPS drift filter pipeline — three stages:
     * 1. Drop invalid coordinates (lat/lon out of range)
     * 2. Drop points with an implied speed > filterMaxSpeedKmh between two consecutive positions
     * 3. Drop points with an accuracy > filterMaxAccuracyM (if the device sends this attribute)
     * 4. Skip points that are < filterMinDistanceM from the previous point (stationary noise)
     */

    private List<Position> applyDriftFilter(List<Position> positions) {
        List<Position> result = new ArrayList<>();
        Position prev = null;

        for (Position p : positions) {
            // Stage 1: valid coordinates
            if (!isValidCoordinate(p.getLatitude(), p.getLongitude())) {
                LOGGER.warning(String.format("Drift filter: invalid coord lat=%.6f lon=%.6f",
                        p.getLatitude(), p.getLongitude()));
                continue;
            }

            // Stage 2: GPS accuracy attribute (optional — the device must send the “accuracy” attribute)
            if (filterMaxAccuracyM > 0) {
                Object acc = p.getAttributes() != null ? p.getAttributes().get("accuracy") : null;
                if (acc instanceof Number && ((Number) acc).doubleValue() > filterMaxAccuracyM) {
                    LOGGER.fine(String.format("Drift filter: low accuracy %.1fm > %dm at lat=%.6f lon=%.6f",
                            ((Number) acc).doubleValue(), filterMaxAccuracyM,
                            p.getLatitude(), p.getLongitude()));
                    continue;
                }
            }

            if (prev != null) {
                double distM   = haversineMeters(prev.getLatitude(), prev.getLongitude(),
                                                  p.getLatitude(),    p.getLongitude());
                long   dtMs    = p.getFixTime().getTime() - prev.getFixTime().getTime();

                // Stage 3: speed filter — implied speed between two points
                if (filterMaxSpeedKmh > 0 && dtMs > 0) {
                    double speedKmh = (distM / 1000.0) / (dtMs / 3_600_000.0);
                    if (speedKmh > filterMaxSpeedKmh) {
                        LOGGER.warning(String.format(
                                "Drift filter: speed %.0f km/h > %d km/h — drop lat=%.6f lon=%.6f",
                                speedKmh, filterMaxSpeedKmh, p.getLatitude(), p.getLongitude()));
                        continue;
                    }
                }

                // Stage 4: minimum distance — skip points that are too close (stationary noise)
                if (filterMinDistanceM > 0 && distM < filterMinDistanceM) {
                    LOGGER.fine(String.format(
                            "Drift filter: distance %.1fm < %dm — skip lat=%.6f lon=%.6f",
                            distM, filterMinDistanceM, p.getLatitude(), p.getLongitude()));
                    continue;
                }
            }

            result.add(p);
            prev = p;
        }

        // Always include the final point of the original position so that the route isn't cut off
        if (!positions.isEmpty() && !result.isEmpty()) {
            Position last = positions.get(positions.size() - 1);
            if (result.get(result.size() - 1) != last
                    && isValidCoordinate(last.getLatitude(), last.getLongitude())) {
                result.add(last);
            }
        }

        int dropped = positions.size() - result.size();
        if (dropped > 0) {
            LOGGER.info(String.format("Drift filter: %d/%d positions dropped, %d passed",
                    dropped, positions.size(), result.size()));
        }
        return result;
    }

    /**
     * Haversine distance in meters between two lat/lon points.
     */
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Convert Saturn positions to OSRM coordinate string.
     * Format: lon1,lat1;lon2,lat2;...
     */
    private String buildCoordinatesString(List<Position> positions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < positions.size(); i++) {
            Position p = positions.get(i);
            if (i > 0) {
                sb.append(";");
            }
            // Saturn (lat,lon) -> OSRM (lon,lat)
            sb.append(String.format("%.6f,%.6f", p.getLongitude(), p.getLatitude()));
        }
        return sb.toString();
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
    }

    private String generateCacheKey(List<Position> positions) {
        StringBuilder sb = new StringBuilder();
        sb.append(profile).append(":");
        for (Position p : positions) {
            sb.append(String.format("%.4f,%.4f;", p.getLatitude(), p.getLongitude()));
        }
        if (!positions.isEmpty()) {
            sb.append(positions.get(0).getFixTime().getTime() / 3600000);
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private List<Position> getCachedRoute(String key) {
        CacheEntry entry = routeCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp > cacheTtl) {
            routeCache.remove(key);
            return null;
        }
        return entry.positions;
    }

    private static class CacheEntry {
        final List<Position> positions;
        final long timestamp;

        CacheEntry(List<Position> positions, long timestamp) {
            this.positions = positions;
            this.timestamp = timestamp;
        }
    }
}
