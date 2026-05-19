// src/main/java/org/saturn/utils/GeofenceUtils.java

/*
 * GeofenceUtils.java 2026 WrA (wra.eng@gmail.com)
 *
 * Utility class for parsing Backend geofence WKT geometry.
 *
 * Backend stores geofence areas as WKT POLYGON only.
 * Coordinate order in Backend WKT: LAT LON (not the OGC standard LON LAT).
 *
 * Example input:
 *   POLYGON ((-6.902647 107.623395, -6.902656 107.623307, ...))
 *
 * All methods are stateless and null-safe.
 */

package org.saturn.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeofenceUtils {

    private static final Logger LOGGER = Logger.getLogger(GeofenceUtils.class.getName());

    // Matches: POLYGON ((... )) or POLYGON((...))
    private static final Pattern POLYGON_PATTERN =
            Pattern.compile("POLYGON\\s*\\(\\((.+?)\\)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Each coordinate pair: "-6.902647 107.623395"
    private static final Pattern COORD_PAIR =
            Pattern.compile("([+-]?\\d+\\.?\\d*)\\s+([+-]?\\d+\\.?\\d*)");

    private GeofenceUtils() {
        // utility class — no instantiation
    }

    /**
     * Simple container for a lat/lon coordinate.
     */
    public static class Coordinate {
        public final double lat;
        public final double lon;

        public Coordinate(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        /** OSRM format: lon,lat */
        public String toOsrmString() {
            return String.format("%.6f,%.6f", lon, lat);
        }

        @Override
        public String toString() {
            return String.format("Coordinate(lat=%.6f, lon=%.6f)", lat, lon);
        }
    }

    /**
     * Check whether a WKT string is a POLYGON.
     *
     * @param wkt area string from Backend geofence
     * @return true if the string matches POLYGON syntax
     */
    public static boolean isPolygon(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            return false;
        }
        return POLYGON_PATTERN.matcher(wkt.trim()).find();
    }

    /**
     * Parse all vertices from a Backend WKT POLYGON string.
     *
     * Backend coordinate order: LAT LON
     * The closing point (same as the first) is automatically excluded.
     *
     * @param wkt area string from Backend geofence
     * @return list of Coordinate; empty list if parse fails
     */
    public static List<Coordinate> parsePolygon(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            return Collections.emptyList();
        }

        Matcher polyMatcher = POLYGON_PATTERN.matcher(wkt.trim());
        if (!polyMatcher.find()) {
            LOGGER.warning("GeofenceUtils: not a valid POLYGON WKT: " + wkt);
            return Collections.emptyList();
        }

        String coordBlock = polyMatcher.group(1);
        List<Coordinate> points = new ArrayList<>();
        Matcher coordMatcher = COORD_PAIR.matcher(coordBlock);

        while (coordMatcher.find()) {
            try {
                double lat = Double.parseDouble(coordMatcher.group(1));
                double lon = Double.parseDouble(coordMatcher.group(2));
                points.add(new Coordinate(lat, lon));
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "GeofenceUtils: failed to parse coordinate pair", e);
            }
        }

        if (points.isEmpty()) {
            LOGGER.warning("GeofenceUtils: no coordinate pairs found in POLYGON");
            return Collections.emptyList();
        }

        // Remove closing point if it duplicates the first point
        if (points.size() > 1) {
            Coordinate first = points.get(0);
            Coordinate last  = points.get(points.size() - 1);
            if (Double.compare(first.lat, last.lat) == 0
                    && Double.compare(first.lon, last.lon) == 0) {
                points.remove(points.size() - 1);
            }
        }

        LOGGER.fine(String.format("GeofenceUtils: parsed %d vertices from POLYGON", points.size()));
        return Collections.unmodifiableList(points);
    }

    /**
     * Calculate the arithmetic centroid (average of all vertices) of a Backend POLYGON.
     *
     * Suitable for convex or roughly circular geofences (e.g., zone around an office).
     * For highly concave polygons, the centroid may fall outside the polygon boundary —
     * acceptable for OSRM routing purposes.
     *
     * @param wkt area string from Backend geofence
     * @return centroid Coordinate, or null if the WKT cannot be parsed
     */
    public static Coordinate centroid(String wkt) {
        List<Coordinate> points = parsePolygon(wkt);

        if (points.isEmpty()) {
            LOGGER.warning("GeofenceUtils: centroid() called on unparseable WKT");
            return null;
        }

        double sumLat = 0.0;
        double sumLon = 0.0;

        for (Coordinate p : points) {
            sumLat += p.lat;
            sumLon += p.lon;
        }

        Coordinate result = new Coordinate(sumLat / points.size(), sumLon / points.size());
        LOGGER.fine("GeofenceUtils: centroid = " + result);
        return result;
    }

    /**
     * Convenience: centroid directly as an OSRM coordinate string "lon,lat".
     * Returns null if the WKT cannot be parsed.
     *
     * @param wkt area string from Backend geofence
     * @return OSRM-formatted coordinate string, or null
     */
    public static String centroidOsrm(String wkt) {
        Coordinate c = centroid(wkt);
        return c != null ? c.toOsrmString() : null;
    }
}
