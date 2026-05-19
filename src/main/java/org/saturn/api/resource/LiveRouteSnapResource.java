// src/main/java/org/saturn/api/resource/LiveRouteSnapResource.java
// 2026 WrA (wra.eng@gmail.com)
//
// Provides snapped live route segment for a single device.
// Uses a sliding window of recent positions from CacheManager (no DB query),
// filters stationary points (traffic light / parking), then routes through
// OSRM /route/v1. Returns a GeoJSON LineString.
//
// Endpoint : GET /api/route/live-snap?deviceId={id}
// Auth     : existing session (same as all /api/* endpoints)
// Cache    : BYPASSED — always fresh for live tracking
// Fallback : if OSRM unavailable, returns raw straight-line segment

package org.saturn.api.resource;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.saturn.model.Position;
import org.saturn.routing.OsrmClient;
import org.saturn.session.cache.CacheManager;
import org.saturn.api.BaseResource;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

@Path("route")
@Produces(MediaType.APPLICATION_JSON)
public class LiveRouteSnapResource extends BaseResource {

    private static final Logger LOGGER = Logger.getLogger(LiveRouteSnapResource.class.getName());

    /**
     * Maximum number of positions sent to OSRM per snap request.
     * Larger window = smoother route, slightly higher latency.
     * 10 positions covers ~1-3 minutes of typical driving at 5s interval.
     */
    private static final int    SNAP_WINDOW  = 10;

    /**
     * Minimum distance in meters between two consecutive positions
     * to be included in the snap window.
     * Filters out stationary noise (traffic lights, parking stops).
     * Set low enough to not skip legitimate slow movement.
     */
    private static final double MIN_DIST_M   = 5.0;

    @Inject
    private CacheManager cacheManager;

    @Nullable
    @Inject
    private OsrmClient osrmClient;

    // -------------------------------------------------------------------------
    // GET /api/route/live-snap?deviceId=X
    // -------------------------------------------------------------------------

    /**
     * Returns a GeoJSON LineString of the snapped route segment for the device.
     *
     * Takes the last N positions from CacheManager (in-memory, no DB),
     * routes through OSRM /route/v1, and returns the snapped geometry.
     *
     * If OSRM is unavailable or disabled, returns a straight-line fallback
     * from the raw position coordinates.
     *
     * @param deviceId target device ID
     * @return GeoJSON Feature { type, geometry: { type: LineString, coordinates } }
     */
    @Path("live-snap")
    @GET
    public Response getLiveSnap(@QueryParam("deviceId") long deviceId) {
        if (deviceId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"message\":\"deviceId is required\"}")
                    .build();
        }

        // 1. Get position history from CacheManager (realtime, no DB)
        Deque<Position> positionDeque = cacheManager.getPositions(deviceId);
        List<Position> positions = new ArrayList<>(positionDeque);

        if (positions.size() < 2) {
            // Not enough positions yet — return empty geometry
            return Response.ok(emptyLineString()).build();
        }

        // 2. Build snap window: sliding window of last N positions,
        //    filtered to remove stationary duplicates (traffic lights, stops)
        List<Position> segment = buildSnapWindow(positions);

        // 3. Snap via OSRM — bypass cache for live data
        if (osrmClient != null) {
            try {
                List<Position> snapped = osrmClient.calculateRoute(segment);
                if (snapped != null && snapped.size() >= 2) {
                    return Response.ok(buildGeoJson(snapped)).build();
                }
            } catch (Exception e) {
                LOGGER.warning("LiveRouteSnap: OSRM error for device "
                        + deviceId + " — " + e.getMessage() + " — fallback to raw");
            }
        }

        // 4. Fallback — straight line from raw segment
        LOGGER.fine("LiveRouteSnap: fallback straight-line for device " + deviceId);
        return Response.ok(buildGeoJson(segment)).build();
    }

    // -------------------------------------------------------------------------
    // Snap window builder
    // -------------------------------------------------------------------------

    /**
     * Build a deduplicated snap window from the full position history.
     *
     * Steps:
     * 1. Take the last SNAP_WINDOW positions (sliding window)
     * 2. Filter consecutive positions that are < MIN_DIST_M apart
     *    — eliminates stationary noise from traffic lights and parking stops
     * 3. Always keep the very last position so the route tip stays current
     * 4. Fall back to the raw window if filtering leaves < 2 positions
     *
     * @param positions full position history from CacheManager (oldest first)
     * @return filtered segment ready for OSRM /route/v1
     */
    private List<Position> buildSnapWindow(List<Position> positions) {
        // Step 1: sliding window — last N positions
        List<Position> window = new ArrayList<>(positions.subList(
                Math.max(0, positions.size() - SNAP_WINDOW),
                positions.size()));

        if (window.size() < 2) {
            return window;
        }

        // Step 2: filter stationary points
        List<Position> filtered = new ArrayList<>();
        Position prev = null;

        for (Position p : window) {
            if (!isValidCoordinate(p.getLatitude(), p.getLongitude())) {
                continue;
            }
            if (prev == null) {
                filtered.add(p);
                prev = p;
                continue;
            }
            double dist = haversineMeters(
                    prev.getLatitude(), prev.getLongitude(),
                    p.getLatitude(),    p.getLongitude());
            if (dist >= MIN_DIST_M) {
                filtered.add(p);
                prev = p;
            } else {
                LOGGER.fine(String.format(
                        "LiveRouteSnap: skip stationary point dist=%.1fm device=%s",
                        dist, p.getDeviceId()));
            }
        }

        // Step 3: always include the very last position (current tip)
        Position last = window.get(window.size() - 1);
        if (!filtered.isEmpty() && filtered.get(filtered.size() - 1) != last
                && isValidCoordinate(last.getLatitude(), last.getLongitude())) {
            filtered.add(last);
        }

        // Step 4: fallback if filtering was too aggressive
        if (filtered.size() < 2) {
            LOGGER.fine("LiveRouteSnap: filter left < 2 positions — using raw window");
            return window;
        }

        LOGGER.fine(String.format(
                "LiveRouteSnap: window=%d → filtered=%d positions",
                window.size(), filtered.size()));
        return filtered;
    }

    /**
     * Haversine distance in meters between two lat/lon points.
     */
    private static double haversineMeters(double lat1, double lon1,
                                           double lat2, double lon2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
            && !(lat == 0 && lon == 0);
    }

    // -------------------------------------------------------------------------
    // GeoJSON builders
    // -------------------------------------------------------------------------

    /**
     * Build GeoJSON Feature wrapping a LineString from Position list.
     * Coordinate order: [longitude, latitude] per GeoJSON spec.
     */
    private String buildGeoJson(List<Position> positions) {
        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) coords.append(',');
            coords.append(String.format("[%.6f,%.6f]",
                    positions.get(i).getLongitude(),
                    positions.get(i).getLatitude()));
        }
        return String.format(
                "{\"type\":\"Feature\","
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[%s]},"
                + "\"properties\":{}}",
                coords);
    }

    /** Empty GeoJSON LineString — returned when < 2 positions available. */
    private String emptyLineString() {
        return "{\"type\":\"Feature\","
             + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[]},"
             + "\"properties\":{}}";
    }
}
